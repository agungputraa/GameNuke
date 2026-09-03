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
import org.json.JSONArray
import org.json.JSONObject

/**
 * High-Performance Dual-Engine Macro Coordinator for Game Nuke.
 *
 * Engine selection:
 * 1. Shizuku / ADB Privileged Engine: ~0ms input injection via privileged binder/shell.
 *    Bypasses Android gesture pacing limits, achieving up to 60+ clicks/second for fast-hand combos.
 * 2. Universal Accessibility Service Engine: Fallback using Android dispatchGesture API.
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
        val profileName: String = "Default Combo"
    )

    enum class MacroEngine {
        NONE,
        ACCESSIBILITY,
        SHIZUKU_PRIVILEGED
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _state = MutableStateFlow(MacroState())
    val state: StateFlow<MacroState> = _state.asStateFlow()

    private var executionJob: Job? = null
    private val adbManager = AdbManager.getInstance(context)

    companion object {
        private const val TAG = "NukeMacroController"
        private const val PREFS_NAME = "NukeMacroPrefs"
        private const val KEY_PROFILES = "macro_profiles_json"

        @Volatile
        private var instance: NukeMacroController? = null

        fun getInstance(context: Context): NukeMacroController {
            return instance ?: synchronized(this) {
                instance ?: NukeMacroController(context.applicationContext).also { instance = it }
            }
        }
    }

    init {
        detectBestEngine()
        loadDefaultProfile()
    }

    /**
     * Determine best available injection engine (Shizuku > Accessibility > None)
     */
    fun detectBestEngine(): MacroEngine {
        val hasPrivileged = runCatching {
            NukeConnectionManager.isConnected() || adbManager.isConnected()
        }.getOrDefault(false)

        val engine = when {
            hasPrivileged -> MacroEngine.SHIZUKU_PRIVILEGED
            NukeMacroService.isServiceRunning -> MacroEngine.ACCESSIBILITY
            else -> MacroEngine.NONE
        }
        _state.update { it.copy(activeEngine = engine) }
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
     * Start execution of configured macro points
     */
    fun startMacro(): Boolean {
        val currentPoints = _state.value.points
        if (currentPoints.isEmpty()) {
            Log.w(TAG, "Cannot start macro: no points configured")
            return false
        }

        val engine = detectBestEngine()
        if (engine == MacroEngine.NONE) {
            Log.w(TAG, "Cannot start macro: no engine ready (neither Shizuku nor Accessibility)")
            return false
        }

        stopMacro()

        _state.update { it.copy(isRunning = true, isPaused = false, currentLoop = 0) }

        executionJob = scope.launch {
            var loop = 0
            val targetLoops = _state.value.loopCount
            val speed = _state.value.speedMultiplier

            while (isActive && _state.value.isRunning && (targetLoops == 0 || loop < targetLoops)) {
                loop++
                _state.update { it.copy(currentLoop = loop) }

                for (point in currentPoints) {
                    if (!isActive || !_state.value.isRunning) break

                    // Execute click via best available engine
                    executeClick(point.x, point.y, point.holdDurationMs)

                    // Delay interval scaled by speed multiplier
                    val scaledDelay = (point.delayAfterMs / speed).toLong().coerceAtLeast(8L)
                    delay(scaledDelay)
                }
            }

            _state.update { it.copy(isRunning = false, isPaused = false) }
        }

        return true
    }

    fun stopMacro() {
        executionJob?.cancel()
        executionJob = null
        _state.update { it.copy(isRunning = false, isPaused = false) }
    }

    private suspend fun executeClick(x: Float, y: Float, holdMs: Long) {
        val dm = context.resources.displayMetrics
        val safeX = x.coerceIn(0f, dm.widthPixels.toFloat())
        val safeY = y.coerceIn(0f, dm.heightPixels.toFloat())
        val safeHold = holdMs.coerceIn(10L, 2000L)

        val engine = _state.value.activeEngine
        if (engine == MacroEngine.SHIZUKU_PRIVILEGED) {
            // Instant 0ms input tap via privileged shell/binder
            val cmdRes = runCatching {
                adbManager.executeCommand("input tap ${safeX.toInt()} ${safeY.toInt()}", "/", 1_500L)
            }.getOrNull()

            if (cmdRes == null || !cmdRes.isSuccess) {
                // Fallback to accessibility if shell hit an unexpected glitch
                NukeMacroService.performTap(safeX, safeY, safeHold)
            }
        } else if (engine == MacroEngine.ACCESSIBILITY) {
            NukeMacroService.performTap(safeX, safeY, safeHold)
        }
    }

    /**
     * Save current profile to preferences
     */
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
