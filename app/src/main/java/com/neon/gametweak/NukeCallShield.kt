package com.neon.gametweak
import android.content.Context
/** Compatibility for saved settings. Call screening is unavailable in this distribution. */
object NukeCallShield {
    const val PREF_ENABLED = "call_shield_enabled"
    fun isSupported(context: Context) = false
    fun isRoleHeld(context: Context) = false
    fun isEnabled(context: Context) = false
    fun requestRole(context: Context) = false
    fun setEnabled(context: Context, enabled: Boolean): Boolean {
        context.getSharedPreferences("NukePrefs", Context.MODE_PRIVATE).edit().remove(PREF_ENABLED).apply()
        return !enabled
    }
}
