# MC Removal 变更影响审查报告

> **本报告为静态审查级别，未含真机/CI 验证。**
> 已完成的可运行验证：clean build（Gradle，**34 tasks executed**，非增量）× 补丁前后各一次 + JVM 单测 **23/23** + 资源自洽性脚本 + 一系列 grep/可达性扫描。
> **未做**：真机安装与运行、`assembleFordebug` 出包、dex 合并、任何运行时日志采集。凡标「待运行确认」的结论均未在设备上验证过。

> ## ⚠️ 工作区保护（最高操作风险）—— 已于 2026-10-01 解除
> 送审时 `/workspace/FCL` 有 **11 项 untracked 条目**，包含整个 `com/dsh` 主体
> （25 源文件 + 10 assets + 8 布局 + 2 测试）。**任何** `git clean -fd`、`git checkout -- .`、
> `git reset --hard`、IDE 的「revert」都会**不可恢复地**删除它们（`git checkout` 救不回 untracked）。
> **✅ 已固化：`4aea9e5`**（`git add -A && git commit`，基线 `f4f2624`），工作区现为干净；
> 回滚从「危险手工操作」变为一条 `git revert 4aea9e5`。
> 改动前另已把 **45 个 untracked 文件**备份到 `/workspace/_review_evidence/untracked_backup/`。
> 详见 §15。

> ## ✅ 本次审查已落地：补丁 A / B + 8 处文档批注
> | 提交 | 内容 |
> |---|---|
> | `4aea9e5` | 审查对象固化（1284 files, +6779 −226529）—— 保护 untracked，使回滚可行 |
> | `a62ed0d` | **补丁 A + B**（3 文件，+31 −4）：M-01 `FCLPath` 上提、M-02 `LOG_DIR` 迁私有目录 |
> | `f4f2624` | 基线（上游 main） |
> 文档批注：`mc-removal.md` ×5、`brief` ×7、`INDEX.md` ×1（覆盖 **D-1 ~ D-8 全部 8 处**）。
> 补丁后复验：clean build **34/34 executed, 0 error**；单测 **23/23**。

---


## 0. 审查范围、置信度与未覆盖项

### 0.1 已读材料（全部存在、可读，无缺失）

| # | 材料 | 状态 | 核对结果 |
|---|---|---|---|
| 1 | `/workspace/docs/reports/mc-removal-review-brief.md` | ✅ 371 行 | 与声称一致 |
| 2 | `/workspace/docs/reports/mc-removal.md` | ✅ 487 行 | 行数与 brief §0 声称的 469 行不符（P3-6） |
| 3 | `/workspace/docs/INDEX.md` | ✅ 90 行 | 「当前状态」节已反映本次变更 |
| 4 | `/workspace/FCL` 仓库 | ✅ 可读 | 分支 `main`，浅克隆，基线 `f4f2624` |
| 5 | 基线 commit / 变更统计 | ✅ 复现 | `git diff --shortstat HEAD` = **1239 files changed, 323 insertions(+), 226529 deletions(-)**，逐文件 insertions 求和 = 323，删除求和 = 226529，**完全自洽** |

**未提供也未存在的材料**（如实记录，不作推测）：patch/diff 文件、CI 配置、APK 产物、真机日志、崩溃报告、监控数据、issue/PR。`/workspace/dsh-fcl-arm64.apk` 是变更前旧包，未使用。

### 0.2 置信度分级说明

| 标记 | 含义 |
|---|---|
| **静态确证** | 由编译器/脚本/可枚举的代码事实直接证明，反例不存在（例：「零调用者」由全仓库 grep 得出） |
| **静态推断** | 由代码事实 + Android 平台行为推断，逻辑闭合但未经设备验证 |
| **待运行确认** | 依赖真机行为（ROM 差异、SELinux、存储挂载、native 行为），静态无法定论 |

### 0.3 未覆盖项及原因

| 未覆盖项 | 原因 |
|---|---|
| 运行时崩溃验证 | 无设备、无 APK（按项目约定未打包） |
| dex 方法数 / 65536 限制 | 需 `assembleFordebug`；minSdk 26 原生支持 multidex，风险低但未实测 |
| APK 体积与安装行为 | 未出包 |
| proot 二进制实际执行 | 需要 `libproot.so` / `libproot_loader.so`，仓库内不存在（`FCL/src/main/jniLibs/` 已删除，`assets/dsh/proot/` 只有 `PLACEHOLDER.txt`） |
| rootfs 解压与 Node 实际运行 | 需要 `rootfs.tar.xz`，仓库内不存在（`assets/dsh/rootfs/PLACEHOLDER.txt`） |
| `com/dsh` 业务逻辑缺陷 | **不在本次范围**（brief §6.3 明示，已由 round2~round5 覆盖） |
| fcllibrary 内部缺陷 | 本次变更未触碰 fcllibrary，不在范围 |

---

## 1. 变更点摘要

### 1.1 变更规模（实测，与文档一致）

| 状态 | 数量 | 文档声称 | 实测 |
|---|---|---|---|
| 删除 `D` | 1228 | 1228 | ✅ 一致 |
| 修改 `M` | 11 | 11 | ✅ 一致 |
| 未跟踪 `??` | 11 | 11 | ✅ 一致 |

`git diff --numstat` 自洽性核对（仅 11 个 M 文件有 insertions）：

```
FCL/build.gradle.kts                                    +1   -122
FCL/src/main/AndroidManifest.xml                        +35  -116
FCL/src/main/java/com/mio/util/AndroidUtil.kt           +0   -73
FCL/src/main/java/com/mio/util/DialogUtil.kt            +0   -15
FCL/src/main/java/com/tungsten/fcl/FCLApp.java          +4   -2
FCL/src/main/java/com/tungsten/fcl/activity/SplashActivity.kt  +20 -242
FCL/src/main/java/com/tungsten/fcl/util/RuntimeUtils.java      +8  -83
FCL/src/main/java/com/tungsten/fclcore/util/io/ChecksumMismatchException.java  +5 -2
FCL/src/main/res/values-zh/strings.xml                  +125 -1303
FCL/src/main/res/values/strings.xml                     +125 -1440
settings.gradle.kts                                     +0   -8
                                        insertions 合计 = 323 ✅
```

### 1.2 删除清单（按可枚举的路径）

| 类别 | 删除文件数（实测） | 文档声称 | 一致 |
|---|---|---|---|
| `FCL/src/main/java/**` 的 `.java`/`.kt` | **605** | 605 | ✅ |
| `FCL/src/main/res/**` | **282** | 282 | ✅ |
| `FCL/src/main/jniLibs/**` | **41** | 41 | ✅ |
| `FCL/src/main/jni/**` | **90** | 90 | ✅ |
| `FCL/src/main/jreAssets/**` | **23** | 23 | ✅ |
| `FCL/src/main/assets/**` | **80** | 80 | ✅ |
| `FCL/libs/*.aar` | **7** | 7 | ✅ |
| `LWJGL/**`（整模块） | **90** | 90 | ✅ |
| `Terracotta/**`（整模块） | **8** | 8 | ✅ |

目录级确认（实测）：
```
ls Terracotta LWJGL                                              → No such file or directory（两级目录均已不存在）
ls FCL/src/main/jniLibs FCL/src/main/jni FCL/src/main/jreAssets   → 三者均不存在
ls -A FCL/libs                                                    → 0 个文件（空目录）
find FCL/src -type d -empty                                       → 0 个（无空目录残留）
```

### 1.3 保留清单（实测计数）

| 路径 | 文件数 | 文档声称 | 一致 |
|---|---|---|---|
| `com/dsh` | 25 | 25 | ✅ |
| `com/tungsten/fcllibrary` | 60 | 60 | ✅ |
| `com/tungsten/fclcore` | 394 | 394 | ✅ |
| `com/tungsten/fcl` | 9 | 9 | ✅ |
| `com/tungsten/fclauncher/utils` | 2 | 2 | ✅ |
| `com/mio/util` | 4 | 4 | ✅ |
| `ZipFileSystem` | 12 | 12 | ✅ |
| **合计** | **506** | INDEX 称 494 | ⚠️ 见 P3-6（494 = 506 − ZipFileSystem 12，INDEX 的口径与自身分项表矛盾） |

- 保留依赖：实测 `FCL/build.gradle.kts` 的 `dependencies` 块与 brief §3.2「21 条」逐条一致；`chardet` 在（位置从 room 上方挪到 commons-compress 下方，仅顺序变化）。
- **ksp 插件**：`FCL/build.gradle.kts` 的 `plugins {}` 中已无 `alias(libs.plugins.ksp)`，`FCL/build.gradle.kts` 中也已无 `ksp { arg("room.schemaLocation", ...) }` 块 ✅。
- 保留 assets：仅 `assets/dsh/`（10 文件 / 70K）✅。

### 1.4 文档声称 vs 代码实际（差异汇总）

| # | 文档位置 | 文档声称 | 代码实际 | 影响 |
|---|---|---|---|---|
| D-1 | brief §5.2 / mc-removal §5.3 | SplashActivity「340 行 → 72 行」 | 实测 `git show HEAD:...SplashActivity.kt \| wc -l` = **286**，现文件 = **64** | 文档数字失真，不影响结论（P3-6） |
| D-2 | mc-removal.md 章节 | 有 §0~§12 | **缺 §6**（§5 直接跳 §7） | 引用「§6」会指向不存在的章节（P3-6） |
| D-3 | brief §7.D | 「`getIdentifier` 全仓库只有 1 处（LocaleUtils 取 `world_time`）」 | 实测 **3 处**：`LocaleUtils.java:130`（world_time，活的）、`AndroidUtil.kt:128`（`getLocalizedText`，**零调用者**）、`AndroidUtil.kt:138`（`hasStringId`，**零调用者**） | 结论仍成立（另 2 处不可达），但「只有 1 处」的表述不成立（P3-6） |
| D-4 | mc-removal §9.4.4 | 「`res/values-v31/`：**仅含** `icon_background_color` → 框架颜色，已核实安全」 | `values-v31/` 有 **2 个文件**：`colors.xml`（框架颜色，安全）+ **`themes.xml`（重定义整个 `Theme.FoldCraftLauncher` 与 `Theme.Splash`）** | 「仅含框架颜色」的判断**不成立**；裁决需重做（见 P2-6） |
| D-5 | mc-removal §9.4.6 | 「启动页布局原为**横屏设计**，已改为竖屏显示，视觉效果未验证」 | `activity_splash.xml` 与 HEAD **逐字节相同**，内容是 `match_parent` + `ConstraintLayout` + `FragmentContainerView`，**不含任何横屏专有元素**，方向中立 | 该「风险」是**虚惊**（P3-6） |
| D-6 | brief §7.C | 「保留 7 个权限与 **10 个组件**」 | 实测 **8 Activity + 1 Service + 2 Provider = 11 个组件**；权限 7 个 ✅ | 组件数少计 1（P3-6） |
| D-7 | INDEX「当前状态」 | 「494 个源文件 / 71,623 行」 | 实测 506 个（含 ZipFileSystem）/ **77,190 行** | 数字失真（P3-6） |
| D-8 | brief §0 | 「mc-removal.md（469 行，12 章）」 | 实测 487 行，`^## ` 标题 12 个（编号 0~12 缺 6） | 轻微（P3-6） |

**D-4 与 D-5 是本次审查新发现的实质性判断错误**：前者把一个「有内容的主题覆盖文件」当成「只有一行框架颜色」，后者把一个不存在的问题记成了待验证风险。

---

## 2. 影响面分析（对应 brief §7 的 A~H 八维度）

### A. 对保留代码的影响

| 项 | 结论 |
|---|---|
| **影响** | 编译期零残留；运行期有一处**被掩盖的空值依赖**（C-1）与一组**方法裁剪** |
| **证据** | ①clean build `BUILD SUCCESSFUL, 0 error`（34 tasks **executed**，非 up-to-date）②裁剪的 6 个方法全仓库零调用者：`installJna`/`installJava`/`patchJava`（仅 `RuntimeUtils.java:25` 的 KDoc 注释提及）、`checkElfIsAndroid`/`getElfArchFromZip`/`openLinkWithBuiltinWebView`/`showItemSelectionDialog`（grep 命中 0）③`ChecksumMismatchException` 父类由 `fclcore.download.ArtifactMalformedException` 改为 `java.io.IOException`——原父类所在的包已整包删除，故**不可能存在** `catch (ArtifactMalformedException)`，改动的影响面为空 |
| **置信度** | 静态确证 |
| **回滚难度** | 低（受跟踪文件，`git checkout --` 可恢复；但会连带丢失 `FCLApp.java` 的 dsh 初始化） |

### B. 对 App 启动流程的影响

| 项 | 结论 |
|---|---|
| **影响** | SplashActivity 由 286 行裁到 64 行，**成为启动流程的单点**；取消了存储权限门禁；`FCLPath` 初始化只剩这一条路径 |
| **证据** | `git diff`：`+20 -242`；删除内容实测为 8 项运行时门禁（`lwjgl/cacio/cacio17/java8/java17/java21/java25/jna`）、`initState()`（探测 version + 生成 `resolv.conf`）、`checkPermission()/requestPermission()/hasPermission()`、EULA Fragment 跳转、`handleModpack()`；新流程 `init()` → `FCLPath.loadPaths` + `Logging.start` → `enterDsh()` |
| **置信度** | 静态确证（改动内容）/ 静态推断（取消权限的安全性）/ **待运行确认**（视觉与首启行为） |
| **回滚难度** | 中（受跟踪文件可回滚，但回滚后 `com.dsh` 无入口，App 与当前 dsh 代码不兼容） |

**两点必须点明的观察（非本次引入，供决策参考）**：`SplashActivity.kt:36-37` 是 `super.onCreate()` 之后再调 `installSplashScreen()`——与 `androidx.core:core-splashscreen` 官方推荐顺序相反，会造成一帧主题闪烁。但**HEAD 的原始文件是同样的顺序**（`git show HEAD:...SplashActivity.kt` 第 63-64 行为 `super.onCreate` 然后 `installSplashScreen`），**不是本次引入的回归**，本次审查不作为问题项。

### C. 对 AndroidManifest / 权限的影响

| 项 | 结论 |
|---|---|
| **影响** | 删除 9 权限 / 6 Activity / 1 activity-alias / 2 Service / 1 Provider；保留 7 权限 / 11 组件；**删除存储权限导致 `FCLActivity` 的 `loadPaths` 分支恒假（C-1，本次最高优先级）** |
| **证据** | 见 §3 与 §7 第 5 问；`grep -E "MainActivity\|WebActivity\|ControllerActivity\|ShellActivity\|JVMActivity\|JVMCrashActivity\|ImportActivity\|ProcessService\|TerracottaVPNService\|FolderProvider" AndroidManifest.xml` → **0 命中**；`grep -c activity-alias` → **0**；11 个组件逐个核对类文件**全部存在**（含 `androidx.core.content.FileProvider` 来自 AAR） |
| **置信度** | 静态确证 |
| **回滚难度** | 低（单文件 116 行，`git checkout --` 即可；但会重新引入已删组件的入口，指向不存在的类 → **不可单独回滚**，必须整体回滚） |

### D. 对资源的影响

| 项 | 结论 |
|---|---|
| **影响** | 删 282 个资源文件；编译期自洽；**未发现**「插件/第三方按名字引用资源」的通道（无插件系统、无 RRO/skin 通道） |
| **证据** | ①clean build 通过 `processDebugResources`（aapt2 link 会拒绝任何悬空的 `@xxx` 或 `R.xxx`）②独立脚本复核：`@layout/@drawable/@anim/@xml/@mipmap` 引用**缺失 0**；`@string` 引用在 `values` 中**缺失 0**，`values-zh` 仅缺 `app_name`/`color_picker_arrow`（二者均 `translatable="false"`，**属正确行为**）③报「缺失」的 `style/Theme.SplashScreen`、`Widget.MaterialComponents.Button.*` 由 AAR 提供，非本仓库资源 ④全仓库孤立资源扫描：drawable/layout/anim/xml **无孤立项**；字符串仅 3 项「零引用」（`world_time` 为动态取用，`dsh_action_configure_key`/`dsh_bootstrap_failed` 为真孤立，属 `com/dsh` 范围）⑤动态引用实测 3 处，其中 2 处零调用者（D-3） |
| **置信度** | 静态确证 |
| **回滚难度** | 中（282 个受跟踪文件可 `git checkout` 批量恢复；但**未跟踪的 8 个 dsh 布局会被 `git clean` 波及**，必须先备份） |

### E. 对构建配置的影响

| 项 | 结论 |
|---|---|
| **影响** | 删除 CMake/NDK/prefab/bytehook/jreAssets 任务/LWJGL 裁剪逻辑/15 条依赖/ksp 插件；**残留 3 处无害冗余** |
| **证据** | ①clean build 实测通过，**无任何模块再声明 ksp**（`grep -rn "ksp" --include=*.kts --include=*.gradle` 仅命中过期的 `build/intermediates/**` 二进制缓存，非配置）②`ZipFileSystem/build.gradle.kts` 仅用 `android.library`，无 ksp ✅ ③无 `@AutoService`／无注解处理器产物引用 ✅ ④`gradle/libs.versions.toml` 仍保留 `jelf/taptargetview/nanohttpd/opennbt/tomlj/constantPoolScanner/jsoup/touchcontroller/paletteKtx/gamepadRemapper/segmentedButton/room/ksp/bytehook` 的 `[versions]` 与 `[libraries]` 条目，**含 `ksp = { id = "com.google.devtools.ksp" }`**（P3-2）⑤`splits { abi }` 保留，已无 native 库，`-Darch=arm64` 只影响 APK 文件名（P3-2）⑥`fileTree("libs","*.jar","*.aar")` 指向**空目录**（实测 `ls -A FCL/libs` = 0），空操作（P3-2） |
| **置信度** | 静态确证 |
| **回滚难度** | 低（单文件 122 行） |

