# 项目评估与优化报告（第九轮）

> **项目**：DeepSeek Harness (dsh) 安卓启动器（FCL 改造）
> **审查对象**：`/workspace/FCL`（git HEAD `15ac9c6` + 本轮 3 个文件的改动，未打包）
> **审查时间**：2026-10-02
> **一句话结论**：前八轮已把并发/生命周期/资源机制兜底做得很扎实。**本轮命中第八轮遗留的两处"使用逻辑"口径问题——① `isReady()` 与 `preflight()` 对 proot 的判据不一致（假就绪）；② 下载页反复点击会累积空实例**；已做可编译验证的修复（含 4 个新单测），并梳理出若干遗留建议项。
> **验证级别**：编译 BUILD SUCCESSFUL / 单测 **27/27**（原 23 + 新增 4）/ 脚本一致性 18/18 全绿；平台/资源机制类结论仍标注「待真机确认」。

---

## 1. 项目概览

### 1.1 用途
在**未 root** 安卓手机上运行 **DeepSeek Harness（dsh）**（Node 版编码/对话 agent）：把 `@deepseek-ai/dsh` 装进 App 私有目录里的 proot Linux rootfs，用 WebView 承载其 Web UI，模型走 DeepSeek 云端 API。由 **Fold Craft Launcher（FCL）** 改造而来，删除全部 Minecraft 代码，只保留 fcllibrary UI 框架与少量通用工具。

### 1.2 技术栈与版本
| 项 | 值 |
|---|---|
| 语言/构建 | Kotlin + Java 17、AGP 8.13.2、Gradle 8.14.4、viewBinding |
| SDK | compileSdk 35 / targetSdk **34** / minSdk 26 |
| 模块 | `:FCL` + `:ZipFileSystem` |
| Native | jniLibs 仅预留 `libproot.so`/`libproot_loader.so` 打包位（当前 PLACEHOLDER） |
| 运行时底座 | proot（arm64）+ rootfs.tar.xz + 3 个 POSIX sh 脚本（仓库内均 PLACEHOLDER） |

### 1.3 目录结构（关键）
```
FCL/
├─ FCL/src/main/java/com/dsh/       ← 项目自己的代码（core + ui + shell）
│   ├─ core/   DshRuntime/Installer/Bootstrap/Instances/Credentials/Registry/LogBus/Paths/DownloadViewModel…
│   └─ ui/     DshWebViewActivity + DshSettingsActivity + shell(DshMainActivity/…) )视图
├─ FCL/src/main/java/com/tungsten/  ← FCL 遗产（fcllibrary/fclcore/mio 保留的 UI 框架）
├─ FCL/src/main/assets/dsh/         scripts/*.sh + proot/、rootfs/（占位）
└─ FCL/src/test/java/com/dsh/       单测源集（2 文件 / 27 用例）
```

### 1.4 运行 / 验证方式
| 目的 | 命令 | 本轮结果 |
|---|---|---|
| 编译（不打包） | `sh /workspace/run-compile.sh` | **BUILD SUCCESSFUL** |
| 单测 | `sh /workspace/run-tests.sh` | **TOTAL=27 FAILED=0** |
| 脚本一致性 | `sh /workspace/dsh-launcher-poc/scripts/test-scripts-posix.sh` | **PASS=18 FAIL=0** |
| 出 APK | `sh /workspace/build-apk.sh` | 未执行（按约定） |
| 真机 e2e | —— | 仍空白（缺 proot/rootfs 二进制 + 无设备） |

---

## 2. 使用逻辑梳理与完善

### 2.1 端到端使用路径（代码级，与上一轮一致）
```
① 冷启动：FCLApp.onCreate → FCLPath.loadPaths + DshPaths.loadPaths + DshInstances.init(异步 repair)
          → SplashActivity → DshMainActivity（横屏外壳，左菜单+ViewPager2+右面板）
② 底座：DshBootstrap.install → 空间检查→按 version 增量解压 scripts/proot/rootfs → verifySync
③ 下载 dsh：DshDownloadUI → DshRegistry.fetchVersionsCached(10min缓存,20s超时,断网回退)
          → (本轮新增去重) → DshInstances.create/复用 + DshInstaller.install
④ 配置：DshSettingsActivity/UI → Keystore AES-GCM 存 API key + 测试连接
⑤ 启动：DshLauncher.startInstance → DshRuntime.start（预检→单实例→proot→抓 token URL）
          → DshRuntimeService 前台服务保活 → DshWebViewActivity
⑥ 使用：WebView 加载 http://127.0.0.1:<port>/?token=…
⑦ 停止/清理：TERM→5s→KILL；删除实例=stopAndWait+删目录；冷启 adoptOrphan 认领孤儿
```

