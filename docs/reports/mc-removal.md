# 变更报告：移除全部 Minecraft（MC）相关代码与资源

> ## ⚠️ 工作区保护（最高操作风险）—— 2026-10-01 已解除
> 本报告最初提交审查时，`/workspace/FCL` 有 **11 项 untracked 条目**，包含整个 `com/dsh` 主体
> （25 源文件 + 10 assets + 8 布局 + 2 测试）。**任何** `git clean -fd`、`git checkout -- .`、
> `git reset --hard`、IDE 的"revert"都会**不可恢复地**删除它们。
> **✅ 已于 2026-10-01 固化：`git add -A && git commit` → `4aea9e5`**（基线 `f4f2624`）。
> 工作区现已干净，回滚从"危险手工操作"变为一条 `git revert 4aea9e5`。
> 改动前另已把 45 个 untracked 文件备份到 `/workspace/_review_evidence/untracked_backup/`。

> **本文档的独立影响审查见 [`mc-removal-impact-review.md`](mc-removal-impact-review.md)。**
> 该审查发现本文档 8 处数字/事实偏差（含 2 处实质性判断错误、1 处章节缺失 §6），
> 已就地在 §5.3 / §9.4.4 / §9.4.6 加修正批注。若本文与审查报告冲突，**以审查报告为准**。

> **报告性质**：结构性强删改，供 **其他 AI / 人工独立审查**。
> **一句话**：把 FCL（FoldCraftLauncher，一个 Minecraft 启动器）改造成**纯 dsh 启动器**——
> 删掉所有 MC 业务代码、native 库、运行时资产与 MC 资源，只保留 FCL 的 **UI 框架**、**少量通用工具**，
> 以及新增的 `com.dsh` 启动器代码。
>
> **审查者请重点看**：§4 删除边界判定、§7 依赖临界点、§9 已知残留与风险。
> 每一条都可以用 §10 的命令独立复核。

---

## 0. 环境与前提（读之前需知）

| 项 | 值 |
|---|---|
| 仓库 | `/workspace/FCL`（git 仓库，原始 remote: FCL-Team/FoldCraftLauncher） |
| 基线提交 | `f4f2624`（main 分支，浅克隆 `--depth 1`） |
| 工作目录 | 改动**全部在工作区，尚未 commit**（`git status` 可见） |
| 包名 | 仍为 `com.tungsten.fcl`（**未改**，见 §9.2） |
| 构建环境 | `/opt/android-sdk`（SDK 35 + NDK 27）、JDK 17、Gradle 8.14.4 |
| 验证方式 | Kotlin+Java+资源+Manifest 编译（**未打包 APK**，用户要求） |

> **重要**：本仓库的 `com.dsh`（dsh 启动器代码）是**未提交的新增内容**（untracked）。
> `git checkout -- <file>` 会覆盖已修改的受跟踪文件、**但不会删除** untracked 文件；
> 然而对本仓库中**已修改的**文件执行 checkout 会丢失其未提交改动 —— 本次已踩过一次（见 §11.2）。

---

## 1. 变更总览（数字）

`git status --porcelain` 统计：

| 状态 | 数量 |
|---|---|
| 删除（`D`） | **1228** |
| 修改（`M`） | 11 |
| 新增未跟踪（`??`） | 11（其中 2 个是目录） |

删除文件的分类：

| 类别 | 删除文件数 | 说明 |
|---|---|---|
| Java/Kotlin 源码 | **605** | MC 业务代码 |
| res 资源 | **282** | 布局/drawable/anim/语言包 |
| jniLibs（预编译 .so） | **41** | arm64+armeabi+x86+x86_64 四套 |
| jni（C/C++ 源码） | **90** | CMake 工程 |
| jreAssets（JRE 压缩包） | **23** | JRE 8/17/21/25 |
| assets（MC 资产） | **80** | 运行时/游戏/控制器等 |
| libs（.aar） | **7** | GL4ES/SDL/zink/lwjgl/openal/spirv |
| `LWJGL/` 模块 | **90** | 整个模块 |
| `Terracotta/` 模块 | **8** | 整个模块（MC 联机） |

变更后规模：

| 项 | 变更前 | 变更后 |
|---|---|---|
| 源文件数 | 约 1700+ | **494** |
| 源代码行数 | — | **71,623** |
| 源码体积 | 9.2 MB | **3.7 MB** |
| res 体积 | 5.2 MB | **1.8 MB** |
| assets 体积 | 29 MB | **70 KB**（仅 dsh） |
| 仓库内 native | 139 MB(jniLibs)+94 MB(aar)+1.6 MB(jni)+154 MB(jreAssets) | **0** |
| Gradle 模块 | `:FCL :Terracotta :ZipFileSystem :LWJGL :LWJGL:*` | `:FCL :ZipFileSystem` |

---

## 2. 判定方法（如何区分「MC」与「地基」）

不能无脑递归删除，因为 dsh 代码是**建立在 FCL 基础设施之上**的。实际采用的方法：

1. **列出 dsh 的全部外部依赖**（`grep '^import' com/dsh/**`）。
   结果：dsh 只依赖以下 6 个 FCL 类 + AndroidX + Gson：

   | dsh 依赖的类 | 来源 |
   |---|---|
   | `com.tungsten.fcl.R` / `databinding.*` | 生成物（保留） |
   | `com.tungsten.fcllibrary.component.FCLActivity` | UI 框架 |
   | `com.tungsten.fcl.util.RuntimeUtils` | 资产解压 |
   | `com.tungsten.fclauncher.utils.FCLPath` | 路径常量 |
   | `com.tungsten.fclcore.util.gson.JsonUtils` | JSON |
   | `com.tungsten.fclcore.util.io.HttpRequest` | 网络 |

