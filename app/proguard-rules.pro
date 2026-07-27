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

# Media3 (ExoPlayer) Dynamic Factory & Extension keep rules
-keep class androidx.media3.exoplayer.hls.** { *; }
-keep class androidx.media3.exoplayer.dash.** { *; }
-keep class androidx.media3.exoplayer.rtsp.** { *; }
-keep class androidx.media3.exoplayer.source.ProgressiveMediaSource$Factory { *; }
-keep class androidx.media3.exoplayer.source.DefaultMediaSourceFactory { *; }

# JUPnP DLNA keep rules
-keep class org.jupnp.** { *; }
-keep interface org.jupnp.** { *; }
-keep class org.eclipse.jetty.** { *; }

# LibVLC keep rules
-keep class org.videolan.libvlc.** { *; }

# NanoHTTPD keep rules
-keep class fi.iki.elonen.** { *; }

# SMB (jCIFS-NG) & WebDAV keep rules
-keep class jcifs.** { *; }
-keep class com.github.sardine.** { *; }

# SSHJ & BouncyCastle keep rules
-keep class com.hierynomus.** { *; }
-keep class net.schmizz.** { *; }
-keep class org.bouncycastle.** { *; }

# MSAL (OneDrive) keep rules
-keep class com.microsoft.identity.** { *; }

# OkHttp & Coroutines keep rules
-dontwarn okhttp3.**
-dontwarn org.xmlpull.v1.**
-dontwarn com.sun.net.httpserver.**
-dontwarn edu.umd.cs.findbugs.**
-dontwarn java.awt.**
-dontwarn java.beans.**
-dontwarn java.lang.management.**
-dontwarn javax.imageio.**
-dontwarn javax.naming.**
-dontwarn javax.security.auth.**
-dontwarn org.eclipse.jetty.jmx.**
-dontwarn org.ietf.jgss.**
-dontwarn org.osgi.**
-dontwarn sun.security.x509.**
-dontwarn com.google.auto.value.AutoValue
-dontwarn com.microsoft.device.display.**

-keep class okhttp3.** { *; }
-keepattributes *Annotation*, Signature, InnerClasses, EnclosingMethod