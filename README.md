<div align="center">

# DSHarness-FCL-Launcher

**在未 root 的安卓手机上运行 [DeepSeek Harness](https://github.com/deepseek-ai/deepseek-harness)（dsh）**
基于 [Fold Craft Launcher](https://github.com/FCL-Team/FoldCraftLauncher) 改造的 dsh 启动器

![Version](https://img.shields.io/badge/version-0.1.6--SNAPSHOT-orange?style=flat-square)
![Platform](https://img.shields.io/badge/platform-Android%20arm64-blue?style=flat-square)
![Tests](https://img.shields.io/badge/unit%20tests-331%20passing-brightgreen?style=flat-square)
![License](https://img.shields.io/badge/license-GPL--3.0-green?style=flat-square)

**[⬇️ 下载最新版](https://github.com/sqkl520/dsh-fcl-android-launcher/releases)** ·
[文档总览](docs/README.md) ·
[问题反馈](https://github.com/sqkl520/dsh-fcl-android-launcher/issues)

</div>

> ⚠️ **这是一个用 AI 辅助开发（vibe coding）的个人项目，处于早期开发中** ——
> 有任何问题欢迎提 Issues，如果喜欢就点个 Star 吧！
>
> **公开可下载的最新包：`0.1.6-SNAPSHOT-fix4`**（见 [Releases](https://github.com/sqkl520/dsh-fcl-android-launcher/releases)）。
> 更新的修复包（fix5/fix6）正在真机验证中，验证通过后才会发布到 Releases。
> 一律以 Releases 页为准 —— 装完请到**关于页核对版本号后缀**（如 `-fix4`）确认装对了包。

---

## ✨ 功能特性

**运行与实例管理**

- **无 root 运行** —— proot 兜住 Linux 环境跑 Node 版 dsh，`targetSdk 34` 不降级
- **多实例管理** —— 多个 dsh 实例独立安装 / 启动 / 停止 / 删除，互不干扰
- **版本管理** —— 从 npm registry 拉取版本列表，一键安装指定版本的 dsh
- **一键启动** —— 自动捕获访问凭证，应用内直接打开 dsh 界面
- **外部浏览器逃生** —— 设备 WebView 过旧时，一键用系统浏览器（Edge / Chrome）打开 dsh

**界面与体验**

- **三页外壳** —— 实例 / 版本 / 设置，FCL 原版视觉风格，亮暗主题跟随
- **任务区** —— 安装 / 启动 / 停止的进行态与终态（完成 / 失败 / 已取消）都看得见
- **按实例分流的日志** —— 每个实例独立日志 + 应用全局日志，均落盘可查
- **中英繁三语** —— 简体中文 / 繁體中文 / English 可切换

## 🚀 快速开始

1. 去 [Releases](https://github.com/sqkl520/dsh-fcl-android-launcher/releases) 下载最新 APK
   （`dsh-fcl-android-launcher-*-arm64.apk`；文件名带 `-fixN` 的是当前验证包，装它）
2. 在 arm64 安卓手机上安装（**未 root 也用**）；首次安装请预留约 **1.5 GB** 空闲空间
   （界面内提示同源，按实际门槛动态计算）
3. 首次启动等待运行环境解压（几分钟，期间保持应用在前台）
4. 去「版本」页安装一个 dsh → 回「实例」页启动 → 在 dsh 界面里填 API Key，开聊
   （API Key 完全由 dsh 自己管理：dsh 界面 → 设置 → 模型）

## ❓ 常见问题

| 问题 | 解法 |
|---|---|
| 打开界面看到 `dsh web authentication required` | 先在系统浏览器里清一次该站点（127.0.0.1）的缓存/cookie，再从应用内重新打开；最新验证包已针对此链路做了多轮修复，若仍复现请带日志反馈 |
| 提示「WebView 过旧」 | 升级「Android System WebView」（部分设备为 Chrome）；急用时点「用外部浏览器打开」 |
| 界面白屏 / 模型选不了 | 同上——多半是 WebView 内核过旧，dsh 新版前端需要 119+ |
| 首次要等多久？要多大空间？ | 解压约需 1.5 GB 空闲、几分钟（视闪存速度）；解压期间保持前台或锁屏放置均可 |
| 下载/安装中断了要重来吗？ | 不用——已下载的内容有缓存，重试不会从头再来 |
| 品牌 / 包名 | **fix6 起**：正式包名 `com.dsh.fcl.androidlauncher` + 专用签名密钥（非 debuggable）。**fix1~fix5**：验证包，包名带 `.debug` 后缀、公开调试签名——两者是**不同的 App，可并存**（fix6 不继承旧版数据） |

## 🔧 它是怎么工作的

```
Android App (本仓库)
   └─ proot（无 root 的 Linux 环境模拟，基于 ptrace 系统调用翻译）
        └─ Linux rootfs（Debian 12 bookworm arm64，内置 Node 22 + 预装 dsh）
             └─ Node.js
                  └─ dsh（DeepSeek Harness）
                       └─ 通过 HTTPS 调 DeepSeek 云端 API
```

界面沿用 FCL 的视觉风格与 GUI 框架（`fcllibrary` + `ThemeEngine`）。

<details>
<summary><b>关键设计：W^X 绕过（PROOT_LOADER）—— 为什么不用降 targetSdk</b></summary>

Android 10+ 对 `targetSdk ≥ 29` 的 App 启用 **W^X**：禁止 `execve` 应用数据目录里的文件，
而 proot 需要在数据目录里执行 rootfs 的 `/bin/sh`、`node`。

**解法**：proot 执行 rootfs 程序时走自带的 **loader**（`PROOT_LOADER` 机制），
把 loader 放进 APK 的 `jniLibs`（安装后位于 `nativeLibraryDir`，SELinux 允许执行），
从而**无需把 targetSdk 降到 28**。详见 [`docs/design/wx-exec-proot-loader.md`](docs/design/wx-exec-proot-loader.md)。

> 该机制经同类项目 [`oonid/pr`](https://github.com/oonid/pr) 在真机（Android 16 / SDK 36）验证通过。

</details>

## 📌 当前进度

> 2026-10-19 更新。版本号唯一真源：`gradle.properties` 的 `dshVersion` / `dshVersionCode`。

| 模块 | 状态 |
|---|---|
| FCL 改造：移除全部 Minecraft 代码与资源 | ✅ 完成（约 −226k 行） |
| 应用外壳：三页 + 实例详情 + 右面板 + 动态岛 | ✅ 完成 |
| 运行时底座：proot + rootfs + PROOT_LOADER | ✅ 已集成、真机点亮 |
| 真机端到端：装 → 启 → 停 → 再启 → 对话 | ✅ 已点亮（0.1.6，2026-10-11） |
| 修复轮次 fix1 / fix2 | ✅ 已发布 |
| 修复轮次 fix3 / fix4 | ✅ 已补发 Releases（2026-10-17） |
| 修复轮次 **fix5**（401 全链路 + 体验修复） | 🔍 真机验证中（code 111） |
| **fix6**（R18 五路审查修复 + **出货身份切换：新签名/正式包名/非 debuggable**） | 📦 已出包（code 112），待验证后发布 |
| `1.0.0`（能正常启动 / 下载 / 管理 dsh） | ⬜ 待 0.1.6 转正 |

**质量基线**：编译 BUILD SUCCESSFUL · 单元测试 **331/331** · 脚本自检 18/18
（每轮修复都跑全量；关键修复均钉成断言）

## 📦 构建

**环境要求**：JDK 17、Android SDK 35、NDK 27；工具链接线见 [`docs/ENVIRONMENT.md`](docs/ENVIRONMENT.md) §1。

```powershell
# 电脑（Windows，当前主力环境）直接用 Gradle：
.\gradlew.bat --no-daemon -Darch=arm64 :FCL:compileDebugKotlin :FCL:compileDebugJavaWithJavac `
  :FCL:processDebugResources :FCL:processDebugMainManifest
.\gradlew.bat --no-daemon :FCL:testFordebugUnitTest           # 单测
.\gradlew.bat --no-daemon -Darch=arm64 :FCL:assembleRelease   # 打包出货包（需先备好 rootfs）
```

> 出货走 `assembleRelease`（T-D1 起：正式包名 / 非 debuggable / 专用密钥；密钥接线见
> `docs/PACKAGING.md` 密钥段）。调试通道仍可用 `assembleFordebug`（debuggable，抓日志用）。

手机（arm64 Linux 沙箱）上用同目录的 `run-compile.sh` / `run-tests.sh` / `build-apk.sh`
（纯 POSIX sh，自动识别源码目录）；一条命令出整版用 `sh release.sh`。

打包前必须备好 `FCL/src/main/assets/dsh/rootfs/rootfs.tar.xz`（142 MB，不入 Git；
最省事的取法见 [`docs/ROOTFS.md`](docs/ROOTFS.md)）。
**APK 不进 Git**：新版本作为 GitHub Release 附件分发。

## 📚 文档

> 完整文档索引见 [`docs/README.md`](docs/README.md)

| 文档 | 内容 |
|---|---|
| [`docs/PLAN.md`](docs/PLAN.md) | 项目总纲与总体方案 |
| [`docs/ROADMAP.md`](docs/ROADMAP.md) | 里程碑规划 M1~M6 |
| [`docs/TASKS.md`](docs/TASKS.md) | 待办清单 |
| [`docs/PACKAGING.md`](docs/PACKAGING.md) / [`docs/ROOTFS.md`](docs/ROOTFS.md) | 打包说明 / rootfs 构建与瘦身 |
| [`docs/ENVIRONMENT.md`](docs/ENVIRONMENT.md) | 开发环境接线（JDK / SDK / NDK） |
| [`docs/design/app-shell.md`](docs/design/app-shell.md) | 应用外壳改造设计（含界面跟 FCL 走的硬性规范） |
| [`docs/design/wx-exec-proot-loader.md`](docs/design/wx-exec-proot-loader.md) | W^X 限制与 PROOT_LOADER 绕过方案 |
| [`docs/design/proot-engine-integration.md`](docs/design/proot-engine-integration.md) | **【未采用的备选路线】** 留作决策记录 |
| [Releases](https://github.com/sqkl520/dsh-fcl-android-launcher/releases) | **APK 下载**（公开仓，免 token） |

## ⚖️ 许可与致谢

本项目基于 [Fold Craft Launcher](https://github.com/FCL-Team/FoldCraftLauncher)（**GPL-3.0**）改造，
因此同样以 **GPL-3.0** 发布。详见 [`LICENSE`](LICENSE)。

打包进 APK 的 `libproot.so` / `libproot-loader.so` / `libbusybox.so` 与 `ptyjni` 桥接来自
[`oonid/pr`](https://github.com/oonid/pr)（`:proot-engine` 与 `pr-cli` 为 MIT，patched proot 本体为
GPL-2.0-or-later）。本项目已是 GPL 系，不构成新障碍。
> 注：`:proot-engine` 作为 **Gradle 模块**的集成方案**最终没有采用**（见
> `docs/design/proot-engine-integration.md`）；这里指的是**二进制与 PTY 桥接的出处**。

- [Fold Craft Launcher](https://github.com/FCL-Team/FoldCraftLauncher) —— 界面框架与改造起点
- [DeepSeek Harness](https://github.com/deepseek-ai/deepseek-harness) —— 本启动器承载的 agent 本体
- [oonid/pr](https://github.com/oonid/pr) —— targetSdk 35+ 下的 proot 适配方案（PROOT_LOADER / seccomp / CLONE 剥离）
- [proot](https://github.com/proot-me/proot) / [Termux](https://github.com/termux) —— 无 root 的 Linux 环境

## ⭐ Star History

<a href="https://www.star-history.com/?repos=sqkl520%2Fdsh-fcl-android-launcher&type=date&legend=top-left">
 <picture>
   <source media="(prefers-color-scheme: dark)" srcset="https://api.star-history.com/chart?repos=sqkl520%2Fdsh-fcl-android-launcher&type=date&theme=dark&legend=top-left" />
   <source media="(prefers-color-scheme: light)" srcset="https://api.star-history.com/chart?repos=sqkl520%2Fdsh-fcl-android-launcher&type=date&legend=top-left" />
   <img alt="Star History Chart" src="https://api.star-history.com/chart?repos=sqkl520%2Fdsh-fcl-android-launcher&type=date&legend=top-left" />
 </picture>
</a>
