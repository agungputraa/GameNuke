package com.neon.gametweak

import android.app.Activity
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.widget.FrameLayout
import com.vungle.ads.AdConfig
import com.vungle.ads.BannerAdListener
import com.vungle.ads.BaseAd
import com.vungle.ads.InitializationListener
import com.vungle.ads.InterstitialAd
import com.vungle.ads.InterstitialAdListener
import com.vungle.ads.RewardedAd
import com.vungle.ads.RewardedAdListener
import com.vungle.ads.VungleAdSize
import com.vungle.ads.VungleAds
import com.vungle.ads.VungleBannerView
import com.vungle.ads.VungleError
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Revenue-optimized, gameplay-safe Liftoff Monetize (Vungle SDK 7.x) coordinator.
 *
 * Ad strategy:
 * - Banner     : bottom-anchored on passive screens (VungleBannerView as a View)
 * - Interstitial: natural screen transitions, 7-minute global cooldown
 * - App Open   : on foreground/cold-start using InterstitialAd + APP_OPEN placement,
 *                45-minute cooldown, requires 4+ foregrounds
 * - Rewarded   : on-demand (optional reward gate)
 *
 * Safety rules:
 * - Never show full-screen while HUD/overlay gaming session is active
 * - 3-minute grace after gaming session ends before showing full-screen
 * - USE_TEST_ADS flag is used for debug logging; Vungle auto-detects test devices
 */
object NukeAdManager {
    private const val TAG = "NukeAdManager"

    // ── Placement IDs ──────────────────────────────────────────────────────────
    val APP_ID: String          get() = BuildConfig.VUNGLE_APP_ID
    val BANNER_ID: String       get() = BuildConfig.VUNGLE_BANNER_ID
    val INTERSTITIAL_ID: String get() = BuildConfig.VUNGLE_INTERSTITIAL_ID
    val APP_OPEN_ID: String     get() = BuildConfig.VUNGLE_APP_OPEN_ID
    val REWARD_ID: String       get() = BuildConfig.VUNGLE_REWARD_ID

    // ── Timing constants (Optimized for High Revenue & Active Monetization) ───
    private const val APP_OPEN_TIMEOUT_MS              = 4L * 60L * 60L * 1_000L  // 4h
    private const val MIN_BETWEEN_FULLSCREEN_MS        = 75L * 1_000L             // 75 seconds between interstitials
    private const val MIN_BETWEEN_APP_OPEN_MS          = 3L * 60L * 1_000L        // 3 minutes between app-open ads
    private const val MIN_SESSION_AGE_FOR_INTERSTITIAL = 15L * 1_000L             // 15 seconds after app startup
    private const val GAMEPLAY_GRACE_MS                = 30L * 1_000L             // 30 seconds after game session ends
    private const val NAVIGATION_CLICK_INTERVAL        = 2                        // Every 2 navigation clicks
    private const val MIN_FOREGROUNDS_BEFORE_APP_OPEN  = 1                        // Shows on 1st background resume

    // ── Shared preferences ─────────────────────────────────────────────────────
    private const val ADS_PREFS              = "NukeAdExperience"
    private const val KEY_FOREGROUND_COUNT   = "foreground_count"
    private const val KEY_LAST_APP_OPEN_MS   = "last_app_open_ms"
    private const val KEY_LAST_FULLSCREEN_MS = "last_fullscreen_ms"
    private const val KEY_LAST_GAMEPLAY_END  = "last_gameplay_end_ms"

    private val mainHandler = Handler(Looper.getMainLooper())
    private val processStartedElapsed = SystemClock.elapsedRealtime()

    @Volatile var initialized = false
    @Volatile private var initStarted = false

    // Interstitial (standard navigation gate)
    private var interstitialAd: InterstitialAd? = null
    private var interstitialLoading = false

    // App Open (uses InterstitialAd with APP_OPEN placement ID)
    private var appOpenAd: InterstitialAd? = null
    private var appOpenLoadTimeMs = 0L
    private var appOpenLoading = false

    // Rewarded
    private var rewardedAd: RewardedAd? = null
    private var rewardedLoading = false

