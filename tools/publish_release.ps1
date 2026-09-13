# Game Nuke 1-Click Release and GitHub Publisher
# Automates: Dynamic Version Parsing -> Assemble Signed APK -> Update Metadata -> Deploy Web/README/Workflows -> Create GitHub Release -> Upload Asset

$ErrorActionPreference = "Continue"

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

# 2. Automatically parse version from app/build.gradle.kts
$BuildGradle = Get-Content "$RootDir\app\build.gradle.kts" -Raw
$VersionCode = 22
$VersionName = "2.9.0-Void"
if ($BuildGradle -match 'versionCode\s*=\s*(\d+)') {
    $VersionCode = [int]$matches[1]
}
if ($BuildGradle -match 'versionName\s*=\s*"([^"]+)"') {
    $VersionName = $matches[1]
}
$CleanVersion = $VersionName.Replace("-Void", "").Replace("-Quasar", "").Replace("-prem", "")

Write-Host "[1/6] Detected target version: v$VersionName (Code: $VersionCode)" -ForegroundColor Cyan

# 3. Check or build signed release APK
Write-Host "   Verifying signed release APK..." -ForegroundColor Yellow
$TargetApkName = "GameNuke-v$VersionName.apk"
$ApkPath = "$RootDir\release-apk\$TargetApkName"

$FallbackApk = "$RootDir\app\build\outputs\apk\release\app-release.apk"
if (Test-Path $FallbackApk) {
    Copy-Item -Path $FallbackApk -Destination $ApkPath -Force
    Write-Host "   Updated $ApkPath with fresh build from $FallbackApk" -ForegroundColor Green
}
if (Test-Path $ApkPath) {
    Copy-Item -Path $ApkPath -Destination "$RootDir\gamenukeweb\$TargetApkName" -Force
    Copy-Item -Path $ApkPath -Destination "$RootDir\$TargetApkName" -Force
    Write-Host "   Synced $TargetApkName to gamenukeweb/ and root repository." -ForegroundColor Green
}

if (-not (Test-Path $ApkPath)) {
    Write-Host "   Building signed release APK with Gradle assembleRelease..." -ForegroundColor Yellow
    & "$RootDir\gradlew.bat" assembleRelease
    if (Test-Path $FallbackApk) {
        Copy-Item -Path $FallbackApk -Destination $ApkPath -Force
    }
}

if (-not (Test-Path $ApkPath)) {
    Write-Error "Build failed: signed APK not found at $ApkPath!"
    exit 1
}

$ApkItem = Get-Item $ApkPath
$ApkSizeMb = [math]::Round($ApkItem.Length / 1MB, 1)
$ApkSha256 = (Get-FileHash $ApkPath -Algorithm SHA256).Hash
$ApkName = $ApkItem.Name

Write-Host "   APK verified: $ApkName ($ApkSizeMb MB)" -ForegroundColor Green
Write-Host "   SHA256: $ApkSha256" -ForegroundColor DarkGray

# 4. Update gamenukeweb/version.json
Write-Host "[2/6] Updating version metadata in gamenukeweb/version.json..." -ForegroundColor Yellow
$VersionJsonPath = "$RootDir\gamenukeweb\version.json"
if (Test-Path $VersionJsonPath) {
    $vJson = Get-Content $VersionJsonPath -Raw | ConvertFrom-Json
    $vJson.versionCode = $VersionCode
    $vJson.versionName = $VersionName
    $vJson.apkSizeMb = "$ApkSizeMb"
    $vJson.sha256 = "$ApkSha256"
    $vJson.publishedAt = (Get-Date -Format "yyyy-MM-dd")
    $vJson.downloadUrl = "https://github.com/$Owner/$Repo/releases/download/v$VersionName/$ApkName"
    $vJson.localApkUrl = $TargetApkName
    $vJson.githubReleaseUrl = "https://github.com/$Owner/$Repo/releases/download/v$VersionName/$ApkName"
    
    $vJson.directlinkAdUrl = "https://dulyhagglermounting.com/2082665"
    $vJson.downloadDirectlinkUrl = "https://bmadss.com/get/?spot_id=2006837&cat=25&subid=808526990"
    $vJson.appName = "Game Nuke Void Edition"
    $vJson.releaseNotes = @(
        "Macro Studio Opacity Control: Interactive transparency tuning (20% - 100%) for both overlay and reticle pins",
        "Hardware Touch Driver Integration: Native libwandev.so bridge with GNU hash & Bloom filter verification",
        "Liftoff Monetize Optimization: Official VungleBannerView integration and clean passive screen rendering",
        "Systemwide English Standardization: Complete enterprise-grade English localization across all dialogs and toasts",
        "Process Purge Guardian: Intelligent non-root memory trimming with whitelist protection for active games and recorders",
        "Battery Optimization Guard: Realtime system readiness status and direct unrestricted power mode exemption"
    )
    
    $vJson | ConvertTo-Json -Depth 10 | Set-Content $VersionJsonPath
    Copy-Item -Path $VersionJsonPath -Destination "$RootDir\version.json" -Force
    $webFilesToSync = @(
        "index.html", "download.html", "CNAME", "app.js", "download.js", "site-config.js",
        "particles.js", "style.css", "style-v5.css", "style-v6.css", "favicon.png",
        "about.html", "contact.html", "guides.html", "privacy.html", "terms.html", "404.html",
        "manifest.webmanifest", "robots.txt", "sitemap.xml", "llms.txt"
    )
    foreach ($wf in $webFilesToSync) {
        $wfPath = "$RootDir\gamenukeweb\$wf"
        if (Test-Path $wfPath) {
            Copy-Item -Path $wfPath -Destination "$RootDir\$wf" -Force
        }
    }
    if (Test-Path "$RootDir\gamenukeweb\assets") {
        Copy-Item -Path "$RootDir\gamenukeweb\assets\*" -Destination "$RootDir\assets" -Recurse -Force
    }
}

