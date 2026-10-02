# 项目评估与优化报告（第八轮）

> **项目**：DeepSeek Harness (dsh) 安卓启动器（FCL 改造）
> **审查对象**：`/workspace/FCL`（git HEAD `494f234` + 本轮改动，未打包）
> **审查时间**：2026-10-02
> **一句话结论**：前七轮已把核心并发/生命周期/去 Material 做得很扎实。**本轮作为独立复审，主要命中前七轮未覆盖的「资源访问机制不一致导致首启解压可能被跳过」这一静默失败路径，以及若干安全卫生/使用逻辑细节**；已做两处可编译验证的防御性修缮，其余以建议给出。
> **验证级别**：编译（BUILD SUCCESSFUL，1m31s）/ 单测（23/23）/ 脚本一致性（18/18）全绿；平台/资源机制类结论仍标注「待真机确认」。

---

## 1. 项目概览

### 1.1 用途
在**未 root** 安卓手机上运行 **DeepSeek Harness（dsh）**（Node 版编码/对话 agent）：把 `@deepseek-ai/dsh` 装进 App 私有目录里的 proot Linux rootfs，用 WebView 承载其 Web UI，模型走 DeepSeek 云端 API。由 **Fold Craft Launcher（FCL）** 改造而来，删除全部 Minecraft 代码，只保留 fcllibrary UI 框架与少量通用工具。

### 1.2 技术栈与版本
| 项 | 值 |
|---|---|
| 语言/构建 | Kotlin 2.4.10 + Java 17、AGP 8.13.2、Gradle 8.14.4、viewBinding |
| SDK | compileSdk 35 / targetSdk **34** / minSdk 26 |
| 模块 | `:FCL` + `:ZipFileSystem` |
| Native | 无（jniLibs 仅预留 `libproot.so`/`libproot_loader.so` 打包位，当前为 PLACEHOLDER） |
| 依赖 | gson、commons-compress、xz、coroutines、lifecycle、material、commons-io 等 |
| 运行时底座 | proot（arm64）+ rootfs.tar.xz + 3 个 POSIX sh 脚本（仓库内均为 PLACEHOLDER） |

### 1.3 目录结构（关键）
```
FCL/
├─ FCL/src/main/java/com/dsh/       ← 项目自己的代码（core 18 + ui 7 + shell 9）
│   ├─ core/   DshRuntime/Installer/Bootstrap/Instances/Credentials/Registry/LogBus/Paths 等
│   └─ ui/     DshWebViewActivity + DshSettingsActivity + shell(DshMainActivity/…)
├─ FCL/src/main/java/com/tungsten/  ← FCL 遗产（fcllibrary/fclcore/mio 保留的 UI 框架）
├─ FCL/src/main/res/                19 布局 / values(+zh)
├─ FCL/src/main/assets/dsh/         scripts/*.sh + proot/、rootfs/（占位）
└─ FCL/src/test/java/com/dsh/       单测源集（2 文件 / 23 用例）
```

### 1.4 运行 / 验证方式
| 目的 | 命令 | 本轮结果 |
|---|---|---|
| 编译（不打包） | `sh /workspace/run-compile.sh` | **BUILD SUCCESSFUL**（1m31s） |
| 单测 | `sh /workspace/run-tests.sh` | **TOTAL=23 FAILED=0** |
| 脚本一致性 | `sh /workspace/dsh-launcher-poc/scripts/test-scripts-posix.sh` | **PASS=18 FAIL=0** |
| 出 APK | `sh /workspace/build-apk.sh` | 未执行（按约定） |
| 真机 e2e | —— | 仍空白（缺 proot/rootfs + 无设备） |

---

## 2. 使用逻辑梳理与完善

