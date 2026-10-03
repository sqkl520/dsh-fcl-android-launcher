# 项目评估与优化报告（第十轮）

> **项目**：DeepSeek Harness (dsh) 安卓启动器（FCL 改造）
> **审查对象**：`/workspace/FCL`，git HEAD `d82f409`（阶段 A~D 已落地）+ 本轮 6 个文件的改动（未提交、未打包）
> **审查时间**：2026-10-03
> **一句话结论**：前九轮把并发 / 生命周期 / 资源兜底做得很扎实，**但阶段 A~D 引入的新代码带进来 2 个 P1**——① 单测门禁已经**红了**（新增文案未登记进资源契约测试）；② `RuntimeUtils.isLatest` 对**非数字版本号**做 `Long.parseLong`，一旦 classpath 资源可解析就会抛异常（已用 JVM harness 实测复现）。另有 4 个 P2（删除实例误杀别的实例 / JNI 符号名不匹配 / 就绪判据漏执行位 / rootfs 升级失败会连旧的都丢）与 1 个**实测数值错误**（空间门槛比它要保护的 rootfs 还小）。本轮全部修掉，并保持编译 / 单测 / 脚本三套动态验证全绿。
> **验证级别**：`run-compile.sh` **BUILD SUCCESSFUL**；单测 **27/27**（修复前 **26/27，红的**）；脚本一致性 **18/18**；`nm` 校验 JNI 符号；JVM harness 复现旧缺陷；**未打包、未上真机**（平台类结论仍标「待真机确认」）。

---

## 1. 项目概览

### 1.1 用途
在**未 root** 的安卓手机上跑 **DeepSeek Harness（dsh）**（Node 版编码/对话 agent）：把 `@deepseek-ai/dsh` 装进 App 私有目录里的 proot Linux rootfs，用 WebView 承载其 Web UI，模型走 DeepSeek 云端 API。由 **Fold Craft Launcher（FCL）** 改造而来，已删除全部 Minecraft 代码，只保留 fcllibrary UI 框架与少量通用工具。

### 1.2 技术栈与版本
| 项 | 值 |
|---|---|
| 语言/构建 | Kotlin + Java 17、AGP 8.13.2、Gradle 8.14.4、viewBinding、core library desugaring |
| SDK | compileSdk 35 / targetSdk **34** / minSdk 26 |
| 模块 | `:FCL` + `:ZipFileSystem` |
| Native | `src/main/cpp/ptyjni/`（PTY 桥接，CMake + NDK 27，arm64-v8a）；`jniLibs/arm64-v8a/`：`libproot.so`(2.6M) / `libproot-loader.so`(20K) / `libbusybox.so`(1.1M) |
| 运行时底座 | patched proot + `PROOT_LOADER`（绕过 targetSdk≥29 的 W^X）+ Debian bookworm arm64 rootfs + Node 22.23.3 + 预装 `@deepseek-ai/dsh@0.1.6-alpha.2` |
| rootfs 资产 | `assets/dsh/rootfs/rootfs.tar.xz` = 314,360,800 B；**解压后实测 1,607,908,864 B（≈1.50 GiB）**，膨胀约 5.1×；该文件 gitignore，属本地打包输入 |

### 1.3 目录结构（关键）
```
FCL/
├─ FCL/src/main/java/com/dsh/        项目自有代码（34 文件 / 5,705 行）
│   ├─ core/  DshBootstrap / DshRuntime / DshInstaller / DshInstances / DshCredentials /
│   │         DshRegistry / DshDownloadViewModel / ProotCommand / ProotProcessExecutor /
│   │         DshPaths / DshLogBus / DshRuntimeService / PtyNative / SingleFlight …
│   └─ ui/    DshWebViewActivity / DshSettingsActivity / shell(DshMainActivity + 5 页) / Adapter×3
├─ FCL/src/main/java/com/tungsten/    FCL 遗产（fcllibrary / fclcore / fcl 保留部分）
├─ FCL/src/main/cpp/ptyjni/           PTY 原生桥接（MIT，取自 oonid/pr :proot-engine）
├─ FCL/src/main/jniLibs/arm64-v8a/    libproot.so / libproot-loader.so / libbusybox.so
├─ FCL/src/main/assets/dsh/           scripts/*.sh + proot/(占位) + rootfs/(tar.xz + version)
└─ FCL/src/test/java/com/dsh/         单测源集（2 文件 / 27 用例）
```
全仓规模（本轮实测）：`find FCL/src/main/java ZipFileSystem/src -name "*.java" -o -name "*.kt" | wc -l` = **466**；`xargs wc -l | tail -1` = **70,561 行**。

### 1.4 运行 / 验证方式
| 目的 | 命令 | 本轮结果 |
|---|---|---|
| 编译（不打包） | `sh /workspace/run-compile.sh` | **BUILD SUCCESSFUL**（含原生 ptyjni，`libptyjni.so` 36,312 B） |
| 单测 | `sh /workspace/run-tests.sh` | **TOTAL=27 FAILED=0**（修复前 FAILED=1） |
| 脚本一致性 | `sh /workspace/dsh-launcher-poc/scripts/test-scripts-posix.sh` | **PASS=18 FAIL=0** |
| 代码风格门禁 | `./gradlew checkstyle` | **未运行**：离线环境无 `com.puppycrawl.tools:checkstyle:10.12.5` 缓存 |
| 出 APK | `sh /workspace/build-apk.sh` | 未执行（按项目约定，改完代码默认不打包） |
| 真机 e2e | —— | **仍空白**（无设备；W^X / PROOT_LOADER / isLatest 机制待真机确认） |

---

## 2. 使用逻辑梳理与完善

