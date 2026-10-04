# 前端对照报告：我们 vs FCL 原版

> 对照对象：本地基线 `647919c`（FCL 上游源码，含 `FCL/`、`fcllibrary/`、`res/`）。
> 目的：回答「我们的前端还缺什么」。只列**非 MC 的通用 UI**；MC 专属（账户/Java/控制器/整合包/
> 资源包/渲染器/联机…）一律不算缺口。

## 0. 结论摘要

结构上真正缺的是 **4 件事**，其余是资源与组件级的补齐。
**（2026-10-03 更新：G1~G5 已全部落地，见下表"落地"列）**

| # | 缺口 | 影响 | 优先级 | 落地 |
|---|---|---|---|---|
| G1 | **页内多页容器 + 标签栏体系**（`FCLPage` / `FCLMultiPageUI` / `FCLUILayout` + `FCLTabLayout`） | 设置页无法像 FCL 那样分「启动器设置 / 插件管理 / 关于」子页；下载页无法做「分类 / 详情」子页 | P1 | ✅ `73f3d99`（`DshMultiPageUI`；设置页 = 启动器设置 \| 关于） |
| G2 | **关于页**（`AboutPage` + `item_about.xml` + `item_about_desc.xml`） | 我们现在只有一个弹窗，没有 FCL 那种带图标/版本/链接/描述的卡片列表页 | P1 | ✅ `97151a2`（`DshAboutUI`，说明行 + 链接组） |
| G3 | **主题自定义整套资源**（`FCLColorPickerDialog` / `ColorPickerView` / `AlphaPatternDrawable` / `dialog_color_picker` / `FCLNumberSeekBar`） | 主题色、次要色、颜色透明度、背景图**全都做不了** | P1 | ✅ `136d962`（含 0~255 透明度、0~10 动画速度滑条） |
| G4 | **文件/图片选择**（`browser/` 整包：`FileBrowser` / `FileBrowserActivity` / `FileBrowserLauncher` / `SelectionMode`） | 「选择背景图」没有选择器可用（只能提示"待接入"） | P1 | ✅ 走系统 `ACTION_OPEN_DOCUMENT`（不恢复 FileBrowser 整包） |
| G5 | **页面切换动画**（FCL：淡入 + 上滑 30dp / 250ms） | 目前切页是硬切，无过渡（用户反馈的"没有任何动画"） | P2 | ✅ `97151a2`（外壳）+ `73f3d99`（页内子页） |

后续待办见 `docs/TASKS.md`。


## 1. 页面体系对照（最大的结构性差异）

**FCL**：
```
MainActivity
└── ViewPager2（垂直、禁手势、不预加载、不保存状态）
    └── 每个 tab = 一个 FCLMultiPageUI（页内还能有子页 + FCLTabLayout 标签栏）
        ├── SettingUI   = [版本设置 | 启动器设置 | 插件管理 | 关于]  ← 4 个子页
        ├── DownloadUI  = [分类 | 详情 | 搜索] 等子页
        └── ...
```
- 涉及类：`FCLMultiPageUI`、`FCLPage`、`FCLUILayout`（均**缺失**）
- 页面切换动画：`anim/frag_start_anim.xml`、`anim/frag_stop_anim.xml`（**缺失**）

**我们**：
```
DshMainActivity
└── ViewPager2（垂直、禁手势、不预加载、不保存状态）← 这部分已与 FCL 一致
    └── 每个 tab = 一个 DshPageUI（单层，无子页、无页内标签栏）
```
→ 结论：ViewPager 的配置我们已经对齐；**缺的是"页内多页 + 标签栏"这一层**。

## 2. 逐页对照

| 页面 | FCL | 我们 | 差距 |
|---|---|---|---|
| 主界面 | `MainUI`：版本卡片 + 启动按钮 + 公告 + 收藏 + 皮肤 | 实例列表 + 右面板 | **有意差异**（dsh 没有"版本卡片"概念），不算缺口 |
| 设置 | `SettingUI`（4 个子页 + 标签栏） | 单页分组列表 | **缺子页/标签栏**（G1）；设置项本身本轮已按 FCL 行样式重做 |
| 关于 | `AboutPage`（独立页 + 卡片列表） | 弹窗 | **缺页面**（G2） |
| 插件管理 | `PluginManagePage` | "管理" tab 仍是占位 | 计划内（管理功能未定），保持占位 |
| 下载 | `DownloadUI`（分类/搜索/详情子页 + `FCLAppBarLayout` 折叠栏） | 单页版本列表 | 缺子页体系（G1）、缺 `FCLAppBarLayout` |
| 日志 | 无独立页（MC 日志在 `LogSharingUtils`） | 已有控制台式日志页 | 本项目自研，已优于 FCL |
| 实例详情 | `VersionSettingPage`（RecyclerView 分组行） | 表单式 `DshSettingsActivity` | 建议改成同样的行式结构 |

## 3. 组件级缺口（fcllibrary 通用组件）

| 组件 | 用途 | 状态 |
|---|---|---|
| `FCLUILayout` | 页内容器（`FCLPage` 的宿主） | ✗ 缺 |
| `FCLPage` / `FCLMultiPageUI` | 页/多页基类 | ✗ 缺 |
| `FCLFragment` | Fragment 基类 | ✗ 缺（我们不走 Fragment，可跳过） |
| `FCLColorPickerDialog` + `ColorPickerView` + `AlphaPatternDrawable` | 主题色取色 | ✗ 缺（G3） |
| `FCLNumberSeekBar` | 带数值的 SeekBar（设置页用） | ✗ 缺 |
| `FCLAppBarLayout` | 折叠式标题栏 | ✗ 缺（下载页可能用得上） |
| `DialogCard` | 对话框内卡片容器 | ✗ 缺（非必需） |
| `FCLCheckBoxTreeAdapter/Item` | 多选树（FCL 用于下载分类） | ✗ 缺（暂不需要） |
| `DraggableTextView` | 可拖动文本（游戏内控制） | MC 专属，跳过 |
| `FileBrowser*`（整包） | 文件/图片选择 | ✗ 缺（G4） |
| `FCLTabLayout` | 页内标签栏 | ✓ 有（但没被用起来，因为缺 G1） |
| `FCLMenuView` / `FCLDynamicIsland` / `FCLSpinner` / `FCLSwitch` / `FCLProgressBar` / `FCLImageButton` | 基础控件 | ✓ 有 |

