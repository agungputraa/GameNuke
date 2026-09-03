package com.neon.gametweak

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager

class ActiveGameDetector(
    private val context: Context,
    private val adbManager: AdbManager
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
        "com.mobile.legends" to GameMeta("Mobile Legends", 3, "MOBA"),
        "com.mobile.legends.mi" to GameMeta("Mobile Legends", 3, "MOBA"),
        "com.tencent.ig" to GameMeta("PUBG Mobile", 7, "Battle Royale"),
        "com.pubg.imobile" to GameMeta("Battlegrounds Mobile", 7, "Battle Royale"),
        "com.rekoo.pubgm" to GameMeta("PUBG Mobile", 7, "Battle Royale"),
        "com.activision.callofduty.shooter" to GameMeta("Call of Duty Mobile", 4, "FPS"),
        "com.garena.game.codm" to GameMeta("Call of Duty Mobile", 4, "FPS"),
        "com.dts.freefireth" to GameMeta("Free Fire", 7, "Battle Royale"),
        "com.dts.freefiremax" to GameMeta("Free Fire MAX", 7, "Battle Royale"),
        "com.riotgames.league.wildrift" to GameMeta("Wild Rift", 3, "MOBA"),
        "com.miHoYo.GenshinImpact" to GameMeta("Genshin Impact", 2, "Heavy Game"),
        "com.HoYoverse.hkrpgoversea" to GameMeta("Honkai: Star Rail", 2, "Heavy Game"),
        "com.hoyoverse.hkrpgoversea" to GameMeta("Honkai: Star Rail", 2, "Heavy Game"),
        "com.proximabeta.nikke" to GameMeta("GODDESS OF VICTORY: NIKKE", 2, "Heavy Game"),
        "com.kurogame.wutheringwaves.global" to GameMeta("Wuthering Waves", 2, "Heavy Game"),
        "com.supercell.clashofclans" to GameMeta("Clash of Clans", 1, "Strategy"),
        "com.supercell.brawlstars" to GameMeta("Brawl Stars", 3, "MOBA"),
        "com.roblox.client" to GameMeta("Roblox", 1, "Game"),
        "com.mojang.minecraftpe" to GameMeta("Minecraft", 1, "Game"),
        "com.gameloft.android.ANMP.GloftA9HM" to GameMeta("Asphalt 9", 5, "Racing"),
        "com.carxtech.sr" to GameMeta("CarX Street", 5, "Racing")
    )

    data class ForegroundState(
        val focusedPackage: String?,
        val resumedGame: ActiveGame?,
    )

    suspend fun detectState(): ForegroundState {
        // Run fixed read-only diagnostics separately and filter in Kotlin. Never compose shell
        // pipelines (`grep`, `head`, `;`) because the Game Nuke core intentionally has no generic shell.
        val window = ShellBridge.result(adbManager, "dumpsys window", timeoutMs = 1800L)
        val activities = ShellBridge.result(adbManager, "dumpsys activity activities", timeoutMs = 1800L)
        val combined = buildString {
            if (window.isSuccess) appendLine(window.output)
            if (activities.isSuccess) appendLine(activities.output)
        }
        if (combined.isBlank()) throw IllegalStateException("Foreground activity query unavailable")
        val focusedLines = combined.lineSequence().filter { line ->
            line.contains("mCurrentFocus") ||
                line.contains("mFocusedApp") ||
                line.contains("topResumedActivity") ||
                line.contains("mTopActivity") ||
                line.contains("mResumedActivity") ||
                line.contains("ResumedActivity")
        }.joinToString("\n")
        val packages = extractPackages(focusedLines.ifBlank { combined })
        val game = packages.asSequence().mapNotNull { classifyGame(it) }.firstOrNull()
        return ForegroundState(packages.firstOrNull(), game)
    }

    suspend fun detectForegroundPackage(): String? = detectState().focusedPackage

    suspend fun detect(): ActiveGame? = detectState().resumedGame

    private fun classifyGame(pkg: String): ActiveGame? {
        if (isIgnoredPackage(pkg)) return null

        knownGames[pkg]?.let { return ActiveGame(pkg, it.label, it.genreCode, it.genreLabel, true) }

        val pmMeta = fromPackageManager(pkg)
        if (pmMeta != null) return pmMeta

        val lower = pkg.lowercase()
        val looksGame = listOf(
            "game", "games", "pubg", "freefire", "cod", "mihoyo", "hoyoverse", "riot", "moonton",
            "supercell", "minecraft", "roblox", "asphalt", "carx", "ea.gp", "epicgames", "tencent", "garena"
        ).any { lower.contains(it) }
        if (!looksGame) return null
        return ActiveGame(pkg, prettyName(pkg), 1, "Game", false)
    }

    private fun fromPackageManager(pkg: String): ActiveGame? {
        return try {
            val pm = context.packageManager
            val info = if (android.os.Build.VERSION.SDK_INT >= 33) {
                pm.getApplicationInfo(pkg, PackageManager.ApplicationInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION") pm.getApplicationInfo(pkg, 0)
            }
            val label = pm.getApplicationLabel(info).toString().ifBlank { prettyName(pkg) }
            val lower = (pkg + " " + label).lowercase()
            val categoryGame = android.os.Build.VERSION.SDK_INT >= 26 && info.category == ApplicationInfo.CATEGORY_GAME
            val labelLooksGame = listOf("game", "mlbb", "pubg", "free fire", "codm", "genshin", "honkai", "roblox", "minecraft", "asphalt").any { lower.contains(it) }
            if (!categoryGame && !labelLooksGame) return null
            val genre = inferGenre(lower)
            ActiveGame(pkg, label, genre.first, genre.second, false)
        } catch (_: Exception) {
            null
        }
    }

    private fun inferGenre(text: String): Pair<Int, String> {
        return when {
            listOf("pubg", "free fire", "battle", "royale").any { text.contains(it) } -> 7 to "Battle Royale"
            listOf("cod", "shooter", "fps").any { text.contains(it) } -> 4 to "FPS"
            listOf("mobile legends", "wild rift", "moba").any { text.contains(it) } -> 3 to "MOBA"
            listOf("genshin", "honkai", "wuthering", "impact").any { text.contains(it) } -> 2 to "Heavy Game"
            listOf("asphalt", "racing", "carx").any { text.contains(it) } -> 5 to "Racing"
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
        return pkg == context.packageName ||
            pkg == "android" ||
            pkg.startsWith("com.android.") ||
            pkg.startsWith("com.google.android.") ||
            pkg.startsWith("com.sec.android.app.launcher") ||
            pkg.startsWith("com.miui.home") ||
            pkg.startsWith("com.oppo.launcher") ||
            pkg.startsWith("com.coloros.") ||
            pkg.startsWith("com.transsion.") ||
            pkg.startsWith("com.realme.") ||
            pkg.startsWith("com.vivo.")
    }

    private fun prettyName(pkg: String): String {
        return pkg.substringAfterLast('.')
            .replace('_', ' ')
            .replace('-', ' ')
            .replaceFirstChar { it.uppercase() }
    }
}