### 2.1 端到端路径（代码级，本轮已随阶段 A~D 更新）
```
① 冷启动：FCLApp.onCreate → FCLPath.loadPaths + DshPaths.loadPaths + DshInstances.init(异步 repair)
          → SplashActivity（只装 FCL 日志，不再申请存储权限）
          → DshMainActivity（横屏外壳：左菜单 + ViewPager2 五页 + 右面板 + ThemeEngine 背景）
② 底座：DshBootstrap.install
          → 建目录 → [脚本：isLatest + start-dsh.sh 存在性兜底]
          → [proot/loader：只校验 jniLibs 是否齐备 + 可执行]
          → [rootfs：isLatest + rootfsLooksUsable 兜底 → 空间检查 → 解压 .tmp → 校验布局 → 原子替换]
          → verifySync()：proot 内跑 probe.sh（9 项：rootfs 布局/node/bash/execSync/spawnSync×2/npm/预装 dsh/native dlopen）
③ 下载 dsh：DshDownloadUI → DshRegistry.fetchVersionsCached(10min 缓存 / 20s 超时 / 断网回退)
          → chooseInstanceToInstall 去重 → DshInstances.create 或复用 + DshInstaller.install
④ 配置：DshSettingsActivity（实例级）→ Keystore AES-GCM 存 API key + 测试连接；
        DshSettingsUI（全局启动器设置，FCL 风格分组行）
⑤ 启动：DshLauncher.startInstance（凭据检查 → 弹窗）→ DshRuntime.start（预检 → 单实例 → proot
          → 抓 `dsh web: http://127.0.0.1:<port>/?token=…`）→ DshRuntimeService 前台服务保活
⑥ 使用：WebView 加载回环 URL（token→cookie，cookie 持久化在 DSH_HOME，可免 token 复进）
⑦ 停止/清理：TERM→5s→KILL；删除实例 = (仅在删的是运行中实例时) stopAndWait + 删目录；
             冷启 adoptOrphan 认领孤儿（pid + cmdline + 端口三条件）
