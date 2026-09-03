package com.neon.gametweak.ui.screens

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.net.Uri
import android.os.Build
import android.provider.DocumentsContract
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.CutCornerShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.neon.gametweak.AdbManager
import com.neon.gametweak.LocalWebServer
import com.neon.gametweak.GameCatalogRepository
import com.neon.gametweak.NukeGamingShellGateway
import com.neon.gametweak.OverlayPermissionController
import com.neon.gametweak.NukeToast
import com.neon.gametweak.NukeAdManager
import com.neon.gametweak.findActivity
import com.neon.gametweak.Tx
import com.neon.gametweak.AdBlockStatus
import com.neon.gametweak.NukeAdBlockDetector
import com.neon.gametweak.NukeAdBlockDetectedDialog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader

@Composable
fun CleanerScreen(adbManager: AdbManager) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val gateway = remember(adbManager) { NukeGamingShellGateway(adbManager) }
    var adbConnected by remember { mutableStateOf(adbManager.isConnected()) }
    var isWorking by remember { mutableStateOf(false) }
    var operation by remember { mutableStateOf("READY") }
    var lastGainMb by remember { mutableLongStateOf(0L) }

    fun dirBytes(file: File?): Long {
        if (file == null || !file.exists()) return 0L
        if (file.isFile) return file.length()
        return file.listFiles()?.sumOf { dirBytes(it) } ?: 0L
    }

    fun memorySnapshot(): Pair<Long, Boolean> {
        val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as? android.app.ActivityManager
        val info = android.app.ActivityManager.MemoryInfo()
        runCatching { manager?.getMemoryInfo(info) }
        return info.availMem to info.lowMemory
    }

    fun freeStorageBytes(): Long = runCatching {
        android.os.StatFs(android.os.Environment.getDataDirectory().path).availableBytes
    }.getOrDefault(0L)

    var ownCacheBytes by remember { mutableLongStateOf(dirBytes(context.cacheDir)) }
    var showAdBlockDialog by remember { mutableStateOf(false) }
    var adBlockStatus by remember { mutableStateOf(AdBlockStatus()) }

    fun checkAdBlockOrRun(action: () -> Unit) {
        val check = NukeAdBlockDetector.checkStatus(context, adbManager)
        if (check.isDetected) {
            adBlockStatus = check
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

    if (showAdBlockDialog && adBlockStatus.isDetected) {
        NukeAdBlockDetectedDialog(
            status = adBlockStatus,
            adbManager = adbManager,
            onDismiss = { showAdBlockDialog = false },
        )
    }

    LaunchedEffect(Unit) {
        while (true) {
            adbConnected = runCatching { adbManager.isConnected() }.getOrDefault(false)
            delay(2500L)
        }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize().background(Color(0xFF020705)),
        contentPadding = PaddingValues(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            Column(
                Modifier.fillMaxWidth().clip(CutCornerShape(topStart = 22.dp, topEnd = 4.dp, bottomStart = 4.dp, bottomEnd = 22.dp))
                    .background(
                        androidx.compose.ui.graphics.Brush.horizontalGradient(
                            listOf(Color(0xFF0C241B), Color(0xFF06100C), Color(0xFF100D16)),
                        ),
                    )
                    .border(1.dp, Color(0xFF35C99B).copy(alpha = .38f), CutCornerShape(topStart = 22.dp, topEnd = 4.dp, bottomStart = 4.dp, bottomEnd = 22.dp))
                    .padding(16.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier.size(48.dp).clip(CutCornerShape(topStart = 14.dp, bottomEnd = 14.dp))
                            .background(Color(0xFF35C99B).copy(alpha = .10f))
                            .border(1.dp, Color(0xFF35C99B).copy(alpha = .35f), CutCornerShape(topStart = 14.dp, bottomEnd = 14.dp)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(Icons.Rounded.CleaningServices, null, tint = Color(0xFF35C99B), modifier = Modifier.size(25.dp))
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(Tx.t("OPTIMASI NUKE", "NUKE OPTIMIZER"), color = Color.White, fontWeight = FontWeight.Black, fontSize = 15.sp, letterSpacing = 1.2.sp)
                        Text(
                            if (adbConnected) Tx.t("KONTROL LANJUTAN AKTIF", "EXTENDED CONTROL ONLINE") else Tx.t("KONTROL STANDAR // HUBUNGI UNTUK AKSI MENDALAM", "STANDARD CONTROL // CONNECT FOR DEEP ACTIONS"),
                            color = if (adbConnected) Color(0xFF35C99B) else Color(0xFFFFC857),
                            fontSize = 8.5.sp,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                    Box(Modifier.size(7.dp).background(if (adbConnected) Color(0xFF35C99B) else Color(0xFFFFC857), CircleShape))
                }
                Spacer(Modifier.height(10.dp))
                Text(
                    Tx.t(
                        "Pembersihan dibagi menjadi cache aplikasi, pemulihan memori saat dibutuhkan, dan pembersihan penyimpanan yang didukung perangkat.",
                        "Cleanup is split into app cache, pressure-aware memory recovery, and supported storage cleanup.",
                    ),
                    color = Color(0xFF9BB0A6), fontSize = 10.sp, lineHeight = 14.sp,
                )
                if (lastGainMb > 0L) {
                    Spacer(Modifier.height(8.dp))
                    Text("${Tx.t("HASIL TERVERIFIKASI TERAKHIR", "LAST VERIFIED GAIN")}  +${lastGainMb}MB", color = Color(0xFF35C99B), fontSize = 9.sp, fontWeight = FontWeight.Black, fontFamily = FontFamily.Monospace)
                }
            }
        }

        item {
            OptimizerActionCard(
                title = Tx.t("APP CACHE", "APP CACHE"),
                detail = Tx.t("Bersihkan file sementara Game Nuke saja", "Clear Game Nuke temporary files only"),
                value = "${"%.1f".format(ownCacheBytes / (1024.0 * 1024.0))} MB",
                icon = Icons.Rounded.DeleteSweep,
                accent = Color(0xFF35F2FF),
                enabled = !isWorking,
            ) {
                checkAdBlockOrRun {
                    isWorking = true
                    operation = Tx.t("MEMBERSIHKAN CACHE APLIKASI", "CLEARING APP CACHE")
                    scope.launch(Dispatchers.IO) {
                        val before = dirBytes(context.cacheDir)
                        runCatching { context.cacheDir.listFiles()?.forEach { it.deleteRecursively() } }
                        val after = dirBytes(context.cacheDir)
                        withContext(Dispatchers.Main) {
                            ownCacheBytes = after
                            lastGainMb = ((before - after).coerceAtLeast(0L) / (1024L * 1024L))
                            operation = Tx.t("CACHE APLIKASI SELESAI", "APP CACHE COMPLETE")
                            isWorking = false
                            context.findActivity()?.let { NukeAdManager.showInterstitial(it) }
                        }
                    }
                }
            }
        }

        item {
            OptimizerActionCard(
                title = Tx.t("DEEP RECLAIM", "DEEP RECLAIM"),
                detail = if (adbConnected) Tx.t("Pemulihan memori terukur dengan penanganan latar belakang yang aman", "Measured memory reclaim with safe background handling") else Tx.t("Pemulihan lanjutan tidak tersedia saat ini", "Advanced reclaim unavailable on this device right now"),
                value = if (adbConnected) Tx.t("SIAP", "READY") else Tx.t("MODE DASAR", "BASIC MODE"),
                icon = Icons.Rounded.Memory,
                accent = Color(0xFF35C99B),
                enabled = adbConnected && !isWorking,
            ) {
                checkAdBlockOrRun {
                    isWorking = true
                    operation = Tx.t("MEMULIHKAN MEMORI", "MEMORY RECLAIM")
                    scope.launch(Dispatchers.IO) {
                        val before = memorySnapshot()
                        val compact = gateway.compactSystem()
                        if (compact.isSuccess && before.second) {
                            runCatching { gateway.killSafeBackground() }
                        }
                        delay(450L)
                        val after = memorySnapshot()
                        val gain = ((after.first - before.first).coerceAtLeast(0L) / (1024L * 1024L))
                        withContext(Dispatchers.Main) {
                            lastGainMb = gain
                            operation = if (compact.isSuccess) Tx.t("PEMULIHAN TERVERIFIKASI", "RECLAIM VERIFIED") else Tx.t("PEMULIHAN DITOLAK", "RECLAIM REJECTED")
                            isWorking = false
                            context.findActivity()?.let { NukeAdManager.showInterstitial(it) }
                        }
                    }
                }
            }
        }

        item {
            OptimizerActionCard(
                title = Tx.t("CACHE TRIM", "CACHE TRIM"),
                detail = if (adbConnected) Tx.t("Pulihkan ruang penyimpanan saat didukung sistem", "Recover bounded storage headroom when supported") else Tx.t("Pembersihan lanjutan tidak tersedia saat ini", "Advanced storage cleanup unavailable right now"),
                value = if (adbConnected) Tx.t("TRIM AMAN", "SAFE TRIM") else Tx.t("MODE DASAR", "BASIC MODE"),
                icon = Icons.Rounded.Storage,
                accent = Color(0xFFFFC857),
                enabled = adbConnected && !isWorking,
            ) {
                checkAdBlockOrRun {
                    isWorking = true
                    operation = Tx.t("TRIM CACHE PAKET", "PACKAGE CACHE TRIM")
                    scope.launch(Dispatchers.IO) {
                        val before = freeStorageBytes()
                        val desiredFree = (before + 512L * 1024L * 1024L).coerceAtMost(32L * 1024L * 1024L * 1024L)
                        val result = gateway.trimCaches(desiredFree)
                        delay(500L)
                        val after = freeStorageBytes()
                        withContext(Dispatchers.Main) {
                            lastGainMb = ((after - before).coerceAtLeast(0L) / (1024L * 1024L))
                            operation = if (result.isSuccess) Tx.t("TRIM CACHE TERVERIFIKASI", "CACHE TRIM VERIFIED") else Tx.t("TRIM CACHE DITOLAK", "CACHE TRIM REJECTED")
                            isWorking = false
                            context.findActivity()?.let { NukeAdManager.showInterstitial(it) }
                        }
                    }
                }
            }
        }

        item {
            Row(
                Modifier.fillMaxWidth().clip(CutCornerShape(topStart = 12.dp, bottomEnd = 12.dp)).background(Color(0xFF06100C))
                    .border(1.dp, Color(0xFF173A31), CutCornerShape(topStart = 12.dp, bottomEnd = 12.dp))
                    .clickable { runCatching { context.startActivity(Intent(android.provider.Settings.ACTION_INTERNAL_STORAGE_SETTINGS)) } }
                    .padding(13.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Rounded.Settings, null, tint = Color(0xFF9877FF), modifier = Modifier.size(19.dp))
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(Tx.t("PENGELOLA PENYIMPANAN ANDROID", "ANDROID STORAGE MANAGER"), color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Black)
                    Text(Tx.t("Buka kontrol penyimpanan resmi sistem", "Open the official system storage controls"), color = Color(0xFF9BB0A6), fontSize = 9.sp)
                }
                Icon(Icons.Rounded.ChevronRight, null, tint = Color(0xFF9BB0A6))
            }
        }

        item {
            Box(
                Modifier.fillMaxWidth().clip(CutCornerShape(9.dp)).background(Color(0xFF050B09))
                    .border(1.dp, Color(0xFF182A24), CutCornerShape(9.dp)).padding(10.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (isWorking) {
                        CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp, color = Color(0xFF35C99B))
                    } else {
                        Box(Modifier.size(6.dp).background(Color(0xFF35C99B), CircleShape))
                    }
                    Spacer(Modifier.width(8.dp))
                    Text(operation, color = Color(0xFF9BB0A6), fontSize = 9.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

@Composable
private fun OptimizerActionCard(
    title: String,
    detail: String,
    value: String,
    icon: ImageVector,
    accent: Color,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val actualAccent = if (enabled) accent else Color(0xFF56635E)
    Row(
        Modifier.fillMaxWidth().clip(CutCornerShape(topStart = 13.dp, topEnd = 2.dp, bottomStart = 2.dp, bottomEnd = 13.dp))
            .background(androidx.compose.ui.graphics.Brush.horizontalGradient(listOf(actualAccent.copy(alpha = .08f), Color(0xFF07100D), Color(0xFF050807))))
            .border(1.dp, actualAccent.copy(alpha = .32f), CutCornerShape(topStart = 13.dp, topEnd = 2.dp, bottomStart = 2.dp, bottomEnd = 13.dp))
            .clickable(enabled = enabled, onClick = onClick).padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(38.dp).clip(CutCornerShape(9.dp)).background(actualAccent.copy(alpha=.10f)), contentAlignment = Alignment.Center) {
            Icon(icon, null, tint = actualAccent, modifier = Modifier.size(20.dp))
        }
        Spacer(Modifier.width(11.dp))
        Column(Modifier.weight(1f)) {
            Text(title, color = if (enabled) Color.White else Color(0xFF7E8B85), fontSize = 11.sp, fontWeight = FontWeight.Black, letterSpacing = .6.sp)
            Spacer(Modifier.height(2.dp))
            Text(detail, color = Color(0xFF9BB0A6), fontSize = 9.sp, lineHeight = 12.sp, maxLines = 2, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
        }
        Spacer(Modifier.width(8.dp))
        Text(value, color = actualAccent, fontSize = 9.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Black, maxLines = 1)
    }
}

data class NukeProcess(val pid: String, val memoryMb: Long, val name: String, val isSystem: Boolean)

@Composable
fun ProcessManagerScreen(adbManager: AdbManager) {
    val context = LocalContext.current
    var processList by remember { mutableStateOf<List<NukeProcess>>(emptyList()) }
    var isRefreshing by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }
    var totalMemoryUsed by remember { mutableStateOf(0L) }
    val scope = rememberCoroutineScope()

    val accent = Color(0xFF35C99B)
    val danger = Color(0xFFFF1744)
    val alert = Color(0xFFFFB300)
    val bgCard = Color(0xFF06100C)
    val bgInset = Color(0xFF0D1B15)
    val textDim = Color(0xFF9BB0A6)

    val gateway = remember(adbManager) { NukeGamingShellGateway(adbManager) }
    val fetchProcesses = {
        scope.launch {
            isRefreshing = true
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val tempList = mutableListOf<NukeProcess>()
                    var memSum = 0L
                    if (adbManager.isConnected()) {
                        val snap = gateway.readRssProcessSnapshot()
                        if (snap.isSuccess) {
                            snap.output.lineSequence().drop(1).forEach { row ->
                                val parts = row.trim().split(Regex("\\s+"))
                                if (parts.size < 3) return@forEach
                                val pid = parts.first()
                                val rssKb = parts[parts.size - 2].toLongOrNull() ?: return@forEach
                                val name = parts.last().substringBefore(':')
                                val memMb = rssKb / 1024
                                val protected = name == context.packageName || name == "system_server" ||
                                    name.startsWith("com.android.systemui") || name.startsWith("com.android.phone") ||
                                    name.contains("launcher", ignoreCase = true)
                                val isSystem = name.startsWith("com.android.") || name.startsWith("android.") || !name.contains(".")
                                if (!protected && memMb > 0) {
                                    memSum += memMb
                                    tempList.add(NukeProcess(pid, memMb, name, isSystem))
                                }
                            }
                        }
                    }
                    // Privacy-safe local fallback: Android may only expose a subset of running apps,
                    // but this still keeps the monitor useful instead of returning a blank page.
                    if (tempList.isEmpty()) {
                        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? android.app.ActivityManager
                        val running = runCatching { am?.runningAppProcesses.orEmpty() }.getOrDefault(emptyList())
                        val pids = running.map { it.pid }.toIntArray()
                        val mem = if (pids.isNotEmpty()) runCatching { am?.getProcessMemoryInfo(pids).orEmpty() }.getOrDefault(emptyArray()) else emptyArray()
                        running.forEachIndexed { index, proc ->
                            val name = proc.processName.substringBefore(':')
                            if (name == context.packageName || !name.contains('.')) return@forEachIndexed
                            val mb = mem.getOrNull(index)?.totalPss?.div(1024L) ?: 0L
                            if (mb > 0) {
                                memSum += mb
                                tempList.add(NukeProcess(proc.pid.toString(), mb, name, name.startsWith("com.android.")))
                            }
                        }
                    }
                    tempList.distinctBy { it.name }.sortedByDescending { it.memoryMb } to memSum
                }.getOrNull()
            }
            result?.let { (items, memory) -> processList = items; totalMemoryUsed = memory }
            isRefreshing = false
        }
    }

    LaunchedEffect(Unit) { fetchProcesses() }
    val filteredList = processList.filter { it.name.contains(searchQuery, ignoreCase = true) }

    Column(modifier = Modifier.fillMaxSize().background(Color(0xFF020705)).imePadding()) {

        Column(
            modifier = Modifier.fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Box(modifier = Modifier.size(8.dp).background(accent, CircleShape))
                Spacer(Modifier.width(8.dp))
                Text(Tx.t("PROSES AKTIF", "ACTIVE PROCESSES"), color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Black, letterSpacing = 2.sp)
                Spacer(Modifier.width(8.dp))
                Text("${filteredList.size} pid", color = textDim, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
                Spacer(Modifier.weight(1f))
                Box(
                    modifier = Modifier.clip(CutCornerShape(6.dp))
                        .background(bgInset)
                        .border(1.dp, accent.copy(alpha = 0.4f), CutCornerShape(6.dp))
                        .padding(horizontal = 10.dp, vertical = 4.dp),
                ) {
                    Text(
                        "$totalMemoryUsed MB",
                        color = accent, fontSize = 12.sp, fontWeight = FontWeight.Black, fontFamily = FontFamily.Monospace,
                    )
                }
            }

            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    modifier = Modifier.weight(1f).height(46.dp),
                    placeholder = {
                        Text(
                            Tx.t("Filter package...", "Filter package..."),
                            color = textDim, fontSize = 12.sp, fontFamily = FontFamily.Monospace,
                        )
                    },
                    singleLine = true,
                    textStyle = TextStyle(color = Color.White, fontSize = 13.sp, fontFamily = FontFamily.Monospace),
                    leadingIcon = { Icon(Icons.Rounded.Search, null, tint = textDim, modifier = Modifier.size(16.dp)) },
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = accent,
                        unfocusedBorderColor = Color(0xFF202028),
                        focusedContainerColor = bgCard,
                        unfocusedContainerColor = bgCard,
                    ),
                    shape = CutCornerShape(8.dp),
                )
                Box(
                    modifier = Modifier.size(46.dp).clip(CutCornerShape(8.dp))
                        .background(bgCard)
                        .border(1.dp, accent.copy(alpha = 0.5f), CutCornerShape(8.dp))
                        .clickable { fetchProcesses() },
                    contentAlignment = Alignment.Center,
                ) {
                    if (isRefreshing) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), color = accent, strokeWidth = 2.dp)
                    } else {
                        Icon(Icons.Rounded.Refresh, null, tint = accent, modifier = Modifier.size(20.dp))
                    }
                }
            }

            Box(
                modifier = Modifier.fillMaxWidth().height(44.dp).clip(CutCornerShape(8.dp))
                    .background(accent.copy(alpha = 0.10f))
                    .border(1.5.dp, accent.copy(alpha = 0.55f), CutCornerShape(8.dp))
                    .clickable { fetchProcesses() },
                contentAlignment = Alignment.Center,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.Refresh, null, tint = accent, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(Tx.t("SEGARKAN DAFTAR PROSES", "REFRESH PROCESS SNAPSHOT"), color = accent, fontWeight = FontWeight.Black, fontSize = 11.sp, letterSpacing = 1.5.sp)
                }
            }
        }

        Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(Color(0xFF202028)))

        if (filteredList.isEmpty() && !isRefreshing) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Rounded.SearchOff, null, tint = textDim, modifier = Modifier.size(48.dp))
                    Spacer(Modifier.height(8.dp))
                    Text(Tx.t("TIDAK ADA PROSES YANG COCOK", "NO PROCESSES MATCHED"), color = textDim, fontSize = 11.sp, fontFamily = FontFamily.Monospace, letterSpacing = 2.sp)
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                items(filteredList) { proc ->
                    val rowAccent = if (proc.isSystem) textDim else accent
                    Row(
                        modifier = Modifier.fillMaxWidth().clip(CutCornerShape(10.dp))
                            .background(bgCard)
                            .border(1.dp, if (proc.isSystem) Color(0xFF1E2824) else accent.copy(alpha = 0.25f), CutCornerShape(10.dp))
                            .padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(
                            modifier = Modifier.size(36.dp).clip(CutCornerShape(8.dp))
                                .background(rowAccent.copy(alpha = 0.12f))
                                .border(1.dp, rowAccent.copy(alpha = 0.35f), CutCornerShape(8.dp)),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                if (proc.isSystem) Icons.Rounded.SettingsSystemDaydream else Icons.Rounded.Apps,
                                null,
                                tint = rowAccent,
                                modifier = Modifier.size(18.dp),
                            )
                        }
                        Spacer(Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                proc.name,
                                color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold,
                                maxLines = 1,
                                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                            )
                            Spacer(Modifier.height(2.dp))
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("pid:", color = textDim, fontSize = 9.sp, fontFamily = FontFamily.Monospace)
                                Text(proc.pid, color = textDim, fontSize = 9.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
                                Spacer(Modifier.width(10.dp))
                                Text(
                                    "${proc.memoryMb}MB",
                                    color = if (proc.memoryMb > 200) alert else accent,
                                    fontSize = 10.sp,
                                    fontFamily = FontFamily.Monospace,
                                    fontWeight = FontWeight.Black,
                                )
                                if (proc.isSystem) {
                                    Spacer(Modifier.width(8.dp))
                                    Box(
                                        modifier = Modifier.clip(CutCornerShape(3.dp))
                                            .background(textDim.copy(alpha = 0.18f))
                                            .padding(horizontal = 5.dp, vertical = 1.dp),
                                    ) {
                                        Text(Tx.t("SISTEM", "SYS"), color = textDim, fontSize = 11.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
                                    }
                                }
                            }
                        }
                        Spacer(Modifier.width(8.dp))
                        Box(
                            modifier = Modifier.size(32.dp).clip(CutCornerShape(7.dp))
                                .background(rowAccent.copy(alpha = 0.08f))
                                .border(1.dp, rowAccent.copy(alpha = 0.25f), CutCornerShape(7.dp)),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(Icons.Rounded.Visibility, null, tint = rowAccent.copy(alpha = 0.75f), modifier = Modifier.size(15.dp))
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun DiagnosticsConsoleScreen(adbManager: AdbManager) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val gateway = remember(adbManager) { NukeGamingShellGateway(adbManager) }
    var running by remember { mutableStateOf(false) }
    var report by remember {
        mutableStateOf(
            "GAME NUKE DIAGNOSTICS\n" +
                "Read-only fixed probes only. Arbitrary shell execution is disabled in the Play build.\n\n" +
                "Press RUN DIAGNOSTICS to collect a bounded report."
        )
    }
    val accent = Color(0xFF35C99B)
    val textDim = Color(0xFF9BB0A6)

    fun copyReport() {
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? android.content.ClipboardManager
        cm?.setPrimaryClip(android.content.ClipData.newPlainText("Game Nuke diagnostics", report))
        NukeToast.success(context, Tx.t("Laporan disalin", "Diagnostics copied"))
    }

    Column(modifier = Modifier.fillMaxSize().background(Color(0xFF020705)).padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Box(modifier = Modifier.size(9.dp).background(accent, CircleShape))
            Spacer(Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(Tx.t("KONSOL DIAGNOSTIK", "DIAGNOSTICS CONSOLE"), color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                Text(Tx.t("HANYA-BACA • DIAGNOSTIK SISTEM", "READ-ONLY • SYSTEM DIAGNOSTICS"), color = textDim, fontSize = 10.sp, letterSpacing = 1.sp)
            }
            IconButton(onClick = { copyReport() }) {
                Icon(Icons.Rounded.ContentCopy, contentDescription = "Copy diagnostics", tint = accent)
            }
        }
        Spacer(Modifier.height(12.dp))
        Text(
            Tx.t("Layar ini menampilkan diagnostik sistem yang terverifikasi dan aman.", "This screen shows read-only verified and safe system diagnostics."),
            color = textDim, fontSize = 12.sp,
        )
        Spacer(Modifier.height(12.dp))
        Button(
            enabled = !running,
            onClick = {
                if (running) return@Button
                running = true
                scope.launch {
                    val generated = withContext(Dispatchers.IO) {
                        val cpu = gateway.readCpuProcessSnapshot()
                        val rss = gateway.readRssProcessSnapshot()
                        buildString {
                            appendLine("GAME NUKE DIAGNOSTICS")
                            appendLine("App: ${com.neon.gametweak.BuildConfig.VERSION_NAME}")
                            appendLine("Android: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
                            appendLine("Device: ${Build.MANUFACTURER} ${Build.MODEL}")
                            appendLine("Connection: ${adbManager.connectionMode()}")
                            appendLine("Persistent core: ${if (adbManager.persistentCoreOnline()) "ONLINE" else "OFFLINE"}")
                            appendLine("Engine Access: PRIVILEGED (System Core / Direct Link)")
                            appendLine()
                            appendLine("--- CPU SNAPSHOT ---")
                            appendLine(if (cpu.isSuccess) cpu.output.take(12_000) else "Unavailable: ${cpu.output.take(240)}")
                            appendLine()
                            appendLine("--- MEMORY PROCESS SNAPSHOT ---")
                            appendLine(if (rss.isSuccess) rss.output.lineSequence().take(40).joinToString("\n") else "Unavailable: ${rss.output.take(240)}")
                        }
                    }
                    report = generated
                    running = false
                    NukeToast.success(context, Tx.t("Diagnostik selesai", "Diagnostics complete"))
                }
            },
            colors = ButtonDefaults.buttonColors(containerColor = accent, contentColor = Color(0xFF00150E)),
            shape = CutCornerShape(topStart = 9.dp, bottomEnd = 9.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (running) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), color = Color(0xFF00150E), strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                    Text(Tx.t("MENJALANKAN DIAGNOSTIK...", "RUNNING DIAGNOSTICS..."), fontSize = 12.sp, fontWeight = FontWeight.Black)
                } else {
                    Icon(Icons.Rounded.PlayArrow, null, tint = Color(0xFF00150E), modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(Tx.t("JALANKAN DIAGNOSTIK", "RUN DIAGNOSTICS"), fontSize = 12.sp, fontWeight = FontWeight.Black)
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        SelectionContainer(modifier = Modifier.weight(1f).fillMaxWidth()) {
            Text(
                report,
                modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState())
                    .background(Color(0xFF06100C), CutCornerShape(topStart = 12.dp, bottomEnd = 12.dp))
                    .border(1.dp, accent.copy(alpha = .24f), CutCornerShape(topStart = 12.dp, bottomEnd = 12.dp))
                    .padding(14.dp),
                color = Color(0xFFD8E9E0), fontFamily = FontFamily.Monospace, fontSize = 11.sp, lineHeight = 15.sp,
            )
        }
    }
}

@Composable
fun WebUiScreen(webServer: LocalWebServer) {
    val context = LocalContext.current
    var isApiRunning by remember { mutableStateOf(webServer.isApiRunning) }
    var isWebRunning by remember { mutableStateOf(webServer.isWebRunning) }
    var isCopying by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    val accent = Color(0xFF35C99B)
    val danger = Color(0xFFFF1744)
    val bgCard = Color(0xFF06100C)
    val bgInset = Color(0xFF0D1B15)
    val textDim = Color(0xFF9BB0A6)

    val folderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            isCopying = true
            scope.launch(Dispatchers.IO) {
                try {
                    webServer.documentRoot.deleteRecursively()
                    webServer.documentRoot.mkdirs()
                    val rootDocId = DocumentsContract.getTreeDocumentId(uri)
                    copyFolderRecursive(context, uri, rootDocId, webServer.documentRoot)
                } finally {
                    withContext(Dispatchers.Main) { isCopying = false; NukeToast.success(context, Tx.t("Penyimpanan Terpasang", "Volume Mounted")) }
                }
            }
        }
    }

    val baseUrl = "http://127.0.0.1:${webServer.apiPort}"

    LazyColumn(
        modifier = Modifier.fillMaxSize().background(Color(0xFF020705)),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(CutCornerShape(14.dp))
                    .background(bgCard)
                    .border(
                        1.dp,
                        if (isApiRunning) accent.copy(alpha = 0.6f) else Color(0xFF202028),
                        CutCornerShape(14.dp),
                    )
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier.size(36.dp).clip(CutCornerShape(8.dp))
                            .background(if (isApiRunning) accent.copy(alpha = 0.15f) else bgInset),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(Icons.Rounded.Api, null, tint = if (isApiRunning) accent else textDim, modifier = Modifier.size(20.dp))
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text("CORE API SERVICE", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Black, letterSpacing = 1.5.sp)
                        Text(
                            text = if (isApiRunning) "RUNNING · port ${webServer.apiPort}" else "OFFLINE",
                            color = if (isApiRunning) accent else textDim,
                            fontSize = 10.sp,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 1.sp,
                        )
                    }
                    val dotColor = if (isApiRunning) accent else danger
                    Box(modifier = Modifier.size(8.dp).background(dotColor, CircleShape))
                }
                Button(
                    onClick = {
                        if (isApiRunning) {
                            webServer.stopApiServer()
                            isApiRunning = webServer.isApiRunning
                        } else {
                            webServer.startApiServer()
                            isApiRunning = webServer.isApiRunning
                        }
                    },
                    modifier = Modifier.fillMaxWidth().height(44.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (isApiRunning) danger.copy(alpha = 0.18f) else accent.copy(alpha = 0.18f),
                    ),
                    border = androidx.compose.foundation.BorderStroke(1.dp, if (isApiRunning) danger else accent),
                    shape = CutCornerShape(10.dp),
                ) {
                    Icon(
                        if (isApiRunning) Icons.Rounded.PowerOff else Icons.Rounded.PlayArrow,
                        null,
                        tint = if (isApiRunning) danger else accent,
                        modifier = Modifier.size(16.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        if (isApiRunning) Tx.t("MATIKAN API", "STOP API") else Tx.t("AKTIFKAN API", "START API"),
                        color = if (isApiRunning) danger else accent,
                        fontWeight = FontWeight.Black,
                        fontSize = 12.sp,
                        letterSpacing = 1.5.sp,
                    )
                }
                if (isApiRunning) {
                    Box(
                        modifier = Modifier.fillMaxWidth().clip(CutCornerShape(8.dp))
                            .background(bgInset).padding(10.dp),
                    ) {
                        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text("BASE URL", color = textDim, fontSize = 11.sp, fontFamily = FontFamily.Monospace, letterSpacing = 1.5.sp)
                            SelectionContainer {
                                Text(baseUrl, color = accent, fontSize = 12.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
            }
        }

        item {
            Column(
                modifier = Modifier.fillMaxWidth().clip(CutCornerShape(14.dp))
                    .background(bgCard)
                    .border(
                        1.dp,
                        if (isWebRunning) accent.copy(alpha = 0.6f) else Color(0xFF202028),
                        CutCornerShape(14.dp),
                    )
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier.size(36.dp).clip(CutCornerShape(8.dp))
                            .background(if (isWebRunning) accent.copy(alpha = 0.15f) else bgInset),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(Icons.Rounded.Language, null, tint = if (isWebRunning) accent else textDim, modifier = Modifier.size(20.dp))
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text("STATIC WEB SERVER", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Black, letterSpacing = 1.5.sp)
                        Text(
                            text = if (isWebRunning) "RUNNING · port ${webServer.webPort}" else "OFFLINE",
                            color = if (isWebRunning) accent else textDim,
                            fontSize = 10.sp,
                            fontFamily = FontFamily.Monospace,
                            letterSpacing = 1.sp,
                        )
                    }
                    val dotColor = if (isWebRunning) accent else danger
                    Box(modifier = Modifier.size(8.dp).background(dotColor, CircleShape))
                }
                OutlinedButton(
                    onClick = { folderPicker.launch(null) },
                    modifier = Modifier.fillMaxWidth().height(40.dp),
                    enabled = !isCopying,
                    border = androidx.compose.foundation.BorderStroke(1.dp, accent.copy(alpha = 0.5f)),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = accent),
                    shape = CutCornerShape(10.dp),
                ) {
                    Icon(Icons.Rounded.FolderOpen, null, tint = accent, modifier = Modifier.size(14.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(
                        if (isCopying) Tx.t("MEMASANG...", "MOUNTING...") else Tx.t("PILIH FOLDER WEB", "SELECT FOLDER"),
                        color = accent, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp,
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = {
                            if (isWebRunning) webServer.stopWebServer() else webServer.startWebServer()
                            isWebRunning = webServer.isWebRunning
                        },
                        modifier = Modifier.weight(1f).height(40.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (isWebRunning) danger.copy(alpha = 0.18f) else accent.copy(alpha = 0.18f),
                        ),
                        border = androidx.compose.foundation.BorderStroke(1.dp, if (isWebRunning) danger else accent),
                        shape = CutCornerShape(10.dp),
                        enabled = !isCopying,
                    ) {
                        Text(
                            if (isWebRunning) Tx.t("STOP", "STOP") else Tx.t("START", "START"),
                            color = if (isWebRunning) danger else accent,
                            fontWeight = FontWeight.Black, fontSize = 11.sp, letterSpacing = 1.sp,
                        )
                    }
                    if (isWebRunning) {
                        Button(
                            onClick = { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("http://127.0.0.1:${webServer.webPort}"))) },
                            modifier = Modifier.weight(1f).height(40.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = bgInset),
                            shape = CutCornerShape(10.dp),
                        ) {
                            Icon(Icons.Rounded.OpenInBrowser, null, tint = accent, modifier = Modifier.size(14.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(Tx.t("BUKA", "OPEN"), color = accent, fontWeight = FontWeight.Black, fontSize = 11.sp)
                        }
                    }
                }
            }
        }

        item {
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(modifier = Modifier.size(4.dp).background(accent))
                Spacer(Modifier.width(8.dp))
                Text("API ENDPOINTS · v1", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Black, letterSpacing = 2.sp)
                Spacer(Modifier.width(8.dp))
                Text("${buildEndpointSpecs().size} routes", color = textDim, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
                Spacer(Modifier.weight(1f))
                if (!isApiRunning) {
                    Text(Tx.t("NYALAKAN SERVER UNTUK TES", "START SERVER TO TEST"), color = Color(0xFFFFB300), fontSize = 9.sp, fontWeight = FontWeight.Bold)
                }
            }
        }

        items(buildEndpointSpecs()) { spec ->
            ApiEndpointCard(
                spec = spec,
                baseUrl = baseUrl,
                serverRunning = isApiRunning,
                accent = accent,
                danger = danger,
                bgCard = bgCard,
                bgInset = bgInset,
                textDim = textDim,
            )
        }

        item {
            Box(
                modifier = Modifier.fillMaxWidth().clip(CutCornerShape(10.dp))
                    .background(bgCard).border(1.dp, Color(0xFF202028), CutCornerShape(10.dp))
                    .padding(14.dp),
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("USAGE FROM EXTERNAL CLIENT", color = textDim, fontSize = 9.sp, fontFamily = FontFamily.Monospace, letterSpacing = 1.5.sp)
                    Text(
                        "Server binds to 127.0.0.1 loopback for secure local IPC. " +
                            "Third-party apps, overlays, automation tools, or PC utilities (via 'adb forward tcp:8080 tcp:8080') can interact with all endpoints.",
                        color = Color(0xFFCCCCCC), fontSize = 11.sp, lineHeight = 16.sp,
                    )
                }
            }
        }
    }
}

