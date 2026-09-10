# =============================================================================
# Game Nuke Premium - ProGuard / R8 Configuration
# =============================================================================
# CRITICAL: The old `-keep class kotlinx.coroutines.** { *; }` rule caused a
# StackOverflowError crash. Vungle SDK classes and retained coroutine internals
# created a recursive call cycle under R8. Now using MINIMAL targeted keeps.
# =============================================================================

# --- Google Play Core ---
-keep class com.google.android.gms.** { *; }
-keep class com.google.android.play.core.** { *; }
-keep interface com.google.android.play.core.** { *; }
-dontwarn com.google.android.play.core.**
-dontwarn com.google.android.gms.**

# --- Keep Annotations & WebView JavaScript Bridge ---
-keepattributes JavascriptInterface
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}
-keepclasseswithmembers class * {
    @androidx.annotation.Keep <methods>;
}
-keepclasseswithmembers class * {
    @androidx.annotation.Keep <fields>;
}
-keep @androidx.annotation.Keep class * { *; }

# --- Liftoff Monetize / Vungle SDK ---
-dontwarn com.vungle.**
-keep class com.vungle.** { *; }
-keepclassmembers class com.vungle.** { *; }

# --- Strip verbose logs in release (keeps ERROR + WARN for crash diagnostics) ---
-assumenosideeffects class android.util.Log {
    public static *** d(...);
    public static *** v(...);
    public static *** i(...);
}

# --- Obfuscation config ---
# Only third-party libs get repackaged. App classes keep their names so that
# the AndroidManifest component references always resolve correctly.
-keepattributes Exceptions, InnerClasses, Signature, *Annotation*, EnclosingMethod, SourceFile, LineNumberTable
-dontwarn java.lang.invoke.**
-dontwarn sun.misc.Unsafe

# ── App classes: keep names so AndroidManifest + reflection always work ──────
-keep class com.neon.gametweak.NukeApplication { *; }
-keep class com.neon.gametweak.SplashActivity { *; }
-keep class com.neon.gametweak.MainActivity { *; }
-keep class com.neon.gametweak.FloatingBoosterService { *; }
-keep class com.neon.gametweak.NukeWebServerService { *; }
-keep class com.neon.gametweak.NukeMacroService { *; }
-keep class com.neon.gametweak.NukeVpnService { *; }
-keep class com.neon.gametweak.NukeVpnTrampolineActivity { *; }
-keep class com.neon.gametweak.GameLaunchSplashActivity { *; }
-keep class com.neon.gametweak.CallShieldRoleActivity { *; }
-keep class com.neon.gametweak.NukeCallScreeningService { *; }
-keep class com.neon.gametweak.PairingReceiver { *; }

# ── Singletons & state objects (accessed from coroutines/lambdas by reference) ─
-keep class com.neon.gametweak.NukeRemoteConfigRepository { *; }
-keep class com.neon.gametweak.NukeAdManager { *; }
-keep class com.neon.gametweak.ConsentManager { *; }
-keep class com.neon.gametweak.NukeRuntimeState { *; }
-keep class com.neon.gametweak.NukeConnectionManager { *; }
-keep class com.neon.gametweak.NukeMacroController { *; }
-keep class com.neon.gametweak.NukeAppUpdater { *; }
-keep class com.neon.gametweak.NukeToast { *; }
-keep class com.neon.gametweak.Tx { *; }
-keep class com.neon.gametweak.SafePreferences { *; }
-keep class com.neon.gametweak.NukeAdBlockDetector { *; }
-keep class com.neon.gametweak.AppUpdateController { *; }
-keep class com.neon.gametweak.IntegrityGuard { *; }
-keep class com.neon.gametweak.NukeAdbOrchestrator { *; }
-keep class com.neon.gametweak.NukeDisplayProfileController { *; }
-keep class com.neon.gametweak.AdbManager { *; }
-keep class com.neon.gametweak.LocalWebServer { *; }
-keep class com.neon.gametweak.NukeLiveChatNotifier { *; }
-keep class com.neon.gametweak.NukeLiveChatReceiver { *; }
-keep class com.neon.gametweak.NukeLiveChatScheduler { *; }
-keep class com.neon.gametweak.NukeLiveChatRepository { *; }
-keep class com.neon.gametweak.NukeLiveChatOverlay { *; }
-keep class com.neon.gametweak.NukeChatMessage { *; }

