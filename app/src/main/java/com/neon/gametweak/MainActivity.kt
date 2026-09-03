package com.neon.gametweak

import android.Manifest
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.setContent
import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.CutCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.lifecycleScope
import android.util.Log
import android.widget.FrameLayout
import com.vungle.ads.VungleBannerView
import com.neon.gametweak.ui.screens.*
import com.neon.gametweak.ui.theme.NukeEnterpriseTheme
import com.neon.gametweak.ui.theme.Neon
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

val TikTokIcon: ImageVector
    get() = ImageVector.Builder("TikTok", 24.dp, 24.dp, 24f, 24f).apply {
        path(fill = SolidColor(Color.White)) {
            moveTo(19.32f, 7.15f)
            curveToRelative(-1.54f, -0.23f, -2.87f, -0.89f, -3.86f, -1.82f)
            curveToRelative(-0.96f, -0.91f, -1.54f, -2.18f, -1.64f, -3.53f)
            horizontalLineToRelative(-3.43f)
            verticalLineToRelative(13.76f)
            curveToRelative(0.0f, 2.5f, -2.03f, 4.53f, -4.53f, 4.53f)
            reflectiveCurveToRelative(-4.53f, -2.03f, -4.53f, -4.53f)
            reflectiveCurveToRelative(2.03f, -4.53f, 4.53f, -4.53f)
            curveToRelative(0.48f, 0.0f, 0.94f, 0.08f, 1.37f, 0.22f)
            verticalLineToRelative(3.58f)
            curveToRelative(-0.43f, -0.17f, -0.89f, -0.26f, -1.37f, -0.26f)
            curveToRelative(-1.0f, 0.0f, -1.81f, 0.81f, -1.81f, 1.81f)
            reflectiveCurveToRelative(0.81f, 1.81f, 1.81f, 1.81f)
            reflectiveCurveToRelative(1.81f, -0.81f, 1.81f, -1.81f)
            verticalLineTo(2.0f)
            horizontalLineToRelative(3.43f)
            curveToRelative(0.06f, 1.82f, 0.81f, 3.45f, 2.05f, 4.63f)
            curveToRelative(1.23f, 1.17f, 2.91f, 1.88f, 4.76f, 1.95f)
            verticalLineToRelative(3.47f)
            curveToRelative(-1.3f, -0.06f, -2.53f, -0.42f, -3.6f, -0.98f)
            close()
        }
    }.build()

val InstagramIcon: ImageVector
    get() = ImageVector.Builder("Instagram", 24.dp, 24.dp, 24f, 24f).apply {
        path(fill = SolidColor(Color.White)) {
            moveTo(7.8f, 2.0f)
            horizontalLineToRelative(8.4f)
            curveTo(19.4f, 2.0f, 22.0f, 4.6f, 22.0f, 7.8f)
            verticalLineToRelative(8.4f)
            curveToRelative(0.0f, 3.2f, -2.6f, 5.8f, -5.8f, 5.8f)
            horizontalLineTo(7.8f)
            curveTo(4.6f, 22.0f, 2.0f, 19.4f, 2.0f, 16.2f)
            verticalLineTo(7.8f)
            curveTo(2.0f, 4.6f, 4.6f, 2.0f, 7.8f, 2.0f)
            close()
            moveTo(12.0f, 7.3f)
            curveToRelative(-2.6f, 0.0f, -4.7f, 2.1f, -4.7f, 4.7f)
            reflectiveCurveToRelative(2.1f, 4.7f, 4.7f, 4.7f)
            reflectiveCurveToRelative(4.7f, -2.1f, 4.7f, -4.7f)
            reflectiveCurveToRelative(-2.1f, -4.7f, -4.7f, -4.7f)
            close()
            moveTo(12.0f, 14.8f)
            curveToRelative(-1.5f, 0.0f, -2.8f, -1.3f, -2.8f, -2.8f)
            reflectiveCurveToRelative(1.3f, -2.8f, 2.8f, -2.8f)
            reflectiveCurveToRelative(2.8f, 1.3f, 2.8f, 2.8f)
            reflectiveCurveToRelative(-1.3f, 2.8f, -2.8f, 2.8f)
            close()
            moveTo(17.3f, 5.3f)
            curveToRelative(-0.7f, 0.0f, -1.3f, 0.6f, -1.3f, 1.3f)
            reflectiveCurveToRelative(0.6f, 1.3f, 1.3f, 1.3f)
            reflectiveCurveToRelative(1.3f, -0.6f, 1.3f, -1.3f)
            reflectiveCurveToRelative(-0.6f, -1.3f, -1.3f, -1.3f)
            close()
        }
    }.build()

