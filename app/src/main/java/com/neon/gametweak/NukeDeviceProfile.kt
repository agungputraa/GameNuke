package com.neon.gametweak

import android.os.Build

/**
 * Device identity used only for diagnostics and compatibility messaging.
 *
 * Game Nuke never branches performance commands by chipset brand. Every deep operation is still
 * capability-probed and read-back verified, which is safer across Snapdragon, MediaTek, Exynos,
 * Tensor, Unisoc and OEM-custom Android builds.
 */
data class NukeDeviceProfile(
    val manufacturer: String,
    val model: String,
    val soc: String,
    val hardware: String,
    val androidLabel: String,
    val wirelessDebugHint: String,
) {
    val compactLabel: String
        get() = listOf(manufacturer, model).filter { it.isNotBlank() }.joinToString(" ").trim()
            .ifBlank { "ANDROID DEVICE" }

    companion object {
        fun current(): NukeDeviceProfile {
            val maker = Build.MANUFACTURER.orEmpty().trim().ifBlank { "Android" }
            val model = Build.MODEL.orEmpty().trim().ifBlank { "Device" }
            val soc = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                listOf(Build.SOC_MANUFACTURER.orEmpty(), Build.SOC_MODEL.orEmpty())
                    .filter { it.isNotBlank() }
                    .joinToString(" ")
                    .ifBlank { Build.HARDWARE.orEmpty() }
            } else Build.HARDWARE.orEmpty()

            val hint = when (maker.lowercase()) {
                "xiaomi", "redmi", "poco" ->
                    "If extended control is restricted, also enable USB debugging (Security settings) in Developer options."
                "oppo", "oneplus", "realme" ->
                    "If extended control is limited, check Developer options for Permission monitoring and disable it while Device Control is active."
                "huawei", "honor" ->
                    "If Device Control randomly stops, enable the vendor debugging option that stays available while USB mode is Charge only."
                "meizu" ->
                    "If extended control is limited, check Developer options and temporarily disable the vendor restriction that blocks debugging commands."
                "vivo", "iqoo" ->
                    "Keep Wireless debugging enabled during the session; vendor background limits can stop discovery."
                "samsung" ->
                    "Wireless debugging is supported on Android 11+; keep Game Nuke unrestricted from battery optimization if discovery is interrupted."
                "transsion", "tecno", "infinix", "itel" ->
                    "On HiOS/XOS, exclude Game Nuke from Phone Master auto-freeze to ensure seamless background wireless ADB and HUD stability."
                "asus", "rog" ->
                    "ROG Phone / Armoury Crate: Game Nuke works alongside Game Genie. Ensure background execution is set to Unrestricted."
                "motorola", "lenovo" ->
                    "Moto ReadyFor / Gametime: Wireless debugging operates natively on Android 11+ with capability-probed fallback."
                "google" ->
                    "Google Pixel: Pure AOSP wireless debugging supported natively across Android 11 through Android 16/17."
                else ->
                    "Android 11+ Wireless debugging is capability-probed; rejected OEM commands automatically fall back."
            }

            return NukeDeviceProfile(
                manufacturer = maker,
                model = model,
                soc = soc.ifBlank { "Unknown SoC" },
                hardware = Build.HARDWARE.orEmpty().ifBlank { "unknown" },
                androidLabel = "Android ${Build.VERSION.RELEASE} • API ${Build.VERSION.SDK_INT}",
                wirelessDebugHint = hint,
            )
        }
    }
}
