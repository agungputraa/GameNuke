package com.neon.gametweak

import android.app.Activity
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
    @Volatile var cachedCdnUpdate: NukeAppUpdater.UpdateInfo? = null
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
                NukeToast.success(activity, Tx.t("Pembaruan siap dipasang", "Update is ready to install"), long = true)
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

    fun check(activity: Activity) {
        // 1. Check via Edge CDN (GitHub Pages / jsDelivr) for instant website/sideload updates
        NukeAppUpdater.checkForUpdates(
            context = activity.applicationContext,
            onUpdateAvailable = { cdnInfo ->
                hasUpdate = true
                availableVersion = cdnInfo.versionName
                cachedCdnUpdate = cdnInfo
                Log.i(TAG, "Edge CDN Update available: v${cdnInfo.versionName}")
            },
            onUpToDate = {
                Log.i(TAG, "App is up to date via Edge CDN")
            },
            onError = {
                Log.w(TAG, "Edge CDN update check fallback to Play Store: $it")
            }
        )

        // 2. Fallback to Google Play Store check
        val mgr = updateManager ?: return
        runCatching {
            mgr.appUpdateInfo
                .addOnSuccessListener { info ->
                    val updateAvailable = info.updateAvailability() == UpdateAvailability.UPDATE_AVAILABLE
                    val flexAllowed = info.isUpdateTypeAllowed(AppUpdateType.FLEXIBLE)
                    if (updateAvailable && flexAllowed) {
                        hasUpdate = true
                        availableVersion = info.availableVersionCode().toString()
                    }
                    if (info.installStatus() == InstallStatus.DOWNLOADED) {
                        runCatching { mgr.completeUpdate() }
                    }
                }
                .addOnFailureListener { e ->
                    Log.w(TAG, "appUpdateInfo failed: ${e.message}")
                }
        }
    }

    fun startFlexibleUpdate(activity: Activity) {
        // If Edge CDN update is available, download directly without Play Store
        val cdnUpdate = cachedCdnUpdate
        if (cdnUpdate != null) {
            NukeToast.success(activity, "Mendownload update v${cdnUpdate.versionName} dari server resmi…", long = true)
            NukeAppUpdater.startDownloadAndInstall(activity, cdnUpdate)
            return
        }

        val mgr = updateManager ?: run {
            openPlayStorePage(activity)
            NukeToast.success(activity, Tx.t("Membuka Google Play Store...", "Opening Google Play Store..."))
            return
        }
        val l = launcher ?: run {
            openPlayStorePage(activity)
            NukeToast.success(activity, Tx.t("Membuka Google Play Store...", "Opening Google Play Store..."))
            return
        }
        runCatching {
            mgr.appUpdateInfo.addOnSuccessListener { info ->
                if (info.updateAvailability() == UpdateAvailability.UPDATE_AVAILABLE &&
                    info.isUpdateTypeAllowed(AppUpdateType.FLEXIBLE)
                ) {
                    runCatching {
                        mgr.startUpdateFlowForResult(
                            info,
                            l,
                            AppUpdateOptions.newBuilder(AppUpdateType.FLEXIBLE).build(),
                        )
                    }.onSuccess { NukeToast.success(activity, Tx.t("Alur pembaruan dibuka.", "Update flow opened.")) }
                        .onFailure {
                            openPlayStorePage(activity)
                        }
                } else {
                    NukeToast.success(activity, Tx.t("Sudah versi terbaru (v${BuildConfig.VERSION_NAME})", "Game Nuke is already up to date (v${BuildConfig.VERSION_NAME})"))
                }
            }.addOnFailureListener { _ ->
                openPlayStorePage(activity)
            }
        }.onFailure {
            openPlayStorePage(activity)
        }
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