2. **反向求闭包**：把上述类当作根，逐层 grep「谁引用了它」，得到**必须保留的最小集合**。
   例如 `FCLActivity` → `ThemeEngine` / `FileBrowserLauncher` / `LocaleUtils` / `DisplayUtil`；
   `ThemeEngine` → `ThemeData` / `ThemePreference`（依赖 datastore + kotlinx.serialization）；
   `fcllibrary` → `fclcore.fakefx.*`（292 个文件的属性绑定系统）、`fclcore.task.{Schedulers,Task}`、
   `fclcore.util.{Pair,io.IOUtils,io.HttpRequest}`、`mio.util.{AndroidUtilKt,DisplayUtil,ImageUtil,showErrorDialog}`。

3. **不属于闭包的 MC 目录整包删除**（`fcl/{game,ui,control,...}`、`fclcore/{auth,download,game,launch,mod}` 等）。

4. **资源同理**：以「保留代码里出现的 `R.xxx` + 保留布局里的 `@xxx` + 传递闭包」为白名单，
   删除其余资源（§5.3）。

---

## 3. 保留清单（含保留理由）

### 3.1 `com/tungsten/fcllibrary/` —— 60 个源文件（UI 框架）★核心保留
FCL 的界面基础库，**保留它是为了维持用户要求的"FCL 界面风格"**。含 22 个 `component/view/` 自定义控件
（`FCLButton`、`FCLTextView`、`FCLMenuView`、`FCLDynamicIsland`、`FCLConstraintLayout`…）、
`ThemeEngine`（主题引擎）、`FCLBaseUI`/`FCLCommonUI`/`FCLPage`（页面基类）、
`browser/`（文件浏览器）、`crash/`（崩溃上报）、`util/`（`LocaleUtils`、`LogSharingUtils` 等）。

### 3.2 `com/tungsten/fclcore/` —— 394 个源文件（4 个子包）

| 子包 | 文件数 | 保留理由 |
|---|---|---|
| `fakefx/` | 292 | **fcllibrary 依赖**的 JavaFX 风格属性绑定系统（`BooleanProperty`、`ObservableList` 等）。**不是 MC 专属**，UI 层大量使用 |
| `util/` | 85 | 通用工具（`gson`/`io`/`function`/`tree`/`platform`/`fakefx` 子包）。dsh 依赖其中的 `JsonUtils`、`HttpRequest` |
| `task/` | 12 | `Schedulers`（17 处外部引用）、`Task`（4 处）被 fcllibrary 使用 |
| `event/` | 5 | `EventBus`/`Event` 通用事件总线（MC 的 4 个 Event 子类已删） |

### 3.3 其余保留

| 路径 | 文件数 | 保留理由 |
|---|---|---|
| `com/dsh/` | 25 | **本项目的主体**（dsh 启动器），未改动 |
| `com/tungsten/fcl/FCLApp.java` | 1 | Application 入口；`onCreate` 里初始化 dsh 路径与实例仓库 |
| `com/tungsten/fcl/activity/SplashActivity.kt` | 1 | 唯一 Activity 入口（已重写，见 §5.3） |
| `com/tungsten/fcl/util/RuntimeUtils.java` | 1 | 资产解压（已裁剪，见 §5.4） |
| `com/tungsten/fclauncher/utils/{FCLPath,Architecture}.java` | 2 | 路径常量（dsh 用 `FCLPath.NATIVE_LIB_DIR`）；`Architecture` 被 `FCLPath`/构建逻辑引用 |
| `com/mio/util/{AndroidUtil,DialogUtil,DisplayUtil,ImageUtil}.kt` | 4 | fcllibrary 依赖（已裁剪，见 §5.5/§5.6） |
| `ZipFileSystem/` | 12 | `com.sun.nio.zipfs` 实现；`fclcore.util.io.CompressingUtils` 依赖 |

### 3.4 保留的 assets
**只有 `assets/dsh/`**（10 个文件，70 KB）：`version` × 4、`README.md`、`PLACEHOLDER.txt` × 2、
`scripts/{setup-node-dsh.sh,start-dsh.sh,probe.sh}`。即 dsh 首启解压底座。

---

## 4. 删除清单（详列）

### 4.1 整个删除的 Gradle 模块
- `Terracotta/`（MC 联机 / VPN）—— 源码 30 MB（含 4 个 ABI 的 `libterracotta.so`）
- `LWJGL/`（MC 用的 LWJGL 版本目录 `3.3.3` / `3.4.1` + `compileOnly`）

`settings.gradle.kts` 同步移除这两个模块的 `include`。

### 4.2 删除的 Java/Kotlin 包（605 个文件）

| 包 | 删除文件数 | 内容 |
|---|---|---|
| `com/tungsten/fcl/{control,fragment,game,scoped,setting,terracotta,ui,upgrade}` | 230 + 20(ui=download 等) | 控制器、EULA/运行时 Fragment、游戏启动、文件夹 Provider、MC 配置(Profile/Accounts/VersionSetting)、Terracotta 联机、8 个 MC 页面、更新检查 |
| `com/tungsten/fclcore/{auth,download,game,launch,mod}` | 262 | 微软/OAuth 登录、版本/整合包/Forge/Fabric 下载安装、版本仓库、JVM 启动、Mod 解析 |
| `com/tungsten/fcl/activity/*`（除 `SplashActivity.kt`） | 6 | `MainActivity`、`JVMActivity`、`JVMCrashActivity`、`WebActivity`、`ControllerActivity`、`ShellActivity` |
| `com/tungsten/fclauncher/*`（除 `utils/{FCLPath,Architecture}`） | 13 | `FCLauncher`（JVM 启动）、`FCLConfig`、`bridge/FCLBridge`、`keycodes/*` |
| `com/mio/*`（除 `util/`） | ~50 | `JavaManager`、`manager/RendererManager`、`skin/`、`minecraft/`、`plugin/`、`data/`(Room)、`datastore/`、`touchcontroller/`、`dialog/`、`controlconverter/`、`promo/`、`cache/`、`flite/`、`download/` |
| `com/mio/util/` 中 11 个文件 | 11 | `AnimUtil`、`MathUtil`、`ParseUtil`、`SystemDns`、`PixelIcon`、`SourceBadgeStyle`、`PerfUtil`、`GuideUtil`、`LauncherUtil`、`LayoutConverter`、`LoginProgress`（均经引用检查确认无人使用） |
| `com/oracle/dalvik/` | 4 | `VMLauncher`（同进程启动 JVM） |
| `org/`（`libsdl/`、`lwjgl/`、`jackhuang/hmcl/`） | 15 | SDL 胶水、LWJGL GLFW 胶水、hmcl 工具 |
| `com/tungsten/fclcore/util/{LibFilter,Pack200Utils}.java` | 2 | LWJGL 过滤、pack200 解包（MC JRE 用） |
| `com/tungsten/fclcore/util/io/HttpServer.java` | 1 | nanohttpd OAuth 回调服务器 |
| `com/tungsten/fcl/util/TaskCancellationAction.java` | 1 | 引用了已删的 `fcl.ui`，且无人使用 |
| `com/tungsten/fclcore/util/{versioning,png,skin}/` | 3 个包 | MC 版本号解析、PNG 处理、MC 皮肤（引用检查确认无人使用） |

