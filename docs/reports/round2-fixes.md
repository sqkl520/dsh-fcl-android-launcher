# dsh 启动器：第二轮审查 / Bug 修复 / 性能与可靠性加固

> 日期：2026-09-22　范围：`FCL/src/main/java/com/dsh/**`（24 个 Kotlin 文件，4310 行）、
> 6 个布局、Manifest、中英 strings、`assets/dsh/scripts/*`。
> 上游背景见 [../PLAN.md](./../PLAN.md)；本文只讲**这一轮改了什么、为什么改、验证到什么程度**。

---

## 0. 一句话总结

第一轮的代码"能编译、链路设计正确"，但**离"用户装上就能用"还有距离**：没有 API Key 录入入口、
运行日志页没有任何入口、安装会假成功、界面一退出安装就断、WebView 抓不到 token 就永远转圈。
本轮把这些**功能性缺口全部补齐**，并修掉 10 个会导致卡死/ANR/状态损坏的 bug、
6 处性能问题、11 处可靠性/安全问题；脚本层重写了"就绪/失败"信号协议。

验证方式（不是"看了一遍"）：
**真编译**（`compileDebugKotlin` BUILD SUCCESSFUL，dsh 文件 0 error / 0 warning）+
**真跑脚本**（改写后的 `start-dsh.sh` 用真实 dsh 包跑 4 个场景，含 200/401/信号转发）+
**真跑逻辑**（30 条断言直连编译产物 + 14 个 JVM 单测）。

---

## 1. 审查范围与方法

| 类型 | 具体做法 |
|---|---|
| 静态审查 | 逐个读完 18 个原 dsh 文件；对照 FCL 既有设施（`FCLPath`/`RuntimeUtils`/`HttpRequest`/`Logging`/`FCLActivity`/`SplashActivity`）确认复用姿势是否正确 |
| 真实行为探测 | 用工作区里已装好的真实 dsh（Node 22.23.2 + `@deepseek-ai/dsh`，与 0.1.5-rc.2 同代）实测：`dsh web --help`、token→cookie 鉴权、**cookie 是否跨进程重启存活**、`--port 0` 自动分配、启动打印的真实格式 |
| 真编译 | `./gradlew :FCL:compileDebugKotlin -Darch=arm64`（arm64+qemu aapt2 环境）→ BUILD SUCCESSFUL |
| 真跑脚本 | 4 个场景：固定端口 / `PORT=0` / 包缺失失败 / 自检探针 |
| 真跑逻辑 | Java 直连编译产物 30 条断言；Kotlin 单测文件用 kotlinc 编译并反射执行 14 个用例 |

实测得到的两条关键事实（后面多处修复依赖它）：

1. **cookie 跨进程重启依然有效**：停掉 `dsh web` 再用同一 `DSH_HOME` 重启，旧 cookie 访问 `/` 仍是 200。
   签名密钥持久化在 `DSH_HOME/.dsh` 里。→ 所以 WebView 只要持久化 cookie，重进就不必再走 token。
2. **dsh 先 bind 端口、后打印 token URL**：端口通不代表"可访问"，必须等 URL 那行。
   → 脚本的就绪判据必须以此为主（这个坑本轮实测踩到过，见 §7）。

---

## 2. 致命问题（不修则"根本用不起来"）

### 2.1 没有任何入口能录入 API Key　`DshCredentials` / UI
- **现象**：`DshCredentials.storeEncrypted()` 写好了，但**全工程无任何调用点**。用户装完 dsh 启动实例，
  永远是"界面能开、对话必失败"，且无从下手。
- **根因**：第一轮只做了凭据的存储层与注入层，漏了输入层。
- **修复**：新增 **`ui/DshSettingsActivity`（实例设置页）**：录入（Keystore 加密存储）、脱敏回显、
  清除、**测试连接**（新增 `core/DeepSeekApi.verifyKey`，能区分"密钥无效 401/403"与"网络不通"）。
  实例菜单里加"设置"入口；启动时若发现未配置 key，弹"去配置 / 仍然启动"。

