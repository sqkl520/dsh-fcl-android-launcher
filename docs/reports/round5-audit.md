# 项目评估与优化报告（第五轮）—— dsh 安卓启动器

> 被审对象：`/workspace/FCL`（FoldCraftLauncher 基座 + `com.dsh` 启动器模块）
> 基线：第四轮审计（`round4-audit.md`）之后的代码
> 本轮时间：2026-09-23　|　审查方式：静态审查 + 编译/资源处理 + JVM 单测 + 脚本一致性测试（无限真机）
> 结论摘要：**修掉 1 个 P0（进实例列表页必崩）、7 个 P1、8 个 P2**，新增 1 个"资源格式契约"回归测试（23/23 通过），
> 脚本一致性 18/18 保持通过，编译与资源处理均通过。

---

## 1. 项目概览

### 1.1 项目用途

在**未 root 的安卓手机**上运行 DeepSeek Harness（dsh，`@deepseek-ai/dsh`，npm 上的 AI CLI/harness）。
dsh 是 Node/TS 项目，其原生依赖只发布 linux glibc/musl（无 bionic），所以在 Termux 原生环境下跑不起来，
必须靠 **proot + Linux rootfs（Ubuntu glibc / Alpine musl，arm64）** 间接运行，再用 WebView 承载
`dsh web` 的界面。本项目就是把这条链路做进 FCL（一个安卓 Minecraft 启动器）里的**启动器/管理器**：
首启解压运行时底座 → 按版本安装 dsh（多实例隔离）→ 启动/停止长驻进程（前台服务保活）→ WebView 联调 →
日志、磁盘占用、凭据（DeepSeek API Key）管理。

### 1.2 技术栈

| 层 | 内容 |
|---|---|
| App | Android（Kotlin 2.4.10 / Java），FCL 基座，applicationId `com.tungsten.fcl`，多模块（FCL / Terracotta / ZipFileSystem / LWJGL） |
| SDK | compileSdk 35 / targetSdk 34 / minSdk 26，AGP 8.13.2，Gradle 8.14.4，NDK 27，viewBinding + DataBinding |
| 异步/状态 | Kotlin Coroutines + Flow（StateFlow 为主），进程级 `DshAppScope`，`SingleFlight` 原子单飞 |
| 持久化 | Gson（`instances.json`、`dsh.pid`）、Android Keystore（AES-GCM，API Key 密文） |
| 运行时 | proot（打包为 `libproot.so` / `libproot_loader.so`）、rootfs（tar.xz 首启解压）、rootfs 内 Node 22/24 + npm |
| 网络 | npm registry（版本列表，走 FCL 的 `HttpRequest`）、DeepSeek 云端 API（key 校验，`HttpURLConnection`） |
| 文档/测试 | `docs/`（本报告所在）、JVM 单测（`org.junit` 桩 + MiniRunner，见 §9）、POSIX 脚本一致性测试 |

### 1.3 目录结构（只列本次相关部分）

```
FCL/src/main/java/com/dsh/
  core/   DshRuntime.kt(556→604)  DshInstances.kt(284→297)   DshInstaller.kt(303→312)
          ProotProcessExecutor.kt(212→205)  DshLogBus.kt(194→212)  ProotCommand.kt(162)
          DshBootstrap.kt(271)  DshCredentials.kt(161)  DshPaths.kt(204)  DshRegistry.kt(181)
          DshInstance.kt(79)  DshDownloadViewModel.kt(131)  DshVersionListItem.kt(47)
          DshRuntimeService.kt(200)  DshServices.kt(26)  DshAppScope.kt(22)  SingleFlight.kt(51)
          DeepSeekApi.kt(80)
  ui/     DshInstancesActivity.kt(331)  DshDownloadActivity.kt(174)  DshSettingsActivity.kt(263)
          DshWebViewActivity.kt(272→278)  DshLogsActivity.kt(94)  DshInstanceAdapter.kt(214)
          DshVersionAdapter.kt(92)
FCL/src/main/assets/dsh/            version + proot/ + rootfs/（PLACEHOLDER，待放大文件）+ scripts/
FCL/src/main/res/layout/            activity_dsh_*.xml ×5、item_dsh_instance.xml、view_dsh_bootstrap_banner.xml
FCL/src/test/java/com/dsh/          DshCoreLogicTest.kt(269)、DshResourceFormatTest.kt(164，本轮新增)
FCL/src/main/res/values{,-zh}/strings.xml   本轮新增 136 条 dsh 文案
docs/                              INDEX/PLAN/ROADMAP/PACKAGING + design/ + reports/（本报告为 reports/round5-audit）
```

当前 `com.dsh` 共 **25 个 Kotlin 文件 / 4812 行**。

### 1.4 运行方式

```bash
# 出 APK（需要 Android SDK + NDK + JDK17；手机上做不了）
cd FCL && ./gradlew :FCL:assembleFordebug        # 或 assembleDebug
# 单模块编译（本沙箱验证用的命令，见 §9）
GRADLE_OPTS="-Xmx1300m -XX:MaxMetaspaceSize=450m" ./gradlew --no-daemon --offline -Darch=arm64 :FCL:compileDebugKotlin
# 离线单测（本沙箱）
sh /workspace/run-tests.sh
```

装机后的用户路径：**长按主界面左侧"设置"** → 实例列表页 →（首次弹"准备运行时"横幅，解压 proot/rootfs/脚本）
→ 下载页选版本安装 → 回列表页配置 API Key → 启动 → WebView 里对话。

---

## 2. 使用逻辑梳理与完善

### 2.1 安装与配置

