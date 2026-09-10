package com.neon.gametweak.ui.screens

import com.neon.gametweak.nukePressFeedback
import android.app.ActivityManager
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.Environment
import android.os.StatFs
import android.provider.Settings
import android.view.WindowManager
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Adb
import androidx.compose.material.icons.rounded.AdminPanelSettings
import androidx.compose.material.icons.rounded.BatteryChargingFull
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.CleaningServices
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.DeveloperMode
import androidx.compose.material.icons.rounded.Gamepad
import androidx.compose.material.icons.rounded.Layers
import androidx.compose.material.icons.rounded.Memory
import androidx.compose.material.icons.rounded.NotificationsOff
import androidx.compose.material.icons.rounded.OpenInNew
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Security
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.Storage
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.material.icons.rounded.Thermostat
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material.icons.rounded.Wifi
import androidx.compose.material.icons.rounded.WifiTethering
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.neon.gametweak.AdbManager
import com.neon.gametweak.BuildConfig
import com.neon.gametweak.NukeConnectionManager
import com.neon.gametweak.NukeGamingShellGateway
import com.neon.gametweak.NukeIadbBridge
import com.neon.gametweak.NukeLocalCpuSampler
import com.neon.gametweak.NukeRuntimeState
import com.neon.gametweak.NukeShizukuBridge
import com.neon.gametweak.NukeToast
import com.neon.gametweak.NukeAdManager
import com.neon.gametweak.findActivity
import com.neon.gametweak.Tx
import com.neon.gametweak.safeBoolean
import com.neon.gametweak.AdBlockStatus
import com.neon.gametweak.NukeAdBlockDetector
import com.neon.gametweak.NukeAdBlockDetectedDialog
import com.neon.gametweak.ui.theme.Neon
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

private data class CommandCenterTelemetry(
    val adbConnected: Boolean = false,
    /** Label of the active backend: "LOCAL CORE", "SHIZUKU", "IADB", or "OFFLINE" */
    val connectionMode: String = "OFFLINE",
    val cpuLoad: Int? = null,
    val ramUsedPercent: Int = 0,
    val ramUsedGb: Float = 0f,
    val ramTotalGb: Float = 0f,
    val storageFreeGb: Float = 0f,
    val storageTotalGb: Float = 0f,
    val batteryPercent: Int = 0,
    val temperatureC: Float = 0f,
    val currentHz: Int = 0,
    val maxHz: Int = 0,
    val network: String = "OFFLINE",
    val overlayReady: Boolean = false,
    val sessionReady: Boolean = false,
    val dndReady: Boolean = false,
    val shizukuAvailable: Boolean = false,
    val iadbAvailable: Boolean = false,
)

private val ReactorShape = androidx.compose.foundation.shape.RoundedCornerShape(16.dp)
private val ReactorSmall = androidx.compose.foundation.shape.RoundedCornerShape(12.dp)

/**
 * Main Game Nuke command center. The first screen is deliberately not a collection of generic
 * Android tweak cards: it shows session readiness, measured telemetry, and the few controls a
 * player needs before arming the floating gaming core.
 */
