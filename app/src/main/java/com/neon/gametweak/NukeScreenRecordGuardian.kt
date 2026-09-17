package com.neon.gametweak

import android.app.ActivityManager
import android.content.Context
import android.hardware.display.DisplayManager
import android.media.AudioManager
import android.os.Build
import android.util.Log
import android.view.Display
import java.util.Locale

/**
 * NukeScreenRecordGuardian — Creator & Streamer Protection Engine.
 *
 * Guarantees that active screen recording, game streaming, video capture tools,
 * and media projection services are NEVER killed, frozen, or interrupted by
 * Game Nuke's AI Sentinel, Deep Clean, or Task Manager.
 *
 * Fully supports:
 * - OEM built-in screen recorders (Xiaomi/HyperOS, Samsung OneUI, Oppo/Realme ColorOS,
 *   Vivo/iQOO OriginOS, Transsion/Infinix/Tecno, Asus ROG, Motorola, Pixel/AOSP).
 * - Third-party & Google Play Store screen recorders (XRecorder, AZ, Mobizen, Vidma, etc.).
 * - Live streaming suites (Twitch, YouTube, Discord, Streamlabs, Glip, Prism, Turnip).
 * - Real-time dynamic detection of active screen capture (MediaProjection, VirtualDisplay,
 *   AudioRecordingConfiguration, Foreground Service types).
 */
object NukeScreenRecordGuardian {

    private const val TAG = "NukeRecordGuardian"

