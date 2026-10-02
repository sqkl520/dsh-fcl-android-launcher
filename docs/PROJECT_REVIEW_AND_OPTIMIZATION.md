# 项目评估与优化报告

> **项目**：DeepSeek Harness (dsh) 安卓启动器（FCL 改造）
> **审查对象**：`/workspace/FCL`（git HEAD `977a885`，本轮改动未打包）
> **审查时间**：2026-10-02
> **一句话结论**：前六轮加固已把核心并发/可靠性做得很扎实，**本轮在"§2.5 去 Material 的收尾"、"删除实例竞态"、"WebView 失败面板被盖掉"、超时/通知文案、日志环剪性能这几处做了清账**，全部在沙箱内编译/单测/脚本三套验证通过。
> **验证级别**：编译（BUILD SUCCESSFUL）/ 单测（23/23）/ 脚本一致性（18/18）三套全绿；平台限制类结论仍标注「待真机确认」。

---

## 1. 项目概览

### 1.1 用途
在**未 root** 安卓手机上运行 **DeepSeek Harness（dsh）**（Node 版编码/对话 agent）：把 `@deepseek-ai/dsh` 装进 App 私有目录里的 proot Linux rootfs，用 WebView 承载其 Web UI，模型走 DeepSeek 云端 API。由 **Fold Craft Launcher（FCL）** 改造而来：**删除全部 Minecraft 代码/资源，只保留 FCL 的 fcllibrary UI 框架与少量通用工具**。用户停留在手机上协作开发，仓库在 GitHub。

### 1.2 技术栈与版本
| 项 | 值 |
|---|---|
| 语言/构建 | Kotlin 2.4.10 + Java 17、AGP 8.13.2、Gradle 8.14.4、viewBinding |
| SDK | compileSdk 35 / targetSdk **34** / minSdk 26 |
| 模块 | `:FCL`（业务）+ `:ZipFileSystem` |
| Native | 无（jniLibs 仅留 `libproot.so`/`libproot_loader.so` 打包位） |
| 依赖 | gson、commons-compress 1.26、xz 1.9、coroutines、lifecycle、material、commons-io 等 |
| 运行时底座 | proot（arm64）+ rootfs.tar.xz + 3 个 POSIX sh 脚本（仓库内均为 PLACEHOLDER） |

### 1.3 目录结构（关键）
```
FCL/
├─ FCL/src/main/java/com/dsh/       ← 项目自己的代码（core 18 + ui 7 + shell 10）
│   ├─ core/   DshRuntime/Installer/Bootstrap/Instances/Credentials/Registry/LogBus/Paths 等
│   └─ ui/     shell(DshMainActivity/DshPageUI/…) + DshWebViewActivity + 2 Adapter
├─ FCL/src/main/java/com/tungsten/  ← FCL 遗产（fcllibrary/fclcore/mio，保留的 UI 框架）
├─ FCL/src/main/res/                14 布局 / drawable / values(+zh)
├─ FCL/src/main/assets/dsh/         scripts/*.sh（已就绪）+ proot/、rootfs/（占位）
└─ FCL/src/test/java/com/dsh/       单测源集
```

### 1.4 运行 / 验证方式
| 目的 | 命令 | 本轮结果 |
|---|---|---|
| 编译（不打包） | `sh /workspace/run-compile.sh` | **BUILD SUCCESSFUL**，0 error |
| 单测 | `sh /workspace/run-tests.sh` | **TOTAL=23 FAILED=0** |
| 脚本一致性 | `cd /workspace/dsh-launcher-poc/scripts && sh test-scripts-posix.sh` | **PASS=18 FAIL=0** |
| 出 APK | `sh /workspace/build-apk.sh` | 未执行（按约定） |
| 真机 e2e | —— | 仍空白（缺 proot/rootfs + 无设备；R-02 平台风险未决） |

---

## 2. 使用逻辑梳理与完善