### 2.2 日志页没有任何入口　`DshLogsActivity`
- **现象**：安装/启动失败都提示"详见日志"，但**日志页只能靠代码手动启动**，用户点不到。
- **修复**：实例列表页顶部加"日志"按钮；每个实例的"更多"菜单加"日志"；启动失败对话框加"查看日志"。
  日志页再加"复制全部"与落盘文件路径显示。

### 2.3 运行时底座未就绪时无提示、无法就地准备　`DshBootstrap`
- **现象**：proot/rootfs 没解压时，安装会跑到一半失败，用户只看到一句"请稍候"。
- **修复**：实例列表页顶部 **运行时横幅**（显示缺什么 + "准备运行时"按钮，带阶段进度）；
  下载页首次安装前也自动走同一条准备流程。

### 2.4 安装"假成功"　`core/DshInstaller.kt`
- **现象**：只要脚本退出码是 0 就标 `READY`；版本号解析失败时还会把字符串 `"latest"` 当成版本号写进实例——
  用户看到"就绪"，点启动却报"缺少 dsh 包"。
- **修复**：安装后**必须**能从 `node_modules/@deepseek-ai/dsh/package.json` 解析出真实版本，
  并校验 `lib/bin.js` 存在；否则判 `BROKEN` 并附带 npm 失败摘要（收集 `npm ERR!` 尾部）。

### 2.5 WebView 无限转圈　`ui/DshWebViewActivity`
- **现象**：`DshRuntime` 抓不到 token 时，`running.url` 永远是 null，界面就是一个永远转的进度条，
  没有失败出口、没有重试、没有停止。
- **修复**：完整状态机 —— 启动中 / 加载中 / 就绪 / 失败（原因 + 重试 + 看日志 + 停止）；
  **401 兜底**（cookie 失效时若有 token URL 就自动重载一次，否则明确提示）；
  cookie 显式持久化（利用 §1 的实测结论，重进免 token）。

---

## 3. 严重 Bug（卡死 / ANR / 数据损坏）

