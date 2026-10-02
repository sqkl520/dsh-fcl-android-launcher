# dsh 启动器 第四轮代码审计与修缮报告

日期：2026-09-23
范围：`FCL/src/main/java/com/dsh/**`（第三轮后 24 个 Kotlin 文件 + 本轮新增 1 个 = 25 个 ≈4600 行）、`FCL/src/main/assets/dsh/scripts/*.sh`、相关资源与测试
基线：第三轮审计修缮（见 `round3-audit.md`）之后的代码
产出：**发现并修复 8 个 P0/P1 崩溃与可靠性缺陷 + 6 个 P2 性能/质量缺陷**，新增 `core/SingleFlight.kt` 与 2 个可回归单测

---

## 0. 结论摘要（TL;DR）

| 项目 | 结论 |
|---|---|
| 架构设计 | **依然良好**。三轮加固后的分层（core/ui 分离、ProotCommand 单一命令构造处、进程级作用域、StateFlow 驱动）没有结构性问题，本轮不动架构 |
| **最严重问题** | **1 个必崩缺陷**：前台服务被 `startForegroundService()` 拉起后，若"实例此刻刚好已退出"，代码没调用 `startForeground()` 就 `stopSelf()`，Android 8+ 会抛 `ForegroundServiceDidNotStartInTimeException` **直接崩溃**（不是 ANR）。这是"启动即失败/秒退"这条边界路径上的定时炸弹 |
| 可靠性 | 修复后**明显改善**：消除了 1 处必崩、1 处协程泄漏、2 处状态覆盖竞态、1 处安装永久卡死、1 处多实例误杀 |
| UI 正确性 | 修复了一个**很影响体验**的 bug：列表的"运行/删除中"是 Adapter 自己的字段，不在 `DshInstance` 里，DiffUtil 比较不到 → 点了启动按钮文字不变、也无法从列表停止 |
| 性能 | 3 处主线程 Keystore/加解密移出、1 处高频对象分配消除、1 处潜在线程泄漏堵住 |
| 验证 | 全量 Kotlin 编译 **BUILD SUCCESSFUL（0 error）**；单测 **21/21 通过**（新增 2 个）；脚本一致性 **18/18** |

**一句话**：第三轮解决了"能不能在真机上跑起来"（脚本解释器）；这一轮解决的是"**跑起来之后在各种边界与并发下会不会崩、会不会卡死、会不会串台**"。

---

## 1. 评估方法与可信边界

本环境是**离线 arm64 proot 沙箱**，无真机。采用与前几轮一致的替代验证链，并明确每条结论的证据强度：

| 手段 | 覆盖内容 | 证据强度 |
|---|---|---|
| Gradle 全量 `:FCL:compileDebugKotlin`（Android SDK35 + NDK27 + JDK17，`GRADLE_OPTS` 限制堆内存 + `--offline -Darch=arm64`） | 全部 25 个文件在**真实 Android 依赖**下的语法/类型/API 正确性 | 强（BUILD SUCCESSFUL，0 error） |
| `kotlin-compiler-embeddable` 独立编译测试文件 + 桩 JUnit + 反射 runner | 21 个纯逻辑用例（含本轮新增的 SingleFlight 并发 2 例） | 强（PASS=21 / FAIL=0） |
| 真实 dash 跑脚本 + 桩 node/npm（`test-scripts-posix.sh`） | 脚本在目标解释器下可运行、就绪标记、信号转发 | 强（PASS=18 / FAIL=0） |
| 人工代码走查（生命周期、并发、线程归属） | 崩溃/竞态/泄漏这类"编译器查不出、单测难覆盖"的缺陷 | 中（推理 + 交叉核对 Android 文档语义） |
| **未覆盖** | 真机前台服务在各厂商 ROM 上的实际行为、Keystore 实际耗时、WebView 交互 | **弱 —— 见第 6 节** |

> 说明：本轮多数缺陷属于"生命周期时序 / 并发 / 线程归属"，它们**在编译期与常规单测里都不可见**，主要靠人工走查 Android 契约（如"被 startForegroundService 拉起必须 5 秒内 startForeground"）发现。已能纯逻辑化的部分（单飞闸门）补了可回归单测。

---

## 2. P0/P1 缺陷（崩溃与可靠性）

### P0-1 前台服务"被拉起却没 startForeground 就自停"→必崩