### F. 对 dsh 功能的影响

| 项 | 结论 |
|---|---|
| **影响** | dsh 依赖的 6 个 FCL 类全部保留；**Node 侧协议零改动**（本次 diff 未触碰 `com/dsh` 与 `assets/dsh`，二者为 untracked） |
| **证据** | ①`com/dsh` 25 文件、`assets/dsh` 10 文件在 `git status` 中为 `??`，**不在本次 diff 内**（`git diff HEAD` 不显示它们）②保留的 6 个类实测存在：`R`/databinding（`com.tungsten.fcl.databinding.*` 由 viewBinding 生成，编译已证）、`FCLActivity`、`RuntimeUtils`（`isLatest`/`install`×2/`copyAssets`×2/`uncompressTarXZ`×2/`InstallListener` 均在）、`FCLPath`、`fclcore.util.gson.JsonUtils`、`fclcore.util.io.HttpRequest` ③`DshBootstrap` 调 `RuntimeUtils.isLatest/install` 与 `/assets/dsh/{proot,rootfs,scripts}` —— `assets/dsh/` 目录完整（实测 10 文件） |
| **置信度** | 静态确证 |
| **回滚难度** | — （不受影响） |

### G. 对「未提交成果」的风险

| 项 | 结论 |
|---|---|
| **影响** | **最高实际风险不在代码，而在工作区状态**：11 个 untracked 条目包含整个 dsh 主体 |
| **证据** | `git clean -nd` 列出将被删除的 11 项：`FCL/src/main/assets/dsh/`、`FCL/src/main/java/com/dsh/`、8 个 `activity_dsh_*.xml`/`item_dsh_*.xml`/`view_dsh_bootstrap_banner.xml`、`FCL/src/test/java/com/dsh/` —— 任何 `git clean -fd` 或 IDE 的「revert」会**不可恢复地**删除它们（`git checkout` 救不回 untracked） |
| **置信度** | 静态确证 |
| **回滚难度** | **不可回滚**（无备份时）。本次审查已在 `/workspace/_review_evidence/` 留存部分构建产物证据；**强烈建议先 `git add -A && git commit`** |

### H. 对「其他文档」的影响

| 项 | 结论 |
|---|---|
| **影响** | `docs/` 多份文档按旧形态描述，且 **`mc-removal.md` 自身有 4 处事实性错误**（D-1/D-2/D-4/D-5） |
| **证据** | brief §7.H 已自认 `PLAN.md`/`PACKAGING.md`/`design/*`/`reports/round2~5` 过时；本次审查另外发现 mc-removal.md 与 brief 自身也有 8 处数字/事实不符（见 §1.4） |
| **置信度** | 静态确证 |
| **回滚难度** | — （文档） |

---

## 3. 对 C-1 的判定与落地建议

### 3.1 事实基础（全部静态确证）

```java
// FCL/src/main/java/com/tungsten/fcllibrary/component/FCLActivity.java:47-56
47:  boolean hasPermission;
48:  if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
49:      hasPermission = Environment.isExternalStorageManager();
50:  } else {
51:      hasPermission = ActivityCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED && ...
52:  }
53:  if (hasPermission) {
54:      FCLPath.loadPaths(this);
55:  }
56:  fileLauncher = new FileBrowserLauncher(this);
```
`MANAGE_EXTERNAL_STORAGE` / `READ|WRITE_EXTERNAL_STORAGE` 已从 Manifest 删除（实测新 Manifest 只剩 7 权限，无存储权限）→ `isExternalStorageManager()` 恒 false、`checkSelfPermission` 恒 DENIED → **第 54 行不可达**。

`FCLPath.loadPaths` 的**实际调用点仅 3 处**（全仓库 grep `loadPaths`）：
| 位置 | 状态 |
|---|---|
| `FCLActivity.java:54` | 恒不可达（上述） |
| `SplashActivity.kt:49` | **唯一有效调用**，且排在所有 dsh Activity 之前 |
| `DshPaths.loadPaths`（同名不同类） | 无关 |

`FCLPath` 静态字段的**全部消费点**（全仓库 grep `FCLPath\.`）：
| 位置 | 字段 | 空值防护 |
|---|---|---|
| `DshBootstrap.kt:77` | `NATIVE_LIB_DIR` | ✅ `resolveProotBin` 内 `isNullOrEmpty()` |
| `ProotCommand.kt:63` / `:114` / `:145` | `NATIVE_LIB_DIR` | ✅ 同上 |
| `ThemeEngine.kt:174,177` | `LT/DK_BACKGROUND_PATH`（`File(...)` 参数，null 会 NPE） | ❌ 无防护，但在 `runCatching` 内 |
| `ThemeEngine.kt:180,182` | `LT/DK_BACKGROUND_PATH`（传给非空形参 `ImageUtil.load(path: String)`） | ❌ **无防护且不在 runCatching 内** |
| `SplashActivity.kt:50` | `LOG_DIR` | ❌ 无防护（但同方法内先调 `loadPaths`，故有序保证） |

### 3.2 四个必答子问题

#### 子问题 1：所有非 Splash 入口是否会撞上未初始化状态？

| 入口 | 是否绕过 SplashActivity | 触发后 `FCLPath` 状态 | 是否崩溃 | 依据 |
|---|---|---|---|---|
| LAUNCHER（点图标） | 否 | 已初始化 | 否 | Manifest `intent-filter MAIN/LAUNCHER` |
| **通知 PendingIntent** | **是** | **未初始化**（null） | **否**（靠 dsh 的 null 防护） | `DshRuntimeService.kt:158-163` `Intent(context, DshInstancesActivity::class.java)` + `PendingIntent.getActivity`，`FLAG_ACTIVITY_NEW_TASK`；`DshInstancesActivity` 是 exported=false 的 Activity，被本 App 自己创建的 PendingIntent 唤起是允许的 → **冷启动直达实例列表页** |
| ACTION_VIEW 深链 | **不存在** | — | — | 承载深链的 `activity-alias .ImportActivity`（含 `file/content` + `zip/mrpack/7z` + `BROWSABLE`）**已被删除**，新 Manifest `activity-alias` 计数 = 0 |
| BOOT_COMPLETED / 任何 receiver | **不存在** | — | — | 新旧 Manifest 均无 `<receiver>` |
| 外部分享（ACTION_SEND 落进来） | 不存在 | — | — | 无对应 intent-filter（原先也只有 `ImportActivity` 的 VIEW，非 SEND） |
| 崩溃页 | 是（进程内拉起） | 视崩溃时机 | 否 | `CrashReporter` 的 `UncaughtExceptionHandler` 直接 `startActivity(CrashReportActivity)`；`CrashReportActivity` 不读 `FCLPath` |
| dsh 内部 `startActivity` | 否（都在 Splash 之后） | 已初始化 | 否 | `DshInstancesActivity.kt:68,76,79,149,224,247,291,305` 等均为 App 内跳转 |
| 进程回收后恢复 | **取决于恢复的是哪个 Activity** | `Application.onCreate` 会重跑 → `DshPaths` 就绪，但 **`FCLPath` 仍只有 Splash 路径会初始化** | 否 | 系统恢复的是栈顶 Activity（通常是 `DshInstancesActivity` 或 `DshWebViewActivity`）→ 与通知路径同构 |
| 测试环境直接实例化 Activity | 是 | 未初始化 | **可能崩溃** | Robolectric/仪器测试直接 `ActivityScenario.launch(DshInstancesActivity)` 不经过 Splash；当前仓库无此类测试（`src/test/java/com/dsh` 是纯 JVM 单测），但**将来加仪器测试就会踩** |

**裁决：撞得上，但当前不会崩。** 理由：`FCLPath` 的唯一非空消费方（dsh 的 proot 路径解析）**已经做了 null 容忍**，会静默回退到 `assets/dsh/proot/`；而唯一不容忍 null 的消费方（`ThemeEngine.applyBackground`）是**死代码**（见 §4）。
**置信度**：代码事实「通知冷启动绕过 Splash」= **静态确证**；「因此不崩溃」= **静态推断**（依赖 dsh 的 null 防护持续存在）。
**这是本次审查认定最值得采纳的一条**：一个「只有在特定入口才暴雷」的隐式契约，成本是 1 行代码。

#### 子问题 2：`loadPaths()` 是否幂等？Splash 里那一次是否应删？

**幂等：是，静态确证。** `FCLPath.loadPaths`（`FCLPath.java:46-104`）由「纯赋值」+ `init(path)` 组成，而 `init` 是 `if (!new File(path).exists()) new File(path).mkdirs();` —— 无追加、无计数、无状态累积，重复调用结果完全相同。
传参差异也已核对：`SplashActivity` 传 Activity context、`FCLApp` 会传 Application context，但 `getFilesDir()`/`getCacheDir()`/`getDir()`/`getExternalFilesDir(null)`/`getApplicationInfo().nativeLibraryDir` 在 Activity 与 Application 上**返回值相同**（同包同 UID），故两次调用的赋值结果一致。

**建议：应删 `SplashActivity.kt:49`，保留 `:50`。** 理由：
1. 消除「两处调用」——否则未来任一处改了参数/顺序，另一处不受影响，正是这类隐式依赖的成因；
2. 删掉后只剩 `FCLApp.onCreate` 一处，**语义唯一**；
3. `Logging.start(Paths.get(FCLPath.LOG_DIR))` 仍留在 Splash（它是展示层行为，且依赖 `LOG_DIR` 已就绪——由 `FCLApp.onCreate` 保证）。

⚠️ **顺序陷阱（必须在实施时注意）**：`FCLApp.onCreate` 里 `FCLPath.loadPaths(this)` 必须排在 `Logging`/`DshPaths` 的**前面或与其并列**，且**不能**放在任何「后台 init」之后——`SplashActivity.init()` 在 `async(Dispatchers.IO)` 完成后立刻 `enterDsh()`，若 `FCLPath` 的初始化被挪到异步，`Logging.start` 就会读到 null 的 `LOG_DIR`。

**代价说明（必须写入 PR 描述）**：`FCLPath.loadPaths` 会做约 19 次 `exists()/mkdirs()` 磁盘 I/O。它在 `FCLApp.onCreate`（主线程）执行，而当前在 `Dispatchers.IO` 上执行。19 个目录判存通常在毫秒级，同文件里 `DshPaths.loadPaths(this)` **已经是主线程同步执行**并有相同模式，故可接受；若想保守，可只把 `NATIVE_LIB_DIR`（纯内存赋值，零 I/O）提前，其余留在原处——但**不推荐**，那会重新制造两段式初始化。

#### 子问题 3：在 `FCLApp.onCreate` 内，`loadPaths()` 是否排在 ContentProvider / WorkManager / 通知通道之前？

**这里有一个必须澄清的平台事实，静态确证：ContentProvider 的 `onCreate()` 早于 `Application.onCreate()`。**

实测本 App 只有一个 Provider 参与启动期初始化：
```
AndroidManifest.xml:  <provider android:name="com.tungsten.fcllibrary.crash.CrashReporterInitProvider"
                                 android:initOrder="101" />
CrashReporterInitProvider.java:12-15:
    public boolean onCreate() {
        CrashReporter.install(getContext());   // ← 只做的事：Thread.setDefaultUncaughtExceptionHandler
        return false;
    }
```
`CrashReporter.install` 全程**不读 `FCLPath`、不读 `DshPaths`**（实测其 700+ 行内无这两个符号）→ **把 `loadPaths` 放进 `FCLApp.onCreate` 即可，无需也无法排到 Provider 之前；本仓库也不需要。**

| 初始化项 | 相对 `FCLApp.onCreate` | 是否使用 `FCLPath` |
|---|---|---|
| `CrashReporterInitProvider`（唯一 ContentProvider） | **之前** | 否 ✅ |
| `Application.attachBaseContext`（`instance = this`） | 之前 | 否 |
| `registerActivityLifecycleCallbacks` | 同方法，当前在 `loadPaths` 之前 | 否 |
| `DshPaths.loadPaths` / `DshInstances.init` | 同方法，**同一行区域** | 否（另一套路径） |
| WorkManager / JobScheduler | **不存在**（实测 grep `WorkManager\|JobScheduler` → 0 命中） | — |
| 通知通道 | 不是 App 启动期行为，而是 `DshRuntimeService.createChannel` 在**服务启动时**惰性创建（`DshRuntimeService.kt:191-201`）→ 必然晚于 `Application.onCreate` ✅ | 否 |

**建议顺序**（`FCLApp.onCreate` 内）：
```java
super.onCreate();
this.registerActivityLifecycleCallbacks(this);
com.tungsten.fclauncher.utils.FCLPath.loadPaths(this);   // ← 新增，必须在 DshPaths 之前/并列
com.dsh.core.DshPaths.loadPaths(this);
com.dsh.core.DshInstances.init();
```
**置信度**：静态确证（Provider 早于 Application 是 Android 文档化行为；「Provider 不用 FCLPath」是 grep 事实）。

#### 子问题 4：`DshPaths.resolveProotBin` 的注释语义应如何改？

**应改为「防御性保护」，且必须保留判空。** 现状（`DshPaths.kt:151-152`）：
```kotlin
* @param nativeLibDir 传入 FCLPath.NATIVE_LIB_DIR；该字段可能为空（FCLPath 未初始化），
*                     这里容忍 null 并直接走回退路径（原来会 NPE）。
```
问题：`「FCLPath 未初始化」`在修复后**不应再是常态**，注释会把「设计意图」写成「已知缺陷」。

**建议改法（保持代码逻辑逐字不变）**：
```kotlin
* @param nativeLibDir 传入 FCLPath.NATIVE_LIB_DIR。正常情况下该值已由 FCLApp.onCreate 初始化；
*                     此处仍容忍 null/空（例如直接实例化 Activity 的测试、或工具类被单独调用），
*                     并回退到 assets 解压目录 —— 属防御性保护，不是对未初始化状态的兜底。
```
**为什么不能删判空**：即使 `FCLPath` 已初始化，`nativeLibDir` 也**可能合法地不含 `libproot.so`**——当前仓库 `FCL/src/main/jniLibs/` 已删（实测不存在），proot 二进制尚未就位，`resolveProotBin` 的 `if (inJni.isFile)` 判空/判存分支正是 assets 回退方案的正常工作路径。**删掉判空会立刻破坏 assets 方案**。这一条与 C-1 的修复**互不替代**。

### 3.3 修复建议（可直接落地的补丁）

见 §11。要点：**1 行新增 + 1 行删除 + 1 段注释改写，零依赖变动，零新增文件。**

---

## 4. 对「虚惊项」的双标结论

### 4.1 `ThemeEngine.applyBackground` 的 `FCLPath` 空指针风险

| 维度 | 结论 |
|---|---|
| ✅ 当前无影响 | **成立，静态确证。** 唯一调用者 `ThemeEngine.kt:219 applyAndSave(context, view, lt, dk)` 在**全仓库零调用者**（`grep -rn "applyAndSave" FCL/src/main/java FCL/src/main/res`，排除 `ThemeEngine.kt` 自身 → 0 命中）。危险行 `ThemeEngine.kt:180,182`（`ImageUtil.load(FCLPath.LT_BACKGROUND_PATH)`，非空形参、不在 `runCatching` 内，Kotlin 会插入 `checkNotNullParameter` → 传 null 抛 NPE）因此不可达 |
| ⚠️ 复活风险 | **确认为真风险。** 缺口有三重：①`delete`/`catch` 都不在 `runCatching` 内（只有 `:172-179` 的文件拷贝在）②形参 `ltPath/dkPath` 已做 `String?` 判空，说明作者**知道**这里可能是 null，却漏了 `FCLPath` 的两个字段 ③一旦新增「自定义背景」设置项并调用 4 参 `applyAndSave`，只要 `FCLPath` 未初始化就必 NPE |
| **反射核验** | ✅ 未发现反射调用。`grep ThemeEngine` 交叉 `forName\|reflect\|getClass` → **0 命中**；Manifest `<meta-data>` 仅 1 条（`android.support.FILE_PROVIDER_PATHS` → `@xml/provider_paths`，与类加载无关）；无 `<provider>`/`<service>` 按名加载 `ThemeEngine` |
| **是否 public API** | **否。** ①无插件系统残留（`com.mio.plugin.*` 与 `FCLLibrary` 独立模块均已删除）②Kotlin `fun applyAndSave`（`ThemeEngine.kt:219`）是 Kotlin public，但 ABI 只对**编译期**可见；因为**没有任何外部调用者**，不存在「消费者」概念 ③`ThemeEngine.kt:171 applyBackground` 是 `private`，不可从外部触达 |
| **修法建议** | 最小改动：把 `ThemeEngine.kt:180-183` 移入上一段 `runCatching`，或改成 `FCLPath.LT_BACKGROUND_PATH?.let{ ... } ?: ConvertUtils.getBitmapFromRes(...)`。**但因为它是死代码，不建议本次为它单独动刀**；若采纳 §11 的 C-1 修复（`FCLPath` 在 `Application.onCreate` 就绪），这个 ⚠️ 会自动消解 |
| **置信度** | 静态确证（死代码）/ 静态推断（复活后会 NPE） |

### 4.2 `values-v31/` 的安全性判定

**⚠️ 原始判定不成立，必须重做。**

`mc-removal.md §9.4.4` 称「`res/values-v31/`：**仅含** `icon_background_color` → `@android:color/system_accent1_0`（框架颜色），已核实安全」。实测该目录有 **2 个文件**：