### 2.1 端到端使用路径（代码级）
```
① 冷启动：FCLApp.onCreate → FCLPath.loadPaths + DshPaths.loadPaths + DshInstances.init(异步 repair)
          → SplashActivity(sensorLandscape) → DshMainActivity（横屏外壳，左菜单+ViewPager2+右面板）
② 底座（可选/引导横幅）：DshBootstrap.install → 空间检查→按 version 增量解压 scripts/proot/rootfs
          → verifySync 真跑一次 probe.sh → Done/Failed(带原因)
③ 下载 dsh：DshDownloadViewModel → DshRegistry.fetchVersionsCached(10min 缓存, 20s 超时, 断网回退)
          → 选版本 → DshInstances.create + DshInstaller.install（proot 内 npm install）
④ 配置：DshSettingsActivity → 存 API key（Keystore AES-GCM, AAD=instanceId）+ 测试连接
⑤ 启动：DshLauncher.startInstance → DshRuntime.start（预检→单实例→密钥走子进程环境变量→proot -r rootfs→抓 token URL）
          → DshRuntimeService 前台服务保活 → DshWebViewActivity
⑥ 使用：WebView 加载 http://127.0.0.1:<port>/?token=…（cookie 持久化, 401 自动重载一次）
⑦ 停止/清理：TERM→5s→KILL；删除实例=stopAndWait+删目录+清凭据；冷启 adoptOrphan 认领孤儿
```

### 2.2 数据布局（全在 App 私有目录）
```
<filesDir>/dsh/
  rootfs/  scripts/(3 个 sh)  npm-cache/  logs/runtime.log(1MB 轮转)  instances.json(原子写)
  instances/<id>/ node_modules(≈300MB)+home/+workspace/+dsh.pid+credentials.enc
<cacheDir>/dsh/tmp 、tarballs
```
宿主 `<filesDir>/dsh` 经 proot `--bind` 暴露为 rootfs 内 `/opt/dsh`。

### 2.3 本轮「使用逻辑」层面的发现
- **首启解压存在静默跳过风险（R8-01）**：底座脚本/rootfs 的"是否需解压"判定依赖 `RuntimeUtils.isLatest`，
  而它与实际解压用的是**两套资源访问机制**（见 §3）。一旦版本比对在任何环境失效返回"已是最新"，
  首次解压会被永久跳过 → 实例永远起不来，而 `isReady()` 却可能误报就绪。这是使用逻辑里最隐蔽的一环。
- **下载页每点一次版本就新建一个实例（R8-08）**：`installVersion()` 先 `DshInstances.create()` 再 `install()`；
  若用户误点/换版本，会留下多个空实例（NOT_INSTALLED/BROKEN），无去重提示。

### 2.4 仍然缺失的使用说明（建议补）
| 缺口 | 建议 |
|---|---|
| 真机操作手册（首次跑通 checklist） | 新增 `REALDEVICE.md`（复用 round6 §11.3 清单） |
| `rootfs.tar.xz` / `libproot.so` 仍 PLACEHOLDER | 真机前置条件，见 §11 |
| `.github/workflows` 仍 FCL 原版 | 重写为 arm64 编译 + 单测（见 R8-05） |
| 下载页磁盘缓存 | 断网重启后无历史列表（见 R8-09） |

---

## 3. 问题清单

> 级别：P0 阻断/安全/数据丢失；P1 严重 bug/明显性能瓶颈；P2 一般；P3 建议。
> 状态：`已修`=本轮已改并编译验证；`建议`=本轮给出方案未动；`沿用`=上一轮遗留。

