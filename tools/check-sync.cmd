@echo off
REM check-sync.cmd -- consistency check on PC (calls check-sync.sh next to it).
REM ASCII-only on purpose: cmd.exe mangles non-ASCII in .cmd files.
REM Usage: check-sync.cmd [--offline]
setlocal
set "SH=%~dp0check-sync.sh"
where bash >nul 2>&1 || (echo !! bash not found ^(ships with Git for Windows^); run "sh check-sync.sh" in Git Bash instead & exit /b 1)
bash "%SH%" %*
exit /b %ERRORLEVEL%