## 4. 资源级缺口

### 4.1 核对结论（2026-10-04）：**没有需要补的通用资源**

`git ls-tree` 对比基线后，FCL 有而我们缺的 drawable 共 **71 个**，逐类用 `git grep` 查证后确认
**没有一个是我们需要补的**：

| 类别 | 例子 | 为什么不补 |
|---|---|---|
| **FCL 自己也没用的未使用资源** | `bg_game_menu_inset`、`bg_container_transparent_selected`、`bg_right_menu_button`、`ic_baseline_file_24`、`ic_baseline_more_horiz_24`、`ic_baseline_save_24`、`ic_baseline_update_24` | 全仓库零引用（`git grep -l` 为空）——补了也无任何 UI 效果 |
| **MC 专属** | `ic_boat`、`ic_cube`、`ic_pojav`、`ic_package_2_outlined`、`ic_dashboard_filled`、`ic_mobile_rotate_filled`、`ic_mouse_filled`、`ic_baseline_videogame_asset_24`、`ic_baseline_texture_24`、`ic_baseline_microsoft_24`、`ic_baseline_person_add_24`、`ic_baseline_host_24`、`april_fools.png` | MC 账户/整合包/控制器/渲染器/愚人节彩蛋 |
| **MC 页面专用** | `ic_baseline_arrow_forward_24`（mod/整合包行）、`ic_baseline_arrow_upward/downward_24`（`item_view_group`）、`ic_baseline_folder_24`（`item_favorite` 分组）、`ic_outline_info_24`（本地 mod/资源包页）、`bg_item`/`bg_item_clickable`（`item_input_text` 等控制器页） | 只出现在 MC 页面 |
| **通用但本项目位置不同** | `ic_baseline_cloud_download_24`（FCL 主页的"下载"图标入口） | 我们的下载入口在左侧菜单，不需要页内图标入口 |

> ⚠️ **排查时注意子串误匹配**：`git grep bg_item` 会同时命中 `bg_item_rounded`，
> 一度让我误判"FCL 的启动器设置行用的是 `bg_item`"。查资源引用要加边界或用 `-w`。

**已恢复的资源**（都是"确有使用场景"的那批）：`bg_tab_top_rounded`、`bg_number_seekbar_track`、
`bg_container_white_clickable`、`bg_item_rounded_{top,middle,bottom}`、
`ic_baseline_{list,done,close,earth,restore,edit,palette,jump,settings,info}_24`、`ic_start` 等。

### 4.2 原始缺口清单（对照用，保留）

**drawable**：FCL 112 个 → 我们 32 个（缺的即上表 71 个）。

**anim**：FCL 6 个 → 我们 4 个（缺 `frag_start_anim` / `frag_stop_anim`；
它们仅用于 **fragment 子页**，本项目页内多页用 ViewPager2 + 同一套 30dp/250ms 动画，不需要）。


## 5. 动画对照（用户反馈第 4 条）

FCL 的动画来源（**都有现成实现，直接复用即可**）：

| 动画 | FCL 实现 | 我们的状态 |
|---|---|---|
| 页面切换 | `UIManager.pageChangeCallback`：目标页 `alpha 0→1`、`translationY 30dp→0`、`250ms`，且**只在位置真变时播放** | ✗ 缺（`DshUIManager` 无此回调逻辑） |
| 动态岛文字 | `FCLDynamicIsland.setTextWithAnim` | ✓ 已用 |
| 控件按压缩放 | `anim_scale_large`（`stateListAnimator`） | ✓ 部分用（菜单/图标按钮） |
| 列表行点击 | `bg_container_transparent_clickable` | ✓ 已有 |
| 对话框进出场 | `FCLDialog`（可加 `window.setWindowAnimations`） | ✗ 未设置 |
| 标签栏切换（G1 后） | `frag_start_anim` / `frag_stop_anim` | ✗ 缺 |

## 6. 有意保留的差异（不是缺口）

- 主界面不做 FCL 的「版本卡片 + 启动按钮 + 公告 + 收藏 + 皮肤」
- 不做 MC 账户 / Java 管理 / 控制器 / 联机 / 整合包 / 资源包 / 渲染器 / LWJGL
- 不做 `DraggableTextView`（游戏内可拖动控件）
- 日志页是我们自研的控制台视图（FCL 没有对等页面）

## 7. 建议实施顺序

1. **G5 页面切换动画**（改动最小、体感提升最大：一个回调 + 两个 anim 文件）
2. **G2 关于页**（独立页，正好需要 G1 的子页能力 → 可作为 G1 的第一个落地场景）
3. **G1 页内多页 + 标签栏**（结构改造：设置页拆成「启动器设置 / 插件管理 / 关于」）
4. **G3 主题自定义**（恢复取色对话框 + 数值 SeekBar + 图标，接 ThemeEngine）
5. **G4 文件/图片选择**（恢复 `browser/` 或改用系统 `ACTION_OPEN_DOCUMENT`）
6. 资源补齐（第 4 节清单）随各步骤顺带做