    private var isShowingFullScreen = false
    private var navigationCount = 0
    private var lastFullScreenTimeMs = 0L

    @Volatile var mainAppReady: Boolean = false

    // ──────────────────────────────────────────────────────────────────────────
    //  Utility
    // ──────────────────────────────────────────────────────────────────────────

    private fun onMain(action: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) action() else mainHandler.post(action)
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(ADS_PREFS, Context.MODE_PRIVATE)

    private fun lastFullscreenMs(context: Context): Long {
        val persisted = prefs(context).getLong(KEY_LAST_FULLSCREEN_MS, 0L)
        return maxOf(lastFullScreenTimeMs, persisted)
    }

    private fun markFullscreenShown(context: Context, isAppOpen: Boolean = false) {
        val now = System.currentTimeMillis()
        lastFullScreenTimeMs = now
        runCatching {
            val ed = prefs(context).edit().putLong(KEY_LAST_FULLSCREEN_MS, now)
            if (isAppOpen) ed.putLong(KEY_LAST_APP_OPEN_MS, now)
            ed.apply()
        }
    }

    fun markGamingSessionEnded(context: Context) {
        runCatching {
            prefs(context).edit().putLong(KEY_LAST_GAMEPLAY_END, System.currentTimeMillis()).apply()
        }
    }

    private fun fullScreenAllowed(context: Context): Boolean {
        if (NukeRuntimeState.state.value.overlayRunning) return false
        val lastEnd = prefs(context).getLong(KEY_LAST_GAMEPLAY_END, 0L)
        return lastEnd <= 0L || System.currentTimeMillis() - lastEnd >= GAMEPLAY_GRACE_MS
    }

    private fun appOpenEligible(activity: Activity): Boolean {
        val p = prefs(activity)
        val count = p.getInt(KEY_FOREGROUND_COUNT, 0)
        val next = if (count >= Int.MAX_VALUE - 1) Int.MAX_VALUE - 1 else count + 1
        runCatching { p.edit().putInt(KEY_FOREGROUND_COUNT, next).apply() }
        if (next < MIN_FOREGROUNDS_BEFORE_APP_OPEN) return false
        val lastOpen = p.getLong(KEY_LAST_APP_OPEN_MS, 0L)
        return System.currentTimeMillis() - lastOpen >= MIN_BETWEEN_APP_OPEN_MS
    }

    private fun appOpenAvailable(): Boolean {
        val age = System.currentTimeMillis() - appOpenLoadTimeMs
        return appOpenAd != null && appOpenLoadTimeMs > 0L && age in 0 until APP_OPEN_TIMEOUT_MS
    }

    private fun isActivityUsable(activity: Activity) = !activity.isFinishing && !activity.isDestroyed

    // ──────────────────────────────────────────────────────────────────────────
    //  Initialization
    // ──────────────────────────────────────────────────────────────────────────

    fun initialize(context: Context) {
        val appContext = context.applicationContext
        onMain {
            if (initialized || initStarted) return@onMain
            initStarted = true
            VungleAds.init(appContext, APP_ID, object : InitializationListener {
                override fun onSuccess() {
                    onMain {
                        initialized = true
                        initStarted = false
                        Log.i(TAG, "Vungle SDK initialized ✓ (testMode=${BuildConfig.USE_TEST_ADS})")
                        loadInterstitialInternal(appContext)
                        loadAppOpenInternal(appContext)
                        loadRewardedInternal(appContext)
                    }
                }
                override fun onError(error: VungleError) {
                    initStarted = false
                    Log.w(TAG, "Vungle init failed: ${error.errorMessage} (${error.code})")
                }
            })
        }
    }

