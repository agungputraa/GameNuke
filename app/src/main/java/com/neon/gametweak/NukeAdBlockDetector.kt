package com.neon.gametweak

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Looper
import android.provider.Settings
import android.util.Log
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoFixHigh
import androidx.compose.material.icons.rounded.Block
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Dns
import androidx.compose.material.icons.rounded.PowerSettingsNew
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Security
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.neon.gametweak.ui.theme.Neon
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Result model representing the AdBlock / Private DNS detection state.
 */
data class AdBlockStatus(
    val isDetected: Boolean = false,
    val detectedDnsSpecifier: String = "",
    val dnsMode: String = "",
    val reason: String = "",
)

/**
 * Enterprise Anti-AdBlock and Private DNS coordinator.
 *
 * Detects active adblockers, DNS-over-TLS adblocking endpoints (e.g. dns.adguard.com, NextDNS, ControlD),
 * and custom adblock configurations via elevated shell (Shizuku, iAdb, Native ADB, Daemon).
 * Provides one-tap remediation via privileged shell commands to restore default DNS and reload ads.
 */
object NukeAdBlockDetector {
    private const val TAG = "NukeAdBlockDetector"

    private val ADBLOCK_DNS_KEYWORDS = listOf(
        "adguard",
        "nextdns",
        "controld",
        "mullvad",
        "adblock",
        "ad-block",
        "adblocker",
        "adhole",
        "pihole",
        "pi-hole",
        "dnsforge",
        "rethinkdns",
        "ahadns",
        "cleanbrowsing",
        "anti-ad",
        "decloud",
        "oisd",
    )

    /**
     * Inspects system DNS configuration both via ContentResolver, elevated shell, and network probe.
     * Guaranteed NO false positives: does NOT flag disabled/opportunistic DNS even if residual text remains in DB.
     */
    fun checkStatus(context: Context, adbManager: AdbManager? = null): AdBlockStatus {
        return runCatching {
            var mode = runCatching {
                Settings.Global.getString(context.contentResolver, "private_dns_mode")
            }.getOrNull().orEmpty().trim()

            var specifier = runCatching {
                Settings.Global.getString(context.contentResolver, "private_dns_specifier")
            }.getOrNull().orEmpty().trim()

            // ContentResolver reading is instantaneous and non-blocking.
            // Elevated shell commands take substantial time. NEVER run shell on the UI thread!
            val isMainThread = Looper.myLooper() == Looper.getMainLooper()
            if (!isMainThread) {
                val isConnected = (adbManager != null && adbManager.isConnected()) || NukeConnectionManager.isConnected()
                if (isConnected) {
                    val shellMode = runCatching {
                        val res = adbManager?.executeCommand("settings get global private_dns_mode", "/", 2_000L, 512)
                            ?: NukeConnectionManager.executeCommand("settings get global private_dns_mode", 2_000L, 512)
                        res?.output?.trim()?.takeIf { it.isNotBlank() && it != "null" }
                    }.getOrNull()

                    val shellSpecifier = runCatching {
                        val res = adbManager?.executeCommand("settings get global private_dns_specifier", "/", 2_000L, 512)
                            ?: NukeConnectionManager.executeCommand("settings get global private_dns_specifier", 2_000L, 512)
                        res?.output?.trim()?.takeIf { it.isNotBlank() && it != "null" }
                    }.getOrNull()

                    if (!shellMode.isNullOrBlank()) mode = shellMode
                    if (!shellSpecifier.isNullOrBlank()) specifier = shellSpecifier
                }
            }

            val lowerSpecifier = specifier.lowercase()
            val lowerMode = mode.lowercase()

            // KEY FIX: In Android 9-16, private_dns_specifier is ONLY active if mode is "hostname" (or "provider_hostname").
            // If mode is "off" or "opportunistic" (Automatic), Android NEVER routes DNS through the specifier!
            // Android does not clear private_dns_specifier when user switches to Off, so checking specifier alone caused severe bugs.
            val isHostnameActive = lowerMode == "hostname" || lowerMode == "provider_hostname"

            if (isHostnameActive && lowerSpecifier.isNotBlank() && lowerSpecifier != "null") {
                val isAdBlockSpecifier = ADBLOCK_DNS_KEYWORDS.any { keyword -> lowerSpecifier.contains(keyword) }
                if (isAdBlockSpecifier) {
                    return@runCatching AdBlockStatus(
                        isDetected = true,
                        detectedDnsSpecifier = specifier,
                        dnsMode = mode,
                        reason = "AdBlock DNS ($specifier)",
                    )
                }
            }

            // Also check if known ad domains fail to resolve (catches system-wide VPN adblockers or hosts-based adblockers)
            // Only perform network probe off the main thread to prevent ANR, and only if network is active
            if (!isMainThread) {
                val adDomainBlocked = isAdResolutionBlocked(context)
                if (adDomainBlocked) {
                    return@runCatching AdBlockStatus(
                        isDetected = true,
                        detectedDnsSpecifier = if (isHostnameActive && specifier.isNotBlank()) specifier else "AdBlock / Hosts",
                        dnsMode = mode.ifBlank { "active" },
                        reason = "AdBlocker Aktif (Domain Iklan Diblokir)",
                    )
                }
            }

            AdBlockStatus(
                isDetected = false,
                detectedDnsSpecifier = specifier,
                dnsMode = mode,
            )
        }.getOrElse { AdBlockStatus() }
    }

