package com.neon.gametweak

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * High-Performance Dual-Engine Macro Coordinator for Game Nuke.
 *
 * Engine selection (priority order):
 * 1. Shizuku / iADB / Daemon Privileged Engine: ~0ms input injection via privileged shell.
 *    Bypasses Android gesture pacing limits, achieving up to 60+ taps/second.
 * 2. Accessibility Service Engine: Universal fallback using Android dispatchGesture API.
 *    Works on all Android 11+ devices without root or Shizuku.
 *
 * Thread safety model:
 * - All StateFlow updates are atomic.
 * - Shell commands run on Dispatchers.IO (never blocks main thread).
 * - Accessibility gesture dispatch runs on Dispatchers.Main (required by Android API).
 * - Macro loop runs on Dispatchers.Default with proper coroutine cancellation.
 */
class NukeMacroController private constructor(private val context: Context) {

    data class MacroPoint(
        val id: Int,
        var x: Float,
        var y: Float,
        var delayAfterMs: Long = 65L,
        var holdDurationMs: Long = 20L
    )

    data class MacroState(
        val isRunning: Boolean = false,
        val isPaused: Boolean = false,
        val activeEngine: MacroEngine = MacroEngine.NONE,
        val points: List<MacroPoint> = emptyList(),
        val loopCount: Int = 0, // 0 = infinite
        val currentLoop: Int = 0,
        val speedMultiplier: Float = 1.0f,
        val profileName: String = "Default Combo",
        val lastError: String? = null
    )

    enum class MacroEngine {
        NONE,
        ACCESSIBILITY,
        SHIZUKU_PRIVILEGED
    }

    // SupervisorJob ensures child coroutine failures don't cancel the parent scope.
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _state = MutableStateFlow(MacroState())
    val state: StateFlow<MacroState> = _state.asStateFlow()

    private var executionJob: Job? = null

    companion object {
        private const val TAG = "NukeMacroController"
        private const val PREFS_NAME = "NukeMacroPrefs"
        private const val KEY_PROFILES = "macro_profiles_json"

        // Minimum interval to prevent system ANR from overly rapid taps.
        private const val MIN_TAP_INTERVAL_MS = 16L

        @Volatile
        private var instance: NukeMacroController? = null

        fun getInstance(context: Context): NukeMacroController {
            return instance ?: synchronized(this) {
                instance ?: NukeMacroController(context.applicationContext).also { instance = it }
            }
        }
    }

    init {
        // Run engine detection on IO thread — never on main thread to avoid StrictMode violations.
        scope.launch(Dispatchers.IO) {
            detectBestEngine()
        }
        loadDefaultProfile()
    }

    /**
     * Determine best available injection engine (Shizuku/iADB/Daemon > Accessibility > None).
     * Safe to call from any thread.
     */
    fun detectBestEngine(): MacroEngine {
        val hasPrivileged = runCatching {
            NukeConnectionManager.isConnected()
        }.getOrDefault(false)

        val engine = when {
            hasPrivileged -> MacroEngine.SHIZUKU_PRIVILEGED
            NukeMacroService.isServiceRunning -> MacroEngine.ACCESSIBILITY
            else -> MacroEngine.NONE
        }
        _state.update { it.copy(activeEngine = engine, lastError = null) }
        return engine
    }

    fun addPoint(x: Float, y: Float, delayMs: Long = 65L): MacroPoint {
        val currentPoints = _state.value.points.toMutableList()
        val nextId = (currentPoints.maxOfOrNull { it.id } ?: 0) + 1
        val newPoint = MacroPoint(id = nextId, x = x, y = y, delayAfterMs = delayMs)
        currentPoints.add(newPoint)
        _state.update { it.copy(points = currentPoints) }
        saveCurrentProfile()
        return newPoint
    }

    fun removePoint(pointId: Int) {
        val updated = _state.value.points.filterNot { it.id == pointId }
        _state.update { it.copy(points = updated) }
        saveCurrentProfile()
    }

    fun clearPoints() {
        _state.update { it.copy(points = emptyList()) }
        saveCurrentProfile()
    }

    fun updatePointCoordinates(pointId: Int, x: Float, y: Float) {
        val updated = _state.value.points.map {
            if (it.id == pointId) it.copy(x = x, y = y) else it
        }
        _state.update { it.copy(points = updated) }
    }

    fun setSpeedMultiplier(multiplier: Float) {
        _state.update { it.copy(speedMultiplier = multiplier.coerceIn(0.2f, 5.0f)) }
    }

    fun setLoopCount(loops: Int) {
        _state.update { it.copy(loopCount = loops.coerceAtLeast(0)) }
    }

