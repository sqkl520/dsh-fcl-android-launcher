# 设计：W^X 执行限制与 PROOT_LOADER 绕过方案（targetSdk 决策的最终答案）

> **这是整个项目"真机能不能跑起来"的决定性问题的结论文档。**
> 结论先行：**`targetSdk` 不必降到 28**。用 **PROOT_LOADER 机制**（proot 的 loader 放进
> `nativeLibraryDir`）即可在 **targetSdk 29/35/36** 上绕过 W^X，已有同类项目
> （[`oonid/pr`](https://github.com/oonid/pr)）在真机（三星 Android 16 / SDK 36）实测通过。
>
> **但有一个未决红灯**：子进程 execve 在 proot 下可能受限（`cargo build` 实测失败），
> 而 dsh 的核心就是不停开子进程——**这条仍需真机证实**（见 §5 与 §7 第 2 条；
> 现在由 `TASKS.md` T6 真机验收直接回答，已知 `gcc` 的普通 fork+execve 是通的）。

---

## 1. 问题本质（W^X 到底禁什么）

安卓从 Android 10 起，对 `targetSdk ≥ 29` 的 App 在 SELinux `untrusted_app` 域启用
**W^X（Write XOR Execute）**：

| 路径 | SELinux 标签 | 能否 execve |
|---|---|---|
| `/data/app/~~<rand>/<pkg>-<rand>/lib/arm64/`（= `nativeLibraryDir`） | `apk_data_file:s0` | ✅ **允许** |
| `/data/data/<pkg>/files/rootfs/`（= 我们的 rootfs，`filesDir` 下） | `app_data_file:s0` | ❌ **拒绝** |

**只禁 `execve`，不禁 `dlopen`。** 这是关键区分：
- FCL 在 targetSdk 34 下从数据目录 `dlopen` 一堆 `.so` 跑 JVM —— **允许**（所以 FCL 能活）。
- 我们 proot 要 `execve` rootfs 里的 `/bin/sh`、`node` —— **被拒**（所以直接跑不通）。

### 几个"看起来能绕、其实是坑"的死路（oonid/pr 真机踩过）
- **`run-as` 测试会误导**：`run-as` 跑在 `runas_app` 域，**不设 W^X**，execve 数据目录文件会"假通过"。
  **必须从 App UI 进程实测**，不能信 `adb shell run-as` 的结果。
- **`memfd_create` 绕不过**：三星内核把 memfd 标为不可执行；Android 14+ 默认强制 `MFD_NOEXEC_SEAL`。死路。
- **`termux-exec` 的 linker64 法对我们无效**：那是 bionic-only（Termux 的程序是 bionic 原生），
  bionic 的 `/system/bin/linker64` **加载不了 glibc 程序**（我们 rootfs 是 glibc/musl）。已排除。

---

## 2. 解法：PROOT_LOADER 机制

proot 执行 rootfs 内程序时，**不是直接 `execve(目标)`，而是先执行 proot 自己的 loader，
由 loader 去把目标程序 load 起来**（proot 用这个 loader 做地址空间布置）。

proot 源码 `src/execve/enter.c:584`：
```c
loader_path = loader_path ?: getenv("PROOT_LOADER") ?: extract_loader(tracee, false);
```
默认行为是把内置 loader **解压到临时目录**（在 `filesDir` 下 → 被 W^X 拒）。
但只要设了环境变量 `PROOT_LOADER`，就用它指定的二进制当执行代理。

**三步绕过**：
1. 把 proot 的 loader 单独编译成独立二进制（`src/proot/src/loader/loader`，仅 ~5.6KB）
2. 命名为 `libproot-loader.so`，放进 `jniLibs/arm64-v8a/`
   → 安装时系统自动解压到 `nativeLibraryDir`（`apk_data_file`，**execve 允许**）
3. 启动 proot 前设 `PROOT_LOADER=<nativeLibDir>/libproot-loader.so`

于是 execve 的是 loader（合法），rootfs 里的 node/sh 由 loader 加载 —— **W^X 绕过**。

> **与我们现有代码的契合**：`DshPaths.resolveProotLoader()` + `ProotCommand` 本来就预留了
> `PROOT_LOADER` + jniLibs 的位子（前几轮搭对了骨架），只是缺真正的 loader 二进制。

---

## 3. seccomp（zygote BPF 过滤器）

targetSdk 之外，zygote 的 seccomp BPF 还会拦 18+ 个 syscall（按**系统镜像版本**而非 targetSdk 变化）。
oonid/pr 的静态分析结论：
- **proot 自己的 seccomp 过滤器是 dead code**（`enable_syscall_filtering()` 定义了但从不调用）；
  `PROOT_NO_SECCOMP=1` 设了也**无意义**。
  → **这推翻了我们 R-02 兜底逻辑的前提**（我们在 round6 里设想"启动失败自动加 PROOT_NO_SECCOMP 重试"，其实没用）。
- 被拦的 syscall（arm64）：setuid/setgid 族、mount/umount2/chroot、clock_settime 族、
  reboot/init_module/sethostname 等。
- **proot 的 SIGSYS handler 对未知拦截 syscall 默认返回 `-ENOSYS`**，优雅降级；
  只有少数需专门处理（`fchmodat`/`chdir`/`fchdir`/`getcwd`/`linkat`）——oonid/pr 已实现。
  其中 `fchmodat`（arm64 syscall 53）在 targetSdk 29+ 被拦，需加 noop handler 返回 0。

---

## 4. oonid/pr 真机实测结果（Samsung Galaxy / Android 16 / SDK 36）

| targetSdk | 结果 |
|---|---|
| 29（基线） | 终端打开 ✅、`apk --version` ✅、无 SIGSYS |
| 35（Play 最低线） | ✅ 与 29 无差异、无 SELinux denial |
| 36（设备 OS） | ✅ `vim --version` 也 OK |

**全回归（targetSdk 35）**：
| 测试 | 结果 |
|---|---|
| `apk update` | ✅ |
| `apk add openssh` + `ssh -V` | ✅ |
| `apk add gcc` + `gcc --version` | ✅ |
| **gcc 编译 + 运行 hello world** | ✅ |
| **`cargo build`** | ❌ **pre-existing proot limitation** |

---

## 5. ⚠️ 未决红灯：子进程 execve（对 dsh 是生死问题）

`cargo build` 失败的描述：
> `cargo build` fails with ENOSYS when `rustc` tries to **execute as a subprocess**.
> `cargo -V` 和 `rustc -V`（只打印版本）都 OK。gcc 编译正常。被标记为既有限制 T5.7。

**为什么这对我们是红灯**：dsh 的本质就是**不停开子进程**——
- `npm install` 要 spawn 子进程
- dsh 的终端/shell 工具写死 `DEFAULT_BASH_SHELL = '/bin/bash'`、装依赖用 `sh -c`
- agent 执行用户命令 = spawn

如果"子进程 execve"像 `cargo` 一样挂，**dsh 核心功能瘫痪**，集成了也白搭。

**但有反证说明不是"全挂"**：
- `gcc` 能编译 hello world —— gcc 要 spawn `cc1`/`as`/`ld` 子进程，**这条通了**。
- 说明失败的是**某种特定的 exec 方式**（可能是 `posix_spawn` + `CLONE_VM|CLONE_VFORK`，
  Rust/cargo 用的那种 vfork 路径），而不是普通 `fork+execve`。
- oonid/pr 文档也提到做了 **`CLONE_VM`/`CLONE_VFORK` stripping** 让 cargo 能工作——
  说明他们**知道这个问题并在处理**，`cargo build` 失败可能是"还没完全修好"，而非"原理上不可能"。

**→ 集成前必须查清的头号问题**：node 的子进程 spawn 走的是哪条路径（普通 fork+execve
还是 vfork/posix_spawn），会不会命中 `cargo` 那个坑。判断方法见 §7。

---

## 6. 方案裁决总表

| 方案 | 真实性 | 对本项目（glibc rootfs + dsh）结论 |
|---|---|---|
| **PROOT_LOADER（oonid/pr）** | ✅ 真机实测 targetSdk 35/36 过 | ⭐ **首选**：W^X/seccomp 已解决；待验子进程 spawn |
| targetSdk = 28 | ✅ 机制可靠 | 备选兜底：一行改动、侧载自用能跑；但 W^X 这堵墙其实不用降级也能过 |
| proroot（LD_PRELOAD 无 ptrace） | ✅ 存在，专为 glibc | 备选：作者重心已转 proroom，维护存疑 |
| Bunproot | ✅ 存在 | 不匹配：依赖 bionic Bun |
| termux-exec linker64 法 | ✅ 存在 | ❌ bionic-only，加载不了 glibc |
| memfd_create / seccomp 白名单注入 | — | ❌ 死路 / 无实证 |

---

## 7. 下一步（集成前的尽调清单）

> **状态（2026-10-06）**：这份清单写于"打算集成 `:proot-engine`"的时候，**那条路最终没走**
> （见 `design/proot-engine-integration.md` 顶部横幅）。项目改回自研 proot 层，
> 只借用了 oonid/pr 的**二进制与 PTY 桥接**。所以下面第 1/3/4/5 条的"集成尽调"已不需要；
> **第 2 条（子进程 spawn）仍然是最关键的真机待验项** —— 只是现在由 T6 真机验收直接回答。

1. ~~**License**：…~~（已解决：本项目 GPL-3.0，与 proot 的 GPL-2.0-or-later 相容）
2. **🔴 子进程 spawn 验证（最高优先级，仍未在真机证实）**：node 的 `child_process.spawn`
   （libuv → `posix_spawn`/`fork+execvp`）会不会命中 `cargo` 那条 ENOSYS 坑。
   **现在通过 T6 真机验收来回答**：`npm install` 能否跑通、agent 能否 spawn 命令。
3. ~~**`:proot-engine` API**：…~~（未采用该模块）
4. ~~**rootfs 准备**：…~~（仍用自研 rootfs.tar.xz，见 `ROOTFS.md`）
5. ~~**维护状态**：…~~（只取二进制，不依赖其代码演进）

---

## 8. 记录在案的认知修正（避免再走弯路）

| 我之前的判断 | 修正 |
|---|---|
| "targetSdk 34 下 proot+glibc 无干净绕法，只能降 28" | ❌ 错。PROOT_LOADER 机制可绕，targetSdk 35/36 真机验证过 |
| "数据目录的 .node 用 dlopen 也会被 W^X 拦" | ❌ 错。W^X 只禁 execve，dlopen 允许（FCL 即反例） |
| "PROOT_NO_SECCOMP 兜底重试有用"（round6 R-02 设想） | ❌ 错。proot 自带 seccomp 过滤是 dead code，该变量无意义 |
| "termux-exec linker 法也许能用" | ✅ 判断正确：bionic-only，对 glibc 无效 |

> 方法论教训：**涉及真机运行时行为的结论，不要凭机制推理下死判断**；
> 本轮两次关键误判（W^X 对 dlopen、targetSdk 必须降 28）都是推理过度、缺真机数据所致。
> 幸好有 oonid/pr 的真机文档纠偏。
