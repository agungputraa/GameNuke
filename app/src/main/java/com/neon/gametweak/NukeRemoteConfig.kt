package com.neon.gametweak

import android.content.Context
import android.os.SystemClock
import android.util.Base64
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.Locale

/**
 * Immutable, capability-filtered slice of the remote product definition used by the overlay.
 * Remote JSON may select registered UI/operation IDs, but it can never provide executable code.
 */
internal data class NukeRemotePanel(
    val id: String,
    val order: Int,
    val title: String,
    val subtitle: String,
    val icon: String,
    val requiresAll: Set<String>,
    val requiresAny: Set<String>,
)

internal data class NukeRemoteQuickControl(
    val id: String,
    val order: Int,
    val title: String,
    val icon: String,
    val operationId: String,
    val requiresAll: Set<String>,
    val requiresAny: Set<String>,
)

internal data class NukeRemoteTheme(
    val background: String = "#F20A0F0D",
    val surface: String = "#F21A211E",
    val surfaceRaised: String = "#F225302B",
    val primary: String = "#A8FF00",
    val secondary: String = "#00E5A8",
    val accent: String = "#8A5CFF",
    val danger: String = "#FF4D6D",
    val warning: String = "#FFC857",
    val textPrimary: String = "#F4FFF8",
    val textSecondary: String = "#B8C8BE",
    val outline: String = "#4D6F6058",
)

/**
 * Authenticated pointer to the optional ModuleShop catalog.
 *
 * The catalog is not trusted merely because it is hosted on GitHub. Its SHA-256 is carried by the
 * already Ed25519-signed RemoteNuke definition, and every module archive is then pinned by the
 * catalog. Updating modules therefore remains possible without an APK release while retaining an
 * explicit signature -> catalog digest -> archive digest trust chain.
 */
internal data class NukeRemoteModuleShop(
    val enabled: Boolean = true,
    val catalogUrl: String = "https://raw.githubusercontent.com/AgungDevlop/ModuleShop/main/plugins.json",
    val catalogSha256: String = "e41469d89889c7bff7640135d117310a9b7678247342f64cf2a39ae47600b8a5",
    val repositoryOwner: String = "AgungDevlop",
    val repositoryName: String = "ModuleShop",
    val branch: String = "main",
    val maxCatalogBytes: Int = 524_288,
    val maxArchiveBytes: Int = 262_144,
    val maxEntries: Int = 256,
)

internal data class NukeRemoteHudDefinition(
    val revision: Int = 0,
    val trust: String = "BUNDLED",
    val theme: NukeRemoteTheme = NukeRemoteTheme(),
    val panels: List<NukeRemotePanel> = bundledPanels(),
    val quickControls: List<NukeRemoteQuickControl> = bundledQuickControls(),
    val moduleShop: NukeRemoteModuleShop = NukeRemoteModuleShop(),
    val compactMaxWidthDp: Int = 479,
    val mediumMaxWidthDp: Int = 719,
    val shortHeightDp: Int = 359,
    val moduleColumnsCompact: Int = 2,
    val moduleColumnsMedium: Int = 3,
    val moduleColumnsExpanded: Int = 4,
    val panelWidthFraction: Float = .94f,
    val panelMaxWidthDp: Int = 980,
    val panelMinWidthDp: Int = 296,
    val panelMaxHeightFraction: Float = .88f,
    val edgeSizeDp: Int = 42,
    val landscapeLayoutId: String = "split_quantum_wings",
    val portraitLayoutId: String = "stacked_quantum_trapezoids",
    val showEndSession: Boolean = true,
    val panelOpenMs: Int = 240,
    val panelCloseMs: Int = 170,
    val moduleStaggerMs: Int = 22,
    val attachDelayAfterForegroundMs: Long = 120L,
    val waitForGameForegroundMs: Long = 3_500L,
    val heavyOperationsDelayMs: Long = 5_000L,
    val clickDebounceMs: Long = 550L,
    val settingsDebounceMs: Long = 3_000L,
) {
    companion object {
        val Bundled = NukeRemoteHudDefinition()
    }
}

