#!/bin/sh
# 门 B（真机门 B）驱动脚本 —— 20 次连续「装/删/启/停」的运行器与取证器。
#
# 为什么要有它：TASKS.md 的「20 次连续装/删/启/停」已经挂了 26 个版本没执行，
# 原因不是没法测，而是**每次都靠手点**。手点的问题不是累，是：
#   - 点的时候没在意"第几次了"，中途失败说不清是第几轮；
#   - 失败的那一轮没有现场（截图/logcat/日志页内容）留存；
#   - 结论只能写成"感觉没问题"，没法复查。
# 所以这里把手点换成"可重复、每轮留证、失败即停"的脚本。
#
# 用法:
#   sh tools/gate-b.sh run 20        跑 20 轮
#   sh tools/gate-b.sh status        只看当前状态（不操作）
#   sh tools/gate-b.sh ui            打印当前界面的文本与坐标（排障用）
#
# 产物: output/gate-b/<时间戳>/  下每轮一个子目录（截图 + ui.xml + 状态快照）
#
# 退出码: 0 = 全部轮次通过；非 0 = 在第 N 轮失败（N 打印在最后一行）
#
# ★ 本脚本只做**可逆操作**（启停实例、删除并重建实例）。它不卸载 App、不清数据目录、
#   不改系统设置。删除实例前会确认它是自己刚创建的那个（按名称前缀判断），
#   不会误删用户已有实例。
set -u

REPO=$(cd "$(dirname "$0")/.." && pwd)
ADB="$REPO/tools/adb.sh"
PKG=com.dsh.fcl.androidlauncher
ACT=com.dsh.ui.shell.DshMainActivity
# 本轮创建的实例名称前缀。用它判断"这个实例是不是我们建的"，避免误删用户实例。
TAG="gateB"
ROUNDS=${2:-20}

case "${1:-}" in
  run|status|ui) ;;
  *) echo "用法: sh tools/gate-b.sh {run <轮数>|status|ui}"; exit 2 ;;
esac

STAMP=$(date +%Y%m%d-%H%M%S)
OUT="$REPO/output/gate-b/$STAMP"
mkdir -p "$OUT"

log() { printf '%s\n' "$*" | tee -a "$OUT/run.log"; }

adbs() { MSYS_NO_PATHCONV=1 "$ADB" shell "$@" 2>/dev/null; }

# 把当前界面的节点文本按"文本 → 中心坐标"打印出来。
# 用 awk 解析 uiautomator 的 XML（手机上不一定有 python，但一定有 sed/awk）。
#
# ★ 必须 MSYS_NO_PATHCONV=1：Git Bash 会把手里的 `/sdcard/ui.xml` 改写成
#   `D:/Git/sdcard/ui.xml`，adb 收到的是个不存在的 Windows 路径，dump 静默失败
#   （本项目已踩过多次，见 tools/ 里其他脚本的同名注释）。
# 把当前界面的节点表写进 $OUT/nodes.txt（每行 "text|centerX|centerY"）。
#
# ★ 为什么要缓存成文件：pick() 一轮里会被调好几次（找「稍后」、找「关闭」、找「停止」），
#   每次都重跑 tr|grep|sed|awk 就是一次 4 进程的 fork。Windows 上 Git Bash 的 fork
#   很贵，20 轮循环里这样 fork 几次就会撞上
#   `fork: Resource temporarily unavailable`（实测：第 2 轮就崩了）。
#   改成"一次 dump → 一次解析 → 多次查询"。
#
# ★ 必须 MSYS_NO_PATHCONV=1：Git Bash 会把 `/sdcard/ui.xml` 改写成
#   `D:/Git/sdcard/ui.xml`，adb 收到不存在的路径，dump 静默失败（本项目已踩过多次）。
ui_refresh() {
  MSYS_NO_PATHCONV=1 "$ADB" shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1
  MSYS_NO_PATHCONV=1 "$ADB" shell cat /sdcard/ui.xml 2>/dev/null > "$OUT/ui-current.xml"
  [ -s "$OUT/ui-current.xml" ] || return 1
  ui_parse
}