    // Comprehensive list of OEM built-in and popular third-party screen recorders & streaming tools
    val PROTECTED_PACKAGES: Set<String> = setOf(
        // Xiaomi / HyperOS / MIUI / Poco
        "com.miui.screenrecorder",
        "com.miui.video",
        "com.miui.videoplayer",
        "com.xiaomi.screenrecorder",
        "com.xiaomi.gamecenter.pad",
        "com.miui.mediaeditor",
        "com.miui.extraphoto",

        // Samsung OneUI Game Tools & Screen Recorder
        "com.sec.android.app.screenrecorder",
        "com.samsung.android.game.gametools",
        "com.samsung.android.app.soundpicker",
        "com.samsung.android.app.camera.recorder",
        "com.samsung.android.app.smartcapture",
        "com.sec.android.app.quicktool",

        // Google Pixel / AOSP / Play Games / SystemUI
        "com.google.android.apps.recorder",
        "com.google.android.apps.tachyon",
        "com.google.android.play.games",
        "com.android.systemui.screenrecord",
        "com.android.systemui.screenshot",

        // Oppo / OnePlus / Realme ColorOS / OxygenOS / RealmeUI
        "com.oplus.screenrecorder",
        "com.coloros.screenrecorder",
        "com.heytap.screenrecorder",
        "com.realme.screenrecorder",
        "com.oplus.games",
        "com.nearme.gamecenter",
        "com.oplus.cosa",

        // Vivo / iQOO Funtouch / OriginOS
        "com.vivo.screenrecorder",
        "com.vivo.easyshare",
        "com.vivo.game",
        "com.vivo.gamewatch",

        // Asus ROG & ZenUI
        "com.asus.screenrecorder",
        "com.asus.gamewidget",
        "com.asus.gamecenter",

        // Transsion / Infinix / Tecno (XOS / HiOS)
        "com.transsion.screenrecorder",
        "com.transsion.smartpanel",

        // Motorola & Huawei / Honor
        "com.motorola.screenrecord",
        "com.motorola.gamemode",
        "com.huawei.screenrecorder",
        "com.hihonor.screenrecorder",

        // Sony Xperia & Game Enhancer
        "com.sonymobile.screenrecorder",
        "com.sonymobile.gameenhancer",
        "com.sonyericsson.screenrecorder",

        // ZTE / Nubia / RedMagic (NeoForce / GameSpace)
        "cn.nubia.screenrecord",
        "cn.nubia.recorder",
        "cn.nubia.gamelauncher",
        "cn.nubia.neoshare",
        "com.zte.screenrecorder",

        // Lenovo & Legion (ZUI)
        "com.lenovo.screenrecorder",
        "com.zui.screenrecorder",

        // Nothing OS
        "com.nothing.screenrecorder",
        "com.nothing.capture",

        // Meizu (Flyme)
        "com.meizu.media.screenrecorder",
        "com.meizu.gamecenter",

        // Popular Creator Screen Recorders (Play Store & Third Party)
        "com.hecorat.screenrecorder.free",                   // AZ Screen Recorder
        "com.rsupport.mvagent",                              // Mobizen
        "com.rsupport.mobizen.live",                         // Mobizen Live
        "com.rsupport.mobizen.sec",                          // Mobizen Samsung
        "com.xrecorder.screenrecorder",                      // InShot XRecorder
        "videoeditor.videorecorder.screenrecorder",          // InShot XRecorder alt
        "com.inshot.screenrecorder",                         // InShot Recorder
        "video.reface.app",                                  // Reface / Vidma
        "vidma.screenrecorder",                              // Vidma Recorder
        "com.kimcy929.screenrecorder",                       // Screen Recorder (Kimcy929)
        "com.orpheusdroid.screenrecorder",                   // ScreenCam
        "com.gosecure.screencam",                            // ScreenCam alt
        "com.spectrl.rec",                                   // REC Screen Recorder
        "org.blay09.rec",                                    // Screen Recorder
        "com.apowersoft.screenrecord",                       // Apowersoft Screen Recorder
        "com.duapps.recorder",                               // DU Recorder
        "com.tct.screenrecorder",                            // TCL Screen Recorder
        "com.recorder.detector",                             // Screen Recorder Tools
        "com.nll.screenrecorder",                            // Screen Recorder (NLL)
        "com.drivergenius.screenrecorder",                   // Master Screen Recorder
        "com.techbee.screenrecorder",                        // V Recorder
        "com.videomaker.editor.slideshow",                   // V Recorder Editor
        "com.mobi.screenrecorder",                           // GU Screen Recorder
        "com.tianxing.screenrecorder",                       // Super Screen Recorder
        "com.screenrecorder.pro",                            // Screen Recorder Pro
        "com.screenrecorder.recorder.editor",                // Screen Recorder & Video Recorder
        "com.gamersky.recorder",                             // Gamersky Recorder
        "com.tombayley.screenrecorder",                      // Screen Recorder
        "com.appsmartz.screenrecorder",                      // Screen Recorder Video
        "com.media.screenrecorder",                          // Screen Recorder Pro
        "com.camerasideas.trimmer",                          // AndroVid Video Editor & Capture
        "com.nexstreaming.app.kinemasterfree",                // KineMaster
        "com.ryzenrise.gamevids",                            // Game Recorder
        "com.camscanner.recorder",                           // CamScanner Recorder
        "com.blogspot.byterevapps.lollipopscreenrecorder",   // ADV Screen Recorder

        // Streaming & Creator Broadcasting Suites
        "gg.glip.android",                                   // Glip Gaming & Recorder
        "com.glip.mobile",                                   // Glip Mobile
        "mobisocial.arcade",                                 // Omlet Arcade
        "tv.twitch.android.app",                             // Twitch Live Streaming
        "com.discord",                                       // Discord Screen Sharing & Voice Chat
        "com.prism.live",                                    // PRISM Live Studio
        "com.streamlabs",                                    // Streamlabs Mobile
        "com.turnip.gg",                                     // Turnip Gaming Live Stream
        "com.bilibili.studio",                               // Bilibili Live Studio
        "com.ss.android.live.studio"                         // TikTok Live Studio Mobile
    )

    // Substring keywords that identify recording / streaming packages
    private val RECORDER_KEYWORDS = listOf(
        "screenrecorder",
        "screenrecord",
        "screen_recorder",
        "screen_record",
        "screencap",
        "screencapture",
        "screen_cap",
        "recordvideo",
        "videorecorder",
        "video_recorder",
        "livestream",
        "broadcaster",
        "streamlabs",
        "screenrec",
        "recorder",
        "recording",
        "captureservice",
        "xrecorder",
        "mobizen",
        "vidma",
        "camstudio",
        "audiorecorder",
        "mediaprojection",
        "gameenhancer",
        "gamerecorder"
    )

    // Cached dynamic MediaProjection package cache (TTL 3 seconds)
    private var lastMediaProjectionCheckTime = 0L
    private val cachedActiveProjectionPackages = mutableSetOf<String>()

