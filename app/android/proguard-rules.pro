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
