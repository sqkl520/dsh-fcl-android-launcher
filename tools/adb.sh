#!/bin/sh
# tools/adb.sh —— 用绝对路径包装 adb（Git Bash 版），省去把 platform-tools 加进 PATH。
#
# 用法：  sh tools/adb.sh <adb 的任意参数>
# 例：    sh tools/adb.sh devices
#         sh tools/adb.sh install -r output/dsh-fcl-android-launcher-0.1.5-SNAPSHOT-arm64.apk
#         sh tools/adb.sh logcat -c
#
# 参数全部原样透传给真实 adb。路径写死（电脑：D:\Android\Sdk\platform-tools\adb.exe）；
# 若 SDK 装在别处，改下面 ADB= 这一行即可。
set -eu

ADB="D:/Android/Sdk/platform-tools/adb.exe"

if [ ! -f "$ADB" ]; then
  echo "!! 找不到 adb：$ADB" >&2
  echo "   platform-tools 不在此处时，请改本脚本顶部的 ADB= 一行。" >&2
  exit 127
fi

exec "$ADB" "$@"
