#!/bin/sh
# release.sh —— 一个版本出包的完整流程（纯 POSIX sh，电脑 / 手机都能跑）
#
# 步骤（每步都可单独跳过，见下面的开关）：
#   1. 体检        check-sync.sh：版本号一致 / 两仓干净 / 共用文件一致
#   2. 编译        四项编译校验（= run-compile.sh 的内容）
#   3. 单测        单测（= run-tests.sh 的内容）
#   4. 打包        build-apk.sh → output/<name>.apk + apk-archive/<ver>/SHA256SUMS
#   5. 提交        源码仓提交一次并推送
#   6. 发版        gh release create → APK 以 Release 附件分发（不再往 git 塞 90MB 分片）
#
# 用法：
#   sh release.sh                 # 全流程
#   sh release.sh --no-test       # 跳过单测
#   sh release.sh --no-build      # 只体检 + 提交 + 发版（APK 已打好时）
#   sh release.sh --no-release    # 到提交为止，不碰 GitHub
#   sh release.sh --dry-run       # 只打印将要做什么，不改任何东西
#   sh release.sh -m "提交说明"    # 自定义提交说明
#
# 前置：电脑上先 `gh auth login`（已登录则跳过）；手机端若不发版用 --no-release。
set -eu

ROOT=$(cd "$(dirname "$0")/.." && pwd)
# ★ TOOLS：同族脚本都住在 tools/ 里（2026-11-23「工作流脚本纳入源码仓」之后）。
#   本脚本自身就在 tools/ 下，所以直接用它自己的目录 —— 不要再用 $ROOT/xxx.sh：
#   根目录一个 .sh 都没有，那样写会让第 1 步就 exit 1，而且报错文案是"体检未通过"，
#   把"脚本不存在"误报成"体检不合格"（R49 BUILD 审查发现，这是 release.sh 原先完全跑不起来的原因）。
TOOLS=$(cd "$(dirname "$0")" && pwd)
if [ -n "${SRC:-}" ]; then SRC="$SRC"; elif [ -f "$ROOT/settings.gradle.kts" ]; then SRC="$ROOT"; else SRC="$ROOT/dsh-fcl-android-launcher"; fi
[ -d "$SRC/FCL" ] || { echo "!! 找不到源码仓库：$SRC（用 SRC=<路径> 指定）"; exit 1; }

# 2026-11-23：快照仓已删除，只保留源码仓（见 check-sync.sh 顶部说明）

DO_TEST=1; DO_BUILD=1; DO_RELEASE=1; DRY=0; MSG=""
while [ $# -gt 0 ]; do
  case "$1" in
    --no-test)    DO_TEST=0 ;;
    --no-build)   DO_BUILD=0 ;;
    --no-release) DO_RELEASE=0 ;;
    --dry-run)    DRY=1 ;;
    -m)           shift; MSG="${1:-}" ;;
    *) echo "未知参数：$1"; exit 2 ;;
  esac
  shift
done

VER=$(sed -n 's/^dshVersion=//p' "$SRC/gradle.properties" | tr -d '\r' | head -1)
[ -n "$VER" ] || { echo "!! gradle.properties 里没有 dshVersion="; exit 1; }
# ★ R16/fix4：显示用 fix 标签（dshFixLabel，空=正式包）—— fix 包的 tag/附件名/Release
#   标题与注记段都要带它（tag v0.1.6-SNAPSHOT-fix4、附件 …-fix4-arm64.apk、
#   RELEASES.md 里取 `## 0.1.6-SNAPSHOT（fix4，…）` 那一段）。此前这些步骤靠手动，
#   fix2/fix3 的 release.sh 都发到了 base 的 tag 上（REPOS.md §5 有教训记录）。
FIX_LABEL=$(sed -n 's/^dshFixLabel=//p' "$SRC/gradle.properties" | tr -d '\r' | head -1)
RELVER="${VER}${FIX_LABEL:+-$FIX_LABEL}"
NAME="dsh-fcl-android-launcher-${RELVER}-arm64"
APK="$ROOT/output/${NAME}.apk"
[ -z "$MSG" ] && MSG="release: ${RELVER} 出包"

run() { echo "+ $*"; [ "$DRY" = "1" ] || "$@"; }

