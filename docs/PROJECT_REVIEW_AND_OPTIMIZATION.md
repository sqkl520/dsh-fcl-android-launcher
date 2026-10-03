# 项目评估与优化报告（第十一轮）

> **项目**：DeepSeek Harness (dsh) 安卓启动器（FCL 改造）
> **审查对象**：`/workspace/FCL`，git HEAD `d82f409`（阶段 A~D 已落地）+ 第十轮 6 文件未提交改动 + 本轮改动（未提交、未打包）
> **审查时间**：2026-10-03
> **一句话结论**：前十轮把并发 / 生命周期 / 资源兜底做得很扎实，**但阶段 D-1 引入的两条链在真机上都会硬阻塞**——① rootfs 解压把符号链接目标里的 `..` 改写成宿主路径，导致 `/opt/node22/bin/npm` **断链**，`probe.sh` 自检必 FAIL，**底座永远不就绪**；② 「命中 rootfs 预装 dsh 就跳过下载」这个优化改变了产物形态，而 Kotlin 侧三处判定仍只看实例内 `node_modules`，于是**装完必判 BROKEN、重装重复同一结果、永远起不来**。两个都已在沙箱内用**真实资产**独立复现并修掉，另修 2 个 P3。单测 27/27 → **32/32**。
> **验证级别**：`run-compile.sh` **BUILD SUCCESSFUL**；单测 **32/32**；脚本一致性 **18/18**；两个 P1 用**独立 harness + 真实 rootfs** 复现（`readlink -f` 对照 + 预装版本解析对照）；**未打包、未上真机**（解压动作本身只能在真机跑，标「机制已修、真机待验」）。

---

## 1. 项目概览

### 1.1 用途
在**未 root** 的安卓手机上跑 **DeepSeek Harness（dsh）**（Node 版编码/对话 agent）：把 `@deepseek-ai/dsh` 装进 App 私有目录里的 proot Linux rootfs，用 WebView 承载其 Web UI，模型走 DeepSeek 云端 API。由 **Fold Craft Launcher（FCL）** 改造而来，已删除全部 Minecraft 代码，只保留 fcllibrary UI 框架与少量通用工具。

### 1.2 技术栈与版本
| 项 | 值 |
|---|---|
| 语言/构建 | Kotlin 2.4.10 + Java 17、AGP 8.13.2、Gradle 8.14.4、viewBinding、core library desugaring |
| SDK | compileSdk 35 / targetSdk **34** / minSdk 26 |
| 模块 | `:FCL` + `:ZipFileSystem` |
| Native | `src/main/cpp/ptyjni/`（PTY 桥接，CMake + NDK 27，arm64-v8a，**无调用方**）；`jniLibs/arm64-v8a/`：`libproot.so`(2.6M) / `libproot-loader.so`(18K) / `libbusybox.so`(1.1M) |
| 运行时底座 | patched proot + `PROOT_LOADER`（绕 targetSdk≥29 的 W^X）+ Debian bookworm arm64 rootfs + Node 22.23.x（`/opt/node22`）+ 预装 `@deepseek-ai/dsh@0.1.6-alpha.2`（`/opt/dsh-preinstalled`） |
| rootfs 资产 | `assets/dsh/rootfs/rootfs.tar.xz` = 314,360,800 B；**解压后实测 1,607,908,864 B（≈1.50 GiB）**；该文件 gitignore，属本地打包输入 |
| 关键路径约定 | `DshPaths.GUEST_ROOT`/`ProotCommand.GUEST_ROOT` = `/opt/dsh`；`PREINSTALLED_DSH_REL` = `opt/dsh-preinstalled/node_modules/@deepseek-ai/dsh`；`DEFAULT_PATH` 含 `/opt/node22/bin` |

### 1.3 目录结构（关键）
```
FCL/
├─ FCL/src/main/java/com/dsh/        项目自有代码（本轮后 35 文件）
│   ├─ core/  DshBootstrap / DshRuntime / DshInstaller / DshInstances / DshCredentials /
│   │         DshRegistry / DshDownloadViewModel / ProotCommand / ProotProcessExecutor /
│   │         DshPaths / DshLogBus / DshRuntimeService / PtyNative / SingleFlight /
│   │         TarLinkPolicy（★本轮新增）…
│   └─ ui/    DshWebViewActivity / DshSettingsActivity / shell(DshMainActivity + 5 页) / Adapter×3
├─ FCL/src/main/java/com/tungsten/    FCL 遗产（fcllibrary / fclcore / fcl 保留部分；RuntimeUtils 本轮被改）
├─ FCL/src/main/cpp/ptyjni/           PTY 原生桥接（MIT，取自 oonid/pr :proot-engine）
├─ FCL/src/main/jniLibs/arm64-v8a/    libproot.so / libproot-loader.so / libbusybox.so
├─ FCL/src/main/assets/dsh/           scripts/*.sh + proot/(占位) + rootfs/(tar.xz + version)
└─ FCL/src/test/java/com/dsh/         单测源集（2 文件 / 32 用例）
```

### 1.4 运行 / 验证方式
| 目的 | 命令 | 本轮结果 |
|---|---|---|
| 编译（不打包） | `sh /workspace/run-compile.sh` | **BUILD SUCCESSFUL**（34 tasks；含原生 ptyjni `OK: 36312 bytes`） |
| 单测 | `sh /workspace/run-tests.sh` | **TOTAL=32 FAILED=0**（第十轮 27/27 + 本轮 5） |
| 脚本一致性 | `sh /workspace/dsh-launcher-poc/scripts/test-scripts-posix.sh` | **PASS=18 FAIL=0** |
| 代码风格门禁 | `./gradlew checkstyle` | **未运行**：离线环境无 checkstyle 依赖缓存 |
| 出 APK | `sh /workspace/build-apk.sh` | 未执行（按项目约定，改完代码默认不打包） |
| 真机 e2e | —— | **仍空白**（无设备；W^X / PROOT_LOADER / `Os.symlink` 真机行为待确认） |

---

## 2. 使用逻辑梳理与完善

