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
  - **NDK 工具链**：`$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin/*`
  - **SDK 自带 cmake**：`$SDK/cmake/<ver>/bin/{cmake,ninja,cpack,ctest}`
    （不包装的话，AGP 的 `externalNativeBuild` 会直接 SIGILL，报 `finished with non-zero exit value 132`）
  - 包装脚本要防"双重包装"（见下）
- **打包 APK 会很慢**：300MB 的 `rootfs.tar.xz` 若被 aapt 二次压缩，打包阶段可能超过单次调用时限。
  已在 `build.gradle.kts` 里 `androidResources { noCompress += listOf("xz") }`；
  若仍超时，**直接重跑**即可（Gradle 增量，已完成的任务会跳过）
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

## 4. 版本管理与打包

### 4.0 版本号与归档约定

| 版本号 | versionCode | 含义 |
|---|---|---|
| `0.1.0-SNAPSHOT` | 100 | 首版（经两轮真机实测，已归档到 `apk-archive/0.1.0/`） |
| `0.1.1-SNAPSHOT` | 101 | 0.1.0 之后所有未打包改动（DNS 修复 + 前置页 + 任务区 + 动画全对齐） |
| `1.0.0` | — | 留给「能正常启动 / 下载 / 管理 dsh」之后 |

发版流程：
1. 改 `build.gradle.kts` 的 `versionName` / `versionCode`
2. 改 `build-apk.sh` 的 `VERSION=`
3. 打包（`build-apk.sh` 会自动落 `output/` + `apk-archive/<ver>/` + 两处 `SHA256SUMS`）
4. 旧版留在 `apk-archive/<旧版本>/` 不动（快照，不删）
5. `output/` 只放当前版本（旧版移走或被新版覆盖）
6. CHANGELOG 按版本号建段

### 4.1 产物落点（三处各司其职，不要混用）

| 位置 | 用途 | 是否入 Git |
|---|---|---|
| `<工作区>/output/` | **交付/取件**（设备端可见），只放当前版本 | 否 |
| `<工作区>/output/legacy/` | 已废弃的历史冒烟包（已标注可删） | 否 |
| `<仓库>/apk-archive/<version>/` | **版本快照**（保留历史版本便于回滚）+ `SHA256SUMS` | 二进制否；`SHA256SUMS`/`README.md` 是 |
| `<仓库>/FCL/build/outputs/apk/` | Gradle 原始产物；`build-apk.sh` 用 **`mv`** 搬到 `output/`，不留第三份 | 否 |

### 4.2 关于 `/tool_outputs`（不要用）

`/tool_outputs` 是**沙箱内临时目录**，设备端不存在，不是挂载点。
早期打包曾把 APK 放那里，导致设备端取不到件。现在统一用 `/workspace/output/`。

### 4.3 SHA256SUMS 写法

只写**文件名**（不写路径），因为 `output/` 与 `apk-archive/<ver>/` 两处文件同名。
两个目录都能直接 `sha256sum -c` 校验。

### 4.4 build-apk.sh 的产物搬运用 `mv` 不用 `cp`

原来 `cp` 到 output + `cp` 到 archive + Gradle 产物 = **三份 300MB** 副本。
改用 `mv` 把 Gradle 产物搬到 output（Gradle 下次打包会重新生成），副本降到 2 份。

### 4.5 旧产物的处理

MC 时代的早期冒烟包（含 LWJGL/SDL/ANGLE/Mesa + PLACEHOLDER rootfs）：
**不删**，移到 `output/legacy/` 加日期标注。在 README 里说明"可删除以释放空间"。
判断依据：用 `zipfile` 看 `lib/arm64-v8a/` 有没有 `libjvm`/`lwjgl`/`libawt`，
`assets/dsh/rootfs/` 有没有 `PLACEHOLDER.txt`。

### 3.0 长任务千万别绑在"页面级作用域"上（真机实测踩到）

本项目页面是 ViewPager2 里的 `FCLCommonUI`，页面被回收时其协程作用域会被 cancel。
把「解压 300MB rootfs」这类**分钟级任务**放在页面作用域里会得到两个坏结果：

