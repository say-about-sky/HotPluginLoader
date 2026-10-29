@echo off
rem ============================================================
rem  HotPluginLoader - one-shot build (plugin jar + patch dex)
rem  See build.ps1 for options; extra args are passed through, e.g.
rem    build.bat -GameRoot <game dir> -AndroidSdk <sdk>
rem ============================================================
setlocal
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0build.ps1" %*
set "RC=%ERRORLEVEL%"
pause
exit /b %RC%
