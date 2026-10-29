@echo off
rem ============================================================
rem  HotPluginLoader - full build (plugin jar + patch dex)
rem  This is only a thin wrapper around Gradle:
rem      gradlew.bat build       -> build\libs\*.jar + out\game.dex.zip
rem      gradlew.bat shadowJar   -> jar only
rem      gradlew.bat dex         -> dex only
rem  Paths: AOH_GAME_ROOT / ANDROID_HOME / JAVA_HOME (see build.gradle)
rem ============================================================
setlocal
call "%~dp0gradlew.bat" build %*
set "RC=%ERRORLEVEL%"
pause
exit /b %RC%