internal data class NukeRemoteConfigState(
    val hud: NukeRemoteHudDefinition = NukeRemoteHudDefinition.Bundled,
    val status: String = "BUNDLED",
    val lastError: String? = null,
)

/**
 * Two-phase HTTPS loader with ETag, atomic cache, LKG rollback and revision quarantine.
 *
 * Revision 2 is an intentionally pinned bootstrap. Any later content MUST carry an Ed25519
 * detached signature at `<config-url>.sig`; unsigned changed content is rejected before parsing.
 */
internal object NukeRemoteConfigRepository {
    private const val TAG = "NukeRemoteConfig"
    const val CONFIG_URL = "https://raw.githubusercontent.com/agungputraa/RemoteNuke/refs/heads/main/gamenuke-remote-config.v1.json"
    const val SCHEMA_URL = "https://raw.githubusercontent.com/agungputraa/RemoteNuke/refs/heads/main/gamenuke-remote-config.schema.v1.json"
    private const val EXPECTED_HOST = "raw.githubusercontent.com"
    private const val BOOTSTRAP_SHA256 = "b842efa3f186ac2bac930887f5b72032d886a5894e1a642472d4018cc71b3d4c"
    internal const val SIGNING_KEY_ID = "remote-nuke-2026-01"
    private const val ED25519_PUBLIC_KEY_BASE64 = "5MMhWEJE9vmigzLt/tsdZQoGavWgCqYxk7ypf2X9jc4="
    private const val MAX_BYTES = 524_288
    private const val MIN_FETCH_INTERVAL_MS = 15 * 60 * 1000L
    private const val QUARANTINE_MS = 72 * 60 * 60 * 1000L
    private const val PREFS = "NukeRemoteConfig"
    private const val K_LAST_FETCH = "last_fetch_elapsed"
    private const val K_ETAG = "etag"
    private const val K_LAST_MODIFIED = "last_modified"
    private const val K_REVISION_FLOOR = "revision_floor"
    private const val K_QUARANTINE_REVISION = "quarantine_revision"
    private const val K_QUARANTINE_UNTIL = "quarantine_until"
    private const val K_FAILURE_REVISION = "failure_revision"
    private const val K_FAILURE_COUNT = "failure_count"
    private const val K_FAILURE_WINDOW_START = "failure_window_start"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val fetchMutex = Mutex()
    private val _state = MutableStateFlow(NukeRemoteConfigState())
    val state: StateFlow<NukeRemoteConfigState> = _state.asStateFlow()
    @Volatile private var appContext: Context? = null

    fun initialize(context: Context) {
        if (appContext != null) return
        synchronized(this) {
            if (appContext != null) return
            appContext = context.applicationContext
            _state.value = loadBestLocal(context.applicationContext)
        }
        refreshInBackground("app-start")
    }

    fun currentHud(): NukeRemoteHudDefinition = _state.value.hud

    fun refreshInBackground(reason: String, force: Boolean = false) {
        val context = appContext ?: return
        scope.launch { refresh(context, reason, force) }
    }

    fun reportOverlayFailure(revision: Int) {
        if (revision <= 0) return
        val context = appContext ?: return
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        val sameWindow = p.getInt(K_FAILURE_REVISION, -1) == revision &&
            now - p.getLong(K_FAILURE_WINDOW_START, 0L) in 0L..45_000L
        val count = if (sameWindow) p.getInt(K_FAILURE_COUNT, 0) + 1 else 1
        p.edit().putInt(K_FAILURE_REVISION, revision).putInt(K_FAILURE_COUNT, count)
            .putLong(K_FAILURE_WINDOW_START, if (sameWindow) p.getLong(K_FAILURE_WINDOW_START, now) else now).apply()
        if (count >= 2) {
            p.edit().putInt(K_QUARANTINE_REVISION, revision).putLong(K_QUARANTINE_UNTIL, now + QUARANTINE_MS).apply()
            _state.value = loadLastKnownGood(context, "QUARANTINED")
        }
    }

    fun markOverlayStable(revision: Int) {
        val context = appContext ?: return
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (p.getInt(K_FAILURE_REVISION, -1) == revision) {
            p.edit().remove(K_FAILURE_REVISION).remove(K_FAILURE_COUNT).remove(K_FAILURE_WINDOW_START).apply()
        }
    }

