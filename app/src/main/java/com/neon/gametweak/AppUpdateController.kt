package com.neon.gametweak

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import com.google.android.play.core.appupdate.AppUpdateManager
import com.google.android.play.core.appupdate.AppUpdateManagerFactory
import com.google.android.play.core.appupdate.AppUpdateOptions
import com.google.android.play.core.install.InstallStateUpdatedListener
import com.google.android.play.core.install.model.AppUpdateType
import com.google.android.play.core.install.model.InstallStatus
import com.google.android.play.core.install.model.UpdateAvailability
import com.google.android.play.core.review.ReviewManagerFactory

object AppUpdateController {

    private const val TAG = "AppUpdate"
    private const val LAUNCHER_KEY = "nuke_app_update_launcher"

    @Volatile var hasUpdate: Boolean = false
        private set
    @Volatile var availableVersion: String? = null
        private set

    private var updateManager: AppUpdateManager? = null
    private var listener: InstallStateUpdatedListener? = null
    private var launcher: ActivityResultLauncher<IntentSenderRequest>? = null

    fun register(activity: ComponentActivity) {
        updateManager = AppUpdateManagerFactory.create(activity.applicationContext)
        @Suppress("UnsafeOptInUsageError")
        launcher = activity.activityResultRegistry.register(
            LAUNCHER_KEY,
            ActivityResultContracts.StartIntentSenderForResult(),
        ) { result ->
            if (result.resultCode != Activity.RESULT_OK) {
                Log.w(TAG, "update flow result=${result.resultCode}")
            }
        }
        listener = InstallStateUpdatedListener { state ->
            if (state.installStatus() == InstallStatus.DOWNLOADED) {
                NukeToast.success(activity, ("Update is ready to install"), long = true)
                runCatching { updateManager?.completeUpdate() }
            }
        }
        listener?.let { updateManager?.registerListener(it) }
        // Network/update query is deferred by MainActivity until after the first frame.
    }

    fun unregister() {
        listener?.let { updateManager?.unregisterListener(it) }
        listener = null
        runCatching { launcher?.unregister() }
        launcher = null
    }

    const val OFFICIAL_WEBSITE_URL = "https://gamenukeofficial.com"

    fun openOfficialWebsite(context: Context) {
        runCatching {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=${context.packageName}")).apply {
                if (context !is Activity) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            NukeToast.success(context, ("Opening Google Play..."))
        }.onFailure { e ->
            Log.e(TAG, "Failed opening official website", e)
            if (context is Activity) openPlayStorePage(context)
        }
    }

    fun check(activity: Activity) {
        val mgr = updateManager ?: return
        mgr.appUpdateInfo.addOnSuccessListener { info ->
            hasUpdate = info.updateAvailability() == UpdateAvailability.UPDATE_AVAILABLE
            availableVersion = if (hasUpdate) info.availableVersionCode().toString() else null
            if (info.installStatus() == InstallStatus.DOWNLOADED) mgr.completeUpdate()
        }.addOnFailureListener { Log.w(TAG, "Play update check unavailable", it) }
    }

    fun startFlexibleUpdate(activity: Activity) {
        val mgr = updateManager ?: run { openPlayStorePage(activity); return }
        mgr.appUpdateInfo.addOnSuccessListener { info ->
            val updateLauncher = launcher
            if (info.installStatus() == InstallStatus.DOWNLOADED) {
                mgr.completeUpdate()
            } else if (info.updateAvailability() == UpdateAvailability.UPDATE_AVAILABLE &&
                info.isUpdateTypeAllowed(AppUpdateType.FLEXIBLE) && updateLauncher != null) {
                runCatching { mgr.startUpdateFlowForResult(info, updateLauncher,
                    AppUpdateOptions.newBuilder(AppUpdateType.FLEXIBLE).build()) }
                    .onFailure { openPlayStorePage(activity) }
            } else openPlayStorePage(activity)
        }.addOnFailureListener { openPlayStorePage(activity) }
    }

    fun launchInAppReview(activity: Activity, onComplete: (() -> Unit)? = null) {
        val reviewManager = ReviewManagerFactory.create(activity.applicationContext)
        runCatching {
            reviewManager.requestReviewFlow().addOnCompleteListener { task ->
                if (task.isSuccessful) {
                    val info = task.result
                    reviewManager.launchReviewFlow(activity, info).addOnCompleteListener {
                        onComplete?.invoke()
                    }
                } else {
                    openPlayStorePage(activity)
                    onComplete?.invoke()
                }
            }
        }.onFailure {
            openPlayStorePage(activity)
            onComplete?.invoke()
        }
    }

    fun openPlayStorePage(activity: Activity) {
        runCatching {
            val uri = android.net.Uri.parse("market://details?id=${activity.packageName}")
            val goToMarket = android.content.Intent(android.content.Intent.ACTION_VIEW, uri).apply {
                addFlags(
                    android.content.Intent.FLAG_ACTIVITY_NO_HISTORY or
                        android.content.Intent.FLAG_ACTIVITY_NEW_DOCUMENT or
                        android.content.Intent.FLAG_ACTIVITY_MULTIPLE_TASK
                )
            }
            activity.startActivity(goToMarket)
        }.onFailure {
            runCatching {
                activity.startActivity(
                    android.content.Intent(
                        android.content.Intent.ACTION_VIEW,
                        android.net.Uri.parse("https://play.google.com/store/apps/details?id=${activity.packageName}")
                    )
                )
            }
        }
    }
}

