# Video Download by HITIF - ProGuard Rules

# Keep application class
-keep class com.hitif.videodownload.HITIFApplication { *; }
-keep class com.hitif.videodownload.MainActivity { *; }

# Keep Room entities
-keep class com.hitif.videodownload.download.** { *; }
-keep class com.hitif.videodownload.history.HistoryEntry { *; }
-keep class com.hitif.videodownload.history.HistoryDao { *; }
-keep class * extends androidx.room.RoomDatabase

# Keep NewPipe Extractor (v0.26.2)
-dontwarn org.schabi.newpipe.**
-keep class org.schabi.newpipe.** { *; }

# Keep Gson serialized classes
-keep class com.hitif.videodownload.youtube.YouTubeVideoInfo { *; }
-keep class com.hitif.videodownload.youtube.VideoStreamInfo { *; }
-keep class com.hitif.videodownload.youtube.AudioStreamInfo { *; }
-keep class com.hitif.videodownload.youtube.PlaylistInfo { *; }
-keep class com.hitif.videodownload.youtube.YouTubeExtractionException { *; }
-keep class com.hitif.videodownload.web.WebVideoExtractor$SiteConfig { *; }
-keep class com.hitif.videodownload.web.WebVideoExtractor$ExtractedVideo { *; }
-keepattributes Signature
-keepattributes *Annotation*
-keep class sun.misc.Unsafe { *; }

# Keep OkHttp
-dontwarn okhttp3.**
-dontwarn okio.**
-keep class okhttp3.** { *; }
-keep interface okhttp3.** { *; }

# Keep JSoup
-keep class org.jsoup.** { *; }

# Fix missing java.beans (Rhino/Jsoup transitive dependency)
-dontwarn java.beans.**
-dontwarn org.mozilla.javascript.**
-dontwarn org.jspecify.annotations.**
-dontwarn javax.annotation.**
-dontwarn org.checkerframework.**

# Keep Media3/ExoPlayer
-keep class androidx.media3.** { *; }

# Keep Kotlin coroutines
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}

# Keep Lottie
-keep class com.airbnb.lottie.** { *; }

# Keep Coil
-dontwarn coil.**
-keep class coil.** { *; }

# Remove logging in release (keep error and warning for crash diagnosis)
-assumenosideeffects class android.util.Log {
    public static *** d(...);
    public static *** v(...);
    public static *** i(...);
}

# Keep serialized data classes
-keepclassmembers class * {
    <fields>;
}

# Keep enum values
-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}
