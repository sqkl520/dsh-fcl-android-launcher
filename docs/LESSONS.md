# 经验与踩坑记录

> **本文件放"方法论 / 踩过的坑 / 环境约束"**；`CHANGELOG.md` **只记"改了什么"**（变更事实）。
> 遇到新坑或新经验，追加到这里，不要写进 CHANGELOG。

---

## 1. 沙箱 / 构建环境

### 1.1 单次 git 传输有大小上限（重要）

本沙箱（跑在用户手机上的 proot 环境）**对单次网络传输有硬上限**，约 **146MB 处必然断开**。

- 现象：`git push` 到 ~146MB 时 `Connection closed by remote host` / `did not receive expected object`
- **是确定性的**，不是随机抖动：重试 3 次、换 22 端口 / 443 端口，都在**同一个对象**上失败
- 对照实验：推送一个 6MB 的小分支 → 秒成功。说明**远端与凭据都没问题，纯粹是体积**
- 对策：**大体积推送前必须先精简**

**历史精简手法**（保留全部提交信息与源码，只剔除大二进制）：

```bash
git filter-branch --force --index-filter \
  'git rm -r --cached --ignore-unmatch <路径1> <路径2> ...' \
  --prune-empty -- --all
rm -rf .git/refs/original
git reflog expire --expire=now --all
git gc --prune=now --quiet
```

- 本项目实测：`.git` **337MB → 7.2MB**，7 个提交全保留（信息/日期/作者不变），改动仅 SHA 变化
- **改写前务必备份** `.git`（本项目备份在 `_review_evidence/git-backup-before-strip/`）

### 1.2 SSH 走 443 端口更稳

GitHub 的 ssh 也可走 443（受限网络更友好）：

```
ssh://git@ssh.github.com:443/<owner>/<repo>.git
```

### 1.3 其他环境约束

- `~/.ssh` **可能不跨会话持久**；密钥丢了要重新生成 + 重新把公钥加到 GitHub
- 编译耗时长（3~9 分钟）；长时间占用会被 `workspace_shell` 的调用超时打断（表现为 gradle 被 SIGTERM）
- aapt2 / cmake / ninja / clang 等工具是 **x86_64**，在 arm64 沙箱里需用 `qemu-x86_64-static` 包装
- 沙箱内 `.git/objects` 里可能出现 `.l2s.tmp_*` 残留符号链接（中断操作的产物），会挡住 `git gc` 与备份；确认无引用后直接删掉

---

## 2. 死代码清理方法论

### 2.1 判据

**"全仓 0 引用" 不够，要用传递闭包**：

1. 收集所有类 → 建立"类 ← 引用它的文件"映射（扫描 `java/` + `res/` + `AndroidManifest.xml`）
2. 根 = 被 `res/` 或 Manifest 引用的类（以及入口类）
3. 迭代剔除：一个类若没有任何"活着"的引用者，则判为死
4. **逐个核验候选，不要照单全删**；删完**必须编译验证**

### 2.2 ⚠️ Kotlin 的两个大坑（会误判成"死代码"）

按**类名 grep** 对 Kotlin 会漏判，导致把活代码当死代码删掉：

| 坑 | 说明 | 真实案例 |
|---|---|---|
| **顶层函数** | `DialogUtil.kt` 里定义的是顶层函数 `showErrorDialog`，调用方写 `import com.mio.util.showErrorDialog` —— **调用点不含 "DialogUtil" 这个词**，按类名 grep 判定为 0 引用 | `DialogUtil` 被误删（删完编译报错才发现），实际被崩溃上报链路 `LogSharingUtils` 使用 |
| **`Kt` 后缀 / `@file:JvmName`** | Kotlin 文件编译成 `XxxKt` 类，Java 侧引用写 `LogSharingUtilsKt`；正则 `\bLogSharingUtils\b` 因后面紧跟 `Kt`（词字符）而**匹配不上** | `LogSharingUtils` 被误判为死 |

**对策**：Kotlin 文件不能只看类名，要同时搜 **文件名 + 顶层函数名 + `文件名Kt`**；删完必须编译。

### 2.3 差点误删的第三例

`OperatingSystem` 只被 `CommandBuilder`（死）与 `CompressingUtils`（死）引用，看似可删；
但**还被活着的 `IOUtils` 引用** → 保留。**必须做传递闭包，不能只看"引用者数量"。**

### 2.4 资源判定的坑

**"布局没有 `R.layout.xxx` 引用" ≠ 无用**：开启 ViewBinding 后，布局通过
`ActivityXxxBinding.inflate() / .bind()` 使用，引用的是**绑定类名**而非 `R.layout`。
判定无用布局时要同时搜 `XxxBinding`。

---

## 3. Android / FCL 平台经验

### 3.1 改 `namespace` 的连锁影响

把 `namespace` 从 `com.tungsten.fcl` 改成别的包名时：

1. **`R` / `BuildConfig` / `databinding` 的生成包跟着变** → 所有 import 要批量改
2. **容易被漏掉的是"内联全限定引用"**：如 `context.getString(com.tungsten.fcl.R.string.xxx)`，
   它不含 `import` 关键字，按 import 批量替换会漏 → 编译报 `Unresolved reference 'R'`
