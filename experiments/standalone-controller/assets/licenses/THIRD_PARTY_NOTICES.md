# License asset packaging update

The APK may consolidate byte-for-byte identical full license texts. Each original
asset name is retained: duplicate copies become short references to a complete,
verbatim text in the same `assets/licenses/` directory. No unique copyright,
attribution, NOTICE, or license clause is removed. `PACKAGED_LICENSE_MAP.json`
maps every original name and SHA-256 to its full text and records packaged hashes.

The original downloaded license assets and `LICENSE_SOURCES.json` remain unchanged
in the source bundle and fixed inputs. This transformation applies only to build
staging. It does not remove historical Conscrypt/BoringSSL provenance or any LGPL
source/relinking materials. Independent and local builds use the same
`Pack-LicenseAssets.ps1` step.

---

# Packaging update: 1.0-standalone.8

This Android 12+ build uses Android's platform TLS 1.3 implementation and the public
android.net.ssl.SSLSockets.exportKeyingMaterial API (API 31+). No org.conscrypt JAR or
libconscrypt_jni.so is distributed in the APK. Historical Conscrypt/BoringSSL license
texts below are retained as provenance of earlier fixed build inputs.

RSA software key generation/decoding remains provided by the original Bouncy Castle
RSA key implementations, with an identity-only two-service Provider. Signatures and
X.509 certificate parsing use standard platform JCA. The persisted identity format,
SPAKE2 JNI binary, existing listener JAR and remaining dependency versions are unchanged.

The local PairingConnectionCtx patch now uses the public Android 12+ TLS exporter,
keeping the ADB exporter label, null context and 64-byte output length unchanged.

The dependency inventory below documents the original fixed inputs. Conscrypt/BoringSSL
are not runtime dependencies of .8; all remaining applicable third-party notices and
the LGPL SPAKE2 corresponding-source/relinking materials continue to accompany this build.

---

# Third-party notices for the standalone controller

This inventory describes the binary dependencies currently in this project's `libs` directory. License and notice texts are included under `assets/licenses` so they can be distributed inside the APK. Reviewed on 2026-09-17 against the versioned upstream sources and the actual local artifacts. This document does not assign a license to the controller's own code.

## Dependencies

