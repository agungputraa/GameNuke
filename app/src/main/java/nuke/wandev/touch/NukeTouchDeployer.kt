package nuke.wandev.touch

import android.content.Context
import android.os.Build
import android.util.Base64
import android.util.Log
import com.neon.gametweak.IShellService
import com.neon.gametweak.NukeConnectionManager
import java.io.File
import java.util.zip.ZipFile

/**
 * Native Touch Driver Deployer.
 * Attempts to deploy libwandev.so to /data/local/tmp with executable permissions using the
 * best available privileged backend. OEM security policies may restrict deployment.
 */
object NukeTouchDeployer {
    private const val TAG = "NukeTouchDeployer"
    const val TARGET_PATH = "/data/local/tmp/libwandev.so"
    private const val MIN_EXPECTED_SIZE = 20_000L // libwandev.so is ~30KB

    /**
     * Checks if libwandev.so already exists in /data/local/tmp with a valid binary size.
     * Checks both local filesystem (if accessible) and privileged shell check.
     */
    fun isDeployed(): Boolean {
        // Fast path: direct filesystem check (works if app has read access)
        val f = File(TARGET_PATH)
        if (runCatching { f.exists() && f.length() >= MIN_EXPECTED_SIZE }.getOrDefault(false)) {
            return true
        }
        // Fallback: privileged shell check (handles SELinux blocking untrusted_app from /data/local/tmp)
        val res = runCatching {
            NukeConnectionManager.executeCommand("[ -s $TARGET_PATH ] && echo DEPLOYED_OK", timeoutMs = 1500L)
        }.getOrNull()
        return res?.output?.contains("DEPLOYED_OK") == true
    }

    /**
     * Reads the binary bytes of libwandev.so from the most reliable available source:
     * 1. APK Assets (100% reliable, zero SELinux/mount constraints)
     * 2. Direct nativeLibraryDir file
     * 3. ZipFile extraction from the APK sourceDir directly in pure Java
     */
    fun getLibraryBytes(context: Context): ByteArray? {
        // 1. Assets stream (fastest & most reliable)
        runCatching {
            context.assets.open("libwandev.so").use { input ->
                val bytes = input.readBytes()
                if (bytes.size >= MIN_EXPECTED_SIZE) {
                    Log.i(TAG, "Read libwandev.so from assets (${bytes.size} bytes)")
                    return bytes
                }
            }
        }.onFailure { Log.d(TAG, "Assets read skipped: ${it.message}") }

        // 2. Direct nativeLibraryDir file
        runCatching {
            val nativeFile = File(context.applicationInfo.nativeLibraryDir, "libwandev.so")
            if (nativeFile.exists() && nativeFile.length() >= MIN_EXPECTED_SIZE) {
                val bytes = nativeFile.readBytes()
                Log.i(TAG, "Read libwandev.so from nativeLibraryDir (${bytes.size} bytes)")
                return bytes
            }
        }.onFailure { Log.d(TAG, "nativeLibraryDir read skipped: ${it.message}") }

        // 3. Pure Java ZipFile extraction from the base APK
        runCatching {
            val apkFile = File(context.applicationInfo.sourceDir)
            if (apkFile.exists()) {
                ZipFile(apkFile).use { zip ->
                    val entry = Build.SUPPORTED_ABIS.asSequence()
                        .mapNotNull { abi -> zip.getEntry("lib/$abi/libwandev.so") }
                        .firstOrNull()
                        ?: zip.getEntry("assets/libwandev.so")
                        ?: zip.entries().asSequence().firstOrNull { it.name.endsWith("/libwandev.so") }
                    if (entry != null) {
                        zip.getInputStream(entry).use { input ->
                            val bytes = input.readBytes()
                            if (bytes.size >= MIN_EXPECTED_SIZE) {
                                Log.i(TAG, "Read libwandev.so from APK ZipFile (${bytes.size} bytes)")
                                return bytes
                            }
                        }
                    }
                }
            }
        }.onFailure { Log.w(TAG, "APK ZipFile extraction failed: ${it.message}") }

        Log.e(TAG, "CRITICAL: Could not find libwandev.so from any source!")
        return null
    }

    /**
     * Deploys the library via Shizuku / iAdb privileged Binder service (UID 2000).
     */
    fun deployViaBinder(shellService: IShellService, context: Context): Boolean {
        val bytes = getLibraryBytes(context) ?: return false
        return runCatching {
            val ok = shellService.deployTouchLibrary(bytes)
            Log.i(TAG, "deployViaBinder returned: $ok")
            ok
        }.onFailure { Log.w(TAG, "deployViaBinder error: ${it.message}") }.getOrDefault(false)
    }

