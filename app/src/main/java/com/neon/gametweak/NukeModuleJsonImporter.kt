package com.neon.gametweak

import android.content.Context
import android.util.Log
import com.neon.gametweak.NukeSystemParamGuardian.ParamCategory
import com.neon.gametweak.NukeSystemParamGuardian.ParamSource
import com.neon.gametweak.NukeSystemParamGuardian.RiskLevel
import com.neon.gametweak.NukeSystemParamRepository.NukeSystemParam
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

/**
 * NukeModuleJsonImporter — Enterprise JSON Module Ingestion & Anti-Bootloop SafeGuard Engine.
 *
 * Allows developers and gaming creators to distribute custom `.json` parameter modules
 * to their viewers, while enforcing strict parameter safety and device protection,
 * and malicious shell injection.
 */
object NukeModuleJsonImporter {

    private const val TAG = "NukeModuleImporter"

    data class ModuleParamItem(
        val source: ParamSource,
        val key: String,
        val rawValue: String,
        val sanitizedValue: String,
        val description: String,
        val riskLevel: RiskLevel,
        val isAllowed: Boolean,
        val validationReason: String? = null
    )

    data class ParsedModuleResult(
        val moduleName: String,
        val author: String,
        val version: String,
        val description: String,
        val targetOem: String?,
        val allItems: List<ModuleParamItem>
    ) {
        val safeItems: List<ModuleParamItem> get() = allItems.filter { it.isAllowed }
        val blockedItems: List<ModuleParamItem> get() = allItems.filter { !it.isAllowed }
        val totalCount: Int get() = allItems.size
        val safeCount: Int get() = safeItems.size
        val blockedCount: Int get() = blockedItems.size
    }

    data class ApplyModuleReport(
        val totalAttempted: Int,
        val successCount: Int,
        val failCount: Int,
        val blockedSkippedCount: Int,
        val message: String
    )

    /**
     * Parses and deeply validates a module JSON string.
     * Supports:
     *   1. Standard Game Nuke Module schema ({ "module_name": "...", "parameters": [...] })
     *   2. Array of parameter objects ([ { "source": "SYSTEM", "key": "...", "value": "..." } ])
     *   3. Flat key-value dictionary ({ "pointer_speed": "7", "edge_touch_prevention": "0" })
     */
    fun parseAndValidate(jsonString: String): Result<ParsedModuleResult> = runCatching {
        val trimmed = jsonString.trim()
        require(trimmed.isNotBlank()) { "JSON input is empty" }

        var moduleName = "Custom Tuning Module"
        var author = "Community Creator"
        var version = "1.0"
        var description = "Custom gaming parameter optimizations"
        var targetOem: String? = null
        val items = mutableListOf<ModuleParamItem>()

        if (trimmed.startsWith("[")) {
            // Format 2: Array of parameter objects
            val jsonArray = JSONArray(trimmed)
            for (i in 0 until jsonArray.length()) {
                val obj = jsonArray.optJSONObject(i) ?: continue
                parseParamObject(obj)?.let { items.add(it) }
            }
        } else {
            val rootObj = JSONObject(trimmed)

            if (rootObj.has("module_name") || rootObj.has("name") || rootObj.has("parameters")) {
                // Format 1: Standard structured module
                moduleName = rootObj.optString("module_name", rootObj.optString("name", moduleName))
                author = rootObj.optString("author", rootObj.optString("creator", author))
                version = rootObj.optString("version", version)
                description = rootObj.optString("description", description)
                targetOem = rootObj.optString("target_oem", rootObj.optString("oem", "")).ifBlank { null }

                val paramArray = rootObj.optJSONArray("parameters") ?: rootObj.optJSONArray("params")
                if (paramArray != null) {
                    for (i in 0 until paramArray.length()) {
                        val obj = paramArray.optJSONObject(i) ?: continue
                        parseParamObject(obj)?.let { items.add(it) }
                    }
                }
            } else {
                // Format 3: Flat dictionary { "key": "value" }
                val keys = rootObj.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    val value = rootObj.optString(key, "")
                    val source = inferSourceForKey(key)
                    val validated = validateSingleItem(source, key, value, "Imported from flat dictionary")
                    items.add(validated)
                }
            }
        }

        require(items.isNotEmpty()) { "No valid parameters found in the module JSON" }

