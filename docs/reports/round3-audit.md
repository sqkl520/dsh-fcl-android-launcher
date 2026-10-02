# dsh 启动器 第三轮代码审计与修缮报告

日期：2026-09-22
范围：`FCL/src/main/java/com/dsh/**`（24 个 Kotlin 文件 ≈4400 行）、`FCL/src/main/assets/dsh/scripts/*.sh`（3 个脚本）、相关资源与测试
基线：第二轮加固（见 `round2-fixes.md`）之后的代码
产出：**发现并修复 1 个 P0 致命缺陷 + 8 个 P1 可靠性缺陷 + 5 个 P2 性能缺陷**，新增 1 个可回归的脚本一致性测试（18 条断言）

---

## 0. 结论摘要（TL;DR）

| 项目 | 结论 |
|---|---|
| 架构设计 | **良好**。分层清晰（core/ui 分离、单一命令构造处、进程级作用域、StateFlow 驱动），第二轮加固的方向是对的 |
| **致命问题** | **1 个 P0：安装与启动在真机上 100% 直接失败**。脚本是 bash 脚本，但 App 用 `/bin/sh`（Ubuntu/Debian 下是 dash）调用，`set -o pipefail` 让脚本在第 1 行即退出。**这是整个产品能否跑起来的开关** |
| 可靠性 | 修复后**显著改善**：正常停止不再被误报崩溃、状态机不再被旧进程回调污染、孤儿进程可被正确认领、失败诊断不再在重启后消失 |
| 性能 | 修复后**改善**：3 处主线程阻塞 IO 移出、2 处数据结构并发竞争消除、1 处全量重绘消除 |
| 安全 | **良好**。密钥走进程环境变量不进 argv、Keystore AES-GCM + AAD 绑定实例、日志 token 脱敏、WebView 收紧、命令数组传参无注入 |
| 可测试性 | **良好但有盲区**：纯逻辑有 19 个单测覆盖率不错；**但"脚本 + 调用方式"这一层完全没有测试，正是 P0 藏身之处**（已补齐） |

**最重要的一句话**：这一轮最大的价值不是"优化"，而是发现**之前所有"编译通过 + 脚本单独跑通"的验证都无法证明 App 真的能跑** —— 因为验证方式和 App 的实际调用方式不一致。

---

## 1. 评估方法与局限（先说清楚可信边界）

本环境是**离线 arm64 proot 沙箱**，无真机、Gradle 守护进程无法启动。因此采用如下替代验证链，并明确标注每条结论的证据强度：

| 手段 | 覆盖内容 | 证据强度 |
|---|---|---|
| `kotlinc-embeddable` 独立编译 dsh 模块（含**重新生成 R 类**以消除陈旧 R.jar 干扰） | 全部 24 个文件语法/类型/API 正确性 | 强（0 error / 0 warning / 179 class） |
| 桩 JUnit + 反射 runner 跑单测 | 19 个纯逻辑用例 | 强 |
| **真实 dash** 执行脚本 + 桩 node/npm | 脚本在目标解释器下的可运行性、退出码、就绪标记、信号转发 | 强（这就是 P0 的判定依据） |
| 用同一套测试跑"修复前脚本" | 回归证明 | 强（7 PASS / 11 FAIL） |
| 人工对照第二轮 PoC 的 `proot-run.sh` | proot 参数是否漂移 | 中 |
| **未覆盖** | 真机 proot 行为、Android Keystore 实际耗时、WebView 交互、前台服务在厂商 ROM 上的保活 | **弱 —— 见第 6 节待办** |

> 说明：报告中所有"已修复"均通过上述前三项验证；所有"待真机确认"的结论都单列在第 6 节，不与已验证结论混为一谈。

---

## 2. P0 致命缺陷：脚本是 bash，却被 `/bin/sh`（dash）调用

### 2.1 现象

安装与启动**必然失败**，且失败方式极具误导性：日志只有一行

```
/opt/dsh/scripts/setup-node-dsh.sh: 33: set: Illegal option -o pipefail
```

随后 `proot` 以退出码 2 结束。用户看到的是"安装失败"或"进程提前退出（code=2）"，而真实原因是**解释器不兼容**。