### 2.1 端到端路径（代码级）
```
① 冷启动：FCLApp.onCreate → FCLPath.loadPaths + DshPaths.loadPaths + DshInstances.init(异步 repair)
          → SplashActivity（只装 FCL 日志）→ DshMainActivity（横屏外壳：左菜单 + ViewPager2 五页 + 右面板）
② 底座：DshBootstrap.install
          → 建目录 → [脚本：isLatest + start-dsh.sh 存在性兜底]
          → [proot/loader：校验 jniLibs 齐备 + 可执行]
          → [rootfs：isLatest + rootfsLooksUsable 兜底 → 空间检查 → 解压 .tmp → hoist → 校验 → 可回滚替换]
          → verifySync()：proot 内跑 probe.sh（9 项：rootfs 布局/node/bash/execSync/spawnSync×2/npm/预装 dsh/native dlopen）
③ 下载 dsh：DshDownloadUI → DshRegistry.fetchVersionsCached(10min 缓存 / 20s 超时 / 断网回退)
          → chooseInstanceToInstall 去重 → DshInstances.create 或复用 → DshInstaller.install
          → setup-node-dsh.sh：命中 rootfs 预装版本 → 跳过下载（source=preinstalled）
                               否则 npm install 到实例 node_modules
④ 配置：DshSettingsActivity（实例级：API Key / 模型 / profile / 端口 / 自检 / 重装 / 删除）
        DshSettingsUI（全局设置，FCL 风格分组行）
⑤ 启动：DshLauncher.startInstance（凭据检查 → 弹窗）→ DshRuntime.start（预检 → 单实例 → proot
          → 抓 `dsh web: http://127.0.0.1:<port>/?token=…`）→ DshRuntimeService 前台服务保活
⑥ 使用：WebView 加载回环 URL（token→cookie，cookie 持久化在 DSH_HOME）
⑦ 停止/清理：TERM→5s→KILL；删除实例 = (仅在删的是运行中实例时) stopAndWait + 删目录；
             冷启 adoptOrphan 认领孤儿（pid + cmdline + 端口三条件）
