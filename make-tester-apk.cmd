@echo off
rem Builds a release APK signed with your release key and copies it to dist\Mixer-<version>.apk,
rem ready to upload to a GitHub Release.
setlocal
set "ROOT=%~dp0"
if not exist "%ROOT%signing\keystore.properties" (
  echo No release key yet. Run first:  powershell -ExecutionPolicy Bypass -File make-release-key.ps1
  exit /b 1
)
call "%ROOT%gw.cmd" -p "%ROOT%." assembleRelease --console=plain -q || exit /b 1
for /f "tokens=2 delims==" %%v in ('findstr /c:"versionName = " "%ROOT%app\build.gradle.kts"') do set "VER=%%~v"
set "VER=%VER: =%"
set "VER=%VER:"=%"
if not exist "%ROOT%dist" mkdir "%ROOT%dist"
copy /y "%ROOT%app\build\outputs\apk\release\app-release.apk" "%ROOT%dist\Mixer-%VER%.apk" >nul
echo.
echo Ready to share: dist\Mixer-%VER%.apk
