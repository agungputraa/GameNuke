# Push only the gamenukeweb folder to GitHub main and gh-pages branches
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

$WebDir = Join-Path $RootDir "gamenukeweb"
Push-Location $WebDir
try {
    git add .
    git commit -m "fix: elevate header WhatsApp button with bulletproof inline styles and CSS v2.8.4 cache buster" -q
    Write-Host "Pushing gamenukeweb to GitHub main..." -ForegroundColor Cyan
    git push "https://x-access-token:$Token@github.com/$Owner/$Repo.git" HEAD:main --force
    Write-Host "Pushing gamenukeweb to GitHub gh-pages..." -ForegroundColor Cyan
    git push "https://x-access-token:$Token@github.com/$Owner/$Repo.git" HEAD:gh-pages --force
    Write-Host "Done! Web portal pushed safely to both branches." -ForegroundColor Green
} finally {
    Pop-Location
}
