@echo off
rem ============================================================
rem  HotPluginLoader - patch dex only (reuses build\libs\*.jar)
rem  Full build (jar + dex):  build.bat
rem ============================================================
setlocal
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0build.ps1" -DexOnly %*
set "RC=%ERRORLEVEL%"
pause
exit /b %RC%