| 文件 | 内容 | 裁决 |
|---|---|---|
| `values-v31/colors.xml` | `<color name="icon_background_color">@android:color/system_accent1_0</color>` | ✅ **安全**（纯框架颜色，无本仓库引用；`values/colors.xml` 有同名 fallback，未删） |
| `values-v31/themes.xml` | **重定义 `Theme.FoldCraftLauncher`（parent=`Theme.MaterialComponents.DayNight.NoActionBar`）与 `Theme.Splash`（parent=`Theme.FoldCraftLauncher`）**，引用 `@color/default_theme_color` + `@mipmap/ic_launcher` + `android:windowSplashScreenAnimatedIcon` | ⚠️ **需要单独裁决** |

被引用的 `@color/default_theme_color`（`values/colors.xml:3`）与 `@mipmap/ic_launcher`（`mipmap-anydpi-v26/ic_launcher.xml` + 各密度 `ic_launcher.webp`）**均保留**，实测存在 → **不会 `Resources.NotFoundException`**，静态确证。

**但存在一处语义差异（P2-6，待确认）**：`values/themes.xml:27` 的 `Theme.Splash` parent 是 `@style/Theme.SplashScreen`（带 `postSplashScreenTheme` / `windowSplashScreenAnimatedIcon`）；而 `values-v31/themes.xml:18` 把它**覆盖**成 parent=`Theme.FoldCraftLauncher`。于是 **API 31+ 设备上 `Theme.Splash` 实际不带 `postSplashScreenTheme`**，`installSplashScreen()` 的行为与 API < 31 不同。`values-v31/themes.xml` **本次未被修改**（不在 11 个 M 文件内，mtime 为 9/20），因此这是**既有条件**；但本次把启动页方向改为 `sensorPortrait` 并重写了启动流程，使该差异**首次变得可见**。真机需在 Android 12+ 上看首屏是否闪烁/黑屏。

**运行时主题切换 / 第三方 skin / overlay 核验结果**：
| 通道 | 是否存在 | 依据 |
|---|---|---|
| RRO / 运行时资源覆盖（第三方 skin APK） | **不存在** | `grep -rniE "overlay\|rro"` → 仅命中 `windowContentOverlay` 等主题属性，无 overlay 机制代码 |
| 主题热切换 | **存在，但走代码路径不走资源限定符** | `ThemeEngine.updateTheme` + `FCLActivity.onConfigurationChanged` → `refreshTheme()`；颜色是**运行时计算**（`ThemeData.deriveColor`），不读 `values-v31` |
| 第三方按资源名加载 | **不存在** | 无插件系统；无 `Resources.getIdentifier` 的第三方通道 |
| `<meta-data>` 声明 skin | **不存在** | Manifest 仅 1 条 meta-data（FileProvider paths） |

**裁决：`values-v31/colors.xml` = ✅ 安全（静态确证）；`values-v31/themes.xml` = ⚠️ 待确认（语义差异已确认，视觉影响需真机在 Android 12+ 验证）。**

---

## 5. 8 项盲点逐条结论

### 盲点 1：SharedPreferences / DB / 文件残留

| 残留物 | 来源（HEAD 侧核实） | 新代码是否读取 | 行为 | 置信度 |
|---|---|---|---|---|
| prefs 文件 `hidapi` | 上游 HEAD 存在 `getSharedPreferences("hidapi", ...)` | **不读**（当前 0 命中） | **忽略**（不是崩溃，也不会误用默认值——无任何读取点） | 静态确证 |
| prefs 文件 `third_party` | `HEAD:FCL/src/main/java/com/tungsten/fcl/control/RightMenuAdapter.kt:110`、`HEAD:.../fcl/ui/multiplayer/MultiplayerUI.java:80,153` | **不读** | 忽略 | 静态确证 |
| prefs 文件 `DraggableTextView` | `HEAD:FCL/src/main/java/com/mio/ui/view/DraggableTextView.kt:22` | **不读** | 忽略 | 静态确证 |
| prefs `theme`（键 `theme_color` 等） | 旧主题配置 | **读**：`ThemeData.kt:98-101` 做**一次性迁移**到 DataStore | **有明确迁移路径**，迁移后回写，不丢用户主题色 | 静态确证 |
| prefs `launcher`（`themeMode`） | — | **读**：`FCLActivity.java:75` `getInt("themeMode", 0)`（带默认值） | 兼容，无崩溃 | 静态确证 |
| prefs `launcher`（`allowScreenshots`） | — | **读**：`CrashReportActivity.java:44` `getBoolean(..., false)`（带默认值） | 兼容，无崩溃 | 静态确证 |
| prefs `crash_reporter`（`last_crash_timestamp`） | — | **读**：`CrashReporter.java:705/714`（带默认值 -1） | 兼容，无崩溃 | 静态确证 |
| **Room DB**：`FavoriteDatabase`（v2，实体 `DownloadFavoriteEntity`/`FavoriteGroupEntity`） | `HEAD:FCL/src/main/java/com/mio/data/favorite/FavoriteDatabase.kt:29-35`；schema 导出文件 `FCL/schemas/.../1.json`、`2.json` **已删** | **不读**（`grep "androidx.room\|Room\.\|SQLiteOpenHelper\|openOrCreateDatabase"` 在保留代码中 **0 命中**） | 设备上遗留一个 `databases/*.db` 文件，**无代码引用 → 忽略**（不死、不误用） | 静态确证 |
| **DataStore** 文件（`GameItemBarSetting`/`LaunchCountSetting`/`PluginSetting`/`SkinAnimationSetting`） | `HEAD:FCL/src/main/java/com/mio/datastore/*` | **不读** | 设备上遗留 `.pb` 文件，忽略 | 静态确证 |
| `filesDir/{plugins,background,skin}` | `FCLPath.java:66-68` + `:92-94`（`init()`） | `loadPaths` **仍在创建** | 空目录残留 | 静态确证 |
| `getDir("runtime")` / `getDir("runtime_mod")` | `FCLPath.java:78,85`（`init()`） | 仍在创建 | 空目录残留 | 静态确证 |
| `<externalFilesDir>/.minecraft` | `FCLPath.java:71-75`（`PRIVATE_COMMON_DIR`） | 仍在创建 | 空目录残留（App 专属目录，可写） | 静态确证 |
| `/sdcard/FCL/{log,control,share,.minecraft}` | `FCLPath.java:49,69,70,37` | 仍在创建，但**创建必然失败**（见 P2-1） | 无残留（也建不出来） | 静态推断 |
| `/sdcard/FCL/**`（旧 MC 的用户数据：存档/版本/整合包） | 旧版 FCL 写入 | 不读、且**删权限后 App 已无法访问** | **数据仍在设备上但 App 不可达**；无迁移、无提示 | 静态推断 |

**裁决**：**不存在「读到旧 key 崩溃」或「误用默认值」的路径**（所有读取点均带默认值，静态确证）。残留均为**孤儿**，不清理也无害。
**唯一需要产品决策的是最后一行**：若曾有用户把本 App 当 MC 启动器用过，其在 `/sdcard/FCL/` 的存档在本次变更后**既不被读取也无法被 App 访问**（`MANAGE_EXTERNAL_STORAGE` 已删）。brief §8.3 称「App 私有目录内无历史数据需要迁移」——**对 App 私有目录成立，对共享存储不成立**（P2-5）。

**升级路径明确性**：`applicationId` 未改（`com.tungsten.fcl` / fordebug `.debug`），`versionCode` 未改（1333）。覆盖安装保留数据；但由于签名不同（上游 FCL 用 `FCL-Key`，本仓库 debug 用 `FCL-Debug`；`key-store.jks` 与 `debug-key.jks` 是两个文件），**从官方 FCL 升级会 `INSTALL_FAILED_UPDATE_INCOMPATIBLE`，必须卸载重装 → App 私有数据全丢**。这是安装路径的已知后果，非代码缺陷，但应在发布说明中写明。

### 盲点 2：ProGuard / R8 规则

| 检查 | 结果 | 置信度 |
|---|---|---|
| `find . -name "*proguard*"`（排除 `.git`） | **0 个文件**——本仓库**没有 proguard-rules.pro** | 静态确证 |
| `FCL/build.gradle.kts` 中 `proguardFiles(...)` 调用 | **不存在** | 静态确证 |
| `isMinifyEnabled`（release / debug / fordebug） | **三者全为 `false`** → R8 收缩与混淆**未启用** | 静态确证 |

**裁决**：
- 「留着 `-keep` 已删类 → 误导」的情况**不适用**（不存在该文件，brief §7 未列出此项，本报告补充确认）。
- **反向风险为真（P2-4）**：仓库**完全没有** R8 keep 规则，而保留代码里存在大量「按名字反射」的构造：
  - `fclcore/fakefx/property/JavaBeanAccessHelper.java:38` `Class.forName(...)` + `getDeclaredMethod(...)`
  - `fclcore/fakefx/property/PropertyReference.java:126,138,157,182` 及 `.../adapter/{JavaBeanPropertyBuilderHelper,PropertyDescriptor,ReadOnlyJavaBeanPropertyBuilderHelper}.java` 的 `getMethod(...)`
  - fcllibrary 的 22 个自定义 `FCL*` 控件**全部由 XML 按类名实例化**（布局里写全限定类名）
  - `CrashReporter` 反射 Application/Activity 类
  由于 `minifyEnabled = false`，当前**不会**被 R8 破坏；但一旦有人为「减小体积」打开 minify，这些点位会静默崩溃。**当前无影响，属于潜伏配置债。**
- **置信度**：现状 = 静态确证；「打开 minify 后会崩」= 静态推断（未实测）。

### 盲点 3：资源引用与 public.xml / R（插件/第三方按名引用）

| 检查 | 结果 | 置信度 |
|---|---|---|
| `public.xml` | **不存在**（`res/values/public.xml` 实测无） | 静态确证 |
| 保留代码中的 `getIdentifier` | **3 处**：`LocaleUtils.java:130`（`"world_time"`，**活的**，已被白名单保留，缺失时有硬编码 fallback `"EEE, MMM d, yyyy HH:mm:ss"`）、`AndroidUtil.kt:128` `getLocalizedText`（**零调用者**）、`AndroidUtil.kt:138` `hasStringId`（**零调用者**） | 静态确证 |
| 其他动态资源名拼接（`getIdentifier` 之外的写法，如 `getResources().getIdentifier(name, ...)`、字符串拼资源名） | 未发现 | 静态确证 |
| 第三方/插件按名字引用资源 | **不存在通道**：无插件框架残留（`com.mio.plugin.*` 已删）、无 `<meta-data>` 声明、无 `Class.forName` 加载外部 APK 资源 | 静态确证 |
| 孤立资源扫描（对全部保留资源做全仓库引用统计） | drawable/layout/anim/xml **0 孤立**；字符串仅 3 项零引用（`world_time` 动态取用 + `dsh_action_configure_key` / `dsh_bootstrap_failed`，属 `com/dsh`） | 静态确证 |

**裁决**：**不存在「编译期不报错、运行期 `Resources.NotFoundException`」的路径。** 两个动态查询函数本身是死代码，唯一活着的动态查询（`world_time`）**有 fallback**，即使资源被误删也不会崩，只是日期格式回落英文——即「安全失败」。

### 盲点 4：Manifest 四类残留

逐类核对（`AndroidManifest.xml` 实测）：

| 类别 | 结论 | 证据 |
|---|---|---|
| **`<activity>`（8 个）** | ✅ **零残留、零悬空** | 逐个核对类文件存在：`.activity.SplashActivity`、`com.dsh.ui.{DshInstances,DshDownload,DshLogs,DshSettings,DshWebView}Activity`、`com.tungsten.fcllibrary.browser.FileBrowserActivity`、`com.tungsten.fcllibrary.crash.CrashReportActivity`。6 个已删 Activity（`MainActivity`/`WebActivity`/`ControllerActivity`/`ShellActivity`/`JVMActivity`/`JVMCrashActivity`）+ `activity-alias .ImportActivity` 在新 Manifest 中 `grep` **0 命中** |
| **`exported`** | ✅ 语义正确 | `SplashActivity` = `true`（LAUNCHER 必需）；6 个 dsh Activity 未声明 → 默认 `false`（不可外部唤起）；`FileBrowserActivity`/`CrashReportActivity` 未声明 → 默认 `false`（无 intent-filter，无外部入口）；`DshRuntimeService` = `false`；`FileProvider` = `false` + `grantUriPermissions=true`；`CrashReporterInitProvider` = `false` |
| **`<uses-permission>`（7 个）** | ✅ 逐一有使用点，无悬空 | `INTERNET`/`ACCESS_NETWORK_STATE`（npm + DeepSeek API）、`FOREGROUND_SERVICE` + `FOREGROUND_SERVICE_DATA_SYNC` + `FOREGROUND_SERVICE_SPECIAL_USE`（`DshRuntimeService`，与 `foregroundServiceType="dataSync\|specialUse"` 一致）、`POST_NOTIFICATIONS`（`DshInstancesActivity.ensureNotificationPermission()` → `FCLActivity.requestPermissions`，实测路径在）、`WAKE_LOCK`（`com/mio/util/AndroidUtil.kt:278-291` `acquireDownloadWakeLock`）。**已删的 9 个权限均无残留使用点**：`grep "getExternalStorageDirectory"` 仅剩 `FCLPath` 与 browser 模块（browser 是死模块，见 P2-3） |
| **`intent-filter` + `meta-data`** | ✅ 只剩必需项 | `intent-filter` 仅 1 个（SplashActivity 的 `MAIN`/`LAUNCHER`）；`activity-alias` 计数 **0**；已删的 `VpnService` intent-filter、`DOCUMENTS_PROVIDER` intent-filter 均 0 命中；`meta-data` 仅 1 条（FileProvider 的 `FILE_PROVIDER_PATHS` → `@xml/provider_paths`，该文件存在） |

**「能装、能启动、点进去崩」的失败模式：静态层面已排除**（不存在指向已删组件的注册项）。
**置信度**：静态确证（逐组件核对了类文件存在性 + 逐权限核对了使用点）。

### 盲点 5：多进程 / 多 dex

| 检查 | 结果 | 置信度 |
|---|---|---|
| 新 Manifest `android:process` | **0 命中** | 静态确证 |
| 上游 HEAD 的多进程 | 有 2 个：`JVMCrashActivity android:process=":crash"`、`ProcessService android:process=":jvm"` —— **两个组件均已删除** | 静态确证 |
| 代码中 `:remote` / `:jvm` / `:crash` 字样残留 | **0 命中** | 静态确证 |
| `FCLApp.onCreate` 每进程执行一次的影响 | **不存在多进程**，故只执行一次；`FCLPath.loadPaths` **幂等**（见 §3.2 子问题 2），即使在同进程内被 App + Activity 重复调用也安全 | 静态确证 |
| 文件锁竞争 | `FCLPath.loadPaths` 只有 `exists()`/`mkdirs()`（`mkdirs` 本身是原子的，失败仅返回 false），**无文件锁、无共享写入**；`DshPaths.loadPaths` 内的 `DshLogBus.attachFile` 有 `@Synchronized` + 「路径相同即 return」的幂等守卫（`DshLogBus.kt:84-86`） | 静态确证 |
| 多 dex | minSdk 26 → 原生支持 multidex，无需 `multiDexEnabled`；方法数未实测（需出包） | 待运行确认（低风险） |

**裁决：无多进程，无锁竞争风险。** 若将来为 dsh 引入独立进程（例如把 proot 服务拆走），必须重新评估「`Application.onCreate` 每进程都会跑 `FCLPath.loadPaths` + `DshPaths.loadPaths`」的成本与顺序。

### 盲点 6：`diff --shortstat` 自洽性 / 孤立资源 / 空目录

| 子项 | 命令与实测结果 | 结论 |
|---|---|---|
| `git status --porcelain` | `1228 D` / `11 M` / `11 ??`（总 1250 行） | 与文档一致，**静态确证** |
| `git diff --shortstat HEAD` | `1239 files changed, 323 insertions(+), 226529 deletions(-)` | 与送审值**逐位一致** |
| insertions 自洽 | 逐文件 `--numstat` 求和 = **323**（无其他文件产生 insertions） | **自洽** |
| **仅被已删文件引用的资源/字符串/manifest 条目** | 全量引用扫描（java + res + AndroidManifest）＋ 孤立扫描：drawable/layout/anim/xml **0 孤立**；字符串仅 3 项零引用（`world_time` 动态 + 2 个 dsh 字符串）；Manifest 每条均指向存在的类/资源 | **未发现**「仅被已删文件引用」的悬空项 |
| 例外说明 | `@string/file_browser_*`（15 条）+ `@string/file_browser_provider` 仅被 **C-2 的死浏览器模块**引用 → 它们「有引用」但引用方是死的。删 browser 模块时应同步删这批字符串 | 记入 P2-3 |
| **空目录**（git 不追踪） | `find FCL/src -type d -empty` → **0**；`ls -A FCL/libs` → **0 文件**（`FCL/libs/` 目录本身仍在磁盘上，但为空且 git 不追踪，无内容残留） | **无空目录残留** |
| `git clean -ndX`（将被删的忽略文件） | 7 项：`.gradle/`、`.kotlin/`、`FCL/.cxx/`、`FCL/build/`、`ZipFileSystem/build/`、`build/`、`local.properties` | **全部是构建产物/本地配置，无源码**；⚠️ 注意 `local.properties` 含 Android SDK 路径，删除后需重建 |
| `git clean -nd`（将被删的未跟踪文件） | 11 项（`assets/dsh/`、`com/dsh/`、8 个 dsh 布局、`src/test/java/com/dsh/`） | **就是本次的成果，`git clean` 会不可恢复地删除**（见 §2.G / P1-3 风险） |

### 盲点 7：ksp 插件移除的 clean build 验证

**前提已满足：本次确实做了 clean build，不是增量。**

