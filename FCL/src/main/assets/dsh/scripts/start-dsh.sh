#!/bin/sh
# start-dsh.sh — 在 rootfs 内部启动一个 dsh 实例的 web 服务
# 运行环境：proot 内的 Linux 用户态（glibc/Debian，arm64）
#
# 已在 aarch64 + Debian12 + glibc + patched proot + PROOT_LOADER 环境验证：
#   - proot 内 node / bash / child_process 正常
#   - dsh --version 可跑；web 服务在本机 WebView 下使用
#
# 用法:
#   INSTANCE_DIR=/opt/dsh/instances/default PORT=3080 ./start-dsh.sh
#
# 关键环境变量（都可选，有默认值）:
#   INSTANCE_DIR       实例根目录；若其下有 node_modules 则优先使用（多版本隔离）
#   DSH_PREINSTALL_DIR dsh 预装目录（rootfs 内），默认 /opt/dsh-preinstalled
#   DSH_LAUNCH_TARGET  宿主下发的启动目标（fix12）：instance | preinstalled。
#                      非空时**按它执行**（与宿主 [resolveLaunchTarget] 同结论，不再自行回退）；
#                      缺失/空 = 旧宿主或自检路径，退回下面的三级解析。
#   PORT               监听端口，默认 3080；传 0 表示由系统分配
#   HOST               绑定地址，默认 127.0.0.1（给本机 WebView 用，别绑 0.0.0.0）
#   PROFILE            dsh profile，默认 web
#   READY_TIMEOUT      等待就绪的秒数，默认 120
#   NODE_PID_FILE      写 node 真实 pid 的文件，默认 $INSTANCE_DIR/dsh-node.pid
#                      （宿主侧 DshRuntime/ProcessUtil 按它精确终止 node，见下面"停止语义"）
#
# ## dsh 入口解析顺序（方案 B 对齐）
#   0) fix12 起：宿主若下发 `DSH_LAUNCH_TARGET`（instance / preinstalled），**只按它执行**，
#      不再自行回退 —— 宿主 [resolveLaunchTarget] 与脚本必须给出同一个结论（E3-N-8）。
#      目标指向的 `lib/bin.js` 不存在时直接失败（打 ERROR 行），不回退另一侧。
#   1) 实例自己的 node_modules      → 支持"每实例装不同版本"（自定义性）
#   2) rootfs 内预装的 DSH_PREINSTALL_DIR → 首次可用、离线可用（稳定性优先）
#   3) 用 node 在 INSTANCE_DIR 里 require.resolve（兼容旧布局）
#   1)~3) 仅在 DSH_LAUNCH_TARGET 缺失/未知时生效（旧宿主、自检路径）。
#   解析结果会打印 `[start-dsh] dsh src=...`，便于排查。
#
# ## 设计要点
# 后台起 node → 主动探测就绪（token URL 或端口）→ 打印 `[start-dsh] READY ...` →
# 前台跟随输出，收到 TERM/INT 转发给子进程；超时/早退打印 `FAILED reason=...` 并非 0 退出。
#
# ## 可移植性
# 严格 POSIX sh（启动器用 `/bin/sh <script>` 调用，Debian 上是 dash；dash 不支持 pipefail）。
# 端口探测用 node 的 net 模块（不依赖 bash 的 /dev/tcp，也不需要 curl/nc）。
#
# ## 停止语义（B10 修复说明：为什么必须把 node 的 pid 交给宿主）
# 启动器**只能**直接杀宿主侧的 proot 进程；rootfs 里的 node 是 proot 的**子进程**：
#   · kill(2) 只作用于单个 pid，不会连带子进程；
#   · proot 的 event loop 把除 SIGQUIT/SIGILL/SIGABRT/SIGFPE/SIGSEGV 之外的所有信号都装成
#     SIG_IGN（proot 源码 src/tracee/event.c: "Ignore all other signals, including
#     terminating ones (^C for instance)"），所以 **SIGTERM/INT/HUP 对 proot 完全无效**，
#     只有 SIGQUIT（异常路径）与 SIGKILL（不可捕获）能杀掉它；
#   · SIGKILL 下 proot 来不及做任何清理：它的 `atexit(kill_all_tracees)` 与 `--kill-on-exit`
#     都要求"proot 还活着、还在事件循环里"，于是 node 变孤儿、端口一直被占，
#     下次启动就报 EADDRINUSE。
#   · 本脚本的 `trap ... TERM` 在这条链路上**永远不会被触发**：启动器杀的是 proot 的 pid，
#     而本脚本跑在 proot 的另一个 pid（root tracee）上，根本收不到那个信号。
# 两条互相独立的修复（任一条生效都能带走 node）：
#   1) 本脚本把 node 的**真实 pid** 写进 NODE_PID_FILE（对外暴露成宿主路径
#      <filesDir>/dsh/instances/<id>/dsh-node.pid），启动器按 pid 精确终止；
#   2) 启动器在 SIGKILL 之前先给 proot 发 SIGQUIT —— proot 的 SIGQUIT 处理器会先
#      kill_all_tracees() 再走正常退出路径，等于"最后一刻仍把子进程带走"。
# proot **不**伪造 getpid（proot 源码 extension/fake_id0/sendmsg.c 写着
# "Pid is not changed as we don't fiddle with getpid()"），所以这里的 $! 就是宿主上的真实 pid，
# 启动器拿它 kill 是正确的；反过来，哪天 proot 开始伪造 pid，这条路径必须重做。
set -u

# 预装 Node 目录必须进 PATH：rootfs 内 node/npm 放在 /opt/node22/bin
# （ProotCommand 也会设置 PATH，这里再兜一层，避免手工调试时找不到 node）
PATH="/opt/node22/bin:$PATH"
export PATH

INSTANCE_DIR="${INSTANCE_DIR:-$PWD}"
DSH_PREINSTALL_DIR="${DSH_PREINSTALL_DIR:-/opt/dsh-preinstalled}"
PORT="${PORT:-3080}"
HOST="${HOST:-127.0.0.1}"
READY_TIMEOUT="${READY_TIMEOUT:-120}"

export DSH_HOME="${DSH_HOME:-$INSTANCE_DIR/home}"
mkdir -p "$DSH_HOME"