1. 切页 → 协程取消 → 进度对话框再也不刷新（用户看到的就是"卡死"）；
2. 用户再点一次 → 撞上互斥守卫 → 只得到一句「已有解压任务在进行」，**依然看不到进度**。

正确做法：任务跑在**进程级作用域**（本项目 `DshAppScope`），进度用 `StateFlow` 暴露，
界面只做订阅渲染；同时给任务一个 `owner` 标识，让"同一入口重复点击"变成**幂等**而不是报错。

### 3.0a FCL 的图标控件分工（踩过：我从一处用法推断成"一律"，结论错了）

FCL 里两个长得很像的控件，**承载图标的方式与自带反馈完全不同**：

| | `FCLImageButton`（AppCompatImageButton） | `FCLImageView`（AppCompatImageView） |
|---|---|---|
| 图标放哪 | `image` → **`setImageDrawable`**（XML 写 `android:src`） | `image` → **`setBackground`**（XML 写 `android:background`） |
| 缩放 | **`setScaleType(FIT_XY)`** | 未设置（默认 FIT_CENTER） |
| 按压反馈 | **自带 `RippleDrawable`**（颜色 `ltColor`，半径 `no_padding ? 12 : 20` dp） | 无 |
| 典型用途 | **可点击的图标按钮**（`item_version` 的设置/删除、`item_profile` 的删除、`item_remote_version` 的 wiki/save） | **静态图标**（`item_download_task` 的取消、列表行左侧图标） |

- 可点击图标按钮的 FCL 写法：`FCLImageButton` + `android:src` +
  `android:stateListAnimator="@xml/anim_scale_large"` + `app:auto_tint="true"`
  （尺寸多用 `wrap_content`；要紧凑时 `app:no_padding="true"`）
- ⚠️ **教训**：我最初只看了 `item_download_task.xml` 一处，就总结出「图标按钮一律用 FCLImageView、
  不要 FCLImageButton」——这是**从单个样本推断普遍规则**，结果把用户已经对的东西改错了。
  正确做法是先 `git grep` 出**全部**用法再归纳（本项目最后靠 `git grep -l '@xml/anim_scale'` 才看清分布）。

### 3.0b FCL 的水平进度条规范

FCL 所有水平进度条**一律**这样写（已逐一核对 9 个布局，高度全是 3dp）：

```xml
<com.tungsten.fcllibrary.component.view.FCLProgressBar
    style="@style/Widget.AppCompat.ProgressBar.Horizontal"
    android:layout_height="3dp"
    android:indeterminateDrawable="@drawable/bg_progress_indeterminate"
    android:max="1000" />
```

- 用 `Widget.AppCompat.ProgressBar.Horizontal`（**不是** `?android:attr/progressBarStyleHorizontal`）
- 不确定态要显式给 `indeterminateDrawable`，否则用系统默认样式（观感与 FCL 不一致）
- `bg_progress_indeterminate` 是 `animation-list`（引用 `progress_indeterminate_rect1/2`）

### 3.0c FCL 的动画清单（避免重复排查）

FCL 的动画只有这些来源，**没有隐藏菜单**：

- `res/anim/`：`fcl_spinner_popup_enter/exit`、`progress_indeterminate_rect1/2`、
  `frag_start/stop_anim`（后两个是 fragment 子页用，本项目用 ViewPager2 不需要）
- `res/xml/`：`anim_scale`（0.9，可点击行）、`anim_scale_large`（1.5，图标按钮 / `FCLMenuView` / 52dp 元素）
- 代码里只有四处：`UIManager`/`FCLMultiPageUI`（切页淡入 + 上滑 30dp / 250ms）、
  `FCLMenuView`（选中态 tint + Ripple + scale）、`FCLSpinner`（弹窗 `setAnimationStyle` + 箭头 ValueAnimator）、
  `FCLDynamicIsland`（文字切换）