        ParsedModuleResult(
            moduleName = moduleName,
            author = author,
            version = version,
            description = description,
            targetOem = targetOem,
            allItems = items
        )
    }

    private fun parseParamObject(obj: JSONObject): ModuleParamItem? {
        val key = obj.optString("key", obj.optString("name", "")).trim()
        if (key.isBlank()) return null

        val rawVal = obj.optString("value", obj.optString("val", "")).trim()
        val desc = obj.optString("description", obj.optString("desc", ""))
        val sourceStr = obj.optString("source", obj.optString("namespace", "")).uppercase(Locale.ROOT).trim()

        val source = when (sourceStr) {
            "GLOBAL" -> ParamSource.GLOBAL
            "SYSTEM" -> ParamSource.SYSTEM
            "SECURE" -> ParamSource.SECURE
            "PROP", "PROPERTIES" -> ParamSource.PROP
            "KERNEL" -> ParamSource.KERNEL
            else -> inferSourceForKey(key)
        }

        return validateSingleItem(source, key, rawVal, desc)
    }

    private fun inferSourceForKey(key: String): ParamSource {
        val lower = key.lowercase(Locale.ROOT)
        return when {
            lower.startsWith("persist.") || lower.startsWith("debug.") || lower.startsWith("ro.") || lower.startsWith("vendor.") -> ParamSource.PROP
            lower.contains("animation") || lower.contains("low_power") || lower.contains("wifi") || lower.contains("mobile") -> ParamSource.GLOBAL
            lower.contains("secure") -> ParamSource.SECURE
            else -> ParamSource.SYSTEM
        }
    }

    /**
     * Deep inspection against Anti-Bootloop Blacklist, Shell Injection, and Safe Value Ranges.
     */
    private fun validateSingleItem(
        source: ParamSource,
        key: String,
        rawValue: String,
        description: String
    ): ModuleParamItem {
        // 1. Anti-Bootloop Risk Classification
        val risk = NukeSystemParamGuardian.classifyRisk(source, key)

        if (risk == RiskLevel.BLOCKED) {
            return ModuleParamItem(
                source = source,
                key = key,
                rawValue = rawValue,
                sanitizedValue = rawValue,
                description = description,
                riskLevel = RiskLevel.BLOCKED,
                isAllowed = false,
                validationReason = "Blocked by Anti-Bootloop SafeGuard (Critical system component)"
            )
        }

        // 2. Shell Injection & Safe Range Validation
        val validation = NukeSystemParamGuardian.validateValue(source, key, rawValue)
        if (!validation.isValid) {
            return ModuleParamItem(
                source = source,
                key = key,
                rawValue = rawValue,
                sanitizedValue = rawValue,
                description = description,
                riskLevel = RiskLevel.BLOCKED,
                isAllowed = false,
                validationReason = validation.errorMessage ?: "Invalid value syntax or shell injection risk"
            )
        }

        return ModuleParamItem(
            source = source,
            key = key,
            rawValue = rawValue,
            sanitizedValue = validation.sanitizedValue,
            description = description,
            riskLevel = risk,
            isAllowed = true,
            validationReason = if (risk == RiskLevel.MODERATE) "Caution: Verified system parameter" else null
        )
    }

    /**
     * Applies all verified safe parameters in the module and updates the rollback journal.
     */
    suspend fun applyModule(
        context: Context,
        module: ParsedModuleResult
    ): ApplyModuleReport = withContext(Dispatchers.IO) {
        val safeItems = module.safeItems
        var successCount = 0
        var failCount = 0

        for (item in safeItems) {
            val dummyParam = NukeSystemParam(
                id = "${item.source.name}:${item.key}",
                source = item.source,
                key = item.key,
                value = item.sanitizedValue,
                category = ParamCategory.SYSTEM,
                riskLevel = item.riskLevel,
                description = item.description
            )

            val res = NukeSystemParamRepository.applyParameter(context, dummyParam, item.sanitizedValue)
            if (res.success) {
                successCount++
            } else {
                failCount++
                Log.w(TAG, "Failed applying module item: ${item.key} - ${res.message}")
            }
        }

        val msg = buildString {
            append("Applied $successCount parameters from '${module.moduleName}'")
            if (module.blockedCount > 0) {
                append(" · ${module.blockedCount} dangerous keys blocked by SafeGuard")
            }
            if (failCount > 0) {
                append(" · $failCount shell writes failed")
            }
        }

        ApplyModuleReport(
            totalAttempted = safeItems.size,
            successCount = successCount,
            failCount = failCount,
            blockedSkippedCount = module.blockedCount,
            message = msg
        )
    }

    /**
     * Serializes parameters into a valid Game Nuke Module JSON schema.
     */
    fun exportModuleJson(
        moduleName: String,
        author: String,
        version: String = "1.0",
        description: String,
        targetOem: String? = null,
        items: List<ModuleParamItem>
    ): String {
        val root = JSONObject().apply {
            put("module_name", moduleName.trim().ifBlank { "Game Nuke Custom Tuning" })
            put("author", author.trim().ifBlank { "Community Creator" })
            put("version", version)
            put("description", description.trim().ifBlank { "Custom gaming parameter optimizations" })
            if (!targetOem.isNullOrBlank()) {
                put("target_oem", targetOem)
            }
            val array = JSONArray()
            items.forEach { item ->
                val obj = JSONObject().apply {
                    put("source", item.source.name)
                    put("key", item.key)
                    put("value", item.sanitizedValue.ifBlank { item.rawValue })
                    if (item.description.isNotBlank()) {
                        put("description", item.description)
                    }
                }
                array.put(obj)
            }
            put("parameters", array)
        }
        return root.toString(2)
    }

    /**
     * Standard sample template provided to creators and developers.
     */
    fun getCreatorGuideTemplate(): String {
        return """
{
  "module_name": "Pro Gaming Touch & Aim Stabilizer",
  "author": "Agung Developer / YourChannelName",
  "version": "1.0",
  "target_oem": "UNIVERSAL",
  "description": "Optimasi respon layar, zero-edge mistouch filter, dan unlock refresh rate 120Hz.",
  "parameters": [
    {
      "source": "SYSTEM",
      "key": "edge_touch_prevention",
      "value": "0",
      "description": "Menonaktifkan deadzone layar samping untuk respon sentuhan tepi layar yang optimal"
    },
    {
      "source": "SYSTEM",
      "key": "pointer_speed",
      "value": "7",
      "description": "Kecepatan kursor dan responsivitas sentuhan maksimal"
    },
    {
      "source": "GLOBAL",
      "key": "peak_refresh_rate",
      "value": "120.0",
      "description": "Memaksa layar berjalan pada refresh rate puncak 120Hz"
    },
    {
      "source": "GLOBAL",
      "key": "window_animation_scale",
      "value": "0.5",
      "description": "Animasi jendela ultra cepat (0.5x) untuk latency minimal"
    },
    {
      "source": "PROP",
      "key": "debug.sf.latch_unsignaled",
      "value": "1",
      "description": "SurfaceFlinger pipeline low-jitter mode"
    }
  ]
}
""".trimIndent()
    }
}