echo "############################################################"
echo "# release: $RELVER"
echo "#   源码仓 = $SRC"
echo "############################################################"

echo
echo "== 1/6 一致性体检 =="
if [ "$DRY" = "1" ]; then echo "+ sh $TOOLS/check-sync.sh"; else sh "$TOOLS/check-sync.sh" || { echo "!! 体检未通过，先修上面的 FAIL"; exit 1; }; fi

# 体检之后，源码仓必须是干净的 —— 否则"提交"那步会把你没想提交的东西一起提交
DIRTY_S=$(git -C "$SRC" status --porcelain | tr -d '\r')
if [ -n "$DIRTY_S" ]; then
  echo "!! 出包要求先把手上的改动提交掉（release 只提交它自己产生的东西）："
  echo "   源码仓："; echo "$DIRTY_S" | sed 's/^/     /'
  echo "   （或加 --dry-run 先看看流程）"
  [ "$DRY" = "1" ] || exit 1
fi

echo
echo "== 2/6 编译校验 =="
if [ "$DRY" = "1" ]; then echo "+ sh $TOOLS/run-compile.sh"; else sh "$TOOLS/run-compile.sh"; fi

if [ "$DO_TEST" = "1" ]; then
  echo
  echo "== 3/6 单测 =="
  if [ "$DRY" = "1" ]; then echo "+ sh $TOOLS/run-tests.sh"; else sh "$TOOLS/run-tests.sh"; fi
else
  echo; echo "== 3/6 单测（--no-test 跳过）=="
fi

echo
if [ "$DO_BUILD" = "1" ]; then
  echo "== 4/6 打包 =="
  if [ "$DRY" = "1" ]; then echo "+ sh $TOOLS/build-apk.sh"; else sh "$TOOLS/build-apk.sh"; fi
else
  echo "== 4/6 打包（--no-build 跳过）=="
fi

echo
echo "== 5/6 提交源码仓 =="
if [ "$DRY" = "1" ]; then
  echo "+ (源码仓) git add -A && git commit -m \"$MSG\" && git push origin main"
else
  if [ -n "$DIRTY_S" ] || ! git -C "$SRC" diff --cached --quiet; then
    git -C "$SRC" add -A && git -C "$SRC" -c core.autocrlf=false commit -m "$MSG" && git -C "$SRC" push origin main
    echo "   源码仓已推送"
  else
    echo "   源码仓没有改动，跳过"
  fi
fi