    private suspend fun refresh(context: Context, reason: String, force: Boolean) = fetchMutex.withLock {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val elapsed = SystemClock.elapsedRealtime()
        if (!force && elapsed - prefs.getLong(K_LAST_FETCH, 0L) in 0 until MIN_FETCH_INTERVAL_MS) return@withLock
        prefs.edit().putLong(K_LAST_FETCH, elapsed).apply()
        runCatching {
            val result = fetchBytes(CONFIG_URL, prefs.getString(K_ETAG, null), prefs.getString(K_LAST_MODIFIED, null))
            if (result.notModified) return@runCatching
            val bytes = requireNotNull(result.bytes)
            val digest = sha256(bytes)
            val trust = if (digest == BOOTSTRAP_SHA256) {
                "BOOTSTRAP_PINNED"
            } else {
                val signature = fetchBytes("$CONFIG_URL.sig", null, null).bytes
                    ?: error("Detached signature is missing")
                require(verifySignature(bytes, signature)) { "Detached Ed25519 signature is invalid" }
                "SIGNED:$SIGNING_KEY_ID"
            }
            val parsed = parseAndValidate(bytes.toString(Charsets.UTF_8), BuildConfig.VERSION_CODE, trust)
            val quarantined = prefs.getInt(K_QUARANTINE_REVISION, -1) == parsed.revision &&
                System.currentTimeMillis() < prefs.getLong(K_QUARANTINE_UNTIL, 0L)
            require(!quarantined) { "Revision ${parsed.revision} is quarantined after overlay failures" }
            val floor = prefs.getInt(K_REVISION_FLOOR, 0)
            require(parsed.revision >= floor) { "Revision rollback rejected (${parsed.revision} < $floor)" }
            activateAtomically(context, bytes, parsed)
            prefs.edit().putInt(K_REVISION_FLOOR, parsed.revision)
                .putString(K_ETAG, result.etag).putString(K_LAST_MODIFIED, result.lastModified).apply()
            _state.value = NukeRemoteConfigState(parsed, trust)
            Log.i(TAG, "Activated revision ${parsed.revision} from $reason ($trust)")
        }.onFailure { error ->
            Log.w(TAG, "Refresh from $reason kept current/LKG config: ${error.message}")
            _state.value = _state.value.copy(lastError = error.message?.take(180))
        }
    }

    private data class FetchResult(
        val bytes: ByteArray? = null,
        val notModified: Boolean = false,
        val etag: String? = null,
        val lastModified: String? = null,
    )

    private fun fetchBytes(urlString: String, etag: String?, lastModified: String?): FetchResult {
        val url = URL(urlString)
        require(url.protocol == "https" && url.host == EXPECTED_HOST) { "Remote host is not allowed" }
        val connection = (url.openConnection() as HttpURLConnection).apply {
            instanceFollowRedirects = false
            connectTimeout = 3_500
            readTimeout = 5_500
            requestMethod = "GET"
            setRequestProperty("Accept", "application/json,text/plain")
            if (!etag.isNullOrBlank()) setRequestProperty("If-None-Match", etag)
            if (!lastModified.isNullOrBlank()) setRequestProperty("If-Modified-Since", lastModified)
        }
        return try {
            when (val code = connection.responseCode) {
                HttpURLConnection.HTTP_NOT_MODIFIED -> FetchResult(notModified = true)
                HttpURLConnection.HTTP_OK -> {
                    val declared = connection.contentLengthLong
                    require(declared <= MAX_BYTES || declared < 0) { "Remote config exceeds size limit" }
                    val output = ByteArrayOutputStream()
                    connection.inputStream.use { input ->
                        val buffer = ByteArray(8_192)
                        var total = 0
                        while (true) {
                            val read = input.read(buffer)
                            if (read < 0) break
                            total += read
                            require(total <= MAX_BYTES) { "Remote response exceeds size limit" }
                            output.write(buffer, 0, read)
                        }
                    }
                    FetchResult(output.toByteArray(), etag = connection.getHeaderField("ETag"), lastModified = connection.getHeaderField("Last-Modified"))
                }
                else -> error("Remote HTTP $code")
            }
        } finally {
            connection.disconnect()
        }
    }

