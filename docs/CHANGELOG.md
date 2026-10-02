# Changelog

本项目：**DeepSeek Harness (dsh) 安卓启动器** —— 在未 root 的安卓手机上，用 proot 跑 dsh（Node 版编码/对话 agent），基于 FoldCraftLauncher (FCL) 改造。

> **仓库**：<https://github.com/sqkl520/dsh-fcl-android-launcher> ｜ **版本**：`0.1.0-SNAPSHOT`

格式参照 [Keep a Changelog](https://keepachangelog.com/)。由于项目尚未正式发版，各段以**工作阶段/里程碑**划分，并标注对应的 git commit。

> **★ 硬规则（项目约定）**：**任何代码/配置/文档的更改，都必须记入本 CHANGELOG**（在最上方的
> `[Unreleased]` 段按 Added/Changed/Fixed/Removed/Optimized/Refactored/Notes 分类追加）。
> 版本号规则：当前为 `0.1.0-SNAPSHOT`；**待"能正常启动 / 下载 / 管理 dsh"后才标 `1.0.0`**。
> **本文件只记"改了什么"**；经验 / 方法论 / 踩坑请写进 `LESSONS.md`，不要写在这里。

---

## [Unreleased · 文档：新增经验文档并瘦身 CHANGELOG] - 2026-10-02

### Added
- 新增 `docs/LESSONS.md`：经验与踩坑记录（沙箱/网络约束、死代码清理方法论与 Kotlin 假死陷阱、
  Android/FCL 平台经验、文档与流程约定）

### Changed
- `CHANGELOG.md` 中的方法论与踩坑内容移出（改指向 `LESSONS.md`），本文件此后**只记变更事实**
- 文件头「硬规则」补充说明：经验/方法论写 `LESSONS.md`

---

## [Unreleased · 外壳改造阶段 4：裁剪不可达代码与资源] - 2026-10-02

### Changed
- **外壳锁横屏补齐**：`DshWebViewActivity`、`CrashReportActivity` 由 `sensor` 改为 **`sensorLandscape`**
  （`DshMainActivity` 加 `launchMode="singleTop"`，供详情页跳回并复用同一外壳实例）
- `FCLActivity`：移除 `fileLauncher` 字段（文件浏览器已删）与**已失效的外部存储权限判断分支**
  （项目已无存储权限，该分支恒为 false；`FCLPath.loadPaths` 现由 `FCLApp.onCreate` 统一调用）

### Removed
- **已被外壳页取代的 3 个 Activity**：`DshInstancesActivity` / `DshDownloadActivity` / `DshLogsActivity`
  （连 Manifest 注册一并删除；其布局 `activity_dsh_*.xml` 由对应 `Dsh*UI` 页面继续使用，保留）
  - 新增 `DshMainActivity.intentForTab(context, tab)` + `EXTRA_OPEN_TAB`：详情页（实例设置 / WebView）
    改用它跳回指定 tab，替代原先直接 `startActivity` 到旧 Activity
- **文件浏览器模块**（`fcllibrary/browser/`，9 文件）+ `FileBrowserActivity` 注册
  —— MC 时代调用方已删，全仓无启动入口
- **连带死类**：`EditDialog`、`FCLNumberSeekBar`、`FCLCheckBoxTreeAdapter`、`FCLCheckBoxTreeItem`、
  `FCLFragment`、`view/color/`（`ColorPickerView` + `AlphaPatternDrawable`）
- **fclcore 死代码（18 个）**：`util/io/{CSVTable,ChecksumMismatchException,CompressingUtils,HttpMultipartRequest,JarUtils,Unzipper,Zipper}`、
  `util/{CacheRepository,DigestUtils,FutureCallback,Hex,InfiniteSizeList,MurmurHash2}`、
  `task/{FetchTask,DownloadException}`、`util/platform/{CommandBuilder,MemoryUtils}`、`fakefx/PlatformUtil`
- **资源**：3 个布局（`activity_file_browser` / `item_file_browser` / `item_check_box_tree`）、
  2 个图标（`ic_baseline_file_24` / `ic_baseline_folder_24`）、
  22 条无用字符串（`file_browser_*` 21 条 + `color_picker_*` 2 条，中英同步）、
  7 个无对应类的 `declare-styleable`（`KeycodeView`/`LogWindow`/`DraggableTextView`/`FCLTitleView`/`FCLAppBarLayout`/`FCLNumberSeekBar`/`ColorPickerView`）、
  `build.gradle.kts` 中已无用的 `file_browser_provider` resValue

### Notes
- 合计 **44 个文件删除 + 9 个修改**
- 验证：`run-compile.sh` BUILD SUCCESSFUL；**未打包**
- 清理的判定依据与踩坑记录见 `LESSONS.md` §2

---

## [Unreleased · 建仓、推送与历史精简] - 2026-10-02

### Added
- **GitHub 仓库建立并首次推送**：<https://github.com/sqkl520/dsh-fcl-android-launcher>
  （`main` 分支，7 个提交；推送凭据走 SSH，密钥 `sqkl520-dsh-launcher-sandbox`）
- 新增项目 `README.md`（项目定位 / 工作原理 / W^X 绕过说明 / 当前进度 / 构建方式 / 文档索引 / 许可与致谢）
- 将项目文档（`INDEX` / `PLAN` / `ROADMAP` / `PACKAGING` / `design/*` / `reports/*`）纳入仓库 `docs/`

### Removed
- 移除 FCL 自带的 `README_EN.md` / `README_RU.md` / `docs/weblate.md`（已不适用于本项目）
- 根 `CHANGELOG.md` 由 FCL 的版本记录替换为本项目变更日志

### Changed
- **历史精简（rewrite）**：基线提交里含 481MB 已删除的 MC 二进制资产（JRE 压缩包 / jniLibs / aar），
  导致 `.git` 达 337MB，在受限网络下**多次推送失败**。
  遂用 `git filter-branch` 从**全部历史**剔除这 6 个大目录：
  `Terracotta/`、`LWJGL/`、`FCL/libs/`、`FCL/src/main/jreAssets/`、`FCL/src/main/jniLibs/`、
  `FCL/src/main/assets/app_runtime/`（约 455MB）
  - **7 个提交全部保留**（提交信息 / 日期 / 作者不变），源码演化完整，`.git` 337MB → **7.2MB**
  - 副作用：提交 SHA 全部变更（新仓库、无外部引用，无影响）
  - 改写前已完整备份到 `/workspace/_review_evidence/git-backup-before-strip/`

### Notes
- 文档主副本位于 `/workspace/docs`（仓库外工作区），发布时同步到仓库 `docs/`
- `.github/workflows/` 仍为 FCL 原版（checkstyle / release 流程未适配），留待后续处理
- 提交 SHA 对照（旧 → 新，因历史精简而变更）：
  `f4f2624→647919c`、`4aea9e5→5bd0e65`、`a62ed0d→0f7334b`、`515270d→b974b64`、
  `369d4a4→40a43f3`、`5337f5b→b6bbd7c`、`f723120→c428f78`

---

## [Unreleased · 外壳改造阶段 3：横屏 + 右侧面板] - 2026-10-02

### Changed
- **外壳整体改横屏**：`DshMainActivity` / `SplashActivity` / `DshSettingsActivity` 的
  `screenOrientation` 由 `sensorPortrait` 改为 **`sensorLandscape`**（照 FCL 原版横屏形态，右面板得以常驻）。
  `DshWebViewActivity` 保持 **`sensor`**（可自由旋转——dsh Web UI 是聊天界面，竖屏更便于输入）
- `activity_dsh_main.xml` 重排为横屏三栏：左侧 `FCLMenuView` 菜单 + 中间 `ViewPager2` 内容区
  + 右侧面板（`FCLConstraintLayout` + `bg_container_white`，宽 28%），顶部 `FCLDynamicIsland`

### Added
- **右侧面板**：当前实例卡（名称 + 状态/端口）+「启动/停止」+「打开界面」按钮，
  随 `DshInstances.selectedId` / `instances` / `DshRuntime.state` 实时刷新
- 共享启动流程 `DshLauncher`（凭据检查 → 启动 → 导航；含 `prepareRuntime` 进度对话框），
  供实例页与外壳右面板复用，消除启动逻辑重复
- 文案：`dsh_right_current_instance` / `dsh_right_no_instance` / `dsh_action_open_webui`（中英）

### Refactored
- `DshInstancesUI` 的启动/底座准备改为调用 `DshLauncher`（删去页内重复实现）

### Notes
- 验证：`run-compile.sh` BUILD SUCCESSFUL；**未打包**
- 待真机确认：横屏下 ViewPager 页面布局、右面板在窄横屏（小屏手机）的挤压情况

---

## [Unreleased · 死代码清理] - 2026-10-02

> 依据 `round6-optimization.md §11.2` + `mc-removal.md §9.1` 的清单，**逐个做全仓 0 引用核查**后保守删除（不照单全删）。

### Removed
- **源文件（18 个）**：
  - `fcl/util/`：`ShellUtil`、`FXUtils`、`WeakListenerHolder`、`RequestCodes`、`ResourceNotFoundError`
  - `mio/util/`：`AndroidUtil`（其导出函数 `getElfArchFromSo`/`openLink` 等全仓无人引用）
  - `fclcore/`：`util/KeyUtils`、`task/FileDownloadTask`、`task/GetTask`
  - `fcllibrary/`：`component/view/FCLUILayout`、`component/dialog/{FCLColorPickerDialog,FullImageDialog,FullEditDialog}`、`component/ui/{FCLMultiPageUI,FCLPage}`
- **布局（3 个）**：`dialog_color_picker.xml`、`dialog_full_image.xml`、`dialog_full_edit.xml`

### Fixed
- **纠正清单误判**：`mio/util/DialogUtil.kt` 被清单列为死代码，实为误判——其顶层扩展函数
  `showErrorDialog` 被 `fcllibrary/util/LogSharingUtils` → `crash/CrashReportActivity`（崩溃上报链路）引用。
  删除后编译报错，已恢复；并修正其残留的旧包名 import（`com.tungsten.fcl.R` → `com.dsh.fcl.androidlauncher.R`）
- **保留**（清单提及但实际是活的）：`FCLMenuView`/`FCLTabLayout`/`FCLDynamicIsland`（外壳阶段 1/2 在用）、
  `EditDialog`（被 `browser/FileBrowserActivity` 引用）

### Notes
- 判据说明与踩坑记录见 `LESSONS.md` §2（**注意 Kotlin 顶层函数 / `Kt` 后缀的假死陷阱**）
- 验证：`run-compile.sh` BUILD SUCCESSFUL；**未打包**

---

## [Unreleased · 外壳改造阶段 2：五页迁移 + Material→FCL] - 2026-10-02

### Added
- 页面基类 `DshPageUI`（`FCLCommonUI` + 页面级协程作用域，页面被 ViewPager 回收即 `cancel`，解决迁移后 StateFlow 订阅的生命周期）
- 外壳能力接口 `DshShellHost`（页面经它跨 tab 跳转 / 打开详情 Activity，取代各自 `startActivity`）
- 五个 tab 的真实页面（由独立 Activity 迁移为 `DshPageUI`）：
  `DshInstancesUI`（tab0 实例）、`DshDownloadUI`（tab2 下载）、`DshLogsUI`（tab3 日志）、
  `DshSettingsUI`（tab4 设置，作用于当前选中实例，随 `selectedId`/实例列表变化自动重绑）；管理 tab 仍为占位

### Changed
- **布局 Material→fcllibrary 控件（§2.5 硬性要求落地）**，6 个布局：
  `item_dsh_instance` / `item_dsh_version`（MaterialCardView→`FCLConstraintLayout`+`bg_container_white`+`auto_tint`）、
  `activity_dsh_download` / `activity_dsh_logs`（`SwitchMaterial`→`FCLSwitch`、按钮→`FCLButton`、进度→`FCLProgressBar`）、
  `activity_dsh_settings`（`TextInputLayout`+`TextInputEditText`→`FCLEditText`+标签、`Spinner`→`FCLSpinner`、8 按钮→`FCLButton`）、
  `view_dsh_bootstrap_banner`。文字一律 `FCLTextView`+`auto_text_tint` 随主题
- `DshUIManager`：工厂改为真实页面；回收页面时调用 `DshPageUI.destroy()`；构造参数由 `Context` 改为 `DshShellHost`
- `DshMainActivity` 实现 `DshShellHost`（`switchTab`/`openInstanceSettings`/`openWebView`）
- `DshRuntimeService` 通知点击目标由 `DshInstancesActivity` 改为 `DshMainActivity`
- `DshSettingsActivity`（仍保留为实例详情页）：`Spinner` 用法改为 `FCLSpinner` 的 `setItems`/`getSelectedItem`

### Notes
- 保留为独立全屏 Activity：`DshSettingsActivity`（实例详情设置）、`DshWebViewActivity`（dsh WebUI）——符合 FCL "详情页独立 Activity" 的惯例
- 旧并列 Activity `DshInstancesActivity`/`DshDownloadActivity`/`DshLogsActivity` 已被外壳页取代、主流程不再可达（遵循 app-shell.md "先不可达、后删除"，裁剪留到阶段 4）
- 验证：`run-compile.sh` BUILD SUCCESSFUL；**未打包**
- 仍待真机验证：ViewPager 内 PageUI 的协程生命周期、`FCLSpinner`/`FCLEditText` 在页面滑动时的焦点行为

---

## [Unreleased · 命名与版本规范化] - 2026-10-01

### Changed
- **版本号** 设为 `0.1.0-SNAPSHOT`（`versionName`），`versionCode = 100`——1.0.0 留给"能正常启动/下载/管理 dsh"之后
- **桌面显示名**（`app_name`）：`Fold Craft Launcher` → **`DSHarness-FCL-Launcher`**
- **包名**（`applicationId` + `namespace`）：`com.tungsten.fcl` → **`com.dsh.fcl.androidlauncher`**
  - 同步改 47 个文件的 `R`/`databinding`/`BuildConfig` import + `DshInstaller` 5 处内联全限定引用
  - `FileProvider` authority 跟随变为 `com.dsh.fcl.androidlauncher(.debug).provider`
  - `AndroidManifest` 中 `.FCLApp` / `.activity.SplashActivity` 改为全限定名（namespace 变更后相对名会错位）
- **APK 文件名规则**：`FCL-<buildType>-<ver>-<abi>.apk` → **`dsh-fcl-android-launcher-<ver>-<abi>.apk`**
  （即 `dsh-fcl-android-launcher-0.1.0-SNAPSHOT-arm64.apk`）
- 崩溃页提示 `crash_reporter_hint`（中英）中的 "Fold Craft Launcher" → 新名

### Added
- `strings.xml` 暂存关于页文案（阶段 2 设置页用）：
  `dsh_about_full_name` = "DeepSeek Harness FCL Android Launcher"、
  `dsh_about_subtitle` = "dshAndroidLauncher (FCL-based)"
- **确立硬规则：每次更改都必须记入 CHANGELOG**（见本文件顶部）

### Notes
- **源码物理包目录保持 `com/tungsten/*`、`com/mio`**（FCL 框架自身源码，`package` 声明未动）：
  只改"对外身份"（applicationId/namespace/显示名/APK 名/authority），不影响 APK 身份，且契合"基于 FCL 改造"的定位
- **仓库名** `dsh-fcl-android-launcher` 需在 GitHub 侧手动改（沙箱内改不了远程）
- 验证：`run-compile.sh` BUILD SUCCESSFUL；**未打包**（遵循"先不打包"约定）

---

## [Unreleased · 外壳改造阶段 1] - 2026-10-01

### Added
- 新建 dsh 启动器主外壳 `DshMainActivity` + `activity_dsh_main.xml`：照搬 FCL 形态——左侧 `FCLMenuView` 滑出菜单 + `ViewPager2` 内容区 + `FCLDynamicIsland` 动态岛标题
- 新增页面管理器 `DshUIManager`（仿已删的 FCL `UIManager`）：ViewPager2 承载 5 页、随生命周期创建/销毁不保留状态、瞬时切页、页面回调同步菜单高亮与标题
- 新增 5 个菜单：**实例 / 管理 / 下载 / 日志 / 设置**（「管理」为后续插件安装页预留）
- 新增阶段占位页 `DshPlaceholderUI` + `ui_dsh_placeholder.xml`
- 新增 4 个矢量菜单图标 `ic_dsh_{instances,manage,logs,settings}_24.xml`
- 新增 12 条中英文案（5 个 tab 名 + 管理页占位说明）
- 新增硬性设计规范 `docs/design/app-shell.md §2.5`：界面布局/按钮布局/整体主题一律跟 FCL 走（含 Material→fcllibrary 控件替换表、FCL 写法范式、验收命令）

### Changed
- 启动链路由 `SplashActivity → DshInstancesActivity` 改为 `SplashActivity → DshMainActivity`

### Notes
- 外壳全程**零 Material 控件、零 MC 单例**（不触碰 `ConfigHolder`/`RendererManager`）
- 阶段 1 内容区为占位页；阶段 2 将把 5 个独立 Activity 迁移为 `FCLCommonUI` 页面并换成 FCL 控件
- 验证：`run-compile.sh` BUILD SUCCESSFUL（Kotlin+Java+资源+Manifest）；**未打包**（遵循"先不打包"约定）

---

## [调研 · proot 运行时底座路线确定] - 2026-10-01

### Added
- 新增 `docs/design/wx-exec-proot-loader.md`：W^X 执行限制与 PROOT_LOADER 绕过方案（targetSdk 决策的最终答案，含真机实测数据）
- 新增 `docs/design/proot-engine-integration.md`：集成 [`oonid/pr`](https://github.com/oonid/pr) 的 `:proot-engine` 的架构决策（API/License/落地步骤/唯一拦路项）

### Changed
- **targetSdk 决策翻转**：由"降到 28"改为"保持 34，用 PROOT_LOADER 机制绕过 W^X"——有同类项目在真机（Android 16/SDK 36）验证通过

### Notes
- 确认 W^X **只禁 `execve` 数据目录文件、不禁 `dlopen`**（FCL 即反例）
- 确认 proot 自带 seccomp 过滤是 dead code、`PROOT_NO_SECCOMP` 无意义
- 唯一未决拦路项：dsh 子进程 `spawn` 能否在 patched proot 下工作（需真机验证，`cargo build` 在该方案下失败但 gcc 正常）
- 排除：termux-exec linker 法（bionic-only，加载不了 glibc）、memfd_create（内核标非可执行）

---

## [第六轮 · 评估与优化] - 2026-10-01 · `515270d`

### Fixed
- **(P0) rootfs 解压无可执行位**：`RuntimeUtils.uncompressTarXZ` 原来只给目录 `setExecutable`，普通文件保持无 x 位 → rootfs 里的 `/bin/sh`、`node` 解出来不可执行。新增 `restoreExecutableBit()`，按 tar mode 用 `Os.chmod` 还原
- **(P1) 硬链接解包成空文件**：tar 硬链接条目原落到普通文件分支、按 size=0 解出 0 字节空文件。新增 `isLink()` 分支（`Os.link` 优先，失败退 `Files.copy`）
- **(P1) 失效测试源集**：删除 15 个引用已删 MC 类的测试文件 —— 此前 `./gradlew test` / `assembleAndroidTest` 在编译期必然失败
- **(P2) 明文流量全局放行**：`network_security_config.xml` 默认禁止明文，仅放行 `127.0.0.1`/`localhost`（WebView 回环需要）
- **(P2) 云备份吞空间**：`backup_rules.xml` / `data_extraction_rules.xml` 排除 `files/dsh/`（rootfs + 每实例 ~300MB 不进自动备份）
- **(P2) 安装取消→重装竞态**：`DshInstaller` 引入"安装任务所有权"判定，消除两个 npm 同时写一个 node_modules、新任务被旧任务收尾误伤的窗口
- **(P2) 同一失败第二次不提示**：`DshInstancesActivity` 的通知标记在 Idle/Starting/Stopping 时清零

### Optimized
- **rootfs 解压提速 ≈18.5×**：删除每个 ≤20KB 条目的 `Thread.sleep(25)`（4003 条目实测 270s→14.3s），拷贝缓冲 1KB→64KB，进度回调改由 `DshBootstrap` 按时间节流（≤5 次/秒）
- 解压路径 `FileOutputStream` 改 try-with-resources，异常不再泄漏句柄

### Added
- 新增完整评估报告 `docs/reports/round6-optimization.md`（问题清单 R-01~R-21、实测基准、真机验证清单、死代码清单）
- 升级 `run-compile.sh`：覆盖 Kotlin + Java + 资源 + Manifest 四项（仍不打包）

### Notes
- 验证：编译 0 error；单测 23/23；脚本一致性 18/18

---

## [独立审查 · MC 移除影响] - 2026-10-01 · `a62ed0d`

### Fixed
- **(补丁 A, M-01)** `FCLPath.loadPaths()` 上提到 `FCLApp.onCreate`，并删除 `SplashActivity` 中的重复调用 —— 消除"必须先经过启动页"的隐式依赖（通知栏 PendingIntent 冷启动会绕过启动页）
- **(补丁 B, M-02)** `FCLPath.LOG_DIR` 由 `/sdcard/FCL/log` 改为 `context.getDir("log", 0)` —— 原路径在删存储权限后不可写，`fcl.log` 永远建不出来（异常被静默吞掉）

### Added
- 新增 `docs/reports/mc-removal-impact-review.md`（1166 行独立审查报告）与送审请求书 `mc-removal-review-brief.md`

### Notes
- 审查结论：删除边界正确、Manifest 四类零残留、ksp 清理完整、Node 协议零改动；P0=0 / P1=1 / P2=6 / P3=7
- 工作区固化为 git 提交，并备份 45 个 untracked 文件到 `_review_evidence/`

---

## [重大重构 · 移除全部 Minecraft] - 2026-10-01 · `4aea9e5`

> 规模：**1239 files changed, +6779 / −226529**。详见 `docs/reports/mc-removal.md`。

### Removed
- **Gradle 模块**：`Terracotta`（MC 联机，含 30MB native）、`LWJGL`
- **native 与运行时资产**：`jniLibs`（139MB）、`jni`（46 个 C/C++ 源）、`jreAssets`（4 套 JRE 共 154MB）、`libs/*.aar`（94MB GL 库）、`assets/{app_runtime,game,controllers,img}`
- **Java/Kotlin 包**（605 文件）：`fcl/{control,fragment,game,scoped,setting,terracotta,ui,upgrade}`、`fclcore/{auth,download,game,launch,mod}`、`activity` 下除 `SplashActivity` 外全部、`fclauncher` 除 `utils/{FCLPath,Architecture}`、`com/mio` 除 `util`、`com/oracle`、`org/`（SDL/LWJGL 胶水）
- **资源**（282 文件）：173 布局、95 drawable、10 个 MC 语言包、2473 条字符串

### Changed
- **`SplashActivity` 完全重写**（340 行→72 行）：删除 8 项 MC 运行时门禁、EULA、存储权限流程、整合包 intent 处理，改为直接进 dsh 界面
- **`AndroidManifest.xml`**：删 9 权限 / 6 Activity / 2 Service / 1 Provider / 1 activity-alias；启动页方向改竖屏
- **`build.gradle.kts`**：移除 `externalNativeBuild`/`ndkVersion`/`prefab`/`bytehook`/`jreAssets` 任务/LWJGL 裁剪逻辑/15 条 MC 依赖/ksp 插件
- `RuntimeUtils` 裁剪为仅保留 dsh 首启解压所需（`install`/`isLatest`/`uncompressTarXZ`）
- `ChecksumMismatchException` 父类改为 `java.io.IOException`（原父类随 MC 删除）
- `settings.gradle.kts` 只保留 `:FCL` + `:ZipFileSystem`

### Notes
- 保留：`fcllibrary`（UI 框架，维持 FCL 风格）、`fclcore/{fakefx,task,util,event}`、`com/dsh`（25 文件）、2 张背景图
- 变更后：494 源文件 / 71623 行 / 无 native；仓库仅剩 2 个 Gradle 模块
- 新增 `docs/reports/mc-removal.md`（供独立审查）

---

## [前期开发 · dsh 启动器核心 + 五轮加固] - 2026-09-20 ~ 09-23

> 基于 FCL（`f4f2624`）起步，分多轮搭建 dsh 启动器主体并做可靠性加固。详见 `docs/reports/round2~5-*.md`。

### Added
- **dsh 核心代码** `com/dsh/`（core 18 + ui 7）：
  - 运行时底座：`DshBootstrap`（首启解压 + 自检）、`ProotCommand`/`ProotProcessExecutor`（proot 命令单一构造）、`DshPaths`（私有目录布局）
  - 实例/版本管理：`DshInstances`（StateFlow 单例仓库 + 原子持久化）、`DshInstance`、`DshRegistry`（npm abbreviated metadata + 缓存 + 弱网回退）、`DshInstaller`（proot 内 npm install + 单飞 + 看门狗）、`DshVersionListItem`
  - 运行时：`DshRuntime`（起 `dsh web` + 抓 token URL + 状态机 + 孤儿认领）、`DshRuntimeService`（前台服务保活 specialUse）
  - 凭据：`DshCredentials`（Android Keystore AES-GCM，AAD=instanceId，不落明文）、`DeepSeekApi`（Key 测试连接）
  - 支撑：`DshLogBus`（环形缓冲 + 5Hz 合并 + token 脱敏 + 1MB 轮转）、`SingleFlight`（并发安全单飞闸门）、`DshAppScope`、`DshServices`
  - UI：实例/下载/日志/设置/WebView 5 个 Activity + 2 个 Adapter
- **PoC 验证**：在等价环境（aarch64 + proot + glibc + Node 22.23）实测 dsh web 起 UI（HTTP 200）、headless 调 deepseek-flash 返回、token→cookie 鉴权
- **沙箱构建能力**：装好 Android SDK 35 + NDK 27 + Gradle 8.14.4；解决 arm64 跑 x86_64 工具链（aapt2/cmake/ninja 的 qemu 转发）
- 文档体系 `docs/`：`PLAN`/`ROADMAP`/`PACKAGING`/`INDEX` + `design/` + `reports/`

### Fixed（第二~五轮加固，节选）
- **(第五轮 P0)** `dsh_instance_subtitle` 用 `%2$d` 却传字符串 → 进实例列表页必崩，改 `%2$s` + 新增资源占位符契约测试
- **(第五轮)** 迟到退出回调覆盖新实例状态（归属校验）、取消安装误标 BROKEN、并发错误按 instanceId 分表、按 tag 精确杀进程、kill 前读 `/proc/pid/cmdline` 防误杀、认领孤儿假死巡检
- **(第四轮 P0)** `DshRuntimeService.onStartCommand` 任何返回前先 `startForeground`（否则 Android 8+ 必崩）
- **(第四轮)** 状态观察协程泄漏、安装单飞竞态、主线程 Keystore 移 IO、下载 future 超时防线程泄漏、凭据原子写
- **(第三轮 P0)** 启动脚本 bash shebang + pipefail 却被 dash 调用 → 真机 100% 失败，改严格 POSIX sh；端口探测改用 node

### Notes
- 启动脚本 `assets/dsh/scripts/`（setup-node-dsh.sh / start-dsh.sh / probe.sh），严格 POSIX sh、18/18 一致性测试

---

## [基线] - 2026-09-20 · `f4f2624`

### Added
- Fold Craft Launcher 上游 main 分支（Minecraft: Java 版启动器），作为改造起点
