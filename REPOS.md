# 电脑 / 手机 / GitHub —— 仓库关系与日常工作流（先读这个）

> 这份文档回答三个问题：
> **① 我在哪台设备上、改什么文件、该动哪个仓库？**
> **② 出一次版本要走哪几步？**
> **③ 换一台电脑，怎么把这套环境重建出来？**
>
> - 源码仓（Android 工程，完整历史）：[`sqkl520/dsh-fcl-android-launcher`](https://github.com/sqkl520/dsh-fcl-android-launcher) —— **公开**
> - 快照仓（源码以外的工作区文件）：[`sqkl520/dsh-fcl-android-launcher-workspace-snapshot`](https://github.com/sqkl520/dsh-fcl-android-launcher-workspace-snapshot) —— 私有
>   （旧名 `ea`，2026-10-05 已改名）
> - 工具链怎么装：`docs/ENVIRONMENT.md` · rootfs 怎么补：`docs/ROOTFS.md`

---

## 0. 一句话

- **源码**（Android 工程：`FCL/`、`ZipFileSystem/`、Gradle 配置…）→ 只在源码仓。
- **源码以外**（`docs/`、`apk/`、参考材料、根脚本…）→ 只在快照仓。
- 电脑上 **`D:\Projects` 只有一个项目文件夹**：`D:\Projects\dsh-fcl-android-launcher` ——
  两个仓库**共用这一个工作目录**（双 git 目录，见下）。
- **出包 → GitHub Release 附件**（不进 Git 历史，见 §5）。

---

## 1. 电脑：一个文件夹，两个仓库

```text
D:\Projects\dsh-fcl-android-launcher\      ← 项目文件夹（= 源码仓工作区 = 快照仓工作区）
├── .git\                          ← 源码仓的 git 目录（根目录里裸跑 git 就是它）
├── .git-ws-snapshot\              ← 快照仓的 git 目录（core.worktree 指向上层目录）
│
├── ── 源码仓跟踪 ─────────────────────────────
├── FCL/                           ← App 主模块（dsh 相关代码在 FCL/src/main/java/com/dsh/）
├── ZipFileSystem/  config/  gradle/  gradlew  gradlew.bat
├── build.gradle.kts  settings.gradle.kts  gradle.properties   ← ★ 版本号在这里
├── apk-archive/                   ← 版本记录（只有 SHA256SUMS，APK 本体在 Release 里）
├── debug-key.jks  key-store.jks   ← 签名（入库）
├── README.md  LICENSE  .gitattributes
│
├── ── 快照仓跟踪（源码以外，按类别直接铺在根下）──
├── docs/                          ← 文档（★与源码仓共用同一份，两边都要提交）
├── apk/                           ← 历史 APK 分片存档（0.1.0 / 0.1.1 / legacy；只读）
├── dsh/  poc/  dsh-launcher-poc/  oonid-pr-reference/     ← 参考材料 / PoC
├── rootfs-build-info/  _review_evidence/
├── build-apk.sh  run-compile.sh  run-tests.sh             ← 编译/打包/单测（手机上是主力）
├── release.sh  bootstrap.sh  check-sync.sh  ws-git.sh     ← 工作流脚本（见 §3、§4）
├── release.cmd bootstrap.cmd check-sync.cmd ws-git.cmd    ← 上面几个的电脑入口
├── ws-tools/                      ← 两边 info/exclude 的规范副本（bootstrap 用）
└── .github/README.md              ← 本快照仓在 GitHub 上展示的 README
│
├── ── 两个仓库都跟踪（共用文件，改一次、两个仓库各提交一次）──
├── REPOS.md（本文件）  .gitignore
```

**为什么两个仓库能用同一个目录？** 源码仓用根下的 `.git`；
快照仓的 git 目录放在 `.git-ws-snapshot/`，并已设
`core.worktree = D:/Projects/dsh-fcl-android-launcher`。
所以对快照仓要带 `--git-dir` 操作（用包装脚本少打字）：

```powershell
git --git-dir=.git-ws-snapshot --work-tree=. status      # ws-git.cmd 等价于这条
.\ws-git.cmd add -A; .\ws-git.cmd commit -m "..."; .\ws-git.cmd push origin main
```

为避免两个仓库互相"看见"对方的文件，各自在 `info/exclude` 里排除了对方的路径
（两边的 `git status` 都应该是干净的）。**谁的文件被谁跟踪，看上面的树形图。**

> `info/exclude` 在 `.git` 目录**里面**，不受版本控制 —— 新机器 clone 下来是空的。
> 所以仓库里留了一份规范副本 `ws-tools/exclude.*`，由 `bootstrap.sh --install-excludes` 装上。

---

## 2. 手机上长什么样

| 东西 | 位置 |
|---|---|
| 快照仓 clone（+ 手机侧工作区） | `/workspace` |
| 源码 checkout（独立仓库，单独 `git pull`） | `/workspace/dsh-fcl-android-launcher` |
| APK 交付目录 | `/workspace/output/`（设备端可直接取） |

手机工具链：JDK 17 + `/opt/android-sdk` + qemu 包装（见 `docs/ENVIRONMENT.md` §2/§3）。
三个根脚本能自动识别两种布局（电脑：源码就在脚本同目录；手机：源码在 `./dsh-fcl-android-launcher`），
也可用 `SRC=<路径>` 显式指定。`ws-git.sh` 在没有 `.git-ws-snapshot` 的普通 clone 上退化为普通 `git`。

> **手机现在的位置**：主力开发环境已经搬到电脑（Windows x64，原生工具链、没有 qemu 那些坑）。
> 手机保留两个用途：**设备端取件**（真机装 APK 验证）与**应急打包**。

---

## 3. 改文件时的规则

| 你改了什么 | 提交到哪个仓库 |
|---|---|
| `FCL/` 源码、资源、Gradle 配置、`apk-archive/` | 源码仓 |
| `docs/`、`REPOS.md`、`.gitignore`（共用文件） | **两个仓库各提交一次**（同一份工作区，内容天然一致） |
| 根脚本 / 参考材料 / `apk/` / `ws-tools/` / `bootstrap.*` | 快照仓 |

**每次改完"源码以外"的文件 → 提交并推快照仓** —— 手机看不到电脑的本地改动，快照仓是手机的唯一来源。
源码仓的推送按需。

改完跑一条命令体检（版本号一致 / 两仓干净 / 共用文件一致 / 与远端同步）：

```powershell
.\check-sync.cmd          # 电脑（PowerShell 或 cmd）
```
```sh
sh check-sync.sh          # 手机
```

---

## 4. 三个工作流脚本

| 脚本 | 干什么 | 什么时候用 |
|---|---|---|
| `check-sync.sh` / `.cmd` | 一致性体检（4 项，见上） | 每次改完东西 / 出包前 |
| `release.sh` / `.cmd` | 出一个版本的**全流程** | 要出包发版时（见 §5） |
| `bootstrap.sh` / `.cmd` | 在新电脑上重建这套双仓布局 | 换电脑 / 工作区重建（见 §6） |

`.cmd` 只是电脑入口（PowerShell/cmd 里不能直接跑 `.sh`），内容一律 **ASCII + CRLF** ——
`cmd.exe` 会把含中文的 `.cmd` 拆坏（实测）。

---

## 5. 版本与分发

### 版本号只有一个真源

**`gradle.properties` 里的 `dshVersion` / `dshVersionCode`** —— 改版本号只改这两行。

- `FCL/build.gradle.kts` 从它读（缺了直接报错，不会打出空版本号）
- 快照仓的 `build-apk.sh` 也从它读（早先写死在脚本里）
- 文档里**声明当前版本**的地方还有 3 处（`README.md` 徽章、`docs/INDEX.md`、`docs/CHANGELOG.md`），
  `check-sync.sh` 会校验它们与 `gradle.properties` 一致 —— 不一致就 FAIL

### 出一次版本

```powershell
.\release.cmd                    # 体检 → 编译 → 单测 → 打包 → 提交两仓 → 发 GitHub Release
.\release.cmd --no-test          # 跳过单测
.\release.cmd --no-build         # APK 已打好，只提交+发版
.\release.cmd --no-release       # 到提交为止，不碰 GitHub
.\release.cmd --dry-run          # 只打印流程，什么都不改
```

前置：电脑上 `gh auth login` 过一次（GitHub CLI；本机 2026-10-06 装好并已登录）。

### APK 走 GitHub Release 附件，不进 Git

**一句话：APK 只有一个渠道 —— GitHub Release 附件。**
不经过快照仓（`apk/` 不再新增），也不经过别的地方。源码仓公开 → **手机端不用登录、不用 token**，
点开页面直接下载安装。

**为什么**：APK 是 300MB+ 的二进制。早先的做法是切成 90MB 分片提交进快照仓 ——
但 Git 存的是"每个文件的每个版本"，**改一行代码就是一个全新的 300MB 对象**，
旧的那份还永远留着。实测快照仓历史里已经压了 **833 MiB** 的 APK 分片（8 个 90MiB + 2 个 41MiB），
每出一版再涨 300MB+，手机端 clone/pull 只会越来越痛。

现在的分工：

| 位置 | 用途 | 入 Git |
|---|---|---|
| **GitHub Release 附件** | **唯一的分发渠道**（手机从这里下） | — |
| `<项目文件夹>/output/` | 本机交付/取件目录，只放当前版本 | 否 |
| `apk-archive/<version>/SHA256SUMS` | 只有这一行指纹，记录"这个版本出过、指纹是多少" | **是** |
| `apk/<version>/` | **历史存档**（0.1.0 / 0.1.1 / legacy 的分片），**只读、不再新增** | 是（历史遗留） |

**下载页**：<https://github.com/sqkl520/dsh-fcl-android-launcher/releases>

| Release | sha256 |
|---|---|
| `v0.1.0` | `e61a5939…` |
| `v0.1.1-SNAPSHOT` | `bc96c4e7…` |
| `v0.1.2-SNAPSHOT` | `8abaa3ea…` |

校验：`sha256sum` 与 `apk-archive/<version>/SHA256SUMS` 比对（Release 说明里也写了）。
三个包都是 `--prerelease`（项目自己写着"待能正常启动/下载/管理 dsh 后才标 1.0.0"）。

> **历史包袱（已决策，不急着还）**：快照仓里那 833 MiB 分片仍在历史中
> （`apk/` 目录只是不再新增，历史里的旧对象还在）。清理要改写历史 + 强推，
> 等哪天手机端 clone 真的嫌慢再动手。

---

## 6. 换电脑：重建这套布局

```powershell
# 1) 重建双仓共用的工作树（含两边互斥的 info/exclude）
.\bootstrap.cmd D:\Projects\dsh-fcl-android-launcher
#    已有工作树、只是想重装 exclude：.\bootstrap.cmd --install-excludes
#    只想检查布局完不完整：        .\bootstrap.cmd --check
```

然后手工补两样（都不进 Git，必须自己放）：

| 缺什么 | 怎么补 |
|---|---|
| `local.properties`（`sdk.dir`） | 见 `docs/ENVIRONMENT.md` §1.2 |
| rootfs（300MB，打包必需） | 见下 |

### 补 rootfs（300MB，打包必需）

**优先从已发布的 APK 里无损取出**（APK 内是 STORED，取出来与原文件逐字节相同）：

```sh
# 从 GitHub Release 下最新 APK，然后：
unzip -p dsh-fcl-android-launcher-<版本>-arm64.apk assets/dsh/rootfs/rootfs.tar.xz \
  > FCL/src/main/assets/dsh/rootfs/rootfs.tar.xz
sha256sum FCL/src/main/assets/dsh/rootfs/rootfs.tar.xz
# 当前应为 5d762c30b9117830518571bc4eeadf593182cc56d1f472024ba4490aa90682a7
```

> 更早的记录里写的是从 `apk/output-0.1.1-ref.apk` 取 —— 那个文件已删
> （它与 `apk/dsh-fcl-android-launcher-0.1.1-SNAPSHOT-arm64/` 的分片**是同一份内容**，
> 白占 326MB）。从 Release 里取一样，且永远指向最新版。
>
> 从零重建 rootfs 见 `docs/ROOTFS.md`；打包与产物落点见 `docs/PACKAGING.md`。

---

## 常用命令（速查）

```powershell
# 电脑：源码仓
cd D:\Projects\dsh-fcl-android-launcher
git add -A; git commit -m "..."; git push origin main

# 电脑：编译 / 单测 / 打包（直接用 Gradle，最快）
.\gradlew.bat --no-daemon -Darch=arm64 :FCL:compileDebugKotlin :FCL:compileDebugJavaWithJavac `
  :FCL:processDebugResources :FCL:processDebugMainManifest
.\gradlew.bat --no-daemon :FCL:testFordebugUnitTest
.\gradlew.bat --no-daemon -Darch=arm64 :FCL:assembleFordebug

# 电脑：快照仓（共享工作区，用包装脚本）
.\ws-git.cmd add -A; .\ws-git.cmd commit -m "..."; .\ws-git.cmd push origin main

# 电脑：体检 / 出包 / 重建布局
.\check-sync.cmd ; .\release.cmd --dry-run ; .\bootstrap.cmd --check
```

```sh
# 手机：拉最新快照仓 + 源码
cd /workspace && git pull
cd /workspace/dsh-fcl-android-launcher && git pull

# 手机：编译 / 单测 / 打包 / 体检（根脚本自动找源码目录）
cd /workspace && sh run-compile.sh && sh run-tests.sh
sh build-apk.sh && sh check-sync.sh
```

---

## 变更记录

- **2026-10-06**：版本号收敛到 `gradle.properties` 单一来源；APK 分发改走 **GitHub Release 附件**
  （不再往快照仓提交 90MB 分片）；新增 `release` / `bootstrap` / `check-sync` 三个脚本与
  `ws-tools/` 规范 exclude；磁盘清理约 1.3GB（删 0.1.1 参照包、apk-archive 里的二进制副本、`FCL/build`）。
- **2026-10-05（定版）**：电脑单文件夹 `D:\Projects\dsh-fcl-android-launcher`；
  快照仓内容按类别直接铺在其根下（不再包一层 `workspace/`）；
  快照仓 git 目录改为 `.git-ws-snapshot`（`core.worktree` 共用同一目录）；
  快照仓 GitHub 改名 `ea` → `dsh-fcl-android-launcher-workspace-snapshot`。
- **2026-10-05（早些，已废弃）**：`workspace/` 嵌套布局、`android-app` 嵌套布局（均被本次取代）。