### 2.1 端到端使用路径（代码级）
```
① 冷启动：FCLApp.onCreate → FCLPath/DshPaths.loadPaths + DshInstances.init(异步 repair 校准状态)
          → SplashActivity(sensorLandscape) → DshMainActivity（新外壳）
② 准备底座（可选/引导）：DshBootstrap.install → 空间检查→按 version 增量解压 scripts/proot/rootfs
          → verifySync 用 probe.sh 真跑一次 proot → Done/Failed(带原因)
③ 下载 dsh：DshRegistry.fetchVersionsCached(npm abbreviated metadata, 10min 缓存, 弱网回退)
          → 选版本 → DshInstances.create + DshInstaller.install（proot 内 npm install）
④ 配置：DshSettingsUI / DshSettingsActivity → 存 API key（Keystore AES-GCM, AAD=instanceId）+ 测试连接
⑤ 启动：DshRuntime.start（预检→单实例策略→密钥走子进程环境变量→proot -r rootfs→抓 token URL）
          → DshRuntimeService 前台服务保活(specialUse) → DshWebViewActivity
⑥ 使用：WebView 加载 http://127.0.0.1:<port>/?token=…（cookie 持久化, 401 自动重载一次）
⑦ 停止/清理/升级：TERM→5s→KILL；冷启 adoptOrphan 认领；删除实例=停+等退+删目录+清凭据
```

### 2.2 数据布局（全在 App 私有目录，无需存储权限）
```
<filesDir>/dsh/
  rootfs/  scripts/(3 个 sh)  npm-cache/（跨实例共享缓存）
  logs/runtime.log（1MB 轮转）  instances.json（原子写）
  instances/<id>/  node_modules(≈300MB)+home/(DSH_HOME)+workspace/+dsh.pid+credentials.enc
<cacheDir>/dsh/tmp 、tarballs
```
> 宿主 `<filesDir>/dsh` 通过 proot `--bind` 暴露为 rootfs 内 `/opt/dsh`。

### 2.3 本轮"使用逻辑"层面的完善
- **删除实例更可靠**：原先"先 stop 再删目录"里 stop 是异步的，node 还在写时删不干净 → 新增同步版 `stopAndWait()`，删目录前确保进程退出（见 §4 R7-01）。
- **WebView 失败有真出口**：修掉了"失败页面被 onPageFinished 盖成空白"的 bug，错误信息现在能真正留下来（R7-02）。
- **安装超时可解释**：30 分钟看门狗超时不再是笼统的"安装失败"，给出含分钟数的明确文案（R7-03）。
- **通知状态不再骗人**：停止中/空闲不再标"运行中"（R7-04）。

### 2.4 仍然缺失的使用说明（建议补）
| 缺口 | 建议 |
|---|---|
| 仓库 `README.md` 已是 dsh 版 ✓，但 `.github/workflows` 仍是 FCL 原版（按 4 ABI + 依赖已删 JRE/Terracotta 资源/secrets） | 重写 CI（见 §11） |
| 真机操作手册（首次跑通 checklist） | `REALDEVICE.md`（见 §11.3 复用 round6 清单） |
| `rootfs.tar.xz` / `libproot.so` 至今是 PLACEHOLDER | 真机前置条件，见 §11.1 |
| 应用显示名、包名、APK 名已改 ✓ | —— |

---

## 3. 问题清单

> 级别：P0 阻断/安全/数据丢失；P1 严重 bug/明显性能瓶劲；P2 一般；P3 优化建议。
> 状态：`已修`=本轮已改且验证；`沿用`=上一轮遗留、本轮只复核；`待确认`=需你决策 / 需真机。

