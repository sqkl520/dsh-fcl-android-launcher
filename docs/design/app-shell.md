# 设计：应用外壳改造 —— 从 MC 启动器改为 dsh 启动器

> **目标形态**：这个 app 就是给 dsh 用的。MC 那套 GUI（运行时安装页 / EULA / MC 主界面）全部隐藏；
> 但**保留 FCL 的界面风格与底层 GUI 框架**（fcllibrary / ThemeEngine / 背景图/视频 / 动态岛）。
> 新的 dsh 外壳**照 FCL 的 MC 启动器形态来做**：左侧滑出菜单 + ViewPager2 内容区 + 右侧面板 + 动态岛标题。
>
> 本文只讲**怎么做**，不含代码改动。落地阶段见 §7。

---

## 1. 当前问题（为什么要改）

启动链路（读源码确认）：

```
SplashActivity(LAUNCHER)
   └─ 检查 8 项 MC 运行时：lwjgl && cacio && cacio17 && java8 && java17 && java21 && java25 && jna
        ├─ 全装 → enterLauncher() → MainActivity（MC 启动器主界面）
        └─ 没装全 → start() → EulaFragment（首启 EULA）/ RuntimeFragment（就是你截图那个"安装或更新运行环境"）
```

而 dsh 的唯一入口，是在 `MainActivity` 里**长按左侧"设置"**（`DshInstancesActivity`）。

**后果**：
- 想用 dsh，**先被迫下载约 1GB Minecraft 运行时**（JRE 8/17/21/25 + LWJGL + Caciocavallo ×2 + JNA）；
- 装完还要穿过 MC 主界面，再"长按设置"才能摸到 dsh。
- 对一个 dsh 启动器，这个顺序完全是反的。

---

## 2. 保留什么 / 去掉什么

### 保留（底层框架 + 视觉风格）
| 保留项 | 位置 | 用途 |
|---|---|---|
| `FCLActivity` | `fcllibrary/component/` | 主题/全屏/权限/夜间模式的基类 |
| `ThemeEngine` | `fcllibrary/component/theme/` | 主题与配色（外观风格全靠它） |
| `FCLCommonUI` / `FCLBaseUI` | `fcllibrary/component/ui/` | 页面基类（contentView + 生命周期 + `refresh()`） |
| `FCLMenuView` / `FCLButton` / `FCLTextView` … | `fcllibrary/component/view/` | FCL 风格控件 |
| 背景图 / 视频 / 动态岛 | `activity_main.xml` 的 `background`/`video_view`/`title` | 视觉 |
| `FCLPath` / `HttpRequest` / `DownloadManager` / `RuntimeUtils` | `fclauncher/utils`、`fclcore`、`mio/download` | 路径 / 网络 / 下载 / 解压 |
| `DshPaths` / `DshBootstrap` / `DshInstances` … | `com/dsh/core` | 已有 dsh 逻辑（不动） |

### 去掉（MC 专属 GUI 与入口）
| 去掉项 | 位置 | 处理 |
|---|---|---|
| 8 项运行时门禁 | `SplashActivity.init()` | 换成 dsh 底座自检 |
| MC EULA 页 | `fragment/EulaFragment` | 从入口移除（或换成 dsh 说明页） |
| 运行时安装页 | `fragment/RuntimeFragment`（截图） | 从入口移除（或换成 dsh 底座准备页） |
| MC 菜单项与 8 个 MC 页 | `ui/main`、`ui/manage`、`ui/download`、`ui/controller`、`ui/multiplayer`、`ui/setting`、`ui/account`、`ui/version` | 不注册进新外壳 |
| MC 专属 Activity | `JVMActivity`/`ControllerActivity`/`ShellActivity`/`JVMCrashActivity` | 从可达路径移除（代码可先留着） |

> 原则：**先"不可达"，后"删除"**。阶段 1~3 只让它进不去；阶段 4（可选）再裁代码与资源减体积。

