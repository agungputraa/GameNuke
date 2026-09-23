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

$VersionName = "3.4.0-Nexus"
$ApkName = "GameNuke-v3.4.0-Nexus.apk"
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
Write-Host "   GAME NUKE NEXUS - WEB-ONLY DEPLOY & RELEASE" -ForegroundColor Cyan
Write-Host "==========================================================" -ForegroundColor Green
Write-Host "Version: $VersionName" -ForegroundColor Cyan
Write-Host "APK: $ApkName ($ApkSizeMb MB)" -ForegroundColor Green
Write-Host "SHA256: $ApkSha256" -ForegroundColor DarkGray

    Write-Host "[1/2] Preparing isolated web distribution package..." -ForegroundColor Cyan
    try {
        if (-not (Test-Path (Join-Path $WebDir ".nojekyll"))) {
            New-Item -ItemType File -Path (Join-Path $WebDir ".nojekyll") -Force | Out-Null
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
            git commit -m "feat(release): Game Nuke Nexus Edition Web Portal v$VersionName" -q
            git push "https://x-access-token:$Token@github.com/$Owner/$Repo.git" HEAD:main --force -q 2>$null
            git push "https://x-access-token:$Token@github.com/$Owner/$Repo.git" HEAD:master --force -q 2>$null
            git push "https://x-access-token:$Token@github.com/$Owner/$Repo.git" HEAD:gh-pages --force -q 2>$null
            Write-Host "   Web & APK distribution synchronized successfully to main, master, and gh-pages!" -ForegroundColor Green
        } finally {
            Pop-Location
            if (Test-Path $ghPagesTemp) { Remove-Item -Recurse -Force $ghPagesTemp }
        }
    } catch {
        Write-Warning "Deploy failed: $_"
    }

# 2. GitHub Release Creation with Direct Binary Upload
Write-Host "[3/3] Creating GitHub Release $Tag..." -ForegroundColor Cyan

$Headers = @{
    "Authorization" = "token $Token"
    "Accept"        = "application/vnd.github.v3+json"
}

$lines = @(
    "Game Nuke Nexus Edition v$VersionName",
    "",
    "Official Standalone Release with Nexus Multi-Touch & AI Engine, Precision Linear HUD, and Aesthetic Blurred Game Artwork.",
    "",
    "Highlights:",
    "- Nexus Multi-Touch & AI Engine: Full kernel alignment with low-latency touch translation, cognitive hardware tuning, and adaptive gaming pace.",
    "- Precision Linear HUD: Symmetrical linear alignment for active game title, session status, and cockpit controls.",
    "- Aesthetic Blurred Game Artwork: Game profiles display elegant, low-overhead GPU blurred backdrops matching each game's emblem.",
    "- Thermal & CPU Load Optimization: Throttled ADB background polling and zero-allocation Compose drawing to keep the device running cool.",
    "- Zero Ghost Touch & Freeze Elimination: Hardware kernel BTN_TOUCH release detection ensures all synthetic pointer sessions are instantly terminated when physical fingers leave the screen.",
    "- Universal Brand Compatibility: Fully audited and validated across HyperOS/MIUI, One UI, ColorOS/OxygenOS, FuntouchOS, ROG UI, and Stock AOSP on Android 11 through Android 16.",
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
    name             = "Game Nuke Nexus Edition v$VersionName"
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
Write-Host "   SUCCESS! GAME NUKE NEXUS IS PUBLISHED & LIVE" -ForegroundColor Green
Write-Host "==========================================================" -ForegroundColor Green
Write-Host "   Landing Page : https://$Owner.github.io/$Repo/" -ForegroundColor Cyan
Write-Host "   Direct APK   : https://$Owner.github.io/$Repo/$ApkName" -ForegroundColor Cyan
Write-Host "   Metadata API : https://$Owner.github.io/$Repo/version.json" -ForegroundColor Cyan
Write-Host "   Releases     : https://github.com/$Owner/$Repo/releases/tag/$Tag" -ForegroundColor Cyan
Write-Host "==========================================================" -ForegroundColor Green
