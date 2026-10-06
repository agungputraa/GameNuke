# ==============================================================================
# Game Nuke Eternity Edition - Full Project Cleanup & Master Backup Utility
# Removes junk, old decompilation dumps, obsolete APKs, and creates a pristine ZIP.
# ==============================================================================

param(
    [string]$BackupZipPath = "c:\ProyekAndroid\GameNuke_Eternity_Backup_v3.5.0.zip",
    [switch]$SkipGradleClean = $false
)

$ErrorActionPreference = "Continue"
$RootDir = Split-Path -Parent $PSScriptRoot
Set-Location $RootDir

$TotalStopwatch = [System.Diagnostics.Stopwatch]::StartNew()

function Format-Bytes ($bytes) {
    if ($bytes -ge 1GB) { return "{0:N2} GB" -f ($bytes / 1GB) }
    if ($bytes -ge 1MB) { return "{0:N2} MB" -f ($bytes / 1MB) }
    if ($bytes -ge 1KB) { return "{0:N2} KB" -f ($bytes / 1KB) }
    return "$bytes Bytes"
}

Write-Host ""
Write-Host "================================================================================" -ForegroundColor Cyan
Write-Host "   GAME NUKE ETERNITY EDITION - FULL PROJECT AUDIT, CLEANUP & BACKUP            " -ForegroundColor Yellow
Write-Host "================================================================================" -ForegroundColor Cyan
Write-Host "  Project Root : $RootDir" -ForegroundColor White
Write-Host "  Target Backup: $BackupZipPath" -ForegroundColor White
Write-Host "  Started At   : $(Get-Date -Format 'yyyy-MM-dd HH:mm:ss')" -ForegroundColor Gray
Write-Host "--------------------------------------------------------------------------------" -ForegroundColor DarkCyan

$freedBytes = 0
$deletedCount = 0

# -------------------------------------------------------------
# STEP 1: CLEANING DECOMPILED DUMPS & JUNK DIRECTORIES
# -------------------------------------------------------------
Write-Host "[PHASE 1/5] Removing Obsolete Decompiled Dumps & Temporary Folders..." -ForegroundColor Green

$JunkDirs = @(
    "$RootDir\dump",
    "$RootDir\DumpReference"
)

foreach ($dir in $JunkDirs) {
    if (Test-Path $dir) {
        $files = Get-ChildItem -Path $dir -Recurse -File -Force -ErrorAction SilentlyContinue
        $size = ($files | Measure-Object -Property Length -Sum).Sum
        $count = $files.Count
        if ($size) { $freedBytes += $size }
        $deletedCount += $count
        Remove-Item $dir -Recurse -Force -ErrorAction SilentlyContinue
        Write-Host "  [CLEANED] Directory $(Split-Path $dir -Leaf) ($(Format-Bytes $size), $count files)" -ForegroundColor Yellow
    }
}

# -------------------------------------------------------------
# STEP 2: CLEANING OBSOLETE HISTORICAL APKS & SCREENSHOT DUMPS
# -------------------------------------------------------------
Write-Host ""
Write-Host "[PHASE 2/5] Purging Obsolete Historical APKs & Root Screenshot Dumps..." -ForegroundColor Green

$JunkFiles = @(
    # Obsolete root APKs
    "$RootDir\GameNuke-v3.2.1-Spectra.apk",
    "$RootDir\GameNuke-v3.3.0-Hyperion.apk",
    "$RootDir\GameNuke-v3.4.0-Nexus.apk",
    # Obsolete root zips & images
    "$RootDir\GameNukeChatgpt.zip",
    "$RootDir\GameNuke_Source.zip",
    "$RootDir\gamenuke_screen.png",
    "$RootDir\gamenuke_screen2.png",
    "$RootDir\implementation_plan.md",
    # Scratch obsolete APK & dumps
    "$RootDir\scratch\GameNuke-v3.4.0-Nexus.apk"
)

# Obsolete APKs in release-apk (Keep only active v3.5.0-Eternity)
$OldReleaseApks = @(
    "$RootDir\release-apk\GameNuke-Premium-v2.7.0.apk",
    "$RootDir\release-apk\GameNuke-v2.8.0-Quasar.apk",
    "$RootDir\release-apk\GameNuke-v2.9.0-Void.apk",
    "$RootDir\release-apk\GameNuke-v3.0.0-Vortex.apk",
    "$RootDir\release-apk\GameNuke-v3.1.0-Hypernova.apk",
    "$RootDir\release-apk\GameNuke-v3.1.1-Zenith.apk",
    "$RootDir\release-apk\GameNuke-v3.2.0-Orion.apk",
    "$RootDir\release-apk\GameNuke-v3.2.1-Spectra.apk",
    "$RootDir\release-apk\GameNuke-v3.3.0-Hyperion.apk",
    "$RootDir\release-apk\GameNuke-v3.4.0-Nexus.apk",
    "$RootDir\release-apk\GameNuke-v3.4.0-Nexus.apk.idsig"
)

$AllJunkFiles = $JunkFiles + $OldReleaseApks

foreach ($f in $AllJunkFiles) {
    if (Test-Path $f) {
        $item = Get-Item $f
        $freedBytes += $item.Length
        $deletedCount++
        Remove-Item $f -Force -ErrorAction SilentlyContinue
        Write-Host "  [REMOVED] $(Split-Path $f -Leaf) ($(Format-Bytes $item.Length))" -ForegroundColor Yellow
    }
}

