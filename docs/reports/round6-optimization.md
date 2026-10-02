# 项目评估与优化报告 —— dsh 安卓启动器（FCL 改造）

> **审查对象**：`/workspace/FCL`（git `a62ed0d`，审查后修复提交 `515270d`）
> **审查时间**：2026-10-01
> **本轮结论一句话**：代码主体质量已经很高（前五轮加固的成果），本轮**实测发现并由代码修复了 1 个"必失败"级正确性缺陷（rootfs 解压无可执行位）
> + 1 个 18 倍级的解压性能缺陷 + 1 个硬链接解包缺陷 + 4 个 P2 安全/竞态/可用性缺陷**，
> 并**首次把"真机端到端仍跑不起来"的最大平台风险（targetSdk 34 禁止 exec 数据目录文件）摆到台面上**（需你决策，见 §11.1）。
>
> **验证级别**：编译 / 单测 / 脚本测试三套全绿；解压路径有**可复现的基准数据**；平台限制类结论标注「静态确证 / 静态推断 / 待真机确认」。

---

## 0. TL;DR（给不想看长文的人）

| 结论 | 内容 |
|---|---|
| 本轮改动 | 26 个文件（+175 / −73016），提交 **`515270d`**；一条 `git revert 515270d` 可整体回滚 |
| 已修复 | 失效测试源集（`gradlew test` 现在能编译了）、rootfs 解压的可执行位/硬链接/性能、明文流量收口、备份规则、安装器取消-重装竞态、失败提示被吞 |
| 性能实测 | 解压 4003 条目（4000 个小文件）的 rootfs 型归档：**270.0s → 14.7s（≈18.5×）**；同时把 300MB 级 rootfs 的"假死感"消掉 |
| **最大未决风险** | **`targetSdk = 34` 时 Android 10+ 禁止应用 execve 自己数据目录里的文件**（W^X/SELinux）。proot 二进制已按 jniLibs 规避，但 **rootfs 里的 `/bin/sh`、`node` 同样受限** —— 这是"真机端到端仍空白"的头号嫌疑，也是唯一需要你拍板的事（`targetSdk 28` vs 改架构） |
| 未做 | 死代码清理（13 个类 + 4 个布局，已列清单）、仓库内 MC 残留资料（README/CI/CHANGELOG）、应用改名、真机验证 |

---

## 1. 项目概览

### 1.1 用途
未 root 安卓手机上的 **DeepSeek Harness（dsh）启动器**：把 `@deepseek-ai/dsh`（Node 版编码/对话 agent）装进手机 App 私有目录里的
proot Linux rootfs 中运行，用 WebView 承载它的 Web UI；模型走 DeepSeek 云端 API。由 **FCL（Fold Craft Launcher）** 改造而来：
保留 UI 框架（fcllibrary）与少量通用工具，删除全部 Minecraft 代码与资源。

### 1.2 技术栈与版本

| 项 | 值 |
|---|---|
| 语言/构建 | Kotlin 2.4.10 + Java 17、AGP 8.13.2、Gradle 8.14.4、viewBinding |
| SDK | compileSdk **35** / targetSdk **34** / minSdk **26** |
| 模块 | `:FCL`（唯一业务模块）、`:ZipFileSystem`（jar 文件系统，压缩包读取用） |
| Native | **无**（jniLibs / jni / jreAssets 全部已删；仅留 `libproot.so` 的打包位） |
| 主依赖 | gson、commons-compress 1.26.0、xz 1.9、kotlinx-coroutines、lifecycle、material、glide、datastore |
| 运行时底座 | proot（arm64）+ rootfs.tar.xz + 3 个 POSIX sh 脚本（仓库内均为 PLACEHOLDER，需自行补齐） |

### 1.3 目录结构（只列关键）

```
FCL/
├─ FCL/src/main/java/com/dsh/            ← 本项目自己的代码（25 个文件，约 4.8k 行）
│   ├─ core/  DshInstances/DshInstaller/DshRuntime/DshRuntimeService/DshBootstrap/
│   │         DshCredentials/DshRegistry/DshLogBus/DshPaths/ProotCommand/
│   │         ProotProcessExecutor/SingleFlight/DeepSeekApi/...
│   └─ ui/    DshInstancesActivity / DshDownloadActivity / DshSettingsActivity /
│             DshLogsActivity / DshWebViewActivity / 2 个 Adapter
├─ FCL/src/main/java/com/tungsten/       ← FCL 遗产：fcllibrary（UI 框架）、fclcore（工具）、mio/util
├─ FCL/src/main/res/                     ← 19 布局 / 17 drawable / values(+zh) 各 ~170 条文案
├─ FCL/src/main/assets/dsh/              ← scripts/*.sh（已就绪）、proot/、rootfs/（占位，需补二进制）
├─ FCL/src/test/java/com/dsh/            ← 本轮修复后**唯一**的单测源集（23 个用例）
└─ FCL/src/androidTest/java/             ← 本轮修复后只剩 4 个 instrumented 测试
```

### 1.4 运行 / 验证方式

| 目的 | 命令 | 本轮结果 |
|---|---|---|
| 编译（不打包） | `sh /workspace/run-compile.sh` | **BUILD SUCCESSFUL**，0 error（Kotlin+Java+资源+Manifest） |
| 单测 | `sh /workspace/run-tests.sh` | **TOTAL=23 FAILED=0** |
| 脚本一致性 | `cd /workspace/dsh-launcher-poc/scripts && sh test-scripts-posix.sh` | **PASS=18 FAIL=0** |
| 出 APK（未做，按约定） | `sh /workspace/build-apk.sh` | 未执行 |
| 真机 | —— | **仍空白**（缺 libproot.so / rootfs.tar.xz，且沙箱无设备） |

---

## 2. 使用逻辑梳理与完善

### 2.1 端到端使用路径（代码级）