### 2.2 根因

两个脚本的首行是 `#!/usr/bin/env bash`，并且使用了 bash 专有特性：

```sh
set -uo pipefail                      # dash / busybox sh 不支持
(exec 3<>"/dev/tcp/$HOST/$1")         # bash 专有，dash 报 "cannot create /dev/tcp/..."
```

而启动器构造命令时**显式指定了解释器**：

```kotlin
// ProotCommand.build()
add("/usr/bin/env")
...
add("/bin/sh")        // ← 显式解释器
add(script)           // ← 脚本作为参数传入
```

**关键**：`sh script.sh` 这种调用方式下，**脚本的 shebang 被完全忽略**，一律由 `/bin/sh` 解释。目标 rootfs 是 Debian/Ubuntu（glibc），其 `/bin/sh` 指向 **dash**，而 dash 遇到 `set -o pipefail` 会直接报错并退出（exit 2），**脚本第一行就死了**。

### 2.3 为什么第二轮没发现（这是最值得记录的教训）

PoC 阶段脚本是**可执行的独立文件**，直接运行 `./start-dsh.sh` 或 `bash start-dsh.sh` —— 内核按 shebang 调 bash，**当然能跑通**。于是"脚本已验证"这个结论是成立的，但它**无法外推**到 App 的执行路径。第二轮总结里"脚本 4 场景真跑通过"记录的是脚本自身的正确性，而不是"App 调用脚本"的正确性。

> **教训**：验证必须复现"生产调用方式"。被验证对象的行为依赖调用上下文时，"单独测通过"不等于"集成能用"。

### 2.4 修复

1. 两个脚本改为**严格 POSIX sh**：
   - `#!/bin/sh`
   - `start-dsh.sh`：`set -u`（去掉 pipefail）
   - `setup-node-dsh.sh`：`set -eu`（去掉 pipefail）
2. 端口探测改为**不依赖 bash**：用 node 的 `net` 模块（启动阶段 node 必然已就绪），既不依赖 `/dev/tcp` 也不需要 curl/nc：

```sh
port_open() {
  node -e 'var n=require("net"),s=n.connect({host:process.argv[1],port:+process.argv[2]}, ...' "$HOST" "$1"
}
```

3. 保留 `-n` 语法自检；并把 `$(())` 这类 POSIX 算术保留（dash/busybox 都支持）。

### 2.5 证据（回归证明）

新增 `dsh-launcher-poc/scripts/test-scripts-posix.sh`，用**真实 dash** + 桩 node/npm 驱动脚本，对"修复后"与"修复前"分别执行：

| 被测对象 | 结果 |
|---|---|
| 修复后脚本 | **PASS=18 FAIL=0** |
| 修复前脚本（还原 `pipefail` + bash shebang） | **PASS=7 FAIL=11**，失败信息均为 `set: Illegal option -o pipefail` |

修复前脚本的失败明细（节选）：

```
[FAIL] 未给出 package-missing          ← 启动脚本没跑起来
[FAIL] 仍出现 pipefail 报错
[FAIL] 未打印 READY url=
[FAIL] 缺少 STAGE= 进度                 ← 安装脚本没跑起来
[FAIL] 未回读真实版本号
[FAIL] bin.js 缺失
```

这直接证明：**该缺陷在真机上会让"安装 + 启动"两条主链路全部不可用**。

---

## 3. P1 正确性与可靠性缺陷

### P1-1 正常停止被误报为"进程崩溃"

- **现象**：用户点"停止"，界面弹 `进程退出 code=143`（SIGTERM 的退出码），WebView 显示错误而不是"已停止"；状态机停在 `Exited` 无法自行回到 `Idle`。
- **根因**：`stop()` 把状态置为 `Stopping` 后异步 `terminate()`；进程真正退出时触发 `onProcessExit()`，其中 `wasStarting == false`（因为状态是 `Stopping` 而非 `Starting`），于是走了 `else` 分支，把状态置为 **`Exited(code=143)`**。随后 `stop()` 协程里的补偿逻辑判断 `cur is State.Stopping` 已不成立（状态已被改成 `Exited`），**无法纠正**。
- **修复**：引入 `stopRequestedFor` 标记区分"我们要它停"与"它自己崩了"；`start()` 会清空该标记。`onProcessExit()` 新增第一优先级分支处理主动停止，并在 `synchronized` 内**按 instanceId 校验**后才落 `Idle`（防止覆盖新实例的状态）。

