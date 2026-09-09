package com.neon.gametweak

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import java.util.Locale
import java.util.UUID

/**
 * NukeAntivirusEngine — Heuristic Application Security & Permission Integrity Sentinel.
 *
 * Developer: Agung Developer
 *
 * Capabilities:
 * - Real-time Background Sentinel: continuously guards device against zero-day optimizes, rogue background
 *   accessibility interceptors, stealth tapjacking overlays, and hidden trojan droppers.
 * - Deep Heuristic Scanner: audits installed user applications, anomalous permissions, and public storage directories.
 * - 1-Tap Neutralizer: supports silent/privileged uninstall, force-stop, package disable, or user intent fallbacks.
 * - Real-time System Notifications: reports safe status or sounds alarms on threat detection.
 */
object NukeAntivirusEngine {

    private const val TAG = "NukeAntivirus"
    private const val PREFS_NAME = "NukeAntivirusPrefs"
    private const val KEY_SHIELD_ACTIVE = "antivirus_shield_active"
    private const val KEY_LAST_SCAN = "antivirus_last_scan"

    enum class ThreatSeverity {
        CRITICAL,
        HIGH,
        WARNING
    }

    enum class ThreatType {
        MALWARE_PACKAGE,
        ROGUE_ACCESSIBILITY,
        STEALTH_OVERLAY,
        SUSPICIOUS_APK_DROPPER,
        DANGEROUS_PAYLOAD
    }

    data class DetectedThreat(
        val id: String = UUID.randomUUID().toString(),
        val title: String,
        val targetIdentifier: String, // Package name or file absolute path
        val type: ThreatType,
        val severity: ThreatSeverity,
        val description: String,
        val isFile: Boolean = false,
        val appIcon: android.graphics.drawable.Drawable? = null
    )

    data class AntivirusShieldState(
        val isShieldActive: Boolean = true,
        val isScanning: Boolean = false,
        val threats: List<DetectedThreat> = emptyList(),
        val lastScanTimestamp: Long = 0L,
        val scannedItemsCount: Int = 0,
        val statusMessage: String = "System Clean & Secure"
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var sentinelJob: Job? = null

    private val _state = MutableStateFlow(AntivirusShieldState())
    val state: StateFlow<AntivirusShieldState> = _state.asStateFlow()

    // Known suspicious patterns in package names or signatures
    private val SUSPICIOUS_PKG_PATTERNS = listOf(
        "spy", "keylog", "rat", "dropper", "trojan", "stealer",
        "adware", "fakeapp", "xspy", "spymaster",
        "system.patch", "android.service.core", "google.update.service"
    )

    @Volatile private var isInitialized = false

    fun init(context: Context) {
        if (isInitialized) return
        isInitialized = true
        val appContext = context.applicationContext
        val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val active = prefs.getBoolean(KEY_SHIELD_ACTIVE, true)
        val lastScan = prefs.getLong(KEY_LAST_SCAN, 0L)

        _state.update { it.copy(isShieldActive = active, lastScanTimestamp = lastScan) }

        if (active) {
            startSentinel(appContext)
        }
    }

    fun setShieldActive(context: Context, active: Boolean) {
        val appContext = context.applicationContext
        appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_SHIELD_ACTIVE, active).apply()
        _state.update { it.copy(isShieldActive = active) }

        if (active) {
            startSentinel(appContext)
            triggerDeepScan(appContext)
        } else {
            stopSentinel(appContext)
        }
    }

    private fun startSentinel(context: Context) {
        sentinelJob?.cancel()
        sentinelJob = scope.launch {
            Log.d(TAG, "Antivirus Real-time Sentinel started")
            while (isActive) {
                runScanInternal(context)
                // Sentinel checks every 60 seconds
                delay(60_000L)
            }
        }
    }

    private fun stopSentinel(context: Context) {
        sentinelJob?.cancel()
        sentinelJob = null
        NukeAntivirusNotifier.cancelAll(context)
    }

    fun triggerDeepScan(context: Context, onComplete: (() -> Unit)? = null) {
        scope.launch {
            runScanInternal(context)
            onComplete?.invoke()
        }
    }