- **FCL 的 Activity 转场是 `makeCustomAnimation(0,0)`（硬切）、对话框也没设进出场动画** —— 别在这两处"补动画"
- 其余三个"看起来像 MC 专属"的动画组件，本项目**也已照单全收**（用户要求完全套用 FCL）：
  - `SwipeMenuLayout`：`item_favorite` / `item_remote_mod` 的左滑管理菜单 → 我们用在实际例行（设置/删除）
  - `WaveProgressView`：FCL 主页的大进度 → 我们用在首启前置页的主进度（28dp）
  - `AnimUtil`：各列表适配器 `onBindViewHolder` 末尾 `playTranslationX(root, animationSpeed*30L, -100f, 0f)`
    → 我们用在实例列表 / 版本列表的入场动画（注意它会让"整列表重绑时全部一起滑入"，这是 FCL 的既有观感）

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

---

## 8. 就绪判据要对"每一维"对表，不是对"条数"（第十轮）

第九轮把 `isReady()` 对齐到 `preflight()`（补上"有没有 proot 文件"），**第十轮又发现漏了一维**：
`preflight()` 查的是 `exists() && canExecute()`，`isReady()` 查的是 `isFile`。
于是"二进制存在但没有执行位"这种打包异常，仍然表现为**假就绪**——横幅隐藏、点"启动"才报错。

**教训**：对表时逐维比 —— `exists` / `isFile`（不是目录）/ `canExecute` / **内容可用**
（rootfs 要 `bin/sh` 在、脚本要入口文件在）。判据集合必须是执行入口的**超集**，
少一维就多一类假就绪；"检查了 5 个条件"不等于"检查对了"。

同一轮还印证了另一件事：**门禁测试红了就等于没有门禁**。
`DshResourceFormatTest` 的第二条用例（"带占位符的 dsh 文案必须登记进 callSites"）
在阶段 D 新增 `dsh_about_version` 后就一直失败，而它守护的正是第五轮那个
"`%d` 收到 String → 进页面必崩"的 P0。**每轮动手前先跑一遍 `run-tests.sh`**，
和 §6.1 里"每轮先跑 §2.5.5 验收命令"是同一个道理。

---

## 9. 常量要能被实测值证伪（第十轮）

`DshBootstrap.MIN_FREE_BYTES` 写的是 `1500 MiB = 1,572,864,000 B`，注释说"大致覆盖 rootfs + 一个实例的
node_modules"。实际 `du -s --block-size=1 <解压后的 rootfs>` = **1,607,908,864 B** ——
**光 rootfs 就比门槛大**。也就是说这道检查"通过"之后照样会写满磁盘、留下半个 rootfs。

**教训**：凡是"保护某个资产"的阈值（磁盘、内存、超时、重试次数），都要拿**资产的实际尺寸/实际耗时**
对一遍，并且把实测数字写进注释里（哪个命令、什么环境、多少字节）。靠读代码永远看不出
"1500 MiB 够不够"——`du` 一下就知道了。

顺带的两条：
- **检查要放在"真的需要它"的分支里**。原来空间检查在 `install()` 最前面，早于
  "这次到底要不要解压 rootfs"的判定 —— rootfs 已就绪、只是补脚本的场景会被"空间不足"**假失败**。
- **破坏性替换要先备份后上位**。"先 `deleteRecursively()` 旧目录、再 `renameTo()` 新目录"，
  两步之间失败就是**新旧全丢**。改成"旧目录改名 `.old` → 新内容上位 → 校验 → 成功删备份 / 失败回滚"，
  峰值占用不变（旧实现解压 `.tmp` 时旧目录本来也还在），但失败从"底座全没"变成"回到旧版可用"。

---

## 10. 编进去了 ≠ 能用：JNI 符号名 / 无调用方的 native 代码（第十轮）

`src/main/cpp/ptyjni/ptyjni.c` 是从上游 oonid/pr 的 `:proot-engine` 搬来的，符号名仍是
`Java_id_or_oo_pr_engine_PtyNative_*`；本项目的 Kotlin 类是 `com.dsh.core.PtyNative`。
JNI 按"类全限定名 + 方法名"查找实现，**对不上就是 `UnsatisfiedLinkError`**。

它之所以一直没炸，只因为**全仓没有调用方**（`ProotRunner` 走的是 `ProcessBuilder`）。
这类"编进了 APK、但没人用"的 native 代码最危险：静态审查看不出问题、编译 100% 通过、
体积照付，等哪天有人接上去才发现要改包名。