# --- 凭据（批次 5 起：本脚本不再碰它）-------------------------------------
# API Key 完全交给 dsh 自己：它在 $DSH_HOME/.credentials.yaml 里存，页面里改、页面里生效。
# 启动器也不再注入 DEEPSEEK_API_KEY（注进去会让 dsh 的写入被判为"被环境遮蔽"而直接报错），
# 所以这里既不需要读凭据文件、也无从判断"有没有配 Key"——那是 dsh 自己的状态。
# 需要非交互式喂 Key 的场景（CI / Termux）自己 export 到环境里即可，dsh 会读到。

# 原生模块加载器（node-addon-native-custom-loader）默认会先把 .node
# **硬链接**到 os.tmpdir() 下的缓存目录再 require。
# 在部分文件系统上该硬链接会失败，表现为：
#   EINVAL: invalid argument, readlink '.../native-cache/....node'
#   → Error: No usable native binding found for node-addon-require-builtin-...
# 我们的 .node 本来就在可 dlopen 的目录里，直接关掉这层缓存最稳。
NARB_DISABLE_NATIVE_CACHE="${NARB_DISABLE_NATIVE_CACHE:-1}"
export NARB_DISABLE_NATIVE_CACHE

# --- 定位 dsh 入口 bin.js --------------------------------------------------
DSH_REL="node_modules/@deepseek-ai/dsh/lib/bin.js"
BIN_JS=""
DSH_SRC=""
# --- 宿主下发优先（fix12 / E3-N-8）----------------------------------------
# 宿主 [resolveLaunchTarget] 已经按**同一套判据**（实例 package.json+bin.js 优先、预装兜底）
# 决定了这次该跑哪份包，并通过 argvEnv 下发。脚本若再自己回退一次，就会出现
# "宿主判 NotInstalled 拒绝启动 / 或者宿主判 Instance 而脚本跑 Preinstalled"的错位。
# 所以：下发值有效时**只认它**，目标缺 lib/bin.js 直接失败，不静默回退。
# 变量缺失/为空/未知值 ⇒ 完全退回下面的三级解析（旧宿主 + 自检路径 + 手工调试）。
if [ -n "${DSH_LAUNCH_TARGET:-}" ]; then
  case "$DSH_LAUNCH_TARGET" in
    instance)
      BIN_JS="$INSTANCE_DIR/$DSH_REL"
      DSH_SRC="instance"
      ;;
    preinstalled)
      BIN_JS="$DSH_PREINSTALL_DIR/$DSH_REL"
      DSH_SRC="preinstalled"
      ;;
    *)
      echo "[start-dsh] WARN unknown-launch-target=$DSH_LAUNCH_TARGET, falling back to auto detection"
      ;;
  esac
  if [ -n "$DSH_SRC" ] && [ ! -f "$BIN_JS" ]; then
    # 宿主说"跑这一份"，磁盘上却没有入口：**不回退**。宿主的 NotReady 文案与脚本结论必须一致，
    # 否则用户看到"未安装"、日志却说"已用预装启动"，下一次排查从第一步就错。
    echo "[start-dsh] ERROR launch target=$DSH_LAUNCH_TARGET but lib/bin.js missing"
    echo "[start-dsh] FAILED reason=package-missing"
    echo "[start-dsh]   target: $BIN_JS" >&2
    exit 1
  fi
fi

if [ -z "$BIN_JS" ]; then
  if [ -f "$INSTANCE_DIR/$DSH_REL" ]; then
    BIN_JS="$INSTANCE_DIR/$DSH_REL"
    DSH_SRC="instance"
  elif [ -f "$DSH_PREINSTALL_DIR/$DSH_REL" ]; then
    BIN_JS="$DSH_PREINSTALL_DIR/$DSH_REL"
    DSH_SRC="preinstalled"
  else
    # 兼容旧布局：用 node 自己解析（需要可用的 node）
    RESOLVED="$(cd "$INSTANCE_DIR" 2>/dev/null && node -e "process.stdout.write(require.resolve('@deepseek-ai/dsh/lib/bin.js'))" 2>/dev/null || true)"
    if [ -n "$RESOLVED" ] && [ -f "$RESOLVED" ]; then
      BIN_JS="$RESOLVED"
      DSH_SRC="resolved"
    fi
  fi
fi

if [ -z "$BIN_JS" ]; then
  echo "[start-dsh] FAILED reason=package-missing"
  echo "[start-dsh] ERROR: @deepseek-ai/dsh not found in the instance directory or the preinstalled directory" >&2
  echo "[start-dsh]   instance:     $INSTANCE_DIR/$DSH_REL" >&2
  echo "[start-dsh]   preinstalled: $DSH_PREINSTALL_DIR/$DSH_REL" >&2
  exit 1
fi

if ! command -v node >/dev/null 2>&1; then
  echo "[start-dsh] FAILED reason=node-missing"
  echo "[start-dsh] ERROR: node not found in PATH (expected /opt/node22/bin/node)" >&2
  exit 1
fi

DSH_VER="$(node -e "try{process.stdout.write(require('$BIN_JS/../../package.json').version)}catch(e){}" 2>/dev/null || true)"

# --- 降级可见性（fix12 / C-07）---------------------------------------------
# 只在"实际跑的是预装版、而实例明确要的是**另一个**版本"时打一行机器可读的 DEGRADED。
# 目的：日志要能回答"为什么装的 0.2.1、跑起来却是 0.1.6"，而不是让这条降级只存在于宿主的
# 推导里。DSH_VERSION 缺失（旧宿主/自检）/等于 latest /与预装版本相符 ⇒ 不打扰。
# 预装版本按本脚本既有手法从 package.json 读（node 此处已确认可用），失败只是空串、不致命。
DSH_VERSION="${DSH_VERSION:-}"
if [ "$DSH_SRC" = "preinstalled" ] && [ -n "$DSH_VERSION" ] && [ "$DSH_VERSION" != "latest" ]; then
  PRE_VER="$(node -e "try{process.stdout.write(require('$DSH_PREINSTALL_DIR/node_modules/@deepseek-ai/dsh/package.json').version)}catch(e){}" 2>/dev/null || true)"
  if [ -n "$PRE_VER" ] && [ "$PRE_VER" != "$DSH_VERSION" ]; then
    echo "[start-dsh] DEGRADED reason=preinstalled-fallback expected=$DSH_VERSION actual=$PRE_VER"
  fi
