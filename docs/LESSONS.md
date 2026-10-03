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
- **⚠️ qemu 包装脚本的"双重包装"坑**：如果按
  `for f in *; do [ -f "$f.real" ] && continue; mv "$f" "$f.real"; 写包装脚本; done`
  这种写法循环，**刚生成的 `X.real` 会在同一次循环里被再包一层**（因为此时 `X.real.real` 还不存在，
  守卫条件拦不住），于是变成 `X` → `X.real`(脚本) → `X.real.real`(真二进制)，
  而 `X.real` 是脚本、被 qemu 当 x86_64 ELF 执行 → 直接失败，且**报错信息极不明显**
  （cmake 只会说 "CMake will not be able to correctly generate this project"）。
  - **判断方法**：`ls $NDK/toolchains/llvm/prebuilt/linux-x86_64/bin/*.real.real | wc -l`
    不为 0 就是被双重包装了
  - **修复**：对每个 `X.real.real` 执行 `mv -f X.real.real X.real`（保留外层包装脚本不动）
  - 本项目 NDK 27 曾出现 38 个工具被双重包装，修复后 clang 恢复正常
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

## 2.5 rootfs 构建与 l2s 机制（重要）

### `.l2s.*` 不是垃圾

本沙箱的 proot 层会把**某些条目实现为 `.l2s.*` 形式的链接/背衬文件**（形如
`/usr/bin/.l2s.perl.dpkg-new0001`、`/var/lib/dpkg/.l2s.status0001`）。

- **不要**把它们当临时文件删掉——删了会让对应的正式条目（如 `/usr/bin/perl`）变成坏条目，
  之后连 `dpkg` 都报 `unable to stat './usr/bin/perl' ... Operation not permitted`，`apt-get` 也随之失败
- 正确做法：**打包时排除**，例如 `tar --exclude='.l2s.*' -cf rootfs.tar -C rootfs .`
- 若确实出现**悬空**的 `.l2s` 链接（`readlink` 结果含 `.l2s.` 且目标不存在），才可以删；
  恢复被删的包用 `chroot <rootfs> apt-get install -y --reinstall <pkg>`

### rootfs 里"看起来断了"的软链可能是正常的

guest 内的绝对路径软链（如 `/usr/local/bin/node -> /opt/dsh/node/bin/node`）在**宿主**上会被判为断链。
按"清理断链"批量删除会**误删 guest 的正常软链**（本项目第一次就误删了 `/usr/local/bin/{node,npm,npx}`）。
判据：只删除 `readlink` 结果里含 `.l2s.` 且目标不存在的链接。

### 嵌套 proot 会破坏内层 rootfs 的 stat

沙箱自身就是 proot；在其内部再跑 proot（`proot -r <rootfs>`）时：

- `execve`、`open`、`access` 正常（程序能跑、`cat` 能读、`fs.existsSync` 返回 true）
- 但 **`stat`/`statx` 会失败并报 ENOENT**（`ls`、`stat`、`node` 的模块路径解析都会中招）

这是**沙箱的嵌套伪影，不是 rootfs 或设备的问题**。要验证 rootfs 内容，改用 `chroot <rootfs> ...`
（本项目在 chroot 下验证了 node/npm/bash/dsh/`.node`/child_process 全部正常）。

### bind 挂载点会遮蔽 rootfs 里的同名目录

`ProotCommand` 会把宿主 `filesDir/dsh` 绑定到 guest 的 `/opt/dsh`。
**绑定是整目录替换**：rootfs 镜像里 `/opt/dsh/` 下原有的东西会全部看不见。

- 实测教训：曾把官方 Node 放在 `/opt/dsh/node`、预装 dsh 放在 `/opt/dsh/app`，
  设备上这两个会"消失"（只有宿主 `filesDir/dsh` 的内容可见）
- 正确做法：**rootfs 自带的东西放 bind 目标之外**（本项目最终为 `/opt/node22` 与
  `/opt/dsh-preinstalled`），`/opt/dsh` 只用于宿主数据（scripts/instances/npm-cache）

### node-addon 加载器的硬链接缓存会误伤（重要）

dsh 启动时若报：

```
No usable native binding found for node-addon-require-builtin-linux-arm64-gnu (auto)
```

深挖 `error.attempts[].attempts[]` 可见真正原因：

```
EINVAL: invalid argument, readlink '.../native-cache/node-addon-require-builtin-linux-arm64-gnu/0.1.7/.../linux-arm64-gnu-napi-v9.node'
```

即 `node-addon-native-custom-loader` 会先把 `.node` **硬链接**到
`os.tmpdir()/node-addon-native-custom-loader-<uid>/native-cache/...` 再 `require`；
某些文件系统/沙箱上该硬链接不成立，于是一路失败。

**对策**：设 `NARB_DISABLE_NATIVE_CACHE=1`（本项目已在 `start-dsh.sh` / `probe.sh` 默认开启），
让加载器直接从包目录 `require` 原始 `.node`。

### 其他

- web profile 的 HMR 插件要求 `--expose-internals`（不能放进 `NODE_OPTIONS`，只能直接传给 node）；
  缺了它 dsh 仍能起，但会打印 `hmr ...: Error: --expose-internals is required for HMR service`

### 后台任务活不过一次工具调用

`nohup ... &` 起的后台进程会在本次 `workspace_shell` 调用结束时被 SIGTERM 杀掉
（debootstrap 就是这样中断的）。长任务必须**在单次调用内跑完**，或拆成多步
（例如先 `tar -cf`，再单独 `xz`，避免 tar+xz 一次超时）。

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

---

## 7. 缺口判据要与"真会失败"的入口一致（第九轮）

**现象**：`DshBootstrap.isReady()` 只查 version 文件 + start-dsh.sh + rootfs 布局，**没查 proot 二进制**；
但 `missingSummary()` 和 `ProotCommand.preflight()`（启动/安装真正会执行的那一步）都把 proot 当必要条件。
于是出现"`isReady()==true`（bootstrap 横幅隐藏、界面看着正常）但一启动就 preflight 报『缺少 proot』"的
**假就绪**——用户得到的第一个错误藏在点"启动"之后，而不是在首页横幅上。

**教训**：凡是有一个"就绪判定"（横幅/图标/状态）和一个"实际执行入口"（启动/安装/自检），
两者的**必要条件集合必须一致**。就绪判定比执行入口少条件，就会产生"看着好了、一用就炸"的假就绪；
多条件则会产生"永远就绪不了"的空转。本轮把 `isReady()` 对齐到 `preflight()`（proot 可解析），
并为 proot 分支补了"文件缺失即解压"的兜底，堵住两个方向。
审查时可用这个套路：把"是否就绪"的判据逐条与"会真正失败的那个函数"的检查项对表。
