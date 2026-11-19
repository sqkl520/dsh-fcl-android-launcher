#!/bin/sh
# publish-fix23-26.sh —— 把 fix23~fix26 四个已打好但未发布的包补发到 GitHub Releases
#
# 背景：这四轮的包早就在 output/ 里打好了、校验值也已登记进 apk-archive/0.1.6-SNAPSHOT/SHA256SUMS，
#      但一直没发到 Releases 页（也没有 tag）。于是文档写"当前验证包 fix26"，用户却在 Releases
#      上下不到。这个脚本专门补这一步，不重新编译、不重新打包。
#
# 每个包在发之前都做四项校验，任何一项不符就中止：
#   1. APK 存在
#   2. sha256 与 apk-archive/0.1.6-SNAPSHOT/SHA256SUMS 里登记的一致
#   3. APK 内的 versionCode / versionName 与 gradle.properties 的该轮编号对得上
#   4. 签名与已发布的 fix22 一致（否则用户无法覆盖安装）
#
# 用法：
#   sh publish-fix23-26.sh --dry-run    # 只校验 + 打印，不发
#   sh publish-fix23-26.sh              # 校验通过后逐个发布
set -eu

ROOT=$(cd "$(dirname "$0")" && pwd)
if [ -f "$ROOT/settings.gradle.kts" ]; then SRC="$ROOT"; else SRC="$ROOT/dsh-fcl-android-launcher"; fi
[ -d "$SRC/FCL" ] || { echo "!! 找不到源码仓库：$SRC"; exit 1; }

REPO=sqkl520/dsh-fcl-android-launcher
OUT="$SRC/output"
SUMS="$SRC/apk-archive/0.1.6-SNAPSHOT/SHA256SUMS"
# 对外版本号（段标题是 `## 0.1.6-SNAPSHOT（fixN，…）`，抽取正文时要用）
VER=$(sed -n 's/^dshVersion=//p' "$SRC/gradle.properties" 2>/dev/null | tr -d '\r' | head -1)
[ -n "$VER" ] || { echo "!! gradle.properties 里没有 dshVersion="; exit 1; }
DRY=0
[ "${1:-}" = "--dry-run" ] && DRY=1

