# Push only the gamenukeweb folder to GitHub main and gh-pages branches, and publish GitHub Release
$RootDir = Split-Path -Parent $PSScriptRoot
$EnvFile = Join-Path $PSScriptRoot "github_token.env"
$Config = @{}
Get-Content $EnvFile | ForEach-Object {
    if ($_ -match '^\s*([^#=]+)\s*=\s*(.*)\s*$') {
        $Config[$matches[1].Trim()] = $matches[2].Trim()
    }
}
$Owner = $Config["GITHUB_REPO_OWNER"]
$Repo  = $Config["GITHUB_REPO_NAME"]
$Token = $Config["GITHUB_TOKEN"]

$VersionName = "3.2.1-Spectra"
$ApkName = "GameNuke-v3.2.1-Spectra.apk"
$Tag = "v$VersionName"
$WebDir = Join-Path $RootDir "gamenukeweb"
$ApkPath = Join-Path $WebDir $ApkName

if (-not (Test-Path $ApkPath)) {
    Write-Error "APK not found at $ApkPath!"
    exit 1
}

$ApkItem = Get-Item $ApkPath
$ApkSizeMb = [math]::Round($ApkItem.Length / 1MB, 1)
$ApkSha256 = (Get-FileHash $ApkPath -Algorithm SHA256).Hash

Write-Host "==========================================================" -ForegroundColor Green
Write-Host "   GAME NUKE SPECTRA - WEB-ONLY DEPLOY & RELEASE" -ForegroundColor Cyan
Write-Host "==========================================================" -ForegroundColor Green
Write-Host "Version: $VersionName" -ForegroundColor Cyan
Write-Host "APK: $ApkName ($ApkSizeMb MB)" -ForegroundColor Green
Write-Host "SHA256: $ApkSha256" -ForegroundColor DarkGray

# 1. Commit and push gamenukeweb strictly to GitHub
Push-Location $WebDir
try {
    git add .
    $status = git status --porcelain
    if ($status) {
        git commit -m "feat(release): Game Nuke Spectra Edition v$VersionName (Web Portal & Official Standalone Release)"
    }
    Write-Host "[1/3] Pushing gamenukeweb strictly to GitHub main..." -ForegroundColor Cyan
    $mainPushed = $false
    try {
        git push origin main
        $mainPushed = $true
    } catch {
        Write-Warning "Direct push to main failed: $_"
    }

    Write-Host "[2/3] Publishing gamenukeweb branch gh-pages for instant CDN hosting..." -ForegroundColor Cyan
    try {
        if (-not (Test-Path ".nojekyll")) {
            New-Item -ItemType File -Name ".nojekyll" -Force | Out-Null
        }
        $ghPagesTemp = Join-Path $RootDir "scratch\gh_pages_deploy"
        if (Test-Path $ghPagesTemp) { Remove-Item -Recurse -Force $ghPagesTemp }
        Copy-Item -Path $WebDir -Destination $ghPagesTemp -Recurse -Force
        Push-Location $ghPagesTemp
        try {
            if (Test-Path ".git") { Remove-Item -Recurse -Force ".git" }
            git init -q
            git config user.name "agungputraa"
            git config user.email "agungputraa@users.noreply.github.com"
            git add .
            git commit -m "feat(release): Game Nuke Spectra Edition Web Portal v$VersionName" -q
            git push "https://x-access-token:$Token@github.com/$Owner/$Repo.git" HEAD:gh-pages --force -q 2>$null
            Write-Host "   Web distribution synchronized successfully to gh-pages!" -ForegroundColor Green
        } finally {
            Pop-Location
            if (Test-Path $ghPagesTemp) { Remove-Item -Recurse -Force $ghPagesTemp }
        }
    } catch {
        Write-Warning "Deploy to gh-pages failed: $_"
    }
} finally {
    Pop-Location
}

# 2. GitHub Release Creation with Direct Binary Upload
Write-Host "[3/3] Creating GitHub Release $Tag..." -ForegroundColor Cyan

$Headers = @{
    "Authorization" = "token $Token"
    "Accept"        = "application/vnd.github.v3+json"
}

$lines = @(
    "Game Nuke Spectra Edition v$VersionName",
    "",
    "Official Standalone Release with Zero-Latency Touch Engine, Watchdog Ghost Touch Eliminator & Ironclad Game Immunity.",
    "",
    "Highlights:",
    "- Zero-Latency Touch Engine: Eliminated touchscreen freeze by retiring aggressive kernel evdev grab during bridge bootstrap (Shizuku, iADB, Native ADB).",
    "- Watchdog Ghost Touch Eliminator: Added watchdog auto-release and guaranteed ACTION_UP lifecycle on all macro modes (Rapid, Tap, Hold, Swipe, Double Tap).",
    "- Ironclad Game & Screen Recorder Immunity: Sentinel, Task Manager, Kill Zombie, Manage Load, and Deep Clean 100% guarantee no active game or recorder is ever stopped.",
    "- HyperOS, MIUI & OneUI Compatibility: Hardened against aggressive OEM process killers and background touch restrictions.",
    "- Macro Studio Non-Interfering Overlay: Fullscreen canvas touch-through mode ensuring responsive zero-lag touch controls during intense gaming.",
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
    name             = "Game Nuke Spectra Edition v$VersionName"
    body             = $ReleaseBody
    draft            = $false
    prerelease       = $false
} | ConvertTo-Json

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

# Upload APK asset
Write-Host "   Checking existing assets on release..." -ForegroundColor Yellow
$CleanUploadUrl = $UploadUrl -replace '\{\?name,label\}', "?name=$ApkName"
try {
    $Assets = Invoke-RestMethod -Uri "https://api.github.com/repos/$Owner/$Repo/releases/$ReleaseId/assets" -Headers $Headers -Method Get
    foreach ($a in $Assets) {
        if ($a.name -eq $ApkName) {
            Write-Host "   Replacing previous asset $($a.id) ($($a.name))..." -ForegroundColor DarkGray
            Invoke-RestMethod -Uri "https://api.github.com/repos/$Owner/$Repo/releases/assets/$($a.id)" -Headers $Headers -Method Delete
        }
    }
} catch {}

Write-Host "   Uploading $ApkName to GitHub Release via streaming curl..." -ForegroundColor Cyan
$uploadUrlClean = $CleanUploadUrl
& curl.exe -sS -X POST `
    -H "Authorization: token $Token" `
    -H "Content-Type: application/vnd.android.package-archive" `
    --data-binary "@$ApkPath" `
    --retry 3 `
    --connect-timeout 30 `
    -m 300 `
    "$uploadUrlClean"
Write-Host "   Asset upload completed!" -ForegroundColor Green

Write-Host "==========================================================" -ForegroundColor Green
Write-Host "   SUCCESS! GAME NUKE SPECTRA IS PUBLISHED & LIVE" -ForegroundColor Green
Write-Host "==========================================================" -ForegroundColor Green
Write-Host "   Landing Page : https://$Owner.github.io/$Repo/" -ForegroundColor Cyan
Write-Host "   Direct APK   : https://$Owner.github.io/$Repo/$ApkName" -ForegroundColor Cyan
Write-Host "   Metadata API : https://$Owner.github.io/$Repo/version.json" -ForegroundColor Cyan
Write-Host "   Releases     : https://github.com/$Owner/$Repo/releases/tag/$Tag" -ForegroundColor Cyan
Write-Host "==========================================================" -ForegroundColor Green
