[CmdletBinding()]
param([string]$Jdk,[string]$AndroidSdk,[string]$InstanceId='main')
$ErrorActionPreference='Stop'
$sourceRoot=$PSScriptRoot
. (Join-Path $PSScriptRoot 'scripts\PortablePaths.ps1')
$layout=Get-VivoPortableContext -InstanceId $InstanceId
$toolchain=Resolve-VivoAndroidToolchain -Context $layout -Jdk $Jdk -AndroidSdk $AndroidSdk
Invoke-VivoPortableBuild -InstanceId $InstanceId -Body ({
    param($context)
    Import-Module $toolchain.NativeModule -Force
    $work=Join-Path $context.instanceRoot 'build\core'
    $classes=Join-Path $work 'classes'
    $testClasses=Join-Path $work 'tests'
    $candidate=Join-Path $context.instanceRoot 'build\candidates\core'
    $nativeRoot=Join-Path $context.instanceRoot ('temp\native\core-'+[Guid]::NewGuid().ToString('N'))
    foreach($directory in @($classes,$testClasses,$candidate,$nativeRoot)){[void][IO.Directory]::CreateDirectory($directory)}
    $nativeRuns=[Collections.Generic.List[object]]::new()
    function Invoke-CoreTool([string]$Executable,[string[]]$NativeArguments,[string]$Step) {
        $result=Invoke-AndroidPortableProcess -FilePath $Executable -Arguments $NativeArguments -WorkingDirectory $sourceRoot -LogRoot (Join-Path $nativeRoot $Step)
        $nativeRuns.Add($result)
        if($result.ExitCode -ne 0){throw ('Portable core step failed: '+$Step+'; inspect '+$result.LogRoot)}
    }
    $java=Join-Path $toolchain.Jdk 'bin\java.exe'
    $javac=Join-Path $toolchain.Jdk 'bin\javac.exe'
    $jar=Join-Path $toolchain.Jdk 'bin\jar.exe'
    $androidJar=Join-Path $toolchain.AndroidSdk ('platforms\'+$toolchain.Platform+'\android.jar')
    $d8=Join-Path $toolchain.AndroidSdk ('build-tools\'+$toolchain.BuildTools+'\lib\d8.jar')
    foreach($file in @($androidJar,$d8)){if(-not(Test-Path -LiteralPath $file -PathType Leaf)){throw 'Pinned Android platform/build tool is missing'}}
    $sources=@(Get-ChildItem -LiteralPath (Join-Path $sourceRoot 'src\main\java') -Filter '*.java' -Recurse|ForEach-Object FullName)
    Invoke-CoreTool $javac (@('-encoding','UTF-8','-source','8','-target','8','-Xlint:-options','-cp',$androidJar,'-d',$classes)+$sources) '01-compile-core'
    Invoke-CoreTool $javac @('-encoding','UTF-8','-source','8','-target','8','-Xlint:-options','-cp',$classes,'-d',$testClasses,(Join-Path $sourceRoot 'tests\DetectorTest.java')) '02-compile-existing-detector-test'
    Invoke-CoreTool $java @('-cp',($classes+';'+$testClasses),'local.vivodoubleclick.DetectorTest') '03-existing-detector-test'
    $compiled=Join-Path $work 'classes.jar'
    Invoke-CoreTool $jar @('cf',$compiled,'-C',$classes,'.') '04-jar'
    $output=Join-Path $candidate 'vivo-camera.jar'
    Invoke-CoreTool $java @('-cp',$d8,'com.android.tools.r8.D8','--min-api','26','--lib',$androidJar,'--output',$output,$compiled) '05-dex'
    $result=[ordered]@{status='built';product='accepted-core-1.0-source';artifact=$output;bytes=(Get-Item -LiteralPath $output).Length;sha256=(Get-FileHash -LiteralPath $output -Algorithm SHA256).Hash.ToLowerInvariant();instance=$InstanceId;nativeRuns=@($nativeRuns);toolchain=$toolchain.Descriptor;installed=$false;published=$false;realDataOrKeysAccessed=$false}
    $resultPath=Join-Path $work 'portable-build-result.json'
    [IO.File]::WriteAllText($resultPath,($result|ConvertTo-Json -Depth 8),[Text.UTF8Encoding]::new($false))
    [pscustomobject]@{product='core';artifact=$output;result=$resultPath;nativeSteps=$nativeRuns.Count}
}.GetNewClosure())