### P1-2 旧实例的退出回调会污染新实例的状态（竞态）

- **现象**：切换实例启动时（A 停 → 立刻起 B），B 可能瞬间被 A 的退出回调打成 `Exited`，表现为"刚点启动就显示崩溃"。
- **根因**：`stop(A)` 的终止是异步的（最长 5s），期间 `start(B)` 已把状态推进到 `Starting(B)`；A 进程退出回调随后无条件覆盖 `_state`。
- **修复**：所有状态跃迁都改为**先校验 instanceId 再写**（`stop()`、`onProcessExit()` 两处），这是修 P1-1 时的同一处改动，顺带堵住了这个竞态。

### P1-3 `ProotProcessExecutor` 超时判定存在跨线程可见性问题

- **现象**：极端情况下 npm 卡死超时后，返回值可能不是 `-2`（超时标记）而被当成正常退出，从而**误判安装成功**。
- **根因**：`var timedOut = false` 由看门狗线程写入、由调用线程在 `waitFor()` 返回后读取，二者之间**没有 happens-before 关系**（非 volatile），JIT 可将其缓存/提升。
- **修复**：改用 `AtomicBoolean`。

### P1-4 `nameOf` 用非并发 `HashMap` 被多线程读写

- **根因**：`start()`（UI 线程）、`stop()`（UI 线程）、`onProcessExit()`/`onProcessLine()`（IO 协程）都会碰 `nameOf`。Java/Android 的 `HashMap` 在并发 put 下可能死循环或丢数据。
- **修复**：改 `ConcurrentHashMap`。

### P1-5 自动端口实例的孤儿进程无法被清理（300MB 泄漏）

- **现象**：App 被系统杀掉后，proot+node 仍在后台跑（约 300MB 常驻），下次启动既认领不了也杀不掉，导致端口/内存被长期占用。
- **根因**：`start()` 里写 pid 文件用的是 `instance.port`，而默认配置是 `port = 0`（系统分配）——`adoptOrphan()` 判定 `if (port <= 0 ...) return false`，于是**永远无法认领**。另外 `Handle.pid()` 依赖反射（ART 上 `java.lang.Process.pid()` 可能不存在），失败时静默返回 `-1`，pid 文件根本不写。
- **修复**：
  - 就绪后（拿到真实端口）**重写 pid 文件**（`onProcessLine` 已有此逻辑，现在明确保证 pid 一并更新）；
  - pid 取不到时**显式记一行警告**，而不是静默降级——否则"为什么有个 node 杀不掉"无从查起。

### P1-6 失败诊断在重启 App 后消失

- **现象**：实例安装失败（`BROKEN`，带明确失败原因）→ 重启 App → 实例变成"未安装"且**原因不再显示**，用户完全不知道当初为什么失败。
- **根因**：`DshInstances.repair()` 把"磁盘上没有可用包"的状态统一降级为 `NOT_INSTALLED`；而 `DshInstanceAdapter` 只在 `state == BROKEN` 时展示 `lastError`。两处叠加＝诊断信息被吞。
- **修复**：`repair()` 保留 `BROKEN`（只清 `INSTALLING` 这种瞬态）；Adapter 同时展示 `NOT_INSTALLED` 下的 `lastError`（例如"已取消"）。

### P1-7 运行时底座解压存在 check-then-act 竞态

- **根因**：`DshBootstrap.install()` 先读 `_busy.value` 再置真，两个界面（列表页横幅 / 下载页）可能同时进入，**并发解压同一个 rootfs**，互相覆盖。
- **修复**：改用 `AtomicBoolean.compareAndSet` 占位，`finally` 释放。

### P1-8 Android 13+ 未申请通知权限