### 4.3 删除的 native 与运行时资产

| 路径 | 内容 | 体积 |
|---|---|---|
| `FCL/src/main/jniLibs/{arm64-v8a,armeabi-v7a,x86,x86_64}/` | 41 个 .so：`libgl4es_114`、`libEGL_angle`、`libOSMesa_*`、`libzink_dri`、`libvulkan_freedreno`、`libvirglrenderer`、`libSDL2/3`、`libopenal`、`libspirv-cross`、`libvgpu`、`libterracotta` 等 | 139 MB |
| `FCL/libs/*.aar` | 7 个：`NG-GL4ES`、`SDL`、`kopper-zink`、`lwjgl-3.3.3/3.4.1-natives`、`openal-soft`、`spirv-cross-natives` | 94 MB |
| `FCL/src/main/jni/` | CMake 工程 + 46 个 C/C++ 源（`fcl_loader`、`pojavexec`、`awt_bridge`、`glxshim`、`jsound`、`flite`、`androidnsbypass`、`linkerhook`…） | 1.6 MB 源码 |
| `FCL/src/main/jreAssets/app_runtime/java/{jre8,jre17,jre21,jre25}/` | 4 套 JRE 压缩包（`universal.tar.xz` + `bin-*.tar.xz`） | 154 MB |
| `FCL/src/main/assets/{app_runtime,game,controllers,img}/` + `mod_data.txt`、`modpack_data.txt`、`options.txt`、`eula.txt`、`microsoft_auth.html` | LWJGL/JNA/Caciocavallo natives、MC 资源与配置、皮肤贴图 | 29 MB |

> **注**：`assets/img/{alex.png,steve.png,skin_model/}` 是 MC 皮肤贴图，已确认 fcllibrary / `mio.util` 均未引用。

### 4.4 删除的资源（282 个文件）

| 类别 | 删除 | 保留 | 说明 |
|---|---|---|---|
| `res/layout/` | **173** | 19 | 删的是 MC 页面/对话框/控制器界面等 |
| `res/drawable/` | **95** | 17 | 删的是 MC 图标/皮肤/控件背景等 |
| `res/anim/` | 4 | 2 | 删 `frag_start/stop_anim`（Fragment 已删）、`progress_indeterminate_rect1/2` |
| `res/values-{de,fa,ja,pt-rBR,ru,tr,uk,vi,zh-rHK,zh-rTW}/` | 10 个语言包 | — | MC 翻译（依据：`du` 各约 7.5K~124K） |
| `res/values/strings.xml` | 1364 条 | 169 条 | 只留被保留代码/布局引用的 |
| `res/values-zh/strings.xml` | 1299 条 | 166 条 | 同上 |

**资源白名单的构造方式**（可复核，见 §10.3）：
1. 保留代码中的 `R.layout.*` / `R.drawable.*` / `R.string.*` / `R.anim.*` 引用；
2. 保留布局中的 `@layout/`、`@drawable/`、`@string/`、`@anim/` 引用；
3. 保留的 `values/`（styles/themes/colors）与 `AndroidManifest.xml` 中的 `@xxx` 引用；
4. **传递闭包**：被保留的 drawable XML 内再引用的 drawable 递归加入；
5. 手工补：`world_time`（`LocaleUtils` 用 `getIdentifier` 动态取）、`app_version`/`file_browser_provider`（Gradle `resValue` 生成，不在 XML 中）。

---

## 5. 修改（非删除）的 11 个文件

| 文件 | 改动 |
|---|---|
| `settings.gradle.kts` | 移除 `:Terracotta`、`:LWJGL`、`:LWJGL:lwjgl-3.3.3`、`:LWJGL:lwjgl-3.4.1` 的 include；只留 `:FCL`、`:ZipFileSystem` |
| `FCL/build.gradle.kts` | 见下 §5.1 |
| `FCL/src/main/AndroidManifest.xml` | 见下 §5.2 |
| `FCL/src/main/java/com/tungsten/fcl/activity/SplashActivity.kt` | 见下 §5.3（重写） |
| `FCL/src/main/java/com/tungsten/fcl/util/RuntimeUtils.java` | 见下 §5.4（裁剪） |
| `FCL/src/main/java/com/tungsten/fcl/FCLApp.java` | 仅清理注释（删掉指向已删 `PerfUtil` 的注释行） |
| `FCL/src/main/java/com/mio/util/AndroidUtil.kt` | 见下 §5.5（裁剪） |
| `FCL/src/main/java/com/mio/util/DialogUtil.kt` | 见下 §5.6（裁剪） |
| `FCL/src/main/java/com/tungsten/fclcore/util/io/ChecksumMismatchException.java` | 父类 `ArtifactMalformedException`（在已删的 `fclcore.download`）→ 改为 `java.io.IOException` |
| `FCL/src/main/res/values/strings.xml` | 1364 条 MC 文案删除（dsh 文案 124 条保留） |
| `FCL/src/main/res/values-zh/strings.xml` | 1299 条删除（dsh 文案 124 条保留） |

