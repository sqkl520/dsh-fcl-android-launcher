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
#   READY_TIMEOUT      等待就绪的秒数，默认 120
#   NODE_PID_FILE      写 node 真实 pid 的文件，默认 $INSTANCE_DIR/dsh-node.pid
#                      （宿主侧 DshRuntime/ProcessUtil 按它精确终止 node，见下面"停止语义"）
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
#
# ## 停止语义（B10 修复说明：为什么必须把 node 的 pid 交给宿主）
# 启动器**只能**直接杀宿主侧的 proot 进程；rootfs 里的 node 是 proot 的**子进程**：
#   · kill(2) 只作用于单个 pid，不会连带子进程；
#   · proot 的 event loop 把除 SIGQUIT/SIGILL/SIGABRT/SIGFPE/SIGSEGV 之外的所有信号都装成
#     SIG_IGN（proot 源码 src/tracee/event.c: "Ignore all other signals, including
#     terminating ones (^C for instance)"），所以 **SIGTERM/INT/HUP 对 proot 完全无效**，
#     只有 SIGQUIT（异常路径）与 SIGKILL（不可捕获）能杀掉它；
#   · SIGKILL 下 proot 来不及做任何清理：它的 `atexit(kill_all_tracees)` 与 `--kill-on-exit`
#     都要求"proot 还活着、还在事件循环里"，于是 node 变孤儿、端口一直被占，
#     下次启动就报 EADDRINUSE。
#   · 本脚本的 `trap ... TERM` 在这条链路上**永远不会被触发**：启动器杀的是 proot 的 pid，
#     而本脚本跑在 proot 的另一个 pid（root tracee）上，根本收不到那个信号。
# 两条互相独立的修复（任一条生效都能带走 node）：
#   1) 本脚本把 node 的**真实 pid** 写进 NODE_PID_FILE（对外暴露成宿主路径
#      <filesDir>/dsh/instances/<id>/dsh-node.pid），启动器按 pid 精确终止；
#   2) 启动器在 SIGKILL 之前先给 proot 发 SIGQUIT —— proot 的 SIGQUIT 处理器会先
#      kill_all_tracees() 再走正常退出路径，等于"最后一刻仍把子进程带走"。
# proot **不**伪造 getpid（proot 源码 extension/fake_id0/sendmsg.c 写着
# "Pid is not changed as we don't fiddle with getpid()"），所以这里的 $! 就是宿主上的真实 pid，
# 启动器拿它 kill 是正确的；反过来，哪天 proot 开始伪造 pid，这条路径必须重做。
set -u

# 预装 Node 目录必须进 PATH：rootfs 内 node/npm 放在 /opt/node22/bin
# （ProotCommand 也会设置 PATH，这里再兜一层，避免手工调试时找不到 node）
PATH="/opt/node22/bin:$PATH"
export PATH

INSTANCE_DIR="${INSTANCE_DIR:-$PWD}"
DSH_PREINSTALL_DIR="${DSH_PREINSTALL_DIR:-/opt/dsh-preinstalled}"
PORT="${PORT:-3080}"
HOST="${HOST:-127.0.0.1}"
READY_TIMEOUT="${READY_TIMEOUT:-120}"

export DSH_HOME="${DSH_HOME:-$INSTANCE_DIR/home}"
mkdir -p "$DSH_HOME"

# --- 凭据（批次 5 起：本脚本不再碰它）-------------------------------------
# API Key 完全交给 dsh 自己：它在 $DSH_HOME/.credentials.yaml 里存，页面里改、页面里生效。
# 启动器也不再注入 DEEPSEEK_API_KEY（注进去会让 dsh 的写入被判为"被环境遮蔽"而直接报错），
# 所以这里既不需要读凭据文件、也无从判断"有没有配 Key"——那是 dsh 自己的状态。
# 需要非交互式喂 Key 的场景（CI / Termux）自己 export 到环境里即可，dsh 会读到。

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

# --- 端口就绪探测（用 node 的 net 模块，不依赖 bash 的 /dev/tcp，也不需要 curl/nc）--------
port_open() {
  node -e 'var n=require("net"),s=n.connect({host:process.argv[1],port:+process.argv[2]},function(){s.end();process.exit(0)});s.on("error",function(){process.exit(1)});s.setTimeout(1500,function(){s.destroy();process.exit(1)})' "$HOST" "$1" 2>/dev/null
}

# --- 端口预检：被占用时不要"直接失败"（B10 的第二半）------------------------
# 实例端口是"上次成功启动后回写进配置"的固定端口（自动端口也会被回写），下次启动直接复用。
# 一旦那个端口还被上次没退干净的 node、或被别的 App 占着，node 起来就会立刻
#   Error: listen EADDRINUSE: address already in use 127.0.0.1:<PORT>
# 整个进程随即退出，启动器只看到一句 "FAILED reason=node-exit code=1"，用户拿不到任何线索。
# 这里在启动前先探一次：被占用就**改用自动端口**（PORT=0，由系统挑一个空闲端口），
# 并打印机器可读的 WARN，让启动器回读真实端口写回配置（原本就有的回写逻辑会自动接管）。
# 代价：用户手工指定的固定端口在这种情况下会被换成一个随机端口（日志/界面显示的是新端口）；
# 换来的是"实例一定能起来"，而不是一句无信息的失败。
if [ "$PORT" != "0" ] && port_open "$PORT"; then
  echo "[start-dsh] WARN port-in-use port=$PORT fallback=auto"
  PORT=0