if [ "$DO_RELEASE" = "1" ]; then
  echo
  echo "== 6/6 发 GitHub Release（APK 作附件）=="
  [ -f "$APK" ] || [ "$DRY" = "1" ] || { echo "!! 找不到 $APK（先去掉 --no-build 打包）"; exit 1; }
  TAG="v${RELVER}"
  NOTES="$ROOT/output/release-notes-${RELVER}.md"
  if [ "$DRY" = "1" ]; then
    echo "+ gh release create $TAG \"$APK\" --title \"$RELVER\" --notes-file <docs/RELEASES.md 的本版段落>"
  else
    # Release 正文取自**面向用户**的 docs/RELEASES.md 里本版那一段。
    #
    # ★ 为什么不从 CHANGELOG 抠：仓库根还有一份只在本机工作区存在的 CHANGELOG（内部流水账，
    #   里面写着工作区机制、双仓布局、审查记录这些东西），那是给**我们自己**看的。
    #   公开仓面向用户，Release 页面更是用户第一眼看到的东西 —— 两者必须分开。
    #   所以：内部流水账归 CHANGELOG，公开发布说明归 docs/RELEASES.md（两仓共用、会推到公开仓）。
    # ★ R16/fix4：fix 段的标题格式是 `## <版本>（fixN，…）`（全角括号），不是 `-fixN` ——
    #   有标签时按 `## <版本>（<标签>` 精确锁定那一段；无标签（正式包）按 `## <版本>` 前缀。
    #   段的结束一律是**下一个 `## ` 标题**（此前用"前缀不匹配才停"的口径，
    #   同一版本的多段 fix 标题共享前缀，会把后面几段全部吞进来）。
    # 去掉段落末尾的 `---` 分隔线与空行：GitHub 自己会在正文与附件区之间画分隔线，
    # 我们再加一条就成了两条。同时去掉首尾空行，避免正文顶部/底部多出一截空白。
    # ★ 段标题就是产物版本串本身：`## 0.1.6-SNAPSHOT-fix26`，与 tag / APK 文件名完全同源
    #   （= dshVersion + "-" + dshFixLabel）。同源就没有「改名改一半」的对不上风险。
    if [ -n "$FIX_LABEL" ]; then SEC_PREFIX="## ${RELVER}"; else SEC_PREFIX="## ${VER}"; fi
    # ★ 必须**精确匹配整行**，不能用前缀。
    #   事故记录：原来写 `index($0,v)==1`（前缀匹配），而 `## …-fix2` 恰好是 `## …-fix26` 的
    #   前缀，于是抽 fix2 时把 fix26~fix20 七段全抽了出来、抽 fix1 时抽出 fix19~fix10 十段，
    #   线上那两个 Release 的正文因此是别的版本堆栈，用户找不到自己下载的那个包。
    #   判定：整行 == v，或 v 之后紧跟全角括号注释（`## 0.1.6-SNAPSHOT-fix9（已由…）`）。
    awk -v v="$SEC_PREFIX" '
      {
        hit = 0
        if ($0 == v) hit = 1
        else if (index($0, v) == 1 && index(substr($0, length(v)+1), "（") == 1) hit = 1
      }
      hit { f=1 }
      f && /^## / && !hit { exit }
      f { lines[++n] = $0 }
      END {
        last = n
        while (last > 0 && (lines[last] ~ /^[[:space:]]*$/ || lines[last] ~ /^-{3,}[[:space:]]*$/)) last--
        first = 1
        while (first < last && lines[first] ~ /^[[:space:]]*$/) first++
        for (i = first; i <= last; i++) print lines[i]
      }' "$SRC/docs/RELEASES.md" > "$NOTES"
    [ -s "$NOTES" ] || printf '# %s\n\n（docs/RELEASES.md 里没有 %s 段落）\n' "$RELVER" "$SEC_PREFIX" > "$NOTES"
    # ★ 只附一行 sha256 —— 学 Aegis / NewPipe 的做法：正文里不再重复文件名和体积。
    #   APK 附件就在本页面上（GitHub 自己会列出文件名与大小），再写一遍纯属噪音；
    #   校验值本身有用（防止下到半截的包），保留但压成一行。
    SUM=$(sha256sum "$APK" 2>/dev/null | awk '{print $1}')
    if [ -n "$SUM" ]; then
      printf '\n---\n\nsha256: `%s`\n' "$SUM" >> "$NOTES"
    fi
    if command -v gh >/dev/null 2>&1; then
      # 更新已有 release 的说明（--notes-file 会覆盖正文，--clobber 覆盖同名附件）
      if gh release view "$TAG" -R sqkl520/dsh-fcl-android-launcher >/dev/null 2>&1; then
        echo "   已存在 release $TAG，覆盖附件与说明"
        gh release edit "$TAG" -R sqkl520/dsh-fcl-android-launcher --notes-file "$NOTES" >/dev/null
        gh release upload "$TAG" "$APK" --clobber -R sqkl520/dsh-fcl-android-launcher
      else
        gh release create "$TAG" "$APK" -R sqkl520/dsh-fcl-android-launcher \
          --title "$RELVER" --notes-file "$NOTES" --prerelease
      fi
      echo "   https://github.com/sqkl520/dsh-fcl-android-launcher/releases/tag/$TAG"
    else
      echo "!! 找不到 gh（GitHub CLI）。APK 已就绪，手动发版："
      echo "   1) 打开 https://github.com/sqkl520/dsh-fcl-android-launcher/releases/new?tag=$TAG"
      echo "   2) 标题填 $RELVER，说明用 $NOTES，附件选 $APK"
    fi
  fi
else
  echo; echo "== 6/6 发版（--no-release 跳过）=="
  echo "   APK：$APK"
fi

if [ "$DRY" = "1" ]; then
  echo; echo "== DRY-RUN 结束：以上是完整流程，什么都没改 =="
fi
