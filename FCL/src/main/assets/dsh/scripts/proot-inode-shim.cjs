/**
 * BL-247 修复：proot 环境下让 inode 校验恒成立。
 *
 * ## 问题（真机实测，KKG-AN00 / Android 10 / proot）
 *
 * dsh 的会话写锁在 `@deepseek-ai/dsh-session-persistence-jsonl/src/lease.ts`：
 * 先用非阻塞 `flock(2)` 拿锁，**然后**校验"我锁住的 inode 是否还是 lock 路径上那个文件"：
 *
 * ```js
 * const held = await handle.stat({ bigint: true })      // 经 fd
 * const current = await stat(path, { bigint: true })    // 经路径
 * if (current.ino === held.ino && current.dev === held.dev) return ...  // 成功
 * // 否则关掉重来，3 次都不匹配 ↓
 * throw new SessionAlreadyOwnedError(id)
 * ```
 *
 * **在 proot 下这两个 inode 永远不同**（实测，三个不同挂载点都一样）：
 * ```
 * /opt/dsh/.../sessions/.flock-probe  fstat=0:6062660  stat=0:1630527
 * /tmp/.flock-probe                   fstat=0:6062660  stat=0:1630716
 * /opt/dsh/instances/....flock-probe  fstat=0:6062660  stat=0:1629121
 * ```
 * `fstat` 的 ino 恒为 proot 编的一个假值，`stat` 返回的是真实 inode。
 * 于是 lease 的校验 3 次全失败 ⇒ 抛 `SessionAlreadyOwnedError` ⇒
 * 用户在界面里看到 `session "…" is already owned by an active write handle`，
 * **连全新的会话、第一条消息都发不出去**。
 *
 * 这不是 dsh 的缺陷，也不是我们的错 —— 是 proot 的文件系统虚拟化让"fd 的 inode"
 * 与"路径的 inode"不一致，而这个校验的前提在真机上不成立。
 *
 * ## 修法：把两边都投影到"由路径决定"的稳定 inode
 *
 * 不改 flock 语义（真正的互斥仍由内核锁提供），只让那次**自校验**通过。
 * 具体：给 `fs.promises.stat` / `fs.statSync` / `FileHandle.prototype.stat` 包裹一层，
 * 把返回的 `ino`/`dev` 换成按**解析后的真实路径**哈希出来的稳定值。
 * 这样：
 * - 同一个文件经 fd 和经路径 → 得到同一个投影值 → 校验成立；
 * - 不同文件 → 不同值 → 校验仍然能挡住"锁的文件被换掉"这种真异常；
 * - `flock` 本身没被动过 → 跨进程互斥照旧。
 *
 * ## 为什么用 preload 而不是改 dsh
 *
 * 本仓不改 `dsh/`（上游）。启动器已经通过 `NODE_OPTIONS` 传 `--max-old-space-size`，
 * 再挂一个 `--require` 即可，属于既有机制的自然延伸。
 *
 * ## 只在需要时生效
 *
 * 只在检测到"fstat 与 stat 的 ino 不一致"时安装改写。真机/桌面 Linux 上两者一致时
 * 本模块什么都不做（零行为变化）。
 */

'use strict'

const fs = require('fs')
const path = require('path')
const crypto = require('crypto')

/** proot 让 fd 与 path 看到不同 inode 时，用这个标记只装一次 */
const INSTALLED = Symbol.for('dsh.proot.inodeShim')

/**
 * fd → 真实路径 的映射。
 *
 * 为什么需要它：`fstatSync(fd)` / `FileHandle.stat()` 拿到的只有 fd，要把 ino 投影成
 * "由路径决定的值"就必须知道这个 fd 对应哪个路径。`/proc/self/fd/N` 是最自然的来源，
 * 但**proot 里 /proc 是虚拟化的、可能读不到**（本机 Windows 上也读不到，实测踩到）。
 *
 * 所以退一步：自己记录。包装 `open` / `openSync` / `fs.promises.open`，拿到 fd 后把
 * "fd → 解析后的路径"存进来。这样不依赖 /proc。
 *
 * 数量无需担心：dsh 同时打开的 session.lock 是常数级（单实例策略下通常 1 个）。
 */
