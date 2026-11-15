[CmdletBinding()]
param(
    [Parameter(Mandatory)][string]$SdkPath,
    [Parameter(Mandatory)][string]$JdkPath,
    [Parameter(Mandatory)][string]$WorkRoot
)
$ErrorActionPreference = 'Stop'
$controllerRoot = $PSScriptRoot
$androidJar = Join-Path $SdkPath 'platforms\android-37.0\android.jar'
$buildTools = Join-Path $SdkPath 'build-tools\36.0.0'
$java = Join-Path $JdkPath 'bin\java.exe'
$javac = Join-Path $JdkPath 'bin\javac.exe'
$jar = Join-Path $JdkPath 'bin\jar.exe'
$keytool = Join-Path $JdkPath 'bin\keytool.exe'
$aapt = Join-Path $buildTools 'aapt2.exe'
$align = Join-Path $buildTools 'zipalign.exe'
$d8 = Join-Path $buildTools 'lib\d8.jar'
$signer = Join-Path $buildTools 'lib\apksigner.jar'
function Invoke-Checked {
    param([string]$Executable, [string[]]$Arguments)
    & $Executable @Arguments
    if ($LASTEXITCODE -ne 0) { throw "Build command failed: $Executable ($LASTEXITCODE)" }
}
$buildRoot = Join-Path $WorkRoot ('build\' + [Guid]::NewGuid().ToString('N'))
$classes = Join-Path $buildRoot 'classes'
$dex = Join-Path $buildRoot 'dex'
$dist = Join-Path $WorkRoot 'dist'
$keys = Join-Path $WorkRoot 'development-signing'
New-Item -ItemType Directory -Force -Path $classes,$dex,$dist,$keys | Out-Null
$names = @('libadb-3.1.1.jar','spake2-2.2.1.jar','bcprov-1.81.jar',
    'bcpkix-1.81.jar','bcutil-1.81.jar','annotation-1.9.1.jar','kotlin-stdlib-2.0.21.jar')
$libs = @($names | ForEach-Object {
    $p = Join-Path $controllerRoot ('libs\' + $_)
    if (!(Test-Path -LiteralPath $p)) { throw "Missing dependency: $p" }
    $p
})
# A small source patch adds real socket deadlines and removes peer payload logging.
# Keep the downloaded upstream JAR intact and exclude only the replaced class family.
$patchedClass = Join-Path $controllerRoot 'src\io\github\muntashirakon\adb\PairingConnectionCtx.java'
if (Test-Path -LiteralPath $patchedClass) {
    $filteredAdb = Join-Path $buildRoot 'libadb-filtered.jar'
    Copy-Item -LiteralPath $libs[0] -Destination $filteredAdb
    Add-Type -AssemblyName System.IO.Compression
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    $archive = [System.IO.Compression.ZipFile]::Open($filteredAdb, [System.IO.Compression.ZipArchiveMode]::Update)
    try {
        $replaceEntries = @($archive.Entries | Where-Object { $_.FullName -match '^io/github/muntashirakon/adb/PairingConnectionCtx(\$[^/]+)?\.class$' })
        if ($replaceEntries.Count -eq 0) { throw 'Could not locate upstream pairing class to replace' }
        foreach ($entry in $replaceEntries) { $entry.Delete() }
    } finally { $archive.Dispose() }
    $libs[0] = $filteredAdb
}
$unsigned = Join-Path $buildRoot 'unsigned.apk'
$aligned = Join-Path $buildRoot 'aligned.apk'
$apk = Join-Path $dist 'vivo-camera-standalone-1.0.apk'
Invoke-Checked $aapt @('compile','--dir',(Join-Path $controllerRoot 'res'),'-o',(Join-Path $buildRoot 'resources.zip'))
Invoke-Checked $aapt @('link','-o',$unsigned,'--manifest',(Join-Path $controllerRoot 'AndroidManifest.xml'),
    '-I',$androidJar,'-A',(Join-Path $controllerRoot 'assets'),(Join-Path $buildRoot 'resources.zip'),
    '--min-sdk-version','31','--target-sdk-version','36')
$sources = @((Get-ChildItem -LiteralPath (Join-Path $controllerRoot 'src') -Filter '*.java' -Recurse).FullName)
Invoke-Checked $javac (@('-encoding','UTF-8','--release','8','-classpath',(($androidJar + ';') + ($libs -join ';')),'-d',$classes) + $sources)
$classFiles = @((Get-ChildItem -LiteralPath $classes -Filter '*.class' -Recurse).FullName)
Invoke-Checked $java (@('-cp',$d8,'com.android.tools.r8.R8','--release','--min-api','31','--lib',$androidJar,'--pg-conf',(Join-Path $controllerRoot 'shrink-rules.pro'),'--output',$dex) + $classFiles + $libs)
$dexFiles = @(Get-ChildItem -LiteralPath $dex -Filter 'classes*.dex')
if ($dexFiles.Count -eq 0) { throw 'D8 did not produce any DEX files' }
foreach ($item in $dexFiles) { Invoke-Checked $jar @('uf',$unsigned,'-C',$dex,$item.Name) }
Invoke-Checked $jar @('uf',$unsigned,'-C',(Join-Path $controllerRoot 'native'),'lib')
Invoke-Checked $align @('-f','-P','16','4',$unsigned,$aligned)
$keyStore = Join-Path $keys 'controller.keystore'
if (!(Test-Path -LiteralPath $keyStore)) {
    Invoke-Checked $keytool @('-genkeypair','-keystore',$keyStore,'-alias','controller','-storepass','android','-keypass','android',
        '-keyalg','RSA','-keysize','2048','-validity','3650','-dname','CN=Vivo Camera Development Build','-noprompt')
}
Invoke-Checked $java @('-jar',$signer,'sign','--ks',$keyStore,'--ks-key-alias','controller','--ks-pass','pass:android',
    '--key-pass','pass:android','--out',$apk,$aligned)
Invoke-Checked $java @('-jar',$signer,'verify','--verbose',$apk)
Invoke-Checked $align @('-c','-P','16','4',$apk)
$manifest = & $aapt dump xmltree $apk --file AndroidManifest.xml
if ($LASTEXITCODE -ne 0) { throw 'Could not inspect built manifest' }
$manifest | Set-Content -LiteralPath (Join-Path $dist 'manifest-dump.txt') -Encoding utf8
if (($manifest -join "`n") -match 'Shizuku') { throw 'Unexpected external service dependency' }
$pairConfig = & $aapt dump xmltree $apk --file res/xml/pairing_accessibility.xml
if ($LASTEXITCODE -ne 0) { throw 'Could not inspect pairing helper configuration' }
$pairConfig | Set-Content -LiteralPath (Join-Path $dist 'pairing-helper-dump.txt') -Encoding utf8
if (($pairConfig -join "`n") -match 'canRequestFilterKeyEvents|flagRequestFilterKeyEvents') { throw 'Pairing helper must not request key filtering' }
Get-FileHash -Algorithm SHA256 -LiteralPath $apk | ConvertTo-Json -Compress
Write-Output "APK: $apk"
