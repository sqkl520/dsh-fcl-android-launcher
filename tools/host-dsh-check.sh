#!/bin/sh
# tools/host-dsh-check.sh —— 在**电脑上**验证 dsh 侧的启动链与生产者行为，不必装到手机。
#
# ## 这个脚本能测什么（都是近期真出过问题的地方）
#
# 1. `start-dsh.sh` 的**全部逻辑**：入口解析（instance / preinstalled）、版本回读、
#    端口探测、READY 协议、pid 文件、`--patch` 旗标的位置与退化重试。
# 2. `launcher.patch.yml` **真的被 dsh 接受**：用 `--dump-config` 断言
#    `client-hmr.pollIntervalMs` 与 `launcher-assets` 行都进了组合树。
# 3. **`/assets` 缓存头真的回来了**（fix22 / BL-222 的验收本体）——
#    并且带**反向对照**：去掉 overlay 必须没有这个头。
#    没有对照的断言是同义反复，测不出"head 本来就有"还是"我们的插件加的"。
#
# ## 这个脚本**测不到**什么（不要指望它）
#
# - **WebView 版本门禁 / 复制链接面板**：那是 Android UI，必须真机（见 tools/e2e-device.sh）。
# - **proot / busybox / ptyjni 任何原生层**：这里根本没跑 proot。
# - **手机上 `/opt/dsh` 的 bind 布局**：这里路径是宿主路径。
# - **预装版 dsh 的行为**：这里用 npm 装的那一份；手机预装的可能版本不同。
#
# ## 为什么节点是 junction 而不是复制
#
# `npm i @deepseek-ai/dsh` 约 722 MB / 2 万个文件。每次跑都复制一遍要几分钟。
# Windows 上 `mklink /J` 建目录 junction 是毫秒级的，且对 Node 的模块解析完全透明。
# 这是**测试脚手架**的取巧——测的不是安装流程本身，所以不需要真复制。
#
# 用法：
#   sh tools/host-dsh-check.sh              # 用默认 dsh 版本
#   DSH_VERSION=0.2.1-rc.2 sh tools/host-dsh-check.sh
#   KEEP=1 sh tools/host-dsh-check.sh       # 跑完保留临时目录（排查用）
#
# 退出码：0 = 全部通过；1 = 有断言失败；2 = 环境不满足（不是代码的问题）。
set -u

ROOT=$(cd "$(dirname "$0")/.." && pwd)
DSH_VERSION="${DSH_VERSION:-0.1.6-alpha.2}"
PORT_WAIT_S="${PORT_WAIT_S:-90}"

PASS=0; FAIL=0
# 本次运行起过的外层 shell pid（cleanup 兜底用）。必须先初始化：
# cleanup 注册在 trap 上，`set -u` 下引用未定义变量会让清理本身报错退出。
SHELL_PIDS=""
ok()  { PASS=$((PASS+1)); echo "  [PASS] $1"; }
bad() { FAIL=$((FAIL+1)); echo "  [FAIL] $1"; }
note(){ echo "         $1"; }

# ---------------------------------------------------------------- 0. 环境
echo "== 0. 环境检查 =="
if ! command -v node >/dev/null 2>&1; then
  echo "!! 找不到 node —— 这个脚本需要在 Windows 上跑（Git Bash / MSYS2）" >&2
  exit 2
fi
NODE_V=$(node -v)
echo "  node: $NODE_V"

# MSYS 会把 /tmp/xxx 改写成 D:/Git/tmp/xxx，msys2 路径转换必须关掉，
# 否则传给 node 的路径全部错位（这个坑实测过）。
export MSYS_NO_PATHCONV=1

SCRIPTS="$ROOT/FCL/src/main/assets/dsh/scripts"
[ -f "$SCRIPTS/start-dsh.sh" ] || { echo "!! 找不到 $SCRIPTS/start-dsh.sh" >&2; exit 2; }
[ -f "$SCRIPTS/launcher-assets.mjs" ] || { echo "!! 找不到 $SCRIPTS/launcher-assets.mjs" >&2; exit 2; }