### 5.1 `FCL/build.gradle.kts` 具体改动
- 删除 `defaultConfig.externalNativeBuild { cmake { arguments("-DANDROID_STL=c++_shared") } }`
- 删除顶层 `externalNativeBuild { cmake { path = ... CMakeLists.txt } }`
- 删除 `ndkVersion = "27.0.12077973"`
- 删除 `buildFeatures.prefab = true`
- 删除 `packaging.jniLibs.pickFirsts += listOf("**/libbytehook.so")`
- 删除 `FilterJreAssets` 任务与 `filterJreAssets` 注册、`addStaticSourceDirectory("src/main/jreAssets")`
- 删除 `androidComponents` 里「按架构裁剪 LWJGL/JNA natives」的 `mergeAssets.doLast` 逻辑（含不再需要的 import `MergeSourceSetFolders`）
- 删除依赖 15 条：`jelf`、`taptargetview`、`nanohttpd`、`opennbt`、`tomlj`、`constant-pool-scanner`、`jsoup`、`touchcontroller`、`palette-ktx`、`gamepad-remapper`、`segmented-button`、`room-runtime`、`room-ktx`、`ksp(room-compiler)`、`project(":Terracotta")`、`libs.bytehook`
- 删除 `plugins { alias(libs.plugins.ksp) }` 与 `ksp { arg("room.schemaLocation", ...) }` 块
- **保留**：`splits`（`-Darch` 按 ABI 分包，仍用于 `arm64` 构建）、`signingConfigs`（`FCLKey`/`FCLDebugKey`）、`compileOptions`（desugaring）、`checkstyle` 任务、`updateMap` 任务

**保留的依赖（21 条）及用途**：
`desugar.jdk.libs`(脱糖)、`fileTree(libs/*.jar,*.aar)`(现为空目录)、`project(":ZipFileSystem")`(CompressingUtils)、
`commons.io`(fcllibrary 文件浏览器)、`commons.compress`+`xz`(RuntimeUtils 解 tar.xz)、`gson`、`junrar`(CompressingUtils)、
`appcompat`、`viewpager2`、`core.splashscreen`、`material`、`constraintlayout`、`core.ktx`、
`lifecycle.{runtime.ktx,viewmodel}`、`recyclerview`、`coroutines.android`、`glide`(ImageUtil)、
`datastore`+`kotlinx.serialization.json`(ThemePreference)

> ⚠️ **审查点**：`chardet` 曾被我误判删除（因其包名是 `org.glavo.chardet` 而非我 grep 的
> `org.mozilla.universalchardet`），编译报错后已恢复。**说明"未被 import"不等于"未使用"**，
> 已用编译验证兜底。

### 5.2 `AndroidManifest.xml` 具体改动
- 删除权限：`READ/WRITE_EXTERNAL_STORAGE`、`MANAGE_EXTERNAL_STORAGE`、`ACCESS_WIFI_STATE`、`VIBRATE`、
  `REQUEST_INSTALL_PACKAGES`、`REQUEST_DELETE_PACKAGES`、`FOREGROUND_SERVICE_CONNECTED_DEVICE`、
  `HIGH_SAMPLING_RATE_SENSORS`、`RECORD_AUDIO`
- 删除组件：6 个 Activity（`MainActivity`/`WebActivity`/`ControllerActivity`/`ShellActivity`/`JVMActivity`/`JVMCrashActivity`）、
  `activity-alias .ImportActivity`（整合包导入）、2 个 Service（`fclcore.download.ProcessService`、`.terracotta.TerracottaVPNService`）、
  1 个 Provider（`.scoped.FolderProvider`）
- 删除 `<uses-feature glEsVersion>`、`android:appCategory="game"`、`android:isGame`、`largeHeap`、
  `preserveLegacyExternalStorage`、`requestLegacyExternalStorage`、`allowNativeHeapPointerTagging`，以及 `<queries>` 里的 pojavlaunch 包名
- **保留**：`INTERNET`、`ACCESS_NETWORK_STATE`、`WAKE_LOCK`、`FOREGROUND_SERVICE`、`POST_NOTIFICATIONS`、
  `FOREGROUND_SERVICE_DATA_SYNC`、`FOREGROUND_SERVICE_SPECIAL_USE`
- **保留组件**：`SplashActivity`(LAUNCHER)、4 个 `com.dsh.ui.*` Activity、`DshRuntimeService`（`dataSync|specialUse`）、
  `fcllibrary.browser.FileBrowserActivity`、`fcllibrary.crash.CrashReportActivity`/`CrashReporterInitProvider`、
  `androidx.core.content.FileProvider`
- **保留 `usesCleartextTraffic="true"`**：dsh 的 Web UI 走 `http://127.0.0.1:<port>`
- 启动页方向由 `sensorLandscape` 改为 **`sensorPortrait`**（dsh 界面为竖屏）

### 5.3 `SplashActivity.kt` —— **完全重写**（原 340 行 → 现 72 行）

> **修正（2026-10-01，见 mc-removal-impact-review.md §1.4 D-1）**：标题中的"340 → 72"与实测不符。
> 实测 `git show HEAD:FCL/src/main/java/com/tungsten/fcl/activity/SplashActivity.kt | wc -l` = **286**（原），
> 当前文件 = **64** 行（本次再叠加 [M-01]/[M-02] 批注后为 70 行）。
> `git diff --numstat` 实测为 `+20 -242`。结论（大幅裁剪、成为启动单点、需靠 dsh 侧 null 防护兜底）
> **不变**，数字请以 **286 → 64** 为准。
**删除的逻辑**：
- 8 项 MC 运行时门禁：`if (lwjgl && cacio && cacio17 && java8 && java17 && java21 && java25 && jna) enterLauncher() else start()`
- `initState()`：探测 LWJGL/Caciocavallo/JRE/JNA 的 `version` 文件 + 生成 MC 的 `resolv.conf`
- `start()` → `EulaFragment`（MC EULA）/ `RuntimeFragment`（运行时安装页）
- `enterLauncher()` → `RendererManager.init` / `JavaManager.init` / `Controllers.init` / `ConfigHolder.init` → `MainActivity`
- `handleModpack()`（整合包 intent 处理）
- 存储权限流程：`checkPermission()` / `requestPermission()` / `hasPermission()` / EULA 同意弹窗