- **现象**：API 33+ 上前台服务通知（含"停止"按钮）不显示，用户只能回到 App 才能停掉常驻 agent；而这条通知正是本服务"可被用户看见和停止"的凭据。
- **根因**：清单里声明了 `POST_NOTIFICATIONS`，但**运行期从未申请**。
- **修复**：启动实例时申请一次（`ensureNotificationPermission()`）；用户拒绝不影响进程本身，只是少了通知入口。

---

## 4. P2 性能与体验缺陷

| 编号 | 问题 | 位置 | 修复 |
|---|---|---|---|
| P2-1 | **启动实例会阻塞 UI 线程**：`killStale()` 读 `/proc`、Keystore 解密、`ProcessBuilder.start()`（fork+exec proot）全在主线程 | `DshInstancesActivity.doStart` | 移到 `Dispatchers.IO`，结果回主线程再决定导航 |
| P2-2 | **每次进 Activity 都在主线程读/写最多 1MB 日志**（`attachFile` 由 `loadPaths` 触发，而 `loadPaths` 在 App 启动与多个页面 onCreate 都调） | `DshLogBus.attachFile` | 幂等（同路径直接返回）+ 截断移到 IO，并在 `lock` 内做"读尾+覆写"避免与追加写交错 |
| P2-3 | **打开设置页时主线程做 Keystore AES-GCM 解密**（部分机型 100ms+，明显掉帧） | `DshSettingsActivity.refreshCredentialStatus` | 整体移入 IO，UI 先显示"检查中…" |
| P2-4 | **`notifyDataSetChanged()` 抵消了 DiffUtil 的收益**：列表在安装过程中每 200ms 全量重绘，闪烁且打断 DiffUtil 动画 | `DshInstancesActivity.onResume` | 删除该全量刷新（状态由 StateFlow 驱动，返回页面时自动回放最新值） |
| P2-5 | **`sizeCache` 是非并发 `HashMap`**，被 IO 回调与主线程同时读写 | `DshInstanceAdapter` | 改 `ConcurrentHashMap` |

---

## 5. 逐方面评估（分维度）

| 维度 | 评分 | 说明 |
|---|---|---|
| **架构与分层** | ★★★★☆ | `core`（无 UI 依赖、可测）/`ui` 分离干净；`ProotCommand` 作为唯一命令构造处是很好的设计（第二轮成果）；`DshAppScope` 让长任务脱离界面生命周期，方向完全正确 |
| **进程生命周期** | ★★★☆☆→★★★★☆ | 修复前有"正常停止被当崩溃""旧回调污染新实例""孤儿认领条件永假"三个实质缺陷；修复后状态机自洽，孤儿可认领可清理 |
| **并发正确性** | ★★★☆☆→★★★★☆ | 修复前存在 2 处数据结构竞态 + 1 处跨线程可见性问题 + 1 处 check-then-act；现已全部消除 |
| **性能** | ★★★☆☆→★★★★☆ | 主线程 IO 已清理；日志 O(n²) 与全量重绘在第二轮已解决；本轮补齐 5 处 |
| **安全** | ★★★★★ | 密钥不进 argv（`ps` 不可见）、不落明文盘、Keystore + AAD 绑定 instanceId、密文带版本字节、token 双重重脱敏、WebView 关 file/content 访问 + 站外链接外跳、proot 命令数组传参无注入、环境变量白名单拦 `LD_PRELOAD`。**这部分质量很高** |
| **可测试性** | ★★★☆☆→★★★★☆ | 纯逻辑 19 个单测覆盖良好；**但脚本/进程集成层此前是 0 测试，P0 就藏在那里**，本轮补上 18 条断言（含回归证明） |
| **可维护性** | ★★★★☆ | 注释质量高（几乎每处修复都写了"为什么"），文件粒度合理，无明显复制粘贴；`DshLogBus`/`DshPaths` 职责单一 |
| **健壮性/错误处理** | ★★★★☆ | 有超时看门狗、失败出口、原子写盘、清单损坏备份、磁盘空间预检、rootfs 单层目录自动上提。剩余风险主要在真机环境差异 |
| **文档** | ★★★★☆ | `../PLAN.md` / `round2-fixes.md` / `../design/multi-version.md` / `../design/ui-manifest.md` 记录完整；建议把本轮的"验证方式必须匹配调用方式"写进项目约定 |