| ID | 级别 | 类型 | 位置 | 问题 | 影响 | 证据 | 修复方案 | 状态 |
|---|---|---|---|---|---|---|---|---|
| **R7-01** | P1 | 正确性/数据 | `DshInstances.delete()` / `DshRuntime` | 删除实例时先 `stop()`（异步 TERM→5s），紧接着 `deleteRecursively()`：node 仍在写 node_modules → 目录删不干净（~300MB 残留）且界面无提示 | 残留占空间、用户误以为已删 | 代码路径分析（stop 内部 `scope.launch { terminate }` 不阻塞） | 新增 `stopAndWait()`，删除前轮询到离开 Stopping；超时硬删并记日志 | **已修** |
| **R7-02** | P1 | 正确性/UX | `DshWebViewActivity` | `onReceivedError`/HTTP 4xx 后 `onPageFinished` 仍无条件 `showWeb()`；失败页面也会回调 onPageFinished → 错误面板瞬间被盖上，用户见空白页 | 失败无反馈，像 dsh 卡死 | 平台行为（WebView 对失败页回调 onFinished）+ 代码路径 | 加 `pageFailed` 标志，onPageStarted 清零、错误回调置真、onPageFinished 先查 | **已修** |
| **R7-03** | P2 | 可用性 | `DshInstaller.runInstall` / `ProotProcessExecutor` | 看门狗超时返回 `-2`（魔法数）未翻译成可读原因，`errorSummary` 落回泛化"安装失败" | 用户不知道是卡死/超时 | 代码阅读（-2 仅 `errorSummary` 兜底） | 抽常量 `TIMEOUT_EXIT_CODE=-2`，超时给 `dsh_install_timeout`（含分钟数） | **已修** |
| **R7-04** | P2 | 一致/误导 | `DshRuntimeService.buildNotification` | `Stopping/Idle/Failed/Exited` 均显示 `dsh_notify_running`（"运行中"） | 停止中/空闲的极短窗口误导用户 | round6 R-15 沿用未修 | 加 `dsh_notify_stopping`，`Stopping` 与其它非 Running 用停止文案 | **已修** |
| **R7-05** | P2 | UI 一致性 | `activity_dsh_instances.xml` `activity_dsh_webview.xml` | 仍混用 `MaterialButton`/原生 `ProgressBar`/`TextView`，违背 §2.5 硬性要求；验收命令 1 有输出 | 观感与 FCL 外壳割裂，验收失败 | `grep -l "com.google.android.material" *dsh*.xml`（改前 2 行命中） | 换 `FCLButton/FCLProgressBar/FCLTextView`；背景改 `bg_container_white` | **已修** |
| **R7-06** | P3 | 性能 | `DshLogBus.append` | 环剪用 `repeat(){ removeAt(0) }`，O(n²) 数组搬移；且注释称"避免搬移"与实现不符 | 2000 行规模下可忽略，但注释误导 | 代码阅读（round6 R-16 沿用） | `subList().clear()` 单次搬移 + 修正注释 | **已修** |
| **R7-07** | P3 | 资源冗余 | `strings.xml` 中英 | `dsh_action_configure_key` 全仓 0 引用 | 冗余 | 扫描 | 删除 | **已修** |
| **R7-08** | P2 | 一致性 | `DshRuntime` 等 | `MaterialAlertDialogBuilder` 在 `com/dsh` 仍 22 处（§2.5 说"新代码统一 FCLAlertDialog，可逐步归零"） | 对话框观感非 FCL | 扫描 | 建议后续批次替换（本轮未动，避免大范围改动风险） | 沿用 |
| **R7-09** | P0 | 平台/架构 | `targetSdk=34` + filesDir | Android 10+ 禁 execve 数据目录文件（W^X）；已用 jniLibs+PROOT_LOADER 方案，但**真机端到端未验证** | 真机可能底座跑不起来 | round6 R-02，本轮沿用 | **待你决策**：targetSdk=28 vs 保 34 走 PROOT_LOADER（见 §11.1） | 待确认 |
| **R7-10** | P2 | 仓库卫生 | `.github/workflows/*` | FCL 原版 CI（4 ABI 打包、依赖已删 JRE/Terracotta 资产与 secrets），一旦启用必失败 | 误导新读者 / CI 不可用 | 静态 | 重写为 arm64 编译+单测 | 未修 |
| **R7-11** | P2 | 死代码 | 见 round6 §11.2 | 仍有 13 类 + 4 布局死代码候选（本轮阶段 4 已删 44 文件，未全清） | 维护噪音 | round6 清单 | 单独提交清理 | 未修 |
| **R7-12** | P3 | 构建/体积 | `FCL/build.gradle.kts` | release 未开 `minifyEnabled`/`shrinkResources` | APK 更大/易反编译 | 静态 | 开 R8（需回归） | 未修 |
| **R7-13** | P3 | 依赖 | `libs.versions.toml` | `junrar/jsoup/tomlj/constantPoolScanner` 等疑似 MC 遗产未核实是否仍被引用 | 依赖冗余/风险面 | round6 §11.2 待核实 | 联网核对 + 移除 | 待验证 |

