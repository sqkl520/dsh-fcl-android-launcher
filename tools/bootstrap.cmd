@echo off
REM bootstrap.cmd -- rebuild the shared-worktree layout on a new PC (calls bootstrap.sh).
REM ASCII-only on purpose: cmd.exe mangles non-ASCII in .cmd files.
REM Usage: bootstrap.cmd <target-folder> [--repo URL] [--snapshot URL]
REM        bootstrap.cmd --install-excludes
REM        bootstrap.cmd --check
setlocal
set "SH=%~dp0bootstrap.sh"
where bash >nul 2>&1 || (echo !! bash not found ^(ships with Git for Windows^) & exit /b 1)
bash "%SH%" %*
exit /b %ERRORLEVEL%
