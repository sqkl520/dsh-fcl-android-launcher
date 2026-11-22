#!/bin/sh
# 编译验证脚本（不打包 APK）
# 覆盖：Kotlin 编译 + Java 编译 + 资源处理 + Manifest 合并 + 原生 ptyjni
# 需要出 APK 时另跑 build-apk.sh
set -e
export GRADLE_OPTS="-Xmx1300m -XX:MaxMetaspaceSize=450m"
# 本仓库根 = 脚本所在目录的上一级（脚本已移入 tools/，不再硬编码 /workspace）
ROOT=$(cd "$(dirname "$0")/.." && pwd)
# 源码目录自动识别（见 REPOS.md）：电脑=本目录；手机=子目录 dsh-fcl-android-launcher
if [ -n "${SRC:-}" ]; then :; elif [ -f "$ROOT/settings.gradle.kts" ]; then SRC="$ROOT"; else SRC="$ROOT/dsh-fcl-android-launcher"; fi
[ -d "$SRC" ] || { echo "!! 找不到源码仓库：$SRC（用 SRC=<路径> 指定）"; exit 1; }
cd "$SRC" || exit 1

./gradlew --no-daemon --offline -Darch=arm64 \
  :FCL:compileDebugKotlin \
  :FCL:compileDebugJavaWithJavac \
  :FCL:processDebugResources \
  :FCL:processDebugMainManifest

# ─── 源码内容哈希戳（与 run-tests.sh 的新鲜度守卫配对）────────────────────────
# 为什么需要它：run-tests.sh 的守卫用 **mtime** 判"产物是否过期"。而 Gradle 是**按内容
# 哈希**判定 up-to-date 的 —— 两者语义不同，于是出现一个假红窗口：
#   只要源文件内容没变、mtime 变新（`touch` / `git stash pop` / 编辑器保存但内容相同 /
#   checkout 到同一内容），Gradle 认为无事可做、不重编译，而守卫看到"源文件比 .class 新"
#   就报"产物已过期"。本轮实测踩过：`git stash pop` 之后连续几次跑测试都被守卫拦下，
#   一度被误读成"测试 flaky"。
# 做法：编译成功后把**源码内容**的哈希写进产物目录。守卫在 mtime 报警时改比哈希 ——
#   哈希一致 ⇒ 内容没变、产物是最新的，放行；哈希不同 ⇒ 真的改了，照旧拦截。
# ⚠️ 戳必须在 Gradle **成功之后**写：失败时写戳等于把"陈旧产物"盖章成最新，比不写更糟。
# 口径必须与 run-tests.sh 里的 SOURCE_HASH_CMD / SOURCE_HASH_STAMP **完全一致**，
#   否则两边算出的哈希永远不等，守卫会退化成"每次都报过期"。
SOURCE_HASH_STAMP="$SRC/FCL/build/tmp/kotlin-classes/debug/.source-hash"
mkdir -p "$(dirname "$SOURCE_HASH_STAMP")" 2>/dev/null || true
if command -v sha256sum > /dev/null 2>&1; then
  ( cd "$SRC" && find FCL/src/main/java \( -name '*.kt' -o -name '*.java' \) -type f \
      -print | LC_ALL=C sort | xargs sha256sum ) | sha256sum | awk '{print $1}' > "$SOURCE_HASH_STAMP"
  echo "== 源码内容哈希戳已更新: $(cat "$SOURCE_HASH_STAMP" 2>/dev/null) =="
else
  # 没有 sha256sum 时**删掉**旧戳而不是留一份陈旧的 —— 否则守卫会拿过期哈希做判断。
  rm -f "$SOURCE_HASH_STAMP"
  echo "== 警告：找不到 sha256sum，无法写源码哈希戳（新鲜度守卫将只按 mtime 判断）=="
fi