```
cd /workspace/FCL
export GRADLE_OPTS="-Xmx1300m -XX:MaxMetaspaceSize=450m"
./gradlew --no-daemon --offline :FCL:clean :ZipFileSystem:clean      → BUILD SUCCESSFUL（1m04s，2 tasks executed）
sh /workspace/run-compile.sh                                        → BUILD SUCCESSFUL（3m48s，34 actionable tasks: 34 executed）
```
关键点：`34 actionable tasks: 34 executed`（**不是 up-to-date**），说明 Kotlin/Java/资源/Manifest 全部真跑。

| 检查项 | 结果 | 置信度 |
|---|---|---|
| 任何模块（含 fcllibrary、ZipFileSystem）仍声明 `id("com.google.devtools.ksp")`？ | **否**。`grep -rn "ksp" --include=*.kts --include=*.gradle --include=*.properties .` 仅命中 `build/intermediates/**` 下的**过期二进制缓存路径**（`compile-file-map.properties` 里的文件名巧合），**无任何 Gradle 配置命中** | 静态确证 |
| `settings.gradle.kts` 是否只含 `:FCL` + `:ZipFileSystem`？ | 是（`include(":FCL")`、`include(":ZipFileSystem")`；`:Terracotta`、`:LWJGL`、`:LWJGL:lwjgl-3.3.3`、`:LWJGL:lwjgl-3.4.1` 四行 `include` 及其 `projectDir` 重定向全部删除） | 静态确证 |
| 源码是否仍引用注解处理器产物（`@AutoService` / `META-INF/services` / 生成的 `*_Impl`）？ | **否**。`grep -rn "AutoService\|javax.annotation.processing\|META-INF/services"` → 0 命中 | 静态确证 |
| 是否有 Room 注解残留（ksp 移除后会编译失败）？ | **否**。`grep -rn "androidx.room\|Room\.\|@Entity\|@Dao\|@Database"` 在保留源码中 → 0 命中 | 静态确证 |
| `schemas/` 目录 | `FCL/schemas/com.mio.data.favorite.FavoriteDatabase/{1,2}.json` **已删除**（在 1228 条 D 内），与 ksp `room.schemaLocation` 一并移除，**一致** | 静态确证 |
| `libs.versions.toml` 里 `ksp` 插件别名 | **仍保留**（`ksp = { id = "com.google.devtools.ksp", version.ref = "ksp" }`）——未被任何模块 `alias()` 使用，**仅冗余不生效** | 静态确证（P3-2） |

**裁决：clean build 通过，ksp 清理完整。**

### 盲点 8：Node 22/24 + 云端 API 协议边界

**核心事实：本次 diff 完全没有触碰协议面。** `com/dsh/**`（25 文件）与 `assets/dsh/**`（10 文件）在 `git status` 中为 `??`（untracked），`git diff HEAD` 不显示、也不修改它们。因此**不存在「删 Kotlin 侧代码改变了目录/env/JSON 约定」的可能**（静态确证）。

为回答「协议是否自洽」，仍做了正向核对（结果：自洽）：

| 协议面 | Kotlin 侧 | Node/脚本侧 | 是否一致 |
|---|---|---|---|
| 宿主目录 → rootfs 挂载点 | `ProotCommand.kt:28` `GUEST_ROOT = "/opt/dsh"` + `:128` `--bind=${DshPaths.ROOT_DIR}:$GUEST_ROOT` | `setup-node-dsh.sh` 用 `INSTANCE_DIR=/opt/dsh/instances/<id>` | ✅ |
| 实例目录 | `DshInstaller.kt:244,257` `"INSTANCE_DIR" to instancePathInRootfs` | 脚本 `INSTANCE_DIR="${INSTANCE_DIR:-$PWD}"` | ✅ |
| 版本 | `DshInstaller.kt:245,258` `"DSH_VERSION" to version` | `DSH_VERSION="${DSH_VERSION:-latest}"` | ✅ |
| npm 缓存 | `DshInstaller.kt:246,259` `"NPM_CONFIG_CACHE" to "/opt/dsh/npm-cache"` | `if [ -n "${NPM_CONFIG_CACHE:-}" ]; then mkdir -p ...` | ✅ |
| 安装进度通道 | `DshInstaller.kt:275-276` 解析 `"[setup] STAGE="` | `setup-node-dsh.sh` `stage() { echo "[setup] STAGE=$*"; }` | ✅ |
| 安装完成判据 | `DshPaths.kt:124-129` 校验 `node_modules/@deepseek-ai/dsh/package.json` 与 `lib/bin.js` | 脚本同样校验这两个路径，缺失则 `exit 1` | ✅ |
| 启动就绪信号 | `DshRuntime` 解析 `[start-dsh] READY ...` | `start-dsh.sh` 打印 `[start-dsh] READY port=` / `READY url=` | ✅ |
| 失败信号 | 解析 `FAILED reason=` | 脚本打印 `[start-dsh] FAILED reason=node-exit/ready-timeout/...`、`[setup] FAILED reason=...` | ✅ |
| `DSH_HOME` | `ProotCommand.kt:157` 在 env 白名单内 | `export DSH_HOME="${DSH_HOME:-$INSTANCE_DIR/home}"` | ✅ |
| API Key 传递 | `ProotCommand.kt:146-147` 走 `procEnv`（进程环境，**不进 argv**）+ `isAllowedEnvKey` 白名单 `key.startsWith("DEEPSEEK_")` | `start-dsh.sh` 优先读进程环境变量 `DEEPSEEK_API_KEY`，仅在其为空时回退 `CRED_FILE` | ✅ |
| 旧凭据文件 | `DshPaths.kt:134-136` `instanceLegacyCredentials` 标注「已废弃」但**函数保留** | 脚本保留 `CRED_FILE` 分支用于手工调试 | ✅ 一致（启用需显式传 `CRED_FILE`，App 不传） |
| Web UI 地址 / cleartext | `DshWebViewActivity` 加载 `http://127.0.0.1:<port>/?token=...` | `HOST=127.0.0.1`、脚本只绑本地 | ✅。**cleartext 所需的两项均已保留**：Manifest `android:usesCleartextTraffic="true"` + `res/xml/network_security_config.xml`（`cleartextTrafficPermitted="true"`），且 `networkSecurityConfig` 属性仍在 `application` 上 |
| 云端 API | DeepSeek API 调用发生在 **rootfs 内的 Node 进程**，不经 App 的 Java 网络栈 | — | ✅ 与本次删除无关；`INTERNET` + `ACCESS_NETWORK_STATE` 已保留 |
| `PROOT_LOADER` / `PROOT_TMP_DIR` | `ProotCommand.kt:144-145` 传入 | proot 自身消费 | ✅（运行时需真机验证） |

**裁决：本次变更未改变任何 Node/脚本/环境变量/JSON 字段约定；正向核对发现协议自洽。**
**但必须如实指出**：这是**没有真机验证过的**一致——`libproot.so`/`libproot_loader.so` 与 `rootfs.tar.xz` 均不存在于仓库（`assets/dsh/proot/PLACEHOLDER.txt`、`assets/dsh/rootfs/PLACEHOLDER.txt`），所以「协议层面」的静态一致 ≠ 「能跑起来」。
**置信度**：静态确证（未改动 + 名称逐项对照）；运行期可用与否 = **待运行确认**。

---

## 6. 问题清单

级别定义：**P0** = 编译/运行必然失败；**P1** = 核心路径存在可证伪的失败模式或引入架构脆弱点；**P2** = 功能静默退化 / 潜伏风险 / 既有条件被本次变更「点亮」；**P3** = 清洁度与文档。

| ID | 级别 | 类型 | 位置 | 问题 | 影响 | 证据 | 修复方案 | 状态 |
|---|---|---|---|---|---|---|---|---|
| **M-01** | **P1** | 隐式依赖 / 入口遗漏 | `FCLActivity.java:47-55`；`SplashActivity.kt:49`；`FCLApp.java:28-34` | `FCLPath.loadPaths()` 在基类中被权限门控（删权限后恒不可达），全仓库只剩 SplashActivity 一条有效路径 → **通知栏冷启动等入口绕过启动页时 `FCLPath` 未初始化** | 当前不崩（dsh 侧有 null 防护，会回退 assets 目录）；一旦防护失效或新增 `FCLPath` 消费方，则**只在特定入口暴雷**，且症状与入口强相关、极难复现 | 权限已删；`FCLActivity.java:53` `if (hasPermission)`；调用点 grep 仅 3 处；`DshRuntimeService.kt:158-163` contentIntent 直达 `DshInstancesActivity`（绕过 Splash） | 在 `FCLApp.onCreate` 内、`DshPaths.loadPaths` **之前或并列**加 `FCLPath.loadPaths(this)`；删除 `SplashActivity.kt:49` 那一行（保留 `:50` 的 `Logging.start`）。见 §11 | **✅ 已修（补丁 A，`a62ed0d`）**：`FCLApp.onCreate` 内、`DshPaths.loadPaths` **之前**加 `FCLPath.loadPaths(this)`；删除 `SplashActivity.kt` 那一行（保留 `Logging.start`）。详见 §11 | ✅ **已修** |
| **M-02** | **P2** | 静默功能退化 + 文档与代码不符 | `SplashActivity.kt:50`；`FCLPath.java:49`；`Logging.java:56-68` | `Logging.start(Paths.get(FCLPath.LOG_DIR))` 的 `LOG_DIR = Environment.getExternalStorageDirectory() + "/FCL/log"` 在**外部存储**；`MANAGE_EXTERNAL_STORAGE` 等已删、`requestLegacyExternalStorage`/`preserveLegacyExternalStorage` 已删、targetSdk 34 → 该目录**不可写**，`fcl.log` 永远建不出来 | 不崩溃（`catch (IOException)` 吞掉），但**运行日志不落盘**，崩溃后无可取证文件；同时 `FCLPath.loadPaths` 的 `init(LOG_DIR/CONTROLLER_DIR/SHARE_DIR/SHARED_COMMON_DIR)` 四次 `mkdirs` 静默失败。`SplashActivity` 的 KDoc 称「rootfs/实例/日志全部在 App 私有目录」——**对本条不成立** | `FCLPath.java:49,37,69,70`；`Logging.java:59-68` 的 `try { Files.createDirectories; new FileHandler } catch (IOException)`；新 Manifest 无存储权限 | 把 `LOG_DIR` 改为 `context.getDir("log",0)` 或 `filesDir/log`；或显式接受「不落盘」并修正 SplashActivity 的 KDoc。**建议前者**（1 行） | **✅ 已修（补丁 B，`a62ed0d`）**：`LOG_DIR = context.getDir("log", 0)`；并同步修正 `SplashActivity` 的 KDoc。详见 §11 | ✅ **已修** |
| **M-03** | **P2** | 潜伏配置债 | `FCL/build.gradle.kts`（无 proguardFiles）；三个 buildType 的 `isMinifyEnabled=false` | 仓库**完全没有** R8 keep 规则，而保留代码大量按名反射（`fakefx` 的 `Class.forName`/`getMethod`、22 个由 XML 按类名实例化的 `FCL*` 控件、`CrashReporter` 反射） | 当前 minify 关闭 → **无影响**；一旦有人打开 minify，这些点位**静默崩溃且难排查** | `find . -name "*proguard*"` → 0；`build.gradle.kts` 无 `proguardFiles`；`isMinifyEnabled = false` × 3；`JavaBeanAccessHelper.java:38,42` 等 | 新增 `proguard-rules.pro` 并对上述类加 `-keep`（**仅在将来启用 minify 时需要**）；或显式在 build 文件里写注释声明「本项目不启用 minify」 | 未修 |
| **M-04** | **P2** | 死重 + 权限缺失放大 | `FCLActivity.java:56`；`fcllibrary/browser/**`（7 文件）；Manifest 的 `FileBrowserActivity` | `FCLActivity.onCreate` 仍**无条件** `new FileBrowserLauncher(this)` 并存进 public 字段 `fileLauncher`，但全仓库**零读取者**；browser 模块与 `FileBrowserActivity` 同样零调用；而该模块的功能依赖**已删除的存储权限**与 `FileBrowser.java:47` 的 `/sdcard` 根目录 | 当前无害（死代码）；但它是一个「表面可用、一旦启用即碎」的陷阱：`FileBrowserAdapter.java:147` 用 `FileProvider.getUriForFile` 分享 `/sdcard` 文件，而 App 已无读取 `/sdcard` 的权限 | `grep "fileLauncher\|FileBrowserLauncher"` → 仅定义与赋值，**无读取**；`FileBrowser.java:47` `initDir = getExternalStorageDirectory()`；新 Manifest 无存储权限 | 删除 browser 模块 7 文件 + Manifest 注册 + `FCLActivity.java:25,33,56` 三处，并同步删除 15 条 `@string/file_browser_*` 与 `@string/file_browser_provider`（见 §6 盲点 6）。**属可选优化，不建议与 M-01 同批做** | 未修 |
| **M-05** | **P2** | 设备端孤儿数据 | 设备 `shared_prefs/`、`databases/`、`files/`、`/sdcard/FCL/` | 被删模块在用户设备上写过的 prefs（`hidapi`/`third_party`/`DraggableTextView`）、Room 库（`FavoriteDatabase` v2）、DataStore `.pb`、`filesDir/{plugins,background,skin}`、`getDir("runtime"/"runtime_mod")`、`externalFilesDir/.minecraft` **无任何清理或迁移** | **无崩溃、无误用**（所有读取点带默认值或零读取点）；仅占用少量空间；`/sdcard/FCL/**` 里的旧 MC 用户数据变成**不可达数据**（无迁移、无提示） | 见 §6 盲点 1 全表 | 若在意：在首启做一次「旧数据检测 + 提示」。若不打算支持从 MC-FCL 升级，**明确记录「不支持」并在发布说明写明**即可 | 未修 |
| **M-06** | **P2** | 既有条件被点亮 | `res/values-v31/themes.xml:3-20` | API 31+ 用 `parent="Theme.FoldCraftLauncher"` **覆盖** `Theme.Splash`，覆盖了 `values/themes.xml:27` 的 `parent="@style/Theme.SplashScreen"` + `postSplashScreenTheme` | API ≥ 31 与 < 31 的启动页语义不同；本次又把方向改为 `sensorPortrait` 并重写启动流程，**该差异首次可见**（是否闪烁/黑屏未验证）。**注意：该文件本次未修改** | `values-v31/themes.xml` 与 `values/themes.xml` 的 `Theme.Splash` 两条定义；文件 mtime 9/20（未改动） | 真机在 Android 12+ 上核对首屏；若异常，把 `values-v31/themes.xml` 的 `Theme.Splash` 改回 `parent="@style/Theme.SplashScreen"` 只保留 `windowLayoutInDisplayCutoutMode` | 待运行确认 |
| **M-07** | **P2** | Manifest 行为变化 | `AndroidManifest.xml`（application 属性） | 删除了 `android:allowNativeHeapPointerTagging="false"`（同时也删了 `largeHeap`、`hasFragileUserData`、`appCategory`、`isGame`、`requestLegacyExternalStorage`、`preserveLegacyExternalStorage`、`extractNativeLibs` 相关项） | ①`allowNativeHeapPointerTagging` 当初是为 native 栈关闭的，而**将来仍要 exec 原生 proot 二进制**（`jniLibs/libproot.so`）→ 指针标记是否影响 proot 未验证 ②`hasFragileUserData` 移除后，卸载不再提供「保留应用数据」选项（dsh 数据 ~300MB/实例，重装即重下） ③`largeHeap` 移除对「Node 在独立进程里跑」的架构无实质影响 | 新 Manifest 实测无这些属性；`grep -c "allowNativeHeapPointerTagging"` → 0；`packaging.jniLibs.useLegacyPackaging=true` **保留**（合并 Manifest 中 `extractNativeLibs="true"` ✅，这是 proot 可执行的前提，**未遗漏**） | 建议把 `android:allowNativeHeapPointerTagging="false"` **加回**（1 行，非依赖引入）：成本为 0，可消除一个真机才知道的坑。其余属性维持删除即可 | 待运行确认 |
| **M-08** | **P3** | 死代码簇 | `fcl/util/{Constants,FXUtils,RequestCodes,ResourceNotFoundError,ShellUtil,WeakListenerHolder}.java`；`fclcore/task/**` 与 `fclcore/util/io/**` 的下载簇；`AndroidUtil.kt:122-140` | 外部引用数 0 的文件与函数 | 无运行时影响，仅 dex 体积与维护成本 | 逐文件 grep：`FXUtils`/`RequestCodes`/`ResourceNotFoundError`/`ShellUtil`/`WeakListenerHolder` 外部引用 **0**；`Constants` 的唯一「命中」是 `fakefx/FXPermissions.java:6` 的英文注释「Constants used for...」（**假阳性**）；`getLocalizedText`/`hasStringId` 仅自引用 | 可选：做一次从 `FCLApp`/Splash/各 dsh Activity 出发的完整可达性分析后清理 | 未修（计划内，见 §14） |
| **M-09** | **P3** | 构建配置冗余 | `gradle/libs.versions.toml` | 被移除的 15 条依赖的 `[versions]`/`[libraries]` 条目**全部保留**，含 `room` 与 **`ksp` 插件别名** | 无功能影响；误导后人以为 Room/ksp 还在用（本次审查就因它多花一步核实） | toml 全文实测；`grep "ksp"` 在 .kts 中 0 命中 | 删除未使用的 `[versions]`/`[libraries]`/`[plugins]` 条目 | 未修 |
| **M-10** | **P3** | 悬空 declare-styleable | `res/values/attrs.xml:4-13`（`KeycodeView`、`LogWindow`、`DraggableTextView`）、`:83`（`ColorPickerView` 的 `parent="ColorPanelView"`） | 3 个自定义属性组对应的 View 类**已被本次变更删除**，`declare-styleable` 声明成为悬空；`ColorPanelView` 更是新旧仓库都**不存在** | 无运行时影响（attr 只是声明，无人解析；aapt2 容忍悬空 `parent`）；属编译期残留 | `find` 三类文件 → 0 命中；`HEAD` 侧确认三者曾存在于 `fcl/control/view/` 与 `mio/ui/view/`；`grep -rl "\bKeycodeView\b" res java` 仅命中 `attrs.xml` 自身；`attrs.xml` **不在 11 个 M 文件内**（本次未修改） | 删除这 3 个 `declare-styleable`；`ColorPanelView` parent 属既有问题，可一并清理 | 未修 |
| **M-11** | **P3** | 孤儿视图 | `res/layout/activity_splash.xml:12-16` | `<FragmentContainerView android:id="@+id/fragment">` 保留，但 Fragment 体系（EulaFragment/RuntimeFragment）已删，无人使用 | 无（多一个空 View） | 布局与 HEAD 逐字节相同；`grep "R.id.fragment\|commitAllowingStateLoss"` → 0 | 删掉该子视图（可选） | 未修 |
| **M-12** | **P3** | 死方法 | `FCLApp.java:52-64` | `private void enabledStrictMode()` 无调用者（本次 diff 删掉了它唯一的注释调用点 `// enabledStrictMode();`） | 无 | diff 实测删除 `- // enabledStrictMode();`；方法体仍在；grep 调用者 0 | 删除该方法（可选） | 未修 |
| **M-13** | **P3** | 文档准确性 | brief §5.2/§7.C/§7.D/§0；mc-removal §5.3/§9.4.4/§9.4.6；INDEX「当前状态」 | 8 处数字/事实不符（详见 §1.4 D-1~D-8），其中 **D-4（values-v31 判定错误）**与 **D-5（横屏虚惊）**是实质性判断错误 | 误导后续审查与决策 | §1.4 全表 | 按 §1.4 逐条修正；mc-removal 补 §6 或改为连续编号 | **✅ 已批注（8/8）**：`mc-removal.md` ×5、`brief` ×7、`INDEX.md` ×1，覆盖 D-1~D-8。详见 §11 补丁 C | ✅ **已批注** |