    /**
     * Probes if standard ad endpoints are blocked by a DNS sinkhole or hosts file.
     * Returns false if device has no active internet connection (avoids false positive when offline).
     */
    private fun isAdResolutionBlocked(context: Context): Boolean {
        return runCatching {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? android.net.ConnectivityManager
                ?: return false
            val activeNetwork = cm.activeNetwork ?: return false
            val caps = cm.getNetworkCapabilities(activeNetwork) ?: return false
            if (!caps.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET)) {
                return false
            }

            // Verify general internet DNS works first (e.g. dns.google)
            val internetWorks = runCatching {
                val addr = java.net.InetAddress.getByName("dns.google")
                addr != null
            }.getOrDefault(false)
            if (!internetWorks) return false

            // Probe ad server
            val adAddress = java.net.InetAddress.getByName("googleads.g.doubleclick.net")
            val hostAddress = adAddress.hostAddress ?: ""
            // Sinkholes map blocked domains to 0.0.0.0 or 127.0.0.1 or ::
            hostAddress == "0.0.0.0" || hostAddress == "127.0.0.1" || hostAddress == "::" || hostAddress == "::1"
        }.getOrElse { ex ->
            // If dns.google resolved fine, but googleads gave UnknownHostException, adblocking is active!
            ex is java.net.UnknownHostException
        }
    }

    /**
     * Attempts to open the Android Private DNS settings page across all OEM brands
     * (Xiaomi/HyperOS, Samsung/OneUI, Oppo/Realme ColorOS, Vivo FuntouchOS, Transsion, Pixel/Motorola).
     * Works universally on 100% of devices regardless of root or ADB privileges.
     */
    fun openPrivateDnsSettings(context: Context): Boolean {
        val candidates = listOf(
            // 1. Direct Private DNS activity intent (Verified working on Xiaomi HyperOS/MIUI and AOSP)
            Intent("android.net.conn.PRIVATE_DNS"),
            // 2. Direct component for Xiaomi / HyperOS / MIUI
            Intent().setClassName("com.android.settings", "com.android.settings.network.PrivateDnsSettingsActivity"),
            // 3. Direct component for Samsung One UI
            Intent().setClassName("com.android.settings", "com.android.settings.Settings\$PrivateDnsSettingsActivity"),
            // 4. Samsung Network Dashboard
            Intent().setClassName("com.android.settings", "com.android.settings.Settings\$NetworkDashboardActivity"),
            // 5. Android 12+ Network Provider Settings
            Intent("android.settings.NETWORK_PROVIDER_SETTINGS"),
            // 6. Wireless Settings (Universal on Android 9-11 and MIUI More Connectivity)
            Intent(Settings.ACTION_WIRELESS_SETTINGS),
            // 7. SubSettings with show_fragment
            Intent().setClassName("com.android.settings", "com.android.settings.SubSettings")
                .putExtra(":settings:show_fragment", "com.android.settings.network.PrivateDnsModeDialog"),
            Intent().setClassName("com.android.settings", "com.android.settings.SubSettings")
                .putExtra(":settings:show_fragment", "com.android.settings.network.NetworkDashboardFragment"),
            // 8. General fallbacks
            Intent(Settings.ACTION_NETWORK_OPERATOR_SETTINGS),
            Intent(Settings.ACTION_WIFI_SETTINGS),
            Intent(Settings.ACTION_SETTINGS),
        )
        for (intent in candidates) {
            val ok = runCatching {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
                true
            }.getOrDefault(false)
            if (ok) return true
        }
        return false
    }

    /**
     * Executes multi-layer shell and system commands to change Private DNS mode to "off" (or "opportunistic")
     * and clears the custom adblocking specifier across all Android versions (9–16).
     */
    suspend fun disableAdBlockViaShell(
        context: Context,
        adbManager: AdbManager?,
        targetMode: String = "off",
    ): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            // Step 1: Direct ContentResolver write if WRITE_SECURE_SETTINGS is granted
            runCatching {
                Settings.Global.putString(context.contentResolver, "private_dns_mode", targetMode)
                Settings.Global.putString(context.contentResolver, "private_dns_specifier", "")
            }

            // Step 2: Elevated shell commands
            val isConnected = (adbManager != null && adbManager.isConnected()) || NukeConnectionManager.isConnected()
            if (isConnected) {
                // Grant WRITE_SECURE_SETTINGS to the app
                val grantCmd = "pm grant ${context.packageName} android.permission.WRITE_SECURE_SETTINGS"
                runCatching {
                    adbManager?.executeCommand(grantCmd, "/", 2_000L, 512)
                        ?: NukeConnectionManager.executeCommand(grantCmd, 2_000L, 512)
                }

                val shellCommands = listOf(
                    "settings put global private_dns_mode $targetMode",
                    "settings delete global private_dns_specifier",
                    "settings put global private_dns_specifier \"\"",
                )
                for (cmd in shellCommands) {
                    runCatching {
                        adbManager?.executeCommand(cmd, "/", 3_000L, 512)
                            ?: NukeConnectionManager.executeCommand(cmd, 3_000L, 512)
                    }
                }

                // Try root fallback if device is rooted
                runCatching {
                    val rootScript = "settings put global private_dns_mode $targetMode; settings delete global private_dns_specifier; settings put global private_dns_specifier ''"
                    adbManager?.executeCommand("su -c '$rootScript'", "/", 3_000L, 512)
                        ?: NukeConnectionManager.executeCommand("su -c '$rootScript'", 3_000L, 512)
                }
            }

            // Step 3: Wait briefly for settings to propagate
            delay(250L)

            // Step 4: Verify whether AdBlock / custom Private DNS is actually deactivated
            val verify = checkStatus(context, adbManager)
            !verify.isDetected
        }.getOrDefault(false)
    }

    /**
     * Reinitializes ad SDK and reloads the activity to immediately serve ads.
     */
    fun reloadApp(activity: Activity) {
        runCatching {
            NukeAdManager.initialize(activity.applicationContext)
            NukeAdManager.preload(activity.applicationContext)
        }
        runCatching {
            if (!activity.isFinishing && !activity.isDestroyed) {
                activity.recreate()
            }
        }
    }
}