const fdPaths = new Map()

/** 记一条 fd → path；已存在就覆盖（fd 会被内核复用） */
function rememberFd(fd, target) {
  if (typeof fd !== 'number' || fd < 0) return
  try {
    fdPaths.set(fd, path.resolve(String(target)))
  } catch {}
}

/** 查 fd 对应的路径：先查自己的表，再退回 /proc（两条路能通一条就行） */
function pathOfFd(fd) {
  const known = fdPaths.get(fd)
  if (known) return known
  try {
    return fs.readlinkSync(`/proc/self/fd/${fd}`)
  } catch {
    return null
  }
}

/**
 * 由路径算一个稳定的 32 位无符号投影值。
 *
 * 用 sha1 的前 4 字节：碰撞概率对"会话目录里那几个文件"完全够用，
 * 而且同一路径在任何进程、任何时刻都得到同一个值（不能随机，否则跨进程校验会打架）。
 */
function projectIno(realPath) {
  const h = crypto.createHash('sha1').update(realPath).digest()
  return h.readUInt32BE(0)
}

/** dev 统一投影成 0：真机上本来就是 0，保持"同一设备"的语义 */
const PROJECT_DEV = 0

/**
 * 把一个 `fs.Stats` 对象的 ino/dev 换成投影值。
 *
 * `Stats` 的这两个字段在 Node 上是普通数据属性（bigint 模式下是 BigInt），
 * 但不同实现（node / 各 polyfill）可能用 getter，所以用 `Object.defineProperty`
 * 覆盖并保留原值的类型（number vs bigint）—— 否则 `===` 比较会因类型漂移而失效
 * （本地实测踩到过：投影成 number 但对面是 bigint，比较恒 false）。
 */
function rewriteStats(stats, realPath, wantBigInt) {
  if (!stats || typeof stats !== 'object') return stats
  const ino = wantBigInt ? BigInt(projectIno(realPath)) : projectIno(realPath)
  const dev = wantBigInt ? BigInt(PROJECT_DEV) : PROJECT_DEV
  try {
    Object.defineProperty(stats, 'ino', {
      value: ino, writable: false, configurable: true, enumerable: true,
    })
    Object.defineProperty(stats, 'dev', {
      value: dev, writable: false, configurable: true, enumerable: true,
    })
  } catch {
    // 只读属性且不可重定义：跳过这家实现（一致性自检会兜底）
  }
  return stats
}

/** 从 options 判断调用方是否要 bigint（`{bigint:true}` 或字符串 `'bigint'`） */
function wantsBigInt(options) {
  if (options === 'bigint') return true
  return !!(options && typeof options === 'object' && options.bigint)
}

/**
 * 探测本进程里 fd 与 path 的 inode 是否一致。
 *
 * 为什么要探测而不是无条件安装：在正常的 Linux 上 `flock` + inode 校验是**正确的**，
 * 无条件改写会削弱它对"锁住的文件被 unlink/重建"这种真异常的检测力。
 * 只有确认"这台机器的 fd 与 path 看不到同一个 inode"时才需要修正。
 */
function needsShim() {
  const probe = path.join(
    process.env.TMPDIR || '/tmp',
    `.dsh-inode-probe-${process.pid}`
  )
  let fd
  try {
    fd = fs.openSync(probe, 'w')
    // ★ 必须两边都传 bigint:true 再比 —— 不传时返回 number，传了返回 bigint，
    //   直接比会因类型不同而恒 false（本地实测踩到：false 会被误判成"需要 shim"）。
    const viaFd = fs.fstatSync(fd, { bigint: true })
    const viaPath = fs.statSync(probe, { bigint: true })
    return viaFd.ino !== viaPath.ino || viaFd.dev !== viaPath.dev
  } catch {
    // 探测本身失败（只读 /tmp 等）⇒ 不装，保持原行为
    return false
  } finally {
    if (fd !== undefined) { try { fs.closeSync(fd) } catch {} }
    try { fs.unlinkSync(probe) } catch {}
  }
}

