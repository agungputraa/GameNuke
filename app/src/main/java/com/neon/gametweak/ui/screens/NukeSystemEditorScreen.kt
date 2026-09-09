package com.neon.gametweak.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.ContentPaste
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.FileDownload
import androidx.compose.material.icons.rounded.FileUpload
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.MenuBook
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Restore
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Security
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.Smartphone
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.StarBorder
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.foundation.BorderStroke
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Surface
import com.neon.gametweak.NukeModuleJsonImporter
import com.neon.gametweak.NukeModuleJsonImporter.ParsedModuleResult
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.neon.gametweak.AdbManager
import com.neon.gametweak.NukeConnectionManager
import com.neon.gametweak.NukeDeviceProfile
import com.neon.gametweak.NukeSystemParamGuardian
import com.neon.gametweak.NukeSystemParamGuardian.OemBrand
import com.neon.gametweak.NukeSystemParamGuardian.ParamCategory
import com.neon.gametweak.NukeSystemParamGuardian.ParamSource
import com.neon.gametweak.NukeSystemParamGuardian.RiskLevel
import com.neon.gametweak.NukeSystemParamRepository
import com.neon.gametweak.NukeSystemParamRepository.NukeSystemParam
import com.neon.gametweak.NukeToast
import kotlinx.coroutines.launch

// ── EXECUTIVE COLOR SYSTEM (ELEGANT, SOPHISTICATED, NON-LOUD) ─────────────────
private val StudioBg = Color(0xFF090C0F)           // Matte obsidian base
private val StudioCard = Color(0xFF11161B)         // Deep charcoal slate surface
private val StudioCardHigh = Color(0xFF161D24)     // Elevated inset panel
private val StudioBorder = Color(0xFF222B35)       // Discrete subtle divider
private val StudioBorderLight = Color(0xFF2E3B48)  // Highlight divider
private val StudioEmerald = Color(0xFF10B981)      // Verified Safe / Production Ready
private val StudioSky = Color(0xFF38BDF8)          // Parameter identity / Telemetry
private val StudioAmber = Color(0xFFF59E0B)        // Caution / User Modified
private val StudioRose = Color(0xFFEF4444)         // Blocked / Security Restriction
private val StudioTextPrimary = Color(0xFFF8FAFC)  // Crisp white
private val StudioTextMuted = Color(0xFF94A3B8)    // Slate-400 secondary
private val StudioTextDim = Color(0xFF64748B)      // Slate-500 tertiary

enum class ParamFilterTab(val title: String) {
    ALL("ALL"),
    PRESETS("CURATED PRESETS"),
    MODIFIED("MODIFIED"),
    FAVORITES("FAVORITES"),
    GLOBAL("GLOBAL"),
    SYSTEM("SYSTEM"),
    SECURE("SECURE"),
    PROP("PROPERTIES")
}