fi

# --- 端口就绪探测（用 node 的 net 模块，不依赖 bash 的 /dev/tcp，也不需要 curl/nc）--------
port_open() {
  node -e 'var n=require("net"),s=n.connect({host:process.argv[1],port:+process.argv[2]},function(){s.end();process.exit(0)});s.on("error",function(){process.exit(1)});s.setTimeout(1500,function(){s.destroy();process.exit(1)})' "$HOST" "$1" 2>/dev/null
}

# --- 端口预检：被占用时不要"直接失败"（B10 的第二半）------------------------
# 实例端口是"上次成功启动后回写进配置"的固定端口（自动端口也会被回写），下次启动直接复用。
# 一旦那个端口还被上次没退干净的 node、或被别的 App 占着，node 起来就会立刻
#   Error: listen EADDRINUSE: address already in use 127.0.0.1:<PORT>
# 整个进程随即退出，启动器只看到一句 "FAILED reason=node-exit code=1"，用户拿不到任何线索。
# 这里在启动前先探一次：被占用就**改用自动端口**（PORT=0，由系统挑一个空闲端口），
# 并打印机器可读的 WARN，让启动器回读真实端口写回配置（原本就有的回写逻辑会自动接管）。
# 代价：用户手工指定的固定端口在这种情况下会被换成一个随机端口（日志/界面显示的是新端口）；
# 换来的是"实例一定能起来"，而不是一句无信息的失败。
if [ "$PORT" != "0" ] && port_open "$PORT"; then
  echo "[start-dsh] WARN port-in-use port=$PORT fallback=auto"
  PORT=0
fi

echo "[start-dsh] node    : $(node -v)"
echo "[start-dsh] dsh bin : $BIN_JS"
[ -n "$DSH_VER" ] && echo "[start-dsh] dsh ver : $DSH_VER"
echo "[start-dsh] dsh src : $DSH_SRC"
echo "[start-dsh] listen  : http://$HOST:$PORT"
echo "[start-dsh] DSH_HOME: $DSH_HOME"
echo "[start-dsh] workdir : $PWD"

# ===== 沙箱能力探测（BL-250）=====
#
# ## 为什么要在这里探
#
# dsh 的 Linux 沙箱链是 `bwrap → landlock`（sandbox-local/src/index.ts:159
# 的 PLATFORM_CHAINS，**没有 android 这一项**）。Android 上 process.platform === 'linux'，
# 所以必然走这条链。两级都不可用 ⇒ 抛 SANDBOX_UNAVAILABLE ⇒
# workspace-write 模式下**所有命令被拒**（fail closed，不是降级）。
#
# 结果是：用户看到的是"每条命令都报沙箱错误"，看起来像 App 坏了。
#
# 本段只**探测并如实打印**，不改任何行为、不做任何决定 —— 决定权在用户（P0 的 UI 层）。
# 输出进实例日志，既让用户能看到，也让我们排障时有据可查。
#
# ★ 只探测一次、缓存在实例目录里：每次启动都跑 unshare/mount 这些是浪费。
#   缓存文件里存"探测时间 + 结论"，P0 的 UI 会读它。
SANDBOX_CACHE="$DSH_HOME/.sandbox-probe"
# 缓存要带上"探针逻辑版本"：改了探针代码就该重探，不然旧结论会一直卡在那儿。
# 改探针逻辑时手动递增这个数字。
PROBE_REV="3"
_should_probe=1
if [ -n "${DSH_FORCE_PROBE:-}" ]; then
  _should_probe=1
elif [ -f "$SANDBOX_CACHE" ] && [ -n "$(find "$SANDBOX_CACHE" -mtime -7 2>/dev/null)" ]; then
  # 缓存有效期内、且是同一版探针写的 ⇒ 跳过
  if grep -q "^probe_rev=$PROBE_REV$" "$SANDBOX_CACHE" 2>/dev/null; then
    _should_probe=0
  fi
fi