fi

echo "[start-dsh] node    : $(node -v)"
echo "[start-dsh] dsh bin : $BIN_JS"
[ -n "$DSH_VER" ] && echo "[start-dsh] dsh ver : $DSH_VER"
echo "[start-dsh] dsh src : $DSH_SRC"
echo "[start-dsh] listen  : http://$HOST:$PORT"
echo "[start-dsh] DSH_HOME: $DSH_HOME"
echo "[start-dsh] workdir : $PWD"

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

# 透传"新增"输出（按字节偏移，不截断日志，否则后面就没法再 grep URL）
OFFSET=0
flush_new() {
  [ -n "${LOGFILE:-}" ] || return 0
  [ -f "$LOGFILE" ] || return 0
  size=$(wc -c < "$LOGFILE" 2>/dev/null || true)
  case "$size" in
    ''|*[!0-9]*) size=0 ;;
  esac
  if [ "$size" -gt "$OFFSET" ]; then
    tail -c +"$((OFFSET + 1))" "$LOGFILE"
    OFFSET="$size"
  fi
}

# --- node pid 文件与信号处理（B10 修复第一条路径）--------------------------
NODE_PID=""
NODE_PID_FILE="${NODE_PID_FILE:-$INSTANCE_DIR/dsh-node.pid}"

# 收尾：把 node 收干净（TERM → 最多 $1 秒 → KILL），并清掉 pid / 日志文件。
# $1 省略时等 5s（正常停止）；就绪超时那条路径传 2，别让失败再多拖 5 秒。
terminate_node() {
  wait_secs="${1:-5}"
  case "$wait_secs" in
    ''|*[!0-9]*) wait_secs=5 ;;
  esac
  if [ -n "${NODE_PID:-}" ]; then
    kill -TERM "$NODE_PID" 2>/dev/null || true
    i=0
    while [ "$i" -lt "$wait_secs" ]; do
      kill -0 "$NODE_PID" 2>/dev/null || break
      sleep 1
      i=$((i + 1))
    done
    kill -KILL "$NODE_PID" 2>/dev/null || true
  fi
  rm -f "$NODE_PID_FILE" 2>/dev/null || true
  rm -f "${LOGFILE:-}" 2>/dev/null || true
}

# 被 TERM/INT 打到（Termux 里 Ctrl-C、宿主 proot-run.sh、或启动器"恰好"杀到了本 shell）时走这里。
# ★ 注意：启动器杀的是 **proot 自己**，不是本 shell，所以 App 场景下这条 trap 收不到那个信号 ——
#   它服务于"直接跑脚本"的场景（Termux / 调试 / 测试脚本），但必须是对的：脚本退出时不能留 node。
on_signal() {
  echo "[start-dsh] STOP signal=$1"
  flush_new
  terminate_node 5
  exit 0
}

# ★ trap 必须在 node 启动**之前**装上：否则 `node ... &` 与 `trap` 之间收到 TERM，
#   默认动作会直接杀掉本 shell，node 就成了没人管的孤儿（正是 B10 要消灭的东西）。
trap 'on_signal TERM' TERM
trap 'on_signal INT' INT

# --- 启动 node（后台）----------------------------------------------------
# 坑点(已验证): 默认 web profile 的 dsh-hmr 需要 node 带 --expose-internals，
# 且该 flag 不允许放进 NODE_OPTIONS，只能直接传给 node。
node --expose-internals "$BIN_JS" web --no-open --host "$HOST" --port "$PORT" >"$LOGFILE" 2>&1 &
NODE_PID=$!

# 把 node 的真实 pid（+本次实际请求的端口）写给宿主侧启动器：停止时它按这个 pid 精确终止。
# 失败不致命（脚本自己的 trap 仍能清理），但要明确告警——否则"停止后 node 残留"又会无迹可查。
if printf '%s %s\n' "$NODE_PID" "$PORT" > "$NODE_PID_FILE" 2>/dev/null; then
  echo "[start-dsh] node pid: $NODE_PID"
else
  echo "[start-dsh] 警告: 无法写 node pid 文件 $NODE_PID_FILE（停止时可能残留 node）" >&2
fi

URL_RE='http://[^ ]*token=[A-Za-z0-9_-]*'

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
    # 端口被占是"看起来莫名其妙的 node-exit code=1"最常见的原因（node 日志里必有 EADDRINUSE）。
    # 给出机器可读的原因，启动器据此显示"端口被占用"而不是一句 code=1。
    if grep -q 'EADDRINUSE' "$LOGFILE" 2>/dev/null; then
      echo "[start-dsh] FAILED reason=port-in-use port=$PORT code=${code:-1}"
    else
      echo "[start-dsh] FAILED reason=node-exit code=$code"
    fi
    rm -f "$NODE_PID_FILE" "$LOGFILE" 2>/dev/null || true
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
  # 超时：把 node 收掉再退出，避免半死进程占着端口（收尾同时会删掉 pid 文件）
  echo "[start-dsh] FAILED reason=ready-timeout after ${READY_TIMEOUT}s"
  if grep -q 'EADDRINUSE' "$LOGFILE" 2>/dev/null; then
    echo "[start-dsh] 提示: 日志里有 EADDRINUSE，端口 $PORT 被占用" >&2
  fi
  flush_new
  terminate_node 2
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
rm -f "$NODE_PID_FILE" "$LOGFILE" 2>/dev/null || true
exit "$code"