@Composable
fun NukeSystemEditorScreen(adbManager: AdbManager) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val deviceProfile = remember { NukeDeviceProfile.current() }
    val detectedBrand = remember { NukeSystemParamGuardian.detectDeviceBrand() }

    var allParams by remember { mutableStateOf<List<NukeSystemParam>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }
    var searchQuery by remember { mutableStateOf("") }
    var selectedTab by remember { mutableStateOf(ParamFilterTab.PRESETS) }
    var selectedCategory by remember { mutableStateOf<ParamCategory?>(null) }
    var filterBrandOnly by remember { mutableStateOf(false) }
    var revision by remember { mutableIntStateOf(0) }

    // Dialog states
    var editingParam by remember { mutableStateOf<NukeSystemParam?>(null) }
    var showRollbackConfirmDialog by remember { mutableStateOf(false) }
    var showImportDialog by remember { mutableStateOf(false) }
    var showExportDialog by remember { mutableStateOf(false) }
    var showGuideDialog by remember { mutableStateOf(false) }

    val isPrivileged = remember(revision) {
        NukeConnectionManager.isConnected() || adbManager.isConnected()
    }

    var dynamicallyDiscoveredParams by remember { mutableStateOf<List<NukeSystemParam>>(emptyList()) }
    var isProbingDynamic by remember { mutableStateOf(false) }

    // Load parameters from device
    LaunchedEffect(revision) {
        isLoading = true
        allParams = NukeSystemParamRepository.fetchAllParameters(context)
        isLoading = false
    }

    // Dynamic hidden parameter probe triggered when searching
    LaunchedEffect(searchQuery, allParams) {
        val q = searchQuery.trim()
        if (q.length >= 2) {
            isProbingDynamic = true
            val existingIds = allParams.map { it.id }.toSet()
            val dynamicItems = NukeSystemParamRepository.queryDynamicHiddenParameters(context, q, existingIds)
            dynamicallyDiscoveredParams = dynamicItems
            isProbingDynamic = false
        } else {
            dynamicallyDiscoveredParams = emptyList()
            isProbingDynamic = false
        }
    }

    val combinedParams = remember(allParams, dynamicallyDiscoveredParams) {
        if (dynamicallyDiscoveredParams.isEmpty()) allParams
        else {
            val existingIds = allParams.map { it.id }.toSet()
            val newItems = dynamicallyDiscoveredParams.filter { !existingIds.contains(it.id) }
            newItems + allParams
        }
    }

    // Fast filter logic
    val filteredParams = remember(combinedParams, searchQuery, selectedTab, selectedCategory, filterBrandOnly) {
        val query = searchQuery.trim().lowercase()
        combinedParams.filter { param ->
            val tabMatch = when (selectedTab) {
                ParamFilterTab.ALL -> true
                ParamFilterTab.PRESETS -> param.isCurated
                ParamFilterTab.MODIFIED -> param.isModified
                ParamFilterTab.FAVORITES -> param.isFavorite
                ParamFilterTab.GLOBAL -> param.source == ParamSource.GLOBAL
                ParamFilterTab.SYSTEM -> param.source == ParamSource.SYSTEM
                ParamFilterTab.SECURE -> param.source == ParamSource.SECURE
                ParamFilterTab.PROP -> param.source == ParamSource.PROP
            }

            val catMatch = selectedCategory == null || param.category == selectedCategory
            val brandMatch = !filterBrandOnly || param.targetBrand == detectedBrand
            val searchMatch = query.isEmpty() ||
                    param.key.lowercase().contains(query) ||
                    param.value.lowercase().contains(query) ||
                    (param.description?.lowercase()?.contains(query) == true)

            tabMatch && catMatch && brandMatch && searchMatch
        }
    }

    val modifiedCount = remember(combinedParams) { combinedParams.count { it.isModified } }
    val safeCount = remember(combinedParams) { combinedParams.count { it.riskLevel == RiskLevel.SAFE } }
    val curatedCount = remember(combinedParams) { combinedParams.count { it.isCurated } }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(StudioBg)
    ) {
        // ── TOP TELEMETRY & COMMAND DECK ─────────────────────────────────────
        ExecutiveHeader(
            deviceProfile = deviceProfile,
            detectedBrand = detectedBrand,
            totalCount = allParams.size,
            safeCount = safeCount,
            curatedCount = curatedCount,
            modifiedCount = modifiedCount,
            isPrivileged = isPrivileged,
            isLoading = isLoading,
            onRefresh = { revision++ },
            onEmergencyRollback = { showRollbackConfirmDialog = true },
            onApplyGamingBoost = {
                scope.launch {
                    val count = NukeSystemParamRepository.applyCuratedGamingOptimizations(context)
                    NukeToast.success(context, "Applied $count curated gaming optimizations!")
                    revision++
                }
            },
            onOpenImport = { showImportDialog = true },
            onOpenExport = { showExportDialog = true },
            onOpenGuide = { showGuideDialog = true }
        )

        // ── SEARCH BOX ───────────────────────────────────────────────────────
        Box(modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) {
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(50.dp),
                placeholder = {
                    Text(
                        "Search key, value or gaming preset...",
                        color = StudioTextDim,
                        fontSize = 12.sp
                    )
                },
                leadingIcon = {
                    Icon(
                        Icons.Rounded.Search,
                        contentDescription = "Search",
                        tint = StudioTextMuted,
                        modifier = Modifier.size(18.dp)
                    )
                },
                trailingIcon = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (isProbingDynamic) {
                            CircularProgressIndicator(
                                color = StudioSky,
                                strokeWidth = 1.5.dp,
                                modifier = Modifier
                                    .padding(end = 6.dp)
                                    .size(14.dp)
                            )
                        }
                        if (!isLoading && combinedParams.isNotEmpty()) {
                            Text(
                                "${filteredParams.size}/${combinedParams.size}",
                                color = StudioTextDim,
                                fontSize = 10.sp,
                                fontFamily = FontFamily.Monospace,
                                modifier = Modifier.padding(end = if (searchQuery.isNotEmpty()) 4.dp else 12.dp)
                            )
                        }
                        if (searchQuery.isNotEmpty()) {
                            IconButton(onClick = { searchQuery = "" }) {
                                Icon(
                                    Icons.Rounded.Close,
                                    contentDescription = "Clear",
                                    tint = StudioTextMuted,
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        }
                    }
                },
                singleLine = true,
                shape = RoundedCornerShape(10.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = StudioEmerald,
                    unfocusedBorderColor = StudioBorder,
                    focusedContainerColor = StudioCard,
                    unfocusedContainerColor = StudioCard,
                    focusedTextColor = StudioTextPrimary,
                    unfocusedTextColor = StudioTextPrimary
                )
            )
        }

        // ── UNIFIED FILTER & COMMAND DECK (SEJAJAR, RAPI & RESPONSIVE) ────────
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 3.dp),
            shape = RoundedCornerShape(12.dp),
            color = StudioCard,
            border = BorderStroke(1.dp, StudioBorder)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                // Tier 1: Source Selector (Uniform 32.dp height, exact padding)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    ParamFilterTab.values().forEach { tab ->
                        val isSelected = selectedTab == tab
                        val count = when (tab) {
                            ParamFilterTab.ALL -> combinedParams.size
                            ParamFilterTab.PRESETS -> curatedCount
                            ParamFilterTab.MODIFIED -> modifiedCount
                            ParamFilterTab.FAVORITES -> combinedParams.count { it.isFavorite }
                            ParamFilterTab.GLOBAL -> combinedParams.count { it.source == ParamSource.GLOBAL }
                            ParamFilterTab.SYSTEM -> combinedParams.count { it.source == ParamSource.SYSTEM }
                            ParamFilterTab.SECURE -> combinedParams.count { it.source == ParamSource.SECURE }
                            ParamFilterTab.PROP -> combinedParams.count { it.source == ParamSource.PROP }
                        }

                        Box(
                            modifier = Modifier
                                .height(32.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(if (isSelected) StudioEmerald.copy(alpha = 0.18f) else StudioCardHigh)
                                .border(
                                    1.dp,
                                    if (isSelected) StudioEmerald else StudioBorder,
                                    RoundedCornerShape(8.dp)
                                )
                                .clickable { selectedTab = tab }
                                .padding(horizontal = 10.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.Center
                            ) {
                                Text(
                                    tab.title,
                                    color = if (isSelected) StudioEmerald else StudioTextMuted,
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                    fontSize = 11.sp
                                )
                                Spacer(Modifier.width(5.dp))
                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(4.dp))
                                        .background(if (isSelected) StudioEmerald.copy(alpha = 0.25f) else StudioCard)
                                        .padding(horizontal = 5.dp, vertical = 1.dp)
                                ) {
                                    Text(
                                        count.toString(),
                                        color = if (isSelected) StudioTextPrimary else StudioTextDim,
                                        fontFamily = FontFamily.Monospace,
                                        fontSize = 9.5.sp,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                }
                            }
                        }
                    }
                }

                HorizontalDivider(
                    modifier = Modifier.padding(horizontal = 8.dp),
                    color = StudioBorder.copy(alpha = 0.6f),
                    thickness = 0.8.dp
                )

                // Tier 2: Category & OEM Chips (Uniform 28.dp height, exact padding)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    val isAllCat = selectedCategory == null && !filterBrandOnly
                    Box(
                        modifier = Modifier
                            .height(28.dp)
                            .clip(RoundedCornerShape(7.dp))
                            .background(if (isAllCat) StudioSky.copy(alpha = 0.18f) else StudioCardHigh)
                            .border(
                                1.dp,
                                if (isAllCat) StudioSky else StudioBorder,
                                RoundedCornerShape(7.dp)
                            )
                            .clickable {
                                selectedCategory = null
                                filterBrandOnly = false
                            }
                            .padding(horizontal = 10.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            "🌐 All Categories",
                            color = if (isAllCat) StudioSky else StudioTextMuted,
                            fontSize = 10.sp,
                            fontWeight = if (isAllCat) FontWeight.Bold else FontWeight.Normal
                        )
                    }

                    if (detectedBrand != OemBrand.GENERIC) {
                        val isBrandActive = filterBrandOnly
                        Box(
                            modifier = Modifier
                                .height(28.dp)
                                .clip(RoundedCornerShape(7.dp))
                                .background(if (isBrandActive) StudioSky.copy(alpha = 0.22f) else StudioCardHigh)
                                .border(
                                    1.dp,
                                    if (isBrandActive) StudioSky else StudioBorder,
                                    RoundedCornerShape(7.dp)
                                )
                                .clickable {
                                    filterBrandOnly = !filterBrandOnly
                                    if (filterBrandOnly) selectedCategory = null
                                }
                                .padding(horizontal = 10.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.Center
                            ) {
                                Icon(
                                    Icons.Rounded.Bolt,
                                    contentDescription = null,
                                    tint = if (isBrandActive) StudioSky else StudioTextMuted,
                                    modifier = Modifier.size(11.dp)
                                )
                                Spacer(Modifier.width(4.dp))
                                Text(
                                    detectedBrand.chipLabel,
                                    color = if (isBrandActive) StudioSky else StudioTextMuted,
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }

                    ParamCategory.values().forEach { cat ->
                        val isCatSelected = selectedCategory == cat && !filterBrandOnly
                        Box(
                            modifier = Modifier
                                .height(28.dp)
                                .clip(RoundedCornerShape(7.dp))
                                .background(if (isCatSelected) StudioEmerald.copy(alpha = 0.18f) else StudioCardHigh)
                                .border(
                                    1.dp,
                                    if (isCatSelected) StudioEmerald else StudioBorder,
                                    RoundedCornerShape(7.dp)
                                )
                                .clickable {
                                    selectedCategory = if (isCatSelected) null else cat
                                    filterBrandOnly = false
                                }
                                .padding(horizontal = 10.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                cat.displayName,
                                color = if (isCatSelected) StudioEmerald else StudioTextMuted,
                                fontSize = 10.sp,
                                fontWeight = if (isCatSelected) FontWeight.Bold else FontWeight.Normal
                            )
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(4.dp))

        // ── PARAMETER LIST CONTENT ───────────────────────────────────────────
        if (isLoading) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator(color = StudioEmerald, modifier = Modifier.size(36.dp), strokeWidth = 2.5.dp)
                    Spacer(Modifier.height(14.dp))
                    Text(
                        "DISCOVERING DEVICE PARAMETERS...",
                        color = StudioTextPrimary,
                        fontSize = 11.sp,
                        letterSpacing = 1.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        "Parsing live hardware properties and settings tables",
                        color = StudioTextMuted,
                        fontSize = 10.5.sp
                    )
                }
            }
        } else if (filteredParams.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        Icons.Rounded.Search,
                        contentDescription = null,
                        tint = StudioTextDim,
                        modifier = Modifier.size(38.dp)
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "No matching parameters found",
                        color = StudioTextPrimary,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        "Try a different keyword or filter tab",
                        color = StudioTextMuted,
                        fontSize = 11.sp
                    )
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 14.dp, end = 14.dp, top = 4.dp, bottom = 28.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                items(filteredParams, key = { it.id }) { param ->
                    ExecutiveParamListItem(
                        param = param,
                        onEdit = { editingParam = param },
                        onApplyPreset = { presetVal ->
                            scope.launch {
                                val res = NukeSystemParamRepository.applyParameter(context, param, presetVal)
                                if (res.success) {
                                    NukeToast.success(context, res.message)
                                    revision++
                                } else {
                                    NukeToast.error(context, res.message, long = true)
                                }
                            }
                        },
                        onRevertStock = {
                            scope.launch {
                                val res = NukeSystemParamRepository.revertSingleParameter(context, param)
                                if (res.success) {
                                    NukeToast.success(context, res.message)
                                    revision++
                                } else {
                                    NukeToast.error(context, res.message, long = true)
                                }
                            }
                        },
                        onToggleFavorite = {
                            NukeSystemParamRepository.toggleFavorite(context, param.id)
                            revision++
                        },
                        onCopy = {
                            val cb = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                            cb?.setPrimaryClip(ClipData.newPlainText("System Parameter", "${param.key}=${param.value}"))
                            NukeToast.success(context, "Copied ${param.key}")
                        }
                    )
                }
            }
        }
    }

    // ── DIALOGS ──────────────────────────────────────────────────────────────
    editingParam?.let { param ->
        ExecutiveEditDialog(
            param = param,
            onDismiss = { editingParam = null },
            onRevertStock = {
                scope.launch {
                    val res = NukeSystemParamRepository.revertSingleParameter(context, param)
                    if (res.success) {
                        NukeToast.success(context, res.message)
                        editingParam = null
                        revision++
                    } else {
                        NukeToast.error(context, res.message, long = true)
                    }
                }
            },
            onApply = { newValue ->
                scope.launch {
                    val res = NukeSystemParamRepository.applyParameter(context, param, newValue)
                    if (res.success) {
                        NukeToast.success(context, res.message)
                        editingParam = null
                        revision++
                    } else {
                        NukeToast.error(context, res.message, long = true)
                    }
                }
            }
        )
    }

    if (showRollbackConfirmDialog) {
        AlertDialog(
            onDismissRequest = { showRollbackConfirmDialog = false },
            containerColor = StudioCard,
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.Warning, contentDescription = null, tint = StudioAmber)
                    Spacer(Modifier.width(8.dp))
                    Text("RESTORE ALL DEFAULTS", color = StudioTextPrimary, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                }
            },
            text = {
                Column {
                    Text(
                        "This will safely revert all $modifiedCount modified parameters back to their original factory values recorded in the journal.",
                        color = StudioTextMuted,
                        fontSize = 12.sp
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Safety validation active: parameters will be restored cleanly.",
                        color = StudioEmerald,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Medium
                    )
                }
            },
            confirmButton = {
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(StudioAmber)
                        .clickable {
                            showRollbackConfirmDialog = false
                            scope.launch {
                                val summary = NukeSystemParamRepository.rollbackAll(context)
                                NukeToast.success(
                                    context,
                                    "Restored: ${summary.successCount} parameters back to stock.",
                                    long = true
                                )
                                revision++
                            }
                        }
                        .padding(horizontal = 14.dp, vertical = 7.dp)
                ) {
                    Text("CONFIRM RESTORE", color = Color.Black, fontWeight = FontWeight.Bold, fontSize = 11.sp)
                }
            },
            dismissButton = {
                TextButton(onClick = { showRollbackConfirmDialog = false }) {
                    Text("CANCEL", color = StudioTextMuted, fontSize = 11.sp)
                }
            }
        )
    }

    if (showImportDialog) {
        ExecutiveModuleImportDialog(
            onDismiss = { showImportDialog = false },
            onModuleApplied = {
                showImportDialog = false
                revision++
            }
        )
    }

    if (showExportDialog) {
        ExecutiveModuleExportDialog(
            deviceProfile = deviceProfile,
            allParams = allParams,
            filteredParams = filteredParams,
            onDismiss = { showExportDialog = false }
        )
    }

    if (showGuideDialog) {
        ExecutiveCreatorGuideDialog(
            onDismiss = { showGuideDialog = false }
        )
    }
}

