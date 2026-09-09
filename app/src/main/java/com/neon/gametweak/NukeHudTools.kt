package com.neon.gametweak

import android.app.AppOpsManager
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Process
import android.os.SystemClock
import android.view.Gravity
import android.view.MotionEvent
import android.view.WindowManager
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.neon.gametweak.ui.theme.NukeEnterpriseTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.net.InetSocketAddress
import java.net.Socket

/** Owns lightweight optional tools for one user-started HUD session. */
class NukeHudTools(private val context: Context, private val scope: CoroutineScope) {
    companion object {
        fun hasUsageAccess(context: Context): Boolean = runCatching {
            val ops = context.getSystemService(AppOpsManager::class.java) ?: return false
            ops.unsafeCheckOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS,
                Process.myUid(),
                context.packageName,
            ) == AppOpsManager.MODE_ALLOWED
        }.getOrDefault(false)
    }

    private val wm = requireNotNull(context.getSystemService(WindowManager::class.java))
    private var switcher: ComposeView? = null
    private var lifecycle: OverlayComposeLifecycleOwner? = null
    private var switchTimeout: Job? = null
    private var recentLoad: Job? = null
    private var pingJob: Job? = null
    private val measured = MutableStateFlow<Long?>(null)
    val pingMs = measured.asStateFlow()
    private val monitoring = MutableStateFlow(false)
    val pingEnabled = monitoring.asStateFlow()

    private val screenReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) = close()
    }

    init {
        androidx.core.content.ContextCompat.registerReceiver(
            context,
            screenReceiver,
            android.content.IntentFilter(Intent.ACTION_SCREEN_OFF),
            androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED,
        )
    }

    fun destroy() {
        close()
        runCatching { context.unregisterReceiver(screenReceiver) }
    }

    private fun dp(value: Int) = (value * context.resources.displayMetrics.density).toInt()

    private fun params(width: Int, height: Int) = WindowManager.LayoutParams(
        width,
        height,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
        PixelFormat.TRANSLUCENT,
    ).apply {
        gravity = Gravity.TOP or Gravity.LEFT
        layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
    }

    fun togglePing() {
        if (pingJob != null) {
            pingJob?.cancel()
            pingJob = null
            monitoring.value = false
            measured.value = null
            return
        }
        monitoring.value = true
        pingJob = scope.launch {
            try {
                while (isActive) {
                    measured.value = withContext(Dispatchers.IO) {
                        runCatching {
                            Socket().use { socket ->
                                val started = SystemClock.elapsedRealtime()
                                socket.connect(InetSocketAddress("1.1.1.1", 443), 1_000)
                                (SystemClock.elapsedRealtime() - started).coerceAtLeast(1L)
                            }
                        }.getOrNull()
                    }
                    delay(2_000)
                }
            } finally {
                measured.value = null
                monitoring.value = false
            }
        }
    }

    fun openRecentApps() {
        if (!hasUsageAccess(context)) {
            runCatching {
                context.startActivity(
                    Intent(context, NukeUsageAccessActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            }.onFailure { NukeToast.error(context, "Usage access setup could not be opened.") }
            return
        }
        recentLoad?.cancel()
        recentLoad = scope.launch {
            val apps = withContext(Dispatchers.IO) {
                runCatching {
                    val now = System.currentTimeMillis()
                    context.getSystemService(UsageStatsManager::class.java)
                        ?.queryUsageStats(
                            UsageStatsManager.INTERVAL_DAILY,
                            now - 7 * 86_400_000L,
                            now,
                        ).orEmpty()
                        .filter { it.packageName != context.packageName }
                        .sortedByDescending { it.lastTimeUsed }
                        .distinctBy { it.packageName }
                        .mapNotNull { item ->
                            val launch = context.packageManager.getLaunchIntentForPackage(item.packageName)
                                ?: return@mapNotNull null
                            runCatching {
                                val info = context.packageManager.getApplicationInfo(item.packageName, 0)
                                RecentApp(
                                    context.packageManager.getApplicationLabel(info).toString(),
                                    context.packageManager.getApplicationIcon(info).toBitmap(dp(40), dp(40)).asImageBitmap(),
                                    launch,
                                )
                            }.getOrNull()
                        }
                        .take(4)
                }.getOrDefault(emptyList())
            }
            if (apps.isEmpty()) {
                NukeToast.error(context, "No recent launchable apps are available.")
                return@launch
            }
            closeSwitcher()
            runCatching { showSwitcher(apps) }
                .onFailure {
                    closeSwitcher()
                    NukeToast.error(context, "App switcher could not be opened.")
                }
        }
    }

    private data class RecentApp(
        val label: String,
        val icon: androidx.compose.ui.graphics.ImageBitmap,
        val intent: Intent,
    )

    private fun showSwitcher(apps: List<RecentApp>) {
        val owner = OverlayComposeLifecycleOwner().also { it.start() }
        lifecycle = owner
        val view = ComposeView(context)
        switcher = view
        view.setViewTreeLifecycleOwner(owner)
        view.setViewTreeSavedStateRegistryOwner(owner)
        view.setViewTreeViewModelStoreOwner(owner)
        view.setContent { RecentAppsPanel(apps) }
        view.setOnTouchListener { _, event ->
            if (event.action == MotionEvent.ACTION_OUTSIDE) {
                closeSwitcher()
                true
            } else {
                false
            }
        }
        val lp = params(dp(300), WindowManager.LayoutParams.WRAP_CONTENT).apply {
            flags = flags or WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH
            gravity = Gravity.CENTER
        }
        wm.addView(view, lp)
        switchTimeout = scope.launch {
            delay(5_000)
            closeSwitcher()
        }
    }

    @Composable
    private fun RecentAppsPanel(apps: List<RecentApp>) {
        NukeEnterpriseTheme {
            Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surface) {
                Column(Modifier.padding(12.dp)) {
                    Text("Recent apps", style = MaterialTheme.typography.titleMedium)
                    Row(
                        Modifier.padding(top = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        apps.forEach { app ->
                            Column(
                                Modifier.width(58.dp).clickable {
                                    runCatching {
                                        context.startActivity(app.intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                                    }.onFailure { NukeToast.error(context, "This app could not be launched.") }
                                    closeSwitcher()
                                },
                                horizontalAlignment = Alignment.CenterHorizontally,
                            ) {
                                Image(app.icon, app.label, Modifier.size(40.dp))
                                Text(app.label, maxLines = 1, style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                    TextButton(
                        onClick = ::closeSwitcher,
                        modifier = Modifier.nukePressFeedback(),
                    ) { Text("Close") }
                }
            }
        }
    }

    private fun closeSwitcher() {
        switchTimeout?.cancel()
        switchTimeout = null
        switcher?.let {
            runCatching { wm.removeViewImmediate(it) }
            it.disposeComposition()
        }
        switcher = null
        lifecycle?.destroy()
        lifecycle = null
    }

    fun close() {
        recentLoad?.cancel()
        recentLoad = null
        closeSwitcher()
        pingJob?.cancel()
        pingJob = null
        monitoring.value = false
        measured.value = null
    }
}
