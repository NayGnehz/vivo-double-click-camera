# SPAKE2 source and rebuilding / relinking

This directory accompanies the controller source project and APK. It contains the actual corresponding source archives for the LGPL-covered SPAKE2 dependency, including its native submodule, not only links to external sources. No dependency source has been modified in these archives.

## Exact source versions

- `spake2-java-2.2.1.zip`: official `MuntashirAkon/spake2-java` tag `2.2.1`, commit `7615ddd680b990e14513ebb66eac4cb0dbf82464`.
- `spake2-c-0d15933e5ba3e662cb01245a7ac0dc9fca3eac31.zip`: the exact native commit recorded by that release's gitlink at `android/src/main/cpp/spake2-c`.
- `SOURCE_MANIFEST.json`: original download URLs, pinned commit information, byte sizes, and SHA-256 values.
- `SHA256SUMS.txt`: SHA-256 checksums of the two unmodified archives.

The Java archive retains `gradlew`, `gradlew.bat`, the wrapper JAR/properties, root and module Gradle scripts, CMake files, `.gitmodules`, and LICENSE. The native archive retains its C sources/headers, Makefile, and LICENSE. The upstream tag's `android/build.gradle` still says `version = '2.2.0'`; this is present in the official **2.2.1 tag** and has deliberately not been rewritten.

The controller's full Java source, including its LibADB pairing patch, is in [`../src`](../src). Its manifest and resources are in [`../AndroidManifest.xml`](../AndroidManifest.xml) and [`../res`](../res); the shell listener and control assets are in [`../assets`](../assets). The actual application build is [`../Build-Standalone.ps1`](../Build-Standalone.ps1). The other dependency JARs and native Conscrypt binary used by that build are already supplied in `../libs` and `../native`.

## Extract and restore the native submodule

Use PowerShell from the **standalone-controller directory**, the directory containing `Build-Standalone.ps1`. The commands below create a fresh uniquely named source working directory; they do not change or remove the supplied archives.

```powershell
$controllerRoot = (Get-Location).Path
$sourceBundle = Join-Path $controllerRoot 'third-party-source'
$sourceWork = Join-Path $sourceBundle ('work-' + [Guid]::NewGuid().ToString('N'))
$sourceManifest = Get-Content -LiteralPath (Join-Path $sourceBundle 'SOURCE_MANIFEST.json') -Raw | ConvertFrom-Json
foreach ($item in $sourceManifest.archives) {
    $archivePath = Join-Path $sourceBundle $item.file
    $actualHash = (Get-FileHash -Algorithm SHA256 -LiteralPath $archivePath).Hash.ToLowerInvariant()
    if ($actualHash -ne $item.sha256) { throw "Source archive hash mismatch: $($item.file)" }
}
New-Item -ItemType Directory -Path $sourceWork | Out-Null
Expand-Archive -LiteralPath (Join-Path $sourceBundle 'spake2-java-2.2.1.zip') -DestinationPath $sourceWork
Expand-Archive -LiteralPath (Join-Path $sourceBundle 'spake2-c-0d15933e5ba3e662cb01245a7ac0dc9fca3eac31.zip') -DestinationPath $sourceWork
$spakeSource = Join-Path $sourceWork 'spake2-java-2.2.1'
$nativeSource = Join-Path $sourceWork 'spake2-c-0d15933e5ba3e662cb01245a7ac0dc9fca3eac31'
$nativeTarget = Join-Path $spakeSource 'android/src/main/cpp/spake2-c'
New-Item -ItemType Directory -Force -Path $nativeTarget | Out-Null
Get-ChildItem -LiteralPath $nativeSource -Force | ForEach-Object {
    Copy-Item -LiteralPath $_.FullName -Destination $nativeTarget -Recurse
}
```

After this step, `android/src/main/cpp/spake2-c/spake2.c` and `android/src/main/cpp/spake2-c/include/spake2/spake2.h` must exist below `$spakeSource`. Modify the extracted sources as desired. Keep the Java/JNI API compatible with `io.github.muntashirakon.crypto.spake2.Spake2Context` and the library name `spake2`, or update the controller/LibADB source to match an intentional API change.

