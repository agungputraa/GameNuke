package com.neon.gametweak

import android.app.role.RoleManager
import android.content.Context
import android.content.Intent
import android.os.Build

/** Official Android call-screening role helper. No invasive phone/call-log permission is used. */
object NukeCallShield {
    const val PREF_ENABLED = "call_shield_enabled"

    fun isSupported(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return false
        val rm = context.getSystemService(RoleManager::class.java) ?: return false
        return runCatching { rm.isRoleAvailable(RoleManager.ROLE_CALL_SCREENING) }.getOrDefault(false)
    }

    fun isRoleHeld(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return false
        val rm = context.getSystemService(RoleManager::class.java) ?: return false
        return runCatching { rm.isRoleHeld(RoleManager.ROLE_CALL_SCREENING) }.getOrDefault(false)
    }

    fun isEnabled(context: Context): Boolean =
        context.getSharedPreferences("NukePrefs", Context.MODE_PRIVATE)
            .safeBoolean(PREF_ENABLED, false) && isRoleHeld(context)

    fun setEnabled(context: Context, enabled: Boolean): Boolean {
        if (enabled && !isRoleHeld(context)) return false
        context.getSharedPreferences("NukePrefs", Context.MODE_PRIVATE)
            .edit().putBoolean(PREF_ENABLED, enabled).apply()
        return true
    }

    /** Opens a tiny Activity bridge because RoleManager's chooser requires an Activity context. */
    fun requestRole(context: Context): Boolean {
        if (!isSupported(context)) return false
        if (isRoleHeld(context)) return true
        return runCatching {
            context.startActivity(
                Intent(context, CallShieldRoleActivity::class.java)
                    .addFlags(
                        Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION or
                            Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP,
                    ),
            )
            true
        }.getOrDefault(false)
    }
}
