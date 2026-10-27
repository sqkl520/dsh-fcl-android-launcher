#!/bin/sh
# setup-node-dsh.sh — 在 rootfs 内部准备 Node 运行时，并为一个实例准备 dsh
# 运行环境：proot 内的 Linux 用户态（Debian=glibc，arm64）
#
# 做三件事：
#   1) 确保有满足 dsh 要求的 Node（^22.19 || >=24）与 npm
#      —— 方案 B 的 rootfs 已预装官方 Node（/opt/node22/bin），通常直接命中
#   2) 若请求版本与 rootfs 内预装版本一致、且实例还没有自己的 node_modules，
#      **直接把预装版本认作本实例的 dsh**（离线可用、不重复下载 ~500MB）
#   3) 否则把指定版本的 @deepseek-ai/dsh 装进 INSTANCE_DIR（独立 node_modules，多版本隔离）
#
# 用法:
#   INSTANCE_DIR=/opt/dsh/instances/default DSH_VERSION=0.1.6-alpha.2 ./setup-node-dsh.sh
#
# 环境变量:
#   INSTANCE_DIR       实例目录，默认 $PWD
#   DSH_VERSION        dsh 版本号，默认 latest
#   DSH_PREINSTALL_DIR dsh 预装目录（rootfs 内），默认 /opt/dsh-preinstalled
#   NODE_MAJOR         需要安装 Node 时用的主版本，默认 22
#   NPM_CONFIG_CACHE   npm 缓存目录（启动器指向 <filesDir>/dsh/npm-cache，跨实例复用）
#
# 机器可读输出：`[setup] STAGE=<key>[ <args>]`、`[setup] DONE version=... source=...`；
# 失败一律 `[setup] FAILED reason=...` 并以非 0 退出（启动器据此判定 BROKEN）。
set -eu

# Interpreter-wide EXIT trap (BL-108): `set -e` aborts on the first failing command
# (npm init / npm install / apt-get ...) without printing any FAILED line, so the launcher
# only ever saw a bare non-zero exit and fell back to its generic "install failed" text.
# This trap guarantees every abnormal exit still carries the machine-readable reason line
# promised in the header comment. `$?` is read first thing, before anything else can clobber it.
# No other trap exists in this script (verified: only `trap` mentions are the stage-key docs);
# the trap is thus registered here once and never overwritten.
_on_exit() {
  rc=$?
  if [ "$rc" -ne 0 ]; then
    echo "[setup] FAILED reason=unexpected-exit code=$rc" >&2
  fi
}
trap _on_exit EXIT

# 预装 Node 进 PATH（rootfs 内 node/npm 在 /opt/node22/bin）
PATH="/opt/node22/bin:$PATH"
export PATH

# apt-get must never block on an interactive prompt (K35): without a noninteractive frontend a
# debconf dialog (e.g. tzdata) or a config-file conflict waits for stdin that never comes, so the
# whole install hangs until the 30-minute watchdog kills it. All apt-get calls below inherit this.
# (apk ignores it on musl rootfs; the apt install lines already pass -y.)
DEBIAN_FRONTEND=noninteractive
export DEBIAN_FRONTEND

INSTANCE_DIR="${INSTANCE_DIR:-$PWD}"
DSH_VERSION="${DSH_VERSION:-latest}"
DSH_PREINSTALL_DIR="${DSH_PREINSTALL_DIR:-/opt/dsh-preinstalled}"
NODE_MAJOR="${NODE_MAJOR:-22}"

mkdir -p "$INSTANCE_DIR"

# 阶段标记：`[setup] STAGE=<key>[ <args>]`。
# ★ 第一个词是**稳定的 key**（小写 ASCII、kebab-case），其余部分是它的参数。
#   为什么 key 偏偏写成"读起来像英文短语"的样子（installing / verifying-install）
#   而不是纯符号（stage_install / S3）：启动器侧不认识某个 key 时会**原样显示整行**，
#   那时 `installing @deepseek-ai/dsh@0.2.1-alpha.1` 至少还是一句英文，
#   而 `S3 v=0.2.1-alpha.1` 就成了一行机器码。详见 DshUiText.stage() 的兜底规则。
stage() {
  stage_key="${1:-unknown}"
  shift || true          # 位置参数已空时 shift 会返回非 0，set -e 下必须兜住
  if [ "$#" -gt 0 ]; then
    echo "[setup] STAGE=$stage_key $*"
  else
    echo "[setup] STAGE=$stage_key"
  fi
}


# --- 检测发行版/libc ------------------------------------------------------
LIBC="glibc"
if [ -f /etc/alpine-release ]; then LIBC="musl"; fi
echo "[setup] libc=$LIBC arch=$(uname -m) instance=$INSTANCE_DIR version=$DSH_VERSION"

# --- 确保 Node 满足版本要求 ----------------------------------------------
node_ok() {
  command -v node >/dev/null 2>&1 || return 1
  node -e 'const v=process.versions.node.split(".").map(Number);
    process.exit(((v[0]===22&&v[1]>=19)||v[0]>=24)?0:1)' 2>/dev/null
}