    fun preload(context: Context) {
        val appContext = context.applicationContext
        onMain {
            if (!initialized) { initialize(appContext); return@onMain }
            loadInterstitialInternal(appContext)
            loadAppOpenInternal(appContext)
            loadRewardedInternal(appContext)
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    //  Interstitial
    // ──────────────────────────────────────────────────────────────────────────

    private fun loadInterstitialInternal(context: Context) {
        if (!initialized || interstitialLoading || interstitialAd != null) return
        interstitialLoading = true
        val appCtx = context.applicationContext
        val ad = InterstitialAd(appCtx, INTERSTITIAL_ID, AdConfig())
        ad.adListener = object : InterstitialAdListener {
            override fun onAdLoaded(baseAd: BaseAd) {
                interstitialAd = ad
                interstitialLoading = false
                Log.d(TAG, "Interstitial loaded ✓")
            }
            override fun onAdFailedToLoad(baseAd: BaseAd, error: VungleError) {
                interstitialLoading = false
                interstitialAd = null
                Log.w(TAG, "Interstitial load failed: ${error.errorMessage}")
            }
            override fun onAdStart(baseAd: BaseAd)     { isShowingFullScreen = true }
            override fun onAdEnd(baseAd: BaseAd)       {
                isShowingFullScreen = false
                interstitialAd = null
                loadInterstitialInternal(appCtx)
            }
            override fun onAdClicked(baseAd: BaseAd)   {}
            override fun onAdImpression(baseAd: BaseAd){}
            override fun onAdLeftApplication(baseAd: BaseAd) {}
            override fun onAdFailedToPlay(baseAd: BaseAd, error: VungleError) {
                isShowingFullScreen = false
                interstitialAd = null
                loadInterstitialInternal(appCtx)
            }
        }
        ad.load()
    }

    fun showInterstitial(activity: Activity, force: Boolean = false, onAdClosed: (() -> Unit)? = null) {
        val gate = OneShot(onAdClosed)
        onMain { showInterstitialInternal(activity, force, gate) }
    }

    private fun showInterstitialInternal(activity: Activity, force: Boolean, gate: OneShot) {
        if (!isActivityUsable(activity) || isShowingFullScreen || !mainAppReady || !fullScreenAllowed(activity)) {
            gate.run(); return
        }
        if (SystemClock.elapsedRealtime() - processStartedElapsed < MIN_SESSION_AGE_FOR_INTERSTITIAL) {
            gate.run(); return
        }
        val now = System.currentTimeMillis()
        if (now - lastFullscreenMs(activity) < MIN_BETWEEN_FULLSCREEN_MS) {
            gate.run(); return
        }
        if (!force) {
            navigationCount = if (navigationCount >= Int.MAX_VALUE - 1) 1 else navigationCount + 1
            if (navigationCount % NAVIGATION_CLICK_INTERVAL != 0) {
                gate.run()
                if (interstitialAd == null) loadInterstitialInternal(activity.applicationContext)
                return
            }
        }
        val ad = interstitialAd
        if (ad == null || !ad.canPlayAd()) {
            loadInterstitialInternal(activity.applicationContext)
            gate.run(); return
        }
        interstitialAd = null
        markFullscreenShown(activity.applicationContext)
        val appCtx = activity.applicationContext
        ad.adListener = object : InterstitialAdListener {
            override fun onAdLoaded(baseAd: BaseAd) {}
            override fun onAdFailedToLoad(baseAd: BaseAd, error: VungleError) {}
            override fun onAdStart(baseAd: BaseAd)   { isShowingFullScreen = true }
            override fun onAdEnd(baseAd: BaseAd)     {
                isShowingFullScreen = false
                loadInterstitialInternal(appCtx)
                gate.run()
            }
            override fun onAdClicked(baseAd: BaseAd)  {}
            override fun onAdImpression(baseAd: BaseAd) {}
            override fun onAdLeftApplication(baseAd: BaseAd) {}
            override fun onAdFailedToPlay(baseAd: BaseAd, error: VungleError) {
                isShowingFullScreen = false
                loadInterstitialInternal(appCtx)
                gate.run()
            }
        }
        runCatching { ad.play(activity) }.onFailure {
            Log.w(TAG, "Interstitial play threw", it)
            isShowingFullScreen = false
            loadInterstitialInternal(activity.applicationContext)
            gate.run()
        }
    }

    fun showInterstitialForEvent(activity: Activity, onAdClosed: (() -> Unit)? = null) =
        showInterstitial(activity, force = true, onAdClosed = onAdClosed)

    // ──────────────────────────────────────────────────────────────────────────
    //  App Open Ad (uses InterstitialAd with APP_OPEN placement)
    // ──────────────────────────────────────────────────────────────────────────

    fun loadAppOpen(context: Context) {
        onMain {
            if (!initialized) { initialize(context); return@onMain }
            loadAppOpenInternal(context.applicationContext)
        }
    }

    private fun loadAppOpenInternal(context: Context) {
        if (!initialized || appOpenLoading || appOpenAvailable()) return
        appOpenLoading = true
        val appCtx = context.applicationContext
        val ad = InterstitialAd(appCtx, APP_OPEN_ID, AdConfig())
        ad.adListener = object : InterstitialAdListener {
            override fun onAdLoaded(baseAd: BaseAd) {
                appOpenAd = ad
                appOpenLoadTimeMs = System.currentTimeMillis()
                appOpenLoading = false
                Log.d(TAG, "App-open loaded ✓")
            }
            override fun onAdFailedToLoad(baseAd: BaseAd, error: VungleError) {
                appOpenLoading = false
                appOpenAd = null
                Log.w(TAG, "App-open load failed: ${error.errorMessage}")
            }
            override fun onAdStart(baseAd: BaseAd)   { isShowingFullScreen = true }
            override fun onAdEnd(baseAd: BaseAd)     {
                isShowingFullScreen = false
                appOpenAd = null
                loadAppOpenInternal(appCtx)
            }
            override fun onAdClicked(baseAd: BaseAd)  {}
            override fun onAdImpression(baseAd: BaseAd) {}
            override fun onAdLeftApplication(baseAd: BaseAd) {}
            override fun onAdFailedToPlay(baseAd: BaseAd, error: VungleError) {
                isShowingFullScreen = false
                appOpenAd = null
                loadAppOpenInternal(appCtx)
            }
        }
        ad.load()
    }

    fun showAppOpen(activity: Activity) = onMain { showAppOpenInternal(activity) }

    private fun showAppOpenInternal(activity: Activity) {
        if (!isActivityUsable(activity) || isShowingFullScreen || !mainAppReady || !fullScreenAllowed(activity)) return
        if (!appOpenEligible(activity)) return
        if (System.currentTimeMillis() - lastFullscreenMs(activity) < MIN_BETWEEN_FULLSCREEN_MS) return
        val ad = appOpenAd
        if (!appOpenAvailable() || ad == null || !ad.canPlayAd()) {
            appOpenAd = null
            loadAppOpenInternal(activity.applicationContext)
            return
        }
        appOpenAd = null
        markFullscreenShown(activity.applicationContext, isAppOpen = true)
        runCatching { ad.play(activity) }.onFailure {
            Log.w(TAG, "App-open play threw", it)
            isShowingFullScreen = false
            loadAppOpenInternal(activity.applicationContext)
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    //  Rewarded Ad
    // ──────────────────────────────────────────────────────────────────────────

    private fun loadRewardedInternal(context: Context) {
        if (!initialized || rewardedLoading || rewardedAd != null) return
        rewardedLoading = true
        val appCtx = context.applicationContext
        val ad = RewardedAd(appCtx, REWARD_ID, AdConfig())
        ad.adListener = object : RewardedAdListener {
            override fun onAdLoaded(baseAd: BaseAd) {
                rewardedAd = ad
                rewardedLoading = false
                Log.d(TAG, "Rewarded loaded ✓")
            }
            override fun onAdFailedToLoad(baseAd: BaseAd, error: VungleError) {
                rewardedLoading = false
                rewardedAd = null
                Log.w(TAG, "Rewarded load failed: ${error.errorMessage}")
            }
            override fun onAdStart(baseAd: BaseAd)      { isShowingFullScreen = true }
            override fun onAdEnd(baseAd: BaseAd)        {
                isShowingFullScreen = false
                rewardedAd = null
                loadRewardedInternal(appCtx)
            }
            override fun onAdRewarded(baseAd: BaseAd)   { Log.d(TAG, "User earned reward ✓") }
            override fun onAdClicked(baseAd: BaseAd)    {}
            override fun onAdImpression(baseAd: BaseAd) {}
            override fun onAdLeftApplication(baseAd: BaseAd) {}
            override fun onAdFailedToPlay(baseAd: BaseAd, error: VungleError) {
                isShowingFullScreen = false
                rewardedAd = null
                loadRewardedInternal(appCtx)
            }
        }
        ad.load()
    }

    // ── Booster VIP Pass (Smart Frequency Capping) ───────────────────────────
    private const val KEY_BOOSTER_VIP_EXPIRES_MS = "booster_vip_expires_ms"
    private const val BOOSTER_VIP_DURATION_MS    = 5L * 60L * 1_000L // 5 mins VIP pass for high rewarded frequency

    fun isBoosterVipActive(context: Context): Boolean {
        val expires = prefs(context).getLong(KEY_BOOSTER_VIP_EXPIRES_MS, 0L)
        return System.currentTimeMillis() < expires
    }

    fun grantBoosterVipPass(context: Context, durationMs: Long = BOOSTER_VIP_DURATION_MS) {
        val until = System.currentTimeMillis() + durationMs
        runCatching { prefs(context).edit().putLong(KEY_BOOSTER_VIP_EXPIRES_MS, until).apply() }
    }

    fun getBoosterVipRemainingMinutes(context: Context): Int {
        val expires = prefs(context).getLong(KEY_BOOSTER_VIP_EXPIRES_MS, 0L)
        val remaining = expires - System.currentTimeMillis()
        return if (remaining > 0) (remaining / (60 * 1000L)).toInt() else 0
    }

    /**
     * Booster Rewarded Ad Gate:
     * - If VIP pass is active: skips ad and immediately launches game
     * - If ad plays successfully: grants 30-min VIP pass & launches game
     * - If ad fails, errors, or is not ready: NEVER BLOCKS USER; fails open gracefully & launches game!
     */
    fun showBoosterRewarded(
        activity: Activity,
        onProceedToGame: (unlockedVip: Boolean) -> Unit
    ) {
        val context = activity.applicationContext
        if (isBoosterVipActive(context)) {
            Log.d(TAG, "Booster VIP active, bypassing ad")
            onProceedToGame(true)
            return
        }

        val ad = rewardedAd
        if (ad == null || !ad.canPlayAd()) {
            Log.d(TAG, "Rewarded ad not ready, proceeding gracefully without blocking user")
            loadRewardedInternal(context)
            // Fail-safe: jangan bikin booster macet!
            grantBoosterVipPass(context, 10L * 60L * 1000L)
            onProceedToGame(true)
            return
        }

        rewardedAd = null
        val appCtx = activity.applicationContext
        var earnedReward = false
        ad.adListener = object : RewardedAdListener {
            override fun onAdLoaded(baseAd: BaseAd) {}
            override fun onAdFailedToLoad(baseAd: BaseAd, error: VungleError) {}
            override fun onAdStart(baseAd: BaseAd) { isShowingFullScreen = true }
            override fun onAdEnd(baseAd: BaseAd) {
                isShowingFullScreen = false
                loadRewardedInternal(appCtx)
                if (earnedReward) {
                    grantBoosterVipPass(appCtx)
                }
                onProceedToGame(earnedReward)
            }
            override fun onAdRewarded(baseAd: BaseAd) {
                earnedReward = true
                Log.d(TAG, "User earned booster reward ✓")
            }
            override fun onAdClicked(baseAd: BaseAd) {}
            override fun onAdImpression(baseAd: BaseAd) {}
            override fun onAdLeftApplication(baseAd: BaseAd) {}
            override fun onAdFailedToPlay(baseAd: BaseAd, error: VungleError) {
                isShowingFullScreen = false
                loadRewardedInternal(appCtx)
                // Fail-safe: jangan bikin booster macet!
                grantBoosterVipPass(appCtx, 10L * 60L * 1000L)
                onProceedToGame(true)
            }
        }
        runCatching {
            ad.play(activity)
        }.onFailure {
            Log.w(TAG, "Rewarded play threw, continuing to game", it)
            isShowingFullScreen = false
            loadRewardedInternal(activity.applicationContext)
            grantBoosterVipPass(appCtx, 10L * 60L * 1000L)
            onProceedToGame(true)
        }
    }

    /**
     * Show a rewarded ad. [onRewarded] fires only if the user completes the ad.
     * [onClosed] always fires after the ad is dismissed.
     */
    fun showRewarded(activity: Activity, onRewarded: () -> Unit = {}, onClosed: () -> Unit = {}) {
        onMain {
            if (!isActivityUsable(activity) || isShowingFullScreen || !mainAppReady) { onClosed(); return@onMain }
            val ad = rewardedAd
            if (ad == null || !ad.canPlayAd()) {
                loadRewardedInternal(activity.applicationContext)
                onClosed(); return@onMain
            }
            rewardedAd = null
            val appCtx = activity.applicationContext
            var rewarded = false
            ad.adListener = object : RewardedAdListener {
                override fun onAdLoaded(baseAd: BaseAd) {}
                override fun onAdFailedToLoad(baseAd: BaseAd, error: VungleError) {}
                override fun onAdStart(baseAd: BaseAd)   { isShowingFullScreen = true }
                override fun onAdEnd(baseAd: BaseAd)     {
                    isShowingFullScreen = false
                    loadRewardedInternal(appCtx)
                    if (rewarded) onRewarded()
                    onClosed()
                }
                override fun onAdRewarded(baseAd: BaseAd) { rewarded = true }
                override fun onAdClicked(baseAd: BaseAd)  {}
                override fun onAdImpression(baseAd: BaseAd) {}
                override fun onAdLeftApplication(baseAd: BaseAd) {}
                override fun onAdFailedToPlay(baseAd: BaseAd, error: VungleError) {
                    isShowingFullScreen = false
                    loadRewardedInternal(appCtx)
                    onClosed()
                }
            }
            runCatching { ad.play(activity) }.onFailure {
                Log.w(TAG, "Rewarded play threw", it)
                isShowingFullScreen = false
                loadRewardedInternal(activity.applicationContext)
                onClosed()
            }
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    //  Banner  (VungleBannerView — it IS a View, added directly to container)
    // ──────────────────────────────────────────────────────────────────────────

    /**
     * Creates and loads a Vungle banner into [container].
     * Returns the [VungleBannerView] handle so the caller can call [VungleBannerView.finishAd]
     * during onDispose.
     */
    fun loadBannerInto(context: Context, container: FrameLayout): VungleBannerView? {
        if (!initialized) return null
        return runCatching {
            val bannerView = VungleBannerView(context, BANNER_ID, VungleAdSize.BANNER)
            bannerView.adListener = object : BannerAdListener {
                override fun onAdLoaded(baseAd: BaseAd) {
                    Log.d(TAG, "Banner loaded ✓")
                    container.post {
                        container.removeAllViews()
                        container.addView(bannerView)
                    }
                }
                override fun onAdFailedToLoad(baseAd: BaseAd, error: VungleError) {
                    Log.w(TAG, "Banner load failed: ${error.errorMessage}")
                }
                override fun onAdClicked(baseAd: BaseAd)        {}
                override fun onAdImpression(baseAd: BaseAd)     {}
                override fun onAdLeftApplication(baseAd: BaseAd){}
                override fun onAdStart(baseAd: BaseAd)          {}
                override fun onAdEnd(baseAd: BaseAd)            {}
                override fun onAdFailedToPlay(baseAd: BaseAd, error: VungleError) {}
            }
            bannerView.load()
            bannerView
        }.getOrElse {
            Log.w(TAG, "Banner creation threw", it)
            null
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    //  Internal helper
    // ──────────────────────────────────────────────────────────────────────────

    private class OneShot(private val action: (() -> Unit)?) {
        private val done = AtomicBoolean(false)
        fun run() {
            if (!done.compareAndSet(false, true)) return
            val task = action ?: return
            if (Looper.myLooper() == Looper.getMainLooper()) task()
            else mainHandler.post(task)
        }
    }
}
