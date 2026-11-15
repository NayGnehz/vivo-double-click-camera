# Shrink unused library code only. Keep names and bytecode behavior stable.
-dontobfuscate
-dontoptimize
-keepattributes Signature,Exceptions,InnerClasses,EnclosingMethod,*Annotation*

# Android manifest entry points, UI callbacks, and identity compatibility.
-keep class local.vivodoubleclick.controller.** { *; }
-keep class io.github.muntashirakon.adb.** { *; }

# Exact Conscrypt 2.6.3 consumer rule: JNI and reflection enter these classes.
-keep class org.conscrypt.** { *; }
-keep class io.github.muntashirakon.crypto.spake2.** { *; }
-keepclasseswithmembernames,includedescriptorclasses class * { native <methods>; }

# BC loads algorithm mappings and SPI implementations by name. This app uses
# its existing RSA/SHA-256 and X.509 provider for ADB identities, unchanged.
-keep class org.bouncycastle.jcajce.provider.asymmetric.RSA$* { *; }
-keep class org.bouncycastle.jcajce.provider.asymmetric.rsa.** { *; }
-keep class org.bouncycastle.jcajce.provider.asymmetric.X509$* { *; }
-keep class org.bouncycastle.jcajce.provider.asymmetric.x509.** { *; }
-keep class org.bouncycastle.jcajce.provider.digest.SHA256$* { *; }

# Optional Android-internal compatibility paths, from Conscrypt's consumer rules.
-dontwarn android.util.StatsEvent
-dontwarn dalvik.system.BlockGuard
-dontwarn dalvik.system.BlockGuard$Policy
-dontwarn dalvik.system.CloseGuard
-dontwarn com.android.org.conscrypt.AbstractConscryptSocket
-dontwarn com.android.org.conscrypt.ConscryptFileDescriptorSocket
-dontwarn com.android.org.conscrypt.OpenSSLSocketImpl
-dontwarn com.android.org.conscrypt.SSLParametersImpl
-dontwarn org.apache.harmony.xnet.provider.jsse.OpenSSLSocketImpl
-dontwarn org.apache.harmony.xnet.provider.jsse.SSLParametersImpl