# ── AIDL / Parcelable (IPC) ───────────────────────────────────────────────────
-keep class com.neon.gametweak.IShellService* { *; }
-keep interface com.neon.gametweak.IShellService* { *; }
-keep class com.neon.gametweak.ShellUserService { *; }
-keep class com.neon.gametweak.ShellResult { *; }
-keepclassmembers class com.neon.gametweak.ShellResult {
    public static final android.os.Parcelable$Creator *;
}

# ── Data classes in StateFlows ────────────────────────────────────────────────
# Kotlin data classes must keep their component() functions & copy() for StateFlow.
-keepclassmembers class com.neon.gametweak.** {
    ** component*();
    ** copy(...);
}

# ── App Process Entrypoint Daemon ─────────────────────────────────────────────
-keep class com.neon.gametweak.NukeShellDaemon {
    public static void main(java.lang.String[]);
}

# --- Kotlin ---
-keep class kotlin.Metadata { *; }
-keep class kotlin.reflect.** { *; }
-dontwarn kotlin.**

# --- Kotlin Coroutines ---
# SIGSEGV FIX: kotlinx.coroutines uses sun.misc.Unsafe to access fields by offset.
# If R8 renames or removes volatile fields, Unsafe.objectFieldOffset() returns wrong
# offsets → SEGV_ACCERR crash in DefaultDispatcher thread at startup.
# We MUST keep: class names (for Unsafe lookup) + volatile fields (for correct offsets).
# We do NOT keep all members (to avoid Vungle StackOverflow conflict from old rules).
-keepnames class kotlinx.coroutines.**
-keepclassmembers class kotlinx.coroutines.** {
    volatile <fields>;
    <init>(...);
}
-keepclassmembernames class kotlinx.** {
    volatile <fields>;
}
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}
-keepclassmembers class kotlinx.coroutines.internal.MainDispatcherLoader {
    public static <fields>;
}
-dontwarn kotlinx.coroutines.**

# --- Jetpack Compose ---
-keep class androidx.compose.runtime.** { *; }
-keep class androidx.compose.ui.** { *; }
-keep class androidx.compose.foundation.** { *; }
-dontwarn androidx.compose.**

# --- AndroidX Lifecycle ---
-keep class androidx.lifecycle.** { *; }
-dontwarn androidx.lifecycle.**

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

# --- ADB engine (pure Java) + crypto ---
-keep class io.github.muntashirakon.adb.** { *; }
-keep class io.github.muntashirakon.crypto.** { *; }
-dontwarn io.github.muntashirakon.**
-keep class org.bouncycastle.** { *; }
-dontwarn org.bouncycastle.**
-keep class org.conscrypt.** { *; }
-dontwarn org.conscrypt.**
-dontwarn javax.naming.**

# --- OkHttp & TLS ---
-dontwarn org.openjsse.**
-dontwarn okhttp3.internal.platform.**
-dontwarn okhttp3.**
-dontwarn okio.**

# --- Vungle Ads SDK: full keep to prevent internal StackOverflow ---
-keep class com.vungle.** { *; }
-keepclassmembers class com.vungle.** { *; }
-keep interface com.vungle.** { *; }
-dontwarn com.vungle.**

# --- Native Touch Subsystem (Must match libtouch.so JNI signatures exactly) ---
-keep class frb.axeron.server.touch.** { *; }
-keepclassmembers class frb.axeron.server.touch.** { *; }
-dontwarn frb.axeron.server.touch.**

