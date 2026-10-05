# 任务清单（待办）

> 放**已明确要做、但未做**的事项。做完挪到 `CHANGELOG.md`。
> 缺口来源与证据：`docs/reports/frontend-gap-vs-fcl.md`（前端 vs FCL 原版逐项对照）。

## 已完成（保留索引，便于回溯）

| 原编号 | 内容 | 落地 |
|---|---|---|
| G5 | 页面切换动画（淡入 + 上滑 30dp / 250ms，仅位置真变时播） | commit `97151a2` |
| G2 | 关于页（说明行 + 链接组，照 FCL `AboutPage`） | commit `97151a2` |
| G1 | 页内多页容器 + 标签栏（`DshMultiPageUI`；设置页 = 启动器设置 \| 关于） | commit `73f3d99` |
| G3 | 主题自定义（取色对话框 + 数值滑条 + 多图标行） | commit `136d962` |
| G4 | 文件/图片选择（系统 `ACTION_OPEN_DOCUMENT`） | commit `467c5c1` |
| — | 真机二测问题修复（DNS / 安装防重复 / FCL 规范控件 / 状态误判） | commit `ca95ab0` |
| — | 首启前置页 + 首页任务区 | commit `d285671`+`9cb4855` |
| — | **动画 16/16 全对齐**（含 SwipeMenuLayout / WaveProgressView / AnimUtil） | commit `d4b1a92`…`4d79564` |
| — | APK 产物整理 + 0.1.1 打包 + 0.1.0 归档 | commit `26d3a8a`/`a262a7e` |
| **T1** | 实例详情页改 FCL 行式（`DshInstanceSettingAdapter` + 值行/按钮行） | commit `839e0fa` |
| **T4** | 右面板按钮照 FCL 形态（图标+文字 clickable 容器；原 T4 前提「用 `bg_right_menu_button`」经核对是 FCL 未使用资源，已改按真实写法） | commit `839e0fa` |
| **T2** | 12 种语言 + 自定义启动器名（`LauncherUtil` + 编辑行） | commit `8b49ea9` |
| **T9** | `.github/workflows` 适配 —— **不需要做了**：FCL 原版 CI 在搬到电脑时已整体删除（`92ba6df`，远端实测 `workflows total_count: 0`） | commit `92ba6df` |

## P1 · UI 还原

### ~~T3. 资源补齐~~ —— **已核对，无需执行**
- 结论：FCL 有而我们缺的 71 个 drawable，**没有一个需要补** ——
  要么是 FCL 自己的未使用资源（`bg_right_menu_button` / `bg_game_menu_inset` /
  `bg_container_transparent_selected` / `ic_baseline_file_24` 等，全仓库零引用），
  要么是 MC 专属（控制器/整合包/账户/渲染器），要么是 MC 页面专用图标
- 证据与逐条清单见 `reports/frontend-gap-vs-fcl.md` §4.1
- 教训：`git grep bg_item` 会命中 `bg_item_rounded`（子串误匹配），查资源引用要加边界

### T5. 插件管理子页（等插件体系确定）
- 设置页目前 2 个子页（启动器设置 | 关于）；FCL 是 4 个。插件体系定下来后补第三个子页，
  并可考虑撤掉外壳菜单里的「管理」占位 tab

## P2 · 运行时与稳定性

### T6. 真机端到端验收（关键）

> **0.1.2-SNAPSHOT 包已就绪**（2026-10-05 出包，`output/dsh-fcl-android-launcher-0.1.2-SNAPSHOT-arm64.apk`，
> sha256 `8abaa3ea…`，见 CHANGELOG）；2026-10-06 起**已作为 GitHub Release 附件发布**
> （<https://github.com/sqkl520/dsh-fcl-android-launcher/releases/tag/v0.1.2-SNAPSHOT>，公开仓免 token 可下）
> —— 直接下载装机开验。

- **DNS 修复是否真的生效**（`npm install` 能否成功装 0.2.x）
- 首次解压 300MB rootfs 的耗时与成功率（需 ≥2GB 空闲）
- 前置页 / 首页任务区 / 实例详情页 / 右面板按钮的实际观感与行为
- proot 内 `dsh web` 能否起来、WebView token/cookie、息屏保活、失败恢复

### T7. rootfs 瘦身与可下载化
- 现含 `build-essential/python3/pkg-config/git`（为了编译原生模块）
- 评估：编译工具按需安装；更远期改为首次下载 + 校验

### T8. 缓存与清理 UI
- npm cache（`/opt/dsh/npm-cache`）与 rootfs 体积显示 + 一键清理，放进设置页

## P3 · 其他

### T10. 文档同步 —— **主体已完成（2026-10-06）**
- 已做：`PLAN.md` / `ROADMAP.md` / `INDEX.md` 里"运行时底座要走 `:proot-engine`"的过时表述
  全部更正为「自研 proot 层 + 预打包 rootfs」；`proot-engine-integration.md` 顶部标注**未采用**；
  `PACKAGING.md` 的 "Ubuntu/Alpine"、"需你放入" 改为现状（Debian 12 + 已就位）。
- 剩余（低优先）：`PLAN.md` 仍是早期方案原貌（有顶部过时声明指路），彻底重写收益不大。



