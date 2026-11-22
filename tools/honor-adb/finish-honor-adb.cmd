@echo off
REM ============================================================
REM  finish-honor-adb.cmd —— **驱动装好、确认能用之后**运行（右键「以管理员身份运行」）
REM
REM 作用：关闭测试模式，然后提示你再重启一次。
REM       测试模式会降低系统驱动签名要求，装完就该关掉。
REM ============================================================
setlocal

echo === 关闭测试模式 ===
bcdedit /set testsigning off
if errorlevel 1 (
  echo [X] 失败（需要管理员权限）
  pause
  exit /b 1
)
echo [OK] 已设为 off

echo.
echo === 当前引导项 ===
bcdedit /enum {current} | findstr /i "testsigning"

echo.
echo ============================================================
echo  请**重启电脑**让设置生效。
echo  重启后右上角"测试模式"水印应该消失，ADB 仍然可用。
echo ============================================================
pause
