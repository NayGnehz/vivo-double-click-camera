[CmdletBinding()]
param(
    [string]$AdbPath,
    [string]$Serial,
    [ValidateRange(150, 600)][int]$DoubleMs = 350,
    [ValidateRange(0, 1000)][int]$DelayMs = 150,
    [switch]$DryRun
)

$ErrorActionPreference = 'Stop'
$remoteDir = '/data/local/tmp/vivo-double-click'
$jarPath = Join-Path $PSScriptRoot 'dist\vivo-camera.jar'
$controlPath = Join-Path $PSScriptRoot 'scripts\device-control.sh'

foreach ($requiredFile in @($jarPath, $controlPath)) {
    if (!(Test-Path -LiteralPath $requiredFile -PathType Leaf) -or (Get-Item -LiteralPath $requiredFile).Length -eq 0) {
        throw "Required file is missing or empty: $requiredFile"
    }
}

function Resolve-AdbExecutable {
    param([string]$RequestedPath)
    if ($RequestedPath) {
        $resolved = Get-Command -Name $RequestedPath -CommandType Application -ErrorAction SilentlyContinue
        if (-not $resolved) { throw "ADB was not found: $RequestedPath" }
        return $resolved.Source
    }
    throw 'The shared Android platform-tools/ADB closure is not available yet. Supply -AdbPath explicitly; no global or user-profile tool fallback is used.'
}

$adbExecutable = Resolve-AdbExecutable $AdbPath
$deviceOutput = & $adbExecutable devices
if ($LASTEXITCODE -ne 0) { throw 'Could not list ADB devices.' }
$devices = @($deviceOutput | ForEach-Object {
    if ($_ -notmatch '^List of devices attached' -and $_ -match '^(\S+)\s+(\S+)') {
        [PSCustomObject]@{ Serial = $matches[1]; State = $matches[2] }
    }
})
if (!$Serial) {
    if ($devices.Count -ne 1) {
        throw 'Connect exactly one device, or use -Serial to select a device explicitly. Run adb devices to inspect connections.'
    }
    $Serial = $devices[0].Serial
}
$selected = @($devices | Where-Object { $_.Serial -eq $Serial })
if ($selected.Count -ne 1 -or $selected[0].State -ne 'device') {
    throw "Device '$Serial' is unavailable or not authorized. Unlock the phone and allow USB debugging."
}

function Invoke-DeviceAdb {
    param([string[]]$Arguments)
    $result = & $adbExecutable -s $Serial @Arguments
    if ($LASTEXITCODE -ne 0) { throw "ADB command failed (exit $LASTEXITCODE): $($Arguments -join ' ')" }
    return $result
}

Write-Host "Installing on $Serial ..."
# Stop the old version before replacing its JAR. Missing control + existing PID is an error.
$stopExisting = "if [ -f '$remoteDir/device-control.sh' ]; then sh '$remoteDir/device-control.sh' stop; elif [ -f '$remoteDir/service.pid' ]; then echo 'ERROR: Existing PID file without a control script; refusing to replace the JAR' >&2; exit 1; fi"
Invoke-DeviceAdb -Arguments @('shell', '-T', $stopExisting)
Invoke-DeviceAdb -Arguments @('shell', '-T', "mkdir -p '$remoteDir' && chmod 700 '$remoteDir'")
Invoke-DeviceAdb -Arguments @('push', $jarPath, "$remoteDir/vivo-camera.jar.new")
Invoke-DeviceAdb -Arguments @('push', $controlPath, "$remoteDir/device-control.sh.new")
$install = "test -s '$remoteDir/vivo-camera.jar.new' && test -s '$remoteDir/device-control.sh.new' && mv '$remoteDir/vivo-camera.jar.new' '$remoteDir/vivo-camera.jar' && mv '$remoteDir/device-control.sh.new' '$remoteDir/device-control.sh' && chmod 400 '$remoteDir/vivo-camera.jar' && chmod 700 '$remoteDir/device-control.sh'"
Invoke-DeviceAdb -Arguments @('shell', '-T', $install)
$startArguments = @('shell', '-T', 'sh', "$remoteDir/device-control.sh", 'start', '--double-ms', "$DoubleMs", '--delay-ms', "$DelayMs")
if ($DryRun) { $startArguments += '--dry-run' }
$startOutput = @(Invoke-DeviceAdb -Arguments $startArguments)
$startOutput | ForEach-Object { Write-Host $_ }
if (!($startOutput | Where-Object { $_ -match '^READY([ :]|$)' })) {
    throw 'The device did not report READY. Inspect /data/local/tmp/vivo-double-click/service.log.'
}
Write-Host 'Service is READY. You may disconnect USB; run this script again after the phone restarts.'
if ($DryRun) { Write-Host 'Dry run: double-clicks are logged, but the camera is not launched.' }