### 一个正面评价

第二轮加固的**判断力是准确的**：把安装器从 `lifecycleScope` 换到进程级作用域、把状态按磁盘真实内容校准、密钥不落盘、命令构造收敛到一处 —— 这些都是对的、且是本轮修复能站得住的基础。本轮的问题集中在**"最后一公里"：脚本与调用方式不匹配**，以及若干并发细节。

---

## 6. 未修复项与真机待办（风险清单）

> 以下均**未**在本轮修改，属于需要真机/上游决策的事项。

1. **真机端到端验证仍是空白**（最高优先级）
   - 待办：补 `jniLibs/libproot.so` + `libproot_loader.so`（优先）与 `rootfs.tar.xz`（内置 Node），走通"首启解压 → 自检 → 安装 → 启动 → WebView"。
   - 需要重点观察：`set -u` 下变量展开的行为、node 端口探测在低版本 node 上的可用性、proot seccomp 加速是否在部分机型失败。

2. **`PROOT_NO_SECCOMP` 兜底未启用**
   - `ProotCommand.isAllowedEnvKey` 已白名单该变量，但无人设置它。部分 Android 机型 proot 的 seccomp 加速会失败，需要 `PROOT_NO_SECCOMP=1`（代价是变慢）。**建议做成"启动失败后自动重试一次带该变量的启动"**，而不是默认开启。

3. **`Process.pid()` 反射在 ART 上可能拿不到**
   - 已加警告日志。若要彻底解决，需改为解析 `/proc/self/task/*/children` 或读取 proot 包装脚本输出的 `$$`。当前降级行为是"孤儿无法自动认领/清理"。

4. **其他语言的 `strings.xml` 存在 aapt2 编译错误**（**非 dsh 引入，属仓库既有问题**）
   - `values-de` / `values-fa` / `values-ja` / `values-pt-rBR` / `values-ru` 等在 `aapt2 compile` 时报
     `multiple substitutions specified in non-positional format; did you mean to add the formatted="false" attribute?`
   - 影响：这些 locale 下若做完整资源编译会失败。修复方式是给这些字符串加 `formatted="false"` 或改用位置参数 `%1$s`。**建议单独立项处理**，与 dsh 无关。

5. **`DshDownloadViewModel` 的安装进度是单一全局流**
   - `DshInstaller.progress` 是全局 `StateFlow`，若同时安装两个实例，下载页的进度提示会混在一起（列表页按实例 id 展示进度是正确的，不受影响）。属体验瑕疵，非功能缺陷。

6. **`DshSettingsActivity` 未使用 `repeatOnLifecycle`**
   - "测试连接"是 `lifecycleScope.launch`，页面销毁后仍可能回写视图（不会崩溃，但会短暂持有引用）。可接受。

---

## 7. 验证结果汇总

```
======== 1) Kotlin 编译 (dsh 模块 24 文件) ========
exit=0  errors=0  warnings=0  classes=179        ← 独立编译，含重新生成的 R 类

======== 2) JVM 单元测试 ========
==== tests: PASS=19 FAIL=0 ====                  ← 含本轮新增 5 个用例

======== 3) 脚本 POSIX 一致性（修复后） ========
==== 结果: PASS=18 FAIL=0 ====

======== 4) 脚本 POSIX 一致性（修复前，回归证明） ========
==== 结果: PASS=7 FAIL=11 ====                   ← 11 条失败全部指向 pipefail
```

### 本轮新增测试用例

| 用例 | 保护的行为 |
|---|---|
| `sanitizesNonLeadingTokenParam` | `&token=`（LAN 变体）也必须脱敏 |
| `sanitizesTokenCaseInsensitively` | `TOKEN=` 大小写混写也必须脱敏 |
| `formatsSizesWithCorrectUnits` | 体积文本单位正确；`-1` 不抛异常不显示负数 |
| `parsesPortFromUrlWithoutToken` | 无 token 的 URL 仍能解析端口（cookie 模式） |
| `ignoresEmptyToken` | `?token=` 空值不得被当成已认证 URL（否则必然 401） |