data class ApiEndpointSpec(
    val method: String,
    val path: String,
    val description: String,
    val params: List<Pair<String, String>>,
    val sampleResponse: String,
    val testable: Boolean = true,
    val requiresQuery: Boolean = false,
    val defaultQuery: String = "",
)

private fun buildEndpointSpecs(): List<ApiEndpointSpec> = listOf(
    ApiEndpointSpec(
        method = "GET", path = "/api/status",
        description = "Health check. Returns system health, app version, active backend, and uptime.",
        params = emptyList(),
        sampleResponse = "{\n  \"status\": \"online\",\n  \"app\": \"Game Nuke\",\n  \"version\": \"1.2.0\",\n  \"backend\": \"IADB\",\n  \"backend_connected\": true\n}",
    ),
    ApiEndpointSpec(
        method = "GET", path = "/api/telemetry",
        description = "Real-time hardware metrics: CPU load %, RAM usage, storage, battery, display refresh rate, and network.",
        params = emptyList(),
        sampleResponse = "{\n  \"cpu_load_percent\": 18,\n  \"ram\": {\"total_mb\": 7820, \"used_mb\": 4210, \"used_percent\": 53},\n  \"battery\": {\"level_percent\": 84, \"temperature_c\": 36.5},\n  \"display\": {\"current_hz\": 120}\n}",
    ),
    ApiEndpointSpec(
        method = "POST", path = "/api/exec",
        description = "Execute a shell command with elevated privileges (Shizuku / Wireless ADB) and return exit code, output, and execution time.",
        params = listOf("cmd" to "string (shell command)"),
        sampleResponse = "{\n  \"success\": true,\n  \"command\": \"getprop ro.build.version.release\",\n  \"exit_code\": 0,\n  \"output\": \"15\\n\",\n  \"duration_ms\": 24\n}",
        requiresQuery = true,
        defaultQuery = "cmd=getprop ro.build.version.release",
    ),
    ApiEndpointSpec(
        method = "SSE", path = "/api/stream",
        description = "Server-Sent Events (SSE) live telemetry stream (1 Hz update rate).",
        params = emptyList(),
        sampleResponse = "data: {\"cpu_load_percent\": 18, \"ram\": {\"used_percent\": 53}, \"temp_c\": 36.5}\n\n",
        testable = false,
    ),
    ApiEndpointSpec(
        method = "GET", path = "/api/thermal",
        description = "Hardware thermal throttling status, thermal headroom, and battery temperature.",
        params = emptyList(),
        sampleResponse = "{\n  \"thermal_status\": \"NONE\",\n  \"thermal_status_code\": 0,\n  \"battery_temperature_c\": 36.5,\n  \"is_throttling\": false\n}",
    ),
    ApiEndpointSpec(
        method = "GET", path = "/api/device",
        description = "Hardware specifications: Manufacturer, Model, Android OS version, SDK level, and ABIs.",
        params = emptyList(),
        sampleResponse = "{\n  \"manufacturer\": \"Xiaomi\",\n  \"model\": \"POCO X6 Pro 5G\",\n  \"android_version\": \"14\",\n  \"sdk_int\": 34\n}",
    ),
    ApiEndpointSpec(
        method = "GET", path = "/api/packages/info",
        description = "Inspect package details: version, targetSdk, system status, and game classification.",
        params = listOf("package" to "string (package name)"),
        sampleResponse = "{\n  \"success\": true,\n  \"package\": \"com.neon.gametweak\",\n  \"app_name\": \"Game Nuke\",\n  \"version_name\": \"1.2.0\",\n  \"is_system\": false\n}",
        requiresQuery = true,
        defaultQuery = "package=com.neon.gametweak",
    ),
    ApiEndpointSpec(
        method = "POST", path = "/api/overlay",
        description = "Control or inspect the floating Gaming Cockpit HUD overlay.",
        params = listOf("action" to "start | stop | toggle | status"),
        sampleResponse = "{\n  \"success\": true,\n  \"action\": \"status\",\n  \"overlay_active\": true\n}",
        requiresQuery = true,
        defaultQuery = "action=status",
    ),
    ApiEndpointSpec(
        method = "GET", path = "/api/integrity",
        description = "Check network integrity and resolve Private DNS ad-blocking.",
        params = listOf("action" to "check | disable (optional)"),
        sampleResponse = "{\n  \"is_detected\": false,\n  \"filter_name\": \"None\",\n  \"core_tools_accessible\": true\n}",
        requiresQuery = false,
        defaultQuery = "action=check",
    ),
    ApiEndpointSpec(
        method = "POST", path = "/api/battery/profile",
        description = "Get or apply system power and refresh rate profiles.",
        params = listOf("mode" to "performance | balanced | powersave (optional)"),
        sampleResponse = "{\n  \"success\": true,\n  \"active_profile\": \"performance\"\n}",
        requiresQuery = false,
        defaultQuery = "mode=performance",
    ),
    ApiEndpointSpec(
        method = "POST", path = "/api/toast",
        description = "Dispatch a local toast notification directly on the device.",
        params = listOf("message" to "string (notification message)"),
        sampleResponse = "{\n  \"success\": true,\n  \"delivered\": true,\n  \"message\": \"Game Nuke IPC Notification\"\n}",
        requiresQuery = true,
        defaultQuery = "message=Game Nuke REST API Active",
    ),
    ApiEndpointSpec(
        method = "GET", path = "/api/games",
        description = "List all discovered games and installed gaming apps on this device.",
        params = emptyList(),
        sampleResponse = "{\n  \"count\": 1,\n  \"games\": [\n    {\"package\": \"com.dts.freefireth\", \"title\": \"Free Fire\", \"installed\": true}\n  ]\n}",
    ),
    ApiEndpointSpec(
        method = "GET", path = "/api/games/launch",
        description = "Launch a specific game session with gaming HUD overlay.",
        params = listOf("package" to "string"),
        sampleResponse = "{\n  \"success\": true,\n  \"package\": \"com.dts.freefireth\"\n}",
        requiresQuery = true,
        defaultQuery = "package=com.dts.freefireth",
    ),
    ApiEndpointSpec(
        method = "GET", path = "/api/display",
        description = "Get display refresh rate capabilities, active modes, resolution, and density.",
        params = emptyList(),
        sampleResponse = "{\n  \"current_hz\": 120,\n  \"max_hz\": 120,\n  \"supported_hz\": [60, 90, 120]\n}",
    ),
    ApiEndpointSpec(
        method = "GET", path = "/api/display/fps",
        description = "Set active display refresh rate via system display manager.",
        params = listOf("hz" to "number (30..240)"),
        sampleResponse = "{\n  \"success\": true,\n  \"target_hz\": 120\n}",
        requiresQuery = true,
        defaultQuery = "hz=120",
    ),
    ApiEndpointSpec(
        method = "GET", path = "/api/boost",
        description = "Perform memory optimization and game session compaction.",
        params = emptyList(),
        sampleResponse = "{\n  \"success\": true,\n  \"action\": \"boost_completed\",\n  \"privileged\": true\n}",
    ),
    ApiEndpointSpec(
        method = "GET", path = "/api/cleaner/trim",
        description = "Trigger system memory compaction and cache trim.",
        params = emptyList(),
        sampleResponse = "{\n  \"success\": true,\n  \"privileged\": true,\n  \"ram_freed_mb\": 340\n}",
    ),
    ApiEndpointSpec(
        method = "GET", path = "/api/dnd",
        description = "Check or toggle Game Do-Not-Disturb (DND) focus mode.",
        params = listOf("enable" to "boolean (optional)"),
        sampleResponse = "{\n  \"dnd_active\": true,\n  \"permission_granted\": true\n}",
    ),
    ApiEndpointSpec(
        method = "GET", path = "/api/processes",
        description = "Query active user process list and memory consumption.",
        params = emptyList(),
        sampleResponse = "{\n  \"processes\": [\n    {\"pid\": 1234, \"memory_mb\": 256, \"name\": \"com.app.game\"}\n  ]\n}",
    ),
    ApiEndpointSpec(
        method = "GET", path = "/api/docs",
        description = "OpenAPI 3.0 compatible JSON specification and route documentation.",
        params = emptyList(),
        sampleResponse = "{\n  \"openapi\": \"3.0.0\",\n  \"info\": {\"title\": \"Game Nuke Local REST API\"}\n}",
    ),
)

