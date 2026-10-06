# DeepSeek Harness 安卓启动器 —— 完整落地方案

> ## ⚠️ 过时声明（2026-10-01 标注，2026-10-06 更正）
>
> 本文是**早期方案的总纲**，写于外壳改造之前，多处描述已经落地或已改路线，
> **保留原貌只为追溯决策**。看当前状态请看：
>
> | 想知道什么 | 看哪份 |
> |---|---|
> | 当前状态 / 版本 / 代码在哪 | `README.md`（本目录） |
> | 接下来做什么、按什么顺序 | `ROADMAP.md` |
> | 运行时底座**当前实现**（自研 proot 层 + 预打包 rootfs） | `PACKAGING.md` + `ROOTFS.md` |
> | 技术选型与取舍 | `docs/PLAN.md` |
>
> **两点更正**（本文与新文档冲突时以下面为准）：
> 1. **运行时底座没有改走 `:proot-engine`** —— 2026-10-01 一度决定集成 oonid/pr 的 `:proot-engine`，
>    但**最终没有采用**，项目继续走"自研 proot 层 + 预打包 rootfs"（阶段 A~E-1 已落地）。
>    `design/proot-engine-integration.md` 作为**未采用的备选路线**保留。**targetSdk 保持 34、用
>    PROOT_LOADER 绕 W^X 这个结论不变**（见 `design/wx-exec-proot-loader.md`）。
> 2. **本文多数章节已落地或已过时**：应用外壳（§架构中"MC 启动器 + 长按进 dsh"的描述）、
>    proot 启动链、rootfs 准备（现成 rootfs 已就位，见 `ROOTFS.md`）、真机联调步骤（§8.5）。
>    其中 **§8.5 的"补齐 proot/rootfs 大文件"那一步已无必要**（文件已就位），其余步骤仍可参考。

---

> 在未 root 的普通安卓手机上，做一个 DeepSeek Harness（dsh）的启动器/管理器 App。
> 借鉴 FoldCraftLauncher（FCL）的技术，模型走 DeepSeek 云端 API，手机只跑 dsh 本体与界面。
>
> 本文整合任务1（proot 启动链）、任务2（多版本/实例管理+下载）、任务3（运行时保活+WebView）
> 的设计与实测结论，作为单一落地参考。

---

## 0. TL;DR

- **可行性已实证**：在等价环境（aarch64 + proot + Ubuntu24.04/glibc + Node22.23）里，dsh 的
  arm64 原生模块正常加载、`dsh web` 返回完整 Web UI（HTTP 200）、headless 用真实 key 调
  `deepseek-flash` 返回结果。整条链路端到端跑通。
- **第二轮加固（2026-09-22）已完成**：修掉 10 个致命/严重 bug（无 API Key 入口、日志页无入口、
  安装假成功、界面退出即中断安装、主线程删 300MB 必 ANR、孤儿进程占端口、启动无超时、WebView
  无限转圈…）、6 处性能问题（日志 O(n²)、全量重绘…）、11 处可靠性/安全问题（明文 key 落盘、
  密钥进 argv、token 进日志…），并补齐交互层（新增实例设置页、日志入口、底座引导横幅、安装进度、
  失败出口、热恢复）。**逐项清单见 第二轮加固报告**。
- **核心架构**：Android App（借鉴 FCL 的下载/实例/前台服务范式）+ 自带 proot rootfs（跑 Node+dsh）
  + WebView（加载 dsh 自带的 Web UI）+ 云端 DeepSeek API。
- **关键约束**：dsh 原生模块只发 glibc/musl（无 android/bionic），所以**必须 proot**，不能用 Termux
  的 bionic Node，也不能像 FCL 那样 in-process dlopen。
- **代码进度**：`com.dsh.core`（17 个）+ `com.dsh.ui`（7 个）= **24 个 Kotlin 文件 ≈ 4310 行**，加
  **7 个布局 xml**、Manifest 注册、中英文案、assets 底座目录、1 个 JVM 单测文件。
- **已通过完整 Android 编译**（含第二轮改动）：`./gradlew :FCL:compileDebugKotlin` **BUILD SUCCESSFUL**，
  dsh 文件 **0 error / 0 warning**（见 §7.1）。
- **已打进 APK**（第一轮产物）：`assembleFordebug` 产出已签名 APK；第二轮改动经真编译验证，
  重新打包需在有网络/非限时环境跑一次。
- 核心逻辑做过**真运行**：脚本 4 场景真跑（含 200/401 与信号转发）、30 条断言直连编译产物、
  14 个 JVM 单测全部通过。