**保留 / 新逻辑**：
```kotlin
override fun onCreate(...) {
    installSplashScreen()
    setContentView(binding.root)
    ImageUtil.loadInto(binding.background, ThemeEngine.getInstance().getTheme().getBackground(this))
    init()
}
private fun init() = lifecycleScope.launch {
    async(Dispatchers.IO) { FCLPath.loadPaths(this@SplashActivity); Logging.start(Paths.get(FCLPath.LOG_DIR)) }.await()
    enterDsh()
}
private fun enterDsh() { startActivity(Intent(this, com.dsh.ui.DshInstancesActivity::class.java), ...); finish() }
```

> ⚠️ **审查点**：本改动**取消了外部存储权限申请**。依据：dsh 的 rootfs/实例/日志全部在
> App 私有目录（`filesDir`/`cacheDir`，见 `DshPaths`）。**如果后续功能需要访问 `/sdcard`，需重新加回权限与引导。**

> **⚠️ 修正与落地（2026-10-01，见 mc-removal-impact-review.md §6 M-01 / M-02 / 补丁 A、B）**
> 上一条"审查点"的判断**不完整**，实测有两处被漏掉，已修：
>
> **(1) `FCLPath` 的隐式依赖（M-01，P1）**——本报告 §9.4.1 已识别"基类不再初始化 `FCLPath`"，
> 但**低估了其影响面**：`FCLActivity.java:47-55` 的 `if (hasPermission) { FCLPath.loadPaths(this); }`
> 在删权限后**恒不可达**，而**通知栏 `PendingIntent` 冷启动会绕过启动页**
> （`DshRuntimeService.kt:158-163` → `DshInstancesActivity`），此时 `FCLPath` 全部静态字段为 null。
> 当前不崩**仅因为** dsh 侧恰好做了 null 防护（`DshPaths.resolveProotBin/Loader` 的 `isNullOrEmpty()`，
> 静默回退 `assets/dsh/proot/`）——这是**巧合兜底，不是设计**。
> **已落地补丁 A**：`FCLApp.onCreate` 新增 `FCLPath.loadPaths(this)`（排在 `DshPaths.loadPaths` 之前），
> 并**删除** `SplashActivity.kt` 中那一行（去重，消除"必须先经过启动页"的隐式契约）。
> 修复后 `FCLPath` 在任何入口（Activity / Service / PendingIntent / 测试）首次读取前均已就绪。
>
> **(2) 日志不落盘（M-02，P2）**——上文"日志全部在 App 私有目录"**对 `fcl.log` 不成立**：
> `FCLPath.LOG_DIR` 实为 `/sdcard/FCL/log`（外部存储），删权限 + targetSdk 34 后**不可写**，
> `Logging.start` 的 `catch (IOException)` 会静默吞掉异常 → **崩溃后无可取证文件**；
> 且 `FCLPath.loadPaths` 里另有 4 次 `init()`（`LOG_DIR`/`CONTROLLER_DIR`/`SHARE_DIR`/`SHARED_COMMON_DIR`）
> 同样静默失败。
> **已落地补丁 B**：`LOG_DIR = context.getDir("log", 0).getAbsolutePath()`，并同步修正 SplashActivity 的 KDoc。
>
> 验证：clean build `34/34 executed, 0 error`；单测 `TOTAL=23 FAILED=0`；
> 全仓库 `FCLPath.loadPaths` 有效调用点**唯一**（`FCLApp.java:41`）。

### 5.4 `RuntimeUtils.java` —— 裁剪（原 12 个方法 → 现 6 个）
- **删除**：`installJna()`、`installJava()`、`patchJava()`（依赖 `FCLauncher`、`Pack200Utils`、`Architecture`、`R.string`）
- **保留**：`InstallListener`、`isLatest()`、`install()`×2、`copyAssets()`×2、`uncompressTarXZ()`×2
  —— 即 `DshBootstrap` 首启解压所需的全部
- 同步移除 import：`com.tungsten.fcl.R`、`FCLauncher`、`Architecture`、`Pack200Utils`

### 5.5 `AndroidUtil.kt` —— 裁剪
- 删除函数：`checkElfIsAndroid()`、`getElfArchFromZip()`（依赖 jelf）、`openLinkWithBuiltinWebView()`（依赖已删的 `WebActivity`）
- 移除 import：`net.fornwall.jelf.ElfFile`、`com.tungsten.fcl.activity.WebActivity`
- 保留其余（`copyToClipBoard`、`openLink`、`getFileName`、`ViewPager2.disableMouseWheelScroll`、`acquire/releaseDownloadWakeLock` 等，fcllibrary 使用）

### 5.6 `DialogUtil.kt` —— 裁剪
- 删除 `showItemSelectionDialog()`（依赖已删的 `com.mio.dialog.ItemSelectionDialog`）
- 保留 `showErrorDialog()`×2、`showWarningDialog()`（`fcllibrary.util.LogSharingUtils` 使用）

---

> **⚠️ 章节缺口说明（2026-10-01，见 mc-removal-impact-review.md §1.4 D-2）**
> 本文档编号 **§0~§12 但缺 §6** —— 上面 §5 结束后**直接跳到 §7**，不存在"### 6. …"章节。
> 后续引用请勿写"§6"（会指向空）。实测一级标题 12 个，编号为 0,1,2,3,4,5,7,8,9,10,11,12。
> 若需连续编号，建议把 §7~§12 依次改为 §6~§11，或直接补一个 §6 占位章节。

---

## 7. 依赖临界点（最容易被质疑的判定，请重点审）

以下几处是"看似 MC、实际保留"或"看似通用、实际是 MC"的边界，**判定依据都在下表中**：