| 步骤 | 现状 | 本轮变化 |
|---|---|---|
| 运行时底座 | 首启/点横幅时 `DshBootstrap.install()`：磁盘检查（≥1.5GB）→ 解压 scripts/proot/rootfs（各带 version 增量）→ rootfs 临时目录解压 + 顶层目录自动上提 + 原子替换 → 在 proot 里跑 `probe.sh` 自检 | 无变化（上轮已加固） |
| 放 proot/rootfs | 需人工准备两个大文件（见 `../PACKAGING.md`）：`libproot.so`/`libproot_loader.so` → jniLibs；`rootfs.tar.xz` → assets | 无变化（**仍是 M1 的唯一关键路径**） |
| API Key | 实例设置页录入 → Keystore AES-GCM 加密落盘（AAD=instanceId）→ 启动时解密后只经**子进程环境变量**注入 | 无变化 |
| 实例配置 | 名称 / 模型（deepseek-flash、deepseek-v4-pro）/ profile（web、headless）/ 端口（0=自动） | 无变化 |

### 2.2 启动与初始化

1. `FCLApp.onCreate()` → `DshPaths.loadPaths()`（建目录、绑定日志文件）+ `DshInstances.init()`（读清单 + 异步 `repair()` 校准状态）。
2. 各 dsh 页面 `onCreate` 再兜底调用一次（幂等，防进程被系统重建）。
3. `DshInstancesActivity` 调 `maybeAdoptOrphan()`：若上次 App 被系统杀但 proot/node 还活着 → 认领（`adoptOrphan`）。

### 2.3 核心业务流程（现状 = 本轮修完后的语义）

```
[启动] 点"启动" → IO 线程检查凭据状态（None→引导配置 / Unreadable→提示重录 / Ok→继续）
  → DshRuntime.start(ctx, instance)（@Synchronized，IO 线程）
      预检（proot 可执行 + rootfs 可引导 + 脚本在位）
      磁盘校验（package.json / lib/bin.js 真实存在）
      同实例在跑→复用；别的实例在跑→先 stop（异步终止，最长 5s）
      清同实例孤儿 → 解密 key 注册脱敏 → 构造 argv/env → fork/exec proot
      → state=Starting → 起 180s 看门狗 → 起前台服务 → 跳 WebView
  → 脚本侧：后台起 node（--expose-internals）→ 轮询日志抓 token URL（或端口兜底）
      → 打印 [start-dsh] READY url=… → 前台跟随输出直到 node 退出
  → onProcessLine 抓到 URL → state=Running(url, port) → 端口写回实例配置 → WebView loadUrl
[就绪前退出] → 疑似 proot/seccomp 不兼容 → **自动带 PROOT_NO_SECCOMP=1 重试一次**（新）
            → 否则 state=Failed(退出码 + 最近 3 行日志)
[停止] 停止/通知栏停止 → TERM → 等 5s → KILL → 删 pid 文件 → Idle → 收前台服务
[崩溃] 进程非主动退出 → state=Exited(code)（界面 toast）或 Failed（界面弹原因 + 看日志）
[冷启认领] pid 文件 + /proc cmdline 双重校验 → 直接 Running → **10s 存活巡检，死了自动落 Exited**（新）
```

### 2.4 API / CLI 示例

没有对外 API；内部"接口"是三个单例 + 脚本契约：

* `DshInstaller.install(instance, version)` / `cancel(id)` / `statuses`（进程级单例，`DshServices.installer(ctx)`）
* `DshRuntime.start/stop/adoptOrphan/state`（`StateFlow<State>`，界面只订阅）
* 脚本契约（rootfs 内，均由 `/bin/sh <script>` 调用，严格 POSIX）：
  * `setup-node-dsh.sh`：`[setup] STAGE=…` 进度、`[setup] DONE version=…` 成功、`FAILED reason=…` 失败
  * `start-dsh.sh`：`[start-dsh] READY url=…` 就绪、`FAILED reason=…` 失败、`EXIT code=…` 结束
  * `probe.sh`：`dsh-probe-ok` 自检通过

### 2.5 错误处理与退出清理（现状）

* 进程异常 → `Failed/Exited` + 原因（含最近日志尾部）+ 前台服务自动收尾（通知不会残留）。
* 取消安装 → `NOT_INSTALLED + "已取消"`（**本轮修**：以前会被写成 BROKEN）；若取消时文件其实已装完则按磁盘事实置 READY。
* 删除实例 → 先停进程 → 异步递归删除（不阻塞主线程）→ 记录残留情况。
* 退出清理：pid 文件、前台通知、`credentials.env` 历史明文残留（`cleanupLegacyFiles`）。

### 2.6 已完善的使用说明（本轮新增/更正）

1. 更正路线图 M4 中的一条判断：**`values-*/strings.xml` 的 aapt2 "non-positional format" 是警告不是错误**，
   不会挡住 `assembleDebug`（§9 有实测证据）。若要用 `formatted="false"` "消警告"要小心：那会让这些文案的
   `getString(id, args)` 参数**不再被替换**（界面上直接显示 `%s`），正确做法是改成位置参数（`%1$s`）。
2. 新增"资源格式契约"测试，把 `strings.xml` 占位符与代码调用参数绑起来（见 §9），把"进列表页必崩"这类
   跨文件缺陷变成可自动拦截的问题。
3. 新增脚本/单测的一键离线跑法：`/workspace/run-tests.sh`（不需要真机、不需要联网 Gradle 单测任务）。

---

## 3. 问题清单

> 级别：P0 阻断/必崩 · P1 严重 · P2 一般 · P3 优化。
> 状态：`已修` = 本轮改完并验证；`已证伪` = 审查中发现原判断不成立；`待办` = 记录未做。