    private fun activateAtomically(context: Context, bytes: ByteArray, parsed: NukeRemoteHudDefinition) {
        val dir = File(context.filesDir, "remote_config").apply { mkdirs() }
        val active = File(dir, "active.json")
        val lkg = File(dir, "last_known_good.json")
        if (active.isFile) runCatching { active.copyTo(lkg, overwrite = true) }
        val candidate = File(dir, "candidate-${parsed.revision}.tmp")
        candidate.outputStream().use { stream -> stream.write(bytes); stream.fd.sync() }
        require(candidate.renameTo(active) || runCatching { candidate.copyTo(active, overwrite = true); candidate.delete(); true }.getOrDefault(false)) {
            "Atomic config activation failed"
        }
    }

    private fun loadBestLocal(context: Context): NukeRemoteConfigState {
        val active = File(context.filesDir, "remote_config/active.json")
        if (active.isFile) runCatching {
            val parsed = parseAndValidate(active.readText(), BuildConfig.VERSION_CODE, "CACHE")
            val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val quarantined = p.getInt(K_QUARANTINE_REVISION, -1) == parsed.revision &&
                System.currentTimeMillis() < p.getLong(K_QUARANTINE_UNTIL, 0L)
            if (!quarantined) return NukeRemoteConfigState(parsed, "CACHE")
        }
        return loadLastKnownGood(context, "BUNDLED")
    }

    private fun loadLastKnownGood(context: Context, status: String): NukeRemoteConfigState {
        val lkg = File(context.filesDir, "remote_config/last_known_good.json")
        if (lkg.isFile) runCatching {
            val parsed = parseAndValidate(lkg.readText(), BuildConfig.VERSION_CODE, "LKG")
            return NukeRemoteConfigState(parsed, "LKG")
        }
        return NukeRemoteConfigState(NukeRemoteHudDefinition.Bundled, status)
    }

    private fun verifySignature(content: ByteArray, signatureBytes: ByteArray): Boolean = runCatching {
        val signature = if (signatureBytes.size == 64) signatureBytes else Base64.decode(signatureBytes.toString(Charsets.UTF_8).trim(), Base64.DEFAULT)
        require(signature.size == 64)
        val publicKey = Base64.decode(ED25519_PUBLIC_KEY_BASE64, Base64.DEFAULT)
        val verifier = Ed25519Signer().apply {
            init(false, Ed25519PublicKeyParameters(publicKey, 0))
            update(content, 0, content.size)
        }
        verifier.verifySignature(signature)
    }.getOrDefault(false)

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes).joinToString("") { "%02x".format(Locale.US, it) }
}

private val knownPanelIds = setOf(
    "frame_control", "deep_clean", "crosshair", "live_monitor", "network",
    "pressure_radar", "screen_hud", "cpu_monitor",
)
private val knownQuickIds = setOf("adaptive_mode", "focus", "network_boost", "crosshair_toggle", "keep_awake")

