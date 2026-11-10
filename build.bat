@echo off
setlocal EnableDelayedExpansion
title Temporary Portal Mod - NeoForge 1.21.1 Builder

echo ============================================================
echo   Temporary Portal Mod - NeoForge 1.21.1 (Windows Builder)
echo ============================================================
echo.

where java >nul 2>nul
if errorlevel 1 (
    echo [ERROR] Java is not found in your PATH!
    echo Please install JDK 21 and make sure java is added to system PATH.
    pause
    exit /b 1
)

echo [1/3] Checking Java version...
java -version
echo.

echo [2/3] Checking Gradle Wrapper...
if not exist "%~dp0gradle\wrapper\gradle-wrapper.jar" (
    echo [INFO] gradle-wrapper.jar is missing. Downloading standard Gradle wrapper...
    powershell -NoProfile -ExecutionPolicy Bypass -Command "[Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12; Invoke-WebRequest -Uri 'https://raw.githubusercontent.com/gradle/gradle/master/gradle/wrapper/gradle-wrapper.jar' -OutFile '%~dp0gradle\wrapper\gradle-wrapper.jar'"
)

if not exist "%~dp0gradle\wrapper\gradle-wrapper.jar" (
    echo [INFO] Alternative download via services.gradle.org...
    powershell -NoProfile -ExecutionPolicy Bypass -Command "[Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12; Invoke-WebRequest -Uri 'https://repo.maven.apache.org/maven2/org/gradle/gradle-wrapper/8.10.2/gradle-wrapper-8.10.2.jar' -OutFile '%~dp0gradle\wrapper\gradle-wrapper.jar'"
)

if not exist "%~dp0gradle\wrapper\gradle-wrapper.jar" (
    echo.
    echo [ERROR] Could not automatically download gradle-wrapper.jar.
    echo If you have Gradle installed on your PC, you can run: gradle wrapper
    pause
    exit /b 1
)

echo.
echo [3/3] Building NeoForge mod jar...
call "%~dp0gradlew.bat" build

if errorlevel 1 (
    echo.
    echo ============================================================
    echo   [ERROR] Build failed! Check the console output above.
    echo ============================================================
    pause
    exit /b 1
)

echo.
echo ============================================================
echo   [SUCCESS] Build completed successfully!
echo   Your compiled mod .jar is in: build\libs\
echo ============================================================
echo.
pause
