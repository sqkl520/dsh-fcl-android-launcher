# 审查请求书：移除全部 Minecraft 代码与资源

> ## ⚠️ 工作区保护（最高操作风险）—— 2026-10-01 已解除
> 送审时 `/workspace/FCL` 有 **11 项 untracked 条目**，包含整个 `com/dsh` 主体
> （25 源文件 + 10 assets + 8 布局 + 2 测试）。**任何** `git clean -fd`、`git checkout -- .`、
> `git reset --hard`、IDE 的"revert"都会**不可恢复地**删除它们。
> **✅ 已固化：`git add -A && git commit` → `4aea9e5`**（基线 `f4f2624`），工作区已干净，
> 回滚变为一条 `git revert 4aea9e5`。改动前另已备份 45 个 untracked 文件到
> `/workspace/_review_evidence/untracked_backup/`。

> **📌 审查已完成，结果见 [`mc-removal-impact-review.md`](mc-removal-impact-review.md)。**
> 结论摘要：删除边界**正确**（问 1/问 2 均为"未发现"）、Manifest 四类**零残留**、ksp 清理**完整**、
> Node 协议**零改动**；发现 **P0 = 0 / P1 = 1（M-01）/ P2 = 6 / P3 = 7**。
> **M-01 与 M-02 已落地修复（补丁 A / B）**，并已在本请求书 §0 / §7.B / §7.C / §7.D 就地加修正批注。
> 若本请求书与审查报告冲突，**以审查报告为准**。


> 本文是**送审说明**，配合 `reports/mc-removal.md`（变更详情）一起提交给审查方（AI 或人工）。
> 请先读本文建立背景，再按 §8「重点关注」逐项核查 mc-removal.md 的对应章节。

---

## 0. 审查文档

| 项 | 值 |
|---|---|
| **主审查对象** | `docs/reports/mc-removal.md`（469 行，12 章）—— 本次变更的完整记录 |
>
> **修正（见 mc-removal-impact-review.md §1.4 D-8）**：实测 `wc -l` = **487 行**（加批注后更长）；
> 一级标题 `^## ` 实测 **12 个，编号 0~12 但缺 §6**（§5 直接跳 §7）。引用时请注意章节缺口。
| 送审方式 | 主文档 + 本文 + 代码仓库（见 §1） |
| 审查范围 | **仅限本次「移除全部 MC」的变更**；`com.dsh` 业务逻辑的既有缺陷不在本次范围（已有 round2~round5 四轮报告） |

**建议的审查顺序**：本文 §8 → mc-removal.md 的 §3/§4（留/删清单）→ §7（依赖临界点）→ §9（残留与风险）→ §10（独立复核命令）。

---

## 1. 项目代码 / 仓库

| 项 | 值 |
|---|---|
| 仓库根 | `/workspace/FCL`（沙箱内路径；对应宿主 `/data/data/me.rerere.rikkahub/files/workspaces/<uuid>/files/FCL`） |
| 上游来源 | `https://github.com/FCL-Team/FoldCraftLauncher.git` |
| 克隆方式 | `git clone --depth 1 --branch main`（**浅克隆**，无历史） |
| 当前分支 | `main` |
| 同工作区其他目录 | `ZipFileSystem/`（保留的 Gradle 模块）、`build/`、`config/`、`docs/`、`gradle/`、`scripts/` |
| 文档目录 | `/workspace/docs/`（**在仓库外**，不随 git 提交，需单独拷贝） |

### 变更后目录结构（只列保留部分）

