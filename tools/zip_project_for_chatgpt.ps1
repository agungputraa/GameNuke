# Game Nuke — Source Code Archiver for ChatGPT
$ErrorActionPreference = "Stop"

$RootDir = Split-Path -Parent $PSScriptRoot
$ZipFile = Join-Path $RootDir "GameNukeChatgpt.zip"

if (Test-Path $ZipFile) {
    Remove-Item $ZipFile -Force
    Write-Host "Removed existing GameNukeChatgpt.zip" -ForegroundColor DarkGray
}

Write-Host "Scanning source files in $RootDir..." -ForegroundColor Cyan

# Exclude patterns (regex against relative path)
$ExcludeDirPattern = '([\\/]|^)(app[\\/]build|build|\.gradle|\.git|\.idea|\.kotlin|release-apk|release-aab|\.cxx|\.externalNativeBuild)([\\/]|$)'
$ExcludeFilePattern = '\.(zip|jks|keystore|apk|aab|hprof|log)$|^release\.properties$|^local\.properties$|^tools[\\/].*\.(env|token)$'

Add-Type -AssemblyName System.IO.Compression.FileSystem
Add-Type -AssemblyName System.IO.Compression

$archive = [System.IO.Compression.ZipFile]::Open($ZipFile, [System.IO.Compression.ZipArchiveMode]::Create)

$allFiles = Get-ChildItem -Path $RootDir -Recurse -File
$zippedCount = 0
$totalBytes = 0

try {
    foreach ($file in $allFiles) {
        $relPath = $file.FullName.Substring($RootDir.Length).TrimStart('\', '/')
        
        # Check folder exclusions
        if ($relPath -match $ExcludeDirPattern) {
            continue
        }
        
        # Check file exclusions
        if ($relPath -match $ExcludeFilePattern) {
            continue
        }
        
        # Add to zip
        $entryName = $relPath.Replace('\', '/')
        $entry = $archive.CreateEntry($entryName, [System.IO.Compression.CompressionLevel]::Optimal)
        
        $entryStream = $entry.Open()
        $fileStream = [System.IO.File]::OpenRead($file.FullName)
        $fileStream.CopyTo($entryStream)
        
        $fileStream.Dispose()
        $entryStream.Dispose()
        
        $zippedCount++
        $totalBytes += $file.Length
    }
} finally {
    $archive.Dispose()
}

$zipItem = Get-Item $ZipFile
$uncompressedMb = [math]::Round($totalBytes / 1MB, 2)
$zipMb = [math]::Round($zipItem.Length / 1MB, 2)

Write-Host "==========================================================" -ForegroundColor Green
Write-Host "   GAME NUKE SOURCE CODE PACKAGED SUCCESSFULLY FOR CHATGPT" -ForegroundColor Green
Write-Host "==========================================================" -ForegroundColor Green
Write-Host "   Zip Location      : $($zipItem.FullName)" -ForegroundColor Cyan
Write-Host "   Files Included    : $zippedCount source files" -ForegroundColor White
Write-Host "   Uncompressed Size : $uncompressedMb MB" -ForegroundColor White
Write-Host "   Compressed Zip    : $zipMb MB" -ForegroundColor Green
Write-Host "==========================================================" -ForegroundColor Green