| # | 位置 | 现象与根因 | 修复 |
|---|---|---|---|
| 3.1 | `DshInstaller`/界面 | 安装用的是 `lifecycleScope`：**装 300MB 依赖时旋转屏幕/按返回就取消协程**，proot 里的 npm 变孤儿，实例状态永久停在 `INSTALLING`（还写进了磁盘） | 新增 `core/DshAppScope`（进程级 SupervisorJob+IO）；长任务全部脱离界面生命周期；`DshInstances.init()` 后异步 `repair()` 按磁盘真实内容校准卡死状态 |
| 3.2 | `DshInstances.delete` | 在主线程 `deleteRecursively()` 删约 300MB → **必 ANR** | 异步删除 + `deleting` 状态（列表显示"删除中"并禁用操作）+ 删除前先停进程 |
| 3.3 | `DshInstances.create` | id = `inst-<毫秒>`：同一毫秒建两个实例 → **撞 id**（目录互踩、清单重复项） | id 加进程序列号 + 随机后缀，并校验不与现有 id/目录冲突 |
| 3.4 | `DshInstances.load` | `instances.json` 解析失败被 `runCatching` 静默吞掉 → 用户以为"实例全没了" | 坏文件另存 `.corrupt-<时间>` 并写日志总线；清单重置而非静默；写盘改为**临时文件 + rename** 原子替换并在 `Mutex` 内串行化 |
| 3.5 | `DshRuntime.stop` | 只持有 `Process` 句柄：**App 被系统杀掉后重进，无法停止仍在跑的 dsh**（端口被旧进程占着，新实例起不来） | 新增 `dsh.pid` 记录；`adoptOrphan()` **认领**仍在跑的实例（校验 pid 的 cmdline 确属本实例、端口可连）；`killStale()` 清理不属于本实例的残留；停止走 TERM→5s→KILL |
| 3.6 | 端口 | 新建实例固定 3080；`pickFreePort` 只避开"本 App 已知"的端口，**不检查系统占用**，被别的 App 占用即启动失败 | 默认 `port=0` 交给系统分配，从 dsh 输出回读真实端口写回实例（实测 `--port 0` 可用），设置页可手填 |
| 3.7 | `DshRuntimeService` | 长驻进程自行退出后**没人停服务** → 通知栏永久"运行中"；`onStartCommand` 只判 `running==null`，`Starting` 阶段会闪断服务 | 服务内订阅 `DshRuntime.state`（非 Starting/Running 即 `stopForeground+stopSelf`）；通知加"停止"动作；用 `drop(1)` 避开 StateFlow 回放与 `startForeground` 抢时序 |
| 3.8 | `DshRuntime.start` | 只要 `Process.start()` 不抛异常就返回 true，**之后没有任何超时/失败出口** | 启动看门狗 180s + 进程早退检测 + 失败原因回传（脚本侧 `READY_TIMEOUT` 提前 15s 给出具体原因） |
| 3.9 | `DeepSeekApi`（新增文件内） | `(URL(...) as HttpURLConnection)` —— **编译告警直接点出来的必崩强转**（`URL` 不是 `URLConnection`） | 改为 `URL(...).openConnection() as HttpURLConnection`（编译期抓到，已修） |
| 3.10 | `ProotCommand`（新增文件内） | KDoc 里写了 `scripts/*.sh`，Kotlin **块注释可嵌套**，`/*` 打开了一个永不闭合的注释 → 整个文件语法错误、连带 20 条"Unresolved reference" | 改写注释（编译期抓到，已修） |

> 3.9 / 3.10 说明"必须真编译"：这两处单靠阅读极难发现，前者上真机就是 100% 崩溃。

---

## 4. 性能优化

| # | 位置 | 问题 | 优化 |
|---|---|---|---|
| 4.1 | `DshRuntime._logs` → 新增 `DshLogBus` | ①`StateFlow<List<String>>` 每来一行 `(it + line)` **复制整表**；②日志页每行 `joinToString` 500 行再塞进 TextView；③每行 `fullScroll`。npm 输出上万行 → **O(n²) + UI 卡死** | 环形缓冲 + **~5Hz 合并刷新**（revision 递增，UI 只比较 revision）；日志页仅在贴底/开关打开时滚动；同时**落盘** `logs/runtime.log`（1MB 截断） |
| 4.2 | `DshInstanceAdapter` | `notifyDataSetChanged()` 全量重绘（安装中状态每秒变）→ 列表闪烁、点击被吞 | `DiffUtil`（实例列表 + 版本列表都换）；安装状态只刷新受影响行 |
| 4.3 | 实例列表体积 | 原为每行在绑定里 `walkTopDown` 扫 300MB 目录 | 体积缓存（key=id:版本:状态）+ 去重 + 异步回填；设置页同样走缓存 |
| 4.4 | `DshRegistry`/`DshDownloadViewModel` | 每次进下载页都打 registry；`future.get()` **无超时**，弱网永远转圈 | 10 分钟内存缓存（`fetchVersionsCached`）+ 20s 超时 + 失败回退旧数据并提示 |
| 4.5 | `setup-node-dsh.sh` | 每个实例独立 npm 缓存 → 重复安装重复下载整棵依赖树 | 新增共享 `npm-cache/`（bind 进 rootfs），`--prefer-offline` |
| 4.6 | `DshInstances.save` | 在调用线程（含主线程）写 JSON | 统一异步写 + 串行化 + 原子替换 |
| 4.7 | `ProotProcessExecutor` | 两份 proot 命令行各写一遍且已漂移（bind 项/工作目录/白名单不一致） | 抽出 `ProotCommand` 单一构造处；命令一律数组参数（无字符串拼接注入风险） |