---

## 2.5 硬性要求：界面布局 / 按钮布局 / 整体主题一律跟 FCL 走（2026-10-01 新增）

> **要求原文**：「界面布局按钮布局以及整体主题要跟 FCL 走」。
>
> 这条**覆盖**本目录 `ui-manifest.md` 的初版做法（那版用了 Material 控件）。
> 它是**验收标准**：dsh 页面的观感与交互必须与 FCL 原界面一致，不能出现"Material 风格混进 FCL 外壳"的割裂感。

### 2.5.1 三条拆解

| # | 要求 | 具体含义 |
|---|---|---|
| ① | **整体主题跟 FCL** | 颜色/背景/圆角/点击反馈全部由 `ThemeEngine` + `ThemeData` 驱动；页面背景用 FCL 的 `background_{light,dark}.jpg`；控件通过 `app:use_theme_color` / `app:auto_tint` 自动跟随主题换色（**不要在布局里写死颜色**） |
| ② | **按钮布局跟 FCL** | 用 `FCLButton`/`FCLImageButton`，按钮排布沿用 FCL 惯例（对话框底部 `positive`/`neutral`/`negative`/`extra` 四键，等宽 `layout_weight="1"`；页面内动作按钮用 `FCLImageButton` + `app:auto_tint`） |
| ③ | **界面布局跟 FCL** | 页面骨架照 FCL：左侧 `FCLMenuView` 滑出菜单 + `ViewPager2` 内容区 + 右侧面板 + 顶部 `FCLDynamicIsland`；列表项用 FCL 的"容器"写法（见下） |

### 2.5.2 控件替换表（**当前 dsh 布局的实际用量 → 目标**）

现状（2026-10-01 实测，8 个 dsh 布局）——**全是 Material，0 个 fcllibrary 控件**：

| 当前使用 | 用量 | → 替换为 | 备注 |
|---|---|---|---|
| `com.google.android.material.button.MaterialButton` | **21** | `com.tungsten.fcllibrary.component.view.FCLButton` | 加 `app:ripple="true"`；`style="...OutlinedButton"` / `TextButton` 改为 FCL 的 `app:shape` 或直接默认样式 |
| `com.google.android.material.card.MaterialCardView` | **3** | `FCLConstraintLayout` / `FCLLinearLayout` + `android:background="@drawable/bg_container_white"` + `app:auto_tint="true"` | **FCL 没有 Card 控件**，它用"容器 + 半透明底 + 自动着色"实现卡片观感（见 `item_version.xml` / `item_file_browser.xml` 的写法） |
| `com.google.android.material.textfield.TextInputLayout` | **3** | 去掉（FCL 不用 floating label 包装） | 直接用 `FCLEditText` + 上方 `FCLTextView` 当标签（FCL 惯例如 `dialog_edit.xml`） |
| `com.google.android.material.textfield.TextInputEditText` | **3** | `com.tungsten.fcllibrary.component.view.FCLEditText` | 加 `app:auto_edit_tint="true"` |
| `com.google.android.material.switchmaterial.SwitchMaterial` | **2** | `com.tungsten.fcllibrary.component.view.FCLSwitch` | — |
| `MaterialAlertDialogBuilder`（代码里） | 若干 | `com.tungsten.fcllibrary.component.dialog.FCLAlertDialog.Builder` | API：`setAlertLevel(AlertLevel.ALERT/INFO)` + `setPositiveButton{}/setNegativeButton{}/setNeutralButton{}/setExtraButton{}` + `useAutoLink()` |
| `androidx.recyclerview.widget.RecyclerView` | 保持 | **保持**（FCL 自己也用 RecyclerView） | 但列表项布局内要用 FCL 控件 |
| 普通 `TextView` / `Button` | — | 尽量换 `FCLTextView` / `FCLButton` | `FCLTextView` 的 `app:auto_text_tint` 才会跟随主题 |
| `ProgressBar` | — | `FCLProgressBar` | — |