@Composable
private fun ExecutiveHeader(
    deviceProfile: NukeDeviceProfile,
    detectedBrand: OemBrand,
    totalCount: Int,
    safeCount: Int,
    curatedCount: Int,
    modifiedCount: Int,
    isPrivileged: Boolean,
    isLoading: Boolean,
    onRefresh: () -> Unit,
    onEmergencyRollback: () -> Unit,
    onApplyGamingBoost: () -> Unit,
    onOpenImport: () -> Unit,
    onOpenExport: () -> Unit,
    onOpenGuide: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 6.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(StudioCard)
            .border(1.dp, StudioBorder, RoundedCornerShape(12.dp))
            .padding(12.dp)
    ) {
        // Title and Status Row
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
        ) {
            Box(
                modifier = Modifier
                    .size(34.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(StudioCardHigh)
                    .border(1.dp, StudioBorderLight, RoundedCornerShape(8.dp)),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Rounded.Tune, contentDescription = null, tint = StudioEmerald, modifier = Modifier.size(18.dp))
            }

            Spacer(Modifier.width(10.dp))

            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "Device System Editor",
                        color = StudioTextPrimary,
                        fontWeight = FontWeight.Bold,
                        fontSize = 13.sp
                    )
                }
                Spacer(Modifier.height(1.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(5.dp)
                            .clip(CircleShape)
                            .background(if (isPrivileged) StudioEmerald else StudioAmber)
                    )
                    Spacer(Modifier.width(5.dp))
                    Text(
                        if (isPrivileged) "Privileged Shell Active (Direct Write)" else "Local Discovery Mode (Read Only)",
                        color = if (isPrivileged) StudioEmerald else StudioAmber,
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Medium
                    )
                }
            }

            // Refresh Button
            IconButton(onClick = onRefresh, modifier = Modifier.size(30.dp)) {
                if (isLoading) {
                    CircularProgressIndicator(color = StudioEmerald, modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                } else {
                    Icon(Icons.Rounded.Refresh, contentDescription = "Refresh", tint = StudioTextMuted, modifier = Modifier.size(16.dp))
                }
            }
        }

        Spacer(Modifier.height(8.dp))

        // Action Deck: Apply Presets, Import JSON, Creator Guide
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(5.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Apply Curated Presets button
            Box(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(6.dp))
                    .background(StudioEmerald.copy(alpha = 0.12f))
                    .border(0.8.dp, StudioEmerald.copy(alpha = 0.40f), RoundedCornerShape(6.dp))
                    .clickable { onApplyGamingBoost() }
                    .padding(vertical = 7.dp),
                contentAlignment = Alignment.Center
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.Bolt, contentDescription = null, tint = StudioEmerald, modifier = Modifier.size(11.dp))
                    Spacer(Modifier.width(2.dp))
                    Text("APPLY PRESETS", color = StudioEmerald, fontSize = 8.sp, fontWeight = FontWeight.Bold)
                }
            }

            // Import Module JSON button
            Box(
                modifier = Modifier
                    .weight(0.95f)
                    .clip(RoundedCornerShape(6.dp))
                    .background(StudioCardHigh)
                    .border(0.8.dp, StudioBorderLight, RoundedCornerShape(6.dp))
                    .clickable { onOpenImport() }
                    .padding(vertical = 7.dp),
                contentAlignment = Alignment.Center
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.FileDownload, contentDescription = null, tint = StudioTextMuted, modifier = Modifier.size(11.dp))
                    Spacer(Modifier.width(2.dp))
                    Text("IMPORT JSON", color = StudioTextMuted, fontSize = 8.sp, fontWeight = FontWeight.Bold)
                }
            }

            // Export Module JSON button
            Box(
                modifier = Modifier
                    .weight(0.95f)
                    .clip(RoundedCornerShape(6.dp))
                    .background(StudioCardHigh)
                    .border(0.8.dp, StudioBorderLight, RoundedCornerShape(6.dp))
                    .clickable { onOpenExport() }
                    .padding(vertical = 7.dp),
                contentAlignment = Alignment.Center
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.FileUpload, contentDescription = null, tint = StudioSky, modifier = Modifier.size(11.dp))
                    Spacer(Modifier.width(2.dp))
                    Text("EXPORT JSON", color = StudioSky, fontSize = 8.sp, fontWeight = FontWeight.Bold)
                }
            }

            // Creator Guide button
            Box(
                modifier = Modifier
                    .weight(0.75f)
                    .clip(RoundedCornerShape(6.dp))
                    .background(StudioCardHigh)
                    .border(0.8.dp, StudioBorder, RoundedCornerShape(6.dp))
                    .clickable { onOpenGuide() }
                    .padding(vertical = 7.dp),
                contentAlignment = Alignment.Center
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.MenuBook, contentDescription = null, tint = StudioTextDim, modifier = Modifier.size(11.dp))
                    Spacer(Modifier.width(2.dp))
                    Text("GUIDE", color = StudioTextDim, fontSize = 8.sp, fontWeight = FontWeight.Bold)
                }
            }
        }

        Spacer(Modifier.height(8.dp))

        // Device Brand Banner
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(6.dp))
                .background(StudioCardHigh)
                .padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.Smartphone, contentDescription = null, tint = StudioTextDim, modifier = Modifier.size(12.dp))
                Spacer(Modifier.width(5.dp))
                Text(
                    deviceProfile.compactLabel,
                    color = StudioTextPrimary,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }
            Text(
                detectedBrand.displayName,
                color = StudioTextMuted,
                fontSize = 9.5.sp,
                fontWeight = FontWeight.SemiBold
            )
        }

        Spacer(Modifier.height(8.dp))

        // Stat Counters
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            HeaderStatTile(
                label = "PARAMETERS",
                value = if (isLoading) "..." else totalCount.toString(),
                accent = StudioSky,
                modifier = Modifier.weight(1f)
            )
            HeaderStatTile(
                label = "SAFE TWEAKS",
                value = if (isLoading) "..." else safeCount.toString(),
                accent = StudioEmerald,
                modifier = Modifier.weight(1f)
            )
            HeaderStatTile(
                label = "CURATED",
                value = if (isLoading) "..." else curatedCount.toString(),
                accent = StudioTextPrimary,
                modifier = Modifier.weight(1f)
            )
            HeaderStatTile(
                label = "MODIFIED",
                value = modifiedCount.toString(),
                accent = if (modifiedCount > 0) StudioAmber else StudioTextDim,
                modifier = Modifier.weight(1f)
            )
        }

        // Emergency Rollback Strip (Responsive & Touch-Friendly)
        AnimatedVisibility(visible = modifiedCount > 0) {
            Column {
                Spacer(Modifier.height(8.dp))
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(6.dp))
                        .background(StudioAmber.copy(alpha = 0.12f))
                        .border(1.dp, StudioAmber.copy(alpha = 0.35f), RoundedCornerShape(6.dp))
                        .clickable { onEmergencyRollback() }
                        .padding(horizontal = 10.dp, vertical = 6.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.Rounded.History, contentDescription = null, tint = StudioAmber, modifier = Modifier.size(14.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(
                                "$modifiedCount modified · Tap to restore",
                                color = StudioAmber,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        Spacer(Modifier.width(6.dp))
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(4.dp))
                                .background(StudioAmber)
                                .padding(horizontal = 8.dp, vertical = 4.dp)
                        ) {
                            Text(
                                "↺ RESTORE",
                                color = Color.Black,
                                fontSize = 9.sp,
                                fontWeight = FontWeight.Black
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun HeaderStatTile(label: String, value: String, accent: Color, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(6.dp))
            .background(StudioCardHigh)
            .border(0.8.dp, StudioBorder, RoundedCornerShape(6.dp))
            .padding(vertical = 5.dp, horizontal = 4.dp)
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
            Text(value, color = accent, fontSize = 13.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
            Text(label, color = StudioTextDim, fontSize = 7.5.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.3.sp)
        }
    }
}

@Composable
private fun ExecutiveParamListItem(
    param: NukeSystemParam,
    onEdit: () -> Unit,
    onApplyPreset: (String) -> Unit,
    onRevertStock: () -> Unit,
    onToggleFavorite: () -> Unit,
    onCopy: () -> Unit
) {
    val isBlocked = param.riskLevel == RiskLevel.BLOCKED
    val riskColor = when (param.riskLevel) {
        RiskLevel.SAFE -> StudioEmerald
        RiskLevel.MODERATE -> StudioAmber
        RiskLevel.BLOCKED -> StudioRose
    }
    val sourceShort = when (param.source) {
        ParamSource.GLOBAL -> "GLB"
        ParamSource.SYSTEM -> "SYS"
        ParamSource.SECURE -> "SEC"
        ParamSource.PROP -> "PROP"
        ParamSource.KERNEL -> "KRNL"
    }

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        color = StudioCard,
        border = BorderStroke(1.dp, if (param.isModified) StudioAmber.copy(alpha = 0.5f) else StudioBorder)
    ) {
        Column(modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                // Source Pill
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(4.dp))
                        .background(riskColor.copy(alpha = 0.12f))
                        .border(0.6.dp, riskColor.copy(alpha = 0.4f), RoundedCornerShape(4.dp))
                        .padding(horizontal = 4.dp, vertical = 2.dp)
                ) {
                    Text(
                        sourceShort,
                        color = riskColor,
                        fontSize = 8.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace
                    )
                }

                Spacer(Modifier.width(8.dp))

                // Middle: Key Name & Subtitle
                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            param.key,
                            color = StudioTextPrimary,
                            fontSize = 11.5.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false)
                        )
                        if (param.isModified) {
                            Spacer(Modifier.width(4.dp))
                            Box(
                                modifier = Modifier
                                    .size(5.dp)
                                    .clip(CircleShape)
                                    .background(StudioAmber)
                            )
                        }
                        param.targetBrand?.let { brand ->
                            Spacer(Modifier.width(4.dp))
                            Text(
                                brand.chipLabel,
                                color = StudioSky,
                                fontSize = 7.5.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }

                    val subtitleText = when {
                        param.isModified && !param.stockValue.isNullOrBlank() -> "Stock: ${param.stockValue}"
                        !param.description.isNullOrBlank() -> param.description
                        else -> null
                    }
                    if (subtitleText != null) {
                        Text(
                            subtitleText,
                            color = if (param.isModified) StudioAmber.copy(alpha = 0.8f) else StudioTextDim,
                            fontSize = 9.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }

                Spacer(Modifier.width(8.dp))

                // Value Chip
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(5.dp))
                        .background(StudioCardHigh)
                        .border(0.6.dp, StudioBorderLight, RoundedCornerShape(5.dp))
                        .clickable { onCopy() }
                        .padding(horizontal = 7.dp, vertical = 3.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        param.value.ifBlank { "—" },
                        color = if (param.isModified) StudioAmber else StudioEmerald,
                        fontSize = 10.5.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                Spacer(Modifier.width(4.dp))

                // Action icons
                if (param.isModified) {
                    IconButton(onClick = onRevertStock, modifier = Modifier.size(24.dp)) {
                        Icon(Icons.Rounded.Restore, contentDescription = "Revert", tint = StudioAmber, modifier = Modifier.size(13.dp))
                    }
                }

                if (!isBlocked) {
                    IconButton(onClick = onEdit, modifier = Modifier.size(24.dp)) {
                        Icon(Icons.Rounded.Edit, contentDescription = "Edit", tint = StudioEmerald, modifier = Modifier.size(13.dp))
                    }
                } else {
                    Icon(
                        Icons.Rounded.Security,
                        contentDescription = "Locked",
                        tint = StudioRose.copy(alpha = 0.6f),
                        modifier = Modifier.size(14.dp).padding(start = 4.dp)
                    )
                }

                IconButton(onClick = onToggleFavorite, modifier = Modifier.size(22.dp)) {
                    Icon(
                        if (param.isFavorite) Icons.Rounded.Star else Icons.Rounded.StarBorder,
                        contentDescription = "Favorite",
                        tint = if (param.isFavorite) StudioAmber else StudioTextDim,
                        modifier = Modifier.size(13.dp)
                    )
                }
            }

            // Compact Presets Row if available
            if (!isBlocked && param.presets.isNotEmpty()) {
                Spacer(Modifier.height(4.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("PRESET:", color = StudioTextDim, fontSize = 7.5.sp, fontWeight = FontWeight.Bold)
                    param.presets.forEach { pVal ->
                        val isCurrent = param.value == pVal
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(3.dp))
                                .background(if (isCurrent) StudioEmerald.copy(alpha = 0.2f) else StudioCardHigh)
                                .border(0.5.dp, if (isCurrent) StudioEmerald else StudioBorder, RoundedCornerShape(3.dp))
                                .clickable { onApplyPreset(pVal) }
                                .padding(horizontal = 5.dp, vertical = 1.dp)
                        ) {
                            Text(
                                pVal,
                                color = if (isCurrent) StudioEmerald else StudioTextMuted,
                                fontSize = 8.sp,
                                fontFamily = FontFamily.Monospace,
                                fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Normal
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ExecutiveEditDialog(
    param: NukeSystemParam,
    onDismiss: () -> Unit,
    onRevertStock: () -> Unit,
    onApply: (String) -> Unit
) {
    var inputValue by remember { mutableStateOf(param.value) }
    var validationResult by remember {
        mutableStateOf(NukeSystemParamGuardian.validateValue(param.source, param.key, param.value))
    }

    LaunchedEffect(inputValue) {
        validationResult = NukeSystemParamGuardian.validateValue(param.source, param.key, inputValue)
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = StudioCard,
        title = {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.Tune, contentDescription = null, tint = StudioEmerald)
                    Spacer(Modifier.width(8.dp))
                    Text("Edit Parameter", color = StudioTextPrimary, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                }
                Spacer(Modifier.height(3.dp))
                Text(
                    "${param.source.name} · ${param.key}",
                    color = StudioSky,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        },
        text = {
            Column {
                if (!param.description.isNullOrBlank()) {
                    Text(param.description, color = StudioTextMuted, fontSize = 11.sp)
                    Spacer(Modifier.height(8.dp))
                }

                // Reference Box
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(6.dp))
                        .background(StudioCardHigh)
                        .padding(8.dp)
                ) {
                    Column {
                        Text("Current on phone: ${param.value}", color = StudioTextMuted, fontFamily = FontFamily.Monospace, fontSize = 10.sp)
                        if (!param.stockValue.isNullOrBlank()) {
                            Spacer(Modifier.height(2.dp))
                            Text("Stock baseline: ${param.stockValue}", color = StudioEmerald, fontFamily = FontFamily.Monospace, fontSize = 9.5.sp)
                        }
                    }
                }

                Spacer(Modifier.height(10.dp))

                OutlinedTextField(
                    value = inputValue,
                    onValueChange = { inputValue = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("New Value", fontSize = 11.sp) },
                    singleLine = true,
                    shape = RoundedCornerShape(8.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = if (validationResult.isValid) StudioEmerald else StudioRose,
                        unfocusedBorderColor = if (validationResult.isValid) StudioBorder else StudioRose,
                        focusedTextColor = StudioTextPrimary,
                        unfocusedTextColor = StudioTextPrimary,
                        focusedContainerColor = StudioCardHigh,
                        unfocusedContainerColor = StudioCardHigh
                    )
                )

                Spacer(Modifier.height(6.dp))

                // Validation Status
                if (!validationResult.isValid) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.Warning, contentDescription = null, tint = StudioRose, modifier = Modifier.size(13.dp))
                        Spacer(Modifier.width(4.dp))
                        Text(
                            validationResult.errorMessage ?: "Invalid value",
                            color = StudioRose,
                            fontSize = 10.sp
                        )
                    }
                } else {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.CheckCircle, contentDescription = null, tint = StudioEmerald, modifier = Modifier.size(13.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Anti-Bootloop Validation: SAFE", color = StudioEmerald, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                    }
                }

                if (param.presets.isNotEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    Text("Quick Presets:", color = StudioTextDim, fontSize = 9.5.sp)
                    Spacer(Modifier.height(3.dp))
                    Row(
                        modifier = Modifier.horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(5.dp)
                    ) {
                        param.presets.forEach { p ->
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(4.dp))
                                    .background(StudioCardHigh)
                                    .border(0.6.dp, StudioBorder, RoundedCornerShape(4.dp))
                                    .clickable { inputValue = p }
                                    .padding(horizontal = 7.dp, vertical = 3.dp)
                            ) {
                                Text(p, color = StudioSky, fontSize = 9.5.sp, fontFamily = FontFamily.Monospace)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (param.isModified) {
                    TextButton(onClick = onRevertStock) {
                        Text("REVERT STOCK", color = StudioAmber, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                    }
                    Spacer(Modifier.width(4.dp))
                }
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(if (validationResult.isValid) StudioEmerald else StudioBorder)
                        .clickable(enabled = validationResult.isValid) {
                            onApply(inputValue)
                        }
                        .padding(horizontal = 12.dp, vertical = 6.dp)
                ) {
                    Text("APPLY SAFELY", color = Color.Black, fontWeight = FontWeight.Bold, fontSize = 10.5.sp)
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("CANCEL", color = StudioTextMuted, fontSize = 10.5.sp)
            }
        }
    )
}

@Composable
private fun ExecutiveModuleImportDialog(
    onDismiss: () -> Unit,
    onModuleApplied: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var selectedTab by remember { mutableIntStateOf(0) } // 0 = File, 1 = Paste
    var jsonText by remember { mutableStateOf("") }
    var parsedResult by remember { mutableStateOf<ParsedModuleResult?>(null) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var isApplying by remember { mutableStateOf(false) }

    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri != null) {
            runCatching {
                val content = context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
                if (!content.isNullOrBlank()) {
                    jsonText = content
                    val res = NukeModuleJsonImporter.parseAndValidate(content)
                    if (res.isSuccess) {
                        parsedResult = res.getOrNull()
                        errorMessage = null
                    } else {
                        errorMessage = res.exceptionOrNull()?.message ?: "Gagal membaca struktur modul JSON"
                        parsedResult = null
                    }
                }
            }.onFailure {
                errorMessage = "Gagal membuka file: ${it.localizedMessage}"
                parsedResult = null
            }
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = StudioCard,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.FileDownload, contentDescription = null, tint = StudioSky, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Text("IMPORT DEVICE MODULE", color = StudioTextPrimary, fontWeight = FontWeight.Bold, fontSize = 14.sp)
            }
        },
        text = {
            Column(modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
                Text(
                    "Impor modul tuning buatan developer/creator (.json) untuk optimasi gaming instan.",
                    color = StudioTextMuted,
                    fontSize = 11.sp
                )

                Spacer(Modifier.height(10.dp))

                // Input Mode Selector Tabs
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(6.dp))
                            .background(if (selectedTab == 0) StudioSky.copy(alpha = 0.2f) else StudioCardHigh)
                            .border(1.dp, if (selectedTab == 0) StudioSky else StudioBorder, RoundedCornerShape(6.dp))
                            .clickable { selectedTab = 0 }
                            .padding(vertical = 7.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Rounded.Description, contentDescription = null, tint = if (selectedTab == 0) StudioSky else StudioTextDim, modifier = Modifier.size(13.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("FILE .JSON", color = if (selectedTab == 0) StudioSky else StudioTextMuted, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                        }
                    }

                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(6.dp))
                            .background(if (selectedTab == 1) StudioSky.copy(alpha = 0.2f) else StudioCardHigh)
                            .border(1.dp, if (selectedTab == 1) StudioSky else StudioBorder, RoundedCornerShape(6.dp))
                            .clickable { selectedTab = 1 }
                            .padding(vertical = 7.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Rounded.ContentPaste, contentDescription = null, tint = if (selectedTab == 1) StudioSky else StudioTextDim, modifier = Modifier.size(13.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("PASTE TEKS", color = if (selectedTab == 1) StudioSky else StudioTextMuted, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }

                Spacer(Modifier.height(10.dp))

                if (selectedTab == 0) {
                    // File Pick button
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(StudioCardHigh)
                            .border(1.dp, StudioSky.copy(alpha = 0.4f), RoundedCornerShape(8.dp))
                            .clickable { filePickerLauncher.launch("*/*") }
                            .padding(vertical = 14.dp, horizontal = 12.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(Icons.Rounded.FileDownload, contentDescription = null, tint = StudioSky, modifier = Modifier.size(24.dp))
                            Spacer(Modifier.height(6.dp))
                            Text("PILIH FILE MODULE .JSON", color = StudioSky, fontWeight = FontWeight.Bold, fontSize = 11.5.sp)
                            Text("Buka dari penyimpanan internal atau unduhan", color = StudioTextDim, fontSize = 9.sp)
                        }
                    }
                } else {
                    // Paste text field
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Text("KODE JSON MODUL:", color = StudioTextDim, fontSize = 9.sp, fontWeight = FontWeight.Bold)
                        TextButton(
                            onClick = {
                                val cb = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                                val clip = cb?.primaryClip?.getItemAt(0)?.text?.toString()
                                if (!clip.isNullOrBlank()) {
                                    jsonText = clip
                                    val res = NukeModuleJsonImporter.parseAndValidate(clip)
                                    if (res.isSuccess) {
                                        parsedResult = res.getOrNull()
                                        errorMessage = null
                                    } else {
                                        errorMessage = res.exceptionOrNull()?.message ?: "Format JSON tidak valid"
                                        parsedResult = null
                                    }
                                } else {
                                    NukeToast.error(context, "Clipboard kosong!")
                                }
                            }
                        ) {
                            Icon(Icons.Rounded.ContentPaste, contentDescription = null, tint = StudioSky, modifier = Modifier.size(12.dp))
                            Spacer(Modifier.width(3.dp))
                            Text("TEMPEL DARI CLIPBOARD", color = StudioSky, fontSize = 9.5.sp, fontWeight = FontWeight.Bold)
                        }
                    }

                    OutlinedTextField(
                        value = jsonText,
                        onValueChange = {
                            jsonText = it
                            if (it.isNotBlank()) {
                                val res = NukeModuleJsonImporter.parseAndValidate(it)
                                if (res.isSuccess) {
                                    parsedResult = res.getOrNull()
                                    errorMessage = null
                                } else {
                                    errorMessage = res.exceptionOrNull()?.message ?: "Format JSON tidak valid"
                                    parsedResult = null
                                }
                            } else {
                                parsedResult = null
                                errorMessage = null
                            }
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(100.dp),
                        placeholder = { Text("Paste kode modul JSON di sini...", color = StudioTextDim, fontSize = 10.sp) },
                        shape = RoundedCornerShape(8.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = StudioSky,
                            unfocusedBorderColor = StudioBorder,
                            focusedTextColor = StudioTextPrimary,
                            unfocusedTextColor = StudioTextPrimary,
                            focusedContainerColor = StudioCardHigh,
                            unfocusedContainerColor = StudioCardHigh
                        )
                    )
                }

                // Error message banner
                errorMessage?.let { err ->
                    Spacer(Modifier.height(8.dp))
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(6.dp))
                            .background(StudioRose.copy(alpha = 0.12f))
                            .border(0.8.dp, StudioRose.copy(alpha = 0.4f), RoundedCornerShape(6.dp))
                            .padding(8.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Rounded.Warning, contentDescription = null, tint = StudioRose, modifier = Modifier.size(14.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(err, color = StudioRose, fontSize = 10.sp)
                        }
                    }
                }

                // Module Preview Card
                parsedResult?.let { module ->
                    Spacer(Modifier.height(10.dp))
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(8.dp),
                        color = StudioCardHigh,
                        border = BorderStroke(1.dp, StudioBorderLight)
                    ) {
                        Column(modifier = Modifier.padding(10.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                                Text(module.moduleName, color = StudioTextPrimary, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(4.dp))
                                        .background(StudioEmerald.copy(alpha = 0.2f))
                                        .padding(horizontal = 6.dp, vertical = 2.dp)
                                ) {
                                    Text("v${module.version}", color = StudioEmerald, fontSize = 9.sp, fontWeight = FontWeight.Bold)
                                }
                            }

                            Spacer(Modifier.height(2.dp))
                            Text("By ${module.author}", color = StudioSky, fontSize = 10.sp, fontWeight = FontWeight.Medium)

                            if (module.description.isNotBlank()) {
                                Spacer(Modifier.height(4.dp))
                                Text(module.description, color = StudioTextMuted, fontSize = 9.5.sp)
                            }

                            Spacer(Modifier.height(8.dp))

                            // Stats Pills
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(4.dp))
                                        .background(StudioEmerald.copy(alpha = 0.15f))
                                        .padding(horizontal = 6.dp, vertical = 2.dp)
                                ) {
                                    Text("✓ ${module.safeCount} AMAN", color = StudioEmerald, fontSize = 9.sp, fontWeight = FontWeight.Bold)
                                }

                                if (module.blockedCount > 0) {
                                    Box(
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(4.dp))
                                            .background(StudioRose.copy(alpha = 0.15f))
                                            .padding(horizontal = 6.dp, vertical = 2.dp)
                                    ) {
                                        Text("⛔ ${module.blockedCount} BLOCKED (SAFEGUARD)", color = StudioRose, fontSize = 9.sp, fontWeight = FontWeight.Bold)
                                    }
                                }
                            }

                            Spacer(Modifier.height(8.dp))
                            Text("PARAMETER DALAM MODUL:", color = StudioTextDim, fontSize = 8.5.sp, fontWeight = FontWeight.Bold)
                            Spacer(Modifier.height(4.dp))

                            module.allItems.take(8).forEach { item ->
                                Row(
                                    modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                                        Text(item.source.name, color = StudioSky, fontSize = 8.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
                                        Spacer(Modifier.width(4.dp))
                                        Text(item.key, color = StudioTextPrimary, fontSize = 9.5.sp, fontFamily = FontFamily.Monospace, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    }
                                    Spacer(Modifier.width(6.dp))
                                    Text(
                                        if (item.isAllowed) item.sanitizedValue else "BLOCKED",
                                        color = if (item.isAllowed) StudioEmerald else StudioRose,
                                        fontSize = 9.5.sp,
                                        fontWeight = FontWeight.Bold,
                                        fontFamily = FontFamily.Monospace
                                    )
                                }
                            }

                            if (module.allItems.size > 8) {
                                Text("+ ${module.allItems.size - 8} parameter lainnya...", color = StudioTextDim, fontSize = 8.5.sp)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            parsedResult?.let { module ->
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(if (module.safeCount > 0 && !isApplying) StudioEmerald else StudioBorder)
                        .clickable(enabled = module.safeCount > 0 && !isApplying) {
                            isApplying = true
                            scope.launch {
                                val report = NukeModuleJsonImporter.applyModule(context, module)
                                NukeToast.success(context, report.message, long = true)
                                isApplying = false
                                onModuleApplied()
                            }
                        }
                        .padding(horizontal = 12.dp, vertical = 7.dp)
                ) {
                    Text(
                        if (isApplying) "MENERAPKAN..." else "⚡ PASANG MODUL (${module.safeCount} PARAM)",
                        color = Color.Black,
                        fontWeight = FontWeight.Bold,
                        fontSize = 10.5.sp
                    )
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("BATAL", color = StudioTextMuted, fontSize = 10.5.sp)
            }
        }
    )
}

@Composable
private fun ExecutiveModuleExportDialog(
    deviceProfile: NukeDeviceProfile,
    allParams: List<NukeSystemParam>,
    filteredParams: List<NukeSystemParam>,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current

    var moduleName by remember { mutableStateOf("${deviceProfile.compactLabel} Pro Preset") }
    var author by remember { mutableStateOf("Game Nuke User") }
    var description by remember { mutableStateOf("Custom tuned gaming parameters exported from Game Nuke") }
    var exportTab by remember { mutableIntStateOf(0) } // 0 = Modified, 1 = Curated, 2 = Current View

    val modifiedParams = remember(allParams) { allParams.filter { it.isModified } }
    val curatedParams = remember(allParams) { allParams.filter { it.isCurated } }

    val activeParams = remember(exportTab, modifiedParams, curatedParams, filteredParams) {
        when (exportTab) {
            0 -> if (modifiedParams.isNotEmpty()) modifiedParams else curatedParams
            1 -> curatedParams
            else -> filteredParams.ifEmpty { curatedParams }
        }
    }

    val generatedJson = remember(moduleName, author, description, activeParams) {
        val items = activeParams.map { p ->
            NukeModuleJsonImporter.ModuleParamItem(
                source = p.source,
                key = p.key,
                rawValue = p.value,
                sanitizedValue = p.value,
                description = p.description ?: "",
                riskLevel = p.riskLevel,
                isAllowed = p.riskLevel != RiskLevel.BLOCKED
            )
        }
        NukeModuleJsonImporter.exportModuleJson(
            moduleName = moduleName,
            author = author,
            version = "1.0",
            description = description,
            targetOem = deviceProfile.manufacturer,
            items = items
        )
    }

    val saveFileLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/json")
    ) { uri: Uri? ->
        if (uri != null) {
            runCatching {
                context.contentResolver.openOutputStream(uri)?.use { stream ->
                    stream.write(generatedJson.toByteArray(Charsets.UTF_8))
                }
                NukeToast.success(context, "Preset JSON tersimpan di perangkat!")
                onDismiss()
            }.onFailure {
                NukeToast.error(context, "Gagal menyimpan file: ${it.localizedMessage}")
            }
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = StudioCard,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.FileUpload, contentDescription = null, tint = StudioSky, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Text("EXPORT PRESET MODULE", color = StudioTextPrimary, fontWeight = FontWeight.Bold, fontSize = 14.sp)
            }
        },
        text = {
            Column(modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
                Text(
                    "Ekspor settingan & parameter HP Anda ke format .json agar bisa dibagikan ke gamer lain atau penonton Anda.",
                    color = StudioTextMuted,
                    fontSize = 11.sp
                )

                Spacer(Modifier.height(10.dp))

                // Source Tabs
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(5.dp)
                ) {
                    val tabs = listOf(
                        "MODIFIED (${modifiedParams.size})",
                        "CURATED (${curatedParams.size})",
                        "VIEW (${filteredParams.size})"
                    )
                    tabs.forEachIndexed { index, label ->
                        val isSelected = exportTab == index
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(6.dp))
                                .background(if (isSelected) StudioSky.copy(alpha = 0.2f) else StudioCardHigh)
                                .border(1.dp, if (isSelected) StudioSky else StudioBorder, RoundedCornerShape(6.dp))
                                .clickable { exportTab = index }
                                .padding(vertical = 6.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                label,
                                color = if (isSelected) StudioSky else StudioTextMuted,
                                fontSize = 9.sp,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium
                            )
                        }
                    }
                }

                Spacer(Modifier.height(10.dp))

                // Module Metadata Inputs
                Text("NAMA PRESET / MODUL:", color = StudioTextDim, fontSize = 9.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(3.dp))
                OutlinedTextField(
                    value = moduleName,
                    onValueChange = { moduleName = it },
                    modifier = Modifier.fillMaxWidth().height(48.dp),
                    shape = RoundedCornerShape(8.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = StudioSky,
                        unfocusedBorderColor = StudioBorder,
                        focusedTextColor = StudioTextPrimary,
                        unfocusedTextColor = StudioTextPrimary,
                        focusedContainerColor = StudioCardHigh,
                        unfocusedContainerColor = StudioCardHigh
                    )
                )

                Spacer(Modifier.height(8.dp))

                Text("CREATOR / AUTHOR:", color = StudioTextDim, fontSize = 9.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(3.dp))
                OutlinedTextField(
                    value = author,
                    onValueChange = { author = it },
                    modifier = Modifier.fillMaxWidth().height(48.dp),
                    shape = RoundedCornerShape(8.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = StudioSky,
                        unfocusedBorderColor = StudioBorder,
                        focusedTextColor = StudioTextPrimary,
                        unfocusedTextColor = StudioTextPrimary,
                        focusedContainerColor = StudioCardHigh,
                        unfocusedContainerColor = StudioCardHigh
                    )
                )

                Spacer(Modifier.height(8.dp))

                Text("DESKRIPSI MODUL:", color = StudioTextDim, fontSize = 9.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(3.dp))
                OutlinedTextField(
                    value = description,
                    onValueChange = { description = it },
                    modifier = Modifier.fillMaxWidth().height(48.dp),
                    shape = RoundedCornerShape(8.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = StudioSky,
                        unfocusedBorderColor = StudioBorder,
                        focusedTextColor = StudioTextPrimary,
                        unfocusedTextColor = StudioTextPrimary,
                        focusedContainerColor = StudioCardHigh,
                        unfocusedContainerColor = StudioCardHigh
                    )
                )

                Spacer(Modifier.height(10.dp))

                // Parameters count summary
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(6.dp))
                        .background(StudioCardHigh)
                        .border(0.8.dp, StudioBorderLight, RoundedCornerShape(6.dp))
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("${activeParams.size} Parameter Disertakan", color = StudioEmerald, fontSize = 10.5.sp, fontWeight = FontWeight.SemiBold)
                    Text("Verified Safe Schema", color = StudioTextDim, fontSize = 9.5.sp)
                }

                Spacer(Modifier.height(10.dp))

                // JSON Code Preview Header
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("PREVIEW FILE .JSON:", color = StudioTextDim, fontSize = 9.sp, fontWeight = FontWeight.Bold)
                    TextButton(
                        onClick = {
                            val cb = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                            cb?.setPrimaryClip(ClipData.newPlainText("GameNuke Module Preset", generatedJson))
                            NukeToast.success(context, "Kode JSON disalin ke clipboard!")
                        }
                    ) {
                        Icon(Icons.Rounded.ContentCopy, contentDescription = null, tint = StudioSky, modifier = Modifier.size(12.dp))
                        Spacer(Modifier.width(3.dp))
                        Text("SALIN JSON", color = StudioSky, fontSize = 9.5.sp, fontWeight = FontWeight.Bold)
                    }
                }

                Surface(
                    modifier = Modifier.fillMaxWidth().height(120.dp),
                    shape = RoundedCornerShape(6.dp),
                    color = Color(0xFF090C0E),
                    border = BorderStroke(0.8.dp, StudioBorder)
                ) {
                    Text(
                        generatedJson,
                        color = StudioTextPrimary,
                        fontSize = 8.5.sp,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier
                            .padding(8.dp)
                            .verticalScroll(rememberScrollState())
                    )
                }
            }
        },
        confirmButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                // Share button
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(StudioCardHigh)
                        .border(1.dp, StudioBorderLight, RoundedCornerShape(6.dp))
                        .clickable {
                            val sendIntent = Intent(Intent.ACTION_SEND).apply {
                                type = "text/plain"
                                putExtra(Intent.EXTRA_SUBJECT, moduleName)
                                putExtra(Intent.EXTRA_TEXT, generatedJson)
                            }
                            context.startActivity(Intent.createChooser(sendIntent, "Bagikan Modul JSON Game Nuke"))
                        }
                        .padding(horizontal = 10.dp, vertical = 7.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.Share, contentDescription = null, tint = StudioSky, modifier = Modifier.size(12.dp))
                        Spacer(Modifier.width(3.dp))
                        Text("SHARE", color = StudioSky, fontWeight = FontWeight.Bold, fontSize = 10.5.sp)
                    }
                }

                // Save to file button
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(StudioEmerald)
                        .clickable {
                            val cleanName = moduleName.replace(Regex("[^a-zA-Z0-9_-]"), "_").lowercase()
                            saveFileLauncher.launch("gamenuke_${cleanName}.json")
                        }
                        .padding(horizontal = 12.dp, vertical = 7.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.FileUpload, contentDescription = null, tint = Color.Black, modifier = Modifier.size(12.dp))
                        Spacer(Modifier.width(3.dp))
                        Text("SIMPAN FILE", color = Color.Black, fontWeight = FontWeight.Bold, fontSize = 10.5.sp)
                    }
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("TUTUP", color = StudioTextMuted, fontSize = 10.5.sp)
            }
        }
    )
}

