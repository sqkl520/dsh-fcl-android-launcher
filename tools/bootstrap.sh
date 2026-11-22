#!/bin/sh
# bootstrap.sh —— 在一台**新电脑**上重建工作区
#
# ## 2026-11-23 变更：从「双仓共用工作树」改为**单仓**
#
# 原来这个脚本重建的是「一个文件夹两个仓库」（源码仓 + 快照仓共用同一工作树）。
# 作者在 2026-11-23 决定删掉快照仓，所以：
#   · 只有源码仓（公开）克隆到目标目录；
#   · 内部文档（BUGLEDGER / DEV-PLAN / LESSONS / reports 等 398 个）已移到
#     `D:\Projects\_dsh-internal-docs`，**不在任何仓库里**，本脚本不再涉及它们；
#   · 原 `exclude.snapshot-repo` 与 `ws-git` 脚本一并作废。
#
# 用法：
#   sh bootstrap.sh <项目文件夹> [--repo <源码仓URL>]
#   sh bootstrap.sh --install-excludes      # 只重装源码仓的 info/exclude
#   sh bootstrap.sh --check                 # 只检查当前布局是否完整，不修改
#
# 例（电脑上，新机器）：
#   bash bootstrap.sh /d/Projects/dsh-fcl-android-launcher
#
# 做完之后还需要手工补两样（脚本会在结尾提示）：
#   · local.properties 里的 sdk.dir       （见 docs/ENVIRONMENT.md §1.2）
#   · FCL/src/main/assets/dsh/rootfs/rootfs.tar.xz（见 docs/ROOTFS.md）
set -eu

SRC_URL_DEFAULT="https://github.com/sqkl520/dsh-fcl-android-launcher.git"

die() { echo "!! $*" >&2; exit 1; }
say() { echo "== $*"; }

# ---- 参数 ----------------------------------------------------------------
MODE=init
case "${1:-}" in
  --install-excludes) MODE=excludes; shift ;;
  --check)            MODE=check;    shift ;;
  --help|-h) sed -n '2,22p' "$0"; exit 0 ;;
esac
TARGET="${1:-}"
SRC_URL="$SRC_URL_DEFAULT"
shift 2>/dev/null || true
while [ $# -gt 0 ]; do
  case "$1" in
    --repo) shift; SRC_URL="${1:?--repo 后面要跟 URL}" ;;
    *) die "未知参数：$1" ;;
  esac
  shift
done

# 规范 exclude 文件放在 ws-tools/ 里（工作区文件）
TOOLS=$(cd "$(dirname "$0")/.." && pwd)
EX_SRC="$TOOLS/ws-tools/exclude.source-repo"

install_excludes() {
  root="$1"
  [ -f "$EX_SRC" ]  || die "缺少 $EX_SRC"
  [ -d "$root/.git" ] || die "$root/.git 不存在（源码仓还没 clone？）"
  mkdir -p "$root/.git/info"
  # 保留 git 自己的样板注释头（前 6 行），其余用规范副本覆盖
  { sed -n '1,6p' "$root/.git/info/exclude" 2>/dev/null || true; echo; cat "$EX_SRC"; } > "$root/.git/info/exclude.new"
  mv -f "$root/.git/info/exclude.new" "$root/.git/info/exclude"
  say "已安装源码仓 info/exclude"
}

case "$MODE" in
excludes)
  install_excludes "$TOOLS"
  exit 0
  ;;
check)
  FAIL=0
  ok()  { echo "  [OK]   $1"; }
  no()  { echo "  [FAIL] $1"; FAIL=1; }
  say "检查布局：$TOOLS"
  [ -d "$TOOLS/.git" ] && ok "源码仓 .git 存在" || no "源码仓 .git 缺失"
  # 快照仓已删除；若还留着 .git-ws-snapshot 就提醒一下（不报 FAIL，避免误伤正在迁移的人）
  if [ -d "$TOOLS/.git-ws-snapshot" ]; then
    echo "  [WARN] .git-ws-snapshot 还在（快照仓已于 2026-11-23 决定删除，可手动移除）"
  fi
  grep -q '源码仓要忽略的路径' "$TOOLS/.git/info/exclude" 2>/dev/null \
    && ok "源码仓 info/exclude 已安装" || no "源码仓 info/exclude 未安装（跑 --install-excludes）"
  [ -f "$TOOLS/FCL/src/main/assets/dsh/rootfs/rootfs.tar.xz" ] && ok "rootfs.tar.xz 已就位" || no "rootfs.tar.xz 缺失（见 docs/ROOTFS.md）"
  [ -f "$TOOLS/local.properties" ] && ok "local.properties 已配置" || no "local.properties 缺失（见 docs/ENVIRONMENT.md §1.2）"
  echo
  [ "$FAIL" = "0" ] && echo "布局完整 ✅" || echo "布局不完整 ❌（缺的项见上）"
  exit $FAIL
  ;;
esac

# ---- 初始化模式 ----------------------------------------------------------
[ -n "$TARGET" ] || die "用法：sh bootstrap.sh <项目文件夹> [--repo URL]（或 --install-excludes / --check）"
command -v git >/dev/null 2>&1 || die "找不到 git"

mkdir -p "$TARGET"
TARGET=$(cd "$TARGET" && pwd)
if [ -n "$(ls -A "$TARGET" 2>/dev/null | grep -v '^\.git' || true)" ]; then
  die "$TARGET 不是空目录。要重建请先清空；已有工作树只是想补 exclude 就跑：sh bootstrap.sh --install-excludes"
fi

say "1/3 取源码仓 → $TARGET/.git"
git init -q -b main "$TARGET"
git -C "$TARGET" remote add origin "$SRC_URL"
git -C "$TARGET" fetch -q --depth 1 origin main
git -C "$TARGET" checkout -q -f -b main origin/main
git -C "$TARGET" branch -q --set-upstream-to=origin/main main 2>/dev/null || true
echo "   $(git -C "$TARGET" log --oneline -1)"

say "2/3 安装 info/exclude"
install_excludes "$TARGET"

say "3/3 验证"
(cd "$TARGET" && sh tools/check-sync.sh --offline || true)

cat <<EOF

============================================================
 完成。还差两样（都不进 Git，必须手工补）：
   1) $TARGET/local.properties   →  sdk.dir=D:/Android/Sdk   （ENVIRONMENT.md §1.2）
   2) rootfs.tar.xz（300MB）→ FCL/src/main/assets/dsh/rootfs/（ROOTFS.md；
      优先从 GitHub Release 里的 APK 无损取出）
 补完后跑：sh tools/bootstrap.sh --check

 内部文档（BUGLEDGER / DEV-PLAN / LESSONS / reports）不在仓库里，
 单独存放在：D:\Projects\_dsh-internal-docs
============================================================
EOF