**对策**：
- 搬 native 代码时，**第一件事就是改符号名**（或给 Kotlin 侧加 `@JvmName`/保持上游包名），
  并在文件头写明"改包名/类名必须同步"；
- 用 `nm -D --defined-only lib*.so | grep Java_` 与 Kotlin 侧的类名对一遍，这是**一条命令的验收**；
- 对"无调用方"的原生库（本项目还有 `libbusybox.so` + `DshPaths.resolveBusybox()`）定期做去留决策，
  不要让它长期停在"半接入"状态。

---

## 11. 别改写 tar 的符号链接目标（第十一轮，真机阻塞级）

`RuntimeUtils.uncompressTarXZ` 里有一行从 FCL 上游继承来的处理：

```java
Os.symlink(tarEntry.getLinkName().replace("..", dest.getAbsolutePath()), linkPath);
```

看着像"把相对链接补成绝对路径"，实际是把相对目标**拼坏了**。内核解析相对链接是
**相对链接所在目录**，而 `replace("..", dest)` 会把 `..` 直接换成宿主解压根目录，
语义整个变了。

实测本项目 rootfs（Debian bookworm + 官方 Node 22）里几百个链接命中，其中两个是致命的：

```
opt/node22/bin/npm  -> ../lib/node_modules/npm/bin/npm-cli.js
  正确目标: <rootfs>/opt/node22/lib/node_modules/npm/bin/npm-cli.js   （readlink -f 存在）
  实际写入: <rootfs>/lib/node_modules/npm/bin/npm-cli.js              （readlink -f 不存在）
opt/dsh-preinstalled/node_modules/.bin/dsh -> ../@deepseek-ai/dsh/lib/bin.js
  实际写入: <rootfs>/@deepseek-ai/dsh/lib/bin.js                      （断链）
```

后果链：真机首启解压 → `npm` 断链 → `npm --version` 失败 → `probe.sh` 的 `npm` 项 FAIL →
`DshBootstrap` 自检不通过 → **底座永远不就绪**，安装/启动全挂。

**为什么前几轮没发现**：验证 rootfs 时用的是 `chroot <rootfs>`（在宿主上直接构建出来的目录，
符号链接本来就是对的），**没有走 `RuntimeUtils` 的解压路径**。这类"验证路径 ≠ 真实路径"的
盲区要专门对一遍：凡是"打包 → 解压 → 使用"的链路，验证必须**从解压产物**开始，
而不是从构建产物开始。

**正确做法**：符号链接目标**原样写入**（tar 语义，GNU tar 亦如此）。相对目标交给内核按
链接所在目录解析；绝对目标交给 proot 在 guest 内翻译。任何改写都会破坏它。
本项目已把策略抽成 `com.dsh.core.TarLinkPolicy.symlinkTarget`（恒等函数 + 单测），
让"不做改写"这条决定有代码与测试兜着，防止再次被"顺手优化"掉。

---

## 12. 优化一旦改变"产物形态"，判定口径必须同步（第十一轮，真机阻塞级）

阶段 D-1 加了一个很好的优化：`setup-node-dsh.sh` 命中 rootfs 预装版本时
**跳过下载**，直接 `DONE source=preinstalled`。问题是它改变了**产物形态** ——
优化前"装成功"必然意味着实例目录里有 `node_modules`；优化后"装成功"可能
**实例目录里什么都没有**（真正的包在 rootfs 的 `/opt/dsh-preinstalled` 下）。

而 Kotlin 侧三处判定仍然只看实例路径：

| 位置 | 作用 | 结果 |
|---|---|---|
| `DshInstaller.readInstalledVersion` | 装完硬校验 | 读到 null → `dsh_install_incomplete` → **BROKEN** |
| `DshInstances.repair` | 冷启状态校准 | 降级成 `NOT_INSTALLED`（把装好的实例"修"坏） |
| `DshRuntime.startLocked` | 启动前校验 | 报「未安装」，拒绝启动 |

于是"装完即坏、重装还是坏、永远起不来"——一个纯优化变成了阻塞项。