### 2.5.3 FCL 写法范式（照抄这些属性，别自己发明）

```xml
<!-- 按钮 -->
<com.tungsten.fcllibrary.component.view.FCLButton
    android:id="@+id/positive"
    android:layout_width="0dp" android:layout_weight="1"
    android:layout_height="wrap_content"
    android:text="@string/dialog_positive"
    app:ripple="true" />                       <!-- 有水波反馈；FCL 默认靠 shape 画底 -->

<!-- 卡片/容器（替代 MaterialCardView） -->
<com.tungsten.fcllibrary.component.view.FCLConstraintLayout
    android:layout_width="match_parent" android:layout_height="wrap_content"
    android:background="@drawable/bg_container_white"
    android:padding="10dp"
    app:auto_tint="true">                      <!-- 自动跟随主题给背景着色 -->
    ...
</com.tungsten.fcllibrary.component.view.FCLConstraintLayout>

<!-- 文本 / 输入 -->
<com.tungsten.fcllibrary.component.view.FCLTextView
    app:auto_text_tint="true" ... />
<com.tungsten.fcllibrary.component.view.FCLEditText
    app:auto_edit_tint="true" ... />
```

可用自定义属性（`res/values/attrs.xml`）：`use_theme_color`、`auto_tint`、`text_use_theme_color`、
`auto_text_tint`、`auto_edit_tint`、`auto_src_tint`、`auto_text_background_tint`、`ripple`、
`shape`(integer)、`auto_padding`、`no_padding`、`follow_theme`。

### 2.5.4 适用范围与例外

- **适用**：所有 dsh 页面（实例/管理/下载/日志/设置）与其列表项、对话框、以及阶段 1~3 要新建的外壳。
- **例外（可保留 Material / AndroidX 原生）**：
  - `RecyclerView`、`ViewPager2`、`ConstraintLayout` 这类**布局容器**（FCL 自己也在用）；
  - `WebView`（dsh Web UI 的承载，无 FCL 对应物）——它内部是 dsh 自己的网页，不受本要求约束；
  - `MaterialAlertDialogBuilder` 若已用完可保留，但**新代码统一用 `FCLAlertDialog`**。
- **依赖**：`material` 依赖**保留**（FCL 的 M2 主题 `Theme.FoldCraftLauncher` 继承自 `Theme.MaterialComponents...`，
  去掉会破坏主题链），只是**布局里不再直接用 Material 控件**。

### 2.5.5 验收方法（怎么检查"跟 FCL 走"）

```sh
cd FCL/src/main/res/layout   # 在源码仓根内
# 1) dsh 布局里不应再有 Material 控件（期望输出为空）
grep -l "com.google.android.material" *dsh*.xml

# 2) 应有 fcllibrary 控件（期望每个页面都有若干条）
grep -c "com.tungsten.fcllibrary.component.view" *dsh*.xml

# 3) 代码里新对话框应走 FCLAlertDialog（期望 MaterialAlertDialogBuilder 逐步归零）
grep -rn "MaterialAlertDialogBuilder\|FCLAlertDialog" ../../../java/com/dsh/
```

> **落地时机**：随**阶段 2（五页迁移）**一起做——页面要从 `Activity` 改成 `FCLCommonUI` 时本来就要重写布局，
> 那时一并把控件换成 FCL 的，避免改两遍。

---

## 3. 蓝本：FCL MC 启动器外壳结构

`activity_main.xml`（读源码整理）：

```
ConstraintLayout
├─ background            背景（图片）
├─ video_view            VideoView（动态壁纸）
├─ left_menu             左侧滑出菜单（FCLMenuView × 7）
│     home 主页 / manage 管理 / download 下载 / controller 控制器
│     multiplayer 联机 / setting 设置 / back 返回
├─ right_menu            右侧面板
│     account 账户卡 / version_card 版本卡 / start 启动按钮
│     download_wave_progress + download_panel（下载进度与面板）
├─ ui_layout             ViewPager2（垂直）—— 内容区
└─ title                 FCLDynamicIsland 动态岛标题
```