---

## 1. 背景与目标

### 1.1 两个项目
- **dsh（DeepSeek Harness）**：DeepSeek 开源的 agent harness。**Node.js/TypeScript** 项目（pnpm monorepo，
  Cordis 驱动"一切皆插件"）。启动 `npx @deepseek-ai/dsh web` → `http://127.0.0.1:3080` 起 Web UI。
  模型走云端，本机不做大模型推理。
- **FCL（FoldCraftLauncher）**：安卓上的 Minecraft: Java 版启动器。Kotlin/Java，
  applicationId=`com.tungsten.fcl`。

### 1.2 目标形态（已确定）
- A 方案：做 dsh 的安卓启动器，FCL 只当技术参考。
- 自带 proot rootfs（体验最像成品，用户装 APK 即用，不需先装 Termux）。
- 云端 DeepSeek API（用户有 key）。
- 未 root 普通手机。
- 只要「对话/编码 agent」+「shell/文件操作」两类能力，**不要** computer-use/browser-use。

---

## 2. 关键技术事实（实地拉仓库 + PoC 验证）

### 2.1 dsh
- 包名 `@deepseek-ai/dsh`；npm 上 22 个版本，dist-tags：`latest/next=0.1.5-rc.2`、`alpha=0.1.6-alpha.2`。
- **engines 要求 Node `^22.19.0 || >=24.0.0`**（较新，rootfs 里要装够新的 Node）。
- **原生模块只发 `linux-x64` / `linux-arm64`（glibc+musl 两份）/ `darwin`，无 android/bionic。**
  这条决定了必须 proot 跑标准 Linux（glibc 的 Ubuntu 或 musl 的 Alpine）。
- 启动 `dsh web` 后 stdout 打印 `http://127.0.0.1:<port>/?token=XXXX`。
- **鉴权是 token→cookie 模式**：带 token 首访 302 并 set-cookie，之后靠 cookie。
  带 cookie=200，无 cookie=401。WebView 默认存 cookie，加载一次带 token 的 URL 即可进。
- **坑**：默认 web profile 的 `dsh-hmr` 插件要求 node 带 `--expose-internals`，且该 flag
  **不允许放进 `NODE_OPTIONS`**。所以不能用 `.bin/dsh` 软链，必须直接 `node --expose-internals bin.js`
  （脚本已处理）。或改用 headless/sdk profile（默认关 HMR）。
- 单版本 `node_modules` ≈ **303MB**（190 个顶层包）。
- DeepSeek 模型 id：`deepseek-flash`、`deepseek-v4-pro`；API `https://api.deepseek.com/chat/completions`；
  env `DEEPSEEK_API_KEY`。

### 2.2 FCL（读源码 main@f4f2624）
- **FCL 不用 proot！** 它把 JRE 打包 assets 解压到私有目录，用 native `jre_launcher.c` +
  `VMLauncher.launchJVM`（dlopen `libjvm.so`）在**同进程内**跑 JVM。
- 结论：FCL 的「运行时启动层」对我们**不能照搬**（dsh 是外部 Node 进程且原生依赖要命中 glibc/musl，
  必须 proot）。但 FCL 的**上层管理设施**非常值得搬，且我们照搬了（见 §4.1）。

### 2.3 Landlock 沙箱
- dsh 用 Linux Landlock 做隔离。proot 环境 + 多数安卓内核**测不到可用 Landlock**，隔离会退化。
- 自用 + 审批弹窗可接受，**别拿它跑不可信任务**（dsh 官方 SAFETY 也如此声明）。

---

## 3. 总体架构

```
┌─────────────────────────── Android APK ───────────────────────────┐
│                                                                     │
│  原生启动器 UI（借鉴 FCL：实例列表/下载/日志/设置）                    │
│        │                                                            │
│        ├── DshInstances（实例仓库单例, StateFlow）                    │
│        ├── DshDownloadViewModel ── DshRegistry ──► npm registry      │
│        ├── DshInstaller ─┐                                          │
│        └── DshRuntime ───┤                                          │
│                          ▼                                          │
│                   ProotProcessExecutor（ProcessBuilder + proot）     │
│                          │                                          │
│  ┌───────────────────────▼──────────────────────────────────────┐  │
│  │ proot + rootfs（Ubuntu/Alpine arm64，App 私有目录，共享）        │  │
│  │   Node 22/24 + 各版本 dsh（每实例独立 node_modules）             │  │
│  │   `dsh web` → 127.0.0.1:<port>/?token=XXXX                     │  │
│  └───────────────────────┬──────────────────────────────────────┘  │
│                          │ 捕获 token URL                            │
│  DshRuntimeService（前台服务保活）   DshWebViewActivity（WebView）    │
│                                          │                          │
└──────────────────────────────────────────┼──────────────────────────┘
                                            ▼
                              云端 DeepSeek API（llm 插件调用）
```