```
① 首次启动
   FCLApp.onCreate → FCLPath.loadPaths() + DshPaths.loadPaths() + DshInstances.init()
      └─ DshInstances.init() 会异步 repair()：按磁盘真实内容校准实例状态（INSTALLING 卡死自愈）
   SplashActivity → 直接进 DshInstancesActivity（无 MC 门禁、无存储权限）

② 准备运行时底座（proot + rootfs + scripts）
   列表页顶部横幅 / 下载页安装前自动触发 → DshBootstrap.install()
      磁盘空间检查(≥1.5GB) → 逐项 version 比对 → 解压 scripts / proot / rootfs(原子替换)
      → verifySync() 真的在 proot 里跑 probe.sh 自检 → 失败给人话

③ 安装一个 dsh 版本（可多实例、多版本并存）
   DshDownloadActivity → DshRegistry.fetchVersionsCached()（npm abbreviated metadata，内存缓存 10min，
   弱网回退上次结果）→ 选中版本 → DshInstances.create() → DshInstaller.install()
      → proot 内 setup-node-dsh.sh（装/补 Node 22.19+ → npm install dsh → 校验 package.json+lib/bin.js）
      → 单飞闸门 + 30min 看门狗 + 阶段进度 + 共享 npm 缓存(跨实例复用)

④ 配置 API Key
   DshSettingsActivity → DshCredentials.save()（Android Keystore AES-GCM，AAD=instanceId，
   原子写盘）→ “测试连接”走 DeepSeekApi.verifyKey()（GET /models，10s/15s 超时，区分 401 与网络错误）

⑤ 启动
   DshRuntime.start()
      预检(proot 可执行 + rootfs 可引导 + 脚本在位) → 磁盘事实校验(package.json/bin.js)
      → 单实例策略（先停别的）→ killStale(清同实例孤儿) → 密钥走**子进程环境变量**（不进 argv、不落盘）
      → proot -r rootfs -0 --link2symlink --kill-on-exit --bind=/dev,/proc,/sys,/opt/dsh,/tmp
      → State.Starting → 180s 看门狗 → 抓取 `token=...` URL 或脚本 READY 标记 → State.Running
      → DshRuntimeService 前台服务（specialUse，通知栏可停止）

⑥ 使用
   DshWebViewActivity 加载 http://127.0.0.1:<port>/?token=...（cookie 持久化，401 自动带 token 重载一次）
      站外链接交给系统浏览器；日志页看实时日志（DshLogBus：环形缓冲 + 5Hz 合并刷新 + token 脱敏 + 落盘 1MB 轮转）

⑦ 停止 / 清理 / 升级
   停止：TERM → 5s → KILL，清 pid 文件，状态机区分“主动停止(143)”与“崩溃”
   冷启：adoptOrphan() 认领仍在跑的实例（校验 pid→/proc/cmdline 含 proot+instanceId→端口可连）
   删除：异步删目录 + 清凭据；app 被杀 → 下次启动 repair() 校准状态
```

### 2.2 数据布局（全部在 App 私有目录，无需任何存储权限）

```
<filesDir>/dsh/
  rootfs/            共享 Linux rootfs（proot -r 的新根）
  scripts/           setup-node-dsh.sh / start-dsh.sh / probe.sh
  npm-cache/         跨实例共享 npm 缓存（重复安装不必重下 ~300MB）
  logs/runtime.log   DshLogBus 落盘（>1MB 自动保留尾部）
  instances.json     实例清单（临时文件+rename 原子写）
  instances/<id>/    node_modules(~300MB) + home/(DSH_HOME) + workspace/(agent 工作区) + dsh.pid + credentials.enc
<cacheDir>/dsh/tmp   rootfs 内 /tmp 的宿主目录（系统可回收）
```

映射关系：宿主 `<filesDir>/dsh` 通过 `--bind` 暴露为 rootfs 内 `/opt/dsh`；实例目录 = `/opt/dsh/instances/<id>`。

### 2.3 本轮"使用逻辑"层面的完善

- **首次就能看懂的三步**（此前文档只散落在代码注释里，仓库 README 还是 MC 的）：
  1. 装 APK → 2. 首次点“准备运行时”（需自行放入 proot 二进制与 rootfs.tar.xz，见 `../PACKAGING.md`）→ 3. 下载页装 dsh → 设置页填 Key → 启动。
- 明确了**失败出口**：底座/安装/启动/凭据四类失败都有明确原因 + 一键看日志/重试入口（本轮修掉了其中“同一失败第二次不再提示”的洞，见 R-09）。
- 明确了**清理语义**：停止 = 停进程不留通知；关闭 WebView 页 ≠ 停进程；删实例 = 停进程 + 删目录 + 清凭据。

### 2.4 仍然缺失的使用说明（建议补）

| 缺口 | 建议 |
|---|---|
| 仓库 `README.md`/`README_EN.md`/`README_RU.md`/`CHANGELOG.md` 仍是 MC 启动器内容 | 换成 dsh 启动器的最小说明（现状会误导任何新读者，含未来的你） |
| 应用显示名仍是 `Fold Craft Launcher`（`app_name`），崩溃页提示同 | 改名（待你定名字），见 R-12 |
| 真机操作手册（首次跑通 checklist） | 建议按 §11.3 的清单固化成 `../REALDEVICE.md` |

---

## 3. 问题清单

> 级别定义：**P0** 阻断/安全/数据丢失；**P1** 严重 bug / 明显性能瓶颈；**P2** 一般问题；**P3** 优化建议。
> 状态：`已修`=本轮已改且验证；`待确认`=需要你决策；`未修`=本轮只登记。