---

## 5. 可靠性 / 安全加固

| # | 位置 | 问题 | 修复 |
|---|---|---|---|
| 5.1 | `DshCredentials` | 启动前把**明文 key 写进 `credentials.env`**，停止时再删——崩溃/被系统杀掉就永久留在数据目录；且 `File.setReadable/setWritable` 在 Android 上基本无效（返回值被忽略），是**假加固** | 改为经**子进程环境变量**注入（`ProotCommand.Spec.procEnv`），明文不落盘；保留脚本的 `CRED_FILE` 分支供 Termux 手工用；`cleanupLegacyFiles()` 自动清理历史明文残留 |
| 5.2 | `ProotCommand` | 密钥曾随 `/usr/bin/env DEEPSEEK_API_KEY=...` 进 **argv** → 任何能看 `ps` 的进程都能读到 | 敏感项只走 `ProcessBuilder.environment()`；argv 只放非敏感配置 |
| 5.3 | 日志 | dsh 启动行含 `?token=XXXX`，原样进了日志 UI | `DshLogBus.sanitize()` 对**所有**入队/落盘行做脱敏（`token=***`），并把 token/API key 注册给 FCL 的 `Logging` 做二次兜底；日志只存 `pid`/`port`，不存 URL |
| 5.4 | `DshCredentials` 密文 | 密文格式无版本、无 AAD：拷到别的实例也能解出 | 密文加**版本字节**，并以 `instanceId` 作为 **GCM AAD**（跨实例粘贴必然解密失败）；`status()` 区分"未配置 / 可用 / 解不开" |
| 5.5 | `hasCredential` | 只看文件存在 → 换机恢复/改锁屏后 Keystore 主密钥失效，界面仍显示"已配置"，用户完全摸不着头脑 | 改为**试解密**判定；启动时若"解不开"明确提示重新录入 |
| 5.6 | `DshRuntimeService` | 前台类型用 `dataSync`，**Android 15 起 dataSync 有每天 6 小时上限**——对"长时间挂着的 agent"是硬伤 | 增加 `specialUse` 类型与 `PROPERTY_SPECIAL_USE_FGS_SUBTYPE` 说明，运行时优先 `specialUse`，失败回退 `dataSync` |
| 5.7 | `DshWebViewActivity` | 未限制 `allowFileAccess`/`allowContentAccess`，站外链接会在 WebView 内导航 | 关闭文件/内容访问、禁用多窗口、混合内容 `NEVER_ALLOW`；**非回环链接交给系统浏览器**；cookie 显式 `flush()`；销毁前先从父容器移除再 `destroy()`（原来直接 destroy 有崩溃风险） |
| 5.8 | `ProotCommand.preflight` | proot 二进制缺失/W^X 不可执行、rootfs 不完整时，用户只会看到 proot 的晦涩报错 | 启动/安装前**预检**并返回人话（"请把 proot 放进 jniLibs"、"rootfs 可能多套了一层目录"） |
| 5.9 | `DshBootstrap` | rootfs.tar.xz 若带顶层目录（`ubuntu/...`）→ 解压多套一层，`proot -r` 指错根，且无任何提示 | 解压后检测 `/bin/sh`；若只有单个顶层目录**自动上提**；仍不可用则明确报错 |
| 5.10 | `DshBootstrap` | 不检查磁盘空间，解压到一半失败留下半个 rootfs | 解压前检查可用空间（<1.5GB 直接给出数字提示）；rootfs 解到 `rootfs.tmp` 校验后**原子替换** |
| 5.11 | `DshBootstrap` | 只比对 `version` 文件就判定就绪——**空壳也会被认为可用** | 新增**自检探针** `probe.sh`：真在 proot 里跑一条命令验证整条链路（这是"第一次真正执行 proot"的地方，能在装 dsh 之前暴露 W^X/loader/rootfs 布局问题） |