三层职责：
1. **运行时层**：proot + rootfs 跑 Node+dsh（任务1）。
2. **管理层**：实例/版本/下载/配置（任务2，照搬 FCL 范式）。
3. **接入层**：进程保活 + WebView 显示 dsh UI + API key 安全存取（任务3）。

---

## 4. 目录与代码结构

### 4.1 从 FCL 借鉴 vs 自研

| FCL 的东西 | 我们对应实现 | 关系 |
|---|---|---|
| `FCLauncher.launch`（dlopen libjvm in-process） | `DshRuntime`（ProcessBuilder + proot 外部进程） | **不照搬**，模型不同 |
| `Profiles`（StateFlow 仓库单例） | `DshInstances` | 照搬范式 |
| `Profile.kt`（Gson 序列化单元） | `DshInstance` | 照搬范式 |
| `FCLPath.java`（集中路径） | `DshPaths` | 照搬范式 |
| `DownloadManager`+`DownloadService`（前台保活） | `DshInstaller`+`DshRuntimeService` | 照搬范式 |
| `ui/version`（List/Adapter/Item） | `DshVersionListItem`+`DshDownloadViewModel` | 照搬范式 |
| `HttpRequest`（HttpURLConnection+Gson+异步重试） | `DshRegistry` 直接**复用** | 复用，不引新依赖 |
| `ShellUtil`（ProcessBuilder 跑 sh） | `ProotProcessExecutor` | 参考 |
| `WebActivity`（WebView+WebViewClient） | `DshWebViewActivity` | 参考 |

### 4.2 App 私有目录布局（DshPaths）

```
<filesDir>/dsh/
  rootfs/                共享 proot rootfs（Ubuntu/Alpine arm64），所有实例复用
  scripts/               从 assets 解压出的 proot 脚本（setup/start）
  instances/<id>/        每实例独立
    node_modules/        该实例的 dsh 及依赖（约 300MB）
    package.json
    home/                DSH_HOME（会话/配置/profile）
    credentials.env      运行时明文 key（权限 600，用后抹除）
    credentials.enc      长期密文 key（Keystore AES-GCM）
  instances.json         实例清单（DshInstances 持久化）
<cacheDir>/dsh/tarballs/ 下载临时区
```

### 4.3 已写的 Kotlin 文件（`com.dsh.core` 17 个 + `com.dsh.ui` 7 个 ≈ 4310 行）

> 本节已按**第二轮加固**（2026-09-22）更新；逐项改动与原因见
> 第二轮加固报告。