if [ "$_should_probe" = "1" ]; then
  echo "[start-dsh] sandbox-probe: start"

  # ① 内核版本
  echo "[start-dsh] sandbox-probe kernel=$(uname -r) arch=$(uname -m)"

  # ② bwrap：有没有装、装了能不能用
  if command -v bwrap >/dev/null 2>&1; then
    echo "[start-dsh] sandbox-probe bwrap=present"
    # bwrap 依赖非特权 userns，先用 unshare 探一下内核有没有编 CONFIG_USER_NS
    if unshare -U -r true 2>/dev/null; then
      echo "[start-dsh] sandbox-probe userns=ok"
    else
      echo "[start-dsh] sandbox-probe userns=DENIED (CONFIG_USER_NS missing or blocked -> bwrap cannot work)"
    fi
  else
    echo "[start-dsh] sandbox-probe bwrap=absent"
  fi

  # ③ landlock：用 dsh 自己那个 launcher 探（判据与 dsh 一致，不另造一套）
  #
  #    为什么用它的 --probe 而不是自己调 landlock_create_ruleset：
  #    那个二进制是 dsh 上游的、和它运行期用的是同一个；用它的探针等于复用同一判据，
  #    不会出现"我们以为能用、dsh 以为不能用"的错位。
  LL=""
  for cand in \
      "$DSH_HOME/../node_modules/@deepseek-ai/node-addon-system-linux-arm64/bin/landlock-run" \
      "/opt/dsh-preinstalled/node_modules/@deepseek-ai/node-addon-system-linux-arm64/bin/landlock-run" \
      "$INSTANCE_DIR/node_modules/@deepseek-ai/node-addon-system-linux-arm64/bin/landlock-run"
  do
    if [ -f "$cand" ] && [ -x "$cand" ]; then LL="$cand"; break; fi
  done
  if [ -z "$LL" ]; then
    # 退一步：在 node_modules 里找（不假设具体布局）
    LL=$(find "$INSTANCE_DIR/node_modules" /opt/dsh-preinstalled/node_modules \
            -name landlock-run -type f 2>/dev/null | head -1)
  fi

  if [ -z "$LL" ]; then
    echo "[start-dsh] sandbox-probe landlock=launcher-not-found"
  else
    echo "[start-dsh] sandbox-probe landlock=launcher-present path=$LL"
    _ll_out=$("$LL" --probe 2>&1)
    _ll_rc=$?
    echo "[start-dsh] sandbox-probe landlock-probe rc=$_ll_rc out=$_ll_out"
    if [ "$_ll_rc" -ne 0 ]; then
      echo "[start-dsh] sandbox-probe landlock=UNUSABLE (kernel $(uname -r); Landlock landed in 5.13, cannot be backported)"
    fi
  fi

  # ④ proot 的封锁面 —— 这些决定了"能不能在 proot 内部再造一个沙箱"
  #    答案是不能，所以记下来免得后人白试。
  echo "[start-dsh] sandbox-probe tracerpid=$(sed -n 's/^TracerPid:[[:space:]]*//p' /proc/self/status 2>/dev/null)"
  echo "[start-dsh] sandbox-probe capeff=$(sed -n 's/^CapEff:[[:space:]]*//p' /proc/self/status 2>/dev/null)"
  echo "[start-dsh] sandbox-probe seccomp=$(sed -n 's/^Seccomp:[[:space:]]*//p' /proc/self/status 2>/dev/null)"
  if unshare -U true 2>/dev/null; then
    echo "[start-dsh] sandbox-probe unshare-u=ok"
  else
    echo "[start-dsh] sandbox-probe unshare-u=DENIED"
  fi

  # ⑤ stat(".") 是否可用（inode shim 的已知问题：AT_FDCWD 走不通）
  #    这条直接影响构建工具，独立于沙箱，一并记下来。
  _dot_stat=$(stat -c '%d:%i' . 2>&1)
  _abs_stat=$(stat -c '%d:%i' "$PWD" 2>&1)
  echo "[start-dsh] sandbox-probe stat-dot=$_dot_stat"
  echo "[start-dsh] sandbox-probe stat-abs=$_abs_stat"
  if [ "$_dot_stat" != "$_abs_stat" ]; then
    echo "[start-dsh] sandbox-probe stat-FDCWD=BROKEN (relative 'stat .' disagrees with absolute)"
  else
    echo "[start-dsh] sandbox-probe stat-FDCWD=consistent"
  fi

  # 写缓存（只写结论摘要，供 UI 读）
  {
    echo "probed_at=$(date -u +%Y-%m-%dT%H:%M:%SZ 2>/dev/null || echo unknown)"
    echo "probe_rev=$PROBE_REV"
    echo "kernel=$(uname -r)"
    echo "bwrap=$(command -v bwrap >/dev/null 2>&1 && echo present || echo absent)"
    echo "landlock_rc=${_ll_rc:-na}"
    echo "dot_stat=${_dot_stat}"
  } > "$SANDBOX_CACHE" 2>/dev/null && echo "[start-dsh] sandbox-probe cached=$SANDBOX_CACHE"

  echo "[start-dsh] sandbox-probe: done"
fi

# ===== 插件页加载慢的测量（BL-251）=====
#
# ## 为什么要量
#
# 真机实测：插件页约 4 分钟、设置→内置插件约 3 分钟才出内容，而同机的
# 通用设置 / Agent 预设 / 自动化任务都是秒开。所以不是网络慢、也不是前端渲染慢，
# 是这两个页面各自拉的那条数据特别重。
#
# 读源码怀疑是 `plugin-manager.listBundles()`：
#   dsh/packages/boot/plugin-manager/src/index.ts:199
#   const names = [...new Set([...selected, ...dependencies, ...Object.keys(installation.dependencies ?? {})])]
# 这个 names 是 bundle + 两边的**全部依赖**（几十到几百个），然后对每个名字做
#   createRequire().resolve.paths() 枚举 + 每条路径 existsSync + 一次 readFileSync
# —— 全部同步、顺序执行。在 proot 下每次 syscall 要穿 ptrace 拦截 + 路径重写。
#
# **但这只是假设。** 本段量四个数，用来判断慢在哪一层：
#   A 纯路径枚举（不碰文件系统）
#   B 加逐条 existsSync（stat 的开销）
#   C 加 readFileSync + JSON.parse（读文件的开销）
#   D readdir 一遍 node_modules（"建索引"方案的基线）
#
# B-A 大 ⇒ 慢在 stat；C-B 大 ⇒ 慢在读文件；D ≪ B ⇒ 建索引有效。
#
# ★ 只在**首次**跑（缓存 7 天）：这几段会在 proot 里 fork 几百次 node 调用，
#   每次启动都跑是浪费。
PERF_CACHE="$DSH_HOME/.plugin-perf-probe"
_perf_should=1
if [ -n "${DSH_FORCE_PROBE:-}" ]; then _perf_should=1
elif [ -f "$PERF_CACHE" ] && [ -n "$(find "$PERF_CACHE" -mtime -7 2>/dev/null)" ]; then
  grep -q "^probe_rev=$PROBE_REV$" "$PERF_CACHE" 2>/dev/null && _perf_should=0
fi