---

## 6. 使用逻辑完善（交互层）

1. **实例列表**：顶部"下载 / 日志"；每实例"启动|停止 + 更多（设置 / 日志 / 重新安装 / 删除）"；
   安装中显示**阶段文案 + 进度条**；损坏实例直接显示失败原因；删除中显示"删除中"并禁用操作。
2. **新增实例设置页**：API Key（录入/脱敏回显/测试连接/清除）、实例名、模型
   （`deepseek-flash`、`deepseek-v4-pro`）、profile（`web`、`headless`）、端口（留空=自动）、
   已装版本、占用体积、安装路径、运行时自检、重新安装、日志、删除。
3. **首启引导**：底座未就绪 → 顶部横幅 + 一键准备（带进度对话框，实时显示阶段）。
4. **启动前检查**：未配置 key → "去配置 / 仍然启动"；key 解不开 → 提示重新录入（新增
   `DeepSeekApi.verifyKey`，可区分密钥无效与网络问题）。
5. **失败有出口**：启动失败弹原因 + 一键"查看日志 / 准备运行时"；进程意外退出提示退出码；
   安装失败在列表行内显示原因并可"重新安装"。
6. **热恢复**：冷启后自动尝试**认领**仍在运行的实例，直接进界面（不必重启 300MB 进程）。
7. **日志页**：运行状态行、自动滚动开关（尊重手动滚动位置）、复制全部、落盘路径。
8. **下载页**：刷新按钮（走缓存）、断网回退提示、"安装中"按钮态（防连点建多个实例）、
   装完弹"去实例列表看进度 / 留在此页"。
9. **文案**：新增约 80 条中英文案（`values/strings.xml` + `values-zh/strings.xml`）。

---

## 7. 脚本层改造（`assets/dsh/scripts/`，版本号 1 → 2）

### `start-dsh.sh`（重写）
- 原实现 `exec node ...`：启动器**只能靠第三方日志格式**判断就绪，格式一变就抓瞎；失败无机器可读信号；
  信号无法转发。
- 现在：后台起 node → **按日志里的 token URL 判定就绪**（实测：端口先 bind、URL 后打印，
  所以端口不能作为主判据）→ 打印 `[start-dsh] READY url=...` / `READY port=...`；
  失败打印 `FAILED reason=node-exit|package-missing|ready-timeout` 并非 0 退出；
  支持 `PORT=0`；`trap` 把 TERM/INT 转发给 node（避免孤儿进程）；
  输出按**字节偏移**透传（不截断日志，保证后面还能抓到 URL）。

### `setup-node-dsh.sh`
- 打印机器可读 `STAGE=...`（界面据此显示阶段）；补 `npm` 存在性检查（原来只查 node，
  某些精简 rootfs 里 node 有 npm 没有，会死在 `npm: not found`）；
  共享 npm 缓存 + `--no-fund --no-audit --prefer-offline`；
  装完**硬校验**（必须解析出版本 + `lib/bin.js` 存在），否则非 0 退出。

### `probe.sh`（新增）
首启自检探针：打印内核/架构/node 版本，输出 `dsh-probe-ok`。由 `DshBootstrap.verify()` 调用。

---

## 8. 验证记录（可复现）