- **位置**：`core/DshRuntimeService.kt` `onStartCommand`
- **现象**：用户点启动 → App 调 `startForegroundService()` 拉起 `DshRuntimeService`；如果此刻实例**刚好已经退出或启动失败**（快速失败、被别的实例顶掉、进程刚被系统回收后重放 intent 等），服务进 `onStartCommand` 时看到状态不是 Starting/Running，于是**直接 `stopSelf()` 返回，全程没有调用 `startForeground()`**。
- **根因**：Android 8.0 起有硬性契约——**被 `startForegroundService()` 拉起的服务必须在 ~5 秒内调用 `startForeground()`**，否则系统抛 `ForegroundServiceDidNotStartInTimeException` 让 App **崩溃**（注意：是崩溃，不是 ANR，也不是静默失败）。原代码的"提前 return"分支正好走在这条契约的对立面。
- **为什么前几轮没发现**：前几轮验证的是"正常启动"路径，正常路径最后一定会 `startForegroundCompat(...)`。这个 bug 只在"拉起服务"和"实例消失"这两件事赛跑、且后者赢了时才触发，属于**边界时序**。
- **修复**：`onStartCommand` 的每一条返回路径**在 `stopSelf()` 之前都先调一次 `startForegroundCompat(buildNotification(...))`**，满足契约后再收尾。

```kotlin
if (st !is DshRuntime.State.Starting && st !is DshRuntime.State.Running) {
    // ★ 即使马上要停，也必须先 startForeground 满足"5 秒内"契约，否则系统判定崩溃
    startForegroundCompat(buildNotification(this, st))
    stopSelfSafely()
    return START_NOT_STICKY
}
```

### P1-2 服务的状态观察协程永不取消 → 协程泄漏 + 拽住已销毁的 Service

- **位置**：`core/DshRuntimeService.kt` `onCreate`
- **现象**：`onCreate` 里 `DshAppScope.scope.launch { DshRuntime.state.collect {...} }` 挂在**进程级**作用域上。服务每次"起→停"都会新建一个这样的收集器，但**从不取消**：多次启停后 `DshRuntime.state` 上挂着一堆僵尸收集器，每个都通过闭包引用着一个**已经 `onDestroy` 的 Service 实例**，既泄漏内存，又让"状态一变、N 个旧收集器同时 `stopSelfSafely`"产生不必要的抖动。
- **根因**：把"与某个 Service 实例生命周期绑定"的协程挂到了"进程级、永不结束"的作用域上，却没有留取消句柄。
- **修复**：用字段 `stateWatcher: Job?` 持有该协程，`onCreate` 里先 `cancel()` 旧的再启新的，`onDestroy` 里 `cancel()` 并置空。

### P1-3 列表运行/删除状态变化不刷新 → 按钮点不动、无法停止

- **位置**：`ui/DshInstanceAdapter.kt` `submit`
- **现象**：点"启动"后，列表里那一行的按钮文字仍是"启动"（应变"停止"），因而**无法从列表停止正在跑的实例**；"删除中"的行也不显示"删除中"、不禁用按钮。
- **根因**：`runningId` / `deleting` 是 **Adapter 自己的字段**，不属于 `DshInstance`。而 `submit` 只用 `DiffUtil` 比较**实例对象的内容**——当"哪个在跑"变了但实例列表本身没变时，DiffUtil 判定"内容相同"，**不发出任何更新**，于是那几行永远不重绑。
- **修复**：`submit` 记录 `prevRunning`/`prevDeleting`，DiffUtil 派发之后，对"运行状态变化涉及的行"和"删除集合变化涉及的行"显式 `notifyItemChanged`。既保留 DiffUtil 的增量动画，又保证这两类"外挂状态"能驱动重绘。

### P1-4 安装单飞是 check-then-act + 登记晚于启动 → 可并发写坏 / 永久卡死

- **位置**：`core/DshInstaller.kt` `install`
- **现象**：两个入口（下载页、列表页"重装"）几乎同时点同一实例，两边都看到"没人在装"→ **两个 `npm` 同时写同一个 `node_modules`**，装出半损坏的依赖树。更隐蔽的是：任务若**很快失败**（预检不过，几百毫秒就结束），`finally` 里的 `running.remove(id)` 可能**先于** `running[id] = job` 执行，随后把一个**已经结束的 job**写回表——`isInstalling(id)` 从此**永远为真**，该实例之后所有安装请求都被"已有任务在进行"挡掉，只能杀进程重启。
- **根因**：`if (running.containsKey) return` 与 `running[id]=job` 之间非原子；且登记发生在 `launch{}` 之后（任务体可能已经跑完）。
- **修复**：新增 `core/SingleFlight.kt`，用 `ConcurrentHashMap.putIfAbsent` 把"查+登记"合成一次**原子 CAS**；协程用 `CoroutineStart.LAZY` 创建，**先登记进表、再 `job.start()`**，杜绝"任务体先于登记完成"。`running` 表退化为仅供"取消时找到 job"用。