**教训**：任何"跳过某一步"的优化，都要问一句**「跳过之后，产物长什么样？原来依赖这个产物
存在的那些判定还成立吗？」**。这里的正确做法不是回退优化，而是把"产物在哪"这件事
**收敛成一个解析函数**（`DshPaths.effectiveDshDir(instanceDir, rootfsDir)`：实例优先 →
预装回退 → 都没有返回预期路径），三处判定统一走它，并配单测覆盖三条分支。

顺带一条：脚本里 `DSH_PREINSTALL_DIR`（`/opt/dsh-preinstalled`）、`ProotCommand.GUEST_ROOT`
（`/opt/dsh`）、`DshPaths.PREINSTALLED_DSH_REL` 是**同一件事的三个写法**，分处 sh / Kotlin。
本次在注释里写明"改一处必须三处同步"，但仍属**待收敛项**（建议后续由一处生成或加一致性测试）。

---

## 13. 验证要"能证伪"：拿真实资产 + 独立 harness（第十一轮方法论沉淀）

本轮两个 P1 都是**编译 100% 通过、单测全绿、脚本一致性全绿**却真实存在的缺陷。能抓住它们，
靠的是三条"可证伪"的验证手段，值得固化成习惯：

1. **`readlink -f` 对照**：不满足于"代码看起来对"，直接对真实 rootfs 跑
   `readlink -f opt/node22/bin/npm`，看目标**存不存在**。一行命令就证伪了
   "相对链接会被正确解析"的假设。
2. **独立 harness 打真实资产**：写一个 20 行的 JVM harness，对**真实 rootfs 目录**调用
   `DshPaths.effectiveDshDir` + `DshInstaller.readVersionFromPackageJson`，
   打印"旧逻辑 → null / 新逻辑 → 0.1.6-alpha.2"。比读代码强得多，且可复现。
3. **`du` / `ls -l` 量一下**：承第十轮——凡是"保护某个资产"的阈值、或"某个路径应该存在"
   的假设，都拿实测值/实测存在性对一遍。

---

## 14. "跟 FCL 走"要拿**原版布局**当尺子，别凭印象（第十一轮）

`design/app-shell.md` §2.5 从第七轮起就是硬性要求："界面布局 / 按钮布局 / 整体主题一律跟 FCL 走"。
第七轮做的是"把 Material 控件换成 fcllibrary 控件"，验收命令也只查这个 ——
所以"0 个 Material 控件"通过了，但**观感仍然不像 FCL**。原因是三件事，只有对照原版布局才能发现：

**① 视觉资产被删了，界面对不上是必然的。**
"阶段 4 裁剪"删 MC 资源时，把 FCL 的**通用 UI chrome**（非 MC 内容）一并删了：
`bg_game_menu`（左侧菜单半透明底）、`bg_right_menu`（右侧面板底）、`bg_item_rounded`（圆角行底）、
`bg_container_transparent_clickable`（列表行透明+按压高亮）、`bg_progress*`（FCL 进度条形态）。
没有这些，"跟 FCL 走"只能走成"自己发明一个长得像的"。**教训**：裁剪资源时要按"是否 MC 专属"分类，
而不是按"当前有没有布局引用它"——后者会把设计系统一起删掉。

**② 布局结构（不只是控件类型）必须逐部件对表。**
dsh 外壳虽然用了 fcllibrary 控件，但：左侧菜单没有背景条与 100dp 抬升、右侧面板做成了白卡片
（FCL 是 25% 宽半透明面板）、**动态岛被放到了顶部**（FCL 在底部居中）、缺 `back` 菜单项、
缺 `video_view`。这些都是"控件对了、结构错了"。

**③ 行级范式不同。**
FCL 的列表行有两种范式，必须按"这一行在 FCL 里是什么位置"选：
- **列表项**（`item_version` / `item_profile`）：容器 + `stateListAnimator="@xml/anim_scale"`（按压反馈）
  + `focusable`；档案/实例列表用 `bg_container_transparent_clickable`，版本列表用
  `bg_container_white` + `auto_tint`。
