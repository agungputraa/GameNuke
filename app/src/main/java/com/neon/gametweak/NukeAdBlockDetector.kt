package com.neon.gametweak

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.util.Log
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CutCornerShape
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
        "quad9",
        "decloud",
        "oisd",
        "block",
    )

    /**
     * Inspects system DNS configuration both via ContentResolver and elevated shell.
     */
    fun checkStatus(context: Context, adbManager: AdbManager? = null): AdBlockStatus {
        return runCatching {
            var mode = runCatching {
                Settings.Global.getString(context.contentResolver, "private_dns_mode")
            }.getOrNull().orEmpty().trim()

            var specifier = runCatching {
                Settings.Global.getString(context.contentResolver, "private_dns_specifier")
            }.getOrNull().orEmpty().trim()

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

            val lowerSpecifier = specifier.lowercase()
            val lowerMode = mode.lowercase()

            val isAdBlockSpecifier = lowerSpecifier.isNotBlank() && lowerSpecifier != "null" &&
                ADBLOCK_DNS_KEYWORDS.any { lowerSpecifier.contains(it) }

            val isHostnameModeWithSpecifier = lowerMode == "hostname" && lowerSpecifier.isNotBlank() && lowerSpecifier != "null"

            if (isAdBlockSpecifier || isHostnameModeWithSpecifier) {
                val reason = if (isAdBlockSpecifier) {
                    "AdBlock DNS ($specifier)"
                } else {
                    "Custom Private DNS ($specifier)"
                }
                return@runCatching AdBlockStatus(
                    isDetected = true,
                    detectedDnsSpecifier = specifier,
                    dnsMode = mode,
                    reason = reason,
                )
            }

            AdBlockStatus(
                isDetected = false,
                detectedDnsSpecifier = specifier,
                dnsMode = mode,
            )
        }.getOrElse { AdBlockStatus() }
    }

    /**
     * Executes shell commands to change Private DNS mode to "opportunistic" (Auto) or "off"
     * and clears the custom adblocking specifier.
     */
    suspend fun disableAdBlockViaShell(
        context: Context,
        adbManager: AdbManager?,
        targetMode: String = "opportunistic",
    ): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            val commands = listOf(
                "settings put global private_dns_mode $targetMode",
                "settings delete global private_dns_specifier",
                "settings put global private_dns_specifier \"\"",
            )

            var executed = false
            for (cmd in commands) {
                val res = adbManager?.executeCommand(cmd, "/", 3_000L, 1_024)
                    ?: NukeConnectionManager.executeCommand(cmd, 3_000L, 1_024)
                if (res != null && (res.isSuccess || res.exitCode == 0)) {
                    executed = true
                }
            }

            // Verify the change took effect
            val updated = checkStatus(context, adbManager)
            !updated.isDetected || executed
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
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    var isExecuting by remember { mutableStateOf(false) }
    var executionMessage by remember { mutableStateOf("") }
    val detectedTarget = status.detectedDnsSpecifier.ifBlank { status.reason.ifBlank { "dns.adguard.com" } }

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
                .clip(CutCornerShape(topStart = 18.dp, topEnd = 6.dp, bottomStart = 6.dp, bottomEnd = 18.dp))
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
                    CutCornerShape(topStart = 18.dp, topEnd = 6.dp, bottomStart = 6.dp, bottomEnd = 18.dp)
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
                                .clip(CutCornerShape(6.dp))
                                .background(Color(0xFFFF4B55).copy(alpha = 0.18f))
                                .border(1.dp, Color(0xFFFF4B55), CutCornerShape(6.dp)),
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
                                Tx.t("ADBLOCK TERDETEKSI", "ADBLOCK DETECTED"),
                                color = Color.White,
                                fontSize = 13.5.sp,
                                fontWeight = FontWeight.Black,
                                letterSpacing = 1.sp
                            )
                            Spacer(Modifier.height(1.dp))
                            Text(
                                Tx.t("Tools & Game Panel Terkunci", "Core Tools & Game Panel Locked"),
                                color = Color(0xFFFF7A85),
                                fontSize = 9.5.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                    if (!isExecuting) {
                        IconButton(
                            onClick = onDismiss,
                            modifier = Modifier.size(30.dp)
                        ) {
                            Icon(Icons.Rounded.Close, contentDescription = "Close", tint = Neon.TextDim)
                        }
                    }
                }

                Box(Modifier.fillMaxWidth().height(1.dp).background(Color(0xFF5A1B20)))

                // Target DNS block
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(CutCornerShape(topStart = 6.dp, bottomEnd = 6.dp))
                        .background(Color(0xFF260F12))
                        .border(0.8.dp, Color(0xFFFF4B55).copy(alpha = 0.45f), CutCornerShape(topStart = 6.dp, bottomEnd = 6.dp))
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
                                .clip(CutCornerShape(2.dp))
                                .background(Color(0xFFFF4B55).copy(alpha = 0.25f))
                                .border(0.6.dp, Color(0xFFFF4B55), CutCornerShape(2.dp))
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
                    Tx.t(
                        "Komponen inti tidak dapat digunakan selama sistem mendeteksi pemblokir iklan (%s). Fitur gaming dan booster tools terkunci secara otomatis.\n\nKlik DISABLE untuk memulihkan konfigurasi jaringan default dan mengaktifkan kembali seluruh tools.",
                        "Core features cannot be used while an active ad blocker (%s) is detected. Gaming booster tools are temporarily locked.\n\nClick DISABLE to restore default network settings and unlock full tool access."
                    ).format(detectedTarget),
                    color = Color(0xFFD6C2C4),
                    fontSize = 10.5.sp,
                    lineHeight = 15.sp
                )

                // Loading message during shell execution
                AnimatedVisibility(visible = isExecuting) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(CutCornerShape(6.dp))
                            .background(Color(0xFF0F1E19))
                            .border(0.8.dp, Neon.Accent, CutCornerShape(6.dp))
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
                                executionMessage.ifBlank { Tx.t("Memulihkan konfigurasi jaringan...", "Restoring network configuration...") },
                                color = Neon.Accent,
                                fontSize = 10.5.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }

                // Two clean buttons: DISABLE and LATER
                if (!isExecuting) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        // DISABLE Button (Primary Action: Switches to Automatic via Shell)
                        Box(
                            modifier = Modifier
                                .weight(1.3f)
                                .height(44.dp)
                                .clip(CutCornerShape(topStart = 8.dp, bottomEnd = 8.dp))
                                .background(
                                    Brush.horizontalGradient(
                                        listOf(
                                            Color(0xFF0C382A),
                                            Color(0xFF0A2B20)
                                        )
                                    )
                                )
                                .border(1.dp, Neon.Accent, CutCornerShape(topStart = 8.dp, bottomEnd = 8.dp))
                                .clickable {
                                    isExecuting = true
                                    executionMessage = Tx.t("Memulihkan konfigurasi jaringan...", "Restoring network configuration...")
                                    coroutineScope.launch {
                                        val success = NukeAdBlockDetector.disableAdBlockViaShell(
                                            context = context,
                                            adbManager = adbManager,
                                            targetMode = "opportunistic"
                                        )
                                        delay(500L)
                                        isExecuting = false
                                        if (success) {
                                            NukeToast.success(
                                                context,
                                                Tx.t("Pengaturan jaringan berhasil dipulihkan! Memuat ulang...", "Network configuration restored! Reloading...")
                                            )
                                            onDismiss()
                                            delay(350L)
                                            context.findActivity()?.let { NukeAdBlockDetector.reloadApp(it) }
                                        } else {
                                            NukeToast.error(
                                                context,
                                                Tx.t("Gagal memperbarui konfigurasi jaringan. Pastikan koneksi aktif.", "Failed to update network configuration. Ensure connection is active.")
                                            )
                                        }
                                    }
                                },
                            contentAlignment = Alignment.Center
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Icon(
                                    Icons.Rounded.AutoFixHigh,
                                    contentDescription = null,
                                    tint = Neon.Accent,
                                    modifier = Modifier.size(16.dp)
                                )
                                Text(
                                    Tx.t("DISABLE", "DISABLE"),
                                    color = Neon.Accent,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Black,
                                    letterSpacing = 1.sp
                                )
                            }
                        }

                        // LATER Button
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .height(44.dp)
                                .clip(CutCornerShape(topStart = 8.dp, bottomEnd = 8.dp))
                                .background(Color(0xFF181012))
                                .border(0.8.dp, Color(0xFF5A2A2E), CutCornerShape(topStart = 8.dp, bottomEnd = 8.dp))
                                .clickable { onDismiss() },
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                Tx.t("LATER", "LATER"),
                                color = Neon.TextDim,
                                fontSize = 11.5.sp,
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