3. **`AndroidManifest.xml` 里的相对类名会错位**：`android:name=".FCLApp"` 是相对 **namespace** 解析的，
   namespace 一改就指向不存在的类 → 必须改成**全限定名**（`com.tungsten.fcl.FCLApp`）
4. 源码**物理目录**与 `package` 声明可以不动（只改"对外身份"），前提是继承链上的类仍能被正确引用

### 3.2 W^X 限制的真正边界

Android 10+ 对 `targetSdk ≥ 29` 的应用数据目录启用 W^X：

- **禁 `execve`**（把数据目录里的文件当程序执行）
- **不禁 `dlopen`**（把数据目录里的 `.so` 当插件加载）—— FCL 在 targetSdk 34 下
  `dlopen` 数据目录里的 `libjvm.so` 跑 JVM 就是反例

推论：proot 跑 rootfs 里的 `node`/`sh` 是 `execve` → 被禁；
需要 **PROOT_LOADER 机制**（把 proot 的 loader 放进 `nativeLibraryDir`）绕过。详见
`design/wx-exec-proot-loader.md`。

### 3.3 FCL 相关

- `FCLActivity` 基类**已统一应用全屏策略**，子类无需再调 `applyFullscreen`
- `ThemeData.isFullscreen` 在 Kotlin 侧访问会失败（Java getter 命名），直接用 `getTheme()` 读属性即可
- `FCLSpinner` 的 API 与 Android 原生 `Spinner` **完全不同**：
  用 `setItems(...)` / `getSelectedItem()`，不是 `adapter = ArrayAdapter(...)` / `selectedItem`

---

## 4. 文档与流程约定

- **`CHANGELOG.md` 只记"改了什么"**（Added/Changed/Fixed/Removed/Optimized/Refactored + 验证结果）；
  经验、方法论、踩坑写进本文件
- 文档主副本在 `/workspace/docs`（仓库外工作区），**改动后要同步进仓库 `docs/` 并提交**
- 代码改完**先不打包**，只跑 `run-compile.sh` / `run-tests.sh`；等明确要求再出 APK
- 涉及真机运行时行为的结论，**不要只凭机制推理下死判断**——本项目已因此误判两次
  （"dlopen 也被 W^X 禁"、"targetSdk 必须降到 28"），后被真机数据推翻

---

## 5. WebView / 平台行为

### 5.1 onPageFinished 也会在加载失败后回调

WebView 对**失败页面**同样会回调 `onPageFinished`（常见顺序：`onPageStarted` → `onReceivedError` → `onPageFinished`）。
所以"在 onPageFinished 里无条件切到正常 UI"会把你刚在 onReceivedError 里显示的错误面板盖掉，
用户只会看到一片空白。**判据**：用一个 `pageFailed` 标志，在 onReceivedError / HTTP 4xx 置 true、
onPageStarted 清零，onPageFinished 里先查它再决定显不显示正常内容。

### 5.2 杀死子进程后要等"目录写干净"再删目录

Android 的 `Process.destroy()` / 发 SIGTERM 是**异步**的（还有 KILL 兜底，最长要等几秒）。
如果你紧接着 `deleteRecursively()` 删它的工作目录（node_modules 动辄 300MB），
进程还在写时目录删不干净 → 留下占用大量空间且界面无提示的孤儿。**要删就要"停 + 等退出"再删**，
本项目为此给 `DshRuntime` 加了同步版 `stopAndWait()`（轮询状态离开 Stopping）。

## 6. UI 与 FCL 一致性

### 6.1 "去 Material" 的验收要跑到底

`app-shell.md §2.5` 要求 dsh 布局一律用 fcllibrary 控件。**阶段 2 迁移时只改了一部分页面**，
`activity_dsh_instances.xml` / `activity_dsh_webview.xml` 两个布局里仍混着 `MaterialButton` / 原生
`ProgressBar` / `TextView`——正是 §2.5.5 验收命令 `grep -l "com.google.android.material" *dsh*.xml`
会抓住的地方（改前输出这两行，改后为空）。所以验收命令不只是给别人看的，**每轮动手前先跑一遍**，
避免"方案里写了、代码没全落地"的漂移。当时 `activity_dsh_webview.xml` 的 `?android:attr/colorBackground`
也是原生主题引用，不属于 FCL 视觉（FCL 用 `bg_container_white`），一并换掉。

- **`CHANGELOG.md` 只记"改了什么"**（Added/Changed/Fixed/Removed/Optimized/Refactored + 验证结果）；
  **经验、方法论、踩坑写进本文件**
- 文档主副本在 `/workspace/docs`（仓库外工作区），**改动后要同步进仓库 `docs/` 并提交**
- 代码改完**先不打包**，只跑 `run-compile.sh` / `run-tests.sh`；等明确要求再出 APK
- 涉及真机运行时行为的结论，**不要只凭机制推理下死判断**——本项目已因此误判两次
  （"dlopen 也被 W^X 禁"、"targetSdk 必须降到 28"），后被真机数据推翻
