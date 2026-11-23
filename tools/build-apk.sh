#!/bin/sh
# build-apk.sh —— 打包 dsh-FCL arm64 APK
#
# ★ 源码目录自动识别（见 REPOS.md）：
#     · 电脑（单文件夹）：D:\Projects\dsh-fcl-android-launcher —— 源码与本脚本同目录
#     · 手机：/workspace（本脚本所在） + /workspace/dsh-fcl-android-launcher（源码）
#   仓库关系详见 REPOS.md，也可用环境变量 SRC=<路径> 显式指定。
#
# native/MC 工程已在阶段 4 删除；此脚本不再配置旧的 FCL/src/main/jni。
# 沙箱里 SDK 自带的 cmake/ninja 是 x86_64，已用 qemu 包装（见 docs/ENVIRONMENT.md §3）。
#
# ## 产物落点（约定见 docs/PACKAGING.md）
#   1) <本仓库根>/output/                         交付 / 取件目录（设备端可见，只放当前版本）
#   2) <源码仓库>/apk-archive/<version>/          版本快照（二进制不入 Git，仅提交 SHA256SUMS/README）
#
# ★ 版本号不再写死在这里：从根 gradle.properties 的 dshVersion 读（单一来源）。
# ★ APK 分发走 GitHub Release 附件（见 REPOS.md「版本与分发」），不再往快照仓提交 90MB 分片。
# ★ 用 `mv` 而不是 `cp` 把 Gradle 产物搬到 output：避免同时存在「Gradle 产物 + output + 快照」
#   三份 300MB 的副本（工作区磁盘常年在 90%+）。Gradle 下次打包会重新生成，不受影响。
# ★ 不要写 /tool_outputs：它不是挂载点，只是沙箱内临时目录，设备端不存在。
set -eu

# 本仓库根 = 脚本所在目录的上一级（脚本已移入 tools/，不再硬编码 /workspace）
ROOT=$(cd "$(dirname "$0")/.." && pwd)
# 源码目录自动识别：电脑=本目录（有 settings.gradle.kts）；手机=子目录 dsh-fcl-android-launcher
if [ -n "${SRC:-}" ]; then :; elif [ -f "$ROOT/settings.gradle.kts" ]; then SRC="$ROOT"; else SRC="$ROOT/dsh-fcl-android-launcher"; fi
SDK=${ANDROID_SDK_ROOT:-${ANDROID_HOME:-/opt/android-sdk}}
OUT_DIR="$ROOT/output"

[ -d "$SRC" ] || { echo "!! 找不到源码仓库：$SRC（用 SRC=<路径> 指定）"; exit 1; }

# 版本号单一来源：根 gradle.properties 的 dshVersion（唯一真源，见 REPOS.md）
PROPS="$SRC/gradle.properties"
[ -f "$PROPS" ] || { echo "!! 找不到 $PROPS（版本号单一来源）"; exit 1; }
VERSION=$(sed -n 's/^dshVersion=//p' "$PROPS" | tr -d '\r' | head -1)
[ -n "$VERSION" ] || { echo "!! $PROPS 里没有 dshVersion="; exit 1; }
# ★ R16/fix4：读显示用 fix 标签（dshFixLabel，空=正式包）—— 产物名带上它：
#   ①与 Gradle 产物名（build.gradle.kts 的 outputFileName 现用 versionName=版本+标签）一致；
#   ②fix 包文件名天然与 base 名不同 → 本脚本第 3 步的 `rm -f "$OUT"` 再也不会误删 base 包
#   （这正是 fix2/fix3 出包时踩过的坑，见 REPOS.md §5 的"出包实操教训"）。
FIX_LABEL=$(sed -n 's/^dshFixLabel=//p' "$PROPS" | tr -d '\r' | head -1)
NAME=dsh-fcl-android-launcher-${VERSION}${FIX_LABEL:+-$FIX_LABEL}-arm64
ARCHIVE_DIR="$SRC/apk-archive/${VERSION}"
echo "== 版本（读自 gradle.properties）：$VERSION${FIX_LABEL:+（$FIX_LABEL）} =="

# 工具链接线：手机（arm64 沙箱）用固定路径；电脑上 JAVA_HOME 由用户级
# ~/.gradle/gradle.properties 的 org.gradle.java.home 指定（见 ENVIRONMENT.md §1.2），
# 这里只在"沙箱那套路径真的存在"时才覆盖，否则交给 Gradle 自己找。
if [ -d /usr/lib/jvm/java-17-openjdk-arm64 ]; then
  export JAVA_HOME=/usr/lib/jvm/java-17-openjdk-arm64
  export PATH="$JAVA_HOME/bin:$PATH"
fi
export ANDROID_HOME="$SDK"
export GRADLE_OPTS="-Xmx1400m -XX:MaxMetaspaceSize=450m"

cd "$SRC"
echo "== 1/3 清理旧 APK 输出 =="
rm -rf FCL/build/outputs/apk/release

echo "== 2/3 Gradle 打包 arm64（release = 出货变体，T-D1 2026-10-19 起）=="
# ★ 出货走 release 变体：正式包名（无 .debug）、非 debuggable、新私钥签名（T-D1）。
#   旧的 fordebug 变体保留为内部调试通道（debuggable + run-as 抓日志，见 tools/ondevice.md），
#   不再由本脚本出包。release 变体签名需要 local.properties 的 dsh.pwd（或 DSH_KEYSTORE_PASSWORD）。
./gradlew --no-daemon -Darch=arm64 :FCL:assembleRelease

