# 任务清单（待办）

> 这里放**已明确要做、但本轮未做**的事项（含原因/优先级）。做完就挪到 `CHANGELOG.md`。

## P1 · UI 还原（FCL 一致性）

### T1. FCL 动画体系还原（用户反馈：目前"没有任何动画"）
- **目标**：照搬 FCL 的动画手感，而不是零散加特效。
- **FCL 里已有的动画来源**（可直接复用，无需自研）：
  - `xml/anim_scale_large.xml`、`anim_scale.xml` — 控件按压缩放（已用于 `FCLMenuView` / `FCLImageButton` 的 `stateListAnimator`）
  - `FCLDynamicIsland` 自带文字切换动画（`setTextWithAnim`）
  - ViewPager2 页面切换动画（FCL 的 `UIManager.switchUI` 用"淡入 + 上滑"）
  - `FCLMenuView` 选中态：图标 tint 切换 + Ripple
  - 对话框：`FCLDialog` 的进出场（可用 `window.setWindowAnimations`）
  - 列表项点击：`bg_container_transparent_clickable`（已恢复）
- **建议做法**：先做"页面切换动画"，再做"列表项与按钮按压反馈"，最后补动态岛与对话框。
- **工作量**：中等（主要是 `DshUIManager` 的页面切换 + 新布局属性），可分 2~3 轮。

### T2. 设置页剩余的 FCL 自定义项
已实现：语言、主题模式、动画速度、全屏（忽略刘海）。
待补：
- **主题色 / 暗色主题色**：FCL 用 `FCLColorPickerDialog`（阶段 4 被删，需从基线恢复）+「重置 / 从背景提取 / 设置」三按钮行
- **次要色 / 次要暗色**：同上
- **颜色透明度**：FCL 的 `SEEKBAR_COLOR_ALPHA`（0~255），需要给适配器加 SeekBar 行
- **背景图（亮/暗）**：需要图片选择器（FCL 用 `FileBrowser`，阶段 4 被删）→ 建议改用系统 `ACTION_OPEN_DOCUMENT`
- **语言**：目前只开放 3 项（跟随系统/简中/English），FCL 有 12 种
- **菜单图标 / 光标**：dsh 暂不需要（MC 遗留），可跳过

### T3. 实例详情页（`DshSettingsActivity`）与 FCL 版本设置页对齐
- FCL 的 `VersionSettingPage` 也是 RecyclerView 分组行；目前实例页仍是"表单"形态。
- 目标：改成同样的行式结构（开关/按钮/编辑行），并复用 `SpacingItemDecoration`。

## P2 · 运行时与稳定性

### T4. 真机端到端验收（阶段 E）
- 首次解压 300MB rootfs 的耗时与成功率（需 ≥2GB 空闲）
- proot 内 `npm` / `dsh` 在 SELinux + W^X 下的实际表现
- WebView token/cookie、息屏保活、停止后清理、失败恢复

### T5. rootfs 瘦身与可下载化（阶段 F）
- 当前 rootfs 含 `build-essential/python3/pkg-config/git`（为了能编译原生模块）——约 300MB 压缩后
- 评估：把编译工具做成"按需安装"（首次需要时 apt 装），换取更小 APK
- 更远期：rootfs 改为首次下载 + 校验

### T6. 缓存与清理
- npm cache（`/opt/dsh/npm-cache`）与 rootfs 的体积显示 + 一键清理，放进设置页

## P3 · 其他

### T7. `.github/workflows` 适配
- 仓库里仍是 FCL 原版的 CI（checkstyle / release），未适配本项目，会在 GitHub 上失败或误导。

### T8. 文档同步
- `PLAN.md` / `PACKAGING.md` 仍有过时内容（描述的还是"用户自备 proot + rootfs"的旧方案）。