**页面机制（`ui/UIManager.kt`）**：
- `factories` 列表注册 8 个 `FCLCommonUI` 页（MainUI / ManageUI / DownloadUI / ControllerUI /
  MultiplayerUI / SettingUI / AccountUI / VersionUI）；
- `ViewPager2` 垂直承载，`offscreenPageLimit` 默认 → **页面随生命周期创建/销毁，不保留状态**；
- 点菜单 → `switchUI(position)` → 切换时统一"淡入 + 上滑"动画；
- 当前页回调 `pageSelectedListener` → `MainActivity` 用来同步**菜单高亮**与**动态岛标题**。

**页面基类**：`FCLCommonUI(context, layoutId)` ← `FCLBaseUI`，提供
`contentView` / `findViewById` / `onCreate` / `onPause` / `onResume` / `onBackPressed` /
抽象 `refresh(Object...)` / `isShowing()`。

---

## 4. dsh 外壳设计（照搬后的样子）

### 4.1 布局（新增 `activity_dsh_main.xml`，复制 `activity_main.xml` 骨架）

保留 `background` / `video_view` / `left_menu` / `right_menu` / `ui_layout` / `title` 六个部件，
只把菜单项与右侧面板内容换掉。

### 4.2 菜单项映射

| FCL(MC) | dsh | 说明 |
|---|---|---|
| home 主页 | **实例** | dsh 实例列表（作为默认主页面） |
| manage 管理 | **管理** | 保留（后续可放插件安装等管理功能） |
| download 下载 | **下载** | dsh 版本下载（npm registry） |
| controller 控制器 | —（去掉） | MC 专属 |
| multiplayer 联机 | —（去掉） | MC 专属 |
| setting 设置 | **设置** | API key / 模型 / 端口 / 体积清理 / 自检 |
| （新增） | **日志** | 运行时日志 |
| back 返回 | back 返回 | 保留 |

### 4.3 内容区页面注册（ViewPager2 顺序）

| pos | 页（新类） | 布局 | 由谁改造来 |
|---|---|---|---|
| 0 | `DshInstancesUI` | `ui_dsh_instances` | `ui/DshInstancesActivity` |
| 1 | `DshManageUI` | `ui_dsh_manage` | 新建（占位：后续放插件安装等管理功能） |
| 2 | `DshDownloadUI` | `ui_dsh_download` | `ui/DshDownloadActivity` |
| 3 | `DshLogsUI` | `ui_dsh_logs` | `ui/DshLogsActivity` |
| 4 | `DshSettingsUI` | `ui_dsh_settings` | `ui/DshSettingsActivity` |

> **WebView 不进 ViewPager**：`DshWebViewActivity` 仍是独立全屏 Activity（加载 dsh 的 Web UI，
> 需要沉浸式、可返回），由实例页"启动"动作直接 `startActivity` 打开。

### 4.4 右侧面板映射

| FCL(MC) | dsh | 内容 |
|---|---|---|
| 账户卡 | **当前实例卡** | 实例名 / dsh 版本 / 端口 / 状态 |
| 版本卡 | **底座卡** | proot / rootfs / Node 就绪状态（接 `DshBootstrap.isReady` + `verify`） |
| 启动按钮 | **启动 / 停止 dsh** | 接 `DshRuntime.start/stop` + `DshRuntimeService` |
| 下载面板 | 保留 | 安装/下载进度（接 `DshInstaller`） |

### 4.5 动态岛标题
显示当前页名：实例 / 管理 / 下载 / 日志 / 设置。

---

## 5. 改造后的启动链路