| Dependency | Version / exact revision | Applicable licenses and notices | Official source |
| --- | --- | --- | --- |
| `com.github.MuntashirAkon:libadb-android` | 3.1.1 | Apache-2.0 option selected from upstream's GPL-3.0-or-later OR Apache-2.0 grant; original BSD-3-Clause and MIT portions retained. See `libadb-COPYING.txt`, `libadb-ATTRIBUTION.txt`, and the three `libadb-*-*.txt` license files. | https://github.com/MuntashirAkon/libadb-android/tree/3.1.1 |
| `com.github.MuntashirAkon.spake2-java:spake2-android` | 2.2.1 | LGPL-3.0; the library and its use are covered by the LGPL. See `spake2-ATTRIBUTION.txt`, `spake2-LGPL-3.0.txt`, and `GNU-GPL-3.0.txt`. | https://github.com/MuntashirAkon/spake2-java/tree/2.2.1 |
| SPAKE2 native implementation, included by `spake2-android` | `0d15933e5ba3e662cb01245a7ac0dc9fca3eac31` | Upstream LGPL-3.0; source components also include LGPL-2.1-or-later (SHA-512), ISC (Google-derived SPAKE2 / Ed25519), MIT (fiat-crypto), and Apache-2.0 (public header). Exact notices are in `spake2-c-THIRD-PARTY-NOTICES.txt`; full LGPL texts are included. | https://github.com/MuntashirAkon/spake2-c/tree/0d15933e5ba3e662cb01245a7ac0dc9fca3eac31 |
| `org.conscrypt:conscrypt-android` | 2.6.3 | Apache-2.0, with the upstream NOTICE and its Apache Harmony / Netty notices retained in `Conscrypt-*`. | https://github.com/google/conscrypt/tree/2.6.3 |
| BoringSSL, embedded by Conscrypt | `3adc3d1aba162a578e2547f329fcce8659b8e89c` | Apache-2.0 at this exact revision. Preserve `BoringSSL-LICENSE.txt`, `BoringSSL-fiat-LICENSE.txt`, and `BoringSSL-fiat-AUTHORS.txt`. The complete upstream LICENSE also contains a notice expressly limited to test support code; that does not mean those tests are shipped. | https://github.com/google/boringssl/tree/3adc3d1aba162a578e2547f329fcce8659b8e89c |
| `org.bouncycastle:bcprov-jdk15to18`, `bcpkix-jdk15to18`, `bcutil-jdk15to18` | 1.81 | Bouncy Castle's MIT-style license, verbatim in `BouncyCastle-LICENSE.html`. Copyright (c) 2000-2023 The Legion of the Bouncy Castle Inc., as written in tag r1rv81 and the actual bcprov LICENSE class. | https://github.com/bcgit/bc-java/tree/r1rv81 |
| `androidx.annotation:annotation-jvm` | 1.9.1 | Apache-2.0. `AndroidX-annotation-LICENSE.txt` was extracted verbatim from the actual dependency JAR. | https://dl.google.com/dl/android/maven2/androidx/annotation/annotation-jvm/1.9.1/annotation-jvm-1.9.1.jar |
| `org.jetbrains.kotlin:kotlin-stdlib` | 2.0.21 | Apache-2.0; JVM math portions use Boost Software License 1.0. Retain `Kotlin-LICENSE.txt`, `Kotlin-Boost-LICENSE.txt`, `Kotlin-GWT-LICENSE.txt`, and `Kotlin-Guava-LICENSE.txt`. | https://github.com/JetBrains/kotlin/tree/v2.0.21 |

This application includes a locally modified copy of LibADB 3.1.1's `PairingConnectionCtx.java` at `src/io/github/muntashirakon/adb/PairingConnectionCtx.java`. Its source header identifies the upstream commit `c849886ebc6d48e7b46d967e78a6bb65c90c3b74` and the changes: bounded socket operations, an overall deadline, cancellation, null-safe cleanup, and removal of peer identity data from logs. The original SPDX notice is retained; the Apache-2.0 option applies. The upstream source is https://github.com/MuntashirAkon/libadb-android/blob/3.1.1/libadb/src/main/java/io/github/muntashirakon/adb/PairingConnectionCtx.java .

The BoringSSL revision above was read from `org/conscrypt/conscrypt.properties` in the actual `conscrypt-2.6.3.jar`, rather than inferred from the current BoringSSL branch. The SPAKE2 native revision is the gitlink at `android/src/main/cpp/spake2-c` in tag 2.2.1. The pure-Java SPAKE2 implementation and its CC0 EdDSA dependency are not included by the selected `spake2-android` AAR.