| ID | 级别 | 类型 | 位置 | 问题 | 影响 | 复现/证据 | 修复方案 | 状态 |
|---|---|---|---|---|---|---|---|---|
| **R-01** | P1 | 构建/测试 | `FCL/src/test/**`、`FCL/src/androidTest/**` | MC 移除后遗留 **15 个测试文件**引用已删除的类（`GameComponentType`、`Profile`、`ControlButtonData`、`CcConverter`…） | `./gradlew test`、`assembleAndroidTest`、任何 CI 在**编译期必然失败**；测试形同虚设 | `javac`/`kotlinc` 直接复现：`cannot find symbol: class GameComponentType`、`unresolved reference 'CcUtils'` | 删除失效测试（保留 4 个仍可编译的 instrumented 测试） | **已修** |
| **R-02** | **P0** | 平台/架构 | `libs.versions.toml`（targetSdk=34）+ rootfs 位于 `filesDir` | Android 10+ 起，**targetSdk ≥ 29 的应用不能 execve 自己数据目录里的文件**（W^X/SELinux）。项目已用 jniLibs 规避 proot 二进制，但 **rootfs 内的 `/bin/sh`、`/usr/bin/env`、`node` 同样受限** | 真机上 proot 第一次 execve 就 EACCES → 底座自检失败 → 整条链路跑不起来（这正是“真机端到端空白”的头号嫌疑） | 平台文档（Android 10 行为变更：Removed execute permission for app home directory）+ Termux 等同类项目长期锁 targetSdk 28 的事实；项目自身 `../PACKAGING.md` 已记录该限制但只覆盖 proot 二进制 | 二选一：① `targetSdk = 28`（侧载场景可行，改动 1 行）；② 改架构（把可执行体全部放 nativeLibraryDir 的方案），见 §11.1 | **待确认** |
| **R-03** | P1 | 正确性 | `RuntimeUtils.uncompressTarXZ` | 解压 rootfs 时**只有目录被 `setExecutable(true)`**，普通文件保持 `0600/0644`（无 x 位） | 即使 R-02 解决，解出来的 rootfs 里 `/bin/sh`、`node` 也**不可执行** → proot EACCES；表现为“rootfs 解压成功，但连 /bin/sh 都跑不起来” | 基准实测：tar 里 mode=0755 的条目解出后 `rw-------`、`canExecute=false` | 新增 `restoreExecutableBit()`：按 tar mode 用 `Os.chmod` 还原 x 位（失败退 `File.setExecutable`） | **已修** |
| **R-04** | P1 | 性能 | `RuntimeUtils.uncompressTarXZ` | 每个 ≤20KB 的条目 `Thread.sleep(25)`；rootfs 是“几万个小文件”型归档 | 解压被硬生生拖慢到**十几分钟**（4003 条目 = 100s 纯 sleep），用户以为卡死/失败 | 基准：旧实现 4003 条目 **270.0s**（其中 sleep 100s） | 删掉 sleep；拷贝缓冲 1KB→64KB；进度回调改由 `DshBootstrap.listener` 按时间节流(≤5 次/秒) | **已修** |
| **R-05** | P1 | 正确性 | `RuntimeUtils.uncompressTarXZ` | tar **硬链接条目**（rootfs 里很常见）落到“普通文件”分支，按 size=0 解出 **0 字节空文件** | rootfs 内共享 inode 的文件（如 `/usr/bin` 下一批命令）内容为空 → 运行时报“Text file busy/不可执行/内容缺失”类怪错 | 基准：`link0` 旧实现 0 字节，新实现 1024 字节（真硬链接） | 新增 `isLink()` 分支：`Os.link` 优先，失败退 `Files.copy` | **已修** |
| **R-06** | P2 | 安全 | `res/xml/network_security_config.xml` | `base-config cleartextTrafficPermitted="true"` 全局放行明文 HTTP | 本 App 对外全是 HTTPS，唯一明文需求是回环 WebView；全局放行会把任何误导向站外的明文请求（含 dsh token/cookie）暴露给中间人 | 静态确证（配置 + 代码里仅两个 HTTPS 端点） | 默认禁止明文，仅 `127.0.0.1`/`localhost` 例外 | **已修** |
| **R-07** | P2 | 空间/隐私 | `res/xml/backup_rules.xml`、`data_extraction_rules.xml` | 空模板 → `<filesDir>/dsh/`（rootfs + npm-cache + 每实例 300MB）会进入**自动云备份/设备迁移** | 撑爆 25MB 备份配额（挤掉别的 App 数据）、上传量巨大；恢复也无意义（version 比对本机 assets；`credentials.enc` 换机解不开） | 静态确证 | 两个规则文件都 `<exclude domain="file" path="dsh/"/>` | **已修** |
| **R-08** | P2 | 并发/可靠性 | `DshInstaller.install/cancel` | “取消 → 立刻重装”时，**旧任务的 `finally` 会放掉新任务的单飞闸门**、抹掉新任务的错误摘要与取消标记（`cancel()` 先把登记从 `running` 里删了） | 可能同时跑两个 npm 写同一个 `node_modules`（装坏）、此后的“取消”找不到任务、进度被写成“安装失败” | 代码路径分析（与前几轮修过的 check-then-act 同类） | 引入“登记所有权”：`running.remove(id, myJob)` 才允许清理；任务体在写状态前校验 `isCurrentOwner` | **已修** |
| **R-09** | P2 | 可用性 | `DshInstancesActivity.notifyIfNeeded` | `lastNotifiedState` 在状态回到 Idle/Starting 后不清零 | **同一原因连续失败两次时，第二次没有任何提示**（状态数据类相等被当成“已提示”） | 代码路径分析 | Idle/Starting/Stopping 时清标记 | **已修** |
| **R-10** | P2 | 死代码 | 见 §11.2 清单 | MC 移除后残留 **13 个类 + 4 个布局**（fcl/util 5、mio/util 2、fclcore 3、fcllibrary 8，含 `FCLDynamicIsland↔DynamicIslandAnim` 自引用死对） | 维护噪音；后人误用 | 全仓库引用扫描（java/kt/xml/json，均为 0 外部引用） | 建议删除（本轮未做，避免与功能修复混合） | 未修 |
| **R-11** | P2 | 仓库卫生 | `README*.md`、`CHANGELOG.md`、`.github/workflows/*`、`private_key.pepk`、`scripts/fetch-jna.sh`、`docs/weblate.md`、`version_map.json` | 仍是 MC 项目的资料与 CI（workflows 按 4 个 ABI 打包、依赖已删除的 JRE/Terracotta 资产与 secrets） | 新读者被误导；CI 一旦启用必然失败 | 静态确证 | 建议删除或重写（CI 建议只留 arm64 编译+单测） | 未修 |
| **R-12** | P2 | 产品一致性 | `res/values/strings.xml` | `app_name = "Fold Craft Launcher"`、`crash_reporter_hint` 同 | 用户/系统界面显示的仍是 MC 启动器名字 | 静态确证 | 改名（名字待你定） | 待确认 |
| **R-13** | P3 | 注释正确性 | `DshDownloadViewModel.refresh` 注释 | 注释称“HttpRequest 没有 connect/read 超时”“`cancel(true)` 能打断线程” | 两点都与事实不符（`NetworkUtils.TIME_OUT=10s`；`CompletableFuture.cancel(true)` 不中断执行线程），会误导后续优化 | `NetworkUtils.java:40/121`；JDK 规范 | 修正注释；或给注册表请求加更短超时 | 未修 |
| **R-14** | P3 | 诊断 | `DshInstaller.runInstall` | 看门狗超时返回 `-2`，未翻译成用户可读原因 | 用户只看到泛化的“安装失败” | `ProotProcessExecutor` 返回码约定 | 加一条 `dsh_install_timeout` 文案 | 未修 |
| **R-15** | P3 | 文案 | `DshRuntimeService.buildNotification` | 非 Starting/Running 状态也显示“运行中” | 极短窗口内的误导通知（服务随即自停） | `else -> dsh_notify_running` | 按状态给文案 | 未修 |
| **R-16** | P3 | 性能（可忽略） | `DshLogBus.append` | 环形裁剪用 `removeAt(0)`，每行 O(n) 搬移 | 2000 行规模下实测可忽略（注释与实现不一致） | 代码阅读 | 可换 `ArrayDeque` | 未修 |
| **R-17** | P3 | 资源冗余 | `values/values-zh/strings.xml` | `dsh_action_configure_key` 无任何引用（其余文案均被引用） | 冗余 | 扫描（仅此 1 条未引用） | 删除 | 未修 |
| **R-18** | P3 | 注释/KDoc | `DshInstance.kt`、`DshInstances.kt` | KDoc 里 `[com.tungsten.fcl.setting.Profile]` 等链接指向已删除的类 | 点不动/误导 | 静态确证 | 改成文字描述 | 未修 |
| **R-19** | P3 | 测试缺口 | `FCL/src/androidTest/**`（剩 4 个） | 沙箱内无法编译验证（Google Maven `dl.google.com` 不可达），且需真机才能跑 | 这 4 个 instrumented 测试的可用性未证实 | 实测 `curl dl.google.com` 超时；`./gradlew compileDebugUnitTestKotlin` 因仓库不可达失败 | 联网环境跑一次 `:FCL:compileDebugAndroidTestKotlin` | 待验证 |
| **R-20** | P3 | 构建/体积 | `FCL/build.gradle.kts` | release 未开 `isMinifyEnabled`/资源压缩，且 APK 里含 zip 内含物等无用资源 | APK 更大、逆向更容易 | 静态确证 | 建议开 R8 + `shrinkResources`（需回归测试） | 未修 |
| **R-21** | P3 | 依赖 | `gradle/libs.versions.toml` | 无法在沙箱内在线核对 CVE（部分网络受限） | 依赖风险未量化 | —— | 联网环境跑一次依赖扫描 | 待验证 |

