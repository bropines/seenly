# Wire-generated messages are reached through their ADAPTER fields.
-keep class lens.** { *; }
-keepclassmembers class * extends com.squareup.wire.Message { public static final com.squareup.wire.ProtoAdapter ADAPTER; }

# kotlinx.serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers @kotlinx.serialization.Serializable class ** { *** Companion; }

# OkHttp / Ktor optional platform bits
-dontwarn org.slf4j.**
-dontwarn okhttp3.internal.platform.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**

# ML Kit (google flavor) finds its components by name and builds them through their no-argument
# constructors (MlKitInitProvider -> ComponentDiscovery). R8 removed those constructors, so no ML Kit
# client could be made in a release build: on-device text recognition, translation and barcodes all failed.
-keep class * implements com.google.firebase.components.ComponentRegistrar { public <init>(); }
-keep class com.google.mlkit.**.*Registrar { public <init>(); }
-keep class com.google.mlkit.common.sdkinternal.** { *; }
-dontwarn com.google.mlkit.**

# zxing-cpp builds its results from native code through JNI: keep them whole.
