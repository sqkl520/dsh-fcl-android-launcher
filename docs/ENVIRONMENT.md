# 环境准备（在**新设备**上重建开发环境）

> 工具链（JDK / Android SDK / NDK）**不在本仓库里**，属于运行环境而非工程文件。
> 换设备后需要按本文重新准备。
>
> **两种已验证的环境**：
> - **A. Windows x64 电脑（当前主力，2026-10-05 搭建）** —— 见 §1；原生跑 cmake，**没有 qemu 那一套坑**
> - **B. arm64 Linux（手机沙箱 / 原工作区）** —— 见 §2；SDK/NDK 里的 x86_64 工具需 qemu 包装

## 1. Windows x64 电脑（当前环境）

### 1.1 已装组件（实测可编译）

| 组件 | 版本 | 本机路径 | 说明 |
|---|---|---|---|
| JDK | **17**（Microsoft OpenJDK） | `C:\Program Files\Microsoft\jdk-17.0.20.101-hotspot` | AGP 8.x 要求；**注意本机默认 JDK 是 21，必须让 Gradle 用 17** |
| Android Studio | 2026.1.3.7 | `C:\Program Files\Android\Android Studio` | 写代码用；命令行构建不依赖它 |
| Android SDK | platform **35**、build-tools **35.0.0**、platform-tools | `D:\Android\Sdk` | 包名：`Google.AndroidStudio` + `cmdline-tools` |
| Android NDK | **27.0.12077973** | `D:\Android\Sdk\ndk\27.0.12077973` | 只用于 `ptyjni` 桥接 |
| CMake（SDK 自带） | **3.22.1** | `D:\Android\Sdk\cmake\3.22.1\bin\{cmake,ninja}.exe` | AGP `externalNativeBuild` 用；**x64 原生执行，无需 qemu** |

### 1.2 三条关键接线（缺一样就编不了）

```powershell
# ① 让 Gradle 用 JDK 17（本机默认是 21）——写进用户级 gradle.properties
#    %USERPROFILE%\.gradle\gradle.properties
org.gradle.java.home=C:/Program Files/Microsoft/jdk-17.0.20.101-hotspot

# ② 告诉 Gradle SDK 在哪 —— 写进仓库根 local.properties（该文件 .gitignore，不进 Git）
#    D:\Projects\dsh-fcl-android-launcher\local.properties
sdk.dir=D:/Android/Sdk

# ③ 让脚本/其他工具也能找到 SDK（可选但推荐，用户级环境变量）
setx ANDROID_SDK_ROOT "D:\Android\Sdk"
setx ANDROID_HOME     "D:\Android\Sdk"
```

### 1.3 行尾：脚本必须是 LF ⚠️

本机 Git 的 `core.autocrlf` **必须设为 `false`**（并配 `core.eol=lf`）：

```powershell
git config --global core.autocrlf false
git config --global core.eol lf
```

原因：`FCL/src/main/assets/dsh/scripts/*.sh` 会被**原样打进 APK**，
真机上由 proot 里的 `/bin/sh` 执行。Windows 检出成 CRLF 后，首行变成 `#!/bin/sh\r`，
解释器找不到 → 底座脚本全废。`.gitattributes` 已对该目录声明 `text eol=lf` 兜底，
但**本机全局配置也要对**，否则旧检出不会自动更正。

验证（应输出 `i/lf  w/lf`）：

```powershell
git ls-files --eol FCL/src/main/assets/dsh/scripts/
```

### 1.4 Android Studio 首次打开要做的

1. `File → Open` 选源码根（`D:\Projects\dsh-fcl-android-launcher`，**不是**里面的 `FCL/`）
2. `Settings → Build → Build Tools → Gradle → Gradle JDK` 选 **17**（Studio 自带 JBR 是 21，AGP 8.13 要 17）
3. 首次同步会下载 Gradle 8.14.4 + 依赖，需联网，数分钟

### 1.5 本平台的坑

| 现象 | 原因 | 处理 |
|---|---|---|
| `sdkmanager --licenses` 卡在 `Accept? (y/N)`，管道喂 `y` 无效 | `.bat` 包装层吃掉 stdin | 用 `cmd /c "...sdkmanager.bat --licenses < yes.txt"`（PowerShell 管道不行） |
| 首次 Gradle 构建很慢 | 下载 Gradle 发行版 + 全部依赖 | 一次性的；之后走缓存 |
| 编译耗时长（3~9 分钟） | 全量 Kotlin/Java/资源 | 超时就直接重跑，Gradle 增量会跳过已完成任务 |

## 2. arm64 Linux（手机沙箱 / 原工作区）

| 组件 | 版本 | 原工作区路径 | 说明 |
|---|---|---|---|
| JDK | **17** | `/usr/lib/jvm/java-17-openjdk-arm64` | AGP 8.x 要求 |
| Android SDK | compileSdk **35**、build-tools **35.0.0**、platform-tools | `/opt/android-sdk` | 可用 `ANDROID_SDK_ROOT` 覆盖（脚本已支持） |
| Android NDK | **27.0.12077973** | `/opt/android-sdk/ndk/27.0.12077973` | 只用于 `ptyjni` 桥接 |
| Gradle | wrapper 自带 | `FCL/gradlew` | 无需单独安装 |
| 系统 cmake/ninja | 任意较新版本 | `/usr/bin/{cmake,ninja}` | `run-compile.sh` 用它编 ptyjni |

