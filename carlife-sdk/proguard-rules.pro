# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.

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

-keep class android.view.IRotationWatcher { *; }
-keep class android.view.IRotationWatcher$* { *; }

-keep class com.baidu.carlife.protobuf.* { *; }
-keep class com.baidu.carlife.protobuf.*$* { *; }

-keep class com.baidu.carlife.sdk.sender.aidl.* { *; }
-keep class com.baidu.carlife.sdk.sender.aidl.*$* { *; }
-keep class com.baidu.carlife.sdk.util.* { *; }
-keep class com.baidu.carlife.sdk.util.**.* { *; }

-keep class com.baidu.carlife.sdk.CarLifeContext$* { *; }

-keep class com.baidu.carlife.sdk.internal.protocol.CarLifeMessage$* { *; }

######### use DoNotStrip to avoid strip
-keep,allowobfuscation @interface com.baidu.carlife.sdk.util.annotations.DoNotStrip
# Do not strip any method/class that is annotated with @DoNotStrip
-keep @com.baidu.carlife.sdk.util.annotations.DoNotStrip class * { *; }
-keep @com.baidu.carlife.sdk.util.annotations.DoNotStrip interface * { *; }
-keep @com.baidu.carlife.sdk.util.annotations.DoNotStrip interface *$* { *; }

-keepclassmembers class * {
@com.baidu.carlife.sdk.util.annotations.DoNotStrip *;
###########

}

