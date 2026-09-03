# --- Google Play & AdMob ---
-keep class com.google.android.gms.ads.** { *; }
-keep class com.google.android.gms.common.** { *; }
-keep class com.google.android.play.core.** { *; }
-keep interface com.google.android.play.core.** { *; }
-dontwarn com.google.android.play.core.**

# --- App Integrity & Anti-Tamper ---
-keep class com.neon.gametweak.IntegrityGuard { *; }

# --- Keep Annotations ---
-keepclasseswithmembers class * {
    @androidx.annotation.Keep <methods>;
}
-keepclasseswithmembers class * {
    @androidx.annotation.Keep <fields>;
}
-keep @androidx.annotation.Keep class * { *; }

# --- Strip debug logs in release ---
-assumenosideeffects class android.util.Log {
    public static *** d(...);
    public static *** v(...);
}

# --- Obfuscation & Anti-Decompilation ---
-repackageclasses 'com.neon.gametweak.obf'
-allowaccessmodification
-renamesourcefileattribute SourceFile
-keepattributes Exceptions, InnerClasses, Signature, *Annotation*, EnclosingMethod
-dontwarn java.lang.invoke.**

# --- Kotlin & Coroutines ---
-keep class kotlin.Metadata { *; }
-keep class kotlinx.coroutines.** { *; }
-dontwarn kotlinx.coroutines.**

# --- Jetpack Compose ---
-keep class androidx.compose.runtime.** { *; }
-dontwarn androidx.compose.**

# --- Shizuku API (v13.1.5) ---
-keep class dev.rikka.shizuku.** { *; }
-keep interface dev.rikka.shizuku.** { *; }
-dontwarn dev.rikka.shizuku.**
-keep class rikka.shizuku.** { *; }
-keep interface rikka.shizuku.** { *; }
-dontwarn rikka.shizuku.**

# --- iAdb API ---
-keep class com.iadb.** { *; }
-keep interface com.iadb.** { *; }
-dontwarn com.iadb.**

# --- AIDL Interfaces & UserService IPC ---
-keep class com.neon.gametweak.IShellService* { *; }
-keep interface com.neon.gametweak.IShellService* { *; }
-keep class com.neon.gametweak.ShellUserService { *; }
-keep class com.neon.gametweak.ShellResult { *; }
-keepclassmembers class com.neon.gametweak.ShellResult {
    public static final android.os.Parcelable$Creator *;
}

# --- Local Web Server ---
-keep class com.neon.gametweak.LocalWebServer { *; }

# --- ADB engine (pure Java) + crypto ---
-keep class io.github.muntashirakon.adb.** { *; }
-keep class io.github.muntashirakon.crypto.** { *; }
-dontwarn io.github.muntashirakon.**
-keep class org.bouncycastle.** { *; }
-dontwarn org.bouncycastle.**
-keep class org.conscrypt.** { *; }
-dontwarn org.conscrypt.**
-dontwarn javax.naming.**

# --- App Process Entrypoint Daemon ---
-keep class com.neon.gametweak.NukeShellDaemon {
    public static void main(java.lang.String[]);
}

# --- OkHttp & TLS Platforms ---
-dontwarn org.openjsse.**
-dontwarn okhttp3.internal.platform.**
-dontwarn okhttp3.**
-dontwarn okio.**

# --- Vungle Ads SDK ---
-keep class com.vungle.** { *; }
-dontwarn com.vungle.**

