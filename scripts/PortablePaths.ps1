function Get-VivoPortableContext {
    param([string]$InstanceId='main')
    $directory = [IO.DirectoryInfo]$PSScriptRoot
    while ($directory) {
        $entry = Join-Path $directory.FullName '_env\bin\Invoke-Portable.ps1'
        if (Test-Path -LiteralPath $entry -PathType Leaf) {
            return & $entry -Action Resolve -ProjectId vivodoubleclick -InstanceId $InstanceId
        }
        $directory = $directory.Parent
    }
    throw 'Restore the shared _env beside the project workspace first.'
}

function Invoke-VivoPortableBuild {
    param([Parameter(Mandatory)][scriptblock]$Body,[string]$InstanceId='main')
    $layout = Get-VivoPortableContext -InstanceId $InstanceId
    $buildBody = $Body
    Import-Module (Join-Path $layout.environmentRoot 'bin\PortableCache.psm1') -Force
    $callback = {
        param($context)
        $entry = Join-Path $context.environmentRoot 'bin\Invoke-Portable.ps1'
        $values = & $entry -Action Environment -ProjectId vivodoubleclick -InstanceId $InstanceId
        $saved = @{}
        try {
            foreach ($property in $values.PSObject.Properties) {
                $name = $property.Name
                $saved[$name] = [Environment]::GetEnvironmentVariable($name, 'Process')
                [Environment]::SetEnvironmentVariable($name, [string]$property.Value, 'Process')
            }
            $temporary = Join-Path $context.instanceRoot 'temp'
            [void][IO.Directory]::CreateDirectory($temporary)
            foreach ($name in @('TEMP', 'TMP', 'TMPDIR')) {
                if (-not $saved.ContainsKey($name)) { $saved[$name] = [Environment]::GetEnvironmentVariable($name, 'Process') }
                [Environment]::SetEnvironmentVariable($name, $temporary, 'Process')
            }
            $javaHome=Join-Path $context.instanceRoot 'temp\java-home'
            [void][IO.Directory]::CreateDirectory($javaHome)
            foreach ($name in @('JDK_JAVA_OPTIONS','_JAVA_OPTIONS','JAVA_TOOL_OPTIONS','CLASSPATH')) {
                if (-not $saved.ContainsKey($name)) { $saved[$name]=[Environment]::GetEnvironmentVariable($name,'Process') }
                [Environment]::SetEnvironmentVariable($name,$null,'Process')
            }
            [Environment]::SetEnvironmentVariable('JAVA_TOOL_OPTIONS',('-Duser.home="{0}" -Djava.io.tmpdir="{1}"' -f $javaHome,$temporary),'Process')
            & $buildBody $context
        } finally {
            foreach ($name in $saved.Keys) { [Environment]::SetEnvironmentVariable($name, $saved[$name], 'Process') }
        }
    }.GetNewClosure()
    Invoke-PortableInstance -ProjectId vivodoubleclick -InstanceId $InstanceId -Mode Prepare -Body $callback
}

function Assert-VivoAndroidToolchain {
    param([string]$Jdk, [string]$AndroidSdk)
    if (-not $Jdk -or -not $AndroidSdk) {
        throw 'The shared JDK 17 / Android SDK 37 offline closure is not installed yet. Supply explicit -Jdk/-AndroidSdk (or the experiment -JdkPath/-SdkPath) to use your own tools; no global-tool fallback is used.'
    }
}

function Get-VivoFixedInputRoot {
    param([Parameter(Mandatory)]$Context)
    $lock = Get-Content -LiteralPath (Join-Path (Split-Path $PSScriptRoot) 'dependencies.lock.json') -Raw | ConvertFrom-Json
    $manifestPath = [IO.Path]::GetFullPath((Join-Path $Context.environmentRoot $lock.manifest))
    $fixedRoot = [IO.Path]::GetFullPath((Join-Path $Context.environmentRoot $lock.fixedRoot))
    $prefix = [IO.Path]::GetFullPath($Context.environmentRoot).TrimEnd('\') + '\'
    if (-not $manifestPath.StartsWith($prefix, [StringComparison]::OrdinalIgnoreCase) -or
        -not $fixedRoot.StartsWith($prefix, [StringComparison]::OrdinalIgnoreCase)) {
        throw 'Fixed dependency path is outside the shared environment.'
    }
    if (-not (Test-Path -LiteralPath $manifestPath -PathType Leaf) -or
        -not (Test-Path -LiteralPath $fixedRoot -PathType Container)) {
        throw 'Restore the pinned Vivo fixed-input package in _env first.'
    }
    if ((Get-FileHash -LiteralPath $manifestPath -Algorithm SHA256).Hash.ToLowerInvariant() -cne $lock.manifestSha256) {
        throw 'Vivo fixed-input manifest identity does not match dependencies.lock.json.'
    }
    return $fixedRoot
}
function Resolve-VivoAndroidToolchain {
    param([Parameter(Mandatory)]$Context,[string]$Jdk,[string]$AndroidSdk)
    $path = Join-Path $Context.environmentRoot 'manifests\android-toolchain-v1.json'
    $manifest = Get-Content -LiteralPath $path -Raw -Encoding UTF8 | ConvertFrom-Json
    $lock=Get-Content -LiteralPath (Join-Path (Split-Path $PSScriptRoot) 'dependencies.lock.json') -Raw -Encoding UTF8 | ConvertFrom-Json
    if ($manifest.schemaVersion -ne 1 -or $manifest.id -ne $lock.toolchain.id) { throw 'Unsupported Android toolchain manifest.' }
    if ($manifest.jdk.version -ne $lock.toolchain.jdk -or $manifest.androidSdk.platform -ne $lock.toolchain.platform -or $manifest.androidSdk.buildTools -ne $lock.toolchain.buildTools) { throw 'Android tool versions do not match dependencies.lock.json.' }
    foreach ($pin in $lock.toolchain.packages) {
        $package=@($manifest.packages | Where-Object { $_.name -eq $pin.name })
        if ($package.Count -ne 1 -or $package[0].version -ne $pin.version -or $package[0].sha256 -cne $pin.sha256) { throw ('Pinned Android tool package mismatch: '+$pin.name) }
    }
    if (-not $Jdk) { $Jdk = Join-Path $Context.environmentRoot $manifest.jdk.root }
    if (-not $AndroidSdk) { $AndroidSdk = Join-Path $Context.environmentRoot $manifest.androidSdk.root }
    $nativeModule = Join-Path $Context.environmentRoot 'bin\AndroidPortableProcess.psm1'
    foreach ($required in @((Join-Path $Jdk 'bin\java.exe'),(Join-Path $Jdk 'bin\javac.exe'),$nativeModule)) {
        if (-not (Test-Path -LiteralPath $required -PathType Leaf)) { throw 'Restore the complete pinned Android shared toolchain.' }
    }
    return [pscustomobject]@{ Jdk=$Jdk; AndroidSdk=$AndroidSdk; Platform=$manifest.androidSdk.platform; BuildTools=$manifest.androidSdk.buildTools; NativeModule=$nativeModule; Descriptor=$path }
}