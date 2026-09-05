# Add project specific ProGuard rules here.
-keep class com.snaptube.downloader.data.model.** { *; }
-keepclassmembers class * {
    @androidx.annotation.Keep <fields>;
    @androidx.annotation.Keep <methods>;
}