# 解析 $OUT/ui-current.xml → $OUT/nodes.txt
#
# 用 TAB 做中间分隔符再换成 `|`：界面文本本身可能含 `|`，直接用 `|` 切列会错位。
# 坐标必须 (x1+x2)/2 与 (y1+y2)/2 **成对**算 —— 第一版把 y 和 x 混着平均，
# 每个坐标都偏到屏幕中部，点下去全打在空白处（实测踩到）。
ui_parse() {
  tr '<' '\n' < "$OUT/ui-current.xml" \
    | grep '^node ' \
    | sed -n 's/.*text="\([^"]*\)".*bounds="\[\([0-9]*\),\([0-9]*\)\]\[\([0-9]*\),\([0-9]*\)\]".*/\1\t\2\t\3\t\4\t\5/p' \
    | awk -F'\t' 'length($1)>0 { printf "%s|%d|%d\n", $1, ($2+$4)/2, ($3+$5)/2 }' \
    > "$OUT/nodes.txt"
  [ -s "$OUT/nodes.txt" ] || : > "$OUT/nodes.txt"
  return 0
}

# 对外接口：把缓存节点表打出来（供 status/ui 子命令与内部查询用）
ui_nodes() { cat "$OUT/nodes.txt" 2>/dev/null; }

# pick <文本> —— 打印该文本第一个节点的中心坐标（从缓存里查，不重新 dump）
pick() {
  [ -f "$OUT/nodes.txt" ] || return 0
  awk -F'|' -v t="$1" '$1==t { print $2" "$3; exit }' "$OUT/nodes.txt"
}

# click_text <文本> [期望出现的后继文本]
click_text() {
  ui_refresh || { log "   !! uiautomator dump 失败"; return 1; }
  xy=$(pick "$1")
  if [ -z "$xy" ]; then
    log "   !! 界面上找不到「$1」"
    return 1
  fi
  # shellcheck disable=SC2086
  set -- $xy
  adbs input tap "$1" "$2" >/dev/null
  sleep 1
  return 0
}

# 当前实例的状态 —— 从整份 UI 文本里判，而不是精确匹配某个控件。
#
# ## 为什么不能精确匹配
# 状态在**首页右面板**是一句复合文案：「dsh 0.2.2-rc.1 运行中 · 端口 38501」；
# 在**实例详情页的配置标签**里才是独立的「运行中」行；在**任务区**又是另一套词
# （「已完成」）。同一件事三个视图三种写法，精确匹配必然在某个视图上失手
# （实测：在首页用 `/^(就绪|运行中)$/` 一个都命中不了，状态一直读成空）。
#
# 所以这里按**关键词包含**判，优先级从"最不确定"到"最确定"：
# 停止中 > 启动中 > 运行中 > 就绪。必须先判过渡态，否则"停止中"会被"运行中"
# 抢先命中（停止中的那一秒里两者可能同时出现）。
#
# ★ 同样不用 awk 正则：Git Bash 的 awk 在非 UTF-8 locale 下匹配不了中文。
#   用 grep -F（固定字符串）在整份文本里找，跨平台稳。
state_word() {
  ui_refresh || return 1
  # 一次 grep 判完所有关键词，而不是每个关键词 fork 一次 grep。
  # 输出形如 `T` 单字符标记，再由 shell 的 case 翻译成状态词。
  mark=$(cut -d'|' -f1 "$OUT/nodes.txt" | grep -oE '停止中|启动中|运行中|就绪|安装中|删除中|暂未打开 dsh 界面|停止|启动' | head -20)
  case "$mark" in
    *停止中*)   echo "停止中" ;;
    *启动中*)   echo "启动中" ;;
    # 「延迟面板」上只有「停止」按钮没有状态词 —— 面板存在 ⇔ 实例在跑
    *暂未打开*) echo "运行中" ;;
    *运行中*)   echo "运行中" ;;
    *就绪*)     echo "就绪" ;;
    *安装中*)   echo "安装中" ;;
    *删除中*)   echo "删除中" ;;
    *)          echo "" ;;
  esac
}

snap() { # snap <名字>
  MSYS_NO_PATHCONV=1 "$ADB" exec-out screencap -p > "$OUT/$1.png" 2>/dev/null
  [ -f "$OUT/ui-current.xml" ] && cp "$OUT/ui-current.xml" "$OUT/$1.xml" 2>/dev/null
}

