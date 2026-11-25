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

# JNI static registration: C++ functions are named Java_com_opencloudgaming_opennow_NativeCodecProbe_*,
# so R8 must not rename this class in release builds (would cause UnsatisfiedLinkError).
-keep class com.opencloudgaming.opennow.NativeCodecProbe {
    native <methods>;
}

# NewPipeExtractor + Mozilla Rhino JS engine (YouTube stream URL extraction)
-keep class org.schabi.newpipe.extractor.timeago.patterns.** { *; }
-keep class org.mozilla.javascript.** { *; }
-keep class org.mozilla.javascript.engine.** { *; }
-keep class org.mozilla.classfile.ClassFileWriter
-keepclassmembers class * extends com.google.protobuf.GeneratedMessageLite {
    <fields>;
}
-dontwarn org.mozilla.javascript.JavaToJSONConverters
-dontwarn org.mozilla.javascript.tools.**
-dontwarn javax.script.**
-dontwarn javax.annotation.**
-dontwarn jdk.dynalink.**