```
SplashActivity（LAUNCHER，保留但瘦身）
   └─ 只做：DshPaths.loadPaths + DshInstances.init + DshBootstrap 自检
        └─ DshMainActivity（新外壳）
             ├─ ViewPager2：实例 / 管理 / 下载 / 日志 / 设置
             └─ 右侧：实例卡 + 底座卡 + 启动按钮
             └─ 启动动作 → DshWebViewActivity（全屏 Web UI）
   ✗ 不再有：MC 运行时门禁 / MC EULA / MC 主界面
```

> 关键：`DshMainActivity` 继承 `FCLActivity`（自动获得主题/背景/全屏），
> **绝不能**触发 MC 单例初始化 —— 见 §6。

---

## 6. 风险与注意（写代码前必须确认）

1. **别触发 MC 单例初始化**
   `MainActivity.onCreate` 里有 `ConfigHolder.init()` + `RendererManager.init(this)`（MC 专属）。
   新外壳**不要**抄这段，否则会去读 MC 配置、初始化渲染器，可能与 dsh 流程冲突或直接崩。
   需要确认：`ThemeEngine` / `FCLActivity` / `FCLMenuView` 是否隐含依赖这些 MC 单例（目前看没有，但要验）。

2. **Activity → FCLCommonUI 的改造差异**
   现有 4 个 dsh 页是 Activity（`onCreate` + `setContentView` + `lifecycleScope`）。
   改成 `FCLCommonUI` 后：生命周期变成 `onCreate/onPause/onResume/onBackPressed`，
   且**页面会被 ViewPager 回收**（不保留状态）→ 订阅（StateFlow collect）要跟着页面的
   创建/销毁挂载与注销，否则会泄漏或重复订阅。

3. **返回键**
   `UIManager.onBackPressed()` 会转发给当前页；`DshWebViewActivity` 自己要处理 WebView 历史回退。

4. **主题与背景**
   `FCLActivity` → `ThemeEngine.setupThemeEngine` 已覆盖；背景图/视频沿用 `activity_main.xml` 写法。

5. **体积**
   MC 的 35 个 native `.so` 与资源仍会在包内（阶段 4 才裁剪）。
   短期 APK 仍约 190MB —— 但**用户不再被迫下载 MC 运行时**（那 1GB 是运行期下载，不是包体）。

6. **DshLogsUI 与 DshLogBus**
   日志页依赖 `DshLogBus`；作为常驻页面要处理"页面被回收后重订阅"。

7. **`DshBootstrap` 自检作为启动门槛**
   原来门槛是"8 项 MC 运行时"，新门槛应该是"dsh 底座就绪"。
   但**不能硬拦**（否则没放大文件时进不去 app）→ 建议：能进主界面，底座没就绪时在
   实例页顶部显示 banner（`view_dsh_bootstrap_banner` 已存在）引导准备。

---

## 7. 分阶段实施

| 阶段 | 内容 | 涉及 | 结果 | 状态 |
|---|---|---|---|---|
| **0 打通入口（最小）** | `SplashActivity` 跳过 MC 运行时门禁，直接进 `DshInstancesActivity` | 1 个文件 | 立刻能进 dsh 界面，验证 GUI | ✅ 已做 |
| **1 外壳骨架** | 新增 `DshMainActivity` + `activity_dsh_main.xml` + `DshUIManager`（ViewPager2 + 菜单） | 3~5 个文件 | 有 FCL 风格的空壳 | ✅ 已做 |
| **2 五页迁移** | 5 个 dsh 页由 Activity 改为 `FCLCommonUI`，接入 ViewPager2；菜单联动 | 4~10 个文件 | 五页可切换 | ✅ 已做 |
| **3 右侧面板 + 动态岛** | 实例卡 / 底座卡 / 启动按钮 / 标题联动 | 2~3 个文件 | 主界面完整 | ✅ 已做（**外壳改横屏**，右面板常驻） |
| **4（可选）裁剪** | 移除不可达的 MC Activity 注册与其资源、考虑剔除 MC native/资源 | 多处 | 减体积 | 待做 |