| **M-14** | **P3** | 未引用样式 | `res/values/themes.xml` | `FCLCrashSidebarHint`、`TabTextAppearance`、`NavIndicator` 三个 style 除定义外全仓库 0 命中 | 无（多 3 段死样式） | `grep -rn "<name>" java res` 排除定义行后 0 命中（已反向验证 `FCLCrashButton` 等 5 个确被 `activity_crash.xml` 引用、`FCLSpinnerPopupAnimation` 被 `FCLSpinner.kt:104` 引用，**非误报**） | 删除这 3 个 style（可选） | 未修 |

**P0 项：无。**
**P1 项：1 个（M-01），修复成本 1 行新增 + 1 行删除。**

---

## 7. 请求书 6 问的可证伪回答

### 问 1：删除边界——每个被删模块/文件是否被任何存活代码引用？

按**模块/包**粒度逐一给出结论与证据（逐文件 605 个不现实，故按包 + 抽查高风险项）：

| 被删模块/包 | 是否被存活代码引用 | 证据（grep / 编译） | 置信度 |
|---|---|---|---|
| `Terracotta/`（整模块，8 文件） | **否** | `settings.gradle.kts` 删除 `include(":Terracotta")`；`FCL/build.gradle.kts` 删除 `implementation(project(":Terracotta"))`；`grep -rn "terracotta\|Terracotta"` 在保留源码中仅命中 `com/dsh/core/DshInstance.kt` 与 `DshInstances.kt` 的 **KDoc 注释** | 静态确证 |
| `LWJGL/`（整模块，90 文件） | **否** | `settings.gradle.kts` 删除 4 行 `include`；无 `libs.lwjgl` 之类的依赖引用残留（实测 `dependencies` 块已无） | 静态确证 |
| `FCL/libs/*.aar`（7 个） | **否**（但依赖声明仍在，见下） | `fileTree("libs","*.jar","*.aar")` **保留**，但目录实测为空 → 空操作，不引用任何内容 | 静态确证 |
| `FCL/src/main/jniLibs/**`（41） | **否**（且这一点**正是 M-01 的成因**） | `FCLPath.NATIVE_LIB_DIR` 改由 `context.getApplicationInfo().nativeLibraryDir` 提供（运行时值，与仓库内文件无关） | 静态确证 |
| `FCL/src/main/jni/**`（90） | **否** | `externalNativeBuild` 与 `ndkVersion` 已删；无 `System.loadLibrary` 指向这些模块（实测 `grep "loadLibrary"` 在保留源码中 0 命中） | 静态确证 |
| `FCL/src/main/jreAssets/**`（23） | **否** | `filterJreAssets` 任务、`addStaticSourceDirectory("src/main/jreAssets")`、`addGeneratedSourceDirectory(...)` 全部删除；`RuntimeUtils.installJava` 已删（唯一消费方） | 静态确证 |
| `FCL/src/main/assets/{app_runtime,game,controllers,img}`（80） | **否** | `grep -rn "app_runtime\|/game/\|controllers/\|assets/img"` 在保留源码中 0 命中；`RuntimeUtils` 只剩 dsh 需要的通用方法 | 静态确证 |
| `com/tungsten/fcl/{control,fragment,game,scoped,setting,terracotta,ui,upgrade}` + `activity/*`（除 Splash） | **否** | clean build 通过（Java/Kotlin 编译 + 资源链接）→ 任何对已删类的符号引用都会失败；另 `grep -rnE "fcl\.(game\|ui\|setting\|control\|terracotta\|scoped\|upgrade)"` → 仅注释 | 静态确证 |
| `com/tungsten/fclcore/{auth,download,game,launch,mod}` | **否** | 同上；特别注意 `ChecksumMismatchException` 的父类 `download.ArtifactMalformedException` 已随之删除，改为 `IOException` 后编译通过——**反证**该包无残留引用 | 静态确证 |
| `com/tungsten/fclauncher/*`（除 `utils/{FCLPath,Architecture}`） | **否** | `RuntimeUtils` 的 `import com.tungsten.fclauncher.FCLauncher` 已删；`FCLConfig`/`bridge/`/`keycodes/` 无引用 | 静态确证 |
| `com/mio/*`（除 `util/`） | **否** | `DialogUtil.kt` 的 `com.mio.dialog.ItemSelectionDialog` import 已删（唯一引用点被裁掉）；`AndroidUtil.kt` 的 `net.fornwall.jelf` import 已删 | 静态确证 |
| `com/mio/util/` 的 11 个文件 | **否** | 文档称「均经引用检查确认无人使用」；本次抽查 `SystemDns`（`getSystemDnsServerAddresses`，原仅在 `SplashActivity.initState()` 使用，该函数已删）、`PerfUtil`（`FCLApp` 中的注释调用已删）、`GuideUtil`/`PixelIcon`/`SourceBadgeStyle`/`AnimUtil`/`MathUtil`/`ParseUtil`/`LauncherUtil`/`LayoutConverter`/`LoginProgress`（clean build 通过 → 无符号引用） | 静态确证 |
| `com/oracle/dalvik/`（4） | **否** | clean build 通过；`org/`（SDL/LWJGL/hmcl 胶水，15）同理 | 静态确证 |
| `fclcore/util/{LibFilter,Pack200Utils}.java`、`io/HttpServer.java`、`fcl/util/TaskCancellationAction.java`、`fclcore/util/{versioning,png,skin}/` | **否** | `RuntimeUtils` 的 `import ...Pack200Utils` 已删；`HttpServer` 依赖的 `nanohttpd` 依赖已删；`fcl/util/TaskCancellationAction` 引用的 `fcl.ui` 已删；clean build 全通过 | 静态确证 |
| `res/` 282 个文件 | **否** | 见 §5 盲点 3 / §2.D：孤立扫描 0 命中；aapt2 link 通过 | 静态确证 |

**结论：未发现任何被删项仍被存活代码引用。** 唯一「引用已删资源」的情形是**注释**（4 处，见 `mc-removal §9.3`，本次复核确认只有注释）。

### 问 2：过度删除——是否有被删但仍被存活代码/构建脚本/manifest/资源引用的项？

**未发现。**

| 高风险怀疑点（brief 点名的） | 核验结果 | 证据 |
|---|---|---|
| 存储权限移除 | **无残留使用** | `Environment.getExternalStorageDirectory()` 仅剩 2 个消费方：`FCLPath`（自身初始化，静默失败，M-02）与 `fcllibrary/browser/**`（死模块，M-04）。**注意：M-02/M-04 是「被删权限仍被引用」，不是「被删资源仍被引用」——属权限侧而非资源侧的过度删除，且均不崩** |
| 语言包删除（10 个 `values-*`） | **不会被误引用** | `LocaleUtils.setLanguage(base)` 仍支持 de/ja/uk/tw 等索引，但**对应的 `values-*` 已不存在** → Android 资源回落机制会让这些语言显示英文（**不是崩溃**）。文档已如实说明（mc-removal §9.4.5）。**另外**：语言选择入口原本在 MC 设置页，已随 MC 删除，故用户**无法在 App 内选到这些语言**（静态推断） |
| `RuntimeUtils`/`AndroidUtil`/`DialogUtil` 方法裁剪 | **无调用者** | 6 个被删方法逐一 grep：零命中（仅 1 处 KDoc 提及） |
| `assets/img/{alex.png,steve.png,skin_model/}` | **无引用** | `grep -rn "alex\|steve\|skin_model"` 在保留源码/res 中 0 命中 |
| `assets/microsoft_auth.html` + `io/HttpServer.java` | **无引用** | dsh 的登录走本地 WebView（`DshWebViewActivity` + 127.0.0.1 + token），不使用 OAuth 回调服务器 |
| dsh 依赖的 6 个 FCL 类 | **全部保留** | 逐类确认存在（见 §2.F） |

**结论：未发现过度删除。** 唯一「形式上是过度删除、实质是权限侧遗留」的两处已单列为 M-02 / M-04。

### 问 3：遗漏删除——应删但未删的项？

| 类别 | 结论 |
|---|---|
| **空目录** | **无**（`find FCL/src -type d -empty` → 0） |
| **孤立资源** | **无**（drawable/layout/anim/xml 扫描 0 命中；字符串仅 3 项零引用，其中 1 项为动态取用、2 项属 `com/dsh` 范围） |
| **悬空 manifest 条目** | **无**（§5 盲点 4：8 Activity + 1 Service + 2 Provider 的类全部存在；7 权限全部有用点；`activity-alias` 计数 0） |
| **孤立 proguard 规则** | **不适用**（仓库无 proguard 文件，见 §5 盲点 2）→ 反向问题是「一条 keep 都没有」（M-03） |
| **悬空 declare-styleable** | **有 3 个**：`KeycodeView`/`LogWindow`/`DraggableTextView`（`attrs.xml:4-13`）+ `ColorPickerView` 的 `parent="ColorPanelView"`（`:83`，后者新旧仓库都缺）→ **M-10** |
| **未使用的 colors** | **有 5 个**：`default_theme_color_dark`、`black`、`ui_bg_color`、`right_menu_color`、`primary_text`（值定义存在但全仓库无引用；已核实 `values-night/colors.xml` 里的 `primary_text` 覆盖也一并失用） |
| **未使用的 styles** | **有 3 个**：`FCLCrashSidebarHint`、`TabTextAppearance`、`NavIndicator`（均已核实：`grep` 除定义外 0 命中）。**已排除**误报：`FCLCrashButton`/`FCLCrashTitle`/`FCLCrashErrorText`/`FCLCrashSplitLine`/`FCLCrashSidebarTitle` 由保留的 `activity_crash.xml` 逐条引用；`FCLSpinnerPopupAnimation` 由 `FCLSpinner.kt:104` 引用。另 `themes.xml` 中 `colorPrimaryVariant`/`colorOnPrimary`/`colorSecondaryVariant`/`colorOnSecondary` 四个 Material 属性组无消费者（无害） |
| **死代码簇** | **有**（`Constants`/`FXUtils`/`RequestCodes`/`ResourceNotFoundError`/`ShellUtil`/`WeakListenerHolder`；下载簇；`AndroidUtil.getLocalizedText`/`hasStringId`）→ **M-08**，属计划内可选优化 |
| **构建配置冗余** | **有**：`libs.versions.toml` 的 15 条依赖条目 + `ksp` 插件别名 → **M-09**；`FCL/libs` 空目录 + `fileTree("libs", ...)` 空操作；`splits { abi }` 已无意义 |
| **文档遗漏** | **有**：mc-removal.md 缺 §6；8 处数字/事实错误 → **M-13** |

### 问 4：重写后的 `SplashActivity` 是否安全？取消权限与 EULA 是否可接受？

| 子项 | 结论 | 依据 |
|---|---|---|
| 入口唯一性 | ⚠️ **不唯一**：通知栏 PendingIntent 可冷启动直达 `DshInstancesActivity`，绕过 Splash（**M-01**） | `DshRuntimeService.kt:158-163` |
| 取消存储权限 | ✅ **对 dsh 主流程可接受**：`DshPaths` 的 9 个路径全部落在 `filesDir`/`cacheDir`（`DshPaths.kt:83-92`），实测无 `/sdcard` 依赖 | 静态确证 |
| 取消存储权限的**代价** | ⚠️ 两处：①`FCLPath.LOG_DIR` 在 `/sdcard`，日志落盘失效（**M-02**）②`fcllibrary/browser` 若被启用即碎（**M-04**） | 见 M-02/M-04 |
| 未来需要 `/sdcard` 时 | 需重新加回权限 + 引导；`FileBrowser` 的死代码是现成的地雷 | 已在 M-04 记录 |
| 取消 MC EULA | ✅ **正确**：EULA 是 Mojang/Minecraft 的，dsh 无关；保留它反而是错误 | 静态确证 |
| 启动流程正确性 | ✅ 修掉 M-01 后，`FCLPath` → `Logging` → `enterDsh` 的顺序无隐患（`enterDsh` 在 `await` 之后） | `SplashActivity.kt:46-57` |
| 首屏视觉 | ⚠️ **待运行确认**：①`activity_splash.xml` 方向中立（**排除**了文档 D-5 的「横屏设计」担忧）②但 API 31+ 的 `Theme.Splash` 覆盖问题真实存在（**M-06**） | §4.2 |
| `super.onCreate` / `installSplashScreen` 顺序 | 与 HEAD 一致，**非本次回归**，不计为问题 | `git show HEAD:...` 第 63-64 行 |

### 问 5：Manifest 与构建配置的清理是否完整、有无副作用？

| 子项 | 结论 |
|---|---|
| 完整性 | ✅ **四类全部干净**（§5 盲点 4 逐类给出证据）：8 Activity + 1 Service + 2 Provider 无悬空；7 权限全部有用点；`activity-alias` 归零；已删的 6 Activity / 2 Service / 1 Provider / 1 alias 字样 0 命中 |
| 构建配置完整性 | ✅ clean build 通过；**无模块残留 ksp**；无 `@AutoService`；无 Room 注解；`scheme/jniLibs/jni/jreAssets` 的构建入口全部移除 |
| **副作用 1** | ⚠️ `android:allowNativeHeapPointerTagging="false"` 被一并删除，而原生 proot 仍要执行（**M-07**，待真机确认） |
| **副作用 2** | ⚠️ `hasFragileUserData="true"` 被删 → 卸载不再可选「保留数据」（dsh 每实例约 300MB，重装即重下）（M-07） |
| **副作用 3** | ⚠️ `largeHeap="true"` 被删 → 影响有限（重活在独立进程），但 `DshWebViewActivity` 加载 dsh 前端时的 Android 堆上限降低（待观察）（M-07） |
| **未遗漏的关键项** | ✅ `packaging.jniLibs.useLegacyPackaging = true` **保留**，且合并后 Manifest 实测 `android:extractNativeLibs="true"` —— 这是**将来 `libproot.so` 能被 exec 的前提**，若被误删会导致 proot 无法执行。**这条保留是对的** |
| **未遗漏的关键项 2** | ✅ `usesCleartextTraffic="true"` + `network_security_config`（cleartext 允许）双双保留 → WebView 的 `http://127.0.0.1` 可用 |
| **未遗漏的关键项 3** | ✅ `resValue("string","file_browser_provider", ...)` 在 `fordebug` buildType 中保留，与 `FileProvider` 的 `${applicationId}.provider` 一致（清理前构建产物实测 fordebug 合并值 = `com.tungsten.fcl.debug.provider`，与 `applicationIdSuffix=".debug"` 匹配；`debug` 变体则用 `strings.xml` 的静态值 `com.tungsten.fcl.provider`，同样匹配） |
| 冗余 | ⚠️ `splits { abi }`（已无 native）、`fileTree("libs", ...)`（空目录）、`resValue` 的 `curse_api_key`/`oauth_api_key`（MC 用，无消费者）、`libs.versions.toml` 死条目（M-09） |

### 问 6：仅在运行期暴露的风险（编译期无法覆盖）

见 §9 全表。摘要（按可证伪度排序）：

| # | 风险 | 编译期为何不报 | 置信度 |
|---|---|---|---|
| 1 | `FCLPath` 未初始化的入口（M-01） | 静态字段，无编译期检查 | 静态确证（入口存在）/ 静态推断（当前不崩） |
| 2 | `fcl.log` 不落盘（M-02） | Kotlin 运行期异常被 `catch (IOException)` 吞 | 静态推断 |
| 3 | `ThemeEngine.applyBackground` 的 NPE（若复活） | 死代码，编译器不管可达性 | 静态确证（当前死）/ 静态推断（复活后 NPE） |
| 4 | API 31+ 启动页语义差异（M-06） | 资源限定符在 link 期不校验语义 | 待运行确认 |
| 5 | 指针标记影响 proot（M-07） | Manifest 属性无编译期影响 | 待运行确认 |
| 6 | 若启用 minify，反射点位崩溃（M-03） | minify 关闭时一切正常 | 静态推断 |
| 7 | 语言包缺失 → 界面回落英文 | 资源回落是运行期行为 | 静态确证 |
| 8 | 设备端孤儿数据 | 与 App 生命周期无关 | 静态确证（无害） |
| 9 | Node/脚本协议 | 跨进程、跨语言，编译期无关 | 静态确证（协议名逐项对照一致）；真机可用性待确认 |
| 10 | `Process.pid()` 在 ART 上取不到 | 反射 | 待运行确认（brief §6.1 已知项，非本次引入） |