| ID | 级别 | 类型 | 位置 | 问题 | 影响 | 复现/证据 | 修复方案 | 状态 |
|---|---|---|---|---|---|---|---|---|
| **R5-01** | **P0** | 崩溃/正确性 | `res/values/strings.xml:1501`、`values-zh/strings.xml:1362` + `ui/DshInstanceAdapter.kt:115-120` | `dsh_instance_subtitle` 写的是 `%2$d`（要数字），代码传的是**字符串**（`inst.port.toString()` 或"自动/auto"） | `Resources.getString()` 内部是 `String.format`，`%d` 收到 String 抛 `IllegalFormatConversionException` → **只要有任意一个实例，实例列表页一绑定就闪退**（而列表页是这个启动器的唯一入口） | 负向对照：把文案改回 `%2$d` 后新测试立即失败，异常原样打印 `IllegalFormatConversionException: d != java.lang.String` | 文案改 `%2$s`（中英两处）+ 新增 `DshResourceFormatTest` 把"文案占位符 ↔ 调用参数类型"钉死 | 已修（23/23 通过） |
| **R5-02** | **P1** | 并发/状态覆盖 | `core/DshRuntime.kt:513`（原 `onProcessExit`） | 退出回调无条件 `handle=null`/`_running=null`，并把状态改写成 `Exited(A)`——它**不校验当前状态属于哪个实例** | 用户"停 A 起 B"时，A 的退出回调迟到 → B 的进程句柄被抹掉，`stop()` 拿不到句柄只删 pid 文件 → **B 的 proot/node 变成停不掉的孤儿进程**；界面还会显示成"A 退出了" | 代码路径推演（第三轮给状态跃迁定的"先校验 instanceId"约定在 `onProcessExit` 漏了）；`onProcessExit` 是唯一没有校验的分支 | 新增 `ownerOf(state)` 归属判定与 `setStateIfOwned()`：状态/句柄只在"仍属于本实例"时才改；迟到回调只做无副作用清理（删 pid 文件 + 记日志） | 已修 |
| **R5-03** | **P1** | 语义错误 | `core/DshInstaller.kt:99-160`（`install`/`cancel`） | `runInstall()` 阻塞在 `proot.run()` 上，`job.cancel()` 打断不了阻塞调用；取消后 proot 以非 0 退出，任务体继续执行 `fail()` | 用户点"取消"→ 实例被写成 **BROKEN（"安装失败"）**，还带上 npm 的错误尾巴；更糟的是"取消时其实已装完"也会被误标 BROKEN | 逻辑推演（协程取消只在挂起点生效；`ProcessBuilder` 阻塞读不响应取消） | 加 `cancelled` 标记集：`cancel()` 先打标记，任务体在落状态前检查；若文件已装完则按磁盘事实置 READY（不让状态卡在 INSTALLING） | 已修 |
| **R5-04** | **P1** | 并发/串台 | `core/DshInstaller.kt:205-215`（原 `lastErrorSummary`/`errorTail`） | 失败原因/ npm 错误尾巴是**单例级字段**，而安装器是进程级单例、可同时装两个实例 | 双实例并发安装时，A 的失败摘要被 B 的输出冲掉，A 的对话框里显示的是 B 的错误——几乎无法排查 | 代码推演（字段无 key）；路线图 M2 已把它列为待办"进度串台" | 改为 `errorSummaries` / `errorTails` 两张按 instanceId 分表的 map，`errorSummary(id)` 按实例取 | 已修 |
| **R5-05** | **P1** | 并发/资源 | `core/ProotProcessExecutor.kt:111`（原 `destroyActive`） | 只保存**一个** `active` 子进程槽位，后启动的会覆盖前一个 | 同时装两个实例时"取消 A"根本找不到 A 的进程（什么都不杀，A 的 npm 继续写 `node_modules`，日志里出现"取消了还在装"）；也可能误杀 B | 代码推演（单槽位 + 旧代码 `a.tag == null` 时无条件杀） | 改为 `CopyOnWriteArrayList<Active>` 记录全部在跑子进程，按 tag 精确匹配（并要求 tag 完全相等） | 已修 |
| **R5-06** | **P1** | 安全/可靠性 | `core/DshRuntime.kt:664`（`ProcessUtil.kill`） | 停止"认领来的孤儿进程"时只有上次记下的 pid，**不校验身份**就发 TERM/KILL | pid 会被系统复用：孤儿早退出而 pid 被别人用掉时，会**误杀无关进程**（含其它 App 进程，同 uid 下还可能杀到 FCL 自己） | 代码推演（`adoptOrphan` 有 cmdline 校验，真正动手的 `kill` 没有） | `kill()` 前读 `/proc/<pid>/cmdline`，不含 `proot` 就放手并记日志（读不到时不阻断，保持原行为） | 已修 |
| **R5-07** | **P1** | 兼容/可用性 | `core/DshRuntime.kt:172,452,464` | 启动了 180s 还起不来是这链路最典型的真机故障（部分内核与 proot 的 ptrace/seccomp 加速不兼容，rootfs 内进程刚跑就被信号杀掉），但 `PROOT_NO_SECCOMP` 只在环境变量白名单里留了位，**没有任何地方会设置或重试** | 这类机型上 dsh **永远起不来**，用户只看到"启动失败/超时"，无从自救（路线图 M2 第 1 条） | 路线图 `../ROADMAP.md` M2 待办；proot 文档中该变量即为此用途 | 自动兜底：就绪前退出且（退出码 >128 被信号杀 / 日志含 seccomp·ptrace·bad system call·operation not permitted）→ 自动 `PROOT_NO_SECCOMP=1` 重试**一次**（每实例每次用户启动限一次），重试期间不落 Failed（界面不闪失败）；同时把看门狗改成"新启动前取消旧的"避免重试被提前判超时 | 已修 |
| **R5-08** | **P1** | 状态机/假死 | `core/DshRuntime.kt:317,349`（`adoptOrphan`） | 认领来的进程没有 `Process` 句柄也就没有退出回调；它之后自己退出时状态永远停在 `Running` | App 冷启后看到"运行中"，点开 WebView 只有加载失败，界面上没有任何出口（只能靠"停止"复位）——对"后台挂着跑"的主场景很容易遇到 | 代码推演（`PidHandle` 只有 `isAlive/terminate`，无回调） | 新增 `watchAdopted()`：10s 巡检，**双条件**（`/proc/<pid>` 不存在 **且** 端口已关）才判定结束 → 落 `Exited`、删 pid、收前台服务（双条件是为了避免误判把还活着的实例标死） | 已修 |
| R5-09 | P2 | 竞态/超时 | `core/DshRuntime.kt:172,300` | 兜底重试会**第二个**看门狗，而第一个的 deadline 仍从首次启动时刻算 | 重试起来的进程实际只有 ~170s 就被判超时（正常需要 180s），用户看到"启动超时" | 代码推演（`scope.launch` 无条件起新看门狗） | 保存 `watchdogJob`，每次 `startLocked` 前取消旧的再起新的 | 已修 |
| R5-10 | P2 | 超时/误导 | `assets/dsh/scripts/start-dsh.sh:115-160` | 就绪等待按"轮次"计数，而每轮里 `port_open`（node 连接探测）最多 1.5s + `sleep 1` | 名义 `READY_TIMEOUT=165` 实际会拖到 250s+，**启动器 180s 看门狗先到**，于是失败原因从脚本给出的明确原因退化成模糊的"启动超时" | 代码推演（`elapsed=$((elapsed+1))` 与 sleep 混用） | 改用墙上时间（`date +%s`），`date` 不可用时退化为按轮次近似；`PORT_OPEN_SINCE` 的宽限也改为秒 | 已修（脚本测试 18/18 通过） |
| R5-11 | P2 | 静默失败 | `assets/dsh/scripts/start-dsh.sh:88-90` | 日志文件（`$TMPDIR/dsh-start-$$.log`）写不了时静默：node 输出进不去文件 → 永远抓不到 token URL → 表现为"启动超时" | 一类难查的"启动超时"（真实原因是 /tmp 挂载异常） | 代码推演（`: > "$LOGFILE"` 无检查） | 先探测可写，失败退到实例目录，都不可写就 `FAILED reason=no-writable-logdir` 明确退出 | 已修 |
| R5-12 | P2 | 资源占用 | `core/DshLogBus.kt:196-212` | 日志落盘只在 `attachFile` 时截断一次；同一次进程生命周期内**无上限增长** | agent 长驻几天 → `runtime.log` 可能几百 MB，占满用户存储 | 代码推演（`FILE_MAX_BYTES` 只在 attach 路径生效） | `flush()` 后调用 `rotateIfTooBig()`：超 1MB 只保留尾部一半（读文本 + 丢首行的截断处理，加锁避免与写入交错） | 已修 |
| R5-13 | P2 | 性能/正确性 | `core/DshInstances.kt:167`（`repair`） | 文件 IO（读每个实例的 `package.json`）写在 `_instances.update{}` 的 CAS lambda 里 | 列表并发变化时 lambda 会被重试 → 同样的 IO 重复执行；原子更新循环里持有 IO 时间 | 代码推演（`update` 是 CAS 循环） | 先在更新之外算好"磁盘事实"（`planned: id → (版本, 状态)`），再放进 `update{}` 做纯内存替换；期间新建的实例原样保留 | 已修 |
| R5-14 | P2 | 体验/误导 | `ui/DshWebViewActivity.kt:211` | `port` 还是 0（dsh 未打印真实端口）时拼出 `http://127.0.0.1:0/` 去加载 | 用户看到"页面加载失败"，像是 dsh 坏了 | 代码推演（`running.url ?: "http://127.0.0.1:${port}/"`） | 端口 ≤0 时改为显示"启动中"状态面板，不发起加载 | 已修 |
| R5-15 | P2 | 可移植性 | `assets/dsh/scripts/start-dsh.sh`（`flush_new`） | 用了 `local`（非 POSIX；dash/busybox ash 都支持，但部署到其它 sh 会退化） | 低概率：极端精简 rootfs 的 sh 不支持时，`size` 赋值仍可工作，但语义不干净 | shellcheck 类问题；dash -n/busybox 下均可运行 | 去掉 `local` | 已修 |
| R5-16 | P3 | 死代码 | `core/ProotProcessExecutor.kt`（原文件尾部） | `CoroutineScope.watchdog()` 无人调用（与 `DshRuntime` 的内联看门狗重复） | 维护困惑 | grep 全仓无调用点 | 删除该扩展函数与随之无用的 import | 已修 |
| R5-17 | P3 | 体验 | `core/DshDownloadViewModel.kt:56-57` | 任意实例安装失败时清空**全部**"安装中"角标 | 并发安装两个版本时，另一个的按钮态被重置，可能重复建实例 | 代码推演（`Progress.Failed` 不带实例 id） | 建议：`Progress.Failed` 带上 instanceId 并按版本精确清除（**待办**，改动涉及公开 sealed class 形状） | 待办 |
| R5-18 | P3 | 性能 | `core/DshInstances.kt:85-100`（`create`） | 主线程 `mkdirs()` 三次（极小 IO） | 理论上可忽略；严格说违反"UI 线程不做 IO"约定 | 代码推演 | 建议 mkdir 挪到 IO（**待办**，风险极低但也收益极低） | 待办 |
| R5-19 | P3 | 竞态 | `core/DshRuntime.kt:379`（`stop`） | `stop()` 未加锁，与 `@Synchronized` 的 `startLocked` 并发时，理论上可交错出"Stopping 覆盖 Starting" | 窗口极小（都是 UI 串行触发），未见到实际路径 | 代码推演 | 建议 `stop` 内部对状态读写加 `synchronized(this)`（**待办**） | 待办 |
| R5-20 | P3 | 构建/资源 | `res/values-*/strings.xml`（13 个 locale，46 条字符串） | aapt2 报 `Multiple substitutions specified in non-positional format` | **实测为警告，不阻塞构建**（`mergeDebugResources` / `processDebugResources` / `packageDebugResources` 全部通过） | `/tmp/build1.log`：该消息后任务继续，`BUILD FAILED` 的原因是 Kotlin 编译错误而非资源 | 建议**单独立项**改成位置参数（`%1$s`…）；**不要**用 `formatted="false"`（会让 `getString(id,args)` 的参数失效，界面直接显示 `%s`）。与 dsh 无关，属仓库既有问题 | 已证伪（不阻塞）+ 待办 |

