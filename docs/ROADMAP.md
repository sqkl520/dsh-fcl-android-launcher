# dsh 安卓启动器 —— 未来路线规划（Roadmap）

> 把散落在各轮报告与 `PLAN.md §8` 里的待办、真机联调、打磨项，整合成一条
> **按里程碑推进**的更新路线。每个里程碑给出：目标、要做的事、完成判据（DoD）、依赖与风险。
>
> 阅读方式：**从上往下就是建议的推进顺序**。M1 是所有后续的前提（没有真机跑通，一切都是纸面）。

---

## 当前所处位置（截至 2026-10-06）

**外壳改造（M0）已全部完成**（阶段 0~4，含去 Material 收尾，见 `design/app-shell.md` 与 `reports/round7-review-and-optimization.md`）：
app 启动即进 dsh 外壳，五页可切换，无 MC 运行时门禁。

**运行时底座也已落地（代码层面，2026-10-03 阶段 A~E-1）**：自研 proot 层 + 预打包 rootfs
（Debian 12 + Node 22 + 预装 dsh）已接进 APK，三个 jniLibs（`libproot.so` / `libproot-loader.so` /
`libbusybox.so`）就位，`PTY` 桥接（`ptyjni`）已编进去；0.1.1 起出的包是**含完整 rootfs 的整包**。

> **⚠️ 路线更正（2026-10-06）**：`design/proot-engine-integration.md` 曾计划**集成 oonid/pr 的
> `:proot-engine` 替换自研 proot 层**，但**最终没有采用** —— 项目继续走自研 proot 层。
> 本文件此前多处按"要走 proot-engine"描述 M1，与代码现状（`settings.gradle.kts` 只 include `:FCL`
> 与 `:ZipFileSystem`；无 `ProotLauncher`/`ProotHost`，而是 `ProotCommand` + `ProotProcessExecutor`）
> 不符，已于本次更正。那份设计文档保留作**决策记录**（其中的 W^X / PROOT_LOADER 原理仍然有效）。
> **保持 targetSdk 34、用 PROOT_LOADER 绕过 W^X 这一结论不变。**

**所以现在唯一的空白是「真机端到端」**（M1 的最后一格）：底座在真机上到底能不能起来、首启解压
300MB rootfs 要多久、`npm install` 能不能装成、`dsh web` + WebView 能不能对话。这是 M1 的阻塞项，
也是整个项目现在唯一的关键路径。

---

## 里程碑总览

| 里程碑 | 一句话目标 | 性质 | 前置 |
|---|---|---|---|
| **M0 外壳改造** | app 启动即进 dsh 界面，不再被迫装 MC 运行时 | 应做，**已完成**（阶段 0~4） | 无 |
| **M1 首次真机点亮** | 一台真机上端到端跑通一次 | 必做，最高优先级 | **只差真机验收**（底座代码已落地） |
| **M2 稳定性达标** | 装/启/停在真机反复跑不崩不卡不串台 | 必做 | M1 |
| **M3 体验完善** | 从"能用"到"顺手" | 应做 | M2 |
| **M4 可测试性与工程化** | 缺陷能被自动化拦住，而非靠人工审 | 应做 | 并行于 M2/M3 |
| **M5 分发与生态** | 能发布给别人用；跟上 dsh 上游 | 可做 | M3 |
| **M6 能力扩展** | 多设备形态、更多 profile/插件 | 远期 | M5 |

> M0 与 M1 是"外壳"与"内核"的关系，**可并行**：M0 做完 app 能正确进入 dsh 界面（底座未就绪也不挡路），
> M1 做完界面里的"装 dsh → 启动 → WebView"才真正跑通。

---

## M0 · 外壳改造（GUI 先能用）⚡

**目标**：把 app 外壳从"MC 启动器"改成"dsh 启动器"——启动即进 dsh 界面，
不再被迫先下载约 1GB 的 MC 运行时（JRE/LWJGL/Caciocavallo/JNA）。

**为什么排在最前**：它**不依赖 M1 的运行时底座**（proot / rootfs），可以立刻做。
做完之后，app 至少"是个 dsh 启动器"，而不是"MC 启动器 + 藏在长按里的 dsh"。

**设计详见**：`design/app-shell.md`（含阶段 0~4 的分步计划与风险清单）。

