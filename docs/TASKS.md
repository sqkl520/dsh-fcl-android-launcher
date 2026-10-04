# 任务清单（待办）

> 放**已明确要做、但未做**的事项。做完挪到 `CHANGELOG.md`。
> 缺口来源与证据：`docs/reports/frontend-gap-vs-fcl.md`（前端 vs FCL 原版逐项对照）。

## 已完成（保留索引，便于回溯）

| 原编号 | 内容 | 落地 |
|---|---|---|
| G5 | 页面切换动画（淡入 + 上滑 30dp / 250ms，仅位置真变时播） | commit `97151a2` |
| G2 | 关于页（说明行 + 链接组，照 FCL `AboutPage`） | commit `97151a2` |
| G1 | 页内多页容器 + 标签栏（`DshMultiPageUI`；设置页 = 启动器设置 \| 关于） | commit `73f3d99` |
| G3 | 主题自定义（取色对话框 + 数值滑条 + 多图标行；主题色/次要色/透明度/动画速度） | commit `136d962` |
| G4 | 文件/图片选择（走系统 `ACTION_OPEN_DOCUMENT`；背景图亮/暗 + 重置 + 从背景取色） | commit `467c5c1` |
| — | 真机二测问题修复（DNS 注入 / 安装防重复 / FCL 规范控件 / 状态误判） | commit `ca95ab0` |
| — | **首启前置页**（一次性准备页，双栏横屏，失败可重试） | commit `d285671`+`9cb4855` |
| — | **首页任务区**（聚合解压/安装/启停/删除，照 FCL `item_download_task`） | commit `d285671` |

## P1 · UI 还原

### T1. 实例详情页与 FCL `VersionSettingPage` 对齐
- FCL 的版本设置页也是 RecyclerView 分组行；我们仍是表单式 `DshSettingsActivity`。
- 目标：改成行式结构（开关行/按钮行/编辑行），复用 `SpacingItemDecoration` 与现有 5 种行类型。

### T2. 设置页补充 FCL 的自定义项
- **插件管理子页**：设置页目前 2 个子页（启动器设置 | 关于）；FCL 是 4 个。
  插件体系确定后补第三个子页，并可考虑撤掉外壳菜单里的「管理」占位 tab。
- **自定义启动器名**：FCL 启动器设置里有 `custom_launcher_name` 编辑行（`EditDialog` 已恢复）。
- **语言完整列表**：FCL 有 12 种语言，目前只开放 3 项。

### T3. 资源补齐（随各步骤顺带做）
- drawable：`bg_item`、`bg_item_clickable`、`bg_game_menu_inset`，
  以及常用图标（`arrow_upward` / `arrow_downward` / `arrow_forward` / `file` / `folder` / `cloud_download`）。

### T4. 右面板按钮改用 FCL 的 `bg_right_menu_button`
- FCL 右侧面板按钮有自己的底图；我们现在是普通 `FCLButton`。

## P2 · 运行时与稳定性

### T5. 真机端到端验收（关键）
- **DNS 修复是否真的生效**（`npm install` 能否成功装 0.2.x）
- 首次解压 300MB rootfs 的耗时与成功率（需 ≥2GB 空闲）
- 前置页 / 首页任务区的实际观感与行为
- proot 内 `dsh web` 能否起来、WebView token/cookie、息屏保活、失败恢复

### T6. rootfs 瘦身与可下载化
- 现含 `build-essential/python3/pkg-config/git`（为了编译原生模块）
- 评估：编译工具按需安装；更远期改为首次下载 + 校验

### T7. 缓存与清理 UI
- npm cache（`/opt/dsh/npm-cache`）与 rootfs 体积显示 + 一键清理，放进设置页

## P3 · 其他

### T8. `.github/workflows` 适配
- 仓库里仍是 FCL 原版 CI（checkstyle / release），会在 GitHub 上失败或误导。

### T9. 文档同步
- `PLAN.md` / `PACKAGING.md` 仍有过时内容（描述的是"用户自备 proot + rootfs"的旧方案）。