---

## 4. 已完成的修复与优化

按"改了什么 / 为什么 / 影响范围 / 兼容性"归纳。逐条对应 §3 的 ID。

### 4.1 P0：进列表页必崩（R5-01）

* **改了什么**：`dsh_instance_subtitle` 的端口占位符从 `%2$d` 改为 `%2$s`（`values` 与 `values-zh` 各一处）；
  新增 `DshResourceFormatTest` 用真实调用参数跑 `String.format`。
* **为什么**：`DshInstanceAdapter` 传的端口是 `String`（`inst.port.toString()` 或"自动/auto"文案），
  `%d` 收到 `String` 必抛 `IllegalFormatConversionException`。这是**唯一入口页**的绑定必经代码，等于整个启动器一进去就崩。
* **影响范围**：仅这一条文案的显示（端口本就是文本展示，`%s` 语义完全正确）。
* **兼容性**：无数据/接口变化；纯资源文案修正。

### 4.2 P1：迟到退出回调覆盖新实例状态（R5-02）

* **改了什么**：引入 `ownerOf(state)`（返回状态归属的 instanceId）与 `setStateIfOwned(id){…}`；
  `onProcessExit` 开头先判断"当前状态是否属于本实例"，不属于就只删 pid 文件并 return，绝不动 `handle`/`_running`/状态。