@Composable
fun ApiEndpointCard(
    spec: ApiEndpointSpec,
    baseUrl: String,
    serverRunning: Boolean,
    accent: Color,
    danger: Color,
    bgCard: Color,
    bgInset: Color,
    textDim: Color,
) {
    var expanded by remember { mutableStateOf(false) }
    var queryInput by remember(spec.path) { mutableStateOf(spec.defaultQuery) }
    var responseText by remember(spec.path) { mutableStateOf("") }
    var loading by remember(spec.path) { mutableStateOf(false) }
    var statusCode by remember(spec.path) { mutableStateOf<Int?>(null) }
    var elapsedMs by remember(spec.path) { mutableStateOf<Long?>(null) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    val methodColor = when (spec.method) {
        "GET" -> Color(0xFF4CAF50)
        "POST" -> Color(0xFF2196F3)
        "SSE" -> Color(0xFFFFB300)
        else -> textDim
    }

    val fullUrlPreview = baseUrl + spec.path + if (queryInput.isNotBlank()) "?$queryInput" else ""

    Column(
        modifier = Modifier.fillMaxWidth().clip(CutCornerShape(12.dp))
            .background(bgCard).border(1.dp, Color(0xFF202028), CutCornerShape(12.dp)),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().clickable { expanded = !expanded }.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier.clip(CutCornerShape(4.dp))
                    .background(methodColor.copy(alpha = 0.18f))
                    .border(1.dp, methodColor.copy(alpha = 0.5f), CutCornerShape(4.dp))
                    .padding(horizontal = 8.dp, vertical = 3.dp),
            ) {
                Text(spec.method, color = methodColor, fontSize = 10.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Black)
            }
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(spec.path, color = accent, fontSize = 13.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
                Text(spec.description, color = textDim, fontSize = 10.sp, lineHeight = 14.sp)
            }
            Icon(
                if (expanded) Icons.Rounded.KeyboardArrowUp else Icons.Rounded.KeyboardArrowDown,
                null, tint = textDim, modifier = Modifier.size(20.dp),
            )
        }

        if (expanded) {
            HorizontalDivider(color = Color(0xFF202028))

            Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (spec.params.isNotEmpty()) {
                    Text("PARAMETERS", color = textDim, fontSize = 9.sp, fontFamily = FontFamily.Monospace, letterSpacing = 1.5.sp)
                    spec.params.forEach { (name, typeHint) ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(name, color = accent, fontSize = 11.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
                            Spacer(Modifier.width(8.dp))
                            Box(modifier = Modifier.clip(CutCornerShape(3.dp)).background(bgInset).padding(horizontal = 6.dp, vertical = 1.dp)) {
                                Text(typeHint, color = textDim, fontSize = 9.sp, fontFamily = FontFamily.Monospace)
                            }
                            Spacer(Modifier.width(6.dp))
                            Text("required", color = Color(0xFFFFB300), fontSize = 9.sp, fontFamily = FontFamily.Monospace)
                        }
                    }
                }

                Text("SAMPLE RESPONSE", color = textDim, fontSize = 9.sp, fontFamily = FontFamily.Monospace, letterSpacing = 1.5.sp)
                SelectionContainer {
                    Box(
                        modifier = Modifier.fillMaxWidth().clip(CutCornerShape(6.dp))
                            .background(Color(0xFF050507))
                            .border(1.dp, Color(0xFF1A1A22), CutCornerShape(6.dp))
                            .padding(10.dp),
                    ) {
                        Text(spec.sampleResponse, color = Color(0xFFE0E0E0), fontSize = 11.sp, fontFamily = FontFamily.Monospace, lineHeight = 16.sp)
                    }
                }

                if (spec.testable) {
                    Text("TRY IT", color = textDim, fontSize = 9.sp, fontFamily = FontFamily.Monospace, letterSpacing = 1.5.sp)

                    if (spec.requiresQuery) {
                        OutlinedTextField(
                            value = queryInput,
                            onValueChange = { queryInput = it },
                            modifier = Modifier.fillMaxWidth(),
                            placeholder = { Text("query string (e.g. ${spec.defaultQuery})", color = textDim, fontSize = 11.sp) },
                            singleLine = true,
                            textStyle = TextStyle(color = Color.White, fontSize = 12.sp, fontFamily = FontFamily.Monospace),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = accent,
                                unfocusedBorderColor = Color(0xFF202028),
                            ),
                            shape = CutCornerShape(8.dp),
                        )
                    }

                    val curlCommand = if (spec.method == "POST") {
                        if (queryInput.isNotBlank()) {
                            "curl -X POST '${baseUrl + spec.path}' -d \"$queryInput\""
                        } else {
                            "curl -X POST '${baseUrl + spec.path}'"
                        }
                    } else {
                        if (queryInput.isNotBlank()) {
                            val safeQuery = queryInput.split("&").joinToString("&") { part ->
                                val pair = part.split("=", limit = 2)
                                if (pair.size > 1) {
                                    "${pair[0]}=${java.net.URLEncoder.encode(pair[1], "UTF-8")}"
                                } else {
                                    part
                                }
                            }
                            "curl '${baseUrl + spec.path}?$safeQuery'"
                        } else {
                            "curl '${baseUrl + spec.path}'"
                        }
                    }

                    Box(
                        modifier = Modifier.fillMaxWidth().clip(CutCornerShape(6.dp))
                            .background(bgInset).padding(8.dp),
                    ) {
                        SelectionContainer {
                            Text(curlCommand, color = accent, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
                        }
                    }

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = {
                                if (loading) return@Button
                                loading = true
                                responseText = ""
                                statusCode = null
                                elapsedMs = null
                                scope.launch(Dispatchers.IO) {
                                    val started = System.currentTimeMillis()
                                    val targetUrl = if (spec.method == "POST") {
                                        baseUrl + spec.path
                                    } else {
                                        if (queryInput.isNotBlank()) "$baseUrl${spec.path}?$queryInput" else baseUrl + spec.path
                                    }
                                    val (code, body) = httpRequestSimple(
                                        url = targetUrl,
                                        method = spec.method,
                                        bodyPayload = if (spec.method == "POST") queryInput else null,
                                    )
                                    val ended = System.currentTimeMillis()
                                    withContext(Dispatchers.Main) {
                                        statusCode = code
                                        responseText = body
                                        elapsedMs = ended - started
                                        loading = false
                                    }
                                }
                            },
                            enabled = serverRunning && !loading,
                            modifier = Modifier.weight(1f).height(40.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = accent.copy(alpha = 0.18f),
                                disabledContainerColor = Color(0xFF101018),
                            ),
                            border = androidx.compose.foundation.BorderStroke(
                                1.dp,
                                if (serverRunning) accent else Color(0xFF202028),
                            ),
                            shape = CutCornerShape(8.dp),
                        ) {
                            if (loading) {
                                CircularProgressIndicator(modifier = Modifier.size(14.dp), color = accent, strokeWidth = 2.dp)
                                Spacer(Modifier.width(8.dp))
                            } else {
                                @Suppress("DEPRECATION")
                                Icon(Icons.Rounded.Send, null, tint = if (serverRunning) accent else textDim, modifier = Modifier.size(14.dp))
                                Spacer(Modifier.width(6.dp))
                            }
                            Text(
                                if (loading) "SENDING..." else "SEND REQUEST",
                                color = if (serverRunning) accent else textDim,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Black,
                                letterSpacing = 1.sp,
                            )
                        }
                        OutlinedButton(
                            onClick = {
                                val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? android.content.ClipboardManager
                                cm?.setPrimaryClip(android.content.ClipData.newPlainText("curl", curlCommand))
                                NukeToast.success(context, Tx.t("Disalin sebagai cURL", "Copied as cURL"))
                            },
                            modifier = Modifier.height(40.dp),
                            border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF303040)),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = textDim),
                            shape = CutCornerShape(8.dp),
                        ) {
                            Icon(Icons.Rounded.ContentCopy, null, tint = textDim, modifier = Modifier.size(14.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("cURL", color = textDim, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                        }
                    }

                    if (responseText.isNotEmpty() || statusCode != null) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            val responseCode = statusCode
                            val codeColor = when (responseCode) {
                                null -> textDim
                                in 200..299 -> Color(0xFF4CAF50)
                                in 400..599 -> danger
                                else -> Color(0xFFFFB300)
                            }
                            Box(
                                modifier = Modifier.clip(CutCornerShape(4.dp))
                                    .background(codeColor.copy(alpha = 0.18f))
                                    .padding(horizontal = 8.dp, vertical = 3.dp),
                            ) {
                                Text(
                                    "HTTP ${statusCode ?: "?"}",
                                    color = codeColor, fontSize = 10.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Black,
                                )
                            }
                            elapsedMs?.let {
                                Spacer(Modifier.width(8.dp))
                                Text("· ${it}ms", color = textDim, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
                            }
                        }
                        SelectionContainer {
                            Box(
                                modifier = Modifier.fillMaxWidth().heightIn(max = 280.dp).clip(CutCornerShape(6.dp))
                                    .background(Color(0xFF050507))
                                    .border(1.dp, Color(0xFF1A1A22), CutCornerShape(6.dp))
                                    .padding(10.dp).verticalScroll(rememberScrollState()),
                            ) {
                                Text(responseText, color = Color(0xFFE0E0E0), fontSize = 11.sp, fontFamily = FontFamily.Monospace, lineHeight = 16.sp)
                            }
                        }
                    }
                } else {
                    Box(
                        modifier = Modifier.fillMaxWidth().clip(CutCornerShape(6.dp))
                            .background(bgInset).padding(10.dp),
                    ) {
                        Text(
                            "SSE endpoint — open in browser or use EventSource client.",
                            color = textDim, fontSize = 10.sp, fontFamily = FontFamily.Monospace, lineHeight = 14.sp,
                        )
                    }
                }
            }
        }
    }
}