- **设置行**（`item_launcher_setting_button`）：`bg_item_rounded` + 左右 12dp +
  横向行 `minHeight=48dp` + 标签左/动作右 + 描述在下。
dsh 原来两种都做成了"白卡片 + 无按压反馈"。

**做法（可复用）**：把原版布局从 git 历史取出来当尺子，而不是靠记忆：

```sh
git ls-tree -r --name-only <删资源前的提交> | grep 'res/layout/'     # 有哪些原版布局
git show "<提交>:FCL/src/main/res/layout/activity_main.xml"           # 逐部件对照
git show "<提交>:FCL/src/main/java/.../LauncherSettingAdapter.kt"     # 连行为（分组分割线）一起看
```

顺带一条：**FCL 的分组视觉不是"标题行"，是间距+分割线**。
`LauncherSettingPage` 用 `SpacingItemDecoration`（组内 1dp 分割线用主题色画、跨组 8dp、首行 10dp），
`LauncherSettingAdapter.isNextInSameGroup()` 提供判断。dsh 原来自己发明了"分组标题行"，
看着像但就不是 FCL。**结论**：还原 UI 时"行为"（间距、分割线、按压反馈、动画）要和"控件"一起还原。

---

## 15. 一次改完要按"验收命令"复跑（第十一轮）

§6.1 已经写过"每轮动手前先跑 §2.5.5 验收命令"，本轮补上另一半：**改完也要跑**，而且要把
验收从"有没有 Material"扩展成三条：① 布局里 0 个 Material；② 每个页面都有 fcllibrary 控件；
③ 代码里 `MaterialAlertDialogBuilder` 归零。只查 ① 会漏掉"控件对了但结构不对"和"对话框还是 Material"。

---

## 16. Windows 上开发：脚本进了 Git 就可能是 CRLF（2026-10-05，搬到电脑时踩到）

**背景**：`FCL/src/main/assets/dsh/scripts/*.sh` 会被**原样打进 APK**，真机上由 proot 里的
`/bin/sh` 执行。本机 Git 的 `core.autocrlf=true`（Windows 常见默认），
`* text=auto` 会把它们检成 **CRLF**。

**后果**：首行变成 `#!/bin/sh\r` —— 内核找的解释器是 `/bin/sh\r`，不存在 → 脚本根本不执行。
而且这个坑**在 Windows 上完全看不出来**（文件内容"看着"就是对的），只有装到手机上才炸。
本项目三个脚本都中招（probe / setup-node-dsh / start-dsh），是整条底座启动链。

**修复（两处都要做，缺一不可）**：

```powershell
# ① 全局配置：让 Git 认得 LF（Windows 默认 true 会到处转 CRLF）
git config --global core.autocrlf false
git config --global core.eol lf

# ② 仓库内声明（防止别人 clone 时又按自己机器的默认来）
#    .gitattributes 追加：
FCL/src/main/assets/dsh/scripts/** text eol=lf
```

**注意**：改完 **`git checkout --` 不会自动把已检出的 CRLF 转回 LF** ——
必须让文件重新过一次索引（`git rm --cached` 删索引项 + 删工作区文件 + `git checkout HEAD -- <路径>`），
或者干脆重新 clone。验证用 `git ls-files --eol <路径>`，要看到 `i/lf  w/lf`。

**推广**：凡是"文本文件要被另一个操作系统/解释器直接执行"的资产（shell 脚本、Dockerfile、
sbatch 脚本、CI 脚本），都应该显式声明 `eol=lf`，别指望开发者机器的默认配置。

---

## 17. Windows 上装 Android SDK 的两个坑（2026-10-05）

**① `sdkmanager --licenses` 的交互喂不进去**：
`$yes | sdkmanager.bat --licenses` 在 PowerShell 里**无效** —— `.bat` 包装层把 stdin 吃掉了，
它照样打印 `Accept? (y/N)` 然后跳过全部 7 个 license，后续安装包全部报
`Skipping following packages as the license is not accepted`。

正确做法是用 `cmd` 的重定向：