---

## 4. 已完成的修复与优化（逐条）

> 统一验证：`run-compile.sh` **BUILD SUCCESSFUL（0 error）**；`run-tests.sh` **23/23**；`test-scripts-posix.sh` **18/18**；
> 全部改动见提交 `515270d`（`git revert 515270d` 可整体回滚）。

### R-01 删除引用已删除 MC 类的失效测试（P1，构建/测试）
- **为什么**：`./gradlew test` 在当前代码上 100% 编译失败（我 3 轮静态审查没抓到的原因是：只看了 `src/main`）。
- **证据（可复现）**：
  - `javac` 输出：`error: cannot find symbol import com.tungsten.fclcore.game.GameComponentType;`
  - `kotlinc` 输出：`ControlConverterTest.kt:31: error: unresolved reference 'CcUtils'.`（该类随 MC 一起删除）
- **改了什么**：删除 15 个失效测试 + 4 个夹具 JSON（共 26 文件里的大头，−73016 行）。
  保留的 4 个 instrumented 测试（`MurmurHash2Test`、`NetworkUtilsDoGetTest`、`ThemeDataTest`、`ThemeEngineTest`）逐个核对过引用的 API 仍存在。
- **影响面/兼容性**：只删测试，不改产品行为。修复后单测源集 = `run-tests.sh` 实际编译并执行的那两个文件，**覆盖率口径完全对齐**。
- **验证**：用 kotlinc 单独编译整个 `FCL/src/test/java` → **0 error**，MiniRunner 跑 **23/23 PASS**。

### R-03 / R-04 / R-05 rootfs 解压：可执行位 + 硬链接 + 性能（P1）
文件：`FCL/src/main/java/com/tungsten/fcl/util/RuntimeUtils.java`、`com/dsh/core/DshBootstrap.kt`

