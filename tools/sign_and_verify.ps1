$ErrorActionPreference = "Stop"
$RootDir = Split-Path -Parent $PSScriptRoot
Set-Location $RootDir

$ApkSigner = "$env:LOCALAPPDATA\Android\Sdk\build-tools\35.0.0\apksigner.bat"
$SrcApk = "$RootDir\app\build\outputs\apk\release\app-release.apk"
$TargetApk = "$RootDir\release-apk\GameNuke-v3.6.0-Apeiron.apk"
$WebApk = "$RootDir\gamenukeweb\GameNuke-v3.6.0-Apeiron.apk"

Write-Host "Copying fresh release APK..." -ForegroundColor Cyan
Copy-Item $SrcApk $TargetApk -Force

Write-Host "Signing APK with agwallpaper84.jks (v1, v2, v3 schemes)..." -ForegroundColor Yellow
& $ApkSigner sign `
    --ks "$RootDir\agwallpaper84.jks" `
    --ks-pass "pass:agwallpaper" `
    --ks-key-alias "agwallpaper" `
    --key-pass "pass:agwallpaper" `
    --v1-signing-enabled true `
    --v2-signing-enabled true `
    --v3-signing-enabled true `
    $TargetApk

Write-Host "Verifying signature schemes..." -ForegroundColor Green
& $ApkSigner verify --verbose $TargetApk

Write-Host "Syncing signed APK to gamenukeweb..." -ForegroundColor Cyan
Copy-Item $TargetApk $WebApk -Force
Copy-Item $TargetApk "$RootDir\GameNuke-v3.6.0-Apeiron.apk" -Force

$hash = (Get-FileHash $TargetApk -Algorithm SHA256).Hash
$size = [math]::Round((Get-Item $TargetApk).Length / 1MB, 2)
Write-Host "APK SHA256: $hash" -ForegroundColor Magenta
Write-Host "APK Size: $size MB" -ForegroundColor Magenta
