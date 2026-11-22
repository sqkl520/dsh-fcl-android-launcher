@echo off
REM release.cmd -- full release flow on PC (calls release.sh next to it).
REM ASCII-only on purpose: cmd.exe mangles non-ASCII in .cmd files.
REM Usage: release.cmd [--no-test] [--no-build] [--no-release] [--dry-run] [-m "msg"]
setlocal
set "SH=%~dp0release.sh"
where bash >nul 2>&1 || (echo !! bash not found ^(ships with Git for Windows^) & exit /b 1)
bash "%SH%" %*
exit /b %ERRORLEVEL%
