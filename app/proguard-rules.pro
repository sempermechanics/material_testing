# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

# If your project uses WebView with JS, uncomment the following
# and specify the fully qualified class name to the JavaScript interface
# class:
#-keepclassmembers class fqcn.of.javascript.interface.for.webview {
#   public *;
#}

# Uncomment this to preserve the line number information for
# debugging stack traces.
#-keepattributes SourceFile,LineNumberTable

# If you keep the line number information, uncomment this to
# hide the original source file name.
#-renamesourcefileattribute SourceFile

# ============================================================
# JNI REFLECTION CONTRACT
# SemperJNI.cpp resolves this callback method BY NAME at
# runtime (GetMethodID("onProgressUpdate", "(I)V")). If R8
# renames it, the lookup returns null and engine progress
# callbacks silently stop working in release builds.
# ============================================================
-keepclassmembers class * implements com.indicvision.semper.ProgressCallback {
    public void onProgressUpdate(int);
}

# Native entry points: AGP's default rules keep classes with native
# methods, but be explicit — the C symbol names embed this class name.
-keep class com.indicvision.semper.SemperNativeLib { *; }

# ============================================================
# KOTLINX-SERIALIZATION MODELS (backend wire DTOs)
# The library ships consumer rules, but keep our own DTOs'
# serializers explicitly so a library update can't silently
# break the cloud sync payloads.
# ============================================================
-keepclassmembers @kotlinx.serialization.Serializable class com.indicvision.semper.** {
    *** Companion;
}
-keepclasseswithmembers class com.indicvision.semper.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# Keep readable crash reports from the field
-keepattributes SourceFile,LineNumberTable