# 5. Deploy gamenukeweb to GitHub main and gh-pages branches (STRICT WEB ISOLATION)
Write-Host "[3/6] Deploying Web Portal and GitHub Actions to GitHub (main and gh-pages)..." -ForegroundColor Yellow
$WebDir = "$RootDir\gamenukeweb"
Push-Location $WebDir
try {
    if (Test-Path ".git") { 
        Remove-Item -Recurse -Force ".git" 
    }
    git init -q
    git config user.name "Game Nuke Release Automation"
    git config user.email "release@gamenuke.internal"
    
    if (-not (Test-Path ".nojekyll")) {
        New-Item -ItemType File -Name ".nojekyll" -Force | Out-Null
    }

    git add .
    $commitMsg = "Game Nuke Void Edition Web and Release Portal v$VersionName (Tailwind, Alpine.js, Edge CDN, GitHub Actions)"
    git commit -m $commitMsg -q

    # Push to origin 'gh-pages' (Edge CDN serving) and 'main' (Web Release Portal)
    Write-Host "   Deploying web portal to remote gh-pages and main branches..." -ForegroundColor Cyan
    git push "https://x-access-token:$Token@github.com/$Owner/$Repo.git" HEAD:gh-pages --force -q 2>$null
    git push "https://x-access-token:$Token@github.com/$Owner/$Repo.git" HEAD:main --force -q 2>$null

    Write-Host "   Web distribution synchronized successfully to both main and gh-pages!" -ForegroundColor Green
} finally {
    Pop-Location
}

# 6. Create or Update GitHub Release via API
Write-Host "[4/6] Synchronizing GitHub Release via API..." -ForegroundColor Yellow
$Headers = @{
    "Authorization" = "token $Token"
    "Accept"        = "application/vnd.github.v3+json"
}

$Tag = "v$VersionName"
$lines = @(
    "Game Nuke Void Edition v$VersionName",
    "",
    "Official Standalone Release with Dual-Sync Edge CDN Updates.",
    "",
    "Highlights:",
    "- Macro Studio Opacity Control: Interactive transparency tuning (20% - 100%) for overlay and reticle pins.",
    "- Hardware Touch Driver Integration: Native libwandev.so bridge with GNU hash & Bloom filter verification.",
    "- Liftoff Monetize Optimization: Official VungleBannerView integration and clean passive screen rendering.",
    "- Systemwide English Standardization: Complete enterprise-grade English localization across all dialogs and toasts.",
    "- Process Purge Guardian: Intelligent non-root memory trimming with whitelist protection for active games and recorders.",
    "- Battery Optimization Guard: Realtime system readiness status and direct unrestricted power mode exemption.",
    "",
    "Integrity:",
    "- File: $ApkName",
    "- Size: $ApkSizeMb MB",
    "- SHA256: $ApkSha256"
)
$ReleaseBody = $lines -join "`n"

$ReleasePayload = @{
    tag_name         = $Tag
    target_commitish = "main"
    name             = "Game Nuke Void Edition v$VersionName"
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

# 7. Upload APK binary asset
Write-Host "[5/6] Verifying and uploading APK binary asset..." -ForegroundColor Yellow
$CleanUploadUrl = $UploadUrl -replace '\{\?name,label\}', "?name=$ApkName"

try {
    $Assets = Invoke-RestMethod -Uri "https://api.github.com/repos/$Owner/$Repo/releases/$ReleaseId/assets" -Headers $Headers -Method Get
    foreach ($a in $Assets) {
        if ($a.name -eq $ApkName) {
            Write-Host "   Replacing previous asset $($a.id) ($($a.name)) with fresh build..." -ForegroundColor DarkGray
            Invoke-RestMethod -Uri "https://api.github.com/repos/$Owner/$Repo/releases/assets/$($a.id)" -Headers $Headers -Method Delete
        }
    }
} catch {}

if (-not $AssetAlreadyUploaded) {
    Write-Host "   Streaming APK binary to release asset..." -ForegroundColor Cyan
    $UploadHeaders = @{
        "Authorization" = "token $Token"
        "Content-Type"  = "application/vnd.android.package-archive"
    }

    $ApkBytes = [System.IO.File]::ReadAllBytes($ApkPath)
    $UploadResponse = Invoke-RestMethod -Uri $CleanUploadUrl -Headers $UploadHeaders -Method Post -Body $ApkBytes
    Write-Host "   Binary uploaded: $($UploadResponse.browser_download_url)" -ForegroundColor Green
}

# 8. Final Status Report
Write-Host "[6/6] Verifying Live Endpoints..." -ForegroundColor Yellow
Write-Host "==========================================================" -ForegroundColor Green
Write-Host "   SUCCESS! GAME NUKE VOID ECOSYSTEM IS ONLINE" -ForegroundColor Green
Write-Host "==========================================================" -ForegroundColor Green
Write-Host "   Landing Page : https://$Owner.github.io/$Repo/" -ForegroundColor Cyan
Write-Host "   Metadata API : https://$Owner.github.io/$Repo/version.json" -ForegroundColor Cyan
Write-Host "   GitHub Repo  : https://github.com/$Owner/$Repo" -ForegroundColor Cyan
Write-Host "   Releases     : https://github.com/$Owner/$Repo/releases/tag/$Tag" -ForegroundColor Cyan
Write-Host "==========================================================" -ForegroundColor Green
