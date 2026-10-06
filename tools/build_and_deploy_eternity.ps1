# Automated Build, Monitor, Verify, Install, and Deploy Pipeline for Game Nuke Eternity Edition v3.5.0
param(
    [string]$TargetDevice = "adb-EUMB8XWKYPL7Y5HQ-dS1AR3._adb-tls-connect._tcp",
    [switch]$SkipDeploy = $false,
    [switch]$Clean = $false
)

$RootDir = Split-Path -Parent $PSScriptRoot
Set-Location $RootDir

$scriptPath = Join-Path $PSScriptRoot "build_with_dynamic_timer.ps1"
$splat = @{
    TargetDevice = $TargetDevice
    CleanBuild   = $Clean
}

& $scriptPath @splat