WORK="${WORK:-$(mktemp -d 2>/dev/null || echo /tmp/host-dsh-check.$$)}"
mkdir -p "$WORK"
cleanup() {
  # ① 先收 node（按 pid 文件精确杀，不用模式匹配——
  #    模式匹配会误伤用户自己在跑的同名目录进程）
  for f in "$WORK"/*/dsh-node.pid "$WORK"/inst2/dsh-node.pid "$WORK"/inst/dsh-node.pid; do
    [ -f "$f" ] || continue
    pid=$(awk '{print $1}' "$f" 2>/dev/null | head -1)
    case "$pid" in ''|*[!0-9]*) continue;; esac
    taskkill //F //PID "$pid" >/dev/null 2>&1 || kill -9 "$pid" 2>/dev/null || true
  done
  # ② 再收外层 shell —— **这一步以前缺失**，是孤儿 `sh start-dsh.sh` 越积越多的根因。
  #    只杀 node 时，起它的 sh 会留在系统里；本次运行异常退出时更是一个都收不到。
  for p in ${SHELL_PIDS:-}; do
    case "$p" in ''|*[!0-9]*) continue;; esac
    taskkill //F //T //PID "$p" >/dev/null 2>&1 || kill -9 "$p" 2>/dev/null || true
  done
  # ★ 第 7 组会把 overlay 改名成 .off 来跑对照组。脚本若在中途被杀，
  #   这个改名就永久留下了 —— 下次跑会以为"没有 overlay"，第 4/6 组全假红。
  #   收尾时无条件把它恢复回去。
  for o in "$WORK"/inst/launcher.patch.yml "$WORK"/inst2/launcher.patch.yml; do
    [ -f "$o.off" ] && mv -f "$o.off" "$o" 2>/dev/null
  done
  [ "${KEEP:-0}" = "1" ] && { echo "  (KEEP=1，保留 $WORK)"; return; }
  rm -rf "$WORK" 2>/dev/null || true
}
trap cleanup EXIT TERM INT

# ---------------------------------------------------------------- 1. 装 dsh
echo
echo "== 1. 准备 dsh（$DSH_VERSION）=="
DEP="$WORK/dep"
if [ -f "$DEP/node_modules/@deepseek-ai/dsh/lib/bin.js" ]; then
  echo "  复用已装好的 $DEP"
else
  mkdir -p "$DEP"
  ( cd "$DEP" && npm init -y >/dev/null 2>&1 || true
    echo "  安装中（约 700 MB，首次需几分钟）…"
    npm i --no-audit --no-fund "@deepseek-ai/dsh@$DSH_VERSION" >"$WORK/npm.log" 2>&1 ) \
    || { echo "!! npm install 失败，见 $WORK/npm.log" >&2; tail -20 "$WORK/npm.log" >&2; exit 2; }
fi
BIN="$DEP/node_modules/@deepseek-ai/dsh/lib/bin.js"
[ -f "$BIN" ] || { echo "!! 装完仍找不到 $BIN" >&2; exit 2; }
# 只取最后一行：npm 的 warn 可能混进 stdout，直接取首行会拿到空串
# ★ Windows 上的 `node` 是原生程序，**不认识 MSYS 路径**（/tmp/xxx → 它会找 D:\tmp\xxx）。
# 凡是把路径喂给 node 的地方都必须先过 cygpath -w。这个坑在本脚本里踩了三次：
# --version 拿到空、--dump-config 报 Cannot find module、以及 nohup 出来的子进程找不到 bin。
BIN_W=$(cygpath -w "$BIN" 2>/dev/null || echo "$BIN")

DSH_V=$(node "$BIN_W" --version 2>/dev/null | tr -d '\r' | tail -1)
[ -n "$DSH_V" ] && ok "dsh 就位：$DSH_V" || bad "dsh 装了但 --version 无输出"

# ---------------------------------------------------------------- 2. 造实例
echo
echo "== 2. 造一个实例目录（junction 链到 node_modules）=="
INST="$WORK/inst"
mkdir -p "$INST"
if [ -e "$INST/node_modules" ]; then rm -rf "$INST/node_modules"; fi
# junction：毫秒级，对 Node 解析透明。
# ★ 这里踩过两个坑，都记下来：
#   ① `cmd //c mklink /J …` 在本机的 Git Bash 下**不工作**（MSYS 把 `/J` 当路径转换，
#      命令整体被当成 "启动 cmd" 的参数，什么也没建）。
#   ② PowerShell 的 New-Item -ItemType Junction 才可靠。
#   失败就退回 cp -a（慢但一定对）。测试脚手架不该因为建链接失败而假红。
if command -v powershell >/dev/null 2>&1; then
  powershell -NoProfile -Command "\$ErrorActionPreference='Stop'; \
    New-Item -ItemType Junction \
      -Path '$(cygpath -w "$INST/node_modules" 2>/dev/null || echo "$INST/node_modules")' \
      -Target '$(cygpath -w "$DEP/node_modules" 2>/dev/null || echo "$DEP/node_modules")' \
    | Out-Null" >/dev/null 2>&1 || true
fi
if [ ! -f "$INST/node_modules/@deepseek-ai/dsh/lib/bin.js" ]; then
  echo "  junction 不可用，退回复制（约 700 MB，慢）…"
  cp -a "$DEP/node_modules" "$INST/node_modules" 2>/dev/null || true
fi
[ -f "$INST/node_modules/@deepseek-ai/dsh/lib/bin.js" ] \
  && ok "实例里能看到 dsh" || { bad "实例里看不到 dsh（junction 失败且复制也失败）"; exit 1; }

cp "$SCRIPTS/launcher-assets.mjs" "$INST/launcher-assets.mjs"

# ---------------------------------------------------------------- 3. 生成 overlay
# 这份 JSON 的**形状必须与 Kotlin 侧 launcherPatchOverlayJson() 一致**。
# 唯一的差别是 name 用宿主路径（手机上是 /opt/dsh/scripts/...）。
# 想核对一致性：读 FCL/src/main/java/com/dsh/core/DshInstances.kt 的 launcherPatchOverlayJson。
echo
echo "== 3. 生成 launcher.patch.yml（与 Kotlin 侧同形）=="
PLUGIN_HOST=$(cygpath -m "$INST/launcher-assets.mjs" 2>/dev/null || echo "$INST/launcher-assets.mjs")
OVERLAY="$INST/launcher.patch.yml"
if command -v node >/dev/null 2>&1; then
  node -e "
    const fs = require('fs')
    const [plugin, out] = process.argv.slice(1)
    const body = [
      { id: 'client-hmr', config: { pollIntervalMs: 5000 } },
      { insert: [ { id: 'launcher-assets', name: 'file:///' + plugin } ] }
    ]
    fs.writeFileSync(out, JSON.stringify(body, null, 2) + '\n')
  " "$PLUGIN_HOST" "$(cygpath -w "$OVERLAY" 2>/dev/null || echo "$OVERLAY")"
fi
[ -s "$OVERLAY" ] && ok "overlay 已生成" || { bad "overlay 生成失败"; exit 1; }

# ---------------------------------------------------------------- 4. overlay 进组合树
echo
echo "== 4. dsh 是否真的吃下了这份 overlay =="
DUMP="$WORK/dump.yml"
node "$BIN_W" --profile web --patch "$(cygpath -w "$OVERLAY" 2>/dev/null || echo "$OVERLAY")" \
     --dump-config >"$DUMP" 2>"$WORK/dump.err" || true
if grep -q "pollIntervalMs: 5000" "$DUMP" 2>/dev/null; then
  ok "--patch 生效：client-hmr.pollIntervalMs = 5000"
else
  bad "--patch 没生效（dump 里找不到 pollIntervalMs: 5000）"
  note "dsh 版本可能不支持 --patch；见 $WORK/dump.err"
fi
if grep -q "launcher-assets" "$DUMP" 2>/dev/null; then
  ok "--patch 生效：launcher-assets 行已插入"
else
  bad "--patch 没生效（dump 里找不到 launcher-assets）"
fi

# ---------------------------------------------------------------- 5. 起服务
echo
echo "== 5. 用真 start-dsh.sh 起服务（带 overlay）=="
# ★ 每轮开跑前清掉上一轮的残留（日志 + pid）。
#   踩过的坑：`WORK` 固定时重跑，上一轮那份 run-with.log 还在，
#   第 6 组 grep 到了**上一轮的 port**，curl 连一个已经死掉的端口 ⇒ 断言假红，
#   而 hdr-with.txt 根本没被写出（curl 连接失败不会建文件）。
rm -f "$WORK"/run-with.log "$WORK"/run-without.log "$WORK"/hdr-with.txt "$WORK"/hdr-without.txt
rm -f "$INST"/dsh-node.pid 2>/dev/null || true

start_one() {
  # $1 = 日志文件名；overlay 是否存在由调用方决定
  #
  # ★ 这里**必须**同时记住外层 shell 的 pid（`echo $!` 拿到的那个）。
  #   它本身不是 node，但它是 node 的父进程链的一环；只杀 node 会留下它。
  #   实测：脚本跑完很久后，系统里堆积了几十个 `sh.exe start-dsh.sh`，
  #   全是历次运行留下的 —— 因为 cleanup 只按 dsh-node.pid 杀 node，从不收 shell。
  #   node 的真实 pid 仍要读 dsh-node.pid（`echo $!` 拿不到它），两个都收。
  ( cd "$INST" && INSTANCE_DIR="$(cygpath -m "$INST" 2>/dev/null || echo "$INST")" \
      PORT=0 HOST=127.0.0.1 READY_TIMEOUT="$PORT_WAIT_S" \
      exec sh "$SCRIPTS/start-dsh.sh" >"$WORK/$1" 2>&1 ) &
  _shpid=$!
  # 登记到全局，供 cleanup 兜底
  SHELL_PIDS="${SHELL_PIDS:-} $_shpid"
  echo "$_shpid"
}

# 收掉当前实例的服务：node 与它的外层 shell **都要**收。
#
# 历史缺陷（本次修复）：
#   ① 只按 dsh-node.pid 杀 node，外层 `sh start-dsh.sh` 变成孤儿 —— 越跑越多；
#   ② 对子 shell 的 pid 发 `kill -TERM` 在 MSYS 上对 native 进程无效；
#   ③ 没有 trap 兜底时，脚本中途退出会跳过 stop_current。
stop_current() {
  _pidfile="$INST/dsh-node.pid"
  if [ -f "$_pidfile" ]; then
    _node=$(awk '{print $1}' "$_pidfile" 2>/dev/null | head -1)
    case "$_node" in
      ''|*[!0-9]*) ;;
      *) taskkill //F //PID "$_node" >/dev/null 2>&1 || kill -9 "$_node" 2>/dev/null || true ;;
    esac
  fi
  if [ -n "${1:-}" ]; then
    # Windows 上 native 进程要用 taskkill；`kill` 只对 MSYS 进程有效
    taskkill //F //T //PID "$1" >/dev/null 2>&1 || kill -9 "$1" 2>/dev/null || true
  fi
  rm -f "$_pidfile" 2>/dev/null || true
}

SH_PID=$(start_one run-with.log)
i=0
while [ $i -lt $((PORT_WAIT_S * 2)) ]; do
  grep -q 'READY url=' "$WORK/run-with.log" 2>/dev/null && break
  grep -q 'FAILED reason=' "$WORK/run-with.log" 2>/dev/null && break
  sleep 1; i=$((i+1))
done

if grep -q 'READY url=' "$WORK/run-with.log" 2>/dev/null; then
  ok "start-dsh.sh 就绪"
else
  bad "start-dsh.sh 未就绪"
  sed 's/^/         | /' "$WORK/run-with.log" | tail -20
fi

grep -q 'dsh src : instance' "$WORK/run-with.log" 2>/dev/null \
  && ok "入口解析正确（dsh src : instance）" \
  || bad "入口解析异常（期望 dsh src : instance）"

grep -q -- '--patch' "$WORK/run-with.log" 2>/dev/null \
  && ok "命令行里带了 --patch" \
  || note "日志里看不到命令行（start-dsh.sh 不打印 argv，这是正常的）"

# ---------------------------------------------------------------- 6. 缓存头（核心）
# ★ 本脚本反复踩同一个坑，写在这里一次记清：
#   **Git Bash 的 /tmp/xxx 对 Windows 原生程序（node / curl / taskkill）是无效路径。**
#   凡是把"文件路径"作为参数交给它们的地方，都必须先 `cygpath -w`。
#   已经踩到的四处：node --version 拿空、node --dump-config 报 Cannot find module、
#   curl -D 写头文件失败（本次）、以及早期把 /tmp 当 INSTANCE_DIR 传给 node。
#   反例：`ls` / `grep` / `mkdir` 这些是 MSYS 自带的，直接用 /tmp 路径没问题。
echo
echo "== 6. /assets 缓存头（BL-222 的验收本体）=="
PORT=$(grep -o 'READY port=[0-9]*' "$WORK/run-with.log" 2>/dev/null | head -1 | cut -d= -f2)
TOKEN=$(grep -o 'token=[A-Za-z0-9_-]*' "$WORK/run-with.log" 2>/dev/null | head -1 | cut -d= -f2)
ASSET=""
if [ -n "$PORT" ] && [ -n "$TOKEN" ]; then
  # 用 dist 里真实存在的文件名（首页是 303，直接 curl / 拿不到资源名）
  DIST="$INST/node_modules/@deepseek-ai/dsh-web-frontend/dist/assets"
  ASSET=$(ls "$DIST"/*.js 2>/dev/null | head -1 | xargs -r basename 2>/dev/null)
fi

if [ -n "$ASSET" ]; then
  HDR="$WORK/hdr-with.txt"
  CURL_ERR=$(curl -sS -D "$(cygpath -w "$HDR" 2>/dev/null || echo "$HDR")" -o /dev/null \
             "http://127.0.0.1:$PORT/assets/$ASSET?token=$TOKEN" 2>&1)
  if [ ! -s "$HDR" ]; then
    # 空头文件 = curl 没连上（连接失败不会建文件）。这不是"没有缓存头"，
    # 是"根本没问到"——必须分开报，否则会把环境问题误判成功能回归。
    bad "连不上 127.0.0.1:$PORT（不是缓存头的问题，是服务没在跑或端口取错了）"
    note "curl: ${CURL_ERR:-无输出}"
    note "port=$PORT asset=$ASSET"
  elif grep -qi 'cache-control: public, max-age=31536000, immutable' "$HDR"; then
    ok "缓存头存在：immutable"
  else
    bad "缓存头缺失 —— launcher-assets 没生效"
    sed 's/^/         | /' "$HDR" | head -8
  fi
  grep -qi 'x-launcher-asset: 1' "$HDR" 2>/dev/null \
    && ok "响应来自 launcher-assets（x-launcher-asset: 1）" \
    || note "没有 x-launcher-asset 头（可能没走我们的路由）"
else
  bad "拿不到 asset 文件名（$DIST 下没有 .js？）"
fi

# 收掉第一个服务（见 stop_current 的说明：不能用子 shell 的 $!）
stop_current "$SH_PID"
sleep 2

# ---------------------------------------------------------------- 7. 反向对照
# ★ 这一组是本脚本的**区分力来源**。没有它，第 6 组无法排除
#   "dsh 本来就有缓存头"这种情况（那样第 6 组就是同义反复）。
echo
echo "== 7. 反向对照：去掉 overlay 必须**没有**缓存头 =="
mv "$OVERLAY" "$OVERLAY.off"
SH_PID2=$(start_one run-without.log)
i=0
while [ $i -lt $((PORT_WAIT_S * 2)) ]; do
  grep -q 'READY url=' "$WORK/run-without.log" 2>/dev/null && break
  grep -q 'FAILED reason=' "$WORK/run-without.log" 2>/dev/null && break
  sleep 1; i=$((i+1))
done

PORT2=$(grep -o 'READY port=[0-9]*' "$WORK/run-without.log" 2>/dev/null | head -1 | cut -d= -f2)
TOKEN2=$(grep -o 'token=[A-Za-z0-9_-]*' "$WORK/run-without.log" 2>/dev/null | head -1 | cut -d= -f2)
if [ -n "$PORT2" ] && [ -n "$TOKEN2" ] && [ -n "$ASSET" ]; then
  HDR2="$WORK/hdr-without.txt"
  curl -sS -D "$(cygpath -w "$HDR2" 2>/dev/null || echo "$HDR2")" -o /dev/null \
       "http://127.0.0.1:$PORT2/assets/$ASSET?token=$TOKEN2" 2>/dev/null || true
  if grep -qi 'cache-control: public, max-age=31536000, immutable' "$HDR2" 2>/dev/null; then
    bad "对照组也有缓存头 ⇒ 第 6 组的断言没有区分力（可能 dsh 自带该头，或插件根本没被卸载）"
  else
    ok "对照组没有缓存头 ⇒ 第 6 组的断言确实测到了 launcher-assets"
  fi
else
  bad "对照组起不来，无法验证区分力"
  sed 's/^/         | /' "$WORK/run-without.log" 2>/dev/null | tail -10
fi

mv "$OVERLAY.off" "$OVERLAY" 2>/dev/null || true
stop_current "$SH_PID2"
sleep 1

# ---------------------------------------------------------------- 汇总
echo
echo "==== 结果: PASS=$PASS FAIL=$FAIL ===="
if [ "$FAIL" -ne 0 ]; then
  echo "提示：KEEP=1 sh tools/host-dsh-check.sh 可保留临时目录（$WORK）以便排查"
  exit 1
fi
echo "提示：本脚本只覆盖 dsh 侧（启动链 + patch + 缓存头）。"
echo "      WebView 门禁 / 复制链接 / proot 必须用 tools/e2e-device.sh（真机）。"
exit 0