```java
// 1) 还原可执行位（新增）
private static void restoreExecutableBit(File path, TarArchiveEntry entry) {
    int mode = entry.getMode();
    if ((mode & 0111) == 0) return;            // 普通数据文件不动作
    int target = (mode & 0777) | 0100;         // 至少给 owner 的 x
    try { Os.chmod(path.getAbsolutePath(), target); }
    catch (Throwable e) { path.setExecutable(true, false); }   // 非 Android 环境退路
}

// 2) 硬链接分支（新增）：原来会落到“普通文件”分支，按 size=0 解出 0 字节空文件
} else if (tarEntry.isLink()) {
    File linkTarget = new File(dest, tarEntry.getLinkName());
    try { Os.link(linkTarget.getAbsolutePath(), destPath.getAbsolutePath()); }
    catch (Throwable e) { if (linkTarget.isFile())
        Files.copy(linkTarget.toPath(), destPath.toPath(), StandardCopyOption.REPLACE_EXISTING); }

// 3) 删除 Thread.sleep(25) / 缓冲 1KB → 64KB，进度节流移到调用方
```
- **为什么不还原完整 mode**：App 是这些文件唯一的用户；保持 owner 可写可避免 tar 里 0444/0555 的文件在重复解压时写不进去。**这是刻意的取舍**，已写进注释。
- **影响面**：只影响 rootfs/资产的解压（`DshBootstrap` 是唯一调用方）。DshBootstrap 的进度回调加了 200ms 节流，界面仍每 0.2s 刷新一次。
- **兼容性**：`Os.chmod` 在 Android 全版本可用；非 Android（桌面 JVM 跑基准/单测）自动退到 `File.setExecutable`。
- **验证**：见 §5 基准（同时给出 mode/硬链接产物的实测值）。

### R-06 明文流量收口（P2，安全）
`res/xml/network_security_config.xml`：`base-config cleartextTrafficPermitted="false"` + `domain-config` 只放行 `127.0.0.1`、`localhost`。
- **影响面**：App 进程内 Java/WebView 网络栈；**不影响** rootfs 内 apt/npm（它们是 native socket，不受该策略约束）。dsh Web UI 走回环，仍在白名单内。
- **待真机确认**：WebView 加载 `http://127.0.0.1:<port>` 是否仍被放行（按 Android 文档「API 24+ 有 NSC 时以 NSC 为准」应当放行）。若真机上 WebView 打不开，**一行回滚**：把 `base-config` 改回 `true`。

### R-07 备份规则（P2）
`backup_rules.xml`（API ≤30）与 `data_extraction_rules.xml`（API 31+）都排除 `files/dsh/`。

### R-08 安装器取消/重装竞态（P2，可靠性）
文件：`com/dsh/core/DshInstaller.kt`
- `cancel()` 不再从 `running` 摘登记（登记 = “共享状态归谁管”），改为让任务自己在 `finally` 收尾；
- 任务体在写任何状态前校验 `isCurrentOwner(instanceId, myJob)`；
- `finally` 里只有 `running.remove(id, myJob)` 成功才 `gate.release()` / 清错误摘要 / 清取消标记。
- **效果**：旧任务的收尾再也不会放掉新任务的闸门（消除“两个 npm 同时写一个 node_modules”的窗口），也不会把新安装标成 BROKEN。
- **兼容性**：行为对外不变（“取消”仍立即放闸门，允许马上重装；取消时其实已装完仍会标 READY）。

### R-09 失败提示不再被吞（P2，可用性）
`DshInstancesActivity.notifyIfNeeded`：状态回到 Idle/Starting/Stopping 时清 `lastNotifiedState`。

---

## 5. 性能优化

### 5.1 瓶颈分析（唯一实测瓶颈：rootfs 解压）
`uncompressTarXZ` 是"几万个小文件"路径，旧实现有两个叠加问题：
1. **人为 sleep**：`if (entry.getSize() <= 20480) Thread.sleep(25);` —— 4003 条目里 4000 个命中 → **100s 纯等待**；
2. **1KB 拷贝缓冲**：6MB 归档要几十万次 `read/write` 系统调用（Android 上还叠加文件创建开销）。

另外，`DshBootstrap` 的进度回调是"每个文件一次 `runOnUiThread{dialog.setMessage}`"，所以旧实现才用 sleep 限速——
**正确做法是解压全速跑、进度回调自己节流**（本轮改成 ≤5 次/秒）。

### 5.2 基准测试（可复现）
- 方法：用 `commons-compress` 生成一个 rootfs 型归档（25 目录 + 4000 个 1KB 文件 + 1 个 0755 可执行文件 + 1 个硬链接 + 1 个普通文件），
  用同一份 `Bench` 驱动 **HEAD 版** 与 **工作区版** `RuntimeUtils.uncompressTarXZ`，各跑 2~3 次。
- 归档：`sample.tar.xz` = 16 KB（解压后 6.2 MB，**4003 条目**）。脚本与产物在 `/tmp/bench/`（临时目录，可复现生成）。

| 指标 | 旧（HEAD `a62ed0d`） | 新（`515270d`） | 变化 |
|---|---|---|---|
| 解压耗时（4003 条目） | **270.0 s / 271.6 s** | **14.3 s / 15.1 s** | **≈18.5×** |
| 其中纯 sleep | 100 s（4003×25ms） | 0 | 消除 |
| `mode(/usr/bin/app)`（tar=0755） | `rw-------`，`canExecute=false` | `rwx--x--x`，`canExecute=true` | **可用性修复** |
| 硬链接 `link0`（目标 1024B） | **0 字节** | 1024 字节 | **正确性修复** |
| 进度回调次数 | 4003（每次都直达 UI 线程） | 4003（下游节流到 ≤5/s） | UI 不再被淹没 |

> 说明：沙箱负载波动较大（同一版本两次相差可达 2×），所以**比值**比绝对秒数更可信；
> 100s 的 sleep 是确定性的算术事实。真实 rootfs（3~6 万文件）按同比例推算是**几十分钟 → 1 分钟左右**，**待真机确认**。
> 表中 `rwx--x--x` 是桌面 JVM 退路（`File.setExecutable`）的产物；真机走 `Os.chmod` 会精确还原 tar 的 `0755`。

### 5.3 其它已确认无问题 / 未动的地方

