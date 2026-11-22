@echo off
REM tools\adb.cmd —— wrap adb with an absolute path (cmd.exe), no need to add platform-tools to PATH.
REM
REM Usage:  tools\adb.cmd ^<any adb args^>
REM Example:  tools\adb.cmd devices
REM           tools\adb.cmd install -r output\dsh-fcl-android-launcher-0.1.5-SNAPSHOT-arm64.apk
REM           tools\adb.cmd logcat -c
REM
REM All args are passed straight through to the real adb. The path below is hardcoded;
REM if your SDK lives elsewhere, edit the single `set "ADB=..."` line.
REM (Messages kept ASCII so they render in any cmd.exe codepage.)
setlocal
set "ADB=D:\Android\Sdk\platform-tools\adb.exe"

if not exist "%ADB%" (
  echo [adb.cmd] adb not found: %ADB% 1>&2
  echo [adb.cmd] edit the `set "ADB=..."` line in this script if platform-tools is elsewhere. 1>&2
  exit /b 127
)

"%ADB%" %*
exit /b %ERRORLEVEL%