if [ "$_perf_should" = "1" ] && command -v node >/dev/null 2>&1; then
  echo "[start-dsh] perf-probe: start"
  INSTANCE_DIR="$INSTANCE_DIR" node -e '
    const fs = require("fs");
    const path = require("path");
    const { createRequire } = require("module");

    const inst = process.env.INSTANCE_DIR;
    const nm = path.join(inst, "node_modules");

    function time(label, fn) {
      const t0 = process.hrtime.bigint();
      let r;
      try { r = fn() } catch (e) { r = { error: String((e && e.message) || e).slice(0, 100) } }
      const ms = Number(process.hrtime.bigint() - t0) / 1e6;
      console.log("[start-dsh] perf-probe " + label + " = " + ms.toFixed(1) + " ms"
        + (r && r.error ? "  [" + r.error + "]" : ""));
      return r;
    }

    // D：readdir 一遍（建索引方案的基线）
    const pkgNames = time("D-readdir", () => {
      const out = [];
      for (const e of fs.readdirSync(nm, { withFileTypes: true })) {
        if (e.name.startsWith(".")) continue;
        if (e.name.startsWith("@")) {
          try {
            for (const s of fs.readdirSync(path.join(nm, e.name), { withFileTypes: true })) {
              if (s.isDirectory()) out.push(e.name + "/" + s.name);
            }
          } catch {}
        } else if (e.isDirectory()) out.push(e.name);
      }
      return out;
    });
    const names = Array.isArray(pkgNames) ? pkgNames : [];
    console.log("[start-dsh] perf-probe package-count = " + names.length);
    if (names.length === 0) { process.exit(0) }

    const anchor = path.join(inst, "package.json");
    const req = createRequire(fs.existsSync(anchor) ? anchor : path.join(inst, "index.js"));

    // A：只枚举路径，不碰文件系统
    time("A-resolve-paths", () => {
      let n = 0;
      for (const name of names) n += (req.resolve.paths(name) || []).length;
      return { n };
    });

    // B：加逐条 existsSync
    time("B-resolve-exists", () => {
      let hit = 0;
      for (const name of names) {
        for (const sp of (req.resolve.paths(name) || [])) {
          if (fs.existsSync(path.join(sp, name, "package.json"))) { hit++; break }
        }
      }
      return { hit };
    });

    // C：再加 readFileSync + JSON.parse（完整一轮 bundleManifest 的核心动作）
    time("C-resolve-exists-read", () => {
      let ok = 0;
      for (const name of names) {
        for (const sp of (req.resolve.paths(name) || [])) {
          const dir = path.join(sp, name);
          const pj = path.join(dir, "package.json");
          if (fs.existsSync(pj)) {
            try { JSON.parse(fs.readFileSync(pj, "utf8")); ok++ } catch {}
            break;
          }
        }
      }
      return { ok };
    });
  ' 2>&1 | while IFS= read -r l; do echo "$l"; done

  {
    echo "probed_at=$(date -u +%Y-%m-%dT%H:%M:%SZ 2>/dev/null || echo unknown)"
    echo "probe_rev=$PROBE_REV"
  } > "$PERF_CACHE" 2>/dev/null
  echo "[start-dsh] perf-probe: done"
fi

# ===== 预设（agent-presets）的加载开销（BL-251 第二部分）=====
#
# ## 为什么还要单独量这一段
#
# 上一段量出来 `listBundles` 那条路只要 390 ms（583 个包），**不是**慢的原因。
# 重新读前端代码（ui-plugin-manager/src/client/manager-store.ts:442）发现 `read()` 的
# 第一步是 `await pluginInventory.list()`，而它的实现
# （host/plugin-inventory/src/index.ts:95）里有一句：
#
#     const agentPresets = (await presets.compositionInventory()).map(...)
#
# `compositionInventory()`（preset/agent-presets/src/index.ts:322）会
# **逐个预设 await** `fileComposition()`，而后者会：
#   · readFile + js-yaml 解析
#   · `unresolvableRows(...)` —— 对**每一行**做 package 解析（require.resolve 那一套）
#
# 这和 listBundles 是同一个形状的 N×M syscall 放大，但在 preset 这一层。
# 而且它同时解释了三个现象：插件页慢、内置插件页慢、**预设页面加载不全**（同一条查询）。
#
# 本段量：预设目录数、每个 composition 文件的大小、YAML 解析耗时、逐行解析的占比。
PRESET_CACHE="$DSH_HOME/.preset-perf-probe"
_preset_should=1
if [ -n "${DSH_FORCE_PROBE:-}" ]; then _preset_should=1
elif [ -f "$PRESET_CACHE" ] && [ -n "$(find "$PRESET_CACHE" -mtime -7 2>/dev/null)" ]; then
  grep -q "^probe_rev=$PROBE_REV$" "$PRESET_CACHE" 2>/dev/null && _preset_should=0
fi

