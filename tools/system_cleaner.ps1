# Game Nuke System and Project Deep Cleaner
# Safely frees disk space and trims RAM working sets
$ErrorActionPreference = "Continue"

Write-Host "==========================================================" -ForegroundColor Green
Write-Host "   SYSTEM AND PROJECT DEEP CLEANER -- STORAGE AND RAM" -ForegroundColor Cyan
Write-Host "==========================================================" -ForegroundColor Green

function Get-DriveInfo {
    $drive = Get-CimInstance Win32_LogicalDisk -Filter "DeviceID='C:'"
    $freeGb = [math]::Round($drive.FreeSpace / 1GB, 2)
    $totalGb = [math]::Round($drive.Size / 1GB, 2)
    return @{ FreeGB = $freeGb; TotalGB = $totalGb }
}

function Get-RamInfo {
    $os = Get-CimInstance Win32_OperatingSystem
    $freeMb = [math]::Round($os.FreePhysicalMemory / 1KB, 0)
    $totalMb = [math]::Round($os.TotalVisibleMemorySize / 1KB, 0)
    $usedMb = $totalMb - $freeMb
    return @{ UsedMB = $usedMb; FreeMB = $freeMb; TotalMB = $totalMb }
}

$initialDrive = Get-DriveInfo
$initialRam = Get-RamInfo

Write-Host "Initial Status:" -ForegroundColor Yellow
Write-Host "   Drive C: Free space : $($initialDrive.FreeGB) GB / $($initialDrive.TotalGB) GB" -ForegroundColor White
Write-Host "   RAM Usage           : $($initialRam.UsedMB) MB / $($initialRam.TotalMB) MB (Free: $($initialRam.FreeMB) MB)" -ForegroundColor White
Write-Host "----------------------------------------------------------" -ForegroundColor DarkGray

$RootDir = Split-Path -Parent $PSScriptRoot

# 1. CLEAN PROJECT CACHES
Write-Host "[1/4] Cleaning Game Nuke project build caches..." -ForegroundColor Cyan

$ProjectCacheDirs = @(
    "$RootDir\app\build",
    "$RootDir\.gradle",
    "$RootDir\.kotlin"
)

$projectBytesFreed = 0
foreach ($dir in $ProjectCacheDirs) {
    if (Test-Path $dir) {
        try {
            $files = Get-ChildItem -Path $dir -Recurse -File -Force -ErrorAction SilentlyContinue
            $size = ($files | Measure-Object -Property Length -Sum).Sum
            if ($size) { $projectBytesFreed += $size }
            Remove-Item $dir -Recurse -Force -ErrorAction SilentlyContinue
            $dirLeaf = Split-Path $dir -Leaf
            $mbFreed = [math]::Round($size / 1MB, 1)
            Write-Host "   + Cleared project cache: $dirLeaf ($mbFreed MB)" -ForegroundColor Gray
        } catch {}
    }
}

# 2. CLEAN WINDOWS USER AND SYSTEM JUNK
Write-Host "[2/4] Cleaning Windows temporary files and crash dumps..." -ForegroundColor Cyan

$JunkPaths = @(
    $env:TEMP,
    "$env:LOCALAPPDATA\Temp",
    "$env:LOCALAPPDATA\CrashDumps",
    "$env:LOCALAPPDATA\Microsoft\Windows\WER\ReportArchive",
    "$env:LOCALAPPDATA\Microsoft\Windows\WER\ReportQueue",
    "$env:USERPROFILE\.gradle\daemon",
    "$env:USERPROFILE\.gradle\caches\transforms-3",
    "$env:USERPROFILE\.android\cache",
    "$env:LOCALAPPDATA\pip\cache"
)

$systemBytesFreed = 0

foreach ($p in $JunkPaths) {
    if ($p -and (Test-Path $p)) {
        try {
            $items = Get-ChildItem -Path $p -Recurse -File -Force -ErrorAction SilentlyContinue
            foreach ($item in $items) {
                try {
                    $systemBytesFreed += $item.Length
                    Remove-Item $item.FullName -Force -ErrorAction SilentlyContinue
                } catch {}
            }
            Get-ChildItem -Path $p -Recurse -Directory -Force -ErrorAction SilentlyContinue | 
                Sort-Object FullName -Descending | 
                ForEach-Object {
                    try {
                        if (-not (Get-ChildItem $_.FullName)) {
                            Remove-Item $_.FullName -Force -ErrorAction SilentlyContinue
                        }
                    } catch {}
                }
            Write-Host "   + Cleaned: $p" -ForegroundColor Gray
        } catch {}
    }
}

# 3. EMPTY RECYCLE BIN
Write-Host "[3/4] Emptying Recycle Bin..." -ForegroundColor Cyan
try {
    Clear-RecycleBin -Force -ErrorAction SilentlyContinue
    Write-Host "   + Recycle bin cleared." -ForegroundColor Gray
} catch {}

# 4. SAFE RAM OPTIMIZATION
Write-Host "[4/4] Trimming memory and flushing system RAM..." -ForegroundColor Cyan

try {
    $csharpCode = @'
using System;
using System.Runtime.InteropServices;
public class WinMemory {
    [DllImport("psapi.dll")]
    public static extern int EmptyWorkingSet(IntPtr hwProc);
}
'@
    Add-Type -TypeDefinition $csharpCode -ErrorAction SilentlyContinue
} catch {}

$trimmedCount = 0
Get-Process | ForEach-Object {
    try {
        if ($_.Handle -and $_.Id -ne $PID) {
            [WinMemory]::EmptyWorkingSet($_.Handle) | Out-Null
            $trimmedCount++
        }
    } catch {}
}

[System.GC]::Collect()
[System.GC]::WaitForPendingFinalizers()

Write-Host "   + Trimmed memory working sets across $trimmedCount running processes." -ForegroundColor Gray

Start-Sleep -Seconds 1
$finalDrive = Get-DriveInfo
$finalRam = Get-RamInfo

$totalStorageFreedMb = [math]::Round(($projectBytesFreed + $systemBytesFreed) / 1MB, 1)
$diskDeltaGb = [math]::Round($finalDrive.FreeGB - $initialDrive.FreeGB, 2)
$ramFreedMb = [math]::Round($initialRam.UsedMB - $finalRam.UsedMB, 0)

Write-Host "==========================================================" -ForegroundColor Green
Write-Host "   CLEANUP COMPLETED SUCCESSFULLY!" -ForegroundColor Green
Write-Host "==========================================================" -ForegroundColor Green
Write-Host "   Project Cache Freed   : $([math]::Round($projectBytesFreed / 1MB, 1)) MB" -ForegroundColor White
Write-Host "   Windows Junk Cleaned  : $([math]::Round($systemBytesFreed / 1MB, 1)) MB" -ForegroundColor White
Write-Host "   Drive C: Free Space   : $($finalDrive.FreeGB) GB (Gained: +$diskDeltaGb GB)" -ForegroundColor Cyan
Write-Host "   RAM Currently Free    : $($finalRam.FreeMB) MB (Reclaimed: +$ramFreedMb MB)" -ForegroundColor Cyan
Write-Host "==========================================================" -ForegroundColor Green