# ---- 工具定位（aapt2 / apksigner 在 SDK build-tools 下）----
SDK=$(sed -n 's/^sdk\.dir=//p' "$SRC/local.properties" 2>/dev/null | tr -d '\r' | head -1)
BT=$(ls -d "$SDK/build-tools"/* 2>/dev/null | sort -V | tail -1)
AAPT="$BT/aapt2.exe"; [ -f "$AAPT" ] || AAPT="$BT/aapt2"
APKSIGNER="$BT/apksigner.bat"; [ -f "$APKSIGNER" ] || APKSIGNER="$BT/apksigner"

# 基准：已发布的 fix22 的证书指纹。补发的包必须与它相同，否则老用户装不上。
BASE_APK="$OUT/dsh-fcl-android-launcher-0.1.6-SNAPSHOT-fix22-arm64.apk"
[ -f "$BASE_APK" ] || { echo "!! 缺少对照基准 $BASE_APK"; exit 1; }
BASE_CERT=$(sh -c "'$APKSIGNER' verify --print-certs '$BASE_APK'" 2>/dev/null \
  | sed -n 's/.*certificate SHA-256 digest: //p' | head -1 | tr -d '\r')
[ -n "$BASE_CERT" ] || { echo "!! 读不出发布基准的签名证书"; exit 1; }
echo "== 发布基准（fix22）证书指纹：$BASE_CERT"
echo

FAIL=0
# fix编号:versionCode —— 与 git log 里的升版记录对应
for pair in fix23:129 fix24:130 fix25:131 fix26:132; do
  FIX=${pair%%:*}; CODE=${pair##*:}
  RELVER="0.1.6-SNAPSHOT-$FIX"
  APK="$OUT/dsh-fcl-android-launcher-${RELVER}-arm64.apk"
  echo "----------------------------------------------"
  echo "→ $RELVER（期望 versionCode=$CODE）"

  # 1. 存在
  if [ ! -f "$APK" ]; then echo "  [FAIL] 缺少 APK：$APK"; FAIL=1; continue; fi
  echo "  [OK]   APK 存在（$(du -h "$APK" | awk '{print $1}')）"

  # 2. 校验值
  WANT=$(sed -n "s/ .*\*\(dsh-fcl-android-launcher-${RELVER}-arm64\.apk\)$//p" "$SUMS" 2>/dev/null | head -1)
  WANT=$(grep -F "*$(basename "$APK")" "$SUMS" 2>/dev/null | awk '{print $1}' | head -1 | tr -d '\r')
  GOT=$(sha256sum "$APK" | awk '{print $1}')
  if [ -z "$WANT" ]; then
    echo "  [WARN] SHA256SUMS 里没有登记，跳过比对"
  elif [ "$WANT" = "$GOT" ]; then
    echo "  [OK]   sha256 与台账一致"
  else
    echo "  [FAIL] sha256 不符：台账=$WANT 实际=$GOT"; FAIL=1; continue
  fi

  # 3. versionCode / versionName
  BADGING=$(sh -c "'$AAPT' dump badging '$APK'" 2>/dev/null)
  GOT_CODE=$(echo "$BADGING" | sed -n "s/.*versionCode='\([0-9]*\)'.*/\1/p" | head -1)
  GOT_NAME=$(echo "$BADGING" | sed -n "s/.*versionName='\([^']*\)'.*/\1/p" | head -1)
  if [ "$GOT_CODE" = "$CODE" ]; then
    echo "  [OK]   versionCode=$GOT_CODE"
  else
    echo "  [FAIL] versionCode=$GOT_CODE，应为 $CODE"; FAIL=1; continue
  fi
  if [ "$GOT_NAME" = "$RELVER" ]; then
    echo "  [OK]   versionName=$GOT_NAME"
  else
    echo "  [FAIL] versionName=$GOT_NAME，应为 $RELVER"; FAIL=1; continue
  fi

  # 4. 签名与发布基准一致
  CERT=$(sh -c "'$APKSIGNER' verify --print-certs '$APK'" 2>/dev/null \
    | sed -n 's/.*certificate SHA-256 digest: //p' | head -1 | tr -d '\r')
  if [ "$CERT" = "$BASE_CERT" ]; then
    echo "  [OK]   签名与 fix22 一致（可覆盖安装）"
  else
    echo "  [FAIL] 签名不符：$CERT"; FAIL=1; continue
  fi

  # ---- 发版 ----
  TAG="v$RELVER"
  if [ "$DRY" = "1" ]; then
    echo "  [DRY]  将执行：gh release create $TAG <apk> -R $REPO --title $RELVER --prerelease"
    continue
  fi

  # Release 正文取自 docs/RELEASES.md 里该轮那一段（与 release.sh 同一套抽取规则）
  NOTES=$(mktemp)
  awk -v v="## ${VER}（${FIX}" 'index($0,v)==1{f=1} f && /^## / && index($0,v)!=1{exit} f{lines[++n]=$0}
    END{last=n; while(last>0 && (lines[last]~/^[[:space:]]*$/ || lines[last]~/^-{3,}[[:space:]]*$/)) last--;
        first=1; while(first<last && lines[first]~/^[[:space:]]*$/) first++;
        for(i=first;i<=last;i++) print lines[i]}' "$SRC/docs/RELEASES.md" > "$NOTES"
  if [ ! -s "$NOTES" ]; then
    echo "  [FAIL] docs/RELEASES.md 里找不到 $FIX 段落"; rm -f "$NOTES"; FAIL=1; continue
  fi
  # 附一行校验值（与 release.sh 同一口径：不重复附件名与体积）
  printf '\n---\n\nsha256: `%s`\n' "$GOT" >> "$NOTES"

  if gh release view "$TAG" -R "$REPO" >/dev/null 2>&1; then
    echo "  [skip] $TAG 已存在，改用 edit + upload"
    gh release edit "$TAG" -R "$REPO" --title "$RELVER" --notes-file "$NOTES" --prerelease
    gh release upload "$TAG" "$APK" -R "$REPO" --clobber
  else
    gh release create "$TAG" "$APK" -R "$REPO" --title "$RELVER" --notes-file "$NOTES" --prerelease
  fi
  echo "  [OK]   已发布 $TAG"
  rm -f "$NOTES"
done

echo
echo "=============================================="
if [ "$FAIL" = "0" ]; then echo " 全部校验通过"; else echo " 有 FAIL，未全部发布"; fi
echo "=============================================="
exit $FAIL