if [ "$_preset_should" = "1" ] && command -v node >/dev/null 2>&1; then
  echo "[start-dsh] preset-probe: start"
  node -e '
    const fs = require("fs");
    const path = require("path");

    // 预设可能出现在这两处：dsh 包自带的 presets/ 与用户目录
    //
    // ★ SHIPPED_PRESET_ROOT = fileURLToPath(new URL('../presets/', import.meta.url))
    //   也就是 `@deepseek-ai/dsh-agent-presets` 包的**同级** presets/ 目录。
    //   具体层级随打包方式变化，所以这里不猜路径，而是**递归找**"含有
    //   agent.cordis.yml 子目录的那个 presets 目录"。上一版按固定路径猜，结果 roots=none。
    const COMPOSITION = "agent.cordis.yml";
    const cands = [];
    const seen = new Set();

    function hasCompositionDirs(dir) {
      try {
        return fs.readdirSync(dir, { withFileTypes: true })
          .some(d => d.isDirectory() && fs.existsSync(path.join(dir, d.name, COMPOSITION)));
      } catch { return false }
    }

    function walk(dir, depth) {
      if (depth > 6 || seen.has(dir)) return;
      seen.add(dir);
      let entries;
      try { entries = fs.readdirSync(dir, { withFileTypes: true }) } catch { return }
      for (const e of entries) {
        if (!e.isDirectory()) continue;
        const full = path.join(dir, e.name);
        if (e.name === "presets" && hasCompositionDirs(full)) { cands.push(full); continue }
        // 只往"可能是包目录"的地方下钻，避免扫整个 node_modules（583 个包）
        if (depth < 3 && /^@|^dsh|node_modules$/.test(e.name)) walk(full, depth + 1);
      }
    }

    const nmRoots = [
      path.join(process.env.INSTANCE_DIR, "node_modules"),
      "/opt/dsh-preinstalled/node_modules"
    ];
    for (const nm of nmRoots) { if (fs.existsSync(nm)) walk(nm, 0) }

    // 用户目录（注意是点开头）
    const homePresets = path.join(process.env.DSH_HOME, ".agent-presets");
    if (fs.existsSync(homePresets)) cands.push(homePresets);

    const uniq = [...new Set(cands)];
    console.log("[start-dsh] preset-probe roots=" + (uniq.length ? uniq.join(",") : "none"));

    let total = 0, files = 0, yamlMs = 0, readMs = 0, rows = 0;

    for (const root of uniq) {
      let children;
      try { children = fs.readdirSync(root, { withFileTypes: true }) } catch { continue }
      for (const c of children) {
        if (!c.isDirectory()) continue;
        const f = path.join(root, c.name, COMPOSITION);
        if (!fs.existsSync(f)) continue;
        total++;
        files++;
        const t0 = process.hrtime.bigint();
        let content;
        try { content = fs.readFileSync(f, "utf8") } catch { continue }
        readMs += Number(process.hrtime.bigint() - t0) / 1e6;

        // 只统计行数规模，不引 js-yaml（探针不该依赖可选包）
        const lines = content.split("\n").length;
        rows += content.split(/\n\s*-\s/).length - 1;
        const size = content.length;

        console.log("[start-dsh] preset-probe file=" + c.name +
          " size=" + size + "B lines=" + lines);
      }
    }
    console.log("[start-dsh] preset-probe total-presets=" + total +
      " total-rows=" + rows + " read-ms=" + readMs.toFixed(1));
  ' 2>&1 | while IFS= read -r l; do echo "$l"; done

  # --- 第二部分：量 packageInstalled 那个向上遍历 ---
  #
  # 这是怀疑的**真正瓶颈**（discovery.ts:112）：
  #
  #   function packageInstalled(name, base) {
  #     let dir = fileURLToPath(base)
  #     for (;;) {
  #       if (existsSync(join(dir, 'node_modules', pkg, 'package.json'))) return true
  #       const parent = dirname(dir)
  #       if (parent === dir) return false
  #       dir = parent
  #     }
  #   }
  #
  # 从 base 一路向上到 /，**每层一次 existsSync**。106 行 × 约 10 层 = 上千次，
  # 每次都要穿 proot 的 ptrace 拦截 + 路径重写。
  #
  # 本段直接复现这个遍历，量单次与总计耗时。
  node -e '
    const fs = require("fs");
    const path = require("path");

    const presetRoot = "/opt/dsh-preinstalled/node_modules/@deepseek-ai/dsh-agent-presets/presets";
    const samplePkg = "@deepseek-ai/cordis";

    function packageInstalled(name, base) {
      const pkg = name.split("/").slice(0, name.startsWith("@") ? 2 : 1).join("/");
      let dir = base;
      let hops = 0;
      for (;;) {
        hops++;
        if (fs.existsSync(path.join(dir, "node_modules", pkg, "package.json"))) return { found: true, hops };
        const parent = path.dirname(dir);
        if (parent === dir) return { found: false, hops };
        dir = parent;
      }
    }

    // 单个 base 的层级数
    const depth = packageInstalled(samplePkg, presetRoot);
    console.log("[start-dsh] preset-probe pkg-walk hops=" + depth.hops + " found=" + depth.found);

    // 复现 106 行 × N 次
    const ROWS = 106;
    const t0 = process.hrtime.bigint();
    for (let i = 0; i < ROWS; i++) packageInstalled(samplePkg, presetRoot);
    const ms = Number(process.hrtime.bigint() - t0) / 1e6;
    console.log("[start-dsh] preset-probe pkg-walk x" + ROWS + " = " + ms.toFixed(1) + " ms"
      + " (avg " + (ms / ROWS).toFixed(2) + " ms/row)");

    // 对照组：一次纯 existsSync
    const t1 = process.hrtime.bigint();
    for (let i = 0; i < ROWS; i++) fs.existsSync(path.join(presetRoot, "standard", "agent.cordis.yml"));
    const ms1 = Number(process.hrtime.bigint() - t1) / 1e6;
    console.log("[start-dsh] preset-probe baseline-exists x" + ROWS + " = " + ms1.toFixed(1) + " ms");
  ' 2>&1 | while IFS= read -r l; do echo "$l"; done

  {
    echo "probed_at=$(date -u +%Y-%m-%dT%H:%M:%SZ 2>/dev/null || echo unknown)"
    echo "probe_rev=$PROBE_REV"
  } > "$PRESET_CACHE" 2>/dev/null
  echo "[start-dsh] preset-probe: done"
fi



LOGFILE="${TMPDIR:-/tmp}/dsh-start-$$.log"
if ! : > "$LOGFILE" 2>/dev/null; then
  # /tmp（宿主 cacheDir）不可写时退到实例目录；都不可写就明确失败，
  # 而不是让 node 的输出丢掉、就绪检测永远等不到（表现为"启动超时"，无从查起）
  LOGFILE="$INSTANCE_DIR/dsh-start-$$.log"
  if ! : > "$LOGFILE" 2>/dev/null; then
    echo "[start-dsh] FAILED reason=no-writable-logdir"
    exit 1
  fi
fi

# 透传"新增"输出（按字节偏移，不截断日志，否则后面就没法再 grep URL）
OFFSET=0
flush_new() {
  [ -n "${LOGFILE:-}" ] || return 0
  [ -f "$LOGFILE" ] || return 0
  size=$(wc -c < "$LOGFILE" 2>/dev/null || true)
  case "$size" in
    ''|*[!0-9]*) size=0 ;;
  esac
  if [ "$size" -gt "$OFFSET" ]; then
    tail -c +"$((OFFSET + 1))" "$LOGFILE"
    OFFSET="$size"
  fi
}

# --- node pid 文件与信号处理（B10 修复第一条路径）--------------------------
NODE_PID=""
NODE_PID_FILE="${NODE_PID_FILE:-$INSTANCE_DIR/dsh-node.pid}"

# 收尾：把 node 收干净（TERM → 最多 $1 秒 → KILL），并清掉 pid / 日志文件。
# $1 省略时等 5s（正常停止）；就绪超时那条路径传 2，别让失败再多拖 5 秒。
terminate_node() {
  wait_secs="${1:-5}"
  case "$wait_secs" in
    ''|*[!0-9]*) wait_secs=5 ;;
  esac
  if [ -n "${NODE_PID:-}" ]; then
    kill -TERM "$NODE_PID" 2>/dev/null || true
    i=0
    while [ "$i" -lt "$wait_secs" ]; do
      kill -0 "$NODE_PID" 2>/dev/null || break
      sleep 1
      i=$((i + 1))
    done
    kill -KILL "$NODE_PID" 2>/dev/null || true
  fi
  rm -f "$NODE_PID_FILE" 2>/dev/null || true
  rm -f "${LOGFILE:-}" 2>/dev/null || true
}

