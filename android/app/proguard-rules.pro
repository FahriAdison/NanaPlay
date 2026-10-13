-keep class org.webrtc.** { *; }
-keep class org.jni_zero.** { *; }
-keep class kotlinx.serialization.** { *; }
-keepclassmembers class com.opencloudgaming.opennow.** {
    @kotlinx.serialization.Serializable *;
}
# Strip all android.util.Log calls from release builds: diagnostics already go
# through the redaction pipeline, and raw logcat must not carry PII (see
# Diagnostics.kt redaction). Only applies when minify is enabled (release).
-assumenosideeffects class android.util.Log {
    *;
}