@Composable
fun DashboardScreen(
    adbManager: AdbManager,
    onOpenDevOptions: () -> Unit,
    onOpenGames: () -> Unit = {},
    onOpenCleaner: () -> Unit = {},
    onOpenMonitor: () -> Unit = {},
    onOpenSystemEditor: () -> Unit = {},
) {
    val context = LocalContext.current
    val gateway = remember(adbManager) { NukeGamingShellGateway(adbManager) }
    var telemetry by remember { mutableStateOf(CommandCenterTelemetry()) }
    var showConnectionDialog by remember { mutableStateOf(false) }
    var adBlockStatus by remember { mutableStateOf(AdBlockStatus()) }
    var showAdBlockDialog by remember { mutableStateOf(false) }
    var adBlockChecked by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        while (true) {
            telemetry = withContext(Dispatchers.IO) {
                readCommandCenterTelemetry(context, adbManager, gateway)
            }
            if (telemetry.adbConnected && !adBlockChecked) {
                adBlockChecked = true
                val status = withContext(Dispatchers.IO) {
                    NukeAdBlockDetector.checkStatus(context, adbManager)
                }
                adBlockStatus = status
                if (status.isDetected) {
                    showAdBlockDialog = true
                }
            }
            delay(2_500L)
        }
    }

    if (showConnectionDialog) {
        NukeConnectionSelectorDialog(
            telemetry = telemetry,
            adbManager = adbManager,
            onOpenDevOptions = onOpenDevOptions,
            onDismiss = { showConnectionDialog = false },
        )
    }

    if (showAdBlockDialog && adBlockStatus.isDetected) {
        NukeAdBlockDetectedDialog(
            status = adBlockStatus,
            adbManager = adbManager,
            onDismiss = { showAdBlockDialog = false },
        )
    }

    fun handleToolClick(action: () -> Unit) {
        if (adBlockStatus.isDetected) {
            showAdBlockDialog = true
            NukeToast.error(
                context,
                "AdBlock detected! Please disable your AdBlock / Private DNS to use core tools.",
                long = true
            )
        } else {
            action()
        }
    }

    val readyCount = listOf(
        telemetry.adbConnected,
        telemetry.overlayReady,
        telemetry.sessionReady,
        telemetry.dndReady,
    ).count { it }
    val readiness = readyCount / 4f

    LazyColumn(
        modifier = Modifier.fillMaxSize().background(Neon.Bg),
        contentPadding = PaddingValues(start = 14.dp, end = 14.dp, top = 12.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (adBlockStatus.isDetected) {
            item {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(androidx.compose.foundation.shape.RoundedCornerShape(12.dp))
                        .background(Color(0xFF281013))
                        .border(1.dp, Color(0xFFFF4B55), androidx.compose.foundation.shape.RoundedCornerShape(12.dp))
                        .clickable { showAdBlockDialog = true }
                        .padding(12.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(34.dp)
                                .clip(androidx.compose.foundation.shape.RoundedCornerShape(8.dp))
                                .background(Color(0xFFFF4B55).copy(alpha = 0.2f))
                                .border(0.8.dp, Color(0xFFFF4B55), androidx.compose.foundation.shape.RoundedCornerShape(8.dp)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(Icons.Rounded.Warning, contentDescription = null, tint = Color(0xFFFF4B55), modifier = Modifier.size(18.dp))
                        }
                        Column(Modifier.weight(1f)) {
                            Text(
                                ("AdBlock Active · Tools Locked"),
                                color = Color(0xFFFF7A85),
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                letterSpacing = 0.3.sp
                            )
                            Spacer(Modifier.height(1.dp))
                            Text(
                                ("Filter: %s · Tap to resolve").format(adBlockStatus.detectedDnsSpecifier.ifBlank { "AdBlock" }),
                                color = Color(0xFFD6C2C4),
                                fontSize = 10.sp
                            )
                        }
                        Box(
                            modifier = Modifier
                                .clip(androidx.compose.foundation.shape.RoundedCornerShape(6.dp))
                                .background(Color(0xFFFF4B55))
                                .padding(horizontal = 10.dp, vertical = 5.dp)
                        ) {
                            Text(
                                ("RESOLVE"),
                                color = Color.White,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            }
        }

        item {
            ReactorCommandHero(
                telemetry = telemetry,
                readiness = readiness,
                readyCount = readyCount,
                onArm = { handleToolClick(onOpenGames) },
                onAdb = { showConnectionDialog = true },
            )
        }

        item {
            SectionRail(("LIVE ENGINE"), ("MEASURED TELEMETRY"))
            Spacer(Modifier.height(8.dp))
            DualGaugeDeck(telemetry)
        }

        item {
            TelemetryMatrix(telemetry)
        }

        item {
            SectionRail(("SESSION READINESS"), "$readyCount/4 ${("SYSTEM PATHS")}")
            Spacer(Modifier.height(8.dp))
            ReadinessDeck(
                context = context,
                telemetry = telemetry,
                onAdb = { showConnectionDialog = true },
            )
        }

        item {
            SectionRail(("NUKE DECK"), ("HIGH-VALUE CONTROLS"))
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CommandTile(
                    ("GAME SPACE"), ("Choose a game and arm the HUD"), Icons.Rounded.Gamepad,
                    Neon.Accent, Modifier.weight(1f), { handleToolClick(onOpenGames) },
                )
                CommandTile(
                    ("DEEP CLEAN"), ("Storage & memory maintenance"), Icons.Rounded.CleaningServices,
                    Color(0xFFFFB830), Modifier.weight(1f), { handleToolClick(onOpenCleaner) },
                )
            }
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth()) {
                CommandTile(
                    ("SYSTEM EDITOR"), ("Find & tweak parameters safely"), Icons.Rounded.Tune,
                    Color(0xFF00E5FF), Modifier.fillMaxWidth(), { handleToolClick(onOpenSystemEditor) },
                )
            }
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth()) {
                CommandTile(
                    ("DEVICE CONTROL"), if (telemetry.adbConnected) ("Connected via ${telemetry.connectionMode}") else ("Select connection method"),
                    Icons.Rounded.Adb, if (telemetry.adbConnected) Neon.Accent else Color(0xFFFF7A59),
                    Modifier.fillMaxWidth(),
                ) {
                    showConnectionDialog = true
                }
            }
        }

        item {
            SystemIntelligenceCard(telemetry)
        }
    }
}

