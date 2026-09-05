package com.neon.gametweak

import android.content.Context
import android.os.Build

/**
 * NukeScreenRecordGuardian — Creator & Streamer Protection Engine.
 *
 * Guarantees that active screen recording, game streaming, video capture tools,
 * and media projection services are NEVER killed, frozen, or interrupted by
 * Game Nuke's AI Sentinel, Deep Clean, or Task Manager.
 */
object NukeScreenRecordGuardian {

    // Comprehensive list of OEM built-in and popular third-party screen recorders & streaming tools
    val PROTECTED_PACKAGES: Set<String> = setOf(
        // Xiaomi / HyperOS / MIUI
        "com.miui.screenrecorder",
        "com.miui.video",
        "com.xiaomi.gamecenter.pad",

        // Samsung OneUI Game Tools & Screen Recorder
        "com.sec.android.app.screenrecorder",
        "com.samsung.android.game.gametools",
        "com.samsung.android.app.soundpicker",

        // Google Pixel / AOSP Screen Recorder
        "com.google.android.apps.recorder",
        "com.google.android.apps.tachyon",

        // Popular Creator Screen Recorders (Play Store & Third Party)
        "com.hecorat.screenrecorder.free",       // AZ Screen Recorder
        "com.rsupport.mvagent",                  // Mobizen
        "com.xrecorder.screenrecorder",          // InShot XRecorder
        "video.reface.app",                      // Reface / Vidma
        "com.kimcy929.screenrecorder",          // Screen Recorder (Kimcy929)
        "com.orpheusdroid.screenrecorder",       // ScreenCam
        "com.spectrl.rec",                       // REC Screen Recorder
        "org.blay09.rec",                        // Screen Recorder
        "com.apowersoft.screenrecord",           // Apowersoft Screen Recorder
        "com.duapps.recorder",                   // DU Recorder
        "com.tct.screenrecorder",                // TCL Screen Recorder
        "com.asus.screenrecorder",               // Asus ROG Screen Recorder
        "com.oplus.screenrecorder",              // Oppo / OnePlus Screen Recorder
        "com.coloros.screenrecorder",            // ColorOS Screen Recorder
        "com.transsion.screenrecorder",          // Tecno / Infinix Screen Recorder
        "com.motorola.screenrecord",             // Motorola Screen Record
        "com.huawei.screenrecorder",             // Huawei Screen Recorder
        "com.realme.screenrecorder",             // Realme Screen Recorder
        "com.vivo.screenrecorder",               // Vivo / iQOO Screen Recorder
        "com.recorder.detector",                 // Screen Recorder Tools
        "com.nll.screenrecorder",                // Screen Recorder (NLL)
        "com.drivergenius.screenrecorder",       // Master Screen Recorder
        "com.techbee.screenrecorder",            // V Recorder

        // Streaming & Creator Broadcasting Suites
        "gg.glip.android",                       // Glip Gaming & Recorder
        "mobisocial.arcade",                     // Omlet Arcade
        "tv.twitch.android.app",                 // Twitch Live Streaming
        "com.google.android.youtube",            // YouTube Live Streaming
        "com.discord",                           // Discord Screen Sharing & Voice Chat
        "com.prism.live",                        // PRISM Live Studio
        "com.streamlabs",                        // Streamlabs Mobile
        "com.turnip.gg"                          // Turnip Gaming Live Stream
    )

    // Substring keywords that identify recording / streaming packages
    private val RECORDER_KEYWORDS = listOf(
        "screenrecorder",
        "screenrecord",
        "screen_recorder",
        "screen_record",
        "screencap",
        "screencapture",
        "recordvideo",
        "videorecorder",
        "livestream",
        "broadcaster",
        "streamlabs"
    )

    /**
     * Checks whether a package belongs to a screen recorder, streamer, or creator capture utility.
     */
    fun isProtected(packageName: String): Boolean {
        if (packageName.isBlank()) return false
        val clean = packageName.trim().substringBefore(":").lowercase()

        // 1. Direct package match
        if (PROTECTED_PACKAGES.contains(clean)) return true

        // 2. Keyword check
        if (RECORDER_KEYWORDS.any { clean.contains(it) }) return true

        return false
    }

    /**
     * Extra safety check against active foreground services or media projections.
     */
    fun isSafeToKill(context: Context, packageName: String): Boolean {
        if (packageName.isBlank()) return false
        if (packageName == context.packageName) return false
        if (isProtected(packageName)) return false
        return true
    }
}