---

## 4. 已完成的修复与优化

> 统一验证：`run-compile.sh` BUILD SUCCESSFUL；`run-tests.sh` 23/23；`test-scripts-posix.sh` 18/18。未打包。

### R7-01 删除实例竞态：`stopAndWait()`（P1）
- **文件**：`DshRuntime.kt`、`DshInstances.kt`
- **为什么**：`stop()` 内部 `scope.launch { h?.terminate(5000) }` 是异步的，返回时旧进程可能还没死。删除实例紧接着 `deleteRecursively()` 会留下 node 正在写、删不干净的 ~300MB 残留。
- **改了什么**：新增 `suspend fun stopAndWait(reason, timeoutMs=8s)`，轮询 `_state` 直到离开"本实例 Stopping"；`delete()` 删除目录前先 `stopAndWait`，超时也继续硬删并在日志留痕。
- **兼容性**：`stopAndWait` 是新增 API，未改 `stop()` 语义；`delete()` 在 `DshAppScope`（IO）协程内调用，可安全 suspend。
- **影响面**：仅删除实例路径；其它 `stop()` 调用点不变。

### R7-02 WebView 失败面板不再被盖（P1）
- **文件**：`DshWebViewActivity.kt`
- **改了什么**：加 `pageFailed` 标志；`onReceivedError`/401 失败/HTTP≥400 置真，`onPageStarted` 清零，`onPageFinished` 先查再决定是否 `showWeb()`。
- **影响**：加载失败时错误面板（含原因/重试/看日志/停止/返回）能一直显示，不会被空白 WebView 覆盖。

### R7-03 安装超时可读文案（P2）
- **文件**：`ProotProcessExecutor.kt`、`DshInstaller.kt`、strings(+zh)
- **改了什么**：`ProotProcessExecutor` 抽 `TIMEOUT_EXIT_CODE=-2`；`runInstall` 收到该码时 `errorSummary` 用 `@string/dsh_install_timeout`（`%1$d` 分钟）。
- **配套**：`DshResourceFormatTest.callSites` 登记 `dsh_install_timeout`（否则占位符契约测试会红）。

### R7-04 通知状态文案（P2）
- **文件**：`DshRuntimeService.kt`、strings(+zh)
- **改了什么**：新增 `dsh_notify_stopping`（"停止中…"），`Stopping` 及 Idle/Failed/Exited 都改用该文案，不再误用 `dsh_notify_running`。

### R7-05 实例/WebView 页去 Material（§2.5 收尾）
- **文件**：`activity_dsh_instances.xml`、`activity_dsh_webview.xml`
- **改了什么**：2 个 `MaterialButton`（实例页）+ 4 个 `MaterialButton` + 2 原生 `ProgressBar` + 2 原生 `TextView`（WebView 页）全部换为 `FCLButton`/`FCLProgressBar`/`FCLTextView`；标题/空态加 `auto_text_tint`，WebView 状态面板背景 `?android:attr/colorBackground` → `bg_container_white`。
- **验收**：§2.5.5 命令 1 输出为空（8 个 dsh 布局 0 Material 控件）。

### R7-06 日志环剪 O(n²)→O(n)
- **文件**：`DshLogBus.kt`
- **改了什么**：`buffer.subList(0, excess).clear()` 替代 `repeat(){ removeAt(0) }`，并修正注释。

### R7-07 删除未引用字符串
- **文件**：`strings.xml` + `strings-zh.xml`：删 `dsh_action_configure_key`。