private fun parseAndValidate(json: String, appVersionCode: Int, trust: String): NukeRemoteHudDefinition {
    val root = JSONObject(json)
    require(root.optInt("schemaVersion", -1) == 1) { "Unsupported schema version" }
    require(root.optBoolean("enabled", false)) { "Remote config is disabled" }
    require(root.optInt("minAppVersionCode", Int.MAX_VALUE) <= appVersionCode) { "App update is required by remote config" }
    val revision = root.optInt("revision", -1)
    require(revision > 0) { "Invalid revision" }

    val security = root.requireObject("security")
    require(security.optBoolean("allowRemoteOperationSelection", false))
    require(!security.optBoolean("allowRemoteShellTemplates", true)) { "Remote shell templates are forbidden" }
    require(!security.optBoolean("allowArbitraryShell", true)) { "Arbitrary remote shell is forbidden" }
    require(security.optBoolean("rejectShellComposition", false)) { "Shell composition guard is required" }
    val integrity = security.requireObject("integrity")
    if (trust.startsWith("SIGNED")) require(integrity.optString("keyId") == NukeRemoteConfigRepository.SIGNING_KEY_ID) { "Signing keyId mismatch" }

    val overlay = root.requireObject("overlay")
    val boost = overlay.requireObject("boostLaunchPolicy")
    listOf("neverMutateResolution", "neverMutateDensity", "neverRunWmSize", "neverRunWmDensity").forEach {
        require(boost.optBoolean(it, false)) { "Mandatory display safety guard $it is disabled" }
    }
    val forbidden = boost.optJSONArray("forbiddenLocalHandlers").stringSet()
    require(forbidden.contains("builtin.shell.wm_size") && forbidden.contains("builtin.shell.wm_density")) { "Display mutation handlers are not blocked" }

    val operationIds = (
        root.optJSONArray("operationCatalog").objectSequence() +
            root.optJSONArray("shellCatalog").objectSequence() +
            root.optJSONArray("pipelines").objectSequence()
        ).map { it.optString("id") }.filter { it.isNotBlank() }.toSet()
    require(operationIds.isNotEmpty()) { "Operation registry is empty" }
    root.optJSONArray("shellCatalog").objectSequence().forEach { shell ->
        val text = shell.toString().lowercase(Locale.US)
        require(!text.contains("\"command\"") && !text.contains("\"script\"") && !text.contains("wm size") && !text.contains("wm density")) {
            "Raw or display-mutating shell content rejected"
        }
        require(shell.optString("handlerId").startsWith("builtin.shell.")) { "Unregistered shell handler rejected" }
    }

    val flags = root.optJSONArray("featureFlags").objectSequence().associate { flag ->
        flag.optString("id") to (flag.optBoolean("enabled", false) && !flag.optBoolean("killSwitch", false) && flag.optInt("rolloutPercent", 0) > 0)
    }
    val panels = root.optJSONArray("panels").objectSequence().mapNotNull { panel ->
        val id = panel.optString("id")
        if (id !in knownPanelIds || !panel.optBoolean("visible", false)) return@mapNotNull null
        val feature = panel.optString("featureFlag")
        if (feature.isNotBlank() && flags[feature] != true) return@mapNotNull null
        panel.optJSONArray("components").objectSequence().forEach { component ->
            val operation = component.optString("operationId")
            require(operation.isBlank() || operation in operationIds) { "Unknown operation $operation" }
        }
        NukeRemotePanel(
            id = id,
            order = panel.optInt("order", 999),
            title = panel.optString("title").take(48),
            subtitle = panel.optString("subtitle").take(96),
            icon = panel.optString("icon").take(24),
            requiresAll = panel.optJSONArray("requiresAll").stringSet(),
            requiresAny = panel.optJSONArray("requiresAny").stringSet(),
        )
    }.sortedBy { it.order }.toList()
    require(panels.isNotEmpty()) { "No locally supported panel remains" }

    val quick = root.optJSONArray("quickControls").objectSequence().mapNotNull { item ->
        val id = item.optString("id")
        if (id !in knownQuickIds) return@mapNotNull null
        val operation = item.optString("operationId")
        require(operation in operationIds) { "Unknown quick-control operation $operation" }
        NukeRemoteQuickControl(
            id = id,
            order = item.optInt("order", 999),
            title = item.optString("title").take(32),
            icon = item.optString("icon").take(24),
            operationId = operation,
            requiresAll = item.optJSONArray("requiresAll").stringSet(),
            requiresAny = item.optJSONArray("requiresAny").stringSet(),
        )
    }.sortedBy { it.order }.toList()

    val responsive = root.requireObject("responsive")
    val breakpoints = responsive.optJSONArray("breakpoints").objectSequence().associateBy { it.optString("id") }
    val compact = breakpoints["compact"] ?: JSONObject()
    val medium = breakpoints["medium"] ?: JSONObject()
    val expanded = breakpoints["expanded"] ?: JSONObject()
    val short = responsive.optJSONArray("heightOverrides").objectSequence().firstOrNull { it.optString("id") == "short_landscape" }
    val themeColors = root.requireObject("theme").requireObject("colors")
    val window = overlay.requireObject("window")
    val edge = overlay.requireObject("edgeHandle")
    val mainPanel = overlay.requireObject("mainPanel")
    val moduleShopJson = mainPanel.optJSONObject("moduleShop") ?: JSONObject()
    val catalogUrl = moduleShopJson.optString(
        "catalogUrl",
        NukeRemoteModuleShop().catalogUrl,
    )
    val catalogSha256 = moduleShopJson.optString(
        "catalogSha256",
        NukeRemoteModuleShop().catalogSha256,
    ).lowercase(Locale.US)
    // Accept any HTTPS raw.githubusercontent.com catalog URL
    require(catalogUrl.startsWith("https://raw.githubusercontent.com/")) {
        "ModuleShop catalog URL must be on raw.githubusercontent.com"
    }
    require(catalogSha256.matches(Regex("^[0-9a-f]{64}$"))) { "Invalid ModuleShop catalog digest" }
    val motion = root.requireObject("theme").requireObject("motion")
    val runtime = root.requireObject("runtimePolicy")
    return NukeRemoteHudDefinition(
        revision = revision,
        trust = trust,
        theme = NukeRemoteTheme(
            background = themeColors.safeColor("background", "#F20A0F0D"),
            surface = themeColors.safeColor("surface", "#F21A211E"),
            surfaceRaised = themeColors.safeColor("surfaceRaised", "#F225302B"),
            primary = themeColors.safeColor("primary", "#A8FF00"),
            secondary = themeColors.safeColor("secondary", "#00E5A8"),
            accent = themeColors.safeColor("accent", "#8A5CFF"),
            danger = themeColors.safeColor("danger", "#FF4D6D"),
            warning = themeColors.safeColor("warning", "#FFC857"),
            textPrimary = themeColors.safeColor("textPrimary", "#F4FFF8"),
            textSecondary = themeColors.safeColor("textSecondary", "#B8C8BE"),
            outline = themeColors.safeColor("outline", "#4D6F6058"),
        ),
        panels = panels,
        quickControls = quick,
        moduleShop = NukeRemoteModuleShop(
            enabled = moduleShopJson.optBoolean("enabled", true),
            catalogUrl = catalogUrl,
            catalogSha256 = catalogSha256,
            repositoryOwner = moduleShopJson.optString("repositoryOwner", "AgungDevlop"),
            repositoryName = moduleShopJson.optString("repositoryName", "ModuleShop"),
            branch = moduleShopJson.optString("branch", "main"),
            maxCatalogBytes = moduleShopJson.optInt("maxCatalogBytes", 524_288).coerceIn(65_536, 1_048_576),
            maxArchiveBytes = moduleShopJson.optInt("maxArchiveBytes", 262_144).coerceIn(16_384, 1_048_576),
            maxEntries = moduleShopJson.optInt("maxEntries", 256).coerceIn(1, 512),
        ),
        compactMaxWidthDp = compact.optInt("maxWidthDp", 479).coerceIn(320, 599),
        mediumMaxWidthDp = medium.optInt("maxWidthDp", 719).coerceIn(600, 899),
        shortHeightDp = short?.optInt("maxHeightDp", 359)?.coerceIn(240, 480) ?: 359,
        moduleColumnsCompact = compact.optInt("moduleColumns", 2).coerceIn(1, 3),
        moduleColumnsMedium = medium.optInt("moduleColumns", 3).coerceIn(2, 4),
        moduleColumnsExpanded = expanded.optInt("moduleColumns", 4).coerceIn(3, 5),
        panelWidthFraction = window.optDouble("widthFraction", .94).toFloat().coerceIn(.75f, .98f),
        panelMaxWidthDp = window.optInt("maxWidthDp", 980).coerceIn(480, 1_200),
        panelMinWidthDp = window.optInt("minWidthDp", 296).coerceIn(280, 480),
        panelMaxHeightFraction = window.optDouble("maxHeightFraction", .88).toFloat().coerceIn(.65f, .94f),
        edgeSizeDp = edge.optInt("sizeDp", 42).coerceIn(36, 56),
        landscapeLayoutId = mainPanel.optString("landscapeLayoutId", "split_quantum_wings").take(48),
        portraitLayoutId = mainPanel.optString("portraitLayoutId", "stacked_quantum_trapezoids").take(48),
        showEndSession = mainPanel.optBoolean("showEndSession", true),
        panelOpenMs = motion.optInt("panelOpenMs", 240).coerceIn(0, 700),
        panelCloseMs = motion.optInt("panelCloseMs", 170).coerceIn(0, 500),
        moduleStaggerMs = motion.optInt("moduleStaggerMs", 22).coerceIn(0, 80),
        attachDelayAfterForegroundMs = boost.optLong("overlayAttachDelayAfterForegroundMs", 120L).coerceIn(0L, 1_500L),
        waitForGameForegroundMs = boost.optLong("waitForGameForegroundMs", 3_500L).coerceIn(500L, 8_000L),
        heavyOperationsDelayMs = runtime.requireObject("safetyGuards").optLong("blockHeavyOperationsDuringFirstSessionMs", 5_000L).coerceIn(0L, 15_000L),
        clickDebounceMs = runtime.optLong("clickDebounceMs", 550L).coerceIn(250L, 2_000L),
        settingsDebounceMs = runtime.optLong("settingsLaunchDebounceMs", 3_000L).coerceIn(1_000L, 10_000L),
    )
}

