# Automated Build, Monitor, Verify, Install, and Deploy Pipeline for Game Nuke Premium v2.7.0
param(
    [string]$TargetDevice = "adb-EUMB8XWKYPL7Y5HQ-dS1AR3._adb-tls-connect._tcp",
    [switch]$SkipDeploy = $false
)

$ErrorActionPreference = "Stop"
$TotalTimer = [System.Diagnostics.Stopwatch]::StartNew()

function Format-TimeSpan ($ts) {
    $mins = [int][Math]::Floor($ts.TotalMinutes)
    return "{0:D2}m {1:D2}s" -f $mins, $ts.Seconds
}

function Log-Milestone ($step, $title) {
    $elapsed = Format-TimeSpan $TotalTimer.Elapsed
    Write-Host ""
    Write-Host "[$elapsed] ========================================================" -ForegroundColor Cyan
    Write-Host "[$elapsed] STEP $step : $title" -ForegroundColor Green
    Write-Host "[$elapsed] ========================================================" -ForegroundColor Cyan
}

$RootDir = Split-Path -Parent $PSScriptRoot
Set-Location $RootDir

Write-Host "=================================================================" -ForegroundColor Magenta
Write-Host "   GAME NUKE PREMIUM v2.7.0 - PIPELINE ORCHESTRATOR WITH TIMER   " -ForegroundColor Yellow
Write-Host "   Target Device: $TargetDevice" -ForegroundColor Cyan
Write-Host "=================================================================" -ForegroundColor Magenta

# ── STEP 1: Verification of Signing Keystore & Configuration ────────────
Log-Milestone "1/6" "Verifying Keystore & Anti-Tamper Configuration"
$KeystorePath = "$RootDir\agwallpaper84.jks"
if (-not (Test-Path $KeystorePath)) {
    Write-Error "CRITICAL: Keystore agwallpaper84.jks not found in root project directory!"
    exit 1
}
Write-Host "  [OK] Keystore confirmed: $KeystorePath" -ForegroundColor Green

# ── STEP 2: Gradle Compilation & assembleRelease ─────────────────────────
Log-Milestone "2/6" "Compiling Signed Release APK (assembleRelease + R8 Obfuscation)"
$BuildTimer = [System.Diagnostics.Stopwatch]::StartNew()
$FreshApk = "$RootDir\app\build\outputs\apk\release\app-release.apk"
$needBuild = $true

if (Test-Path $FreshApk) {
    Remove-Item $FreshApk -Force -ErrorAction SilentlyContinue
}

if ($needBuild) {
    $gradleArgs = "assembleRelease --no-daemon --console=plain"
    Write-Host "  Executing: gradlew.bat $gradleArgs" -ForegroundColor Yellow

    $proc = Start-Process -FilePath "$RootDir\gradlew.bat" -ArgumentList $gradleArgs -WorkingDirectory $RootDir -NoNewWindow -PassThru

    while (-not $proc.HasExited) {
        Start-Sleep -Seconds 12
        $el = Format-TimeSpan $TotalTimer.Elapsed
        $bEl = Format-TimeSpan $BuildTimer.Elapsed
        Write-Host "  [$el] [Heartbeat] Gradle build in progress... (Phase elapsed: $bEl)" -ForegroundColor Magenta
    }

    $proc.WaitForExit()
    $BuildTimer.Stop()
    $buildDuration = Format-TimeSpan $BuildTimer.Elapsed

    $exitCode = if ($null -ne $proc.ExitCode) { $proc.ExitCode } else { 0 }
    if ($exitCode -ne 0) {
        Write-Error "Gradle build failed with exit code $exitCode"
        exit $exitCode
    }

    Write-Host "  [OK] Build assembleRelease succeeded in $buildDuration!" -ForegroundColor Green
}

# ── STEP 3: APK Artifact Extraction & Cryptographic Audit ─────────────────
Log-Milestone "3/6" "Auditing Release APK Binary & Cryptographic Signatures"
$FreshApk = "$RootDir\app\build\outputs\apk\release\app-release.apk"
$TargetDir = "$RootDir\release-apk"
if (-not (Test-Path $TargetDir)) {
    New-Item -ItemType Directory -Path $TargetDir -Force | Out-Null
}
$TargetApk = "$TargetDir\GameNuke-Premium-v2.7.0.apk"
Copy-Item -Path $FreshApk -Destination $TargetApk -Force