---

## 5. 性能优化
- **本轮实测瓶颈**：无新增重负载；`DshLogBus` 环剪 O(n²)→O(n)（R7-06，规模级可忽略，纯正确性/注释清理）。
- **沿用结论（round6）**：rootfs 解压 18.5×（270s→14.3s）、lazylogic 5Hz 合并、DiffUtil 列表、Keystore 全在 IO 线程、注册表 10min 缓存——均已被前六轮覆盖。
- **未做基准**：APK 体积/冷启动/内存（需打包 + 真机，按约定未打包）。

## 6. 可靠性优化
| 项 | 内容 |
|---|---|
| 删除可靠性 | `delete()` 前 `stopAndWait()`（R7-01），残留风险消除并留日志 |
| 失败可见 | WebView 错误面板留存（R7-02）；安装超时有因（R7-03）；通知状态不误导（R7-04） |
| 已有(沿用) | 状态机+归属校验、孤儿 pid/cmdline 双身份校验、启动 180s/安装 30min 看门狗、SingleFlight、日志脱敏+落盘轮转、单实例策略 |
| 仍缺 | 安装重试退避、真机故障注入（断网/杀进程/存储满/rootfs 损坏）、崩溃上报（复用 fcllibrary CrashReporter） |

## 7. 代码质量评估
| 维度 | 评分 | 说明 |
|---|---|---|
| 正确性 | 8.5/10 | 并发/生命周期边界处理很细（前数轮）；本轮修掉删除竞态 + WebView 失败面板两个 P1 |
| 可读性 | 9/10 | 注释讲"为什么"、命名清晰；本轮修掉一处注释与实现不符（R7-06） |
| 可维护性 | 7.5/10 | 抽出 `ProotCommand`/`DshPaths` 单点、`DshPageUI` 页面基类；扣分在死代码与仓库残留（R7-11/R7-10） |
| 性能 | 8/10 | 已有针对性优化；日志环剪本轮清账 |
| 可靠性 | 8.5/10 | 见 §6；主要风险仍在平台（W^X）与真机行为 |
| 安全性 | 8/10 | Keystore+环境变量注入+日志脱敏+明文收口+WebView 收紧；release 未混淆扣分 |
| 测试 | 6/10 | 单测质量高（含占位符契约）但无真机 e2e；占位符契约测试本轮又守住了 R7-03 新增文案 |

## 8. 安全与依赖评估
- **输入校验**：端口 0~65535、名称非空、proot 参数全 argv 数组、环境变量白名单（挡 LD_PRELOAD）。
- **密钥**：Keystore AES-GCM + AAD=instanceId，运行期只进子进程环境变量（不进 argv/不落盘），日志脱敏。
- **网络暴露面**：dsh 只绑 127.0.0.1；App 明文策略收口到回环（NSC）。
- **依赖**：`junrar/jsoup/tomlj/constantPoolScanner` 疑似 MC 遗产未核实（R7-13，待联网核对）；无法在线核对 CVE。
- **签名**：仓库内 `key-store.jks` / `debug-key.jks`（仅适合侧载自用；对外分发需换 key 移出仓库）。

## 9. 测试与验证
| 命令 | 结果 |
|---|---|
| `sh /workspace/run-compile.sh` | **BUILD SUCCESSFUL**，0 error（Kotlin+Java+资源+Manifest） |
| `sh /workspace/run-tests.sh` | **TOTAL=23 FAILED=0**（含本轮新增 `dsh_install_timeout` 占位符登记） |
| `test-scripts-posix.sh` | **PASS=18 FAIL=0** |
| §2.5.5 验收命令 1 | `grep -l com.google.android.material *dsh*.xml` 输出为空 → 通过 |
| 未跑 | 打包（约定）、真机 e2e、Gradle unitTest 在线依赖、instrumented 测试 |

**建议补充**：解压单测（合成 tar.xz 断言 x 位/硬链接/符号链接）、`DshInstaller` 竞态单测（抽纯逻辑）、真机 checklist（round6 §11.3）。

