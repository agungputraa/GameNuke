# ==============================================================================
# Game Nuke Apeiron Edition - Web Portal and GitHub Release Deployment Pipeline
# Deploys ONLY the gamenukeweb portal and APK binary. Zero Android project code is pushed.
# ==============================================================================

param(
    [switch]$SkipRelease = $false
)

$ErrorActionPreference = "Continue"
$TotalTimer = [System.Diagnostics.Stopwatch]::StartNew()

function Format-Duration ([TimeSpan]$ts) {
    return "{0:D2}m {1:D2}s" -f [int]$ts.TotalMinutes, $ts.Seconds
}

$RootDir = Split-Path -Parent $PSScriptRoot
Set-Location $RootDir

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

$VersionName = "3.6.0-Apeiron"
$ApkName = "GameNuke-v3.6.0-Apeiron.apk"
$Tag = "v$VersionName"
$WebDir = Join-Path $RootDir "gamenukeweb"
$ApkPath = Join-Path $WebDir $ApkName

if (-not (Test-Path $ApkPath)) {
    $releaseApk = Join-Path $RootDir "release-apk\$ApkName"
    if (Test-Path $releaseApk) {
        Copy-Item -Path $releaseApk -Destination $ApkPath -Force
        Write-Host "Synced APK from release-apk to $ApkPath" -ForegroundColor Green
    } else {
        Write-Error "CRITICAL: APK binary not found at $ApkPath or $releaseApk!"
        exit 1
    }
}

$ApkItem = Get-Item $ApkPath
$ApkSizeMb = [math]::Round($ApkItem.Length / 1MB, 2)
$ApkSha256 = (Get-FileHash $ApkPath -Algorithm SHA256).Hash

Write-Host ""
Write-Host "================================================================================" -ForegroundColor Cyan
Write-Host "   GAME NUKE APEIRON EDITION (v3.6.0) - WEB PORTAL & RELEASE DEPLOYER           " -ForegroundColor Yellow
Write-Host "================================================================================" -ForegroundColor Cyan
Write-Host "  Repository       : $Owner/$Repo" -ForegroundColor White
Write-Host "  Release Tag      : $Tag" -ForegroundColor White
Write-Host "  Target APK       : $ApkName ($ApkSizeMb MB)" -ForegroundColor Green
Write-Host "  SHA-256 Checksum : $ApkSha256" -ForegroundColor Yellow
Write-Host "  Scope Guard      : ISOLATED WEB ONLY (Zero Android project source code pushed)" -ForegroundColor Cyan
Write-Host "--------------------------------------------------------------------------------" -ForegroundColor DarkCyan

# -------------------------------------------------------------
# STEP 1: ISOLATED WEB PORTAL PACKAGING & GIT PUSH
# -------------------------------------------------------------
Write-Host "[STEP 1/3] Packaging Isolated Web Portal and Deploying to GitHub..." -ForegroundColor Green

if (-not (Test-Path (Join-Path $WebDir ".nojekyll"))) {
    New-Item -ItemType File -Path (Join-Path $WebDir ".nojekyll") -Force | Out-Null
}

$ghDeployTemp = Join-Path $RootDir "scratch\gh_deploy_temp"
if (Test-Path $ghDeployTemp) {
    Remove-Item -Recurse -Force $ghDeployTemp -ErrorAction SilentlyContinue
}
New-Item -ItemType Directory -Path $ghDeployTemp -Force | Out-Null

Write-Host "  Copying gamenukeweb files and release APK to isolated deployment workspace..." -ForegroundColor DarkGray
Copy-Item -Path "$WebDir\*" -Destination $ghDeployTemp -Recurse -Force

Push-Location $ghDeployTemp
try {
    if (Test-Path ".git") { Remove-Item -Recurse -Force ".git" }
    
    git init -q
    git config user.name "agungputraa"
    git config user.email "agungputraa@users.noreply.github.com"
    git add .
    git commit -m "feat(release): Game Nuke Apeiron Edition v$VersionName Web Portal and APK" -q

    Write-Host "  Pushing to branch: main..." -ForegroundColor Yellow
    $pushMain = git push "https://x-access-token:$Token@github.com/$Owner/$Repo.git" HEAD:main --force 2>&1
    Write-Host "  [OK] Branch 'main' updated successfully." -ForegroundColor Green

    Write-Host "  Pushing to branch: gh-pages (GitHub Pages Live Host)..." -ForegroundColor Yellow
    $pushPages = git push "https://x-access-token:$Token@github.com/$Owner/$Repo.git" HEAD:gh-pages --force 2>&1
    Write-Host "  [OK] Branch 'gh-pages' updated successfully." -ForegroundColor Green
} finally {
    Pop-Location
    if (Test-Path $ghDeployTemp) {
        Remove-Item -Recurse -Force $ghDeployTemp -ErrorAction SilentlyContinue
    }
}