@Composable
private fun ReactorCommandHero(
    telemetry: CommandCenterTelemetry,
    readiness: Float,
    readyCount: Int,
    onArm: () -> Unit,
    onAdb: () -> Unit,
) {
    val accent = if (telemetry.adbConnected) Neon.Accent else Color(0xFFFFB830)

    Box(
        Modifier.fillMaxWidth()
            .clip(ReactorShape)
            .background(
                Brush.verticalGradient(
                    listOf(
                        Color(0xFF0F1B16),
                        Color(0xFF08110D),
                    ),
                ),
            )
            .border(0.8.dp, accent.copy(alpha = 0.30f), ReactorShape)
            .padding(18.dp),
    ) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.size(54.dp)
                        .clip(androidx.compose.foundation.shape.RoundedCornerShape(14.dp))
                        .background(accent.copy(alpha = 0.12f))
                        .border(0.8.dp, accent.copy(alpha = 0.35f), androidx.compose.foundation.shape.RoundedCornerShape(14.dp)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.Rounded.Gamepad,
                        contentDescription = null,
                        tint = accent,
                        modifier = Modifier.size(28.dp),
                    )
                }
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        ("Command Center"),
                        color = Color.White,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 0.3.sp,
                    )
                    Spacer(Modifier.height(3.dp))
                    Text(
                        if (telemetry.adbConnected) "${telemetry.connectionMode} • ${("Ready")}" else ("Standard Mode Ready"),
                        color = accent,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        "Game Nuke v${BuildConfig.VERSION_NAME} • ${("Adaptive gaming optimization engine")}",
                        color = Neon.TextDim,
                        fontSize = 10.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            Spacer(Modifier.height(14.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            ("System Readiness"),
                            color = Neon.TextDim,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Medium,
                        )
                        Spacer(Modifier.weight(1f))
                        Text(
                            "$readyCount / 4",
                            color = accent,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                    Spacer(Modifier.height(6.dp))
                    SegmentedProgress(readiness, accent)
                }
            }

            Spacer(Modifier.height(14.dp))
            val activity = LocalContext.current.findActivity()
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                ReactorButton(
                    ("Launch Game Session"),
                    Icons.Rounded.PlayArrow,
                    Neon.Accent,
                    Modifier.weight(1.4f),
                ) {
                    if (activity != null) {
                        NukeAdManager.showInterstitial(activity) { onArm() }
                    } else {
                        onArm()
                    }
                }
                ReactorButton(
                    if (telemetry.adbConnected) ("Connected") else ("Connect"),
                    Icons.Rounded.DeveloperMode,
                    accent,
                    Modifier.weight(1f),
                    onAdb,
                )
            }
        }
    }
}

