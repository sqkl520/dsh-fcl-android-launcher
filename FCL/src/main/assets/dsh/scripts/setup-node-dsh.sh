#!/bin/sh
# setup-node-dsh.sh — 在 rootfs 内部准备 Node 运行时并安装一个指定版本的 dsh 实例
# 运行环境：proot 内的 Linux 用户态（Debian/Ubuntu=glibc 或 Alpine=musl，arm64）
#
# 做两件事：
#   1) 确保有满足 dsh 要求的 Node（^22.19 || >=24）与 npm
#   2) 把指定版本的 @deepseek-ai/dsh 装进 INSTANCE_DIR（独立 node_modules，便于多版本隔离）
#
# 用法:
#   INSTANCE_DIR=/opt/dsh/instances/default DSH_VERSION=latest ./setup-node-dsh.sh
#
# 环境变量:
#   INSTANCE_DIR    实例目录，默认 $PWD
#   DSH_VERSION     dsh 版本号，默认 latest（可填 0.1.5-rc.2 等具体版本）
#   NODE_MAJOR      需要安装 Node 时用的主版本，默认 22
#   NPM_CONFIG_CACHE npm 缓存目录（启动器指向 <filesDir>/dsh/npm-cache，跨实例复用，
#                    重复安装不必重新下载整棵依赖树）
#
# ## 本次改造（对应审查发现的问题）
# 1. **机器可读进度**：每个阶段打印 `[setup] STAGE=...`，启动器据此在界面上显示进度，
#    而不是把 npm 的输出原样丢给用户（原来界面只有一句"安装中"）。
# 2. **npm 存在性检查**：原来只检查 `node` 在不在，某些精简 rootfs 里 node 有而 npm 没有，
#    结果是在 `npm install` 处抛一句 "npm: not found" 让人一脸问号。
# 3. **显式 CI 模式与日志友好参数**：`--no-fund --no-audit --prefer-offline`，
#    减少无谓的网络与刷屏输出。
# 4. **版本校验硬失败**：装完必须能解析出 dsh 版本，否则以非 0 退出（启动器据此判定 BROKEN），
#    而不是打印一句"完成"让上层以为成功了。
#
# ## 可移植性修复（重要）
# 启动器用 `/bin/sh <script>` 调用本脚本（见 ProotCommand），Debian/Ubuntu 的 `/bin/sh` 是 dash，
# 不支持 `pipefail`。原来的 `#!/usr/bin/env bash` + `set -euo pipefail` 会在 dash 下第 1 行就报错退出，
# 导致安装在真机上直接失败。改成严格 POSIX sh（`set -eu`，去掉 pipefail）。
set -eu

INSTANCE_DIR="${INSTANCE_DIR:-$PWD}"
DSH_VERSION="${DSH_VERSION:-latest}"
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
      # Alpine：社区源里的 nodejs/npm；注意版本要够新，必要时启用 edge/community
      apk add --no-cache nodejs npm >/dev/null
    else
      # Debian/Ubuntu：走 NodeSource 拿到 22.x
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

# --- 安装指定版本的 dsh 到实例目录 ---------------------------------------
cd "$INSTANCE_DIR"
[ -f package.json ] || npm init -y >/dev/null 2>&1

if [ -n "${NPM_CONFIG_CACHE:-}" ]; then
  mkdir -p "$NPM_CONFIG_CACHE"
  echo "[setup] npm cache: $NPM_CONFIG_CACHE"
fi

stage "下载并安装 @deepseek-ai/dsh@$DSH_VERSION"
echo "[setup] 安装 @deepseek-ai/dsh@$DSH_VERSION 到 $INSTANCE_DIR ..."
# --prefer-offline：命中缓存时不打网络；CI=1 让 npm 输出更适合日志
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

echo "[setup] DONE version=$INSTALLED"
echo "[setup] 已完成。已安装 dsh 版本: $INSTALLED"
echo "[setup] node_modules 体积: $(du -sh node_modules 2>/dev/null | cut -f1)"
echo "[setup] 下一步: 启动实例（密钥由启动器通过环境变量注入，无需落盘）"