```
FCL/
├── build.gradle.kts                    [已改]
├── src/main/
│   ├── AndroidManifest.xml             [已改]
│   ├── assets/dsh/                     dsh 首启解压底座（唯一保留的 assets，10 文件 / 70 KB）
│   ├── java/
│   │   ├── com/dsh/                    25 文件 —— dsh 启动器主体（★未跟踪，本次不涉及）
│   │   │   ├── core/                   18 文件：
│   │   │   │                           DeepSeekApi / DshAppScope / DshBootstrap / DshCredentials /
│   │   │   │                           DshDownloadViewModel / DshInstaller / DshInstance / DshInstances /
│   │   │   │                           DshLogBus / DshPaths / DshRegistry / DshRuntime /
│   │   │   │                           DshRuntimeService / DshServices / DshVersionListItem /
│   │   │   │                           ProotCommand / ProotProcessExecutor / SingleFlight
│   │   │   │                           （注：`Probe` / `UrlScanner` / `ProcessUtil` 三个 object 声明在
│   │   │   │                            `DshRuntime.kt` 内部，不是独立文件）
│   │   │   └── ui/                     7 文件：DshInstances / DshDownload / DshLogs / DshSettings /
│   │   │                               DshWebView Activity + DshInstanceAdapter / DshVersionAdapter
│   │   ├── com/mio/util/               4：AndroidUtil / DialogUtil / DisplayUtil / ImageUtil（已裁剪）
│   │   ├── com/tungsten/fcl/           9：FCLApp / activity/SplashActivity / util/{RuntimeUtils 等}
│   │   ├── com/tungsten/fclauncher/utils/  2：FCLPath / Architecture
│   │   ├── com/tungsten/fclcore/       394：fakefx(292) / util(85) / task(12) / event(5)
│   │   └── com/tungsten/fcllibrary/    60：UI 框架（22 个自定义控件 / 主题引擎 / 页面基类 / 浏览器 / 崩溃上报）
│   └── res/
│       ├── layout/    19（其中 8 个是 dsh 的）
│       ├── drawable/  17（含 background_light/dark.jpg 两张背景图）
│       ├── anim/      2
│       ├── values/    169 条字符串     values-zh/  166 条
│       ├── values-night/  values-v31/  xml/  mipmap-*/
├── src/test/java/com/dsh/              2 个单测文件（★未跟踪）
settings.gradle.kts                     [已改] 只留 :FCL :ZipFileSystem
ZipFileSystem/                          12 文件 —— com.sun.nio.zipfs 实现
```

**已整个删除的顶层内容**：`Terracotta/`（MC 联机）、`LWJGL/`（LWJGL 版本目录）、
`FCL/src/main/{jniLibs,jni,jreAssets}/`、`FCL/libs/*.aar`。

---

## 2. 修改前版本 / 修改后版本

| 项 | 修改前 | 修改后 |
|---|---|---|
| 基线 commit | `f4f2624`（`main`，上游最新，浅克隆） | — |
| 工作区状态 | 干净（上游原样） | **全部改动尚未 commit**（见下） |
| 提交状态 | — | `git status`：**1228 删除 / 11 修改 / 11 新增未跟踪** |
| diff 统计 | — | `git diff --shortstat HEAD` = **1239 files changed, 323 insertions(+), 226529 deletions(-)** |
| 版本号 | `versionName 1.3.3.3` / `versionCode 1333` | **未改动**（沿用 FCL 版本号） |
| 是否发布 | — | **未发布、未打包 APK** |

**关键提示（影响审查方式）**：
1. **没有 patch / diff 文件可直接下载** —— 差异存在于工作区，审查方若有仓库访问权可自行 `git diff`。
2. 改动**未提交**，因此 `git diff HEAD` 是完整差异；但 `com/dsh`、`assets/dsh`、8 个 dsh 布局、
   2 个单测文件属**未跟踪（untracked）**，`git diff` **不会**显示它们 —— 它们是本次新增的成果，
   不是本次删改的对象。
3. 若要固化，建议先 `git add -A && git commit`（审查方看到的研究对象会更稳定）。

---

## 3. 技术栈与版本

### 3.1 构建与语言

| 项 | 版本 / 说明 |
|---|---|
| 语言 | **Kotlin 2.4.10**（主力）+ Java 17（fclcore/fcllibrary 大部分为 Java） |
| 构建系统 | Gradle **8.14.4**（wrapper）+ Android Gradle Plugin **8.13.2** |
| JDK | **17**（`/usr/lib/jvm/java-17-openjdk-arm64`） |
| compileSdk / targetSdk / minSdk | **35 / 34 / 26** |
| Android SDK | `/opt/android-sdk`（platforms;android-35、build-tools;35.0.0、platform-tools 37.0.1） |
| NDK | **已不再需要**（原来用 27.0.12077973，本次删除了 native 构建） |
| 构建主机 | **aarch64 + proot 沙箱**（非 x86_64，见 §5 的注意事项） |

### 3.2 保留的依赖（21 条）

```
com.android.tools:desugar_jdk_libs        2.1.5      脱糖（java.time / stream）
commons-io:commons-io                     2.15.1     fcllibrary 文件浏览器
org.apache.commons:commons-compress       1.26.0     RuntimeUtils 解 tar.xz
org.tukaani:xz                            1.9        同上（运行时需要，无 import）
com.google.code.gson:gson                 2.10.1     JSON（dsh 大量使用）
com.github.junrar:junrar                  7.5.5      CompressingUtils
org.glavo:chardet                         2.5.0      IOUtils 编码探测（包名是 org.glavo.chardet）
androidx.appcompat:appcompat              1.7.0
androidx.viewpager2:viewpager2            1.1.0      FCLMenuView/页面框架
androidx.core:core-splashscreen           1.0.1      SplashActivity
com.google.android.material:material      1.12.0     （M2 主题，注意用 SwitchMaterial 而非 MaterialSwitch）
androidx.constraintlayout                 2.2.0
androidx.core:core-ktx                    1.13.0
androidx.lifecycle:{runtime-ktx,viewmodel} 2.6.2
androidx.recyclerview:recyclerview        1.3.1
org.jetbrains.kotlinx:coroutines-android  1.9.0
com.github.bumptech.glide:glide           4.16.0     ImageUtil
androidx.datastore:datastore              1.2.1      ThemePreference
org.jetbrains.kotlinx:serialization-json  1.10.0     ThemePreference
+ project(":ZipFileSystem")
```

