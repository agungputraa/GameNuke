package com.neon.gametweak

import android.content.SharedPreferences

/**
 * SharedPreferences is persisted across app versions. If an older release stored the same key
 * with a different type, Android getters can throw ClassCastException. These helpers recover the
 * affected key to its default instead of letting a background service or Compose screen crash.
 */
internal fun SharedPreferences.safeBoolean(key: String, default: Boolean = false): Boolean =
    readPreferenceSafely(key, default) { getBoolean(key, default) }

internal fun SharedPreferences.safeInt(key: String, default: Int = 0): Int =
    readPreferenceSafely(key, default) { getInt(key, default) }

internal fun SharedPreferences.safeLong(key: String, default: Long = 0L): Long =
    readPreferenceSafely(key, default) { getLong(key, default) }

internal fun SharedPreferences.safeFloat(key: String, default: Float = 0f): Float =
    readPreferenceSafely(key, default) { getFloat(key, default) }

internal fun SharedPreferences.safeString(key: String, default: String = ""): String =
    readPreferenceSafely(key, default) { getString(key, default) ?: default }

internal fun SharedPreferences.safeNullableString(key: String): String? =
    readPreferenceSafely<String?>(key, null) { getString(key, null) }

private inline fun <T> SharedPreferences.readPreferenceSafely(
    key: String,
    default: T,
    reader: () -> T,
): T = try {
    reader()
} catch (_: ClassCastException) {
    runCatching { edit().remove(key).apply() }
    default
} catch (_: Throwable) {
    default
}