---

## 8. 已确认无影响项

| 项目 | 结论 | 依据 | 验证方式 |
|---|---|---|---|
| 编译期符号引用完整性（Kotlin/Java/资源/Manifest） | ✅ 无残留引用 | clean build **BUILD SUCCESSFUL, 0 error**，34/34 tasks **executed** | `:FCL:clean :ZipFileSystem:clean` + `sh /workspace/run-compile.sh` |
| JVM 单测（dsh 核心逻辑） | ✅ 23/23 通过 | `TOTAL=23 FAILED=0` | `sh /workspace/run-tests.sh`（clean build 之后重跑） |
| 资源引用自洽性（`@layout/@drawable/@anim/@xml/@mipmap`） | ✅ 缺失 0 | 独立扫描脚本 + aapt2 link 通过 | 见 §10 命令 D-1 |
| 字符串自洽性 | ✅ `values` 缺失 0；`values-zh` 仅缺 3 条 `translatable="false"` 项（正确） | 同上 | 见 §10 命令 D-2 |
| 孤立资源（drawable/layout/anim/xml） | ✅ 0 孤立 | 全量引用统计 | 见 §10 命令 D-3 |
| Manifest 组件/权限悬空 | ✅ 0 悬空 | 11 组件类文件逐个核对存在；7 权限逐个核对有用点；`activity-alias` 归零 | 见 §10 命令 D-4 |
| 多进程 / `android:process` | ✅ 无 | 新 Manifest 0 命中；旧的 `:jvm`/`:crash` 组件已删；代码无 `:remote`/`:jvm`/`:crash` | 见 §10 命令 D-5 |
| ksp 插件 / 注解处理器残留 | ✅ 无 | 无模块声明 ksp；无 `@AutoService`；无 Room 注解；`schemas/` 已随之删除 | grep + clean build |
| ProGuard 误留已删类 | ✅ 不适用 | 仓库**无** proguard 文件（反向风险见 M-03） | `find . -name "*proguard*"` → 0 |
| 方法裁剪（6 个） | ✅ 零调用者 | `installJna`/`installJava`/`patchJava`/`checkElfIsAndroid`/`getElfArchFromZip`/`openLinkWithBuiltinWebView`/`showItemSelectionDialog` 全仓库 grep 命中 0（仅 1 处 KDoc） | 见 §10 命令 D-6 |
| `ChecksumMismatchException` 改父类 | ✅ 影响面为空 | 原父类 `ArtifactMalformedException` 所在的 `fclcore.download` 已整包删除 → `catch (ArtifactMalformedException)` 不可能存在 | 静态推理 + clean build |
| `ThemeEngine` 被反射调用 | ✅ 无 | `grep ThemeEngine` ∩ `forName/reflect/getClass` = 0；`<meta-data>` 仅 1 条且与类加载无关 | 见 §10 命令 D-7 |
| `FileProvider` 授权一致性 | ✅ 自洽 | fordebug：`resValue` = `com.tungsten.fcl.debug.provider` ↔ `applicationId` + `.debug` + `.provider`；debug：静态串 = `com.tungsten.fcl.provider` ↔ `applicationId` + `.provider` | 清理前的 fordebug 合并产物（已备份至 `/workspace/_review_evidence/fordebug-values.xml`） |
| **`extractNativeLibs` 保留** | ✅ **关键项未遗漏** | 合并 Manifest 实测 `android:extractNativeLibs="true"`（来自 `packaging.jniLibs.useLegacyPackaging = true`）—— 将来 `libproot.so` 可 exec 的前提 | 见 §10 命令 D-8 |
| cleartext 通道（WebView 本地 http） | ✅ 未破坏 | Manifest `usesCleartextTraffic="true"` + `res/xml/network_security_config.xml`（`cleartextTrafficPermitted="true"`）均在，且 `application` 仍声明 `android:networkSecurityConfig` | grep |
| Node/脚本协议（env 名、路径、信号） | ✅ 本次未改动且正向一致 | 见 §5 盲点 8 全表 | 逐名对照 + `git status` 确认 `com/dsh`/`assets/dsh` 为 untracked 未改 |
| `DshLogBus.attachFile` 重复绑定 | ✅ 幂等 | `DshLogBus.kt:84-86` `if (logFile?.absolutePath == file.absolutePath) return` + `@Synchronized`；`DshPaths.loadPaths` 内的调用因此安全 | 读源码 |
| prefs 兼容性（`launcher`/`theme`/`crash_reporter`） | ✅ 带默认值 / 有迁移 | `FCLActivity.java:75`、`CrashReportActivity.java:44`、`CrashReporter.java:705,714`、`ThemeData.kt:96-133` | grep + 读源码 |
| 孤儿 prefs / Room / DataStore | ✅ 无崩溃、无误用 | 零读取点或带默认值 | 见 §5 盲点 1 |
| `SplashActivity` 的 `super.onCreate` / `installSplashScreen` 顺序 | ✅ 非本次引入 | `git show HEAD` 第 63-64 行同序 | 静态确证 |

---

## 9. 待确认项与需补充信息

### 9.1 缺失材料（无法在本次审查中获得）

| 缺失项 | 影响 |
|---|---|
| `libproot.so` + `libproot_loader.so` | 无法验证 `resolveProotBin/Loader` 的 jniLibs 分支、`preflight` 的可执行位检查、指针标记（M-07） |
| `rootfs.tar.xz`（含 Node 22/24） | 无法验证 `DshBootstrap` 的首启解压（符号链接处理）、磁盘空间预检、Node 版本探测 |
| APK 产物（`assembleFordebug` 未执行） | 无法验证 dex 合并、方法数、APK 体积、安装行为 |
| 真机日志 / `adb logcat` | 无法验证 M-02（`fcl.log` 写入失败）、M-06（API 31+ 启动页）、M-07（proot 执行） |
| 变更前的基线 APK 与代码的对应关系 | `/workspace/dsh-fcl-arm64.apk` 是变更前旧包，**不代表现状**，未使用 |

### 9.2 需真机/CI 才能定的项

| # | 项 | 验证方法（可证伪） |
|---|---|---|
| V-1 | 通知栏冷启动直达 `DshInstancesActivity` 时是否一切正常（M-01 的「当前不崩」结论） | 装 APK → 启动一个实例（起前台服务与通知）→ `am force-stop com.tungsten.fcl.debug` → 下拉通知栏点通知 → 观察是否进入实例列表且不崩 |
| V-2 | `fcl.log` 是否真的建不出来（M-02） | 装 APK → 启动一次 → `adb shell run-as com.tungsten.fcl.debug ls -l /storage/emulated/0/FCL/log/` 与 `adb logcat \| grep -i "Unable to create fcl.log"` |
| V-3 | API 31+ 启动页表现（M-06） | 在 Android 12/13/14 设备上观察首屏：是否有黑屏/闪烁、图标是否正确 |
| V-4 | proot 在启用指针标记下能否执行（M-07） | 放入 `libproot.so` → 启动实例 → `adb logcat` 观察是否 `SIGSEGV`/`Illegal instruction`；对比把 `allowNativeHeapPointerTagging="false"` 加回后的表现 |
| V-5 | 首启解压 + 装 dsh + WebView 全链路 | 按 `PACKAGING.md` 放两个大文件 → 出 APK → 装机跑通「解压 → 自检 → 装 dsh → 启动 → WebView 出界面」 |
| V-6 | 前台服务保活（各厂商 ROM） | 多机型后台放置 30/60 分钟观察进程是否被杀（brief §6.1 已知项） |
| V-7 | `Process.pid()` 反射在 ART 上是否可用 | `adb logcat` 查告警日志；或改用 `/proc/self/task/*/children`（brief §6.1 已知项） |
| V-8 | dex 方法数 / APK 体积 | `sh /workspace/build-apk.sh` 后解析 APK |
| V-9 | 卸载后「保留数据」选项是否存在（M-07 副作用 2） | 真机卸载对话框观察（Android 10+） |

### 9.3 需人工确认的协议/反射/skin

| 项 | 为什么需要人工 | 当前静态结论 |
|---|---|---|
| `ThemeEngine.applyBackground` 是否会被「自定义背景」功能复活 | 属产品路线决策 | 当前死代码（✅） |
| 是否支持「从官方 FCL 升级」 | 涉及签名与 `applicationId` 决策 | 签名字段不同（`key-store.jks` vs `debug-key.jks`）→ **升级会失败，需卸载**（静态推断） |
| 是否要在 App 内保留语言切换入口 | `LocaleUtils` 仍支持 12 种语言，但 10 个 `values-*` 已删 | 无 UI 入口（原入口在 MC 设置页）→ 用户实际只能得到英文/简中 |
| `/sdcard/FCL/**` 里的旧 MC 用户数据如何处置 | 产品决策 | 不可达、无迁移 |
| 将来是否启用 R8 minify | 工程决策 | 启用前必须先补 keep 规则（M-03） |

---

## 10. 验证命令与结果

### 10.1 已运行命令与原始输出摘要

| # | 命令 | 结果（原始摘要） | 性质 |
|---|---|---|---|
| C-1 | `cd /workspace/FCL && git log --oneline -3` | `f4f2624 feat: 长按版本卡片弹出版本快速切换菜单…` | 基线确认 |
| C-2 | `git status --porcelain \| awk '{print $1}' \| sort \| uniq -c` | `11 ??` / `1228 D` / `11 M` | ✅ 与文档一致 |
| C-3 | `git diff --shortstat` | `1239 files changed, 323 insertions(+), 226529 deletions(-)` | ✅ 与送审值逐位一致 |
| C-4 | `git diff --numstat HEAD`（求和） | `insertions=323 deletions=226529` | ✅ 自洽 |
| C-5 | `git clean -ndX` | 7 项：`.gradle/`、`.kotlin/`、`FCL/.cxx/`、`FCL/build/`、`ZipFileSystem/build/`、`build/`、`local.properties` | ✅ 全是产物/本地配置 |
| C-6 | `git clean -nd` | 11 项（`assets/dsh/`、`com/dsh/`、8 个 dsh 布局、`src/test/java/com/dsh/`） | ⚠️ 就是本次成果 |
| C-7 | `find FCL/src -type d -empty` | （空输出） | ✅ 无空目录 |
| C-8 | `ls -A FCL/libs \| wc -l` | `0` | ✅ 空目录 |
| C-9 | `ls -d FCL/src/main/{jniLibs,jni,jreAssets}` | 三者均 `No such file or directory` | ✅ 已删 |
| C-10 | `ls -d Terracotta LWJGL` | 二者均 `No such file or directory` | ✅ 已删 |
| **C-11** | `export GRADLE_OPTS="-Xmx1300m -XX:MaxMetaspaceSize=450m"; ./gradlew --no-daemon --offline :FCL:clean :ZipFileSystem:clean` | `BUILD SUCCESSFUL in 1m 4s`，`2 actionable tasks: 2 executed` | — |
| **C-12** | `sh /workspace/run-compile.sh`（**clean 之后**） | `BUILD SUCCESSFUL in 3m 48s`，`34 actionable tasks: 34 executed`；**0 error**；仅 deprecation 警告 11 条（`ThemeEngine.kt:161-167` `systemUiVisibility`/`SYSTEM_UI_FLAG_*`、`DisplayUtil.kt:28,30` `getRealMetrics`/`getDefaultDisplay`、`AndroidUtil.kt:286` `WIFI_MODE_FULL_HIGH_PERF`）+ `Note: Some input files use or override a deprecated API.` | ✅ **clean build 通过（非增量）** |
| **C-13** | `sh /workspace/run-tests.sh`（clean build 之后） | `TOTAL=23  FAILED=0` | ✅ 23/23 |
| C-14 | `grep -rn "ksp" --include=*.kts --include=*.gradle --include=*.properties .` | 仅命中 `build/intermediates/**` 的二进制缓存路径 | ✅ 配置层 0 命中 |
| C-15 | `find . -name "*proguard*" -not -path "./.git/*"` | （空输出） | ✅ 无该文件 |
| C-16 | `grep -rn "android:process" FCL/src/main/AndroidManifest.xml` | （空输出） | ✅ 无多进程 |
| C-17 | `grep -cE "activity-alias"` / `grep -E "MainActivity\|WebActivity\|…\|FolderProvider"` | `0` / （空输出） | ✅ 无残留 |
| C-18 | 11 个 manifest 组件 × 类文件存在性脚本核对 | 全部 `OK(类存在)` | ✅ |
| C-19 | `grep -rn "getIdentifier"` | 3 处（`LocaleUtils.java:130` 活 + `AndroidUtil.kt:128,138` 死） | ⚠️ 修正文档 D-3 |
| C-20 | `grep -rn "applyAndSave\|applyBackground"`（排除 ThemeEngine 自身） | （空输出） | ✅ 死代码确认 |
| C-21 | `grep -rn "ThemeEngine" ∩ forName/reflect/getClass` | （空输出） | ✅ 无反射 |
| C-22 | `grep -rn "androidx.room\|Room\.\|SQLiteOpenHelper"` | （空输出） | ✅ Room 零残留 |
| C-23 | `git grep getSharedPreferences`（HEAD 与当前对比） | HEAD: `hidapi`/`launcher`/`theme`/`third_party`/`DraggableTextView`；当前: 仅 `launcher`/`theme`/`crash_reporter` | ✅ 孤儿 prefs 已定位 |
| C-24 | 逐文件 `wc -l` 对比 HEAD 与当前的 `SplashActivity.kt` | `286` → `64` | ⚠️ 修正文档 D-1 |
| C-25 | `cat res/values-v31/{colors,themes}.xml` | 2 个文件，`themes.xml` 覆盖 `Theme.FoldCraftLauncher`+`Theme.Splash` | ⚠️ 修正文档 D-4 |
| C-26 | `diff <(cat res/layout/activity_splash.xml) <(git show HEAD:...)` | 逐字节相同 | ⚠️ 修正文档 D-5 |

### 10.1b 补丁落地后的复验（`a62ed0d`）

| # | 命令 | 结果 | 性质 |
|---|---|---|---|
| P-1 | `git add -A && git commit`（固化审查对象） | `4aea9e5`，**1284 files changed, +6779 −226529**，工作区 `0` 脏文件 | ✅ untracked 归零 |
| P-2 | 备份 45 个 untracked 文件 → `/workspace/_review_evidence/untracked_backup/` | `com/dsh` 25 + `assets/dsh` 10 + 8 布局 + `test/java/com/dsh` 2 = **45** | ✅ 双保险 |
| P-3 | 落地补丁 A / B（3 文件） | `FCLApp.java` +11、`SplashActivity.kt` +17 −3、`FCLPath.java` +7 −1 | ✅ |
| P-4 | `git status --porcelain` | ` M` × 3（无 untracked、无意外文件） | ✅ |
| **P-5** | `:FCL:clean :ZipFileSystem:clean` → `sh /workspace/run-compile.sh` | `BUILD SUCCESSFUL in 3m 36s`，**`34 actionable tasks: 34 executed`**，**0 error**（仅既有 deprecation 警告） | ✅ **补丁后 clean build 通过** |
| **P-6** | `sh /workspace/run-tests.sh` | **`TOTAL=23  FAILED=0`** | ✅ |
| P-7 | `grep -rn "FCLPath.loadPaths" FCL/src/main/java` | **有效调用点唯一** = `FCLApp.java:41`；`FCLActivity.java:54`（恒不可达死分支，保留不动）；`SplashActivity.kt:58`（仅注释） | ✅ 去重成功 |
| P-8 | `grep -c "import com.tungsten.fclauncher.utils.FCLPath" SplashActivity.kt` | `1`（**未误删 import**，`FCLPath.LOG_DIR` 仍需要） | ✅ |
| P-9 | `grep -n "LOG_DIR = " FCLPath.java` | `54: LOG_DIR = context.getDir("log", 0).getAbsolutePath();` | ✅ |
| P-10 | `grep -c "Environment\." FCLPath.java` | `4`（`SHARED_COMMON_DIR`/`CONTROLLER_DIR`/`SHARE_DIR`/fallback）→ **`import android.os.Environment` 不可删，未误删** | ✅ |
| P-11 | `git commit`（补丁） | `a62ed0d`，**3 files changed, +31 −4**，工作区再次 `0` | ✅ |

### 10.2 资源自洽性复现脚本（本报告实际执行的版本，已修正口径）