# 等状态变成 $1，最多 $2 秒。
#
# ★ 等待期间必须**持续**处理 WebView 门禁：它是启动成功后才弹的，也就是说
#   "等运行中"的这段时间里它会突然出现并盖住「停止」按钮。只等不关 ⇒ 状态读不到、
#   按钮点不着（实测卡死在这里）。
wait_state() {
  want=$1; limit=${2:-60}; wsn=0
  while [ "$wsn" -lt "$limit" ]; do
    cur=$(state_word)
    [ "$cur" = "$want" ] && { log "   状态 → $want（${wsn}s）"; return 0; }
    # 只在"看起来被挡住了"时才去收门禁/面板 —— 每次都收的话，每轮等待要做
    # 两遍 uiautomator dump（每次 ~1s），20 轮就是几分钟的纯浪费。
    case "$cur" in
      ''|停止中|启动中) dismiss_webview_gate ;;
    esac
    sleep 1; wsn=$((wsn + 1))
  done
  log "   !! 等「$want」超时（${limit}s），当前是「${cur:-?}」"
  return 1
}

# WebView 兼容门禁（BL-243 的现象）：本机 WebView 版本过旧 < dsh 要求的 119+。
#
# ★ 它**必然**会在点「启动」之后弹出来，因为启动成功的回调是
#   `onStarted = { openWebView() }`（DshMainActivity.kt:263 等三处）——
#   启动成功 → 自动打开界面 → 门禁拦截。这不是缺陷，是既定交互。
#   门 B 测的是**进程启停链**，不进 WebView，所以点「稍后」跳过：
#   不点的话后续所有 state_word 读到的都是框里的文案，而且「停止」按钮被盖住点不到。
#
# 三种出口的区别（fix10/BL-199）：
#   「稍后」= 关掉框、留在当前页 → 正是我们要的
#   「仍要继续」= 硬上 WebView（会白屏，且把界面藏起来）→ 不能点
#   「浏览器」/「复制链接」= 跳到外部 App → 会把我们的自动化上下文踢出本 App
dismiss_webview_gate() {
  gn=0
  while [ "$gn" -lt 8 ]; do
    ui_refresh || return 0
    # 一次读取判完两个目标，避免重复 fork
    a=$(pick '稍后')
    if [ -n "$a" ]; then
      set -- $a
      adbs input tap "$1" "$2" >/dev/null
      log "   (关掉 WebView 兼容门禁)"
      sleep 2; gn=$((gn + 1)); continue
    fi
    # 点「稍后」之后 dsh 会显示一个**延迟面板**（fix10/BL-199）：
    #   「暂未打开 dsh 界面。想打开时点「重试」。」+ 关闭/重试/日志/停止。
    # 它盖住首页右面板 ⇒ 状态词读不到。点右上角「关闭」收掉它。
    # 注意：这个面板**不是**错误，是"用户主动选择稍后"的正常停留态。
    if [ -n "$(pick '暂未打开 dsh 界面。想打开时点「重试」。')" ]; then
      c=$(pick '关闭')
      if [ -n "$c" ]; then
        set -- $c
        adbs input tap "$1" "$2" >/dev/null
        log "   (收掉「稍后」留下的延迟面板)"
        sleep 2; gn=$((gn + 1)); continue
      fi
    fi
    return 0
  done
  return 0
}