npm_ok() {
  command -v npm >/dev/null 2>&1
}

preinstall_ver() {
  p="$DSH_PREINSTALL_DIR/node_modules/@deepseek-ai/dsh/package.json"
  [ -f "$p" ] || return 1
  node -e "try{process.stdout.write(require('$p').version)}catch(e){process.exit(1)}" 2>/dev/null
}

if node_ok && npm_ok; then
  stage "node-ready" "$(node -v)"
else
  if node_ok && ! npm_ok; then
    # node 有但 npm 没有：单独补 npm，避免走整包安装
    stage "adding-npm"
    if [ "$LIBC" = "musl" ]; then
      apk add --no-cache npm >/dev/null 2>&1 || true
    else
      apt-get update -qq >/dev/null 2>&1 || true
      apt-get install -y -qq npm >/dev/null 2>&1 || true
    fi
  fi

  if ! node_ok; then
    stage "installing-node" "$NODE_MAJOR"
    if [ "$LIBC" = "musl" ]; then
      apk add --no-cache nodejs npm >/dev/null
    else
      apt-get update -qq >/dev/null
      apt-get install -y -qq curl ca-certificates >/dev/null
      curl -fsSL "https://deb.nodesource.com/setup_${NODE_MAJOR}.x" | bash - >/dev/null 2>&1
      apt-get install -y -qq nodejs >/dev/null
    fi
  fi

  if ! node_ok; then
    echo "[setup] FAILED reason=node-version node=$(node -v 2>/dev/null || echo none) required=^22.19||>=24" >&2
    exit 1
  fi
  echo "[setup] Node OK: $(node -v)"
fi

if ! npm_ok; then
  echo "[setup] FAILED reason=npm-missing" >&2
  exit 1
fi

# --- 预装版本命中则跳过安装（稳定性优先：离线可用、不重复下载）-------------
INSTANCE_BIN="$INSTANCE_DIR/node_modules/@deepseek-ai/dsh/lib/bin.js"
if [ ! -f "$INSTANCE_BIN" ]; then
  PRE_VER="$(preinstall_ver || true)"
  if [ -n "${PRE_VER:-}" ]; then
    case "$DSH_VERSION" in
      latest|"$PRE_VER")
        stage "using-preinstalled-dsh" "$PRE_VER"
        # BL-191：命中预装时也明确说一句"用的是哪一份"，否则日志里只有 DONE 行，
        # 看不出这次安装到底有没有下载（现场排查"为什么装得慢"的第一分叉点）。
        echo "[setup] using preinstalled dsh $PRE_VER"
        echo "[setup] DONE version=$PRE_VER source=preinstalled"
        exit 0
        ;;
    esac
    # BL-191：预装未命中 —— 这是现网默认路径（下载页恒传精确版本），接下来是 proot 内
    # 全量 npm install（自估 ~500 MB、数分钟起）。旧版只有下面那句"installing into the
    # instance directory"，用户/日志读者看不出"rootfs 里那 420 MB 预装为什么没被用上"。
    # 一行说全两件事（否则两行在节流窗口内会被合并成 pending，用户只看到一句半）：
    # `[setup] preinstalled` 前缀保留 —— E6 的现场取证（EXP-18）按这个关键字搜日志页。
    echo "[setup] preinstalled dsh $PRE_VER != requested $DSH_VERSION, installing into the instance directory (full npm install, ~500 MB)"
  fi
fi

# --- 安装指定版本的 dsh 到实例目录 ---------------------------------------
cd "$INSTANCE_DIR"
[ -f package.json ] || npm init -y >/dev/null 2>&1

if [ -n "${NPM_CONFIG_CACHE:-}" ]; then
  mkdir -p "$NPM_CONFIG_CACHE"
  echo "[setup] npm cache: $NPM_CONFIG_CACHE"
fi

stage "installing" "@deepseek-ai/dsh@$DSH_VERSION"
echo "[setup] installing @deepseek-ai/dsh@$DSH_VERSION into $INSTANCE_DIR ..."
# --prefer-offline：命中缓存时不打网络
# --loglevel=http（R20）：npm 在非 TTY 下默认**一行不输出**，下载依赖的几分钟里整个界面
# 没有任何东西在动（真机截图实锤："看起来像死机"）。http 级每抓一个 URL 打一行
# `npm http fetch GET 200 <url> <ms>`；启动器侧把它提炼成"实时活动行"显示在卡片/任务区
# （DshInstaller 的 InstallStatus.activity），用户能看着它滚动 —— 与部署页文件路径滚动同观感。
#
# ★ R20 自愈链（两趟）：npm 在"缓存/tree 元数据认为已完整、实际包内容半装"时会 exit 0 直接跳过
#   （真机实锤：js-yaml/dist/js-yaml.mjs 缺失、npm 认为一切正常）。第一趟后跑启动冒烟测试；
#   失败则**删掉 node_modules 重装第二趟**（--prefer-online 强制校验，不吃坏缓存）；
#   第二趟再失败才判 FAILED。为什么不让启动器侧重试：清树重装是脚本自己的领域知识，
#   且重装耗时可长（分钟级），放到安装流程内部走同一套 STAGE/进度反馈最自然。
npm_install_pass() {
  npm install --no-fund --no-audit --prefer-offline --loglevel=http "@deepseek-ai/dsh@$DSH_VERSION"
}
npm_install_pass