```sh
cd /workspace/FCL/FCL/src/main
# D-1 layout/drawable/anim/xml/mipmap 引用
grep -rhoE '@(layout|drawable|anim|xml|mipmap)/[a-z0-9_]+' res/ AndroidManifest.xml | sed 's/@//' | sort -u | while read r; do
  t=${r%%/*}; n=${r##*/}
  find res/$t* -name "$n.*" 2>/dev/null | head -1 | grep -q . || echo "  [缺失] $r"
done
# D-2 string 引用（含 zh）
grep -rhoE '@string/[a-z0-9_]+' res/ AndroidManifest.xml | sed 's|@string/||' | sort -u | while read n; do
  grep -q "name=\"$n\"" res/values/strings.xml   || echo "  [缺失] @string/$n"
  grep -q "name=\"$n\"" res/values-zh/strings.xml || echo "  [zh 缺失] @string/$n"
done
# D-3 孤立资源（drawable/layout/anim/xml）
grep -rhoE 'R\.(string|drawable|layout|anim|xml|color|style|mipmap|id)\.[a-z0-9_]+|@(string|drawable|layout|anim|xml|color|style|mipmap)/[a-z0-9_]+' \
  java res AndroidManifest.xml | sed 's/^R\.//;s/^@//;s/\./\//' | sort -u > /tmp/refs.txt
for t in drawable layout anim xml; do
  for f in $(ls res/$t/ | sed 's/\.[^.]*$//'); do grep -qx "$t/$f" /tmp/refs.txt || echo "  [孤立 $t] $f"; done
done
```
**实测输出**：D-1 **无缺失**；D-2 仅 `[zh 缺失] @string/app_name`、`[zh 缺失] @string/color_picker_arrow`（二者 `translatable="false"`，正确）；D-3 孤立项为 11 个 layout，**逐一核实全部由 ViewBinding 引用**（`ActivityDsh*Binding`/`Dialog*Binding`/`ItemDsh*Binding`），**非真孤立**。

> ⚠️ **脚本首版有 bug（已修正，记录以免后人重复踩坑）**：最初用 `find res/$t* -name "$n.*"` 检查 `@string/*`、用 `sed 's/^R\.//'` 未把 `.` 规范成 `/`，导致 `drawable.background_dark` 与 `drawable/background_dark` 两种写法不互相匹配，**产生了 100+ 条假「缺失」**。判定真伪的权威手段仍是 **clean build 的 aapt2 link + R.jar 编译**（C-12）。

### 10.3 未运行的项及原因（严禁伪造）

| 未运行 | 原因 |
|---|---|
| `./gradlew clean assembleDebug` / `assembleFordebug` | 项目约定「改完不打包」；且**本次审查刻意未出包**，`assembleFordebug` 约 7 分钟且可能超时。因此 **dex/包体积/安装行为均未验证** |
| `./gradlew lint` / `checkstyle` | 沙箱内 lint 需额外依赖，且 3.7MB/506 文件的 Java 检查耗时较长；**未运行**，未产出 lint 报告 |
| 真机安装 / `adb` / `logcat` | 无设备 |
| Node / rootfs / proot 实际执行 | 缺少两个大文件（见 §9.1） |
| `app_process`/Robolectric 仪器测试 | 仓库无此类测试；且当前无 `src/androidTest` 目录 |
| `git add -A && git commit` | **未执行**（属仓库写操作，需用户授权） |

### 10.4 本次审查对工作区的影响（必须知情）

1. 为满足「必须 clean build」的要求，执行了 `:FCL:clean` 与 `:ZipFileSystem:clean` —— **`FCL/build/intermediates/**` 下的清理前产物已被删除**（包括 `mergeFordebugResources` 里那份可用于找回文案的合并 `values.xml`，见 `mc-removal §11.2` 的恢复方法）。
2. 已把其中两份关键证据备份到 `/workspace/_review_evidence/`：`fordebug-values.xml`（清理前的 fordebug 合并字符串表）、`AndroidManifest.xml`（合并后的 Manifest）。
3. **源码未做任何修改**（只读审查）；本报告（原 `/workspace/MC_REMOVAL_IMPACT_REVIEW.md`，已归档至 `docs/reports/mc-removal-impact-review.md`）。
4. `git status` 在 clean 前后**对源码的统计无变化**（`1228 D / 11 M / 11 ??`），本次操作**未触碰任何源码或未跟踪成果**。

---

## 11. 补丁 A / B / C

> **红线遵守声明**：以下改动**不重新引入任何已删除的依赖**、**不重写 `fcllibrary`**、**不基于不存在的文件给 diff**。
> 全部改动基于仓库中**实际存在**的文件与行号。
>
> ## ✅ 落地状态（2026-10-01）
> | 补丁 | 内容 | 状态 | 提交 | 文件 | 行数 |
> |---|---|---|---|---|---|
> | **A** | M-01 `FCLPath` 上提到 `FCLApp.onCreate` + 去掉 `SplashActivity` 重复调用 | **✅ 已落地** | `a62ed0d` | 2 | +17 −2 |
> | **B** | M-02 `LOG_DIR` → `context.getDir("log", 0)` + 修正 SplashActivity KDoc | **✅ 已落地** | `a62ed0d` | 2 | +14 −2 |
> | **C** | 文档批注（D-1 ~ D-8，覆盖 `mc-removal.md` / `brief` / `INDEX.md`） | **✅ 已批注 8/8** | 文档在仓库外，不随 git 提交 | 3 | +13 段批注 |
> | **D** | 可选：`DshPaths` 注释语义改写 / `allowNativeHeapPointerTagging` 加回 | ⏸ **未落地**（B 已在 SplashActivity KDoc 内说明"日志已迁私有目录"，`DshPaths` 注释改写待下一批） | — | — | — |
>
> **落地前置条件已满足**：`4aea9e5` 已把 11 项 untracked 全部提交（§15），改动前另备份 45 文件到
> `/workspace/_review_evidence/untracked_backup/`。

### 11.1 文件列表（实测最终状态）

| # | 文件 | 改动类型 | 实测行数 | 状态 |
|---|---|---|---|---|
| 1 | `FCL/src/main/java/com/tungsten/fcl/FCLApp.java` | 新增 `FCLPath.loadPaths(this)` + 11 行注释 | **+11 −0** | ✅ 已落地 |
| 2 | `FCL/src/main/java/com/tungsten/fcl/activity/SplashActivity.kt` | 删 1 行调用 + KDoc 重写（+17 −3） | **+17 −3** | ✅ 已落地 |
| 3 | `FCL/src/main/java/com/tungsten/fclauncher/utils/FCLPath.java` | `LOG_DIR` 一行 + 6 行注释 | **+7 −1** | ✅ 已落地 |
| 4 | `/workspace/docs/reports/mc-removal.md` | 5 段修正批注（顶部指针 + D-1/D-2/D-4/D-5 + §5.3 M-01/M-02） | **+58 行** | ✅ 已批注 |
| 5 | `/workspace/docs/reports/mc-removal-review-brief.md` | 顶部指针 + D-1/D-3/D-6/D-8 + 补丁落地回执 | **+67 行** | ✅ 已批注 |
| 6 | `/workspace/docs/INDEX.md` | 顶部保护块 + D-7 + 待办/已修 | **+28 行** | ✅ 已批注 |
| 7 | （可选，未做）`com/dsh/core/DshPaths.kt` | 仅改 KDoc 注释（**代码零改动**） | ±3 | ⏸ 待下一批 |
| 8 | （可选，未做）`FCL/src/main/AndroidManifest.xml` | 加回 `allowNativeHeapPointerTagging` | +1 | ⏸ 由 V-4 真机结论决定 |

**新增依赖：无。删除依赖：无。新增文件：无。** （第 8 项是 manifest 属性，不是依赖引入。）

### 11.2 关键 diff

#### 补丁 A（必做）——消除 `FCLPath` 的隐式依赖（M-01）

`FCL/src/main/java/com/tungsten/fcl/FCLApp.java`（当前 28~34 行）：
```diff
     @Override
     public void onCreate() {
         super.onCreate();
         this.registerActivityLifecycleCallbacks(this);
+        // FCLPath 是 FCLActivity（所有 Activity 的基类）与 ThemeEngine 的隐式前置：
+        // 原先在 FCLActivity.onCreate 里由 hasPermission 门控调用，删除存储权限后该分支恒不可达，
+        // 于是只剩 SplashActivity 一条路径会初始化它 —— 通知栏 PendingIntent 冷启动会绕过启动页。
+        // 提到 Application 层，保证任何入口（Activity / Service / PendingIntent）之前就已就绪。
+        com.tungsten.fclauncher.utils.FCLPath.loadPaths(this);
         // DeepSeek Harness 启动器：初始化路径与实例仓库。
         // 只用 App 私有目录（filesDir/cacheDir），不需要任何存储权限；幂等。
         com.dsh.core.DshPaths.loadPaths(this);
         com.dsh.core.DshInstances.init();
     }
```

`FCL/src/main/java/com/tungsten/fcl/activity/SplashActivity.kt`（当前 46~52 行）：
```diff
     private fun init() {
         lifecycleScope.launch {
             async(Dispatchers.IO) {
-                FCLPath.loadPaths(this@SplashActivity)
                 Logging.start(Paths.get(FCLPath.LOG_DIR))
             }.await()
             enterDsh()
         }
     }
```
> 删掉后 `import com.tungsten.fclauncher.utils.FCLPath` **仍需保留**（`:50` 用 `FCLPath.LOG_DIR`）。

**兼容性说明**：
- `FCLPath.loadPaths` 幂等（§3.2 子问题 2），重复调用的结果与单次相同 → **中途崩溃/重入/多入口均安全**。
- 该改动**不影响** `FCLActivity.java:53` 那段（仍恒假、仍无害）；也可以顺手删掉那 4 行死分支，但**不建议与本次同批**（会扩大 diff、增加回归面）。
- 该改动**同时消除了 §4.1 的 ⚠️**：`FCLPath` 就绪后，`ThemeEngine.applyBackground` 的 `LT_BACKGROUND_PATH`/`DK_BACKGROUND_PATH` 必非 null，即便将来被复活也不再有 NPE。
- 代价：约 19 次 `exists()/mkdirs()` 从 IO 线程移到 `Application.onCreate` 主线程（与同方法内既有的 `DshPaths.loadPaths` 一致，毫秒级）。

#### 补丁 B（必做）——修正 `DshPaths` 的注释语义（C-1 子问题 4）

`FCL/src/main/java/com/dsh/core/DshPaths.kt`（当前 145~153 行，**只改注释**）：
```diff
     /**
      * 解析 proot 主程序路径：优先用 jniLibs 打包（nativeLibraryDir 自带执行位，最稳），
      * 回退到 assets 解压目录（[DshBootstrap] 解压并加执行位）。
      *
      * 注意：targetSdk>=29 起，App 私有数据目录里的可执行文件受 W^X/SELinux 限制，
      * assets 方案在多数新机型上会 exec 失败，所以 jniLibs 方案实际是首选。
-     * @param nativeLibDir 传入 FCLPath.NATIVE_LIB_DIR；该字段可能为空（FCLPath 未初始化），
-     *                     这里容忍 null 并直接走回退路径（原来会 NPE）。
+     * @param nativeLibDir 传入 FCLPath.NATIVE_LIB_DIR。正常情况下该值已由 FCLApp.onCreate 初始化；
+     *                     此处仍容忍 null/空（例如直接实例化 Activity 的测试、或工具类被单独调用），
+     *                     并回退到 assets 解压目录 —— 属**防御性保护**，不是对未初始化状态的兜底。
+     *                     判空不可删除：即使 FCLPath 已初始化，nativeLibDir 也可能合法地不含 libproot.so
+     *                     （该文件由打包方后续放入），此时回退正是 assets 方案的工作路径。
      */
```
**兼容性说明**：零行为变更（`DshPaths.kt:154-169` 逻辑一字不动）。**这是防回归的关键**——见 §3.2 子问题 4「删掉判空会立刻破坏 assets 方案」。

#### 补丁 C（建议）——让 `LOG_DIR` 真正可写（M-02）

`FCL/src/main/java/com/tungsten/fclauncher/utils/FCLPath.java`（当前 49 行）：
```diff
-        LOG_DIR = Environment.getExternalStorageDirectory().getAbsolutePath() + "/FCL/log";
+        // [外壳改造] 原为 /sdcard/FCL/log：存储权限（MANAGE_EXTERNAL_STORAGE 等）已移除，
+        // targetSdk 34 下该路径不可写，Logging.start 会静默失败、fcl.log 永远建不出来。
+        // 改到 App 私有目录（与 DshPaths 的落盘位置同域，无需任何权限）。
+        LOG_DIR = context.getDir("log", 0).getAbsolutePath();
```
**兼容性说明**：
- 只影响 `FCLPath.LOG_DIR` 的取值与 `Logging.start` 的落点；`LOG_DIR` 的另外 2 个消费点在**死代码**里（`getLatestGameLog()` 零调用者）→ 无副作用。
- 旧设备上 `/sdcard/FCL/log/fcl.log` 会成为孤儿文件（无害）。
- **如果要保守**：也可以不改代码，改为在 `SplashActivity` 的 KDoc 中如实写明「日志文件不再落盘，仅 logcat + 内存缓冲」，但那样崩溃取证能力就永久缺失，**不建议**。

#### 补丁 D（建议）——加回 1 个 manifest 属性（M-07）

`FCL/src/main/AndroidManifest.xml` 的 `<application>`（当前 27~38 行）：
```diff
     <application
         android:name=".FCLApp"
+        android:allowNativeHeapPointerTagging="false"
         android:allowBackup="true"
```
**理由与边界**：该属性原为 FCL 的 native 栈（GL4ES/LWJGL/SDL）关闭指针标记；本仓库 native **库**已全部删除，但**仍要 exec 原生 proot 二进制**（`libproot.so`，且 proot 是"改指针"的活）。加回它的成本是 0，收益是消除一个只有真机才知道的坑。**这不是"重新引入已删除依赖"**（无依赖、无文件、无构建逻辑），只是一条属性。是否需要，由 V-4 的真机结论决定；**若 V-4 证明无影响，可以不加**。

### 11.3 不推荐的改动（明确划线）

| 不推荐 | 原因 |
|---|---|
| 删除 `DshPaths.resolveProotBin/Loader` 的 `isNullOrEmpty` 判空 | **会破坏 assets 方案的正常工作路径**（见补丁 B 说明） |
| 单独回滚 `AndroidManifest.xml` 或 `FCLApp.java` | 会引起「注册了不存在的组件」或「dsh 未初始化」，必须整体回滚 |
| 现在就把 `fcllibrary/browser` 删掉（M-04） | 与 M-01 无关、会显著扩大 diff；且删除后还需同步删 15 条字符串。**建议独立 PR** |
| 现在就把 `libs.versions.toml` 的死条目删净（M-09） | 低风险但会让 diff 里混入非功能性改动；**建议独立 PR** |
| 打开 R8 minify 以"减小体积" | **在补 M-03 的 keep 规则之前会静默崩溃**，绝对不要先于 M-03 |
| 用 `git checkout -- .` / `git clean -fd` 清理工作区 | **会不可恢复地删除 11 个 untracked 成果** |

---

## 12. 风险、兼容性、回滚

### 12.1 剩余风险分级

| 级别 | 数量 | 条目 | 是否阻塞合并 |
|---|---|---|---|
| **P0** | **0** | — | — |
| **P1** | ~~1~~ **0** | ~~M-01（`FCLPath` 隐式依赖 / 通知冷启动绕过启动页）~~ → **✅ 已修（补丁 A，`a62ed0d`）** | 已消除 |
| **P2** | **5**（原 6） | ~~M-02（日志不落盘）~~ → **✅ 已修（补丁 B）**；剩余：M-03（无 R8 keep 规则）、M-04（死浏览器模块 + 依赖已删权限）、M-05（设备端孤儿数据）、M-06（API 31+ 启动页语义差异，待确认）、M-07（manifest 属性删除的副作用，待确认） | 不阻塞 |
| **P3** | **7** | M-08 ~ M-14（死代码簇、配置冗余、悬空 styleable、孤儿视图/样式/字符串、死方法）—— 其中 **M-13（文档 8 处错误）已批注修正**，实体清理仍待做 | 不阻塞 |

**修复后剩余：P0 = 0，P1 = 0，P2 = 5，P3 = 7。**

### 12.2 数据迁移风险

| 数据 | 风险 | 判定 |
|---|---|---|
| App 私有 prefs（`launcher`/`theme`/`crash_reporter`） | **无** | 读取点全部带默认值；`theme` 有一次性迁移到 DataStore（`ThemeData.kt:96-101`）。静态确证 |
| 孤儿 prefs（`hidapi`/`third_party`/`DraggableTextView`） | **无** | 零读取点 |
| 孤儿 Room DB（`FavoriteDatabase` v2） | **无** | 零读取点；建议保留不删（避免误伤用户数据） |
| `/sdcard/FCL/**`（旧 MC 用户数据：存档、版本、整合包） | **中** | 本次变更后**不可达**（权限已删，无迁移、无提示）。**不丢（文件还在），但用户也拿不回来**（除非用文件管理器手动搬）。需产品决策 |
| dsh 自身数据（`filesDir/dsh/**`：rootfs、instances、npm-cache） | **无** | 本次变更未触碰 `com/dsh` 与 `assets/dsh` |
| 覆盖安装兼容性 | **中** | 签名不同（`key-store.jks` vs `debug-key.jks`）→ **从官方 FCL 升级会 `INSTALL_FAILED_UPDATE_INCOMPATIBLE`**，需卸载重装 → App 私有数据全丢（含已装 dsh 实例 ~300MB/个）。**发布说明必须写明** |

### 12.3 回滚方案

#### ❌ 「不改代码即可回滚」的判定：**不成立**

本变更**未提交**（`11 M` + `1228 D` 全在工作区，另有 `11 ??`）。因此：

| 回滚动作 | 能恢复什么 | 风险 |
|---|---|---|
| `git checkout -- <tracked path>`（逐个文件） | 11 个 M 文件 + 1228 个 D 文件回到 `f4f2624` | ⚠️ **会把 `FCLApp.java` 的 dsh 初始化一起回退** → 回滚后 dsh 不可用（且 `com/dsh` 仍在，形成"半回滚"的不一致状态） |
| `git checkout -- .`（全量受跟踪文件） | 同上，整仓库受跟踪内容回到基线 | 同上；**untracked 的 `com/dsh`/`assets/dsh`/8 布局/测试不会被动**（`checkout` 不动 untracked）→ 得到「上游 FCL + 一堆没被引用的 dsh 文件」 |
| `git clean -fd`（删未跟踪） | 清掉 dsh 成果 | 🚨 **不可恢复**（`com/dsh` 25 文件、`assets/dsh` 10 文件、8 个 dsh 布局、2 个单测全丢） |
| `git clean -ndX`（删忽略文件） | 清构建产物 | ⚠️ 会一并删 `local.properties`（含 SDK 路径），需重建 |