| ID | 级别 | 类型 | 位置 | 问题 | 影响 | 证据 | 修复方案 | 状态 |
|---|---|---|---|---|---|---|---|---|
| **R8-01** | P1 | 正确性/使用逻辑 | `RuntimeUtils.isLatest` + `DshBootstrap` | 版本比对用 `Class.getResourceAsStream("/assets/...")`，解压用 `context.getAssets().open("dsh/...")`——两套机制。前者若在任何环境解析不到（返回"已是最新"），`DshBootstrap.install` 会永远跳过**首次**解压 → scripts/start-dsh.sh 缺失，实例永远起不来；`isReady()` 也可能误报就绪 | 静默失败：装完像"好了"但一启动就"缺少脚本" | 代码阅读（isLatest 的 `stream==null return true` + 与 install 的 srcDir 前缀不一致 `/assets/` vs 无前缀） | ①先加"文件缺失即补解压"的幂等兜底（**本轮已加**）；②真机确认 isLatest 是否可解析，若不可则把版本比对也改为 `context.getAssets()` | **已修(兜底) + 待真机确认** |
| **R8-02** | P3 | 安全卫生 | `AndroidManifest.xml` | `android:usesCleartextTraffic="true"` 与 NSC 并存；API 24+(minSdk 26) 起 NSC 优先，此开关为死配置，且误导"全局允许明文" | 语义歧义/安全卫生 | 静态（NSC 注释自证"被忽略"） | 删除该行，以 NSC 为唯一真源 | **已修** |
| **R8-03** | P3 | 死配置 | `FCLPath.java` | `CONTROLLER_DIR`/`SHARE_DIR`/`SHARED_COMMON_DIR` 仍指向 `/sdcard/FCL/...`；存储权限已删，`init()` mkdir 静默失败；dsh 全流程未用到这些路径 | 误导/死配置 | 代码阅读（dsh 用 `DshPaths` 私有目录，不触碰这些字段） | 单独提交清理（属 FCL 框架文件，谨慎删） | 建议 |
| **R8-04** | P3 | 安全 | 仓库根 | `key-store.jks`/`debug-key.jks` 提交进仓库（public 仓库） | 若口令泄露可伪造签名包 | 静态 | 仅适合侧载自用；对外分发换 key 并移出仓库（口令走 GitHub secret） | 建议 |
| **R8-05** | P2 | 仓库卫生 | `.github/workflows/*` | FCL 原版 CI：build.yml 五 ABI 矩阵 + 引用 `FCL_KEYSTORE_PASSWORD`/`CURSE_API_KEY`/`OAUTH_API_KEY` secrets，本仓库一旦启用必失败 | CI 不可用/误导 | 静态（沿用 R7-10） | 重写为 arm64 编译 + 单测 + checkstyle | 建议 |
| **R8-06** | P2 | UX/体验 | Manifest + UI | 全局 `sensorLandscape` 锁横屏；dsh Web UI 是聊天界面，竖屏更便于输入 | 手机竖屏使用受限于横屏 | 静态 | 可考虑 WebView 页改 `sensor`（保留外壳横屏） | 建议（待用户决策） |
| **R8-07** | P3 | 性能 | `DshLogsUI.render` | 每 revision（~5Hz）把 ≤2000 行 `joinToString` 设进 TextView；revision 已防重复刷新，纯大文本重设开销 | 大日志下 UI 刷新开销 | 代码阅读 | 超长日志时用 `Spannable`/尾部追加；当前可接受 | 建议 |
| **R8-08** | P2 | 数据卫生 | `DshDownloadViewModel.installVersion` | 每点一次版本先 `create()` 再 `install()`；误点/换版本会留多个空实例 | 实例列表被空实例污染 | 代码阅读 | 同一版本单飞/去重；或创建前二次确认 | 建议 |
| **R8-09** | P3 | 可靠性 | `DshRegistry` | 仅内存缓存，无磁盘持久化；断网重启后下载页无任何历史 | 离线体验差 | 代码阅读 | 缓存持久化到 `<filesDir>/dsh/registry-cache.json` | 建议 |
| R8-10 | P0 | 平台/架构 | targetSdk=34 + filesDir | W^X 禁 execve 数据目录文件；已定 PROOT_LOADER 方案，**真机端到端未验证** | 真机可能底座跑不起来 | round6 R-02 | 待你决策 + 真机点亮 | 待确认 |

---

## 4. 已完成的修复与优化

### R8-01 首启解压「文件存在性」兜底（P1→P2）
- **文件**：`DshBootstrap.kt`
- **为什么**：`RuntimeUtils.isLatest`（版本比对）与 `RuntimeUtils.install`（实际解压）分别走
  `Class.getResourceAsStream("/assets/...")` 与 `context.getAssets().open("dsh/...")`。二者前缀不一致
  （一个带 `/assets/`，一个不带），且 `getResourceAsStream` 对 Android `assets/` 未必可解析——一旦它返回
  "已是最新"，首次解压被永久跳过，这是前七轮未覆盖的静默失败路径。