private fun httpRequestSimple(url: String, method: String, bodyPayload: String? = null): Pair<Int, String> {
    return try {
        val u = java.net.URL(url)
        val conn = u.openConnection() as java.net.HttpURLConnection
        conn.connectTimeout = 5000
        conn.readTimeout = 25000
        conn.requestMethod = if (method == "SSE") "GET" else method
        if (method == "POST" && !bodyPayload.isNullOrBlank()) {
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=utf-8")
            val bytes = bodyPayload.toByteArray(Charsets.UTF_8)
            conn.setRequestProperty("Content-Length", bytes.size.toString())
            conn.outputStream.use { it.write(bytes) }
        }
        val code = conn.responseCode
        val stream = if (code in 200..299) conn.inputStream else (conn.errorStream ?: conn.inputStream)
        val body = stream?.bufferedReader()?.use { it.readText() } ?: ""
        conn.disconnect()
        code to body
    } catch (t: Throwable) {
        -1 to "ERROR: ${t.message ?: t::class.java.simpleName}"
    }
}

private fun copyFolderRecursive(context: Context, treeUri: Uri, parentDocId: String, destDir: File) {
    val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentDocId)
    val cursor = context.contentResolver.query(childrenUri, arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME, DocumentsContract.Document.COLUMN_MIME_TYPE), null, null, null)
    cursor?.use {
        while (it.moveToNext()) {
            val docId = it.getString(0)
            val name = it.getString(1)
            val mime = it.getString(2)
            if (mime == DocumentsContract.Document.MIME_TYPE_DIR) {
                val newDir = File(destDir, name).apply { mkdirs() }
                copyFolderRecursive(context, treeUri, docId, newDir)
            } else {
                val fileUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, docId)
                try { context.contentResolver.openInputStream(fileUri)?.use { input -> File(destDir, name).outputStream().use { output -> input.copyTo(output) } } } catch (e: Exception) {}
            }
        }
    }
}