`ANDROID_SDK_ROOT` 若指向别处，脚本会自动跟随：

```sh
export ANDROID_SDK_ROOT=/path/to/android-sdk
```

## 3. 架构相关（**仅 arm64 Linux 需要**）：x86_64 工具需 qemu 包装

> Windows x64 电脑**不需要本节**——SDK 自带的 cmake/ninja/clang 在 x64 上是原生执行的。

arm64 机器上，Android SDK/NDK 里的这些工具是 **x86_64**：

- `$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin/*`（clang 等）
- `$SDK/cmake/<ver>/bin/{cmake,ninja,cpack,ctest}`

直接用会 **SIGILL**（AGP 的 `externalNativeBuild` 报 `finished with non-zero exit value 132`）。
解决办法：用 `qemu-x86_64-static` 写一层包装脚本：

```sh
# 以 SDK 自带 cmake 为例（NDK 里的工具同理）
cd "$SDK/cmake/3.22.1/bin"
for f in cmake ninja cpack ctest; do
  [ -f "$f" ] || continue
  [ -f "$f.real" ] && continue          # 已包装过就跳过
  mv "$f" "$f.real"
  printf '#!/bin/sh\nexec qemu-x86_64-static "%s.real" "$@"\n' "$(pwd)/$f" > "$f"
  chmod +x "$f"
done
```

### ⚠️ 双重包装坑（踩过，报错极不明显）

如果循环里用 `[ -f "$f.real" ] && continue` 这类守卫，**刚生成的 `$f.real` 会在同一次循环里被再包一层**
（因为此时 `$f.real.real` 还不存在，守卫拦不住），结果：

```
cmake → cmake.real(脚本) → cmake.real.real(真二进制)   # qemu 去执行脚本 → 失败
```

- **判断**：`ls $NDK/toolchains/llvm/prebuilt/linux-x86_64/bin/*.real.real | wc -l` 不为 0 就是被双重包装
- **修复**：对每个 `X.real.real` 执行 `mv -f X.real.real X.real`（保留外层包装脚本）
- 本项目 NDK 27 曾出现 **38 个工具被双重包装**，修复后 clang 才正常

## 4. 验证环境

**Windows（当前）**：

```powershell
# 编译校验（Kotlin + Java + 资源 + Manifest）
.\gradlew.bat --no-daemon -Darch=arm64 :FCL:compileDebugKotlin :FCL:compileDebugJavaWithJavac `
  :FCL:processDebugResources :FCL:processDebugMainManifest

# 单测（Gradle 原生跑，不再需要沙箱的 MiniRunner 桩）
.\gradlew.bat --no-daemon :FCL:testFordebugUnitTest

# 打包（需先补 rootfs，见 ROOTFS.md）
.\gradlew.bat --no-daemon -Darch=arm64 :FCL:assembleFordebug
```

**arm64 Linux（手机沙箱）**：`sh run-compile.sh` / `sh run-tests.sh` / `sh build-apk.sh`
—— 这三个脚本在**快照仓**里（电脑上与源码仓共用一个项目文件夹：`D:\Projects\dsh-fcl-android-launcher\*.sh`；
手机 `/workspace/`），是纯 POSIX sh、面向 arm64 Linux；Windows 上直接跑不了，用上面的 Gradle 命令。
两仓关系见根下 `REPOS.md`。

**电脑上的脚本入口（Windows 常用）**：`.cmd` 包装 = 调 Git Bash 跑同名 `.sh`

```powershell
.\check-sync.cmd          # 体检：版本号一致 / 两仓干净 / 共用文件一致 / 与远端同步
.\release.cmd --dry-run   # 出包全流程（加 --dry-run 只打印不改）
.\bootstrap.cmd --check   # 检查双仓共用工作树是否完整
```

> 完整的仓库分工、出包流程、换电脑重建步骤 → 根下 **`REPOS.md`**。

## 5. 已知环境坑（两平台通用）

| 现象 | 原因 | 处理 |
|---|---|---|
| 编译被 SIGTERM 打断 | 编译要 3~9 分钟，超过调用时限 | **直接重跑**，Gradle 增量会跳过已完成任务 |
| 打包阶段超时 | 300MB `rootfs.tar.xz` 若被 aapt 二次压缩会极慢 | `build.gradle.kts` 已 `noCompress += "xz"`；仍超时就重跑 |
| `.git` 里出现 `.l2s.tmp_*` 软链残留 | 手机沙箱存储机制（中断操作的产物） | 确认无引用后可直接删；会挡住 `git gc` |
| `~/.ssh` 不跨会话持久 | 手机沙箱特性 | 密钥丢了要重新生成并把公钥加到 GitHub |
| Android 资源文件报 `InvalidFileException` | 从不区分大小写的文件系统（Windows）拷来的资源文件名重复 | 检查冲突的资源名 |

## 6. 磁盘

| 内容 | 大小 |
|---|---|
| rootfs 解压后（apt 依赖树展开） | ~1.4GB |
| 构建产物与缓存（`FCL/build`、`.gradle`） | ~700MB |
| APK（单个） | ~310MB |
| 工作区整体（含 rootfs 构建目录） | ~5GB |

> 原工作区（手机）磁盘长期在 90%+，打包前建议先清理 `FCL/build`。
> Windows 本机 C: 与 D: 余量充足（2026-10-05：C 92GB / D 54GB 空闲）。
