package com.neon.gametweak

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.os.Build

/**
 * Package-visibility-safe game catalog for Android 11+.
 *
 * Automatic detection uses only launchable apps exposed by the manifest's launcher intent query,
 * then scores CATEGORY_GAME/FLAG_IS_GAME plus conservative package/label heuristics. Users can
 * still launch any visible launcher app from the ALL APPS view and that package is remembered as
 * a game for future scans. No QUERY_ALL_PACKAGES permission is required.
 */
object GameCatalogRepository {
    data class Entry(
        val label: String,
        val packageName: String,
        val detectedAsGame: Boolean,
    )

    data class ScanResult(
        val games: List<Entry>,
        val allLaunchable: List<Entry>,
    )

    private const val PREFS = "NukePrefs"
    private const val KEY_MANUAL_GAMES = "manual_game_packages"
    private const val CACHE_TTL_MS = 20_000L
    @Volatile private var cachedAtMs: Long = 0L
    @Volatile private var cachedResult: ScanResult? = null

    private val strongTokens = setOf(
        "game", "games", "gaming", "pubg", "freefire", "free fire", "codm", "call of duty",
        "mobile legends", "mlbb", "moonton", "hoyoverse", "mihoyo", "genshin", "honkai",
        "wuthering", "kurogame", "riotgames", "wildrift", "supercell", "roblox", "minecraft",
        "mojang", "asphalt", "carx", "netease", "netmarble", "nexon", "konami", "garena",
        "tencent", "level infinite", "epicgames", "electronic arts", "ea.gp", "playrix",
        "steam", "arena breakout", "delta force", "valorant", "pokemon", "netflix games",
        "square enix", "bandai", "namco", "sega", "ubisoft", "gravity", "com2us", "lilith",
        "azur", "yostar", "perfect world", "krafton", "zenless", "tower of fantasy", "racing"
    )

    private val ignoredPackages = setOf(
        "com.android.settings",
        "com.android.systemui",
        "com.google.android.googlequicksearchbox",
        "com.google.android.apps.nexuslauncher"
    )

    fun scan(context: Context, force: Boolean = false): ScanResult {
        val now = android.os.SystemClock.elapsedRealtime()
        val cached = cachedResult
        if (!force && cached != null && now - cachedAtMs in 0..CACHE_TTL_MS) return cached
        val pm = context.packageManager
        val manual = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getStringSet(KEY_MANUAL_GAMES, emptySet())
            ?.toSet().orEmpty()

        val launchers = LinkedHashMap<String, ResolveInfo>()
        queryLauncher(pm, Intent.CATEGORY_LAUNCHER).forEach { info ->
            val pkg = info.activityInfo?.packageName ?: return@forEach
            launchers.putIfAbsent(pkg, info)
        }
        // Android TV/desktop-style launchers occasionally expose games only through LEANBACK.
        queryLauncher(pm, Intent.CATEGORY_LEANBACK_LAUNCHER).forEach { info ->
            val pkg = info.activityInfo?.packageName ?: return@forEach
            launchers.putIfAbsent(pkg, info)
        }

        val entries = launchers.values.mapNotNull { resolve ->
            val activityInfo = resolve.activityInfo ?: return@mapNotNull null
            val appInfo = activityInfo.applicationInfo ?: return@mapNotNull null
            val pkg = appInfo.packageName ?: return@mapNotNull null
            if (pkg == context.packageName || shouldIgnore(pkg)) return@mapNotNull null

            val label = runCatching { resolve.loadLabel(pm)?.toString() }
                .getOrNull().orEmpty().ifBlank {
                    runCatching { pm.getApplicationLabel(appInfo).toString() }.getOrDefault(pkg.substringAfterLast('.'))
                }
            val detected = manual.contains(pkg) || isGame(appInfo, pkg, label)
            Entry(label = label, packageName = pkg, detectedAsGame = detected)
        }.distinctBy { it.packageName }
            .sortedWith(compareBy<Entry>({ !it.detectedAsGame }, { it.label.lowercase() }))

        return ScanResult(
            games = entries.filter { it.detectedAsGame },
            allLaunchable = entries,
        ).also {
            cachedResult = it
            cachedAtMs = android.os.SystemClock.elapsedRealtime()
        }
    }

    fun rememberAsGame(context: Context, packageName: String) {
        if (!isValidPackage(packageName)) return
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val current = prefs.getStringSet(KEY_MANUAL_GAMES, emptySet())?.toMutableSet() ?: mutableSetOf()
        if (current.add(packageName)) {
            prefs.edit().putStringSet(KEY_MANUAL_GAMES, current).apply()
            cachedResult = null
            cachedAtMs = 0L
        }
    }

    fun resolveLaunchIntent(context: Context, packageName: String): Intent? {
        if (!isValidPackage(packageName)) return null
        val pm = context.packageManager
        runCatching { pm.getLaunchIntentForPackage(packageName) }.getOrNull()?.let { return it }

        val base = Intent(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_LAUNCHER)
            setPackage(packageName)
        }
        val resolved = queryIntentActivities(pm, base).firstOrNull() ?: run {
            val leanback = Intent(Intent.ACTION_MAIN).apply {
                addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER)
                setPackage(packageName)
            }
            queryIntentActivities(pm, leanback).firstOrNull()
        } ?: return null
        val activity = resolved.activityInfo ?: return null
        return Intent(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_LAUNCHER)
            component = ComponentName(activity.packageName, activity.name)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        }
    }

    private fun queryLauncher(pm: PackageManager, category: String): List<ResolveInfo> {
        val intent = Intent(Intent.ACTION_MAIN).apply { addCategory(category) }
        return queryIntentActivities(pm, intent)
    }

    private fun queryIntentActivities(pm: PackageManager, intent: Intent): List<ResolveInfo> {
        return runCatching {
            if (Build.VERSION.SDK_INT >= 33) {
                pm.queryIntentActivities(intent, PackageManager.ResolveInfoFlags.of(PackageManager.MATCH_ALL.toLong()))
            } else {
                @Suppress("DEPRECATION")
                pm.queryIntentActivities(intent, PackageManager.MATCH_ALL)
            }
        }.getOrDefault(emptyList())
    }

    @Suppress("DEPRECATION")
    private fun isGame(info: ApplicationInfo, pkg: String, label: String): Boolean {
        if (Build.VERSION.SDK_INT >= 26 && info.category == ApplicationInfo.CATEGORY_GAME) return true
        if ((info.flags and ApplicationInfo.FLAG_IS_GAME) != 0) return true
        val haystack = "$pkg $label".lowercase()
        return strongTokens.any(haystack::contains)
    }

    private fun shouldIgnore(pkg: String): Boolean {
        if (pkg in ignoredPackages) return true
        return pkg.startsWith("com.android.launcher") ||
            pkg.startsWith("com.sec.android.app.launcher") ||
            pkg.startsWith("com.miui.home") ||
            pkg.startsWith("com.oppo.launcher") ||
            pkg.startsWith("com.realme.launcher") ||
            pkg.startsWith("com.vivo.launcher") ||
            pkg.startsWith("com.transsion.launcher")
    }

    private fun isValidPackage(value: String): Boolean =
        value.matches(Regex("^[A-Za-z0-9_]+(\\.[A-Za-z0-9_]+)+$"))
}