**要做的事（概要）**
- **阶段 0**：`SplashActivity` 跳过 8 项 MC 运行时门禁，直接进 dsh 界面（改动最小，可先验证方向） ✅
- **阶段 1~3**：新建 dsh 外壳（左侧滑出菜单 + ViewPager2 五页 + 右侧面板 + 动态岛标题），
  五页 = 实例 / 管理(占位) / 下载 / 日志 / 设置；WebView 保持独立 Activity ✅
- **阶段 4（可选）**：裁剪不可达的 MC 代码与资源，减体积 ✅

**完成判据（DoD）**——**全部已达成（2026-10-02，第七轮）**
- [x] 启动直接进 dsh 外壳，**不出现**"安装或更新运行环境"页与 MC EULA
- [x] 实例 / 下载 / 日志 / 设置 等页可切换；菜单高亮与动态岛标题同步（五页 = 实例/管理/下载/日志/设置）
- [x] **全程不需要装 MC 运行时**即可到达 dsh 界面并使用
- [x] 不触发 MC 单例初始化（`ConfigHolder.init()` / `RendererManager.init()`）
- [x] **8 个 dsh 布局 0 Material 控件**（§2.5 收尾，见 `reports/round7-review-and-optimization.md`）

**依赖 / 风险**：见 `design/app-shell.md` §6（重点是"别触发 MC 单例"与"Activity → FCLCommonUI 的生命周期差异"）。

---

## M1 · 首次真机点亮 🎯

**目标**：在一台未 root 的 arm64 真机上，完整走通一次
「运行时底座就绪 → 下载页装 dsh → 列表页启动 → WebView 出 dsh 界面并能对话」。

> **底座代码已落地**（2026-10-03 阶段 A~E-1）：自研 proot 层 + 预打包 rootfs（Debian 12 + Node 22 +
> 预装 dsh），三个 jniLibs 就位，`ptyjni` 已编进去；0.1.1 起是含完整 rootfs 的整包。
> **本里程碑现在只剩"真机验收"这一步。**

### 要做的（按顺序）

1. **装 0.1.2-SNAPSHOT 到真机** —— 包在 GitHub Release（公开仓，免 token）：
   <https://github.com/sqkl520/dsh-fcl-android-launcher/releases/tag/v0.1.2-SNAPSHOT>
2. **首启**：确认底座解压 300MB rootfs 的耗时与成功率（需 ≥2GB 空闲）；
   解压后自检（`probe.sh`）必须打出 `dsh-probe-ok`，否则先把失败原因记下来。
3. **下载页**：装一个 dsh 版本 → 实例状态到 `READY`。
   重点看 **DNS 修复是否真生效**（`npm install` 能否装 0.2.x）——历史上这里必 `EAI_AGAIN`。
4. **启动**：捕获 `[start-dsh] READY url=http://127.0.0.1:<port>/?token=...`，
   `DshWebViewActivity` 渲染出 dsh Web UI。
5. **对话**：用真实 DeepSeek key 在 WebView 里成功对话一次。
6. **收尾**：停止后进程结束、前台通知消失、明文凭据抹除；息屏/切后台再回来的表现。
7. 把结果（成功/失败与原因）回填到本篇、`INDEX.md`、`TASKS.md` T6。

**就诊入口**：出问题先看应用内「设置 → 运行时自检」与日志页；
`adb logcat` 抓 `com.dsh` 相关行。

### 完成判据（DoD）

- [ ] proot 能进 rootfs 执行 `node -v`（版本达标），`probe.sh` 打出 `dsh-probe-ok`
- [ ] 下载页能装上至少一个 dsh 版本，实例状态到 `READY`
- [ ] 启动后捕获到 `?token=` URL，`DshWebViewActivity` 正常渲染 dsh Web UI
- [ ] 用真实 DeepSeek key 在 WebView 里成功对话一次
- [ ] 停止能干净收尾（进程结束、前台通知消失、明文凭据抹除）

### 兜底路线（只在真机验证失败时才考虑）

**若真机上 proot 起不来（W^X / seccomp / spawn 任一处不通）**，按顺序试：
1. 加 `PROOT_NO_SECCOMP=1`（部分内核的 seccomp 兼容性兜底，见 M2）
2. 按 `design/wx-exec-proot-loader.md` §6 的方案 A：**降 `targetSdk` 到 28** ——
   这是最后的退路，代价是要重新过一遍 Play 的上架限制，非必要不做
3. `design/proot-engine-integration.md` 里记录的**集成 oonid/pr `:proot-engine`** 仍是备选路线
   （那份文档是决策记录，其中的 W^X / PROOT_LOADER 原理有效；真要启用得按它 §11 分阶段做）