```

### 2.2 本轮在使用逻辑层面的发现与完善
- **完善（R11-01）**：解压出的 rootfs **真的能用** —— 符号链接目标不再被改写成宿主路径，`/opt/node22/bin/npm` 不再断链，`probe.sh` 的 `npm` 项能过（这是"底座就绪"的必要条件）。
- **完善（R11-02）**：**"装好了"的判定与"包实际在哪"对齐** —— 命中 rootfs 预装版本时（阶段 D-1 的跳过下载优化），实例目录里没有 `node_modules`，判定改为走 `DshPaths.effectiveDshDir`（实例优先 → 预装回退），实例能真正走到 READY 并被启动。
- **完善（R11-03）**：`installVersion` 不再在"其实没发起安装"时提示"正在安装"。该路径在 R11-02 修好后**变得常见**（预装版本装完即 READY，再点即命中"已装同版本"）。
- **完善（R11-04）**：`ProotCommand.preflight()` 的 rootfs 判据与入参一致（原来收 `rootfsDir` 参数却检查全局 `ROOTFS_DIR`）。
- **待确认（R11-05，P3）**：`values-zh` 缺 `dsh_about_full_name` / `dsh_about_subtitle` 两条**无占位符**文案 → 中文界面「关于」页回退英文。资源契约测试只覆盖带占位符的文案，抓不到这类缺失。

---

## 3. 问题清单

> 级别：P0 阻断/安全/数据丢失；P1 严重；P2 一般；P3 建议。状态：`已修`=本轮已改并编译/单测/独立复现验证；`建议`=给方案未动；`待确认`=需真机或需确认。
> 「沿用」标注沿用前几轮报告编号。

| ID | 级别 | 类型 | 位置 | 问题 | 影响 | 复现/证据 | 修复方案 | 状态 |
|---|---|---|---|---|---|---|---|---|
| **R11-01** | **P1** | 正确性/真机阻塞 | `com/tungsten/fcl/util/RuntimeUtils.java:203`（`uncompressTarXZ` 符号链接分支） | `Os.symlink(linkName.replace("..", dest.getAbsolutePath()), …)` 把相对链接目标里的 `..` 改写成**宿主解压目录绝对路径** | rootfs 内数百个链接目标被改坏，其中 `opt/node22/bin/npm -> ../lib/node_modules/npm/bin/npm-cli.js` 被写成 `<rootfs>/lib/node_modules/npm/bin/npm-cli.js`（**不存在**）。真机首启解压后 `npm --version` 失败 → `probe.sh` 的 `npm` 项 FAIL → `DshBootstrap.verifySync()` 不通过 → **底座永远不就绪**，安装/启动全挂 | 真实 rootfs：`readlink -f opt/node22/bin/npm` → `<rootfs>/opt/node22/lib/node_modules/npm/bin/npm-cli.js`（**存在**）；旧逻辑产物 `<rootfs>/lib/node_modules/npm/bin/npm-cli.js` → **不存在**（`ls` 报 No such file）；`.bin/dsh` 同理 | 链接目标**原样保留**（tar 语义）；策略抽到 `com.dsh.core.TarLinkPolicy.symlinkTarget` + 单测 `symlinkTargetsAreKeptVerbatim` | **已修** |
| **R11-02** | **P1** | 正确性/真机阻塞 | `com/dsh/core/DshInstaller.kt:325`、`DshInstances.kt:173`、`DshRuntime.kt:192` | 阶段 D-1 的"命中预装版本即跳过下载"改变了产物形态（实例目录**没有** `node_modules`，包在 rootfs `/opt/dsh-preinstalled`），但三处判定仍只看实例路径 | ① 装完硬校验读到 `null` → `dsh_install_incomplete` → **BROKEN**；② 冷启 `repair()` 把 READY 降级成 `NOT_INSTALLED`；③ 启动前校验报"未安装"拒绝启动。**装完即坏、重装重复同一结果、永远起不来**（选 `latest` 或选预装版本 `0.1.6-alpha.2` 即命中） | JVM harness 打**真实 rootfs**：旧 `readVersion(instance-only)` → `null`；新 `effectiveDshDir` → `<rootfs>/opt/dsh-preinstalled/node_modules/@deepseek-ai/dsh`，`readVersion` → `0.1.6-alpha.2`，`lib/bin.js` 存在 | 新增 `DshPaths.effectiveDshDir(instanceDir, rootfsDir)`（实例优先 → 预装回退 → 都没有返回实例预期路径），三处统一走 `effectiveDshPackageJson` / `effectiveDshBinJs`；+4 单测覆盖三条分支与"预装半成品不回退" | **已修** |
| **R11-03** | P3 | 使用逻辑/文案 | `com/dsh/core/DshDownloadViewModel.kt:121` + `ui/shell/DshDownloadUI.kt:105` | `installVersion()` 命中"已 READY 同版本"会**跳过安装**，UI 仍 toast「正在安装 %1$s」并弹"去实例页" | 文案与事实不符；R11-02 修好后该路径**变常见**（预装版本装完即 READY） | 代码阅读（`installVersion` 提前 `return`，调用方无条件 toast） | `installVersion` 返回 `InstallDispatch(instance, started)`；UI 据此选文案，新增 `dsh_install_skipped`（已登记进资源契约测试） | **已修** |
| **R11-04** | P3 | 一致性 | `com/dsh/core/ProotCommand.kt:80` | `preflight()` 收 `rootfsDir` 参数，却调用无参 `DshPaths.rootfsLooksUsable()`（看的是全局 `ROOTFS_DIR`） | 传非默认 `rootfsDir` 时预检结论可能失真（当前只有默认路径在用，潜伏） | 代码阅读（参数 vs 判据不一致） | 新增 `DshPaths.rootfsLooksUsable(root: File)` 重载，预检改用入参 | **已修** |
| **R11-05** | P3 | i18n 完整性 | `FCL/src/main/res/values-zh/strings.xml` | 缺 `dsh_about_full_name` / `dsh_about_subtitle`（**无占位符**，故资源契约测试不覆盖） | 中文界面「关于」对话框回退英文 | `python3` 对比两份 strings 的 `dsh_*` 名字集合：`in en not zh: ['dsh_about_full_name', 'dsh_about_subtitle']` | 补两条中文译文；并建议把契约测试扩展到"无占位符文案的 key 也要对齐" | 建议 |
| **R11-06** | **P2** | 死代码/体积 | `com/dsh/core/PtyNative.kt` + `cpp/ptyjni/` + `jniLibs/libbusybox.so` | PTY 桥接与 `libbusybox.so` 随包编译/打包（约 3.7 MB），**全仓无调用方**（`ProotRunner` 走 `ProcessBuilder`；`DshPaths.resolveBusybox` 无人调用） | APK 白增体积；"看起来在用"的误导 | `grep -rn "PtyNative\|resolveBusybox"` 只命中定义处 | 二选一：接入（终端语义/交互）或移除（省体积）。**决策项**（沿用 R10-13） | 建议 |
| **R11-07** | **P2** | CI/仓库 | `.github/workflows/build.yml` / `release.yml` | 仍是 FCL 原版：5 ABI 矩阵 + `secrets.FCL_KEYSTORE_PASSWORD` / `CURSE_API_KEY` / `OAUTH_API_KEY`；本仓库启用必失败（secrets 缺失 → `storePassword = null` → 构建报错），且本工程只有 arm64 资产 | CI 全红；无法作为回归门禁 | 读文件（`build.yml:17-24,41-57`） | 重写为单 job：arm64 编译 + 单测 + checkstyle + 上传 APK（沿用 R10-10） | 建议 |
| **R11-08** | **P2** | 安全/供应链 | 仓库根 `key-store.jks` / `debug-key.jks` / `private_key.pepk`；release+debug 均 `isMinifyEnabled=false` | 签名私钥与口令随 public 仓库分发；release 未混淆 | 可伪造签名包（供应链投毒）；APK 易被逆向 | `git ls-files \| grep -i jks` | 分发用 key 移出仓库、口令走 CI secret；release 开 R8（保留 `assets/dsh/**` keep 规则）（沿用 R10-11） | 建议 |
| **R11-09** | P2 | 可靠性/功耗 | `AndroidManifest.xml:15` + 全仓 | `WAKE_LOCK` 权限已声明但**从未获取**任何 WakeLock | 息屏/Doze 下长驻 agent 可能被挂起或网络节流，WebUI 表现为"假死" | `grep -rn "PowerManager"` 无结果 | Running 期间持 `PARTIAL_WAKE_LOCK`，退出/停止释放（沿用 R10-12） | 建议 |
| **R11-10** | P3 | 可维护性 | `DshPaths.kt` / `ProotCommand.kt` / `setup-node-dsh.sh` | 同一件事三个写法：`GUEST_ROOT`(`/opt/dsh`)、`DSH_PREINSTALL_DIR`(`/opt/dsh-preinstalled`)、`PREINSTALLED_DSH_REL`（宿主相对路径），分处 Kotlin / sh | 改一处漏一处（R11-02 正是这类不同步的产物） | 代码阅读 | 收敛到单一出处（或加一致性测试断言三者互相可推导） | 建议 |
| **R11-11** | P3 | 性能 | `com/dsh/ui/shell/DshLogsUI.kt:552`（`render`） | 每次 flush（~5Hz）把最多 2000 行 `joinToString` 一次 | 峰值约 100 KB/次字符串分配 + 一次全量 `setText` | 代码阅读 | 改为增量 append（只追加新增行）（沿用 R10-17） | 建议 |
| **R11-12** | P3 | 仓库卫生 | `settings.gradle.kts:15` | `rootProject.name = "Fold Craft Launcher"` | 误导（本工程已不是 FCL 启动器；APK 名/IDE 标题跟着） | 读文件 | 改为 `dsh-fcl-android-launcher`（沿用 R10-15） | 建议 |
| **R11-13** | P3 | 可维护性 | `DshVersionListItem.formatSize` vs `DshPaths.formatSize` | 两份体积格式化实现（`-1`/`<=0` 语义略有差异） | DRY 违反 | 代码阅读 | 合并到 `DshPaths.formatSize`（沿用 R10-14） | 建议 |
| **R11-14** | **待确认** | 平台/架构 | targetSdk 34 + `filesDir`；`RuntimeUtils.isLatest` 资源机制；`Os.symlink` 真机行为 | W^X 禁 execve / `PROOT_LOADER` 绕过 / `getResourceAsStream("/assets/…")` 能否解析 / `Os.symlink` 在 app 数据目录的可用性，**均未在真机验证**（沿用 R10-19） | 真机 e2e 仍是最大空白 | —— | 真机点亮 + 现场确认（ROADMAP M1） | 待确认 |

---

## 4. 已完成的修复与优化

> 本轮代码改动 **13 个文件（含 1 个新增）≈ +283 / −31**（不含文档），全部可在沙箱内编译 + 单测验证；不引入新依赖、不改 API/数据格式。

### 4.1 R11-01 符号链接目标改为原样保留（P1，真机阻塞）
- **文件**：`FCL/src/main/java/com/tungsten/fcl/util/RuntimeUtils.java`、新增 `FCL/src/main/java/com/dsh/core/TarLinkPolicy.kt`
- **为什么**：内核解析相对链接是「相对链接所在目录」，而 `replace("..", dest.getAbsolutePath())` 把 `..` 换成解压根目录，语义整个变了。实测本项目 rootfs 里数百个链接命中，其中 `opt/node22/bin/{npm,npx,corepack}` 与 `opt/dsh-preinstalled/node_modules/.bin/dsh` 是**致命**的。
- **改了什么**：
  ```java
  // 旧
  Os.symlink(tarEntry.getLinkName().replace("..", dest.getAbsolutePath()), linkPath);
  // 新
  Os.symlink(com.dsh.core.TarLinkPolicy.symlinkTarget(tarEntry.getLinkName()), linkPath);
  ```
  `TarLinkPolicy.symlinkTarget` 是**恒等函数** —— 刻意如此：它把"不做改写"这条决定落成代码，并给单测一个抓手，防止再次被"顺手优化"掉。
- **兼容性**：对目标里**没有** `..` 的链接（如 `/bin -> usr/bin`、`/bin/sh -> dash`）行为完全不变；对含 `..` 的链接从"写坏"变为"写对"。峰值磁盘占用、解压耗时均不变。
- **验证**（真实资产，非纸面）：
  ```
  # 正向对照（原样保留）
  $ readlink opt/node22/bin/npm
  ../lib/node_modules/npm/bin/npm-cli.js
  $ readlink -f opt/node22/bin/npm
  <rootfs>/opt/node22/lib/node_modules/npm/bin/npm-cli.js   → 存在 ✅
  # 反向对照（旧逻辑产物）
  <rootfs>/lib/node_modules/npm/bin/npm-cli.js              → 不存在 ❌
  ```
  单测 `symlinkTargetsAreKeptVerbatim` 断言恒等 + 目标里不含 `/data/`。

### 4.2 R11-02 「包实际在哪」收敛为单一解析函数（P1，真机阻塞）
- **文件**：`com/dsh/core/DshPaths.kt`、`DshInstaller.kt`、`DshInstances.kt`、`DshRuntime.kt`
- **为什么**：阶段 D-1 的跳过下载优化改变了产物形态；三处判定仍只看实例内 `node_modules`，于是"装成功"的实例被自己的校验判成失败。
- **改了什么**：
  ```kotlin
  // DshPaths
  const val PREINSTALLED_DSH_REL = "opt/dsh-preinstalled/node_modules/@deepseek-ai/dsh"

  fun effectiveDshDir(instanceDir: File, rootfsDir: File): File {
      val inInstance = File(instanceDir, INSTANCE_DSH_REL)
      if (File(inInstance, "package.json").isFile) return inInstance      // 1 实例优先
      val preinstalled = File(rootfsDir, PREINSTALLED_DSH_REL)
      if (File(preinstalled, "package.json").isFile) return preinstalled  // 2 rootfs 预装回退
      return inInstance                                                   // 3 都没有 → 预期路径
  }
  ```
  调用点：`DshInstaller.readInstalledVersion` / `DshInstances.repair` / `DshRuntime.startLocked` 全部改走 `effectiveDshPackageJson` / `effectiveDshBinJs`。删除被取代的 `instanceDshPackageJson` / `instanceDshBinJs`（避免两套口径）。
- **兼容性**：实例内有 `node_modules` 的旧实例行为完全不变（第 1 分支命中）；只有"预装路径"的实例从"判为坏"变为"判为好"。不改脚本契约、不改 `instances.json` 格式。
- **验证**（真实 rootfs，独立 harness）：
  ```
  rootfs preinstalled pkg exists : true
  OLD readVersion(instance-only)  : null   -> 等于 dsh_install_incomplete => BROKEN
  NEW effectiveDshDir             : <rootfs>/opt/dsh-preinstalled/node_modules/@deepseek-ai/dsh
  NEW readVersion(effective)      : 0.1.6-alpha.2
  NEW bin.js exists               : true
  ```
  单测 4 条：实例优先 / 预装回退 / 都没有 / 预装半成品（只有目录无 package.json）不回退。

### 4.3 R11-03 安装文案与事实对齐（P3）
- **文件**：`DshDownloadViewModel.kt`、`DshDownloadUI.kt`、`values/strings.xml`、`values-zh/strings.xml`、`DshResourceFormatTest.kt`
- **改了什么**：`installVersion` 返回 `InstallDispatch(instance, started)`（`started = false` 表示命中"已装同版本"或"已有安装在跑"）；UI 据此选 `dsh_install_skipped` 或 `dsh_install_started`。新文案带 `%1$s`，已按项目约定登记进资源契约测试的 `callSites`。
- **兼容性**：仅文案与返回值形状变化（唯一调用点已同步）；无数据/权限变化。

### 4.4 R11-04 预检 rootfs 判据与入参一致（P3）
- **文件**：`DshPaths.kt`（新增 `rootfsLooksUsable(root: File)` 重载）、`ProotCommand.kt`
- **改了什么**：`preflight()` 改用 `DshPaths.rootfsLooksUsable(File(rootfsDir))`，判据与参数同源。无参版本保留（`DshBootstrap` 用），行为不变。

---

## 5. 性能优化

- **本轮结论**：改动**不引入新瓶颈**，且顺带修正一处隐性开销。
  - `effectiveDshDir` 最多 2 次 `isFile` 判定（`File.isFile` 是一次 `stat`），调用点在安装完成/冷启校准/启动前，量级可忽略；换来的是"不再把装好的实例判坏"。
  - 符号链接分支：`replace(...)`（一次字符串分配 + 可能多次扫描）→ 恒等传参，**少一次字符串分配**（rootfs 里数百个链接条目，累计可忽略但方向正确）。
- **沿用前几轮已确认的优化**（本轮未回退）：rootfs 解压 18.5×（去掉每文件 `Thread.sleep(25)` + 64 KB 缓冲区）、`DshLogBus` 环形缓冲 + ~5 Hz 合并刷新（O(n) 而非 O(n²)）、日志时间戳 ThreadLocal 复用、DiffUtil 局部刷新、Keystore 移 IO、registry 10 min 内存缓存、体积统计按 `id:version:state` 缓存、rootfs 替换从"全量删 + rename"变"rename + rename"。
- **未做基准**：APK 体积 / 冷启动 / 内存 / 解压耗时——需打包 + 真机，按项目约定本轮未打包。**没有伪造任何性能数字**。

---

## 6. 可靠性优化

| 项 | 内容 |
|---|---|
| 底座"能用" | R11-01：解压出的 rootfs 符号链接不再断链 → `probe.sh` 的 `npm` 项能过，底座自检才有意义（否则永远不就绪） |
| 装完即可用 | R11-02：三处判定统一走 `effectiveDshDir`，命中预装的实例能真正到 READY 并被启动；冷启 `repair()` 不再把 READY 降级 |
| 判据与入参一致 | R11-04：`preflight()` 的 rootfs 判据改用入参，不再"看全局" |
| 文案不误导 | R11-03：不再在"没发起安装"时说"正在安装" |
| 回归护栏 | 新增 5 条单测，把"链接目标原样保留"和"预装解析三分支"钉成断言 |
| 沿用（未回退） | 状态机 + 归属校验、孤儿 pid/cmdline/端口三条件认领、启动 180s / 安装 30min 看门狗、SingleFlight 原子单飞、按 tag 精确杀进程、`stopAndWait` 同步停止、日志脱敏 + 落盘 1 MB 轮转、Keystore AES-GCM（AAD=instanceId）、凭据原子写、前台服务 specialUse、`actives` 登记表异常路径收敛、rootfs 升级可回滚、空间门槛按实测值 |
| 仍缺 | 安装失败自动重试/退避、WakeLock（R11-09）、registry 缓存磁盘持久化、真机故障注入 |

---

## 7. 代码质量评估

| 维度 | 评分 | 说明 |
|---|---|---|
| 正确性 | 8.5/10 | 本轮修掉 2 个真机阻塞级 P1；前几轮的并发/生命周期处理仍扎实。扣分：`PtyNative` / `resolveBusybox` 仍是"编进去了但没人用"的半成品态；同一件事三处写法（R11-10） |
| 可读性 | 9/10 | 注释普遍在讲"为什么"；`TarLinkPolicy` 把"不改写"这条决定显式化，比藏在 Java 一行里更可读 |
| 可维护性 | 8/10 | 判定口径收敛成纯函数 + 单测；扣分在 CI 不可用（R11-07）、路径常量三处分散（R11-10） |
| 性能 | 8/10 | 无新增瓶颈，顺带少一次字符串分配；`DshLogsUI` 全量 `setText` 仍可优化（R11-11） |
| 可靠性 | 8.5/10 | 底座可用性 + 装完即可用 + 判据一致；主要风险仍在平台层（W^X）与真机未验证 |
| 安全性 | 8/10 | Keystore + 环境变量注入（不进 argv）+ 日志脱敏 + 明文收口 + NSC 只放行回环 + 备份排除 `filesDir/dsh/`；扣分在仓库内含签名私钥、release 未混淆（R11-08） |
| 测试 | 8/10 | 32/32 全绿（+5）；资源占位符契约 + 纯逻辑单测质量高，且本轮新增的断言**真的抓住了**一个会静默破坏行为的实现细节。扣分在解压链路仍无端到端测试（R11-01 正是这里漏的） |

---

## 8. 安全与依赖评估

- **输入校验**：端口 `0..65535`、实例名非空、proot 参数一律 argv 数组（不做字符串拼接）、环境变量白名单（拦 `LD_PRELOAD`/`LD_LIBRARY_PATH`）。
- **认证授权**：dsh 只绑 `127.0.0.1`；`network_security_config` 默认禁明文、只对 `127.0.0.1`/`localhost` 放行；WebView 关 `file`/`content` 访问、禁多窗口、混合内容 NEVER、站外链接交系统浏览器。
- **密钥管理**：Android Keystore AES-GCM（AAD=instanceId）、明文不落盘（只进子进程环境变量，不进 argv）、日志二次脱敏、`cleanupLegacyFiles` 清历史明文。
- **本轮新增安全面**：无新增权限、无新增网络端点、无新增依赖。`TarLinkPolicy` 只是把"符号链接目标原样写入"显式化——它**不扩大**攻击面（原先的改写反而可能让链接指向 app 数据目录内的任意位置）。
- **依赖漏洞**：离线环境无法核对 CVE（沿用前几轮）。建议联网后对 `commons-compress 1.26.0`、`xz 1.9`、`gson 2.10.1`、`glide 4.16.0`、`jsoup 1.18.3`、`junrar 7.5.5` 做一次扫描（`gradle dependencyUpdates` + OWASP Dependency-Check / `osv-scanner`）。
- **口径不一致（待确认）**：`libs.versions.toml` 声明 `gson = 2.10.1`，而沙箱缓存里跑单测用的是 `gson-2.11.0`。建议联网后确认解析结果并统一（沿用 R10）。

---

## 9. 测试与验证

### 9.1 已运行命令与结果
| 命令 | 结果 |
|---|---|
| `sh /workspace/run-compile.sh` | **BUILD SUCCESSFUL**（34 tasks；Kotlin + Java + 资源 + Manifest + 原生 ptyjni `OK: 36312 bytes`）；仅 2 条既有 `onBackPressed` 弃用告警（`DshMainActivity.kt:185/189`，与本次改动无关） |
| `sh /workspace/run-tests.sh` | **`TOTAL=32 FAILED=0`**（第十轮 27/27 + 本轮 5） |
| `sh /workspace/dsh-launcher-poc/scripts/test-scripts-posix.sh` | **`PASS=18 FAIL=0`**（含 `probe.sh` 真实运行输出 `dsh-probe-ok`） |
| `readlink -f opt/node22/bin/npm`（真实 rootfs） | 解析到存在的 `npm-cli.js`（正向对照） |
| 旧逻辑产物路径存在性检查 | `<rootfs>/lib/node_modules/npm/bin/npm-cli.js` → **不存在**（证伪旧实现） |
| JVM harness `/tmp/evi11/src/PreinstallProbe.java`（打真实 rootfs） | 旧 `readVersion` → `null`；新 `effectiveDshDir` → 预装目录，`readVersion` → `0.1.6-alpha.2`，`bin.js` 存在 |
| `python3` 对比 values / values-zh 的 `dsh_*` 名字集合 | 缺 `dsh_about_full_name`、`dsh_about_subtitle`（→ R11-05） |
| `./gradlew --offline checkstyle` | **未运行**：离线无 checkstyle 依赖缓存 |

### 9.2 未运行项及原因
| 项 | 原因 |
|---|---|
| `./gradlew :FCL:testFordebugUnitTest`（Gradle 在线单测） | 需联网解析依赖；沙箱离线。改用 `run-tests.sh`（kotlin-compiler-embeddable + JUnit 桩 + MiniRunner），跑的是同一批 32 个用例 |
| `checkstyle` | 依赖未缓存。已按规则集手工核对本轮改动的 Java 文件（无 `LineLength` 规则；`EmptyCatchBlock` / `RedundantModifier` / `LeftCurly` 等均不命中） |
| `build-apk.sh` / APK 体积 / 冷启动基准 | 项目约定"改完代码默认不打包，等明确说打包测试再出包" |
| **真机解压动作**（`Os.symlink` 落在 app 数据目录） | 无设备。R11-01 的**策略**已用真实资产在字符串/存在性层验证；**写入动作**只能在真机确认 → 标「机制已修、真机待验」 |
| 真机 e2e、instrumented 测试 | 无设备；W^X / PROOT_LOADER / `isLatest` 机制只能在真机确认 |

### 9.3 建议补充的测试
1. **解压链路的端到端测试（最高优先）**：合成一个含"相对符号链接 + `..`"的 `tar.xz`，解压后断言 `readlink` 目标**未被改写**且**能解析到存在的文件**。R11-01 之所以漏了四轮，就是因为验证从 `chroot <rootfs>`（构建产物）开始，而不是从 `RuntimeUtils` 的**解压产物**开始。
2. **`TarLinkPolicy` 的属性测试**：对随机相对/绝对目标断言恒等，防止"顺手优化"回归。
3. **`DshBootstrap` 空间门槛纯函数化 + 单测**：抽 `computeRequiredFreeBytes(replacing: Boolean)`，断言首装/升级两个数字。
4. **`RuntimeUtils.isLatest` 的 JVM 单测**：参数化覆盖"数字版本 / 语义化版本 / 目标缺失 / 空文件"（第十轮用 harness 验过，应固化）。
5. **资源契约测试扩展**：除"带占位符的文案必须登记"外，再加一条"values 与 values-zh 的 `dsh_*` key 必须对齐"（抓 R11-05 这类漏译）。
6. **`DshInstances.delete` 归属校验单测**：需给 `DshRuntime` 注入可替换的"当前运行实例"来源（当前是 object 单例，测试成本较高）。
7. **资源契约测试接进 CI 必过步骤**：它已能自动发现"未登记文案"，但目前仍靠人工记得登记。

---

## 10. 变更文件与 diff 摘要

```
 本轮代码：13 个文件（含 1 个新增）≈ +283 / −31（不含文档；未提交、未打包）

 新增  FCL/src/main/java/com/dsh/core/TarLinkPolicy.kt        tar 链接目标还原策略（恒等 + 单测抓手）  (+56)
 修改  FCL/src/main/java/com/tungsten/fcl/util/RuntimeUtils.java  符号链接目标原样保留                  (+6 −1)
 修改  FCL/src/main/java/com/dsh/core/DshPaths.kt             effectiveDshDir/effectiveDsh*/rootfsLooksUsable(File)
                                                             （删除 instanceDshPackageJson/instanceDshBinJs） (+52 −9)
 修改  FCL/src/main/java/com/dsh/core/DshInstaller.kt         readInstalledVersion 走 effectiveDshDir    (+17 −9)
 修改  FCL/src/main/java/com/dsh/core/DshInstances.kt         repair 走 effectiveDshPackageJson          (+1 −1)
 修改  FCL/src/main/java/com/dsh/core/DshRuntime.kt           启动前校验走 effectiveDsh*                 (+5 −3)
 修改  FCL/src/main/java/com/dsh/core/ProotCommand.kt         preflight 用入参 rootfs 判据              (+1 −1)
 修改  FCL/src/main/java/com/dsh/core/DshDownloadViewModel.kt installVersion 返回 InstallDispatch       (+13 −5)
 修改  FCL/src/main/java/com/dsh/ui/shell/DshDownloadUI.kt    文案按 started 选择                       (+8 −2)
 修改  FCL/src/main/res/values/strings.xml                    + dsh_install_skipped                      (+1)
 修改  FCL/src/main/res/values-zh/strings.xml                 + dsh_install_skipped                      (+1)
 修改  FCL/src/test/java/com/dsh/DshCoreLogicTest.kt          +5 单测                                    (+121)
 修改  FCL/src/test/java/com/dsh/DshResourceFormatTest.kt     登记 dsh_install_skipped                   (+1)
 文档  docs/CHANGELOG.md / LESSONS.md / INDEX.md / PROJECT_REVIEW_AND_OPTIMIZATION.md
       + docs/reports/round11-review-and-optimization.md（新增）
