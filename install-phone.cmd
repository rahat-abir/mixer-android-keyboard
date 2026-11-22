@echo off
rem Builds the optimised Mixer APK and installs it on the USB-connected phone, then makes it the active keyboard.
setlocal
set "ROOT=%~dp0"
set "ADB=%ROOT%.tools\android-sdk\platform-tools\adb.exe"

call "%ROOT%gw.cmd" -p "%ROOT%." assembleRelease --console=plain -q || exit /b 1
"%ADB%" install -r "%ROOT%app\build\outputs\apk\release\app-release.apk"
if errorlevel 1 (
  echo.
  echo Install failed. If it says INSTALL_FAILED_UPDATE_INCOMPATIBLE, the phone has a copy signed
  echo with a different key. Uninstall it once ^(this clears learned words^) and run this again:
  echo   "%ADB%" uninstall dev.banglish.keyboard
  exit /b 1
)
"%ADB%" shell ime enable dev.banglish.keyboard/.KeyboardService
"%ADB%" shell ime set dev.banglish.keyboard/.KeyboardService
echo.
echo Mixer is installed and selected. Open any text box on the phone to use it.
