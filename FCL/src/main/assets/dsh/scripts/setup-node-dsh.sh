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
# 机器可读输出：`[setup] STAGE=...`、`[setup] DONE version=... source=...`；
# 失败一律 `[setup] FAILED reason=...` 并以非 0 退出（启动器据此判定 BROKEN）。
set -eu

# 预装 Node 进 PATH（rootfs 内 node/npm 在 /opt/node22/bin）
PATH="/opt/node22/bin:$PATH"
export PATH

INSTANCE_DIR="${INSTANCE_DIR:-$PWD}"
DSH_VERSION="${DSH_VERSION:-latest}"
DSH_PREINSTALL_DIR="${DSH_PREINSTALL_DIR:-/opt/dsh-preinstalled}"
NODE_MAJOR="${NODE_MAJOR:-22}"

mkdir -p "$INSTANCE_DIR"

stage() { echo "[setup] STAGE=$*"; }

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
  stage "已有可用 Node $(node -v)"
else
  if node_ok && ! npm_ok; then
    # node 有但 npm 没有：单独补 npm，避免走整包安装
    stage "补充 npm"
    if [ "$LIBC" = "musl" ]; then
      apk add --no-cache npm >/dev/null 2>&1 || true
    else
      apt-get update -qq >/dev/null 2>&1 || true
      apt-get install -y -qq npm >/dev/null 2>&1 || true
    fi
  fi

  if ! node_ok; then
    stage "安装 Node $NODE_MAJOR（首次较慢）"
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
    echo "[setup] FAILED reason=node-version（当前 $(node -v 2>/dev/null || echo '无 node')，要求 ^22.19 || >=24）" >&2
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
        stage "使用 rootfs 预装 dsh $PRE_VER（跳过下载）"
        echo "[setup] DONE version=$PRE_VER source=preinstalled"
        exit 0
        ;;
    esac
    echo "[setup] 预装版本 $PRE_VER ≠ 请求版本 $DSH_VERSION，将安装到实例目录"
  fi
fi

# --- 安装指定版本的 dsh 到实例目录 ---------------------------------------
cd "$INSTANCE_DIR"
[ -f package.json ] || npm init -y >/dev/null 2>&1

if [ -n "${NPM_CONFIG_CACHE:-}" ]; then
  mkdir -p "$NPM_CONFIG_CACHE"
  echo "[setup] npm cache: $NPM_CONFIG_CACHE"
fi

stage "下载并安装 @deepseek-ai/dsh@$DSH_VERSION"
echo "[setup] 安装 @deepseek-ai/dsh@$DSH_VERSION 到 $INSTANCE_DIR ..."
# --prefer-offline：命中缓存时不打网络
npm install --no-fund --no-audit --prefer-offline "@deepseek-ai/dsh@$DSH_VERSION"

stage "校验安装结果"
INSTALLED="$(node -e "console.log(require('./node_modules/@deepseek-ai/dsh/package.json').version)" 2>/dev/null || true)"
if [ -z "$INSTALLED" ]; then
  echo "[setup] FAILED reason=verify-version" >&2
  exit 1
fi
if [ ! -f "node_modules/@deepseek-ai/dsh/lib/bin.js" ]; then
  echo "[setup] FAILED reason=missing-bin-js" >&2
  exit 1
fi

echo "[setup] DONE version=$INSTALLED source=instance"
echo "[setup] node_modules 体积: $(du -sh node_modules 2>/dev/null | cut -f1)"
