# Game Nuke Web — Packager for ChatGPT UI/UX Design
$ErrorActionPreference = "Stop"

$RootDir = Split-Path -Parent $PSScriptRoot
$SrcDir = Join-Path $RootDir "gamenukeweb"
$ZipFile = Join-Path $RootDir "gamenukeweb_chatgpt.zip"

if (Test-Path $ZipFile) {
    Remove-Item $ZipFile -Force
    Write-Host "Removed existing zip file." -ForegroundColor DarkGray
}

Write-Host "Archiving gamenukeweb from: $SrcDir" -ForegroundColor Cyan

Add-Type -AssemblyName System.IO.Compression.FileSystem
Add-Type -AssemblyName System.IO.Compression

$archive = [System.IO.Compression.ZipFile]::Open($ZipFile, [System.IO.Compression.ZipArchiveMode]::Create)
$fileCount = 0
$totalBytes = 0

try {
    Get-ChildItem -Path $SrcDir -Recurse -File | ForEach-Object {
        $relPath = $_.FullName.Substring($SrcDir.Length).TrimStart('\', '/')
        # Exclude git repository metadata
        if ($relPath -match '^(\.git)[\\/]') {
            return
        }
        $entryName = $relPath.Replace('\', '/')
        $entry = $archive.CreateEntry($entryName, [System.IO.Compression.CompressionLevel]::Optimal)
        $entryStream = $entry.Open()
        $fileStream = [System.IO.File]::OpenRead($_.FullName)
        $fileStream.CopyTo($entryStream)
        $fileStream.Dispose()
        $entryStream.Dispose()
        $fileCount++
        $totalBytes += $_.Length
        Write-Host "   + $entryName ($([math]::Round($_.Length / 1KB, 1)) KB)" -ForegroundColor Gray
    }
} finally {
    $archive.Dispose()
}

$zipItem = Get-Item $ZipFile
$sizeMb = [math]::Round($zipItem.Length / 1MB, 2)

Write-Host "==========================================================" -ForegroundColor Green
Write-Host "   GAMENUKEWEB PACKAGED SUCCESSFULLY FOR CHATGPT" -ForegroundColor Green
Write-Host "==========================================================" -ForegroundColor Green
Write-Host "   Zip Location : $($zipItem.FullName)" -ForegroundColor Cyan
Write-Host "   Total Files  : $fileCount" -ForegroundColor White
Write-Host "   Zip Size     : $sizeMb MB" -ForegroundColor Green
Write-Host "==========================================================" -ForegroundColor Green