### 依赖 / 风险

- proot 二进制与设备内核的 seccomp 兼容性 → M2 的 `PROOT_NO_SECCOMP` 兜底
- `Process.pid()` 在 ART 上可能拿不到（已有告警）→ M2 处理
- rootfs 体积（约 300MB×压缩 / 1.4GB 解压后）叠加 APK，注意首装体验 → T7 瘦身

---
## M2 · 稳定性达标

**目标**：M1 跑通后，让核心链路在真机上**反复操作也稳**——不崩、不卡死、多实例不串台。
这一里程碑消化的是前几轮报告里"已识别但需真机确认/仍未做"的可靠性项。

**要做的事**
1. ✅ **`PROOT_NO_SECCOMP` 自动兜底**（第五轮已实现）：就绪前退出且疑似 seccomp/ptrace 不兼容
   （退出码 >128 或日志含 seccomp/ptrace/bad system call 等）时，自动带该变量重试一次（每实例每次启动限一次）。
   真机上仍需确认"重试后确实能救活"。
2. **`Process.pid()` 兜底**：反射拿不到时，退回解析 `/proc/self/task/*/children`，
   或让 proot 包装脚本回吐 `$$`，保证停止/清理能精准杀到目标进程。
   （第五轮已给 `kill()` 加 cmdline 身份校验，避免 pid 复用误杀；但"拿不到 pid"本身仍待兜底。）
3. ✅ **`DshInstaller` 错误按 instanceId 分发**（第五轮已实现）：失败摘要/npm 错误尾巴改为按实例分表，
   消除双实例并发安装时的错误串台（下载页进度条串台仍待补，见 R5-17）。
4. **前台服务真机保活验证**：在主流厂商 ROM（小米/华为/OV/三星等）上验证
   `DshRuntimeService` 的存活与被杀恢复行为，`specialUse` 声明已就位但实际策略因厂商而异。
5. ✅ **认领进程假死**（第五轮已实现）：认领来的孤儿进程死亡后有 10s 存活巡检兜底（双条件判定），
   不再永远停在 Running。其余异常路径（装到一半切后台/断网/磁盘满等）仍需真机复现确认。

**完成判据**
- [ ] 连续装/删/启/停 20 次以上无崩溃、无卡死、无状态残留
- [ ] 断网/切后台/转屏等异常路径均能优雅恢复
- [ ] 双实例并发安装，进度互不干扰
- [ ] 锁屏 + 后台 30 分钟后 dsh 进程仍在（至少在测试机上）

---

## M3 · 体验完善

**目标**：从"能用"到"顺手"。这里都是打磨项，不阻塞可用性。

**要做的事**
- **下载断点续传**：dsh 依赖装到一半断网可续，不必从头再来。
- **Landlock 弱隔离提示**：UI 明确告知"当前环境隔离弱，勿跑不可信任务"，与 dsh 官方 SAFETY 一致。
- **实例配置导出/导入**：把实例元数据（版本/profile/端口/model，**不含明文 key**）导出，便于迁移/备份。
- **正式入口**：~~"长按设置"的隐藏入口~~ → 已由 **M0 外壳改造**取代（启动即进 dsh 主界面）。见 `design/app-shell.md`。
- **体积可视化与清理**：设置页已有体积展示与清理，M3 补"一键清理未使用实例 / 共享 rootfs 重置"。
- **首启体验**：rootfs 解压进度更细、失败可重试、给出预计耗时与占用空间提示。

**完成判据**
- [ ] 断网续传可用
- [ ] 有隔离风险提示
- [ ] 配置可导出导入且不泄露 key

---

## M4 · 可测试性与工程化（可与 M2/M3 并行）

**目标**：把"靠人工审代码才能发现"的缺陷，变成"自动化能拦住"。前四轮有几个 P0 是靠人工核对
Android 文档才发现的（比如前台服务必崩），这类应该被测试兜住。

**要做的事**
- **给 `DshRuntimeService` 加可测抽象**：把"当前状态 / startForeground 调用"注入，
  用 Robolectric 或纯逻辑单测覆盖"服务契约"类缺陷（对应第四轮 P0-1）。
- **给 `DshRuntime` 状态机注入"进程/时间/Context"**：把捕获 URL、停旧起新等竞态变成可单测
  （对应第四轮 P1-6/P1-7）。
- **CI 化**：把已有的三条验证（全量 `compileDebugKotlin`、脚本 POSIX 一致性、JVM 单测）
  接进 CI；解决离线沙箱下 Gradle 的 ksp 卡顿（当前用 kotlinc-embeddable + 桩 JUnit 反射跑）。
