#!/bin/sh
# start-dsh.sh — 在 rootfs 内部启动一个 dsh 实例的 web 服务
# 运行环境：proot 内的 Linux 用户态（glibc/Debian，arm64）
#
# 已在 aarch64 + Debian12 + glibc + patched proot + PROOT_LOADER 环境验证：
#   - proot 内 node / bash / child_process 正常
#   - dsh --version 可跑；web 服务在本机 WebView 下使用
#
# 用法:
#   INSTANCE_DIR=/opt/dsh/instances/default PORT=3080 ./start-dsh.sh
#
# 关键环境变量（都可选，有默认值）:
#   INSTANCE_DIR       实例根目录；若其下有 node_modules 则优先使用（多版本隔离）
#   DSH_PREINSTALL_DIR dsh 预装目录（rootfs 内），默认 /opt/dsh-preinstalled
#   PORT               监听端口，默认 3080；传 0 表示由系统分配
#   HOST               绑定地址，默认 127.0.0.1（给本机 WebView 用，别绑 0.0.0.0）
#   PROFILE            dsh profile，默认 web
#   CRED_FILE          存放 DEEPSEEK_API_KEY 的文件（KEY=VALUE）；App 走进程环境变量，不落盘。
#                      保留该分支是为了 Termux / 手工调试场景。
#   READY_TIMEOUT      等待就绪的秒数，默认 120
#
# ## dsh 入口解析顺序（方案 B 对齐）
#   1) 实例自己的 node_modules      → 支持"每实例装不同版本"（自定义性）
#   2) rootfs 内预装的 DSH_PREINSTALL_DIR → 首次可用、离线可用（稳定性优先）
#   3) 用 node 在 INSTANCE_DIR 里 require.resolve（兼容旧布局）
#   解析结果会打印 `[start-dsh] dsh src=...`，便于排查。
#
# ## 设计要点
# 后台起 node → 主动探测就绪（token URL 或端口）→ 打印 `[start-dsh] READY ...` →
# 前台跟随输出，收到 TERM/INT 转发给子进程；超时/早退打印 `FAILED reason=...` 并非 0 退出。
#
# ## 可移植性
# 严格 POSIX sh（启动器用 `/bin/sh <script>` 调用，Debian 上是 dash；dash 不支持 pipefail）。
# 端口探测用 node 的 net 模块（不依赖 bash 的 /dev/tcp，也不需要 curl/nc）。
set -u

# 预装 Node 目录必须进 PATH：rootfs 内 node/npm 放在 /opt/node22/bin
# （ProotCommand 也会设置 PATH，这里再兜一层，避免手工调试时找不到 node）
PATH="/opt/node22/bin:$PATH"
export PATH

INSTANCE_DIR="${INSTANCE_DIR:-$PWD}"
DSH_PREINSTALL_DIR="${DSH_PREINSTALL_DIR:-/opt/dsh-preinstalled}"
PORT="${PORT:-3080}"
HOST="${HOST:-127.0.0.1}"
CRED_FILE="${CRED_FILE:-$INSTANCE_DIR/credentials.env}"
READY_TIMEOUT="${READY_TIMEOUT:-120}"

export DSH_HOME="${DSH_HOME:-$INSTANCE_DIR/home}"
mkdir -p "$DSH_HOME"

# --- 载入凭据（仅在未通过环境变量提供时）-----------------------------------
# 优先级：进程环境变量（App 走这条，明文不落盘） > CRED_FILE（手工调试用）
if [ -z "${DEEPSEEK_API_KEY:-}" ] && [ -f "$CRED_FILE" ]; then
  while IFS='=' read -r k v; do
    case "$k" in
      DEEPSEEK_API_KEY|DEEPSEEK_BASE_URL|DEEPSEEK_MODEL|DEEPSEEK_DEFAULT_MODEL)
        export "$k=$v" ;;
    esac
  done < "$CRED_FILE"
fi

if [ -z "${DEEPSEEK_API_KEY:-}" ]; then
  echo "[start-dsh] 警告: 未检测到 DEEPSEEK_API_KEY。UI 能起，但对话会失败。" >&2
fi