### 2.2 本轮「使用逻辑」层面的完善/发现
- **完善：第一次"装哪个版本"的决策**（9-02）：原来每次点下载页某版本都新建一个实例，重复点击/换版本会在
  列表里积累一堆 `NOT_INSTALLED` 空壳；现在 `installVersion()` 优先复用「已装同版本的 READY 实例」（不重装）
  或「从未装成的空壳」，确实需要才新建——实例列表不再被误点污染。
- **发现：`isReady()` 假就绪**（9-01）：bootstrap 横幅是否隐藏（`isReady()`）与启动/安装真正会失败的
  `preflight()` 判据不一致——`isReady()` 漏了 proot 二进制。已修（见 §4）。

---

## 3. 问题清单

> 级别：P0 阻断/安全/数据丢失；P1 严重；P2 一般；P3 建议。状态：`已修`=本轮已改并编译验证；`建议`=给方案未动；`沿用`=上一轮遗留。

| ID | 级别 | 类型 | 位置 | 问题 | 影响 | 证据 | 修复方案 | 状态 |
|---|---|---|---|---|---|---|---|---|
| **9-01** | P2 | 正确性/一致性 | `DshBootstrap.isReady()` + `install()` proot 分支 | `isReady()` 不校验 proot 二进制，而 `missingSummary()`/`ProotCommand.preflight()` 都把它当必要条件 → "横幅隐藏但一启动就报缺 proot"的假就绪；proot 分支又无文件存在性兜底 | 首页不提示、错误藏在"启动"之后；assets 方案 proot 可能被永久跳过解压 | 代码阅读（isReady 无 resolveProotBin 检查 + missingSummary 有） | ① isReady 补 `resolveProotBin(...).isFile`；② install proot 分支补"缺二进制即解压" | **已修** |
| **9-02** | P2 | 数据卫生/使用逻辑 | `DshDownloadViewModel.installVersion` | 每次点版本都 `create()` 新建实例；误点/换版本积累一堆空实例（承接 R8-08） | 实例列表被空实例污染 | 代码阅读 | 纯函数 `chooseInstanceToInstall` 复用同版本 READY/空壳，否则新建 | **已修** |
| 9-03 | P2 | 可靠性/资源 | Manifest + 全仓库 | `WAKE_LOCK` 权限已声明但**从未获取**任何 WakeLock；dsh 长驻 agent 在息屏/Doze 时可能被调度挂起或网络节流 | 长任务在后台可能"冻住"，WebUI 假死 | grep 无 `PowerManager`/`acquire` | 运行时 Running 期间持 PARTIAL_WAKE_LOCK（quit 时释放）；注意电池开销 | 建议（待决策） |
| 9-04 | P2 | 仓库卫生/CI | `.github/workflows/*` | 仍是 FCL 原版：build.yml 五 ABI 矩阵 + 引用 `FCL_KEYSTORE_PASSWORD`/`CURSE_API_KEY`/`OAUTH_API_KEY`；本仓库启用必失败（沿用 R8-05/R7-10） | CI 不可用/误导 | 静态 | 重写为 arm64 编译 + 单测 + 混淆 + checkstyle | 建议 |
| 9-05 | P3 | 死配置 | `FCLPath.java` | `CONTROLLER_DIR`/`SHARE_DIR`/`SHARED_COMMON_DIR` 仍指向 `/sdcard/FCL/...`，存储权限已删（沿用 R8-03） | 误导/死配置 | 代码阅读 | 单独提交清理（属 FCL 框架文件） | 建议 |
| 9-06 | P3 | 安全 | 仓库根 + build.gradle.kts | `key-store.jks`/`debug-key.jks` 提交进 public 仓库；release/debug 均 `isMinifyEnabled=false`（未混淆）（沿用 R8-04） | 口令泄露可伪造签名包；APK 易被逆向 | 静态 | 分发换 key 移出仓库，口令走 secret；release 开 R8 混淆 | 建议 |
| 9-07 | P3 | 可靠性 | `DshRegistry` | 仅内存缓存，无磁盘持久化；断网重启后下载页无历史（沿用 R8-09） | 离线体验差 | 代码阅读 | 缓存持久化到 `<filesDir>/dsh/registry-cache.json` | 建议 |
| 9-08 | P2 | UX | Manifest + WebView | 全局 `sensorLandscape` 锁横屏；聊天界面竖屏更易输入（沿用 R8-06） | 竖屏使用受限 | 静态 | WebView 页单独改 `sensor`（保留外壳横屏） | 建议（待决策） |
| 9-09 | P1→P0 | 平台/架构 | targetSdk=34 + filesDir | W^X 禁 execve 数据目录文件；已定 PROOT_LOADER 方案，**真机 e2e 未验证**；`RuntimeUtils.isLatest` 的 `getResourceAsStream("/assets/...")` 真机能否解析也未确认（沿用 R8-01 兜底已加/R8-10） | 真机可能底座跑不起来或首启解压被跳过 | round6 R-02 / R8-01 | 真机点亮 + 现场确认 isLatest 机制 | 待确认 |
| 9-10 | P3 | 可维护性 | `DshVersionListItem.formatSize` | 与 `DshPaths.formatSize` 各写一份体积格式化（仅首次调用约定略不同） | DRY 违反 | 代码阅读 | 复用 `DshPaths.formatSize`（注意 -1 语义） | 建议 |