@Composable
private fun DualGaugeDeck(telemetry: CommandCenterTelemetry) {
    val cpu = telemetry.cpuLoad ?: 0
    val ram = telemetry.ramUsedPercent
    Box(
        Modifier.fillMaxWidth().clip(ReactorShape)
            .background(
                Brush.verticalGradient(
                    listOf(
                        Color(0xFF0F1814),
                        Color(0xFF070E0B),
                    )
                )
            )
            .border(0.8.dp, Color(0xFF1E3A30), ReactorShape)
            .padding(horizontal = 14.dp, vertical = 16.dp),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            AnimatedSpeedometer(
                label = ("CPU Load"),
                value = cpu,
                valueText = telemetry.cpuLoad?.let { "$it%" } ?: "--",
                accent = Color(0xFF35F2FF),
                modifier = Modifier.weight(1f),
            )
            Column(Modifier.width(96.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Box(
                    modifier = Modifier
                        .clip(androidx.compose.foundation.shape.RoundedCornerShape(6.dp))
                        .background(if (telemetry.temperatureC > 43f) Color(0xFFFF5D67).copy(alpha = 0.15f) else Neon.Accent.copy(alpha = 0.12f))
                        .border(0.8.dp, if (telemetry.temperatureC > 43f) Color(0xFFFF5D67).copy(alpha = 0.4f) else Neon.Accent.copy(alpha = 0.3f), androidx.compose.foundation.shape.RoundedCornerShape(6.dp))
                        .padding(horizontal = 8.dp, vertical = 3.dp),
                ) {
                    Text(
                        if (telemetry.temperatureC > 43f) ("HIGH TEMP") else ("OPTIMAL"),
                        color = if (telemetry.temperatureC > 43f) Color(0xFFFF5D67) else Neon.Accent,
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.Center,
                    )
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    "${telemetry.currentHz} Hz",
                    color = Color.White,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    ("Refresh Rate"),
                    color = Neon.TextDim,
                    fontSize = 9.sp,
                )
            }
            AnimatedSpeedometer(
                label = ("RAM Usage"),
                value = ram,
                valueText = "$ram%",
                accent = Neon.Accent,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun AnimatedSpeedometer(
    label: String,
    value: Int,
    valueText: String,
    accent: Color,
    modifier: Modifier = Modifier,
) {
    val animated by animateFloatAsState(
        targetValue = value.coerceIn(0, 100).toFloat(),
        animationSpec = tween(850, easing = FastOutSlowInEasing),
        label = "gauge-$label",
    )
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(108.dp), contentAlignment = Alignment.Center) {
            Canvas(Modifier.fillMaxSize()) {
                val stroke = 8.dp.toPx()
                val start = 140f
                val sweep = 260f
                drawArc(
                    color = Color(0xFF14221C), startAngle = start, sweepAngle = sweep,
                    useCenter = false, style = Stroke(stroke, cap = StrokeCap.Round),
                )
                drawArc(
                    brush = Brush.sweepGradient(listOf(accent.copy(alpha = .30f), accent)),
                    startAngle = start, sweepAngle = sweep * (animated / 100f),
                    useCenter = false, style = Stroke(stroke, cap = StrokeCap.Round),
                )
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(valueText, color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                Text(label, color = Neon.TextDim, fontSize = 10.sp, fontWeight = FontWeight.Medium)
            }
        }
    }
}

@Composable
private fun TelemetryMatrix(telemetry: CommandCenterTelemetry) {
    Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
            MetricTile(("DISPLAY"), "${telemetry.currentHz}/${telemetry.maxHz}Hz", Icons.Rounded.Speed, Color(0xFF35F2FF), Modifier.weight(1f))
            MetricTile(("THERMAL"), "${"%.1f".format(telemetry.temperatureC)}°C", Icons.Rounded.Thermostat, if (telemetry.temperatureC > 43f) Color(0xFFFF5D67) else Neon.Accent, Modifier.weight(1f))
            MetricTile(("BATTERY"), "${telemetry.batteryPercent}%", Icons.Rounded.BatteryChargingFull, Color(0xFFFFB830), Modifier.weight(1f))
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
            MetricTile(("RAM"), "${"%.1f".format(telemetry.ramUsedGb)}/${"%.1f".format(telemetry.ramTotalGb)}G", Icons.Rounded.Memory, Neon.Accent, Modifier.weight(1f))
            MetricTile(("STORAGE"), "${"%.1f".format(telemetry.storageFreeGb)}G ${("FREE")}", Icons.Rounded.Storage, Color(0xFF9877FF), Modifier.weight(1f))
            MetricTile(("NETWORK"), telemetry.network, Icons.Rounded.Wifi, Color(0xFF35F2FF), Modifier.weight(1f))
        }
    }
}

@Composable
private fun ReadinessDeck(context: Context, telemetry: CommandCenterTelemetry, onAdb: () -> Unit) {
    val connLabel = telemetry.connectionMode
    val connDetail = when {
        telemetry.adbConnected -> ("via $connLabel")
        telemetry.iadbAvailable -> ("iAdb ready · tap to connect")
        telemetry.shizukuAvailable -> ("Shizuku ready · tap to connect")
        else -> ("Wireless ADB / Shizuku / iAdb")
    }
    val rows = listOf(
        ReadinessItem(("DEVICE CONTROL"), telemetry.adbConnected, connDetail, Icons.Rounded.Tune, onAdb),
        ReadinessItem(("FLOATING HUD"), telemetry.overlayReady, ("Overlay permission"), Icons.Rounded.Layers) {
            runCatching { context.startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, android.net.Uri.parse("package:${context.packageName}"))) }
        },
        ReadinessItem(("SESSION TRACK"), telemetry.sessionReady, ("Gaming session active"), Icons.Rounded.Security, onAdb),
        ReadinessItem(("GAME FOCUS"), telemetry.dndReady, ("DND policy access"), Icons.Rounded.NotificationsOff) {
            runCatching { context.startActivity(Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS)) }
        },
    )
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        rows.chunked(2).forEach { pair ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                pair.forEach { ReadinessTile(it, Modifier.weight(1f)) }
                if (pair.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

private data class ReadinessItem(val title: String, val ready: Boolean, val detail: String, val icon: ImageVector, val onClick: () -> Unit)

@Composable
private fun ReadinessTile(item: ReadinessItem, modifier: Modifier = Modifier) {
    val accent = if (item.ready) Neon.Accent else Color(0xFFFFB830)
    Row(
        modifier.clip(ReactorSmall).background(Color(0xFF08110E))
            .border(1.dp, accent.copy(alpha = .28f), ReactorSmall)
            .nukePressFeedback().clickable(onClick = item.onClick).padding(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(30.dp).clip(ReactorSmall).background(accent.copy(alpha = .10f)), contentAlignment = Alignment.Center) {
            Icon(item.icon, null, tint = accent, modifier = Modifier.size(17.dp))
        }
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f)) {
            Text(item.title, color = Color.White, fontSize = 9.sp, fontWeight = FontWeight.Black, maxLines = 1)
            Text(item.detail, color = Neon.TextDim, fontSize = 7.5.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Box(Modifier.size(6.dp).clip(CircleShape).background(accent))
    }
}

@Composable
private fun CommandTile(title: String, detail: String, icon: ImageVector, accent: Color, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Column(
        modifier.height(104.dp).clip(ReactorSmall)
            .background(Brush.verticalGradient(listOf(accent.copy(alpha = .09f), Color(0xFF0A1512), Color(0xFF020705))))
            .border(1.dp, accent.copy(alpha = .32f), ReactorSmall)
            .nukePressFeedback().clickable(onClick = onClick).padding(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(32.dp).clip(ReactorSmall).background(accent.copy(alpha = .10f)), contentAlignment = Alignment.Center) {
                Icon(icon, null, tint = accent, modifier = Modifier.size(18.dp))
            }
            Spacer(Modifier.weight(1f))
            Text(("OPEN >"), color = accent, fontSize = 7.sp, fontWeight = FontWeight.Black, fontFamily = FontFamily.Monospace)
        }
        Spacer(Modifier.height(9.dp))
        Text(title, color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Black, letterSpacing = .4.sp)
        Spacer(Modifier.height(2.dp))
        Text(detail, color = Neon.TextDim, fontSize = 8.sp, lineHeight = 10.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun SystemIntelligenceCard(telemetry: CommandCenterTelemetry) {
    val accent = if (telemetry.adbConnected) Neon.Accent else Color(0xFFFFB830)
    Column(
        Modifier.fillMaxWidth().clip(ReactorShape).background(Color(0xFF050B09))
            .border(1.dp, Color(0xFF163B31), ReactorShape).padding(14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.Tune, null, tint = accent, modifier = Modifier.size(19.dp))
            Spacer(Modifier.width(8.dp))
            Text(("SESSION INTELLIGENCE"), color = Color.White, fontWeight = FontWeight.Black, fontSize = 11.sp, letterSpacing = 1.4.sp)
            Spacer(Modifier.weight(1f))
            Text(if (telemetry.adbConnected) ("EXTENDED") else ("STANDARD"), color = accent, fontSize = 8.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.height(8.dp))
        Text(
            if (telemetry.adbConnected) {
                ("Device control is active. Game Nuke can use supported optimizations, verify results, measure telemetry, and restore session-owned changes.")
            } else {
                ("Game Nuke still monitors RAM, temperature, battery, display, and session status. Connect advanced control when the device supports additional optimizations.")
            },
            color = Neon.TextDim, fontSize = 10.sp, lineHeight = 14.sp,
        )
    }
}

@Composable
private fun MetricTile(label: String, value: String, icon: ImageVector, accent: Color, modifier: Modifier = Modifier) {
    Column(
        modifier.clip(ReactorSmall).background(Color(0xFF0C1613)).border(0.8.dp, Color(0xFF1B382F), ReactorSmall).padding(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, tint = accent, modifier = Modifier.size(14.dp))
            Spacer(Modifier.width(6.dp))
            Text(label, color = Neon.TextDim, fontSize = 10.sp, fontWeight = FontWeight.Medium, maxLines = 1)
        }
        Spacer(Modifier.height(5.dp))
        Text(value, color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun ReactorButton(text: String, icon: ImageVector, accent: Color, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Row(
        modifier.height(44.dp).clip(ReactorSmall).background(accent.copy(alpha = .14f))
            .border(0.8.dp, accent.copy(alpha = .40f), ReactorSmall).nukePressFeedback().clickable(onClick = onClick).padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        Icon(icon, null, tint = accent, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Text(text, color = accent, fontSize = 11.5.sp, fontWeight = FontWeight.Bold, maxLines = 1)
    }
}

@Composable
private fun SectionRail(title: String, meta: String) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.width(3.dp).height(14.dp).clip(androidx.compose.foundation.shape.RoundedCornerShape(1.dp)).background(Neon.Accent))
        Spacer(Modifier.width(8.dp))
        Text(title, color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.4.sp)
        Spacer(Modifier.width(10.dp))
        Box(Modifier.weight(1f).height(1.dp).background(Color(0xFF1E352C)))
        Spacer(Modifier.width(10.dp))
        Text(meta, color = Neon.TextDim, fontSize = 10.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun SegmentedProgress(progress: Float, accent: Color) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        repeat(12) { index ->
            val on = (index + 1) / 12f <= progress + .02f
            Box(Modifier.weight(1f).height(4.dp).clip(androidx.compose.foundation.shape.RoundedCornerShape(2.dp)).background(if (on) accent else Color(0xFF1A2824)))
        }
    }
}

private fun readCommandCenterTelemetry(
    context: Context,
    adbManager: AdbManager,
    gateway: NukeGamingShellGateway,
): CommandCenterTelemetry {
    val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
    val info = ActivityManager.MemoryInfo()
    runCatching { am?.getMemoryInfo(info) }
    val totalRam = info.totalMem.coerceAtLeast(0L)
    val usedRam = (totalRam - info.availMem.coerceAtLeast(0L)).coerceAtLeast(0L)
    val ramUsedPercent = if (totalRam > 0L) ((usedRam.toDouble() / totalRam.toDouble()) * 100.0).roundToInt().coerceIn(0, 100) else 0

    val stat = runCatching { StatFs(Environment.getDataDirectory().path) }.getOrNull()
    val storageFree = stat?.availableBytes ?: 0L
    val storageTotal = stat?.totalBytes ?: 0L

    val battery = runCatching { androidx.core.content.ContextCompat.registerReceiver(context, null, IntentFilter(Intent.ACTION_BATTERY_CHANGED), androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED) }.getOrNull()
    val batteryLevel = battery?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
    val batteryScale = battery?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
    val batteryPercent = if (batteryLevel >= 0 && batteryScale > 0) (batteryLevel * 100f / batteryScale).roundToInt().coerceIn(0, 100) else 0
    val temperature = (battery?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) ?: 0) / 10f

    val wm = context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager
    @Suppress("DEPRECATION")
    val display = wm?.defaultDisplay
    val currentHz = runCatching { display?.refreshRate?.roundToInt() ?: 0 }.getOrDefault(0)
    val maxHz = runCatching { display?.supportedModes?.maxOfOrNull { it.refreshRate }?.roundToInt() ?: currentHz }.getOrDefault(currentHz)

    val connectivity = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
    val networkLabel = runCatching {
        val cm = connectivity ?: return@runCatching "OFFLINE"
        val network = cm.activeNetwork ?: return@runCatching "OFFLINE"
        val caps = cm.getNetworkCapabilities(network) ?: return@runCatching "ONLINE"
        when {
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "WIFI"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "MOBILE"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "LAN"
            else -> "ONLINE"
        }
    }.getOrDefault("OFFLINE")

    val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
    val adbConnected = runCatching { NukeConnectionManager.isConnected() }.getOrDefault(false)
    val connectionMode = runCatching { NukeConnectionManager.connectionLabel() }.getOrDefault("OFFLINE")
    val shizukuAvailable = runCatching { NukeShizukuBridge.isRunning() }.getOrDefault(false)
    val iadbAvailable = runCatching { NukeIadbBridge.isRunning() }.getOrDefault(false)
    val cpu = runCatching { if (adbConnected) gateway.readCpuLoadPercent() else null }.getOrNull() ?: NukeLocalCpuSampler.readPercent()

    return CommandCenterTelemetry(
        adbConnected = adbConnected,
        connectionMode = connectionMode,
        cpuLoad = cpu,
        ramUsedPercent = ramUsedPercent,
        ramUsedGb = usedRam / 1_073_741_824f,
        ramTotalGb = totalRam / 1_073_741_824f,
        storageFreeGb = storageFree / 1_073_741_824f,
        storageTotalGb = storageTotal / 1_073_741_824f,
        batteryPercent = batteryPercent,
        temperatureC = temperature,
        currentHz = currentHz,
        maxHz = maxHz,
        network = networkLabel,
        overlayReady = Settings.canDrawOverlays(context),
        sessionReady = NukeRuntimeState.state.value.overlayRunning ||
            context.getSharedPreferences("NukePrefs", Context.MODE_PRIVATE).safeBoolean("overlay_active_session", false),
        dndReady = notificationManager?.isNotificationPolicyAccessGranted == true,
        shizukuAvailable = shizukuAvailable,
        iadbAvailable = iadbAvailable,
    )
}

@Composable
private fun NukeConnectionSelectorDialog(
    telemetry: CommandCenterTelemetry,
    adbManager: AdbManager,
    onOpenDevOptions: () -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val isShizukuInstalled = remember { NukeShizukuBridge.isInstalled(context) }
    val isShizukuRunning = remember { NukeShizukuBridge.isRunning() }
    val isShizukuConnected = remember { NukeShizukuBridge.isConnected() }
    val isShizukuPermitted = remember { NukeShizukuBridge.hasPermission() }

    val isIadbInstalled = remember { NukeIadbBridge.isInstalled(context) }
    val isIadbRunning = remember { NukeIadbBridge.isRunning() }
    val isIadbConnected = remember { NukeIadbBridge.isConnected() }
    val isIadbPermitted = remember { NukeIadbBridge.hasPermission() }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Box(
            modifier = Modifier
                .widthIn(max = 440.dp)
                .fillMaxWidth(0.92f)
                .heightIn(max = 600.dp)
                .clip(androidx.compose.foundation.shape.RoundedCornerShape(20.dp))
                .background(
                    Brush.verticalGradient(
                        listOf(
                            Color(0xFF0F1E19),
                            Color(0xFF08120F),
                            Color(0xFF030706),
                        )
                    )
                )
                .border(
                    0.8.dp,
                    Brush.verticalGradient(listOf(Neon.Accent.copy(alpha = 0.5f), Color(0xFF1B493C))),
                    androidx.compose.foundation.shape.RoundedCornerShape(20.dp)
                )
                .padding(18.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                // Header
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            ("DEVICE CONTROL"),
                            color = Color.White,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Black,
                            letterSpacing = 1.sp,
                            maxLines = 1
                        )
                        Spacer(Modifier.height(4.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                Modifier
                                    .clip(androidx.compose.foundation.shape.RoundedCornerShape(6.dp))
                                    .background(if (telemetry.adbConnected) Neon.Accent.copy(alpha = 0.15f) else Color(0xFFFF7A59).copy(alpha = 0.15f))
                                    .border(0.8.dp, if (telemetry.adbConnected) Neon.Accent.copy(alpha = 0.4f) else Color(0xFFFF7A59).copy(alpha = 0.4f), androidx.compose.foundation.shape.RoundedCornerShape(6.dp))
                                    .padding(horizontal = 7.dp, vertical = 2.dp)
                            ) {
                                Text(
                                    telemetry.connectionMode,
                                    color = if (telemetry.adbConnected) Neon.Accent else Color(0xFFFF7A59),
                                    fontSize = 8.5.sp,
                                    fontWeight = FontWeight.Bold,
                                )
                            }
                            Spacer(Modifier.width(6.dp))
                            Text(
                                if (telemetry.adbConnected) ("Connected & Active") else ("Select Elevated Bridge"),
                                color = Neon.TextDim,
                                fontSize = 9.5.sp,
                                maxLines = 1
                            )
                        }
                    }
                    IconButton(
                        onClick = onDismiss,
                        modifier = (Modifier.size(32.dp)).nukePressFeedback()) {
                        Icon(Icons.Rounded.Close, contentDescription = "Close", tint = Neon.TextDim)
                    }
                }

                Box(Modifier.fillMaxWidth().height(1.dp).background(Color(0xFF1B493C)))

                // Option 1: Connect with iAdb (Primary Recommended)
                val iadbBadge = when {
                    isIadbConnected -> ("ACTIVE")
                    !isIadbInstalled -> ("NOT INSTALLED")
                    !isIadbRunning -> ("OFFLINE")
                    !isIadbPermitted -> ("AUTH NEEDED")
                    else -> ("READY")
                }
                val iadbBadgeColor = when {
                    isIadbConnected -> Neon.Accent
                    !isIadbInstalled -> Color(0xFFFF5D67)
                    !isIadbRunning || !isIadbPermitted -> Color(0xFFFFB830)
                    else -> Color(0xFF35F2FF)
                }
                ConnectionMethodCard(
                    title = ("Connect with iAdb"),
                    tag = ("RECOMMENDED"),
                    badge = iadbBadge,
                    badgeColor = iadbBadgeColor,
                    description = ("High-stability lightweight engine (Android 11+) with no PC or Wi-Fi dependency after initial setup."),
                    icon = Icons.Rounded.Bolt,
                    iconTint = Neon.Accent,
                    onClick = {
                        when {
                            isIadbConnected -> {
                                NukeConnectionManager.preferredBackend = NukeConnectionManager.Backend.IADB
                                NukeToast.success(context, ("iAdb is connected and active."))
                                onDismiss()
                            }
                            !isIadbInstalled -> {
                                NukeToast.error(context, ("iAdb is not installed. Opening download page..."))
                                NukeIadbBridge.launchApp(context)
                            }
                            !isIadbRunning -> {
                                NukeToast.error(context, ("iAdb service is offline. Please launch iAdb and start the service first."))
                                NukeIadbBridge.launchApp(context)
                            }
                            !isIadbPermitted -> {
                                NukeToast.success(context, ("Requesting iAdb authorization..."))
                                NukeIadbBridge.requestPermission()
                                onDismiss()
                            }
                            else -> {
                                NukeConnectionManager.preferredBackend = NukeConnectionManager.Backend.IADB
                                NukeIadbBridge.bindUserServiceNow()
                                NukeToast.success(context, ("Connecting to iAdb..."))
                                onDismiss()
                            }
                        }
                    }
                )

                // Option 2: Connect with Shizuku (Alternative)
                val shizukuBadge = when {
                    isShizukuConnected -> ("ACTIVE")
                    !isShizukuInstalled -> ("NOT INSTALLED")
                    !isShizukuRunning -> ("OFFLINE")
                    !isShizukuPermitted -> ("AUTH NEEDED")
                    else -> ("READY")
                }
                val shizukuBadgeColor = when {
                    isShizukuConnected -> Neon.Accent
                    !isShizukuInstalled -> Color(0xFFFF5D67)
                    !isShizukuRunning || !isShizukuPermitted -> Color(0xFFFFB830)
                    else -> Color(0xFF35F2FF)
                }
                ConnectionMethodCard(
                    title = ("Connect with Shizuku"),
                    tag = ("ALTERNATIVE"),
                    badge = shizukuBadge,
                    badgeColor = shizukuBadgeColor,
                    description = ("Privileged system daemon — stays active permanently across Wi-Fi toggles and gaming sessions."),
                    icon = Icons.Rounded.AdminPanelSettings,
                    iconTint = Color(0xFF35F2FF),
                    onClick = {
                        when {
                            isShizukuConnected -> {
                                NukeConnectionManager.preferredBackend = NukeConnectionManager.Backend.SHIZUKU
                                NukeToast.success(context, ("Shizuku is connected and active."))
                                onDismiss()
                            }
                            !isShizukuInstalled -> {
                                NukeToast.error(context, ("Shizuku is not installed. Opening download page..."))
                                NukeShizukuBridge.launchApp(context)
                            }
                            !isShizukuRunning -> {
                                NukeToast.error(context, ("Shizuku service is offline. Please open Shizuku and start the service first."))
                                NukeShizukuBridge.launchApp(context)
                            }
                            !isShizukuPermitted -> {
                                NukeToast.success(context, ("Requesting Shizuku authorization..."))
                                NukeShizukuBridge.requestPermission()
                                onDismiss()
                            }
                            else -> {
                                NukeConnectionManager.preferredBackend = NukeConnectionManager.Backend.SHIZUKU
                                NukeShizukuBridge.bindUserServiceNow()
                                NukeToast.success(context, ("Connecting to Shizuku..."))
                                onDismiss()
                            }
                        }
                    }
                )

                // Option 3: Wireless ADB Pairing (Manual)
                val nativeBadge = when {
                    telemetry.connectionMode == "LOCAL CORE" -> ("CONNECTED")
                    telemetry.adbConnected && telemetry.connectionMode != "SHIZUKU" && telemetry.connectionMode != "IADB" -> ("CONNECTED")
                    else -> ("STANDBY")
                }
                val nativeBadgeColor = when {
                    telemetry.connectionMode == "LOCAL CORE" || (telemetry.adbConnected && telemetry.connectionMode != "SHIZUKU" && telemetry.connectionMode != "IADB") -> Neon.Accent
                    else -> Color(0xFFFFB830)
                }
                ConnectionMethodCard(
                    title = ("Wireless ADB Pairing"),
                    tag = ("MANUAL"),
                    badge = nativeBadge,
                    badgeColor = nativeBadgeColor,
                    description = ("Manual device link via Developer Options (Wi-Fi network + 6-digit pairing code)."),
                    icon = Icons.Rounded.WifiTethering,
                    iconTint = Color(0xFFFFB830),
                    onClick = {
                        if (telemetry.network == "OFFLINE") {
                            NukeToast.error(context, ("Please enable Wi-Fi for Wireless ADB pairing."))
                        } else {
                            NukeConnectionManager.preferredBackend = NukeConnectionManager.Backend.DAEMON
                            adbManager.startNetworkScanner()
                            onOpenDevOptions()
                            onDismiss()
                        }
                    }
                )
            }
        }
    }
}