# Clean temporary PNGs and log files in scratch directory
if (Test-Path "$RootDir\scratch") {
    $scratchJunk = Get-ChildItem "$RootDir\scratch" -Include "*.png", "*.log", "*.txt", "test_*.ps1", "test_*.py" -File
    foreach ($sf in $scratchJunk) {
        $freedBytes += $sf.Length
        $deletedCount++
        Remove-Item $sf.FullName -Force -ErrorAction SilentlyContinue
    }
    Write-Host "  [CLEANED] Scratch temporary test artifacts and screenshot dumps" -ForegroundColor Yellow
}

Write-Host "  Total Space Recovered: $(Format-Bytes $freedBytes) ($deletedCount files purged)" -ForegroundColor Green

# -------------------------------------------------------------
# STEP 3: GRADLE CLEAN & BUILD CACHE PURGE
# -------------------------------------------------------------
Write-Host ""
Write-Host "[PHASE 3/5] Purging Gradle Build Caches for Pristine Backup..." -ForegroundColor Green

$BuildDirs = @(
    "$RootDir\app\build",
    "$RootDir\.gradle",
    "$RootDir\.kotlin"
)

foreach ($b in $BuildDirs) {
    if (Test-Path $b) {
        $bFiles = Get-ChildItem -Path $b -Recurse -File -Force -ErrorAction SilentlyContinue
        $bSize = ($bFiles | Measure-Object -Property Length -Sum).Sum
        Remove-Item $b -Recurse -Force -ErrorAction SilentlyContinue
        Write-Host "  [CLEANED] Cache folder $(Split-Path $b -Leaf) ($(Format-Bytes $bSize))" -ForegroundColor DarkCyan
    }
}

# -------------------------------------------------------------
# STEP 4: CREATING MASTER BACKUP ZIP ARCHIVE
# -------------------------------------------------------------
Write-Host ""
Write-Host "[PHASE 4/5] Compressing Complete Project into High-Efficiency ZIP Archive..." -ForegroundColor Green

if (Test-Path $BackupZipPath) {
    Remove-Item $BackupZipPath -Force -ErrorAction SilentlyContinue
}

$BackupDir = Split-Path $BackupZipPath -Parent
if ($BackupDir -and -not (Test-Path $BackupDir)) {
    New-Item -ItemType Directory -Path $BackupDir -Force | Out-Null
}

# Staging directory to guarantee completely clean zip structure
$stagingDir = "c:\ProyekAndroid\_backup_staging\GameNukePrem"
if (Test-Path "c:\ProyekAndroid\_backup_staging") {
    Remove-Item "c:\ProyekAndroid\_backup_staging" -Recurse -Force -ErrorAction SilentlyContinue
}
New-Item -ItemType Directory -Path $stagingDir -Force | Out-Null

Write-Host "  Staging verified project files (source, resources, keystores, configs, web, tools)..." -ForegroundColor Cyan

# Robocopy with exclusions: exclude .git, build, caches
# Keep all code, assets, keystores (agwallpaper84.jks), web, worker, release-apk
robocopy $RootDir $stagingDir /MIR /XD .git .gradle .kotlin build app\build _backup_staging /XF *.log *.tmp /NDL /NFL /NJH /NJS
if ($LASTEXITCODE -ge 8) {
    Write-Warning "Robocopy returned exit code $LASTEXITCODE during staging."
}

Write-Host "  Compressing staged project into ZIP archive: $BackupZipPath..." -ForegroundColor Yellow

Add-Type -AssemblyName System.IO.Compression.FileSystem
[System.IO.Compression.ZipFile]::CreateFromDirectory(
    $stagingDir,
    $BackupZipPath,
    [System.IO.Compression.CompressionLevel]::Optimal,
    $false
)

# Also create a root-accessible copy of the backup zip
$RootBackupZip = Join-Path $RootDir "GameNuke_Eternity_Backup_v3.5.0.zip"
if ($RootBackupZip -ne $BackupZipPath) {
    Copy-Item -Path $BackupZipPath -Destination $RootBackupZip -Force
}

# Cleanup staging folder
Remove-Item "c:\ProyekAndroid\_backup_staging" -Recurse -Force -ErrorAction SilentlyContinue

# -------------------------------------------------------------
# STEP 5: VERIFICATION OF BACKUP INTEGRITY
# -------------------------------------------------------------
Write-Host ""
Write-Host "[PHASE 5/5] Verifying Backup Integrity & Contents..." -ForegroundColor Green

if (Test-Path $BackupZipPath) {
    $zipItem = Get-Item $BackupZipPath
    $zipHash = (Get-FileHash -Path $BackupZipPath -Algorithm SHA256).Hash

    Write-Host "  [OK] Backup File Exists : $($zipItem.FullName)" -ForegroundColor Green
    Write-Host "  [OK] Archive Size       : $(Format-Bytes $zipItem.Length)" -ForegroundColor Green
    Write-Host "  [OK] SHA-256 Hash       : $zipHash" -ForegroundColor Yellow
    if ($RootBackupZip -ne $BackupZipPath) {
        Write-Host "  [OK] Project Root Copy  : $RootBackupZip" -ForegroundColor Green
    }
} else {
    Write-Error "CRITICAL: Backup archive was not created!"
    exit 1
}

$TotalStopwatch.Stop()
Write-Host ""
Write-Host "================================================================================" -ForegroundColor Cyan
Write-Host "   CLEANUP & BACKUP PIPELINE COMPLETE - DURATION: $([int]$TotalStopwatch.Elapsed.TotalSeconds)s   " -ForegroundColor Green
Write-Host "================================================================================" -ForegroundColor Cyan
Write-Host ""
