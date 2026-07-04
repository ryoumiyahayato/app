@echo off
set JAVA_HOME=C:\Program Files\Android\Android Studio\jbr
set PATH=%JAVA_HOME%\bin;%PATH%
set GRADLE_USER_HOME=C:\Users\agcrf\.gradle
cd /d C:\Users\agcrf\Desktop\app\electronic-muyu
echo JAVA_HOME=%JAVA_HOME%
echo GRADLE_USER_HOME=%GRADLE_USER_HOME%
echo === GRADLE START ===
call .\gradlew.bat clean assembleDebug --no-daemon
echo === GRADLE EXIT CODE: %ERRORLEVEL% ===
if exist app\build\outputs\apk\debug\app-debug.apk (
    echo === BUILD SUCCESS ===
) else (
    echo === BUILD FAILED - CHECK LOGS ABOVE ===
)
pause