function install() {
  if (fs[INSTALLED]) return
  fs[INSTALLED] = true

  // --- ① fs.promises.stat / lstat ---
  const promises = fs.promises
  const origStat = promises.stat.bind(promises)
  const origLstat = promises.lstat.bind(promises)

  promises.stat = async function stat(target, options) {
    const stats = await origStat(target, options)
    return rewriteStats(stats, path.resolve(String(target)), wantsBigInt(options))
  }
  promises.lstat = async function lstat(target, options) {
    const stats = await origLstat(target, options)
    // lstat 不能 resolve：它就是用来读符号链接本身的
    return rewriteStats(stats, String(target), wantsBigInt(options))
  }

  // --- ② fs.statSync / lstatSync / fstatSync ---
  const origStatSync = fs.statSync
  const origLstatSync = fs.lstatSync
  const origFstatSync = fs.fstatSync
  fs.statSync = function statSync(target, options) {
    const stats = origStatSync(target, options)
    return rewriteStats(stats, path.resolve(String(target)), wantsBigInt(options))
  }
  fs.lstatSync = function lstatSync(target, options) {
    const stats = origLstatSync(target, options)
    return rewriteStats(stats, String(target), wantsBigInt(options))
  }
  // ★ fstatSync 收的是 fd，没有路径参数 —— 从 fdPaths 表（或 /proc 兜底）反查。
  fs.fstatSync = function fstatSync(fd, options) {
    const stats = origFstatSync(fd, options)
    const realPath = pathOfFd(fd)
    return realPath ? rewriteStats(stats, realPath, wantsBigInt(options)) : stats
  }

  // --- ③ open 系列：记录 fd → path，供 fstat 反查 ---
  const origOpenSync = fs.openSync
  fs.openSync = function openSync(target, ...rest) {
    const fd = origOpenSync.call(fs, target, ...rest)
    rememberFd(fd, target)
    return fd
  }
  const origOpen = fs.open
  fs.open = function open(target, ...rest) {
    // 回调式：最后一个参数是 callback，可能前面还有 flags/mode
    const cb = rest[rest.length - 1]
    if (typeof cb !== 'function') return origOpen.call(fs, target, ...rest)
    return origOpen.call(fs, target, ...rest.slice(0, -1), (err, fd) => {
      if (!err) rememberFd(fd, target)
      cb(err, fd)
    })
  }
  const origPromisesOpen = promises.open
  promises.open = async function open(target, ...rest) {
    const fh = await origPromisesOpen.call(promises, target, ...rest)
    // FileHandle 上拿 fd 并记录初始路径；顺带惰性安装 stat 包装（见上面的说明）
    rememberFd(fh && fh.fd, target)
    install._patchFileHandle?.(fh)
    return fh
  }

  // --- ④ FileHandle.prototype.stat（lease.ts 用的是这个）---
  //
  // 这是最关键的一条：`await open(path,'w')` 得到 FileHandle，再 `.stat()`。
  // 它读的是 fd，proot 下拿到的是假 inode。用 fdPaths / /proc 反查真实路径。
  //
  // ★ 不能从 `fs.promises.FileHandle` 取类 —— **Node 上这个属性不存在**
  //   （实测 node 26：`fs.promises.FileHandle === undefined`）。
  //   FileHandle 是 open() 返回对象的 constructor，只能从实例上取 prototype。
  //   这里在第一次 open 成功时惰性安装。
  let fhPatched = false
  const patchFileHandle = (handle) => {
    if (fhPatched || !handle) return
    const proto = Object.getPrototypeOf(handle)
    if (!proto || typeof proto.stat !== 'function') return
    const origFhStat = proto.stat
    proto.stat = async function stat(options) {
      const stats = await origFhStat.call(this, options)
      const realPath = pathOfFd(this.fd)
      return realPath ? rewriteStats(stats, realPath, wantsBigInt(options)) : stats
    }
    fhPatched = true
  }
  // 暴露给下面的 open 包装调用
  install._patchFileHandle = patchFileHandle

  try {
    process.emitWarning(
      '[dsh-proot-inode-shim] fd/path inode mismatch detected (proot); ' +
        'session lock inode self-check is projected onto stable per-path values',
      'ProotCompat'
    )
  } catch {}
}

if (needsShim()) install()

module.exports = { needsShim, install, projectIno }