```

### 2.2 本轮在使用逻辑层面的发现与完善
- **完善（R10-01）**：单测门禁从「红」修回「绿」。阶段 D 新增的 `DshSettingsUI.showAbout()` 用了 `R.string.dsh_about_version`（`版本：%1$s`），但**没有登记进 `DshResourceFormatTest.callSites`** —— 该测试的职责正是"带占位符的 dsh 文案必须登记，否则类型不匹配会在真机上闪退（第五轮 P0 就是这么来的）"。门禁失效意味着这一类 P0 又回到了"没人拦"的状态。
- **完善（R10-02）**：首启解压的版本比对不再依赖"版本号必须是纯数字"。`assets/dsh/rootfs/version` 已从 `1` 变成 `debian-bookworm-arm64-node22-dsh0.1.6-alpha.2-layout2`，而 `isLatest()` 仍在 `Long.parseLong` 它。
- **完善（R10-03）**：删除实例不再连带杀掉**别的**正在运行的实例。
- **完善（R10-06）**：rootfs 升级改成"可回滚"，失败不再同时失去新旧两份底座。
- **完善（R10-19）**：空间检查从"常量 1500 MiB"改成"按实测体积 + 本次是否需要留两份"计算，并且**只在真的要解压时才检查**（原来即使 rootfs 已就绪、只是补脚本也会被门槛判失败）。

---

## 3. 问题清单

> 级别：P0 阻断/安全/数据丢失；P1 严重；P2 一般；P3 建议。状态：`已修`=本轮已改并编译/单测验证；`建议`=给方案未动；`待确认`=需真机。
> 「沿用」标注沿用第九轮报告编号。

| ID | 级别 | 类型 | 位置 | 问题 | 影响 | 复现/证据 | 修复方案 | 状态 |
|---|---|---|---|---|---|---|---|---|
| **R10-01** | **P1** | 测试门禁/回归 | `FCL/src/test/java/com/dsh/DshResourceFormatTest.kt:52` | 新增文案 `dsh_about_version`（`版本：%1$s`，`DshSettingsUI` 关于页使用）带占位符但未登记进 `callSites` → 契约测试失败 | 单测 **26/27（红）**；"占位符类型不匹配 → 真机闪退"这类 P0 失去拦截；文档仍宣称 27/27（口径失真） | `sh run-tests.sh` → `TOTAL=27 FAILED=1 ... [dsh_about_version]` | 补登记 `"dsh_about_version" to arrayOf<Any>("0.1.0-SNAPSHOT")` | **已修** |
| **R10-02** | **P1** | 正确性/静默失败 | `com/tungsten/fcl/util/RuntimeUtils.java:69`（旧行号） | `isLatest()` 对 asset 版本号 `Long.parseLong(...)`，而 rootfs 的 version 是语义化字符串 `debian-bookworm-arm64-node22-dsh0.1.6-alpha.2-layout2` | 一旦 `Class.getResourceAsStream("/assets/...")` 在任何环境解析成功：`isReady()` 被 `runCatching` 吞成「永远不就绪」；`install()` 直接把首启解压判为**失败**（底座装不出来）。另：同一 stream 被打开两次，第二次为 null 时 NPE | JVM harness（照抄旧逻辑 + 真实 version 串）→ `OLD -> THREW java.lang.NumberFormatException: For input string: "debian-bookworm-arm64-node22-dsh0.1.6-alpha.2-layout2"` | 改为「字符串相等优先 + 纯数字时保持数值语义」；只读一次流 | **已修** |
| **R10-03** | **P2** | 使用逻辑/误伤 | `com/dsh/core/DshInstances.kt:222` | `delete(id)` 里无条件调用 `DshRuntime.stopAndWait("实例被删除")`；而 `stopAndWait()` 停的是**当前正在运行的那个实例**，与 `id` 无关 | 删除一个**没在跑**的实例 B，会把正在跑的实例 A 一起杀掉：A 的 dsh 会话中断、WebView 断连、前台通知被收（用户只点了"删除 B"） | 代码阅读（`stopAndWait` → `runningInstanceId()`） | 加归属校验：仅 `DshRuntime.runningInstanceId() == id` 时才停 | **已修** |
| **R10-04** | **P2** | 正确性/潜伏崩溃 | `src/main/cpp/ptyjni/ptyjni.c:41,130…193` | JNI 符号名沿用上游 `Java_id_or_oo_pr_engine_PtyNative_*`，而 Kotlin 侧类已改名 `com.dsh.core.PtyNative` | 任何一次 `PtyNative.*` 调用 → `UnsatisfiedLinkError: No implementation found for ...`。当前全仓无调用方，属**潜伏**缺陷（一旦接入 PTY 就必崩） | `nm -D libptyjni.so` 输出 `Java_id_or_oo_pr_engine_PtyNative_nativeForkPty` 等 7 个；对照 `com/dsh/core/PtyNative.kt` 的类名 | 符号名对齐为 `Java_com_dsh_core_PtyNative_*`，并在文件头写明"改包名必须同步" | **已修** |
| **R10-05** | **P2** | 正确性/就绪口径 | `com/dsh/core/DshBootstrap.kt:69` | `isReady()`/`missingSummary()` 对 proot 只查 `isFile`/`exists()`，而真正会失败的 `ProotCommand.preflight()` 查 `exists() && canExecute()` | 二进制存在但无执行位时：横幅隐藏（以为就绪）→ 一点"启动"才报「proot 二进制没有执行权限」。**第九轮修的是同一类"假就绪"，只是漏了执行位这一维** | 代码阅读（对比 `preflight` 与 `isReady` 的条件集合） | `isReady()` 补 `canExecute()`；`missingSummary()` 区分「缺文件」与「无执行位」；`install()` 的 jniLibs 校验同步 | **已修** |
| **R10-06** | **P2** | 数据丢失/可靠性 | `com/dsh/core/DshBootstrap.kt:272` | rootfs 替换是「先 `deleteRecursively()` 旧目录 → 再 `renameTo()` 新目录」 | 两步之间失败（磁盘写满 / 进程被杀 / rename 异常）→ **新旧 rootfs 同时不存在**：升级失败 = 底座全没，用户必须重新首装 300MB+ | 代码阅读 | 改为「旧目录 rename 成 `.old` → tmp 上位 → 校验新内容 → 成功删备份 / 失败回滚」 | **已修** |
| **R10-07** | P2 | 可靠性/数值错误 | `com/dsh/core/DshBootstrap.kt:42`（旧值） | `MIN_FREE_BYTES = 1500 MiB`(=1,572,864,000 B)，而**仅 rootfs 解压后就有 1,607,908,864 B** —— 门槛比它要保护的对象还小；且检查发生在"是否真的需要解压"之前 | ① 空间检查"通过"后照样写满磁盘、留下半成品；② rootfs 已就绪、只是补脚本的场景被"空间不足"**假失败** | `du -s --block-size=1 /workspace/dsh-rootfs-build/rootfs` → `1607908864` | 按实测值给门槛（1.65 GiB + 600 MB node_modules）；**移到真正要解压的分支里**，并按"是否需要留两份"分别计算 | **已修** |
| **R10-08** | P3 | 资源/可维护性 | `com/dsh/core/ProotProcessExecutor.kt:100` | `actives` 只在正常路径清理；读输出抛异常（进程被外部杀 / 管道断裂）时**留下死进程记录** | 长时间运行后 `destroyActive()` 遍历空转；表缓慢增长 | 代码阅读 | 改到 `finally`，且**只清已退出的**（还活着的必须留表，否则取消安装找不到它） | **已修** |
| **R10-09** | P3 | 死代码 | `com/dsh/core/DshBootstrap.kt:290` | `prootDir()` 已无调用方（阶段 A 改成 jniLibs 方案后不再从 assets 解压 proot） | 死代码 | `grep -rn "prootDir()"` | 删除 | **已修** |
| **R10-10** | **P2** | CI/仓库 | `.github/workflows/build.yml` / `release.yml` / `check-codes.yml` | 仍是 FCL 原版：5 ABI 矩阵（`all/arm/arm64/x86/x86_64`）+ `./gradlew assemblerelease -Darch=…`，并依赖 `secrets.FCL_KEYSTORE_PASSWORD` / `CURSE_API_KEY` / `OAUTH_API_KEY` | 本仓库启用必失败：secrets 缺失 → `signingConfigs.FCLKey.storePassword = null` → 构建报错；且本工程只有 arm64 资产（其余 ABI 无意义）；`release.yml` 靠 tag 触发会全红 | 读文件（`build.yml:17-24,41-57`） | 重写为「arm64 编译 + 单测 + checkstyle + 上传 APK」的单 job（见 §11.3） | 建议（沿用 9-04） |
| **R10-11** | **P2** | 安全/供应链 | 仓库根 `key-store.jks` / `debug-key.jks` / `private_key.pepk`；`FCL/build.gradle.kts` release+debug 均 `isMinifyEnabled=false` | 签名私钥与口令随 public 仓库分发；release 未混淆 | 可伪造签名包（供应链投毒）；APK 易被逆向、proot 参数与 API 交互逻辑一览无余 | `git ls-files \| grep -i jks` | 分发用 key 移出仓库、口令走 CI secret；release 开 R8（保留 proot 脚本 assets 的 keep 规则） | 建议（沿用 9-06） |
| **R10-12** | P2 | 可靠性/功耗 | `AndroidManifest.xml:15` + 全仓 | `WAKE_LOCK` 权限已声明但**从未获取**任何 WakeLock | dsh 是长驻 agent：息屏 / Doze 下可能被调度挂起或网络节流，WebUI 表现为"假死" | `grep -rn "PowerManager"` 无结果 | Running 期间持 `PARTIAL_WAKE_LOCK`（退出/停止释放），并评估耗电 | 建议（沿用 9-03） |
| **R10-13** | P3 | 死代码/体积 | `com/dsh/core/PtyNative.kt` + `cpp/ptyjni/` + `jniLibs/libbusybox.so` | PTY 桥接与 `libbusybox.so` 随包编译/打包（约 3.7 MB），但**全仓无调用方**（`ProotRunner` 走 `ProcessBuilder`；`DshPaths.resolveBusybox` 无人调用） | APK 白增体积；"看起来在用"的误导（R10-04 正是因为没有调用方才没暴露） | `grep -rn "PtyNative\|resolveBusybox"` 只命中定义处 | 二选一：接入（终端语义/交互）或移除（省体积）。**决策项** | 建议（待决策） |
| **R10-14** | P3 | 可维护性 | `DshVersionListItem.formatSize` vs `DshPaths.formatSize` | 两份体积格式化实现（`-1`/`<=0` 语义还略有差异） | DRY 违反，改一处漏一处 | 代码阅读 | 合并到 `DshPaths.formatSize` 并统一"未知"语义 | 建议（沿用 9-10） |
| **R10-15** | P3 | 仓库卫生 | `settings.gradle.kts:15` | `rootProject.name = "Fold Craft Launcher"` | 误导（本工程已不是 FCL 启动器；`BuildConfig`/APK 名/IDE 标题都会跟着） | 读文件 | 改为 `dsh-fcl-android-launcher` | 建议 |
| **R10-16** | P3 | 使用逻辑/文案 | `com/dsh/ui/shell/DshDownloadUI.kt:303` | `installVersion()` 命中「已 READY 同版本」会**跳过安装**，但 UI 仍 toast「安装已开始」并弹"去实例页" | 文案与事实不符（当前几乎不可达，因已装版本的按钮会被隐藏） | 代码阅读 | `installVersion` 返回「是否真的发起安装」，据此选择文案 | 建议 |
| **R10-17** | P3 | 性能 | `com/dsh/ui/shell/DshLogsUI.kt:552` | 每次 flush（~5Hz）把最多 2000 行 `joinToString` 一次 | 峰值约 100 KB/次字符串分配 + 一次全量 `setText` | 代码阅读 | 改为增量 append（只追加新增行） | 建议 |
| **R10-18** | P3 | 一致性 | `com/dsh/core/ProotCommand.kt:80` | `preflight()` 用全局 `DshPaths.rootfsLooksUsable()`（看的是 `DshPaths.ROOTFS_DIR`），与入参 `rootfsDir` 可能不是同一个目录 | 传非默认 rootfsDir 时预检结论可能失真（当前只有默认路径在用） | 代码阅读 | `rootfsLooksUsable(dir)` 接受目录参数 | 建议 |
| **R10-19** | **待确认** | 平台/架构 | targetSdk 34 + `filesDir`；`RuntimeUtils.isLatest` 资源机制 | W^X 禁 execve / `PROOT_LOADER` 绕过 / `getResourceAsStream("/assets/...")` 在真机能否解析，**均未在真机验证**（沿用 9-09） | 真机 e2e 仍是最大空白：底座可能跑不起来 | —— | 真机点亮 + 现场确认（ROADMAP M1） | 待确认 |

---

## 4. 已完成的修复与优化

> 全部改动集中在 6 个文件（+163 / −42），均可在沙箱内编译 + 单测验证；不引入新依赖、不改 API/数据格式。

### 4.1 R10-01 资源契约测试补登记（P1）
- **文件**：`FCL/src/test/java/com/dsh/DshResourceFormatTest.kt`
- **为什么**：`DshSettingsUI.showAbout()` 用了 `getString(R.string.dsh_about_version, BuildConfig.VERSION_NAME)`，而该文案带 `%1$s`。契约测试的第二条用例「带占位符的 dsh 文案必须登记」因此失败 —— 这道门禁的价值就是拦住「`%d` 收到 String → 一进页面必崩」那类 P0（第五轮实际发生过）。门禁红着，等于把同类 P0 的护栏拆了。
- **改了什么**：`callSites` 补一行 `"dsh_about_version" to arrayOf<Any>("0.1.0-SNAPSHOT")`（参数类型与调用点一致：String）。
- **兼容性**：测试专用，零生产影响。
- **验证**：`sh run-tests.sh` → `TOTAL=27 FAILED=0`（修复前 `FAILED=1`）。

### 4.2 R10-02 `RuntimeUtils.isLatest` 版本比对加固（P1）
- **文件**：`FCL/src/main/java/com/tungsten/fcl/util/RuntimeUtils.java`
- **为什么**：`assets/dsh/rootfs/version` 已是语义化字符串（`debian-bookworm-arm64-node22-dsh0.1.6-alpha.2-layout2`），旧实现无条件 `Long.parseLong`。Android 上 assets 不在 java classpath、`getResourceAsStream` 返回 null，所以**靠巧合**没炸；一旦机制变化（桌面端、单测、换打包方式、或有人把 assets 加进 classpath），就会：`isReady()` 被 `runCatching` 吞掉 → **永远"需要更新底座"**；`install()` 抛 `NumberFormatException` → **首启解压直接判失败**。另外旧实现把同一 stream 打开两次，第二次为 null 时 NPE。
- **改了什么**：
  1. 只读一次流（`try-with-resources` 内读出 `assetVersion`）；
  2. 比对顺序改为「**字符串相等 → true**」优先，其次才在「两边都是纯数字」时按数值比较（保留 `"007" == "7"` 的历史语义）；
  3. `NumberFormatException` 不再外泄，按「不同版本」处理（→ 触发解压，安全方向）。
  4. 「资源读不到 → 返回 true」的语义**保持不变**（调用方 `DshBootstrap` 已用"目标文件存在性 / 内容可用性"兜底，见 R8-01）。
- **兼容性**：纯数字版本（`scripts/version = 3`）行为完全不变；非数字版本从「抛异常」变成「可正常比对」。
- **验证**：JVM harness（照抄旧逻辑 + 真实 version 串 + 真实 `RuntimeUtils`）：
  ```
  == rootfs (asset version = debian-bookworm-arm64-node22-dsh0.1.6-alpha.2-layout2)
     OLD -> THREW java.lang.NumberFormatException: For input string: "debian-bookworm-..."
     NEW -> true
  == scripts (asset version = 3)
     OLD -> true
     NEW -> true
  ```

### 4.3 R10-03 删除实例不再误杀别的实例（P2）
- **文件**：`FCL/src/main/java/com/dsh/core/DshInstances.kt`
- **为什么**：`DshRuntime` 是**单实例**策略，`stopAndWait()` 内部取 `runningInstanceId()`，与被删的 `id` 无关。原实现无条件调用，于是「删除一个没在跑的实例」= 「把正在跑的那个杀掉」。
- **改了什么**：加归属校验。
  ```kotlin
  val stopped = if (DshRuntime.runningInstanceId() == id) {
      DshRuntime.stopAndWait("实例被删除")
  } else { true }
  ```
- **兼容性**：删的正是运行中实例时行为不变（仍然"停 → 等退出 → 删目录"）；其余情况不再产生副作用。
- **验证**：编译 BUILD SUCCESSFUL；逻辑变更点由代码路径唯一确定（无 Android 设备可做 instrumented 验证，标注「待真机确认」）。

### 4.4 R10-04 JNI 符号名对齐（P2）
- **文件**：`FCL/src/main/cpp/ptyjni/ptyjni.c`
- **为什么**：从 oonid/pr 搬过来的 native 文件属于上游包名 `id.or.oo.pr.engine.PtyNative`，符号名没跟着本项目的 `com.dsh.core.PtyNative` 改。JNI 按「类全限定名 + 方法名」查找实现，对不上就是 `UnsatisfiedLinkError`。目前没有调用方 → 潜伏。
- **改了什么**：7 个符号 `Java_id_or_oo_pr_engine_PtyNative_*` → `Java_com_dsh_core_PtyNative_*`；文件头加注释说明"改包名/类名必须同步"。
- **兼容性**：无调用方，运行时零影响；未来接入 PTY 时才生效。
- **验证**：`nm -D --defined-only /tmp/ptyjni-check/libptyjni.so` 输出 7 个 `Java_com_dsh_core_PtyNative_*`；`run-compile.sh` 的 ptyjni 步骤 `OK: 36312 bytes`。

### 4.5 R10-05 就绪判据补上"执行位"这一维（P2）
- **文件**：`FCL/src/main/java/com/dsh/core/DshBootstrap.kt`
- **为什么**：第九轮把 `isReady()` 对齐到 `preflight()`，但 `preflight()` 查的是 `exists() && canExecute()`，`isReady()` 只查 `isFile` —— 判据仍**弱于**执行入口，"假就绪"还剩一维没堵（无执行位时横幅隐藏、一启动才报错）。
- **改了什么**：抽出 `prootBinReady()`（`isFile && canExecute()`）与 `prootLoaderReady()`；`isReady()` 用它们；`missingSummary()` 把「缺文件」与「无执行位」分成两句可操作文案；`install()` 的 jniLibs 校验同步（`libproot.so（无执行位）`）。
- **兼容性**：jniLibs 方案下 nativeLibraryDir 自带执行位 → 正常设备零行为变化；只有真的打包异常时才从"静默假就绪"变成"横幅明确提示"。
- **验证**：编译 BUILD SUCCESSFUL；单测 27/27。

### 4.6 R10-06 rootfs 替换改为可回滚（P2，数据丢失面）
- **文件**：`FCL/src/main/java/com/dsh/core/DshBootstrap.kt`（`extractRootfs`）
- **为什么**：原顺序是「删旧 → 改名新」。这两步之间失败就等于**新旧都没了**。rootfs 是 300MB 级资产、且是唯一底座，这种失败代价太高。
- **改了什么**：
  ```
  旧 rootfs --rename--> rootfs.old
  rootfs.tmp --rename--> rootfs        （失败则退化为"逐个移动内容"）
  looksUsable(新) ? 删掉 .old  : 回滚（删新、把 .old 改回来）
  ```
  挪不动旧目录时（少见）退化为原行为并打日志。
- **峰值磁盘占用**：与旧实现**相同**（旧实现在解压 `rootfs.tmp` 时旧目录仍在，峰值同样是「旧 + 新」≈2 份）。改名是 O(1)，不额外复制。
- **兼容性**：成功路径结果一致（rootfs 就位、`version` 写入、`loadPaths` 重建目录）；失败路径从"全丢"变成"回到旧版可用"。
- **验证**：编译 BUILD SUCCESSFUL；`run-compile.sh` 无告警；分支逻辑由代码路径唯一确定。

### 4.7 R10-07 空间门槛按实测值给，并且只在真要解压时检查（P2）
- **文件**：`FCL/src/main/java/com/dsh/core/DshBootstrap.kt`
- **为什么**：两个独立缺陷叠在一起。
  1. **数值错**：`MIN_FREE_BYTES = 1500 MiB = 1,572,864,000 B`，而 `du -s --block-size=1 rootfs` = **1,607,908,864 B**。门槛比被保护对象还小 → "检查通过"之后照样写满。
  2. **位置错**：检查放在 `install()` 最前面，早于"这次到底要不要解压"的判定 → rootfs 已就绪、只是补脚本的场景也会被"空间不足"判失败（假失败）。
- **改了什么**：
  ```kotlin
  private const val ROOTFS_EXTRACTED_BYTES = 1650L * 1024 * 1024   // 实测 1.50 GiB + 余量
  private const val INSTANCE_NODE_MODULES_BYTES = 600L * 1024 * 1024
  const val MIN_FREE_BYTES = ROOTFS_EXTRACTED_BYTES + INSTANCE_NODE_MODULES_BYTES  // ≈2.20 GB
  ```
  检查移动到 rootfs 分支内，并按是否替换计算：首装需 `1 份 rootfs + node_modules`（≈2.20 GB）；升级需 `2 份 rootfs + node_modules`（≈3.81 GB，升级期间新旧并存）。失败文案带上"升级期间新旧两份 rootfs 会同时存在"。
- **兼容性**：空间充足的设备行为不变；空间不足时从"解到一半失败"变为"提前给出带数字的明确原因"（与 `DshPaths.loadPaths` 的既定设计目标一致：失败要显式告诉用户）。
- **验证**：`du` 实测值入表；编译 BUILD SUCCESSFUL；单测 27/27。

### 4.8 R10-08 / R10-09 进程登记表清理 + 删死代码（P3）
- **文件**：`com/dsh/core/ProotProcessExecutor.kt`、`com/dsh/core/DshBootstrap.kt`
- **改了什么**：`ProotProcessExecutor.run` 的 `actives` 清理移到 `finally`，并且**只清理已经退出的进程**（仍存活的必须留表，否则"取消安装"会找不到它 —— 那才是真孤儿 npm）。删除 `DshBootstrap.prootDir()`（阶段 A 后无调用方）。
- **兼容性**：正常路径行为不变。
- **验证**：编译 BUILD SUCCESSFUL。

---

## 5. 性能优化

- **本轮结论**：本轮改动**不引入新瓶颈**，且有一处顺带修正。
  - `isLatest()` 从"打开两次流"变成"读一次"，每次首启判定少一次 asset 打开（`scripts` + `rootfs` 两处，共省 2 次）。
  - rootfs 替换从「`deleteRecursively()` 全量删 + `renameTo`」变成「`renameTo` + `renameTo`」：删除 1.5 GB 目录树是**几万次 unlink**，现在成功路径下改名为 O(1)，只在最后删 `.old`（且该删除不阻塞用户可见流程的语义）。
  - 空间检查从"每次 `install()` 都跑"变成"只在真要解压时跑"（`StatFs` 一次调用，量级可忽略，但逻辑更准）。
- **沿用前几轮已确认的优化**（本轮未回退）：rootfs 解压 18.5×（去掉每文件 `Thread.sleep(25)` + 64 KB 缓冲区）、`DshLogBus` 环形缓冲 + ~5 Hz 合并刷新（O(n) 而非 O(n²)）、日志时间戳 ThreadLocal 复用、DiffUtil 局部刷新、Keystore 移 IO、registry 10 min 内存缓存、体积统计按 `id:version:state` 缓存。
- **未做基准**：APK 体积 / 冷启动 / 内存 / 解压耗时——需打包 + 真机，按项目约定本轮未打包。**没有伪造任何性能数字**。

---

## 6. 可靠性优化

| 项 | 内容 |
|---|---|
| 就绪口径一致 | R10-05：`isReady()` / `missingSummary()` / `install()` 与 `ProotCommand.preflight()` 对 proot 的条件集合（存在 + 可执行）**完全对齐**，堵住"假就绪"的最后一维 |
| 升级可回滚 | R10-06：rootfs 替换先备份、后校验、失败回滚，不再"升级失败=底座全丢" |
| 空间检查有效 | R10-07：门槛按实测值（≥1.50 GiB rootfs），按场景（首装/升级）分别计算，且只在真要解压时判定 |
| 版本比对健壮 | R10-02：不再对版本号做数字假设；资源读不到时语义不变（返回 true），由调用方的文件存在性兜底 |
| 资源清理 | R10-08：`actives` 登记表在异常路径也收敛（只清已退出进程） |
| 误伤防护 | R10-03：删除实例加归属校验，不再波及无关的运行中实例 |
| 沿用（未回退） | 状态机 + 归属校验（迟到回调不改别人状态）、孤儿 pid/cmdline/端口三条件认领、启动 180 s / 安装 30 min 看门狗、SingleFlight 原子单飞、按 tag 精确杀进程、`stopAndWait` 同步停止、日志脱敏 + 落盘 1 MB 轮转、Keystore AES-GCM（AAD=instanceId）、凭据原子写、前台服务 specialUse |
| 仍缺 | 安装失败自动重试/退避、WakeLock（R10-12）、registry 缓存磁盘持久化（沿用 9-07）、真机故障注入 |

---

## 7. 代码质量评估

| 维度 | 评分 | 说明 |
|---|---|---|
| 正确性 | 8.5/10 | 本轮修掉 2 个 P1 + 4 个 P2（含 1 个数据丢失面）；前几轮的并发/生命周期处理仍然扎实。扣分：`PtyNative` 与 `resolveBusybox` 属"编进去了但没人用"，属半成品态 |
| 可读性 | 9/10 | 注释普遍在讲"为什么"（而不是"做了什么"），本轮新增代码保持同一风格；`prootBinReady()` 等抽取让口径集中 |
| 可维护性 | 8/10 | 判据集中、纯函数可单测（`chooseInstanceToInstall`）；扣分在 CI 不可用（R10-10）、死配置（R10-15）、`rootProject.name` 未更新 |
| 性能 | 8/10 | 无新增瓶颈，且顺带减少一次 asset 打开与一次大目录递归删除；`DshLogsUI` 的全量 `setText` 仍可再优化（R10-17） |
| 可靠性 | 8.5/10 | 升级可回滚 + 空间检查有效 + 就绪口径统一；主要风险仍在平台层（W^X）与真机未验证 |
| 安全性 | 8/10 | Keystore + 环境变量注入（不进 argv）+ 日志脱敏 + 明文收口 + NSC 只放行回环 + 备份排除 `filesDir/dsh/`；扣分在仓库内含签名私钥、release 未混淆（R10-11） |
| 测试 | 7.5/10 | 27/27 全绿（本轮从"红"救回）；资源占位符契约测试 + 纯逻辑单测质量高；但**门禁依赖人工记得登记**（R10-01 正是漏登记），且 `isLatest` / 解压链路 / `DshBootstrap` 仍无 JVM 覆盖 |

---

## 8. 安全与依赖评估

- **输入校验**：端口 `0..65535`、实例名非空、proot 参数一律 argv 数组（不做字符串拼接）、环境变量白名单（拦 `LD_PRELOAD`/`LD_LIBRARY_PATH`）。
- **认证授权**：dsh 只绑 `127.0.0.1`；`network_security_config` 默认禁明文、只对 `127.0.0.1`/`localhost` 放行；WebView 关 `file`/`content` 访问、禁多窗口、混合内容 NEVER、站外链接交系统浏览器。
- **密钥管理**：Android Keystore AES-GCM（AAD=instanceId，密文拷到别的实例解不开）、明文不落盘（只进子进程环境变量，不进 argv）、日志二次脱敏（token + 已注册密钥）、`cleanupLegacyFiles` 清历史明文。
- **本轮新增安全面**：无新增权限、无新增网络端点、无新增依赖。JNI 符号名对齐只是把"已经编译进包的代码"变成"真的能跑"（R10-04），不扩大攻击面。
- **依赖漏洞**：离线环境无法核对 CVE（沿用前几轮）。建议联网后对 `commons-compress 1.26.0`、`xz 1.9`、`gson 2.10.1`、`glide 4.16.0`、`jsoup 1.18.3`、`junrar 7.5.5` 做一次 CVE 扫描（`gradle dependencyUpdates` + OWASP Dependency-Check / `osv-scanner`）。
- **建议升级项**：`gson 2.10.1 → 2.11.x`（当前 `libs.versions.toml` 声明 2.10.1，但沙箱缓存里跑单测用的是 2.11.0，**口径不一致，待确认**）；其余见 R10-11。

---

## 9. 测试与验证

### 9.1 已运行命令与结果
| 命令 | 结果 |
|---|---|
| `sh /workspace/run-compile.sh` | **BUILD SUCCESSFUL**（34 tasks；含 Kotlin + Java + 资源 + Manifest + 原生 ptyjni `OK: 36312 bytes`） |
| `sh /workspace/run-tests.sh`（修复前） | `TOTAL=27  FAILED=1` — `DshResourceFormatTest.everyDshStringWithPlaceholdersIsDeclared` → `[dsh_about_version]` |
| `sh /workspace/run-tests.sh`（修复后） | **`TOTAL=27  FAILED=0`** |
| `sh /workspace/dsh-launcher-poc/scripts/test-scripts-posix.sh` | **`PASS=18 FAIL=0`**（含 probe.sh 真实运行输出 `dsh-probe-ok`） |
| `nm -D --defined-only /tmp/ptyjni-check/libptyjni.so` | 7 个 `Java_com_dsh_core_PtyNative_*` 符号（对齐后） |
| JVM harness（`/tmp/evi/src/IsLatestProbe.java`） | 旧逻辑 `THREW NumberFormatException`；新逻辑 `true` |
| `du -s --block-size=1 /workspace/dsh-rootfs-build/rootfs` | `1607908864`（用于 R10-07 的实测门槛） |
| `./gradlew --no-daemon --offline checkstyle` | **失败但非代码问题**：离线无 `com.puppycrawl.tools:checkstyle:10.12.5` 缓存 |

### 9.2 未运行项及原因
| 项 | 原因 |
|---|---|
| `./gradlew :FCL:testFordebugUnitTest`（Gradle 在线单测） | 需联网解析依赖；沙箱离线。改用 `run-tests.sh`（kotlin-compiler-embeddable + JUnit 桩 + MiniRunner），跑的是同一批 27 个用例 |
| `checkstyle` | 见上（依赖未缓存）。已按规则集手工核对本轮改动的 Java 文件（无 `LineLength` 规则；`EmptyCatchBlock` / `RedundantModifier` / `LeftCurly` 等均不命中） |
| `build-apk.sh` / APK 体积 / 冷启动基准 | 项目约定"改完代码默认不打包，等明确说打包测试再出包" |
| 真机 e2e、instrumented 测试 | 无设备；W^X / PROOT_LOADER / `isLatest` 机制只能在真机确认 |

### 9.3 建议补充的测试
1. **`RuntimeUtils.isLatest` 的 JVM 单测**（现在完全可测：把版本串作为参数化输入，覆盖"数字版本 / 语义化版本 / 目标缺失 / 空文件"四种）。本轮已用 harness 验证，但应固化成 `src/test` 里的用例，避免回归。
2. **`DshBootstrap` 空间门槛的纯函数化 + 单测**：把「need 计算」抽成 `computeRequiredFreeBytes(replacing: Boolean)`，直接断言首装/升级两个数字。
3. **rootfs 解压链路合成 tar.xz 单测**：含 x 位、硬链接、符号链接、多套一层顶层目录（`hoistSingleTopLevelDir`）。
4. **`DshInstances.delete` 的归属校验单测**：需要给 `DshRuntime` 注入一个可替换的"当前运行实例"来源（当前是 object 单例，测试成本较高，可先做代码级注释约束）。
5. **资源契约测试的门禁化**：`DshResourceFormatTest` 已经能自动发现"未登记文案"，建议把它接进 CI 的**必过**步骤（R10-10 重写 CI 时一并落实），而不是靠人工记得登记。

---

## 10. 变更文件与 diff 摘要

```
 6 files changed, +163 −42（全部可编译 / 可单测验证；未提交、未打包）

 FCL/src/main/cpp/ptyjni/ptyjni.c                       JNI 符号名对齐 + 命名约定注释      (+15 −7)
 FCL/src/main/java/com/dsh/core/DshBootstrap.kt         isReady 补执行位判据；空间门槛按实测值
                                                        并移到解压分支；rootfs 替换可回滚；
                                                        删死代码 prootDir()                 (+84 −24)
 FCL/src/main/java/com/dsh/core/DshInstances.kt         delete() 加归属校验                (+10 −1)
 FCL/src/main/java/com/dsh/core/ProotProcessExecutor.kt actives 清理移入 finally（只清已退出）(+17 −10)
 FCL/src/main/java/com/tungsten/fcl/util/RuntimeUtils.java  isLatest 字符串/数值双语义 + 单次读流 (+30 −2)
 FCL/src/test/java/com/dsh/DshResourceFormatTest.kt     补登记 dsh_about_version            (+3 −0)