* **为什么**：`stop A → start B` 时 A 的退出是异步的、可能迟到；旧代码无条件清句柄会让 B 的 proot 变成停不掉的孤儿。
* **影响范围**：`DshRuntime` 状态机；对"单实例顺序启停"无行为变化，只在"快速切实例"时更稳。
* **兼容性**：状态语义不变（对外仍是同一套 `State`）。

### 4.3 P1：取消安装被误报为损坏（R5-03）

* **改了什么**：新增 `cancelled: MutableSet<String>`；`cancel()` 先打标记再取消协程；任务体在写最终状态前检查该标记，
  取消路径下按磁盘事实决定 READY 或"已取消"，不再无脑 `fail()`。
* **为什么**：协程取消打断不了 `ProcessBuilder` 的阻塞读，任务体会带着 proot 的非 0 退出继续跑到 `fail()`。
* **影响范围**：安装取消路径；正常成功/失败路径不变。
* **兼容性**：状态取值不变（仍是 `NOT_INSTALLED`/`READY`/`BROKEN`）。

### 4.4 P1：并发安装错误串台（R5-04）+ 子进程管理串台（R5-05）

* **改了什么**：`DshInstaller` 的错误摘要/尾巴改为按 instanceId 分表；`ProotProcessExecutor` 的活动子进程从单槽位改为
  `CopyOnWriteArrayList<Active>`，`destroyActive(tag)` 按 tag 精确杀。
* **为什么**：安装器与执行器都是进程级单例，可同时服务两个实例；单例级共享字段/槽位必然串台。
* **影响范围**：并发安装场景更正确；单实例场景无变化。
* **兼容性**：`destroyActive(null)` 语义从"杀当前一个"变为"杀全部"——仅"App 退出清理"这类无 tag 调用点用到，语义更安全。

### 4.5 P1：认领孤儿时误杀无关进程（R5-06）

* **改了什么**：`ProcessUtil.kill()` 在发信号前读 `/proc/<pid>/cmdline`，不含 `proot` 就放手；读不到则保持原行为（不阻断）。
* **为什么**：pid 会被系统复用，"停止认领来的进程"只有一个可能已失效的 pid，误杀风险真实存在（同 uid 下甚至可能杀到自己）。
* **影响范围**：停止/清理路径；正常情况下 cmdline 必含 proot，行为不变。
* **兼容性**：无接口变化。

### 4.6 P1：seccomp 不兼容自动兜底（R5-07）+ 看门狗竞态（R5-09）

* **改了什么**：`start()` 拆成 `startLocked(ctx, instance, noSeccomp)`；就绪前退出且 `looksLikeSeccompTrouble()` 命中时，
  自动以 `PROOT_NO_SECCOMP=1` 重试一次（`seccompFallbackUsed` 保证每实例每次用户启动限一次，不形成重试风暴）；
  看门狗改为成员 `watchdogJob`，每次 `startLocked` 前 `cancel()` 旧的。
* **为什么**：这是未 root 真机上 proot 最典型的"起不来"，路线图 M2 第一条；不自动兜底用户无从自救。
* **影响范围**：仅"就绪前异常退出且像 seccomp 问题"时触发；正常启动零影响。重试期间维持 `Starting`，界面不闪失败。
* **兼容性**：`PROOT_NO_SECCOMP` 早已在环境变量白名单里；无新增权限。

