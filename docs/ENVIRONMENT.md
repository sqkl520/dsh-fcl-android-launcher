# 环境准备（在**新设备**上重建开发环境）

> 工具链（JDK / Android SDK / NDK）**不在本仓库里**——它们在 `/opt` 下，属于运行环境而非工程文件。
> 换设备（例如搬到手机上的新工作区）后需要按本文重新准备。

## 1. 必需组件

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

## 2. 架构相关：x86_64 工具需要 qemu 包装

本项目的开发环境是 **arm64**，而 Android SDK/NDK 里的这些工具是 **x86_64**：

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

## 3. 验证环境

```sh
sh run-tests.sh     # 纯 JVM，34 项，最快（不需要 SDK）
sh run-compile.sh   # Kotlin/Java/资源 + ptyjni native 检查（需要 SDK + NDK）
sh build-apk.sh     # 出 APK（还需 rootfs，见 ROOTFS.md）
```

三个脚本都是**位置无关**的（仓库根 = 脚本所在目录），搬到任何路径都能跑。

## 4. 已知环境坑

| 现象 | 原因 | 处理 |
|---|---|---|
| 编译被 SIGTERM 打断 | 编译要 3~9 分钟，超过调用时限 | **直接重跑**，Gradle 增量会跳过已完成任务 |
| 打包阶段超时 | 300MB `rootfs.tar.xz` 若被 aapt 二次压缩会极慢 | `build.gradle.kts` 已 `noCompress += "xz"`；仍超时就重跑 |
| `.git`, 里出现 `.l2s.tmp_*` 软链残留 | 沙箱存储机制（中断操作的产物） | 确认无引用后可直接删；会挡住 `git gc` |
| `~/.ssh` 不跨会话持久 | 沙箱特性 | 密钥丢了要重新生成并把公钥加到 GitHub |

## 5. 磁盘

| 内容 | 大小 |
|---|---|
| rootfs 解压后（apt 依赖树展开） | ~1.4GB |
| 构建产物与缓存（`FCL/build`、`.gradle`） | ~700MB |
| APK（单个） | ~310MB |
| 工作区整体（含 rootfs 构建目录） | ~5GB |

> 原工作区磁盘长期在 90%+，打包前建议先清理 `FCL/build`。
