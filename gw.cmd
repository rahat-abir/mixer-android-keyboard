@echo off
rem Runs Gradle with the project-local toolchain in .tools (nothing installed on C:).
set "ROOT=%~dp0"
set "JAVA_HOME=%ROOT%.tools\jdk-17"
set "ANDROID_HOME=%ROOT%.tools\android-sdk"
set "ANDROID_USER_HOME=%ROOT%.tools\android-home"
set "GRADLE_USER_HOME=%ROOT%.tools\gradle-home"
set "PATH=%JAVA_HOME%\bin;%ANDROID_HOME%\platform-tools;%PATH%"
call "%ROOT%gradlew.bat" %*
