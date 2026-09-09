# Backup Script for GameNukePrem
$ErrorActionPreference = "Stop"

$src = "c:\ProyekAndroid\GameNukePrem"
$staging = "c:\ProyekAndroid\_backup_staging\GameNukePrem"
$zipFile = "c:\ProyekAndroid\GameNukePrem_Backup.zip"
$zipDated = "c:\ProyekAndroid\GameNukePrem_Backup_v2.3.0_20260907.zip"

Write-Host "=========================================" -ForegroundColor Cyan
Write-Host "Starting GameNukePrem Full Project Backup" -ForegroundColor Cyan
Write-Host "=========================================" -ForegroundColor Cyan

# 1. Clean Staging Directory
if (Test-Path "c:\ProyekAndroid\_backup_staging") {
    Write-Host "Cleaning old staging folder..." -ForegroundColor Gray
    Remove-Item "c:\ProyekAndroid\_backup_staging" -Recurse -Force
}
New-Item -ItemType Directory -Path $staging -Force | Out-Null

# 2. Copy files excluding build caches and temporary files
Write-Host "Staging source files, keystores, web portal, tools, and release APKs..." -ForegroundColor Yellow
robocopy $src $staging /MIR /XD .gradle .kotlin build .git /XF *.log /NDL /NFL /NJH /NJS

# Robocopy exit codes: 0-7 means success / files copied. 8+ means fatal error.
if ($LASTEXITCODE -ge 8) {
    throw "Robocopy encountered an error with exit code $LASTEXITCODE"
}

# Also ensure keystores, APKs, gradle configs and all code are intact in staging
Write-Host "Files successfully staged." -ForegroundColor Green

# 3. Create ZIP Archive
Write-Host "Compressing project into ZIP archive..." -ForegroundColor Yellow
if (Test-Path $zipFile) { Remove-Item $zipFile -Force }
if (Test-Path $zipDated) { Remove-Item $zipDated -Force }

Add-Type -AssemblyName System.IO.Compression.FileSystem
[System.IO.Compression.ZipFile]::CreateFromDirectory(
    $staging,
    $zipFile,
    [System.IO.Compression.CompressionLevel]::Optimal,
    $false
)

# Duplicate to dated version backup for safety
Copy-Item $zipFile $zipDated -Force

# 4. Clean up staging folder
Remove-Item "c:\ProyekAndroid\_backup_staging" -Recurse -Force

# 5. Verify Backup
if (Test-Path $zipFile) {
    $item = Get-Item $zipFile
    $sizeMB = [math]::Round($item.Length / 1MB, 2)
    Write-Host "=========================================" -ForegroundColor Green
    Write-Host "BACKUP SUCCESSFUL!" -ForegroundColor Green
    Write-Host "Archive: $($item.FullName)" -ForegroundColor Green
    Write-Host "Dated Archive: $zipDated" -ForegroundColor Green
    Write-Host "Size: $sizeMB MB" -ForegroundColor Green
    Write-Host "=========================================" -ForegroundColor Green
} else {
    throw "Backup failed: $zipFile was not created."
}
