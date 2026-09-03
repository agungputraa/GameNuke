# Game Nuke 1-Click Release & GitHub Publisher
# Automates: Build Signed APK -> Generate Metadata -> GitHub Release -> Upload Asset -> Deploy gh-pages

$ErrorActionPreference = "Stop"

$RootDir = Split-Path -Parent $PSScriptRoot
Set-Location $RootDir

Write-Host "==========================================================" -ForegroundColor Green
Write-Host "   GAME NUKE PREMIUM - 1-CLICK RELEASE AND PUBLISHER" -ForegroundColor Cyan
Write-Host "==========================================================" -ForegroundColor Green

# 1. Load credentials
$EnvFile = Join-Path $PSScriptRoot "github_token.env"
if (-not (Test-Path $EnvFile)) {
    Write-Error "Credential file tools/github_token.env not found!"
    exit 1
}

$Config = @{}
Get-Content $EnvFile | ForEach-Object {
    if ($_ -match '^\s*([^#=]+)\s*=\s*(.*)\s*$') {
        $Config[$matches[1].Trim()] = $matches[2].Trim()
    }
}

$Owner = $Config["GITHUB_REPO_OWNER"]
$Repo  = $Config["GITHUB_REPO_NAME"]
$Token = $Config["GITHUB_TOKEN"]

if (-not $Token -or -not $Owner -or -not $Repo) {
    Write-Error "Invalid github_token.env configuration!"
    exit 1
}

Write-Host "[1/6] Checking signed release APK..." -ForegroundColor Yellow
$ApkPath = "$RootDir\release-apk\GameNuke-Premium-v2.2.0.apk"
if (-not (Test-Path $ApkPath)) {
    Write-Host "   Building signed release APK with Gradle..." -ForegroundColor Yellow
    & "$RootDir\gradlew.bat" assembleRelease
}

if (-not (Test-Path $ApkPath)) {
    $ApkPath = "$RootDir\app\build\outputs\apk\release\app-release.apk"
}

if (-not (Test-Path $ApkPath)) {
    Write-Error "Build failed: signed APK not found!"
    exit 1
}

$ApkItem = Get-Item $ApkPath
$ApkSizeMb = [math]::Round($ApkItem.Length / 1MB, 1)
$ApkSha256 = (Get-FileHash $ApkPath -Algorithm SHA256).Hash
$ApkName = $ApkItem.Name

Write-Host "   APK verified: $ApkName ($ApkSizeMb MB)" -ForegroundColor Green
Write-Host "   SHA256: $ApkSha256" -ForegroundColor DarkGray

# 2. Update gamenukeweb/version.json
Write-Host "[2/6] Updating version metadata in gamenukeweb..." -ForegroundColor Yellow
$VersionJsonPath = "$RootDir\gamenukeweb\version.json"
if (Test-Path $VersionJsonPath) {
    $vJson = Get-Content $VersionJsonPath -Raw | ConvertFrom-Json
    $vJson.apkSizeMb = "$ApkSizeMb"
    $vJson.sha256 = "$ApkSha256"
    $vJson.publishedAt = (Get-Date -Format "yyyy-MM-dd")
    $vJson.downloadUrl = "https://github.com/$Owner/$Repo/releases/download/v" + $vJson.versionName + "/$ApkName"
    $vJson | ConvertTo-Json -Depth 10 | Set-Content $VersionJsonPath
}

# 3. Synchronize Git workspace to GitHub main
Write-Host "[3/6] Synchronizing Git workspace to GitHub main..." -ForegroundColor Yellow
if (-not (Test-Path "$RootDir\.git")) {
    git init
    git branch -M main
    git remote add origin "https://x-access-token:$Token@github.com/$Owner/$Repo.git"
} else {
    git remote set-url origin "https://x-access-token:$Token@github.com/$Owner/$Repo.git"
}

git add .
git commit -m "Game Nuke Premium Edition v2.2.0-prem (Dual-Engine Macro, VPN Ping 1ms, Web Ecosystem)" -q
git push -u origin main --force

# 4. Deploy gamenukeweb to gh-pages branch for Edge CDN
Write-Host "[4/6] Deploying gamenukeweb to gh-pages branch (Edge CDN)..." -ForegroundColor Yellow
$WebDir = "$RootDir\gamenukeweb"
Push-Location $WebDir
try {
    if (Test-Path ".git") { Remove-Item -Recurse -Force ".git" }
    git init -q
    git branch -M gh-pages
    git add .
    git commit -m "Deploy Game Nuke Premium Landing Page and Edge CDN Metadata" -q
    git remote add origin "https://x-access-token:$Token@github.com/$Owner/$Repo.git"
    git push -u origin gh-pages --force -q
    Write-Host "   GitHub Pages deployed successfully to https://$Owner.github.io/$Repo/" -ForegroundColor Green
} finally {
    Pop-Location
}

