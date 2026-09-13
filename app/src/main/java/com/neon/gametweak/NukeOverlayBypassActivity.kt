package com.neon.gametweak

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.neon.gametweak.ui.theme.Neon
import com.neon.gametweak.ui.theme.NukeEnterpriseTheme

class NukeOverlayBypassActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // If permission is already granted, finish immediately
        if (Settings.canDrawOverlays(this)) {
            NukeToast.success(this, "Floating HUD overlay permission is already active.")
            finish()
            return
        }

        setContent {
            NukeEnterpriseTheme {
                NukeOverlayBypassScreen(
                    onDismiss = { finish() },
                    onOpenConnection = {
                        val intent = Intent(this, MainActivity::class.java).apply {
                            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
                            putExtra("OPEN_CONNECTION_DIALOG", true)
                        }
                        startActivity(intent)
                        finish()
                    },
                    onCopyCommand = {
                        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        val clip = ClipData.newPlainText(
                            "Game Nuke ADB Command",
                            "adb shell appops set $packageName SYSTEM_ALERT_WINDOW allow"
                        )
                        clipboard.setPrimaryClip(clip)
                        NukeToast.success(this, "ADB command copied to clipboard!")
                    },
                    onOpenAppInfo = {
                        runCatching {
                            startActivity(
                                Intent(
                                    Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                    Uri.parse("package:$packageName")
                                ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            )
                        }
                    },
                    onOpenSettings = {
                        runCatching {
                            startActivity(
                                Intent(
                                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                    Uri.parse("package:$packageName")
                                ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            )
                        }
                    },
                    onVerify = {
                        // Re-check auto-grant via bridge first
                        if (OverlayPermissionController.tryAutoGrantViaBridge(this, silent = false) || Settings.canDrawOverlays(this)) {
                            NukeToast.success(this, "Floating HUD authorization verified! Ready to launch.")
                            finish()
                        } else {
                            NukeToast.unsupported(this, "Permission not yet active. Please complete one of the methods below.", long = true)
                        }
                    }
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (Settings.canDrawOverlays(this)) {
            NukeToast.success(this, "Floating HUD authorization confirmed!")
            finish()
        }
    }
}

@Composable
private fun NukeOverlayBypassScreen(
    onDismiss: () -> Unit,
    onOpenConnection: () -> Unit,
    onCopyCommand: () -> Unit,
    onOpenAppInfo: () -> Unit,
    onOpenSettings: () -> Unit,
    onVerify: () -> Unit,
) {
    val scrollState = rememberScrollState()

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xE6050B08))
            .padding(16.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(18.dp))
                .background(
                    Brush.verticalGradient(
                        listOf(Color(0xFF0F1B16), Color(0xFF08100C))
                    )
                )
                .border(1.dp, Color(0xFF1E3A30), RoundedCornerShape(18.dp))
                .padding(20.dp)
                .verticalScroll(scrollState)
        ) {
            // Header
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                Box(
                    modifier = Modifier
                        .size(42.dp)
                        .clip(CircleShape)
                        .background(Neon.Accent.copy(alpha = 0.15f))
                        .border(1.dp, Neon.Accent.copy(alpha = 0.5f), CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.Rounded.Layers,
                        contentDescription = null,
                        tint = Neon.Accent,
                        modifier = Modifier.size(24.dp)
                    )
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        "FLOATING HUD AUTHORIZATION",
                        color = Color.White,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 0.5.sp
                    )
                    Text(
                        "Android 14-16 & Low-RAM Compatibility Bridge",
                        color = Neon.TextDim,
                        fontSize = 11.sp
                    )
                }
            }

            Spacer(Modifier.height(16.dp))

            // System Restriction Notice
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(Color(0x33FFB300))
                    .border(0.8.dp, Color(0x66FFB300), RoundedCornerShape(10.dp))
                    .padding(12.dp)
            ) {
                Row(verticalAlignment = Alignment.Top) {
                    Icon(
                        Icons.Rounded.WarningAmber,
                        contentDescription = null,
                        tint = Color(0xFFFFB300),
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(Modifier.width(10.dp))
                    Column {
                        Text(
                            "System Restriction Detected",
                            color = Color(0xFFFFB300),
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(Modifier.height(3.dp))
                        Text(
                            "Your device profile disables overlay switches in standard Settings with:\n" +
                            "\"Feature not available — This feature has been disabled because it slows down your phone\"\n\n" +
                            "Game Nuke provides enterprise methods below to safely bypass this restriction without phone slowdown.",
                            color = Color(0xFFE2E8F0),
                            fontSize = 11.sp,
                            lineHeight = 16.sp
                        )
                    }
                }
            }

            Spacer(Modifier.height(14.dp))

            // Method 1: Auto-Grant via Bridge (Recommended)
            BypassOptionCard(
                badge = "RECOMMENDED · 1-TAP",
                badgeColor = Neon.Accent,
                title = "Auto-Grant via Nuke Core Bridge",
                description = "Connect Wireless Debugging or Shizuku on the Dashboard. Game Nuke will automatically grant overlay permission with zero manual toggling.",
                actionLabel = "OPEN DASHBOARD CONNECTION",
                actionIcon = Icons.Rounded.Tune,
                onAction = onOpenConnection
            )

            Spacer(Modifier.height(10.dp))

            // Method 2: Shell Command
            BypassOptionCard(
                badge = "PC / TERMUX / ADB",
                badgeColor = Color(0xFF38BDF8),
                title = "Direct ADB Shell Command",
                description = "Execute this command once via PC or local ADB shell to grant instant system-level authorization:",
                codeSnippet = "adb shell appops set com.neon.gametweak SYSTEM_ALERT_WINDOW allow",
                actionLabel = "COPY ADB COMMAND",
                actionIcon = Icons.Rounded.ContentCopy,
                onAction = onCopyCommand
            )

            Spacer(Modifier.height(10.dp))

            // Method 3: Restricted Settings Unlock
            BypassOptionCard(
                badge = "ANDROID 13-16 SIDELOAD",
                badgeColor = Color(0xFFA78BFA),
                title = "Allow Restricted Settings",
                description = "Open App Info, tap the 3 dots in the top-right corner, and tap 'Allow restricted settings'. Then return and enable overlay.",
                actionLabel = "OPEN APP INFO",
                actionIcon = Icons.Rounded.Settings,
                onAction = onOpenAppInfo
            )

            Spacer(Modifier.height(10.dp))

            // Method 4: Standard Settings Fallback
            BypassOptionCard(
                badge = "FALLBACK",
                badgeColor = Color(0xFF94A3B8),
                title = "System Overlay Settings",
                description = "Try opening standard Android overlay settings directly if your custom ROM allows toggling.",
                actionLabel = "OPEN SYSTEM SETTINGS",
                actionIcon = Icons.AutoMirrored.Rounded.OpenInNew,
                onAction = onOpenSettings
            )

            Spacer(Modifier.height(20.dp))

            // Bottom Buttons
            Button(
                onClick = onVerify,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(46.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Neon.Accent,
                    contentColor = Color.Black
                ),
                shape = RoundedCornerShape(10.dp)
            ) {
                Icon(Icons.Rounded.CheckCircle, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(
                    "VERIFY & LAUNCH HUD",
                    fontWeight = FontWeight.Bold,
                    fontSize = 12.sp,
                    letterSpacing = 0.5.sp
                )
            }

            Spacer(Modifier.height(8.dp))

            TextButton(
                onClick = onDismiss,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    "DISMISS",
                    color = Neon.TextDim,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }
    }
}

@Composable
private fun BypassOptionCard(
    badge: String,
    badgeColor: Color,
    title: String,
    description: String,
    codeSnippet: String? = null,
    actionLabel: String,
    actionIcon: ImageVector,
    onAction: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(Color(0xFF0D1713))
            .border(0.8.dp, Color(0xFF1E3A30), RoundedCornerShape(12.dp))
            .padding(12.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
        ) {
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(4.dp))
                    .background(badgeColor.copy(alpha = 0.15f))
                    .border(0.6.dp, badgeColor.copy(alpha = 0.4f), RoundedCornerShape(4.dp))
                    .padding(horizontal = 6.dp, vertical = 2.dp)
            ) {
                Text(
                    badge,
                    color = badgeColor,
                    fontSize = 9.sp,
                    fontWeight = FontWeight.Bold
                )
            }
            Spacer(Modifier.width(8.dp))
            Text(
                title,
                color = Color.White,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold
            )
        }

        Spacer(Modifier.height(6.dp))

        Text(
            description,
            color = Color(0xFF94A3B8),
            fontSize = 11.sp,
            lineHeight = 15.sp
        )

        if (codeSnippet != null) {
            Spacer(Modifier.height(6.dp))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(6.dp))
                    .background(Color(0xFF050B08))
                    .border(0.6.dp, Color(0xFF1E3A30), RoundedCornerShape(6.dp))
                    .padding(horizontal = 8.dp, vertical = 6.dp)
            ) {
                Text(
                    codeSnippet,
                    color = Neon.Accent,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 10.sp
                )
            }
        }

        Spacer(Modifier.height(10.dp))

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .background(badgeColor.copy(alpha = 0.12f))
                .border(0.8.dp, badgeColor.copy(alpha = 0.35f), RoundedCornerShape(8.dp))
                .clickable { onAction() }
                .padding(vertical = 8.dp),
            contentAlignment = Alignment.Center
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    actionIcon,
                    contentDescription = null,
                    tint = badgeColor,
                    modifier = Modifier.size(15.dp)
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    actionLabel,
                    color = badgeColor,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 0.5.sp
                )
            }
        }
    }
}