### 4.7 P1：认领进程死亡后假死（R5-08）

* **改了什么**：`adoptOrphan` 成功后起 `watchAdopted()`，10s 巡检，**进程不存在且端口已关**双条件才判定结束并落 `Exited`；
  `startLocked`/`stop` 会取消该巡检。
* **为什么**：`PidHandle` 没有退出回调，认领的进程死了状态会永远停在 `Running`（界面假死）。
* **影响范围**：仅"冷启认领"场景新增一个低频轮询；正常启动的进程走 `onProcessExit`，不受影响。
* **兼容性**：无接口变化。双条件判定避免误判把活着的实例标死（那会更糟）。

### 4.8 P2 批量

* **R5-10/11/15（脚本）**：墙上时间计时、日志目录可写探测与兜底、去 `local`。脚本一致性测试 18/18 仍通过。
* **R5-12（日志滚动）**：`rotateIfTooBig` 每次落盘后按 1MB 上限保尾。
* **R5-13（repair 去 IO）**：磁盘事实预计算，`update{}` 内只做纯内存替换。
* **R5-14（WebView 端口 0）**：不再拼 `:0` 加载，改显示"启动中"。
* **R5-16（死代码）**：删除无用 `watchdog` 扩展函数与 import。

---

## 5. 性能优化

### 5.1 瓶颈分析与本轮措施

| 点 | 问题 | 措施 | 量级 |
|---|---|---|---|
| `DshInstances.repair()` | IO 落在 CAS 重试循环里，可能重复读 N 个 `package.json` | 预计算磁盘事实，`update{}` 只做内存替换 | 冷启校准；N=实例数，去掉"重试×N 次读文件"的放大 |
| 日志落盘 | 单次生命周期无限增长 | 落盘后 1MB 上限滚动 | 长驻场景从"几百 MB"降到"≤1MB" |
| 启动就绪探测 | 按轮次计时导致实际超时被拉长到 250s+ | 墙上时间计时 | 超时判定回到设计的 165s，避免看门狗抢跑 |

> 说明：本轮**没有引入基准测试数字**（沙箱内无真机、无法测端到端时延），以上均为算法/资源层面的定性改进，
> 不虚报性能数据。第四轮已完成的高频日志 O(n²)→合并刷新、SimpleDateFormat 线程缓存等仍然有效。

### 5.2 沿用的既有性能设计（未改动，确认仍有效）

* 日志总线环形缓冲 + ~5Hz 合并刷新 + revision 比较（`DshLogBus`）。
* 列表 `DiffUtil` 增量更新、体积统计带缓存且在 IO（`DshInstanceAdapter`）。
* registry 版本列表 10 分钟内存缓存 + 弱网回退（`DshRegistry`）。
* npm 共享缓存目录（重复安装不重新下载整棵依赖树）。

---

## 6. 可靠性优化

| 维度 | 本轮改动 | 说明 |
|---|---|---|
| 异常处理 | seccomp 兜底重试（R5-07）、日志目录不可写显式失败（R5-11） | 把"沉默的启动超时"变成"可自愈"或"有明确原因" |
| 超时/重试 | 看门狗防重复计时（R5-09）、脚本墙上时间计时（R5-10） | 超时判定准确，重试有边界（每实例每次启动限一次） |
| 幂等 | `cancelled`/`seccompFallbackUsed` 标记集、`SingleFlight`（沿用） | 取消/重试/单飞都幂等 |
| 资源释放 | 迟到回调不误清句柄（R5-02）、认领巡检收尾（R5-08）、日志滚动（R5-12） | 不留停不掉的孤儿、不留假死 Running、不撑爆存储 |
| 误伤防护 | `kill` 前 cmdline 校验（R5-06）、`destroyActive` 按 tag（R5-05） | 不误杀无关进程、不误杀别的实例 |
| 日志/监控 | 迟到回调、放弃清理、兜底重试都记一行到 `DshLogBus` | 事后可追溯；token/key 仍全程脱敏 |
| 配置校验 | 端口 0–65535 校验（沿用）、WebView 端口≤0 不加载（R5-14） | 不产生非法 URL |

---

## 7. 代码质量评估

| 维度 | 评分 | 说明 |
|---|---|---|
| 正确性 | 8/10 | 本轮修掉必崩 P0 与多个并发/状态覆盖 P1 后，主链路逻辑自洽；真机端到端仍未验证（扣分主因） |
| 可读性 | 9/10 | 注释密度高、每处修复都写清"为什么"；状态机集中在 `DshRuntime` 一个文件 |
| 可维护性 | 8/10 | 路径/命令/日志/凭据各自单一职责；`ProotCommand` 是命令唯一构造处；新增契约测试降低回归成本 |
| 性能 | 8/10 | 高频路径（日志/列表/网络缓存）已优化；本轮再去掉 repair 的重复 IO 与日志无限增长 |
| 可靠性 | 7/10 | 生命周期/并发/误杀等纸面缺陷已收敛；但"厂商 ROM 保活""proot×内核兼容"必须真机确认 |
| 安全性 | 9/10 | key 走 Keystore + 只经子进程环境注入 + 日志脱敏；WebView 收紧；`kill` 加身份校验后误伤面进一步缩小 |
| 测试 | 6/10 | 纯逻辑 + 资源契约有 JVM 单测（23 例）与脚本测试（18 例）；但 `DshRuntime`/`Service` 的状态机仍缺可注入的单测（路线图 M4） |

---

## 8. 安全与依赖评估