| 项目 | 方法 | 结果 |
|---|---|---|
| **全量真编译** | `./gradlew :FCL:compileDebugKotlin -Darch=arm64 --offline` | **BUILD SUCCESSFUL**，dsh 文件 **0 error / 0 warning** |
| 编译期抓到的真实缺陷 | 同上（首轮编译输出） | 4 处：嵌套注释导致整个文件语法错误、`Process.pid()` 在 Android 运行时不存在、`emptyMap()` 类型推断、`URL as HttpURLConnection` 必崩强转 → 全部已修 |
| **脚本真跑（场景 A）** | 固定端口 3081，真实 dsh 包 | 15s 出 `READY url=...`；带 token 访问 **200**、无 cookie **401**；运行中即可看到透传日志；TERM 后脚本退出码 0、端口释放 |
| **脚本真跑（场景 B）** | `PORT=0` | 自动分配 40233，从 URL 回读成功 |
| **脚本真跑（场景 C）** | 实例目录无 dsh 包 | 立即 `FAILED reason=package-missing`，退出码 1（不再干等到超时） |
| **脚本真跑（场景 D）** | `probe.sh` | `dsh-probe-ok`，退出码 0 |
| **逻辑真跑（Java 直连编译产物）** | 30 条断言：semver / registry 解析排序 / tag 优先级 / 体积格式化 / URL 与 token 解析（含 LAN 变体、PORT=0、脚本标记）/ 日志脱敏 | **30 / 30 通过** |
| **单元测试（JVM）** | 新增 `FCL/src/test/java/com/dsh/DshCoreLogicTest.kt`，用 kotlinc 编译并反射执行 14 个用例 | **14 / 14 通过** |
| 真实 dsh 行为探测 | token→cookie、cookie 跨进程重启、`--port 0`、`dsh web --help` | 均确认（结论见 §1） |
| 静态一致性 | 布局 id ↔ 代码引用、strings（en/zh）齐全、Manifest 注册 | 经真编译校验通过 |

> 关于单元测试：`gradlew testFordebugUnitTest` 在本沙箱**离线环境**下卡在 `kspFordebugUnitTestKotlin`
> （测试变体的 KSP 无法离线解析依赖，与本次改动无关）。因此改用 kotlinc 直接编译该测试文件并用
> 桩 JUnit 反射执行。在有网络的机器上直接跑 `./gradlew :FCL:testFordebugUnitTest --tests "com.dsh.*"` 即可。

---

## 9. 兼容性与迁移

- **`instances.json`**：新增字段（`port=0`、`updatedAt`、`lastError`）有默认值，旧文件可读；
  旧实例的 `port=3080` 照常可用（本次只把**新建**实例默认改为自动分配）。
- **`credentials.env`**：改版后不再产生；已存在的会在下次写入/清理凭据时被删掉，并在日志里留一行说明。
- **底座重解压**：`assets/dsh/version` 与 `scripts/version` 已从 1 提到 **2**，首启会按 `isLatest` 增量重解
  （proot 与 rootfs 子项未变，不会重复解压大文件）。
- **proot 路径**：`resolveProotBin/Loader` 现在容忍 `FCLPath.NATIVE_LIB_DIR` 为空（原来会 NPE），
  jniLibs 优先、assets 回退不变。
- **状态语义**：`INSTALLING` 不再可能长期残留——App 每次启动都会按磁盘内容校准。

---

## 10. 仍未完成 / 需要真机验证

1. **两类平台大文件仍未进仓库**：proot 二进制（推荐 jniLibs 的 `libproot.so` / `libproot_loader.so`）
   与 `rootfs.tar.xz`。代码链路、自检与失败提示已就绪，放入后即可跑（见 `../PACKAGING.md`）。
2. **真机项**：WebView 实际加载与 cookie 持久化、前台服务在 Android 14/15 上的 `specialUse` 生效、
   Keystore 加解密、认领孤儿进程（需真机杀进程复现）。
3. 安装耗时/进度百分比：目前阶段文案可靠，**百分比不做假进度**（npm 输出无稳定口径），
   因此进度条在无法估算时显示为不确定态。
4. 未做（有意留到下一步）：下载断点续传、Landlock 弱隔离的显式提示、实例配置导出/导入。

---

## 11. 文件清单（本轮新增 / 改动）

