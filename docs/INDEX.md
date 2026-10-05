# dsh 安卓启动器 —— 文档总览

> ## 📦 仓库
> **GitHub**：<https://github.com/sqkl520/dsh-fcl-android-launcher>
> 本地源码：`D:\Projects\dsh-fcl-android-launcher`（手机 `/workspace/dsh-fcl-android-launcher`；基线为 FCL 上游 `f4f2624`）
> **快照仓**（源码以外的工作区文件）：[`dsh-fcl-android-launcher-workspace-snapshot`](https://github.com/sqkl520/dsh-fcl-android-launcher-workspace-snapshot)
> （旧名 `ea`）—— 电脑上与源码仓**共用一个项目文件夹**；两仓关系与操作规则见 `REPOS.md`。
> 本项目**基于 [Fold Craft Launcher](https://github.com/FCL-Team/FoldCraftLauncher)（GPL-3.0）改造**，
> 同样以 GPL-3.0 发布。
>
> **当前版本：`0.1.2-SNAPSHOT`** —— 待"能正常启动 / 下载 / 管理 dsh"后才标 `1.0.0`。


> DeepSeek Harness（dsh）在**未 root 安卓手机**上的启动器/管理器。
> 本目录是这个项目唯一的文档根目录。文档**按类别组织**，不再用阅读顺序编号。

---

## 文档地图（按类别）

### 核心文档（顶层，长期常读）
| 文件 | 内容 | 什么时候看 |
|---|---|---|
| `PLAN.md` | **完整落地方案（总纲）**：架构、目录布局、关键流程、安全设计、路线图、真机联调步骤 | 想了解全貌先看这个 |
| `ROADMAP.md` | **未来路线规划**：M1~M6 里程碑（真机点亮→稳定→体验→工程化→分发→扩展） | 想知道"接下来往哪走、按什么顺序" |
| `PACKAGING.md` | **打包说明**：proot 二进制 + rootfs.tar.xz 怎么准备与放置 | 准备出 APK 之前必读 |
| `CHANGELOG.md` | **变更日志**：按阶段/里程碑记录（Added/Changed/Fixed/Removed/Optimized），标注对应 commit | 想快速了解"每一步改了什么" |
| `LESSONS.md` | **经验与踩坑记录**：沙箱/网络约束、死代码清理方法论（Kotlin 假死陷阱）、Android/FCL 平台经验、流程约定 | 动手前避坑、接手项目时先读 |
| `TASKS.md` | **待办清单**：已明确要做但未做的（FCL 动画还原、页内多页体系、主题自定义、真机验收等） | 想知道"接下来做什么、哪些已知未做" |
| `reports/frontend-gap-vs-fcl.md` | **前端 vs FCL 原版逐项对照**：结构性缺口（页内多页/关于页/主题取色/文件选择/切页动画）、组件与资源缺口清单、有意保留的差异、建议顺序 | 想知道"前端还缺什么、为什么" |
| `design/setup-flow-and-task-area.md` | **首启前置页 + 首页任务区 mockup**：一次性准备页（进度/失败/重试）与首页「进行中任务」区域的设计方案 | 做这两个功能前先看 |
| `ROOTFS.md` | **运行时 rootfs 重建步骤**（Debian+Node22+预装 dsh，含清理与自检；原工作区未留档，2026-10-04 固化） | 需要重做/瘦身 rootfs 时 |
| `ENVIRONMENT.md` | **在新设备上重建开发环境**（JDK17/SDK35/NDK27、qemu 包装与双重包装坑、已知环境坑） | 换设备/换工作区时 |
| `reports/animation-vs-fcl.md` | **动画逐项对照**：FCL 的 16 类动画来源 vs 我方现状；`FCLImageButton`/`FCLImageView` 的分工、进度条写法 | 想知道"动画差在哪、控件该用哪个" |
| `design/fcl-ui-restoration.md` | **FCL 原汁原味 UI 还原方案**：FCL 设置结构、dsh 映射、保留/删除/替换清单、实施顺序与验收标准 | 做 UI 还原前必读 |

### `design/` — 设计文档（各功能怎么设计的，相对稳定）
| 文件 | 内容 | 什么时候看 |
|---|---|---|
| `design/proot-chain.md` | 可复现的 proot 启动链（rootfs 选型 + 脚本 + 实测证据） | 关心"怎么在安卓上跑起 Linux" |
| `design/multi-version.md` | 多版本 / 多实例管理 + npm 下载 UI 设计 | 关心实例管理与版本下载 |
| `design/wx-exec-proot-loader.md` | **W^X 执行限制与 PROOT_LOADER 绕过**（targetSdk 决策最终答案；真机实测 + 3 处认知修正） | 想知道"真机到底能不能跑、targetSdk 要不要降" |
| `design/proot-engine-integration.md` | **集成 oonid/pr 的 `:proot-engine`**（替换自研 proot 层的架构决策；API/License/落地步骤/唯一拦路项） | 想知道"运行时底座怎么落地、下一步做什么" |
| `design/ui-manifest.md` | UI 拼装 + Manifest 注册 + 分层验证方法 | 关心界面与组件注册 |
| `design/app-shell.md` | **应用外壳改造**：从 MC 启动器改为 dsh 启动器（保留 FCL 风格与底层框架，照 FCL 外壳形态）；**§2.5 = 硬性要求：界面/按钮/主题一律跟 FCL 走** | 关心"GUI 怎么改成能用的"、要动手改 UI 前必读 |

### `reports/` — 审查报告（每轮一份，按时间累积）
| 文件 | 内容 | 什么时候看 |
|---|---|---|
| `reports/round2-fixes.md` | **第二轮**：审查 + bug 修复 + 性能/可靠性加固 | 想知道"修过哪些坑" |
| `reports/round3-audit.md` | **第三轮**：审计与修缮（含 P0 致命缺陷） | 想知道"现在可信到什么程度" |
| `reports/round4-audit.md` | **第四轮**：崩溃/并发/生命周期加固（含 1 个必崩缺陷） | 想知道"并发/边界下会不会崩/卡死/串台" |
| `reports/round5-audit.md` | **第五轮**：全面评估 + 修复（进主页必崩 P0 + seccomp 自动兜底 + 认领假死） | 想知道"最新一轮改了什么、真机前还差什么" |
| `reports/mc-removal.md` | **MC 移除报告**：删了什么/保留了什么/依赖临界点/残留与风险（**主审查对象**） | 想审查"删 MC 这次大改是否合理" |
| `reports/mc-removal-review-brief.md` | **送审请求书**：给审查方（AI/人工）的背景、范围、六问与重点关注项 | 要送审时的入口文档 |
| `reports/mc-removal-impact-review.md` | **MC 移除影响审查结果**（1166 行）：八维度影响面分析 + M-01~M-04 + 补丁 A/B/C | 想知道"删 MC 有没有伤到别的地方" |
| `reports/round6-optimization.md` | **第六轮：项目评估与优化**（26 文件 +175/−73016）：失效测试源集、rootfs 解压正确性/性能（18.5×）、明文收口、备份规则、安装竞态 | 想知道"最新一轮改了什么、**R-02 平台风险**如何决策" |
| `reports/round7-review-and-optimization.md` | **第七轮：评估与优化**（4 个 P1/P2 修复 + §2.5 去 Material 收尾）；全文 `PROJECT_REVIEW_AND_OPTIMIZATION.md` | 想知道"去 Material 收尾、删除竞态、WebView 失败面板、超时文案"怎么修的 |
| `reports/round8-review-and-optimization.md` | **第八轮：独立复审**（命中 `RuntimeUtils.isLatest` 资源机制不一致导致的"首启解压可能被跳过"静默失败 + 首启兜底/明文卫生两处修缮 + 若干建议）；本轮全文即 `PROJECT_REVIEW_AND_OPTIMIZATION.md` | 想知道"第八轮是否还有新问题、首启解压为什么补文件存在性兜底" |
| `reports/round9-review-and-optimization.md` | **第九轮：复审**（命中 `isReady()`/`preflight()` 对 proot 判据不一致导致的"假就绪" + 下载页空实例累积；修 2 处 + 新增 4 单测）；本轮全文即 `PROJECT_REVIEW_AND_OPTIMIZATION.md` | 想知道"第九轮改了啥：就绪判据统一 + 下载去重" |
| `reports/round10-review-and-optimization.md` | **第十轮：评估与优化**（6 文件 +163/−42）：资源契约测试红→绿、`isLatest` 非数字版本号加固、删除实例归属校验、JNI 符号名对齐、就绪判据补执行位、rootfs 升级可回滚、空间门槛按实测值；本轮全文即 `PROJECT_REVIEW_AND_OPTIMIZATION.md` | 想知道"第十轮改了啥、`MIN_FREE_BYTES` 为什么从 1.5G 提到 2.2G" |
| `reports/round11-review-and-optimization.md` | **第十一轮：评估与优化**（13 文件 +283/−31）：**2 个真机阻塞级 P1** —— ① rootfs 符号链接目标被改写成宿主路径 → `/opt/node22/bin/npm` 断链 → 底座永远不就绪；② 预装 dsh 判定口径未同步 → 装完必判 BROKEN。另修 2 个 P3；单测 27/27→**32/32**；本轮全文即 `PROJECT_REVIEW_AND_OPTIMIZATION.md` | 想知道"第十一轮改了啥、为什么 npm 断链会让底座永远不就绪" |
| `reports/mc-removal-review-brief.md` | **审查请求书**：送审背景（仓库/版本/技术栈/运行方式/业务目标/已知限制）+ **影响面清单** + 期望审查方回答的问题 | 要送审时先看这份 |

分类规则：**顶层**放长期常读的三份（总纲 / 规划 / 打包）；`design/` 放功能设计；
`reports/` 放每轮审查报告（新一轮就往这里加 `roundN-*.md`，顶层不再变乱）。

---

## 当前状态

> ⚠️ **2026-10-01 重大变更**：已完成**移除全部 Minecraft 相关代码与资源**，本项目现在是
> **纯 dsh 启动器**（只保留 FCL 的 UI 框架与少量通用工具）。
> 变更详情见 **`reports/mc-removal.md`**（供独立审查）。
>
> 📌 **2026-10-03 第十一轮评估与优化**：命中**两个真机阻塞级 P1**（都是阶段 D-1 引入的链）——
> ① **rootfs 解压把符号链接目标里的 `..` 改写成宿主路径**：`opt/node22/bin/npm -> ../lib/node_modules/npm/bin/npm-cli.js`
> 被写成 `<rootfs>/lib/node_modules/npm/bin/npm-cli.js`（实测**不存在**），真机首启后 `npm` 断链 →
> `probe.sh` 的 npm 项 FAIL → **底座永远不就绪**；改为**原样保留链接目标**（策略抽到 `TarLinkPolicy` + 单测）。
> ② **「命中预装 dsh 即跳过下载」改变了产物形态，但 Kotlin 三处判定仍只看实例内 `node_modules`** →
> 装完即判 `dsh_install_incomplete` → **BROKEN**、重装重复同一结果、永远起不来；新增
> `DshPaths.effectiveDshDir`（实例优先 → rootfs 预装回退）统一三处口径。另修 2 个 P3
> （安装文案与事实对齐 / `preflight` 判据与入参一致）。单测 27/27 → **32/32**。详见
> **`reports/round11-review-and-optimization.md`**（全文 `PROJECT_REVIEW_AND_OPTIMIZATION.md`）。
>
> 📌 **2026-10-03 第十一轮附加：UI 与 FCL 一致性还原**：把 dsh 界面**逐部件对照 FCL 原版布局**
> （从 git 历史取出 `activity_main.xml` / `item_profile.xml` / `item_version.xml` /
> `item_launcher_setting_button.xml` / `page_setting_list.xml` / `LauncherSettingPage` 作真蓝本）后对齐 ——
> 根因是**阶段 4 裁剪把 FCL 的通用 UI chrome 资产一并删了**（`bg_game_menu` / `bg_right_menu` /
> `bg_item_rounded` / `bg_container_transparent_clickable` / `bg_progress*`），已恢复；
> 外壳补回左侧菜单背景+抬升、`back` 项、`video_view`、右侧面板 25% 半透明形态，
> **动态岛回到 FCL 原位（底部居中）**；列表行/设置行按 FCL 范式重写（含 `anim_scale` 按压反馈、
> `SpacingItemDecoration` 的组内 1dp 分割线）；页内去掉重复大标题、动作改图标按钮；
> **16 处 `MaterialAlertDialogBuilder` 全部换成 `FCLAlertDialog`（归零）**。
> 见 `CHANGELOG.md`「第十一轮附加」与 `LESSONS.md §14/§15`。
>
> 📌 **2026-10-03 第十轮评估与优化**：阶段 A~D 落地后新引入的缺陷被清掉 —— ① **单测门禁原本是红的**
> （`DshSettingsUI` 新增的 `dsh_about_version` 未登记进资源契约测试的 `callSites`，26/27）；② `RuntimeUtils.isLatest`
> 对**非数字版本号**做 `Long.parseLong`（rootfs 的 version 已是 `debian-...-layout2`），一旦 classpath 资源可解析
> 就会抛 `NumberFormatException`（已用 JVM harness 复现）；另修 4 个 P2：**删除实例会误杀别的运行中实例**、
> **JNI 符号名与 `com.dsh.core.PtyNative` 不匹配**（潜伏 `UnsatisfiedLinkError`）、**就绪判据漏查执行位**、
> **rootfs 升级失败会连旧的都丢**；并把空间门槛从 1500 MiB（比 rootfs 实测 1.50 GiB 还小）改成按实测值计算。
> 单测恢复 **27/27**。详见 **`reports/round10-review-and-optimization.md`**。
> 📌 **2026-10-02 第九轮复审**：命中 `DshBootstrap.isReady()` 与 `ProotCommand.preflight()` 对 proot 的
> **判据不一致**——`isReady()` 漏校验 proot 二进制，会出现"横幅隐藏（以为就绪）但一启动就报缺 proot"的
> **假就绪**；并补上了 proot 分支的"缺二进制即补解压"兜底。另修 `DshDownloadViewModel` 下载页
> **重复点击累积空实例**（新增纯函数 `chooseInstanceToInstall` + 4 个单测）。单测升至 **27/27**。
> 详见 **`reports/round9-review-and-optimization.md`**（本轮全文即 `PROJECT_REVIEW_AND_OPTIMIZATION.md`）。
> 📌 **2026-10-02 第八轮独立复审**：命中 `RuntimeUtils.isLatest`（版本比对）与 `RuntimeUtils.install`（解压）的
> **资源机制不一致**——`Class.getResourceAsStream("/assets/...")` vs `context.getAssets().open("dsh/...")`，
> 一旦前者解析不到会**永久跳过首次解压**（静默失败，装完像"好了"但一启动就"缺少脚本"）。
> 本轮已加"文件缺失即补解压"的幂等兜底 + 删除 Manifest 冗余明文开关；详见 `reports/round8-review-and-optimization.md`
> （本轮全文即 `PROJECT_REVIEW_AND_OPTIMIZATION.md`）。
> 📌 **2026-10-02 第七轮收尾**：**外壳改造（M0）全部完成**（阶段 0~4，含去 Material 收尾）；
> 删除实例竞态、WebView 失败面板、安装超时文案、通知状态文案等 4 个 P1/P2 缺陷已修。
> 详见 **`reports/round7-review-and-optimization.md`**（全文 `PROJECT_REVIEW_AND_OPTIMIZATION.md`）。
> **运行时底座路线已定：保持 targetSdk 34，集成 oonid/pr 的 `:proot-engine`**（见 `design/proot-engine-integration.md`），
> 唯一拦路项 = dsh 子进程 spawn 是否能在 patched proot 下工作（需真机先验证）。

- **代码**：`com/dsh/` **42 个文件**（`core` 逻辑 + `ui` 界面）+ FCL 基座
  - `com/dsh/` 42 个（dsh 启动器主体）
  - `com/tungsten/fcllibrary/` 60 个（UI 框架，保留 FCL 风格）
  - `com/tungsten/fclcore/` 394 个（`fakefx` / `util` / `task` / `event`）
  - `com/tungsten/fcl/` 9 个（`FCLApp` / `SplashActivity` / `RuntimeUtils` 等）
  - `com/tungsten/fclauncher/utils/` 2、`com/mio/util/` 4、`ZipFileSystem/` 12
- **资源**：`FCL/src/main/res` 109 个文件 ·
  assets **仅 `dsh/`**（scripts 3 个 + version 标记；`rootfs.tar.xz` 不入 Git）·
  jniLibs 3 个（`libproot.so` / `libproot-loader.so` / `libbusybox.so`）
- **Gradle 模块**：只剩 `:FCL` + `:ZipFileSystem`
- **脚本**：`FCL/src/main/assets/dsh/scripts/` 3 个（严格 POSIX sh，**必须 LF**，见 `ENVIRONMENT.md` §1.3）
- **验证**：编译（Kotlin+Java+资源+Manifest）**BUILD SUCCESSFUL**；单测 **34/34**
- **入口**：`SplashActivity` → 直接进 **`DshMainActivity`**（dsh 外壳，五页：实例/管理/下载/日志/设置；
  **不再有** MC 运行时门禁 / EULA / MC 主界面，也不再有并列的 `DshInstancesActivity` 等旧 Activity）
- **待办**（详见 `TASKS.md`）：
  1. ✅ **【硬性要求】UI 跟 FCL 走**（第七轮收尾）：8 个 dsh 布局 0 Material 控件；
     动画 16/16 对齐；T1/T2/T4 已落地
  2. ✅ **决定 R-02（targetSdk）**：保持 targetSdk 34，用 PROOT_LOADER 绕过 W^X（不降级）
  3. ✅ **外壳改造阶段 1~4** 完成
  4. 🔴 **T6 真机端到端（最大的空白）**：DNS 修复是否真生效（`npm install` 能否装 0.2.x）、
     首次解压耗时/成功率、`dsh web` 起服务 + WebView token/cookie、息屏保活
  5. **T5 插件管理子页**（等插件体系）· **T7 rootfs 瘦身** · **T8 缓存清理 UI** ·
     **T9 `.github/workflows` 适配**（仍是 FCL 原版 CI，会在 GitHub 上失败）· **T10 `PLAN.md`/`PACKAGING.md` 过时内容**
  6. **电脑环境已就绪（2026-10-05）**：Windows x64 + JDK17 + Android Studio + SDK35/NDK27，
     见 `ENVIRONMENT.md` §1；rootfs 已从 0.1.1 参照 APK 无损取回并放回（299.8MB，sha256 `5d762c30…`）。
     **0.1.2-SNAPSHOT 已出包**（`output/dsh-fcl-android-launcher-0.1.2-SNAPSHOT-arm64.apk`，
     326,300,458 字节，sha256 `8abaa3ea…`）；**下一步：装到真机做端到端验证（T6）**
- **已修（2026-10-01，见 `reports/mc-removal-impact-review.md` §11 补丁 A/B）**：
  1. **M-01（P1）** `FCLPath.loadPaths()` 上提到 `FCLApp.onCreate`，并删除 `SplashActivity.kt` 中的重复调用
     —— 消除"必须先经过启动页"的隐式依赖（通知栏 PendingIntent 冷启动会绕过启动页）
  2. **M-02（P2）** `FCLPath.LOG_DIR` 由 `/sdcard/FCL/log` 改为 `context.getDir("log", 0)`
     —— 原路径在删权限后不可写，`fcl.log` 永远建不出来（异常被静默吞掉）
  > 验证：clean build `34/34 executed，0 error`；单测 `TOTAL=23 FAILED=0`。
- **出 APK**：见 `PACKAGING.md`；**改完代码默认不打包**，等明确说"打包测试"再出包

---

## 代码在哪

| 位置 | 说明 |
|---|---|
| `FCL/src/main/java/com/dsh/` | **dsh 启动器主体**（`core` 逻辑 + `ui` 界面），共 42 个文件 |
| `FCL/src/main/java/com/tungsten/` | FCL 基座（`fcllibrary` UI 框架 + `fclcore` 工具），保留 FCL 风格 |
| `FCL/src/main/assets/dsh/` | 运行时底座：`scripts/`（3 个 POSIX sh）+ `rootfs/`（tar.xz 不入 Git） |
| `docs/` | **本目录**，全部文档 |
| `FCL/src/test/java/com/dsh/` | JVM 单测（34 项） |

> `dsh/`（上游源码副本）、`poc/`、`oonid-pr-reference/` 等**参考材料不在源码仓库里**，
> 它们在快照仓 [`dsh-fcl-android-launcher-workspace-snapshot`](https://github.com/sqkl520/dsh-fcl-android-launcher-workspace-snapshot)
> （旧名 `ea`；电脑上与源码仓共用一个项目文件夹，见根下 `REPOS.md`）。

---

## 在电脑上开发（当前环境，2026-10-05）

> 已从手机沙箱搬到 **Windows x64 电脑**。工具链安装与接线见 **`ENVIRONMENT.md` §1**。

```powershell
# 编译校验（Kotlin + Java + 资源 + Manifest）
.\gradlew.bat --no-daemon -Darch=arm64 :FCL:compileDebugKotlin :FCL:compileDebugJavaWithJavac `
  :FCL:processDebugResources :FCL:processDebugMainManifest

# 单测（34 项，Gradle 原生跑）
.\gradlew.bat --no-daemon :FCL:testFordebugUnitTest

# 打包（需先补 rootfs，见 ROOTFS.md）
.\gradlew.bat --no-daemon -Darch=arm64 :FCL:assembleFordebug
```

> `run-compile.sh` / `run-tests.sh` / `build-apk.sh` 是纯 POSIX sh（面向 arm64 Linux），
> 在**快照仓**里（电脑上与源码仓同一目录，手机 `/workspace/`）；Windows 上跑不了 —— 直接用上面的 Gradle 命令。

**曾最缺的一块（已解决）**：`FCL/src/main/assets/dsh/rootfs/rootfs.tar.xz`（300MB，不入 Git）——
2026-10-05 已从 0.1.1 参照 APK 里**无损取出并放回**（sha256 `5d762c30…`）。
补法见 `ROOTFS.md` 与 `README.md`「怎么把 rootfs 补回来」——**优先从已打好的 APK 里无损取出**。

---

## 2026-10-01 全面评估（第六轮）

| 材料 | 内容 |
|---|---|
| `reports/round6-optimization.md` | **第六轮：完整评估与优化报告**（问题清单 R-01~R-21、实测基准、真机验证清单、死代码清单） |
| `reports/mc-removal-impact-review.md` | 上一轮（MC 移除变更）的独立审查报告（M-01~M-04 + 补丁 A/B/C） |
| `reports/mc-removal.md` + `reports/mc-removal-review-brief.md` | 更早一轮：MC 移除变更记录 + 送审请求书 |
| 代码改动 | 提交 **`515270d`**：修 6 项（失效测试源集 / rootfs 解压可执行位 / 解压性能 18.5× / 硬链接解包 / 明文收口 / 备份规则 / 安装竞态 / 失败提示），`git revert 515270d` 可回滚 |
| **新增硬性要求** | **界面布局 / 按钮布局 / 整体主题一律跟 FCL 走** → 规格见 `design/app-shell.md` §2.5 |

> ⚠️ 第六轮把「**真机端到端仍跑不起来**」的头号嫌疑定到了平台层：
> **targetSdk ≥ 29 时 Android 10+ 禁止 execve 应用数据目录里的文件**。
> → **第七轮前已拍板**：**保持 targetSdk 34，用 `:proot-engine`（PROOT_LOADER）绕过**，不降级。

## 2026-10-02 第七轮评估与优化（最近一轮）

| 材料 | 内容 |
|---|---|
| `PROJECT_REVIEW_AND_OPTIMIZATION.md`（全文） + `reports/round7-review-and-optimization.md`（速览） | **第七轮：评估与优化报告**（问题清单 R7-01~R7-13、修复详情、验收清单） |
| 代码改动 | 提交 **`494f234`**：修 4 个 P1/P2 缺陷（删除竞态 / WebView 失败面板 / 安装超时文案 / 通知状态文案）+ §2.5 去 Material 收尾 + 日志环剪 + 删未引用文案 |
| 验证 | 编译 BUILD SUCCESSFUL；单测 23/23；脚本 18/18；§2.5.5 验收命令 1 通过；未打包 |
| 运行时底座 | **已决策：集成 oonid/pr `:proot-engine`，targetSdk 34 不降级**；唯一拦路项 = 子进程 spawn（见 `design/proot-engine-integration.md`） |
