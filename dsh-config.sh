#!/bin/sh
# dsh-配置助手 —— 在真机上配置 dsh 的自定义模型 provider，并验证它活过重启
#
# 为什么要有这个脚本：dsh 的配置分散在三个文件里，写错地方等于没写，而且**没有任何报错**。
# 本会话（2026-11-19）在这上面踩了整整一轮：
#   · 写到 home/profiles/web/cordis.patch.yml → 启动器不读它 → 无效
#   · 写到 launcher.patch.yml → 有效，但被启动器每次启动整份覆盖（BL-246，fix26 起才修好）
#   · 凭据要单独写到 home/.credentials.yaml 的 refs: 下
# 这个脚本把正确的做法固定下来。
#
# 用法：
#   sh dsh-config.sh env                       # 打印当前 dsh 进程实际在用的配置路径
#   sh dsh-config.sh show                      # 打印三处配置文件的现状
#   sh dsh-config.sh set-provider <json>       # 往 launcher.patch.yml 追加/替换一条 provider
#   sh dsh-config.sh set-cred <REF> <SECRET>   # 写凭据到 .credentials.yaml 的 refs:
#   sh dsh-config.sh unlock                    # 清掉残留的 session.lock（报 "already owned by" 时用）
#   sh dsh-config.sh verify                    # 重启 dsh 后检查 provider 是否还在（验证 BL-246）
#
# ⚠️ 只对 **debug 包**（com.dsh.fcl.androidlauncher.debug）可用 —— 它 debuggable，能用 run-as。
#    正式包非 debuggable，run-as 会被拒；那种情况只能用手机 UI 操作。
set -eu

ADB="${ADB:-adb}"
PKG="${PKG:-com.dsh.fcl.androidlauncher.debug}"
INSTANCE="${INSTANCE:-}"

die() { echo "!! $*" >&2; exit 1; }

# 找出实例 id（只有一个实例时直接用；多个要求显式指定）
resolve_instance() {
    if [ -n "$INSTANCE" ]; then
        echo "$INSTANCE"; return
    fi
    ids=$($ADB shell "run-as $PKG sh -c 'ls files/dsh/instances/ 2>/dev/null'" 2>/dev/null | tr -d '\r')
    n=$(echo "$ids" | grep -c . || true)
    [ "$n" -ge 1 ] || die "找不到实例（$PKG 没装，或还没初始化？）"
    [ "$n" -eq 1 ] || die "有 $n 个实例，请用 INSTANCE=<id> 指定：$ids"
    echo "$ids"
}

base() { echo "files/dsh/instances/$(resolve_instance)"; }
patch_file() { echo "$(base)/launcher.patch.yml"; }
cred_file()  { echo "$(base)/home/.credentials.yaml"; }
sessions_dir() { echo "$(base)/home/sessions"; }

# run-as 读写 app 私有文件的统一入口（stdin 传内容，避免引号被 shell 吃掉）
ra_cat_in() { $ADB shell "run-as $PKG sh -c 'cat > $1'" ; }
ra_cat()    { $ADB shell "run-as $PKG sh -c 'cat $1 2>/dev/null'" | tr -d '\r'; }

cmd_env() {
    echo "== dsh 进程实际加载的配置路径 =="
    pid=$($ADB shell "ps -A 2>/dev/null | grep ' node$' | awk '{print \$2}'" | tr -d '\r' | head -1)
    if [ -z "$pid" ]; then
        echo "  (dsh 没在跑 —— 先启动一个实例再看)"
        return 0
    fi
    echo "  node pid = $pid"
    echo "  cmdline  = $($ADB shell "run-as $PKG sh -c 'cat /proc/$pid/cmdline 2>/dev/null | tr \"\\0\" \" \"'" | tr -d '\r')"
    echo
    echo "  ★ 注意看 --patch 后面那个路径：那才是 dsh 真正读的文件。"
    echo "    写到 home/profiles/web/cordis.patch.yml 不会生效（启动器不传它）。"
}

