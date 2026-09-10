# Game Nuke — Project Cleanup Utility
$ErrorActionPreference = "Continue"

$RootDir = Split-Path -Parent $PSScriptRoot

Write-Host "Running Game Nuke project cleanup..." -ForegroundColor Cyan

# 1. Remove obsolete temporary archives and text dumps
$JunkFiles = @(
    "$RootDir\GameNukeChatgpt.zip",
    "$RootDir\VALIDATION_RESULT.txt",
    "$RootDir\implementation_plan.md"
)

foreach ($f in $JunkFiles) {
    if (Test-Path $f) {
        Remove-Item $f -Force
        Write-Host "   - Removed: $(Split-Path $f -Leaf)" -ForegroundColor Yellow
    }
}

# 2. Clean obsolete APK releases (keep only active v2.6.0)
$OldApks = @(
    "$RootDir\release-apk\GameNuke-Premium-v2.2.0.apk",
    "$RootDir\release-apk\GameNuke-Premium-v2.3.0.apk",
    "$RootDir\release-apk\GameNuke-Premium-v2.4.0.apk",
    "$RootDir\release-apk\GameNuke-Premium-v2.5.0.apk"
)

foreach ($apk in $OldApks) {
    if (Test-Path $apk) {
        $sizeMb = [math]::Round((Get-Item $apk).Length / 1MB, 1)
        Remove-Item $apk -Force
        Write-Host "   - Removed obsolete APK: $(Split-Path $apk -Leaf) ($sizeMb MB)" -ForegroundColor Yellow
    }
}

Write-Host "Cleanup completed successfully!" -ForegroundColor Green