| 项 | 结论 |
|---|---|
| 日志（`DshLogBus`） | 已用环形缓冲 + 5Hz 合并 + revision 判断，npm 万行输出不会卡 UI；`removeAt(0)` 的 O(n) 在此规模可忽略 |
| 实例列表 | DiffUtil + 体积缓存 + IO 线程统计，滚动无抖动 |
| 注册表请求 | 已用 10 分钟内存缓存 + `future.get(20s)` + 断网回退；`NetworkUtils` 本身已有 10s connect/read 超时 |
| Keystore 解密 | 全部在 IO 线程（启动点击、进设置页都不会掉帧） |
| 未做基准 | APK 体积 / 冷启动耗时 / 内存（需出包 + 真机，按约定未打包） |

---

## 6. 可靠性优化

### 6.1 项目已有（前几轮成果，本轮复核确认有效）
- **状态机**：`Idle/Starting/Running/Stopping/Failed/Exited`，带"状态归属校验"，迟到回调不许改别人的状态；
- **孤儿进程**：pid 文件 + `cmdline` 身份校验（防 pid 复用误杀）+ 冷启认领 + 港口/进程双条件巡检；
- **超时**：启动 180s 看门狗、安装 30min 看门狗、proot 执行看门狗；
- **幂等/单飞**：`SingleFlight` 闸门（并发安全）、实例 id 唯一、清单原子写；
- **资源释放**：`--kill-on-exit`、TERM→KILL、前台服务与进程解耦、WebView 先摘父再 destroy、Cookie flush；
- **可观测**：日志总线（脱敏 + 落盘 + 轮转）、失败原因进 `lastError`、界面可见；
- **配置校验**：端口范围、名称必填、密码可用性 `status()` 三态（None/Ok/Unreadable）。

### 6.2 本轮新增
| 项 | 内容 |
|---|---|
| 安装任务所有权 | 见 R-08（消除取消/重装竞态） |
| 解压失败路径 | `FileOutputStream` 改 try-with-resources，解压异常不再泄漏句柄 |
| 解压产物可用性 | 可执行位 + 硬链接（R-03/R-05）——这是**可靠性**问题而不是性能问题：不修，真机链路必挂 |
| 用户反馈 | 同一失败重复提示（R-09） |

### 6.3 仍缺（建议）
- 真机故障注入：断网切后台、系统杀进程、存储满、rootfs 损坏（`gameplan` 见 §11.3）；
- 安装重试退避（当前 npm 侧依赖 `--prefer-offline`，无指数退避/断点续传）；
- 启动失败时的**可操作引导**（现在能看日志，但没有“一键换端口/关 seccomp/切 rootfs”之类的按钮，seccomp 已自动兜底一次）；
- 无崩溃上报（CrashReporter 走的是共享链接，`LogSharingUtils` 依赖外部服务）。

---

## 7. 代码质量评估

| 维度 | 评分 | 说明 |
|---|---|---|
| 正确性 | **8.5/10** | 并发/生命周期边界处理得很细（前五轮+本轮）；扣分在**解压路径**（x 位/硬链接两个必错项刚修）与未真机验证 |
| 可读性 | **9/10** | 注释密度高且讲“为什么”，命名清晰；少数注释与实现不符（R-13/R-16） |
| 可维护性 | **7.5/10** | 抽出了 `ProotCommand` 单一构造点、`DshPaths` 单一布局；扣分在 MC 死代码（R-10）、仓库资料残留（R-11）、测试源集此前是坏的（R-01） |
| 性能 | **8/10** | UI/日志/列表都做过针对性优化；解压一处在“未被测过”的路径上藏了 18 倍坑（已修） |
| 可靠性 | **8.5/10** | 见 §6；剩余风险主要在**平台限制**与真机行为 |
| 安全性 | **8/10** | 密钥 Keystore + 环境变量注入 + 无 argv 泄漏 + 日志脱敏 + WebView 收紧 + 明文收口（本轮）；扣分在 release 未混淆、debug 签名复用、无依赖扫描 |
| 测试 | **6/10** | 单测都是"错了会静默"的纯逻辑点，质量高；但**没有真机 e2e**，instrumented 测试无法在沙箱验证，且此前整体编译不通过（本轮修复） |

---

## 8. 安全与依赖评估

### 8.1 输入校验
- 端口（0~65535）、实例名非空、版本号来自 registry 且按 semver 校验、URL 解析用 `Regex`/`URI` 容错；
- proot 参数**全部走 argv 数组**（无字符串拼接 → 无注入面）；环境变量有白名单（挡 `LD_PRELOAD` 这类）。

### 8.2 认证/密钥
- API Key：Android Keystore AES-GCM，AAD = instanceId（密文换实例即解不开），原子写盘，**不落明文**；
- 运行期注入：只进子进程环境变量（`ps` 看不到），启动 token 注册到日志脱敏表；
- WebView：`?token=` → cookie（cookie 签名密钥持久化在 DSH_HOME，重启免 token）；
- 明文凭据文件的历史残留有专门的清理逻辑（`cleanupLegacyFiles`）。

### 8.3 网络暴露面
- dsh web **只绑 127.0.0.1**（不暴露局域网）；本轮把 App 的明文策略收口到回环（R-06）。

### 8.4 依赖
| 依赖 | 版本 | 备注 |
|---|---|---|
| commons-compress / xz | 1.26.0 / 1.9 | 解析不可信 tar.xz 的**攻击面**（rootfs 是你自己打包的，风险可接受） |
| gson | 2.10.1（catalog） / 2.11.0（缓存） | 需确认实际解析版本，建议统一 |
| junrar 7.5.5 / jsoup 1.18.3 / commons-io 2.15.1 | —— | **疑似 MC 遗产**，本轮未核实是否仍被引用（见 §11.2 待办） |
| 未做 | —— | 沙箱网络受限，无法在线核对 CVE，标 **待验证** |