cmd_show() {
    echo "== launcher.patch.yml（启动器 --patch 目标，BL-246 修好后会保留你的条目）=="
    ra_cat "$(patch_file)" || echo "  (读不到)"
    echo
    echo "== .credentials.yaml（只存凭据，refs: 下是 key→secret）=="
    ra_cat "$(cred_file)" | sed 's/\(sk-[A-Za-z0-9_-]\{6\}\)[A-Za-z0-9_-]*/\1…/g' || echo "  (读不到)"
    echo
    echo "== sessions（每个会话一个 session.lock；报 already owned 时用 unlock）=="
    $ADB shell "run-as $PKG sh -c 'ls $(sessions_dir)/*/ 2>/dev/null'" | tr -d '\r'
}

cmd_set_provider() {
    [ $# -ge 1 ] || die "用法: set-provider '<provider 条目的 JSON>'"
    entry=$1
    cur=$(ra_cat "$(patch_file)" || true)

    # 真正的合并逻辑在启动器里（BL-246 / fix26+）：它每次启动会读现有文件、按 id 认领
    # 自己那两条、其余原样保留。所以这里只需保证"文件里有一条 id=llm-pi-ai 的条目"。
    # 已有内容时不覆盖 —— 避免把启动器条目或别人的配置写坏。
    if [ -z "$cur" ] || [ "$cur" = "null" ]; then
        printf '[\n  %s\n]\n' "$entry" | ra_cat_in "$(patch_file)"
        echo "已写入。重启 dsh 后再跑 'verify' 确认它还在。"
    else
        die "文件已存在，本脚本不覆盖已有 patch（避免破坏启动器条目）。
     当前内容：
$cur

     请手动把这条加进数组末尾（保持 JSON 合法）：
  $entry"
    fi
}

cmd_set_cred() {
    [ $# -ge 2 ] || die "用法: set-cred <REF_NAME> <SECRET>"
    ref=$1; secret=$2
    cur=$(ra_cat "$(cred_file)" || true)
    if [ -z "$cur" ]; then
        die "凭据文件不存在 —— 先在 dsh UI 里配一次内置 DeepSeek（它会建出这个文件）"
    fi
    case "$cur" in
        *"$ref:"*) echo "  $ref 已存在，跳过（本脚本不覆盖已有凭据）"; return 0 ;;
    esac
    # 在 refs: 段末尾追加一行
    printf '%s\n  %s: %s\n' "$cur" "$ref" "$secret" | ra_cat_in "$(cred_file)"
    echo "已追加 $ref。"
}

cmd_unlock() {
    echo "== 清理残留 session.lock =="
    before=$($ADB shell "run-as $PKG sh -c 'find $(sessions_dir) -name session.lock 2>/dev/null | wc -l'" | tr -d '\r')
    $ADB shell "run-as $PKG sh -c 'rm -f $(sessions_dir)/*/session.lock'"
    after=$($ADB shell "run-as $PKG sh -c 'find $(sessions_dir) -name session.lock 2>/dev/null | wc -l'" | tr -d '\r')
    echo "  清理前 $before 个，清理后 $after 个"
    echo "  ⚠️ 只在 dsh 已停止时清理；运行中的 dsh 持有这些锁是正常的。"
}

cmd_verify() {
    echo "== 验证 provider 是否活过重启（BL-246）=="
    cur=$(ra_cat "$(patch_file)" || true)
    if echo "$cur" | grep -q 'llm-pi-ai'; then
        echo "  ✅ launcher.patch.yml 里仍有 llm-pi-ai 条目 —— 启动器没有覆盖它（fix26 的修复生效）"
        echo "$cur" | grep -A2 'mygateway' | head -5
    else
        echo "  ❌ llm-pi-ai 条目不见了 —— 启动器把它覆盖了（说明跑的是 fix25 或更早）"
        echo "     当前内容：$cur"
    fi
}

case "${1:-}" in
    env)          cmd_env ;;
    show)         cmd_show ;;
    set-provider) shift; cmd_set_provider "$@" ;;
    set-cred)     shift; cmd_set_cred "$@" ;;
    unlock)       cmd_unlock ;;
    verify)       cmd_verify ;;
    *) sed -n '2,24p' "$0" | sed 's/^# \{0,1\}//'; exit 1 ;;
esac
