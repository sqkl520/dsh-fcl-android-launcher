<div align="center">

# DSHarness-FCL-Launcher

**在未 root 的安卓手机上运行 [DeepSeek Harness](https://github.com/deepseek-ai/deepseek-harness)（dsh）**
基于 [Fold Craft Launcher](https://github.com/FCL-Team/FoldCraftLauncher) 改造的 dsh 启动器

![Version](https://img.shields.io/badge/version-0.1.6--SNAPSHOT-orange?style=flat-square)
![Platform](https://img.shields.io/badge/platform-Android%20arm64-blue?style=flat-square)
![Tests](https://img.shields.io/badge/unit%20tests-513%20passing-brightgreen?style=flat-square)
![License](https://img.shields.io/badge/license-GPL--3.0-green?style=flat-square)

**[⬇️ 下载最新版](https://github.com/sqkl520/dsh-fcl-android-launcher/releases)** ·
[文档总览](docs/README.md) ·
[问题反馈](https://github.com/sqkl520/dsh-fcl-android-launcher/issues)

</div>

> ⚠️ **这是一个高一牲用 AI 辅助开发（vibe coding）做的 App，处于早期开发中**
> 有任何问题、建议都欢迎提交 [Issues](https://github.com/sqkl520/dsh-fcl-android-launcher/issues)；
> 如果喜欢这个项目就 Star 一下喵 ⭐
>
> **当前版本以 [Releases 页](https://github.com/sqkl520/dsh-fcl-android-launcher/releases) 为准**：
> 最新验证包是 `0.1.6-SNAPSHOT-fix22`（截至本文最后更新）。本 README 不单独维护版本号 ——
> 历史上这里写过 `fix4`/`fix6` 等旧值，与 Releases 页对不上时**一律以 Releases 页为准**。
> 装完请到**关于页核对版本号后缀**（如 `-fix22`）确认装对了包；文件名带 `-fixN` 的才是当前验证包。

---

## ✨ 功能特性

**运行与实例管理**

- **无 root 运行** —— proot 兜住 Linux 环境跑 Node 版 dsh，`targetSdk 34` 不降级
- **多实例管理** —— 多个 dsh 实例独立安装 / 启动 / 停止 / 删除，互不干扰
- **版本管理** —— 从 npm registry 拉取版本列表，一键安装指定版本的 dsh
- **一键启动** —— 自动捕获访问凭证，应用内直接打开 dsh 界面
- **外部浏览器也能用** —— 实例详情页可「在浏览器中打开」（用此刻带凭据的地址），也可「复制访问链接」自己粘贴
- **启动目录可自定义** —— dsh 的目录选择器从你指定的目录起步（默认是实例目录），重启后生效
- **版本过旧时的出路** —— 设备 WebView 过旧时，门禁框不再只给"硬闯或离开"两条路：可以直接「复制链接」把带凭据的地址拿到较新内核的浏览器打开，也可以「稍后」先不动（外部浏览器内核过旧同样会白屏，不保证能开）

**界面与体验**

- **三页外壳** —— 实例 / 版本 / 设置，FCL 原版视觉风格，亮暗主题跟随
- **任务区** —— 安装 / 启动 / 停止的进行态与终态（完成 / 失败 / 已取消）都看得见
- **按实例分流的日志** —— 每个实例独立日志 + 应用全局日志，均落盘可查
- **中英繁三语** —— 简体中文 / 繁體中文 / English 可切换
- **打开界面更快** —— 给 dsh 前端静态资源补了 `cache-control: immutable`，同一个界面再打开时不再整套重下

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
| 打开界面看到 `dsh web authentication required` | 回应用内点「打开界面」（会自动带上当前的新 token）；要在外部浏览器里用，到该实例详情页「运行」里点「复制访问链接」，拿带凭据的链接粘贴进浏览器。注意：重启实例后链接与端口都可能变，旧链接随即失效 |
| 提示「WebView 过旧」 | 升级「Android System WebView」（部分设备为 Chrome）；急用时在门禁框点「复制链接」拿到带凭据的地址，粘贴到已更新的浏览器（外部浏览器内核过旧同样会白屏）。不想现在决定就点「稍后」，回到实例页 |
| 界面白屏 / 模型选不了 | 同上——多半是 WebView 内核过旧；dsh 新版前端实测需要 119+（社区/真机经验值，非代码里的硬性门槛） |
| 首次要等多久？要多大空间？ | 解压约需 1.5 GB 空闲、几分钟（视闪存速度）；解压期间保持前台或锁屏放置均可 |
| 下载/安装中断了要重来吗？ | 不用——已下载的内容有缓存，重试不会从头再来 |
| 品牌 / 包名 | **fix6 起**：正式包名 `com.dsh.fcl.androidlauncher` + 专用签名密钥（非 debuggable）。**fix1~fix5**：验证包，包名带 `.debug` 后缀、公开调试签名——两者是**不同的 App，可并存**（fix6 不继承旧版数据） |
| 覆盖安装后脚本要重解压吗？ | 需要时会自动做，不用你操心：启动时只读比对一次本机脚本与 APK 内的版本号，落后就直接进「准备运行环境」页，只重解 `scripts` 子目录（约 45 KB，秒级），**不动 rootfs**。这条路径 fix10 起才接通（此前存量用户升级后不会触发）。**fix22 改了脚本**（新增一个前端资源缓存插件），从更早的包升上来会真正触发一次重解压 |

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

> 最后更新：2026-10-30（fix22）。版本号唯一真源：`gradle.properties` 的 `dshVersion` / `dshVersionCode`。

| 模块 | 状态 |
|---|---|
| FCL 改造：移除全部 Minecraft 代码与资源 | ✅ 完成（约 −226k 行） |
| 应用外壳：三页 + 实例详情 + 右面板 + 动态岛 | ✅ 完成 |
| 运行时底座：proot + rootfs + PROOT_LOADER | ✅ 已集成、真机点亮 |
| 真机端到端：装 → 启 → 停 → 再启 → 对话 | ✅ 已点亮（0.1.6，2026-10-11） |
| 修复轮次 fix1 ~ fix9 | ✅ 全部已发布（fix5 仅本机验证、未上传 Releases，原因见 [`docs/RELEASES.md`](docs/RELEASES.md) 的「fix5，历史验证包」段）；状态见 [Releases](https://github.com/sqkl520/dsh-fcl-android-launcher/releases) |
| 修复轮次 **fix10**（R23 锐评返工：门禁框四支按钮 + 复制后不再空白 + 脚本版本比对接通） | ✅ 已发布（code 116，见 Releases） |
| 修复轮次 **fix11**（R24 状态机收口：启动重试与停止可靠性） | ✅ 已发布（code 117，见 Releases） |
| 修复轮次 **fix12**（R25 判据统一：装好了没只认一套口径 + 失败不再被吞掉） | ✅ 已发布（code 118，见 Releases） |
| 修复轮次 **fix13**（R26 第三件事：测试从「钉实现」升级为「契约 + 管线」） | ✅ 已发布（code 119，见 Releases） |
| 修复轮次 **fix14**（R26 收尾：跨实现一致性测试去掉镜像实现，加变异验证） | ✅ 已发布（code 120，见 Releases） |
| 修复轮次 **fix15**（R27：外部浏览器选不了工作区 —— `HOME` 指向实例 workspace） | ✅ 已发布（code 121，见 Releases） |
| 修复轮次 **fix16**（R27：在浏览器中打开 + 端口稳定，让外部浏览器能长期免认证） | ✅ 已发布（code 122，见 Releases） |
| 修复轮次 **fix17**（R27：HOME 指向实例目录 + 「启动目录」可自定义） | ✅ 已出包（code 123） |
| 修复轮次 **fix18**（R28 锐评返工：注释纠偏 + `..` 拒绝 + 自定义目录自动创建） | ✅ 已出包（code 124） |
| 修复轮次 **fix19**（R28 二轮锐评：幽灵实例保护 + 调用点契约测试） | ✅ 已出包（code 125） |
| 修复轮次 **fix20**（R28 三轮锐评：API Key 暴露面提示 + typo 空目录可诊断） | ✅ 已出包（code 126） |
| 修复轮次 **fix21**（R28 C3 用户视角：空目录自动告警 + 更正三处错误说明） | ✅ 已出包（code 127） |
| 修复轮次 **fix22**（R29：复制链接后不再重问 WebView 门禁 + 前端资源补缓存头，打开界面不再每次重下） | ✅ 已出包（code 128） |
| `1.0.0`（能正常启动 / 下载 / 管理 dsh） | ⬜ 待 0.1.6 转正 |

**质量基线**：编译 BUILD SUCCESSFUL · 单元测试 **513/513** · 脚本自检 28/28
（每轮修复都跑全量；关键修复均钉成断言）

> 版本号唯一真源仍是 `gradle.properties` 的 `dshVersion` / `dshVersionCode`；
> **对外可下载的包以 Releases 页为准**，本表只记轮次进度，不承诺"哪个 fixN 已公开发布"。

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

> 这几个 `run-*.sh` / `build-apk.sh` / `release.sh` 位于**工作区快照仓根目录**，不在 Android 源码仓的
> clone 里（源码仓根目录没有任何 `.sh`）。只 clone 了源码仓的话，跑上面的 Gradle 命令即可，
> 做的事完全一样；细节见 [`CONTRIBUTING.md`](CONTRIBUTING.md) §6.2。

打包前必须备好 `FCL/src/main/assets/dsh/rootfs/rootfs.tar.xz`（142 MB，不入 Git；
最省事的取法见 [`docs/ROOTFS.md`](docs/ROOTFS.md)）。
**APK 不进 Git**：新版本作为 GitHub Release 附件分发。

## 🤝 开发与贡献

这是一个高一牲在课余时间做的小项目，代码不算漂亮，但每一轮修复都有单测钉着。
如果你也想让它跑得更好——欢迎来玩，Issue、PR 都收。

- **贡献指南**：[`CONTRIBUTING.md`](CONTRIBUTING.md) —— 怎么报问题、怎么提 PR、代码风格与测试要求。
- **构建与测试**：`.\gradlew.bat --no-daemon :FCL:testFordebugUnitTest`（Windows，单测）；
  `sh run-compile.sh` → `sh run-tests.sh`（arm64 Linux / 手机沙箱，编译校验 + 单测）。环境接线见 [`docs/ENVIRONMENT.md`](docs/ENVIRONMENT.md)。
- **Issues 与 PR**：报 bug 请带上**关于页显示的版本号后缀**（如 `0.1.6-SNAPSHOT-fix22`）、机型、Android 版本、复现步骤；
  想改代码请先开个 Issue 说一声，避免白写。

[![Contributors](https://contrib.rocks/image?repo=sqkl520/dsh-fcl-android-launcher)](https://github.com/sqkl520/dsh-fcl-android-launcher/graphs/contributors)

本项目基于 [Fold Craft Launcher](https://github.com/FCL-Team/FoldCraftLauncher) 改造，同样以 **GPL-3.0** 发布（见 [`LICENSE`](LICENSE)）。

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