@Composable
private fun ExecutiveCreatorGuideDialog(
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val templateJson = remember { NukeModuleJsonImporter.getCreatorGuideTemplate() }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = StudioCard,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.MenuBook, contentDescription = null, tint = StudioSky, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Text("PANDUAN CREATOR MODULE", color = StudioTextPrimary, fontWeight = FontWeight.Bold, fontSize = 14.sp)
            }
        },
        text = {
            Column(modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
                Text(
                    "Buat modul tuning (.json) untuk dibagikan ke komunitas atau rekan Anda. Modul yang diimpor akan divalidasi secara ketat oleh sistem keamanan Game Nuke sebelum diterapkan.",
                    color = StudioTextMuted,
                    fontSize = 11.sp
                )

                Spacer(Modifier.height(10.dp))

                // Rules
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(8.dp),
                    color = StudioCardHigh,
                    border = BorderStroke(1.dp, StudioBorderLight)
                ) {
                    Column(modifier = Modifier.padding(10.dp)) {
                        Text("ATURAN & STANDAR KEAMANAN:", color = StudioEmerald, fontWeight = FontWeight.Bold, fontSize = 10.sp)
                        Spacer(Modifier.height(4.dp))
                        Text("1. Source yang didukung: 'SYSTEM', 'GLOBAL', 'SECURE', dan 'PROP'.", color = StudioTextPrimary, fontSize = 9.5.sp)
                        Text("2. Nilai aman: Angka, flag boolean (0/1), float (misal 120.0).", color = StudioTextPrimary, fontSize = 9.5.sp)
                        Text("3. Anti-Bootloop SafeGuard: Parameter berbahaya (misal lcd_density, bootloader, zygote flags) akan otomatis ditolak oleh Game Nuke agar HP user tidak bootloop.", color = StudioAmber, fontSize = 9.5.sp)
                    }
                }

                Spacer(Modifier.height(10.dp))

                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text("STRUKTUR JSON SIAP PAKAI:", color = StudioTextDim, fontSize = 9.sp, fontWeight = FontWeight.Bold)
                    TextButton(
                        onClick = {
                            val cb = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                            cb?.setPrimaryClip(ClipData.newPlainText("GameNuke Module Template", templateJson))
                            NukeToast.success(context, "Template JSON disalin ke clipboard!")
                        }
                    ) {
                        Icon(Icons.Rounded.ContentCopy, contentDescription = null, tint = StudioEmerald, modifier = Modifier.size(12.dp))
                        Spacer(Modifier.width(3.dp))
                        Text("SALIN TEMPLATE", color = StudioEmerald, fontSize = 9.5.sp, fontWeight = FontWeight.Bold)
                    }
                }

                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(6.dp),
                    color = Color(0xFF090C0E),
                    border = BorderStroke(0.8.dp, StudioBorder)
                ) {
                    Text(
                        templateJson,
                        color = StudioTextPrimary,
                        fontSize = 9.sp,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier.padding(10.dp)
                    )
                }
            }
        },
        confirmButton = {
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .background(StudioEmerald)
                    .clickable { onDismiss() }
                    .padding(horizontal = 14.dp, vertical = 7.dp)
            ) {
                Text("MENGERTI", color = Color.Black, fontWeight = FontWeight.Bold, fontSize = 10.5.sp)
            }
        }
    )
}