**数据/管理层**
- `DshPaths.kt`（204）— 路径中心（rootfs/proot/scripts/npm-cache/logs/tmp/instances）+ 磁盘占用助手
- `DshRegistry.kt`（181）— npm registry 客户端 + 语义版本排序 + 10 分钟缓存
- `DshInstance.kt`（79）— 实例数据模型（port=0 自动分配、lastError、模型/profile 常量）
- `DshInstances.kt`（277）— 实例仓库单例（StateFlow + 异步原子持久化 + **状态自愈 repair()** + 唯一 id + 异步删除）
- `DshInstaller.kt`（284）— 版本安装器（proot 内 npm）+ 单飞/超时/阶段进度/装完硬校验
- `DshVersionListItem.kt`（47）— 下载列表行模型（tag 优先级）
- `DshDownloadViewModel.kt`（123）— 下载页数据流（缓存/超时/断网回退）
- `DshLogBus.kt`（172）— **新增**：日志总线（环形缓冲、~5Hz 合并刷新、落盘、token 脱敏）
- `DshAppScope.kt`（22）— **新增**：进程级协程作用域（长任务不随界面销毁）
- `DshServices.kt`（26）— **新增**：进程级服务定位器（安装器单例）
- `DshBootstrap.kt`（266）— 底座首启解压（增量/幂等/原子替换/rootfs 上提/磁盘检查/**自检探针**）

**运行时/接入层**
- `ProotCommand.kt`（162）— **新增**：proot 命令行唯一构造处 + 预检 + 环境变量白名单（密钥不进 argv）
- `ProotProcessExecutor.kt`（197）— `ProotExecutor` 真机实现 + `ProotRunner`（长驻进程句柄/优雅终止）
- `DshRuntime.kt`（487）— 长驻 `dsh web` 进程管理：**状态机 / 启动看门狗 / 端口回读 / 认领孤儿进程**
- `DshRuntimeService.kt`（186）— 前台保活服务（specialUse、通知可停、进程退出自动收尾）
- `DshCredentials.kt`（153）— API key 安全存取（Keystore 加密 + AAD + 状态区分；**明文不再落盘**）
- `DeepSeekApi.kt`（80）— **新增**：DeepSeek `/models` 密钥校验（设置页"测试连接"）

**界面层（`com.dsh.ui`，7 个）**
- `DshInstancesActivity.kt`（304）— 实例列表（入口齐备 + 底座横幅 + 安装进度 + 失败出口 + 认领）
- `DshInstanceAdapter.kt`（187）— 实例行（DiffUtil、进度、失败原因、更多菜单、体积缓存）
- `DshDownloadActivity.kt`（174）— 下载页（缓存刷新、底座准备进度、防连点）
- `DshVersionAdapter.kt`（92）— 版本行（DiffUtil、安装中态）
- `DshLogsActivity.kt`（94）— 日志页（revision 重绘、自动滚动开关、复制、落盘路径）
- `DshSettingsActivity.kt`（241）— **新增**：实例设置页（API Key / 名称 / 模型 / profile / 端口 / 自检 / 重装 / 删除）
- `DshWebViewActivity.kt`（272）— WebView（完整状态机、401 兜底、cookie 持久化、安全收紧）

### 4.4 脚本

`dsh-launcher-poc/scripts/`（与 `FCL/src/main/assets/dsh/scripts/` 同源，脚本版本号 2）：
- `setup-node-dsh.sh` — rootfs 内备 Node（校验 ^22.19||>=24）+ 装指定版 dsh 到实例目录；
  打印 `STAGE=...` 供界面显示进度；共享 npm 缓存；装完硬校验
- `start-dsh.sh` — rootfs 内起 `dsh web`；**主动探测就绪**并打印 `READY url=/port=`，
  失败打印 `FAILED reason=...`；信号转发；支持 `PORT=0`
- `probe.sh` — 自检探针（`DshBootstrap.verify` 调用）
- `proot-run.sh` — 设备侧包装器（用 proot 兜 rootfs），真机参考实现

---

## 5. 关键流程

### 5.1 首次安装一个 dsh 实例
1. UI 调 `DshDownloadViewModel.refresh()` → `DshRegistry.fetchVersions()` 拉 npm 版本列表（abbreviated metadata）。
2. 用户选版本 → `installVersion()` → `DshInstances.create()` 建实例目录 + 元数据。
3. `DshInstaller.install()` → `ProotProcessExecutor` 在 proot 里跑 `setup-node-dsh.sh`
   （`npm install @deepseek-ai/dsh@<版本>`），进度经 StateFlow 回传 UI。
4. 成功 → 实例状态置 READY，记录真实版本号。

### 5.2 启动并使用
1. `DshCredentials.writeRuntimeCredential()` 把明文 key 写进 `credentials.env`（权限 600）。
2. `DshRuntime.start()` → proot 里跑 `start-dsh.sh` 起 `dsh web`。
3. `DshRuntimeService`（前台服务）拉起，保活进程。
4. `DshRuntime` 后台读输出，正则捕获 `http://127.0.0.1:<port>/?token=XXXX`。
5. `DshWebViewActivity` 订阅到 URL 就绪 → `loadUrl()` → dsh 完整 Web UI 显示，可对话/编码。
6. 停止：`DshRuntime.stop()` 杀进程 + `clearRuntimeCredential()` 抹明文 + 停前台服务。

---

## 6. 安全设计

- **API key**：长期用 Android Keystore AES-GCM 加密落盘（只存密文 `credentials.enc`）；
  启动前才解密写入 `credentials.env`（权限 600），停止后抹除。明文只在运行瞬间落受限文件。
- **网络绑定**：`dsh web` 只绑 `127.0.0.1`，不暴露局域网。
- **命令注入防护**：proot 命令用数组参数（非字符串拼接）；注入的环境变量走 key 白名单，
  挡掉 `LD_PRELOAD` 等危险变量。
- **隔离弱化告知**：Landlock 在 proot/安卓内核多半 unusable，UI 应提示"勿跑不可信任务"，
  并保留 dsh 的审批弹窗。
- **进程/页面解耦**：关闭 WebView 不停进程；停止在列表显式操作。服务被系统回收时兜底杀进程防僵尸。

---

## 7. 已验证 vs 未验证

### 7.1 已实测通过
| 项 | 方法 | 结果 |
|---|---|---|
| dsh arm64 原生模块加载 | proot+glibc 环境 require | node-addon-system/koffi/node-pty/sharp 全 OK |
| `dsh web` 起 Web UI | 真实启动 + curl | HTTP 200，27KB 完整前端 |
| token→cookie 鉴权 | curl 带/不带 cookie | 带 cookie=200，无 cookie=401 |
| 云端 API 端到端 | headless + 真实 key | 调 deepseek-flash 返回结果 |
| start-dsh.sh | 复用实例真跑 | 正确载入 key、起服务、打印 token URL |
| registry 版本拉取 | 真实请求 | 22 版本，dist-tags 正确 |
| token URL 捕获正则 | 喂真实 dsh 输出 | 成功捕获 |
| **完整 Android 编译** | **Android SDK35+NDK27+Gradle8.14.4，`./gradlew :FCL:compileDebugKotlin`** | **BUILD SUCCESSFUL**（17 个 dsh 文件+6 布局全过类型检查） |
| **APK 打包（跳过 native）** | **`assembleFordebug`（临时注释 externalNativeBuild）** | **BUILD SUCCESSFUL**，产出已签名 APK 362MB；18 个 dsh 类全部进 dex、5 个组件进 Manifest、6 个布局进资源表（用 zipfile/aapt2 核验） |
| **dsh assets 进包** | `mergeFordebugAssets` 中间产物核验 | `dsh/scripts/*.sh` + version 均进入 merged assets；`com/dsh/*` 进入 dexBuilder 产物 |
| 首启解压编排 | 桩 Context+桩 RuntimeUtils 跑真实 DshBootstrap | 12/12 通过（首启解压/幂等/版本升级重解压/proot 路径回退） |
| rootfs tar.xz 符号链接 | 真实 tar+xz 打包解压往返 | 符号链接正确保留（rootfs 关键） |
| 实例仓库功能测试 | 桩 Context 跑真实代码 | 31/31 通过（含持久化往返、端口分配、选中校正） |
| registry 解析/排序 | 真实数据驱动真实 Kotlin | 13/13 断言通过 |
| XML/资源静态一致性 | 脚本交叉校验 | 9 个 XML 良构；R.string/R.id/binding 属性/类名/drawable 全匹配 |

**第二轮加固后的验证（2026-09-22，详见 第二轮加固报告 §8）**

| 项 | 方法 | 结果 |
|---|---|---|
| 全量真编译（含第二轮全部改动） | `./gradlew :FCL:compileDebugKotlin -Darch=arm64` | **BUILD SUCCESSFUL**，dsh 文件 0 error / 0 warning |
| 编译期抓到的真实缺陷 | 同上 | 4 处已修：KDoc 里 `*.sh` 触发**嵌套注释**导致整个文件语法错误；`Process.pid()` 在 Android 运行时**不存在**；`emptyMap()` 类型推断；`URL as HttpURLConnection` **必崩强转** |
| 改写后 `start-dsh.sh` 真跑（固定端口） | 真实 dsh 包，实机等价环境 | 15s 出 `READY url=...`；带 token 访问 **200**、无 cookie **401**；运行中即可见透传日志；TERM 后退出码 0 且端口释放 |
| 改写后 `start-dsh.sh` 真跑（`PORT=0`） | 同上 | 自动分配端口（如 40233），从 URL 回读成功 |
| 失败信号 | 实例目录无 dsh 包 | 立即 `FAILED reason=package-missing`（退出码 1），不再干等到超时 |
| 自检探针 | `probe.sh` | `dsh-probe-ok`（同时打印真实内核/架构/node 版本） |
| 纯逻辑真跑 | Java 直连**编译产物** 30 条断言（semver / registry 解析排序 / tag / URL&token 解析 / 日志脱敏） | **30 / 30 通过** |
| JVM 单测 | 新增 `FCL/src/test/java/com/dsh/DshCoreLogicTest.kt`（kotlinc 编译 + 反射执行 14 例） | **14 / 14 通过** |
| dsh 真实行为探测 | token→cookie、cookie **跨进程重启仍有效**、`--port 0`、`dsh web --help` | 均确认，并据此定下 WebView 与脚本的就绪策略 |

> **arm64 上跑 Android SDK 的关键坑（已解决，供真机/CI 复现参考）**：
> 1. FCL 需 `ndk;27.0.12077973`（有 native CMake 构建），AGP 自动下载曾拿到损坏 zip，改用
>    `sdkmanager "ndk;27.0.12077973"` 手动安装。
> 2. 该 SDK 的 `aapt2` 只有 x86_64 版（Google 不发布 linux-arm64 aapt2）。arm64 上直接执行报
>    `AAPT2 Daemon startup failed`。解法：装 `qemu-x86_64-static` + x86_64 运行时库（`libc6:amd64` 等，
>    apt 源需按架构分流），再把 AGP 缓存 jar 内的 `aapt2` 换成"qemu 转发 wrapper"（真二进制另存，
>    `aapt2` 改为 `exec qemu-x86_64-static <真二进制> "$@"`），清除已解出的 transforms 缓存后重编。

### 7.2 未验证 / 未做
- **含新 assets 的完整 APK 重新产出**：`DshBootstrap` 等新代码已编译通过、assets 已确认进入
  mergeAssets/dex 中间产物；但受沙箱限制（每次命令返回会 SIGTERM 掉 proot 内 gradle，
  重新打包的 dexBuilder 耗时超单次超时上限），未能再生成一次最终 APK 文件。现有 APK 为上一轮
  产物。x86_64 或不限时环境跑 `assembleDebug` 即可一次出含 native + 新 assets 的完整包。
- **native CMake 构建**：FCL 的 46 个 C/C++ 源（跑 Minecraft 的 JRE launcher/GL 等，**与 dsh 无关**）
  需 NDK 工具链（clang/cmake/ninja 全是 x86_64），在 arm64 沙箱需全程 qemu，未跑。上面的
  APK 打包是**临时注释掉 externalNativeBuild** 得到的（现已还原）。
- **proot 二进制与 rootfs.tar.xz**：平台相关大文件，未纳入仓库。需打包方按
  `PACKAGING.md` 补齐（proot 推荐走 jniLibs，rootfs 建议内置 Node）。
- **proot 真机执行**：沙箱自身在 proot 内无法嵌套 proot，`ProotProcessExecutor`/`DshRuntime`
  的 proot 执行是真机接口约定（rootfs 内部流程已在等价环境验证）。
- **运行期行为**：WebView 实际加载、前台服务保活、Keystore 加解密，均需真机。

---

## 8. 落地路线图（建议顺序）

1. ~~**本地编译验证**~~ ✅ 已完成：`./gradlew :FCL:compileDebugKotlin` BUILD SUCCESSFUL
   （arm64 环境需按 §7.1 注解处理 NDK 与 aapt2 的 x86_64/qemu 问题）。
2. ~~**完整 APK 打包**~~ ✅ 已验证（dsh 部分）：`assembleFordebug` 出已签名 APK，18 个 dsh 类进 dex、
   组件/布局全部打包。native CMake 因 arm64 工具链需 qemu 暂跳过（与 dsh 无关，见 §7.2）；
   真机/x86_64 环境跑 `assembleDebug` 可出含 native 的完整包。
3. ~~**assets 与首启解压**~~ ✅ 代码完成并验证：`DshBootstrap` 首启解压（增量/幂等/符号链接）
   编译通过 + 12/12 功能测试；assets 底座目录已建（scripts 已放入，proot 二进制与 rootfs.tar.xz
   待打包方按 `PACKAGING.md` 补齐）。
4. **真机联调**：补齐 proot 二进制（jniLibs）+ rootfs.tar.xz → 首启解压 → 下载页装 dsh → 启动 → WebView 出 UI。
5. **打磨**
   - ~~设置页：API key 录入 UI（接 `DshCredentials`）、模型选择、端口~~ ✅ 已完成
     （第二轮：新增 `ui/DshSettingsActivity`，含"测试连接"与 Keystore 加密存取）
   - ~~实例重命名、体积清理入口、错误提示~~ ✅ 已完成（第二轮；重命名/体积在设置页，错误提示落到行内 + 对话框）
   - 仍未做：下载断点续传、Landlock 弱隔离提示、实例配置导出/导入。
   - 若要做正式入口：在主界面加独立按钮/菜单项（现为长按"设置"的隐藏入口）。
   - **第二轮的完整改动清单**：第二轮加固报告（10 个致命/严重 bug、
     6 处性能、11 处可靠性/安全、交互层补齐、脚本信号协议重写）

---

## 8.5 真机联调步骤（在实机上按序执行）

> ⚠️ **本节写于外壳改造之前，部分已过时**：运行时底座**没有**改走 `:proot-engine`，
> 仍是本文描述的**自研 proot 层 + 预打包 rootfs**（已落地）。
> **步骤 1「补齐两类平台大文件」已无必要** —— proot 三件套在 `jniLibs/`、rootfs 在
> `assets/dsh/rootfs/`，都已就位（见 `ROOTFS.md`）。
> 真机该验什么、怎么验，以 **`TASKS.md` T6** 与 `ROADMAP.md` M1 为准；本节其余步骤仍可参考。

前置：一台 arm64 安卓真机（未 root 即可）、可编译 FCL 的开发环境（x86_64 机器最省事，
或本沙箱这套 arm64+qemu 方案，见 §7.1 注解）。

### 步骤 1：补齐两类平台大文件（**已无必要，均已就位**）
1. **proot 二进制（推荐 jniLibs 方案）**：把 arm64 的 proot、proot loader 命名为
   `libproot.so`、`libproot_loader.so`，放入 `FCL/src/main/jniLibs/arm64-v8a/`。
   系统安装时自动解压到 `nativeLibraryDir` 并自带执行位，`DshPaths.resolveProotBin/Loader`
   会优先取用。（来源：proot-distro / Termux 的 proot 包，或自行交叉编译。）
2. **rootfs.tar.xz**：准备 arm64 的 Ubuntu(glibc，推荐) 或 Alpine(musl) rootfs，
   **打包前把 Node 22/24 预装进去**（省首次联网），打成 `.tar.xz` 放
   `FCL/src/main/assets/dsh/rootfs/rootfs.tar.xz`。删除同目录的 `PLACEHOLDER.txt`。
3. 若改用 assets 方案放 proot，则把 so 放 `assets/dsh/proot/` 并删占位文件（见 `PACKAGING.md`）。

### 步骤 2：出完整 APK
```
./gradlew :FCL:assembleDebug          # 或 assembleFordebug（用仓库内 debug-key，密码 FCL-Debug）
```
此步会正常构建 native CMake（46 个 C/C++，跑 MC 用，与 dsh 无关但 FCL 打包需要）。
arm64 编译机需按 §7.1 处理 aapt2/cmake/ninja 的 x86_64→qemu 问题；x86_64 机无此问题。

### 步骤 3：装机与入口
1. `adb install -r <apk>` 安装到真机。
2. 进入 dsh 启动器：主界面**长按左侧"设置"**菜单（隐藏入口）→ 打开 `DshInstancesActivity`。

### 步骤 4：首启解压 + 装实例
1. 点"下载" → 进 `DshDownloadActivity`，首次会触发 `DshBootstrap` 解压 proot/rootfs/scripts
   到私有目录（有进度提示）。
2. 版本列表加载后（来自 npm registry）选一个版本 → 触发 `DshInstaller` 在 proot 里跑
   `setup-node-dsh.sh` 装 dsh。观察 `DshLogsActivity` 的实时日志。
3. 装完实例状态变 READY。

### 步骤 5：配置 key 并启动
1. 用 `DshCredentials.storeEncrypted()` 存入 DeepSeek API key（设置页 UI 待补，见 §8 第 5 步；
   临调可先在代码里调一次或临时写入实例 `credentials.env`）。
2. 实例列表点"启动" → `DshRuntime` 起 `dsh web`，前台服务保活 → 捕获 token URL →
   `DshWebViewActivity` 加载，出 dsh 完整 Web UI，即可对话/编码。

### 联调排查清单（对应各环节的常见失败点）
- **proot 起不来**：确认 `libproot.so` 有执行位、`PROOT_LOADER`/`PROOT_TMP_DIR` 环境变量正确、
  rootfs 内 `/bin/sh` 存在。
- **Node 版本不符**：rootfs 内 `node -v` 必须 `^22.19||>=24`（`setup-node-dsh.sh` 会校验）。
- **dsh web 起不来**：确认用 `node --expose-internals bin.js`（不能进 NODE_OPTIONS）；看 `DshLogsActivity`。
- **WebView 401**：token→cookie 未生效；确认加载的是带 `?token=` 的完整 URL、WebView 允许 cookie。
- **对话失败但 UI 正常**：多半是 key 未注入或额度问题；检查实例 `credentials.env` 的 `DEEPSEEK_API_KEY`。
- **后台被杀**：确认 `DshRuntimeService` 前台通知常驻、已授予通知权限。

---



| 风险 | 说明 | 缓解 |
|---|---|---|
| 体积大 | 单实例 node_modules ≈303MB + rootfs + Node | 共享 rootfs；提供实例清理；提示用户 |
| 后台被杀 | 安卓杀后台会中断 dsh web | 前台服务 + 常驻通知 + WAKE_LOCK |
| 隔离弱 | Landlock 在 proot/安卓多半 unusable | 保留审批弹窗；提示勿跑不可信任务 |
| Node 版本 | 要求 ^22.19||>=24 | rootfs 预装够新 Node；setup 脚本校验 |
| dsh 快速迭代 | developer preview，会破坏兼容 | 多版本管理；锁定已验证版本 |
| 原生模块兼容 | Alpine(musl) 偶有兼容问题 | 默认用 Ubuntu(glibc)，已实测 |

---

## 10. 附：交付物索引

> ⚠️ **本节写于早期，数量与文件名已经过时**（代码文件从 24 个涨到 40+，
> 布局/脚本也有增减）。**不要照这里的数字对账** —— 当前的文件清单与结构见
> [`README.md`](README.md)「当前状态」与「代码在哪」两节。
>
> 保留本节只为说明**当初打算交付什么**（哪些属于文档、哪些属于代码、哪些属于脚本与夹具），
> 这个分类本身仍然是有效的。

**文档（`docs/`）**

- `README.md` — 文档总览与导航（含当前状态）
- `PLAN.md` — 本文，完整落地方案（总纲）
- `ROADMAP.md` — 里程碑规划
- `PACKAGING.md` — 打包说明：proot 二进制 + rootfs.tar.xz 的准备与放置
- `ROOTFS.md` — rootfs 重建与瘦身
- `ENVIRONMENT.md` — 开发环境（JDK / SDK / NDK）
- `design/` — 设计文档（proot 启动链、多版本、UI 拼装、W^X 绕过…）

**代码（`FCL/`）**

- `FCL/src/main/java/com/dsh/core/` — 路径 / 实例 / 安装 / 运行时 / 凭据 / 日志 / 命令构造
- `FCL/src/main/java/com/dsh/ui/` — 实例列表 / 下载 / 日志 / 设置 / WebView + Adapter
- `FCL/src/main/res/layout/*dsh*.xml` — dsh 页面布局
- `FCL/src/main/assets/dsh/` — 首启解压的运行时底座（`scripts/` + `rootfs/`）
- `FCL/src/test/java/com/dsh/` — JVM 单测

**脚本与夹具**

- `FCL/src/main/assets/dsh/scripts/` — 随包发布的 POSIX sh（`setup-node-dsh.sh` / `start-dsh.sh` / `probe.sh`）

---

## 11. 术语速查（给不熟安卓/Linux 的读者）

| 术语 | 一句话解释 |
|---|---|
| **proot** | 不需要 root 就能"假装 chroot"的工具，让普通安卓能跑一整套 Linux 用户态。FCL 跑 Java、我们跑 Node 都靠它。 |
| **rootfs** | 一整套 Linux 系统的文件（/usr、/lib、/bin…），打包成压缩包，几十~几百 MB。 |
| **glibc / musl / bionic** | 三种 C 标准库。Ubuntu 用 glibc，Alpine 用 musl，安卓自带 bionic。dsh 原生模块只出 glibc/musl，所以不能直接用安卓的 bionic。 |
| **原生模块（.node）** | Node 里用 C/C++ 编译的扩展（如 node-pty、koffi）。它是特定 CPU+libc 的二进制，跨 libc 不通用——这正是必须 proot 的根因。 |
| **profile（dsh）** | dsh 的运行模式：`web`（带 Web UI）/`headless`（一次性任务）/`sdk` 等。 |
| **DSH_HOME** | dsh 存会话、配置、profile 的目录。我们每个实例给一个独立的，实现隔离。 |
| **前台服务** | 安卓里带常驻通知、不易被系统杀的后台服务。用来保活长跑的 `dsh web`。 |
| **token→cookie 鉴权** | dsh Web UI 的登录方式：首次带 token 访问会种 cookie，之后靠 cookie。WebView 默认存 cookie，所以加载一次带 token 的 URL 就进去了。 |

---

## 12. 文档说明

- **版本**：v1.0（整合任务1/2/3）
- **如何阅读**：想快速了解结论看 §0 TL;DR；想动手落地按 §8 路线图；关心可行性看 §7 已验证；
  帮忙写 Android 的同学重点看 §3 架构、§4 代码结构、§5 关键流程。
- **重要提醒**：文中所有涉及测试用的 DeepSeek API key 均未写入任何文件或代码；若你在对话中提供过 key，
  请到 DeepSeek 控制台轮换/删除。生产里 key 只走 `DshCredentials`（Keystore 加密），不要硬编码。
- **当前边界**：逻辑层与运行时链路已用真实数据/真实 dsh 验证；尚未做 Android 编译与 UI 拼装（见 §7.2）。
