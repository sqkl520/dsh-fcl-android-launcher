# 任务清单（待办）

> 放**已明确要做、但未做**的事项。做完挪到 `CHANGELOG.md`。
> 缺口来源与证据：`docs/reports/frontend-gap-vs-fcl.md`（前端 vs FCL 原版逐项对照）。

## P1 · 结构与页面

### T1（G5）页面切换动画
- **现状**：`DshUIManager` 的 ViewPager 配置已与 FCL 一致（垂直、禁手势、不预加载、不保存状态），
  但**缺过渡动画**，切页是硬切。
- **FCL 实现**（`ui/UIManager.kt` 的 `onPageSelected`）：
  目标页 `alpha=0 → 1`、`translationY=30dp → 0`、`duration=250ms`；
  **只在 position 真变时播放**（布局变化重新 dispatch 当前页时不播，避免闪烁）；同步执行不 post。
- **顺带**：`FCLDialog` 未设置 `window.setWindowAnimations`，对话框无进出场动画。
- **工作量**：小（一个回调 + 复用现有 anim）。

### T2（G2）关于页
- FCL：`AboutPage` + `item_about.xml` + `item_about_desc.xml`（图标 + 版本 + 链接 + 描述的卡片列表）。
- 我们：只有 `FCLAlertDialog` 弹窗。
- **建议**：作为 T3（页内多页）的第一个落地场景。

### T3（G1）页内"多页容器 + 标签栏"
- FCL：`FCLMultiPageUI` + `FCLPage` + `FCLUILayout` + `FCLTabLayout`
  → 设置页 = 「启动器设置 / 插件管理 / 关于」4 个子页；下载页 = 分类/详情子页。
- 我们：单层 `DshPageUI`，无子页与页内标签栏（`FCLTabLayout` 已存在但没用起来）。
- **缺的类**：`FCLPage`、`FCLMultiPageUI`、`FCLUILayout`；**缺的动画**：`frag_start_anim` / `frag_stop_anim`。
- **工作量**：中等偏大，是最大的一项结构改造。

### T4（G3）主题自定义（取色 + 数值 SeekBar）
- 缺的资源/类：`FCLColorPickerDialog`、`ColorPickerView`、`AlphaPatternDrawable`、
  `dialog_color_picker.xml`、`FCLNumberSeekBar`、`bg_number_seekbar_track`、
  图标 `ic_baseline_restore_24` / `ic_baseline_edit_24` / `ic_baseline_palette_24`。
- 有了它们才能做 FCL 的：主题色（亮/暗）、次要色（亮/暗）、颜色透明度、
  「重置 / 从背景取色 / 设置」三按钮行。
- 设置行需要支持**一行多个动作按钮**（目前适配器每行只支持一个）。

### T5（G4）文件 / 图片选择
- FCL：`fcllibrary/browser/` 整包（`FileBrowser` / `FileBrowserActivity` / `FileBrowserLauncher` /
  `SelectionMode` / `SelectedFile`），阶段 4 被删。
- 用途：「选择背景图（亮/暗）」。
- **建议**：优先用系统 `ACTION_OPEN_DOCUMENT`（无需恢复整套 FileBrowser，也少一堆权限问题）。

### T6 实例详情页与 FCL `VersionSettingPage` 对齐
- FCL 的版本设置页也是 RecyclerView 分组行；我们仍是表单式 `DshSettingsActivity`。
- 目标：改成行式结构（开关行/按钮行/编辑行），复用 `SpacingItemDecoration`。

### T7 资源补齐（随各步骤顺带做）
- drawable：`bg_item`、`bg_item_clickable`、`bg_container_white_clickable`、
  `bg_container_transparent_selected`、`bg_right_menu_button`、`bg_tab_top_rounded`、
  `bg_game_menu_inset`、`bg_number_seekbar_track`，
  以及常用图标（`done` / `arrow_upward` / `arrow_downward` / `arrow_forward` / `file` / `folder` / `cloud_download`）。
- anim：`frag_start_anim`、`frag_stop_anim`。
- 注意：FCL 的 `values/` 5 个文件我们都有，**样式层不缺**。

### T8 右面板按钮用 FCL 的 `bg_right_menu_button`
- FCL 右侧面板按钮有自己的底图；我们现在是普通 `FCLButton`。

### T9 自定义启动器名（FCL 启动器设置里的 `custom_launcher_name`）
- FCL 有"自定义启动器名"编辑行；dsh 可对应"自定义外壳标题"。

## P2 · 运行时与稳定性

### T10 真机端到端验收（阶段 E）
- 首次解压 300MB rootfs 的耗时与成功率（需 ≥2GB 空闲）
- proot 内 `npm` / `dsh` 在 SELinux + W^X 下的实际表现
- WebView token/cookie、息屏保活、停止后清理、失败恢复

### T11 rootfs 瘦身与可下载化（阶段 F）
- 现含 `build-essential/python3/pkg-config/git`（为了编译原生模块）
- 评估：编译工具按需安装；更远期改为首次下载 + 校验

### T12 缓存与清理 UI
- npm cache（`/opt/dsh/npm-cache`）与 rootfs 体积显示 + 一键清理，放进设置页

## P3 · 其他

### T13 `.github/workflows` 适配
- 仓库里仍是 FCL 原版 CI（checkstyle / release），会在 GitHub 上失败或误导。

### T14 文档同步
- `PLAN.md` / `PACKAGING.md` 仍有过时内容（描述的是"用户自备 proot + rootfs"的旧方案）。
