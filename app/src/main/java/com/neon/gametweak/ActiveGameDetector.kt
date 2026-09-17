package com.neon.gametweak

import android.app.ActivityManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import java.util.Locale

class ActiveGameDetector(
    private val context: Context,
    private val adbManager: AdbManager = AdbManager.getInstance(context)
) {
    data class ActiveGame(
        val packageName: String,
        val label: String,
        val genreCode: Int,
        val genreLabel: String,
        val isKnownGame: Boolean
    )

    private data class GameMeta(val label: String, val genreCode: Int, val genreLabel: String)

    private val knownGames = mapOf(
        // MOBA
        "com.mobile.legends" to GameMeta("Mobile Legends", 3, "MOBA"),
        "com.mobile.legends.mi" to GameMeta("Mobile Legends", 3, "MOBA"),
        "com.riotgames.league.wildrift" to GameMeta("Wild Rift", 3, "MOBA"),
        "com.riotgames.league.wildrifttw" to GameMeta("Wild Rift", 3, "MOBA"),
        "com.riotgames.league.wildriftvn" to GameMeta("Wild Rift", 3, "MOBA"),
        "com.garena.game.kgid" to GameMeta("Arena of Valor", 3, "MOBA"),
        "com.garena.game.kgvn" to GameMeta("Arena of Valor", 3, "MOBA"),
        "com.garena.game.kgtw" to GameMeta("Arena of Valor", 3, "MOBA"),
        "com.levelinfinite.sgameGlobal" to GameMeta("Honor of Kings", 3, "MOBA"),
        "com.supercell.brawlstars" to GameMeta("Brawl Stars", 3, "MOBA"),
        "com.supercell.squad" to GameMeta("Squad Busters", 3, "MOBA"),
        "jp.pokemon.pokemonunite" to GameMeta("Pokémon UNITE", 3, "MOBA"),
        "com.netease.g98na" to GameMeta("Onmyoji Arena", 3, "MOBA"),

        // Battle Royale & Tactical Shooters
        "com.dts.freefireth" to GameMeta("Free Fire", 7, "Battle Royale"),
        "com.dts.freefiremax" to GameMeta("Free Fire MAX", 7, "Battle Royale"),
        "com.tencent.ig" to GameMeta("PUBG Mobile", 7, "Battle Royale"),
        "com.pubg.imobile" to GameMeta("Battlegrounds Mobile", 7, "Battle Royale"),
        "com.pubg.krmobile" to GameMeta("PUBG Mobile KR", 7, "Battle Royale"),
        "com.vng.pubgmobile" to GameMeta("PUBG Mobile VN", 7, "Battle Royale"),
        "com.rekoo.pubgm" to GameMeta("PUBG Mobile", 7, "Battle Royale"),
        "com.tencent.iglite" to GameMeta("PUBG Mobile Lite", 7, "Battle Royale"),
        "com.activision.callofduty.shooter" to GameMeta("Call of Duty Mobile", 4, "FPS"),
        "com.garena.game.codm" to GameMeta("Call of Duty Mobile", 4, "FPS"),
        "com.activision.callofduty.warzone" to GameMeta("Warzone Mobile", 4, "FPS"),
        "com.netease.bloodstrike" to GameMeta("Blood Strike", 4, "FPS"),
        "com.netease.chiji" to GameMeta("Rules of Survival", 7, "Battle Royale"),
        "com.farlightgames.farlight84.gp" to GameMeta("Farlight 84", 7, "Battle Royale"),
        "com.axlebolt.standoff2" to GameMeta("Standoff 2", 4, "FPS"),
        "com.criticalforceentertainment.criticalops" to GameMeta("Critical Ops", 4, "FPS"),
        "com.proximabeta.mf.uamo" to GameMeta("Arena Breakout", 4, "FPS"),
        "com.netease.lztgglobal" to GameMeta("Lost Light", 4, "FPS"),
        "com.ea.gp.apexmobile" to GameMeta("Apex Legends", 7, "Battle Royale"),

        // Heavy RPG & Open World
        "com.miHoYo.GenshinImpact" to GameMeta("Genshin Impact", 2, "Heavy Game"),
        "com.HoYoverse.hkrpgoversea" to GameMeta("Honkai: Star Rail", 2, "Heavy Game"),
        "com.hoyoverse.hkrpgoversea" to GameMeta("Honkai: Star Rail", 2, "Heavy Game"),
        "com.HoYoverse.Nap" to GameMeta("Zenless Zone Zero", 2, "Heavy Game"),
        "com.miHoYo.Nap" to GameMeta("Zenless Zone Zero", 2, "Heavy Game"),
        "com.miHoYo.bh3oversea" to GameMeta("Honkai Impact 3rd", 2, "Heavy Game"),
        "com.kurogame.wutheringwaves.global" to GameMeta("Wuthering Waves", 2, "Heavy Game"),
        "com.proximabeta.nikke" to GameMeta("GODDESS OF VICTORY: NIKKE", 2, "Heavy Game"),
        "net.netmarble.sololv" to GameMeta("Solo Leveling: ARISE", 2, "Heavy Game"),
        "com.nexon.bluearchive" to GameMeta("Blue Archive", 2, "RPG"),
        "com.YoStarEN.Arknights" to GameMeta("Arknights", 1, "Strategy"),
        "com.hotta.tofglobal" to GameMeta("Tower of Fantasy", 2, "Heavy Game"),
        "com.pearlabyss.blackdesertm" to GameMeta("Black Desert Mobile", 2, "RPG"),
        "com.gravity.ro" to GameMeta("Ragnarok M", 2, "RPG"),

        // Sandbox & Strategy
        "com.roblox.client" to GameMeta("Roblox", 1, "Game"),
        "com.mojang.minecraftpe" to GameMeta("Minecraft", 1, "Game"),
        "com.supercell.clashofclans" to GameMeta("Clash of Clans", 1, "Strategy"),
        "com.supercell.clashroyale" to GameMeta("Clash Royale", 1, "Strategy"),
        "com.supercell.hayday" to GameMeta("Hay Day", 1, "Casual"),
        "com.riotgames.league.teamfighttactics" to GameMeta("TFT Mobile", 1, "Strategy"),

        // Racing & Sports
        "com.gameloft.android.ANMP.GloftA9HM" to GameMeta("Asphalt Legends", 5, "Racing"),
        "com.gameloft.android.ANMP.GloftA8HM" to GameMeta("Asphalt 8", 5, "Racing"),
        "com.carxtech.sr" to GameMeta("CarX Street", 5, "Racing"),
        "com.carxtech.carxdr2" to GameMeta("CarX Drift Racing 2", 5, "Racing"),
        "com.ea.gp.fifamobile" to GameMeta("EA FC Mobile", 6, "Sports"),
        "jp.konami.pesam" to GameMeta("eFootball", 6, "Sports"),
        "com.konami.pesam" to GameMeta("eFootball", 6, "Sports"),
        "com.firsttouchgames.dls7" to GameMeta("Dream League Soccer", 6, "Sports"),
        "com.ea.game.nfs14_row" to GameMeta("Need for Speed", 5, "Racing"),
        "com.miniclip.eightballpool" to GameMeta("8 Ball Pool", 6, "Sports"),

        // Casual & Action
        "com.kitkagames.fallbuddies" to GameMeta("Stumble Guys", 1, "Game"),
        "com.kiloo.subwaysurf" to GameMeta("Subway Surfers", 1, "Casual"),
        "com.king.candycrushsaga" to GameMeta("Candy Crush", 1, "Casual"),

        // Gaming Emulators
        "org.ppsspp.ppsspp" to GameMeta("PPSSPP Emulator", 1, "Emulator"),
        "org.ppsspp.ppssppgold" to GameMeta("PPSSPP Gold", 1, "Emulator"),
        "xyz.aethersx2.android" to GameMeta("AetherSX2 PS2", 2, "Emulator"),
        "net.classic.nethersx2" to GameMeta("NetherSX2 PS2", 2, "Emulator"),
        "org.citra.citra_emu" to GameMeta("Citra 3DS", 2, "Emulator"),
        "org.dolphinemu.dolphinemu" to GameMeta("Dolphin GC/Wii", 2, "Emulator"),
        "org.yuzu.yuzu_emu" to GameMeta("Yuzu Switch", 2, "Emulator"),
        "org.vita3k.emulator" to GameMeta("Vita3K PS Vita", 2, "Emulator"),
        "com.winlator" to GameMeta("Winlator Windows Emu", 2, "Emulator"),
        "com.winlator.cmod" to GameMeta("Winlator CMod", 2, "Emulator"),
        "com.winlator.frost" to GameMeta("Winlator Frost", 2, "Emulator"),
        "net.kdt.pojavlaunch" to GameMeta("PojavLauncher", 1, "Emulator"),
        "com.retroarch" to GameMeta("RetroArch", 1, "Emulator"),
        "com.retroarch.aarch64" to GameMeta("RetroArch 64", 1, "Emulator")
    )

    data class ForegroundState(
        val focusedPackage: String?,
        val resumedGame: ActiveGame?,
    )

    suspend fun detectState(): ForegroundState {
        // 1. Try privileged shell command through universal connection manager
        val windowOutput = NukeConnectionManager.executeCommand("dumpsys window", timeoutMs = 2000L)?.output.orEmpty()
        val activitiesOutput = NukeConnectionManager.executeCommand("dumpsys activity activities", timeoutMs = 2000L)?.output.orEmpty()

        val combined = buildString {
            if (windowOutput.isNotBlank()) appendLine(windowOutput)
            if (activitiesOutput.isNotBlank()) appendLine(activitiesOutput)
            if (isEmpty() && adbManager.isConnected()) {
                val w = ShellBridge.result(adbManager, "dumpsys window", timeoutMs = 1800L)
                val a = ShellBridge.result(adbManager, "dumpsys activity activities", timeoutMs = 1800L)
                if (w.isSuccess) appendLine(w.output)
                if (a.isSuccess) appendLine(a.output)
            }
        }

        if (combined.isNotBlank()) {
            val focusedLines = combined.lineSequence().filter { line ->
                line.contains("mCurrentFocus") ||
                    line.contains("mFocusedApp") ||
                    line.contains("topResumedActivity") ||
                    line.contains("mTopActivity") ||
                    line.contains("mResumedActivity") ||
                    line.contains("ResumedActivity")
            }.joinToString("\n")
            val packages = extractPackages(focusedLines)
            val nonIgnored = packages.filterNot { isIgnoredPackage(it) }
            val game = nonIgnored.asSequence().mapNotNull { classifyGame(it) }.firstOrNull()
                ?: nonIgnored.firstOrNull()?.let { createDynamicGameIfValid(it) }
            if (game != null) {
                NukeRuntimeState.lastKnownGamePackage = game.packageName
                if (NukeRuntimeState.state.value.activePackage.isNullOrBlank()) {
                    NukeRuntimeState.update { it.copy(activePackage = game.packageName) }
                }
                runCatching {
                    context.getSharedPreferences("NukePrefs", Context.MODE_PRIVATE)
                        .edit().putString("overlay_active_package", game.packageName).apply()
                }
                return ForegroundState(game.packageName, game)
            }
            if (nonIgnored.isNotEmpty()) {
                val topPkg = nonIgnored.first()
                NukeRuntimeState.lastKnownGamePackage = topPkg
                return ForegroundState(topPkg, null)
            }
        }

        // 2. High-Accuracy Fallback: Query UsageStatsManager recent events (works on all devices without root/ADB)
        val usm = context.getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager
        if (usm != null) {
            val now = System.currentTimeMillis()
            val events = runCatching { usm.queryEvents(now - 900_000L, now) }.getOrNull()
            if (events != null) {
                val event = UsageEvents.Event()
                var lastCandidatePkg: String? = null
                var lastCandidateTime: Long = 0L
                while (events.hasNextEvent()) {
                    events.getNextEvent(event)
                    if (event.eventType == UsageEvents.Event.ACTIVITY_RESUMED) {
                        val pkg = event.packageName
                        if (!pkg.isNullOrBlank() && !isIgnoredPackage(pkg) && event.timeStamp >= lastCandidateTime) {
                            lastCandidatePkg = pkg
                            lastCandidateTime = event.timeStamp
                        }
                    }
                }
                if (!lastCandidatePkg.isNullOrBlank()) {
                    val game = classifyGame(lastCandidatePkg) ?: createDynamicGameIfValid(lastCandidatePkg)
                    if (game != null) {
                        NukeRuntimeState.lastKnownGamePackage = game.packageName
                        if (NukeRuntimeState.state.value.activePackage.isNullOrBlank()) {
                            NukeRuntimeState.update { it.copy(activePackage = game.packageName) }
                        }
                        runCatching {
                            context.getSharedPreferences("NukePrefs", Context.MODE_PRIVATE)
                                .edit().putString("overlay_active_package", game.packageName).apply()
                        }
                        return ForegroundState(game.packageName, game)
                    }
                }
            }
        }

        // 3. Fallback: Query ActivityManager foreground process
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        val runningProcs = runCatching { am?.runningAppProcesses.orEmpty() }.getOrDefault(emptyList())
        val fgProc = runningProcs.firstOrNull {
            it.importance == ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND &&
                !isIgnoredPackage(it.pkgList?.firstOrNull() ?: it.processName)
        }
        val fgPkg = fgProc?.pkgList?.firstOrNull() ?: fgProc?.processName?.substringBefore(":")
        val fgGame = fgPkg?.let { classifyGame(it) ?: createDynamicGameIfValid(it) }
        if (fgGame != null) {
            NukeRuntimeState.lastKnownGamePackage = fgGame.packageName
            if (NukeRuntimeState.state.value.activePackage.isNullOrBlank()) {
                NukeRuntimeState.update { it.copy(activePackage = fgGame.packageName) }
            }
            runCatching {
                context.getSharedPreferences("NukePrefs", Context.MODE_PRIVATE)
                    .edit().putString("overlay_active_package", fgGame.packageName).apply()
            }
            return ForegroundState(fgPkg, fgGame)
        }

        // 4. Session memory fallback: Check activePackage or lastKnownGamePackage
        val sessionPkg = NukeRuntimeState.state.value.activePackage?.takeIf { it.isNotBlank() && !isIgnoredPackage(it) }
            ?: NukeRuntimeState.lastKnownGamePackage?.takeIf { it.isNotBlank() && !isIgnoredPackage(it) }

        if (!sessionPkg.isNullOrBlank()) {
            val sessionGame = classifyGame(sessionPkg) ?: createDynamicGameIfValid(sessionPkg)
            if (sessionGame != null) {
                return ForegroundState(sessionPkg, sessionGame)
            }
        }

        return ForegroundState(fgPkg, null)
    }

    suspend fun detectForegroundPackage(): String? = detectState().focusedPackage

    suspend fun detect(): ActiveGame? = detectState().resumedGame

    fun isLikelyGame(pkg: String?): Boolean {
        if (pkg.isNullOrBlank() || isIgnoredPackage(pkg)) return false
        if (classifyGame(pkg) != null) return true
        return try {
            val pm = context.packageManager
            val info = if (Build.VERSION.SDK_INT >= 33) {
                pm.getApplicationInfo(pkg, PackageManager.ApplicationInfoFlags.of(0L))
            } else {
                @Suppress("DEPRECATION") pm.getApplicationInfo(pkg, 0)
            }
            val isSys = (info.flags and ApplicationInfo.FLAG_SYSTEM) != 0
            val isUpdatedSys = (info.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0
            if (isSys && !isUpdatedSys) return false
            checkGameEngineNativeLibs(info) || checkGameMetadata(info)
        } catch (_: Exception) {
            false
        }
    }

    fun createDynamicGameIfValid(pkg: String): ActiveGame? {
        if (isIgnoredPackage(pkg)) return null
        return try {
            val pm = context.packageManager
            val info = if (Build.VERSION.SDK_INT >= 33) {
                pm.getApplicationInfo(pkg, PackageManager.ApplicationInfoFlags.of(0L))
            } else {
                @Suppress("DEPRECATION") pm.getApplicationInfo(pkg, 0)
            }
            val isSys = (info.flags and ApplicationInfo.FLAG_SYSTEM) != 0
            val isUpdatedSys = (info.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0
            val isCategoryGame = (Build.VERSION.SDK_INT >= 26 && info.category == ApplicationInfo.CATEGORY_GAME) ||
                                 ((info.flags and ApplicationInfo.FLAG_IS_GAME) != 0)
            if (isSys && !isUpdatedSys && !isCategoryGame) return null

            val label = pm.getApplicationLabel(info).toString().ifBlank { prettyName(pkg) }
            val genre = inferGenre((pkg + " " + label).lowercase(Locale.US))
            ActiveGame(pkg, label, genre.first, genre.second, false)
        } catch (_: Exception) {
            null
        }
    }

    fun classifyGame(pkg: String?): ActiveGame? {
        if (pkg.isNullOrBlank() || isIgnoredPackage(pkg)) return null

        // 1. Exact match in known catalog
        knownGames[pkg]?.let { return ActiveGame(pkg, it.label, it.genreCode, it.genreLabel, true) }

        // 2. Dynamic resolution via Android PackageManager (Universal for all Android brands & versions)
        return fromPackageManager(pkg)
    }

    private fun checkGameEngineNativeLibs(info: ApplicationInfo): Boolean {
        return try {
            val nativeDir = info.nativeLibraryDir
            if (!nativeDir.isNullOrBlank()) {
                val dir = java.io.File(nativeDir)
                if (dir.isDirectory) {
                    val libs = dir.list().orEmpty()
                    libs.any { lib ->
                        val lower = lib.lowercase(Locale.US)
                        lower.contains("unity") ||
                        lower.contains("il2cpp") ||
                        lower.contains("unreal") ||
                        lower.contains("ue4") ||
                        lower.contains("godot") ||
                        lower.contains("cocos") ||
                        lower.contains("defold") ||
                        lower.contains("monosgen") ||
                        lower.contains("fmod") ||
                        lower.contains("criware") ||
                        lower == "libmain.so"
                    }
                } else false
            } else false
        } catch (_: Exception) {
            false
        }
    }

    private fun checkGameMetadata(info: ApplicationInfo): Boolean {
        return try {
            val meta = info.metaData ?: return false
            meta.keySet().any { key ->
                val lower = key.lowercase(Locale.US)
                lower.contains("unity") ||
                lower.contains("unreal") ||
                lower.contains("games.app_id") ||
                lower.contains("gamepad")
            }
        } catch (_: Exception) {
            false
        }
    }

    private fun fromPackageManager(pkg: String): ActiveGame? {
        return try {
            val pm = context.packageManager
            val info = if (Build.VERSION.SDK_INT >= 33) {
                pm.getApplicationInfo(pkg, PackageManager.ApplicationInfoFlags.of(0L))
            } else {
                @Suppress("DEPRECATION") pm.getApplicationInfo(pkg, 0)
            }

            // System services / frameworks that are not updated system apps and not in known games are never games
            val isSys = (info.flags and ApplicationInfo.FLAG_SYSTEM) != 0
            val isUpdatedSys = (info.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0
            val isCategoryGame = (Build.VERSION.SDK_INT >= 26 && info.category == ApplicationInfo.CATEGORY_GAME) ||
                                 ((info.flags and ApplicationInfo.FLAG_IS_GAME) != 0)

            if (isSys && !isUpdatedSys && !isCategoryGame) {
                return null
            }

            // Also check for standard Android GAME intent filter
            val hasGameIntent = runCatching {
                val intent = Intent(Intent.ACTION_MAIN).addCategory("android.intent.category.GAME").setPackage(pkg)
                pm.queryIntentActivities(intent, 0).isNotEmpty()
            }.getOrDefault(false)

            val isEngine = checkGameEngineNativeLibs(info)
            val hasGameMeta = checkGameMetadata(info)

            val label = pm.getApplicationLabel(info).toString().ifBlank { prettyName(pkg) }
            val lower = (pkg + " " + label).lowercase(Locale.US)

            if (!isCategoryGame && !hasGameIntent && !isEngine && !hasGameMeta) {
                // Secondary check: only explicit game titles/terms, NEVER generic vendor names (like tencent, netease, etc.)
                val explicitGameWord = listOf(
                    "game", "games", "free fire", "freefire", "mobile legends", "pubg", "codm",
                    "genshin", "honkai", "roblox", "minecraft", "asphalt", "carx", "brawl stars",
                    "clash of clans", "clash royale", "wild rift", "arena of valor", "honor of kings",
                    "farlight", "blood strike", "stumble guys", "subway surfers", "candy crush",
                    "emulator", "ppsspp", "aethersx2", "nethersx2", "citra", "dolphin", "yuzu", "winlator"
                ).any { lower.contains(it) }
                if (!explicitGameWord) return null
            }

            val genre = inferGenre(lower)
            ActiveGame(pkg, label, genre.first, genre.second, false)
        } catch (_: Exception) {
            null
        }
    }

    private fun inferGenre(text: String): Pair<Int, String> {
        return when {
            listOf("pubg", "free fire", "battle", "royale", "farlight").any { text.contains(it) } -> 7 to "Battle Royale"
            listOf("cod", "shooter", "fps", "strike", "standoff").any { text.contains(it) } -> 4 to "FPS"
            listOf("mobile legends", "wild rift", "moba", "arena of valor", "honor of kings").any { text.contains(it) } -> 3 to "MOBA"
            listOf("genshin", "honkai", "wuthering", "impact", "zenless", "star rail").any { text.contains(it) } -> 2 to "Heavy Game"
            listOf("asphalt", "racing", "carx", "drift", "speed").any { text.contains(it) } -> 5 to "Racing"
            listOf("emulator", "ppsspp", "ps2", "3ds", "wii", "switch", "winlator").any { text.contains(it) } -> 1 to "Emulator"
            else -> 1 to "Game"
        }
    }

    private fun extractPackages(raw: String): List<String> {
        if (raw.isBlank()) return emptyList()
        val component = Regex("([a-zA-Z][a-zA-Z0-9_]*(?:\\.[a-zA-Z0-9_]+)+)/[a-zA-Z0-9_.$]+")
        val packageName = Regex("packageName=([a-zA-Z][a-zA-Z0-9_]*(?:\\.[a-zA-Z0-9_]+)+)")
        val ordered = LinkedHashSet<String>()
        raw.lineSequence().forEach { line ->
            component.findAll(line).forEach { match -> match.groupValues.getOrNull(1)?.let(ordered::add) }
            packageName.findAll(line).forEach { match -> match.groupValues.getOrNull(1)?.let(ordered::add) }
        }
        return ordered.toList()
    }

    private fun isIgnoredPackage(pkg: String): Boolean {
        val lower = pkg.lowercase(Locale.US)
        return lower == context.packageName.lowercase(Locale.US) ||
            lower == "com.neon.gametweak" ||
            lower.contains("gametweak") ||
            lower.contains("wandev") ||
            lower.contains("frb.axeron") ||
            lower.contains("nukedaemon") ||
            lower == "android" ||
            lower.startsWith("android.") ||
            lower.startsWith("com.android.") ||
            lower.startsWith("com.google.android.") ||
            lower.startsWith("media.") ||
            lower.startsWith("vendor.") ||
            lower.startsWith("android.hardware.") ||
            lower.contains("soter") ||
            lower.contains("soterserver") ||
            lower.contains("swcodec") ||
            lower.contains("hwcodec") ||
            lower.contains("codec") ||
            lower.contains("launcher") ||
            lower.contains("inputmethod") ||
            lower.contains("keyboard") ||
            lower.contains("ime")
    }

    private fun prettyName(pkg: String): String {
        return pkg.substringAfterLast('.')
            .replace('_', ' ')
            .replace('-', ' ')
            .replaceFirstChar { it.uppercase() }
    }
}