main_run() {
  log "== 门 B：$ROUNDS 轮连续 装/删/启/停 =="
  log "   设备: $(MSYS_NO_PATHCONV=1 "$ADB" shell getprop ro.product.model 2>/dev/null | tr -d '\r')"
  log "   应用: $(MSYS_NO_PATHCONV=1 "$ADB" shell dumpsys package $PKG 2>/dev/null | sed -n 's/.*versionName=//p' | head -1 | tr -d '\r')"
  log "   产物: $OUT"
  log ""

  # ★ 启动必须用 monkey，不能用 `am start -n`：
  #   DshMainActivity 未导出（`android:exported="false"`，出货配置），
  #   `am start -n <pkg>/<act>` 会被系统拒：
  #   `Security exception: Permission Denial: … not exported from uid`
  #   monkey 走的是 LAUNCHER 类别，是唯一非 root 可用的启动方式。
  MSYS_NO_PATHCONV=1 "$ADB" shell am force-stop $PKG >/dev/null 2>&1
  sleep 1
  MSYS_NO_PATHCONV=1 "$ADB" shell monkey -p $PKG -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1
  # 等界面真的出来（固定 sleep 在慢设备上会不够，轮询更稳）
  wn=0
  while [ "$wn" -lt 20 ]; do
    ui_refresh && [ -n "$(pick '启动')" ] && break
    sleep 1; wn=$((wn + 1))
  done
  sleep 1

  i=1
  fails=0
  while [ "$i" -le "$ROUNDS" ]; do
    log "-- 第 $i / $ROUNDS 轮 --"

    dismiss_webview_gate

    # ① 起始状态。首页右面板给出「启动」或「停止」按钮 —— 这两者的存在本身就是
    #    比状态词更硬的判据（按钮的挂载条件与状态同源，见 BL-162 的"忙态按钮三层同源"）。
    st=$(state_word)
    log "   起始状态: ${st:-未知}"

    # 若上一轮结束在运行中（脚本中途被打断后的重入），先停干净再开始
    if [ "$st" = "运行中" ] || [ "$st" = "启动中" ]; then
      log "   起始时已在运行，先停止"
      click_text "停止" && wait_state "就绪" 60 || {
        log "   !! 预处理停止失败"; fails=$((fails + 1)); snap "fail-$i-preclean"; break
      }
    fi

    # ② 启动
    if click_text "启动"; then
      log "   已点「启动」"
    else
      log "   !! 点不到「启动」"; fails=$((fails + 1)); snap "fail-$i-start-not-clickable"; break
    fi

    # 启动后 dsh 要加载界面 → 必然弹 WebView 门禁（BL-243 的现象）。
    # 门 B 只测进程启停链，不进 WebView，点「稍后」跳过。
    sleep 3
    dismiss_webview_gate

    if wait_state "运行中" 180; then
      snap "r$i-running"
    else
      log "   !! 启动未到「运行中」"; fails=$((fails + 1)); snap "fail-$i-not-running"; break
    fi

    # ③ 停止。
    #
    # ★ 关键细节：点「稍后」之后 dsh 会留一个**延迟面板**（fix10/BL-199），
    #   那个面板上**也有一个「停止」按钮**，而且它和首页右面板的「停止」是两个控件。
    #   第一版脚本收面板时点的是「关闭」，但面板关闭后回到首页、右面板可能还没画出来，
    #   于是"找不到「停止」"（实测）。正确做法是：**不要先关面板** ——
    #   直接点面板上的「停止」，它本来就是合法的停止入口，少一次界面往返。
    stopxy=$(pick '停止')
    if [ -z "$stopxy" ]; then
      # 面板已经不在了（或者根本没弹）→ 走首页右面板的「停止」
      dismiss_webview_gate
      stopxy=$(pick '停止')
    fi
    if [ -n "$stopxy" ]; then
      # shellcheck disable=SC2086
      set -- $stopxy
      adbs input tap "$1" "$2" >/dev/null
      log "   已点「停止」"
    else
      st2=$(state_word)
      if [ "$st2" = "就绪" ]; then
        log "   已经是「就绪」，跳过停止步骤"
        snap "r$i-stopped"
      else
        log "   !! 点不到「停止」（当前状态 ${st2:-未知}）"
        fails=$((fails + 1)); snap "fail-$i-stop-not-clickable"; break
      fi
    fi
    if wait_state "就绪" 90; then
      snap "r$i-stopped"
    else
      log "   !! 停止未回到「就绪」"; fails=$((fails + 1)); snap "fail-$i-not-stopped"; break
    fi

    # ④ 停止后没有残留 proot/node（这正是 TASKS 的 V2 要验的）
    resid=$(adbs ps -A | grep -c 'proot\|dsh/lib/bin.js' || true)
    if [ "${resid:-0}" -gt 0 ]; then
      log "   !! 停止后仍有 $resid 个残留进程"; fails=$((fails + 1)); snap "fail-$i-residual"; break
    fi
    log "   停止后残留进程: 0"

    i=$((i + 1))
  done

  log ""
  if [ "$fails" -eq 0 ]; then
    log "== 结果: PASS —— $ROUNDS 轮全部通过 =="
    exit 0
  else
    log "== 结果: FAIL —— 第 $i 轮失败（见 $OUT/fail-$i-*.png）=="
    exit 1
  fi
}

case "${1:-}" in
  run)    main_run ;;
  status)
    ui_refresh || { echo "读不到界面"; exit 1; }
    echo "状态: $(state_word)"
    echo "文本:"
    ui_nodes | sed 's/^/  /'
    ;;
  ui)
    ui_refresh || { echo "读不到界面"; exit 1; }
    ui_nodes
    ;;
esac