* **输入校验**：端口范围、实例名非空；registry/DeepSeek 响应用 Gson 容错解析；WebView 只允许回环 URL 留在容器内，其余交系统浏览器。
* **认证授权**：dsh 的 token→cookie 模式由 `DshWebViewActivity` 承接；API Key 用于 DeepSeek 云端，全程不落明文、不进 argv、不进日志。
* **密钥管理**：Android Keystore AES-GCM，AAD=instanceId（防跨实例复用密文）；密文原子写（临时文件 + rename）；历史明文残留主动清理。
* **进程安全**（本轮强化）：`kill` 前 cmdline 校验、`destroyActive` 按 tag、认领前 pid+cmdline+端口三重校验——最大限度避免误杀。
* **网络出站**：仅 npm registry（版本元数据）与 DeepSeek API（用户自带 key），无代码/数据外传。
* **依赖**：本轮**未新增任何生产依赖**。测试侧仅用自带 `org.junit` 桩（无外部 JUnit）。
* **建议升级项**：无强制项。`strings.xml` 的位置参数化（R5-20）建议单独立项，但不影响构建与安全。

---

## 9. 测试与验证

### 9.1 已运行命令与结果

| 验证 | 命令 | 结果 |
|---|---|---|
| 资源处理（基线） | `:FCL:processFordebugResources` | 通过（`strings` 的 non-positional 仅告警，见下） |
| Kotlin 编译 | `:FCL:compileDebugKotlin`（内存参数见 §1.4） | **BUILD SUCCESSFUL**（0 error），本轮共 3 次通过（首次修 P0 后、加认领巡检后、最终） |
| JVM 单测 | `sh /workspace/run-tests.sh` | **23/23 PASS**（原 21 + 新增资源契约 2） |
| 脚本一致性 | `dsh-launcher-poc/scripts/test-scripts-posix.sh`（dash） | **18/18 PASS** |
| 打包（部分） | `:FCL:assembleFordebug` | 见 §9.3：dex 阶段之前的 dsh 相关任务全过；未产出 APK 的原因与 dsh 无关 |

### 9.2 关键证据

* **P0 是真的会崩**：负向对照——把文案改回 `%2$d` 重跑，新测试立即失败并原样打印
  `java.util.IllegalFormatConversionException: d != java.lang.String`（`format="v%1$s · port %2$d · %3$s"`, `args=[0.1.5-rc.2, 3080, READY]`）。改回 `%2$s` 后 23/23 通过。
* **aapt2 non-positional 是告警不是错误**：`/tmp/build1.log` 里 `mergeDebugResources` 打印该消息后，
  `processFordebugResources`/`packageDebugResources` 继续执行并完成；那次 `BUILD FAILED` 的真正原因是一处 Kotlin
  类型不匹配（`String?` vs `String`，已修）。这修正了路线图 M4"strings 资源错误会挡住 assembleDebug"的判断。

### 9.3 未运行项及原因

* **真机端到端**：沙箱无安卓设备，`M1` 仍空白（需先放入 `libproot.so`/`libproot_loader.so` + `rootfs.tar.xz` 两个大文件）。
* **完整 `assembleFordebug` 出 APK**：卡在 `:FCL:configureCMakeDebug[arm64-v8a]`——这是 FCL 既有的 **NDK/CMake 原生构建**
  （与 dsh 无关，`git status` 显示 `src/main/jni` 未被本项目改动），在本沙箱里 CMake 以退出码 132（SIGILL，疑似 qemu 下的指令兼容）失败。
  绕过该原生任务后，构建推进到 `dexBuilderFordebug`（dsh 的 Kotlin/资源/合并全过），因超时（沙箱 CPU 慢）未跑到落 APK。
  **结论：dsh 侧代码与资源已可编译、可打包；出 APK 需在正常电脑（Android SDK+NDK）上执行。**
* **单测跑法说明**：Gradle 的 `testFordebugUnitTest` 在离线沙箱会卡在 ksp，故用 `kotlin-compiler-embeddable` 直接编译测试 +
  自带 `org.junit` 桩 + `MiniRunner` 反射跑（脚本见 `/workspace/run-tests.sh`）。这是环境权宜，不改变测试语义。

### 9.4 建议补充的测试（待办，多属路线图 M4）

* 给 `DshRuntime` 注入"进程/时间/Context"，用纯逻辑单测覆盖：迟到退出回调不覆盖新实例（R5-02）、seccomp 兜底只重试一次（R5-07）、认领巡检双条件（R5-08）。
* 给 `DshInstaller` 用假 `ProotExecutor` 覆盖：取消不误标 BROKEN（R5-03）、并发两实例错误不串台（R5-04）。
* 把三条验证（编译 / 脚本 / 单测）接入 CI。

---

## 10. 变更文件与 diff 摘要

### 10.1 本轮改动文件（均为已跟踪或已存在文件的修改，无新增生产依赖）

| 文件 | 改动 | 关联 |
|---|---|---|
| `core/DshRuntime.kt` | +`ownerOf`/`setStateIfOwned`；`start`→`startLocked(noSeccomp)`；`onProcessExit` 归属校验重写；`watchAdopted` 认领巡检；`kill` 加 cmdline 校验；看门狗防重复 | R5-02/06/07/08/09 |
| `core/DshInstaller.kt` | 错误摘要/尾巴按 instanceId 分表；`cancelled` 标记与取消路径 | R5-03/04 |
| `core/ProotProcessExecutor.kt` | 活动子进程单槽位→`CopyOnWriteArrayList`，按 tag 精确杀；删死代码 `watchdog` | R5-05/16 |
| `core/DshInstances.kt` | `repair()` 磁盘事实预计算，去掉 CAS 循环内 IO | R5-13 |
| `core/DshLogBus.kt` | `rotateIfTooBig` 落盘后按 1MB 滚动 | R5-12 |
| `ui/DshWebViewActivity.kt` | 端口 ≤0 不加载、显示"启动中" | R5-14 |
| `res/values/strings.xml` | `dsh_instance_subtitle` `%2$d`→`%2$s` | R5-01 |
| `res/values-zh/strings.xml` | 同上（中文） | R5-01 |
| `assets/dsh/scripts/start-dsh.sh` | 墙上时间计时、日志目录可写探测与兜底、去 `local` | R5-10/11/15 |
| `test/java/com/dsh/DshResourceFormatTest.kt` | **新增**：资源占位符 ↔ 调用参数契约测试（164 行） | R5-01 |
| `/workspace/run-tests.sh` | **新增**：离线单测一键脚本（仓库外，随文档一起留存） | 测试基建 |

