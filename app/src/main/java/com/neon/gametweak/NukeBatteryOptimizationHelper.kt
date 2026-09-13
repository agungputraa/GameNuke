package com.neon.gametweak

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import android.util.Log

/**
 * NukeBatteryOptimizationHelper — Universal OEM Battery Exemption Handler.
 *
 * Ensures Game Nuke's background game monitoring, floating HUD, macro engine,
 * and AI Sentinel are not prematurely killed by aggressive OEM battery managers
 * (e.g. Xiaomi MIUI/HyperOS, Samsung OneUI, Oppo ColorOS, Vivo OriginOS, Transsion XOS).
 */
object NukeBatteryOptimizationHelper {

    private const val TAG = "NukeBatteryHelper"

    /**
     * Checks whether Game Nuke is currently exempted from Android OS battery optimization.
     */
    fun isIgnoringBatteryOptimizations(context: Context): Boolean {
        return try {
            val powerManager = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
            powerManager?.isIgnoringBatteryOptimizations(context.packageName) ?: false
        } catch (e: Exception) {
            Log.w(TAG, "Failed to query isIgnoringBatteryOptimizations", e)
            false
        }
    }

    /**
     * Prompts the system dialog requesting battery optimization exemption.
     * Falls back to general battery optimization settings if direct request is blocked by OEM ROM.
     */
    fun requestIgnoreBatteryOptimizations(context: Context) {
        val packageName = context.packageName
        try {
            val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                data = Uri.parse("package:$packageName")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        } catch (e: Exception) {
            Log.w(TAG, "Direct REQUEST_IGNORE_BATTERY_OPTIMIZATIONS intent rejected, falling back to settings", e)
            try {
                val fallbackIntent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(fallbackIntent)
            } catch (fallbackEx: Exception) {
                Log.e(TAG, "Failed to open battery optimization settings", fallbackEx)
                try {
                    val appDetails = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                        data = Uri.parse("package:$packageName")
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    context.startActivity(appDetails)
                } catch (_: Exception) {
                    NukeToast.error(context, "Cannot open battery settings on this device.")
                }
            }
        }
    }
}