### P1-5 `destroyActive()` 无差别杀 → 多实例并发装时"取消 A 杀了 B"

- **位置**：`core/ProotProcessExecutor.kt` `destroyActive`、`core/DshInstaller.kt` `killActive`
- **现象**：同时安装两个实例时，取消 A 的安装会把 B 的 `npm` 一起杀掉（B 日志出现莫名中断、状态卡在 INSTALLING）。
- **根因**：`activeProcess` 是单例字段，`destroyActive()` 无条件杀"当前活跃进程"，不区分归属。
- **修复**：`run(...)` 增加 `tag`（传 `instanceId`）参数，活跃进程记为 `Active(tag, process)`；`destroyActive(tag)` **只在 tag 匹配时才杀**（不传 tag 的旧调用点行为不变，仍杀当前）。

### P1-6 `onProcessLine` 刷新 URL 未校验 instanceId → 旧实例覆盖新实例地址

- **位置**：`core/DshRuntime.kt` `onProcessLine`
- **现象**：切换实例（停 A、马上起 B）时，A 在被 SIGTERM 杀死前打印的 URL，可能把 B 的 `url/port` 覆盖成 A 的，WebView 于是连到**已经关闭的端口**。
- **根因**："已 Running 时只刷新 url"这条分支只判了 `cur is State.Running`，**没判 `cur.instanceId == instance.id`**。第三轮已把"状态跃迁先校验 instanceId"定为约定，这一处当时漏了。
- **修复**：该分支加 `&& cur.instanceId == instance.id`。

### P1-7 `adoptOrphan` 认领期间无条件写状态 → 覆盖用户刚启动的实例

- **位置**：`core/DshRuntime.kt` `adoptOrphan`
- **现象**：冷启动时"认领孤儿进程"是异步的（读 `/proc` + 探端口都要时间）。这期间用户完全可能已手动启动别的实例；认领成功后仍无条件写 `_state`，界面显示 A 在跑、其实 B 在跑。
- **修复**：探测通过后、写状态前，先判 `if (runningInstanceId() != null) return false` 放弃认领，并清掉上一轮的 `stopRequestedFor` 标记。

### P1-8 `start()` 非原子 → 两入口同时起两个 300MB 进程

- **位置**：`core/DshRuntime.kt` `start`
- **现象**：列表页按钮与下载页"装完引导启动"几乎同时进来，两边都看到"没有实例在跑"，于是**同时 fork 两个 node**，后写的 pid 文件覆盖前一个 → 前一个进程谁也认领不了、也杀不掉（300MB 常驻泄漏）。
- **根因**：`start()` 是"查状态→清孤儿→fork/exec"的复合操作，中间还夹着 Keystore 解密等耗时步骤，全程无锁。
- **修复**：`start()` 标注 `@Synchronized`。第二个调用会看到 `Starting` 并直接复用/让位。

---

## 3. P2 缺陷（性能与质量）

| 编号 | 问题 | 位置 | 修复 |
|---|---|---|---|
| P2-1 | **点"启动"在主线程做 Keystore 解密**（判定凭据状态），部分机型明显掉帧 | `ui/DshInstancesActivity.startInstance` | `DshCredentials.status(...)` 移到 `Dispatchers.IO`，回主线程再弹对话框 |
| P2-2 | **保存/测试 Key 在主线程做 Keystore 加解密**（首次保存还会生成主密钥，更慢） | `ui/DshSettingsActivity` | `save`/`load` 移入 IO，按钮期间禁用，避免重复提交 |
| P2-3 | **日志每行都 `new SimpleDateFormat(...)`**：npm 安装每秒上千行输出下是纯浪费；且 SDF 非线程安全 | `core/DshLogBus.append` | 改 `ThreadLocal<SimpleDateFormat>` 按线程缓存复用 |
| P2-4 | **`withTimeout { future.get() }` 挡不住阻塞线程**：`withTimeout` 只取消协程，打断不了卡在 `get()` 的线程；而 FCL 的 `HttpRequest` 没设 connect/read 超时，弱网下该线程被**永久占住**（线程泄漏，越试越少） | `core/DshDownloadViewModel.refresh` | 改 `future.get(timeout, MILLISECONDS)`，超时后 `future.cancel(true)` |
| P2-5 | **`sizeCache` 放在 companion 里当全局静态缓存**：删掉的实例条目永久留存（内存缓慢增长），且跨 Activity 实例串用 | `ui/DshInstanceAdapter` | 改为 Adapter 实例字段，随界面回收 |
| P2-6 | **`DshCredentials.save` 非原子写**：写"版本+IV+密文"一整块时被杀 → 留下解不开的半截文件，用户看到一个报错的 key 还以为自己输错了 | `core/DshCredentials.save` | 先写 `.tmp` 再 `renameTo`，保证磁盘上要么旧内容要么完整新内容 |