| 类 / 包 | 判定 | 依据 |
|---|---|---|
| `fclcore/fakefx/`（292 文件，2.2 MB） | **保留** | 名字像 JavaFX，实际是 fcllibrary 的属性绑定系统；`FCLButton`/`FCLTextView` 等 22 个控件与页面基类全部依赖它 |
| `fclcore/task/Schedulers`、`Task` | **保留** | `HttpRequest` 与 fcllibrary 依赖（17 / 4 处引用） |
| `fclcore/util/io/{HttpRequest,NetworkUtils,FileUtils,IOUtils}` | **保留** | dsh 的 `DshRegistry` 用 `HttpRequest`；`RuntimeUtils` 用 `FileUtils`/`IOUtils` |
| `fclcore/util/io/{CompressingUtils,Unzipper,Zipper,JarUtils,CSVTable,HttpMultipartRequest,ResponseCodeException,ChecksumMismatchException}` | **保留但可能是死代码** | 仅在"下载簇"内部互相引用，见 §9.1 |
| `fclcore/task/{FileDownloadTask,FetchTask,GetTask,AsyncTaskExecutor,…}` | **保留但可能是死代码** | 同上 |
| `mio/util/`（4 文件） | **保留** | fcllibrary 通过 `AndroidUtilKt`/`DisplayUtil`/`ImageUtil`/`showErrorDialog` 引用；**其余 11 个已删** |
| `fclauncher/utils/Architecture.java` | **保留** | `FCLPath` 与 build 逻辑引用（**审查点**：dsh 本身未直接用，可考虑删，但风险高于收益） |
| `ZipFileSystem/` 模块 | **保留** | `CompressingUtils` 用 `com.sun.nio.zipfs`；若按 §9.1 删除下载簇，则本模块亦可删 |
| `com/tungsten/fcl/util/{Constants,FXUtils,RequestCodes,ResourceNotFoundError,ShellUtil,WeakListenerHolder}.java` | **保留（未细审）** | 编译通过，均无 MC 引用；但**很可能也无人使用**，属潜在死代码 |
| `res/drawable/{background_light,background_dark}.jpg`（1.3 MB） | **保留** | `ThemeEngine.getBackground()` 使用，属"FCL 视觉风格"（用户明确要求保留） |
| `res/values-night/`、`res/values-v31/` | **保留** | 非语言包，是夜模式/API 31+ 的限定资源 |

---

## 8. 验证（本次实际执行）

| 检查 | 命令 | 结果 |
|---|---|---|
| Kotlin + Java + 资源 + Manifest 编译 | `sh /workspace/run-compile.sh` | **BUILD SUCCESSFUL，0 error** |
| JVM 单元测试（dsh 逻辑） | `sh /workspace/run-tests.sh` | **TOTAL=23 FAILED=0** |
| 脚本 POSIX 一致性 | `sh /workspace/dsh-launcher-poc/scripts/test-scripts-posix.sh` | **18/18** |

> `run-compile.sh` 已升级为执行四个任务：
> `:FCL:compileDebugKotlin` + `:FCL:compileDebugJavaWithJavac` + `:FCL:processDebugResources` + `:FCL:processDebugMainManifest`
> （**不含打包**，用户要求先不出 APK）。

**未做的验证（审查者请注意）**：
- **未打包 APK**（`assembleFordebug`）—— 因此**未验证** dex 合并、native 打包（已无 native）、APK 体积。
- **未做真机安装 / 运行时验证** —— 因此**未验证**：SplashActivity 重写后能否正常进入 dsh 界面、
  `Theme.Splash` / `activity_splash.xml` 等保留资源在运行期是否完好、`FileBrowserActivity` 等是否仍可用。

---

## 9. 已知残留 / 待审查项

### 9.1 疑似死代码簇（已保留，未裁剪）
以下文件**编译通过但可能从任何入口都不可达**，外部引用数为 0：

```
fclcore/task/     AsyncTaskExecutor, CompletableFutureTask, DownloadException, FetchTask,
                  FileDownloadTask, GetTask, TaskCompletableFuture, TaskEvent,
                  TaskExecutor, TaskListener          （Schedulers / Task 仍被使用）
fclcore/util/io/  CSVTable, ChecksumMismatchException, CompressingUtils, HttpMultipartRequest,
                  JarUtils, ResponseCodeException, Unzipper, Zipper
                  （FileUtils / HttpRequest / IOUtils / NetworkUtils 仍被使用）
fcl/util/         Constants, FXUtils, RequestCodes, ResourceNotFoundError,
                  ShellUtil, WeakListenerHolder
```

> **注意**：上述"外部引用数为 0"是基于 `fcllibrary`/`dsh`/`mio`/`fcl` 的 grep，
> **同包内互相引用未计入**（如 `Task.java` 引用 `TaskExecutor`）。
> 若要确认死亡，需做完整可达性分析（从 `FCLApp` / `SplashActivity` / dsh 各 Activity 出发）。
> **删除它们可进一步减小 dex，属可选优化，本次未做。**

### 9.2 命名与品牌残留（未改，需产品决策）
- 包名仍是 `com.tungsten.fcl`，`applicationId = "com.tungsten.fcl"` / `.debug`
- `app_name` 仍是 **"Fold Craft Launcher"**；Application 类名仍叫 `FCLApp`
- `fcllibrary` / `fclcore` / `fclauncher` 包名与 `FCL` 前缀保留（用户要求保留 FCL 风格，未重命名）
- 仓库 `rootProject.name = "Fold Craft Launcher"`
- 主题名 `Theme.FoldCraftLauncher`
- `versionName = "1.3.3.3"` / `versionCode = 1333`（沿用 FCL 版本号）

### 9.3 其他残留
- **KDoc 注释里提到已删类**（仅注释，无功能影响，共 3 处）：
  - `com/dsh/core/DshInstance.kt` → 提到 `com.tungsten.fcl.setting.Profile`
  - `com/dsh/core/DshInstances.kt` → 提到 `com.tungsten.fcl.setting.Profiles`
  - `com/dsh/core/DshRuntimeService.kt` → 提到 `com.mio.download.DownloadService`
  - 另有 `ChecksumMismatchException.java` 的改造说明注释提到 `fclcore.download.ArtifactMalformedException`
