package com.neon.gametweak.ui.screens

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
import androidx.compose.foundation.shape.CutCornerShape
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

private val ReactorShape = CutCornerShape(topStart = 24.dp, topEnd = 5.dp, bottomStart = 5.dp, bottomEnd = 24.dp)
private val ReactorSmall = CutCornerShape(topStart = 12.dp, topEnd = 2.dp, bottomStart = 2.dp, bottomEnd = 12.dp)

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
            delay(1_350L)
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
        val check = NukeAdBlockDetector.checkStatus(context, adbManager)
        if (check.isDetected || adBlockStatus.isDetected) {
            if (check.isDetected) adBlockStatus = check
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
                        .clip(CutCornerShape(topStart = 10.dp, bottomEnd = 10.dp))
                        .background(Color(0xFF281013))
                        .border(1.dp, Color(0xFFFF4B55), CutCornerShape(topStart = 10.dp, bottomEnd = 10.dp))
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
                                .clip(CutCornerShape(6.dp))
                                .background(Color(0xFFFF4B55).copy(alpha = 0.2f))
                                .border(0.8.dp, Color(0xFFFF4B55), CutCornerShape(6.dp)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(Icons.Rounded.Warning, contentDescription = null, tint = Color(0xFFFF4B55), modifier = Modifier.size(18.dp))
                        }
                        Column(Modifier.weight(1f)) {
                            Text(
                                Tx.t("ADBLOCK AKTIF · TOOLS TERKUNCI", "ADBLOCK ACTIVE · TOOLS LOCKED"),
                                color = Color(0xFFFF7A85),
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Black,
                                letterSpacing = 0.5.sp
                            )
                            Spacer(Modifier.height(1.dp))
                            Text(
                                Tx.t("Filter: %s · Klik DISABLE untuk pulihkan", "Filter: %s · Tap DISABLE to restore").format(adBlockStatus.detectedDnsSpecifier.ifBlank { "AdBlock" }),
                                color = Color(0xFFD6C2C4),
                                fontSize = 9.sp
                            )
                        }
                        Box(
                            modifier = Modifier
                                .clip(CutCornerShape(4.dp))
                                .background(Color(0xFFFF4B55))
                                .padding(horizontal = 8.dp, vertical = 4.dp)
                        ) {
                            Text(
                                Tx.t("DISABLE", "DISABLE"),
                                color = Color.White,
                                fontSize = 9.sp,
                                fontWeight = FontWeight.Black
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
            SectionRail(Tx.t("LIVE ENGINE", "LIVE ENGINE"), Tx.t("TELEMETRI SISTEM", "MEASURED TELEMETRY"))
            Spacer(Modifier.height(8.dp))
            DualGaugeDeck(telemetry)
        }

        item {
            TelemetryMatrix(telemetry)
        }

        item {
            SectionRail(Tx.t("KESIAPAN SESI", "SESSION READINESS"), "$readyCount/4 ${Tx.t("JALUR SISTEM", "SYSTEM PATHS")}")
            Spacer(Modifier.height(8.dp))
            ReadinessDeck(
                context = context,
                telemetry = telemetry,
                onAdb = { showConnectionDialog = true },
            )
        }

        item {
            SectionRail(Tx.t("KONTROL UTAMA", "NUKE DECK"), Tx.t("AKSES CEPAT", "HIGH-VALUE CONTROLS"))
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CommandTile(
                    Tx.t("GAME SPACE", "GAME SPACE"), Tx.t("Pilih game dan aktifkan HUD", "Choose a game and arm the HUD"), Icons.Rounded.Gamepad,
                    Neon.Accent, Modifier.weight(1f), { handleToolClick(onOpenGames) },
                )
                CommandTile(
                    Tx.t("DEEP CLEAN", "DEEP CLEAN"), Tx.t("Persiapan penyimpanan & memori", "Storage + memory preparation"), Icons.Rounded.CleaningServices,
                    Color(0xFFFFC857), Modifier.weight(1f), { handleToolClick(onOpenCleaner) },
                )
            }
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CommandTile(
                    Tx.t("SYSTEM MONITOR", "SYSTEM MONITOR"), Tx.t("Manajemen proses & memori", "Processes and memory pressure"), Icons.Rounded.Memory,
                    Color(0xFF35F2FF), Modifier.weight(1f), { handleToolClick(onOpenMonitor) },
                )
                CommandTile(
                    Tx.t("DEVICE CONTROL", "DEVICE CONTROL"), if (telemetry.adbConnected) Tx.t("Terhubung via ${telemetry.connectionMode}", "Connected via ${telemetry.connectionMode}") else Tx.t("Pilih metode koneksi", "Select connection method"),
                    Icons.Rounded.Adb, if (telemetry.adbConnected) Neon.Accent else Color(0xFFFF7A59),
                    Modifier.weight(1f),
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
    val infinite = rememberInfiniteTransition(label = "reactor")
    val pulse by infinite.animateFloat(
        0.44f, 0.72f,
        animationSpec = infiniteRepeatable(tween(2400, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "reactorPulse",
    )
    val accent = if (telemetry.adbConnected) Neon.Accent else Color(0xFFFFC857)

    Box(
        Modifier.fillMaxWidth()
            .clip(ReactorShape)
            .background(
                Brush.horizontalGradient(
                    listOf(
                        accent.copy(alpha = 0.18f),
                        Color(0xFF0B1A15),
                        Color(0xFF040A08),
                        Color(0xFF10101A),
                    ),
                ),
            )
            .border(1.dp, accent.copy(alpha = 0.42f + pulse * 0.26f), ReactorShape)
            .padding(16.dp),
    ) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.size(62.dp)
                        .clip(CutCornerShape(topStart = 19.dp, topEnd = 2.dp, bottomStart = 2.dp, bottomEnd = 19.dp))
                        .background(Brush.radialGradient(listOf(accent.copy(alpha = 0.28f + pulse * .12f), Color(0xFF07100D), Color(0xFF020504))))
                        .border(1.dp, accent.copy(alpha = .58f), ReactorSmall),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("GN", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Black)
                        Text("CORE", color = accent, fontSize = 7.sp, fontFamily = FontFamily.Monospace, letterSpacing = 1.4.sp)
                    }
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(Tx.t("PUSAT KOMANDO NUKLIR", "NUCLEAR COMMAND CENTER"), color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Black, letterSpacing = 1.2.sp)
                    Spacer(Modifier.height(2.dp))
                    Text(
                        if (telemetry.adbConnected) "${telemetry.connectionMode} ${Tx.t("SIAP", "READY")}" else Tx.t("KONTROL STANDAR SIAP", "STANDARD CONTROL READY"),
                        color = accent,
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace,
                        letterSpacing = .8.sp,
                    )
                    Spacer(Modifier.height(5.dp))
                    Text(
                        "Game Nuke v${BuildConfig.VERSION_NAME} • ${Tx.t("kontrol gaming adaptif", "adaptive gaming control")}",
                        color = Neon.TextDim,
                        fontSize = 10.sp,
                        maxLines = 2,
                    )
                }
            }

            Spacer(Modifier.height(14.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(Tx.t("KESIAPAN", "READINESS"), color = Neon.TextDim, fontSize = 8.sp, fontFamily = FontFamily.Monospace, letterSpacing = 1.5.sp)
                        Spacer(Modifier.weight(1f))
                        Text("$readyCount / 4", color = accent, fontSize = 10.sp, fontWeight = FontWeight.Black, fontFamily = FontFamily.Monospace)
                    }
                    Spacer(Modifier.height(5.dp))
                    SegmentedProgress(readiness, accent)
                }
            }

            Spacer(Modifier.height(12.dp))
            val activity = LocalContext.current.findActivity()
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ReactorButton(
                    Tx.t("MULAI SESI GAME", "ARM GAME SESSION"),
                    Icons.Rounded.PlayArrow,
                    Neon.Accent,
                    Modifier.weight(1.45f),
                ) {
                    if (activity != null) {
                        NukeAdManager.showInterstitial(activity) { onArm() }
                    } else {
                        onArm()
                    }
                }
                ReactorButton(
                    if (telemetry.adbConnected) Tx.t("KONTROL AKTIF", "CONTROL READY") else Tx.t("HUBUNGKAN", "CONNECT"),
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
            .background(Brush.verticalGradient(listOf(Color(0xFF0B1814), Color(0xFF050908))))
            .border(1.dp, Color(0xFF1A493C), ReactorShape)
            .padding(horizontal = 12.dp, vertical = 13.dp),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            AnimatedSpeedometer(
                label = Tx.t("BEBAN CPU", "CPU LOAD"),
                value = cpu,
                valueText = telemetry.cpuLoad?.let { "$it%" } ?: "--",
                accent = Color(0xFF35F2FF),
                modifier = Modifier.weight(1f),
            )
            Column(Modifier.width(92.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(Tx.t("REAKTOR", "REACTOR"), color = Neon.TextDim, fontSize = 7.sp, fontFamily = FontFamily.Monospace, letterSpacing = 1.5.sp)
                Spacer(Modifier.height(4.dp))
                Text(
                    if (telemetry.temperatureC > 43f) Tx.t("PROTEKSI\nSUHU", "THERMAL\nGUARD") else Tx.t("SISTEM\nNOMINAL", "SYSTEM\nNOMINAL"),
                    color = if (telemetry.temperatureC > 43f) Color(0xFFFF5D67) else Neon.Accent,
                    fontSize = 12.sp,
                    textAlign = TextAlign.Center,
                    fontWeight = FontWeight.Black,
                    lineHeight = 14.sp,
                )
                Spacer(Modifier.height(4.dp))
                Text("${telemetry.currentHz} / ${telemetry.maxHz} HZ", color = Color.White, fontSize = 8.sp, fontFamily = FontFamily.Monospace)
            }
            AnimatedSpeedometer(
                label = Tx.t("PENGGUNAAN RAM", "RAM USAGE"),
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
        Box(Modifier.size(112.dp), contentAlignment = Alignment.Center) {
            Canvas(Modifier.fillMaxSize()) {
                val stroke = 7.dp.toPx()
                val start = 145f
                val sweep = 250f
                drawArc(
                    color = Color(0xFF1B2C27), startAngle = start, sweepAngle = sweep,
                    useCenter = false, style = Stroke(stroke, cap = StrokeCap.Round),
                )
                drawArc(
                    brush = Brush.sweepGradient(listOf(accent.copy(alpha = .25f), accent)),
                    startAngle = start, sweepAngle = sweep * (animated / 100f),
                    useCenter = false, style = Stroke(stroke, cap = StrokeCap.Round),
                )
                val radius = size.minDimension * .40f
                for (i in 0..10) {
                    val angle = Math.toRadians((start + sweep * (i / 10f)).toDouble())
                    val c = Offset(size.width / 2f, size.height / 2f)
                    val outer = Offset(c.x + cos(angle).toFloat() * radius, c.y + sin(angle).toFloat() * radius)
                    val innerR = radius - if (i % 5 == 0) 8.dp.toPx() else 5.dp.toPx()
                    val inner = Offset(c.x + cos(angle).toFloat() * innerR, c.y + sin(angle).toFloat() * innerR)
                    drawLine(if (i / 10f <= animated / 100f) accent.copy(alpha = .78f) else Color(0xFF355047), inner, outer, strokeWidth = 1.2.dp.toPx())
                }
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(valueText, color = accent, fontSize = 21.sp, fontWeight = FontWeight.Black, fontFamily = FontFamily.Monospace)
                Text(label, color = Neon.TextDim, fontSize = 7.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace, letterSpacing = 1.1.sp)
            }
        }
    }
}

@Composable
private fun TelemetryMatrix(telemetry: CommandCenterTelemetry) {
    Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
            MetricTile(Tx.t("LAYAR", "DISPLAY"), "${telemetry.currentHz}/${telemetry.maxHz}Hz", Icons.Rounded.Speed, Color(0xFF35F2FF), Modifier.weight(1f))
            MetricTile(Tx.t("SUHU", "THERMAL"), "${"%.1f".format(telemetry.temperatureC)}°C", Icons.Rounded.Thermostat, if (telemetry.temperatureC > 43f) Color(0xFFFF5D67) else Neon.Accent, Modifier.weight(1f))
            MetricTile(Tx.t("BATERAI", "BATTERY"), "${telemetry.batteryPercent}%", Icons.Rounded.BatteryChargingFull, Color(0xFFFFC857), Modifier.weight(1f))
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
            MetricTile(Tx.t("RAM", "RAM"), "${"%.1f".format(telemetry.ramUsedGb)}/${"%.1f".format(telemetry.ramTotalGb)}G", Icons.Rounded.Memory, Neon.Accent, Modifier.weight(1f))
            MetricTile(Tx.t("PENYIMPANAN", "STORAGE"), "${"%.1f".format(telemetry.storageFreeGb)}G ${Tx.t("KOSONG", "FREE")}", Icons.Rounded.Storage, Color(0xFF9877FF), Modifier.weight(1f))
            MetricTile(Tx.t("JARINGAN", "NETWORK"), telemetry.network, Icons.Rounded.Wifi, Color(0xFF35F2FF), Modifier.weight(1f))
        }
    }
}

