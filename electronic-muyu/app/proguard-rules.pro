# Electronic Muyu ProGuard Rules
# Keep all our application classes
-keep class app.electronicmuyu.** { *; }

# Keep data classes used for JSON serialization
-keep class app.electronicmuyu.android.model.** { *; }

# OkHttp
-dontwarn okhttp3.**
-dontwarn okio.**

# Kotlin Coroutines
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}