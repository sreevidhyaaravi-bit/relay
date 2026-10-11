# Installs (or updates) RELAY on every Android phone connected over USB.
# Usage (PowerShell, from the relay folder):   .\scripts\install-all.ps1
# Optional: pass a different APK path:         .\scripts\install-all.ps1 -Apk C:\path\to\app-debug.apk
param(
    [string]$Apk = "app\build\outputs\apk\debug\app-debug.apk"
)

$ErrorActionPreference = "Stop"

# Find adb: on PATH, or in the default Android SDK / platform-tools locations.
$adb = (Get-Command adb -ErrorAction SilentlyContinue).Source
if (-not $adb) {
    $candidates = @(
        "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe",
        "$env:USERPROFILE\platform-tools\adb.exe",
        "$PSScriptRoot\..\platform-tools\adb.exe"
    )
    $adb = $candidates | Where-Object { Test-Path $_ } | Select-Object -First 1
}
if (-not $adb) {
    Write-Host "adb not found. Install Android SDK Platform-Tools (developer.android.com/tools/releases/platform-tools) or Android Studio." -ForegroundColor Red
    exit 1
}
if (-not (Test-Path $Apk)) {
    Write-Host "APK not found at $Apk. Build it first:  .\gradlew.bat assembleDebug" -ForegroundColor Red
    exit 1
}

$devices = & $adb devices | Select-String -Pattern "^\S+\s+device$" | ForEach-Object { ($_ -split "\s+")[0] }
if (-not $devices) {
    Write-Host "No phone found. Connect a phone with USB, enable USB debugging, and accept the prompt on the phone." -ForegroundColor Yellow
    & $adb devices -l
    exit 1
}

foreach ($serial in $devices) {
    $model = (& $adb -s $serial shell getprop ro.product.model).Trim()
    Write-Host "Installing on $model ($serial)..."
    & $adb -s $serial install -r $Apk
    if ($LASTEXITCODE -eq 0) {
        Write-Host "  OK" -ForegroundColor Green
    } else {
        Write-Host "  FAILED. If it says INSTALL_FAILED_UPDATE_INCOMPATIBLE, uninstall RELAY from that phone first." -ForegroundColor Red
    }
}
