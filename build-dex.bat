@echo off
rem ============================================================
rem  HotPluginLoader - patch dex only (reuses build\libs\*.jar)
rem  Thin wrapper around Gradle:  gradlew.bat dex
rem  Full build (jar + dex):      build.bat  /  gradlew.bat build
rem ============================================================
setlocal
call "%~dp0gradlew.bat" dex %*
set "RC=%ERRORLEVEL%"
pause
exit /b %RC%
