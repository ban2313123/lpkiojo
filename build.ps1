# PowerShell script to download gradle-wrapper.jar and build the mod
Write-Host "============================================================" -ForegroundColor Cyan
Write-Host "  Temporary Portal Mod - NeoForge 1.21.1 (PowerShell Build)  " -ForegroundColor Cyan
Write-Host "============================================================" -ForegroundColor Cyan

$wrapperJar = "$PSScriptRoot\gradle\wrapper\gradle-wrapper.jar"
$wrapperDir = "$PSScriptRoot\gradle\wrapper"

if (!(Test-Path $wrapperDir)) {
    New-Item -ItemType Directory -Force -Path $wrapperDir | Out-Null
}

if (!(Test-Path $wrapperJar)) {
    Write-Host "[1/2] Downloading gradle-wrapper.jar..." -ForegroundColor Yellow
    [Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12
    try {
        Invoke-WebRequest -Uri "https://raw.githubusercontent.com/gradle/gradle/master/gradle/wrapper/gradle-wrapper.jar" -OutFile $wrapperJar
        Write-Host "Downloaded successfully!" -ForegroundColor Green
    } catch {
        Write-Host "Trying fallback mirror..." -ForegroundColor Yellow
        Invoke-WebRequest -Uri "https://repo.maven.apache.org/maven2/org/gradle/gradle-wrapper/8.10.2/gradle-wrapper-8.10.2.jar" -OutFile $wrapperJar
    }
}

Write-Host "[2/2] Running build via gradlew.bat..." -ForegroundColor Yellow
& "$PSScriptRoot\gradlew.bat" build