`test-scripts-posix.sh` 的 18 条断言覆盖：dash 语法、`package-missing` 失败出口、`READY url=` 就绪标记（PORT=0 自动端口）、固定端口走 node 探测、TERM 信号转发不留孤儿、`STAGE=` 进度、真实版本回读、**npm 假成功必须非 0 退出**、`probe.sh` 可运行。

---

## 8. 改动文件清单

### Kotlin（8 个）

| 文件 | 改动 |
|---|---|
| `core/DshRuntime.kt` | 新增 `stopRequestedFor` 区分主动停止/崩溃；`onProcessExit` 重写为三态分支并按 instanceId 校验；`nameOf` 改并发容器；pid 不可用告警；`stop()`/`start()` 维护标记 |
| `core/ProotProcessExecutor.kt` | `timedOut` 改 `AtomicBoolean`（跨线程可见性） |
| `core/DshLogBus.kt` | `attachFile` 幂等 + 截断移到 IO + `lock` 内读写 |
| `core/DshBootstrap.kt` | 解压互斥改 `AtomicBoolean.compareAndSet` |
| `core/DshInstances.kt` | `repair()` 保留 `BROKEN`，不再吞掉失败诊断 |
| `ui/DshInstancesActivity.kt` | 启动流程移出主线程；通知权限申请；新增 companion 常量；移除抵消 DiffUtil 的全量刷新 |
| `ui/DshInstanceAdapter.kt` | `sizeCache` 改 `ConcurrentHashMap`；`NOT_INSTALLED` 也展示失败原因 |
| `ui/DshSettingsActivity.kt` | 凭据状态刷新移到 IO，新增"检查中…"态 |

### 脚本（2 个）

| 文件 | 改动 |
|---|---|
| `assets/dsh/scripts/start-dsh.sh` | `#!/bin/sh`；去 pipefail；端口探测改 node |
| `assets/dsh/scripts/setup-node-dsh.sh` | `#!/bin/sh`；去 pipefail |

### 资源 / 测试（3 个）

| 文件 | 改动 |
|---|---|
| `res/values/strings.xml`、`res/values-zh/strings.xml` | 新增 `dsh_key_checking`（中英） |
| `src/test/java/com/dsh/DshCoreLogicTest.kt` | 新增 5 个用例（19 个） |
| `dsh-launcher-poc/scripts/test-scripts-posix.sh` | **新增**：脚本 POSIX 一致性测试（18 断言，含回归证明） |

---

## 9. 复现命令

```sh
# 1) 脚本 POSIX 一致性（不需要 Android SDK）
sh dsh-launcher-poc/scripts/test-scripts-posix.sh
# 回归证明：对修复前脚本执行应得到 11 条 FAIL
sh dsh-launcher-poc/scripts/test-scripts-posix.sh /tmp/oldscripts

# 2) 真机构建（需 Android SDK，注意 arm64 需 aapt2 qemu wrapper）
./gradlew :FCL:compileDebugKotlin --offline -Darch=arm64

# 3) 单测（离线沙箱下 Gradle 的 ksp 任务会卡住，用 kotlinc + 桩 JUnit 替代）
#    见本报告第 1 节验证链
```

---

## 10. 给下一轮的建议（按性价比排序）

1. **真机跑通端到端**，并把"复现生产调用方式"作为验证铁律写进 `../PLAN.md`。本轮 P0 的根因就是违反了这条。
2. **把脚本纳入 CI**：`test-scripts-posix.sh` 已经可以在纯 Linux 环境跑，建议设为提交前必过。
3. 给 `DshRuntime` 的状态机补一层**可测抽象**（把"进程/时间/Context"注入），使 P1-1/P1-2 这类竞态能被单测覆盖，而不是只靠人工推演。
4. 处理第 6 节的 4 个 repo 既有 aapt2 资源错误（与 dsh 无关，但会挡住发布）。
5. 考虑 `DshInstaller.progress` 从全局改为按 instanceId 分发，消除多实例安装时的提示混淆。
