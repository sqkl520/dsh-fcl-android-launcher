# 设计：集成 oonid/pr 的 `:proot-engine`（替换自研 proot 层）

> **状态**：架构决策文档，**尚未改代码**（遵循"先定方案"约定）。
> **决策**：采用 [`oonid/pr`](https://github.com/oonid/pr) 的 `:proot-engine`（Android Library，MIT）+
> `pr-cli`（Rust，MIT）+ patched proot（GPL-2.0）作为运行时底座，**替换**我们自研的
> `ProotCommand` / `ProotProcessExecutor` / `DshBootstrap`(解压部分) / 以及"要求用户补
> libproot.so + rootfs.tar.xz"的整套机制。`targetSdk` **保持 34，不降级**。
>
> 前置阅读：`wx-exec-proot-loader.md`（为什么这条路能绕过 W^X / seccomp）。

---

## 1. 为什么换（一句话）

我们自研的 proot 层有两个没解决的硬问题：
1. **W^X**：rootfs 里的 `/bin/sh`、`node` 在 targetSdk 34 下 execve 被 SELinux 拒 —— 自研版没有 loader 机制。
2. **rootfs 从哪来**：我们要求用户"自己打包 rootfs.tar.xz 内置 Node"，至今是 PLACEHOLDER，从没真机跑通。

`:proot-engine` 两个都解决了，而且**真机实测 targetSdk 35/36 通过**（见 `wx-exec-proot-loader.md` §4）。
与其自己趟雷，不如直接集成一个已验证的引擎。

---

## 2. oonid/pr 的三层架构（我们要用哪些）

```
┌─ android/:app          Jetpack Compose 终端 UI        ← 不要（我们有自己的 FCL 外壳）
├─ android/:proot-engine Kotlin Library（MIT）          ← ★要：ProotHost / ProotLauncher / PtyNative
│    └─ src/main/cpp/    ptyjni（JNI PTY 实现）          ← ★要（随 library 编译）
│    └─ jniLibs/arm64-v8a/
│         libproot.so           proot 本体（~650KB, GPL2）      ← ★要
│         libproot-loader.so    proot loader（~5.6KB）          ← ★要（PROOT_LOADER 指向它）
│         libpr-cli.so          Rust CLI（~2.5MB, MIT）         ← ★要
│         libbusybox.so         busybox（~1.1MB）               ← ★要
│         libbash.so            bash（~1.3MB）                  ← 可选（dsh 终端默认 /bin/bash）
├─ src/pr-cli（Rust）    发行版安装/登录/OCI 拉取          ← ★要（编成 libpr-cli.so）
├─ src/proot（C,GPL2）   patched proot                     ← ★要（编成 libproot.so + loader）
└─ src/scripts/plugins/  14 个发行版 plugin（GPL3）         ← 按需（我们可能只要 alpine/ubuntu）
```

**我们要的最小集合**：`:proot-engine`（含 ptyjni）+ 5 个 jniLibs 二进制 + pr-cli 的发行版 plugin。

---

## 3. `:proot-engine` 的 API（已读源码）

### 3.1 `ProotHost`（我们要实现的接口）
```kotlin
interface ProotHost {
    val prefixDir: File     // 安装前缀（rootfs、bin/pr-cli 所在）
    val homeDir: File       // HOME
    val packageName: String
    val cacheDir: File      // PROOT_TMP_DIR / TMPDIR
}
```
→ **用 `DshPaths` 直接实现**：`prefixDir = <filesDir>/dsh`、`homeDir = 实例的 home`、
`cacheDir = <cacheDir>/dsh/tmp`。零障碍。

### 3.2 `ProotLauncher`（起会话）
```kotlin
val launcher = ProotLauncher(host)
launcher.startSession("alpine", user="root", rows, cols): Session?       // 起发行版 PTY
launcher.startCustomSession(listOf("pr-cli","login","alpine","..."), ...)  // 自定义参数(空格安全)
launcher.runCommand("...", rows, cols): Session?
// Session: read(buf)/write(data)/resize(rows,cols)/close()  —— 都是 PTY 读写
```
底层 = `PtyNative.forkPty(cmd, args, env, rows, cols)` → masterFd。

### 3.3 环境变量（`buildEnvVars` 已内置）
已自动设 `PATH`/`HOME`/`PROOT_TMP_DIR`/`TERM`/`LANG`/`TMPDIR` + `PROOT_NO_SECCOMP=1`
（后者是无害的历史遗留，见 wx 文档 §3）。
**注意**：`PROOT_LOADER` 由 pr-cli 内部（`login.rs`）设置，不在这层。

---

## 4. 集成后的新架构（dsh 侧怎么接）

```
DshInstancesActivity / DshDownloadActivity ...（FCL 外壳，UI 不变）
        │
        ├─ 安装 dsh 版本：DshInstaller
        │     旧：proot 里跑 setup-node-dsh.sh（手拼 proot 命令）
        │     新：ProotLauncher.runCommand("pr-cli login <distro> -- sh -c 'npm i @deepseek-ai/dsh@<v>'")
        │          或先 pr-cli install <distro>（首次），再 login 进去装 dsh
        │
        └─ 启动 dsh：DshRuntime
              旧：ProcessBuilder 起 proot，读 stdout 抓 token URL
              新：ProotLauncher.startCustomSession(["pr-cli","login",distro,"--",
                      "node","--expose-internals",".../dsh/lib/bin.js","web","--host","127.0.0.1","--port","<p>"])
                  → Session.read() 读 PTY 输出 → 抓 token=URL → DshRuntimeService 保活
```

### 4.1 组件对应关系（替换表）

| 现有（自研） | 处置 | 替换为 |
|---|---|---|
| `ProotCommand`（手拼 proot argv） | **删** | pr-cli `login.rs` 内部拼（含 PROOT_LOADER / --change-id / bind） |
| `ProotProcessExecutor`（ProcessBuilder 跑 proot） | **删** | `ProotLauncher` + `PtyNative` |
| `DshBootstrap` 的"解压 proot/rootfs"部分 | **大改** | 改为"确保 pr-cli 已 install 发行版"；proot 二进制走 jniLibs 不再解压 |
| `DshBootstrap` 的"assets/dsh/scripts/*.sh" | **可能废弃** | pr-cli 取代 setup/start/probe 脚本的职责（待确认） |
| `DshPaths.resolveProotBin/Loader` | **改** | loader/proot 由 proot-engine 的 jniLibs 提供，路径逻辑交给引擎 |
| `DshRuntime`（进程管理 + token 抓取） | **改** | 进程→PTY Session；token 抓取逻辑见 §4.2 |
| `DshRuntimeService`（前台保活） | **保留** | 不变（仍然前台服务保活） |
| rootfs.tar.xz（用户自备） | **删** | pr-cli install 从 OCI 镜像拉（`docker.io/library/...`）或内置发行版 plugin |
| Node 怎么进 rootfs | **改** | 在选定发行版里 `apk add nodejs` / `apt install nodejs`，或拉已带 node 的镜像 |

### 4.2 ⚠️ 关键接口差异：stdout 管道 → PTY

- **现在**：`DshRuntime` 读进程 **stdout 管道**，正则 `https?://[\d.]+:\d+/\?token=\S+` 抓 URL。
- **换 PTY 后**：`Session.read()` 读的是**伪终端输出**，含控制字符、ANSI 转义、回显。
  - token 抓取正则**可复用**，但要先对 PTY 字节流做**去 ANSI / 去回车**处理再匹配。
  - 好处：PTY 对 dsh 更自然（dsh web 以为自己在真终端里），且 `node-pty` 这类需求天然满足。
  - `DshLogBus` 的"按行处理"要适配 PTY 的流式字节（不保证按行到达）。

---

## 5. 🔴 集成前必须先验证的头号问题（唯一拦路项）

**dsh 的子进程 spawn 能否在 patched proot 下工作？**

- 已知：oonid/pr 真机实测 `gcc 编译 hello world ✅`（gcc spawn cc1/as/ld 的普通 fork+execve 通），
  但 `cargo build ❌`（Rust 的 vfork/特殊 clone 路径 ENOSYS）。
- dsh 的子进程来源：`npm install`（spawn）、终端工具 spawn `/bin/bash`、agent 执行命令（spawn）。
- **判断**：倾向能跑（更像 gcc 那条通路，不是 cargo），但**无直接证据**。
- **验证方法**（二选一，都需要能跑真机或 oonid/pr 的 APK）：
  1. 装 oonid/pr 的 app → `pr-cli install alpine` → `apk add nodejs npm` →
     跑一段 `node -e "require('child_process').execSync('echo hi')"` 与 `npm install` 看是否 ENOSYS；
  2. 直接把 dsh 装进去跑 `dsh web`，看它开 shell 工具/装依赖是否正常。
- **若此验证不通过** → 整个集成作废，退回"自研 proot + targetSdk 28"兜底方案。
  **所以这是集成的第一步，不是最后一步。**

---

## 6. License 合规（已核）

| 组件 | 协议 | 影响 |
|---|---|---|
| `src/proot/`（libproot.so / loader） | **GPL-2.0-or-later** | 分发 APK 需按 GPL 提供对应源码 |
| `src/scripts/plugins/` | **GPL-3.0-or-later** | 同上 |
| `src/pr-cli/`、`android/`（含 `:proot-engine`） | **MIT** | 可自由改用 |

- 我们项目**本就基于 FCL（GPL-3.0）**，已是 GPL 系 → 引入 proot(GPL2) **不构成新障碍**。
- `:proot-engine`（MIT）可随意改。
- 自用/开源无问题；**闭源商用需法务评估**（GPL 传染）。

---

## 7. 落地步骤（真机验证通过后才做；均不在本次）

1. **验证子进程 spawn**（§5）—— 阻塞项，先做。
2. 把 oonid/pr 的 `:proot-engine` 作为 **Gradle 模块/composite build** 引入
   （`settings.gradle.kts` 加 `include(":proot-engine")` 或 Maven 本地发布）。
3. 构建 5 个 jniLibs 二进制（需 NDK r27c + Rust aarch64-linux-android 工具链；
   **注意：这会把 native 构建重新引回项目** —— 与"删 MC 时去掉 NDK"相反，需恢复 NDK 配置）。
4. 实现 `DshProotHost : ProotHost`（用 `DshPaths` 填 4 个目录）。
5. 改 `DshInstaller`：安装流程改走 `pr-cli install <distro>` + 装 Node + `npm i dsh`。
6. 改 `DshRuntime`：进程→PTY Session，token 抓取加 ANSI 清洗。
7. 删 `ProotCommand` / `ProotProcessExecutor`，精简 `DshBootstrap`。
8. 确定发行版：alpine（musl，小）还是 ubuntu（glibc，稳）——与 dsh 原生模块的 libc 匹配
   （dsh 发 glibc+musl 两版，二者皆可，alpine 体积优先）。
9. 真机端到端回归。

---

## 8. 对现有文档/决策的影响

- **`round6-optimization.md` R-02（targetSdk 决策）**：结论更新为"**不降级，用 PROOT_LOADER**"，
  而非之前倾向的 `targetSdk = 28`。R-02 的"方案 B"在本文找到了正确实现。
- **`PACKAGING.md`**：原"用户自备 proot 二进制 + rootfs.tar.xz"的说明将**作废**，
  改为"由 `:proot-engine` 提供 proot + pr-cli 从 OCI/plugin 装发行版"。
- **删 MC 时移除的 NDK 配置需部分恢复**（proot-engine 有 C/Rust native 构建）。
- **`DshBootstrap` / `ProotCommand` / `ProotProcessExecutor`** 面临重构或删除。

---

## 9. 风险与备选

| 风险 | 应对 |
|---|---|
| 子进程 spawn 不通（§5） | 阻塞项先验；不通则退回 targetSdk 28 自研方案 |
| 引回 native 构建（NDK+Rust）增加构建复杂度 | 可接受；oonid/pr 的 build.sh 已封装；沙箱出 APK 的 qemu 经验仍适用 |
| GPL 传染 | 项目已是 GPL 系，自用/开源无碍 |
| oonid/pr API 变动（半月前还活跃） | 固定到某个 commit/tag 集成，不盲目追 HEAD |
| PTY token 抓取比管道脆弱 | 加 ANSI 清洗 + 保留 pr-cli 的 READY 标记兜底 |

---

## 10. 一句话结论

**`:proot-engine` 是目前已知唯一在 targetSdk 34+ 真机验证过、且能跑 glibc 发行版的现成方案，
API 与我们高度契合，License 不构成新障碍。唯一拦路项是"dsh 子进程 spawn 能否工作"（§5），
这必须作为集成第一步在真机上证实——证实后再按 §7 落地，证伪则退回 targetSdk 28。**