---

## 4. 已完成的修复与优化

### 9-01 `isReady()` 补 proot 判据 + proot 分支"缺即解压"（P2 → 正确性/一致性）
- **文件**：`DshBootstrap.kt`
- **为什么**：`isReady()` 是否隐藏"准备运行时"横幅，与 `missingSummary()`/`ProotCommand.preflight()`（启动/安装真正失败入口）对 proot 的判据不一致——`isReady()` 漏了 proot。前者少条件 → "假就绪"，用户第一个错误出现点被推迟到点"启动"之后。同时第八轮只给 scripts 分支加了文件存在性兜底，proot 分支仍只靠 `isLatest`，assets 方案的 proot 一旦因资源解析不到被跳过，就永远不会被解压出来。
- **改了什么**：
  - `isReady()` 增加 `&& DshPaths.resolveProotBin(FCLPath.NATIVE_LIB_DIR).isFile`；
  - `install()` 的 proot 解压条件改为 `!isLatest(...) || prootMissing`；`prootMissing` 用 `resolveProotBin()`（jniLibs 优先）判定，避免 jniLibs 已就位时因 prootDir 无 `libproot.so` 而每次重复解压 assets 副本。
- **兼容性**：纯增量校验 + 幂等"缺即补"；jniLibs 主路径与"已正确就绪"设备零行为变化。
- **验证**：编译 BUILD SUCCESSFUL；单测 27/27。

### 9-02 下载页"反复点安装"去重（P2 → 数据卫生，客串 R8-08）
- **文件**：`DshDownloadViewModel.kt` + `DshCoreLogicTest.kt`
- **为什么**：`installVersion()` 每次 `create()` → 重复点击/换版本在列表积累空实例，且同一版本可被装 N 份。
- **改了什么**：
  - 新增纯函数 `DshDownloadViewModel.chooseInstanceToInstall(instances, version)`：① 已 READY 装过同版本 → 复用（不重装）；② 有 NOT_INSTALLED 空壳（如上次取消）→ 复用它；③ 都没有 → null（新建）。另抽取 `installVersion` 判断"已 READY 该版本"时跳过重装。
  - 新增单测 4 例（复用同版本 / 不同版本不复用 / 复用空壳 / 无可复用则新建）。
- **兼容性**：对"本来就要新建"的正常路径行为不变；纯去重。
- **验证**：单测 27/27 全绿。

---

## 5. 性能优化
- **结论**：前几轮已覆盖主要瓶颈（rootfs 解压 18.5×、日志合并刷新、环形缓冲 O(n)、DiffUtil、Keystore 移 IO、registry 10min 缓存等）。本轮改动不引入重负载；`chooseInstanceToInstall` 为 O(n) 一次性线性扫描，量级可忽略。
- **未做基准**：APK 体积 / 冷启动 / 内存——需打包 + 真机，按约定未打包。

## 6. 可靠性优化
| 项 | 内容 |
|---|---|
| 就绪一致性 | 9-01：`isReady()` 与 `preflight()` 对 proot 判据对齐，消除"假就绪"；proot 分支补"缺即解压"兜底 |
| 实例卫生 | 9-02：下载页去重，杜绝误点累积空实例 |
| 沿用 | 脚本/rootfs 文件存在性兜底、状态机+归属校验、孤儿 pid/cmdline 双身份校验、启动 180s/安装 30min 看门狗、SingleFlight、日志脱敏+落盘轮转、`stopAndWait`、单实例策略 |
| 仍缺 | 安装重试退避、WakeLock（9-03）、下载缓存磁盘持久化（9-07）、真机故障注入 |

## 7. 代码质量评估
| 维度 | 评分 | 说明 |
|---|---|---|
| 正确性 | 9/10 | 本轮修掉两处使用逻辑口径问题；并发/生命周期很扎实 |
| 可读性 | 9/10 | 注释讲"为什么"、命名清晰；本轮新增函数注释明确 |
| 可维护性 | 8/10 | `chooseInstanceToInstall` 抽纯函数可单测；剩余扣分在死配置(9-05)/CI(9-04) |
| 性能 | 8/10 | 无新增瓶颈 |
| 可靠性 | 8.5/10 | 就绪判据已统一；主要风险仍在平台(W^X)与真机 |
| 安全性 | 8/10 | Keystore+环境变量注入+日志脱敏+明文收口；release 未混淆/仓库含 jks(9-06) 扣分 |
| 测试 | 7/10 | 27/27；新增下载去重单测；`isLatest` 资源机制仍无 JVM 覆盖（待真机） |