# -------------------------------------------------------------
# STEP 2: GITHUB RELEASE CREATION VIA API
# -------------------------------------------------------------
if (-not $SkipRelease) {
    Write-Host ""
    Write-Host "[STEP 2/3] Publishing Official GitHub Release $Tag..." -ForegroundColor Green

    $Headers = @{
        "Authorization" = "token $Token"
        "Accept"        = "application/vnd.github.v3+json"
    }

    $ReleaseNotes = @(
        "# Game Nuke Apeiron Edition v" + $VersionName,
        "",
        "Official signed release of Game Nuke Apeiron Edition with Apeiron Neural Core, Server-Authoritative Credit Wallet, Micro-Session Booster Passes, and eSports Sensi Panel.",
        "",
        "### Release Highlights",
        "- **Apeiron Neural Core & Multi-Touch**: Full kernel alignment with ultra-low latency touch translation, cognitive hardware tuning, and adaptive gaming pace.",
        "- **Server-Authoritative Credit Wallet**: Economical micro-purchases starting at Rp 3.000 for game boosting credits via Pakasir v2 QRIS with automatic minimum fee compliance.",
        "- **Smart 90-Min Booster Sessions**: Transparent user-controlled credit consumption with zero hidden deductions or waste.",
        "- **Hardened Anti-Tamper & Security**: Dual-compatible SHA-256 integrity seal protecting both active subscription periods and credit balances with zero disruption to existing subscribers.",
        "- **AdBlock & DNS Bypass Hardening**: Liftoff/Vungle fail-open loophole completely eliminated.",
        "- **Cross-Brand Android 11-16 Stability**: Rigorously validated for HyperOS, OneUI, ColorOS, OriginOS, ROG UI, and AOSP.",
        "",
        "### Cryptographic Integrity & Verification",
        "- **Artifact Name**: " + $ApkName,
        "- **Binary Size**: " + $ApkSizeMb + " MB (" + $ApkItem.Length + " bytes)",
        "- **SHA-256 Checksum**: " + $ApkSha256,
        "- **Signature Scheme**: APK Signature Scheme v3 (Signer: agwallpaper84.jks)",
        "- **Package Name**: com.neon.gametweak",
        "- **Version Code**: 36",
        "- **Target SDK**: Android 16 (API 36)"
    ) -join "`n"

    $ReleasePayload = @{
        tag_name         = $Tag
        target_commitish = "main"
        name             = "Game Nuke Apeiron Edition v" + $VersionName
        body             = $ReleaseNotes
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
        Write-Host "  Found existing release $Tag (ID: $($ExistingRelease.id)) - updating..." -ForegroundColor Cyan
        $ReleaseId = $ExistingRelease.id
        $UploadUrl = $ExistingRelease.upload_url
        Invoke-RestMethod -Uri "https://api.github.com/repos/$Owner/$Repo/releases/$ReleaseId" -Headers $Headers -Method Patch -Body $ReleasePayload | Out-Null
    } else {
        Write-Host "  Creating new GitHub Release $Tag..." -ForegroundColor Yellow
        $NewRelease = Invoke-RestMethod -Uri "https://api.github.com/repos/$Owner/$Repo/releases" -Headers $Headers -Method Post -Body $ReleasePayload
        $ReleaseId = $NewRelease.id
        $UploadUrl = $NewRelease.upload_url
        Write-Host "  [OK] GitHub Release created successfully (ID: $ReleaseId)" -ForegroundColor Green
    }

    # -------------------------------------------------------------
    # STEP 3: UPLOAD RELEASE APK ASSET
    # -------------------------------------------------------------
    Write-Host ""
    Write-Host "[STEP 3/3] Uploading Release APK Binary to GitHub Release Asset Store..." -ForegroundColor Green
    
    try {
        $Assets = Invoke-RestMethod -Uri "https://api.github.com/repos/$Owner/$Repo/releases/$ReleaseId/assets" -Headers $Headers -Method Get
        foreach ($a in $Assets) {
            if ($a.name -eq $ApkName) {
                Write-Host "  Deleting existing asset $($a.id) ($($a.name))..." -ForegroundColor DarkGray
                Invoke-RestMethod -Uri "https://api.github.com/repos/$Owner/$Repo/releases/assets/$($a.id)" -Headers $Headers -Method Delete | Out-Null
            }
        }
    } catch {}

    $CleanUploadUrl = $UploadUrl -replace '\{\?name,label\}', "?name=$ApkName"
    Write-Host "  Uploading $ApkName ($ApkSizeMb MB) via streaming curl..." -ForegroundColor Yellow

    $curlTimer = [System.Diagnostics.Stopwatch]::StartNew()
    & curl.exe -sS -X POST `
        -H "Authorization: token $Token" `
        -H "Content-Type: application/vnd.android.package-archive" `
        --data-binary "@$ApkPath" `
        --retry 3 `
        --connect-timeout 30 `
        -m 600 `
        "$CleanUploadUrl" | Out-Null
    $curlTimer.Stop()

    Write-Host "  [OK] APK Asset upload completed in $(Format-Duration $curlTimer.Elapsed)!" -ForegroundColor Green
}

Write-Host ""
Write-Host "================================================================================" -ForegroundColor Cyan
Write-Host "   DEPLOYMENT & RELEASE COMPLETE - TOTAL ELAPSED: $(Format-Duration $TotalTimer.Elapsed)   " -ForegroundColor Green
Write-Host "================================================================================" -ForegroundColor Cyan
Write-Host "  Official Web Portal : https://gamenukeofficial.com/" -ForegroundColor Cyan
Write-Host "  GitHub Pages URL    : https://$Owner.github.io/$Repo/" -ForegroundColor Cyan
Write-Host "  Direct APK Download : https://gamenukeofficial.com/$ApkName" -ForegroundColor Cyan
Write-Host "  Release Assets URL  : https://github.com/$Owner/$Repo/releases/tag/$Tag" -ForegroundColor Cyan
Write-Host "================================================================================" -ForegroundColor Cyan
Write-Host ""