- **改了什么**：
  - `isReady()` 增加 `&& File(SCRIPTS_DIR, "start-dsh.sh").isFile`；
  - `install()` 的脚本解压条件改为 `!isLatest(...) || !start-dsh.sh.isFile`（缺文件即补解压）。
- **兼容性**：纯幂等"缺文件即补"的防御；`isLatest` 正常工作时不触发额外解压，行为不变。
- **影响面**：仅底座脚本（scripts）首次/修复解压；rootfs 分支已有 `rootfsLooksUsable()` 防护，proot 走 jniLibs。
- **验证**：编译 BUILD SUCCESSFUL。`isLatest` 是否在真机可解析仍待真机确认。

### R8-02 移除 Manifest 冗余明文开关（P3）
- **文件**：`AndroidManifest.xml`
- **改了什么**：删除 `android:usesCleartextTraffic="true"`。
- **为什么**：minSdk 26，API 24+ NSC 优先，此开关为死配置且传达"全局明文"的错误语义。
- **验证**：`processDebugMainManifest`/`processDebugManifest` 通过，编译全绿。

---

## 5. 性能优化
- **结论**：前几轮已做 rootfs 解压 18.5×、lazylogic 5Hz 合并、日志环剪 O(n)→O(n)、DiffUtil、Keystore 移 IO、注册表 10min 缓存等。
- **本轮**：无新增重负载；仅 R8-07（日志 TextView 大文本重设）标注为可接受的开销，未改动（revision 防重复已生效）。
- **未做基准**：APK 体积 / 冷启动 / 内存（需打包 + 真机，按约定未打包）。

## 6. 可靠性优化
| 项 | 内容 |
|---|---|
| 首启解压 | R8-01 兜底：脚本缺失即补解压；`isReady` 不再只信版本号 |
| 明文策略 | R8-02 以 NSC 为唯一真源（base 禁明文 + 回环放行） |
| 已有(沿用) | 状态机+归属校验、孤儿 pid/cmdline 双身份校验、启动 180s/安装 30min 看门狗、SingleFlight、日志脱敏+落盘轮转、单实例策略、删除 stopAndWait |
| 仍缺 | 安装重试退避、真机故障注入、崩溃上报、R8-09 下载缓存持久化 |

## 7. 代码质量评估
| 维度 | 评分 | 说明 |
|---|---|---|
| 正确性 | 8/10 | 前七轮并发/生命周期很扎实；本轮命中资源机制类静默失败（R8-01），已加防御 |
| 可读性 | 9/10 | 注释讲"为什么"、命名清晰 |
| 可维护性 | 7.5/10 | `ProotCommand`/`DshPaths`/`DshPageUI` 单点化；扣分在死配置(R8-03)与 CI(R8-05) |
| 性能 | 8/10 | 前几轮已覆盖主要瓶颈；本轮无新增 |
| 可靠性 | 8/10 | 见 §6；主要风险仍在平台(W^X)与真机行为 |
| 安全性 | 8/10 | Keystore+环境变量注入+日志脱敏+明文收口+WebView 收紧；release 未混淆、仓库含签名 jks 扣分 |
| 测试 | 6/10 | 单测质量高（含占位符契约）；无真机 e2e；`isLatest` 机制无测试覆盖 |

## 8. 安全与依赖评估
- **输入校验**：端口 0~65535、名称非空、proot 参数全 argv 数组、环境变量白名单（挡 LD_PRELOAD）。
- **密钥**：Keystore AES-GCM + AAD=instanceId；运行期只进子进程环境变量；日志脱敏。
- **网络暴露面**：dsh 只绑 127.0.0.1；明文策略收口到回环（NSC）。
- **依赖**：`junrar/jsoup/tomlj/constantPoolScanner/opennbt/nanohttpd/tapTargetView` 等疑似 MC 遗产仍被引用（沿用 R7-13，待联网核对 CVE）；无法离线核对漏洞。
- **签名**：仓库内 `key-store.jks`/`debug-key.jks` 仅适合侧载自用（R8-04）。