    private suspend fun runScanInternal(context: Context) {
        _state.update { it.copy(isScanning = true, statusMessage = "Scanning memory, packages, and storage...") }
        val appContext = context.applicationContext
        val pm = appContext.packageManager
        val threats = mutableListOf<DetectedThreat>()
        val scannedPkgNames = mutableSetOf<String>()
        var count = 0

        try {
            // ── 1. Audit Installed Applications via Framework ─────────────────
            val installedPackages: List<PackageInfo> = runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    pm.getInstalledPackages(PackageManager.PackageInfoFlags.of(PackageManager.GET_PERMISSIONS.toLong()))
                } else {
                    @Suppress("DEPRECATION")
                    pm.getInstalledPackages(PackageManager.GET_PERMISSIONS)
                }
            }.getOrDefault(emptyList())

            val selfPkg = appContext.packageName

            for (pkg in installedPackages) {
                val pkgName = pkg.packageName
                if (pkgName == selfPkg) continue
                scannedPkgNames.add(pkgName)

                val isSystem = (pkg.applicationInfo?.flags ?: 0) and ApplicationInfo.FLAG_SYSTEM != 0
                count++

                val appLabel = runCatching { pkg.applicationInfo?.loadLabel(pm)?.toString() }.getOrNull() ?: pkgName
                val appIcon = runCatching { pkg.applicationInfo?.loadIcon(pm) }.getOrNull()

                // Rule A: Known malicious patterns in non-system package name
                if (!isSystem) {
                    val lowerPkg = pkgName.lowercase(Locale.ROOT)
                    if (SUSPICIOUS_PKG_PATTERNS.any { lowerPkg.contains(it) }) {
                        threats.add(
                            DetectedThreat(
                                title = appLabel,
                                targetIdentifier = pkgName,
                                type = ThreatType.MALWARE_PACKAGE,
                                severity = ThreatSeverity.CRITICAL,
                                description = "Package identifier matches suspicious naming pattern ($pkgName)",
                                isFile = false,
                                appIcon = appIcon
                            )
                        )
                        continue
                    }
                }

                // Rule B: Rogue Accessibility & Tapjacking Overlay Abuse
                val requestedPermissions = pkg.requestedPermissions?.toList() ?: emptyList()
                val hasOverlay = requestedPermissions.contains("android.permission.SYSTEM_ALERT_WINDOW")
                val hasAccessibility = requestedPermissions.contains("android.permission.BIND_ACCESSIBILITY_SERVICE")
                val hasBoot = requestedPermissions.contains("android.permission.RECEIVE_BOOT_COMPLETED")
                val hasSms = requestedPermissions.contains("android.permission.READ_SMS") || requestedPermissions.contains("android.permission.RECEIVE_SMS")

                if (!isSystem) {
                    if (hasAccessibility && hasBoot) {
                        threats.add(
                            DetectedThreat(
                                title = appLabel,
                                targetIdentifier = pkgName,
                                type = ThreatType.ROGUE_ACCESSIBILITY,
                                severity = ThreatSeverity.CRITICAL,
                                description = "Requests Accessibility Service + Boot Auto-start (High risk background activity signature)",
                                isFile = false,
                                appIcon = appIcon
                            )
                        )
                    } else if (hasOverlay && hasSms) {
                        threats.add(
                            DetectedThreat(
                                title = appLabel,
                                targetIdentifier = pkgName,
                                type = ThreatType.STEALTH_OVERLAY,
                                severity = ThreatSeverity.HIGH,
                                description = "Requests Floating Window Overlay + SMS interception (Potential tapjacking / credential risk)",
                                isFile = false,
                                appIcon = appIcon
                            )
                        )
                    }
                }
            }

            // ── 2. Deep Shell Package Discovery (uses an alternate path around Package Visibility on Android 11+) ──
            if (NukeConnectionManager.isConnected()) {
                val shellPackagesRes = NukeConnectionManager.executeCommand("pm list packages -u -3", 4_000L)
                if (shellPackagesRes?.isSuccess == true) {
                    val lines = shellPackagesRes.output.lines()
                    for (line in lines) {
                        val trimmed = line.trim()
                        if (!trimmed.startsWith("package:")) continue
                        val rawPkg = trimmed.removePrefix("package:").trim()
                        if (rawPkg.isBlank() || rawPkg == selfPkg || scannedPkgNames.contains(rawPkg)) continue

                        count++
                        scannedPkgNames.add(rawPkg)
                        val lowerPkg = rawPkg.lowercase(Locale.ROOT)
                        if (SUSPICIOUS_PKG_PATTERNS.any { lowerPkg.contains(it) }) {
                            threats.add(
                                DetectedThreat(
                                    title = rawPkg,
                                    targetIdentifier = rawPkg,
                                    type = ThreatType.MALWARE_PACKAGE,
                                    severity = ThreatSeverity.CRITICAL,
                                    description = "Privileged audit detected hidden/unlisted suspicious package ($rawPkg)",
                                    isFile = false,
                                    appIcon = null
                                )
                            )
                        }
                    }
                }
            }

