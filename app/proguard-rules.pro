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


-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile


-keep class com.arthenica.** { *; }
-keep class org.ffmpeg.** { *; }
-keep class com.arthenica.smartexception.** { *; }


-dontwarn com.yalantis.ucrop**
-keep class com.yalantis.ucrop** { *; }
-keep interface com.yalantis.ucrop** { *; }


-keep class com.nkd.screenrecorder.model.ImageItem { *; }
-keep class com.nkd.screenrecorder.model.ImageItem$* { *; }
-keep class com.nkd.screenrecorder.model.GifItem { *; }
-keep class com.nkd.screenrecorder.model.GifItem$* { *; }
-keep class com.nkd.screenrecorder.model.MediaItem { *; }
-keep class com.nkd.screenrecorder.model.MediaItem$* { *; }


-keepattributes Signature, *Annotation*