- `FCL/build.gradle.kts` 中 `fileTree(libs/*.jar,*.aar)` 的 `libs/` 目录**已空**
- `FCL/docs/`、`FCL/scripts/`、`FCL/config/`（checkstyle 配置）等随仓库保留，未审
- 仓库根的 `build/`（构建产物）未清理

### 9.4 潜在过度删除风险（请重点复核）
1. **`FCLPath.loadPaths()` 在基类中不再执行**（具体副作用，已追到调用点）：
   `com/tungsten/fcllibrary/component/FCLActivity.java`（**所有 Activity 的基类**）在 `onCreate` 中
   `if (hasPermission) { FCLPath.loadPaths(this); }` —— 依赖 `isExternalStorageManager()` /
   `READ|WRITE_EXTERNAL_STORAGE`。**删除这些权限后该分支恒为 false，`FCLPath.loadPaths()` 不再由基类调用。**
   - **不会崩溃**（未声明权限时 `checkSelfPermission` 返回 DENIED、`isExternalStorageManager()` 返回 false）。
   - 当前可用是因为 `SplashActivity.init()` 里显式调了一次；dsh 侧 `DshPaths.resolveProotBin/Loader`
     已对 `FCLPath.NATIVE_LIB_DIR` 为 null 做防护（形参 `String?` + `isNullOrEmpty()`）。
   - → **建议**：把 `FCLPath.loadPaths()` 上提到 `FCLApp.onCreate`（与 `DshPaths.loadPaths` 并列），消除隐式依赖。
2. **`FCLActivity.onCreate` 仍无条件 `new FileBrowserLauncher(this)`**：
   浏览器模块（`fcllibrary/browser/`，7 个文件）+ Manifest 里的 `FileBrowserActivity` 已成为死重
   （原本调用它的 MC 代码已删，现全仓库无调用者）。无害，但建议一并删除。
3. **`ThemeEngine.applyBackground` 的空指针风险：已核验不成立**（死代码）：
   该函数用 `FCLPath.LT_BACKGROUND_PATH`（未初始化时为 null）传给非空形参 `ImageUtil.load(path: String)`，
   且不在 `runCatching` 内；但其唯一调用者 `ThemeEngine.applyAndSave(context, view, lt, dk)` **全仓库无调用者**
   → 运行期不会触发。将来若恢复"自定义背景"功能需先保证 `FCLPath` 已初始化。
4. **`res/values-v31/`**：仅含 `icon_background_color` → `@android:color/system_accent1_0`（框架颜色），已核实安全。

> **⚠️ 修正（2026-10-01，见 mc-removal-impact-review.md §4.2 / §1.4 D-4）—— 本条为实质性判断错误，原判定不成立。**
> 本节原称"`values-v31/` 仅含 `icon_background_color` → 框架颜色，已核实安全"**不成立**。
> 该目录实有 **2 个文件**：
> - `values-v31/colors.xml` → `<color name="icon_background_color">@android:color/system_accent1_0</color>`（框架颜色，✅ 安全；`values/colors.xml` 有同名 fallback，未删）
> - **`values-v31/themes.xml` → 重定义整个 `Theme.FoldCraftLauncher` 与 `Theme.Splash`**，并把
>   `values/themes.xml:27` 的 `parent="@style/Theme.SplashScreen"` + `postSplashScreenTheme` +
>   `windowSplashScreenAnimatedIcon` **覆盖掉**（改成 parent=`Theme.FoldCraftLauncher`）。
> 被引用的 `@color/default_theme_color` 与 `@mipmap/ic_launcher` 均保留（实测存在），故**不会**
> `Resources.NotFoundException`；但**后果是** API 31+ 设备上 `Theme.Splash` 不带 `postSplashScreenTheme`，
> `installSplashScreen()` 的行为与 API < 31 不同。该文件本次**未被修改**，属既有条件；
> 而本次又把启动页方向改为 `sensorPortrait` 并重写了启动流程，使该差异**首次可见**。
> **待确认（真机 Android 12+）**：首屏是否闪烁/黑屏。若异常，把 `values-v31/themes.xml` 的
> `Theme.Splash` 改回 `parent="@style/Theme.SplashScreen"`，只保留 `windowLayoutInDisplayCutoutMode`。
>
> **已核验的不存在通道**（故本条不会因第三方因素放大）：无 RRO / 运行时资源覆盖（`grep -rniE "overlay|rro"` 仅命中 `windowContentOverlay` 等主题属性）、无第三方 skin、无插件按资源名加载、无 `<meta-data>` 声明 skin。主题热切换存在但走代码路径（`ThemeEngine.updateTheme` + 运行时计算色），不读 `values-v31`。
5. **`values-zh/strings.xml` 缺 3 条**：`app_version`（Gradle 生成）、`cancel`/`ok`（Android 框架 `android.R.string`），
   属正常；但**非中英文语言已全部删除**，海外用户界面将回落英文。
6. **`Theme.Splash` 与 `activity_splash.xml`**：启动页布局原为横屏设计，已改为竖屏显示，**视觉效果未验证**。

> **✅ 修正（2026-10-01，见 mc-removal-impact-review.md §1.4 D-5）—— 本条为虚惊，风险不成立。**
> 本节原称"启动页布局原为**横屏设计**，已改为竖屏显示，视觉效果未验证"——该"风险"**是虚惊**。
> 实测 `res/layout/activity_splash.xml` 与 `HEAD` 版本 **逐字节相同**（`diff` 无输出），内容为
> `match_parent` + `ConstraintLayout` + 一个 `FragmentContainerView`，**不含任何横屏专有元素**，
> 布局本身**方向中立**。"横屏改竖屏"的描述与代码不符，为记录时引入。
> 真正需要关注的是**另一件事**：`Theme.Splash` 在 API 31+ 被 `values-v31/themes.xml` 覆盖（见 §9.4.4 修正批注），
> 那才是本次"重写启动流程 + 改方向"之后首次可见的差异。
7. **`Probe` / `UrlScanner` / `ProcessUtil` 三个 object 声明在 `DshRuntime.kt` 内部**（非独立文件）——
   审查 `com/dsh` 时注意这一点，不要按"缺文件"判断。