# 原生 PTY 桥接（ptyjni）：两条路径
#   · 电脑（Windows）：AGP 的 externalNativeBuild 会用 SDK 自带的 cmake/ninja，**这里不用重复做** ——
#     上面那四个 Gradle 任务已经覆盖；本段只在找得到 posix cmake 时补一次独立编译。
#   · 手机（arm64 Linux 沙箱）：AGP 用的 SDK cmake 是 x86_64、跑不了，改用系统原生 cmake/ninja + NDK 工具链。
# SDK 路径可用环境变量覆盖（新环境可能装在别处）
SDK=${ANDROID_SDK_ROOT:-${ANDROID_HOME:-/opt/android-sdk}}
# 电脑上 SDK 路径写在仓库根 local.properties（该文件不进 Git）
if [ ! -d "$SDK" ] && [ -f "$SRC/local.properties" ]; then
  _s=$(sed -n 's/^sdk\.dir=//p' "$SRC/local.properties" | tr -d '\r' | head -1)
  [ -n "$_s" ] && SDK="$_s"
fi
NDK="$SDK/ndk/27.0.12077973"

# 找一个 POSIX 风格可用的 cmake：优先系统 /usr/bin/cmake，其次 SDK 自带（Windows 上是 .exe）
CMAKE_BIN=""
[ -x /usr/bin/cmake ] && CMAKE_BIN=/usr/bin/cmake
if [ -z "$CMAKE_BIN" ] && [ -x "$SDK/cmake/3.22.1/bin/cmake.exe" ]; then
  CMAKE_BIN="$SDK/cmake/3.22.1/bin/cmake.exe"
fi
NINJA_BIN=""
[ -x /usr/bin/ninja ] && NINJA_BIN=/usr/bin/ninja
if [ -z "$NINJA_BIN" ] && [ -x "$SDK/cmake/3.22.1/bin/ninja.exe" ]; then
  NINJA_BIN="$SDK/cmake/3.22.1/bin/ninja.exe"
fi

if [ -d "$NDK" ] && [ -n "$CMAKE_BIN" ] && [ -n "$NINJA_BIN" ]; then
  echo "== 原生 ptyjni 编译检查 =="
  # ★ 清理上一轮目录，但**不许**因为它失败就判定整个编译失败：
  #   并发跑两个 run-compile.sh 时，另一个进程可能正持有 /tmp/ptyjni-check 下的文件，
  #   `rm -rf` 会以 "Device or resource busy" 退出非零，而 `set -e` 会直接把脚本拖死 ——
  #   症状是"BUILD SUCCESSFUL 了却 exit 1"，看起来像编译失败，实际只是清理残留没删干净。
  #   本轮实测踩过一次（并因此误判了一个后台任务的退出码）。
  #   清不掉就让 cmake 复用/覆盖它：配置阶段本来就会重建需要的产物。
  rm -rf /tmp/ptyjni-check 2>/dev/null || true
  "$CMAKE_BIN" -H"FCL/src/main/cpp/ptyjni" -B/tmp/ptyjni-check -G Ninja \
    -DCMAKE_MAKE_PROGRAM="$NINJA_BIN" \
    -DCMAKE_TOOLCHAIN_FILE="$NDK/build/cmake/android.toolchain.cmake" \
    -DANDROID_ABI=arm64-v8a -DANDROID_PLATFORM=android-26 \
    -DCMAKE_BUILD_TYPE=Debug > /tmp/ptyjni-check-cfg.log 2>&1 \
    || { echo "!! ptyjni 配置失败"; tail -20 /tmp/ptyjni-check-cfg.log; exit 1; }
  "$NINJA_BIN" -C /tmp/ptyjni-check > /tmp/ptyjni-check.log 2>&1 \
    || { echo "!! ptyjni 编译失败"; tail -20 /tmp/ptyjni-check.log; exit 1; }
  echo "   ptyjni OK: $(ls -l /tmp/ptyjni-check/libptyjni.so | awk '{print $5}') bytes"
else
  echo "== 跳过独立 ptyjni 检查（缺 posix cmake/ninja 或 NDK）=="
  echo "   电脑上由 AGP 的 externalNativeBuild 编译 ptyjni（见上面 Gradle 任务的输出）"
fi
