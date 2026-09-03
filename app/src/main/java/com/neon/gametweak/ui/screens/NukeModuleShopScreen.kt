package com.neon.gametweak.ui.screens

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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CutCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Extension
import androidx.compose.material.icons.rounded.Security
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.neon.gametweak.NukeModuleCatalog
import com.neon.gametweak.NukeRuntimeState
import com.neon.gametweak.NukeToast
import com.neon.gametweak.Tx
import com.neon.gametweak.safeBoolean
import com.neon.gametweak.safeInt
import com.neon.gametweak.ui.theme.Neon

/** First-party module manager shared with the in-game cockpit. */
@Composable
fun NukeModuleShopScreen() {
    val context = LocalContext.current
    val prefs = remember { NukeModuleCatalog.prefs(context) }
    val categories = remember { listOf("All") + NukeModuleCatalog.modules.map { it.category }.distinct() }
    var selectedCategory by remember { mutableStateOf("All") }
    var query by remember { mutableStateOf("") }
    var revision by remember { mutableIntStateOf(0) }
    DisposableEffect(prefs) {
        val listener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key?.startsWith("module_") == true || key?.startsWith("cross_") == true) revision++
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        onDispose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    val enabledCount = remember(revision) { NukeModuleCatalog.enabledCount(prefs) }
    val modules = remember(selectedCategory, query, revision) {
        val needle = query.trim()
        NukeModuleCatalog.modules.filter { module ->
            val categoryMatch = selectedCategory == "All" || module.category == selectedCategory
            val queryMatch = needle.isBlank() || listOf(module.title, module.description, module.category, module.id)
                .any { it.contains(needle, ignoreCase = true) }
            categoryMatch && queryMatch
        }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize().background(Neon.Bg),
        contentPadding = PaddingValues(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            Column(
                modifier = Modifier.fillMaxWidth()
                    .background(Neon.BgCard, CutCornerShape(topStart = 16.dp, bottomEnd = 16.dp))
                    .border(1.dp, Neon.Outline, CutCornerShape(topStart = 16.dp, bottomEnd = 16.dp))
                    .padding(16.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.Extension, null, tint = Neon.Accent)
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text("MODULE SHOP", color = Color.White, fontWeight = FontWeight.Black, fontSize = 16.sp, letterSpacing = 1.6.sp)
                        Text("$enabledCount/${NukeModuleCatalog.modules.size} ENABLED // COCKPIT SYNC", color = Neon.TextDim, fontFamily = FontFamily.Monospace, fontSize = 9.sp)
                    }
                }
                Spacer(Modifier.height(10.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.Security, null, tint = Color(0xFFFFC857))
                    Spacer(Modifier.width(8.dp))
                    Text(
                        Tx.t(
                            "Semua modul adalah fitur bawaan Game Nuke. Tidak ada script shell remote yang diunduh dan dijalankan.",
                            "All modules are first-party Game Nuke features. No remote shell script is downloaded and executed.",
                        ),
                        color = Color(0xFFBACBC5), fontSize = 10.sp, lineHeight = 14.sp,
                    )
                }
            }
        }

        item {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it.take(60) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                leadingIcon = { Icon(Icons.Rounded.Search, null, tint = Neon.Accent) },
                placeholder = { Text("Search module / tool", color = Neon.TextDim, fontSize = 10.sp) },
                label = { Text("MODULE SEARCH", fontSize = 8.sp) },
            )
        }

        item {
            LazyRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                items(categories.size, key = { categories[it] }) { index ->
                    val category = categories[index]
                    val active = selectedCategory == category
                    Box(
                        modifier = Modifier.width(92.dp)
                            .background(if (active) Neon.Accent.copy(alpha = .14f) else Neon.BgCardL, CutCornerShape(7.dp))
                            .border(1.dp, if (active) Neon.Accent.copy(alpha = .55f) else Neon.Outline, CutCornerShape(7.dp))
                            .clickable { selectedCategory = category }
                            .padding(vertical = 9.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(category.uppercase(), color = if (active) Neon.Accent else Neon.TextDim, fontSize = 7.5.sp, fontWeight = FontWeight.Black, maxLines = 1)
                    }
                }
            }
        }

        if (selectedCategory == "All" || selectedCategory == "Tactical") {
            item(key = "crosshair_quick_setup") {
                CrosshairQuickSetup(prefs, revision)
            }
        }

        items(modules.size, key = { modules[it].id }) { index ->
            val module = modules[index]
            val checked = NukeModuleCatalog.isEnabled(prefs, module.id)
            Row(
                modifier = Modifier.fillMaxWidth()
                    .background(Neon.BgCard, CutCornerShape(topStart = 12.dp, bottomEnd = 12.dp))
                    .border(1.dp, if (checked) Neon.Accent.copy(alpha = .30f) else Neon.Outline, CutCornerShape(topStart = 12.dp, bottomEnd = 12.dp))
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(module.title, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                        Spacer(Modifier.width(8.dp))
                        Text(module.category.uppercase(), color = Neon.Accent, fontFamily = FontFamily.Monospace, fontSize = 7.sp)
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(module.description, color = Neon.TextDim, fontSize = 9.5.sp, lineHeight = 13.sp)
                }
                Spacer(Modifier.width(12.dp))
                Switch(
                    checked = checked,
                    onCheckedChange = { enabled ->
                        NukeModuleCatalog.setEnabled(prefs, module.id, enabled)
                        revision++
                        NukeToast.success(context, if (enabled) Tx.t("${module.title} diaktifkan", "${module.title} enabled") else Tx.t("${module.title} dinonaktifkan", "${module.title} disabled"))
                    },
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = Color(0xFF07110E),
                        checkedTrackColor = Neon.Accent,
                        uncheckedThumbColor = Color(0xFF9AA7A2),
                        uncheckedTrackColor = Neon.BgInset,
                    ),
                )
            }
        }
    }
}