8. **`Architecture.java` 保留但可能无用**：dsh 本身未直接使用；`FCLPath` 是否引用它需确认。
9. **`fcllibrary.crash.CrashReportActivity` / `CrashReporterInitProvider`** 仍注册在 Manifest 中，
   其上报目标/表单可能已随 MC 代码删除而失效（未核查）。

---

## 10. 如何独立复核（命令）

```sh
cd /workspace/FCL

# 10.1 变更规模
git status --porcelain | awk '{print $1}' | sort | uniq -c        # 1228 D / 11 M / 11 ??
git status --porcelain | grep '^ D' | wc -l                        # 1228

# 10.2 确认 MC 目录已不存在
ls Terracotta LWJGL FCL/src/main/jniLibs FCL/src/main/jni FCL/src/main/jreAssets 2>&1 | head
ls FCL/src/main/assets/                                            # 应只有 dsh
ls FCL/src/main/java/com/tungsten/fcl/                             # 应只有 FCLApp.java/activity/util

# 10.3 校验资源白名单是否自洽（对比保留布局引用的资源是否都存在）
grep -rhoE '@(layout|drawable|string|anim)/[a-z0-9_]+' FCL/src/main/res/layout/ | sort -u | \
  while read r; do t=${r%%/*}; n=${r##*/};
    find FCL/src/main/res/$t* -name "$n.*" >/dev/null 2>&1 || echo "可能缺失: $r"; done

# 10.4 确认保留代码没有再引用已删类（预期只看到 4 处注释提及，见 §9.3）
grep -rnE "fclcore\.(auth|download|game|launch|mod)|com\.mio\.(skin|touchcontroller|download|data)|com\.oracle|com\.tungsten\.fcl\.(game|ui|setting|control|terracotta|scoped|upgrade)" \
  FCL/src/main/java --include=*.java --include=*.kt | grep -v "^.*://" | head

# 10.5 重跑验证
sh /workspace/run-compile.sh      # 期望 BUILD SUCCESSFUL
sh /workspace/run-tests.sh        # 期望 TOTAL=23 FAILED=0
```

---

## 11. 风险、回滚与教训

### 11.1 回滚
- **受跟踪文件**：`git checkout -- <path>` 可恢复任意被删/被改的文件（**注意 §11.2 的坑**）。
- **未跟踪文件**（`com/dsh/`、`assets/dsh/`、8 个 dsh 布局、`FCL/src/test/java/com/dsh/`）：
  `git checkout` **不会**动它们，是本次新增的成果，**删了就没了**。
- 建议在继续改动前先 `git add -A && git commit`（或至少备份 `com/dsh/` 与 `docs/`）。

### 11.2 本次踩过的坑（供后人避免）
**对含未提交改动的文件执行 `git checkout --` 导致数据丢失**：
本次为实现"只保留被引用的字符串"，曾 `git checkout -- res/values/strings.xml res/values-zh/strings.xml`
想恢复原文件重新裁剪——结果把**之前加好的 124 条 `dsh_*` 文案**一起回退掉了（那些是未提交改动）。

**恢复方式**（如果再次发生）：从上次构建的合并产物里捞回：
```
FCL/build/intermediates/incremental/fordebug/mergeFordebugResources/merged.dir/values/values.xml
FCL/build/intermediates/incremental/fordebug/mergeFordebugResources/merged.dir/values-zh/values-zh.xml
```
用正则提取 `<string name="dsh_*">…</string>` 再插回源文件。

**教训**：删除类操作前，先确认目标文件的改动是否已提交；或先把改动 `git stash`/备份。

### 11.3 其他风险
- 本次是**大规模删除**，虽然编译通过，但**运行期行为未验证**（见 §8 尾部）。
- `fclcore/fakefx` 保留了 292 个文件；若未来确认 UI 层不依赖其中某些子模块，仍可继续裁剪。
- 删除 MC 语言包后，`fcllibrary` 内建 UI 的多语言回落到英文；如需恢复，从 git 取回对应 `values-*` 目录即可。

---

## 12. 附：变更后完整目录树（保留部分）

```
FCL/
├── build.gradle.kts               [已改]
├── src/main/
│   ├── AndroidManifest.xml        [已改]
│   ├── assets/dsh/                dsh 首启解压底座（唯一保留的 assets）
│   ├── java/
│   │   ├── com/dsh/               25 文件 —— dsh 启动器主体
│   │   │   ├── core/              DshPaths/Instances/Registry/Installer/Runtime/
│   │   │   │                      Credentials/Bootstrap/ProotCommand/DshLogBus/…
│   │   │   └── ui/                DshInstances/Download/Logs/Settings/WebView Activity + Adapter
│   │   ├── com/mio/util/          4 文件 —— AndroidUtil/DialogUtil/DisplayUtil/ImageUtil（已裁剪）
│   │   ├── com/tungsten/fcl/      9 文件 —— FCLApp / activity/SplashActivity / util/{RuntimeUtils,…}
│   │   ├── com/tungsten/fclauncher/utils/  2 文件 —— FCLPath / Architecture
│   │   ├── com/tungsten/fclcore/  394 文件 —— fakefx(292) / util(85) / task(12) / event(5)
│   │   └── com/tungsten/fcllibrary/  60 文件 —— UI 框架（组件/主题/页面基类/浏览器/崩溃上报）
│   └── res/
│       ├── layout/     19 个（其中 8 个是 dsh 的）
│       ├── drawable/   17 个（含 2 张背景图）
│       ├── anim/       2 个
│       ├── values/     169 条字符串（124 条 dsh + 45 条 FCL）
│       ├── values-zh/  166 条
│       ├── values-night/  values-v31/
│       ├── xml/        data_extraction_rules / backup_rules / network_security_config / provider_paths
│       └── mipmap-*/   应用图标
settings.gradle.kts                [已改] 只留 :FCL :ZipFileSystem
ZipFileSystem/                     12 文件 —— com.sun.nio.zipfs（CompressingUtils 依赖）
```

---

**报告结束。** 若发现过度删除或保留不当，请指出具体文件与依据，可据此调整。
