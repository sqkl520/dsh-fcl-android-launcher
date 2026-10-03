# Changelog

本项目：**DeepSeek Harness (dsh) 安卓启动器** —— 在未 root 的安卓手机上，用 proot 跑 dsh（Node 版编码/对话 agent），基于 FoldCraftLauncher (FCL) 改造。

> **仓库**：<https://github.com/sqkl520/dsh-fcl-android-launcher> ｜ **版本**：`0.1.0-SNAPSHOT`

格式参照 [Keep a Changelog](https://keepachangelog.com/)。由于项目尚未正式发版，各段以**工作阶段/里程碑**划分，并标注对应的 git commit。

> **★ 硬规则（项目约定）**：**任何代码/配置/文档的更改，都必须记入本 CHANGELOG**（在最上方的
> `[Unreleased]` 段按 Added/Changed/Fixed/Removed/Optimized/Refactored/Notes 分类追加）。
> 版本号规则：当前为 `0.1.0-SNAPSHOT`；**待"能正常启动 / 下载 / 管理 dsh"后才标 `1.0.0`**。
> **本文件只记"改了什么"**；经验 / 方法论 / 踩坑请写进 `LESSONS.md`，不要写在这里。

---

## [Unreleased · 前端缺口 G1：页内多页容器 + 标签栏] - 2026-10-03

> 按 `docs/reports/frontend-gap-vs-fcl.md` 的建议顺序，G5/G2 之后的第 3 步（结构性改造）。

### Added
- `DshMultiPageUI`：页内「多页容器」基类，结构照搬 FCL `FCLMultiPageUI` ——
  内层 ViewPager2 + 顶部 `FCLTabLayout` 联动；**禁用滑动**（只由 tab 切换）、**不预加载**、
  **不保留状态**（页面随 ViewPager 创建/回收）、tab 高亮与位置双向同步、
  过渡动画同 FCL（`alpha 0→1` + `translationY 30dp→0` / 250ms，仅位置真变时播放）
- `ui_dsh_multipage.xml`：多页骨架（CoordinatorLayout + `FCLAppBarLayout` + `FCLTabLayout` + `FCLUILayout`），
  照搬 FCL `ui_setting.xml`；tab 改为由子类动态添加（FCL 是 XML 静态 `TabItem`），便于复用
- 移植 FCL 通用组件：`FCLUILayout`、`com/mio/ui/widget/FCLAppBarLayout`
- 恢复资源：`bg_tab_top_rounded`、`ic_baseline_tune_24`、`ic_baseline_settings_24`、`ic_outline_extension_24`；
  `attrs.xml` 补 `FCLAppBarLayout` styleable

### Changed
- **设置 tab 改为页内多页**：`DshSettingsUI` 变成多页容器，子页 = 「启动器设置 | 关于」
  （对应 FCL 的 `SettingUI` = 版本设置/启动器设置/插件管理/关于；dsh 暂为 2 页）
- 原设置列表页改名为 `DshLauncherSettingsPage`（子页），并接受 `onOpenAbout` 回调
- 设置列表里的「关于本启动器」由弹窗改为**切换到「关于」子页**（FCL 里关于本就是设置子页）

### Removed
- `DshFullPageDialog`：G2 阶段为临时承载关于页而建，G1 落地后其用途被设置子页取代，删除以免留下死代码
  （将来需要"临时页/导航栈"时应采用 FCL 的 overlay 机制，而非对话框）

### Notes
- 修掉一个初始化顺序坑：`registry` 不能在基类属性初始化时按抽象 `pageCount` 分配
  （子类属性晚于基类构造初始化，会拿到 0），改为在 `onCreate` 阶段分配
- 验证：`run-compile.sh` 通过；单测 32/32；本轮未打包

---

## [Unreleased · 前端缺口 G5+G2：切页动画 + 关于页] - 2026-10-03

> 按 `docs/reports/frontend-gap-vs-fcl.md` 的建议顺序开工。

### Added
- **关于页**（G2）：`DshAboutUI` + `ui_dsh_about.xml` + `item_dsh_about.xml` + `item_dsh_about_desc.xml`，
  结构照搬 FCL `AboutPage`：顶部说明行 + 链接行合成一组（组首上圆角 / 组尾下圆角 / 中间无圆角，
  行背景 tint 用主题浅色 `ltColor`），组内 1dp 缝隙 + 主题色分割线，说明行与链接组间 8dp
  - 链接：项目仓库 / 基于 FoldCraftLauncher / proot 引擎(oonid/pr) / 许可证 GPL-3.0
  - 恢复图标 `ic_baseline_jump_24`（FCL 关于页的"跳转"图标）
- `DshFullPageDialog`：把任意 `DshPageUI` 全屏装进 FCL 对话框展示（创建时 `onCreate`、
  关闭时 `destroy` 取消协程）。用途：在「页内多页 + 标签栏」（T3）落地前先承载关于页，
  **T3 落地后同一个页面类直接作为设置子页复用，零返工**

### Changed
- `DshUIManager`：新增切页过渡动画（G5），与 FCL `UIManager.pageChangeCallback` 完全一致 ——
  目标页 `alpha 0→1` + `translationY 30dp→0`、250ms；**同步执行不 post**（避免"先显示再消失"的闪烁）、
  **仅在位置真的变化时播放**（布局变化重新 dispatch 当前页时不播）
- 设置页「关于本启动器」由弹窗改为打开关于页

### Notes
- 验证：`run-compile.sh` 通过；单测 32/32（`dsh_about_desc` 占位符已登记）；本轮未打包

---

## [Unreleased · 打包第二版（含首测反馈修复）] - 2026-10-03

### Changed
- 重新打包 `dsh-fcl-android-launcher-0.1.0-SNAPSHOT-arm64.apk`（312MB），
  内容包含本轮「进度可见性 / 设置页照搬 FCL / 移除冗余图标 / 日志页重做」的全部修复
- APK SHA-256：`e61a5939279985b7b2ade103555927250616e3301a2d72baeca6158b710c2b00`

### Notes
- 包内核验：`assets/dsh/rootfs/rootfs.tar.xz` 299.8MB（STORED）；
  `lib/arm64-v8a/` 含 `libproot.so`、`libproot-loader.so`、`libbusybox.so`、`libptyjni.so`；
  dex 内含新增类（`DshOptionDialog`、`DshLauncherSettingAdapter`、`TarLinkPolicy`、`SpacingItemDecoration`）
- 签名与上一版相同（debug key `FCL-Debug`），可直接覆盖安装
- 归档：`apk-archive/0.1.0-SNAPSHOT/`（APK 本体不入 Git，仅 SHA256SUMS）

---

## [Unreleased · 前端 vs FCL 对照审查] - 2026-10-03

### Added
- `docs/reports/frontend-gap-vs-fcl.md`：前端与 FCL 原版（基线 `647919c`）的逐项对照报告，
  含结构性缺口、组件/资源缺口清单、动画对照、有意保留的差异与建议实施顺序
- `docs/TASKS.md` 依对照结果重排（T1~T14）

### Notes
- 对照结论：结构性缺 4 项 ——
  **G1 页内多页容器 + 标签栏**（`FCLPage`/`FCLMultiPageUI`/`FCLUILayout`，缺失；
  导致设置页无法像 FCL 那样分「启动器设置/插件管理/关于」子页）、
  **G2 关于页**（FCL 有独立 `AboutPage` + `item_about*`，我们只有弹窗）、
  **G3 主题自定义整套资源**（`FCLColorPickerDialog`/`ColorPickerView`/`AlphaPatternDrawable`/
  `FCLNumberSeekBar` 等缺失 → 主题色/次要色/透明度/背景图都做不了）、
  **G4 文件/图片选择**（`browser/` 整包在阶段 4 被删 → 背景图无选择器）
- 另：G5 切页动画缺失 —— FCL 在 `UIManager.onPageSelected` 做「淡入 + 上滑 30dp / 250ms」，
  且只在位置真变时播放；我们的 ViewPager 配置已一致但无过渡动画
- 资源层：drawable FCL 112 → 我们 32（缺的多为 MC 图标，通用清单已列）；anim 6 → 4
  （缺 `frag_start_anim`/`frag_stop_anim`）；`values/` 5 个文件齐全，**样式层不缺**
- 已确认**不是**缺口的：ViewPager 配置（垂直/禁手势/不预加载/不保存）、动态岛 `setTextWithAnim`、
  基础控件（`FCLMenuView`/`FCLDynamicIsland`/`FCLSpinner`/`FCLSwitch`/`FCLProgressBar`/`FCLImageButton`/`FCLTabLayout`）
- 本轮仅文档与任务清单，未改代码；未打包

---

## [Unreleased · 真机首测反馈修复（进度可见性 / 设置页 / 图标 / 日志页）] - 2026-10-03

> 来源：APK 安装后用户实测反馈 5 条。

### Fixed
- **解压进度不可见 + 误报「已有解压任务在进行」**：解压任务原先跑在**页面级协程**上，
  切页/页面被 ViewPager 回收即被取消 → 对话框卡死、进度消失；再点一次撞上互斥守卫只得到一句报错。
  现在任务改到进程级 `DshAppScope`，进度经 `DshBootstrap.progress` / `busy` 两个 StateFlow 暴露，
  由实例页横幅渲染（进度条 + 阶段 + 明细 + 就绪后自动隐藏）；`DshBootstrap.install` 增加 `owner`
  参数，同一入口重复点击变为**幂等**而非报错
- 实例页右上角两个图标（日志 / 下载）与左侧菜单重复，且 `ic_dsh_logs_24` 形似汉堡菜单造成误解 —— 移除
- **默认资源 `values/strings.xml` 混入中文**（此前批量加文案时的失误）→ 改为英文并补齐 `values-zh` 对应项

### Changed
- **设置页按 FCL `LauncherSettingPage` 结构重做**：
  行高 48dp、`paddingStart/End 12dp`、label + 占位 + 动作按钮、描述 12sp；
  `SpacingItemDecoration`（**组内 1dp 细缝并绘制主题色分割线、组间 8dp 间距**、首行 10dp）；
  位置感知圆角（`bg_item_rounded{,_top,_middle,_bottom}`，从基线恢复 3 个变体）；
  行背景按主题色 tint，主题切换时重绘分割线
- 设置项重组为 4 组（通用 / 外观 / 运行环境 / DeepSeek Harness），并**新增 4 项可用设置**（行为照搬 FCL）：
  语言（`LocaleUtils.changeLanguage` + 重建界面）、主题模式（`launcher.themeMode` + `AppCompatDelegate` + `ThemeEngine.refreshTheme`）、
  动画速度（`setAnimationSpeed` + 持久化）、全屏/忽略刘海（`applyAndSave(context, window, checked)`）
- **日志页重做为控制台式视图**：深色控制台底（`bg_log_console`）、按级别着色
  （ERROR 红 / WARN 琥珀 / OK 绿 / 普通浅灰，`[tag]` 前缀青色）、级别筛选（全部/警告及以上/仅错误）、
  向上翻看时出现「回到底部」、信息行显示「展示行数 / 总行数 / 被筛掉 / 超限未显示 / 落盘路径」；
  单次最多渲染 1500 行

### Added
- `DshOptionDialog`：基于 `FCLDialog` + `FCLTextView` 的单选对话框（fcllibrary 缺通用选项对话框）
- 布局：`item_dsh_setting_switch.xml`（FCL 开关行）、`item_dsh_setting_group.xml`（小节标题）、`bg_log_console.xml`
- `docs/TASKS.md`：待办清单（含 **FCL 动画体系还原**、设置页剩余自定义项、真机验收等）

### Notes
- 验证：`run-compile.sh` 通过；单测 **32/32**（含新增 `dsh_logs_*` 占位符契约）；本轮未打包

---

## [Unreleased · 阶段 E-1：打包带 rootfs 的 APK] - 2026-10-03

### Added
- 产出首个**内含运行时底座**的 APK：`dsh-fcl-android-launcher-0.1.0-SNAPSHOT-arm64.apk`（312MB）

### Changed
- `FCL/build.gradle.kts`：新增 `androidResources { noCompress += listOf("xz") }`
  —— `rootfs.tar.xz` 已是压缩包，避免 aapt 二次 deflate（实测包内为 STORED）

### Fixed
- 打包环境：Android SDK 自带的 `cmake/3.22.1/bin/{cmake,ninja,cpack,ctest}` 是 x86_64，
  在 arm64 上直接 SIGILL（exit 132）导致 `externalNativeBuild` 失败；
  已用 `qemu-x86_64-static` 包装（与 NDK 工具链同一手法）

### Notes
- APK 内容核验：`assets/dsh/rootfs/rootfs.tar.xz` = 299.8MB（STORED）；
  `lib/arm64-v8a/` 含 `libproot.so`、`libproot-loader.so`、`libbusybox.so`、`libptyjni.so`
- APK SHA-256：`53bd1976e3f2cb28763f37ab70b30a551df689c29e31ff75904da3470c73ebda`
- 归档：`apk-archive/0.1.0-SNAPSHOT/`（APK 本体不入 Git，仅 SHA256SUMS 入库）
- 验证：`run-compile.sh` 通过、单测 32/32、`assembleFordebug` BUILD SUCCESSFUL；**尚未真机安装**

---

## [Unreleased · 第十一轮附加：UI 与 FCL 一致性还原] - 2026-10-03

> 要求：「界面布局 / 按钮布局 / 整体主题一律跟 FCL 走」（`design/app-shell.md` §2.5）。
> 本轮把 dsh 界面**逐部件对照 FCL 原版布局**（从 git 历史取出 `activity_main.xml` /
> `item_profile.xml` / `item_version.xml` / `item_launcher_setting_button.xml` /
> `page_setting_list.xml` / `LauncherSettingPage` + `LauncherSettingAdapter` 作真蓝本）后做了一次对齐。

### Fixed
- **根因：FCL 的通用 UI chrome 资产在「阶段 4 裁剪」时被一并删掉了** —— 没有它们，dsh 界面
  **不可能**长得像 FCL。已从历史恢复（均为纯 XML，无 MC 内容）：
  `bg_game_menu`（左侧菜单底）、`bg_right_menu`（右侧面板底）、`bg_item_rounded`（圆角行底）、
  `bg_container_transparent_clickable`（列表行透明+按压高亮）、`bg_progress` /
  `bg_progress_indeterminate` + `anim/progress_indeterminate_rect{1,2}`（FCL 进度条形态）、
  以及 `ic_baseline_{arrow_back,delete,refresh,content_copy}_24` 图标。
- **主外壳 `activity_dsh_main.xml` 与 FCL `activity_main.xml` 逐部件对齐**：
  左侧菜单补回 `bg_game_menu` + `elevation=100dp` + padding 5/10/5/10 + `clipChildren/clipToPadding=false`；
  菜单项由硬编码 40dp 改回 `wrap_content`（FCLMenuView 自带 8dp 内边距，FCL 写法）；
  **补回 `back` 返回项**（§4.2 明确要求保留）；右侧面板改回 FCL 形态
  （`bg_right_menu` + `layout_constraintWidth_percent=0.25` + `elevation=100dp` + 内层 `right_menu_content`，
  不再是白卡片）；**动态岛从顶部移到 FCL 原位（底部居中 + 15dp 底边距 + `stateListAnimator=@null`）**；
  补回 `video_view`（动态壁纸）与根 `transitionName="background"`；`ui_layout` 高度改回 `match_parent`。
- **主外壳代码补齐 FCL 行为**（`DshMainActivity`）：`back` 项 → `onBackPressedDispatcher`；
  动态壁纸 `setupLiveBackground()` + `onPause/onResume` 暂停恢复 + `onDestroy` `stopPlayback`；
  背景随主题刷新（`ThemeEngine.addRefreshListener` / `onDestroy` 注销，FCL 同款）。
- **列表行对齐 FCL 范式**：
  `item_dsh_instance.xml` 改为照搬 `item_profile.xml`（实例列表 ↔ FCL 档案列表）——
  透明容器 `bg_container_transparent_clickable` + `padding=10dp` + `clickable/focusable`
  + `stateListAnimator=@xml/anim_scale`（**原来完全没有按压反馈**）+ 文字改用 `use_theme_color`；
  `item_dsh_version.xml` 改为照搬 `item_version.xml`——外层 `paddingBottom=8dp` 作行间距 +
  内层 `bg_container_white` + `auto_tint` + `focusable` + `padding=5dp` + `anim_scale`；
  进度条改为 FCL 的 3dp 细条 + `bg_progress_indeterminate`。
- **设置行与设置页对齐 FCL**：
  `item_dsh_setting.xml` 改为照搬 `item_launcher_setting_button.xml`——
  `bg_item_rounded` + 左右 12dp + 横向行 `minHeight=48dp`/上下 8dp + 标签左/动作右 + 描述在下（12sp）；
  `ui_dsh_launcher_settings.xml` 对齐 `page_setting_list.xml`（左右 10dp、`clipToPadding=false`、
  `paddingBottom=10dp` + 居中空态）；
  **恢复 `com.mio.ui.adapter.SpacingItemDecoration`** 并接进设置页——组内相邻行 1dp 分割线（主题色绘制）、
  跨组 8dp 间距、首行 10dp；`DshLauncherSettingAdapter` 去掉 FCL 没有的「分组标题行」，
  改为每行带 `SettingGroup` + `isNextInSameGroup()`（与 FCL `LauncherSettingAdapter` 同构）。
- **页内头部去 Material 化**：三个外壳页（实例/下载/日志）删掉页内 20sp 大标题
  （FCL 的页名由**外壳底部动态岛**显示，页内不重复放大标题），页内动作按钮由文字
  `FCLButton` 改为 `FCLImageButton`（`use_theme_color` + `no_padding` + `anim_scale_large`），
  与 FCL `item_profile` / `item_download_task` 的图标按钮写法一致。
- **16 处 `MaterialAlertDialogBuilder` 全部换成 `FCLAlertDialog`**（§2.5.2 明确要求）：
  `DshLauncher`(5) / `DshSettingsActivity`(3) / `DshInstancesUI`(3) / `DshDownloadUI`(3) / `DshSettingsUI`(2)。
  按 FCL 惯例：错误/危险用 `AlertLevel.ALERT`、信息用 `INFO`；单按钮对话框用
  `setNegativeButton(dialog_positive, null)`；进度对话框改用 `FCLAlertDialog.setMessage()` 就地刷新；
  关于页启用 `useAutoLink()` 让仓库链接可点。**`com/dsh` 下 `MaterialAlertDialogBuilder` 归零**。
- **`activity_dsh_settings.xml`**：区块改为 `FCLLinearLayout` + `bg_item_rounded` +
  `auto_linear_background_tint`（FCL 圆角行容器），页内标题 20sp → 16sp（FCL `dialog_edit` 同款）。

### Removed
- 本轮恢复后**确认无人引用**的资产（避免留死资产，需要时可再从历史取）：
  `bg_game_menu_inset`、`bg_item_rounded_{top,middle,bottom}`、`bg_container_white_clickable`、
  `bg_container_transparent_selected`、`right_arrow`、`transparent`、
  `ic_baseline_{settings,more_horiz,edit,arrow_forward,restore}_24`。

### Notes
- **验证**：`run-compile.sh` BUILD SUCCESSFUL；单测 **32/32**；脚本一致性 **18/18**；
  §2.5.5 三条验收：① dsh 布局里 `com.google.android.material` = **0**；
  ② 12 个 dsh 布局全部含 fcllibrary 控件（`ui_dsh_launcher_settings` 由 0 → 1）；
  ③ `com/dsh` 下 `MaterialAlertDialogBuilder` = **0**、`FCLAlertDialog` 37 处。**未打包、未上真机**。
- **有意保留的差异（1 处，已论证）**：FCL 的设置行是**纯白**（`bg_item_rounded` 不带 tint），
  暗色主题下也是白块；dsh 用 `FCLLinearLayout.auto_linear_background_tint` 让它跟随主题。
  几何/结构/内边距与 FCL 完全一致，仅颜色改为主题驱动（暗色下可读性）。
- **仍存在的差异（待后续）**：FCL 的列表行动作是 `FCLImageButton` 图标按钮，dsh 的实例行仍用文字
  `FCLButton`（FCL 自己的 `item_launcher_setting_button` 也用文字按钮，故不算违规，但可再统一）；
  `PtyNative` / `libbusybox.so` 仍是「编进去了没人用」。

---

## [Unreleased · 第十一轮评估与优化：修复 2 个 P1（真机阻塞级）] - 2026-10-03

> 全文：`PROJECT_REVIEW_AND_OPTIMIZATION.md`（速览：`reports/round11-review-and-optimization.md`）。
> 审查对象：git HEAD `d82f409`（阶段 A~D 已落地）+ 第十轮 6 文件改动（未提交）+ 本轮改动。

### Fixed
- **（P1）rootfs 解压把符号链接目标里的 `..` 改写成宿主路径 → `/opt/node22/bin/npm` 变断链**：
  `RuntimeUtils.uncompressTarXZ` 沿用 FCL 上游的
  `Os.symlink(linkName.replace("..", dest.getAbsolutePath()), ...)`。本项目 rootfs 里数百个链接命中，
  其中 `opt/node22/bin/npm -> ../lib/node_modules/npm/bin/npm-cli.js` 会被写成
  `<rootfs>/lib/node_modules/npm/bin/npm-cli.js`（**实测不存在**，正确目标在
  `<rootfs>/opt/node22/lib/...`，**实测存在**）。后果：真机首启解压后 `npm --version` 失败 →
  `probe.sh` 的 `npm` 项 FAIL → `DshBootstrap` 自检不通过 → **底座永远不就绪**，安装/启动全挂。
  chroot 里直接验证 rootfs 时（不经过该解压路径）是好的，所以此前几轮都没暴露。
  现在改为**原样保留链接目标**（tar 语义），策略抽到 `com.dsh.core.TarLinkPolicy.symlinkTarget`
  并加单测守住（防回归）。
- **（P1）阶段 D-1 的「预装 dsh 跳过下载」优化未与 Kotlin 侧判定口径对齐 → 装完必判 BROKEN**：
  `setup-node-dsh.sh` 命中预装版本时打印 `DONE source=preinstalled` 并**跳过下载**，实例目录里
  因此**没有** `node_modules`；但 `DshInstaller.readInstalledVersion` / `DshInstances.repair` /
  `DshRuntime.startLocked` 都只看实例内的
  `node_modules/@deepseek-ai/dsh/package.json` —— 于是「刚装成功」的实例被判
  `dsh_install_incomplete` → **BROKEN**，且重装重复同一结果（永久循环），启动也会报「未安装」。
  现在新增 `DshPaths.effectiveDshDir(instanceDir, rootfsDir)`（实例优先 → rootfs 预装回退 →
  都没有则返回实例预期路径），三处判定统一走它。已用 JVM harness 对**真实 rootfs** 复现：
  旧逻辑 `null`（→BROKEN），新逻辑 `0.1.6-alpha.2`（→READY）。
- **（P3）`installVersion` 跳过安装却提示「正在安装」**：`DshDownloadViewModel.installVersion`
  改为返回 `InstallDispatch(instance, started)`；`DshDownloadUI` 据此选择文案
  （新增 `dsh_install_skipped`，已登记进资源契约测试）。该路径在 R11-02 修好后**变得常见**
  （预装版本装完即 READY，再点即命中「已装同版本」），所以一并修掉。
- **（P3）`ProotCommand.preflight()` 的 rootfs 判据与入参不一致**：它收 `rootfsDir` 参数，
  却调用无参的 `DshPaths.rootfsLooksUsable()`（看的是全局 `ROOTFS_DIR`）。新增
  `DshPaths.rootfsLooksUsable(root: File)` 重载，预检改用入参。

### Added
- `FCL/src/main/java/com/dsh/core/TarLinkPolicy.kt` —— tar 链接目标还原策略（唯一出处 + 单测抓手）
- `DshPaths.PREINSTALLED_DSH_REL` / `effectiveDshDir` / `effectiveDshPackageJson` /
  `effectiveDshBinJs` / `rootfsLooksUsable(File)`
- 单测 +5（共 **32/32**）：`prefersInstanceDshOverPreinstalled`、`fallsBackToPreinstalledDsh`、
  `returnsInstancePathWhenNothingInstalled`、`doesNotFallBackToIncompletePreinstalled`、
  `symlinkTargetsAreKeptVerbatim`

### Removed
- `DshPaths.instanceDshPackageJson` / `instanceDshBinJs`（被 `effectiveDsh*` 取代，避免两套口径）

### Notes
- **验证**：`run-compile.sh` BUILD SUCCESSFUL（0 error，仅 2 条既有 `onBackPressed` 弃用告警）；
  单测 **32/32**（第十轮 27/27 + 本轮 5）；脚本一致性 **18/18**；
  两个 P1 均用**独立 harness + 真实资产**复现（符号链接目标 `readlink -f` 对照；
  真实 rootfs 的预装 dsh 版本解析）。**本轮未打包、未上真机**。
- **仍未做（沿用前几轮）**：CI 重写、签名私钥外移 + release 混淆、WakeLock、
  PTY/busybox 去留决策、registry 缓存落盘、日志增量渲染、`rootProject.name`。
- **平台项待真机确认**：W^X / PROOT_LOADER；本轮修的符号链接与预装解析已用真实资产在沙箱内验证，
  但**解压动作本身**（`Os.symlink`）只能在真机跑，属「机制已修、真机待验」。
- **待确认（新增，P3）**：`values-zh` 缺 `dsh_about_full_name` / `dsh_about_subtitle`
  两条**无占位符**文案 → 中文界面「关于」页回退英文（资源契约测试只覆盖带占位符的文案，抓不到）。

---

## [Unreleased · 第十轮评估与优化：修复 2 个 P1 + 4 个 P2] - 2026-10-03

> 全文：`PROJECT_REVIEW_AND_OPTIMIZATION.md`（速览：`reports/round10-review-and-optimization.md`）。
> 审查对象：git HEAD `d82f409`（阶段 A~D 已落地）+ 本轮 6 个文件的改动。

### Fixed
- **（P1）资源契约测试红 → 绿**：`DshSettingsUI` 新增的 `@string/dsh_about_version`（`版本：%1$s`）
  带占位符却未登记进 `DshResourceFormatTest.callSites`，导致单测 `26/27`（FAILED=1）。
  "占位符类型不匹配 → 真机闪退"这类 P0 的护栏因此失效。已补登记，恢复 `27/27`。
- **（P1）`RuntimeUtils.isLatest` 不再假设版本号是数字**：`assets/dsh/rootfs/version` 已是语义化字符串
  （`debian-bookworm-arm64-node22-dsh0.1.6-alpha.2-layout2`），旧实现无条件 `Long.parseLong` ——
  一旦 classpath 资源可解析就抛 `NumberFormatException`（`isReady()` 被吞成"永远不就绪"，
  `install()` 直接把首启解压判失败）。改为"字符串相等优先 + 纯数字时保持数值语义"，
  并把同一个 stream 只读一次（旧实现第二次可能拿到 null → NPE）。已用 JVM harness 复现旧行为并验证新行为。
- **（P2）删除实例不再误杀别的实例**：`DshInstances.delete()` 原来无条件调用
  `DshRuntime.stopAndWait()`，而它停的是**当前正在运行的那个实例**（单实例策略），与被删 id 无关 ——
  删一个没在跑的实例会把正在跑的那个杀掉。现加归属校验（仅被删实例在跑时才停）。
- **（P2）JNI 符号名与 Kotlin 类对齐**：`ptyjni.c` 的 7 个符号仍是上游
  `Java_id_or_oo_pr_engine_PtyNative_*`，而 Kotlin 侧类是 `com.dsh.core.PtyNative` ——
  任何一次调用都会 `UnsatisfiedLinkError`（当前无调用方，属潜伏缺陷）。已改为
  `Java_com_dsh_core_PtyNative_*` 并加注释说明"改包名必须同步"。
- **（P2）就绪判据补上"执行位"这一维**：`DshBootstrap.isReady()` 只查 proot `isFile`，
  而 `ProotCommand.preflight()` 查 `exists() && canExecute()` —— 无执行位时"横幅隐藏但一启动就报错"
  （第九轮修的同类"假就绪"漏了这一维）。`isReady()` / `missingSummary()` / `install()` 已对齐。
- **（P2）rootfs 升级可回滚**：原来"先删旧目录、再 rename 新目录"，两步之间失败会**同时失去新旧两份**
  rootfs。改为"旧目录改名 `.old` → 新内容上位 → 校验 → 成功删备份 / 失败回滚"。
- **（P2）空间门槛按实测值给，并移到真正要解压的分支**：原 `MIN_FREE_BYTES = 1500 MiB`
  (=1,572,864,000 B) 比**仅 rootfs 解压后的实测体积**（`du` = 1,607,908,864 B）还小；
  且检查发生在"是否需要解压"判定之前，导致"rootfs 已就绪、只补脚本"的场景被假失败。
  现按实测值（1.65 GiB + 600 MB node_modules）计算，并按首装（1 份）/ 升级（2 份）分别判定。
- **（P3）`ProotProcessExecutor` 登记表在异常路径也收敛**：`actives` 原来只在正常路径清理，
  读输出抛异常会留下死进程记录；现移入 `finally` 且只清**已退出**的进程（存活的必须留表，
  否则"取消安装"找不到它）。

### Removed
- `DshBootstrap.prootDir()`（阶段 A 改为 jniLibs 方案后已无调用方）。

### Notes
- **验证**：`run-compile.sh` BUILD SUCCESSFUL（含原生 ptyjni 36,312 B）；单测 **27/27**
  （修复前 26/27）；脚本一致性 **18/18**；`nm -D libptyjni.so` 确认符号名；
  JVM harness 复现旧 `NumberFormatException`；`du` 实测 rootfs 体积。**本轮未打包**。
- **仍待处理**（详见报告 §3）：CI 重写（R10-10）、签名私钥外移 + release 混淆（R10-11）、
  WakeLock（R10-12）、PTY/busybox 去留决策（R10-13）、registry 缓存落盘、WebView 竖屏、
  `FCLPath` 死配置、`rootProject.name` 更新。
- **平台项待真机确认**（R10-19）：W^X / PROOT_LOADER / `getResourceAsStream("/assets/...")` 在真机上的行为。

---

## [Unreleased · 阶段 D-1：脚本与预装 dsh 对齐 + 运行时自检] - 2026-10-03

### Added
- `probe.sh` 重写为**真实运行时自检**（9 项，逐项打印 `dsh-probe: <项> = ok|FAIL`）：
  rootfs 布局、node 版本（^22.19 || >=24）、bash、`child_process.execSync`、
  `spawnSync(/bin/bash)`、`spawnSync(/bin/sh)`、npm、预装 dsh、原生模块 `dlopen`；
  全部通过时输出 `dsh-probe-ok`

### Changed
- **guest 内布局调整**（避免被 bind 遮蔽）：rootfs 内 Node 移到 `/opt/node22`，
  预装 dsh 移到 `/opt/dsh-preinstalled`。原因：`ProotCommand` 会把 `filesDir/dsh` 绑定到 guest 的
  `/opt/dsh`，原先把 node/dsh 放在 `/opt/dsh/` 下会在设备上被挂载点遮蔽而"消失"
- `ProotCommand.DEFAULT_PATH`：`/opt/dsh/node/bin` → `/opt/node22/bin`
- `start-dsh.sh`：dsh 入口按 **实例 → 预装 → node 解析** 三级解析，并打印 `dsh src=` / `dsh ver=`；
  PATH 前缀改为 `/opt/node22/bin`；**禁用原生模块加载器的硬链接缓存**（`NARB_DISABLE_NATIVE_CACHE=1`）
- `setup-node-dsh.sh`：请求版本与预装一致（或 `latest`）且实例无 `node_modules` 时，
  **直接采用预装版本并跳过下载**（打印 `DONE ... source=preinstalled`），版本不同才装到实例目录
- `scripts/version` 2 → 3（使设备重新解压脚本）
- rootfs 重新打包：300MB；SHA-256=`5d762c30b9117830518571bc4eeadf593182cc56d1f472024ba4490aa90682a7`；rootfs version 标记为 `layout2`

### Notes
- 关键修复：dsh 启动报 `No usable native binding found for node-addon-require-builtin-linux-arm64-gnu`，
  根因是加载器把 `.node` **硬链接**到 `os.tmpdir()` 缓存时失败（`EINVAL ... readlink`）；
  关闭该缓存后正常
- 实测（chroot 内，等价 Linux 用户态）：`probe.sh` 9/9 通过；`setup-node-dsh.sh` 三种分支判定正确；
  `start-dsh.sh` 端到端拉起 dsh web 并输出 `READY url=http://127.0.0.1:3080/?token=...`
- 验证：`run-compile.sh` BUILD SUCCESSFUL + ptyjni OK；本轮未打包

---

## [Unreleased · 阶段 C：宿主侧最小运行链验证] - 2026-10-03

### Added
- rootfs 内恢复 `perl` / `perl-base` / `bzip2`（此前清理时误删），guest 内 `apt-get` / `dpkg` 恢复正常

### Changed
- 重新制作 `rootfs.tar.xz`（打包时排除沙箱的 `.l2s.*` 条目），更新 `assets/dsh/rootfs/version`
- rootfs 归档更新为 300MB；SHA-256=`ee54ddff773395c0c388871388af010d9b11241ae04650a9848488eee2affbfb`（本地文件，不提交 Git）

### Notes
- **关键验证（沙箱内实测，patched proot 为静态 aarch64 可执行文件）**：
  - `proot -r <rootfs>` 可正常执行 rootfs 的 `/bin/sh`
  - `PROOT_LOADER=<外部 loader>` 机制生效（`-0` 伪 root 得到 uid=0）
  - proot 内 `node --version` / `bash --version` 正常
  - **proot 内 `child_process` 全部通过**：`execSync`、`spawnSync('/bin/bash')`、`spawnSync('/bin/sh')`
    —— 即此前判定的"头号拦路项（子进程 spawn）"在 proot 下可用
  - guest 内 `npm install` 可用，含需要编译工具链的原生模块（`node-pty` 安装成功）
  - tar 完整性：解压后 chroot 实跑 `node --version` / `npm --version` / `dsh --version`（=0.1.6-alpha.2）均正常
- **尚未验证**：真机（Android targetSdk 34 + SELinux）下 proot 内的 npm/dsh 运行
  —— 沙箱的双层 proot 会让内层 `stat` 返回 ENOENT，无法在此环境验证该环节
- 验证：源码编译通过；本轮未打包

---

## [Unreleased · 阶段 B：Debian arm64 rootfs 与 Node/dsh 基线] - 2026-10-03

### Changed
- 选择并固定第一版运行时组合：Debian arm64 slim + glibc + 官方 Node.js 22.23.3 + npm 10.9.9 + bash/coreutils/ca-certificates/procps
- 使用预打包 rootfs 基线，安装 `@deepseek-ai/dsh@0.1.6-alpha.2`
- `ProotCommand.DEFAULT_PATH` 增加 `/opt/dsh/node/bin`，为 rootfs 内官方 Node 提供稳定 PATH
- 移除 rootfs PLACEHOLDER 标记，更新 rootfs version 元数据；实际 `rootfs.tar.xz` 作为本地 APK 打包输入，不提交 Git

### Notes
- rootfs 本地归档：279MB；SHA-256=`16badbfc94112218a98c684ac88734c96303ff4b50cf4260f7e83204b98e1e77`
- 基础验证：Node/npm/bash、`child_process.execSync`、`child_process.spawnSync('/bin/bash')`、`dsh --help` 均通过
- 尚未在 patched proot + Android 真机上验证；`.node`、插件、npm、shell tool 仍是下一步验收项
- 验证：源码编译已通过；本轮未打包

---

## [Unreleased · 阶段 A：接入 proot 底座（jniLibs + PTY）] - 2026-10-03

### Added
- 接入 `oonid/pr` 固定 commit `fcf25cb` 的原生产物到 `FCL/src/main/jniLibs/arm64-v8a/`：
  `libproot.so`、`libproot-loader.so`、`libbusybox.so`（sha256 记录在 `design/proot-engine-integration.md §13`）
- 新增 `src/main/cpp/ptyjni/`（`ptyjni.c` + `CMakeLists.txt`）：PTY 原生桥接（MIT，来自 `:proot-engine`）
- 新增 `com/dsh/core/PtyNative.kt`：PTY 原生接口（forkPty / read / write / resize / waitPid / close）
- `DshPaths.resolveBusybox()`：解析 jniLibs 的 `libbusybox.so`
- `FCL/build.gradle.kts` 增加 `externalNativeBuild` 指向 `src/main/cpp/ptyjni/CMakeLists.txt`

### Changed
- `DshPaths.resolveProotLoader()` 回退文件名修正为 `libproot-loader.so`
- `DshBootstrap.isReady()` / `missingSummary()`：proot 与 loader 一律以 **jniLibs** 为准，
  不再要求 assets 中存在 proot 副本与版本文件
- `DshBootstrap.install()`：删除"从 assets 解压 proot"的分支，改为校验 jniLibs 里的
  `libproot.so` / `libproot-loader.so` 是否齐备；缺失时明确报出缺哪个文件（属打包问题）
- `run-compile.sh`：新增原生 `ptyjni` 编译检查步骤

### Notes
- 本批次**未接入** `libpr-cli.so`（第一版 rootfs 预打包、沿用现有 `DshInstaller`，不需要 pr-cli 的发行版/OCI 管理）
- 修复构建环境问题：NDK 工具链被 qemu **双重包装**（38 个工具），已还原（判据与修复方法记入 `LESSONS.md §1.3`）
- 验证：`run-compile.sh` BUILD SUCCESSFUL + `libptyjni.so` 36440 B；**未打包**
- rootfs 仍是 PLACEHOLDER，运行时尚未真机跑通

---

## [Unreleased · FCL 风格启动器设置页第一批实现] - 2026-10-03

### Added
- 新增 FCL 风格全局设置页布局 `ui_dsh_launcher_settings.xml`
- 新增设置行布局 `item_dsh_setting.xml`
- 新增 `DshLauncherSettingAdapter`：使用 RecyclerView 分组行组织通用 / 运行环境 / dsh 设置

### Changed
- `DshSettingsUI` 从实例详情表单改为全局启动器设置页
- 设置页接入真实入口：运行时自检、日志、dsh 版本管理、实例管理、复制日志、关于页
- 实例级配置继续由独立 `DshSettingsActivity` 承载，职责与全局启动器设置分离
- `DshUIManager` 设置页工厂改为传入 `DshShellHost`

### Notes
- 本批次只实现 FCL 设置页的结构、分组和 dsh 入口；ThemeEngine 的语言/主题色/背景/动画等可编辑设置将在下一批接入
- 验证：`run-compile.sh` BUILD SUCCESSFUL；未打包
- FCL 原版对应关系与后续还原清单见 `docs/design/fcl-ui-restoration.md`

---

## [Unreleased · FCL 原汁原味 UI 还原审查] - 2026-10-03

### Added
- 新增 `docs/design/fcl-ui-restoration.md`：记录 FCL 启动器设置页的原版结构、dsh 映射、保留/删除/替换清单、设置分组草案与验收标准

### Notes
- 本轮先完成 FCL 基线对照，尚未改设置页代码；后续按文档逐步将全局设置改成 FCL 风格 RecyclerView 分组设置
- MC 专属设置不恢复，改成 dsh runtime / API Key / 模型 / profile / 实例管理等对应功能

---

## [Unreleased · UI 优化：恢复 FCL 主题背景] - 2026-10-03

### Changed
- `DshMainActivity` 接入 FCL `ThemeEngine` 主题背景：使用 `background_light.jpg` / `background_dark.jpg`
  设置主外壳背景，跟随系统亮暗模式切换
- 保持当前 dsh 三栏结构不变：左侧菜单 / 中间 ViewPager2 / 右侧实例面板；只补回 FCL 原版背景层，避免重新引入 MC UI

### Notes
- FCL 上游完整仓库因大体积网络传输限制未重新 clone；本次使用本地 FCL 基线提交中的原版 UI 模板作对照
- 验证：`run-compile.sh` BUILD SUCCESSFUL；未打包

---

## [Unreleased · APK 归档] - 2026-10-03

### Added
- 新增 `apk-archive/` 版本归档目录，按版本号分文件夹保存 APK 与 `SHA256SUMS`
- 归档当前版本 APK：`apk-archive/0.1.0-SNAPSHOT/dsh-fcl-android-launcher-0.1.0-SNAPSHOT-arm64.apk`
- `build-apk.sh` 改为每次打包后自动复制 APK 并更新对应版本校验文件
- `.gitignore` 默认排除 APK 二进制，仅保留归档说明与 SHA-256 校验文件

### Notes
- 当前 APK 仍保存在工作区，默认不提交到 Git，避免仓库被二进制膨胀

---

## [Unreleased · 打包脚本适配与 APK 产出] - 2026-10-03

### Fixed
- 修正 `build-apk.sh`：移除已删除的旧 native CMake 配置流程（不再访问 `FCL/src/main/jni`），改为当前无 native 工程的 Gradle arm64 打包流程
- 打包脚本统一复制并校验最终 APK，输出固定为 `dsh-fcl-android-launcher-0.1.0-SNAPSHOT-arm64.apk`

### Notes
- APK 已成功产出：`/workspace/dsh-fcl-android-launcher-0.1.0-SNAPSHOT-arm64.apk`
- SHA-256：`12520a91df56245c4ee33de54186a3fde29b482df4d94dd190d8b8eb241d1013`
- 文件大小约 11MB；本轮是首次实际打包，尚未真机安装验证

---

## [Unreleased · 第九轮审查与优化] - 2026-10-02

> 本轮 = 第八轮之后的独立复审。重点：第八轮遗留的\"使用逻辑\"缺口（下载页空实例污染、isReady 与
> preflight 判据不一致）。完整报告见 `docs/PROJECT_REVIEW_AND_OPTIMIZATION.md`；
> 速览见 `docs/reports/round9-review-and-optimization.md`。
> 代码改动：`DshBootstrap.kt` + `DshDownloadViewModel.kt` + 新增 4 个单测（见下）。
> 验证：编译 BUILD SUCCESSFUL（`run-compile.sh`）；单测 **27/27**（原 23 + 新增 4）；脚本一致性 18/18。

### Changed

**1. `DshBootstrap.isReady()` 补 proot 二进制存在性校验（P2，正确性/一致性）**
- 问题：原 `isReady()` 只校验版本文件 + `start-dsh.sh` + rootfs 布局，**不校验 proot 是否可解析**；
  而 `missingSummary()` / `ProotCommand.preflight()` 都把 proot 当作必要条件。于是可同时出现
  \"`isReady()==true`（bootstrap 横幅隐藏）但一启动就 preflight 报『缺少 proot 可执行文件』\"的自相矛盾。
- 改动：`isReady()` 增加 `&& DshPaths.resolveProotBin(FCLPath.NATIVE_LIB_DIR).isFile`（jniLibs 优先、assets 回退）。
- 影响：横幅的\"是否提示准备运行时\"与真实可启动性一致；纯增量校验，对正常就绪设备零行为变化。

**2. `DshBootstrap.install()` proot 分支补\"文件缺失即解压\"兜底（P2，可靠性）**
- 背景：第八轮只给 scripts 分支加了文件存在性兜底，proot 分支仍只依赖 `isLatest`。一旦
  `getResourceAsStream` 在该环境不可解析（`isLatest` 恒为 true），assets 方案的 proot 会被
  **永远跳过解压**，与本轮 `isReady()` 新校验互相印证后会形成一个\"永远不具备\"的空转。
- 改动：解压条件改为 `!isLatest(...) || prootMissing`，`prootMissing` 用 `resolveProotBin()`（jniLibs 优先）判定，
  避免 jniLibs 已就位时因 prootDir 无 `libproot.so` 而每次重复解压 assets 副本。
- 影响：幂等\"缺二进制即补\";jniLibs 主路径不受影响。

### Added

**3. 下载页\"反复点安装\"去重（P2，数据卫生，承接第八轮建议 R8-08）**
- 问题：`DshDownloadViewModel.installVersion()` 每次点击都 `DshInstances.create()` 新建实例；
  用户换版本/重复点击会在实例列表积累一堆只有目录、没装 dsh 的**空实例**。
- 改动：
  - 新增纯函数 `DshDownloadViewModel.chooseInstanceToInstall(instances, version)`：
    ① 已 READY 装过同版本 → 复用（不重复触发安装/重新下载依赖树）；
    ② 否则有 NOT_INSTALLED 空壳 → 复用它（避免累积）；③ 都没有 → 新建。
  - `installVersion()` 改用该函数。
- 影响：误点/重复安装不再污染列表；对\"本来就要新建\"的正常路径行为不变。
- 单测：`DshCoreLogicTest` 新增 4 例（复用同版本 / 不同版本不复用 / 复用空壳 / 无可复用则新建）。

---

## [Unreleased · 第八轮审查与优化] - 2026-10-02

> 本轮 = 独立复审（第七轮之后），重点核查前七轮可能遗漏的**使用逻辑/资源机制/安全卫生**类问题。
> 完整报告见 `docs/PROJECT_REVIEW_AND_OPTIMIZATION.md`；速览见 `docs/reports/round8-review-and-optimization.md`。
> 代码改动：Manifest + `DshBootstrap.kt` 两处（见下）。

### Changed

**1. 移除 AndroidManifest 全局明文开关（P3，安全卫生）**
- `android:usesCleartextTraffic="true"` 与 `@xml/network_security_config` 并存：API 24+（本工程 minSdk 26）起 NSC 优先，此开关实际是死配置；
  且它向阅读者传达\"全局允许明文\"的错误印象（实际 NSC 已收口到 127.0.0.1/localhost）。
- 删除该行，明文策略以 `network_security_config.xml`（base 禁明文 + 回环放行）为唯一真源。
- 影响范围：仅 Manifest 属性；WebView 加载 `http://127.0.0.1:<port>` 仍由 NSC 的 domain-config 放行，行为不变。

**2. `DshBootstrap` 首次解压补\"文件存在性\"兜底（P1→P2，可靠性）**
- 背景：`RuntimeUtils.isLatest(...)` 用 `Class.getResourceAsStream("/assets/...")` 读版本号，
  与解压用的 `context.getAssets().open("dsh/...")` 是**两套访问机制**。若前者在任何环境解析不到（返回\"已是最新\"），
  仅靠 `isLatest` 判定的 `DshBootstrap.install` 会**永远跳过首次解压** → scripts/probe.sh、start-dsh.sh 缺失，
  实例永远起不来，而 `isReady()` 也可能误报\"就绪\"。这是前七轮未覆盖的静默失败路径。
- 改动：
  - `isReady()` 增加 `&& File(SCRIPTS_DIR, "start-dsh.sh").isFile`；
  - `install()` 的脚本解压条件改为 `!isLatest(...) || !start-dsh.sh.isFile`。
- 性质：纯\"文件缺失即补解压\"的幂等防护；`isLatest` 正常工作的环境行为不变。
- 影响范围：仅底座脚本（scripts）首次/修复解压；rootfs 分支已有 `rootfsLooksUsable()` 防护，proot 走 jniLibs 不受影响。

### Notes

- 验证：`run-compile.sh` **BUILD SUCCESSFUL**（1m31s，5 executed / 29 up-to-date）；单测 23/23；脚本 18/18；**未打包**。
- 本项 `isLatest` 的资源机制是否在真机可解析属**待真机确认**（不要仅凭机制推理下死判断）——本次先做不改变正常工作路径的防御性兜底。

## [Unreleased · 第七轮审查与优化] - 2026-10-02

> 本轮 = 评估 + 修复，审查对象：`com/dsh/**`（全部核心 + UI）+ 可达 FCL 遗产 + 脚本/资源/Manifest/Gradle。
> 完整报告见 `docs/PROJECT_REVIEW_AND_OPTIMIZATION.md`；速览见 `docs/reports/round7-review-and-optimization.md`。
> 全部改动已提交 `494f234`（`git revert 494f234` 可整体回滚）。

### Fixed

**1. 实例删除竞态：删除目录前先等进程真正退出（P1）**
- **现象**：`DshInstances.delete()` 里先调 `DshRuntime.stop("实例被删除")`，紧接着 `deleteRecursively()`。
  但 `stop()` 内部是 `scope.launch { h?.terminate(5000) }` —— **异步**的，返回时旧进程可能还活着（TERM 后最多 5s 才 KILL）。
  若 node 仍在往 `node_modules` 写文件，`deleteRecursively` 就删不干净，留下 ~300MB 残留，
  而界面早已把实例从列表移除，**用户完全看不到提示**。
- **改动**：
  - `DshRuntime.kt`：新增 `suspend fun stopAndWait(reason, timeoutMs = 8s)` —— 先 `stop()`，
    再轮询 `_state` 直到离开"本实例的 Stopping"（即进程已退出、状态被 onProcessExit/stop 协程落到 Idle 或别的新状态）。
  - `DshInstances.kt`：`delete()` 协程里把 `DshRuntime.stop(...)` 换成 `DshRuntime.stopAndWait("实例被删除")`，
    超时（8s）仍继续硬删，并在日志总线留一行"停止超时"以便事后追查。
- **影响范围**：仅"删除实例"路径；其它 `stop()` 调用点（用户手动停、切实例、通知栏停）不受影响，语义不变。
- **兼容性**：`stopAndWait` 是新增 API，未改 `stop()` 签名；`delete()` 在 `DshAppScope`（IO）协程内调用，可安全 suspend。
- **验证**：编译 BUILD SUCCESSFUL；单测 23/23。（删目录的并发行为需真机复现确认，沙箱无法实测。）

**2. WebView 失败面板被 `onPageFinished` 盖成空白（P1）**
- **现象**：`DshWebViewActivity` 里 `onReceivedError` / `onReceivedHttpError`(4xx) 调 `showError()` 显示错误面板，
  但 **WebView 对"失败页面"同样会回调 `onPageFinished`**（常见顺序：onPageStarted → onReceivedError → onPageFinished），
  而原来的 `onPageFinished` 无条件 `showWeb()` —— 于是错误面板瞬间被盖上，用户只见一片空白 WebView，
  像 dsh 卡死，且"重试/看日志/停止/返回"这些出口全部不可见。
- **改动**：`DshWebViewActivity.kt` 新增 `private var pageFailed = false`：
  - `onPageStarted`（且 URL 是回环）→ `pageFailed = false` + `showWeb()`
  - `onReceivedError`（主 frame）→ `pageFailed = true` + `showError(...)`
  - `onReceivedHttpError`：401 走重载前清标志；401 无 token 或其它 ≥400 → `pageFailed = true` + `showError(...)`
  - `onPageFinished` → **先查 `pageFailed`，为真则不 `showWeb()`**（保留错误面板）
- **影响范围**：仅 WebView 页；对正常加载无行为变化（onPageStarted 已清标志）。
- **验证**：编译通过。真机上需确认各厂商 WebView 回调顺序一致（尤其 401 重载后成功时标志确实被清）。

**3. 安装看门狗超时无可读文案（P2）**
- **现象**：`ProotProcessExecutor` 在 `timeoutMs` 超时后返回 `-2`（魔法数），`DshInstaller.runInstall`
  `return exit == 0` → false → `errorSummary` 落到泛化的 `dsh_install_failed_generic`（"Install failed"），
  用户完全不知道是 npm 卡死/超时。
- **改动**：
  - `ProotProcessExecutor.kt`：抽命名常量 `companion object { const val TIMEOUT_EXIT_CODE = -2 }`，返回处用常量替代 `-2`。
  - `DshInstaller.kt`：`runInstall` 里 `if (exit == ProotProcessExecutor.TIMEOUT_EXIT_CODE)` →
    `errorSummaries[id] = context.getString(R.string.dsh_install_timeout, INSTALL_TIMEOUT_MS / 60_000)`。
  - `strings.xml` / `strings-zh.xml`：新增 `dsh_install_timeout`（中英，带 `%1$d` 分钟数）。
- **影响范围**：仅安装失败时的原因文案；退出码语义不变。
- **配套**：`DshResourceFormatTest` 的 `callSites` 登记 `dsh_install_timeout`（否则"带占位符文案必须登记"契约测试会红）。
- **验证**：编译通过；单测 23/23（含占位符契约）。

**4. 通知状态在非运行态误报"运行中"（P2）**
- **现象**：`DshRuntimeService.buildNotification` 的 `status` 分支里，`Stopping` / `Idle` / `Failed` / `Exited`
  全部落到 `else -> dsh_notify_running`（"运行中"），在服务即将自停的极短窗口里会误导用户。
- **改动**：
  - `DshRuntimeService.kt`：`status` 增加 `is DshRuntime.State.Stopping -> dsh_notify_stopping`，
    `else`（Idle/Failed/Exited）也改用 `dsh_notify_stopping`（不再误标 Running）。
  - `strings.xml` / `strings-zh.xml`：新增 `dsh_notify_stopping`（"停止中…"）。
- **影响范围**：仅通知文案；服务仍会在非 Running/Starting 时自停（行为不变）。
- **验证**：编译通过。

### Changed

**§2.5 硬性要求收尾：实例页 / WebView 页去 Material（P2，UI 一致性）**
- **背景**：`app-shell.md §2.5` 要求所有 dsh 布局一律用 fcllibrary 控件 + ThemeEngine 主题。
  阶段 2 已把 6 个布局换成 FCL 控件，但 `activity_dsh_instances.xml` 与 `activity_dsh_webview.xml`
  仍混着 `MaterialButton` / 原生 `ProgressBar` / `TextView` —— 正是 §2.5.5 验收命令
  `grep -l "com.google.android.material" *dsh*.xml` 会抓住的地方（改前这两行有输出）。
- **改动**：
  - `activity_dsh_instances.xml`：2 个 `MaterialButton`（btn_logs/btn_download）→ `FCLButton`（`app:ripple`）；
    `title` / `empty_hint` 原生 `TextView` → `FCLTextView`（`app:auto_text_tint` 随主题换色），去掉写死的 `#888888`。
  - `activity_dsh_webview.xml`：4 个 `MaterialButton`（btn_retry/logs/stop/back）→ `FCLButton`；
    2 个原生 `ProgressBar` → `FCLProgressBar`；`state_text` 原生 `TextView` → `FCLTextView`；
    状态面板背景 `?android:attr/colorBackground` → `@drawable/bg_container_white`（FCL 容器风格）。
- **影响范围**：仅这两个布局；所有 `@+id` 不变，viewBinding 字段名未变 → 无编译/逻辑破坏。
- **验收**：**8 个 dsh 布局 0 Material 控件**，`grep -l com.google.android.material *dsh*.xml` 输出为空（命令 1 通过）。
- **遗留**：代码里 `MaterialAlertDialogBuilder` 在 `com/dsh` 仍有 22 处（§2.5 允许"新代码统一 FCLAlertDialog，逐步归零"），
  作 P3 项，本轮未大范围替换（避免一次性改动面过大）。

### Removed

- **未引用文案 `dsh_action_configure_key`（中英）**：全仓 0 引用（grep 确认），删除（R-17）。

### Optimized

- **`DshLogBus` 环形裁剪 O(n²) → O(n)**：原来 `repeat(buffer.size - MAX_LINES) { removeAt(0) }` 是逐行
  `removeAt(0)`（ArrayList 头删 = 数组搬移），且注释自称"避免每行搬移"与实际不符。改为
  `buffer.subList(0, buffer.size - MAX_LINES).clear()`（单次搬移）+ 修正注释。

### Refactored

- **`ProotProcessExecutor` 超时退出码抽为命名常量** `TIMEOUT_EXIT_CODE = -2`（替代魔法数）。

### Notes

- 验证：`run-compile.sh` **BUILD SUCCESSFUL**；`run-tests.sh` **23/23**；`test-scripts-posix.sh` **18/18**；**未打包**。
- 新增/删除依赖：**无**。
- 涉及真机运行时行为的结论（删除并发、WebView 回调顺序）标注「待真机确认」，未伪造运行结果。

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