**本次移除的依赖 15 条**：`jelf`、`taptargetview`、`nanohttpd`、`opennbt`、`tomlj`、
`constant-pool-scanner`、`jsoup`、`touchcontroller`、`palette-ktx`、`gamepad-remapper`、
`segmented-button`、`room-runtime`、`room-ktx`、`room-compiler(ksp)`、`bytehook`，
外加 `project(":Terracotta")`；并移除 **ksp 插件**。

### 3.3 运行环境（App 侧）

| 项 | 值 |
|---|---|
| 目标设备 | **未 root 的 arm64 安卓手机**（Android 8.0+，minSdk 26） |
| 应用包名 | `com.tungsten.fcl`（debug 变体 `com.tungsten.fcl.debug`） |
| 应用内运行时 | **proot + Linux rootfs（Ubuntu/glibc 或 Alpine/musl，arm64）**，rootfs 内跑 Node.js 22/24 与 dsh |
| 模型 | DeepSeek 云端 API（`https://api.deepseek.com`），本机不做推理 |
| 无数据库 | 配置用 Gson 落 JSON 文件（`instances.json`）+ SharedPreferences + Android Keystore（API key） |

---

## 4. 运行 / 部署方式

| 项 | 值 |
|---|---|
| CI/CD | **无**（本项目无任何 CI 配置） |
| 容器化 | 无 |
| 本地构建 | `./gradlew --no-daemon -Darch=arm64 :FCL:assembleFordebug`（debug 签名用仓库内 `debug-key.jks`，密码内置 `FCL-Debug`） |
| 沙箱专用脚本 | `/workspace/run-compile.sh`（Kotlin+Java+资源+Manifest 编译，**不打包**）<br>`/workspace/run-tests.sh`（JVM 单测）<br>`/workspace/build-apk.sh`（一键出 APK）<br>`/workspace/dsh-launcher-poc/scripts/test-scripts-posix.sh`（脚本一致性） |
| 部署方式 | 手动 `adb install` 或侧载 APK |
| 沙箱构建的已知坑 | ①NDK 工具链只有 x86_64，arm64 需 qemu 转发（**本次删 native 后此坑已消失**）<br>②aapt2 只有 x86_64，需 qemu wrapper（仍存在）<br>③Gradle 内存需 `GRADLE_OPTS="-Xmx1300m -XX:MaxMetaspaceSize=450m"`<br>④`assembleFordebug` 约 7 分钟，可能超过单次命令超时，需增量续跑 |

**当前约定**：**改完代码默认不打包**，只在明确要求时出 APK。

---

## 5. 业务目标与核心流程

### 5.1 项目用途
把 **DeepSeek Harness（`dsh`，DeepSeek 开源的 agent harness）** 搬到**未 root 的安卓手机**上运行，
做成一个类似「启动器」的 App：用户装 APK 即可，无需 Termux、无需 root。

> 背景：`dsh` 是 Node.js 项目，其原生模块只发布 **linux glibc/musl** 版本（无 android/bionic），
> 因此**必须用 proot 跑一套标准 Linux 用户态**来承载 Node，不能直接用安卓的 bionic。
> 因此选择了已解决同类问题的 FCL（它用 proot 跑 JRE + Minecraft）作为技术底座。

### 5.2 与 FCL 的关系（本次变更的由来）
| 阶段 | 形态 | 问题 |
|---|---|---|
| 改造前 | FCL 是 Minecraft 启动器；dsh 作为**附加功能**藏在主界面「长按设置」里 | 想用 dsh，必须先下载约 1 GB 的 MC 运行时（JRE/LWJGL/Caciocavallo/JNA），穿过 MC 主界面才能摸到 dsh —— 顺序完全反了 |
| 本次变更后 | **纯 dsh 启动器**：保留 FCL 的 UI 风格与底层框架，删掉全部 MC 业务 | 启动即进 dsh 界面 |

