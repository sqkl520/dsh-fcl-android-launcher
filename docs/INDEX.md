# dsh 安卓启动器 —— 文档总览

> ## 📦 仓库
> **GitHub**：<https://github.com/sqkl520/dsh-fcl-android-launcher>
> 本地仓库：`/workspace/FCL`（基线为 FCL 上游 `f4f2624`）
> 本项目**基于 [Fold Craft Launcher](https://github.com/FCL-Team/FoldCraftLauncher)（GPL-3.0）改造**，
> 同样以 GPL-3.0 发布。
>
> **当前版本：`0.1.0-SNAPSHOT`** —— 待"能正常启动 / 下载 / 管理 dsh"后才标 `1.0.0`。


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
| `reports/mc-removal-review-brief.md` | **审查请求书**：送审背景（仓库/版本/技术栈/运行方式/业务目标/已知限制）+ **影响面清单** + 期望审查方回答的问题 | 要送审时先看这份 |

分类规则：**顶层**放长期常读的三份（总纲 / 规划 / 打包）；`design/` 放功能设计；
`reports/` 放每轮审查报告（新一轮就往这里加 `roundN-*.md`，顶层不再变乱）。

---

## 当前状态

> ⚠️ **2026-09-30 重大变更**：已完成**移除全部 Minecraft 相关代码与资源**，本项目现在是
> **纯 dsh 启动器**（只保留 FCL 的 UI 框架与少量通用工具）。**本目录其余文档尚未全面反映此变更。**
> 变更详情见 **`reports/mc-removal.md`**（供独立审查）。

- **代码**：全仓库 **494 个源文件 / 71,623 行 / 3.7 MB**
  > **修正（2026-10-01，见 `reports/mc-removal-impact-review.md` §1.4 D-7）**：数字与实测不符。
  > 实测 `find FCL/src/main/java ZipFileSystem/src -name "*.java" -o -name "*.kt" | wc -l` = **506**，
  > `xargs wc -l | tail -1` = **77,190 行**，`du -sh FCL/src/main/java` = **3.7M**（体积一项正确）。
  > 差值 **12** 恰好等于 `ZipFileSystem/` 的源文件数 —— 即"494"是**不含 ZipFileSystem** 的口径，
  > 却与下面同段落的分项表（把 `ZipFileSystem/ 12` 也列了进去）自相矛盾。请统一口径。
  > 分项实测：`com/dsh` 25、`fcllibrary` 60、`fclcore` 394、`fcl` 9、`fclauncher/utils` 2、
  > `mio/util` 4、`ZipFileSystem` 12 —— **前六项合计 494**，故 494 应表述为"不含 ZipFileSystem"。
  - `com/dsh/` 25 个（dsh 启动器主体，`core` 18 + `ui` 7）
  - `com/tungsten/fcllibrary/` 60 个（UI 框架，保留 FCL 风格）
  - `com/tungsten/fclcore/` 394 个（`fakefx` 292 / `util` 85 / `task` 12 / `event` 5）
  - `com/tungsten/fcl/` 9 个（`FCLApp` / `SplashActivity` / `RuntimeUtils` 等）
  - `com/tungsten/fclauncher/utils/` 2、`com/mio/util/` 4、`ZipFileSystem/` 12
- **资源**：res **1.8 MB**（19 布局 / 17 drawable / 2 anim / 字符串 values 169 + values-zh 166）、
  assets **仅 `dsh/`**（70 KB）、**无 native**（jniLibs / jni / jreAssets / libs.aar 全删）
- **Gradle 模块**：只剩 `:FCL` + `:ZipFileSystem`
- **脚本**：`FCL/src/main/assets/dsh/scripts/` 3 个（严格 POSIX sh）；
  `dsh-launcher-poc/scripts/` 6 个参考/测试脚本
- **验证**：`sh /workspace/run-compile.sh`（Kotlin+Java+资源+Manifest）**BUILD SUCCESSFUL，0 error**；
  单测 **23/23**；脚本一致性 **18/18**
- **入口**：`SplashActivity` → 直接进 `DshInstancesActivity`（**不再有** MC 运行时门禁 / EULA / MC 主界面）
- **待办**：
  1. **【硬性要求】UI 跟 FCL 走**（2026-10-01 新增）：界面布局 / 按钮布局 / 整体主题一律使用 fcllibrary 控件与
     `ThemeEngine` 主题（当前 8 个 dsh 布局仍**全是 Material 控件、0 个 FCL 控件**，需替换）。
     要求拆解 + 控件替换表 + FCL 写法范式 + 验收命令见 **`design/app-shell.md` §2.5**；
     落地时机 = 随外壳改造**阶段 2**（页面从 Activity 改 FCLCommonUI 时一并重写布局，避免改两遍）。
  2. **同步更新文档**（本目录多份文档仍按旧的"FCL + MC"形态描述）
     > 部分已完成：2026-10-01 已在 `reports/mc-removal.md` §5.3/§9.4.4/§9.4.6 与
     > `reports/mc-removal-review-brief.md` §0/§7.B/§7.C/§7.D 加修正批注（共 8 处，D-1~D-8）。
     > 其余文档（`PLAN.md` / `PACKAGING.md` / `design/*` / `round2~5`）**仍然过时**。
  3. **决定 R-02**（`targetSdk 34` 禁止 exec 数据目录里的文件）—— 真机点亮的前提：
     方案 A `targetSdk = 28`（1 行，侧载自用可行）vs 方案 B 改架构。
     见 `reports/round6-optimization.md` §11.1。
  4. 外壳改造阶段 1~3（新外壳骨架 / 五页迁移 / 右侧面板），见 `design/app-shell.md`
  5. 真机端到端：准备 `proot` 二进制 + `rootfs.tar.xz` → 出 APK → 联调（见 `PLAN.md` §8.5）
     > ⚠️ 这是**最大的空白**：`libproot.so`/`libproot_loader.so` 与 `rootfs.tar.xz` 至今不存在于仓库
     > （只有 `PLACEHOLDER.txt`），**从未出过 APK、从未上过真机**。
  6. 可选：清理 `reports/mc-removal.md` §9.1 与 `reports/round6-optimization.md` §11.2 的死代码
- **已修（2026-10-01，见 `reports/mc-removal-impact-review.md` §11 补丁 A/B）**：
  1. **M-01（P1）** `FCLPath.loadPaths()` 上提到 `FCLApp.onCreate`，并删除 `SplashActivity.kt` 中的重复调用
     —— 消除"必须先经过启动页"的隐式依赖（通知栏 PendingIntent 冷启动会绕过启动页）
  2. **M-02（P2）** `FCLPath.LOG_DIR` 由 `/sdcard/FCL/log` 改为 `context.getDir("log", 0)`
     —— 原路径在删权限后不可写，`fcl.log` 永远建不出来（异常被静默吞掉）
  > 验证：clean build `34/34 executed，0 error`；单测 `TOTAL=23 FAILED=0`。
- **出 APK**：`sh /workspace/build-apk.sh`（**改完代码默认不打包**，等明确说"打包测试"再出包）

---

## 代码在哪

| 位置 | 说明 |
|---|---|
| `FCL/` | App 主仓库（FoldCraftLauncher 基座，Kotlin/Java）。启动器代码在 `FCL/src/main/java/com/dsh/` |
| `dsh/` | dsh 上游仓库（只读参考，用来确认行为与 API） |
| `poc/` | 真实已安装的 dsh + Node 环境（用于实测行为、跑脚本验证） |
| `docs/` | **本目录**，全部文档 |

---

## 到电脑后的最短路径

1. 准备两个大文件（见 `PACKAGING.md`）：
   proot 二进制 → 放 `FCL/src/main/jniLibs/arm64-v8a/`（推荐）；
   `rootfs.tar.xz` → 放 `FCL/src/main/assets/dsh/rootfs/`。
2. 编译：`./gradlew :FCL:assembleDebug`（需要 Android SDK + NDK + JDK17）。
3. 装机 → 首启解压（自动）→ 运行时自检 → 下载页装 dsh → 列表页启动 → WebView 出界面。
4. 逐项对照 `PLAN.md` §8.5 的联调步骤与排查清单。

> 注意：编译需要 Android SDK / NDK / Gradle 工具链，**手机上是做不了的**，
> 所以这一步只能等换到电脑。手机上这段时间适合读文档、改代码、准备两个大文件。

---

## 2026-10-01 全面评估（本轮）

| 材料 | 内容 |
|---|---|
| `reports/round6-optimization.md` | **第六轮：完整评估与优化报告**（问题清单 R-01~R-21、实测基准、真机验证清单、死代码清单） |
| `reports/mc-removal-impact-review.md` | 上一轮（MC 移除变更）的独立审查报告（M-01~M-04 + 补丁 A/B/C） |
| `reports/mc-removal.md` + `reports/mc-removal-review-brief.md` | 更早一轮：MC 移除变更记录 + 送审请求书 |
| 代码改动 | 提交 **`515270d`**：修 6 项（失效测试源集 / rootfs 解压可执行位 / 解压性能 18.5× / 硬链接解包 / 明文收口 / 备份规则 / 安装竞态 / 失败提示），`git revert 515270d` 可回滚 |
| **新增硬性要求** | **界面布局 / 按钮布局 / 整体主题一律跟 FCL 走** → 规格见 `design/app-shell.md` §2.5 |

> ⚠️ 本轮把「**真机端到端仍跑不起来**」的头号嫌疑定到了平台层：
> **targetSdk ≥ 29 时 Android 10+ 禁止 execve 应用数据目录里的文件**——proot 二进制已用 jniLibs 规避，
> 但 rootfs 内的 `/bin/sh`、`node` 同样受限。决策项与两条可选路线见报告 **§11.1**（需先拍板再真机验证）。
