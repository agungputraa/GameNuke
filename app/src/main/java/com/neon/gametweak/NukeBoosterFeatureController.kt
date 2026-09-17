package com.neon.gametweak

import android.content.Context
import android.util.Log
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/**
 * NukeBoosterFeatureController — Remote Emergency Killswitch & Dynamic Component Controller.
 *
 * Controlled via `booster_features.json` in the Game Nuke GitHub repository.
 * Allows the developer to instantly disable or isolate any faulty/buggy floating HUD component
 * without requiring an immediate APK re-release.
 */
object NukeBoosterFeatureController {

    private const val TAG = "NukeBoosterFeatures"
    private const val PREFS_NAME = "nuke_booster_remote_features"
    private const val KEY_CACHE = "remote_features_json"
    private const val KEY_LAST_FETCH = "last_fetch_ts"
    private const val THROTTLE_MS = 60 * 60 * 1000L // 1 hour

    private const val PRIMARY_URL = "https://gamenukevip.agungofficialdev.workers.dev/api/features"
    private const val FALLBACK_URL = "https://agungputraa.github.io/GameNuke/booster_features.json"

    private val disabledComponents = ConcurrentHashMap.newKeySet<String>()
    private val disabledReasons = ConcurrentHashMap<String, String>()
    private val executor = Executors.newSingleThreadExecutor()

    private var initialized = false

    fun init(context: Context) {
        if (initialized) return
        initialized = true

        val sp = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val cachedJson = sp.getString(KEY_CACHE, null)
        if (!cachedJson.isNullOrBlank()) {
            parseAndApply(cachedJson)
        }

        val lastFetch = sp.getLong(KEY_LAST_FETCH, 0L)
        if (System.currentTimeMillis() - lastFetch > THROTTLE_MS) {
            refreshAsync(context)
        }
    }

    fun isComponentEnabled(componentKey: String): Boolean {
        return !disabledComponents.contains(componentKey.lowercase().trim())
    }

    fun getDisabledReason(componentKey: String): String {
        return disabledReasons[componentKey.lowercase().trim()]
            ?: "This feature is temporarily undergoing developer maintenance."
    }

    fun checkAccessOrToast(context: Context, componentKey: String, featureName: String): Boolean {
        init(context)
        if (!isComponentEnabled(componentKey)) {
            val reason = getDisabledReason(componentKey)
            NukeToast.error(context, "$featureName disabled: $reason")
            return false
        }
        return true
    }

    fun refreshAsync(context: Context) {
        val appContext = context.applicationContext
        executor.execute {
            var jsonStr = fetchUrl(PRIMARY_URL)
            if (jsonStr == null) {
                jsonStr = fetchUrl(FALLBACK_URL)
            }

            if (jsonStr != null) {
                parseAndApply(jsonStr)
                appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                    .edit()
                    .putString(KEY_CACHE, jsonStr)
                    .putLong(KEY_LAST_FETCH, System.currentTimeMillis())
                    .apply()
                Log.d(TAG, "Successfully refreshed remote booster feature flags")
            }
        }
    }

    private fun fetchUrl(target: String): String? {
        return try {
            val url = URL(target)
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "GET"
            conn.connectTimeout = 5000
            conn.readTimeout = 5000
            conn.setRequestProperty("User-Agent", "GameNuke-App/3.2")
            if (conn.responseCode == 200) {
                BufferedReader(InputStreamReader(conn.inputStream, "UTF-8")).use { it.readText() }
            } else {
                null
            }.also { conn.disconnect() }
        } catch (_: Exception) {
            null
        }
    }

    private fun parseAndApply(jsonStr: String) {
        try {
            val json = JSONObject(jsonStr)
            disabledComponents.clear()
            disabledReasons.clear()

            val disabledArr = json.optJSONArray("disabled_components")
            if (disabledArr != null) {
                for (i in 0 until disabledArr.length()) {
                    val key = disabledArr.optString(i, "").lowercase().trim()
                    if (key.isNotBlank()) disabledComponents.add(key)
                }
            }

            val overrides = json.optJSONObject("component_overrides")
            if (overrides != null) {
                val keys = overrides.keys()
                while (keys.hasNext()) {
                    val k = keys.next()
                    val obj = overrides.getJSONObject(k)
                    val enabled = obj.optBoolean("enabled", true)
                    val reason = obj.optString("disabled_reason", "")
                    val cleanKey = k.lowercase().trim()
                    if (!enabled) {
                        disabledComponents.add(cleanKey)
                        if (reason.isNotBlank()) {
                            disabledReasons[cleanKey] = reason
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error parsing remote booster features: ${e.message}")
        }
    }
}
