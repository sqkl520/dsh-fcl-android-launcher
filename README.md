<div align="center">

# DSHarness-FCL-Launcher

**DeepSeek Harness FCL Android Launcher**

*dshAndroidLauncher (FCL-based)*

在**未 root** 的安卓手机上运行 [DeepSeek Harness (dsh)](https://github.com/deepseek-ai) —— 基于 [Fold Craft Launcher](https://github.com/FCL-Team/FoldCraftLauncher) 改造的启动器

![Version](https://img.shields.io/badge/version-0.1.3--SNAPSHOT-orange?style=flat-square)
![Platform](https://img.shields.io/badge/platform-Android%20arm64-blue?style=flat-square)
![License](https://img.shields.io/badge/license-GPL--3.0-green?style=flat-square)

</div>

---

## 这是什么

一个安卓 App，让你**不用 root** 就能在手机上跑 dsh（DeepSeek Harness —— 对话 / 编码 agent）。
界面沿用 FCL 的视觉风格与 GUI 框架（fcllibrary + ThemeEngine），底层用 **proot** 兜一个 Linux 环境来跑 Node.js 版 dsh。

> **项目状态：0.1.3-SNAPSHOT（早期开发中）**
> 外壳（界面骨架 / 五页 / 横屏右面板）已完成；运行时底座（proot + rootfs + PROOT_LOADER）已集成，
> 0.1.1 起能出完整 APK（含 rootfs）；**端到端尚未在真机跑通**（真机验证进行中）。
> 版本号规则：待"能正常启动 / 下载 / 管理 dsh"后才标 `1.0.0`。

---

## 怎么工作的

```
Android App (本仓库)
   └─ proot（无 root 的 Linux 环境模拟，基于 ptrace 系统调用翻译）
        └─ Linux rootfs（Debian 12 bookworm arm64，内置 Node 22 + 预装 dsh）
             └─ Node.js
                  └─ dsh（DeepSeek Harness）
                       └─ 通过 HTTPS 调 DeepSeek 云端 API
```

### 关键设计：W^X 绕过（PROOT_LOADER）

Android 10+ 对 `targetSdk ≥ 29` 的 App 启用 **W^X**：禁止 `execve` 应用数据目录（`filesDir`）里的文件。
而 proot 需要在数据目录里执行 rootfs 的 `/bin/sh`、`node`。

**解法**：proot 执行 rootfs 程序时，走的是 proot 自带的 **loader**（`PROOT_LOADER` 机制）。
把这个 loader 放进 APK 的 `jniLibs`（安装后位于 `nativeLibraryDir`，SELinux 允许执行），
execve 的就是 loader 而非数据目录文件 —— 从而绕过 W^X，**无需把 targetSdk 降到 28**。

> 该机制经同类项目 [`oonid/pr`](https://github.com/oonid/pr) 在真机（Android 16 / SDK 36）验证通过。
> 详见 [`docs/design/wx-exec-proot-loader.md`](docs/design/wx-exec-proot-loader.md)。

---

## 当前进度

| 模块 | 状态 |
|---|---|
| FCL 改造：移除全部 Minecraft 代码与资源 | ✅ 完成（约 −226k 行，APK 337M → 15M） |
| 应用外壳：左侧菜单 + ViewPager2 内容区 + 右侧面板 + 动态岛（横屏） | ✅ 完成 |
| 界面：实例 / 管理 / 下载 / 日志 / 设置 五页 | ✅ 完成 |
| dsh 版本管理：npm registry 版本列表 + 安装 | ✅ 完成（逻辑） |
| 运行时底座：proot + rootfs + PROOT_LOADER 集成 | ✅ 已集成（0.1.1 起 APK 含完整 rootfs；待真机验证） |
| 真机端到端验证 | ⬜ 待做 |

---

## 构建

**环境要求**：JDK 17、Android SDK 35、NDK 27（仅集成 proot 引擎时需要）；工具链接线见 [`docs/ENVIRONMENT.md`](docs/ENVIRONMENT.md) §1。

```powershell
# 电脑（Windows，当前主力环境）直接用 Gradle：
.\gradlew.bat --no-daemon -Darch=arm64 :FCL:compileDebugKotlin :FCL:compileDebugJavaWithJavac `
  :FCL:processDebugResources :FCL:processDebugMainManifest    # = run-compile.sh 覆盖的四项
.\gradlew.bat --no-daemon :FCL:testFordebugUnitTest           # 单测（34 项）
.\gradlew.bat --no-daemon -Darch=arm64 :FCL:assembleFordebug  # 打包 → dsh-fcl-android-launcher-0.1.3-SNAPSHOT-arm64.apk
```

一条命令出整版（体检 → 编译 → 单测 → 打包 → 提交两仓 → 发 GitHub Release）：

```powershell
.\release.cmd --dry-run   # 先看流程；去掉 --dry-run 就是真跑
```

手机（arm64 Linux 沙箱）上用同目录的 `run-compile.sh` / `run-tests.sh` / `build-apk.sh`
（纯 POSIX sh，自动识别源码目录）。

**版本号只有一个真源**：`gradle.properties` 的 `dshVersion` / `dshVersionCode`。
**APK 不进 Git**：新版本作为 GitHub Release 附件分发（见 [Releases](https://github.com/sqkl520/dsh-fcl-android-launcher/releases)）。

---

## 文档

> 完整文档索引见 [`docs/INDEX.md`](docs/INDEX.md)

| 文档 | 内容 |
|---|---|
| [`docs/CHANGELOG.md`](docs/CHANGELOG.md) | 变更日志（按阶段/里程碑记录） |
| [`docs/PLAN.md`](docs/PLAN.md) | 项目总纲与总体方案 |
| [`docs/ROADMAP.md`](docs/ROADMAP.md) | 里程碑规划 M1~M6 |
| [`docs/PACKAGING.md`](docs/PACKAGING.md) | 打包说明（proot 二进制 / rootfs 准备） |
| [`docs/design/app-shell.md`](docs/design/app-shell.md) | 应用外壳改造设计（含界面跟 FCL 的硬性规范） |
| [`docs/design/wx-exec-proot-loader.md`](docs/design/wx-exec-proot-loader.md) | W^X 限制与 PROOT_LOADER 绕过方案 |
| [`docs/design/proot-engine-integration.md`](docs/design/proot-engine-integration.md) | **【未采用的备选路线】** oonid/pr `:proot-engine` 的集成方案 —— 最终没走这条路（用自研 proot 层），留作决策记录 |
| [`docs/reports/`](docs/reports/) | 各轮评审与优化报告 |
| [`docs/INDEX.md`](docs/INDEX.md) | **文档总览**：全部文档的索引与当前状态 |
| [Releases](https://github.com/sqkl520/dsh-fcl-android-launcher/releases) | **APK 下载**（公开仓，免 token）——新版本以 Release 附件分发，不进 Git |

---

## 许可

本项目基于 [Fold Craft Launcher](https://github.com/FCL-Team/FoldCraftLauncher)（**GPL-3.0**）改造，
因此同样以 **GPL-3.0** 发布。详见 [`LICENSE`](LICENSE)。

打包进 APK 的 `libproot.so` / `libproot-loader.so` / `libbusybox.so` 与 `ptyjni` 桥接来自
[`oonid/pr`](https://github.com/oonid/pr)（`:proot-engine` 与 `pr-cli` 为 MIT，patched proot 本体为
GPL-2.0-or-later）。本项目已是 GPL 系，不构成新障碍。
> 注：`:proot-engine` 作为 **Gradle 模块**的集成方案**最终没有采用**（见
> `docs/design/proot-engine-integration.md`）；这里指的是**二进制与 PTY 桥接的出处**。

## 致谢

- [Fold Craft Launcher](https://github.com/FCL-Team/FoldCraftLauncher) —— 界面框架与改造起点
- [oonid/pr](https://github.com/oonid/pr) —— targetSdk 35+ 下的 proot 适配方案（PROOT_LOADER / seccomp / CLONE 剥离）
- [proot](https://github.com/proot-me/proot) / [Termux](https://github.com/termux) —— 无 root 的 Linux 环境
