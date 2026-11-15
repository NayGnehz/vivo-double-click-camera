[CmdletBinding()]
param([Parameter(Mandatory=$true)][string]$StagingLicenseDirectory)
$ErrorActionPreference='Stop'
$licenseRoot=[IO.Path]::GetFullPath($StagingLicenseDirectory)
$sourceRoot=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot 'assets\licenses'))
if($licenseRoot.TrimEnd('\','/') -ieq $sourceRoot.TrimEnd('\','/')){throw 'License packaging must use a staging directory, not source assets.'}
if(-not(Test-Path -LiteralPath $licenseRoot -PathType Container)){throw 'License staging directory is missing.'}
$mapPath=Join-Path $licenseRoot 'PACKAGED_LICENSE_MAP.json'

function Get-TextHash([byte[]]$bytes){
    $sha=[Security.Cryptography.SHA256]::Create()
    try{return ([BitConverter]::ToString($sha.ComputeHash($bytes))).Replace('-','').ToLowerInvariant()}
    finally{$sha.Dispose()}
}
function Test-PackagedMap($map){
    if($map.format -cne 'deduplicated-license-assets-v1'){throw 'Unknown packaged license map.'}
    foreach($entry in $map.files){
        if($entry.file -notmatch '^[A-Za-z0-9][A-Za-z0-9._-]+$' -or $entry.fullTextFile -notmatch '^[A-Za-z0-9][A-Za-z0-9._-]+$'){throw 'Invalid license asset name.'}
        $fullText=[IO.File]::ReadAllBytes((Join-Path $licenseRoot $entry.fullTextFile))
        $packaged=[IO.File]::ReadAllBytes((Join-Path $licenseRoot $entry.file))
        if((Get-TextHash $fullText) -cne $entry.sourceSha256 -or (Get-TextHash $packaged) -cne $entry.packagedSha256){throw 'Packaged license verification failed.'}
    }
}
if(Test-Path -LiteralPath $mapPath){
    $existing=Get-Content -LiteralPath $mapPath -Raw -Encoding UTF8|ConvertFrom-Json
    Test-PackagedMap $existing
    [pscustomobject]@{status='verified';files=@($existing.files).Count;aliases=@($existing.files|Where-Object {$_.file -cne $_.fullTextFile}).Count}
    return
}

# Prefer the runtime component name for shared Apache/LGPL texts. Each complete
# source input is retained elsewhere in the source bundle; only build staging changes.
$files=@(Get-ChildItem -LiteralPath $licenseRoot -File | Sort-Object @{Expression={if($_.Name -in @('Kotlin-LICENSE.txt','spake2-LGPL-3.0.txt')){0}else{1}}},Name)
$canonical=@{}
$entries=[Collections.Generic.List[object]]::new()
$aliasCount=0
foreach($file in $files){
    if($file.Name -notmatch '^[A-Za-z0-9][A-Za-z0-9._-]+$'){throw 'Invalid license asset name.'}
    [byte[]]$original=[IO.File]::ReadAllBytes($file.FullName)
    $hash=Get-TextHash $original
    $fullName=$file.Name
    if($canonical.ContainsKey($hash)){
        $fullName=$canonical[$hash].name
        if([Convert]::ToBase64String($original) -cne [Convert]::ToBase64String($canonical[$hash].bytes)){throw 'License hash collision.'}
        $alias="License text for $($file.Name)`n`nThe original text is byte-for-byte identical to $fullName.`nFull license: $fullName`nOriginal SHA-256: $hash`n"
        [IO.File]::WriteAllText($file.FullName,$alias,[Text.UTF8Encoding]::new($false))
        $aliasCount++
    }else{$canonical[$hash]=@{name=$file.Name;bytes=$original}}
    $entries.Add([ordered]@{file=$file.Name;fullTextFile=$fullName;sourceSha256=$hash;packagedSha256=(Get-TextHash ([IO.File]::ReadAllBytes($file.FullName)))})
}
$map=[ordered]@{
    format='deduplicated-license-assets-v1'
    note='Every original license, notice and attribution input is retained in the source bundle. Aliases in this APK point to a byte-for-byte identical full text in this directory. sourceSha256 identifies the complete original input.'
    files=@($entries|Sort-Object file)
}
[IO.File]::WriteAllText($mapPath,($map|ConvertTo-Json -Depth 5),[Text.UTF8Encoding]::new($false))
Test-PackagedMap $map
[pscustomobject]@{status='packed';files=$files.Count;aliases=$aliasCount;uniqueTexts=$canonical.Count}