    /**
     * Checks whether a package belongs to a screen recorder, streamer, or creator capture utility
     * based on static package lists and keyword matching.
     */
    fun isProtected(packageName: String?): Boolean {
        if (packageName.isNullOrBlank()) return false
        return runCatching {
            val clean = packageName.trim().substringBefore(":").lowercase(Locale.US)
            PROTECTED_PACKAGES.contains(clean) || RECORDER_KEYWORDS.any { clean.contains(it) }
        }.getOrDefault(false)
    }

    /**
     * Context-aware protection check:
     * Cross-references static list + dynamic MediaProjection + active VirtualDisplay
     * + active AudioRecordingConfiguration + active foreground services.
     */
    fun isProtected(context: Context, packageName: String?): Boolean {
        if (packageName.isNullOrBlank()) return false
        return runCatching {
            val clean = packageName.trim().substringBefore(":").lowercase(Locale.US)

            // 1. Static list & keyword matches
            if (isProtected(clean)) return@runCatching true

            // 2. Active MediaProjection grant holder
            val activeProjections = getActiveMediaProjectionPackages(context)
            if (activeProjections.any { it == clean || clean.startsWith(it) || it.startsWith(clean) }) {
                return@runCatching true
            }

            // 3. Audio Recording client package check (Android 10+ / API 29+)
            val activeAudioRecorders = getActiveAudioRecordingPackages(context)
            if (activeAudioRecorders.any { it == clean || clean.startsWith(it) || it.startsWith(clean) }) {
                return@runCatching true
            }

            // 4. Process inspection (Foreground Service with MediaProjection or Microphone type)
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            val runningProcs = runCatching { am?.runningAppProcesses.orEmpty() }.getOrDefault(emptyList())
            val matched = runningProcs.firstOrNull {
                val procName = it.processName.lowercase(Locale.US)
                procName == clean || procName.startsWith("$clean:") || it.pkgList?.any { p -> p.lowercase(Locale.US) == clean } == true
            }

            if (matched != null) {
                // If the process has an active foreground service and matches any recorder keyword
                if (matched.importance <= ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND_SERVICE) {
                    if (RECORDER_KEYWORDS.any { matched.processName.lowercase(Locale.US).contains(it) }) {
                        return@runCatching true
                    }
                }
            }

            false
        }.getOrDefault(false)
    }

    /**
     * Checks if the device has an active screen recording or casting session running right now.
     * Uses DisplayManager (Virtual Display), MediaProjection dumpsys, and active audio capture.
     */
    fun isScreenRecordingActive(context: Context): Boolean {
        return runCatching {
            // 1. Virtual display check (MediaProjection creates virtual displays)
            val dm = context.getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager
            val displays = dm?.displays.orEmpty()
            val hasVirtualDisplay = displays.any { d ->
                d.displayId != Display.DEFAULT_DISPLAY &&
                (d.name.contains("record", ignoreCase = true) ||
                 d.name.contains("screen", ignoreCase = true) ||
                 d.name.contains("cast", ignoreCase = true) ||
                 d.name.contains("capture", ignoreCase = true) ||
                 d.name.contains("mirror", ignoreCase = true) ||
                 d.name.contains("projection", ignoreCase = true) ||
                 d.name.contains("virtual", ignoreCase = true))
            }
            if (hasVirtualDisplay) return@runCatching true

            // 2. MediaProjection holder check
            if (getActiveMediaProjectionPackages(context).isNotEmpty()) return@runCatching true

            // 3. Audio recording with screen capture keywords
            if (getActiveAudioRecordingPackages(context).any { isProtected(it) }) return@runCatching true

            false
        }.getOrDefault(false)
    }