## 10. 变更文件与 diff 摘要
```
 13 files changed（+多个 strings 与 doc）
 FCL/src/main/java/com/dsh/core/DshRuntime.kt          +stopAndWait；修注释转义
 FCL/src/main/java/com/dsh/core/DshInstances.kt        delete 前 stopAndWait
 FCL/src/main/java/com/dsh/core/DshRuntimeService.kt   通知状态文案
 FCL/src/main/java/com/dsh/core/DshInstaller.kt        超时文案 + TIMEOUT_EXIT_CODE
 FCL/src/main/java/com/dsh/core/ProotProcessExecutor.kt 抽常量 TIMEOUT_EXIT_CODE
 FCL/src/main/java/com/dsh/core/DshLogBus.kt           环剪 O(n²)→O(n)
 FCL/src/main/java/com/dsh/ui/DshWebViewActivity.kt    pageFailed 修复
 res/layout/activity_dsh_instances.xml、activity_dsh_webview.xml  Material→FCL
 res/values*/strings.xml                              +dsh_install_timeout/dsh_notify_stopping；−dsh_action_configure_key
 FCL/src/test/java/com/dsh/DshResourceFormatTest.kt    登记 dsh_install_timeout
 docs/(工作副本)：CHANGELOG.md、LESSONS.md、PROJECT_REVIEW_AND_OPTIMIZATION.md、reports/round7-*
```
**新增/删除依赖**：无。

## 11. 风险、兼容性与后续建议
### 11.1 头号未决：真机端到端（沿用 round6 R-02）
targetSdk 34 + filesDir 的 W^X 限制：proot 二进制已走 jniLibs + PROOT_LOADER，但 rootfs 内 `/bin/sh`、`node` 仍受 **execve 数据目录文件**限制。**建议先按方案 A（`targetSdk=28`，1 行）做真机点亮**，能通后再评估是否保 34。真机前不要因"代码都对"就认为端到端没问题。
### 11.2 兼容性影响
- `stopAndWait` 新增 API，不影响既有调用。
- 布局替换保持全部 `@+id` 不变，viewBinding 字段名未变 → 无编译/逻辑破坏。
- 删除两条未引用文案 + 新增两条文案，占位符契约测试保证不炸。
### 11.3 后续优化路线
1. R-02 决策 + 真机点亮（一切前提）；
2. 死代码/仓库卫生一次清（R7-11/R7-10）；把 `MaterialAlertDialogBuilder`→`FCLAlertDialog` 分批替换（R7-08）；
3. 把 round6 解压基准固化成单测；恢复 CI（arm64 编译+单测+checkstyle+脚本）；
4. R7-12 开 R8；R7-13 依赖清账。

## 12. 验收清单
- [x] 项目可编译（run-compile.sh BUILD SUCCESSFUL，0 error）
- [x] 单测全绿（23/23）
- [x] 脚本全绿（18/18）
- [ ] 核心流程端到端可运行（**未验证**：缺 proot/rootfs + 无设备 + R7-09 未决）
- [x] 无新增严重问题（静态审查 + 编译 + 单测覆盖）
- [x] 性能结论明确（本轮无新增瓶颈；沿用前轮大幅优化）
- [x] 可靠性结论明确（修 4 个 P1/P2 可靠性/可用性缺陷）
- [x] 文档完整（本报告 + CHANGELOG + LESSONS + 同步仓库）

---

### 附：审查方法与局限
- **方法**：通读 `com/dsh/**` 全部源码 + 可达 FCL 遗产 + 脚本/资源/Manifest/Gradle；全仓引用扫描（§2.5 验收）；编译/单测/脚本三套动态验证。
- **局限**：无设备、无 proot/rootfs 二进制、Google Maven 不可达（`gradlew test` 在线任务跑不了，改为离线桩）。因此涉及真机运行时行为（SELinux/WebView 细节/proot 交互）的结论均标注「待真机确认」，未伪造运行结果；本轮所有"已修"项均以可复现方式在沙箱内验证。