    /**
     * Deploys the library using shell commands across all available backends (Native ADB / Shizuku / iAdb).
     * Fast path 1: direct copy from nativeLibraryDir.
     * Fast path 2: direct Toybox unzip extraction from base APK.
     * Fallback 3: safe chunked Base64 Toybox pipe to avoid oversized command arguments.
     * Availability still depends on the active privileged backend and OEM security policy.
     */
    fun deployViaCommand(context: Context): Boolean {
        // Fast path 1: Direct copy from application nativeLibraryDir if accessible to shell
        val nativeFile = File(context.applicationInfo.nativeLibraryDir, "libwandev.so")
        if (nativeFile.exists() && nativeFile.length() >= MIN_EXPECTED_SIZE) {
            val cpCmd = "mkdir -p /data/local/tmp 2>/dev/null; (cp '${nativeFile.absolutePath}' $TARGET_PATH 2>/dev/null || cat '${nativeFile.absolutePath}' > $TARGET_PATH 2>/dev/null) && chmod 755 $TARGET_PATH && [ -s $TARGET_PATH ] && echo DEPLOYED_OK"
            val res = NukeConnectionManager.executeCommand(cpCmd, timeoutMs = 2500L)
            if (res?.output?.contains("DEPLOYED_OK") == true || isDeployed()) {
                Log.i(TAG, "deployViaCommand succeeded via direct nativeLibraryDir copy")
                return true
            }
        }

        // Fast path 2: Direct Toybox unzip extraction from installed APK path (pm path)
        val pkg = context.packageName
        val unzipCmd = "APK=\$(pm path $pkg 2>/dev/null | head -n1 | cut -d: -f2 | tr -d '\\r'); ABI=\$(getprop ro.product.cpu.abi 2>/dev/null | tr -d '\\r'); if [ -n \"\$APK\" ]; then mkdir -p /data/local/tmp 2>/dev/null; (unzip -p \"\$APK\" assets/libwandev.so > $TARGET_PATH.tmp 2>/dev/null || unzip -p \"\$APK\" \"lib/\$ABI/libwandev.so\" > $TARGET_PATH.tmp 2>/dev/null || unzip -p \"\$APK\" lib/arm64-v8a/libwandev.so > $TARGET_PATH.tmp 2>/dev/null || unzip -p \"\$APK\" lib/armeabi-v7a/libwandev.so > $TARGET_PATH.tmp 2>/dev/null); if [ -s $TARGET_PATH.tmp ]; then mv $TARGET_PATH.tmp $TARGET_PATH 2>/dev/null || cp $TARGET_PATH.tmp $TARGET_PATH 2>/dev/null; rm -f $TARGET_PATH.tmp 2>/dev/null; chmod 755 $TARGET_PATH; [ -s $TARGET_PATH ] && echo DEPLOYED_OK; fi; fi"
        val unzipRes = NukeConnectionManager.executeCommand(unzipCmd, timeoutMs = 3500L)
        if (unzipRes?.output?.contains("DEPLOYED_OK") == true || isDeployed()) {
            Log.i(TAG, "deployViaCommand succeeded via APK unzip extraction")
            return true
        }

        // Universal fallback 3: Pure Java assets/APK bytes piped through Base64 in safe 4KB chunks
        val bytes = getLibraryBytes(context) ?: return false
        val b64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
        NukeConnectionManager.executeCommand("rm -f $TARGET_PATH.b64 2>/dev/null", timeoutMs = 1500L)
        val chunkSize = 4096
        var chunkIdx = 0
        var allChunksOk = true
        while (chunkIdx < b64.length) {
            val end = minOf(chunkIdx + chunkSize, b64.length)
            val chunk = b64.substring(chunkIdx, end)
            val appendCmd = "mkdir -p /data/local/tmp 2>/dev/null; printf '%s' '$chunk' >> $TARGET_PATH.b64"
            val res = NukeConnectionManager.executeCommand(appendCmd, timeoutMs = 2500L)
            if (res == null || res.exitCode != 0) {
                allChunksOk = false
                break
            }
            chunkIdx = end
        }
        if (allChunksOk) {
            val decodeCmd = "base64 -d $TARGET_PATH.b64 > $TARGET_PATH && chmod 755 $TARGET_PATH && rm -f $TARGET_PATH.b64 && [ -s $TARGET_PATH ] && echo DEPLOYED_OK"
            val res = NukeConnectionManager.executeCommand(decodeCmd, timeoutMs = 3500L)
            if (res?.output?.contains("DEPLOYED_OK") == true || isDeployed()) {
                Log.i(TAG, "deployViaCommand succeeded via chunked Base64")
                return true
            }
        }

        return isDeployed()
    }

    /**
     * High-level orchestrator: attempts deployment through each supported backend in priority order.
     */
    fun ensureDeployed(context: Context): Boolean {
        if (isDeployed()) {
            return true
        }

        // Try 1: Via privileged Binder if available
        val shellService = NukeConnectionManager.ensureShellService(1500L)
        if (shellService != null && runCatching { shellService.ping() }.getOrDefault(false)) {
            if (deployViaBinder(shellService, context)) {
                return true
            }
        }

        // Try 2: Via shell command (direct copy / APK unzip / chunked base64)
        if (deployViaCommand(context)) {
            return true
        }

        return isDeployed()
    }
}