- **修 `strings.xml` 的 aapt2 错误**（`values-de/fa/ja/pt-rBR/ru` 等 `multiple substitutions
  in non-positional format`）：**第五轮已证实这是"告警"不是"错误"，不会挡 `assembleDebug`/发布**
  （实测 `mergeDebugResources` 打印后构建继续）。仍建议改成位置参数（`%1$s`…）以消警告；
  **切勿**用 `formatted="false"`——那会让 `getString(id, args)` 的参数不再被替换（界面直接显示 `%s`）。
  非 dsh 引入、仓库既有，建议单独立项。

**完成判据**
- [ ] `DshRuntimeService` / `DshRuntime` 关键路径有单测
- [ ] CI 跑通编译 + 脚本 + 单测三条线
- [ ] `assembleDebug` 不再被 strings 资源错误挡住

---

## M5 · 分发与生态

**目标**：能把成品发给别人用；跟上 dsh 上游的快速迭代。

**要做的事**
- **发布渠道**：签名策略、版本号规范、CHANGELOG；决定是否随 FCL 主线发布，还是独立分发。
- **rootfs 分发优化**：rootfs 几百 MB 直接塞进 APK 太重，考虑
  「首启从可信源下载 rootfs」或「按 ABI 拆分 APK」或「APK 内放精简 rootfs + 首启补装」。
- **跟随 dsh 上游**：dsh 是 developer preview、会破坏兼容。建立"锁定已验证版本 + 定期验证新版本"
  的机制；关注上游对原生模块 ABI、profile、鉴权方式的变更。
- **多版本兼容矩阵**：记录"哪个 dsh 版本 × 哪个 Node × 哪个 rootfs"验证过，写进文档。

**完成判据**
- [ ] 有可分发的签名 APK 与发布说明
- [ ] rootfs 分发方案落地（不再让 APK 无谓膨胀）
- [ ] 有 dsh 版本兼容矩阵并定期更新

---

## M6 · 能力扩展（远期）

**目标**：在稳定可用之后，探索更大的想象空间。均为**可选**方向，视需求再定。

- **更多 dsh profile**：目前聚焦 `web`；可探索 `headless`（一次性任务/自动化）、`sdk`。
- **更多能力接入**：用户当前只要「对话/编码 agent + shell/文件」；若未来需要，
  再评估 MCP 插件接入（computer-use/browser-use 明确不在范围内）。
- **多设备形态**：平板/折叠屏适配、桌面模式；FCL 本身面向折叠屏，有基础。
- **性能与省电**：长驻 Node 进程的内存/耗电优化，空闲自动挂起/唤醒。

---

## 优先级速记

- **关键路径**：M1 的**真机验收**（底座代码已落地，只差一台机器跑一遍）。
- **可以并行做**：M4 测试与工程化（`T9` 已清；脚本自检还没接进 `run-tests.sh`）、
  T7 rootfs 瘦身、T10 文档同步（`PLAN.md`/`PACKAGING.md` 的过时内容）。
- **紧跟其后**：M2（真机稳了才敢给人用），M4 可以并行（边修边补测试）。
- **锦上添花**：M3 打磨、M5 分发。
- **看情况**：M6 扩展。

> 一句话：**外壳通了（M0 ✅）、底座也接上了（✅），现在只差它在真机上活一次（M1），
> 然后活得稳（M2），最后活得好（M3+）。**

---

## 与其他文档的关系

- 真机怎么验、验什么：见 **`TASKS.md` T6** 与 `PLAN.md §8.5`（后者是旧的自研流程记录，
  步骤仍可参考，但"补齐 proot/rootfs 大文件"那部分已过时 —— 那些文件已经就位）。
- 运行时底座**怎么落地**：**当前实现 = 自研 proot 层 + 预打包 rootfs**，
  见 `PACKAGING.md`（打包）与 `ROOTFS.md`（rootfs 重建）。
  `design/proot-engine-integration.md` 是**未采用的备选路线**（决策记录，W^X/PROOT_LOADER 原理有效），
  `design/wx-exec-proot-loader.md` 是 W^X 原理。
- 各里程碑里"为什么这么做"的**缺陷背景**：见 `reports/round2~11` 审查报告。
- 本文档随进展更新：里程碑达成后在对应处打勾，并把结论回填到 `INDEX.md` 与 `TASKS.md`。
