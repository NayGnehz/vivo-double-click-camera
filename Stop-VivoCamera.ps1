[CmdletBinding()]
param(
    [string]$AdbPath,
    [string]$Serial
)

$ErrorActionPreference = 'Stop'
$remoteDir = '/data/local/tmp/vivo-double-click'

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

$stop = "if [ -f '$remoteDir/device-control.sh' ]; then sh '$remoteDir/device-control.sh' stop; elif [ -f '$remoteDir/service.pid' ]; then echo 'ERROR: PID file exists but the control script is missing; no process was killed' >&2; exit 1; else echo 'STOPPED: service is not installed'; fi"
& $adbExecutable -s $Serial shell -T $stop
if ($LASTEXITCODE -ne 0) { throw "Stopping the service failed (exit $LASTEXITCODE)." }