---

## 4. 逐方面评估（分维度）

| 维度 | 上轮 | 本轮 | 说明 |
|---|---|---|---|
| 架构与分层 | ★★★★☆ | ★★★★☆ | 未动结构；`SingleFlight` 抽成可测小类符合既有风格 |
| 进程/服务生命周期 | ★★★★☆ | ★★★★★ | 补掉了**必崩**的 startForeground 契约缺口与协程泄漏，这是本轮最大价值 |
| 并发正确性 | ★★★★☆ | ★★★★★ | 单飞原子化 + start 加锁 + 两处状态覆盖竞态 + 多实例误杀，全部消除 |
| UI 正确性 | ★★★★☆ | ★★★★★ | 修掉"运行态不刷新导致按钮点不动"这个很影响体验的 bug |
| 性能 | ★★★★☆ | ★★★★☆ | 主线程 Keystore 三处移出、高频分配消除、线程泄漏堵住 |
| 安全 | ★★★★★ | ★★★★★ | 维持高水准；凭据写盘改原子，减少"解不开的密文"误导 |
| 可测试性 | ★★★★☆ | ★★★★☆ | 单飞可单测且已覆盖；生命周期/服务契约类仍靠人工走查（见建议） |

### 一个正面评价
前三轮的方向都是对的，本轮**没有推翻任何设计**，改动全部是"在既定架构内补时序/并发/契约的洞"。尤其"命令构造收敛到 ProotCommand""状态跃迁校验 instanceId"这两条既定约定，让 P1-6 这类问题一眼可辨——**约定本身在发挥作用**。

---

## 5. 验证结果汇总

```
======== 1) Kotlin 全量编译（Gradle, 真实 Android 依赖, 25 文件） ========
./gradlew :FCL:compileDebugKotlin --offline -Darch=arm64  →  BUILD SUCCESSFUL
errors=0（dsh 相关 0 warning；仓库其余模块的既有 deprecation 警告与本轮无关）

======== 2) JVM 单元测试（kotlinc-embeddable + 桩 JUnit + 反射 runner） ========
==== tests: PASS=21 FAIL=0 ====      ← 含本轮新增 2 个 SingleFlight 用例

======== 3) 脚本 POSIX 一致性（真实 dash + 桩 node/npm） ========
==== 结果: PASS=18 FAIL=0 ====
```

### 本轮新增单测

| 用例 | 保护的行为 |
|---|---|
| `singleFlightAcquireReleaseAcquire` | 占坑成功 → 重复占坑必失败 → 释放后能再占；不同 key 互不影响（多实例可并行装） |
| `singleFlightIsAtomicUnderConcurrency` | 16 线程同时抢同一 key，**只能有一个赢家**（直接对应原 check-then-act 竞态） |

---

## 6. 改动文件清单

### Kotlin（7 改 + 1 新增）

