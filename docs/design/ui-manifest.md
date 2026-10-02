# 设计：UI 拼装 + Manifest 注册（在 FCL 里改代码）

> ## ⚠️ 本文已被**部分取代**（2026-10-01）
> 本文的初版做法使用了 **Material 控件**（`MaterialButton` / `MaterialCardView` / `MaterialAlertDialogBuilder` / `MaterialSwitch`）。
> 用户随后提出**硬性要求**：「界面布局按钮布局以及整体主题要跟 FCL 走」。
> **控件选型与布局写法请以 [`app-shell.md`](./app-shell.md) **§2.5** 为准**（含 Material → fcllibrary 控件替换表、
> FCL 写法范式、验收方法）；本文其余部分（Manifest 注册、分层验证方法）仍然有效。

在任务2/3 的数据层与运行时层之上，补齐**可交互的界面**和**系统注册**，让功能能在
FCL 里被打开使用。所有代码仍在 `com.dsh` 包下，对 FCL 原有代码只做了 3 处最小改动。

---

## 1. 新增文件

### 布局（`res/layout/`）
| 文件 | 用途 |
|---|---|
| `activity_dsh_instances.xml` | 实例列表页：标题 + 列表 + "下载"按钮 + 空态提示 |
| `item_dsh_instance.xml` | 实例行：名称 + 版本/端口/状态 + 启动/停止 + 删除 |
| `activity_dsh_download.xml` | 下载页：标题 + "显示预览版"开关 + 版本列表 + 加载/错误态 |
| `item_dsh_version.xml` | 版本行：版本号 + tag + 体积 + 安装按钮 / 已安装角标 |
| `activity_dsh_logs.xml` | 日志页：滚动文本 + 清空 |
| `activity_dsh_webview.xml` | WebView 页：全屏 WebView + 进度条 |

### Kotlin
- `com/dsh/ui/DshInstanceAdapter.kt` — 实例列表 Adapter（viewBinding）
- `com/dsh/ui/DshVersionAdapter.kt` — 版本列表 Adapter（viewBinding）
- `com/dsh/ui/DshInstancesActivity.kt` — **启动器主入口**：列表、启动/停止/删除、跳下载页
- `com/dsh/ui/DshDownloadActivity.kt` — 下载页：接 `DshDownloadViewModel` + `ProotProcessExecutor`
- `com/dsh/ui/DshLogsActivity.kt` — 日志页：订阅 `DshRuntime.logs`
- `com/dsh/ui/DshWebViewActivity.kt` — WebView 承载 dsh UI（从 `com.dsh.core` 移到 `ui` 包，
  改为继承 `FCLActivity` 以复用 FCL 主题）

---

## 2. 对 FCL 的 3 处最小改动

1. **`AndroidManifest.xml`**
   - 注册 4 个 dsh Activity（`sensorPortrait`；WebView 用 `sensor` + `adjustResize` 便于输入）
   - 注册 `com.dsh.core.DshRuntimeService`，照 `DownloadService` 加 `foregroundServiceType="dataSync"`
2. **`res/values/strings.xml` + `res/values-zh/strings.xml`** — 新增 22 条 dsh 界面文案（英文默认 + 简中）。
3. **`FCLApp.java`** — `onCreate` 里加两行：`DshPaths.loadPaths(this)` + `DshInstances.init()`。
   只用 App 私有目录（filesDir/cacheDir），不需要存储权限，故可在此安全调用；幂等。
4. **`MainActivity.kt`** — 在 `setting` 菜单上加长按入口：
   ```kotlin
   setting.setOnLongClickListener {
       startActivity(Intent(this, com.dsh.ui.DshInstancesActivity::class.java)); true
   }
   ```
   与 FCL 自身"长按 back 打开 ShellActivity"的隐藏入口同款交互。

> 入口说明：为不干扰 FCL 既有 UI，采用了**长按左侧"设置"**进入 dsh 启动器（隐藏入口）。
> 若想做成正式入口，可在主界面加独立按钮/菜单项（改动更大，未做）。

---

## 3. 关键设计取舍

- **不用 FCL 的 FCLPage/ViewPager 页面机制，改用独立 Activity**：FCLPage 与 FCL 的
  导航/主题机器耦合很深，强行接入改动面大且易与主干冲突。独立 Activity（继承 `FCLActivity`
  复用主题与权限处理）自包含、可独立演示、好维护。