# 原生模块加载器（node-addon-native-custom-loader）默认会先把 .node
# **硬链接**到 os.tmpdir() 下的缓存目录再 require。
# 在部分文件系统上该硬链接会失败，表现为：
#   EINVAL: invalid argument, readlink '.../native-cache/....node'
#   → Error: No usable native binding found for node-addon-require-builtin-...
# 我们的 .node 本来就在可 dlopen 的目录里，直接关掉这层缓存最稳。
NARB_DISABLE_NATIVE_CACHE="${NARB_DISABLE_NATIVE_CACHE:-1}"
export NARB_DISABLE_NATIVE_CACHE

# --- 定位 dsh 入口 bin.js --------------------------------------------------
DSH_REL="node_modules/@deepseek-ai/dsh/lib/bin.js"
BIN_JS=""
DSH_SRC=""
if [ -f "$INSTANCE_DIR/$DSH_REL" ]; then
  BIN_JS="$INSTANCE_DIR/$DSH_REL"
  DSH_SRC="instance"
elif [ -f "$DSH_PREINSTALL_DIR/$DSH_REL" ]; then
  BIN_JS="$DSH_PREINSTALL_DIR/$DSH_REL"
  DSH_SRC="preinstalled"
else
  # 兼容旧布局：用 node 自己解析（需要可用的 node）
  RESOLVED="$(cd "$INSTANCE_DIR" 2>/dev/null && node -e "process.stdout.write(require.resolve('@deepseek-ai/dsh/lib/bin.js'))" 2>/dev/null || true)"
  if [ -n "$RESOLVED" ] && [ -f "$RESOLVED" ]; then
    BIN_JS="$RESOLVED"
    DSH_SRC="resolved"
  fi
fi

if [ -z "$BIN_JS" ]; then
  echo "[start-dsh] FAILED reason=package-missing"
  echo "[start-dsh] 错误: 实例目录与预装目录都找不到 @deepseek-ai/dsh。" >&2
  echo "[start-dsh]   实例: $INSTANCE_DIR/$DSH_REL" >&2
  echo "[start-dsh]   预装: $DSH_PREINSTALL_DIR/$DSH_REL" >&2
  exit 1
fi

if ! command -v node >/dev/null 2>&1; then
  echo "[start-dsh] FAILED reason=node-missing"
  echo "[start-dsh] 错误: PATH 中找不到 node（期望 /opt/node22/bin/node）" >&2
  exit 1
fi

DSH_VER="$(node -e "try{process.stdout.write(require('$BIN_JS/../../package.json').version)}catch(e){}" 2>/dev/null || true)"

echo "[start-dsh] node    : $(node -v)"
echo "[start-dsh] dsh bin : $BIN_JS"
[ -n "$DSH_VER" ] && echo "[start-dsh] dsh ver : $DSH_VER"
echo "[start-dsh] dsh src : $DSH_SRC"
echo "[start-dsh] listen  : http://$HOST:$PORT"
echo "[start-dsh] DSH_HOME: $DSH_HOME"
echo "[start-dsh] workdir : $PWD"

# --- 端口就绪探测（用 node 的 net 模块，不依赖 bash 的 /dev/tcp，也不需要 curl/nc）--------
port_open() {
  node -e 'var n=require("net"),s=n.connect({host:process.argv[1],port:+process.argv[2]},function(){s.end();process.exit(0)});s.on("error",function(){process.exit(1)});s.setTimeout(1500,function(){s.destroy();process.exit(1)})' "$HOST" "$1" 2>/dev/null
}

# --- 启动 node（后台）----------------------------------------------------
# 坑点(已验证): 默认 web profile 的 dsh-hmr 需要 node 带 --expose-internals，
# 且该 flag 不允许放进 NODE_OPTIONS，只能直接传给 node。
LOGFILE="${TMPDIR:-/tmp}/dsh-start-$$.log"
if ! : > "$LOGFILE" 2>/dev/null; then
  # /tmp（宿主 cacheDir）不可写时退到实例目录；都不可写就明确失败，
  # 而不是让 node 的输出丢掉、就绪检测永远等不到（表现为"启动超时"，无从查起）
  LOGFILE="$INSTANCE_DIR/dsh-start-$$.log"
  if ! : > "$LOGFILE" 2>/dev/null; then
    echo "[start-dsh] FAILED reason=no-writable-logdir"
    exit 1
  fi
fi
node --expose-internals "$BIN_JS" web --no-open --host "$HOST" --port "$PORT" >"$LOGFILE" 2>&1 &
NODE_PID=$!