@Composable
private fun ConnectionMethodCard(
    title: String,
    tag: String? = null,
    badge: String,
    badgeColor: Color,
    description: String,
    icon: ImageVector,
    iconTint: Color,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(androidx.compose.foundation.shape.RoundedCornerShape(14.dp))
            .background(Color(0xFF0C1613))
            .border(0.8.dp, Color(0xFF1E3A31), androidx.compose.foundation.shape.RoundedCornerShape(14.dp))
            .nukePressFeedback().clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                Modifier
                    .size(38.dp)
                    .clip(androidx.compose.foundation.shape.RoundedCornerShape(10.dp))
                    .background(iconTint.copy(alpha = 0.12f))
                    .border(0.8.dp, iconTint.copy(alpha = 0.35f), androidx.compose.foundation.shape.RoundedCornerShape(10.dp)),
                contentAlignment = Alignment.Center
            ) {
                Icon(icon, contentDescription = null, tint = iconTint, modifier = Modifier.size(20.dp))
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        title,
                        color = Color.White,
                        fontSize = 12.5.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    Spacer(Modifier.width(6.dp))
                    Box(
                        Modifier
                            .clip(androidx.compose.foundation.shape.RoundedCornerShape(6.dp))
                            .background(badgeColor.copy(alpha = 0.15f))
                            .border(0.8.dp, badgeColor.copy(alpha = 0.6f), androidx.compose.foundation.shape.RoundedCornerShape(6.dp))
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                    ) {
                        Text(
                            badge,
                            color = badgeColor,
                            fontSize = 8.5.sp,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1
                        )
                    }
                }
                if (tag != null) {
                    Spacer(Modifier.height(3.dp))
                    Box(
                        Modifier
                            .clip(androidx.compose.foundation.shape.RoundedCornerShape(4.dp))
                            .background(iconTint.copy(alpha = 0.18f))
                            .padding(horizontal = 5.dp, vertical = 1.5.dp)
                    ) {
                        Text(tag, color = iconTint, fontSize = 7.5.sp, fontWeight = FontWeight.Bold)
                    }
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    description,
                    color = Neon.TextDim,
                    fontSize = 10.sp,
                    lineHeight = 14.sp
                )
            }
        }
    }
}
