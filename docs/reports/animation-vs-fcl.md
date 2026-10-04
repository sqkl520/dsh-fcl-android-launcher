# 动画对照报告：我们 vs FCL 原版

> 对照对象：本地基线 `647919c`。逐项核对 FCL 的全部动画来源（资源 + 代码调用点）与我们的现状。
> 结论：**FCL 一共 16 类动画，我们已对齐 13 类；本轮修正 2 处偏离，1 类确认不需要。**

## 1. 完整对照表

| # | 动画 | FCL 实现 | 我们 | 状态 |
|---|---|---|---|---|
| 1 | 卡片按压缩放（小） | `anim_scale`（scaleX/Y → **0.9**），用于 20 个布局的**可点击行容器** | 同 | ✅ 一致 |
| 2 | 图标/大元素按压缩放 | `anim_scale_large`（→ **1.5**）：`FCLMenuView`（代码里 setStateListAnimator）、`item_version`/`item_profile`/`item_remote_version` 的图标按钮、`activity_main` 的 52dp 元素 | 本轮修正 | ✅ 已对齐 |
| 3 | 页面切换（tab 级） | `UIManager` / `FCLMultiPageUI`：`alpha 0→1` + `translationY 30dp→0`，250ms，**仅位置真变时播**、同步执行不 post | 同（`DshUIManager` + `DshMultiPageUI`） | ✅ 一致 |
| 4 | 动态岛文字切换 | `FCLDynamicIsland.setTextWithAnim`（按 `height/width` 算 scale → `anim.refresh/run`） | 用同一个控件 | ✅ 一致 |
| 5 | 菜单图标选中态 | `FCLMenuView`：选中 `dkColor` / 未选中 `autoTint` + Ripple（`ltColor`，半径 20dp） | 用同一个控件 | ✅ 一致 |
| 6 | 图标按钮按压涟漪 | `FCLImageButton` **自带** `RippleDrawable`（`ltColor`；半径 no_padding?12:20dp）+ `FIT_XY` | 本轮改回 `FCLImageButton` | ✅ 已对齐 |
| 7 | Spinner 下拉弹窗进出场 | `fcl_spinner_popup_enter/exit` + `FCLSpinnerPopupAnimation`（`ListPopupWindow.setAnimationStyle`） | 资源与 style 均在，`FCLSpinner` 原样使用 | ✅ 一致 |
| 8 | Spinner 箭头展开旋转 | `FCLSpinner.animateArrow`（ValueAnimator 0→10000 → `level`） | 同上（控件自带） | ✅ 一致 |
| 9 | 不定进度条 | `bg_progress_indeterminate`（`animation-list`：rect1/rect2）作为 `indeterminateDrawable` | 本轮补齐（任务区/前置页原本没设） | ✅ 已对齐 |
| 10 | Activity 转场 | `ActivityOptionsCompat.makeCustomAnimation(0, 0)`（即**硬切、无动画**） | 同 | ✅ 一致 |
| 11 | 对话框进出场 | **FCL 未设置**（无 `setWindowAnimations` / `windowAnimationStyle`） | 同（我们也没设） | ✅ 一致（非缺口） |
| 12 | RecyclerView 增删动画 | 默认（FCL 未自定义 `ItemAnimator`；仅 `ModListPage` 临时禁用过） | 默认 | ✅ 一致 |
| 13 | 进度条着色 | `FCLProgressBar.refreshTheme` → `progressTintList` = `dkColor` | 用同一个控件 | ✅ 一致 |
| 14 | 侧滑菜单 | `com/mio/ui/widget/SwipeMenuLayout`（translationX 吸附开合） | 未使用 | ⛔ 本项目无此需求 |
| 15 | 波浪进度 | `com/mio/ui/view/WaveProgressView`（ValueAnimator 无限循环） | 未使用 | ⛔ MC 专属（下载游戏资源用） |
| 16 | 动画工具类 | `com/mio/util/AnimUtil.kt`（translationX/Y/Z、rotation、scaleX/Y、alpha、delay、interpolator） | 未恢复 | ⛔ 不需要（我们用 `view.animate()` 链式 API，能力等价） |
| — | `frag_start_anim` / `frag_stop_anim` | 500ms alpha + translateX 100%（**fragment 子页**用） | 未恢复 | ⛔ 不需要（我们页内多页是 ViewPager2 + 第 3 类的 30dp/250ms） |

## 2. 本轮修正的 2 处偏离

### （1）图标按钮：错改成 `FCLImageView`，且误删了按压缩放
上一轮我依据 `item_download_task.xml` 的取消按钮，得出"图标按钮一律用
`FCLImageView + background`、不设 stateListAnimator"——**这个结论是错的**（过度概括）。

实际 FCL 里两种控件分工明确（差异来自控件实现）：

| | `FCLImageButton` | `FCLImageView` |
|---|---|---|
| 图标承载 | `image` → **`setImageDrawable`（`android:src`）** | `image` → **`setBackground`（`android:background`）** |
| 缩放 | **`setScaleType(FIT_XY)`** | 未设置（默认 FIT_CENTER） |
| 按压反馈 | **自带 `RippleDrawable`**（`ltColor`，半径 12/20dp） | 无 |
| 用途 | **可点击的图标按钮** | **静态展示图标** |

- FCL 的可点击图标按钮用 `FCLImageButton + src + anim_scale_large`：
  `item_version`（设置/删除）、`item_profile`（删除）、`item_remote_version`（wiki/save）
- `FCLImageView + background` 用于**不强调交互**的图标：`item_download_task` 的取消、列表行左侧图标

**修正**：日志页 3 个按钮（筛选/复制/清空）与下载页刷新按钮改回
`FCLImageButton + android:src + anim_scale_large + auto_tint`；任务行的取消按钮保持
`FCLImageView + background`（与 FCL `item_download_task` 一致）。

### （2）不定进度条缺 `indeterminateDrawable` + style 用错
FCL 的水平进度条一律是：

```xml
style="@style/Widget.AppCompat.ProgressBar.Horizontal"
android:layout_height="3dp"                       <!-- FCL 所有水平进度条都是 3dp -->
android:indeterminateDrawable="@drawable/bg_progress_indeterminate"
android:max="1000"
```

我们任务区/前置页原本用的是 `style="?android:attr/progressBarStyleHorizontal"`（系统 attr）
且**没设** `indeterminateDrawable`，前置页还写成了 8dp。

**修正**：统一为 FCL 的写法（3dp + AppCompat style + `bg_progress_indeterminate`）。

## 3. 结论

- FCL 的动画**没有"隐藏菜单"**：所有动画都在 `res/anim`（6 个）、`res/xml`（2 个 scale selector）
  与四处代码调用点（`UIManager`/`FCLMultiPageUI`、`FCLMenuView`、`FCLSpinner`、`FCLDynamicIsland`）里
- 我们**已完成 13/16**；未对齐的 3 类里，2 类是本项目不需要（侧滑菜单、波浪进度），
  1 类是可选工具类
- 真正需要"看起来像 FCL"的几个关键动效——**页面切换、菜单选中、按压缩放、按压涟漪、
  Spinner 弹窗、动态岛文字、不定进度条**——现已全部对齐
