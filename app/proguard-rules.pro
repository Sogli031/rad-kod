# ProGuard rules for RAD Shift Tracker

# Keep the application class
-keep class com.rad.shifttracker.** { *; }

# Keep data classes used for JSON serialization
-keepclassmembers class com.rad.shifttracker.DayRecord { *; }
-keepclassmembers class com.rad.shifttracker.MonthSummary { *; }
-keepclassmembers class com.rad.shifttracker.BatchRecord { *; }
-keepclassmembers class com.rad.shifttracker.Shift { *; }
-keepclassmembers class com.rad.shifttracker.BrigadeType { *; }
-keepclassmembers class com.rad.shifttracker.OvertimeType { *; }
-keepclassmembers class com.rad.shifttracker.PayConfig { *; }
-keepclassmembers class com.rad.shifttracker.AppConfig { *; }

# Keep enum classes
-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}

# Keep Kotlin metadata
-keep class kotlin.Metadata { *; }

# Compose rules
-dontwarn androidx.compose.**
-keep class androidx.compose.** { *; }

# Coroutines
-dontwarn kotlinx.coroutines.**
-keep class kotlinx.coroutines.** { *; }

# JSON parsing with org.json
-keep class org.json.** { *; }

# Google Play Services Auth
-keep class com.google.android.gms.** { *; }
-dontwarn com.google.android.gms.**

# Google API Client
-keep class com.google.api.** { *; }
-keep class com.google.api.client.** { *; }
-keep class com.google.api.services.** { *; }
-dontwarn com.google.api.**

# Google HTTP Client
-keep class com.google.http.** { *; }
-dontwarn com.google.http.**

# Gson (used by Google API)
-keep class com.google.gson.** { *; }
-dontwarn com.google.gson.**

# Keep Google credential classes
-keep class com.google.api.client.googleapis.extensions.android.gms.auth.** { *; }

# Apache HTTP Client (transitive dependency from Google API)
-dontwarn org.apache.http.**
-dontwarn javax.naming.**
-dontwarn org.ietf.jgss.**