@Composable
private fun CrosshairQuickSetup(prefs: android.content.SharedPreferences, revision: Int) {
    val context = LocalContext.current
    var enabled by remember(revision) { mutableStateOf(prefs.safeBoolean("cross_en", false)) }
    var style by remember(revision) { mutableIntStateOf(prefs.safeInt("cross_type", 1).coerceIn(0, 3)) }
    var size by remember(revision) { mutableFloatStateOf(prefs.safeInt("cross_size", 22).coerceIn(8, 64).toFloat()) }
    var opacity by remember(revision) { mutableFloatStateOf(prefs.safeInt("cross_opacity", 95).coerceIn(20, 100).toFloat()) }
    var dot by remember(revision) { mutableStateOf(prefs.safeBoolean("cross_dot", true)) }
    val styleNames = listOf("DOT", "CROSS", "CIRCLE", "TACTIC")

    Column(
        modifier = Modifier.fillMaxWidth()
            .background(Neon.BgCard, CutCornerShape(topStart = 12.dp, bottomEnd = 12.dp))
            .border(1.dp, Neon.Accent.copy(alpha = .28f), CutCornerShape(topStart = 12.dp, bottomEnd = 12.dp))
            .padding(14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("CROSSHAIR QUICK SETUP", color = Color.White, fontWeight = FontWeight.Black, fontSize = 12.sp, letterSpacing = 1.sp)
                Text("SYNCED WITH THE FLOATING COCKPIT", color = Neon.TextDim, fontFamily = FontFamily.Monospace, fontSize = 8.sp)
            }
            Switch(
                checked = enabled,
                onCheckedChange = {
                    enabled = it
                    prefs.edit().putBoolean("cross_en", it).apply()
                    NukeToast.success(context, if (it) Tx.t("Crosshair diaktifkan", "Crosshair enabled") else Tx.t("Crosshair dinonaktifkan", "Crosshair disabled"))
                },
                colors = SwitchDefaults.colors(checkedTrackColor = Neon.Accent),
            )
        }
        Spacer(Modifier.height(10.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            styleNames.forEachIndexed { index, name ->
                val active = style == index
                Box(
                    modifier = Modifier.weight(1f)
                        .background(if (active) Neon.Accent.copy(alpha = .14f) else Neon.BgInset, CutCornerShape(6.dp))
                        .border(1.dp, if (active) Neon.Accent.copy(alpha = .55f) else Neon.Outline, CutCornerShape(6.dp))
                        .clickable {
                            style = index
                            prefs.edit().putInt("cross_type", index).apply()
                            NukeToast.success(context, Tx.t("Gaya crosshair diubah ke $name", "Crosshair style set to $name"))
                        }
                        .padding(vertical = 8.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(name, color = if (active) Neon.Accent else Neon.TextDim, fontSize = 7.sp, fontWeight = FontWeight.Black)
                }
            }
        }
        Spacer(Modifier.height(10.dp))
        Text("SIZE  ${size.toInt()}", color = Neon.TextDim, fontFamily = FontFamily.Monospace, fontSize = 8.sp)
        Slider(
            value = size,
            onValueChange = { size = it },
            onValueChangeFinished = {
                prefs.edit().putInt("cross_size", size.toInt()).apply()
                NukeToast.success(context, Tx.t("Ukuran crosshair ${size.toInt()}", "Crosshair size set to ${size.toInt()}"))
            },
            valueRange = 8f..64f,
        )
        Text("OPACITY  ${opacity.toInt()}%", color = Neon.TextDim, fontFamily = FontFamily.Monospace, fontSize = 8.sp)
        Slider(
            value = opacity,
            onValueChange = { opacity = it },
            onValueChangeFinished = {
                prefs.edit().putInt("cross_opacity", opacity.toInt()).apply()
                NukeToast.success(context, Tx.t("Opacity crosshair ${opacity.toInt()}%", "Crosshair opacity set to ${opacity.toInt()}%"))
            },
            valueRange = 20f..100f,
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(
                checked = dot,
                onCheckedChange = {
                    dot = it
                    prefs.edit().putBoolean("cross_dot", it).apply()
                    NukeToast.success(context, if (it) Tx.t("Titik tengah crosshair diaktifkan", "Crosshair center dot enabled") else Tx.t("Titik tengah crosshair dinonaktifkan", "Crosshair center dot disabled"))
                },
            )
            Text("CENTER DOT", color = Color(0xFFCEDBD6), fontSize = 9.sp, fontWeight = FontWeight.Bold)
        }
    }
}

