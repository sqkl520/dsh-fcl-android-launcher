# 打包说明（运行时底座 + 出 APK）

> ## ⚠️ 部分过时（2026-10-01 标注，2026-10-05 复核）
>
> **已作废的部分**：文末〔在 arm64 上出完整 APK（qemu 转发 NDK 工具链）〕整节 ——
> 那是**手机沙箱（arm64）**才需要的 qemu 包装手法。现在开发环境是 **Windows x64**，
> SDK 自带的 cmake/ninja/clang 原生执行，**不需要 qemu**。
>
> **关于 `:proot-engine`**：`design/proot-engine-integration.md` 曾计划改用它，
> 但**最终没有采用** —— 项目继续走"自研 proot 层 + 预打包 rootfs"（阶段 A~E-1 已落地，
> 0.1.1 已出 APK）。因此下文"运行时底座"章节**仍然适用**，只是要按下面「Windows 打包」一节操作。
>
> ---

> 本文分两部分：
> 1. **运行时底座**：APK 里要打包的两类平台大文件（proot 二进制、rootfs 压缩包）怎么准备、放在哪、
>    以及首启之后会发生什么。
> 2. **出 APK**：怎么产出完整可用的 arm64 APK（含 native 库）。见〔Windows 打包〕与文末〔在 arm64 上出完整 APK〕。
>
> 对应代码：`FCL/src/main/assets/dsh/`（解压底座）+ `com.dsh.core.DshBootstrap`（首启解压与自检）。
> 该 assets 目录内另有一份最小提示 `FCL/src/main/assets/dsh/README.md`，指向本文。

## Windows 打包（当前环境，2026-10-05）

```powershell
# 前置：工具链按 ENVIRONMENT.md §1 装好；rootfs 按 ROOTFS.md 补回
#       FCL/src/main/assets/dsh/rootfs/rootfs.tar.xz   ← 300MB，不入 Git

cd D:\Projects\dsh-fcl-android-launcher
.\gradlew.bat --no-daemon -Darch=arm64 :FCL:assembleFordebug
```

产物：`FCL/build/outputs/apk/fordebug/dsh-fcl-android-launcher-<ver>-arm64.apk`。

**打包注意**：
- `build.gradle.kts` 已声明 `noCompress += listOf("xz")`，rootfs 在包内是 **STORED**（未压缩）——
  这既避免 aapt 二次压缩拖慢打包，也让我们能**直接从 APK 里无损取出 rootfs**（见 `ROOTFS.md`）
- rootfs 未就位时也能打包成功，但装到手机上**跑不起来**（底座是 PLACEHOLDER）

## 产物落点（交付 / 快照 / 构建产物）

三个位置各司其职，**不要互相混用**：

| 位置 | 用途 | 是否入 Git |
|---|---|---|
| `<工作区>/output/` | **交付 / 取件**（设备端可访问：`<rikkahub files>/workspaces/<id>/files/output/`）；只放当前版本 | 否 |
| `<工作区>/output/legacy/` | 已废弃的历史冒烟包（MC 时代，rootfs 只是 PLACEHOLDER，**不能用**） | 否 |
| `<仓库>/apk-archive/<version>/` | **版本快照**（保留历史版本便于回滚）+ `SHA256SUMS` | 二进制否；`SHA256SUMS`/`README.md` 是 |
| `<仓库>/FCL/build/outputs/apk/` | Gradle 原始产物；`build-apk.sh` 会把它 **`mv`** 到 `output/`，不留第三份 | 否 |

命名规则：`dsh-fcl-android-launcher-<version>-arm64.apk`（如
`dsh-fcl-android-launcher-0.1.0-SNAPSHOT-arm64.apk`）。

`SHA256SUMS` 里只写**文件名**（两处同名），因此 `output/` 与 `apk-archive/<version>/`
都能直接 `sha256sum -c` 校验。

> ⚠️ **不要用 `/tool_outputs`**：它不是挂载点，只是沙箱内的临时目录，设备端不存在。
> 早期打包曾把 APK 放那里，后来统一改到 `output/`。

第二轮加固（2026-09-22）后，assets 里的 `version` 与 `scripts/version` 已提到 **2**
（子项版本变化会触发增量重解压）。改动与原因见 `reports/round2-fixes.md`。

## 布局（`FCL/src/main/assets/dsh/` 目录内）