@Composable
private fun ReadinessDeck(context: Context, telemetry: CommandCenterTelemetry, onAdb: () -> Unit) {
    val connLabel = telemetry.connectionMode
    val connDetail = when {
        telemetry.adbConnected -> Tx.t("via $connLabel", "via $connLabel")
        telemetry.iadbAvailable -> Tx.t("iAdb siap · tap untuk hubungkan", "iAdb ready · tap to connect")
        telemetry.shizukuAvailable -> Tx.t("Shizuku siap · tap untuk hubungkan", "Shizuku ready · tap to connect")
        else -> Tx.t("Wireless ADB / Shizuku / iAdb", "Wireless ADB / Shizuku / iAdb")
    }
    val rows = listOf(
        ReadinessItem(Tx.t("KONTROL PERANGKAT", "DEVICE CONTROL"), telemetry.adbConnected, connDetail, Icons.Rounded.Tune, onAdb),
        ReadinessItem(Tx.t("FLOATING HUD", "FLOATING HUD"), telemetry.overlayReady, Tx.t("Izin overlay aktif", "Overlay permission"), Icons.Rounded.Layers) {
            runCatching { context.startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, android.net.Uri.parse("package:${context.packageName}"))) }
        },
        ReadinessItem(Tx.t("SESI GAMING", "SESSION TRACK"), telemetry.sessionReady, Tx.t("Sesi gaming aktif", "Gaming session active"), Icons.Rounded.Security, onAdb),
        ReadinessItem(Tx.t("FOKUS GAME", "GAME FOCUS"), telemetry.dndReady, Tx.t("Akses mode jangan ganggu", "DND policy access"), Icons.Rounded.NotificationsOff) {
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
    val accent = if (item.ready) Neon.Accent else Color(0xFFFFC857)
    Row(
        modifier.clip(ReactorSmall).background(Color(0xFF08110E))
            .border(1.dp, accent.copy(alpha = .28f), ReactorSmall)
            .clickable(onClick = item.onClick).padding(10.dp),
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
            .background(Brush.verticalGradient(listOf(accent.copy(alpha = .09f), Color(0xFF0A1512), Color(0xFF050807))))
            .border(1.dp, accent.copy(alpha = .32f), ReactorSmall)
            .clickable(onClick = onClick).padding(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(32.dp).clip(ReactorSmall).background(accent.copy(alpha = .10f)), contentAlignment = Alignment.Center) {
                Icon(icon, null, tint = accent, modifier = Modifier.size(18.dp))
            }
            Spacer(Modifier.weight(1f))
            Text(Tx.t("BUKA >", "OPEN >"), color = accent, fontSize = 7.sp, fontWeight = FontWeight.Black, fontFamily = FontFamily.Monospace)
        }
        Spacer(Modifier.height(9.dp))
        Text(title, color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Black, letterSpacing = .4.sp)
        Spacer(Modifier.height(2.dp))
        Text(detail, color = Neon.TextDim, fontSize = 8.sp, lineHeight = 10.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun SystemIntelligenceCard(telemetry: CommandCenterTelemetry) {
    val accent = if (telemetry.adbConnected) Neon.Accent else Color(0xFFFFC857)
    Column(
        Modifier.fillMaxWidth().clip(ReactorShape).background(Color(0xFF050B09))
            .border(1.dp, Color(0xFF163B31), ReactorShape).padding(14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.Tune, null, tint = accent, modifier = Modifier.size(19.dp))
            Spacer(Modifier.width(8.dp))
            Text(Tx.t("INTELLIGENCE SISTEM", "NUKE INTELLIGENCE"), color = Color.White, fontWeight = FontWeight.Black, fontSize = 11.sp, letterSpacing = 1.4.sp)
            Spacer(Modifier.weight(1f))
            Text(if (telemetry.adbConnected) Tx.t("LANJUTAN", "EXTENDED") else Tx.t("STANDAR", "STANDARD"), color = accent, fontSize = 8.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.height(8.dp))
        Text(
            if (telemetry.adbConnected) {
                Tx.t(
                    "Kontrol perangkat aktif. Game Nuke dapat memakai optimasi yang didukung perangkat, memverifikasi hasil, mengukur telemetry, lalu memulihkan perubahan milik sesi.",
                    "Device control is active. Game Nuke can use supported optimizations, verify results, measure telemetry, and restore session-owned changes.",
                )
            } else {
                Tx.t(
                    "Game Nuke tetap memantau RAM, suhu, baterai, display, dan status sesi. Hubungkan kontrol lanjutan jika perangkat mendukung optimasi tambahan.",
                    "Game Nuke still monitors RAM, temperature, battery, display, and session status. Connect advanced control when the device supports additional optimizations.",
                )
            },
            color = Neon.TextDim, fontSize = 10.sp, lineHeight = 14.sp,
        )
    }
}

@Composable
private fun MetricTile(label: String, value: String, icon: ImageVector, accent: Color, modifier: Modifier = Modifier) {
    Column(
        modifier.clip(ReactorSmall).background(Color(0xFF07100D)).border(1.dp, Color(0xFF173A31), ReactorSmall).padding(9.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, tint = accent, modifier = Modifier.size(13.dp))
            Spacer(Modifier.width(5.dp))
            Text(label, color = Neon.TextDim, fontSize = 6.7.sp, fontFamily = FontFamily.Monospace, letterSpacing = .8.sp, maxLines = 1)
        }
        Spacer(Modifier.height(4.dp))
        Text(value, color = accent, fontSize = 10.5.sp, fontWeight = FontWeight.Black, fontFamily = FontFamily.Monospace, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun ReactorButton(text: String, icon: ImageVector, accent: Color, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Row(
        modifier.height(44.dp).clip(ReactorSmall).background(accent.copy(alpha = .10f))
            .border(1.dp, accent.copy(alpha = .55f), ReactorSmall).clickable(onClick = onClick).padding(horizontal = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        Icon(icon, null, tint = accent, modifier = Modifier.size(17.dp))
        Spacer(Modifier.width(7.dp))
        Text(text, color = accent, fontSize = 9.sp, fontWeight = FontWeight.Black, letterSpacing = .7.sp, maxLines = 1)
    }
}

@Composable
private fun SectionRail(title: String, meta: String) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.width(18.dp).height(2.dp).background(Neon.Accent))
        Spacer(Modifier.width(7.dp))
        Text(title, color = Color.White, fontSize = 9.sp, fontWeight = FontWeight.Black, letterSpacing = 1.2.sp)
        Spacer(Modifier.width(8.dp))
        Box(Modifier.weight(1f).height(1.dp).background(Color(0xFF1B493C)))
        Spacer(Modifier.width(8.dp))
        Text(meta, color = Neon.TextDim, fontSize = 6.7.sp, fontFamily = FontFamily.Monospace)
    }
}

@Composable
private fun SegmentedProgress(progress: Float, accent: Color) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        repeat(12) { index ->
            val on = (index + 1) / 12f <= progress + .02f
            Box(Modifier.weight(1f).height(4.dp).clip(CutCornerShape(1.dp)).background(if (on) accent else Color(0xFF1D2A26)))
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

    val battery = runCatching { context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) }.getOrNull()
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
                .clip(CutCornerShape(topStart = 20.dp, topEnd = 6.dp, bottomStart = 6.dp, bottomEnd = 20.dp))
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
                    1.dp,
                    Brush.verticalGradient(listOf(Neon.Accent.copy(alpha = 0.6f), Color(0xFF1B493C))),
                    CutCornerShape(topStart = 20.dp, topEnd = 6.dp, bottomStart = 6.dp, bottomEnd = 20.dp)
                )
                .padding(16.dp)
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
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                Tx.t("JEMBATAN KONTROL PERANGKAT", "DEVICE CONTROL BRIDGES"),
                                color = Color.White,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Black,
                                letterSpacing = 1.sp
                            )
                            Spacer(Modifier.width(8.dp))
                            Box(
                                Modifier
                                    .clip(CutCornerShape(2.dp))
                                    .background(Neon.Accent.copy(alpha = 0.2f))
                                    .border(0.8.dp, Neon.Accent, CutCornerShape(2.dp))
                                    .padding(horizontal = 6.dp, vertical = 2.dp)
                            ) {
                                Text(
                                    telemetry.connectionMode,
                                    color = if (telemetry.adbConnected) Neon.Accent else Color(0xFFFF7A59),
                                    fontSize = 7.5.sp,
                                    fontWeight = FontWeight.Black,
                                    fontFamily = FontFamily.Monospace
                                )
                            }
                        }
                        Spacer(Modifier.height(2.dp))
                        Text(
                            Tx.t("Pilih framework atau bridge nirkabel untuk tuning performa perangkat", "Select elevated framework or wireless bridge for hardware tuning"),
                            color = Neon.TextDim,
                            fontSize = 9.5.sp,
                            lineHeight = 13.sp
                        )
                    }
                    IconButton(
                        onClick = onDismiss,
                        modifier = Modifier.size(32.dp)
                    ) {
                        Icon(Icons.Rounded.Close, contentDescription = "Close", tint = Neon.TextDim)
                    }
                }

                Box(Modifier.fillMaxWidth().height(1.dp).background(Color(0xFF1B493C)))

                // Option 1: Connect with iAdb (Primary Recommended)
                val iadbBadge = when {
                    isIadbConnected -> Tx.t("AKTIF", "ACTIVE")
                    !isIadbInstalled -> Tx.t("BELUM TERPASANG", "NOT INSTALLED")
                    !isIadbRunning -> Tx.t("OFFLINE", "OFFLINE")
                    !isIadbPermitted -> Tx.t("BUTUH IZIN", "AUTH NEEDED")
                    else -> Tx.t("SIAP", "READY")
                }
                val iadbBadgeColor = when {
                    isIadbConnected -> Neon.Accent
                    !isIadbInstalled -> Color(0xFFFF5D67)
                    !isIadbRunning || !isIadbPermitted -> Color(0xFFFFC857)
                    else -> Color(0xFF35F2FF)
                }
                ConnectionMethodCard(
                    title = Tx.t("Hubungkan dengan iAdb", "Connect with iAdb"),
                    tag = Tx.t("DIREKOMENDASIKAN", "RECOMMENDED"),
                    badge = iadbBadge,
                    badgeColor = iadbBadgeColor,
                    description = Tx.t("Mesin ringan stabilitas tinggi (Android 11+) tanpa ketergantungan PC / Wi-Fi setelah setup awal.", "High-stability lightweight engine (Android 11+) with zero PC / Wi-Fi dependency after initial setup."),
                    icon = Icons.Rounded.Bolt,
                    iconTint = Neon.Accent,
                    onClick = {
                        when {
                            isIadbConnected -> {
                                NukeConnectionManager.preferredBackend = NukeConnectionManager.Backend.IADB
                                NukeToast.success(context, Tx.t("iAdb terhubung dan aktif.", "iAdb is connected and active."))
                                onDismiss()
                            }
                            !isIadbInstalled -> {
                                NukeToast.error(context, Tx.t("iAdb belum terpasang. Membuka halaman unduhan...", "iAdb is not installed. Opening download page..."))
                                NukeIadbBridge.launchApp(context)
                            }
                            !isIadbRunning -> {
                                NukeToast.error(context, Tx.t("Layanan iAdb offline. Buka aplikasi iAdb dan jalankan layanan terlebih dahulu.", "iAdb service is offline. Please launch iAdb and start the service first."))
                                NukeIadbBridge.launchApp(context)
                            }
                            !isIadbPermitted -> {
                                NukeToast.success(context, Tx.t("Meminta otorisasi iAdb...", "Requesting iAdb authorization..."))
                                NukeIadbBridge.requestPermission()
                                onDismiss()
                            }
                            else -> {
                                NukeConnectionManager.preferredBackend = NukeConnectionManager.Backend.IADB
                                NukeIadbBridge.bindUserServiceNow()
                                NukeToast.success(context, Tx.t("Menghubungkan ke iAdb...", "Connecting to iAdb..."))
                                onDismiss()
                            }
                        }
                    }
                )

                // Option 2: Connect with Shizuku (Alternative)
                val shizukuBadge = when {
                    isShizukuConnected -> Tx.t("AKTIF", "ACTIVE")
                    !isShizukuInstalled -> Tx.t("BELUM TERPASANG", "NOT INSTALLED")
                    !isShizukuRunning -> Tx.t("OFFLINE", "OFFLINE")
                    !isShizukuPermitted -> Tx.t("BUTUH IZIN", "AUTH NEEDED")
                    else -> Tx.t("SIAP", "READY")
                }
                val shizukuBadgeColor = when {
                    isShizukuConnected -> Neon.Accent
                    !isShizukuInstalled -> Color(0xFFFF5D67)
                    !isShizukuRunning || !isShizukuPermitted -> Color(0xFFFFC857)
                    else -> Color(0xFF35F2FF)
                }
                ConnectionMethodCard(
                    title = Tx.t("Hubungkan dengan Shizuku", "Connect with Shizuku"),
                    tag = Tx.t("ALTERNATIF", "ALTERNATIVE"),
                    badge = shizukuBadge,
                    badgeColor = shizukuBadgeColor,
                    description = Tx.t("Daemon sistem dengan hak istimewa — tetap aktif secara permanen saat Wi-Fi mati/hidup.", "Privileged system daemon — stays active permanently across Wi-Fi toggles and gaming sessions."),
                    icon = Icons.Rounded.AdminPanelSettings,
                    iconTint = Color(0xFF35F2FF),
                    onClick = {
                        when {
                            isShizukuConnected -> {
                                NukeConnectionManager.preferredBackend = NukeConnectionManager.Backend.SHIZUKU
                                NukeToast.success(context, Tx.t("Shizuku terhubung dan aktif.", "Shizuku is connected and active."))
                                onDismiss()
                            }
                            !isShizukuInstalled -> {
                                NukeToast.error(context, Tx.t("Shizuku belum terpasang. Membuka halaman unduhan...", "Shizuku is not installed. Opening download page..."))
                                NukeShizukuBridge.launchApp(context)
                            }
                            !isShizukuRunning -> {
                                NukeToast.error(context, Tx.t("Layanan Shizuku offline. Buka Shizuku dan jalankan layanan terlebih dahulu.", "Shizuku service is offline. Please open Shizuku and start the service first."))
                                NukeShizukuBridge.launchApp(context)
                            }
                            !isShizukuPermitted -> {
                                NukeToast.success(context, Tx.t("Meminta otorisasi Shizuku...", "Requesting Shizuku authorization..."))
                                NukeShizukuBridge.requestPermission()
                                onDismiss()
                            }
                            else -> {
                                NukeConnectionManager.preferredBackend = NukeConnectionManager.Backend.SHIZUKU
                                NukeShizukuBridge.bindUserServiceNow()
                                NukeToast.success(context, Tx.t("Menghubungkan ke Shizuku...", "Connecting to Shizuku..."))
                                onDismiss()
                            }
                        }
                    }
                )

                // Option 3: Wireless ADB Pairing (Manual)
                val nativeBadge = when {
                    telemetry.connectionMode == "LOCAL CORE" -> Tx.t("TERHUBUNG", "CONNECTED")
                    telemetry.adbConnected && telemetry.connectionMode != "SHIZUKU" && telemetry.connectionMode != "IADB" -> Tx.t("TERHUBUNG", "CONNECTED")
                    else -> Tx.t("STANDBY", "STANDBY")
                }
                val nativeBadgeColor = when {
                    telemetry.connectionMode == "LOCAL CORE" || (telemetry.adbConnected && telemetry.connectionMode != "SHIZUKU" && telemetry.connectionMode != "IADB") -> Neon.Accent
                    else -> Color(0xFFFFC857)
                }
                ConnectionMethodCard(
                    title = Tx.t("Wireless ADB Pairing", "Wireless ADB Pairing"),
                    tag = Tx.t("MANUAL", "MANUAL"),
                    badge = nativeBadge,
                    badgeColor = nativeBadgeColor,
                    description = Tx.t("Koneksi manual via Opsi Pengembang (jaringan Wi-Fi + 6 digit kode pairing).", "Manual device link via Developer Options (Wi-Fi network + 6-digit pairing code)."),
                    icon = Icons.Rounded.WifiTethering,
                    iconTint = Color(0xFFFFC857),
                    onClick = {
                        if (telemetry.network == "OFFLINE") {
                            NukeToast.error(context, Tx.t("Aktifkan Wi-Fi untuk pairing Wireless ADB.", "Please enable Wi-Fi for Wireless ADB pairing."))
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
            .clip(CutCornerShape(topStart = 12.dp, topEnd = 3.dp, bottomStart = 3.dp, bottomEnd = 12.dp))
            .background(Color(0xFF091411))
            .border(1.dp, Color(0xFF1E3A31), CutCornerShape(topStart = 12.dp, topEnd = 3.dp, bottomStart = 3.dp, bottomEnd = 12.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                Modifier
                    .size(36.dp)
                    .clip(CutCornerShape(6.dp))
                    .background(iconTint.copy(alpha = 0.12f))
                    .border(0.8.dp, iconTint.copy(alpha = 0.35f), CutCornerShape(6.dp)),
                contentAlignment = Alignment.Center
            ) {
                Icon(icon, contentDescription = null, tint = iconTint, modifier = Modifier.size(18.dp))
            }
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.weight(1f, fill = false)
                    ) {
                        Text(
                            title,
                            color = Color.White,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        if (tag != null) {
                            Spacer(Modifier.width(6.dp))
                            Box(
                                Modifier
                                    .clip(CutCornerShape(2.dp))
                                    .background(iconTint.copy(alpha = 0.2f))
                                    .padding(horizontal = 4.dp, vertical = 1.dp)
                            ) {
                                Text(tag, color = iconTint, fontSize = 6.5.sp, fontWeight = FontWeight.Black)
                            }
                        }
                    }
                    Spacer(Modifier.width(6.dp))
                    Box(
                        Modifier
                            .clip(CutCornerShape(2.dp))
                            .background(badgeColor.copy(alpha = 0.15f))
                            .border(0.6.dp, badgeColor.copy(alpha = 0.7f), CutCornerShape(2.dp))
                            .padding(horizontal = 5.dp, vertical = 1.5.dp)
                    ) {
                        Text(
                            badge,
                            color = badgeColor,
                            fontSize = 7.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace,
                            maxLines = 1
                        )
                    }
                }
                Spacer(Modifier.height(3.dp))
                Text(
                    description,
                    color = Neon.TextDim,
                    fontSize = 9.5.sp,
                    lineHeight = 13.sp
                )
            }
        }
    }
}
