#!/bin/sh
# check-sync.sh —— 一致性体检（电脑 / 手机都能跑，纯 POSIX sh）
#
# 查三件事：
#   1. 版本号单一来源：gradle.properties 的 dshVersion == 文档里声明的「当前版本」== APK 产物文件名
#   2. 仓库 status 是否干净
#   3. 是否与远端同步
#
# 用法：sh check-sync.sh [--offline]     （--offline 跳过第 3 项，不联网）
# 退出码：0 = 全部通过；1 = 有 FAIL
#
# ★ 2026-11-23：快照仓已删除（作者决定）。本脚本原来是「两仓体检」，现在只体检
#   **源码仓**。内部文档（BUGLEDGER / DEV-PLAN / LESSONS / reports 等）已移到
#   `D:\Projects\_dsh-internal-docs`，不在任何仓库里，本脚本不再涉及它们。
set -u

ROOT=$(cd "$(dirname "$0")/.." && pwd)
if [ -n "${SRC:-}" ]; then SRC="$SRC"; elif [ -f "$ROOT/settings.gradle.kts" ]; then SRC="$ROOT"; else SRC="$ROOT/dsh-fcl-android-launcher"; fi
[ -d "$SRC" ] || { echo "!! 找不到源码仓库：$SRC（用 SRC=<路径> 指定）"; exit 1; }

FAIL=0
ok()   { echo "  [OK]   $1"; }
bad()  { echo "  [FAIL] $1"; FAIL=1; }
warn() { echo "  [WARN] $1"; }

OFFLINE=0
[ "${1:-}" = "--offline" ] && OFFLINE=1

echo "=============================================="
echo " 1. 版本号单一来源"
echo "=============================================="
PROPS="$SRC/gradle.properties"
VER=$(sed -n 's/^dshVersion=//p' "$PROPS" 2>/dev/null | tr -d '\r' | head -1)
CODE=$(sed -n 's/^dshVersionCode=//p' "$PROPS" 2>/dev/null | tr -d '\r' | head -1)
if [ -n "$VER" ]; then ok "gradle.properties: dshVersion=$VER (code=$CODE)"; else bad "gradle.properties 缺少 dshVersion="; VER="?"; fi

# 文档里声明的版本号：只在「声明处」比对，不在历史记录里比对
#   声明处 = README.md / README-EN.md 的 badge、docs/RELEASES.md 的 `## <版本>` 标题
# ★ 2026-11-23 修：去掉了 docs/INDEX.md 与 docs/CHANGELOG.md 两条。
#   这两个文件是**内部文档**，已移到 D:\Projects\_dsh-internal-docs，在本仓库既不存在
#   （`ls` 确认 no such file）也不被 Git 跟踪，同时已列进 ws-tools/exclude.source-repo。
#   保留它们只会每跑一次体检就打两行「缺少 …」的假噪音，掩盖真正的问题。
# ★ 反过来也要守住：以后谁把声明版本号的文档移出/改名，必须同时迁移这里的校验，
#   否则版本号就失去了一处守卫 —— 那正是本脚本存在的唯一理由。
check_decl() {
  f="$1"; pat="$2"; label="$3"
  [ -f "$f" ] || { warn "缺少 $f"; return; }
  got=$(sed -n "$pat" "$f" | head -1 | tr -d '\r')
  # 基础版本比对：`0.1.6` 与 `0.1.6-SNAPSHOT` 视为一致（标题写基础版本 + 构建编号，
  # 而 dshVersion 带 `-SNAPSHOT` 预发布后缀。两者是同一个版本线。）
  got_base=$(echo "$got" | sed -n 's/-SNAPSHOT$//p'); [ -n "$got_base" ] || got_base="$got"
  ver_base=$(echo "$VER" | sed -n 's/-SNAPSHOT$//p'); [ -n "$ver_base" ] || ver_base="$VER"
  if [ -z "$got" ]; then warn "$label：没找到声明行（模式不匹配？）"
  elif [ "$got" = "$VER" ] || [ "$got_base" = "$ver_base" ]; then ok "$label = $got"
  else bad "$label = $got（应为 $VER）"; fi
}
# docs/RELEASES.md 是**面向用户**的发布说明（会推到公开仓），最新那版必须与真源一致。
# 格式是 `## <版本>` 开头的二级标题；取第一个匹配（文件按"最新在最上面"排）。
# ★ fix 后缀豁免：最新段的标题是产物版本串 `## 0.1.6-SNAPSHOT-fix26`，而本脚本校验的
#   `dshVersion` 是 `0.1.6-SNAPSHOT`（fix 包不升 dshVersion，只递增 code，这是故意的）。
#   所以比对时剥掉 `-fixN` 尾巴，只比版本主体。
check_decl "$SRC/docs/RELEASES.md" 's/^## \([0-9][0-9.]*\(-SNAPSHOT\)\?\).*/\1/p'              "docs/RELEASES.md 最新版本"
# shields.io 的 badge 把 `-` 转义成 `--`，比对时要按同样规则转换
BADGE_VER=$(echo "$VER" | sed 's/-/--/g')
# 两版 README 都要查：只查中文版的话，英文版的 badge 会无声漂移
# （README-EN.md 是对外面向英文用户的主入口，同样是"版本声明处"）
for _readme in "$SRC/README.md" "$SRC/README-EN.md"; do
  _name=$(basename "$_readme")
  if [ ! -f "$_readme" ]; then
    bad "$_name 不存在（中英双语入口是成对的，缺一个会让另一种语言的读者断链）"
  elif grep -q "badge/version-$BADGE_VER" "$_readme" 2>/dev/null; then
    ok "$_name badge = $VER"
  else
    bad "$_name badge 与 $VER 不一致（badge 里应写 version-$BADGE_VER）"
  fi
