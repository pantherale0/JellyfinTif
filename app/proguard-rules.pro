# Jellyfin SDK
-keep class org.jellyfin.sdk.** { *; }
-dontwarn org.jellyfin.sdk.**

# Media3
-keep class androidx.media3.** { *; }
-dontwarn androidx.media3.**

# OkHttp
-dontwarn okhttp3.**
-dontwarn okio.**

# Hilt workers
-keep class * extends androidx.work.Worker
-keep class * extends androidx.work.CoroutineWorker