### 8.5 签名与发布
- `release`/`debug` 都用仓库里的 `key-store.jks`，`fordebug` 用 `debug-key.jks`（**仓库内私钥**，仅适合自用/侧载；对外分发需换 key 并移出仓库）；
- 未开 R8（R-20）；`usesCleartextTraffic="true"` 已由 NSC 覆盖，建议顺手删掉避免误导。

---

## 9. 测试与验证

### 9.1 已运行（本轮，全部在最终代码上）

| # | 命令 | 结果 |
|---|---|---|
| 1 | `sh /workspace/run-compile.sh` | **BUILD SUCCESSFUL**，`34 actionable tasks`，0 error（Kotlin+Java+资源+Manifest 合并） |
| 2 | `sh /workspace/run-tests.sh` | **TOTAL=23 FAILED=0** |
| 3 | `cd /workspace/dsh-launcher-poc/scripts && sh test-scripts-posix.sh` | **PASS=18 FAIL=0** |
| 4 | kotlinc 编译 `FCL/src/test/java` 全部源码 | **0 error**（修复后单测源集整体可编译） |
| 5 | javac 编译旧单元测试（HEAD 版） | 复现 R-01：`cannot find symbol: GameComponentType / BMCLAPIDownloadProvider` |
| 6 | kotlinc 编译旧 `com/mio/controlconverter` 测试（HEAD 版） | 复现 R-01：`unresolved reference 'CcUtils'` |
| 7 | 解压基准（HEAD vs 工作区，4003 条目） | 270.0s → 14.3s；x 位/硬链接产物对照，见 §5.2 |
| 8 | 全仓库对"已删除 MC 类名"的引用扫描（640 个类名） | `src/main` + 单测 **0 命中**；instrumented 11 个文件命中（本轮已删） |

### 9.2 未运行及原因

| 未运行 | 原因 |
|---|---|
| `./gradlew test` / `:FCL:compileDebugUnitTestKotlin` | 依赖需从 **Google Maven（dl.google.com）** 拉取，沙箱内该域名不可达（实测 curl 超时）→ 改为离线自建 JUnit 桩跑（#2），并单独验证测试源集可编译（#4） |
| `:FCL:compileDebugAndroidTestKotlin` | 同上（androidx.test 依赖不在离线缓存） |
| `assembleFordebug`（出 APK） | 按项目约定：只在你说“打包测试”时才打包 |
| 真机 e2e（安装/启动/WebView/停止/孤儿认领） | 沙箱无设备；且 `libproot.so`、`rootfs.tar.xz` 在仓库里是 PLACEHOLDER |

### 9.3 建议补充的测试
1. **解压单测**：用合成 tar.xz 断言 x 位/硬链接/符号链接/深路径（本轮基准已具备雏形，可固化成 `RuntimeUtilsTest`）；
2. **安装器竞态单测**：把 `DshInstaller` 的“闸门/所有权”逻辑抽成纯逻辑（像 `SingleFlight` 那样）后可直接 JVM 测；
3. 真机 checklist（§11.3）逐项打勾，尤其是 **R-02 的 exec 限制**；
4. CI（若恢复）：`compileDebug* + checkstyle + 单测 + 脚本测试` 四件套。

---

## 10. 变更文件与 diff 摘要

**提交**：`515270d`（父提交 `a62ed0d`），`git revert 515270d` 可整体回滚。

```
 26 files changed, 175 insertions(+), 73016 deletions(-)
```

| 文件 | 变更 | 对应问题 |
|---|---|---|
| `FCL/src/main/java/com/tungsten/fcl/util/RuntimeUtils.java` | +78 −29：可执行位还原、硬链接分支、删 sleep、64KB 缓冲、try-with-resources | R-03/04/05 |
| `FCL/src/main/java/com/dsh/core/DshBootstrap.kt` | +20 −2：进度回调按时间节流（200ms） | R-04 |
| `FCL/src/main/java/com/dsh/core/DshInstaller.kt` | +41 −11：安装任务所有权判定（取消/重装竞态） | R-08 |
| `FCL/src/main/java/com/dsh/ui/DshInstancesActivity.kt` | +10：失败提示标记在新生命周期清零 | R-09 |
| `FCL/src/main/res/xml/network_security_config.xml` | 重写：明文仅回环 | R-06 |
| `FCL/src/main/res/xml/backup_rules.xml` / `data_extraction_rules.xml` | 重写：排除 `dsh/` | R-07 |
| `FCL/src/test/java/**`（4 个 + 4 个夹具） | 删除 | R-01 |
| `FCL/src/androidTest/java/**`（11 个） | 删除 | R-01 |

**新增/删除依赖**：无。

---

## 11. 风险、兼容性与后续建议

### 11.1 ⚠️ 需要你决策的头号问题：targetSdk 34 与“数据目录不可执行”（R-02）

**事实（高置信，建议真机复核）**：Android 10+ 起，`targetSdk ≥ 29` 的应用**不能执行自己数据目录里的文件**
（官方行为变更：“移除应用主目录的执行权限”；Termux 等同类项目为此长期锁 `targetSdk = 28`）。
项目已经意识到这点（`../PACKAGING.md`），但**只把 proot 二进制搬到 jniLibs**；而 proot 真正 execve 的是
**rootfs 里的 `/bin/sh`、`/usr/bin/env`、`node`** —— 它们都在 `filesDir` 下，**同样会被拒**。

**两个可选路线**：

| 方案 | 改动量 | 代价 |
|---|---|---|
| **A. `targetSdk = 28`**（推荐做“真机点亮”的第一步） | 1 行（`gradle/libs.versions.toml`） | 失去部分新版本平台行为（存储/通知/FGS 类型等），Play 上架不合规 —— 但本项目是**侧载自用**，且 Android 14 仍允许安装 targetSdk ≥ 23 的包；配合本轮 R-03 的 x 位修复，理论上即可跑通 |
| **B. 保持 targetSdk 34，改执行体位置** | 大 | 需要把“要 exec 的东西”全部放 `nativeLibraryDir`（只能放 `lib*.so`）。可行的最小闭环是：静态链接的 node + busybox 放进 jniLibs，rootfs 只当数据目录（npm 需要 `sh` 时用 busybox 兜）——复杂且要重新验证 dsh 的 npm 安装路径 |