```powershell
1..50 | ForEach-Object { 'y' } | Out-File -Encoding ascii yes.txt
cmd /c '"D:\Android\Sdk\cmdline-tools\latest\bin\sdkmanager.bat" --sdk_root="D:\Android\Sdk" --licenses < yes.txt'
```

（也试过手写 `licenses/android-sdk-license` 文件，**不可靠**：哈希要精确到字节、
一行一个 32 位十六进制，多一个换行或少一个字符就会被判为"未接受"，且不报具体原因。）

**② 系统默认 JDK 是 21，AGP 8.13 要 17**：
Gradle 默认用 `JAVA_HOME`。不改的话要么报版本错，要么用错版本编。
最省事的接线是用户级 `%USERPROFILE%\.gradle\gradle.properties`：

```properties
org.gradle.java.home=C:/Program Files/Microsoft/jdk-17.0.20.101-hotspot
```

**顺带**：Windows 上跑不了仓库外那三个 `run-*.sh`（纯 POSIX sh），直接用 Gradle 等价命令即可 ——
`compileDebugKotlin` + `compileDebugJavaWithJavac` + `processDebugResources` + `processDebugMainManifest`
（= `run-compile.sh` 覆盖的四项）、`testFordebugUnitTest`（= `run-tests.sh` 那 34 项）。
Windows 上**不需要 qemu 包装**（那是 arm64 才有的坑，见 §1.3）。

---

## 18. 同一份内容维护两遍，迟早会分叉（2026-10-05）

仓库里 `CHANGELOG.md`（根）与 `docs/CHANGELOG.md` **逐字节相同**，是两个独立文件、各自提交。
这种"双份真相"最后一定会出现"改了这份忘了那份"，而 CHANGELOG 恰恰是项目的硬规则（每改必记）。

**处理**：按项目已有的约定（"文档主副本在 `docs/`"）**只保留 `docs/` 那份**，根目录的直接删。
判断依据不是"哪个更顺手"，而是**项目自己写明的主副本在哪**。

**推广**：发现两份同名同内容文件时，先查历史确认**哪一份是被引用/被约定的**
（例如 FCL 的 release workflow 读的是根目录那份 —— 但我们没有那个 workflow 了），
再删掉另一份，而不是两份都留着"图省事"。




## 19. 两个 git 仓库共用一个工作目录（2026-10-05）

最终布局：电脑 `D:\Projects\dsh-fcl-android-launcher` 一个文件夹里，源码仓用自己的 `.git`，
快照仓的 git 目录改名为 `.git-ws-snapshot/` 并设 `core.worktree` 指向同一目录 ——
省掉了"两棵工作树互相拷贝同步"的全部麻烦（文档天然一份，不再有"同步 docs 副本"这一步）。

**踩过的点（都靠实测确认）**：

1. **`info/exclude` 只挡"未跟踪"，不挡"已跟踪"**：快照仓原先跟踪根 `README.md`，
   现在根 `README.md` 属于源码仓 —— 光在 exclude 里写 `/README.md` 没用，
   必须先 `git rm --cached README.md` 让快照仓松手，否则 `git add -A` 会把源码仓的 README 提交进快照仓。
2. **`.gitattributes` 是"工作区级"的**：即使快照仓不跟踪 `.gitattributes`，
   它读的仍是同一份工作区文件 —— 所以 `*.sh text eol=lf` 一条规则两个仓库同时受益
   （PC 上编辑过的脚本被 `git add` 时自动按 LF 入库，手机拉下去不会变成 `\r` 脚本）。
3. **别忘 `.git-ws-snapshot/` 要在源码仓侧忽略**（它不叫 `.git`，git 不会自动忽略），
   否则源码仓 `git status` 里永远挂一个 untracked。
4. **GitHub 的 README 展示优先级**：`.github/README.md` > 根 `README.md` > `docs/README.md`。
   两个仓库共用一个根目录时，让快照仓把 README 放 `.github/`，源码仓用自己的根 `README.md`，互不打架。
5. 日常操作包装成 `ws-git.sh` / `ws-git.cmd`（`--git-dir=.git-ws-snapshot --work-tree=.`），
   普通 clone（手机）上没有 `.git-ws-snapshot` 时退化为普通 `git`。