```
dsh/
  version              底座总版本（说明用；实际按各子项 version 增量解压）
  proot/
    version            proot 子项版本
    libproot.so        [需你放入] proot 主程序 (arm64)
    libproot_loader.so [需你放入] proot loader (arm64)
  rootfs/
    version            rootfs 子项版本
    rootfs.tar.xz      [需你放入] 精简 Linux rootfs（Ubuntu/Alpine arm64，建议内置 Node 22/24）
  scripts/
    version
    setup-node-dsh.sh  已放入（打印 STAGE=... 进度；装完硬校验）
    start-dsh.sh       已放入（打印 READY url=/FAILED reason=...；信号转发；支持 PORT=0）
    probe.sh           已放入（自检探针，DshBootstrap.verify 调用）
```

## 你需要补齐的两类二进制文件

代码链路（解压、加执行位、增量更新、原子替换、rootfs 布局自愈、自检、proot 命令拼装）已全部就绪
并通过编译验证，但 **proot 二进制** 和 **rootfs 压缩包** 是平台相关的大文件，需你按下述方式准备后放入。

### 1) proot 二进制（二选一，**强烈推荐方案 A**）

**方案 A（推荐）：作为 jniLibs 打包**
- 把 arm64 的 proot、proot loader 分别命名为 `libproot.so`、`libproot_loader.so`
  放到 `FCL/src/main/jniLibs/arm64-v8a/`。
- 系统安装时会自动解压到 `nativeLibraryDir` 并**自带执行位**，最稳。
- `DshPaths.resolveProotBin/Loader` 会优先用这里的；`ProotCommand.preflight` 也会因此通过。
- 此时 `dsh/proot/` 下的 so 可不放（bootstrap 会跳过，回退逻辑仍在）。

**方案 B：作为 assets 打包**
- 把二进制放到 assets 目录 `FCL/src/main/assets/dsh/proot/libproot.so`、`.../libproot_loader.so`。
- `DshBootstrap` 解压后会 `setExecutable(true)`。但注意：**targetSdk ≥ 29 起，App 私有数据目录里的
  可执行文件普遍受 W^X/SELinux 限制，多数新机型上会 exec 失败**。
- 这种情况下启动器**不再静默失败**：预检会直接提示"proot 二进制没有执行权限，请改用 jniLibs 打包"，
  自检（`DshBootstrap.verify` / 设置页"运行时自检"）也会给出具体退出码与输出。

proot 二进制来源：proot-distro 项目的预编译产物、termux 的 proot 包，或自行交叉编译。

### 2) rootfs.tar.xz

- arm64 的 Ubuntu 或 Alpine rootfs（见 PLAN.md §1 选型：默认 Ubuntu/glibc）。
- **强烈建议在打包前把 Node 22/24 预装进 rootfs**，省去首启联网装 Node
  （注意 `probe.sh` 会报告 rootfs 内有没有 node；没有也能用，只是安装 dsh 时要联网）。
- 打成 `.tar.xz`（`DshBootstrap` 用 `RuntimeUtils.uncompressTarXZ` 解压，会正确处理符号链接）。
- 放到 `dsh/rootfs/rootfs.tar.xz`。
- **包内必须是"根"的形态**（顶层就是 `bin/`、`usr/`、`etc/`…）。若不小心多套了一层目录
  （例如顶层是 `ubuntu/`），`DshBootstrap` 会在解压后**自动上提**并记录一行日志；
  仍不可用则给出明确报错，而不是让 proot 抛晦涩错误。

> 注：这两类文件动辄几十~几百 MB，未纳入本仓库。放入后把对应子目录的 `version`
> 改成更大的整数即可触发重新解压（**改 `scripts/` 或 `rootfs/` 的 version 只会重解该项**）。

## 首启之后会发生什么（排查用）

1. `DshBootstrap.install()`：检查磁盘空间（< 1.5GB 直接提示）→ 按 `version` 逐项解压
   （scripts → proot → rootfs）→ rootfs 解到 `rootfs.tmp` 校验后原子替换。
2. **自检**：在 proot 里执行 `scripts/probe.sh`，必须打印 `dsh-probe-ok`；
   否则报"自检未通过：<原因>"（这一步会在装 dsh 之前暴露 proot 缺失/权限/loader/rootfs 布局问题）。
3. 安装 dsh：`setup-node-dsh.sh` 输出 `[setup] STAGE=...` 供界面显示进度；
   npm 缓存共享在 `<filesDir>/dsh/npm-cache`。
4. 启动：`start-dsh.sh` 探测就绪后打印 `[start-dsh] READY url=http://127.0.0.1:<port>/?token=...`。

## 密钥与令牌（安全约定）

- **API key 不再落盘**：启动器通过**子进程环境变量**把 `DEEPSEEK_API_KEY` 传给 proot，
  既不进命令行参数（`ps` 看不到），也不会产生 `credentials.env`。
  脚本里的 `CRED_FILE` 分支仅为 Termux/手工调试保留。