val YouTubeIcon: ImageVector
    get() = ImageVector.Builder("YouTube", 24.dp, 24.dp, 24f, 24f).apply {
        path(fill = SolidColor(Color(0xFFFF0033))) {
            moveTo(21.58f, 7.19f)
            curveToRelative(-0.23f, -0.86f, -0.91f, -1.54f, -1.77f, -1.77f)
            curveTo(18.25f, 5f, 12f, 5f, 12f, 5f)
            reflectiveCurveToRelative(-6.25f, 0f, -7.81f, 0.42f)
            curveToRelative(-0.86f, 0.23f, -1.54f, 0.91f, -1.77f, 1.77f)
            curveTo(2f, 8.75f, 2f, 12f, 2f, 12f)
            reflectiveCurveToRelative(0f, 3.25f, 0.42f, 4.81f)
            curveToRelative(0.23f, 0.86f, 0.91f, 1.54f, 1.77f, 1.77f)
            curveTo(5.75f, 19f, 12f, 19f, 12f, 19f)
            reflectiveCurveToRelative(6.25f, 0f, 7.81f, -0.42f)
            curveToRelative(0.86f, -0.23f, 1.54f, -0.91f, 1.77f, -1.77f)
            curveTo(22f, 15.25f, 22f, 12f, 22f, 12f)
            reflectiveCurveToRelative(0f, -3.25f, -0.42f, -4.81f)
            close()
        }
        path(fill = SolidColor(Color.White)) {
            moveTo(10f, 15.5f)
            lineTo(10f, 8.5f)
            lineTo(16f, 12f)
            close()
        }
    }.build()

class MainActivity : ComponentActivity() {
    private var integrityCheckScheduled = false
    private val adbSetupPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) {
        openDeveloperOptionsNow()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        runCatching { AppUpdateController.register(this) }

        val adbManager = AdbManager.getInstance(this)

        setContent {
            NukeEnterpriseTheme {
                MainAppHost(adbManager) { openDeveloperOptions() }
            }
        }

        // First-frame-first bootstrap: avoid competing with Compose/layout on cold launch.
        window.decorView.postDelayed({
            if (!isFinishing && !isDestroyed) {
                runCatching { NukeAdbOrchestrator.start(applicationContext) }
                scheduleDisplayRecovery()
                runCatching { AppUpdateController.check(this) }
                runCatching {
                    ConsentManager.gatherConsent(this) { canRequestAds ->
                        if (canRequestAds && !isFinishing && !isDestroyed) {
                            NukeAdManager.initialize(applicationContext)
                            NukeAdManager.preload(applicationContext)
                        }
                    }
                }
            }
        }, 700L)
    }

    private fun scheduleDisplayRecovery() {
        if (!NukeDisplayProfileController.hasOwnedOverride(applicationContext)) return
        lifecycleScope.launch(Dispatchers.IO) {
            repeat(4) { attempt ->
                delay(700L + attempt * 650L)
                val sessionHandoffAlive = NukeRuntimeState.state.value.overlayRunning || NukeRuntimeState.isLaunchHandoffActive()
                if (sessionHandoffAlive) return@launch
                val result = NukeDisplayProfileController.recoverStaleOverride(
                    applicationContext,
                    overlayRunningInProcess = sessionHandoffAlive,
                )
                when (result.outcome) {
                    NukeDisplayProfileController.Outcome.SUCCESS -> {
                        NukeToast.success(applicationContext, "Recovered display settings after an interrupted session", true)
                        return@launch
                    }
                    NukeDisplayProfileController.Outcome.UNCHANGED -> return@launch
                    NukeDisplayProfileController.Outcome.ERROR -> {
                        NukeToast.error(applicationContext, result.message, true)
                        return@launch
                    }
                    NukeDisplayProfileController.Outcome.UNSUPPORTED,
                    NukeDisplayProfileController.Outcome.DEFERRED -> Unit
                }
            }
            Log.w("GameNuke", "Display recovery is still deferred; trusted core not available yet")
        }
    }

    private fun openDeveloperOptions() {
        val needed = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                needed += Manifest.permission.POST_NOTIFICATIONS
            }
            if (checkSelfPermission(Manifest.permission.NEARBY_WIFI_DEVICES) != PackageManager.PERMISSION_GRANTED) {
                needed += Manifest.permission.NEARBY_WIFI_DEVICES
            }
        }
        if (needed.isNotEmpty()) adbSetupPermissionLauncher.launch(needed.toTypedArray())
        else openDeveloperOptionsNow()
    }

    private fun openDeveloperOptionsNow() {
        try {
            startActivity(Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS).apply { flags = Intent.FLAG_ACTIVITY_NEW_TASK })
        } catch (e: ActivityNotFoundException) {
            try {
                startActivity(Intent(Settings.ACTION_SETTINGS).apply { flags = Intent.FLAG_ACTIVITY_NEW_TASK })
            } catch (ex: Exception) {
                Log.w("GameNuke", "Unable to open Android settings", ex)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        NukeAdManager.mainAppReady = true
        if (!integrityCheckScheduled) {
            integrityCheckScheduled = true
            lifecycleScope.launch(Dispatchers.Default) {
                val result = runCatching { IntegrityGuard.check(applicationContext) }.getOrNull()
                if (result?.compromised == true) NukeAdManager.mainAppReady = false
            }
        }
    }

    override fun onDestroy() {
        runCatching { AppUpdateController.unregister() }
        super.onDestroy()
    }
}