| 文件 | 改动 |
|---|---|
| `core/DshRuntimeService.kt` | ★所有返回路径先 `startForeground` 再自停（修必崩）；状态观察协程改 `Job` 持有 + `onDestroy` 取消 |
| `core/DshRuntime.kt` | `start()` 加 `@Synchronized`；`onProcessLine` 刷新 URL 分支校验 instanceId；`adoptOrphan` 认领前判"已有实例在跑则放弃" |
| `core/DshInstaller.kt` | 引入 `SingleFlight` 原子单飞；`launch(LAZY)` 先登记后启动；`killActive`/`run` 传 `tag` 只杀本实例 |
| `core/ProotProcessExecutor.kt` | `run(...)` 增 `tag` 参数；活跃进程记 `Active(tag, process)`；`destroyActive(tag)` 按归属杀 |
| `core/DshLogBus.kt` | 时间戳格式化器改 `ThreadLocal` 缓存 |
| `core/DshCredentials.kt` | `save` 改"临时文件 + rename"原子写 |
| `core/DshDownloadViewModel.kt` | 版本拉取超时改 `future.get(timeout)` + 超时 `cancel(true)`，堵住线程泄漏 |
| `ui/DshInstancesActivity.kt` | `startInstance` 的 Keystore 状态判定移到 IO |
| `ui/DshSettingsActivity.kt` | 保存/测试 Key 的加解密移到 IO，按钮期间禁用 |
| `ui/DshInstanceAdapter.kt` | `submit` 对运行/删除态变化的行显式 `notifyItemChanged`；`sizeCache` 由 companion 静态改实例字段 |

### 新增

| 文件 | 说明 |
|---|---|
| `core/SingleFlight.kt` | 按 key 的原子单飞闸门（`putIfAbsent` CAS），纯逻辑可单测 |

### 测试

| 文件 | 改动 |
|---|---|
| `src/test/java/com/dsh/DshCoreLogicTest.kt` | 新增 2 个 SingleFlight 用例（19 → 21） |

---

## 7. 未修复项与真机待办（风险清单，本轮**未**改）

1. **真机端到端仍是空白**（最高优先级）。待补 `jniLibs/libproot.so` + `libproot_loader.so`（优先）与 `rootfs.tar.xz`（内置 Node），走通"首启解压 → 自检 → 安装 → 启动 → WebView"。
2. **`PROOT_NO_SECCOMP` 兜底未启用**：白名单里有该变量但无人设置。建议做成"启动失败后自动重试一次带该变量的启动"。
3. **`Process.pid()` 反射在 ART 上可能拿不到**：已有告警日志；彻底解决需解析 `/proc/self/task/*/children` 或让 proot 包装脚本回吐 `$$`。
4. **其他语言 `strings.xml` 的 aapt2 编译错误**（**非 dsh 引入，仓库既有**）：`values-de/fa/ja/pt-rBR/ru` 等报 `multiple substitutions specified in non-positional format`，需加 `formatted="false"` 或改位置参数。建议单独立项。
5. **`DshInstaller.progress` 仍是单一全局流**：同时装两个实例时下载页进度提示会混（列表页按实例 id 展示不受影响）。属体验瑕疵。
6. **前台服务在各厂商 ROM 上的保活/被杀行为**：只能真机验证；`specialUse` 声明已就位，但实际策略因厂商而异。

---

## 8. 给下一轮的建议（按性价比排序）

1. **真机跑通端到端**，把"复现生产调用方式"继续作为验证铁律。
2. **给 `DshRuntimeService` 加一层可测抽象**（把"当前状态 / startForeground 调用"注入），让 P0-1 这类**服务契约**缺陷能被 Robolectric 或纯逻辑单测覆盖，而不是只靠人工核对 Android 文档。
3. **给 `DshRuntime` 状态机注入"进程/时间/Context"**，把 P1-6/P1-7 这类竞态变成可单测。
4. 处理第 7 节的 aapt2 资源错误（与 dsh 无关，但会挡住 `assembleDebug`/发布）。
5. `DshInstaller.progress` 从全局改为按 instanceId 分发，消除多实例安装提示混淆。

---

## 附：本地复现命令

```sh
# 1) 全量 Kotlin 编译（需 Android SDK；注意用 GRADLE_OPTS 传 JVM 内存，-X 不能直接当 gradle 参数）
cat > /workspace/run-compile.sh <<'EOF'
export GRADLE_OPTS="-Xmx1300m -XX:MaxMetaspaceSize=450m"
cd /workspace/FCL || exit 1
exec ./gradlew --no-daemon --offline -Darch=arm64 :FCL:compileDebugKotlin
EOF
sh /workspace/run-compile.sh          # BUILD SUCCESSFUL

# 2) 脚本 POSIX 一致性（不需要 Android SDK）
sh dsh-launcher-poc/scripts/test-scripts-posix.sh      # PASS=18 FAIL=0

# 3) 单测（离线沙箱下 Gradle 的 ksp 会卡住，用 kotlinc-embeddable + 桩 JUnit 反射跑；
#    关键：编译器 classpath 必须同时带 kotlin-reflect 与 kotlinx-coroutines-core-jvm）
#    详见本轮记录的完整命令
```