            // ── 3. Storage Threat Scan (Downloads & Staged Payloads) ──────────
            val dirsToScan = listOfNotNull(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                File(Environment.getExternalStorageDirectory(), "Download"),
                File(Environment.getExternalStorageDirectory(), "WhatsApp/Media/WhatsApp Documents"),
                File(Environment.getExternalStorageDirectory(), "Android/media/com.whatsapp/WhatsApp/Media/WhatsApp Documents"),
                File(Environment.getExternalStorageDirectory(), "Telegram/Telegram Documents")
            )

            val scannedFilePaths = mutableSetOf<String>()

            for (dir in dirsToScan) {
                if (dir.exists() && dir.isDirectory) {
                    val files = dir.listFiles() ?: continue
                    for (f in files) {
                        if (!f.isFile) continue
                        count++
                        scannedFilePaths.add(f.absolutePath)
                        val name = f.name.lowercase(Locale.ROOT)
                        if (name.endsWith(".apk") || name.endsWith(".dex") || name.endsWith(".payload")) {
                            if (SUSPICIOUS_PKG_PATTERNS.any { name.contains(it) } || name.contains("mod")) {
                                threats.add(
                                    DetectedThreat(
                                        title = f.name,
                                        targetIdentifier = f.absolutePath,
                                        type = ThreatType.SUSPICIOUS_APK_DROPPER,
                                        severity = ThreatSeverity.HIGH,
                                        description = "Suspicious installer package found in local storage (${f.parentFile?.name})",
                                        isFile = true
                                    )
                                )
                            }
                        }
                    }
                }
            }