@Composable
fun DevScreen() {
    val accent = Color(0xFF35C99B)
    val textDim = Color(0xFF9BB0A6)

    Column(
        modifier = Modifier.fillMaxSize().background(Color(0xFF020705))
            .verticalScroll(rememberScrollState()).padding(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Spacer(Modifier.height(20.dp))
        Box(
            modifier = Modifier.size(140.dp)
                .background(Color(0xFF06100C), CutCornerShape(24.dp))
                .border(1.5.dp, accent.copy(alpha = 0.6f), CutCornerShape(24.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Rounded.AccountCircle, null, tint = accent, modifier = Modifier.size(96.dp))
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.width(3.dp).height(24.dp).background(accent))
            Spacer(Modifier.width(9.dp))
            Text("AGUNG DEV", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Black, letterSpacing = 4.sp)
        }
        Text(
            "SYSTEM OPTIMIZATION ARCHITECT",
            color = accent, fontSize = 11.sp, fontWeight = FontWeight.Bold,
            letterSpacing = 2.5.sp, fontFamily = FontFamily.Monospace,
        )
        Box(
            modifier = Modifier.fillMaxWidth().height(1.dp).background(
                androidx.compose.ui.graphics.Brush.horizontalGradient(
                    listOf(Color.Transparent, accent.copy(alpha = 0.4f), Color.Transparent)
                )
            )
        )
        Spacer(Modifier.height(8.dp))

        Column(
            modifier = Modifier.fillMaxWidth().clip(CutCornerShape(12.dp))
                .background(Color(0xFF06100C))
                .border(1.dp, Color(0xFF202028), CutCornerShape(12.dp))
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(modifier = Modifier.size(4.dp).background(accent))
                Spacer(Modifier.width(8.dp))
                Text(Tx.t("PERNYATAAN MISI", "MISSION STATEMENT"), color = textDim, fontSize = 10.sp, fontFamily = FontFamily.Monospace, letterSpacing = 2.sp)
            }
            Text(
                Tx.t(
                    "Game Nuke beroperasi di bawah limitasi sistem dengan validasi keamanan ketat. Tweaks tervalidasi & non-destructive.",
                    "Game Nuke operates under OS limitations with strict safety validation. Tweaks are validated & non-destructive."
                ),
                color = Color(0xFFCFCFCF), fontSize = 12.sp, lineHeight = 18.sp,
            )
        }

        Column(
            modifier = Modifier.fillMaxWidth().clip(CutCornerShape(12.dp))
                .background(Color(0xFF06100C))
                .border(1.dp, Color(0xFF202028), CutCornerShape(12.dp))
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(modifier = Modifier.size(4.dp).background(accent))
                Spacer(Modifier.width(8.dp))
                Text(Tx.t("TEKNOLOGI SISTEM", "TECH STACK"), color = textDim, fontSize = 10.sp, fontFamily = FontFamily.Monospace, letterSpacing = 2.sp)
            }
            listOf(
                "Kotlin · Jetpack Compose · Material 3",
                "Capability Engine · Session Rollback · Thermal Guard",
                "Adaptive gaming controls · Live telemetry · Session safety",
            ).forEach { stack ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(4.dp).background(accent.copy(alpha = 0.75f)))
                    Spacer(Modifier.width(8.dp))
                    Text(stack, color = Color.White, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                }
            }
        }
        Spacer(Modifier.height(20.dp))
    }
}

