# 电脑 / 手机 / GitHub —— 两个仓库的关系（先读这个）

> 这份文档回答一个问题：**我在哪台设备上、改什么文件、该动哪个仓库？**
>
> - 源码仓（Android 工程，完整历史）：[`sqkl520/dsh-fcl-android-launcher`](https://github.com/sqkl520/dsh-fcl-android-launcher)
> - 快照仓（源码以外的工作区文件）：[`sqkl520/dsh-fcl-android-launcher-workspace-snapshot`](https://github.com/sqkl520/dsh-fcl-android-launcher-workspace-snapshot)
>   （旧名 `ea`，2026-10-05 已改名）

---

## 一句话

- **源码**（Android 工程：`FCL/`、`ZipFileSystem/`、Gradle 配置…）→ 只在源码仓。
- **源码以外**（`docs/`、`apk/`、参考材料、根脚本…）→ 只在快照仓。
- 电脑上 **`D:\Projects` 只有一个项目文件夹**：`D:\Projects\dsh-fcl-android-launcher` ——
  两个仓库**共用这一个工作目录**（双 git 目录，见下）。

---

## 电脑：一个文件夹，两个仓库

```text
D:\Projects\dsh-fcl-android-launcher\      ← 项目文件夹（= 源码仓工作区 = 快照仓工作区）
├── .git\                          ← 源码仓的 git 目录（根目录里裸跑 git 就是它）
├── .git-ws-snapshot\              ← 快照仓的 git 目录（core.worktree 指向上层目录）
│
├── ── 源码仓跟踪 ─────────────────────────────
├── FCL/                           ← App 主模块（dsh 相关代码在 FCL/src/main/java/com/dsh/）
├── ZipFileSystem/  config/  gradle/  gradlew  gradlew.bat
├── build.gradle.kts  settings.gradle.kts  gradle.properties
├── apk-archive/                   ← 版本快照（SHA256SUMS 入库，APK 本体不入库）
├── debug-key.jks  key-store.jks   ← 签名（入库）
├── README.md  LICENSE  .gitattributes
│
├── ── 快照仓跟踪（源码以外，按类别直接铺在根下）──
├── docs/                          ← 文档（★与源码仓共用同一份，两边都要提交）
├── apk/                           ← APK 分片存档（sh apk/reassemble.sh 拼回；output-0.1.1-ref.apk 是参照包）
├── dsh/  poc/  dsh-launcher-poc/  oonid-pr-reference/     ← 参考材料 / PoC
├── rootfs-build-info/  _review_evidence/
├── build-apk.sh  run-compile.sh  run-tests.sh             ← 根脚本（手机直接 sh 跑）
├── ws-git.sh  ws-git.cmd                                  ← 快照仓 git 包装（见下）
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
# 电脑：快照仓的 git 操作（ws-git.cmd 等价于下一条）
git --git-dir=.git-ws-snapshot --work-tree=. status
.\ws-git.cmd add -A; .\ws-git.cmd commit -m "..."; .\ws-git.cmd push origin main
```

为避免两个仓库互相"看见"对方的文件，各自在 `info/exclude` 里排除了对方的路径
（两边的 `git status` 都应该是干净的）。**谁的文件被谁跟踪，看上面的树形图。**

---

## 手机上长什么样

| 东西 | 位置 |
|---|---|
| 快照仓 clone（+ 手机侧工作区） | `/workspace` |
| 源码 checkout（独立仓库，单独 `git pull`） | `/workspace/dsh-fcl-android-launcher` |
| APK 交付目录 | `/workspace/output/`（设备端可直接取） |

手机工具链：JDK 17 + `/opt/android-sdk` + qemu 包装（见 `docs/ENVIRONMENT.md` §3）。
三个根脚本能自动识别两种布局（电脑：源码就在脚本同目录；手机：源码在 `./dsh-fcl-android-launcher`），
也可用 `SRC=<路径>` 显式指定。`ws-git.sh` 在没有 `.git-ws-snapshot` 的普通 clone 上退化为普通 `git`。

---

## 改文件时的规则（重要）

| 你改了什么 | 提交到哪个仓库 |
|---|---|
| `FCL/` 源码、资源、Gradle 配置、`apk-archive/` | 源码仓 |
| `docs/`、`REPOS.md`、`.gitignore`（共用文件） | **两个仓库各提交一次**（同一份工作区，内容天然一致） |
| 根脚本 / 参考材料 / `apk/` 分片 / `rootfs-build-info/` / `ws-git.*` | 快照仓 |

**每次改完"源码以外"的文件 → 提交并推快照仓** —— 手机看不到电脑的本地改动，快照仓是手机的唯一来源。
源码仓的推送按需。

---

## 常用命令

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
```

```sh
# 手机：拉最新快照仓 + 源码
cd /workspace && git pull
cd /workspace/dsh-fcl-android-launcher && git pull

# 手机：编译 / 单测 / 打包（根脚本自动找源码目录）
cd /workspace && sh run-compile.sh && sh run-tests.sh
```

---

## 大文件怎么办（rootfs / APK 整包都不在仓库里）

| 缺什么 | 怎么补 |
|---|---|
| `FCL/src/main/assets/dsh/rootfs/rootfs.tar.xz`（300MB，打包必需） | **优先从已打好的 APK 里无损取出**（APK 内是 STORED）：<br>`unzip -p dsh-fcl-android-launcher-0.1.1-SNAPSHOT-arm64.apk assets/dsh/rootfs/rootfs.tar.xz > rootfs.tar.xz`<br>校验 `sha256 = 5d762c30b9117830518571bc4eeadf593182cc56d1f472024ba4490aa90682a7`<br>快照仓 `apk/` 下有分片（`sh apk/reassemble.sh` 拼回）；另见 `docs/ROOTFS.md`（从零重建）与 `docs/PACKAGING.md` |
| 构建产物 `FCL/build/`、`.gradle/` | 跑一次编译就会重新生成 |
| `poc/node_modules/`、`dsh/` 的依赖 | `npm install` |

---

## 变更记录

- **2026-10-05（本次）**：定为**最终布局** —— 电脑单文件夹 `D:\Projects\dsh-fcl-android-launcher`；
  快照仓内容按类别直接铺在其根下（不再包一层 `workspace/`）；
  快照仓 git 目录改为 `.git-ws-snapshot`（`core.worktree` 共用同一目录）；
  快照仓 GitHub 改名 `ea` → `dsh-fcl-android-launcher-workspace-snapshot`；
  根脚本 SRC 自动识别两种布局。
- **2026-10-05（早些，已废弃）**：`workspace/` 嵌套布局、`android-app` 嵌套布局（均被本次取代）。
