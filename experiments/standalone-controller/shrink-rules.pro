# Shrink and optimize dependency code. Preserve names, app/ADB entry points and JNI boundaries.
-dontobfuscate
-keepattributes Signature,Exceptions,InnerClasses,EnclosingMethod,*Annotation*

# Android manifest entry points, UI callbacks, and identity compatibility.
-keep class local.vivodoubleclick.controller.** { *; }
-keep class io.github.muntashirakon.adb.** { *; }

# SPAKE2 JNI is preserved; TLS uses Android's public platform provider.
-keep class io.github.muntashirakon.crypto.spake2.** { *; }
-keepclasseswithmembernames,includedescriptorclasses class * { native <methods>; }

# These two SPI implementations are selected by our small identity-only provider.
# Signature and certificate algorithms are selected from Android's standard JCA.
-keep class org.bouncycastle.jcajce.provider.asymmetric.rsa.KeyPairGeneratorSpi { *; }
-keep class org.bouncycastle.jcajce.provider.asymmetric.rsa.KeyFactorySpi { *; }