@Composable
fun BlankFallback(message: String) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF020705)),
        contentAlignment = Alignment.Center
    ) {
        Text(message, color = Color(0xFF35C99B), fontWeight = FontWeight.Bold)
    }
}

@Composable
fun BannerAdView() {
    val context = LocalContext.current
    if (!ConsentManager.canRequestAds()) return
    val lifecycleOwner = LocalLifecycleOwner.current
    val container = remember { FrameLayout(context) }
    // Hold the VungleBannerView handle so we can clean it up on dispose
    val bannerAdRef = remember { mutableStateOf<VungleBannerView?>(null) }

    LaunchedEffect(Unit) {
        if (!NukeAdManager.initialized) return@LaunchedEffect
        val ad = NukeAdManager.loadBannerInto(context, container)
        bannerAdRef.value = ad
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_DESTROY -> {
                    runCatching { bannerAdRef.value?.finishAd() }
                    bannerAdRef.value = null
                }
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            runCatching { bannerAdRef.value?.finishAd() }
        }
    }

    AndroidView(
        modifier = Modifier.fillMaxWidth(),
        factory = { container },
        update = { }
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainAppHost(adbManager: AdbManager, onOpenDevOptions: () -> Unit) {
    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    val coroutineScope = rememberCoroutineScope()
    val context = LocalContext.current
    var currentRoute by remember { mutableStateOf("dashboard") }
    var showLangMenu by remember { mutableStateOf(false) }
    var showAdvanced by remember { mutableStateOf(false) }
    var webServer by remember { mutableStateOf<LocalWebServer?>(null) }
    var showManualAdBlockDialog by remember { mutableStateOf(false) }
    var manualAdBlockStatus by remember { mutableStateOf(AdBlockStatus()) }

    if (showManualAdBlockDialog && manualAdBlockStatus.isDetected) {
        NukeAdBlockDetectedDialog(
            status = manualAdBlockStatus,
            adbManager = adbManager,
            onDismiss = { showManualAdBlockDialog = false },
        )
    }

    val exportAdbIdentity = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/zip"),
    ) { uri ->
        if (uri != null) {
            coroutineScope.launch {
                val result = kotlinx.coroutines.withContext(Dispatchers.IO) { AdbIdentityBackup.exportTo(context, uri) }
                NukeToast.fromResult(context, result.success, result.message, long = true)
            }
        }
    }
    val restoreAdbIdentity = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) {
            coroutineScope.launch {
                val result = kotlinx.coroutines.withContext(Dispatchers.IO) { AdbIdentityBackup.importFrom(context, uri) }
                NukeToast.fromResult(context, result.success, result.message, long = true)
                if (result.success) NukeAdbOrchestrator.kick("identity-restored")
            }
        }
    }

    // The embedded local server is only created when the user actually opens Web UI.
    LaunchedEffect(currentRoute) {
        if (currentRoute == "webui" && webServer == null) {
            webServer = kotlinx.coroutines.withContext(Dispatchers.IO) {
                runCatching { LocalWebServer.getInstance(context) }.getOrNull()
            }
        }
    }

    fun navigateWithAd(route: String) {
        if (currentRoute == route) return
        val activity = context.findActivity()
        if (activity == null) {
            currentRoute = route
            return
        }
        // Natural screen transition: navigate when the ad is dismissed, or immediately when no ad is due.
        NukeAdManager.showInterstitial(activity) { currentRoute = route }
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            Surface(modifier = Modifier.width(320.dp).fillMaxHeight(), color = Color(0xFF020705)) {
                Column(modifier = Modifier.fillMaxSize().systemBarsPadding()) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(220.dp)
                            .background(
                                androidx.compose.ui.graphics.Brush.verticalGradient(
                                    listOf(
                                        Color(0xFF06251B).copy(alpha = 0.45f),
                                        Color(0xFF0A0A12),
                                    )
                                )
                            ),
                        contentAlignment = Alignment.Center,
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Box(
                                modifier = Modifier
                                    .size(86.dp)
                                    .clip(CutCornerShape(topStart = 18.dp, bottomEnd = 18.dp))
                                    .background(Color(0xFF08140F))
                                    .border(1.dp, Color(0xFF35C99B).copy(alpha = 0.52f), CutCornerShape(topStart = 18.dp, bottomEnd = 18.dp)),
                                contentAlignment = Alignment.Center,
                            ) {
                                Image(
                                    painter = androidx.compose.ui.res.painterResource(id = R.drawable.logo_nuke),
                                    contentDescription = "Game Nuke",
                                    modifier = Modifier
                                        .size(64.dp)
                                        .clip(CutCornerShape(topStart = 10.dp, bottomEnd = 10.dp)),
                                )
                            }
                            Spacer(modifier = Modifier.height(14.dp))
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Box(Modifier.width(20.dp).height(2.dp).background(Color(0xFF35C99B)))
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    "GAME NUKE",
                                    color = Color.White,
                                    fontSize = 18.sp,
                                    fontWeight = FontWeight.Black,
                                    letterSpacing = 3.sp,
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Box(Modifier.width(20.dp).height(2.dp).background(Color(0xFF35C99B)))
                            }
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                "ENTERPRISE EDITION · v${BuildConfig.VERSION_NAME}",
                                color = Color(0xFF35C99B),
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                letterSpacing = 2.sp,
                                fontFamily = androidx.compose.ui.text.font.FontFamily.SansSerif,
                            )
                        }
                    }

                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(1.dp)
                            .background(
                                androidx.compose.ui.graphics.Brush.horizontalGradient(
                                    listOf(
                                        Color.Transparent,
                                        Color(0xFF35C99B).copy(alpha = 0.5f),
                                        Color.Transparent,
                                    )
                                )
                            ),
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        "  NAVIGATION",
                        color = Color(0xFF9BB0A6),
                        fontSize = 10.sp,
                        fontFamily = androidx.compose.ui.text.font.FontFamily.SansSerif,
                        letterSpacing = 2.sp,
                        modifier = Modifier.padding(start = 24.dp, top = 6.dp, bottom = 4.dp),
                    )
                    DrawerItem(Icons.Rounded.Dns, Tx.t("Web Server & REST API", "Web Server & REST API")) {
                        navigateWithAd("webui")
                        coroutineScope.launch { drawerState.close() }
                    }
                    DrawerItem(Icons.Rounded.LibraryBooks, Tx.t("Dokumentasi", "Documentation")) {
                        navigateWithAd("tutorial")
                        coroutineScope.launch { drawerState.close() }
                    }
                    DrawerItem(Icons.Rounded.PersonSearch, Tx.t("Tentang Pengembang", "About Developer")) {
                        navigateWithAd("dev")
                        coroutineScope.launch { drawerState.close() }
                    }
                    DrawerItem(Icons.Rounded.Security, Tx.t("Status Integritas Jaringan", "Network Integrity Status")) {
                        coroutineScope.launch {
                            drawerState.close()
                            val status = withContext(Dispatchers.IO) {
                                NukeAdBlockDetector.checkStatus(context, adbManager)
                            }
                            manualAdBlockStatus = status
                            if (status.isDetected) {
                                showManualAdBlockDialog = true
                            } else {
                                NukeToast.success(context, Tx.t("Status jaringan normal. Seluruh tools aktif.", "Network status normal. All tools operational."))
                            }
                        }
                    }
                    if (ConsentManager.isPrivacyOptionsRequired(context)) {
                        DrawerItem(Icons.Rounded.PrivacyTip, Tx.t("Privasi Iklan", "Ad Privacy")) {
                            coroutineScope.launch { drawerState.close() }
                            context.findActivity()?.let { ConsentManager.showPrivacyOptionsForm(it) }
                        }
                    }
                    DrawerItem(Icons.Rounded.SystemUpdate, Tx.t("Cek Pembaruan", "Check Update")) {
                        coroutineScope.launch { drawerState.close() }
                        context.findActivity()?.let { activity ->
                            NukeToast.success(activity, Tx.t("Memeriksa pembaruan sistem...", "Checking for updates..."))
                            AppUpdateController.startFlexibleUpdate(activity)
                        }
                    }
                    DrawerItem(Icons.Rounded.Verified, Tx.t("Beri Rating", "Rate App")) {
                        coroutineScope.launch { drawerState.close() }
                        context.findActivity()?.let { activity ->
                            NukeToast.success(activity, Tx.t("Membuka halaman ulasan...", "Opening review page..."))
                            AppUpdateController.launchInAppReview(activity)
                        }
                    }

                    Spacer(modifier = Modifier.weight(1f))
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(1.dp)
                            .background(
                                androidx.compose.ui.graphics.Brush.horizontalGradient(
                                    listOf(
                                        Color.Transparent,
                                        Color(0xFF35C99B).copy(alpha = 0.3f),
                                        Color.Transparent,
                                    )
                                )
                            ),
                    )
                    Column(modifier = Modifier.fillMaxWidth().padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            "ENGINEERED BY",
                            color = Color(0xFF9FB3AA),
                            fontSize = 9.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 2.sp,
                            fontFamily = androidx.compose.ui.text.font.FontFamily.SansSerif,
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            "AGUNG · DEV",
                            color = Color(0xFF35C99B),
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Black,
                            letterSpacing = 3.sp,
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
                            SocialButton(TikTokIcon, "https://tiktok.com/@gamenukeofficial", brandTint = Color.White, borderAccent = Color(0xFF25F4EE))
                            SocialButton(InstagramIcon, "https://www.instagram.com/agungeka_22?igsh=dmo4YnR4dTF3cnhq", brandTint = Color.Unspecified, borderAccent = Color(0xFFE1306C))
                            SocialButton(YouTubeIcon, "https://youtube.com/@neoncoreofficialpro?si=g051yTnkdx3A3Hmi", brandTint = Color.Unspecified, borderAccent = Color(0xFFFF0033))
                        }
                    }
                }
            }
        }
    ) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = {
                        AnimatedContent(
                            targetState = currentRoute,
                            transitionSpec = { fadeIn(animationSpec = tween(220)) togetherWith fadeOut(animationSpec = tween(180)) },
                            label = "TitleAnimation",
                        ) { route ->
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Box(
                                    Modifier
                                        .width(3.dp)
                                        .height(34.dp)
                                        .background(Color(0xFF35C99B))
                                )
                                Spacer(Modifier.width(10.dp))
                                Column {
                                    Text(
                                        "GAME NUKE // SYSTEM",
                                        color = Color(0xFF797983),
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 11.sp,
                                        letterSpacing = 1.6.sp,
                                        fontFamily = androidx.compose.ui.text.font.FontFamily.SansSerif,
                                    )
                                    Text(
                                        when (route) {
                                            "dashboard" -> Tx.t("Pusat Komando", "Command Center")
                                            "games" -> Tx.t("Profil Game", "Game Profiles")
                                            "cleaner" -> Tx.t("Pembersih Sistem", "Deep Wipe")
                                            "processes" -> Tx.t("Manajer Tugas", "Task Manager")
                                            "exec" -> Tx.t("Diagnostik", "Diagnostics")
                                            "webui" -> Tx.t("Server Lokal", "Local Server")
                                            "dev" -> "Agung Dev"
                                            else -> Tx.t("Dokumentasi", "Documentation")
                                        },
                                        fontWeight = FontWeight.Black,
                                        color = Color.White,
                                        fontSize = 17.sp,
                                        letterSpacing = 0.7.sp,
                                    )
                                }
                            }
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = Color(0xFF020705)),
                    navigationIcon = {
                        IconButton(onClick = { coroutineScope.launch { drawerState.open() } }) {
                            Icon(Icons.Rounded.Sort, contentDescription = "Menu", tint = Color.White, modifier = Modifier.size(28.dp))
                        }
                    },
                    actions = {
                        Box {
                            IconButton(onClick = { showLangMenu = true }) {
                                Icon(Icons.Rounded.Translate, contentDescription = "Translate", tint = Color(0xFF35C99B), modifier = Modifier.size(24.dp))
                            }
                            DropdownMenu(
                                expanded = showLangMenu,
                                onDismissRequest = { showLangMenu = false },
                                modifier = Modifier.background(Color(0xFF121212))
                            ) {
                                Tx.supportedLangs.forEach { (code, label) ->
                                    DropdownMenuItem(
                                        text = { Text(label, color = if (Tx.currentLang == code) Color(0xFF35C99B) else Color.White, fontWeight = FontWeight.Bold) },
                                        onClick = {
                                            Tx.setLang(code)
                                            context.getSharedPreferences("NukePrefs", Context.MODE_PRIVATE)
                                                .edit().putString("hud_lang", code).apply()
                                            NukeRuntimeState.update { it.copy(language = code) }
                                            showLangMenu = false
                                        }
                                    )
                                }
                            }
                        }
                    },
                    modifier = Modifier.statusBarsPadding()
                )
            },
            bottomBar = {
                Column {
                    Box(
                        modifier = Modifier.fillMaxWidth().height(1.dp).background(
                            androidx.compose.ui.graphics.Brush.horizontalGradient(
                                listOf(Color.Transparent, Color(0xFF35C99B).copy(alpha = 0.5f), Color(0xFF35C99B).copy(alpha = 0.5f), Color.Transparent)
                            )
                        )
                    )
                    NavigationBar(containerColor = Color(0xFF0A0A0A), tonalElevation = 0.dp, modifier = Modifier.height(80.dp)) {
                    NavigationBarItem(
                        selected = currentRoute == "dashboard",
                        onClick = { navigateWithAd("dashboard") },
                        icon = { Icon(Icons.Rounded.Speed, contentDescription = null, modifier = Modifier.size(26.dp)) },
                        label = { Text(Tx.t("Core", "Core"), fontSize = 11.sp, fontWeight = FontWeight.Bold) },
                        colors = NavigationBarItemDefaults.colors(selectedIconColor = Color.White, selectedTextColor = Color(0xFF35C99B), indicatorColor = Color(0xFF35C99B).copy(alpha = 0.14f), unselectedIconColor = Color(0xFF74747D), unselectedTextColor = Color(0xFF74747D))
                    )
                    NavigationBarItem(
                        selected = currentRoute == "games",
                        onClick = { navigateWithAd("games") },
                        icon = { Icon(Icons.Rounded.Gamepad, contentDescription = null, modifier = Modifier.size(26.dp)) },
                        label = { Text(Tx.t("Games", "Games"), fontSize = 11.sp, fontWeight = FontWeight.Bold) },
                        colors = NavigationBarItemDefaults.colors(selectedIconColor = Color.White, selectedTextColor = Color(0xFF35C99B), indicatorColor = Color(0xFF35C99B).copy(alpha = 0.14f), unselectedIconColor = Color(0xFF74747D), unselectedTextColor = Color(0xFF74747D))
                    )
                    NavigationBarItem(
                        selected = currentRoute == "cleaner",
                        onClick = { navigateWithAd("cleaner") },
                        icon = { Icon(Icons.Rounded.CleaningServices, contentDescription = null, modifier = Modifier.size(26.dp)) },
                        label = { Text(Tx.t("Optimize", "Optimize"), fontSize = 11.sp, fontWeight = FontWeight.Bold) },
                        colors = NavigationBarItemDefaults.colors(selectedIconColor = Color.White, selectedTextColor = Color(0xFF35C99B), indicatorColor = Color(0xFF35C99B).copy(alpha = 0.14f), unselectedIconColor = Color(0xFF74747D), unselectedTextColor = Color(0xFF74747D))
                    )
                    NavigationBarItem(
                        selected = currentRoute == "processes",
                        onClick = { navigateWithAd("processes") },
                        icon = { Icon(Icons.Rounded.Memory, contentDescription = null, modifier = Modifier.size(26.dp)) },
                        label = { Text(Tx.t("Monitor", "Monitor"), fontSize = 11.sp, fontWeight = FontWeight.Bold) },
                        colors = NavigationBarItemDefaults.colors(selectedIconColor = Color.White, selectedTextColor = Color(0xFF35C99B), indicatorColor = Color(0xFF35C99B).copy(alpha = 0.14f), unselectedIconColor = Color(0xFF74747D), unselectedTextColor = Color(0xFF74747D))
                    )
                }
                }
            },
            containerColor = Color(0xFF020705),
            modifier = Modifier.navigationBarsPadding()
        ) { innerPadding ->
            Column(modifier = Modifier.padding(innerPadding).fillMaxSize()) {
                Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                    AnimatedContent(
                        targetState = currentRoute,
                        transitionSpec = {
                            slideInHorizontally(initialOffsetX = { it / 3 }, animationSpec = tween(180)) + fadeIn(animationSpec = tween(150)) togetherWith
                            slideOutHorizontally(targetOffsetX = { -it / 3 }, animationSpec = tween(180)) + fadeOut(animationSpec = tween(120))
                        },
                        label = "ScreenTransition"
                    ) { route ->
                        when (route) {
                            "dashboard" -> DashboardScreen(
                                adbManager = adbManager,
                                onOpenDevOptions = onOpenDevOptions,
                                onOpenGames = { navigateWithAd("games") },
                                onOpenCleaner = { navigateWithAd("cleaner") },
                                onOpenMonitor = { navigateWithAd("processes") },
                            )
                            "games" -> GameProfileScreen(adbManager)
                            "cleaner" -> CleanerScreen(adbManager)
                            "processes" -> ProcessManagerScreen(adbManager)
                            "exec" -> DiagnosticsConsoleScreen(adbManager)
                            "webui" -> {
                                val server = webServer
                                if (server != null) WebUiScreen(server)
                                else BlankFallback("Local server initializing...")
                            }
                            "dev" -> DevScreen()
                            "tutorial" -> TutorialScreen()
                        }
                    }
                }
                // Keep banners on high-value passive screens only. Do not cover diagnostics, Web UI,
                // developer, or tutorial workflows where persistent ads are distracting.
                if (currentRoute in setOf("dashboard", "games", "cleaner", "processes")) {
                    Column(modifier = Modifier.fillMaxWidth().background(Color(0xFF020705))) {
                        Box(
                            modifier = Modifier.fillMaxWidth().height(1.dp).background(
                                androidx.compose.ui.graphics.Brush.horizontalGradient(
                                    listOf(Color.Transparent, Color(0xFF35C99B).copy(alpha = 0.28f), Color.Transparent)
                                )
                            )
                        )
                        BannerAdView()
                    }
                }
            }
        }
    }

}