stage "verifying-install"
verify_tree() {
  INSTALLED="$(node -e "console.log(require('./node_modules/@deepseek-ai/dsh/package.json').version)" 2>/dev/null || true)"
  [ -n "$INSTALLED" ] || return 1
  [ -f "node_modules/@deepseek-ai/dsh/lib/bin.js" ] || return 1
  return 0
}
if ! verify_tree; then
  echo "[setup] FAILED reason=verify-version" >&2
  exit 1
fi

# --- 启动冒烟测试（R20 / fix7）：装完**实跑一次** `bin.js --version` -----------------------
# 为什么必须有（真机实锤，2026-10-19）：npm 退出码 0 但依赖树存在"半装"文件时（解压不完整 /
# 缓存损坏），上面的两条静态检查（package.json 版本 + bin.js 存在）**全过**，而 dsh 一启动
# 就死：真机日志 `ERR_MODULE_NOT_FOUND: .../js-yaml/dist/js-yaml.mjs`（@deepseek-ai/dsh-app-boot
# 静态 import js-yaml 的 ESM 入口，该文件在本机干净安装 100% 在；缺失=树坏了）。
# `--version` 是零副作用的真实启动路径：它要把整个 import 图 link 完才可能打印版本
# （本机实测：好树 exit 0 打印版本；删掉 js-yaml.mjs 后 exit 1、报错与真机完全一致）。
# timeout 兜底：坏树在极端情况下可能挂在 link 上（本机未复现），30s 远超正常 ~1s。
# rootfs（Debian）带 coreutils 的 /usr/bin/timeout（已核对 rootfs.tar.xz 清单）。
smoke() {
  SMOKE_OUT="$(timeout 30 node node_modules/@deepseek-ai/dsh/lib/bin.js --version 2>&1)" || return 1
  return 0
}
if ! smoke; then
  # --- 门①（fix12 / C-14，只做门①）----------------------------------------
  # 冒烟测试本身依赖 rootfs 里的 coreutils `timeout`（见上面 smoke() 的注释）。它缺失时
  # smoke() 必然失败，但失败原因**不是树坏了** —— 此时 `rm -rf node_modules` 会删掉一棵
  # 好树、逼用户重下 ~500 MB，第二趟冒烟仍然会失败（缺的还是 timeout），白白多花流量与时间。
  # 所以：先确认冒烟的环境前提，缺就**不清树**、直接给出可归因的失败。
  # （门②"树损坏特征"判据本轮明确不做：ERR_MODULE_NOT_FOUND 在非损坏场景也会出现，
  #   误判会少一次自愈。）
  if ! command -v timeout >/dev/null 2>&1; then
    echo "[setup] FAILED reason=smoke-env detail=timeout-missing" >&2
    exit 1
  fi
  # 第一趟冒烟失败 → 清树重装（自愈）。
  echo "[setup] smoke test failed on pass 1; wiping node_modules and reinstalling once" >&2
  stage "reinstalling" "@deepseek-ai/dsh@$DSH_VERSION"
  rm -rf node_modules package-lock.json
  npm install --no-fund --no-audit --prefer-online --loglevel=http "@deepseek-ai/dsh@$DSH_VERSION"
  if ! verify_tree || ! smoke; then
    echo "[setup] smoke test failed; output tail:" >&2
    printf '%s\n' "$SMOKE_OUT" | tail -5 >&2
    # 把根因首行塞进 FAILED 行本身：失败摘要收集器（DshInstaller.errorTails）只认
    # `[setup] FAILED` 前缀与 npm 错误行，裸 node 堆栈收集不进去 —— 让最关键的一句
    # （如 `Error [ERR_MODULE_NOT_FOUND]: Cannot find module '…/js-yaml.mjs'`）随 FAILED 行进摘要。
    SMOKE_ERR="$(printf '%s\n' "$SMOKE_OUT" | grep -m1 -E 'Error|error' || true)"
    [ -n "$SMOKE_ERR" ] || SMOKE_ERR="$(printf '%s\n' "$SMOKE_OUT" | head -1)"
    echo "[setup] FAILED reason=smoke-test detail=$(printf '%s' "$SMOKE_ERR" | head -c 200)" >&2
    exit 1
  fi
fi
INSTALLED="$(node -e "console.log(require('./node_modules/@deepseek-ai/dsh/package.json').version)" 2>/dev/null || true)"
echo "[setup] smoke: bin.js --version -> $(printf '%s' "$SMOKE_OUT" | head -1)"

echo "[setup] DONE version=$INSTALLED source=instance"
echo "[setup] node_modules size: $(du -sh node_modules 2>/dev/null | cut -f1)"