**建议**：先按 A 做“真机点亮”（改一行 + 本轮修复），确认链路能通；之后若要坚持 targetSdk 34，再评估 B。
**在真机验证前，不要因为“代码看起来都对”就认为端到端没问题** —— 这是本项目目前最大的不确定性。

### 11.2 已核实的死代码清单（R-10，建议单独一个提交删除）

> 判据：全仓库（`*.java/*.kt/*.xml/*.json`，含布局与 `attrs.xml`）对外引用数为 0；`src/main` 内互相引用只出现在同为死的文件之间。

| 文件 | 备注 |
|---|---|
| `com/tungsten/fcl/util/{ShellUtil,FXUtils,WeakListenerHolder,RequestCodes,ResourceNotFoundError}.java` | 0 引用 |
| `com/mio/util/{DialogUtil,AndroidUtil}.kt` | 0 引用（`AndroidUtil` 只被死的 `FCLMultiPageUI` 引用） |
| `com/tungsten/fclcore/util/KeyUtils.java` | 0 引用 |
| `com/tungsten/fclcore/task/{FileDownloadTask,GetTask}.java` | 0 引用 |
| `com/tungsten/fcllibrary/component/view/{FCLCheckBoxTreeAdapter? ,FCLTabLayout,FCLMenuView,FCLUILayout}.java` | 0 引用（`FCLTabLayout` 仅出现在 `attrs.xml`） |
| `com/tungsten/fcllibrary/component/dialog/{FCLColorPickerDialog,FullImageDialog}.java` | 0 引用（连带 `res/layout/dialog_color_picker.xml`、`dialog_full_image.xml`） |
| `com/tungsten/fcllibrary/component/ui/{FCLMultiPageUI,FCLBaseUI?,FCLPage?}`、`anim/DynamicIslandAnim.java` + `view/FCLDynamicIsland.java` | `DynamicIsland` 与 `DynamicIslandAnim` 是**互相引用的死对**；`FCLPage/FCLBaseUI` 需先确认继承链（`FCLActivity` 是否继承 `FCLBaseUI`）再删 |
| `res/layout/{dialog_edit,dialog_full_edit,dialog_color_picker,dialog_full_image}.xml` + 相关 `strings` | 随死类一起清 |
| **待核实**：`libs.versions.toml` 里 `junrar/jsoup/opennbt/taptargetview/tomlj/constantPoolScanner` 等是否仍被引用 | MC 遗产疑似 |

### 11.3 真机验证清单（建议照做）
1. `targetSdk` 决策后出包：`sh /workspace/build-apk.sh`；
2. 放入 `jniLibs/arm64-v8a/libproot.so` + `libproot_loader.so`、`assets/dsh/rootfs/rootfs.tar.xz`（**含 Node 22.19+**）；
3. 首启 → “准备运行时” → 观察自检（**R-03/R-04 的修复在这里体现**：不再假死、`probe.sh` 能真跑起来）；
4. 下载页装一个版本 → 看阶段进度；中途点“取消”，再立刻重装，确认不出现“两个 npm 同时装/状态错乱”（R-08）；
5. 设置页填 Key → “测试连接”；
6. 启动 → WebView 是否加载出 UI（**R-06 的 NSC 收紧是否影响回环明文加载**）；
7. 返回桌面等 5 分钟 → 通知是否还在、进程是否活着；杀掉 App → 重进是否**认领**成功；
8. 停止 → 通知消失、`ps` 里无残留 node；删除实例 → 目录真的被清掉。

### 11.4 后续优化路线（建议顺序）
1. **R-02 决策 + 真机点亮**（一切的前提）；
2. 死代码/仓库卫生一次清干净（R-10/R-11/R-12/R-17/R-18）——它们对“下一个接手的人（或 AI）”的成本最高；
3. 把本轮基准固化成单测（§9.3）；恢复 CI（编译+checkstyle+单测+脚本）；
4. R-13/R-14/R-15 的小改进；R-20 开 R8。

---

## 12. 验收清单

- [x] 项目可编译（`run-compile.sh` BUILD SUCCESSFUL，0 error）
- [x] 单测源集可编译并可运行（23/23；`gradlew test` 的编译阻塞已解除，跑该任务受沙箱网络限制）
- [x] 脚本测试通过（18/18）
- [ ] 核心流程端到端可运行（**未验证**：缺 proot/rootfs + 无设备 + R-02 未决）
- [x] 无新增严重问题（静态审查 + 编译 + 单测覆盖本轮改动面）
- [x] 性能有改善且给出明确结论（解压 18.5×，见 §5.2；其余项结论：无实测瓶颈）
- [x] 可靠性有改善且给出明确结论（竞态修复、失败提示、解压正确性；平台级风险已单列）
- [x] 文档完整（本报告 + 死代码/真机清单 + 变更摘要；仓库内 README 等仍待更新，见 §2.4）

---

### 附：本次审查的方法与局限（写在最后，避免误读）

- **方法**：静态通读 `com/dsh/**` 全量源码 + fcllibrary/fclcore 可达部分；全仓库引用扫描（含资源与死代码判据）；
  编译/单测/脚本三套动态验证；对解压路径用**合成归档做了前后对照基准**（可复现）。
- **局限**：无设备、无 proot 二进制、无 rootfs、无 CI 历史、Google Maven 不可达。
  因此**任何涉及真机运行时行为（SELinux、ROM 差异、WebView 行为、proot 交互）的结论都标了“待真机确认”**，
  没有伪造运行结果；本轮所有"已修"项都在沙箱内用可复现的方式验证过。
