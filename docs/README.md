# 文档总览

> ## 📦 项目
> **仓库**：<https://github.com/sqkl520/dsh-fcl-android-launcher>
> **下载**：<https://github.com/sqkl520/dsh-fcl-android-launcher/releases>
>
> 本项目**基于 [Fold Craft Launcher](https://github.com/FCL-Team/FoldCraftLauncher)（GPL-3.0）改造**，
> 同样以 **GPL-3.0** 发布。
>
> **当前版本：`0.1.3-SNAPSHOT`** —— 待"能正常启动 / 下载 / 管理 dsh"后才标 `1.0.0`。

> **DeepSeek Harness（dsh）在未 root 安卓手机上的启动器**。
> 用 proot 兜一个 Linux 环境跑 Node 版 dsh，界面沿用 FCL 的视觉与控件体系。

---

## 文档地图

| 文件 | 内容 | 什么时候看 |
|---|---|---|
| `PLAN.md` | **总体方案**：架构、目录布局、关键流程、安全设计 | 想了解全貌 |
| `ROADMAP.md` | **路线规划**：M1~M6 里程碑（真机点亮 → 稳定 → 体验 → 工程化 → 分发 → 扩展） | 想知道"接下来往哪走" |
| `TASKS.md` | **待办清单**：已明确要做但未做的 | 想知道"还有哪些没做" |
| `PACKAGING.md` | **打包说明**：运行时底座（proot / rootfs）怎么准备，怎么出 APK | 要自己打包时 |
| `ROOTFS.md` | **rootfs 构建与瘦身**：Debian + Node + 预装 dsh 的完整步骤，含可复现的瘦身命令 | 要重做/精简运行时环境时 |
| `ENVIRONMENT.md` | **开发环境**：JDK / Android SDK / NDK 要求与接线 | 准备编译环境时 |
| `design/app-shell.md` | **应用外壳设计**：从 MC 启动器改为 dsh 启动器；**§2.5 = 界面/按钮/主题一律跟 FCL 走** | 要改 UI 前必读 |
| `design/wx-exec-proot-loader.md` | **W^X 限制与 PROOT_LOADER 绕过**：targetSdk 34 下怎么执行 rootfs 里的程序 | 想知道"为什么不用降 targetSdk" |
| `design/multi-version.md` | **多版本 / 多实例管理**设计 | 关心实例与版本下载 |
| `design/proot-chain.md` | 可复现的 proot 启动链 | 关心"怎么在安卓上跑起 Linux" |
| `design/proot-engine-integration.md` | **【未采用的备选路线】** 另一套 proot 集成方案，留作决策记录（其中 W^X 原理仍有效） | 想了解技术选型的取舍 |
| `design/ui-manifest.md` | UI 拼装 + Manifest 注册 + 分层验证方法 | 关心界面与组件注册 |
| `design/fcl-ui-restoration.md` | **FCL 原汁原味 UI 还原方案**：结构映射、保留/删除/替换清单、验收标准 | 做 UI 还原前 |
| `design/setup-flow-and-task-area.md` | 首启前置页 + 首页任务区设计 | 做这两个功能前 |

---

## 当前状态

- **代码**：`com/dsh/` **42 个文件**（`core` 逻辑 + `ui` 界面）+ FCL 基座
  - `com/tungsten/fcllibrary/` 60 个（UI 框架，保留 FCL 风格）
  - `com/tungsten/fclcore/` 394 个（`fakefx` / `util` / `task` / `event`）
  - `com/tungsten/fcl/` 9 个（`FCLApp` / `SplashActivity` / `RuntimeUtils` 等）
  - `com/tungsten/fclauncher/utils/` 2、`com/mio/util/` 4、`ZipFileSystem/` 12
- **资源**：`FCL/src/main/res` 109 个文件 · assets 仅 `dsh/`（3 个 POSIX sh + version 标记；
  `rootfs.tar.xz` 不入 Git）· jniLibs 3 个（`libproot.so` / `libproot-loader.so` / `libbusybox.so`）
- **Gradle 模块**：`:FCL` + `:ZipFileSystem`
- **验证**：编译（Kotlin+Java+资源+Manifest）**BUILD SUCCESSFUL**；单测 **53/53**；脚本自检 **18/18**
- **入口**：`SplashActivity` → **`DshMainActivity`**（外壳五页：实例 / 管理 / 下载 / 日志 / 设置；
  无 MC 运行时门禁、无 EULA、无 MC 主界面）

### 运行时底座

**自研 proot 层 + 预打包 rootfs**（Debian 12 + Node 22 + 预装 dsh）。
**targetSdk 保持 34**，用 **PROOT_LOADER** 绕过 Android 10+ 的 W^X 执行限制
（原理见 `design/wx-exec-proot-loader.md`）。

rootfs 已于 2026-10-06 瘦身：`rootfs.tar.xz` **314 MB → 142 MB（−52.7%）**，
解压后 1436 MB → 862 MB；APK 相应从 311 MiB 降到 153 MiB（步骤见 `ROOTFS.md` §10）。

---

## 代码在哪

| 位置 | 说明 |
|---|---|
| `FCL/src/main/java/com/dsh/` | **dsh 启动器主体**（`core` 逻辑 + `ui` 界面），42 个文件 |
| `FCL/src/main/java/com/tungsten/` | FCL 基座（`fcllibrary` UI 框架 + `fclcore` 工具） |
| `FCL/src/main/assets/dsh/` | 运行时底座：`scripts/`（3 个 POSIX sh）+ `rootfs/`（tar.xz 不入 Git） |
| `FCL/src/test/java/com/dsh/` | JVM 单测 |
| `docs/` | 本目录 |

---

## 自己编译

工具链要求与接线见 **`ENVIRONMENT.md`**。最短路径：

```powershell
# 编译校验（Kotlin + Java + 资源 + Manifest）
.\gradlew.bat --no-daemon -Darch=arm64 :FCL:compileDebugKotlin :FCL:compileDebugJavaWithJavac `
  :FCL:processDebugResources :FCL:processDebugMainManifest

# 单测
.\gradlew.bat --no-daemon :FCL:testFordebugUnitTest

# 打包（需先备好 rootfs，见 ROOTFS.md）
.\gradlew.bat --no-daemon -Darch=arm64 :FCL:assembleFordebug
```

打包前必须备好 `FCL/src/main/assets/dsh/rootfs/rootfs.tar.xz`（142 MB，不入 Git）。
最省事的办法是**从已发布的 APK 里无损取出**（APK 内是 STORED，取出来逐字节相同）：

```sh
unzip -p dsh-fcl-android-launcher-<版本>-arm64.apk assets/dsh/rootfs/rootfs.tar.xz \
  > FCL/src/main/assets/dsh/rootfs/rootfs.tar.xz
```

从零重建 rootfs 见 `ROOTFS.md`。