done
# 语言互链必须成对：缺一边就会出现"点进去回不来"的单向链接
if [ -f "$SRC/README.md" ] && [ -f "$SRC/README-EN.md" ]; then
  _fwd=""; _back=""
  grep -q '(README-EN.md)' "$SRC/README.md" 2>/dev/null && _fwd=1
  grep -q '(README.md)' "$SRC/README-EN.md" 2>/dev/null && _back=1
  if [ -n "$_fwd" ] && [ -n "$_back" ]; then
    ok "中英 README 互链成对"
  else
    [ -z "$_fwd" ] && bad "README.md 缺少指向 README-EN.md 的语言入口"
    [ -z "$_back" ] && bad "README-EN.md 缺少指回 README.md 的语言入口"
  fi
fi
# 其余公开文档同样必须中英成对 —— 只加一边会让另一种语言的读者撞 404。
# 名单新增文档时要同步加到这里，否则新文档的中英配对无人看守。
for _base in SECURITY.md \
             CONTRIBUTING.md \
             docs/README.md \
             docs/PLAN.md \
             docs/ROADMAP.md \
             docs/TASKS.md \
             docs/PACKAGING.md \
             docs/ROOTFS.md \
             docs/ENVIRONMENT.md \
             docs/RELEASES.md; do
  _zh="$SRC/$_base"
  _en="$SRC/${_base%.md}-EN.md"
  if [ ! -f "$_zh" ]; then
    bad "缺少中文文档 $_base（配对检查的前提）"
  elif [ ! -f "$_en" ]; then
    bad "${_base%.md}-EN.md 不存在（$_base 有英文阅读者，英文版不能缺）"
  elif ! head -1 "$_en" | grep -q "($(basename "$_base"))"; then
    bad "${_base%.md}-EN.md 首行不是语言入口（应形如 [简体中文]($(basename "$_base")) | English）"
  elif ! head -1 "$_zh" | grep -q "($(basename "${_base%.md}")-EN.md)"; then
    bad "$_base 首行缺少指向英文版的入口"
  else
    ok "中英成对：$_base"
  fi
done

# ★ fix 标签 vs 公开发布：代码里的 dshFixLabel 可能已经跑到 fixN，而 Releases 页只发到更早的
#   fixM。这不是错误（fix 包本来就是先在真机验、后发布），但**必须让人一眼看见差值** ——
#   2026-11 就发生过：文档写"当前验证包 fix26"，而 Releases 最新只有 fix22，用户下不到。
#   这里只 WARN 不 FAIL：是否发布是人的决定，脚本不该替他决定。
FIXLBL=$(sed -n 's/^dshFixLabel=//p' "$PROPS" 2>/dev/null | tr -d '\r' | head -1)
if [ -n "$FIXLBL" ]; then
  # 只在能拿到远端时比对（离线检查跳过，避免把"查不到"报成"没发布"）
  if command -v git >/dev/null 2>&1; then
    _latest_tag=$(git -C "$SRC" tag --list 'v*-fix*' --sort=-v:refname 2>/dev/null | head -1 | tr -d '\r')
    if [ -n "$_latest_tag" ]; then
      _latest_fix=$(echo "$_latest_tag" | sed -n 's/.*-\(fix[0-9]*\)$/\1/p')
      if [ "$_latest_fix" = "$FIXLBL" ]; then
        ok "最新 tag $_latest_fix 与 dshFixLabel=$FIXLBL 一致"
      else
        warn "代码是 $FIXLBL，但最新 tag 只到 ${_latest_fix:-（无）} —— $FIXLBL 尚未发布，文档不要说它是'当前验证包'"
      fi
    fi
  fi
fi

echo
echo "=============================================="
echo " 2. 仓库 status"
echo "=============================================="
SRC_ST=$(git -C "$SRC" status --porcelain | tr -d '\r')
if [ -z "$SRC_ST" ]; then ok "源码仓干净"; else bad "源码仓有未提交改动："; echo "$SRC_ST" | sed 's/^/         /'; fi

if [ "$OFFLINE" = "0" ]; then
  echo
  echo "=============================================="
  echo " 3. 与远端是否同步"
  echo "=============================================="
  L=$(git -C "$SRC" rev-parse HEAD 2>/dev/null | tr -d '\r')
  R=$(git -C "$SRC" ls-remote origin main 2>/dev/null | awk '{print $1}' | tr -d '\r')
  if [ -z "$R" ]; then warn "拿不到远端（离线？）"
  elif [ "$L" = "$R" ]; then ok "源码仓 与 origin/main 一致（$(echo "$L" | cut -c1-7)）"
  else bad "源码仓 本地 $(echo "$L" | cut -c1-7) ≠ 远端 $(echo "$R" | cut -c1-7)（需要 push 或 pull）"; fi
fi

echo
echo "=============================================="
if [ "$FAIL" = "0" ]; then echo " 结果：全部通过 ✅"; else echo " 结果：有 FAIL ❌（见上）"; fi
echo "=============================================="
exit $FAIL