# 被 TERM/INT 打到（Termux 里 Ctrl-C、宿主 proot-run.sh、或启动器"恰好"杀到了本 shell）时走这里。
# ★ 注意：启动器杀的是 **proot 自己**，不是本 shell，所以 App 场景下这条 trap 收不到那个信号 ——
#   它服务于"直接跑脚本"的场景（Termux / 调试 / 测试脚本），但必须是对的：脚本退出时不能留 node。
on_signal() {
  echo "[start-dsh] STOP signal=$1"
  flush_new
  terminate_node 5
  exit 0
}

# ★ trap 必须在 node 启动**之前**装上：否则 `node ... &` 与 `trap` 之间收到 TERM，
#   默认动作会直接杀掉本 shell，node 就成了没人管的孤儿（正是 B10 要消灭的东西）。
trap 'on_signal TERM' TERM
trap 'on_signal INT' INT

# --- 启动 node（后台）----------------------------------------------------
# BL-222：启动器在启动前写了一份自己的 patch overlay（实例目录下的 launcher.patch.yml），
# 用 `--patch` 传进去，优先级高于 bundle / profile / $DSH_HOME 三层。
#
# 三个坑，都是实测踩出来的：
# ① **旗标位置是硬约束**：`dsh web --patch X --no-open ...` 生效；把 --patch 放到
#    --no-open 之后会 `error: unknown option '--patch'` —— dsh 的命令行在第一个不认识的
#    token 处把后续全部交给 app 解析。
# ② **overlay 缺失时绝对不能传**：dsh 读不到 overlay 会直接退出（不是降级）。
#    所以这里必须 -f 判一次；文件在但内容坏掉属于我们自己的写错，Kotlin 侧是原子写，不会产生半截文件。
# ③ **不支持 --patch 的旧版本**：旗标在 0.0.1-rc.1 / 0.1.0-rc.7 / 0.1.2-alpha.5 /
#    0.1.6-alpha.2 / 0.2.1-rc.2 都有；万一哪天上游删了，传它会直接 usage error。
#    这里的退化处理：先带 --patch 起，若 node 在 READY 之前就退出（说明可能是 usage error），
#    再把去掉 --patch 的命令重试一次 —— 优化项永远不该成为"起不来"的原因。
# 坑点(已验证): 默认 web profile 的 dsh-hmr 需要 node 带 --expose-internals，
# 且该 flag 不允许放进 NODE_OPTIONS，只能直接传给 node。
LAUNCHER_PATCH="$INSTANCE_DIR/launcher.patch.yml"

# ★ BL-247：proot 下的 inode 一致性 shim。
#
# 问题：dsh 的会话写锁（lease.ts）在 flock 成功之后还要校验
# `fstat(fd).ino === stat(path).ino`。proot 让这两个值**永远不同**
# （实测三个挂载点一致：fstat 恒为 proot 编的假值，stat 是真实 inode），
# 于是校验 3 次全失败 ⇒ 抛 SessionAlreadyOwnedError ⇒ 用户看到
# `session "…" is already owned by an active write handle`，连全新会话都发不出消息。
#
# 修法：preload 一个 shim，把 ino/dev 投影成"由路径决定的稳定值"，让那次自校验通过。
# flock 的互斥语义没有被改动。shim 自己会先探测，环境正常时它什么都不做。
SHIM="${DSH_SCRIPTS_DIR:-/opt/dsh/scripts}/proot-inode-shim.cjs"
# 注意 --require 只能在 NODE_OPTIONS 里（--expose-internals 不行，见上面的注释）。
# 用追加而不是覆盖：NODE_OPTIONS 里可能已经有启动器传进来的 --max-old-space-size。
if [ -f "$SHIM" ]; then
  NODE_OPTIONS="${NODE_OPTIONS:-} --require $SHIM"
  export NODE_OPTIONS
  echo "[start-dsh] inode shim : $SHIM"
fi

PATCH_USED=0
if [ -f "$LAUNCHER_PATCH" ]; then
  node --expose-internals "$BIN_JS" web --patch "$LAUNCHER_PATCH" --no-open --host "$HOST" --port "$PORT" >"$LOGFILE" 2>&1 &
  NODE_PID=$!
  PATCH_USED=1
else
  node --expose-internals "$BIN_JS" web --no-open --host "$HOST" --port "$PORT" >"$LOGFILE" 2>&1 &
  NODE_PID=$!
fi
# ★ 这里**不做** sleep：正常启动要几十秒，为"检测 usage error"先等 2 秒是给所有用户
#   加税。退化重试挂在下面 READY 循环里 node 提前退出的那个既有出口上（见 PATCH_USED）。

# 把 node 的真实 pid（+本次实际请求的端口）写给宿主侧启动器：停止时它按这个 pid 精确终止。
# 失败不致命（脚本自己的 trap 仍能清理），但要明确告警——否则"停止后 node 残留"又会无迹可查。
if printf '%s %s\n' "$NODE_PID" "$PORT" > "$NODE_PID_FILE" 2>/dev/null; then
  echo "[start-dsh] node pid: $NODE_PID"
else
  echo "[start-dsh] WARN: cannot write node pid file $NODE_PID_FILE (a leftover node process is possible on stop)" >&2
fi

URL_RE='http://[^ ]*token=[A-Za-z0-9_-]*'

# 墙上时间计时：每轮里 port_open 最多等 1.5s，再加 sleep 1s，按"轮次"计数会让
# READY_TIMEOUT=165 实际拖到 250s+ —— 启动器看门狗（180s）会先到，于是失败原因变成模糊的
# "启动超时"，而不是脚本给出的明确原因。date 不可用时自动退化为按轮次近似。
now_epoch() {
  n=$(date +%s 2>/dev/null || true)
  case "$n" in
    ''|*[!0-9]*) echo "" ;;
    *) echo "$n" ;;
  esac
}