    /**
     * Start execution. Returns true if macro started, false if no engine is available.
     */
    fun startMacro(): Boolean {
        // Re-detect engine on each start to pick up newly granted permissions.
        val engine = detectBestEngine()
        if (engine == MacroEngine.NONE) {
            Log.w(TAG, "Cannot start macro: no engine available (enable Accessibility Service or Shizuku)")
            _state.update { it.copy(lastError = "No engine available. Enable Accessibility Service or connect Shizuku.") }
            return false
        }

        // Auto-add a default point at 75%/65% screen if no points configured.
        if (_state.value.points.isEmpty()) {
            val dm = context.resources.displayMetrics
            addPoint(dm.widthPixels * 0.75f, dm.heightPixels * 0.65f, 60L)
            Log.d(TAG, "Auto-added default macro point at 75%/65% screen position")
        }

        stopMacro()
        _state.update { it.copy(isRunning = true, isPaused = false, currentLoop = 0, lastError = null) }

        executionJob = scope.launch {
            var loop = 0
            val targetLoops = _state.value.loopCount
            val speed = _state.value.speedMultiplier

            try {
                while (isActive && _state.value.isRunning &&
                    (targetLoops == 0 || loop < targetLoops)) {
                    loop++
                    _state.update { it.copy(currentLoop = loop) }

                    val activePoints = _state.value.points.toList() // snapshot to avoid CME
                    for (point in activePoints) {
                        if (!isActive || !_state.value.isRunning) break

                        val success = executeClick(point.x, point.y, point.holdDurationMs)

                        // Enforce minimum interval + speed-scaled delay to prevent ANR.
                        val scaledDelay = (point.delayAfterMs / speed)
                            .toLong()
                            .coerceAtLeast(MIN_TAP_INTERVAL_MS)
                        delay(scaledDelay)

                        if (!success) {
                            // Engine became unavailable mid-run, abort gracefully.
                            Log.w(TAG, "Macro engine unavailable mid-run, stopping")
                            break
                        }
                    }
                }
            } catch (e: Exception) {
                if (isActive) {
                    Log.e(TAG, "Macro execution error", e)
                    _state.update { it.copy(lastError = "Macro stopped: ${e.message}") }
                }
            } finally {
                _state.update { it.copy(isRunning = false, isPaused = false) }
            }
        }

        return true
    }

    fun stopMacro() {
        executionJob?.cancel()
        executionJob = null
        _state.update { it.copy(isRunning = false, isPaused = false) }
    }

    /**
     * Execute a single tap with engine fallback.
     * - Shell commands run on Dispatchers.IO.
     * - Accessibility gestures dispatched on Dispatchers.Main.
     *
     * Returns true if the tap was successfully dispatched.
     */
    private suspend fun executeClick(x: Float, y: Float, holdMs: Long): Boolean {
        val dm = context.resources.displayMetrics
        val safeX = x.coerceIn(0f, dm.widthPixels.toFloat() - 1f)
        val safeY = y.coerceIn(0f, dm.heightPixels.toFloat() - 1f)
        val safeHold = holdMs.coerceIn(10L, 500L)

        // --- Engine 1: Privileged shell (Shizuku / iADB / Daemon / ADB) ---
        if (NukeConnectionManager.isConnected()) {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    NukeConnectionManager.executeCommand(
                        "input tap ${safeX.toInt()} ${safeY.toInt()}",
                        timeoutMs = 800L
                    )
                }.getOrNull()
            }
            if (result?.isSuccess == true) return true
            // Shell failed (permission lost, etc.) — fall through to accessibility.
            Log.d(TAG, "Shell tap failed, falling back to Accessibility")
        }

        // --- Engine 2: Accessibility Service ---
        if (NukeMacroService.isServiceRunning) {
            return withContext(Dispatchers.Main) {
                NukeMacroService.performTap(safeX, safeY, safeHold)
            }
        }

        return false
    }

    fun saveCurrentProfile() {
        runCatching {
            val arr = JSONArray()
            _state.value.points.forEach { p ->
                val obj = JSONObject().apply {
                    put("id", p.id)
                    put("x", p.x.toDouble())
                    put("y", p.y.toDouble())
                    put("delay", p.delayAfterMs)
                    put("hold", p.holdDurationMs)
                }
                arr.put(obj)
            }
            prefs.edit().putString(KEY_PROFILES, arr.toString()).apply()
        }
    }

    private fun loadDefaultProfile() {
        runCatching {
            val json = prefs.getString(KEY_PROFILES, null) ?: return
            val arr = JSONArray(json)
            val list = mutableListOf<MacroPoint>()
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                list.add(
                    MacroPoint(
                        id = obj.getInt("id"),
                        x = obj.getDouble("x").toFloat(),
                        y = obj.getDouble("y").toFloat(),
                        delayAfterMs = obj.optLong("delay", 65L),
                        holdDurationMs = obj.optLong("hold", 20L)
                    )
                )
            }
            _state.update { it.copy(points = list) }
        }
    }
}
