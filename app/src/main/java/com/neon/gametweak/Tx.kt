package com.neon.gametweak

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap

object Tx {
    var currentLang by mutableStateOf("en")

    val supportedLangs = listOf(
        "en" to "English",
        "id" to "Bahasa Indonesia",
        "pt" to "Português",
        "vi" to "Tiếng Việt",
        "th" to "ไทย",
        "tr" to "Türkçe",
        "es" to "Español",
        "ru" to "Русский",
        "zh-CN" to "中文",
        "ar" to "العربية",
    )

    private val cache = mutableStateMapOf<String, String>()
    private val pending = ConcurrentHashMap.newKeySet<String>()
    private val translationScope = CoroutineScope(
        SupervisorJob() + Dispatchers.IO + CoroutineExceptionHandler { _, _ -> Unit },
    )
    private val translationSlots = Semaphore(2)
    private const val MAX_CACHE = 500

    fun t(idText: String, enText: String): String {
        val lang = currentLang
        if (lang == "en") return enText
        if (lang == "id") return idText

        val key = lang + "::" + enText
        cache[key]?.let { return it }

        if (pending.add(key)) {
            translationScope.launch {
                try {
                    val translated = translationSlots.withPermit { fetchTranslation(enText, lang) }
                    if (translated.isNotBlank()) {
                        withContext(Dispatchers.Main) {
                            if (cache.size >= MAX_CACHE) {
                                val oldKeys = cache.keys.take(cache.size - MAX_CACHE + 1)
                                oldKeys.forEach { cache.remove(it) }
                            }
                            cache[key] = translated
                        }
                    }
                } finally {
                    pending.remove(key)
                }
            }
        }
        return enText
    }

    private fun fetchTranslation(text: String, targetLang: String): String {
        val encoded = URLEncoder.encode(text, "UTF-8")
        runCatching {
            val url = URL("https://translate.googleapis.com/translate_a/single?client=gtx&sl=en&tl=$targetLang&dt=t&q=$encoded")
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "GET"
            conn.connectTimeout = 4000
            conn.readTimeout = 6000
            conn.setRequestProperty("User-Agent", "Mozilla/5.0")
            if (conn.responseCode == 200) {
                val response = conn.inputStream.bufferedReader().readText()
                val outer = JSONArray(response).getJSONArray(0)
                val sb = StringBuilder()
                for (i in 0 until outer.length()) {
                    val seg = outer.optJSONArray(i)
                    if (seg != null && seg.length() > 0) sb.append(seg.optString(0, ""))
                }
                val joined = sb.toString().trim()
                if (joined.isNotBlank()) return joined
            }
        }
        runCatching {
            val url = URL("https://api.mymemory.translated.net/get?q=$encoded&langpair=en|$targetLang")
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "GET"
            conn.connectTimeout = 4000
            conn.readTimeout = 6000
            if (conn.responseCode == 200) {
                val response = conn.inputStream.bufferedReader().readText()
                val translated = JSONObject(response).getJSONObject("responseData").getString("translatedText")
                if (translated.isNotBlank() && !translated.contains("MYMEMORY WARNING", ignoreCase = true)) {
                    return translated
                }
            }
        }
        return ""
    }

    fun setLang(code: String) {
        if (code == currentLang) return
        currentLang = code
    }

    fun toggle() {
        currentLang = if (currentLang == "en") "id" else "en"
    }
}