@Composable
fun DrawerItem(icon: ImageVector, title: String, onClick: () -> Unit) {
    val accent = Color(0xFF35C99B)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 14.dp, horizontal = 24.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(2.dp, 18.dp)
                .background(accent),
        )
        Spacer(modifier = Modifier.width(14.dp))
        Box(
            modifier = Modifier
                .size(36.dp)
                .background(Color(0xFF111118), CutCornerShape(topStart = 8.dp, bottomEnd = 8.dp))
                .border(1.dp, accent.copy(alpha = 0.25f), CutCornerShape(topStart = 8.dp, bottomEnd = 8.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = null, tint = accent, modifier = Modifier.size(18.dp))
        }
        Spacer(modifier = Modifier.width(14.dp))
        Text(
            title,
            color = Color.White,
            fontSize = 14.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 0.5.sp,
        )
        Spacer(modifier = Modifier.weight(1f))
        Icon(
            Icons.Rounded.ChevronRight,
            contentDescription = null,
            tint = Color(0xFF555555),
            modifier = Modifier.size(18.dp),
        )
    }
}

@Composable
fun SocialButton(icon: ImageVector, url: String, brandTint: Color = Color.White, borderAccent: Color = Color(0xFF35C99B)) {
    val uriHandler = LocalUriHandler.current
    Box(
        modifier = Modifier
            .size(width = 66.dp, height = 48.dp)
            .clip(CutCornerShape(topStart = 8.dp, bottomEnd = 8.dp))
            .background(Color(0xFF0C1411))
            .border(1.2.dp, borderAccent.copy(alpha = 0.5f), CutCornerShape(topStart = 8.dp, bottomEnd = 8.dp))
            .clickable { runCatching { uriHandler.openUri(url) } },
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, tint = brandTint, modifier = Modifier.size(26.dp))
    }
}