> **阶段 1~3 已实施（2026-10-01 ~ 10-02）**。决策：外壳**整体横屏**（FCL 原版形态），
> 右面板常驻；`DshWebViewActivity` 保持 `sensor` 可自由旋转。详见 `../CHANGELOG.md`。

**建议先做阶段 0**：改动最小，能立刻验证"这个方向对不对"，再决定要不要投入 1~3。

> **阶段 0 已实施（2026-09-29）**：见本文 §11。

---

## 8. 验证方式

- **编译**：`sh run-compile.sh`（BUILD SUCCESSFUL）
- **单测**：`sh run-tests.sh`
- **真机**：
  - 启动 **直接进 dsh 外壳**，不再出现"安装或更新运行环境"页；
  - 四个页面可切换、菜单高亮与标题同步；
  - 启动按钮能拉起 WebView（依赖 M1 的两个大文件）；
  - 全程**不需要**装 MC 运行时。

---

## 9. 对 ROADMAP 的影响

- 这是一条**新的独立工作流（GUI 改造）**，**不依赖 M1 的两个大文件**（proot 二进制 / rootfs）→ 可以**先做**。
- 它与 M1（真机点亮数据链路）是"外壳"与"内核"的关系，可并行：
  - GUI 改造完成 = app 能正确进入 dsh 界面（即使底座未就绪也不挡路）；
  - M1 完成 = 界面里的"装 dsh → 启动 → WebView"真正跑通。
- 建议在 `ROADMAP.md` 的 M3（体验完善）里，把"外壳改造"提为独立前置项。

---

## 10. 与其他文档的关系

- 现有 dsh 界面来源：本目录 `ui-manifest.md`（那是"在 FCL 里加 dsh 页"的初版设计，本文是它的**下一步演进**）。
- 底座就绪判定与首启解压：`../PACKAGING.md`、`../../FCL/src/main/java/com/dsh/core/DshBootstrap.kt`。
- 真机联调总步骤：`../PLAN.md` §8.5。
- 里程碑与优先级：`../ROADMAP.md`。

---

## 11. 阶段 0 实施记录（2026-09-29）

**改动文件**：仅 1 个 —— `FCL/src/main/java/com/tungsten/fcl/activity/SplashActivity.kt`

**改了什么**

1. 新增开关常量：
   ```kotlin
   companion object {
       private const val DSH_MODE = true   // false 即一键回退到原 FCL(MC) 流程
   }
   ```
2. `init()` 里改为分支：
   ```kotlin
   if (!DSH_MODE) initState()            // dsh 模式跳过 MC 运行时探测 + resolv.conf
   ...
   if (DSH_MODE) enterDsh()
   else if (lwjgl && cacio && ...) enterLauncher()
   else start()
   ```
3. 新增 `enterDsh()`：直接 `startActivity(DshInstancesActivity)` + `finish()`。
   **刻意不调用** `RendererManager.init` / `JavaManager.init` / `Controllers.init` /
   `ConfigHolder.init`（MC 专属）。

**保留未动的部分**
- EULA 弹窗（`isAgree`）与存储权限流程仍走原路 —— 手机上此前已授权，不影响；
  后续若确认 dsh 完全不需要外部存储权限，可在阶段 1 一并去掉。
- `initState()`、`enterLauncher()`、`start()` 及所有 MC 相关代码**原样保留**（只是 dsh 模式下不再走到）。

**验证**
- `sh run-compile.sh` → **BUILD SUCCESSFUL**（2m06s）
- `sh run-tests.sh` → **TOTAL=23 FAILED=0**
- `DshInstancesActivity` 已在 Manifest 注册（第 139 行）

**预期效果（真机）**
- 启动 → 不再出现"安装或更新运行环境"页 → 直接进 dsh 实例列表。
- 全程无需安装任何 MC 运行时。

**回退方式**：把 `DSH_MODE` 改回 `false` 重新编译即可。