APK=$(find FCL/build/outputs/apk/release -type f -name '*.apk' -printf '%T@ %p\n' | sort -nr | head -1 | cut -d' ' -f2-)
[ -n "$APK" ] || { echo "!! 找不到 APK 输出"; exit 1; }

# ★ 出货三查（T-D1 起；锐评"发布错位"整改的一部分）：包名必须无 .debug、必须**不** debuggable、
#   versionName 必须与 gradle.properties 一致。badging 读不到时（aapt2 缺失）跳过并提示，
#   不静默放行——但也不因为工具缺失把整条出包链挡死（手机沙箱可不带 aapt2）。
AAPT2=""
for c in "${ANDROID_HOME:-}/build-tools/"*/aapt2 "${SDK}/build-tools/"*/aapt2.exe "${SDK}/build-tools/"*/aapt2; do
  [ -x "$c" ] && { AAPT2="$c"; break; }
done
if [ -n "$AAPT2" ]; then
  BADGING=$("$AAPT2" dump badging "$APK" 2>/dev/null | head -1)
  PKG=$(echo "$BADGING" | sed -n "s/^package: name='\([^']*\)'.*/\1/p")
  VN=$(echo "$BADGING" | sed -n "s/.*versionName='\([^']*\)'.*/\1/p")
  DEB=$("$AAPT2" dump badging "$APK" 2>/dev/null | grep -c "application-debuggable" || true)
  echo "   包名=$PKG  versionName=$VN  debuggable=$DEB"
  case "$PKG" in
    *".debug") echo "!! 出货包不应带 .debug 后缀（装错变体？）"; exit 1 ;;
  esac
  [ "$DEB" = "0" ] || { echo "!! 出货包不应是 debuggable（装错变体？）"; exit 1; }
  [ "$VN" = "${VERSION}${FIX_LABEL:+-$FIX_LABEL}" ] || { echo "!! versionName 与 gradle.properties 不一致"; exit 1; }
  echo "   出货三查通过 ✓"
else
  echo "   [warn] 找不到 aapt2，跳过出货三查（装 aapt2 后可用 check-apk 复核）"
fi

echo "== 3/3 落交付目录 + 版本快照 =="
mkdir -p "$OUT_DIR" "$ARCHIVE_DIR"
OUT="$OUT_DIR/${NAME}.apk"
# ★ 目标已存在 ⇒ **先改名备份，绝不删除**（项目红线，见 MEMORY.md「output 老版本 APK 永不删」）。
#
#   原来这里是 `rm -f "$OUT"; mv "$APK" "$OUT"` —— 同版本重打包时会**静默销毁上一份 APK**。
#   这正是 2026-10-19 那次事故的同一形态：`rm -f` 覆盖了当时唯一一份 0.1.6 正式包，
#   且尚未发布到 GitHub Release、无任何外部备份，导致"修之前的版本"永久丢失。
#   R49 BUILD 审查用 /tmp 复现了这条：旧 fix40 的内容被新产物覆盖、无任何残留。
#
#   为什么要备份而不是直接覆盖：output/ 是本机唯一集齐全版本的目录，老版本是
#   "修之前/之后"的对照基准 + 回滚手段。哪怕别处有备份，这里也不许删。
if [ -f "$OUT" ]; then
  # 时间戳后缀：同一秒内重复出包会撞名，所以撞名时再加序号（不覆盖已存在的备份）。
  _bak="$OUT_DIR/${NAME}.$(date -u +%Y%m%dT%H%M%SZ 2>/dev/null || date +%Y%m%d%H%M%S).bak"
  _n=1
  while [ -e "$_bak" ]; do _bak="${_bak%.bak}-$_n.bak"; _n=$((_n + 1)); done
  mv "$OUT" "$_bak" || { echo "!! 备份旧 APK 失败（拒绝覆盖，中止）：$OUT -> $_bak"; exit 1; }
  echo "   [keep] 已备份旧 APK：$(basename "$_bak")"
  echo "          （红线：output/ 内老版本永不删除，只改名保留）"
fi
mv "$APK" "$OUT"

# ★ 不再往 apk-archive/ 复制一份二进制（同一份内容占 300MB，纯浪费）。
#   apk-archive/<ver>/ 只留 SHA256SUMS 作为「这个版本出过、指纹是多少」的记录；
#   APK 本体走 GitHub Release 附件（见 REPOS.md「版本与分发」）。
# ★ 追加而不是覆盖（2026-10-19 事故修正：原来 `>` 会把同一版本目录下历次出包的
#   全部指纹行冲成只剩本次一行 —— fix6 出包时实际发生过，已从 git 恢复）。
#   先删同名旧行再追加本次，保证幂等且不丢历史；文件头保持与 sha256sum -c 兼容。
for SUMS in "$ARCHIVE_DIR/SHA256SUMS" "$OUT_DIR/SHA256SUMS"; do
  touch "$SUMS"
  grep -v "  \?\*\?${NAME}.apk\$" "$SUMS" > "$SUMS.tmp" 2>/dev/null || true
  mv "$SUMS.tmp" "$SUMS"
done
( cd "$OUT_DIR" && sha256sum "${NAME}.apk" ) >> "$ARCHIVE_DIR/SHA256SUMS"
( cd "$OUT_DIR" && sha256sum "${NAME}.apk" ) >> "$OUT_DIR/SHA256SUMS"

echo
echo "== 完成 =="
ls -lh "$OUT"
cat "$ARCHIVE_DIR/SHA256SUMS"
echo
echo "提示：交付/取件请用 $OUT_DIR/（设备端：<workspaces>/<id>/files/output/）"
echo "      发版：cd $ROOT && sh release.sh（或电脑上 release.cmd）→ 传 GitHub Release"