## Build the replacement library

There are two routes. These instructions preserve the library's complete upstream build while also documenting the smaller direct build that matches this application's JAR-plus-native packaging. The library was **not recompiled during preparation of this source bundle**: the current machine has no Android NDK/CMake installation. The required toolchain is listed explicitly rather than silently installed.

### Route A: upstream Gradle build

The supplied release uses Android Gradle Plugin 8.9.2, a Gradle 9.0.0 wrapper, Android platform 35, and Build Tools 35.0.0. Its native build also needs an Android NDK and CMake. Use a compatible installed JDK (17 or newer as required by those tools), configure `sdk.dir` in the extracted repository's `local.properties`, and run from `$spakeSource`:

```powershell
.\gradlew.bat :android:assembleRelease
```

The first Gradle build may download its declared tool/build dependencies. The upstream wrapper/plugin combination is preserved as released; if a local Gradle compatibility issue occurs, use Route B with an already installed Android NDK/CMake instead. The expected output is `android/build/outputs/aar/android-release.aar`; extract its `classes.jar` and `jni/arm64-v8a/libspake2.so` for the replacement step below.

### Route B: direct Java + CMake/NDK build

This route needs JDK 17, Android SDK platform `android-37.0` (or another compatible installed platform), Android NDK r28 or newer, CMake, and Ninja. Set the paths to **your already installed** toolchain. CMake/Ninja can be supplied by the Android SDK CMake package. It builds the two Android Java wrapper files and the exact upstream native CMake target without Gradle. NDK r28+ and the explicit linker option below provide 16 KB ELF page alignment.

```powershell
$sdkPath = 'C:\Android\Sdk'
$jdkPath = 'C:\Tools\jdk-17
$ndkPath = 'C:\path\to\installed\android-ndk-r28-or-newer'
$cmakeExe = 'C:\path\to\installed\cmake.exe'
$ninjaExe = 'C:\path\to\installed\ninja.exe'
$javaClasses = Join-Path $sourceWork 'wrapper-classes'
$nativeBuild = Join-Path $sourceWork 'native-build'
$nativeOutput = Join-Path $sourceWork 'native-output'
$replacementJar = Join-Path $sourceWork 'spake2-2.2.1.jar'
New-Item -ItemType Directory -Force -Path $javaClasses,$nativeBuild,$nativeOutput | Out-Null
$wrapperSources = @(Get-ChildItem -LiteralPath (Join-Path $spakeSource 'android/src/main/java') -Filter '*.java' -Recurse | Select-Object -ExpandProperty FullName)
$wrapperClassPath = @(
    (Join-Path $sdkPath 'platforms/android-37.0/android.jar'),
    (Join-Path $controllerRoot 'libs/annotation-1.9.1.jar'),
    (Join-Path $controllerRoot 'libs/kotlin-stdlib-2.0.21.jar')
) -join ';'
& (Join-Path $jdkPath 'bin/javac.exe') -encoding UTF-8 --release 8 -classpath $wrapperClassPath -d $javaClasses @wrapperSources
if ($LASTEXITCODE -ne 0) { throw 'SPAKE2 Java compilation failed' }
& (Join-Path $jdkPath 'bin/jar.exe') --create --file $replacementJar -C $javaClasses .
if ($LASTEXITCODE -ne 0) { throw 'SPAKE2 JAR creation failed' }
$cmakeArguments = @(
    '-S', (Join-Path $spakeSource 'android/src/main/cpp'),
    '-B', $nativeBuild,
    '-G', 'Ninja',
    ('-DCMAKE_MAKE_PROGRAM=' + $ninjaExe),
    ('-DCMAKE_TOOLCHAIN_FILE=' + (Join-Path $ndkPath 'build/cmake/android.toolchain.cmake')),
    '-DANDROID_ABI=arm64-v8a',
    '-DANDROID_PLATFORM=android-27',
    '-DANDROID_STL=c++_static',
    '-DCMAKE_BUILD_TYPE=Release',
    ('-DCMAKE_LIBRARY_OUTPUT_DIRECTORY=' + $nativeOutput),
    '-DCMAKE_SHARED_LINKER_FLAGS=-Wl,-z,max-page-size=16384'
)
& $cmakeExe @cmakeArguments
if ($LASTEXITCODE -ne 0) { throw 'SPAKE2 CMake configuration failed' }
& $cmakeExe --build $nativeBuild --config Release
if ($LASTEXITCODE -ne 0) { throw 'SPAKE2 native compilation failed' }
$replacementSo = Join-Path $nativeOutput 'libspake2.so'
if (!(Test-Path -LiteralPath $replacementSo)) { throw 'Missing rebuilt libspake2.so' }
```