```
**新增/删除依赖**：无。
**数据格式 / API 变更**：无（无 Manifest 权限变化、无 shared prefs / JSON 结构变化、无字符串 key 增删）。
**建议 commit message**（沿用项目风格）：
```
fix: 第十轮审查修复——资源契约测试补登记/isLatest 非数字版本号加固/删除实例归属校验/
     JNI 符号名对齐/就绪判据补执行位/rootfs 升级可回滚/空间门槛按实测值
```

---

## 11. 风险、兼容性与后续建议

### 11.1 头号未决（沿用 9-09 / R10-19）
targetSdk 34 + `filesDir` 的 **W^X 限制**、`:proot-engine` 的 **PROOT_LOADER 绕过**、以及
`getResourceAsStream("/assets/...")` 在真机上的**实际行为**，仍是最大不确定项。
本项目已因此类"机制推理"误判过两次（LESSONS §3.2 / §4），**不要只凭机制下死判断**：
第八/九/十轮已把能做的防御都加上了（文件存在性兜底、口径对齐、可回滚），下一步只有真机点亮。

### 11.2 兼容性影响
- **R10-01**：仅测试文件，零生产影响。
- **R10-02**：纯数字版本（`scripts/version`）行为不变；语义化版本从"抛异常"变为"可比对"。资源读不到时语义保持不变。
- **R10-03**：删的正是运行中实例时行为不变；其余情况只减少副作用。
- **R10-04**：无调用方，运行时零影响。
- **R10-05**：jniLibs 正常打包的设备零影响；打包异常时从"静默假就绪"变为"明确提示"。
- **R10-06**：成功路径结果一致；峰值磁盘占用与旧实现相同（旧实现解压 `.tmp` 时旧目录也仍在）。
- **R10-07**：**唯一有用户可见行为变化的修复**——空间不足时从"解到一半失败"变成"提前拒绝并给出数字"；门槛从 1.47 GiB 提到 2.20 GiB（首装）/ 3.81 GiB（升级）。这是**有意的**：低于这个数字本来就会失败。
- **R10-08 / R10-09**：正常路径不变 / 死代码删除。

### 11.3 后续优化路线（按优先级）
1. **🔴 真机点亮 + 确认 `isLatest` 机制**（一切前提；ROADMAP M1）。装上后重点看：首启是否真的解压出 rootfs、`probe.sh` 9/9 是否通过、`dsh web` 是否能起。
2. **CI 重写（R10-10）** —— 建议替换 `.github/workflows/build.yml` 为单 job：
   ```yaml
   name: Build
   on: { pull_request: {}, push: { branches: ["**"] }, workflow_dispatch: {} }
   jobs:
     build:
       runs-on: ubuntu-latest
       steps:
         - uses: actions/checkout@v4
         - uses: actions/setup-java@v4
           with: { distribution: temurin, java-version: 17, cache: gradle }
         - uses: android-actions/setup-android@v3
         - name: Install NDK
           run: sdkmanager "ndk;27.0.12077973"
         - name: Unit tests
           run: ./gradlew --no-daemon -Darch=arm64 :FCL:testFordebugUnitTest
         - name: Checkstyle
           run: ./gradlew --no-daemon :FCL:checkstyle
         - name: Assemble (arm64 only)
           env: { FCL_KEYSTORE_PASSWORD: ${{ secrets.FCL_KEYSTORE_PASSWORD }} }
           run: ./gradlew --no-daemon -Darch=arm64 :FCL:assembleFordebug
         - uses: actions/upload-artifact@v4
           with: { name: dsh-fcl-arm64, path: FCL/build/outputs/apk/**/*.apk }
   ```
   并同步删除/改写 `release.yml`（它 `uses: ./.github/workflows/build.yml` 且依赖 `secrets.TOKEN`）。
3. **签名与混淆（R10-11）**：把 `key-store.jks`/`debug-key.jks`/`private_key.pepk` 移出仓库（`.gitignore` + 从历史里清），口令走 CI secret；release 开 R8（保留 `assets/dsh/**`、proot 参数构造、Gson 反射模型的 keep 规则）。
4. **决策 PTY / busybox 去留（R10-13）**：要么接入（终端语义、交互式 shell），要么移除（省 ~3.7 MB + 少一个需要维护的 JNI 面）。
5. **WakeLock（R10-12）**、**WebView 竖屏（沿用 9-08）**、**registry 缓存磁盘持久化（沿用 9-07）**、**`FCLPath` 死配置清理（R10-15）**、**格式函数合并（R10-14）**、**日志增量渲染（R10-17）**。
6. **把 R10-01 的教训固化**：资源契约测试已能自动发现未登记文案，接进 CI 必过步骤即可（否则下一次新增文案还会红）。

### 11.4 本轮暴露的两条方法论（已写入 `LESSONS.md §8/§9`）
- **「就绪判据 ⊇ 执行判据」要逐维对表**：第九轮对的是"有没有 proot 文件"，漏了"能不能执行"。对表时要逐条比对 `exists/isFile/canExecute/内容可用`，而不是比"检查了几个东西"。
- **常量要能被实测值证伪**：`MIN_FREE_BYTES=1500 MiB` 与 rootfs 实际 1.50 GiB 的差距，靠"读代码"是看不出来的，`du` 一下就知道。凡是"保护某个资产"的阈值，都要拿资产的实际尺寸对一遍。

---

## 12. 验收清单

- [x] 项目可编译（`run-compile.sh` BUILD SUCCESSFUL，含原生 ptyjni）
- [x] 单测全绿（**27/27**，本轮从 26/27 红修回）
- [x] 脚本一致性全绿（18/18）
- [ ] 核心流程端到端可运行（**未验证**：无设备 + R10-19 平台项未决）
- [x] 无新增严重问题（静态 + 编译 + 单测 + `nm` + JVM harness 四重验证）
- [x] 性能有明确结论（本轮无新增瓶颈；顺带减少一次 asset 打开与一次 1.5 GB 目录递归删除；**未伪造基准数字**）
- [x] 可靠性有改善（升级可回滚、空间检查有效、就绪口径统一、误杀防护、登记表收敛）
- [x] 文档完整（本报告 + `reports/round10-*` + CHANGELOG + LESSONS + INDEX，并同步仓库 `docs/`）

---

### 附：审查方法与局限
- **方法**：通读 `com/dsh/**` 全部 34 个源文件 + 关键 FCL 遗产（`FCLApp` / `SplashActivity` / `FCLPath` / `RuntimeUtils`）+ 3 个 POSIX 脚本 + `ptyjni.c` + Manifest / Gradle / CI / NSC / 备份规则 / 测试源集；全仓引用扫描（`grep` 传递闭包）；编译 + 单测 + 脚本三套动态验证；对两个关键缺陷做了**独立复现**（`nm` 校验 JNI 符号、JVM harness 复现 `NumberFormatException`）；对空间门槛做了**实测取值**（`du`）。
- **局限**：无 Android 设备、无 proot 真机运行环境、离线（无法解析 checkstyle / 依赖 CVE / 在线 Gradle 单测）。所有"真机行为"类结论标注「待真机确认」，未伪造任何运行结果；本轮"已修"项均在沙箱内经可复现代码 + 编译/单测验证。