**结论**：
1. **「删 MC 的主体可回滚」**（受跟踪文件）—— 但必须**逐文件**挑，不能全量 `checkout`，否则连 dsh 的初始化一起回退到"死状态"。
2. **「新增的 dsh 成果不可回滚」**——git 救不回来。因此**任何回滚操作前必须先做备份或 `git add -A && git commit`**。
3. **最安全的一次性动作**：`git add -A && git commit -m "..."`。提交后本变更就变成"可 `git revert` 的一个 commit"，回滚从"危险的手工操作"变成"一条命令"。**这是本次审查的第一条操作性建议**（与 brief §7.G 的诉求一致）。

#### 回滚剧本（按需选择）

| 目标 | 操作 | 副作用 |
|---|---|---|
| 只想要一个"稳定的审查对象" | `git add -A && git commit`（**推荐先做**） | 无（只是固化） |
| 想知道"如果删错了能退回去吗" | 上面的 commit 之后 `git revert <sha>` | 恢复全部 1228 个被删文件与 11 个 M 文件；untracked 成果因被 commit 而一并保留 |
| 想单独恢复某个被删文件 | `git checkout f4f2624 -- <path>` | 只影响该文件；但恢复的类若被新代码引用会编译失败（不会静默错） |
| 想彻底回到上游 FCL | `git checkout -- .` **然后** manual 处理 `com/dsh` 的引用 | **不可行**——`FCLApp.java` 回到上游后 `DshPaths` 不再初始化，但 `com/dsh` 文件还在树里，属"能编译但入口没接线"的状态；需同时恢复 `SplashActivity.kt`（上游版会去找已删的 `EulaFragment`）→ **等价于整体回滚** |

---

## 13. 验收清单

- [x] **可 clean build** —— ✅ 已实测：`:FCL:clean :ZipFileSystem:clean` 后 `run-compile.sh` → `BUILD SUCCESSFUL, 34/34 executed, 0 error`（C-11/C-12）
- [x] **核心流程入口均已判定** —— ✅ 9 类入口逐一判定（§3.2 子问题 1 全表），覆盖 LAUNCHER / 通知 / 深链 / 广播 / 分享 / dsh 内跳转 / 进程回收 / 崩溃页 / 测试
- [x] **无 P0/P1 未处理** —— ✅ **P0 = 0；P1 = 0**（M-01 已于 `a62ed0d` 修复：补丁 A）；M-02（P2）亦已修复（补丁 B）
- [x] **对其他方面影响已排查** —— ✅ A~H 八维度 + 8 项盲点全部给出结论与证据（§2、§5）
- [x] **文档与代码一致** —— ✅ **已修正 8/8**（§1.4 D-1~D-8 全部就地批注）：`mc-removal.md` ×5（含 D-4 `values-v31`、D-5 横屏虚惊、D-2 缺 §6、D-1 行数、§5.3 M-01/M-02）、`brief` ×7、`INDEX.md` ×1。
  ⚠️ 但仍留有**未批注的过时文档**：`PLAN.md` / `PACKAGING.md` / `design/*` / `reports/round2~5`（brief §7.H 自认，本报告 §2.H 复述）
- [x] **回滚方案可行** —— ✅ 可行但**有条件**：受跟踪文件可回滚；untracked 成果**必须先备份/提交**（§12.3）。**「不改代码即可回滚」不成立**
- [x] **编译期无残留引用** —— ✅ clean build + 全仓库 grep 双向印证
- [x] **Manifest 四类无残留** —— ✅（§5 盲点 4）
- [x] **ksp / 注解处理器清理完整** —— ✅（§5 盲点 7）
- [x] **无多进程、无锁竞争** —— ✅（§5 盲点 5）
- [x] **Node 协议未受影响** —— ✅ 本次 diff 未触碰 `com/dsh` / `assets/dsh`，且正向核对一致（§5 盲点 8）
- [x] **补丁已落地且复验通过** —— ✅ `a62ed0d`（3 文件 +31 −4）；补丁后 clean build **34/34 executed, 0 error**、单测 **23/23**
- [x] **工作区已受保护** —— ✅ `4aea9e5` 提交 1284 files（+6779 −226529），untracked 归零；另备份 45 文件到 `_review_evidence/untracked_backup/`
- [ ] **真机端到端可用** —— ❌ **未验证**（缺 `libproot.so`/`libproot_loader.so`/`rootfs.tar.xz`；未出 APK；无设备）。这是本次变更**最大的整体空白**，且**在本次变更之前就已存在**
- [ ] **APK 可打包/安装** —— ❌ 未执行 `assembleFordebug`（按项目约定）

---

## 14. 结论

### 14.1 是否可合并 / 发布

| 问题 | 结论 |
|---|---|
| **删除边界是否正确？** | ✅ **正确。** 605 个源文件 + 282 个资源 + 全部 native/JRE/assets/libs + 2 个 Gradle 模块，**未发现**仍被存活代码/构建/manifest/资源引用的被删项（问 1、问 2 均为「未发现」）。`fakefx`(292)/`ZipFileSystem`/`Architecture`/`fclcore.task.Schedulers` 等"依赖临界点"的保留判定**均成立**（clean build 反证 + 引用计数印证） |
| **是否可合并？** | ✅ **可以合并（条件已满足）**。P0 = 0、P1 = 0、P2 剩 5（均不阻塞）。**补丁 A / B 已落地（`a62ed0d`）并复验通过**，审查对象已由 `4aea9e5` 固化 |
| **本次审查是否改变了结论？** | ❌ **没有改变"可以合并"。** 但改变了**合并的前提**：从「可以先合并、事后再修」变为「**修完再合并**」——因为 M-01 的症状（通知冷启动绕过启动页）**只在特定入口出现、极难复现**，一旦带着它发布，排查成本远高于那 1 行 |
| **是否可发布？** | ❌ **不可以。** 与本次变更无关的既有空白依然存在：**真机端到端从未跑通**（缺两个平台大文件）、**从未出过 APK**、**没有任何运行时验证**。本次变更把"能编译"维持住了（这本身是了不起的，226,529 行删除后 clean build 0 error），但"能编译"离"能用"还有一段距离 |
| **本次变更的净效果** | 把一个「必须先下 1GB MC 运行时才能摸到 dsh」的启动器，变成「装完即进 dsh 列表页」的纯 dsh 启动器，源码 1700+ → 506 文件、仓库内 native 0、assets 29MB → 70KB。方向正确，执行干净，**唯一实质缺陷是一个由"删权限"间接引起的隐式初始化依赖（M-01）** |

### 14.2 必须修复项（合并前）—— ✅ 全部已落地

| 顺序 | 项 | 成本 | 为什么必须 | 状态 |
|---|---|---|---|---|
| 1 | **M-01**：`FCLPath.loadPaths(this)` 上提到 `FCLApp.onCreate`，删除 `SplashActivity.kt` 中的重复调用 | +11 −2 | 这是本次变更**唯一由自己引入**的架构缺陷：删权限 → 基类初始化失效 → 只剩一条隐式路径，而通知冷启动能绕过它。修它同时消掉 `ThemeEngine` 的 ⚠️ | ✅ `a62ed0d` |
| 2 | **M-02**：`LOG_DIR` 由 `/sdcard/FCL/log` 改 `context.getDir("log",0)` + 修正 KDoc | +14 −2 | 删权限后外部存储不可写，`fcl.log` **永远建不出来**且异常被静默吞掉（崩溃后无可取证） | ✅ `a62ed0d` |
| 3 | **`git add -A && git commit`** | 1 条命令 | 把不可回滚的 `1228 D + 11 M + 11 ??` 变成可 `git revert` 的 commit | ✅ `4aea9e5` |
| 4 | **8 处文档批注** | 3 个文档 | 后续任何人先读 `mc-removal.md` 都会被 D-4 / D-5 带偏 | ✅ 8/8 |

**仍建议但未落地（不阻塞）**：`DshPaths.resolveProotBin` 的 KDoc 语义改写（原计划作为"补丁 B"，本次改为由 `SplashActivity` KDoc 承载说明；`DshPaths.kt` 注释可作为下一批）。**其判空逻辑必须保留**——即使 `FCLPath` 已初始化，`nativeLibDir` 也可能合法地不含 `libproot.so`（仓库内 `jniLibs/` 已删），此时回退 `assets/dsh/proot/` 正是 assets 方案的正常路径。

### 14.3 后续跟进项（不阻塞合并）

| 优先级 | 项 | 触发条件 |
|---|---|---|
| 高 | **真机端到端**（V-5）：放 `libproot.so`/`libproot_loader.so` + `rootfs.tar.xz` → `sh /workspace/build-apk.sh` → 跑通「解压 → 自检 → 装 dsh → 启动 → WebView」 | 拿到两个大文件、换到有设备的电脑上 |
| 高 | **V-1 / V-3 / V-4** 三项定点验证（通知冷启动——验证 M-01 修复、API 31+ 启动页、proot 与指针标记） | 有设备即可，都是一次安装能覆盖的 |
| 高 | **V-2**（`fcl.log` 是否真的写到私有目录）—— 验证 M-02 修复 | 与 V-1 同一次安装即可 |
| 中 | **M-07**（`allowNativeHeapPointerTagging="false"` 加回与否） | 取决于 V-4 结论 |
| 中 | **M-03**（补 R8 keep 规则 / 或显式声明不启用 minify） | 在有人想开 minify 之前 |
| 中 | **`DshPaths.resolveProotBin` KDoc 语义改写**（见 §14.2 末尾） | 下一批小改 |
| 低 | **M-04**（删 `fcllibrary/browser` 死模块 + `fileLauncher` 字段 + 15 条字符串） | 独立 PR |
| 低 | **M-08 / M-10 / M-14**（死代码簇、悬空 styleable、孤儿 style） | 做完整可达性分析后一起清 |
| 低 | **M-09**（`libs.versions.toml` 死条目 + `ksp` 别名 + `splits.abi` + 空 `fileTree("libs")`） | 独立 PR |
| 低 | **M-05**（设备端孤儿数据的处置策略 / 发布说明写明"不支持从 MC-FCL 升级"） | 与发布说明同批 |

### 14.4 一句话总结

> 这是一次**边界划得很准**的大规模删除（226,529 行删完后 clean build 0 error、23/23 单测通过、四类 manifest 清理零残留、资源引用零悬空、Node 协议零改动）；它**唯一的自身缺陷**是"删掉存储权限"这个动作间接掐断了 `FCLActivity` 里那条 `FCLPath.loadPaths()`，使得初始化退化为"必须先经过启动页"的隐式契约——当时靠 dsh 侧已有的 null 防护侥幸不崩，而**通知栏冷启动已经能绕过启动页**。
> **该缺陷与随之暴露的"`fcl.log` 写不进去"已于 `a62ed0d` 一并修复并复验（clean build 34/34 + 单测 23/23）；审查对象已由 `4aea9e5` 固化，工作区干净、可 `git revert`。**
> 与变更本身无关、但必须在报告里说清的是：**这个项目至今没有真机跑通过，也没有出过 APK**，因此本报告的一切"安全/无影响"结论都止于"静态确证"，**不等于能用**。修完 M-01/M-02 之后，剩下的最大空白**依然是真机端到端**（缺 `libproot.so` + `rootfs.tar.xz`，见 §9.1）。

---

## 15. 工作区保护提示（独立成节）

> ## ⚠️ 工作区保护（最高操作风险）—— 已于 2026-10-01 解除
> 审查开始时 `/workspace/FCL` 有 **11 项 untracked 条目**，包含整个 `com/dsh` 主体
> （25 源文件 + 10 assets + 8 布局 + 2 测试）。**任何** `git clean -fd`、
> `git checkout -- .`、`git reset --hard`、IDE 的「revert」都会**不可恢复地**删除它们。
> **合并/发布前必须先执行：`git add -A && git commit`。**
> 提交后回滚从「危险手工操作」变为一条 `git revert`。

### 15.1 实测证据（`git clean -nd`，11 项）

```
?? FCL/src/main/assets/dsh/                              （10 文件：proot/ rootfs/ scripts/ version README.md）
?? FCL/src/main/java/com/dsh/                            （25 源文件 = 整个启动器主体）
?? FCL/src/main/res/layout/activity_dsh_download.xml
?? FCL/src/main/res/layout/activity_dsh_instances.xml
?? FCL/src/main/res/layout/activity_dsh_logs.xml
?? FCL/src/main/res/layout/activity_dsh_settings.xml
?? FCL/src/main/res/layout/activity_dsh_webview.xml
?? FCL/src/main/res/layout/item_dsh_instance.xml
?? FCL/src/main/res/layout/item_dsh_version.xml
?? FCL/src/main/res/layout/view_dsh_bootstrap_banner.xml
?? FCL/src/test/java/com/dsh/                            （2 单测文件）
```
`git checkout` **救不回 untracked**；`git clean -fd` **不可恢复**。
对照：`git clean -ndX` 只会删 `.gradle/`、`.kotlin/`、`FCL/.cxx/`、`FCL/build/`、
`ZipFileSystem/build/`、`build/`、`local.properties`（7 项，全是产物/本地配置，**无害**）
—— 但注意它会连 `local.properties`（含 Android SDK 路径）一起删掉。

### 15.2 已执行的保护动作

| 动作 | 命令 | 结果 |
|---|---|---|
| 1. 备份 | `cp -a` 到 `/workspace/_review_evidence/untracked_backup/` | **45 文件**：`dsh_java` 25 / `dsh_assets` 10 / `dsh_layouts` 8 / `dsh_test` 2 |
| 2. 固化 | `git add -A && git commit` | **`4aea9e5`** —— `1284 files changed, 6779 insertions(+), 226529 deletions(-)` |
| 3. 校验 | `git status --porcelain \| wc -l` | **`0`**（工作区干净，untracked 归零） |
| 4. 落地补丁 | 补丁 A / B | **`a62ed0d`** —— `3 files changed, +31 −4` |
| 5. 复验 | clean build + 单测 | **`34/34 executed, 0 error`** / **`23/23`** |

提交历史（三段式，可逐段回滚）：
```
a62ed0d  fix: 修复审查发现的 P1/P2 两处缺陷（补丁 A / B）      ← 本报告 §11
4aea9e5  refactor: 移除全部 Minecraft 代码与资源，改为纯 dsh 启动器  ← 审查对象（1284 files）
f4f2624  feat: 长按版本卡片弹出版本快速切换菜单...              ← 基线（上游 main）
```

### 15.3 回滚剧本

| 目标 | 命令 | 副作用 |
|---|---|---|
| 只撤销补丁 A / B | `git revert a62ed0d` | 回到「有 M-01/M-02 缺陷但可编译」的状态 |
| 撤销整个 MC 移除 | `git revert 4aea9e5` | 恢复 1228 个被删文件 + 11 个 M 文件；**`com/dsh` 因已被 commit 而一并保留**，需另行处理 |
| 完全回到上游 | `git reset --hard f4f2624` + 清理 dsh 文件 | 🚨 **`reset --hard` 会丢工作区改动；执行前务必确认没有未提交内容**（当前已是干净的，但仍应养成先 `git status` 的习惯） |

### 15.4 同类风险提示（写给下一次）

1. **长期把成果停在 untracked 状态 = 把唯一的副本交在 `git clean` 手上。** 本次能安全审查，
   是因为审查一开始就做了「先备份、再提交、后动刀」三步；反过来做（先动刀）就会在
   第一次 `git checkout -- .` 时毁掉 `com/dsh`。
2. **`git checkout -- <文件>` 对「含未提交改动」的文件是破坏性操作** —— 本项目已经踩过一次
   （`mc-removal.md §11.2`：124 条 dsh 文案被回退，靠构建中间产物侥幸找回）。
   改用 `git stash` 或先 commit 再 checkout。
3. **`git checkout -- .` 与 `git clean -fd` 的组合是这次变更最危险的命令对**：
   前者把受跟踪文件退回上游（于是 `SplashActivity` 会去找已删的 `EulaFragment`，
   编译立刻失败），后者删掉 dsh 成果（**永久**）。两者叠加 = 变更整体蒸发且不可恢复。
4. **本次审查已代执行保护动作**（§15.2）。若后续还要做实验性改动，
   建议先用 `git switch -c exp/xxx` 开分支，而不是继续在主分支工作区裸改。

---

**报告结束。**
本报告 §0~§15 全部非空；表格配对与代码块闭合已自检通过（详见 §16 自检记录）。

---

## 16. 报告自检记录

| 检查项 | 方法 | 结果 |
|---|---|---|
| §0~§15 是否全部非空 | `python3` 逐章统计字符数 | ✅ 全部 ≥ 1000 字符，无空章 |
| 表格是否配对 | 扫描「以 `\|` 开头但不以 `\|` 结尾」的行 | ✅ **异常 0 行** |
| 代码块是否闭合 | 统计 ```` ``` ```` 围栏行数是否为偶数 | ✅ **28 行 → 偶数，闭合** |
| 是否含未验证的「运行时通过」表述 | 全文核查 | ✅ 无；所有运行结论均附**已执行命令 + 原始输出摘要**（§10.1 / §10.1b） |
| 红线遵守 | 全文核查 | ✅ 未建议重新引入任何已删依赖；未建议重写 `fcllibrary`；未对不存在的文件给 diff；未使用「看起来安全/应该没问题」；未在未备份时执行破坏性 git 命令 |
| 置信度标注 | 逐条核查 | ✅ 每条结论均标 静态确证 / 静态推断 / 待运行确认 |