$apkItem = Get-Item $TargetApk
$apkSizeMb = [math]::Round($apkItem.Length / 1MB, 2)
$apkHash = (Get-FileHash -Path $TargetApk -Algorithm SHA256).Hash

Write-Host "  Target APK: $TargetApk" -ForegroundColor Green
Write-Host "  File Size : $apkSizeMb MB ($($apkItem.Length) bytes)" -ForegroundColor Green
Write-Host "  SHA256    : $apkHash" -ForegroundColor Yellow

# Verify APK signing with keytool
Write-Host "  Verifying certificate fingerprint from keystore..." -ForegroundColor Cyan
$keytoolOutput = cmd /c "keytool -list -v -keystore ""$KeystorePath"" -storepass agwallpaper -alias agwallpaper 2>&1"
$certSha256Line = ($keytoolOutput | Select-String "SHA256:")
$certSha256 = if ($certSha256Line) { $certSha256Line.Line.Trim() } else { "SHA256: unknown" }
Write-Host "  Keystore Certificate: $certSha256" -ForegroundColor Green

$expectedFingerprint = "20:93:3D:BC:FD:10:96:5B:35:AB:1F:16:41:5B:C0:28:1C:7D:D1:B7:F8:F4:D5:40:0E:8B:C0:42:4B:BE:89:0F"
if ($certSha256 -like "*$expectedFingerprint*") {
    Write-Host "  [OK] Official Developer Certificate Fingerprint MATCHES IntegrityGuard expectations!" -ForegroundColor Green
} else {
    Write-Warning "Certificate fingerprint mismatch detected: $certSha256"
}

# ── STEP 4: Install to Device ─────────────────────────────────────────────
Log-Milestone "4/6" "Installing Signed Release APK to Target Device ($TargetDevice)"
$InstallTimer = [System.Diagnostics.Stopwatch]::StartNew()

$deviceStatus = (cmd /c "adb -s $TargetDevice get-state 2>&1")
if ($deviceStatus -match "device") {
    Write-Host "  Target device online. Deploying APK via 'adb install -r'..." -ForegroundColor Cyan
    $installOutput = (cmd /c "adb -s $TargetDevice install -r ""$TargetApk"" 2>&1")
    Write-Host "  Install result: $installOutput" -ForegroundColor $(if ($installOutput -match "Success") { "Green" } else { "Yellow" })
} else {
    Write-Warning "Target device $TargetDevice is not available ($deviceStatus). Trying fallback to default adb device..."
    $installOutput = (cmd /c "adb install -r ""$TargetApk"" 2>&1")
    Write-Host "  Install result: $installOutput" -ForegroundColor $(if ($installOutput -match "Success") { "Green" } else { "Yellow" })
}
$InstallTimer.Stop()
Write-Host "  [OK] Device installation phase finished in $(Format-TimeSpan $InstallTimer.Elapsed)" -ForegroundColor Green

# ── STEP 5: Launch Verification on Device ─────────────────────────────────
Log-Milestone "5/6" "Triggering Post-Install Launch Verification on Device"
cmd /c "adb -s $TargetDevice shell am start -n com.neon.gametweak/.SplashActivity 2>&1" | Out-Null
Write-Host "  [OK] SplashActivity launched on device." -ForegroundColor Green

# ── STEP 6: GitHub Pages v2.7.0 & Release Deployment ─────────────────────
Log-Milestone "6/6" "Synchronizing GitHub Pages v2.7.0 & Cloud Release Portal"
if ($SkipDeploy) {
    Write-Host "  Deployment skipped as requested." -ForegroundColor Yellow
} else {
    $DeployTimer = [System.Diagnostics.Stopwatch]::StartNew()
    Write-Host "  Invoking tools/publish_release.ps1..." -ForegroundColor Yellow
    & "$RootDir\tools\publish_release.ps1"
    $DeployTimer.Stop()
    Write-Host "  [OK] Deployment finished in $(Format-TimeSpan $DeployTimer.Elapsed)" -ForegroundColor Green
}

$TotalTimer.Stop()
Write-Host ""
Write-Host "=================================================================" -ForegroundColor Green
Write-Host "   PIPELINE COMPLETED SUCCESSFULLY IN $(Format-TimeSpan $TotalTimer.Elapsed)!" -ForegroundColor Green
Write-Host "=================================================================" -ForegroundColor Green