    /**
     * Resolves packages currently holding a MediaProjection session across Android 9 to 15.
     */
    fun getActiveMediaProjectionPackages(context: Context): Set<String> {
        val now = System.currentTimeMillis()
        if (now - lastMediaProjectionCheckTime < 3_000L && cachedActiveProjectionPackages.isNotEmpty()) {
            return cachedActiveProjectionPackages
        }

        synchronized(cachedActiveProjectionPackages) {
            if (now - lastMediaProjectionCheckTime < 3_000L && cachedActiveProjectionPackages.isNotEmpty()) {
                return cachedActiveProjectionPackages
            }

            cachedActiveProjectionPackages.clear()
            val cmd = "dumpsys media_projection 2>/dev/null"
            val output = runCatching {
                val adb = AdbManager.getInstance(context)
                if (adb.isConnected()) {
                    adb.executeCommand(cmd, "/", 1_500L)?.output.orEmpty()
                } else {
                    NukeConnectionManager.executeCommand(cmd, 1_500L)?.output.orEmpty()
                }
            }.getOrDefault("")

            if (output.isNotBlank()) {
                val regexPatterns = listOf(
                    Regex("""(?:packageName|pkg|mPackageName)=([a-zA-Z0-9_.]+)"""),
                    Regex("""MediaProjection\{[^}]+packageName=([a-zA-Z0-9_.]+)[^}]*\}"""),
                    Regex("""App:\s*([a-zA-Z0-9_.]+)"""),
                    Regex("""RecordConfig:\s*packageName:\s*([a-zA-Z0-9_.]+)"""),
                    Regex("""Client:\s*([a-zA-Z0-9_.]+)""")
                )

                regexPatterns.forEach { regex ->
                    regex.findAll(output).forEach { match ->
                        val pkg = match.groupValues.getOrNull(1)?.trim()?.lowercase(Locale.US)
                        if (!pkg.isNullOrBlank() && pkg != "android" && !pkg.startsWith("com.android.systemui")) {
                            cachedActiveProjectionPackages.add(pkg)
                        }
                    }
                }
            }
            lastMediaProjectionCheckTime = now
            return cachedActiveProjectionPackages
        }
    }

    /**
     * Native check via AudioManager to discover active recording client packages (API 29+).
     * Works on ALL devices without root or ADB!
     */
    fun getActiveAudioRecordingPackages(context: Context): Set<String> {
        val recordingPackages = mutableSetOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            val audio = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            val configs = runCatching { audio?.activeRecordingConfigurations.orEmpty() }.getOrDefault(emptyList())
            for (config in configs) {
                val clientPkg = runCatching {
                    val method = config.javaClass.getMethod("getClientPackageName")
                    method.isAccessible = true
                    method.invoke(config) as? String
                }.getOrNull()?.lowercase(Locale.US)
                if (!clientPkg.isNullOrBlank() && clientPkg != "android") {
                    recordingPackages.add(clientPkg)
                }
            }
        }
        return recordingPackages
    }

    /**
     * Checks whether a package is ACTIVELY RECORDING or STREAMING right now.
     * STRICT USER RULE: Screen recorders are ONLY protected if they are CURRENTLY RECORDING!
     * If they are idle/standby in the background, they return FALSE and are safe to be ended/balanced.
     */
    fun isActivelyRecording(context: Context, packageName: String?): Boolean {
        if (packageName.isNullOrBlank()) return false
        return runCatching {
            val clean = packageName.trim().substringBefore(":").lowercase(Locale.US)

            // Must be a recorder/streamer package or have recorder keywords
            val isRecorder = isProtected(clean)
            if (!isRecorder) return@runCatching false

            // 1. Check if MediaProjection session is active and held by this package
            val activeProjections = getActiveMediaProjectionPackages(context)
            if (activeProjections.any { it == clean || clean.startsWith(it) || it.startsWith(clean) }) {
                return@runCatching true
            }

            // 2. Check if active AudioRecording is running by this package
            val activeAudio = getActiveAudioRecordingPackages(context)
            if (activeAudio.any { it == clean || clean.startsWith(it) || it.startsWith(clean) }) {
                return@runCatching true
            }

            // 3. If global screen recording is active (e.g. VirtualDisplay created for recording)
            if (isScreenRecordingActive(context)) {
                val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
                val runningProcs = runCatching { am?.runningAppProcesses.orEmpty() }.getOrDefault(emptyList())
                val matched = runningProcs.firstOrNull {
                    val procName = it.processName.lowercase(Locale.US)
                    procName == clean || procName.startsWith("$clean:") || it.pkgList?.any { p -> p.lowercase(Locale.US) == clean } == true
                }
                if (matched != null && matched.importance <= ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND_SERVICE) {
                    return@runCatching true
                }
                if (activeProjections.isNotEmpty()) {
                    return@runCatching activeProjections.contains(clean)
                }
            }

            false
        }.getOrDefault(false)
    }

    /**
     * Extra safety check against active foreground services or media projections.
     */
    fun isSafeToKill(context: Context, packageName: String): Boolean {
        if (packageName.isBlank()) return false
        if (packageName == context.packageName) return false
        return runCatching {
            // Screen recorder is only immune if actively recording!
            if (isActivelyRecording(context, packageName)) return@runCatching false
            true
        }.getOrDefault(false)
    }
}
