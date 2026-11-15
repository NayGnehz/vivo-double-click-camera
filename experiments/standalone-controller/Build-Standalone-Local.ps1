[CmdletBinding()]
param([string]$SdkPath,[string]$JdkPath,[string]$InstanceId='main',[switch]$Unsigned)
$ErrorActionPreference='Stop'
$sourceRoot=$PSScriptRoot
. (Join-Path (Split-Path (Split-Path $PSScriptRoot)) 'scripts\PortablePaths.ps1')
$layout=Get-VivoPortableContext -InstanceId $InstanceId
$toolchain=Resolve-VivoAndroidToolchain -Context $layout -Jdk $JdkPath -AndroidSdk $SdkPath
$fixedRoot=Get-VivoFixedInputRoot -Context $layout
Invoke-VivoPortableBuild -InstanceId $InstanceId -Body ({
    param($context)
    Import-Module $toolchain.NativeModule -Force
    $buildRoot=Join-Path $context.instanceRoot ('build\standalone-controller\'+[Guid]::NewGuid().ToString('N'))
    $nativeRoot=Join-Path $context.instanceRoot ('temp\native\standalone-'+[Guid]::NewGuid().ToString('N'))
    $classes=Join-Path $buildRoot 'classes'
    $dex=Join-Path $buildRoot 'dex'
    $dist=Join-Path $context.instanceRoot 'build\candidates\standalone-controller'
    $keys=Join-Path $context.instanceRoot 'temp\development-signing'
    foreach($directory in @($classes,$dex,$dist,$keys,$nativeRoot)){[void][IO.Directory]::CreateDirectory($directory)}
    $nativeRuns=[Collections.Generic.List[object]]::new()
    function Invoke-Checked {
        param([string]$Executable,[string[]]$Arguments,[switch]$CaptureOutput)
        $step=('{0:d2}-{1}' -f ($nativeRuns.Count+1),[IO.Path]::GetFileNameWithoutExtension($Executable))
        # The Android native tools use ANSI file APIs on Windows. Relative arguments
        # keep the inherited Unicode working directory out of those file names.
        $workingDirectory=$sourceRoot
        if ([IO.Path]::GetFileName($Executable) -in @('aapt2.exe','zipalign.exe')) {
            $workingDirectory=$buildRoot
            $Arguments=@($Arguments | ForEach-Object {
                if ([IO.Path]::IsPathRooted($_)) { [IO.Path]::GetRelativePath($buildRoot,$_) } else { $_ }
            })
        }
        $result=Invoke-AndroidPortableProcess -FilePath $Executable -Arguments $Arguments -WorkingDirectory $workingDirectory -LogRoot (Join-Path $nativeRoot $step)
        $nativeRuns.Add($result)
        if($result.ExitCode -ne 0){throw ('Standalone build step failed: '+$step+'; inspect '+$result.LogRoot)}
        if($CaptureOutput){return @(Get-Content -LiteralPath (Join-Path $result.LogRoot 'stdout.log') -Encoding UTF8)}
    }
    $androidJar=Join-Path $toolchain.AndroidSdk ('platforms\'+$toolchain.Platform+'\android.jar')
    $buildTools=Join-Path $toolchain.AndroidSdk ('build-tools\'+$toolchain.BuildTools)
    $java=Join-Path $toolchain.Jdk 'bin\java.exe'
    $javac=Join-Path $toolchain.Jdk 'bin\javac.exe'
    $jar=Join-Path $toolchain.Jdk 'bin\jar.exe'
    $keytool=Join-Path $toolchain.Jdk 'bin\keytool.exe'
    $aapt=Join-Path $buildTools 'aapt2.exe'
    $align=Join-Path $buildTools 'zipalign.exe'
    $d8=Join-Path $buildTools 'lib\d8.jar'
    $signer=Join-Path $buildTools 'lib\apksigner.jar'
    $names=@('libadb-3.1.1.jar','spake2-2.2.1.jar','conscrypt-2.6.3.jar','bcprov-1.81.jar','bcpkix-1.81.jar','bcutil-1.81.jar','annotation-1.9.1.jar','kotlin-stdlib-2.0.21.jar')
    $libs=@($names|ForEach-Object {
        $path=Join-Path $fixedRoot ('experiments\standalone-controller\libs\'+$_)
        if(-not(Test-Path -LiteralPath $path -PathType Leaf)){throw 'Restore fixed Android dependencies first.'}
        $path
    })
    # The unchanged project source replaces only the upstream class family.
    $filteredAdb=Join-Path $buildRoot 'libadb-filtered.jar'
    Copy-Item -LiteralPath $libs[0] -Destination $filteredAdb
    Add-Type -AssemblyName System.IO.Compression
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    $archive=[IO.Compression.ZipFile]::Open($filteredAdb,[IO.Compression.ZipArchiveMode]::Update)
    try {
        $entries=@($archive.Entries|Where-Object {$_.FullName -match '^io/github/muntashirakon/adb/PairingConnectionCtx(\$[^/]+)?\.class$'})
        if(-not $entries.Count){throw 'Expected upstream pairing class family missing.'}
        foreach($entry in $entries){$entry.Delete()}
    } finally {$archive.Dispose()}
    $libs[0]=$filteredAdb
    $assetRoot=Join-Path $buildRoot 'assets'
    [void][IO.Directory]::CreateDirectory($assetRoot)
    foreach($asset in Get-ChildItem -LiteralPath (Join-Path $sourceRoot 'assets') -Force){
        if($asset.Name -ne 'licenses'){Copy-Item -LiteralPath $asset.FullName -Destination $assetRoot -Recurse}
    }
    Copy-Item -LiteralPath (Join-Path $fixedRoot 'experiments\standalone-controller\assets\licenses') -Destination (Join-Path $assetRoot 'licenses') -Recurse
    # Stage only packaging inputs so native tools never traverse Chinese path segments.
    $packagingInput=Join-Path $buildRoot 'input'
    [void][IO.Directory]::CreateDirectory($packagingInput)
    Copy-Item -LiteralPath (Join-Path $sourceRoot 'res') -Destination (Join-Path $packagingInput 'res') -Recurse
    Copy-Item -LiteralPath (Join-Path $sourceRoot 'AndroidManifest.xml') -Destination (Join-Path $packagingInput 'AndroidManifest.xml')
    $packagingAndroidJar=Join-Path $packagingInput 'android.jar'
    Copy-Item -LiteralPath $androidJar -Destination $packagingAndroidJar
    $unsignedApk=Join-Path $buildRoot 'unsigned.apk'
    $aligned=Join-Path $buildRoot 'aligned.apk'
    $apk=Join-Path $dist 'vivo-camera-standalone-1.0.apk'
    Invoke-Checked $aapt @('compile','--dir',(Join-Path $packagingInput 'res'),'-o',(Join-Path $buildRoot 'resources.zip'))
    Invoke-Checked $aapt @('link','-o',$unsignedApk,'--manifest',(Join-Path $packagingInput 'AndroidManifest.xml'),'-I',$packagingAndroidJar,'-A',$assetRoot,(Join-Path $buildRoot 'resources.zip'),'--min-sdk-version','27','--target-sdk-version','36')
    $sources=@((Get-ChildItem -LiteralPath (Join-Path $sourceRoot 'src') -Filter '*.java' -Recurse).FullName)
    Invoke-Checked $javac (@('-encoding','UTF-8','--release','8','-classpath',(($androidJar+';')+($libs -join ';')),'-d',$classes)+$sources)
    $classFiles=@((Get-ChildItem -LiteralPath $classes -Filter '*.class' -Recurse).FullName)
    Invoke-Checked $java (@('-cp',$d8,'com.android.tools.r8.D8','--min-api','27','--lib',$androidJar,'--output',$dex)+$classFiles+$libs)
    $dexFiles=@(Get-ChildItem -LiteralPath $dex -Filter 'classes*.dex')
    if(-not $dexFiles.Count){throw 'D8 did not produce DEX output.'}
    foreach($item in $dexFiles){Invoke-Checked $jar @('uf',$unsignedApk,'-C',$dex,$item.Name)}
    Invoke-Checked $jar @('uf',$unsignedApk,'-C',(Join-Path $fixedRoot 'experiments\standalone-controller\native'),'lib')
    Invoke-Checked $align @('-f','-P','16','4',$unsignedApk,$aligned)
    if($Unsigned){
        Copy-Item -LiteralPath $aligned -Destination $apk -Force
    } else {
        # This cache-only key is disposable, unrelated to the user's signing keys.
        $keyStore=Join-Path $keys 'portable-development.keystore'
        if(-not(Test-Path -LiteralPath $keyStore)){
            Invoke-Checked $keytool @('-genkeypair','-keystore',$keyStore,'-alias','portable-development','-storepass','android','-keypass','android','-keyalg','RSA','-keysize','2048','-validity','3650','-dname','CN=Portable Development Only,O=Local,C=CN','-noprompt')
        }
        Invoke-Checked $java @('-jar',$signer,'sign','--ks',$keyStore,'--ks-key-alias','portable-development','--ks-pass','pass:android','--key-pass','pass:android','--out',$apk,$aligned)
        Invoke-Checked $java @('-jar',$signer,'verify','--verbose',$apk)
    }
    Invoke-Checked $align @('-c','-P','16','4',$apk)
    $manifest=Invoke-Checked $aapt @('dump','xmltree',$apk,'--file','AndroidManifest.xml') -CaptureOutput
    $manifest|Set-Content -LiteralPath (Join-Path $dist 'manifest-dump.txt') -Encoding UTF8
    if(($manifest -join [Environment]::NewLine) -match 'Shizuku'){throw 'Unexpected external service dependency.'}
    $pairConfig=Invoke-Checked $aapt @('dump','xmltree',$apk,'--file','res/xml/pairing_accessibility.xml') -CaptureOutput
    $pairConfig|Set-Content -LiteralPath (Join-Path $dist 'pairing-helper-dump.txt') -Encoding UTF8
    if(($pairConfig -join [Environment]::NewLine) -match 'canRequestFilterKeyEvents|flagRequestFilterKeyEvents'){throw 'Pairing helper must not request key filtering.'}
    $result=[ordered]@{status='built';product='standalone-controller-current-source';artifact=$apk;bytes=(Get-Item -LiteralPath $apk).Length;sha256=(Get-FileHash -LiteralPath $apk -Algorithm SHA256).Hash.ToLowerInvariant();instance=$InstanceId;nativeRuns=@($nativeRuns);developmentSignature=(-not $Unsigned);realDataOrKeysAccessed=$false;installed=$false;published=$false;toolchain=$toolchain.Descriptor}
    $resultPath=Join-Path $context.instanceRoot 'build\standalone-portable-build-result.json'
    [IO.File]::WriteAllText($resultPath,($result|ConvertTo-Json -Depth 8),[Text.UTF8Encoding]::new($false))
    [pscustomobject]@{product='standalone';artifact=$apk;result=$resultPath;nativeSteps=$nativeRuns.Count;developmentSignature=(-not $Unsigned)}
}.GetNewClosure())