@Composable
fun TutorialScreen() {
    val accent = Color(0xFF35C99B)
    val textDim = Color(0xFF9BB0A6)
    val bgCard = Color(0xFF06100C)
    val bgInset = Color(0xFF0D1B15)

    LazyColumn(
        modifier = Modifier.fillMaxSize().background(Color(0xFF020705)),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        val steps = listOf(
            Triple("01", Tx.t("Buka Developer Options", "Open Developer Options"), Tx.t("Settings → System → Developer Options. Aktifkan jika belum.", "Settings → System → Developer Options. Enable if hidden.")),
            Triple("02", Tx.t("Aktifkan Wireless Debugging", "Enable Wireless Debugging"), Tx.t("Toggle 'Wireless debugging' di Developer Options.", "Toggle 'Wireless debugging' in Developer Options.")),
            Triple("03", Tx.t("Pair Device", "Pair Device"), Tx.t("Tap 'Pair device with pairing code'. Catat kode 6-digit + port.", "Tap 'Pair device with pairing code'. Note 6-digit code + port.")),
            Triple("04", Tx.t("Input ke App", "Input to App"), Tx.t("Buka tab Pair di aplikasi → masukkan IP, port, kode.", "Open Pair tab in app → input IP, port, code.")),
            Triple("05", Tx.t("Core Link Online", "Core Link Online"), Tx.t("Status berubah jadi ENGINE ONLINE. Game Nuke hanya mengaktifkan jalur yang lolos capability check di HP ini.", "Status switches to ENGINE ONLINE. Game Nuke only enables paths that pass this device's capability check.")),
        )
        item {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Box(modifier = Modifier.size(8.dp).background(accent, CircleShape))
                Spacer(Modifier.width(8.dp))
                Text(Tx.t("PANDUAN INISIALISASI", "INITIALIZATION SEQUENCE"), color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Black, letterSpacing = 2.sp)
                Spacer(Modifier.width(8.dp))
                Text("${steps.size} ${Tx.t("langkah", "steps")}", color = textDim, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
            }
        }
        items(steps) { (num, title, desc) ->
            Row(
                modifier = Modifier.fillMaxWidth().clip(CutCornerShape(12.dp))
                    .background(bgCard)
                    .border(1.dp, Color(0xFF202028), CutCornerShape(12.dp))
                    .padding(14.dp),
                verticalAlignment = Alignment.Top,
            ) {
                Box(
                    modifier = Modifier.size(38.dp).clip(CutCornerShape(8.dp))
                        .background(accent.copy(alpha = 0.15f))
                        .border(1.dp, accent.copy(alpha = 0.5f), CutCornerShape(8.dp)),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(num, color = accent, fontSize = 13.sp, fontWeight = FontWeight.Black, fontFamily = FontFamily.Monospace)
                }
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(title, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(4.dp))
                    Text(desc, color = Color(0xFFAAAAAA), fontSize = 11.sp, lineHeight = 15.sp)
                }
            }
        }
        item { Spacer(Modifier.height(8.dp)) }
        item {
            Box(
                modifier = Modifier.fillMaxWidth().clip(CutCornerShape(12.dp))
                    .background(bgInset)
                    .border(1.dp, accent.copy(alpha = 0.3f), CutCornerShape(12.dp))
                    .padding(14.dp),
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.Info, null, tint = accent, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(Tx.t("TIPS PENTING", "PRO TIP"), color = accent, fontSize = 10.sp, fontFamily = FontFamily.Monospace, letterSpacing = 2.sp, fontWeight = FontWeight.Bold)
                    }
                    Text(
                        Tx.t(
                            "Pairing key disimpan setelah pairing berhasil. Port koneksi Wireless Debugging dapat berubah setelah reboot/jaringan berubah, jadi Game Nuke memakai discovery + reconnect dan tetap melakukan capability check ulang.",
                            "The pairing key is retained after a successful pair. Wireless Debugging connection ports can change after reboot/network changes, so Game Nuke uses discovery + reconnect and re-checks capabilities."
                        ),
                        color = Color(0xFFCCCCCC), fontSize = 11.sp, lineHeight = 16.sp,
                    )
                }
            }
        }
    }
}