# 5. Create GitHub Release via API
Write-Host "[5/6] Creating GitHub Release via API..." -ForegroundColor Yellow
$Headers = @{
    "Authorization" = "token $Token"
    "Accept"        = "application/vnd.github.v3+json"
}

$Tag = "v2.2.0-prem"
$ReleaseBody = "Game Nuke Premium Edition v2.2.0`n`nOfficial Standalone Release with Unlimited Edge CDN Updates.`n`nHighlights:`n- Macro Fast-Hand Dual-Engine: Shizuku privileged input (~0ms latency) + Accessibility fallback.`n- VPN Ping Booster: 1ms local loopback responder for Mobile Legends lobby + Ultra-Low Latency Gaming DNS (Cloudflare 1.1.1.1 and Google 8.8.8.8).`n- Pro Gaming Deck Expansion: Tactical Crosshair Studio, Force 120Hz Refresh Rate, Anti-Mistouch Palm Shield.`n- Automated In-App Updater: Instant background checks without Google Play Store restrictions.`n`nIntegrity:`n- File: $ApkName`n- Size: $ApkSizeMb MB`n- SHA256: $ApkSha256"

$ReleasePayload = @{
    tag_name         = $Tag
    target_commitish = "main"
    name             = "Game Nuke Premium Edition v2.2.0"
    body             = $ReleaseBody
    draft            = $false
    prerelease       = $false
} | ConvertTo-Json

# Check if release already exists
$ExistingRelease = $null
try {
    $ExistingRelease = Invoke-RestMethod -Uri "https://api.github.com/repos/$Owner/$Repo/releases/tags/$Tag" -Headers $Headers -Method Get
} catch {}

$ReleaseId = $null
$UploadUrl = $null

if ($ExistingRelease) {
    Write-Host "   Found existing release $Tag (ID: $($ExistingRelease.id))" -ForegroundColor Cyan
    $ReleaseId = $ExistingRelease.id
    $UploadUrl = $ExistingRelease.upload_url
} else {
    $NewRelease = Invoke-RestMethod -Uri "https://api.github.com/repos/$Owner/$Repo/releases" -Headers $Headers -Method Post -Body $ReleasePayload
    $ReleaseId = $NewRelease.id
    $UploadUrl = $NewRelease.upload_url
    Write-Host "   Release created successfully (ID: $ReleaseId)" -ForegroundColor Green
}

# 6. Upload APK binary asset
Write-Host "[6/6] Uploading APK binary to GitHub Release assets..." -ForegroundColor Yellow
$CleanUploadUrl = $UploadUrl -replace '\{\?name,label\}', "?name=$ApkName"

# Check if asset exists and delete if needed
try {
    $Assets = Invoke-RestMethod -Uri "https://api.github.com/repos/$Owner/$Repo/releases/$ReleaseId/assets" -Headers $Headers -Method Get
    foreach ($a in $Assets) {
        if ($a.name -eq $ApkName) {
            Write-Host "   Deleting old asset $($a.id)..." -ForegroundColor DarkGray
            Invoke-RestMethod -Uri "https://api.github.com/repos/$Owner/$Repo/releases/assets/$($a.id)" -Headers $Headers -Method Delete
        }
    }
} catch {}

$UploadHeaders = @{
    "Authorization" = "token $Token"
    "Content-Type"  = "application/vnd.android.package-archive"
}

$ApkBytes = [System.IO.File]::ReadAllBytes($ApkPath)
$UploadResponse = Invoke-RestMethod -Uri $CleanUploadUrl -Headers $UploadHeaders -Method Post -Body $ApkBytes

Write-Host "==========================================================" -ForegroundColor Green
Write-Host "   SUCCESS! GAME NUKE PREMIUM RELEASE IS LIVE" -ForegroundColor Green
Write-Host "==========================================================" -ForegroundColor Green
Write-Host "   Landing Page : https://$Owner.github.io/$Repo/" -ForegroundColor Cyan
Write-Host "   Metadata API : https://$Owner.github.io/$Repo/version.json" -ForegroundColor Cyan
Write-Host "   Release URL  : $($UploadResponse.browser_download_url)" -ForegroundColor Cyan
Write-Host "==========================================================" -ForegroundColor Green