- **启动 token 不进日志**：所有进入日志总线（含落盘的 `logs/runtime.log`）的行都会被脱敏为 `token=***`。
- `dsh.pid` 只记录 `pid` 与 `port`，不记录 URL/token。

---

## 在 arm64 上出完整 APK（qemu 转发 NDK 工具链）

**问题**：FCL 有 native CMake 构建（46 个 C/C++ 源，产出 `libfcl.so`、`libpojavexec.so` 等 12 个库）。
但 Android SDK/NDK 的构建工具链（`cmake`、`ninja`、`clang`、`ld.lld`）**只有 x86_64 版**，
而编译机是 aarch64、且 proot 下注册不了 `binfmt_misc`。直接跑 AGP 的 `externalNativeBuild`
会崩在 `configureCMakeDebug[arm64-v8a]`（CMake 被 x86_64 直接 exec → SIGILL，退出码 132）。

**解法**（已验证）：用 `qemu-x86_64-static` **透明转发整个 NDK 工具链**，并改用
**原生 arm64 的 cmake/ninja** 做配置与驱动，绕开 AGP 自己调 SDK 里 x86_64 cmake 的那一步。

### 关键点

1. **包装 NDK 工具链**：把 `${NDK}/toolchains/llvm/prebuilt/linux-x86_64/bin/` 下的
   x86_64 ELF 换成包装脚本（真二进制改名 `*.real`）：
   ```sh
   #!/bin/sh
   exec /usr/bin/qemu-x86_64-static -0 "$0" <绝对路径>/<名字>.real "$@"
   ```
   - **必须 `-0 "$0"`**：`lld` 靠 `argv[0]` 判断驱动类型（`ld.lld`/`lld-link`…）、
     `clang` 靠 `clang++` 这个名字进 C++ 模式。不给 `-0` 会报
     `lld is a generic driver` / 或全部按 C 编译。
   - **跳过符号链接**：`clang` → `clang-18`、`ld.lld` → `lld` 都是符号链接；
     只包装真文件，否则会"双重包装"导致 `clang.real` 指向脚本而 qemu 报错。
   - 用 `-0 "$0"` 还保留了完整调用路径，clang 据此解析 `InstalledDir` 找到同目录的链接器。

2. **原生 cmake/ninja**：`apt install cmake ninja-build`（这才是 aarch64 原生，不会被 qemu 拖慢）。
   配置时传 `-DCMAKE_MAKE_PROGRAM=/usr/bin/ninja`。

3. **prefab 依赖**：`CMakeLists.txt` 里 `find_package(bytehook REQUIRED CONFIG)` 来自 AGP 的 prefab。
   先把仓库跑一次到 AGP 生成 `FCL/.cxx/<variant>/<hash>/prefab/<abi>/prefab`，
   配置时加 `-DCMAKE_FIND_ROOT_PATH=<该 prefab 目录>`。
   （缺失时也可从 gradle 缓存里的 `bytehook-*` 目录手工合成 `bytehookConfig.cmake`。）

4. **补齐 `libc++_shared.so`**：`libandroidnsbypass.so` 依赖它，从
   `$NDK/toolchains/llvm/prebuilt/linux-x86_64/sysroot/usr/lib/aarch64-linux-android/libc++_shared.so`
   取，和编出的库一起放进 jniLibs 暂存目录。

5. **交给 AGP 打包**：临时注释 `build.gradle.kts` 里两处 `externalNativeBuild`，
   并加一句 `sourceSets.getByName("main") { jniLibs.srcDir("/tmp/fcl-jnilibs") }`，
   然后 `./gradlew --no-daemon -Darch=arm64 :FCL:assembleFordebug`。**打完记得还原 `build.gradle.kts`**。

### 实测结果（2026-09-29）

- 工具链包装：NDK bin 下 38 个真二进制被包装（符号链接保留）。
- native 配置：Clang 18.0.1 ABI 检测通过，14 秒。
- native 编译：**58 个构建步骤 43 秒完成**，产出 12 个 arm64 `.so`。
- 打包：`assembleFordebug` BUILD SUCCESSFUL，产出
  `FCL-fordebug-1.3.3.3-arm64-v8a.apk`（约 189MB，已签名）。
- 内容核验：APK 内 arm64 库 **35 个**（含此前缺失的 `libfcl.so`/`libpojavexec.so`/
  `libpojavexec_awt.so`/`libc++_shared.so`），dsh 类 **30 个**进 dex，dsh assets 全部在包内。

> 一键复现：`sh /workspace/build-apk.sh`（含幂等包装、配置、编译、暂存、打包、还原）。
> 磁盘要求：构建期间约需 3~4GB 余量。
