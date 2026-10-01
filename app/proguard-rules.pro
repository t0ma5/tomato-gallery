-keep class com.simplemobiletools.** { *; }
-keep class tomato.simple.gallery.** { *; }
-dontwarn android.graphics.Canvas
-dontwarn com.simplemobiletools.**
-dontwarn tomato.simple.gallery.**
-dontwarn org.apache.**
-keep class com.awxkee.** { *; }
-dontwarn com.awxkee.**
-keep class com.github.penfeizhou.** { *; }
-keep class com.equationl.ncnnandroidppocr.** { *; }

# Picasso
-dontwarn javax.annotation.**
-keepnames class okhttp3.internal.publicsuffix.PublicSuffixDatabase
-dontwarn org.codehaus.mojo.animal_sniffer.*
-dontwarn okhttp3.internal.platform.ConscryptPlatform

-keepclassmembers class * implements android.os.Parcelable {
    static ** CREATOR;
}

# RenderScript
-keepclasseswithmembernames class * {
native <methods>;
}
-keep class androidx.renderscript.** { *; }

# Reprint
-keep class com.github.ajalt.reprint.module.** { *; }
