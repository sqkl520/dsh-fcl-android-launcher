#!/bin/sh
# tools/e2e-device.sh —— 把 ondevice.md 里"需真机"的手工步骤脚本化。
#
# ## 这个脚本能测什么（= 只有真机能测的那部分）
#
# Android UI 与生命周期，具体是近期真出过问题的几条：
# 1. 装机 → 冷启 → 落到主壳（`DshMainActivity`），版本号与包名对不对。
# 2. `run-as` 读实例日志：`launcher=<版本>`、`home-has-no-subdir` 告警（fix21）、
#    `--patch`/`launcher-assets` 相关行（fix22）。
# 3. `uiautomator dump` 断言门禁框/面板上的**文案**（WebView 版本门禁、复制链接后的面板）。
# 4. 截图留证（人工复核用，脚本不断言像素）。
#
# ## 这个脚本**测不到**什么
#
# - dsh 内部的 HTTP 行为（`/assets` 缓存头、picker 返回几条）→ 用 tools/host-dsh-check.sh。
# - proot 内部（node 在 rootfs 里怎么跑）——日志能反映一部分，但断言不到系统调用层。
# - **WebView 框内文字**：`uiautomator dump` 对 WebView 只给一个节点，框内文案要看截图或日志。
#   门禁框是**原生对话框**，dump 得到；页面内容是 WebView，dump 不到。
#
# ## 用法
#
#   sh tools/e2e-device.sh                     # 用 output/ 里最新的 fix 包
#   APK=output/xxx.apk sh tools/e2e-device.sh  # 指定包
#   SKIP_INSTALL=1 sh tools/e2e-device.sh      # 不重装，只跑断言（快速回归）
#   SHOTS=1 sh tools/e2e-device.sh             # 额外拉截图到 output/shots/
#
# 退出码：0 = 通过；1 = 断言失败；2 = 没接设备 / 环境不满足（不是代码问题）。
set -u

ROOT=$(cd "$(dirname "$0")/.." && pwd)
ADB="$ROOT/tools/adb.sh"
PKG_PROD="com.dsh.fcl.androidlauncher"
PKG_DEBUG="com.dsh.fcl.androidlauncher.debug"

# ★ Git Bash 下 adb shell 的路径参数会被 MSYS 改装（/sdcard/x → D:/Git/sdcard/x）。
#   所有 `adb shell` 调用都必须关掉路径转换，否则读出来的文件全是空的。
export MSYS_NO_PATHCONV=1

PASS=0; FAIL=0; SKIP=0
ok()   { PASS=$((PASS+1)); echo "  [PASS] $1"; }
bad()  { FAIL=$((FAIL+1)); echo "  [FAIL] $1"; }
skip() { SKIP=$((SKIP+1)); echo "  [SKIP] $1"; }
note() { echo "         $1"; }

# ---------------------------------------------------------------- 0. 设备
echo "== 0. 设备 =="
[ -f "$ADB" ] || { echo "!! 找不到 $ADB" >&2; exit 2; }
DEVICES=$(sh "$ADB" devices 2>/dev/null | awk 'NR>1 && $2=="device" {print $1}')
if [ -z "$DEVICES" ]; then
  echo "!! 没有已授权的设备（adb devices 为空）" >&2
  echo "   接上手机、打开 USB 调试、手机上点「允许」后重试。" >&2
  echo "   这是环境问题，不是测试失败。" >&2
  exit 2
fi
NDEV=$(printf '%s\n' "$DEVICES" | wc -l | tr -d ' ')
if [ "$NDEV" -gt 1 ]; then
  echo "!! 接了 $NDEV 台设备，请只留一台（或多台时用 ANDROID_SERIAL 指定）" >&2
  exit 2
fi
echo "  设备: $DEVICES"
SDK=$(sh "$ADB" shell getprop ro.build.version.sdk 2>/dev/null | tr -d '\r')
MODEL=$(sh "$ADB" shell getprop ro.product.model 2>/dev/null | tr -d '\r')
echo "  $MODEL / API $SDK"