private fun JSONObject.requireObject(key: String): JSONObject = optJSONObject(key) ?: error("Missing object $key")
private fun JSONObject.safeColor(key: String, fallback: String): String = optString(key, fallback).takeIf { it.matches(Regex("^#[0-9A-Fa-f]{8}$|^#[0-9A-Fa-f]{6}$")) } ?: fallback
private fun JSONArray?.objectSequence(): Sequence<JSONObject> = sequence {
    val source = this@objectSequence ?: return@sequence
    for (index in 0 until source.length()) source.optJSONObject(index)?.let { yield(it) }
}
private fun JSONArray?.stringSet(): Set<String> {
    val source = this ?: return emptySet()
    return buildSet { for (index in 0 until source.length()) source.optString(index).takeIf { it.isNotBlank() }?.let(::add) }
}

private fun bundledPanels(): List<NukeRemotePanel> = listOf(
    NukeRemotePanel("frame_control", 10, "Frame Control", "Native frame path and measured FPS", "speed", emptySet(), setOf("local.display", "shell.frame_scan")),
    NukeRemotePanel("deep_clean", 20, "Deep Clean", "Measured cache and memory reclaim", "clean", setOf("local.telemetry"), emptySet()),
    NukeRemotePanel("crosshair", 30, "Crosshair Studio", "Canvas aim overlay", "crosshair", setOf("overlay"), emptySet()),
    NukeRemotePanel("live_monitor", 40, "Live Monitor", "CPU and RAM telemetry", "monitor", setOf("local.telemetry"), emptySet()),
    NukeRemotePanel("network", 50, "Network Core", "Link quality and Wi-Fi session lock", "network", emptySet(), setOf("local.wifi", "local.telemetry")),
    NukeRemotePanel("pressure_radar", 60, "Pressure Radar", "Protected background process release", "radar", setOf("shell.background_release"), emptySet()),
    NukeRemotePanel("screen_hud", 70, "Screen and HUD", "Orientation and overlay comfort", "screen", setOf("overlay"), emptySet()),
    NukeRemotePanel("cpu_monitor", 100, "CPU Monitor", "Read-only per-core clocks", "cpu", setOf("shell.cpu_clocks"), emptySet()),
)

private fun bundledQuickControls(): List<NukeRemoteQuickControl> = listOf(
    NukeRemoteQuickControl("adaptive_mode", 10, "Game Mode", "gamepad", "session.adaptive_mode", emptySet(), setOf("local.display", "shell.game_mode")),
    NukeRemoteQuickControl("focus", 20, "Focus", "focus", "focus.dnd", setOf("local.dnd"), emptySet()),
    NukeRemoteQuickControl("network_boost", 30, "Net Lock", "network", "network.session_lock", setOf("local.wifi"), emptySet()),
    NukeRemoteQuickControl("crosshair_toggle", 40, "Crosshair", "crosshair", "crosshair.enabled", setOf("overlay"), emptySet()),
    NukeRemoteQuickControl("keep_awake", 50, "Awake", "screen", "screen.keep_awake", setOf("local.keep_awake"), emptySet()),
)