# 退出/被杀时把信号转发给 node，避免留下孤儿进程
forward() {
  kill -TERM "$NODE_PID" 2>/dev/null || true
  wait "$NODE_PID" 2>/dev/null || true
  rm -f "$LOGFILE" 2>/dev/null || true
  exit 0
}
trap forward TERM INT

URL_RE='http://[^ ]*token=[A-Za-z0-9_-]*'
OFFSET=0

# 透传"新增"输出（按字节偏移，不截断日志，否则后面就没法再 grep URL）
flush_new() {
  size=$(wc -c < "$LOGFILE" 2>/dev/null || echo 0)
  if [ "$size" -gt "$OFFSET" ]; then
    tail -c +"$((OFFSET + 1))" "$LOGFILE"
    OFFSET="$size"
  fi
}

# 墙上时间计时：每轮里 port_open 最多等 1.5s，再加 sleep 1s，按"轮次"计数会让
# READY_TIMEOUT=165 实际拖到 250s+ —— 启动器看门狗（180s）会先到，于是失败原因变成模糊的
# "启动超时"，而不是脚本给出的明确原因。date 不可用时自动退化为按轮次近似。
now_epoch() {
  n=$(date +%s 2>/dev/null || true)
  case "$n" in
    ''|*[!0-9]*) echo "" ;;
    *) echo "$n" ;;
  esac
}

READY=0
READY_URL=""
elapsed=0
elapsed_epoch_start=$(now_epoch)
# 端口先于 token URL 出现是常态：dsh 的 HTTP server 先 bind，等插件树加载完才打印带 token 的 URL。
# 所以"就绪"的**首要**判据是日志里出现 token URL；只有在固定端口且端口已开、又迟迟等不到 URL 时，
# 才退化为"端口可用就算就绪"（此时由启动器用已保存的 cookie 访问）。
PORT_OPEN_SINCE=-1
URL_GRACE=20
while : ; do
  NOW=$(now_epoch)
  if [ -n "$NOW" ] && [ -n "$elapsed_epoch_start" ]; then
    elapsed=$((NOW - elapsed_epoch_start))
  fi
  [ "$elapsed" -lt "$READY_TIMEOUT" ] || break

  flush_new
  if ! kill -0 "$NODE_PID" 2>/dev/null; then
    flush_new
    wait "$NODE_PID" 2>/dev/null
    code=$?
    echo "[start-dsh] FAILED reason=node-exit code=$code"
    rm -f "$LOGFILE" 2>/dev/null || true
    exit "${code:-1}"
  fi

  READY_URL="$(grep -o "$URL_RE" "$LOGFILE" 2>/dev/null | head -1 || true)"
  if [ -n "$READY_URL" ]; then
    READY=1
    break
  fi

  if [ "$PORT" != "0" ] && port_open "$PORT"; then
    if [ "$PORT_OPEN_SINCE" -lt 0 ]; then
      PORT_OPEN_SINCE="$elapsed"
    elif [ $((elapsed - PORT_OPEN_SINCE)) -ge "$URL_GRACE" ]; then
      # 端口通了但没等到 URL（例如 dsh 换掉了日志格式）：仍算就绪，用已有 cookie 访问
      READY=1
      break
    fi
  else
    PORT_OPEN_SINCE=-1
  fi
  sleep 1
  if [ -z "$NOW" ]; then
    elapsed=$((elapsed + 1))   # date 不可用时的退化计时
  fi
done

if [ "$READY" -ne 1 ]; then
  # 超时：把 node 收掉再退出，避免半死进程占着端口
  echo "[start-dsh] FAILED reason=ready-timeout after ${READY_TIMEOUT}s"
  kill -TERM "$NODE_PID" 2>/dev/null || true
  sleep 2
  kill -KILL "$NODE_PID" 2>/dev/null || true
  flush_new
  rm -f "$LOGFILE" 2>/dev/null || true
  exit 1
fi

flush_new
echo "[start-dsh] READY port=$PORT"
if [ -n "$READY_URL" ]; then
  # 机器可读的就绪标记：启动器不必依赖第三方日志格式
  echo "[start-dsh] READY url=$READY_URL"
fi

# --- 前台跟随 node 输出直到退出 ------------------------------------------
while kill -0 "$NODE_PID" 2>/dev/null; do
  flush_new
  sleep 1
done
flush_new
wait "$NODE_PID" 2>/dev/null
code=$?
echo "[start-dsh] EXIT code=$code"
rm -f "$LOGFILE" 2>/dev/null || true
exit "$code"