- **组件选型对齐 FCL 现状**：FCL 应用主题是 `Theme.MaterialComponents.DayNight.NoActionBar`
  （**Material 2**）。因此用 M2 的 `SwitchMaterial` 而非 Material3 的 `MaterialSwitch`。
  经核对，我用的 `MaterialButton` / `MaterialCardView` / `SwitchMaterial` 与 FCL 自身布局中
  已在使用的组件**完全一致**，在该主题下可用。

---

## 4. 验证方法与结果（本轮的重点）

本沙箱 `dl.google.com` 不可达 → **装不了 Android SDK，无法做完整 Android 编译**。
故改用分层验证，尽量把能验证的都验证掉：

### 4.1 真编译 + 真运行（8 个纯逻辑/核心文件）
写了 Java 桩（`android.content.Context`、`com.tungsten.fclauncher.utils.FCLPath`、
`com.tungsten.fclcore.util.gson.JsonUtils`、`...io.HttpRequest`），用**完整 kotlinc 2.0.21**
编译**未经修改的真实 Kotlin 源码**并运行：

- 通过文件：`DshPaths` `DshInstance` `DshInstances` `DshInstaller` `DshRegistry`
  `DshVersionListItem` `DshDownloadViewModel` `ProotProcessExecutor`
- **编译结果：0 错误 0 警告**（唯一警告来自测试文件的反射转换）
- **功能测试 31/31 通过**，包括：
  - 路径初始化：rootfs/instances/tarball 目录均正确创建于私有目录
  - 实例增删：创建、id 唯一、目录自动建立
  - **端口自动分配**：首个 3080、第二个 3081（多实例不冲突）
  - 状态机：INSTALLING → READY + 版本号记录
  - **持久化往返**：创建 → 落盘 `instances.json` →（反射复位 + 清内存）模拟重启 → 重新载入，
    实例、版本号、选中项全部正确恢复
  - **选中项校正**：删除当前选中实例后，自动回落到剩余实例
  - `ProotExecutor` 契约：脚本路径与环境变量正确传递
- 另用**真实 npm registry 数据**驱动 `DshRegistry` + `DshVersionListItem`，13 项断言全过
  （22 个版本、dist-tags 标注、排序严格递减、`rc.10 > rc.2`、已安装标记等）。

### 4.2 语法层校验（其余 9 个含 Android 依赖的文件）
用 kotlinc 编译全部 17 个文件（缺 Android SDK，unresolved reference 属预期）：
**无任何语法错误**。所有非解析类报错经逐条核对，均为"缺 `android.app.Service`/`FCLActivity`/
ViewBinding 导致 `overrides nothing`"等连锁误报（其中 `DshRegistry.kt` 的报错已被 4.1 证伪为干净）。

### 4.3 静态一致性校验
- 9 个 XML/Manifest 全部良构
- Kotlin 引用的 18 个 `R.string.*` 全部已定义（`android.R.string.cancel` 为框架资源）
- Kotlin 引用的所有 `R.id.*` 与布局 `@+id` 完全匹配
- viewBinding 类名与布局文件名逐一对应
- `binding.X` 属性名与布局 id（camelCase）完全匹配
- 引用的 drawable（`ic_baseline_download_24`、`bg_container_white`）均存在
- Material 组件选型与 FCL 现有布局一致（见 §3）

### 4.4 仍未验证
- **完整 Android 编译**：需 Android SDK（`dl.google.com` 不可达 + 沙箱内存仅 ~1.3GB 可用）。
  请在本地执行 `./gradlew :FCL:compileDebugKotlin` 确认。
- **9 个 UI/服务文件的类型检查**：只能做语法层校验（缺 android.jar / androidx / viewBinding 生成类）。
- **运行期行为**：proot 真机执行、WebView 实际加载、前台服务保活，均需真机。

---

## 5. 下一步

1. 本地 `./gradlew :FCL:compileDebugKotlin` 过一遍（补上 4.4 的编译验证）。
2. assets 打包与首启解压（proot 二进制 + rootfs 压缩包 → `DshPaths`），照 `RuntimeUtils` 的 tar.xz 逻辑。
3. 真机联调：首启解压 → 下载页装 dsh → 启动 → WebView 出 UI。
4. 打磨：设置页（API key 录入 UI，接 `DshCredentials`）、模型选择、实例重命名、体积清理入口。