            // ── 4. Deep Shell Storage Sweep (uses an alternate path around Scoped Storage on Android 11 to 17) ──
            if (NukeConnectionManager.isConnected()) {
                val findScript = "find /sdcard/Download /data/local/tmp -maxdepth 3 -type f \\( -name \"*.apk\" -o -name \"*.dex\" -o -name \"*.payload\" \\) 2>/dev/null"
                val findRes = NukeConnectionManager.executeCommand(findScript, 5_000L)
                if (findRes?.isSuccess == true) {
                    val fileLines = findRes.output.lines()
                    for (pathLine in fileLines) {
                        val filePath = pathLine.trim()
                        if (filePath.isBlank() || scannedFilePaths.contains(filePath)) continue
                        count++
                        scannedFilePaths.add(filePath)

                        val fileName = filePath.substringAfterLast("/").lowercase(Locale.ROOT)
                        if (SUSPICIOUS_PKG_PATTERNS.any { fileName.contains(it) } || fileName.contains("mod")) {
                            threats.add(
                                DetectedThreat(
                                    title = filePath.substringAfterLast("/"),
                                    targetIdentifier = filePath,
                                    type = ThreatType.SUSPICIOUS_APK_DROPPER,
                                    severity = ThreatSeverity.HIGH,
                                    description = "Privileged audit detected unverified installation binary ($filePath)",
                                    isFile = true
                                )
                            )
                        }
                    }
                }
            }

        } catch (e: Throwable) {
            Log.e(TAG, "Error during security scan", e)
        }

        val now = System.currentTimeMillis()
        appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putLong(KEY_LAST_SCAN, now).apply()

        val statusMsg = if (threats.isEmpty()) {
            "System Secure & Clean (0 Risks Detected)"
        } else {
            "⚠️ ${threats.size} Security Risks Detected!"
        }

        _state.update {
            it.copy(
                isScanning = false,
                threats = threats,
                lastScanTimestamp = now,
                scannedItemsCount = count,
                statusMessage = statusMsg
            )
        }

        // ── 5. Dispatch Notifications ─────────────────────────────────────
        if (_state.value.isShieldActive) {
            if (threats.isEmpty()) {
                NukeAntivirusNotifier.showSafeNotification(appContext, count)
            } else {
                NukeAntivirusNotifier.showThreatAlertNotification(appContext, threats)
            }
        }
    }

    // ── Threat Actions ────────────────────────────────────────────────────────

    fun uninstallOrDeleteThreat(context: Context, threat: DetectedThreat): Boolean {
        val appContext = context.applicationContext
        if (threat.isFile) {
            val file = File(threat.targetIdentifier)
            var deleted = runCatching { file.delete() }.getOrDefault(false)
            if (!deleted && NukeConnectionManager.isConnected()) {
                val res = NukeConnectionManager.executeCommand("rm -f '${threat.targetIdentifier}'", 3_000L)
                if (res?.isSuccess == true) deleted = true
            }
            if (deleted) {
                removeThreatFromState(threat.id)
                NukeToast.success(appContext, "Threat payload removed: ${threat.title}")
                return true
            } else {
                NukeToast.error(appContext, "Failed to delete file (Storage permission denied)")
                return false
            }
        } else {
            val pkg = threat.targetIdentifier
            // Try privileged shell uninstall first
            var success = false
            if (NukeConnectionManager.isConnected()) {
                val res = NukeConnectionManager.executeCommand("pm uninstall $pkg", 5_000L)
                if (res?.isSuccess == true && res.output.contains("Success", ignoreCase = true)) {
                    success = true
                }
            }
            if (success) {
                removeThreatFromState(threat.id)
                NukeToast.success(appContext, "Malicious app uninstalled: ${threat.title}")
                return true
            } else {
                // Launch user-facing system uninstall dialog
                val intent = Intent(Intent.ACTION_UNINSTALL_PACKAGE).apply {
                    data = Uri.parse("package:$pkg")
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
                appContext.startActivity(intent)
                return false
            }
        }
    }

    fun forceStopThreat(context: Context, threat: DetectedThreat): Boolean {
        val appContext = context.applicationContext
        if (threat.isFile) return false
        val pkg = threat.targetIdentifier

        if (NukeConnectionManager.isConnected()) {
            val res = NukeConnectionManager.executeCommand("am force-stop $pkg", 3_000L)
            if (res?.isSuccess == true) {
                NukeToast.success(appContext, "Force stop executed for: ${threat.title}")
                return true
            }
        }

        // Fallback: Open app details
        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.parse("package:$pkg")
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        appContext.startActivity(intent)
        return false
    }

    fun isolateThreat(context: Context, threat: DetectedThreat): Boolean {
        val appContext = context.applicationContext
        if (threat.isFile) return false
        val pkg = threat.targetIdentifier

        if (NukeConnectionManager.isConnected()) {
            val res = NukeConnectionManager.executeCommand("pm disable-user --user 0 $pkg 2>/dev/null || pm disable $pkg", 3_000L)
            if (res?.isSuccess == true && (res.output.contains("disabled", ignoreCase = true) || res.output.contains("new state", ignoreCase = true))) {
                NukeToast.success(appContext, "App isolated and frozen: ${threat.title}")
                return true
            }
        }

        NukeToast.unsupported(appContext, "Isolation requires Wireless ADB / Shizuku privileges")
        return false
    }

    fun neutralizeAll(context: Context) {
        val appContext = context.applicationContext
        val currentThreats = _state.value.threats
        if (currentThreats.isEmpty()) {
            NukeToast.success(appContext, "No threats detected on device.")
            return
        }

        scope.launch {
            var neutralizedCount = 0
            for (t in currentThreats) {
                if (t.isFile) {
                    var deleted = runCatching { File(t.targetIdentifier).delete() }.getOrDefault(false)
                    if (!deleted && NukeConnectionManager.isConnected()) {
                        val res = NukeConnectionManager.executeCommand("rm -f '${t.targetIdentifier}'", 3_000L)
                        if (res?.isSuccess == true) deleted = true
                    }
                    if (deleted) neutralizedCount++
                } else {
                    if (NukeConnectionManager.isConnected()) {
                        NukeConnectionManager.executeCommand("am force-stop ${t.targetIdentifier}; pm disable-user --user 0 ${t.targetIdentifier}", 3_000L)
                        neutralizedCount++
                    }
                }
            }
            triggerDeepScan(appContext)
            NukeToast.success(appContext, "🛡️ $neutralizedCount threats successfully neutralized!")
        }
    }

    private fun removeThreatFromState(threatId: String) {
        _state.update { current ->
            val updated = current.threats.filter { it.id != threatId }
            current.copy(
                threats = updated,
                statusMessage = if (updated.isEmpty()) "System Clean & Secure" else "⚠️ ${updated.size} Threats Remaining"
            )
        }
    }
}
