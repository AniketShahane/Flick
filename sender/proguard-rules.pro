# R8 rules for the phone app. The release build runs R8 in full mode, so anything
# reached by name rather than by a static reference has to be named here.
#
# Bias: keep too much rather than too little. A release APK a few hundred
# kilobytes larger is not a defect; one that crashes when the user opens the
# scanner or starts the server is.

# Readable failure diagnostics. FlickLog records `e.javaClass.simpleName` and the
# in-app Diagnostics sheet shows those lines to the user, so obfuscated exception
# names would silently gut a shipped feature.
-keepnames class * extends java.lang.Throwable
-keepattributes SourceFile,LineNumberTable,Signature,Exceptions,InnerClasses,EnclosingMethod
-renamesourcefileattribute SourceFile

# Ktor 3 (CIO server, CIO client, client websockets). Ktor resolves engines,
# plugin attribute keys and coroutine internals through type tokens and service
# lookups that R8 cannot follow.
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

# ML Kit barcode scanning, bundled-model variant. The scanner and the generated
# gms internals are instantiated reflectively by the ML Kit runtime.
-keep class com.google.mlkit.** { *; }
-keep class com.google.android.gms.internal.mlkit_** { *; }
-keep class com.google.android.odml.** { *; }
-dontwarn com.google.mlkit.**
-dontwarn com.google.android.gms.**

# kotlinx-serialization. The control protocol is hand-rolled on org.json today, so
# these rules are inert; they exist so that annotating a wire type @Serializable
# cannot quietly produce a release build that fails to deserialize.
-keepattributes RuntimeVisibleAnnotations,AnnotationDefault
-keepclassmembers class com.flick.** { *** Companion; }
-keepclasseswithmembers class com.flick.** { kotlinx.serialization.KSerializer serializer(...); }
-keep,includedescriptorclasses class com.flick.**$$serializer { *; }
-dontwarn kotlinx.serialization.**

# Annotation-only and JVM-only classpath references pulled in transitively by
# Guava, CameraX, Coil/OkHttp and ML Kit. None exist on Android, and R8 fails the
# build on missing classes unless they are named.
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