Kotlin attribution: Copyright 2010-2024 JetBrains s.r.o and respective authors and developers. The verbatim repository `Kotlin-upstream-NOTICE.txt` is retained; its original title refers to the compiler distribution, while this APK includes only the standard library. The standard-library notices are identified in the versioned [Kotlin license inventory](https://github.com/JetBrains/kotlin/blob/v2.0.21/license/README.md): collections derived from GWT (Copyright 2007-08 Google Inc.), `UnsignedJVM.kt` derived from Guava (Copyright 2011 The Guava Authors), and `MathJVM.kt` derived from Boost special math functions (Copyright Eric Ford & Hubert Holin 2001). Compiler-only and test-only dependencies are not claimed as APK dependencies.

## LGPL source and relinking materials

SPAKE2-Android and its native library are LGPL-covered components, not Apache-only dependencies. Copies of LGPL-3.0, GPL-3.0, LGPL-2.1, the native component license, and the original copyright notices accompany this inventory. Nothing in these notices restricts modification of the LGPL-covered portions or reverse engineering for debugging those modifications.

The companion local project now includes the actual source archives in `third-party-source/spake2-java-2.2.1.zip` and `third-party-source/spake2-c-0d15933e5ba3e662cb01245a7ac0dc9fca3eac31.zip`, including the upstream build scripts and licenses. `third-party-source/SOURCE_MANIFEST.json` and `SHA256SUMS.txt` record their exact revisions and verified hashes. `third-party-source/README.md` explains restoration of the native submodule, the required Java/Android native toolchain, library rebuilding, replacement of the actual JAR/native-library inputs, full application rebuilding, and installation/signing considerations.

The controller source (`src`), resources (`res`), shell assets (`assets`), manifest, remaining binary build inputs (`libs`, `native`), and root `Build-Standalone.ps1` accompany these archives in this local project. These materials allow rebuilding/relinking with a modified compatible library; no application check forbids such a replacement. The library native rebuild instructions were checked against its source/build definitions but have not been executed here because this machine has no Android NDK/CMake installed. Share this companion project and source bundle together with the APK; the source ZIPs are outside the APK, while the license texts and this notice are included as APK assets. Nothing here claims a bit-for-bit reproduction of the upstream binary.

## License provenance

The downloaded license files were preserved verbatim. The two attribution files collect actual upstream copyright notices; `spake2-c-THIRD-PARTY-NOTICES.txt` preserves the complete leading license comments of the listed native source files. `LICENSE_SOURCES.json` records each source and SHA-256 of its copied text. The GNU GPL copy retains the upstream SPDX usage preface before its complete license text.

- `AndroidX-annotation-LICENSE.txt` — https://dl.google.com/dl/android/maven2/androidx/annotation/annotation-jvm/1.9.1/annotation-jvm-1.9.1.jar#META-INF/androidx/annotation/annotation/LICENSE.txt
- `BoringSSL-LICENSE.txt` — https://raw.githubusercontent.com/google/boringssl/3adc3d1aba162a578e2547f329fcce8659b8e89c/LICENSE
- `BoringSSL-fiat-AUTHORS.txt` — https://raw.githubusercontent.com/google/boringssl/3adc3d1aba162a578e2547f329fcce8659b8e89c/third_party/fiat/AUTHORS
- `BoringSSL-fiat-LICENSE.txt` — https://raw.githubusercontent.com/google/boringssl/3adc3d1aba162a578e2547f329fcce8659b8e89c/third_party/fiat/LICENSE
- `BouncyCastle-LICENSE.html` — https://raw.githubusercontent.com/bcgit/bc-java/r1rv81/LICENSE.html
- `Conscrypt-Harmony-LICENSE.txt` — https://raw.githubusercontent.com/google/conscrypt/2.6.3/licenses/LICENSE.harmony.txt
- `Conscrypt-LICENSE.txt` — https://raw.githubusercontent.com/google/conscrypt/2.6.3/LICENSE
- `Conscrypt-NOTICE.txt` — https://raw.githubusercontent.com/google/conscrypt/2.6.3/NOTICE
- `Conscrypt-Netty-LICENSE.txt` — https://raw.githubusercontent.com/google/conscrypt/2.6.3/licenses/LICENSE.netty.txt
- `GNU-GPL-3.0.txt` — https://raw.githubusercontent.com/MuntashirAkon/libadb-android/3.1.1/LICENSES/GPL-3.0-or-later
- `GNU-LGPL-2.1.txt` — https://www.gnu.org/licenses/old-licenses/lgpl-2.1.txt
- `Kotlin-Boost-LICENSE.txt` — https://raw.githubusercontent.com/JetBrains/kotlin/v2.0.21/license/third_party/boost_LICENSE.txt
- `Kotlin-GWT-LICENSE.txt` — https://raw.githubusercontent.com/JetBrains/kotlin/v2.0.21/license/third_party/gwt_license.txt
- `Kotlin-Guava-LICENSE.txt` — https://raw.githubusercontent.com/JetBrains/kotlin/v2.0.21/license/third_party/guava_license.txt
- `Kotlin-LICENSE.txt` — https://raw.githubusercontent.com/JetBrains/kotlin/v2.0.21/license/LICENSE.txt
- `Kotlin-upstream-NOTICE.txt` — https://raw.githubusercontent.com/JetBrains/kotlin/v2.0.21/license/NOTICE.txt
- `libadb-ATTRIBUTION.txt` — Attribution from exact tag source headers and README
- `libadb-Apache-2.0.txt` — https://raw.githubusercontent.com/MuntashirAkon/libadb-android/3.1.1/LICENSES/Apache-2.0
- `libadb-BSD-3-Clause.txt` — https://raw.githubusercontent.com/MuntashirAkon/libadb-android/3.1.1/LICENSES/BSD-3-Clause
- `libadb-COPYING.txt` — https://raw.githubusercontent.com/MuntashirAkon/libadb-android/3.1.1/COPYING
- `libadb-MIT.txt` — https://raw.githubusercontent.com/MuntashirAkon/libadb-android/3.1.1/LICENSES/MIT
- `spake2-ATTRIBUTION.txt` — Attribution from exact tag source headers and README
- `spake2-LGPL-3.0.txt` — https://raw.githubusercontent.com/MuntashirAkon/spake2-java/2.2.1/LICENSE
- `spake2-c-LGPL-3.0.txt` — https://raw.githubusercontent.com/MuntashirAkon/spake2-c/0d15933e5ba3e662cb01245a7ac0dc9fca3eac31/LICENSE
- `spake2-c-THIRD-PARTY-NOTICES.txt` — Verbatim header extraction; exact source URLs in file

## Local artifact SHA-256

The JAR entries extracted from AARs are listed alongside their original containers; they are the same dependency, not additional products.

| Local file | SHA-256 |
| --- | --- |
| `annotation-1.9.1.jar` | `1e343917ebf27ba96fe4dc52b1cad7fd32b738fbc6355bb6cd5b3b305d7212d0` |
| `bcpkix-1.81.jar` | `aa204a0c8b5f5564bf2b1a055e2550534b24217b23f63ea4cb762c908ff811d9` |
| `bcprov-1.81.jar` | `0ef9c4d9536719aaa1c92a7c694d87981fa9d4d64dd9479cc322a08aebbc651e` |
| `bcutil-1.81.jar` | `82fc2c3bc17c06a6fc4e28b73adf1601a01b91a97f7b72c0cfbabaf8bf1f3fe7` |
| `conscrypt-2.6.3.aar` | `dcfbda8f3a5e01e0b06263e1b969dfa7d6dd389c8b54e5c08be11cddf2f43edf` |
| `conscrypt-2.6.3.jar` | `35c3e7bda831a64fd7801a0c8437a96802a1cddcb8a50913e4ca00ea215b516c` |
| `kotlin-stdlib-2.0.21.jar` | `f31cc53f105a7e48c093683bbd5437561d1233920513774b470805641bedbc09` |
| `libadb-3.1.1.aar` | `e6fcb495a20a507ca4a125afc9c554e838a84b3a15fbcc29f7deafd0f88bbb58` |
| `libadb-3.1.1.jar` | `6700483121b899978ccf3dcd20d18cdb137c41df321a5d202d8f17e616a3af18` |
| `spake2-2.2.1.aar` | `8798fb6c04b5d53a6307ed9481e9afe563227abd4e8b9e917c8514fa850071bb` |
| `spake2-2.2.1.jar` | `91303c0c773db231079184f9266ce9c90c78cefa1c26ec4e0e71577d7c49c71a` |