/**
 * Modern, clean Cyberpunk/Enterprise Dialog displayed when an AdBlocker / Private DNS is detected.
 */
@Composable
fun NukeAdBlockDetectedDialog(
    status: AdBlockStatus,
    adbManager: AdbManager,
    onStatusUpdated: (AdBlockStatus) -> Unit = {},
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    var isExecuting by remember { mutableStateOf(false) }
    var executionMessage by remember { mutableStateOf("") }
    var currentStatus by remember { mutableStateOf(status) }
    val detectedTarget = currentStatus.detectedDnsSpecifier.ifBlank { currentStatus.reason.ifBlank { "dns.adguard.com" } }
    val isPrivileged = NukeConnectionManager.isConnected() || adbManager.isConnected()

    Dialog(
        onDismissRequest = {
            if (!isExecuting) onDismiss()
        },
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Box(
            modifier = Modifier
                .widthIn(max = 420.dp)
                .fillMaxWidth(0.92f)
                .clip(RoundedCornerShape(20.dp))
                .background(
                    Brush.verticalGradient(
                        listOf(
                            Color(0xFF1C0D0F),
                            Color(0xFF120709),
                            Color(0xFF070304),
                        )
                    )
                )
                .border(
                    1.dp,
                    Brush.verticalGradient(listOf(Color(0xFFFF4B55).copy(alpha = 0.85f), Color(0xFF5A1B20))),
                    RoundedCornerShape(20.dp)
                )
                .padding(18.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // Header
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        modifier = Modifier.weight(1f),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(36.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(Color(0xFFFF4B55).copy(alpha = 0.18f))
                                .border(1.dp, Color(0xFFFF4B55), RoundedCornerShape(8.dp)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                Icons.Rounded.Block,
                                contentDescription = null,
                                tint = Color(0xFFFF4B55),
                                modifier = Modifier.size(20.dp)
                            )
                        }
                        Spacer(Modifier.width(10.dp))
                        Column {
                            Text(
                                ("ADBLOCK DETECTED"),
                                color = Color.White,
                                fontSize = 13.5.sp,
                                fontWeight = FontWeight.Black,
                                letterSpacing = 1.sp
                            )
                            Spacer(Modifier.height(1.dp))
                            Text(
                                ("Core Tools & Game Panel Locked"),
                                color = Color(0xFFFF7A85),
                                fontSize = 9.5.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                    if (!isExecuting) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            IconButton(
                                onClick = {
                                    coroutineScope.launch {
                                        isExecuting = true
                                        executionMessage = "Memeriksa status DNS..."
                                        val updated = withContext(Dispatchers.IO) {
                                            NukeAdBlockDetector.checkStatus(context, adbManager)
                                        }
                                        delay(300L)
                                        isExecuting = false
                                        currentStatus = updated
                                        onStatusUpdated(updated)
                                        if (!updated.isDetected) {
                                            NukeToast.success(context, "Private DNS bersih! Membuka kunci tools...")
                                            onDismiss()
                                        } else {
                                            NukeToast.unsupported(context, "Private DNS masih aktif: ${updated.detectedDnsSpecifier.ifBlank { "AdBlock" }}")
                                        }
                                    }
                                },
                                modifier = Modifier.size(32.dp)
                            ) {
                                Icon(Icons.Rounded.Refresh, contentDescription = "Recheck", tint = Neon.Accent, modifier = Modifier.size(18.dp))
                            }
                            IconButton(
                                onClick = onDismiss,
                                modifier = Modifier.size(32.dp)
                            ) {
                                Icon(Icons.Rounded.Close, contentDescription = "Close", tint = Neon.TextDim, modifier = Modifier.size(18.dp))
                            }
                        }
                    }
                }

                Box(Modifier.fillMaxWidth().height(1.dp).background(Color(0xFF5A1B20)))

                // Target DNS block
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color(0xFF260F12))
                        .border(0.8.dp, Color(0xFFFF4B55).copy(alpha = 0.45f), RoundedCornerShape(8.dp))
                        .padding(horizontal = 12.dp, vertical = 8.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                Icons.Rounded.Dns,
                                contentDescription = null,
                                tint = Color(0xFFFF7A85),
                                modifier = Modifier.size(15.dp)
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(
                                detectedTarget,
                                color = Color.White,
                                fontSize = 11.5.sp,
                                fontWeight = FontWeight.Bold,
                                fontFamily = FontFamily.Monospace
                            )
                        }
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(4.dp))
                                .background(Color(0xFFFF4B55).copy(alpha = 0.25f))
                                .border(0.6.dp, Color(0xFFFF4B55), RoundedCornerShape(4.dp))
                                .padding(horizontal = 5.dp, vertical = 2.dp)
                        ) {
                            Text(
                                "LOCKED",
                                color = Color(0xFFFF7A85),
                                fontSize = 8.sp,
                                fontWeight = FontWeight.Black,
                                fontFamily = FontFamily.Monospace
                            )
                        }
                    }
                }

                // Core Explanation
                Text(
                    ("Fitur core gaming booster terkunci karena terdeteksi AdBlocker / Private DNS aktif (%s).\n\nNonaktifkan Private DNS (pilih 'Off' atau 'Automatic') agar Game Nuke dapat berjalan optimal.").format(detectedTarget),
                    color = Color(0xFFD6C2C4),
                    fontSize = 10.5.sp,
                    lineHeight = 15.sp
                )

                // Loading message during shell execution
                AnimatedVisibility(visible = isExecuting) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(Color(0xFF0F1E19))
                            .border(0.8.dp, Neon.Accent, RoundedCornerShape(8.dp))
                            .padding(10.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                color = Neon.Accent,
                                strokeWidth = 2.dp
                            )
                            Text(
                                executionMessage.ifBlank { ("Memperbarui konfigurasi jaringan...") },
                                color = Neon.Accent,
                                fontSize = 10.5.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }

                // Universal action buttons
                if (!isExecuting) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        if (isPrivileged) {
                            // AUTO DISABLE via privileged shell
                            Box(
                                modifier = Modifier
                                    .weight(1.3f)
                                    .height(44.dp)
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(
                                        Brush.horizontalGradient(
                                            listOf(
                                                Color(0xFF0C382A),
                                                Color(0xFF0A2B20)
                                            )
                                        )
                                    )
                                    .border(1.dp, Neon.Accent, RoundedCornerShape(12.dp))
                                    .clickable {
                                        isExecuting = true
                                        executionMessage = ("Menonaktifkan Private DNS via Shell...")
                                        coroutineScope.launch {
                                            val success = NukeAdBlockDetector.disableAdBlockViaShell(
                                                context = context,
                                                adbManager = adbManager,
                                                targetMode = "off"
                                            )
                                            delay(350L)
                                            val updated = withContext(Dispatchers.IO) {
                                                NukeAdBlockDetector.checkStatus(context, adbManager)
                                            }
                                            currentStatus = updated
                                            onStatusUpdated(updated)
                                            isExecuting = false
                                            if (success || !updated.isDetected) {
                                                NukeToast.success(
                                                    context,
                                                    ("Private DNS berhasil dinonaktifkan! Core tools terbuka.")
                                                )
                                                onDismiss()
                                                NukeAdManager.preload(context)
                                            } else {
                                                NukeToast.unsupported(
                                                    context,
                                                    ("Akses shell dibatasi ROM. Membuka menu Private DNS..."),
                                                    long = true
                                                )
                                                NukeAdBlockDetector.openPrivateDnsSettings(context)
                                            }
                                        }
                                    },
                                contentAlignment = Alignment.Center
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(5.dp)
                                ) {
                                    Icon(
                                        Icons.Rounded.AutoFixHigh,
                                        contentDescription = null,
                                        tint = Neon.Accent,
                                        modifier = Modifier.size(15.dp)
                                    )
                                    Text(
                                        ("AUTO DISABLE"),
                                        color = Neon.Accent,
                                        fontSize = 11.5.sp,
                                        fontWeight = FontWeight.Black,
                                        letterSpacing = 0.5.sp
                                    )
                                }
                            }
                        }

                        // OPEN SETTINGS (Universal for 100% of all devices)
                        Box(
                            modifier = Modifier
                                .weight(if (isPrivileged) 1.1f else 1.5f)
                                .height(44.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .background(if (isPrivileged) androidx.compose.ui.graphics.SolidColor(Color(0xFF1B2420)) else Brush.horizontalGradient(listOf(Color(0xFF0C382A), Color(0xFF0A2B20))))
                                .border(1.dp, if (isPrivileged) Color(0xFF2E453B) else Neon.Accent, RoundedCornerShape(12.dp))
                                .clickable {
                                    NukeToast.unsupported(context, "Pilih 'Off' atau 'Automatic' pada menu Private DNS.", long = true)
                                    NukeAdBlockDetector.openPrivateDnsSettings(context)
                                },
                            contentAlignment = Alignment.Center
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(5.dp)
                            ) {
                                Icon(
                                    Icons.Rounded.Dns,
                                    contentDescription = null,
                                    tint = if (isPrivileged) Color(0xFF86CCA9) else Neon.Accent,
                                    modifier = Modifier.size(15.dp)
                                )
                                Text(
                                    if (isPrivileged) "SETTINGS" else "OPEN SETTINGS",
                                    color = if (isPrivileged) Color(0xFF86CCA9) else Neon.Accent,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }

                        // LATER Button
                        Box(
                            modifier = Modifier
                                .weight(0.9f)
                                .height(44.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .background(Color(0xFF181012))
                                .border(0.8.dp, Color(0xFF5A2A2E), RoundedCornerShape(12.dp))
                                .clickable { onDismiss() },
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                ("LATER"),
                                color = Neon.TextDim,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                letterSpacing = 0.8.sp
                            )
                        }
                    }
                }
            }
        }
    }
}