### 新增（7 个 Kotlin + 2 个布局 + 1 个脚本 + 1 个测试）
| 文件 | 说明 |
|---|---|
| `core/DshLogBus.kt` | **日志总线**：环形缓冲、~5Hz 合并刷新、落盘（1MB 截断）、token/密钥脱敏 |
| `core/DshAppScope.kt` | 进程级协程作用域（长任务不再随界面销毁） |
| `core/DshServices.kt` | 进程级服务定位器（安装器单例，单飞控制才真正生效） |
| `core/ProotCommand.kt` | **proot 命令行唯一构造处** + 预检 + 环境变量白名单 + 密钥不进 argv |
| `core/DeepSeekApi.kt` | DeepSeek `/models` 密钥校验（显式超时；区分 401/网络错误） |
| `ui/DshSettingsActivity.kt` | **实例设置页**（补齐 API Key 录入等缺失入口） |
| `res/layout/activity_dsh_settings.xml` | 设置页布局 |
| `res/layout/view_dsh_bootstrap_banner.xml` | 运行时未就绪横幅（实例列表页 include） |
| `assets/dsh/scripts/probe.sh` | 运行时自检探针 |
| `FCL/src/test/java/com/dsh/DshCoreLogicTest.kt` | dsh 纯逻辑 JVM 单测（14 例） |


### 重写（改动量大）
`core/DshInstances.kt`（状态自愈/异步原子写/id 唯一/删除异步）、`core/DshRuntime.kt`（状态机/超时/认领孤儿/端口回读/密钥走环境变量）、
`core/DshInstaller.kt`（真校验/单飞/超时/阶段进度）、`core/DshCredentials.kt`（不落明文/AAD/状态区分）、
`core/DshPaths.kt`（workspace/npm-cache/logs/tmp + 磁盘助手 + 容错）、`core/DshBootstrap.kt`（自检/原子替换/hoist/空间检查）、
`core/DshRuntimeService.kt`（自收尾/specialUse/通知可停）、`core/DshRegistry.kt`（缓存/version 兜底/+build 剥离）、
`core/DshDownloadViewModel.kt`（缓存/超时/不泄漏 collector）、`core/DshVersionListItem.kt`（tag 优先级）、
`core/DshInstance.kt`（port=0/updatedAt/lastError/常量）、`ui/*`（6 个界面全部按新状态机与入口改造）、
`assets/dsh/scripts/start-dsh.sh`、`assets/dsh/scripts/setup-node-dsh.sh`。

### 其他改动
- `AndroidManifest.xml`：注册 `DshSettingsActivity`；运行服务加 `specialUse` 类型 + 属性说明；
  新增 `FOREGROUND_SERVICE_SPECIAL_USE` 权限。
- `res/values/strings.xml`、`res/values-zh/strings.xml`：新增约 80 条文案（en/zh 同步）。
- `assets/dsh/version`、`assets/dsh/scripts/version`：1 → 2（触发增量重解）。
- 布局 6 个全部更新（新 id、安装进度、失败原因、状态面板、日志工具栏等）。

---

## 12. 变更速查（按"你会关心的问题"索引）

| 你想知道 | 看这里 |
|---|---|
| 为什么以前装了用不了 | §2.1（没有 key 入口）、§2.3（底座没准备）、§2.4（假成功） |
| 为什么以前会卡死/白屏 | §3.1（界面退出中断安装）、§3.5（孤儿进程占端口）、§2.5（WebView 无失败出口） |
| 为什么以前卡顿/发热 | §4.1（日志 O(n²)）、§4.2（全量重绘）、§4.3（扫目录算体积） |
| 密钥/令牌安全吗 | §5.1–5.5、§5.3（token 脱敏）、§7（脚本不再依赖明文文件） |
| 多实例/端口怎么定的 | §3.6（默认自动分配 + 回读） |
| Android 14/15 上能长期跑吗 | §5.6（specialUse）、§3.7（通知可停、进程退出自动收尾） |
| 怎么验证是好的 | §8（编译 + 脚本 4 场景 + 30 断言 + 14 单测） |
| 下一步做什么 | §10（补两个大文件 → 真机联调清单） |