# ---------------------------------------------------------------- 1. 选 APK
echo
echo "== 1. APK =="
APK="${APK:-}"
if [ -z "$APK" ]; then
  APK=$(ls -t "$ROOT"/output/*-arm64.apk 2>/dev/null | head -1)
fi
if [ -z "$APK" ] || [ ! -f "$APK" ]; then
  echo "!! 找不到 APK（output/ 下没有 *-arm64.apk，也没设 APK=）" >&2
  exit 2
fi
echo "  $APK"

# ---------------------------------------------------------------- 2. 装机
echo
echo "== 2. 安装 =="
PKG="$PKG_PROD"
if [ "${SKIP_INSTALL:-0}" = "1" ]; then
  skip "SKIP_INSTALL=1，跳过安装"
else
  # ★ adb.exe 是 Windows 原生程序，`/d/Projects/...` 这种 MSYS 路径它不认
  #   （报 `failed to stat ...: No such file or directory`）。
  #   这条在真机上实测踩到过 —— e2e 脚本第一次跑就是这么红的。
  APK_W=$(cygpath -w "$APK" 2>/dev/null || echo "$APK")
  OUT=$(sh "$ADB" install -r "$APK_W" 2>&1 | tr -d '\r')
  case "$OUT" in
    *Success*|*success*) ok "安装成功" ;;
    *) bad "安装失败"; note "$OUT" ;;
  esac
fi

# 判断装的是哪个包（出货包还是调试包）
if sh "$ADB" shell pm list packages 2>/dev/null | grep -q "package:$PKG_PROD$"; then
  PKG="$PKG_PROD"
elif sh "$ADB" shell pm list packages 2>/dev/null | grep -q "package:$PKG_DEBUG$"; then
  PKG="$PKG_DEBUG"
  note "检测到调试包（$PKG_DEBUG）"
else
  bad "两个包名都没装上"
  echo "==== 结果: PASS=$PASS FAIL=$FAIL SKIP=$SKIP ===="
  exit 1
fi

# 版本号（必须与 APK 文件名一致，否则说明装错了包）
VER=$(sh "$ADB" shell dumpsys package "$PKG" 2>/dev/null | grep -m1 versionName | sed 's/.*versionName=//' | tr -d '\r')
echo "  已装版本: ${VER:-?}"

# ---------------------------------------------------------------- 3. 冷启
echo
echo "== 3. 冷启到主壳 =="
sh "$ADB" shell am force-stop "$PKG" >/dev/null 2>&1 || true
sh "$ADB" shell am start -n "$PKG/com.tungsten.fcl.activity.SplashActivity" >/dev/null 2>&1 || true

i=0; LANDED=""
while [ $i -lt 60 ]; do
  TOP=$(sh "$ADB" shell dumpsys activity activities 2>/dev/null | grep -m1 -E 'topResumedActivity|mResumedActivity' | tr -d '\r')
  case "$TOP" in
    *DshMainActivity*) LANDED="main"; break ;;
    *DshSetupActivity*) LANDED="setup"; break ;;
    *DshWebViewActivity*) LANDED="webview"; break ;;
  esac
  sleep 1; i=$((i+1))
done

case "$LANDED" in
  main)    ok "落到主壳（DshMainActivity）" ;;
  setup)   ok "落到准备页（DshSetupActivity）—— 首次安装的正常落点" ;;
  webview) ok "落到 WebView 页（已自动打开界面）" ;;
  "")      bad "60 秒内没落到任何已知页面"; note "top=$TOP" ;;
esac

# ---------------------------------------------------------------- 4. 日志断言
echo
echo "== 4. 实例日志断言（run-as）=="
# 应用私有目录只有 debug 包能 run-as；出货包需要 root。
CAN_RUNAS=1
if ! sh "$ADB" shell run-as "$PKG" ls /data/data/"$PKG"/files/dsh >/dev/null 2>&1; then
  CAN_RUNAS=0
fi

if [ "$CAN_RUNAS" = "1" ]; then
  LOG=$(sh "$ADB" shell run-as "$PKG" cat /data/data/"$PKG"/files/dsh/logs/runtime.log 2>/dev/null | tr -d '\r')
  if [ -z "$LOG" ]; then
    skip "runtime.log 为空或读不到"
  else
    printf '%s\n' "$LOG" | grep -q 'launcher=' \
      && ok "日志里有启动版本行" \
      || bad "日志里没有 launcher= 行"

    # fix21：空 HOME 自动告警。这条**出现了才是问题**，所以两条都报（出现=提示，不出现=正常）
    if printf '%s\n' "$LOG" | grep -q 'home-has-no-subdir'; then
      note "⚠ 出现了 home-has-no-subdir 告警 —— 「选择工作区目录」会显示空框（fix21 的诊断生效了）"
      note "  修法：实例设置页把「启动目录」指向一个有内容的目录（通常是 workspace 的上一级）"
    else
      ok "没有 home-has-no-subdir 告警（启动目录非空）"
    fi

    # fix22：overlay 写入失败会留一行（成功时不打印，属于正常）
    if printf '%s\n' "$LOG" | grep -q 'launcher patch overlay 写入失败'; then
      bad "launcher.patch.yml 写入失败 —— /assets 缓存头优化不会生效"
    else
      ok "没有 overlay 写入失败"
    fi
  fi
else
  skip "无法 run-as（出货包且未 root）—— 日志断言跳过"
  note "想跑这组：装 debug 包（./gradlew :FCL:assembleFordebug），或先 adb root"
fi

# ---------------------------------------------------------------- 5. 门禁框
echo

echo "== 5. WebView 门禁框 + fix22 复制链接流程 =="
# ⚠ 这一组在**真机实测跑通过**（HONOR X10 Max / KKG-AN00 / Android 10 / WebView 114）：
#   门禁框弹出 → Copy link → Copy → 落到「Link copied」面板（**不重弹**）→ Retry → 门禁回来。
#
# 两个必踩的坑，都记在这里：
# ① 第 3 组刚把 App 冷启回主壳，此刻屏上**没有**门禁框。必须自己导航过去
#    （实例卡片 → Start（若没跑）→ Open UI）。第一版漏了，永远报"没有门禁框"，是假绿。
# ② dump 出来的按钮文案取决于系统语言：英文机是 `Copy link` / `Copy` / `Retry`，
#    中文机是「复制链接」/「复制」/「重试」。只认一套会漏。
UI=/sdcard/e2e-ui.xml
NAV_FAILED=0

dump_ui() {
  sh "$ADB" shell uiautomator dump "$UI" >/dev/null 2>&1
  sh "$ADB" shell cat "$UI" 2>/dev/null | tr -d '\r'
}

# 从 bounds="[x1,y1][x2,y2]" 算中心点 → "cx cy"
# ★ 踩过的坑：`sed 's/\]\[/ /;s/,/ /g'` 这种写法搞不定 ——
#   `[1754,267][2208,375]` 去括号后是 `1754,267 2208,375`，awk 按空格取字段会拿到
#   `1754,267` 这一整串，$1+$3 直接算成 877/1104（错得离谱，但不会报错）。
#   正确做法是把逗号和空格都归一成空白，让 awk 拿到 4 个纯数字。
center_of() {
  printf '%s' "$1" | sed 's/></>\n</g' \
    | grep -F "text=\"$2\"" | head -1 \
    | grep -oE 'bounds="\[[0-9]+,[0-9]+\]\[[0-9]+,[0-9]+\]"' \
    | tr -c '0-9\n' ' ' \
    | awk '{printf "%d %d", ($1+$3)/2, ($2+$4)/2}'
}

# 取某个 resource-id 节点是否 enabled（"true"/"false"/空）
enabled_of() {
  printf '%s' "$1" | sed 's/></>\n</g' | grep "id/$2\"" | head -1 \
    | grep -oE 'enabled="[a-z]+"' | head -1 | sed 's/enabled="//;s/"//'
}

# 取某个 resource-id 节点的中心坐标（同样的 bounds 解析规则，见 center_of 的注释）
center_of_id() {
  printf '%s' "$1" | sed 's/></>\n</g' | grep "id/$2\"" | head -1 \
    | grep -oE 'bounds="\[[0-9]+,[0-9]+\]\[[0-9]+,[0-9]+\]"' | head -1 \
    | tr -c '0-9\n' ' ' \
    | awk '{printf "%d %d", ($1+$3)/2, ($2+$4)/2}'
}

U=$(dump_ui)
# ★ 第 3 组 `am force-stop` + `am start` 之后，主壳要几秒才完成首帧。
#   实测：force-stop 之后立刻 dump，能拿到主壳的节点树（看起来"就绪"），
#   但此时点击层还没挂上 —— `input tap` 打到空气里，node 也起不来。
#   症状极具迷惑性：坐标对、enabled=true、点了没反应，三次都一样。
#   这里先等一次稳定的主壳（连续两次 dump 都能看到 right_start 且布局不变）再动手。
note "等待主壳稳定…"
prev=""; stable=0; k=0
while [ $k -lt 20 ]; do
  U=$(dump_ui)
  cur=$(printf '%s' "$U" | grep -oE 'id/right_start"[^>]*enabled="[a-z]+"' | head -1)
  if [ -n "$cur" ] && [ "$cur" = "$prev" ]; then
    stable=$((stable+1)); [ $stable -ge 2 ] && break
  else
    stable=0
  fi
  prev="$cur"; sleep 1; k=$((k+1))
done

# ★ 系统弹窗会挡住整个流程：实测踩到「USB 连接方式」对话框（传输照片/传输文件/仅充电）。
#   它不是 App 的一部分，但会把 App 的节点树整个替换掉 —— 不处理就会误判成"找不到 Open UI"。
#   这里只点「传输文件」或「取消」，绝不点其他应用的按钮。
if printf '%s' "$U" | grep -qE 'text="USB 连接方式"|text="Use USB to"'; then
  note "检测到系统「USB 连接方式」对话框，选「传输文件」关掉"
  for t in "传输文件" "Transfer files"; do
    C=$(center_of "$U" "$t"); [ -n "$C" ] && { sh "$ADB" shell input tap $C >/dev/null 2>&1; break; }
  done
  sleep 3; U=$(dump_ui)
fi

# 已经在 WebView 页就不用导航
if printf '%s' "$U" | grep -qE 'System WebView is too old|Link copied|WebView 版本过旧|链接已复制'; then
  note "已在 WebView 页"
else
  # 实例没跑（Open UI 禁用）就先点 Start
  if [ "$(enabled_of "$U" right_webui)" = "false" ]; then
    if [ "$(enabled_of "$U" right_start)" = "true" ]; then
      C=$(center_of_id "$U" right_start)
      if [ -n "$C" ]; then
        note "实例未运行，点 Start 并等待就绪…"
        # ★ 点一次可能不生效：第 3 组刚 force-stop 过，主壳的点击层要等一次完整重绘。
        #   实测踩到：tap 发出去了，但界面没进「Starting dsh」，node 也没起，
        #   而主壳的 Ready 是**陈旧状态**（上次留下的），看起来像成功。
        #   所以点完必须验证"真的进了启动页 / 真的开始跑了"，没进就重点。
        attempt=0
        while [ $attempt -lt 3 ]; do
          sh "$ADB" shell input tap $C >/dev/null 2>&1
          # ★ 判据要给足时间：实测主壳点 Start 后，启动页/门禁框要 5~8 秒才画出来。
          #   写 4 秒会误判成"点击未生效"，然后重点三次 —— 第三次正好把已经在跑的实例又停了。
          #   这是第一版真实踩到的坑：日志显示「点击未生效 ×3」，但 node 进程其实起过。
          sleep 8; U=$(dump_ui)
          printf '%s' "$U" | grep -qE 'Starting dsh|正在启动|System WebView is too old|Link copied|WebView 版本过旧|链接已复制' && break

          attempt=$((attempt+1))
          [ $attempt -lt 3 ] && note "点击未生效，重点 Start（第 $((attempt+1)) 次）"
          C=$(center_of_id "$U" right_start)
          [ -z "$C" ] && break
        done
        # 就绪的判据有三个，任一命中即可：
        #   ① 直接弹出 WebView 门禁框（本例：WebView 114 < 119，启动即弹）
        #   ② 落到「已复制」面板（上一次测试留下的状态）
        #   ③ 右栏 Open UI 变为可用
        # ★ 首次装完 dsh 后要跑 npm/解包，实测可能 >90 秒；给到 240 秒。
        i=0
        while [ $i -lt 240 ]; do
          sleep 3; U=$(dump_ui)
          printf '%s' "$U" | grep -qE 'System WebView is too old|Link copied|WebView 版本过旧|链接已复制' && break
          [ "$(enabled_of "$U" right_webui)" = "true" ] && break
          i=$((i+3))
        done
        if [ $i -ge 240 ]; then
          bad "点 Start 后 240 秒仍未就绪 —— 实例没起来（先看主壳右栏的失败原因或日志页）"
          note "当前屏：$(printf '%s' "$U" | grep -oE 'text="[^"]{3,}"' | head -4 | tr '\n' ' ')"
        fi
      fi
    fi
  fi
  C=$(center_of_id "$U" right_webui)
  # ★ 若 Open UI 仍不可用（实例没起来 / 启动失败），不要再往下走成"没有门禁框"的假绿。
  if [ -z "$C" ] && ! printf '%s' "$U" | grep -qE 'System WebView is too old|Link copied'; then
    bad "拿不到 Open UI 按钮且屏上没有门禁框 —— 无法验证第 6 条（实例未就绪？）"
    note "当前屏：$(printf '%s' "$U" | grep -oE 'text="[^"]{3,}"' | head -4 | tr '\n' ' ')"
    NAV_FAILED=1
  fi
  if [ -n "$C" ]; then
    sh "$ADB" shell input tap $C >/dev/null 2>&1
    sleep 5
  else
    note "找不到 Open UI 按钮（可能已在别的页面）"
  fi
fi

UIDUMP=$(dump_ui)
if [ "${NAV_FAILED:-0}" = "1" ]; then
  : # 上面已经 bad 过了，不再重复判定（否则一个故障报两条）
elif [ -z "$UIDUMP" ]; then
  skip "uiautomator dump 拿不到内容"
elif printf '%s' "$UIDUMP" | grep -qE 'System WebView is too old|WebView 版本过旧'; then
  ok "门禁框正在显示（WebView < 119）"

  # ---- 复制链接 → 不该重弹门禁（fix22 的核心） ----
  COPY_BTN=""
  for t in "Copy link" "复制链接"; do
    C=$(center_of "$UIDUMP" "$t")
    [ -n "$C" ] && { COPY_BTN="$C"; break; }
  done

  if [ -z "$COPY_BTN" ]; then
    skip "找不到「Copy link」按钮，跳过复制流程"
  else
    sh "$ADB" shell input tap $COPY_BTN >/dev/null 2>&1
    sleep 3
    UIDUMP=$(dump_ui)

    CONF=""
    for t in "Copy" "复制"; do
      C=$(center_of "$UIDUMP" "$t")
      [ -n "$C" ] && { CONF="$C"; break; }
    done

    if [ -z "$CONF" ]; then
      skip "没等到复制确认框"
    else
      sh "$ADB" shell input tap $CONF >/dev/null 2>&1
      sleep 4
      UIDUMP=$(dump_ui)

      if printf '%s' "$UIDUMP" | grep -qE 'Link copied|链接已复制'; then
        ok "复制成功后落到「已复制」面板"
        # ★ 整个 fix22 第 6 条的意义就在这里：改之前，这一步会重新弹出同一个门禁框
        if printf '%s' "$UIDUMP" | grep -qE 'System WebView is too old|WebView 版本过旧'; then
          bad "门禁框又弹回来了 —— fix22 的 DeferredPanel 没生效"
        else
          ok "门禁框没有重弹（fix22 生效）"
        fi

        # Retry 必须真的能回到门禁（否则是死按钮）
        RT=""
        for t in "Retry" "重试"; do
          C=$(center_of "$UIDUMP" "$t")
          [ -n "$C" ] && { RT="$C"; break; }
        done
        if [ -n "$RT" ]; then
          sh "$ADB" shell input tap $RT >/dev/null 2>&1
          sleep 5
          UIDUMP=$(dump_ui)
          if printf '%s' "$UIDUMP" | grep -qE 'System WebView is too old|WebView 版本过旧'; then
            ok "点「Retry」后门禁框正常重新出现（不是死按钮）"
          else
            bad "点「Retry」后门禁框没回来 —— gateCopied 没被清除"
          fi
        else
          skip "找不到 Retry 按钮"
        fi
      else
        bad "复制后没有落到「已复制」面板"
        note "当前屏：$(printf '%s' "$UIDUMP" | grep -oE 'text="[^"]{4,}"' | head -3 | tr '\n' ' ')"
      fi
    fi
  fi
else
  ok "没有门禁框（WebView 版本达标，或已放行）"
fi

# ---------------------------------------------------------------- 6. 截图
if [ "${SHOTS:-0}" = "1" ]; then
  echo
  echo "== 6. 截图 =="
  mkdir -p "$ROOT/output/shots"
  SHOT="$ROOT/output/shots/$(date +%Y%m%d-%H%M%S)-e2e.png"
  if sh "$ADB" shell screencap -p /sdcard/e2e-shot.png >/dev/null 2>&1 \
     && sh "$ADB" pull /sdcard/e2e-shot.png "$SHOT" >/dev/null 2>&1; then
    ok "截图已存：$SHOT"
  else
    note "截图失败（不影响断言）"
  fi
  sh "$ADB" shell rm -f /sdcard/e2e-shot.png >/dev/null 2>&1 || true
fi

sh "$ADB" shell rm -f "$UI" >/dev/null 2>&1 || true

# ---------------------------------------------------------------- 汇总
echo
echo "==== 结果: PASS=$PASS FAIL=$FAIL SKIP=$SKIP ===="
if [ "$FAIL" -ne 0 ]; then
  echo "提示：出问题时先看 run-as 拉下来的 runtime.log；"
  echo "     下列命令能拿到 App 侧异常：sh tools/adb.sh logcat -d | grep -i dsh"
  exit 1
fi
echo "提示：本脚本覆盖 Android UI / 生命周期。"
echo "      dsh 侧的 HTTP 行为（缓存头 / picker）用 tools/host-dsh-check.sh。"
exit 0