## 9. 测试与验证
| 命令 | 结果 |
|---|---|
| `sh /workspace/run-compile.sh` | **BUILD SUCCESSFUL**（1m31s，5 executed / 29 up-to-date） |
| `sh /workspace/run-tests.sh` | **TOTAL=23 FAILED=0** |
| `sh /workspace/dsh-launcher-poc/scripts/test-scripts-posix.sh` | **PASS=18 FAIL=0** |
| 未跑 | 打包（约定）、真机 e2e、Gradle 在线单测、instrumented 测试 |

**建议补充**：`RuntimeUtils.isLatest` 的资源解析单测（JVM 下无法覆盖 Android assets，需真机/或改为注入 asset 读取器）；解压单测（合成 tar.xz 断言 x 位/硬链接/符号链接）；`DshInstaller` 竞态单测。

## 10. 变更文件与 diff 摘要
```
 2 files changed（+2 处，全部可编译验证）
 FCL/src/main/AndroidManifest.xml                    删除 android:usesCleartextTraffic="true"
 FCL/src/main/java/com/dsh/core/DshBootstrap.kt      isReady 增加 start-dsh.sh 存在性校验；
                                                     install() 脚本解压条件加"缺文件即补解压"
 docs/(工作副本)：CHANGELOG.md、reports/round8-review-and-optimization.md、PROJECT_REVIEW_AND_OPTIMIZATION.md
```
**新增/删除依赖**：无。

## 11. 风险、兼容性与后续建议
### 11.1 头号未决：真机端到端 + `isLatest` 资源机制
- targetSdk 34 + filesDir 的 W^X 限制（沿用 round6 R-02）仍是最大不确定项。
- **R8-01**：`RuntimeUtils.isLatest` 的 `Class.getResourceAsStream("/assets/...")` 在真机是否能解析，
  需真机首次安装验证——**不要只凭机制推理下死判断**（本项目已因机制误判过两次）。本次先加了不改正常工作路径的防御兜底；
  若真机确认不可解析，应把版本比对统一改为 `context.getAssets().open("dsh/.../version")`（需给 `isLatest` 传 Context 或改为实例方法）。
### 11.2 兼容性影响
- Manifest 删除属性：minSdk 26 下 NSC 优先，无行为变化。
- `DshBootstrap` 改动：仅"缺文件时补解压"，对已正确解压的设备零影响。
### 11.3 后续优化路线
1. 真机点亮（一切前提）+ 确认 `isLatest` 机制；
2. CI 重写（R8-05）、死配置清理（R8-03）、依赖清账（R7-13）；
3. 下载缓存持久化（R8-09）、下载页空实例去重（R8-08）；
4. R7-12 开 R8、R7-08 `MaterialAlertDialogBuilder`→`FCLAlertDialog` 分批替换。

## 12. 验收清单
- [x] 项目可编译（run-compile.sh BUILD SUCCESSFUL）
- [x] 单测全绿（23/23）
- [x] 脚本全绿（18/18）
- [ ] 核心流程端到端可运行（**未验证**：缺 proot/rootfs + 无设备 + R8-10 未决）
- [x] 无新增严重问题（静态 + 编译 + 单测覆盖；R8-01 已加防御兜底）
- [x] 性能结论明确（本轮无新增瓶颈）
- [x] 可靠性结论明确（补首启解压兜底 + 明文卫生）
- [x] 文档完整（本报告 + CHANGELOG + LESSONS + 同步仓库）

---

### 附：审查方法与局限
- **方法**：通读 `com/dsh/**` 全部源码 + 可达 FCL 遗产（FCLApp/SplashActivity/RuntimeUtils/FCLPath）+ 脚本/资源/Manifest/Gradle/CI；全仓引用扫描；编译/单测/脚本三套动态验证。
- **局限**：无设备、无 proot/rootfs 二进制、Google Maven 不可达；涉及真机运行时行为（assets 资源解析、SELinux、WebView 细节）均标注「待真机确认」，未伪造运行结果；本轮"已修"项均以可复现方式在沙箱内编译验证。
