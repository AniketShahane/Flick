# R8 rules for the TV app. The release build runs R8 in full mode, so anything
# reached by name rather than by a static reference has to be named here.
#
# Bias: keep too much rather than too little. A release APK a few hundred
# kilobytes larger is not a defect; one that cannot decode a Dolby Vision stream
# or cannot accept a pairing is.

# Readable failure diagnostics. FlickLog records `err=${e.javaClass.simpleName}`
# and CastFailureCode reporting is built on exception identity, so obfuscated
# throwable names would degrade every structured failure the TV reports.
-keepnames class * extends java.lang.Throwable
-keepattributes SourceFile,LineNumberTable,Signature,Exceptions,InnerClasses,EnclosingMethod
-renamesourcefileattribute SourceFile

# Load-bearing, not defensive: PlaybackFailureClassifier distinguishes an
# unsupported container from malformed bytes by comparing
# `cause.javaClass.name` against this exact string. Renaming it silently
# reclassifies every container rejection as MALFORMED_MEDIA.
-keepnames class androidx.media3.exoplayer.source.UnrecognizedInputFormatException

# Media3 / ExoPlayer. The library ships consumer rules for its own reflective
# extractor and renderer lookups; these cover the classpath references to
# optional decoder extensions that are not on this build's classpath at all.
-dontwarn androidx.media3.**
-dontwarn com.google.common.**

# Ktor 3 (CIO server + websockets) for the control channel. Ktor resolves
# engines, plugin attribute keys and coroutine internals through type tokens and
# service lookups that R8 cannot follow.
-keep class io.ktor.** { *; }
-keep class kotlinx.coroutines.** { *; }
-keepclassmembers class kotlinx.coroutines.** { volatile <fields>; }
-dontwarn io.ktor.**
-dontwarn kotlinx.coroutines.**
-dontwarn kotlinx.io.**

# slf4j-simple is Ktor's logging backend and is found through
# META-INF/services/org.slf4j.spi.SLF4JServiceProvider, never by reference.
-keep class org.slf4j.** { *; }
-dontwarn org.slf4j.**

# ZXing renders the pairing QR. Only the core encoder is on the classpath, so its
# j2se/AWT-facing siblings are absent by design.
-keep class com.google.zxing.** { *; }
-dontwarn com.google.zxing.**

# kotlinx-serialization. The control protocol is hand-rolled on org.json today, so
# these rules are inert; they exist so that annotating a wire type @Serializable
# cannot quietly produce a release build that fails to deserialize.
-keepattributes RuntimeVisibleAnnotations,AnnotationDefault
-keepclassmembers class com.flick.** { *** Companion; }
-keepclasseswithmembers class com.flick.** { kotlinx.serialization.KSerializer serializer(...); }
-keep,includedescriptorclasses class com.flick.**$$serializer { *; }
-dontwarn kotlinx.serialization.**

# Annotation-only and JVM-only classpath references pulled in transitively by
# Guava (via Media3) and Ktor. None exist on Android, and R8 fails the build on
# missing classes unless they are named.
-dontwarn java.lang.ClassValue
-dontwarn java.lang.instrument.**
-dontwarn java.lang.management.**
-dontwarn javax.annotation.**
-dontwarn javax.lang.model.**
-dontwarn javax.naming.**
-dontwarn sun.misc.**
-dontwarn org.checkerframework.**
-dontwarn afu.org.checkerframework.**
-dontwarn com.google.errorprone.annotations.**
-dontwarn com.google.j2objc.annotations.**
-dontwarn org.jspecify.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
-dontwarn org.ietf.jgss.**
