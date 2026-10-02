# dsh 安卓启动器 —— 未来路线规划（Roadmap）

> 把散落在各轮报告与 `PLAN.md §8` 里的待办、真机联调、打磨项，整合成一条
> **按里程碑推进**的更新路线。每个里程碑给出：目标、要做的事、完成判据（DoD）、依赖与风险。
>
> 阅读方式：**从上往下就是建议的推进顺序**。M1 是所有后续的前提（没有真机跑通，一切都是纸面）。

---

## 当前所处位置（截至第七轮，2026-10-02）

**外壳改造（M0）已全部完成**（阶段 0~4，含去 Material 收尾，见 `design/app-shell.md` 与 `reports/round7-review-and-optimization.md`）：
app 启动即进 dsh 外壳，五页可切换，无 MC 运行时门禁。**真机端到端仍是空白**——最大的原因不再是"GUI 没做好"，
而是"运行时底座没落地"：proot/rootfs 至今是 PLACEHOLDER，且自研 proot 层过不了 W^X（targetSdk 34）。

**运行时底座路线已定**（见 `design/proot-engine-integration.md`）：**保持 targetSdk 34，不降级**，
集成 oonid/pr 的 `:proot-engine`（PROOT_LOADER 绕 W^X，真机 targetSdk 35/36 验证过）。**唯一拦路项 =
dsh 子进程 spawn 能否在 patched proot 下工作**（cargo 那条 vfork 路径 ENOSYS；gcc 的普通 fork 通），
这必须作为集成第一步在真机上证实（详见 M1）。

所以路线图的第一站，只有一个目标：**先验证子进程 spawn，再把它在真机上真正活一次**。

---

## 里程碑总览

| 里程碑 | 一句话目标 | 性质 | 前置 |
|---|---|---|---|
| **M0 外壳改造** | app 启动即进 dsh 界面，不再被迫装 MC 运行时 | 应做，**已完成**（阶段 0~4） | 无（不依赖 proot/rootfs） |
| **M1 运行时底座落地 + 首次真机点亮** | 验证子进程 spawn → 集成 `:proot-engine` → 一台真机上端到端跑通一次 | 必做，最高优先级 | 先验证 spawn（阻塞项）→ 集成 proot-engine → 出 APK |
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

**为什么排在最前**：它**不依赖 M1 的运行时底座**（proot-engine / rootfs），可以立刻做。
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

## M1 · 运行时底座落地 + 首次真机点亮 🎯

**目标**：在一台未 root 的 arm64 真机上，完整走通一次
「运行时底座就绪 → 下载页装 dsh → 列表页启动 → WebView 出 dsh 界面并能对话」。

> ⚠️ **路线更新（2026-10-01）**：运行时底座**不再走"用户自备 proot 二进制 + rootfs.tar.xz"**的自研路径，
> 改为**集成 oonid/pr 的 `:proot-engine`**（`targetSdk` 保持 34，不降级）。理由、API、
> License、落地步骤见 `design/proot-engine-integration.md`；W^X 绕过原理见 `design/wx-exec-proot-loader.md`。
> `PACKAGING.md`（自研 proot/rootfs 准备）从此**作废**，仅"在 arm64 上出 APK"一节仍适用。

**要做的事（按顺序，第一项是阻塞项）**
1. 🔴 **验证 dsh 子进程 spawn**（唯一拦路项，集成第一步）：
   oonid/pr 真机实测 `gcc 编译 hello world ✅`（普通 fork+execve 通）、但 `cargo build ❌`（vfork 路径 ENOSYS）。
   dsh 的核心是频繁开子进程（`npm install` / spawn `/bin/bash` / agent 执行命令），必须确认 node 的 spawn
   （libuv → `posix_spawn`/`fork+execvp`）不命中 cargo 那个坑。方法：装 oonid/pr APK → `pr-cli install alpine`
   → `apk add nodejs npm` → 跑 `node -e "require('child_process').execSync('echo hi')"` 与 `npm install`。
2. 把 `:proot-engine` 作为 Gradle 模块引入（`settings.gradle.kts` 加 `include`）
   —— ⚠️ 这会**恢复一部分 NDK + Rust native 构建**（删 MC 时去掉了 NDK，需按 `design/proot-engine-integration.md §7-3` 恢复）。
3. 构建 5 个 jniLibs 二进制（libproot.so / libproot-loader.so / libpr-cli.so / libbusybox.so / 可选 libbash.so）。
4. 实现 `DshProotHost : ProotHost`（用 `DshPaths` 填 4 个目录）；把 `DshInstaller`/`DshRuntime`
   从"自拼 proot 命令 + stdout 管道"改为 `ProotLauncher`（PTY Session）——注意 token 抓取要加 ANSI 清洗。
5. 删 `ProotCommand` / `ProotProcessExecutor`，精简 `DshBootstrap`。
6. 出 APK：`./gradlew --no-daemon -Darch=arm64 :FCL:assembleFordebug`（脚本 `sh /workspace/build-apk.sh`）。
7. 装机，跑通端到端，把结果回填到本篇与 `PLAN.md`。

> 若第 1 步验证 spawn 不通 → 集成作废，退回兜底方案 A（`targetSdk = 28` 自研 proot），见 `wx-exec-proot-loader.md §6`。

**完成判据（DoD）**
- [ ] 运行时底座就绪（proot-engine 集成后），proot 能进发行版执行 `node -v`（版本达标）
- [ ] 下载页能装上至少一个 dsh 版本，实例状态到 `READY`
- [ ] 启动后捕获到 `?token=` URL，`DshWebViewActivity` 正常渲染 dsh Web UI
- [ ] 用真实 DeepSeek key 在 WebView 里成功对话一次
- [ ] 停止能干净收尾（进程结束、前台通知消失、明文凭据抹除）

**风险**：见 `design/proot-engine-integration.md §9`（子进程 spawn、native 构建复杂度、API 变动、PTY 抓取脆弱）。

**依赖 / 风险**
- proot 二进制与设备内核的 seccomp 兼容性 → 见 M2 的 `PROOT_NO_SECCOMP` 兜底。
- `Process.pid()` 在 ART 上可能拿不到（已有告警）→ M2 处理。
- rootfs 体积（几十~几百 MB）叠加 APK，注意首装体验。

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

- **可以立即做**：**死代码 / CI / 依赖清账**（与运行时底座无关，可并行推进）。
- **关键路径**：M1 **先验证子进程 spawn**（阻塞项），过了再集成 `:proot-engine` + 出 APK + 真机点亮。
- **紧跟其后**：M2（真机稳了才敢给人用），M4 可以并行（边修边补测试）。
- **锦上添花**：M3 打磨、M5 分发。
- **看情况**：M6 扩展。

> 一句话：**先把外壳打通（M0，让 GUI 能用），再让它在真机上活一次（M1），
> 然后活得稳（M2），最后活得好（M3+）。**

---

## 与其他文档的关系

- 真机联调的**具体操作步骤**：见 `PLAN.md §8.5`（本文档只做规划，不重复步骤）。**注**：§8.5 目前仍是
  旧的"自备 proot + rootfs.tar.xz"流程，集成 proot-engine 后需改写。
- 运行时底座**怎么落地**：见 `design/proot-engine-integration.md`（proot-engine）与 `design/wx-exec-proot-loader.md`（W^X 原理）。
- 各里程碑里"为什么这么做"的**缺陷背景**：见 `reports/round2~7` 审查报告。
- 本文档随进展更新：里程碑达成后在对应处打勾，并把结论回填到 `PLAN.md §7` 与 `INDEX.md`。