### 10.2 关键 diff（节选，示意语义）

```kotlin
// DshRuntime.kt —— 迟到退出回调不再覆盖新实例（R5-02）
private fun onProcessExit(instance: DshInstance, code: Int) {
    val cur = _state.value
    if (ownerOf(cur) != instance.id) {          // ← 新增归属校验
        ProcessUtil.removePidFile(instance.id)  // 只做无副作用清理
        return                                  // 不动 handle/_running/state
    }
    ...
}
```

```kotlin
// DshRuntime.kt —— seccomp 兜底重试（R5-07），就绪前退出时触发
} else if (cur is State.Starting) {
    if (!retryIfSeccompLooksGuilty(instance, code)) {   // 命中→带 PROOT_NO_SECCOMP=1 重试一次
        setStateIfOwned(instance.id) { State.Failed(instance.id, instance.name, reason) }
    }
}
```

```sh
# start-dsh.sh —— 墙上时间计时（R5-10），避免看门狗抢跑
now_epoch() { n=$(date +%s 2>/dev/null || true); case "$n" in ''|*[!0-9]*) echo "";; *) echo "$n";; esac; }
while : ; do
  NOW=$(now_epoch)
  [ -n "$NOW" ] && [ -n "$elapsed_epoch_start" ] && elapsed=$((NOW - elapsed_epoch_start))
  [ "$elapsed" -lt "$READY_TIMEOUT" ] || break
  ...
done
```

### 10.3 依赖变化

* 新增/删除依赖：**无**。

---

## 11. 风险、兼容性与后续建议

### 11.1 剩余风险

* **真机端到端未验证（最大风险）**：所有生命周期/并发修复都是纸面正确，`M1` 仍需真机点亮。
* **proot × 内核兼容**：R5-07 的自动兜底覆盖"被信号杀 + 关键字命中"的常见情形，但不能保证覆盖所有内核差异。
* **厂商 ROM 保活**：前台服务 `specialUse` 已声明，实际存活行为因 ROM 而异（路线图 M2）。
* **`Process.pid()` 反射**：ART 上仍可能拿不到（已告警）；拿不到时孤儿认领/清理退化。
* **认领巡检双条件**：为避免误杀采取"进程不存在且端口关"才判死；极端情况下（进程已死但端口被别的东西占住）会延迟判定——属"宁可慢、不可误杀"的取舍。

### 11.2 兼容性影响

* 无数据格式变化（`instances.json`/`dsh.pid`/密文格式均不变）。
* 无对外接口签名破坏；`destroyActive(null)` 语义变"杀全部"，仅影响无 tag 的清理调用点，方向更安全。
* 文案 `%2$d→%2$s` 不影响其它语言（其它 locale 的该条目沿用 `%2$s` 结构，测试已覆盖中/英）。

### 11.3 后续建议路线（衔接 ROADMAP.md）

1. **M1 真机点亮**（不变，唯一关键路径）：放两个大文件 → 电脑出 APK → 五步联调。重点观察 R5-07 的 seccomp 兜底是否真的救活。
2. **M2 稳定性**：本轮已把 M2 里"seccomp 兜底""进度串台""认领假死"三项从待办变为已实现，剩"厂商保活""`Process.pid()` 兜底"。
3. **M4 工程化**：补 `DshRuntime`/`Service` 可注入单测；把 §9 三条验证接入 CI；单独立项做 `strings.xml` 位置参数化（R5-20，仅消警告）。
4. **P3 收尾**：R5-17（失败角标带 instanceId）、R5-19（`stop` 加锁）择机处理。

---

## 12. 验收清单

- [x] 项目可编译（`:FCL:compileDebugKotlin` BUILD SUCCESSFUL，0 error）
- [x] 资源可处理（`:FCL:processFordebugResources` 通过；non-positional 为告警不阻塞）
- [x] 关键测试通过（JVM 单测 23/23、脚本一致性 18/18）
- [x] 无新增严重问题（本轮改动均定向、可回滚；未引入新依赖）
- [x] 性能有改善或给出明确结论（repair 去重 IO、日志滚动、就绪计时；不虚报基准数字）
- [x] 可靠性有改善（并发/迟到回调/误杀/假死/兜底重试均已收敛，逐条见 §6）
- [x] 文档完整（本报告 `round5-audit.md`）
- [ ] 核心流程真机可运行（**待 M1**：需放入 proot + rootfs 两个大文件并在电脑上出 APK）
- [ ] 完整 `assembleFordebug` 出 APK（本沙箱受限于 NDK/CMake 与 dsh 无关的原生构建，需在正常电脑执行）

---

> 附：本轮验证脚本 `/workspace/run-tests.sh`（离线跑 JVM 单测）与既有 `dsh-launcher-poc/scripts/test-scripts-posix.sh`
> （脚本一致性）可重复执行。`docs/` 在 FCL 仓库之外，需单独拷走保存。
