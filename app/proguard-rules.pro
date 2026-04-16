# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.kts.
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

# OkHttp can probe optional TLS providers at runtime. We don't ship these
# provider implementations, so R8 should not treat them as required classes.
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.jsse.**
-dontwarn org.bouncycastle.jsse.provider.**
-dontwarn org.openjsse.**

# Fastjson reflects over model fields stored in SharedPreferences / local files.
# If R8 renames these fields, release builds can deserialize empty/default models
# and wallpaper services may render a black frame.
-keep class com.zeaze.tianyinwallpaper.model.** { *; }
-keep class com.zeaze.tianyinwallpaper.ui.commom.SaveData { *; }
-keep class com.zeaze.tianyinwallpaper.update.UpdateInfo { *; }

# Wallpaper services are referenced by the system and app-side component lookups.
-keep class com.zeaze.tianyinwallpaper.service.TianYinWallpaperService { *; }
-keep class com.zeaze.tianyinwallpaper.service.VideoRasterWallpaperService { *; }
-keep class com.zeaze.tianyinwallpaper.service.StaticRasterWallpaperService { *; }