data class GameApp(val name: String, val packageName: String, val detected: Boolean)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GameProfileScreen(adbManager: AdbManager) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var gameList by remember { mutableStateOf<List<GameApp>>(emptyList()) }
    var allLaunchable by remember { mutableStateOf<List<GameApp>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }
    var showAllApps by remember { mutableStateOf(false) }
    var launchingPackage by remember { mutableStateOf<String?>(null) }
    var pendingGameToLaunch by remember { mutableStateOf<GameApp?>(null) }
    var showAdBlockDialog by remember { mutableStateOf(false) }
    var adBlockStatus by remember { mutableStateOf(AdBlockStatus()) }

    fun loadIcon(packageName: String): ImageBitmap? = try {
        val iconDrawable = context.packageManager.getApplicationIcon(packageName)
        val maxPx = 96
        val rawW = iconDrawable.intrinsicWidth.coerceAtLeast(1)
        val rawH = iconDrawable.intrinsicHeight.coerceAtLeast(1)
        val scale = minOf(1f, maxPx.toFloat() / maxOf(rawW, rawH).toFloat())
        val width = (rawW * scale).toInt().coerceAtLeast(1)
        val height = (rawH * scale).toInt().coerceAtLeast(1)
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        iconDrawable.setBounds(0, 0, width, height)
        iconDrawable.draw(canvas)
        bitmap.asImageBitmap()
    } catch (_: Exception) { null }

    fun refreshCatalog() {
        isLoading = true
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                val scan = GameCatalogRepository.scan(context)
                val all = scan.allLaunchable.map { entry ->
                    GameApp(entry.label, entry.packageName, entry.detectedAsGame)
                }
                val detectedPkgs = scan.games.mapTo(hashSetOf()) { it.packageName }
                all.filter { it.packageName in detectedPkgs } to all
            }
            gameList = result.first
            allLaunchable = result.second
            if (result.first.isEmpty() && result.second.isNotEmpty()) showAllApps = true
            isLoading = false
        }
    }

    LaunchedEffect(Unit) { refreshCatalog() }
    val visibleApps = if (showAllApps) allLaunchable else gameList

    Box(modifier = Modifier.fillMaxSize().background(Color(0xFF020705))) {
        Column(modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    Box(modifier = Modifier.size(8.dp).background(Color(0xFF35C99B), CircleShape))
                    Spacer(Modifier.width(8.dp))
                    Text(Tx.t("GAME SPACE // PELUNCUR SESI", "GAME SPACE // SESSION LAUNCHER"), color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Black, letterSpacing = 1.5.sp)
                }
                Text(
                    Tx.t(
                        "Game Nuke membaca aplikasi launcher yang terlihat melalui package-visibility Android, lalu memprioritaskan aplikasi kategori game. Jika metadata OEM/game tidak lengkap, gunakan ALL APPS sekali; aplikasi yang dipilih akan diingat sebagai game.",
                        "Game Nuke reads launcher apps through Android package visibility and prioritizes apps categorized as games. If OEM/game metadata is incomplete, use ALL APPS once; the selected app will be remembered as a game.",
                    ),
                    color = Color(0xFF9BB0A6), fontSize = 11.sp, lineHeight = 15.sp,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Box(
                        Modifier.weight(1f).height(38.dp).clip(CutCornerShape(topStart = 9.dp, bottomEnd = 9.dp))
                            .background(if (!showAllApps) Color(0xFF35C99B).copy(alpha = .18f) else Color(0xFF07110D))
                            .border(1.dp, if (!showAllApps) Color(0xFF35C99B) else Color(0xFF26362F), CutCornerShape(topStart = 9.dp, bottomEnd = 9.dp))
                            .clickable { showAllApps = false },
                        contentAlignment = Alignment.Center,
                    ) {
                        Text("${Tx.t("TERDETEKSI", "DETECTED")}  ${gameList.size}", color = if (!showAllApps) Color(0xFF35C99B) else Color(0xFF9BB0A6), fontSize = 9.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Black, letterSpacing = 1.sp)
                    }
                    Box(
                        Modifier.weight(1f).height(38.dp).clip(CutCornerShape(topEnd = 9.dp, bottomStart = 9.dp))
                            .background(if (showAllApps) Color(0xFF35C99B).copy(alpha = .18f) else Color(0xFF07110D))
                            .border(1.dp, if (showAllApps) Color(0xFF35C99B) else Color(0xFF26362F), CutCornerShape(topEnd = 9.dp, bottomStart = 9.dp))
                            .clickable { showAllApps = true },
                        contentAlignment = Alignment.Center,
                    ) {
                        Text("${Tx.t("SEMUA APLIKASI", "ALL APPS")}  ${allLaunchable.size}", color = if (showAllApps) Color(0xFF35C99B) else Color(0xFF9BB0A6), fontSize = 9.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Black, letterSpacing = 1.sp)
                    }
                }
                Box(
                    Modifier.fillMaxWidth().clip(CutCornerShape(topStart = 7.dp, bottomEnd = 7.dp))
                        .background(Color(0xFF06110D))
                        .border(1.dp, Color(0xFF35C99B).copy(alpha = 0.24f), CutCornerShape(topStart = 7.dp, bottomEnd = 7.dp))
                        .padding(horizontal = 10.dp, vertical = 7.dp),
                ) {
                    Text(
                        Tx.t(
                            "HUD membutuhkan Display over other apps. Session overlay dijalankan sebagai foreground gaming session yang dimulai oleh user agar lebih stabil selama game panjang.",
                            "The HUD needs Display over other apps. The overlay runs as a user-started foreground gaming session for better reliability during long sessions.",
                        ),
                        color = Color(0xFF9BB0A6), fontSize = 9.sp, lineHeight = 13.sp,
                    )
                }
            }

            Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(Color(0xFF202028)))

            if (isLoading) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator(color = Color(0xFF35C99B), strokeWidth = 2.dp, modifier = Modifier.size(32.dp))
                        Spacer(Modifier.height(8.dp))
                        Text(Tx.t("MEMINDAI PELUNCUR...", "SCANNING LAUNCHERS"), color = Color(0xFF9BB0A6), fontSize = 10.sp, fontFamily = FontFamily.Monospace, letterSpacing = 2.sp)
                    }
                }
            } else if (visibleApps.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(24.dp)) {
                        Icon(Icons.Rounded.Gamepad, null, tint = Color(0xFF9BB0A6), modifier = Modifier.size(44.dp))
                        Spacer(Modifier.height(10.dp))
                        Text(Tx.t("TIDAK ADA KATEGORI GAME DITEMUKAN", "NO GAME CATEGORY FOUND"), color = Color(0xFF9BB0A6), fontSize = 11.sp, fontFamily = FontFamily.Monospace, letterSpacing = 1.5.sp)
                        Spacer(Modifier.height(8.dp))
                        Text(
                            Tx.t("Beberapa game/OEM tidak menandai CATEGORY_GAME. Buka ALL APPS dan pilih game satu kali.", "Some games/OEMs do not expose CATEGORY_GAME. Open ALL APPS and select the game once."),
                            color = Color(0xFF758980), fontSize = 10.sp, textAlign = TextAlign.Center,
                        )
                        Spacer(Modifier.height(14.dp))
                        Button(onClick = { showAllApps = true }, colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF35C99B), contentColor = Color.Black), shape = CutCornerShape(8.dp)) {
                            Text(Tx.t("BUKA SEMUA APLIKASI", "OPEN ALL APPS"), fontSize = 10.sp, fontWeight = FontWeight.Black)
                        }
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(visibleApps, key = { it.packageName }) { game ->
                        val gameIcon by produceState<ImageBitmap?>(initialValue = null, key1 = game.packageName) {
                            value = withContext(Dispatchers.IO) { loadIcon(game.packageName) }
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth().clip(CutCornerShape(12.dp))
                                .background(Color(0xFF06100C))
                                .border(1.dp, if (game.detected) Color(0xFF173A2D) else Color(0xFF202028), CutCornerShape(12.dp))
                                .clickable(enabled = launchingPackage == null) {
                                    val check = NukeAdBlockDetector.checkStatus(context, adbManager)
                                    if (check.isDetected) {
                                        adBlockStatus = check
                                        showAdBlockDialog = true
                                        launchingPackage = null
                                        NukeToast.error(
                                            context,
                                            "AdBlock detected! Please disable your AdBlock / Private DNS to use core tools and boost games.",
                                            long = true
                                        )
                                        return@clickable
                                    }

                                    val pkg = game.packageName
                                    launchingPackage = pkg
                                    if (!OverlayPermissionController.hasOverlayPermission(context)) {
                                        OverlayPermissionController.requestManualGrant(context)
                                        NukeToast.unsupported(context, Tx.t("Aktifkan izin overlay, lalu tap game ini lagi.", "Enable overlay permission, then tap this game again."), long = true)
                                        launchingPackage = null
                                    } else {
                                        if (!game.detected) GameCatalogRepository.rememberAsGame(context, pkg)
                                        val gameLaunch = runCatching { GameCatalogRepository.resolveLaunchIntent(context, pkg) }.getOrNull()
                                        if (gameLaunch == null) {
                                            NukeToast.unsupported(context, Tx.t("Launcher aplikasi tidak ditemukan.", "App launcher was not found."))
                                            launchingPackage = null
                                        } else {
                                            val proceedLaunch = {
                                                val cinematic = Intent(context, com.neon.gametweak.GameLaunchSplashActivity::class.java).apply {
                                                    putExtra(com.neon.gametweak.GameLaunchSplashActivity.EXTRA_GAME_PACKAGE, pkg)
                                                    putExtra(com.neon.gametweak.GameLaunchSplashActivity.EXTRA_LAUNCH_TOKEN, java.util.UUID.randomUUID().toString())
                                                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                                }
                                                if (runCatching { context.startActivity(cinematic) }.isFailure) {
                                                    NukeToast.error(context, Tx.t("Game gagal dibuka.", "The game could not be opened."))
                                                    launchingPackage = null
                                                } else {
                                                    scope.launch {
                                                        delay(1_800L)
                                                        if (launchingPackage == pkg) launchingPackage = null
                                                    }
                                                }
                                            }

                                            // If VIP Pass is already active: launch immediately with 0 ads!
                                            if (NukeAdManager.isBoosterVipActive(context)) {
                                                proceedLaunch()
                                            } else {
                                                // Show the Booster Rewarded Dialog before launching
                                                pendingGameToLaunch = game
                                            }
                                        }
                                    }
                                }
                                .padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Box(
                                modifier = Modifier.size(48.dp).clip(CutCornerShape(8.dp))
                                    .background(Color(0xFF0D1B15))
                                    .border(1.dp, Color(0xFF35C99B).copy(alpha = 0.3f), CutCornerShape(8.dp)),
                                contentAlignment = Alignment.Center,
                            ) {
                                gameIcon?.let { iconBitmap ->
                                    Image(bitmap = iconBitmap, contentDescription = null, modifier = Modifier.size(36.dp))
                                } ?: run {
                                    Icon(Icons.Rounded.Gamepad, null, tint = Color(0xFF35C99B), modifier = Modifier.size(24.dp))
                                }
                            }
                            Spacer(Modifier.width(14.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(game.name, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                                Spacer(Modifier.height(2.dp))
                                Text(game.packageName, color = Color(0xFF9BB0A6), fontSize = 9.sp, fontFamily = FontFamily.Monospace, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                            }
                            Spacer(Modifier.width(8.dp))
                            Box(
                                modifier = Modifier.height(34.dp).clip(CutCornerShape(7.dp))
                                    .background(Color(0xFF35C99B).copy(alpha = 0.15f))
                                    .border(1.dp, Color(0xFF35C99B), CutCornerShape(7.dp))
                                    .padding(horizontal = 10.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Rounded.PlayArrow, null, tint = Color(0xFF35C99B), modifier = Modifier.size(15.dp))
                                    Spacer(Modifier.width(4.dp))
                                    Text(
                                        if (launchingPackage == game.packageName) "ARMING" else "BOOST",
                                        color = Color(0xFF35C99B),
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.Black,
                                        letterSpacing = 1.5.sp,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        // ── BOOSTER REWARDED CONFIRMATION DIALOG ─────────────────────────────
        val targetGame = pendingGameToLaunch
        if (targetGame != null) {
            val proceedDirectly = {
                val pkg = targetGame.packageName
                val cinematic = Intent(context, com.neon.gametweak.GameLaunchSplashActivity::class.java).apply {
                    putExtra(com.neon.gametweak.GameLaunchSplashActivity.EXTRA_GAME_PACKAGE, pkg)
                    putExtra(com.neon.gametweak.GameLaunchSplashActivity.EXTRA_LAUNCH_TOKEN, java.util.UUID.randomUUID().toString())
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                if (runCatching { context.startActivity(cinematic) }.isFailure) {
                    NukeToast.error(context, Tx.t("Game gagal dibuka.", "The game could not be opened."))
                }
                scope.launch {
                    delay(1_800L)
                    launchingPackage = null
                }
            }

            NukeBoosterRewardDialog(
                gameName = targetGame.name,
                onDismiss = {
                    pendingGameToLaunch = null
                    launchingPackage = null
                },
                onWatchAdAndBoost = {
                    pendingGameToLaunch = null
                    val act = context.findActivity()
                    if (act != null) {
                        NukeToast.success(context, Tx.t("Memuat Video Sponsor...", "Loading Sponsor Video..."))
                        NukeAdManager.showBoosterRewarded(act) { unlockedVip ->
                            if (unlockedVip) {
                                NukeToast.success(context, Tx.t("🎮 Sesi Gaming Tanpa Iklan Aktif!", "🎮 Ad-Free Gaming Session Active!"))
                            }
                            proceedDirectly()
                        }
                    } else {
                        proceedDirectly()
                    }
                }
            )
        }

        if (showAdBlockDialog && adBlockStatus.isDetected) {
            NukeAdBlockDetectedDialog(
                status = adBlockStatus,
                adbManager = adbManager,
                onDismiss = { showAdBlockDialog = false },
            )
        }
    }
}

@Composable
fun NukeBoosterRewardDialog(
    gameName: String,
    onDismiss: () -> Unit,
    onWatchAdAndBoost: () -> Unit,
) {
    androidx.compose.ui.window.Dialog(
        onDismissRequest = onDismiss,
        properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth(0.88f)
                .clip(CutCornerShape(topStart = 20.dp, bottomEnd = 20.dp))
                .background(Color(0xFF07120E))
                .border(1.dp, Color(0xFFFFC857).copy(alpha = 0.65f), CutCornerShape(topStart = 20.dp, bottomEnd = 20.dp))
                .padding(20.dp)
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Box(
                    Modifier.size(48.dp).clip(CircleShape).background(Color(0xFFFFC857).copy(alpha = 0.15f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Rounded.Bolt, null, tint = Color(0xFFFFC857), modifier = Modifier.size(28.dp))
                }
                Spacer(Modifier.height(10.dp))
                Text(Tx.t("MULAI SESI GAMING", "START GAMING SESSION"), color = Color(0xFFFFC857), fontSize = 15.sp, fontWeight = FontWeight.Black, letterSpacing = 1.sp)
                Spacer(Modifier.height(4.dp))
                Text(
                    gameName,
                    color = Color.White,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center
                )
                Spacer(Modifier.height(10.dp))
                Box(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(Color(0xFF0E221A)).padding(10.dp)
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Rounded.CheckCircle, null, tint = Color(0xFF35C99B), modifier = Modifier.size(14.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(Tx.t("Floating Gaming HUD & Crosshair", "Floating Gaming HUD & Crosshair"), color = Color(0xFFE0EAE5), fontSize = 10.sp)
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Rounded.CheckCircle, null, tint = Color(0xFF35C99B), modifier = Modifier.size(14.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(Tx.t("Pengaturan Tampilan & Pengelolaan Memori", "Display Settings & Memory Management"), color = Color(0xFFE0EAE5), fontSize = 10.sp)
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Rounded.CheckCircle, null, tint = Color(0xFF35C99B), modifier = Modifier.size(14.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(Tx.t("Tanpa Iklan Selama Sesi & Akses Fitur Penuh", "No Ads During Session & Full Feature Access"), color = Color(0xFFFFC857), fontSize = 10.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
                Spacer(Modifier.height(14.dp))
                Text(
                    Tx.t(
                        "Tonton 1 video sponsor singkat untuk membuka sesi gaming tanpa iklan dan mengakses semua fitur.",
                        "Watch 1 short sponsor video to unlock an ad-free gaming session and access all features.",
                    ),
                    color = Color(0xFF9BB0A6),
                    fontSize = 9.5.sp,
                    textAlign = TextAlign.Center,
                    lineHeight = 13.sp
                )
                Spacer(Modifier.height(16.dp))
                Button(
                    onClick = onWatchAdAndBoost,
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFFC857), contentColor = Color.Black),
                    shape = CutCornerShape(topStart = 10.dp, bottomEnd = 10.dp),
                    modifier = Modifier.fillMaxWidth().height(44.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.PlayArrow, null, tint = Color.Black, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(Tx.t("TONTON & MULAI", "WATCH & START"), fontSize = 11.sp, fontWeight = FontWeight.Black, letterSpacing = 0.5.sp)
                    }
                }
                Spacer(Modifier.height(8.dp))
                OutlinedButton(
                    onClick = onDismiss,
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFF9BB0A6)),
                    border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF26362F)),
                    shape = CutCornerShape(topStart = 8.dp, bottomEnd = 8.dp),
                    modifier = Modifier.fillMaxWidth().height(36.dp)
                ) {
                    Text(Tx.t("BATAL", "CANCEL"), fontSize = 9.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}