## Replace the actual inputs and rebuild the controller

`Build-Standalone.ps1` consumes `libs/spake2-2.2.1.jar` and `native/lib/arm64-v8a/libspake2.so` directly. **Replacing only `libs/spake2-2.2.1.aar` does not change this application's build.** The filename `2.2.1` is kept because the build script selects that path explicitly; record your modifications separately.

For Route B, `$replacementJar` and `$replacementSo` are already set. For Route A, extract `android-release.aar` into a fresh folder and set them to its `classes.jar` and `jni/arm64-v8a/libspake2.so`.

```powershell
$replacementBackup = Join-Path $sourceWork 'original-controller-inputs'
New-Item -ItemType Directory -Path $replacementBackup | Out-Null
Copy-Item -LiteralPath (Join-Path $controllerRoot 'libs/spake2-2.2.1.jar') -Destination (Join-Path $replacementBackup 'spake2-2.2.1.jar')
Copy-Item -LiteralPath (Join-Path $controllerRoot 'native/lib/arm64-v8a/libspake2.so') -Destination (Join-Path $replacementBackup 'libspake2.so')
Copy-Item -LiteralPath $replacementJar -Destination (Join-Path $controllerRoot 'libs/spake2-2.2.1.jar') -Force
Copy-Item -LiteralPath $replacementSo -Destination (Join-Path $controllerRoot 'native/lib/arm64-v8a/libspake2.so') -Force
& (Join-Path $controllerRoot 'Build-Standalone.ps1') -SdkPath $sdkPath -JdkPath $jdkPath
```

This recompiles all provided controller Java source, processes the replacement SPAKE2 JAR with D8, packages the replacement `.so`, aligns, signs, and verifies `dist/vivo-camera-standalone-1.0.apk`. Preserve/update the notices and modification descriptions when distributing a changed library or APK. The original archive checksums continue to describe the untouched source archives; update any inventory describing changed binary files.

## Installing a relinked build

Use Android's ordinary package installer, or your own authorized ADB connection with `adb install -r dist/vivo-camera-standalone-1.0.apk`. An in-place update needs the same app-signing key. The supplied build script uses `keys/controller.keystore`, creating a local key if absent; this signing key is unrelated to the phone's wireless ADB pairing identity. A build signed with your own different key requires uninstalling the previous package before installing it, which removes that application's private pairing state and requires pairing/permission setup again. Do not uninstall merely to test rebuilding.

The application has no library integrity check that rejects a modified, compatible SPAKE2 library. Android still enforces normal APK signing, native ABI, and app installation rules. These instructions do not require disabling device security or changing the native listener's behavior.

## Distribution contents

Keep this directory, both source archives, `../src`, `../assets`, `../res`, `../AndroidManifest.xml`, `../Build-Standalone.ps1`, the build inputs in `../libs` and `../native`, and `../THIRD_PARTY_NOTICES.md` together when sharing this source/relinking package with an APK. The source archives are outside the APK to avoid duplicating the entire project inside application assets. The APK's `assets/licenses` points to this companion project. No large unrelated dependency repositories were downloaded for this bundle.