## 8. 安全与依赖评估
- **输入校验**：端口 0~65535、名称非空、proot 参数全 argv 数组、环境变量白名单（挡 LD_PRELOAD）。
- **密钥**：Keystore AES-GCM + AAD=instanceId；运行期只进子进程环境变量；日志脱敏。
- **网络暴露面**：dsh 只绑 127.0.0.1；明文收口到回环（NSC）。
- **新增风险提示**：`WAKE_LOCK` 已声明但未使用（9-03）；`.github/workflows` 引用的 secrets 一旦启用会失败（9-04）；仓库内签名 jks + release 未混淆（9-06）。依赖 CVE 仍需联网核对（沿用）。

## 9. 测试与验证
| 命令 | 结果 |
|---|---|
| `sh /workspace/run-compile.sh` | **BUILD SUCCESSFUL** |
| `sh /workspace/run-tests.sh` | **TOTAL=27 FAILED=0**（原 23 + 新增 4） |
| `sh /workspace/dsh-launcher-poc/scripts/test-scripts-posix.sh` | **PASS=18 FAIL=0** |
| 未跑 | 打包（约定）、真机 e2e、Gradle 在线单测、instrumented 测试 |

**建议补充**：`RuntimeUtils.isLatest` 的资源解析单测（需真机/改为注入 asset 读取器）；`DshBootstrap` 解压链路的合成 tar.xz 单测（含 x 位/硬链接/符号链接）；`DshInstaller` 竞态单测。

## 10. 变更文件与 diff 摘要
```
 3 files changed（+86 −5），全部可编译/可单测验证
 FCL/src/main/java/com/dsh/core/DshBootstrap.kt           isReady 补 proot 判据；install proot 分支"缺即解压"
 FCL/src/main/java/com/dsh/core/DshDownloadViewModel.kt   新增 chooseInstanceToInstall；installVersion 去重
 FCL/src/test/java/com/dsh/DshCoreLogicTest.kt            新增 4 个下载去重单测
 docs/(工作副本)：CHANGELOG.md、LESSONS.md、reports/round9-review-and-optimization.md、
                  PROJECT_REVIEW_AND_OPTIMIZATION.md（本轮全文）
```
**新增/删除依赖**：无。

## 11. 风险、兼容性与后续建议
### 11.1 头号未决（沿用）
- targetSdk 34 + filesDir 的 W^X 限制 + `isLatest` 的 `getResourceAsStream("/assets/...")` 能否在真机解析，
  仍是最大不确定项（见 §3 9-09）——**不要只凭机制推理下死判断**。先做能不改工作路径的防御（第八/九轮已加），真机首装后确认。

### 11.2 兼容性影响
- `isReady()` 增量校验：仅让"横幅是否提示"与真实可启动性一致；对正确就绪设备零影响。
- `install()` proot 分支补兜底：幂等"缺即补"，jniLibs 主路径不受影响。
- `installVersion()` 去重：正常"新建"路径不变，仅减少重复/误点的空实例。

### 11.3 后续优化路线
1. 真机点亮 + 确认 `isLatest` 机制（一切前提）；
2. 决策并实现 WakeLock（9-03）、WebView 竖屏（9-08）；
3. CI 重写（9-04）、死配置清理（9-05）、依赖清账、release 混淆 + 签名外移（9-06）；
4. 下载缓存磁盘持久化（9-07）、`DshVersionListItem` 去重格式函数（9-10）。

## 12. 验收清单
- [x] 项目可编译（run-compile.sh BUILD SUCCESSFUL）
- [x] 单测全绿（27/27）
- [x] 脚本全绿（18/18）
- [ ] 核心流程端到端可运行（**未验证**：缺 proot/rootfs + 无设备 + 9-09 未决）
- [x] 无新增严重问题（静态 + 编译 + 单测覆盖）
- [x] 性能结论明确（本轮无新增瓶颈）
- [x] 可靠性结论明确（修掉假就绪 + 空实例污染）
- [x] 文档完整（本报告 + CHANGELOG + LESSONS + 同步仓库）

---

### 附：审查方法与局限
- **方法**：通读 `com/dsh/**` 全部源码 + 关键 FCL 遗产（FCLApp/RuntimeUtils/FCLPath/SplashActivity）+ Manifest/Gradle/CI/资源/测试；全仓引用扫描；编译 + 单测 + 脚本三套动态验证。
- **局限**：无设备、无 proot/rootfs 二进制、Google Maven 不可达；所有"真机行为"类结论标注「待真机确认」，未伪造运行结果；本轮"已修"项均在沙箱内经可复现代码 + 编译/单测验证。