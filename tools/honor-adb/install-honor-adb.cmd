@echo off
REM ============================================================
REM  install-honor-adb.cmd —— 第一次重启**之后**运行本脚本（右键「以管理员身份运行」）
REM
REM 它会：
REM   1) 装改过的 android_winusb_honor.inf（含荣耀 VID_12D1&PID_108A&MI_02）
REM   2) 把出错的 HDB 接口强制绑到 WinUSB
REM   3) 启动 adb 并列出设备
REM
REM 前提：已执行过 `bcdedit /set testsigning on` 并**重启**
REM      （桌面右下角应显示"测试模式 Windows 10"水印）
REM ============================================================
setlocal
cd /d "%~dp0"

echo === 0. 检查测试模式 ===
bcdedit /enum {current} | findstr /i "testsigning Yes" >nul
if errorlevel 1 (
  echo [X] testsigning 未生效。请先执行:
  echo     bcdedit /set testsigning on
  echo     然后重启。
  pause
  exit /b 1
)
echo [OK] testsigning = Yes

echo.
echo === 1. 安装驱动包 ===
pnputil /add-driver "%~dp0android_winusb_honor.inf" /install
if errorlevel 1 (
  echo [X] 驱动安装失败
  pause
  exit /b 1
)
echo [OK] 驱动已入库

echo.
echo === 2. 把 HDB 接口绑到 WinUSB ===
REM 用 pnputil 的 /enable-device 不行，这里用设备管理器同款接口：
REM 通过 devcon 缺失时，用 PowerShell 的 Update-PnpDeviceDriver 替代（PS7）或直接
REM 让 Windows 自己挑新入库的驱动。
powershell -NoProfile -Command ^
  "$d = Get-PnpDevice -PresentOnly | Where-Object { $_.InstanceId -like '*VID_12D1*MI_02*' };" ^
  "if (-not $d) { Write-Host '[X] 找不到 HDB 接口（手机是否插着？）'; exit 1 };" ^
  "Write-Host ('当前状态: ' + $d.Status);" ^
  "Disable-PnpDevice -InstanceId $d.InstanceId -Confirm:$false -ErrorAction SilentlyContinue;" ^
  "Start-Sleep -Seconds 2;" ^
  "Enable-PnpDevice -InstanceId $d.InstanceId -Confirm:$false -ErrorAction SilentlyContinue;" ^
  "Start-Sleep -Seconds 4;" ^
  "$a = Get-PnpDevice -PresentOnly | Where-Object { $_.InstanceId -eq $d.InstanceId };" ^
  "Write-Host ('重启后状态: ' + $a.Status + ' | 类: ' + $a.Class)"

echo.
echo === 3. 启动 adb ===
"D:\Android\Sdk\platform-tools\adb.exe" kill-server >nul 2>&1
"D:\Android\Sdk\platform-tools\adb.exe" start-server
timeout /t 3 /nobreak >nul
"D:\Android\Sdk\platform-tools\adb.exe" devices -l

echo.
echo ============================================================
echo  如果上面 "devices" 还是空的：
echo    a) 手机上会弹「允许 USB 调试吗」——点「允许」（勾选"始终允许"）
echo    b) 若没弹：设置 - 系统和更新 - 开发人员选项 - 撤销 USB 调试授权，重插
echo    c) 仍然不行就装荣耀官方驱动（荣耀手机助手 / HiSuite），它更完整
echo ============================================================
pause
