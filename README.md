<div align="center">

# DSHarness-FCL-Launcher

**DeepSeek Harness FCL Android Launcher**

在**未 root** 的安卓手机上运行 [DeepSeek Harness](https://github.com/deepseek-ai/deepseek-harness) —— 基于 [Fold Craft Launcher](https://github.com/FCL-Team/FoldCraftLauncher) 改造的 dsh 启动器

![Version](https://img.shields.io/badge/version-0.1.6--SNAPSHOT-orange?style=flat-square)
![Platform](https://img.shields.io/badge/platform-Android%20arm64-blue?style=flat-square)
![License](https://img.shields.io/badge/license-GPL--3.0-green?style=flat-square)

</div>

> ⚠️ **这是一个用 AI vibe coding 做的个人项目（早期开发中），有任何问题欢迎提 Issues，如果喜欢就点个 Star 吧！**

## 功能特性

- **多实例管理** —— 多个 dsh 实例独立安装 / 启动 / 停止 / 删除，互不干扰
- **版本管理** —— 从 npm registry 拉取版本列表，一键安装指定版本的 dsh
- **一键启动** —— 自动捕获 token URL，应用内直接打开 dsh 界面
- **任务区** —— 安装 / 启动 / 停止的进行态与终态（完成 / 失败 / 已取消）都看得见
- **按实例分流的日志** —— 每个实例独立日志 + 应用全局日志，均落盘可查
- **三页外壳** —— 实例 / 版本 / 设置，FCL 原版视觉风格，亮暗主题跟随
- **中英繁三语** —— 简体中文 / 繁體中文 / English 可切换
- **无 root 运行** —— proot 兜住 Linux 环境，targetSdk 34 不降级
- **外部浏览器逃生** —— 设备 WebView 过旧时，一键用系统浏览器（Edge / Chrome）打开 dsh

## 安装

1. 去 [Releases](https://github.com/sqkl520/dsh-fcl-android-launcher/releases) 下载最新版本的 APK（`dsh-fcl-android-launcher-*-arm64.apk`；文件名带 `-fixN` 的是当前验证包，装它）
2. 在 arm64 安卓手机上安装（未 root 也行）；首次安装请预留约 **1.4GB** 空闲空间
3. 首次启动等待运行环境解压完成（几分钟，期间保持应用在前台）
4. 去「版本」页安装一个 dsh → 回「实例」页启动 → 在 dsh 界面里填 API Key，开聊
5. 老设备若提示 WebView 过旧：点「用外部浏览器打开」，或去应用商店升级 Android System WebView

## 怎么工作的

```
Android App (本仓库)
   └─ proot（无 root 的 Linux 环境模拟，基于 ptrace 系统调用翻译）
        └─ Linux rootfs（Debian 12 bookworm arm64，内置 Node 22 + 预装 dsh）
             └─ Node.js
                  └─ dsh（DeepSeek Harness）
                       └─ 通过 HTTPS 调 DeepSeek 云端 API
```

界面沿用 FCL 的视觉风格与 GUI 框架（fcllibrary + ThemeEngine）。

### 关键设计：W^X 绕过（PROOT_LOADER）

Android 10+ 对 `targetSdk ≥ 29` 的 App 启用 **W^X**：禁止 `execve` 应用数据目录里的文件，
而 proot 需要在数据目录里执行 rootfs 的 `/bin/sh`、`node`。

**解法**：proot 执行 rootfs 程序时走自带的 **loader**（`PROOT_LOADER` 机制），
把 loader 放进 APK 的 `jniLibs`（安装后位于 `nativeLibraryDir`，SELinux 允许执行），
从而**无需把 targetSdk 降到 28**。详见 [`docs/design/wx-exec-proot-loader.md`](docs/design/wx-exec-proot-loader.md)。

> 该机制经同类项目 [`oonid/pr`](https://github.com/oonid/pr) 在真机（Android 16 / SDK 36）验证通过。

---

## 当前进度

| 模块 | 状态 |
|---|---|
| FCL 改造：移除全部 Minecraft 代码与资源 | ✅ 完成（约 −226k 行） |
| 应用外壳：三页（实例 / 版本 / 设置）+ 实例详情 + 右面板 + 动态岛 | ✅ 完成 |
| dsh 版本管理：npm registry 版本列表 + 安装 | ✅ 完成 |
| 运行时底座：proot + rootfs + PROOT_LOADER 集成 | ✅ 已集成 |
| 真机端到端：装 → 启 → 停 → 再启 | ✅ 已点亮（0.1.6；WebView 对话受设备内核版本影响，过旧设备用外部浏览器） |
| 第三轮反馈修复（fix1） | ✅ 已发（历史验证包） |
| 第四轮反馈 + 全量审计修复（fix2） | ✅ 已发预发布验证中 |
| `1.0.0`（能正常启动 / 下载 / 管理 dsh） | ⬜ 待 0.1.6 转正 |

---

## 构建

**环境要求**：JDK 17、Android SDK 35、NDK 27；工具链接线见 [`docs/ENVIRONMENT.md`](docs/ENVIRONMENT.md) §1。

```powershell
# 电脑（Windows，当前主力环境）直接用 Gradle：
.\gradlew.bat --no-daemon -Darch=arm64 :FCL:compileDebugKotlin :FCL:compileDebugJavaWithJavac `
  :FCL:processDebugResources :FCL:processDebugMainManifest
.\gradlew.bat --no-daemon :FCL:testFordebugUnitTest           # 单测
.\gradlew.bat --no-daemon -Darch=arm64 :FCL:assembleFordebug  # 打包 → dsh-fcl-android-launcher-0.1.6-SNAPSHOT-arm64.apk
```

手机（arm64 Linux 沙箱）上用同目录的 `run-compile.sh` / `run-tests.sh` / `build-apk.sh`
（纯 POSIX sh，自动识别源码目录）；一条命令出整版用 `sh release.sh`。

**版本号只有一个真源**：`gradle.properties` 的 `dshVersion` / `dshVersionCode`。
**APK 不进 Git**：新版本作为 GitHub Release 附件分发（见 [Releases](https://github.com/sqkl520/dsh-fcl-android-launcher/releases)）。

---

## 文档

> 完整文档索引见 [`docs/README.md`](docs/README.md)

| 文档 | 内容 |
|---|---|
| [`docs/README.md`](docs/README.md) | **文档总览**：全部文档的索引与当前状态 |
| [`docs/PLAN.md`](docs/PLAN.md) | 项目总纲与总体方案 |
| [`docs/ROADMAP.md`](docs/ROADMAP.md) | 里程碑规划 M1~M6 |
| [`docs/PACKAGING.md`](docs/PACKAGING.md) | 打包说明（proot 二进制 / rootfs 准备） |
| [`docs/ROOTFS.md`](docs/ROOTFS.md) | rootfs 构建与瘦身（Debian + Node + 预装 dsh 的完整步骤） |
| [`docs/ENVIRONMENT.md`](docs/ENVIRONMENT.md) | 开发环境：JDK / Android SDK / NDK 要求与接线 |
| [`docs/TASKS.md`](docs/TASKS.md) | 待办清单 |
| [`docs/design/app-shell.md`](docs/design/app-shell.md) | 应用外壳改造设计（含界面跟 FCL 的硬性规范） |
| [`docs/design/wx-exec-proot-loader.md`](docs/design/wx-exec-proot-loader.md) | W^X 限制与 PROOT_LOADER 绕过方案 |
| [`docs/design/proot-engine-integration.md`](docs/design/proot-engine-integration.md) | **【未采用的备选路线】** oonid/pr `:proot-engine` 的集成方案 —— 最终没走这条路（用自研 proot 层），留作决策记录 |
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
- [DeepSeek Harness](https://github.com/deepseek-ai/deepseek-harness) —— 本启动器承载的 agent 本体
- [oonid/pr](https://github.com/oonid/pr) —— targetSdk 35+ 下的 proot 适配方案（PROOT_LOADER / seccomp / CLONE 剥离）
- [proot](https://github.com/proot-me/proot) / [Termux](https://github.com/termux) —— 无 root 的 Linux 环境

## Star History

<a href="https://www.star-history.com/?repos=sqkl520%2Fdsh-fcl-android-launcher&type=date&legend=top-left">
 <picture>
   <source media="(prefers-color-scheme: dark)" srcset="https://api.star-history.com/chart?repos=sqkl520%2Fdsh-fcl-android-launcher&type=date&theme=dark&legend=top-left" />
   <source media="(prefers-color-scheme: light)" srcset="https://api.star-history.com/chart?repos=sqkl520%2Fdsh-fcl-android-launcher&type=date&legend=top-left" />
   <img alt="Star History Chart" src="https://api.star-history.com/chart?repos=sqkl520%2Fdsh-fcl-android-launcher&type=date&legend=top-left" />
 </picture>
</a>
