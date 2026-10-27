# 贡献指南

## 1. 先说清楚这是什么项目

这是一个**个人项目**：高一牲用 AI 辅助（vibe coding）写出来、在真机上一点点磨的
DeepSeek Harness（dsh）安卓启动器，基于 [Fold Craft Launcher](https://github.com/FCL-Team/FoldCraftLauncher)（GPL-3.0）改造。

先说三件实话，免得你白等：

- **不承诺响应时效**。上课、考试、被 bug 卡住都可能导致几周不看 Issue。
- **不承诺"这个功能我要做"**。这里的方向由作者自己定，Issue 里说得很好的点子也可能不做。
- **但 PR 会被认真读**。带测试、说清动机的 PR，通过率比"只改一行"高得多。

如果你能接受这三点，往下看。

## 2. 报告问题

**先搜一遍 [Issues](https://github.com/sqkl520/dsh-fcl-android-launcher/issues)**（含已关闭的）——
不少问题在 Releases 说明里已经写了根因和绕法，比如 WebView 内核过旧导致的白屏。

开新 Issue 时请用模板，并尽量带齐这六样：

| 需要 | 为什么 |
|---|---|
| **版本号后缀**（关于页里那串，如 `0.1.6-SNAPSHOT-fix14`） | 没有它无法判断你装的是不是当前验证包；README 历史上有过版本号对不上的事故 |
| **机型 + Android 版本** | proot / WebView / 存储路径的行为跟 ROM 强相关 |
| **复现步骤** | "点了启动就白屏"和"装完 dsh 点启动白屏"是两类问题 |
| **日志片段** | 应用内日志页可复制；实例日志与全局日志分开看，贴对应那份 |
| **是否 root** | 本项目主打**未 root**；root 环境会改变 proot 的权限判定 |
| **期望 vs 实际** | 一句话即可，但要有 |

### 日志怎么取

- **应用内**（最省事）：实例详情页的「日志」看该实例，设置页的全局日志看整体；长按可复制。
- **落盘文件**（不走 logcat —— 本项目的 dsh 日志没有写给 `android.util.Log`，logcat 里抓不到）：
  - 全局：`<filesDir>/dsh/logs/runtime.log`（1 MB 截断轮转）
  - 实例：`<filesDir>/dsh/logs/instance-<id>.log`
  - 取法（**只有调试通道能用 `run-as`**，正式包不行）：
    ```sh
    adb shell run-as com.dsh.fcl.androidlauncher.debug cat files/dsh/logs/runtime.log
    ```
- **应用直接崩了 / 原生 proot、pty 层报错**（Java 层日志抓不到的那种）才用 logcat：
  `adb logcat -d > logcat.txt`。
- 注意：`tools/ondevice.md` 那份更详细的上机手册在**工作区快照仓**里，源码仓不收录，
  别在文档里链它（会 404）。

贴日志请**截掉 API Key、token、访问链接**。dsh 的访问链接里带凭据，贴到公开 Issue 等于公开你的实例。

## 3. 提出改动

**先开 Issue，再写代码。**

原因很实际：这个仓库的历史里，有些"看起来该这么改"的地方是**踩过坑之后才写成现在这样**的
（比如为什么单测用 `testFordebugUnitTest` 而不是 `testDebugUnitTest`、为什么停止要等 13 秒、
为什么"装好了没"只允许一套判据）。你不先问一句，很可能把一条带着注释和断言的防线拆掉，
PR 就得来回改。

Issue 里写清：**你要解决什么现象 / 你打算怎么改 / 会不会动到已有测试**。
作者回一句"可以"再动手，最省双方时间。

## 4. 提交 PR

```text
fork → 从 main 开分支 → 改 → 本地跑绿 → 提 PR
```

- 分支名随意，建议 `fix/xxx` 或 `feat/xxx`。
- 一个 PR 只做一件事。顺手改格式、顺手重命名、顺手升依赖，都会被要求拆开。
- **`sh run-tests.sh` 必须绿**（或等价 Gradle 单测全绿，见 §6），PR 描述里贴结果。
- 提交信息用中文、写清"改了什么 + 为什么"；仓库历史就是这个风格，照着写即可。
- **不要**在提交信息或 PR 里加 `Co-Authored-By` / `Generated with ...` 之类的署名尾巴。

## 5. 开发环境

**不要在这里重复搭环境的步骤**——直接看 [`docs/ENVIRONMENT.md`](docs/ENVIRONMENT.md)：

- §1 Windows（当前主力环境）：JDK 17、Android SDK 35、NDK 27 的三条接线（`JAVA_HOME`、`local.properties`、环境变量）。
- §2 arm64 Linux：手机沙箱里的另一套接线。
- §1.3 **行尾**：`*.sh` 必须 LF，`*.cmd` 必须 CRLF —— 仓库有 `.gitattributes` 兜底，但别手动改坏。
- §4 验证环境：三条 Gradle 命令，抄了就能跑。

一句话版本：**JDK 17 + Android SDK 35 + NDK 27**，SDK 路径写进 `local.properties`（该文件不进 Git）。

## 6. 本地构建与测试

### 6.1 Windows（`gradlew.bat`）

```powershell
# 编译校验（Kotlin + Java + 资源 + Manifest 合并）
.\gradlew.bat --no-daemon -Darch=arm64 :FCL:compileDebugKotlin :FCL:compileDebugJavaWithJavac `
  :FCL:processDebugResources :FCL:processDebugMainManifest

# 单元测试——注意任务名是 testFordebugUnitTest
.\gradlew.bat --no-daemon :FCL:testFordebugUnitTest

# 打包（出货包需要 release 密钥 + 已备好 rootfs，见 docs/PACKAGING.md、docs/ROOTFS.md）
.\gradlew.bat --no-daemon -Darch=arm64 :FCL:assembleRelease
```

> **为什么不是 `testDebugUnitTest`？** 本模块的 `testBuildType = "fordebug"`（`FCL/build.gradle.kts`），
> 单测跑在 `fordebug` 变体上，所以任务名是 `testFordebugUnitTest`。`testDebugUnitTest` 也能跑，
> 但**不是**本项目的发版门禁，结论可能和 CI 前的那一轮不一致。

### 6.2 Linux / 手机沙箱（纯 POSIX sh）

```sh
sh run-compile.sh    # 先编主代码（单测依赖它的产物）
sh run-tests.sh      # Kotlin 单测 + 运行时脚本自检
sh build-apk.sh      # 出 APK 到 output/
```

三条都要**按顺序**跑：`run-tests.sh` 用的是 `run-compile.sh` 编出来的 class，
而且它自带"产物新鲜度检查"——源码比 class 新就直接报错退出，防止拿旧实现跑出假绿。

> **这两个 `run-*.sh` 在哪？** 它们在**工作区快照仓**的根目录，不在 Android 源码仓的 clone 里。
> 只 clone 源码仓的话，用 §6.1 的 Gradle 命令，做的事完全一样。
> `build-apk.sh` 还要求 `FCL/src/main/assets/dsh/rootfs/rootfs.tar.xz` 已就位（142 MB，不入 Git）。

### 6.3 "绿"的标准

**0 失败**，且测试条数**不低于你改动前的数字**。

> 不在文档里写死"当前 N 条"——这个数字每轮都在涨，写死就必然过期（README 顶部的
> 徽章已经因为这个原因错过一次：`394` 一直挂到实际 `438`）。以你自己跑出来的结果为准：
> 先记下改动前的条数，改完再跑，只许增加、不许减少。

## 7. 代码风格

从现有代码里总结出来的，不是凭空立的规矩：

- **Kotlin 优先**。新逻辑写 `.kt`；Java 只留 FCL 遗留部分。纯逻辑（判据、状态派生、格式化）
  抽成**无 Android 依赖的纯函数**，这样能被 JVM 单测直接钉住（参考 `com.dsh.core` 下的 `planRepair`、
  `versionRowStates`、`resolveLaunchTarget`）。
- **注释写"为什么"，不写"是什么"**。`// 递增计数` 这种注释会被删；写"这里必须等 13 秒，因为最坏情况
  要 12 秒，8 秒永远走超时分支"才有价值。仓库里大量注释是踩坑记录，**改代码前先读注释**。
- **中文注释、中文提交信息**。界面文案走三语资源（简中/繁中/English），不要把中文硬编码进布局。
- **一个函数一件事**。状态机、判据、文案映射分开写，别在一个方法里既判断又弹框。
- **不要为了"更优雅"重写能跑的状态机**。这些地方每一行都对应一个真机事故。
- **UI 跟 FCL 走**：控件、主题、交互沿用 FCL 体系（见 `docs/design/app-shell.md` §2.5），
  不要引入新风格（Material3 组件、新图标库等）而不先讨论。

## 8. 测试的硬要求

这一节没有商量余地：

1. **新增行为必须有断言**。修 bug 就先写一条能复现的失败用例，再修到它绿。
   只改代码不写测试的 PR，会被要求补。
2. **不许恒真断言**。`assertEquals(2, 1 + 1)`、`assertTrue(true)`、自己给自己赋值的断言——
   这类东西在这个仓库里出现过，现在被明确禁止：断言必须能因为实现改变而失败。
3. **`sh run-tests.sh` 必须绿**（或 `:FCL:testFordebugUnitTest` 全绿）。红了就说明没做完，
   不要用"这个用例本来就不稳"解释——不稳定的用例本身就是要修的对象。
4. **测不到的东西要写清**。凡是只能真机验证的（proot 启动、WebView 内核、SELinux），
   在 PR 描述里写明"已在 X 机型实测 / 未实测，需要作者上机验证"，不要含糊过去。
5. **不让单测依赖执行顺序**。进程级单例（如 `DshInstances`）在 `@Before`/`@After` 里都要复位。

## 9. 不要提交的东西

| 路径 / 文件 | 原因 |
|---|---|
| `local.properties` | 本机 SDK 路径，已在 `.gitignore` |
| `release-key/`（`dsh-release.jks`） | **出货签名私钥**，泄露等于任何人都能发"官方包" |
| `FCL/src/main/assets/dsh/rootfs/rootfs.tar.xz` | 142 MB，超 Git 单文件限制，且可重建（见 `docs/ROOTFS.md`） |
| `*.apk` / `output/` | 产物走 GitHub Release 附件，不进 Git 历史 |
| `build/`、`.gradle/`、`.kotlin/` | 可重建的缓存 |
| 日志、`.hprof`、截图临时文件 | 见 `.gitignore` |
| 仓库里已有的 `debug-key.jks` / `key-store.jks` | 是 FCL 遗留的**调试**签名，**不要替换、不要删**，否则老包升级链断掉 |

提交前自查：

```sh
git status
git diff --cached
```

看到上面任何一项出现在暂存区，就 `git restore --staged <file>`。
