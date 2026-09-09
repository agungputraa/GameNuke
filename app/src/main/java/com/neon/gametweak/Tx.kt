package com.neon.gametweak
/** English-only compatibility for legacy preference and runtime callers. No translation network calls. */
object Tx {
    const val currentLang = "en"
    val supportedLangs = listOf("en" to "English")
    fun setLang(code: String) = Unit
    fun toggle() = Unit
}