READY=0
READY_URL=""
elapsed=0
elapsed_epoch_start=$(now_epoch)
# 端口先于 token URL 出现是常态：dsh 的 HTTP server 先 bind，等插件树加载完才打印带 token 的 URL。
# 所以"就绪"的**首要**判据是日志里出现 token URL；只有在固定端口且端口已开、又迟迟等不到 URL 时，
# 才退化为"端口可用就算就绪"（此时由启动器用已保存的 cookie 访问）。
PORT_OPEN_SINCE=-1
URL_GRACE=20
while : ; do
  NOW=$(now_epoch)
  if [ -n "$NOW" ] && [ -n "$elapsed_epoch_start" ]; then
    elapsed=$((NOW - elapsed_epoch_start))
  fi
  [ "$elapsed" -lt "$READY_TIMEOUT" ] || break

  flush_new
  if ! kill -0 "$NODE_PID" 2>/dev/null; then
    flush_new
    wait "$NODE_PID" 2>/dev/null
    code=$?
    # ★ BL-222：带 --patch 启动却在 READY 之前就退出 —— 最可能是上游删了 `--patch` 旗标
    #   （usage error 是毫秒级返回的，所以走到这里基本就是它）。退化重试一次不带 --patch，
    #   并把上一次的失败输出留在日志里（不能吞掉，否则将来真出别的问题就查不到了）。
    #   只重试一次：PATCH_USED 置 0 之后不会再进来。
    if [ "$PATCH_USED" = "1" ]; then
      echo "[start-dsh] WARN: dsh exited before READY while using --patch (code=${code:-1}), retrying without it"
      echo "[start-dsh] previous attempt (last 8 lines):" >&2
      tail -8 "$LOGFILE" >&2 2>/dev/null || true
      : > "$LOGFILE"
      # ★ 日志被截断，读取游标必须跟着归零（r31-s01-logic-03）：OFFSET 是 $LOGFILE 的字节游标，
      #   不归零则 flush_new 的 `size > OFFSET` 在重试日志重新长到旧长度之前恒假 —— 这段时间
      #   一行都不转发；一旦超过又从旧坐标切起，首行整行丢失、后半从词中间开始。
      #   全脚本只有 :213 复位过它（脚本初始化，一次），截断点必须自己复位。
      OFFSET=0
      PATCH_USED=0
      node --expose-internals "$BIN_JS" web --no-open --host "$HOST" --port "$PORT" >"$LOGFILE" 2>&1 &
      NODE_PID=$!
      # ★ 重试换到了**新的**子进程，pid 文件必须跟着换成新 pid（原来这里只清空，而首次写入在
      #   下面的 READY 循环**之前**、只执行一次 ⇒ 重试成功后 pid 文件恒为 0 字节，宿主按 pid
      #   精确终止失效，node 变孤儿、端口一直被占）。写法与首次启动保持一致。
      if printf '%s %s\n' "$NODE_PID" "$PORT" > "$NODE_PID_FILE" 2>/dev/null; then
        echo "[start-dsh] node pid: $NODE_PID"
      else
        echo "[start-dsh] WARN: cannot write node pid file $NODE_PID_FILE (a leftover node process is possible on stop)" >&2
      fi
      continue
    fi
    # 端口被占是"看起来莫名其妙的 node-exit code=1"最常见的原因（node 日志里必有 EADDRINUSE）。
    # 给出机器可读的原因，启动器据此显示"端口被占用"而不是一句 code=1。
    if grep -q 'EADDRINUSE' "$LOGFILE" 2>/dev/null; then
      echo "[start-dsh] FAILED reason=port-in-use port=$PORT code=${code:-1}"
    else
      echo "[start-dsh] FAILED reason=node-exit code=$code"
    fi
    rm -f "$NODE_PID_FILE" "$LOGFILE" 2>/dev/null || true
    exit "${code:-1}"
  fi

  READY_URL="$(grep -o "$URL_RE" "$LOGFILE" 2>/dev/null | head -1 || true)"
  if [ -n "$READY_URL" ]; then
    READY=1
    break
  fi

  if [ "$PORT" != "0" ] && port_open "$PORT"; then
    if [ "$PORT_OPEN_SINCE" -lt 0 ]; then
      PORT_OPEN_SINCE="$elapsed"
    elif [ $((elapsed - PORT_OPEN_SINCE)) -ge "$URL_GRACE" ]; then
      # 端口通了但没等到 URL（例如 dsh 换掉了日志格式）：仍算就绪，用已有 cookie 访问
      READY=1
      break
    fi
  else
    PORT_OPEN_SINCE=-1
  fi
  sleep 1
  if [ -z "$NOW" ]; then
    elapsed=$((elapsed + 1))   # date 不可用时的退化计时
  fi
done

if [ "$READY" -ne 1 ]; then
  # 超时：把 node 收掉再退出，避免半死进程占着端口（收尾同时会删掉 pid 文件）
  echo "[start-dsh] FAILED reason=ready-timeout after ${READY_TIMEOUT}s"
  if grep -q 'EADDRINUSE' "$LOGFILE" 2>/dev/null; then
    echo "[start-dsh] HINT: EADDRINUSE in the log, port $PORT is taken" >&2
  fi
  flush_new
  terminate_node 2
  exit 1
fi

flush_new
# When PORT=0 (auto), the kernel picked a real port; dsh prints it inside the token URL
# (READY_URL). Parse it back so the host gets a usable port instead of the literal 0 (K35).
# For a fixed PORT the value is already known, so keep it as-is.
READY_PORT="$PORT"
if [ "$PORT" = "0" ] && [ -n "$READY_URL" ]; then
  rest="${READY_URL#*://}"      # strip scheme  -> host:port/path?query
  hostport="${rest%%/*}"        # drop path     -> host:port
  parsed="${hostport##*:}"      # drop host     -> port
  case "$parsed" in
    ''|*[!0-9]*) : ;;           # not a clean number: keep 0 rather than print garbage
    *) READY_PORT="$parsed" ;;
  esac
fi
echo "[start-dsh] READY port=$READY_PORT"
if [ -n "$READY_URL" ]; then
  # 机器可读的就绪标记：启动器不必依赖第三方日志格式
  echo "[start-dsh] READY url=$READY_URL"
fi

# --- 前台跟随 node 输出直到退出 ------------------------------------------
while kill -0 "$NODE_PID" 2>/dev/null; do
  flush_new
  sleep 1
done
flush_new
wait "$NODE_PID" 2>/dev/null
code=$?
echo "[start-dsh] EXIT code=$code"
rm -f "$NODE_PID_FILE" "$LOGFILE" 2>/dev/null || true
exit "$code"
