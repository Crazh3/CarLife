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

# AOA/USB 相关类会被车机端 App 直接调用（如 UsbAccessoryStarter.resetForceReInitState），
# SDK 内部无引用时会被 R8 当作无用类剥离，导致 :vehicle-app:minifyReleaseWithR8 报
# "Missing class com.baidu.carlife.sdk.receiver.transport.aoa.UsbAccessoryStarter"，这里保留
-keep class com.baidu.carlife.sdk.receiver.transport.aoa.* { *; }
-keep class com.baidu.carlife.sdk.receiver.transport.aoa.*$* { *; }
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