```
> 说明：`git diff` 的未提交总量还包含**第十轮**的 6 文件改动（`ptyjni.c` / `DshBootstrap.kt` / `DshInstances.kt` / `ProotProcessExecutor.kt` / `RuntimeUtils.java` / `DshResourceFormatTest.kt`，+163 −42），上表已把与本轮重叠的文件拆开计数。

**新增/删除依赖**：无。
**数据格式 / API 变更**：无（无 Manifest 权限变化、无 shared prefs / JSON 结构变化、无字符串 key 删改；仅新增 1 条文案）。
**建议 commit message**（沿用项目风格）：
```
fix: 第十一轮审查修复——rootfs 符号链接目标原样保留（npm 断链致底座永不就绪）/
     预装 dsh 判定收敛为 effectiveDshDir（装完必判 BROKEN）/ 安装文案与事实对齐 /
     preflight rootfs 判据与入参一致，+5 单测
```

---

## 11. 风险、兼容性与后续建议

### 11.1 头号未决（沿用 R10-19）
targetSdk 34 + `filesDir` 的 **W^X 限制**、`:proot-engine` 的 **PROOT_LOADER 绕过**、
`getResourceAsStream("/assets/…")` 在真机上的**实际行为**，以及本轮新增的 **`Os.symlink` 在
app 数据目录的可用性**，仍未在真机验证。本项目已因"机制推理"误判过两次（LESSONS §3.2 / §4），
**不要只凭机制下死判断**；下一步只有真机点亮（ROADMAP M1）。

### 11.2 兼容性影响
- **R11-01**：目标不含 `..` 的链接行为不变；含 `..` 的从"写坏"变"写对"。解压耗时/磁盘占用不变。**用户可见变化**：真机首启自检可能从"FAIL"变"PASS"（这是修复的目的）。
- **R11-02**：实例内有 `node_modules` 的旧实例行为完全不变；只有"预装路径"的实例从 BROKEN/NOT_INSTALLED 变 READY。**用户可见变化**：预装版本的实例可以真正启动。
- **R11-03**：仅文案；返回值形状变化已同步唯一调用点。
- **R11-04**：默认路径行为不变；传非默认 `rootfsDir` 时判据变准。

### 11.3 后续优化路线（按优先级）
1. **🔴 真机点亮（ROADMAP M1）**：装上后重点看 ① 首启解压后 `/opt/node22/bin/npm` 是否可执行（R11-01 的写入动作）；② `probe.sh` 9/9；③ 装预装版本后实例是否变 READY（R11-02）；④ `dsh web` 是否起、WebView 是否出界面。
2. **把解压链路纳入自动化测试（§9.3-1）**：这是本轮两个 P1 的共同盲区——"验证从构建产物开始，而不是从解压产物开始"。
3. **CI 重写（R11-07）** + 把单测/脚本/资源契约三条线设为必过。
4. **签名与混淆（R11-08）**：私钥移出仓库，release 开 R8（保留 `assets/dsh/**` keep 规则）。
5. **决策 PTY / busybox 去留（R11-06）**。
6. **收敛路径常量（R11-10）**：`GUEST_ROOT` / `DSH_PREINSTALL_DIR` / `PREINSTALLED_DSH_REL` 三处写法收敛到单一出处或加一致性测试。
7. **补 zh 文案 + 扩展资源契约测试（R11-05）**。
8. WakeLock（R11-09）、日志增量渲染（R11-11）、`rootProject.name`（R11-12）、格式函数合并（R11-13）。

### 11.4 本轮暴露的三条方法论（已写入 `LESSONS.md §11/§12/§13`）
- **别改写 tar 的符号链接目标**：相对目标由内核按链接所在目录解析，任何改写都会破坏它。且"验证路径 ≠ 真实路径"的盲区要专门对一遍。
- **优化一旦改变产物形态，判定口径必须同步**：任何"跳过某一步"的优化，都要问"跳过之后产物长什么样、原来依赖这个产物的判定还成立吗"。
- **验证要"能证伪"**：拿真实资产 + 独立 harness + `readlink -f`/`du`/`ls -l` 这类一行命令，比读代码强得多。

---

## 12. UI 与 FCL 一致性还原（本轮附加）

> 要求：「界面布局 / 按钮布局 / 整体主题一律跟 FCL 走」（`design/app-shell.md` §2.5）。
> 方法：**把 FCL 原版布局从 git 历史取出来当尺子**（`git show 5bd0e65^:...`），逐部件对照，
> 而不是凭印象判断"像不像"。

### 12.1 根因：FCL 的通用 UI chrome 在阶段 4 裁剪时被一并删了

`git diff --diff-filter=D 5bd0e65^ 5bd0e65 -- res/drawable` 显示 95 个 drawable 被删，
其中**非 MC** 的通用 UI chrome 包括：

| 资产 | 用途 | 处理 |
|---|---|---|
| `bg_game_menu` | 左侧菜单半透明底（`activity_main.xml` 的 `left_menu`） | **已恢复** |
| `bg_right_menu` | 右侧面板半透明底（`right_menu`） | **已恢复** |
| `bg_item_rounded` | 圆角行底（`item_launcher_setting_button` / `item_version_setting_*`） | **已恢复** |
| `bg_container_transparent_clickable` | 列表行"透明 + 按压高亮"（`item_profile`） | **已恢复** |
| `bg_progress` / `bg_progress_indeterminate` + 2 个 anim | FCL 进度条形态（`item_download_task`） | **已恢复** |
| `ic_baseline_{arrow_back,delete,refresh,content_copy}_24` | 返回/删除/刷新/复制动作图标 | **已恢复** |

**结论**：这些资产缺失时，"跟 FCL 走"只能走成"自己发明一个长得像的" —— 这是前几轮
"验收命令通过但观感不像"的**真正原因**。裁剪资源要按"是否 MC 专属"分类，而不是按"当前有没有布局引用"。

### 12.2 主外壳 `activity_dsh_main.xml` 逐部件对齐 `activity_main.xml`

| 部件 | FCL 原版 | dsh 改前 | 本轮 |
|---|---|---|---|
| 左侧菜单 | `bg_game_menu` + `elevation=100dp` + padding 5/10/5/10 + `clipChildren/clipToPadding=false` | 无背景、无抬升、padding 8/12 | ✅ 对齐 |
| 菜单项尺寸 | `wrap_content`（FCLMenuView 自带 8dp padding） | 硬编码 40dp | ✅ `wrap_content` |
| 菜单项数量 | home/manage/download/controller/multiplayer/setting/**back** | 实例/管理/下载/日志/设置（**缺 back**） | ✅ 补 `back` |
| 右侧面板 | `bg_right_menu` + `width_percent=0.25` + `elevation=100dp` + 内层 `right_menu_content` | 白卡片 + 8dp 外边距 + `bg_container_white` | ✅ 对齐 |
| 动态岛 | **底部居中** + `marginBottom=15dp` + `stateListAnimator=@null` | **顶部** + `minWidth=120dp` | ✅ 移回底部 |
| 视频背景 | `video_view`（VideoView） | 无 | ✅ 补回并接 `setupLiveBackground` |
| 内容区 | `match_parent` 高、无额外边距 | `0dp` 高 + 8dp 上下边距 | ✅ 对齐 |
| 根 | `id=background` + `transitionName=background` | 有 id、无 transitionName | ✅ 补 |

外壳代码同步（`DshMainActivity`）：`back` → `onBackPressedDispatcher`；动态壁纸
`setupLiveBackground()` + `onPause/onResume` 暂停恢复 + `onDestroy` `stopPlayback`；
背景随主题刷新（`ThemeEngine.addRefreshListener`，`onDestroy` 注销）—— 均照搬 FCL `MainActivity`。

### 12.3 行级范式对齐（FCL 有两种，必须按"这一行在 FCL 里是什么位置"选）

| 布局 | FCL 蓝本 | 改前 | 本轮 |
|---|---|---|---|
| `item_dsh_instance.xml` | `item_profile.xml`（档案列表 = 实例列表） | `bg_container_white` + padding 12dp + **无按压反馈** | `bg_container_transparent_clickable` + `clickable/focusable` + padding 10dp + `stateListAnimator=@xml/anim_scale` + `use_theme_color` |
| `item_dsh_version.xml` | `item_version.xml` | 单层容器 + padding 12dp + 无按压反馈 | 外层 `paddingBottom=8dp` + 内层 `bg_container_white`/`auto_tint`/`focusable`/`padding=5dp`/`anim_scale`；进度条改 3dp + `bg_progress_indeterminate` |
| `item_dsh_setting.xml` | `item_launcher_setting_button.xml` | `bg_container_white` + padding 14/10 | `bg_item_rounded` + 左右 12dp + 行 `minHeight=48dp`/上下 8dp + 标签左/动作右 + 描述在下 |
| `ui_dsh_launcher_settings.xml` | `page_setting_list.xml` | 裸 RecyclerView（**0 个 fcllibrary 控件**） | 左右 10dp + `clipToPadding=false` + `paddingBottom=10dp` + 居中空态（1 个 fcllibrary 控件） |
| 设置页分组 | `SpacingItemDecoration` + `isNextInSameGroup()`：组内 1dp 分割线（主题色画）、跨组 8dp、首行 10dp | 自己发明的「分组标题行」（FCL 没有） | 恢复 `com.mio.ui.adapter.SpacingItemDecoration` 并接进设置页；`DshLauncherSettingAdapter` 改为每行带 `SettingGroup` + `isNextInSameGroup()` |
| 三个外壳页头部 | FCL 页内**不放大标题**（页名由底部动态岛显示）；动作按钮用 `FCLImageButton`（`use_theme_color`+`no_padding`+`anim_scale_large`） | 页内 20sp 大标题 + 文字按钮 | 删标题 + 动作改 `FCLImageButton` |
| `activity_dsh_settings.xml` | FCL 的圆角行容器 | 裸 `LinearLayout` 区块 + 20sp 标题 | 区块改 `FCLLinearLayout` + `bg_item_rounded` + `auto_linear_background_tint`；标题 16sp |

### 12.4 对话框：16 处 `MaterialAlertDialogBuilder` → `FCLAlertDialog`（§2.5.2 要求）

| 文件 | 处数 |
|---|---|
| `DshLauncher.kt` | 5 |
| `DshSettingsActivity.kt` | 3 |
| `DshInstancesUI.kt` | 3 |
| `DshDownloadUI.kt` | 3 |
| `DshSettingsUI.kt` | 2 |

FCL 惯例：错误/危险 → `AlertLevel.ALERT`，信息 → `AlertLevel.INFO`；单按钮对话框 →
`setNegativeButton(getString(dialog_positive), null)`（FCL 自己的写法）；进度对话框改用
`FCLAlertDialog.setMessage()` 就地刷新（不再是 Material 的 `setMessage`）；关于页启用
`useAutoLink()` 让仓库链接可点。

### 12.5 验收结果（§2.5.5 三条命令）

```
① grep -l "com.google.android.material" *dsh*.xml        → 空（0 个 Material 控件）
② grep -c "com.tungsten.fcllibrary.component.view" *dsh*.xml
     activity_dsh_main 12 / activity_dsh_settings 36 / item_dsh_instance 8 / item_dsh_version 7
     activity_dsh_webview 7 / activity_dsh_logs 6 / item_dsh_setting 5 / activity_dsh_download 4
     activity_dsh_instances 3 / ui_dsh_placeholder 4 / view_dsh_bootstrap_banner 4
     ui_dsh_launcher_settings 1（改前 0）
③ grep -rn "MaterialAlertDialogBuilder" com/dsh              → 0（改前 21 处命中 / 16 处调用）
   grep -rn "FCLAlertDialog" com/dsh                         → 37
```

### 12.6 有意保留的差异（1 处）与仍存差异

- **有意**：FCL 的设置行是**纯白**（`bg_item_rounded` 不带 tint，暗色下也是白块）；dsh 用
  `FCLLinearLayout.auto_linear_background_tint` 让它跟随主题。几何/结构/内边距与 FCL 完全一致，
  仅颜色改为主题驱动（暗色下可读性）。这是**唯一**主动偏离，理由已写进 CHANGELOG。
- **仍存**：实例行的动作仍是文字 `FCLButton`（FCL 的 `item_launcher_setting_button` 也用文字按钮，
  故不算违规，但可再统一为 `FCLImageButton`）；`PtyNative` / `libbusybox.so` 仍「编进去了没人用」（R11-06）。

### 12.7 本轮 UI 改动文件

```
 布局（8）   activity_dsh_main / activity_dsh_instances / activity_dsh_download / activity_dsh_logs /
             activity_dsh_settings / item_dsh_instance / item_dsh_setting / item_dsh_version /
             ui_dsh_launcher_settings（9 个）
 代码（5）   DshMainActivity / DshSettingsUI / DshLauncherSettingAdapter / DshInstancesUI /
             DshDownloadUI / DshSettingsActivity（6 个）
 资源        恢复 7 个 drawable + 2 个 anim（均为 FCL 通用 chrome，非 MC）；删除 13 个恢复后无人引用的
 新增代码    com/mio/ui/adapter/SpacingItemDecoration.kt（从历史恢复，纯 UI）
```

---

## 13. 验收清单

- [x] 项目可编译（`run-compile.sh` BUILD SUCCESSFUL，含原生 ptyjni）
- [x] 单测全绿（**32/32**，本轮 +5）
- [x] 脚本一致性全绿（18/18）
- [ ] 核心流程端到端可运行（**未验证**：无设备 + R11-14 平台项未决）
- [x] 无新增严重问题（静态审查 + 编译 + 单测 + 真实资产 harness 四重验证）
- [x] 性能有明确结论（本轮无新增瓶颈；顺带少一次字符串分配；**未伪造基准数字**）
- [x] 可靠性有改善（底座可自检通过、装完即可用、判据与入参一致、文案不误导、+5 回归护栏）
- [x] **UI 与 FCL 一致**（§2.5.5 三条验收全过：布局内 Material 控件 **0** / 12 个 dsh 布局均有
      fcllibrary 控件 / `MaterialAlertDialogBuilder` **归零**；外壳与列表行/设置行逐部件对照 FCL 原版布局）
- [x] 文档完整（本报告 + `reports/round11-*` + CHANGELOG + LESSONS + INDEX，并同步仓库 `docs/`）

---

### 附：审查方法与局限
- **方法**：通读 `com/dsh/**` 全部自有源文件 + 关键 FCL 遗产（`FCLApp` / `SplashActivity` / `FCLPath` / `RuntimeUtils` / `HttpRequest` / `NetworkUtils` / `Schedulers`）+ 3 个 POSIX 脚本 + `ptyjni.c` + Manifest / Gradle / CI / NSC / 备份规则 / 测试源集；全仓引用扫描（`grep` 传递闭包）；编译 + 单测 + 脚本三套动态验证；对两个 P1 做**独立复现**（真实 rootfs 的 `readlink -f` 对照、真实 rootfs 的预装版本解析 harness）；对 rootfs 的符号链接形态做**实测盘点**（`find -type l` + `readlink`）。
- **局限**：无 Android 设备、无 proot 真机运行环境、离线（无法解析 checkstyle / 依赖 CVE / 在线 Gradle 单测）。所有"真机行为"类结论标注「待真机确认」，未伪造任何运行结果；本轮"已修"项均在沙箱内经可复现代码 + 编译/单测/真实资产 harness 验证。
