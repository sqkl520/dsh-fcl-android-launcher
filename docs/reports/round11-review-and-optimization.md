# 第十一轮：评估与优化（速览）

> 全文：`../PROJECT_REVIEW_AND_OPTIMIZATION.md`。审查对象：`/workspace/FCL`，git HEAD `d82f409`
> + 第十轮未提交改动 + 本轮改动（未提交、未打包）。时间：2026-10-03。

## 一句话

前十轮把并发/生命周期/资源兜底做得很扎实，**但阶段 D-1 引入的两条链在真机上都会硬阻塞**：
① rootfs 解压把符号链接目标里的 `..` 改写成宿主路径 → `/opt/node22/bin/npm` **断链** →
`probe.sh` 必 FAIL → **底座永远不就绪**；② 「命中预装 dsh 就跳过下载」改变了产物形态，
而 Kotlin 侧三处判定仍只看实例内 `node_modules` → **装完必判 BROKEN、重装重复同一结果、永远起不来**。
两个都已在沙箱内用**真实资产**独立复现并修掉，另修 2 个 P3。单测 27/27 → **32/32**。

## 本轮修了什么

| ID | 级别 | 一句话 | 证据 |
|---|---|---|---|
| R11-01 | **P1** | rootfs 符号链接目标原样保留（不再把 `..` 换成宿主路径） | 真实 rootfs：`readlink -f opt/node22/bin/npm` 解析到**存在**的 `opt/node22/lib/node_modules/npm/bin/npm-cli.js`；旧逻辑产物 `<rootfs>/lib/node_modules/npm/bin/npm-cli.js` **不存在** |
| R11-02 | **P1** | 「包实际在哪」收敛为 `DshPaths.effectiveDshDir`（实例优先 → rootfs 预装回退），三处判定统一走它 | JVM harness 打真实 rootfs：旧 `readVersion(instance-only)` → `null`（→BROKEN）；新 → `0.1.6-alpha.2` |
| R11-03 | P3 | `installVersion` 返回 `InstallDispatch(instance, started)`，不再"没装却说正在装" | 代码路径 + 新增文案 `dsh_install_skipped`（已登记契约测试） |
| R11-04 | P3 | `ProotCommand.preflight()` 的 rootfs 判据改用入参 | 新增 `DshPaths.rootfsLooksUsable(root: File)` 重载 |
| R11-05 | P3 | `values-zh` 缺 `dsh_about_full_name`/`dsh_about_subtitle`（无占位符，契约测试抓不到） | 名字集合对比 → 待补 |

## 为什么 R11-01 藏了四轮

验证 rootfs 用的是 `chroot <rootfs>` —— 那是**在宿主上直接构建出来的目录**，符号链接本来就是对的，
**没走 `RuntimeUtils` 的解压路径**。而真机走的正是解压路径。教训：
凡"打包 → 解压 → 使用"的链路，验证必须**从解压产物**开始，而不是从构建产物开始。

## 本轮改动规模

13 个代码/资源文件（含 1 个新增 `TarLinkPolicy.kt`）≈ **+283 / −31**，另加文档。
无新增依赖、无权限/数据格式/字符串 key 变更。

## 附加：UI 与 FCL 一致性还原（同轮）

要求见 `design/app-shell.md` §2.5（"界面布局 / 按钮布局 / 整体主题一律跟 FCL 走"）。
本轮**把 FCL 原版布局从 git 历史取出来当尺子**逐部件对照，发现**根因**：
阶段 4 裁剪 MC 资源时，把 FCL 的**通用 UI chrome**（非 MC）一并删了 ——
`bg_game_menu` / `bg_right_menu` / `bg_item_rounded` / `bg_container_transparent_clickable` /
`bg_progress*`。没有它们，界面**不可能**像 FCL（所以第七轮"0 个 Material 控件"通过了但观感不对）。

已做：

| 项 | 结果 |
|---|---|
| 恢复 FCL 通用 chrome 资产 | 7 个 drawable + 2 个 anim（纯 XML，非 MC）；删掉恢复后无人引用的 13 个 |
| 主外壳逐部件对齐 | 左侧菜单背景+100dp 抬升+padding 5/10/5/10；菜单项 `wrap_content`；**补回 `back` 项**；右侧面板 `bg_right_menu`+25%+内层 content；**动态岛移回底部居中**；补 `video_view`；`ui_layout` `match_parent` |
| 外壳代码补 FCL 行为 | `back`→`onBackPressedDispatcher`；动态壁纸 setup/pause/resume/stopPlayback；背景随主题刷新 |
| 列表行范式 | 实例行照 `item_profile`（透明容器+`anim_scale` 按压反馈）；版本行照 `item_version`（外层间距+内层卡片+`anim_scale`） |
| 设置行/页 | 照 `item_launcher_setting_button` + `page_setting_list`；恢复 `SpacingItemDecoration`（组内 1dp 分割线/跨组 8dp）；去掉 FCL 没有的"分组标题行" |
| 页内头部 | 删掉与动态岛重复的 20sp 大标题；动作改 `FCLImageButton`（FCL 图标按钮写法） |
| 对话框 | **16 处 `MaterialAlertDialogBuilder` → `FCLAlertDialog`，归零** |

验收（§2.5.5 三条）：① 布局内 Material 控件 **0**；② 12 个 dsh 布局全部含 fcllibrary 控件
（`ui_dsh_launcher_settings` 由 0 → 1）；③ `com/dsh` 下 `MaterialAlertDialogBuilder` **0**、
`FCLAlertDialog` 37 处。编译 BUILD SUCCESSFUL / 单测 **32/32** / 脚本 **18/18**。

**有意保留的 1 处差异**：FCL 设置行是纯白（暗色下也白），dsh 用
`FCLLinearLayout.auto_linear_background_tint` 跟随主题 —— 几何/结构一致，仅颜色主题化。
方法论见 `LESSONS.md §14/§15`。

## 验证

| 命令 | 结果 |
|---|---|
| `sh /workspace/run-compile.sh` | **BUILD SUCCESSFUL**（仅 2 条既有 `onBackPressed` 弃用告警） |
| `sh /workspace/run-tests.sh` | **TOTAL=32 FAILED=0** |
| `sh /workspace/dsh-launcher-poc/scripts/test-scripts-posix.sh` | **PASS=18 FAIL=0** |
| `readlink -f` 对照（真实 rootfs） | 正向解析成功 / 旧逻辑产物不存在 |
| JVM harness（真实 rootfs 预装 dsh） | 旧 `null` → 新 `0.1.6-alpha.2` |
| checkstyle | 未运行（离线无依赖缓存） |
| 真机 e2e | **未验证**（`Os.symlink` 的写入动作只能在真机跑） |

## 下一步

1. 🔴 **真机点亮（ROADMAP M1）**：重点看 ① 解压后 `npm` 是否可执行（R11-01 的写入动作）；
   ② `probe.sh` 9/9；③ 装预装版本后实例是否 READY（R11-02）；④ `dsh web` 起没起、WebView 出没出界面。
2. **把解压链路纳入自动化测试**（本轮两个 P1 的共同盲区）。
3. CI 重写 / 签名私钥外移 + release 混淆 / PTY·busybox 去留决策 / 路径常量三处收敛 / 补 zh 文案。

## 方法论（已写入 `LESSONS.md §11/§12/§13`）

- 别改写 tar 的符号链接目标；"验证路径 ≠ 真实路径"的盲区要专门对一遍。
- 优化一旦改变产物形态，判定口径必须同步。
- 验证要"能证伪"：真实资产 + 独立 harness + `readlink -f`/`du`/`ls -l`。