### 5.3 用户核心路径（变更后）
```
启动 App（SplashActivity）
  └─ 直接进入 dsh 实例列表（DshInstancesActivity）        ← 无 MC 运行时门禁 / 无 EULA / 不再申请存储权限
       ├─ 「下载」→ 从 npm registry 拉 dsh 版本列表 → 选中安装（DshInstaller 在 proot 里跑 npm install）
       ├─ 「设置」→ 录入 DeepSeek API Key（Android Keystore 加密存储）+ 「测试连接」
       ├─ 「日志」→ 实时查看 proot/dsh 运行输出
       └─ 实例列表「启动」→ DshRuntime 起 `dsh web`（前台服务保活）
              └─ 捕获 `http://127.0.0.1:<port>/?token=...` → DshWebViewActivity 加载 dsh 完整 Web UI
                   └─ 用户在 WebView 里与 dsh 对话 / 让它写代码、操作文件
```

---

## 6. 已知问题与限制

### 6.1 本次变更前就存在的限制（非本次引入）
| 项 | 说明 |
|---|---|
| **真机端到端未验证** | 最关键的空白：需要准备 `libproot.so` + `libproot_loader.so`（走 jniLibs）与 `rootfs.tar.xz`（内置 Node）两个平台大文件，才能走通「首启解压 → 自检 → 装 dsh → 启动 → WebView」。**目前一次都没在真机上跑通过** |
| **本次也未打包 APK** | 按约定「改完不打包」，因此 dex 合并、APK 体积、安装行为均未验证 |
| `Process.pid()` 反射 | 在 ART 上可能取不到，已有告警日志；彻底解决需解析 `/proc/self/task/*/children` 或让 proot 回吐 `$$` |
| 前台服务保活 | 各厂商 ROM 行为不一，需真机验证 |
| Landlock 隔离弱 | proot + 多数安卓内核测不到可用 Landlock，dsh 的沙箱会退化；不可用于不可信任务 |
| `dsh` 上游不稳定 | 处于 developer preview，会做破坏性变更 |

### 6.2 本次变更引入 / 遗留的问题（**审查重点之一**）
| 项 | 说明 | 详见 |
|---|---|---|
| 疑似死代码簇 | `fclcore/task/{FileDownloadTask,FetchTask,GetTask,AsyncTaskExecutor,…}`、`fclcore/util/io/{CompressingUtils,Unzipper,Zipper,JarUtils,CSVTable,…}`、`fcl/util/{Constants,FXUtils,RequestCodes,ResourceNotFoundError,ShellUtil,WeakListenerHolder}` 均"外部引用数 0"，但保留了 | mc-removal.md §9.1 |
| 浏览器模块残留 | **`FCLActivity.onCreate` 仍会 `new FileBrowserLauncher(this)`**（所有 Activity 的基类，含 dsh 的）。原本调用它的 MC 代码已删，现无人调用；但该模块若被启用会依赖**已删除的存储权限** | 见 §8.E |
| 语言包被大幅删除 | 保留 `values`(英) + `values-zh`(简中)，删除了 de/fa/ja/pt-rBR/ru/tr/uk/vi/zh-rHK/zh-rTW。海外用户界面回落英文 | mc-removal.md §9.4 |
| 版本号与包名未改 | `versionName 1.3.3.3`、`applicationId com.tungsten.fcl`、`app_name "Fold Craft Launcher"`、`FCLApp` 类名等仍为 FCL 品牌 | mc-removal.md §9.2 |
| 启动页方向改动未验证 | `SplashActivity` 由 `sensorLandscape` 改为 `sensorPortrait`，但 `activity_splash.xml` 原为横屏设计 | mc-removal.md §9.4 |

### 6.3 明确「不能改动」的部分
- **`com/tungsten/fcllibrary/` 必须保留** —— 用户明确要求保留 FCL 的界面风格与底层 GUI 框架，
  这是本次变更的红线（删它就等于重写 UI）。
- **`com/dsh/` 不在本次审查范围** —— 其逻辑已经过四轮独立审计（见 `reports/round2~round5`）。
- 背景图 `res/drawable/background_{light,dark}.jpg` 保留（属"FCL 视觉风格"）。

---

## 7. 重点关注：本次修改是否影响其他方面 🔴

> 这是送审方最关心的问题。以下按「影响面」逐项列出**已由编译验证**与**未经运行验证**的部分，
> 并给出**具体的待核查点**。

### A. 对保留代码的影响
- ✅ **已验证**：`sh /workspace/run-compile.sh` 覆盖 **Kotlin 编译 + Java 编译 + 资源处理 + Manifest 合并**，
  结果 **BUILD SUCCESSFUL，0 error**；单测 **23/23**。
  这说明「保留的代码没有引用已删的类」「保留的布局没有引用已删的资源」。
- ⚠️ **待核查**：编译通过 ≠ 运行正确。裁剪过 3 个类的方法（`RuntimeUtils` 删 3 个方法、
  `AndroidUtil` 删 3 个函数、`DialogUtil` 删 1 个函数），**需确认这些方法确实无人调用**
  （判断依据：全仓库 grep 引用数为 0）+ 运行期无隐藏反射调用。
- ⚠️ **待核查**：`ChecksumMismatchException` 的**父类被改**（`ArtifactMalformedException` → `IOException`）。
  需确认没有 `catch (ArtifactMalformedException)` 依赖它（该父类已删，故不可能有；但需确认上游调用点语义未变）。

### B. 对 App 启动流程的影响
- **`SplashActivity` 被完全重写**（340 行 → 72 行）：删除 8 项 MC 运行时门禁、EULA、存储权限流程、
  整合包 intent 处理；改为直接 `startActivity(DshInstancesActivity)`。
  > **修正（见 mc-removal-impact-review.md §1.4 D-1）**：行数与实测不符。实测
  > `git show HEAD:...SplashActivity.kt | wc -l` = **286**，当前 = **64**；`git diff --numstat` = `+20 -242`。
  > 结论（大幅裁剪、成为启动单点）不变。
- ⚠️ **待核查 1**：这是**入口的唯一路径**，一旦有问题 App 直接不可用。
  > **⚠️ 修正（见 mc-removal-impact-review.md §3.2 子问题 1 / §6 M-01）—— 该判断不成立。**
  > 本页**不是唯一入口**：**通知栏 `PendingIntent` 冷启动会绕过它**
  > （`DshRuntimeService.kt:158-163` 构造 `Intent(context, DshInstancesActivity::class.java)` +
  > `PendingIntent.getActivity(..., FLAG_ACTIVITY_NEW_TASK)`，`DshInstancesActivity` 虽 `exported=false`，
  > 但被本 App 自身创建的 PendingIntent 唤起是允许的）→ 进程被杀后点通知，**冷启动直达实例列表页**。
  > 另外"进程回收后恢复"恢复的也是栈顶 Activity（通常是 `DshInstancesActivity` / `DshWebViewActivity`），
  > 同样不过启动页。**已落地补丁 A 修复**（`FCLPath.loadPaths` 上提到 `FCLApp.onCreate`）。
- ⚠️ **待核查 2**：**取消存储权限申请**是否安全？判断依据是「dsh 只用 `filesDir`/`cacheDir`」
  （见 `DshPaths`）。若将来需要访问 `/sdcard`（导入/导出配置、选择 rootfs 文件），需重新加回权限与引导。
  > **⚠️ 修正（见 mc-removal-impact-review.md §6 M-02 / M-04）—— 判断不完整。**
  > ①对 **dsh 主流程**成立（`DshPaths` 9 个路径经实测全在 `filesDir`/`cacheDir`，无 `/sdcard` 依赖）；
  > ②但**对 `FCLPath` 不成立**：`LOG_DIR` 原为 `/sdcard/FCL/log`，删权限后不可写，`fcl.log` 永远建不出来
  > （`Logging.start` 的 `catch(IOException)` 静默吞掉）；`CONTROLLER_DIR`/`SHARE_DIR`/`SHARED_COMMON_DIR`
  > 三次 `mkdirs` 同样静默失败。**已落地补丁 B**（`LOG_DIR` 改 `context.getDir("log",0)`）。
  > ③`fcllibrary/browser/` 7 个文件 + `FileBrowserActivity` 是死模块（零调用者），但它
  > **依赖的正是被删掉的存储权限**（`FileBrowser.java:47` 的 initDir = `/sdcard`；
  > `FileBrowserAdapter.java:147` 分享 `/sdcard` 文件）—— 属"表面可用、启用即碎"，见 M-04。
- ⚠️ **待核查 3**：`Theme.Splash` 样式与 `activity_splash.xml` 保留，但方向改为竖屏，**视觉效果未验证**。

### C. 对 AndroidManifest / 权限的影响
- 删除 9 个权限、6 个 Activity、2 个 Service、1 个 Provider、1 个 activity-alias；保留 7 个权限与 10 个组件。
  > **修正（见 mc-removal-impact-review.md §1.4 D-6）**：组件数少计 1。实测**保留 11 个组件**
  > = 8 Activity（`SplashActivity` + `com.dsh.ui.{DshInstances,DshDownload,DshLogs,DshSettings,DshWebView}Activity`
  > + `fcllibrary.browser.FileBrowserActivity` + `fcllibrary.crash.CrashReportActivity`）
  > + 1 Service（`DshRuntimeService`）+ 2 Provider（`androidx.core.content.FileProvider`、
  > `fcllibrary.crash.CrashReporterInitProvider`）。权限 7 个 ✅ 与实测一致。
  > 11 个组件已逐个核对**类文件均存在**，`activity-alias` 计数实测 **0**（已删组件字样 0 命中）。
- **核验后的三条具体影响**（均已逐一追到调用点，非推测）：

  **C-1 🔴 `FCLPath.loadPaths()` 在基类中不再执行**
  `com/tungsten/fcllibrary/component/FCLActivity.java`（**所有 Activity 的基类**）：
  ```java
  if (hasPermission) { FCLPath.loadPaths(this); }   // ← 删权限后恒为 false
  fileLauncher = new FileBrowserLauncher(this);     // ← 仍执行（见 C-2）
  ```
  - **不会崩溃**：未声明权限时 `checkSelfPermission` 返回 DENIED、`isExternalStorageManager()` 返回 false。
  - **后果**：`FCLPath` 的静态字段（`NATIVE_LIB_DIR` / `LT_BACKGROUND_PATH` …）在基类路径下**不再被初始化**。
  - **当前为何仍可用**：`SplashActivity.init()` 里**显式**调用了 `FCLPath.loadPaths(this)`（唯一入口，先于所有 dsh Activity）。
  - **dsh 侧已做防护**：`DshPaths.resolveProotBin/Loader` 的形参是 `String?` 且 `isNullOrEmpty()` 判空
    （源码注释记明"原来会 NPE"，属前几轮已修项）。
  - **建议**：把 `FCLPath.loadPaths()` 上提到 `FCLApp.onCreate`（与 `DshPaths.loadPaths` 并列），
    消除"必须先经过 Splash"的隐式依赖。**这是本次审查最值得采纳的一条。**
    > **✅ 已采纳并落地（2026-10-01，补丁 A，见 mc-removal-impact-review.md §3 / §11）**
    > ①`FCLApp.onCreate` 新增 `com.tungsten.fclauncher.utils.FCLPath.loadPaths(this)`，
    > 排在 `DshPaths.loadPaths(this)` **之前**；②**删除** `SplashActivity.kt` 中那一行（去重）。
    > 落地后全仓库 `FCLPath.loadPaths` 的**有效调用点唯一** = `FCLApp.java:41`
    > （`FCLActivity.java:54` 那段仍恒不可达且无害，保留不动以免扩大 diff）。
    > 验证：clean build `34/34 executed, 0 error`、单测 `23/23`。
    >
    > **补充（本节原文低估的两点）**：
    > - **"当前为何仍可用"的解释不完整**：这里说"因为 `SplashActivity.init()` 显式调了一次（唯一入口）"，
    >   但**通知 PendingIntent 冷启动会绕过它**（`DshRuntimeService.kt:158-163`），此时 `FCLPath` 为 null。
    >   之所以没崩，**仅因为** dsh 侧恰好有 null 防护（`DshPaths.resolveProotBin/Loader` 的 `isNullOrEmpty()`
    >   → 静默回退 `assets/dsh/proot/`）。**这是巧合兜底，不是设计**，不应作为"安全"的依据。
    > - **上提到 `FCLApp.onCreate` 是否够早**：够。本 App 唯一的 ContentProvider
    >   （`CrashReporterInitProvider`，`initOrder="101"`）虽然在 `Application.onCreate` **之前**执行，
    >   但其 `onCreate` 只调用 `CrashReporter.install()`（`Thread.setDefaultUncaughtExceptionHandler`），
    >   **不读 `FCLPath`、不读 `DshPaths`**（实测）。WorkManager/JobScheduler **不存在**（grep 0 命中）；
    >   通知通道由 `DshRuntimeService.createChannel` 在**服务启动时**惰性创建，必然更晚。

  **C-2 ⚠️ 浏览器模块残留（死代码，无运行风险，但有维护成本）**
  `FCLActivity.onCreate` 仍无条件 `new FileBrowserLauncher(this)`；但全仓库**已无任何代码调用**
  `fileLauncher` 或 `requestPermissions` 的文件选择功能（原本调用它的 MC 代码已删）。
  浏览器模块 `fcllibrary/browser/`（7 个文件）+ `FileBrowserActivity` 注册因此成为死重。
  → **建议**：删除该模块与 Manifest 中的注册（**注意**：删 `fileLauncher` 字段需同步改 `FCLActivity`）。

  **C-3 ✅ `ThemeEngine.applyBackground` 的 `FCLPath` 空指针风险：不成立（已核验为死代码）**
  `ThemeEngine` 第 174~182 行用 `FCLPath.LT_BACKGROUND_PATH`（未初始化时为 null）并传给
  `ImageUtil.load(path: String)`（**非空形参**，传 null 会抛异常），且该调用**不在 `runCatching` 内**。
  但追查调用链后确认：`applyBackground` 只被 `ThemeEngine.applyAndSave(context, view, lt, dk)` 调用，
  而**该重载全仓库无调用者** → 死代码 → **运行期不会触发**。
  → 无需修改；若将来恢复"自定义背景"功能，需先保证 `FCLPath` 已初始化。

  **C-4 ✅ 保留的 `POST_NOTIFICATIONS` 权限仍被正确使用**
  `DshInstancesActivity.ensureNotificationPermission()` → `FCLActivity.requestPermissions(arrayOf(POST_NOTIFICATIONS), …)`；
  该权限**在 Manifest 中保留**，路径正常。

- ⚠️ **待核查（留给审查方）**：保留的 `fcllibrary.crash.CrashReportActivity` / `CrashReporterInitProvider`
  是否仍需？其上报目标/表单可能已随 MC 代码删除而失效。

### D. 对资源的影响
- 删除 282 个资源文件（173 布局 / 95 drawable / 4 anim / 10 语言包 / 2473 条字符串）。
- ✅ **已验证**：白名单自洽性检查（保留布局引用的 `@layout/@drawable/@string/@anim` 是否都存在）→ **缺失 0**。
- ⚠️ **待核查**：**动态资源引用**（`getIdentifier`）。全仓库只有 1 处
  （`LocaleUtils` 取 `world_time`），已加入白名单保留。需确认没有其他形式的动态引用
  （如字符串拼接资源名、`Resources.getIdentifier` 的其他写法）。
  > **修正（见 mc-removal-impact-review.md §1.4 D-3）**：实测**全仓库 3 处，不是 1 处**：
  > ①`fcllibrary/util/LocaleUtils.java:130` 取 `"world_time"`（**活的**，白名单已保留，
  > 且缺失时有硬编码 fallback `"EEE, MMM d, yyyy HH:mm:ss"` → 安全失败，不崩）
  > ②`mio/util/AndroidUtil.kt:128` `getLocalizedText()`（**零调用者**，死代码）
  > ③`mio/util/AndroidUtil.kt:138` `hasStringId()`（**零调用者**，死代码）。
  > 并核验：无 `public.xml`、无字符串拼资源名的其他写法、**无第三方/插件按名引用资源的通道**
  > （无插件框架残留、无 `<meta-data>` 声明、无 `Class.forName` 加载外部 APK 资源）。
  > 因此**不存在**"编译期不报错、运行期 `Resources.NotFoundException`"的路径。
- ⚠️ **待核查**：`res/values-v31/` 只含少数条目，需确认其引用完整（编译通过但不代表运行期 OK）。

### E. 对构建配置的影响
- 删除 `externalNativeBuild` / `ndkVersion` / `prefab` / `bytehook` / `jreAssets` 任务 / LWJGL 裁剪逻辑 / 15 条依赖 / ksp 插件。
- ⚠️ **待核查 1**：`splits { abi }` 保留了，但**已无 native 库** → `-Darch=arm64` 会产生
  `FCL-fordebug-1.3.3.3-arm64-v8a.apk` 这样的文件名后缀，**分流已无实际意义**（无害，但可清理）。
- ⚠️ **待核查 2**：`implementation(fileTree("libs", "*.jar", "*.aar"))` 的 `libs/` **目录已空**，
  这条依赖现为空操作（无害，但可删）。
- ⚠️ **待核查 3**：`gradle/libs.versions.toml` 中**被移除依赖的 version 条目未清理**（无害，但冗余）。
- ⚠️ **待核查 4**：删除 ksp 插件后，若仓库中仍有任何 Room 注解代码会编译失败（当前已无，编译验证通过）。

### F. 对 dsh 功能的影响
- ✅ **已验证**：dsh 依赖的 6 个 FCL 类全部保留且编译通过：
  `R`/`databinding`、`FCLActivity`、`RuntimeUtils`（`uncompressTarXZ`/`isLatest`/`install` 保留）、
  `FCLPath`、`JsonUtils`、`HttpRequest`。
- ⚠️ **待核查**：`DshBootstrap` 首启解压路径依赖 `RuntimeUtils.uncompressTarXZ`（符号链接处理）与
  `assets/dsh/**` —— 该 assets 目录完整保留。但**真机行为未验证**。

### G. 对「未提交成果」的风险
- 🔴 `com/dsh/`（259 KB）、`assets/dsh/`（66 KB）、8 个 dsh 布局、`test/java/com/dsh/`（24 KB）
  均为 **untracked**。
- **风险**：对**已跟踪文件**执行 `git checkout --` 会恢复上游版本（**本次已因此丢过一次数据**：
  124 条 dsh 文案被回退，后从构建中间产物中找回）；
  而 untracked 文件一旦删除**无法用 git 恢复**。
- **建议**：审查前先 `git add -A && git commit`，或至少备份上述目录。

### H. 变更对「其他文档」的影响
- ⚠️ **`docs/` 下多份文档已过时**：`PLAN.md`、`PACKAGING.md`、`design/ui-manifest.md`、
  `design/app-shell.md`、`reports/round2~round5` 均按「FCL + dsh」形态描述，未反映本次删除。
  仅 `INDEX.md`「当前状态」章节与 `reports/mc-removal.md` 已更新。
  **审查时请以此为准，不要被旧文档误导。**

---

## 8. 补充材料

### 8.1 同仓库相关文档（`/workspace/docs/`）
| 文件 | 相关性 |
|---|---|
| `reports/mc-removal.md` | ★ **主审查对象**：变更详情 |
| `INDEX.md` | 文档总览；「当前状态」章节已更新为变更后状态 |
| `PLAN.md` | 项目总纲（**部分过时**）：架构、目录、安全设计、真机联调步骤 §8.5 |
| `ROADMAP.md` | 里程碑 M0~M6（**部分过时**） |
| `PACKAGING.md` | 两个平台大文件（proot 二进制 / rootfs）的准备说明 + 出 APK 的 qemu 方案 |
| `design/app-shell.md` | 外壳改造分阶段计划（阶段 0 已完成；1~3 待做） |
| `design/proot-chain.md` | proot 启动链设计（rootfs 选型 + 脚本 + 实测证据） |
| `design/multi-version.md` | 多版本/实例管理 + npm 下载 UI 设计 |
| `design/ui-manifest.md` | dsh 页面的初版 UI 设计（**部分过时**） |
| `reports/round2/3/4/5-*.md` | dsh 逻辑的前四轮审计报告（**本次范围之外**） |

### 8.2 验证记录（本次实际执行，可复现）
| 检查 | 命令 | 结果 |
|---|---|---|
| Kotlin + Java + 资源 + Manifest | `sh /workspace/run-compile.sh` | **BUILD SUCCESSFUL，0 error** |
| JVM 单元测试 | `sh /workspace/run-tests.sh` | **TOTAL=23 FAILED=0** |
| 脚本 POSIX 一致性 | `sh /workspace/dsh-launcher-poc/scripts/test-scripts-posix.sh` | **18/18** |
| 资源白名单自洽性 | 见 mc-removal.md §10.3 | **缺失 0** |
| 引用残留检查 | 见 mc-removal.md §10.4 | 仅 4 处**注释**提及（无代码引用） |

### 8.3 不存在的材料（如实说明）
- **无 issue / PR**：本项目未走上游贡献流程。
- **无监控数据 / 日志**：未做真机运行，因此无运行时日志、无崩溃报告、无性能数据。
- **无迁移脚本**：本次是删除，不涉及数据迁移（App 私有目录内无历史数据需要迁移）。
- **无 APK 产物**：按约定未打包；`/workspace/dsh-fcl-arm64.apk` 是**变更前**的旧包（189 MB，含 MC），
  **不代表当前代码状态**，请勿据此评估体积。

---

## 9. 期望审查方回答的问题

请审查方针对以下问题给出结论与依据：

1. **删除边界是否正确**？§7「依赖临界点」中判为"保留"的项（尤其 `fclcore/fakefx` 292 文件、
   `ZipFileSystem` 模块、`fclauncher/utils/Architecture.java`），是否确有保留必要？
2. **是否有过度删除**？特别关注：存储权限移除、语言包删除、`RuntimeUtils`/`AndroidUtil`/`DialogUtil` 的方法裁剪。
3. **是否有遗漏删除**？§9.1 列出的疑似死代码簇是否应一并删除？浏览器模块是否应删？
4. **重写后的 `SplashActivity` 是否安全**？取消权限与 EULA 是否可接受？
5. **Manifest 与构建配置的清理是否完整、有无副作用**？
6. **是否存在只在运行期才会暴露的风险**（编译无法覆盖的）？请给出具体风险点与验证方法。

---

**（本文结束。主变更详情见 `reports/mc-removal.md`）**
