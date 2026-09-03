package com.neon.gametweak

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ProgressIndicatorDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import com.neon.gametweak.ui.theme.HexGridBackground
import com.neon.gametweak.ui.theme.HudShape
import com.neon.gametweak.ui.theme.Neon
import kotlinx.coroutines.delay

/**
 * Lightweight launch surface. Ads, ADB discovery, integrity scans and the local web server are not
 * started here anymore; they are deferred until after the MainActivity has rendered its first frame.
 */
class SplashActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)

        setContent {
            MaterialTheme(
                colorScheme = darkColorScheme(
                    primary = Neon.Accent,
                    background = Neon.Bg,
                    surface = Neon.BgRaised,
                ),
            ) {
                FastNukeSplash {
                    runCatching {
                        startActivity(
                            Intent(this@SplashActivity, MainActivity::class.java).apply {
                                addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
                            },
                        )
                        @Suppress("DEPRECATION")
                        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
                    }
                    finish()
                }
            }
        }
    }
}

@Composable
private fun FastNukeSplash(onFinished: () -> Unit) {
    val infinite = rememberInfiniteTransition(label = "splash_ring")
    val sweep by infinite.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(2200, easing = LinearEasing), RepeatMode.Restart),
        label = "sweep",
    )
    var progress by remember { mutableFloatStateOf(.08f) }
    var status by remember { mutableStateOf("BOOT CORE") }

    LaunchedEffect(Unit) {
        // Four coarse updates instead of dozens of 22 ms recompositions.
        delay(120L); status = "LOAD INTERFACE"; progress = .36f
        delay(170L); status = "INIT SESSION"; progress = .68f
        delay(170L); status = "READY"; progress = 1f
        delay(260L)
        onFinished()
    }

    val animatedProgress by animateFloatAsState(
        targetValue = progress,
        animationSpec = tween(180, easing = FastOutSlowInEasing),
        label = "progress",
    )
    val configuration = LocalConfiguration.current
    val landscape = configuration.screenWidthDp > configuration.screenHeightDp

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.radialGradient(
                    colors = listOf(Color(0xFF0A2A20), Neon.Bg, Color(0xFF020403)),
                    radius = with(LocalDensity.current) { 520.dp.toPx() },
                ),
            ),
        contentAlignment = Alignment.Center,
    ) {
        // Static grid only. The old moving scanline + multi-animation stack was intentionally removed.
        HexGridBackground(Modifier.fillMaxSize())

        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(if (landscape) 12.dp else 18.dp),
            modifier = Modifier.fillMaxWidth(if (landscape) .68f else .88f).padding(horizontal = 22.dp),
        ) {
            Box(
                modifier = Modifier.size(if (landscape) 142.dp else 178.dp),
                contentAlignment = Alignment.Center,
            ) {
                Canvas(Modifier.fillMaxSize()) {
                    val stroke = 2.2.dp.toPx()
                    val pad = 8.dp.toPx()
                    drawArc(
                        color = Neon.Accent.copy(alpha = .78f),
                        startAngle = sweep,
                        sweepAngle = 118f,
                        useCenter = false,
                        topLeft = Offset(pad, pad),
                        size = Size(size.width - pad * 2f, size.height - pad * 2f),
                        style = Stroke(stroke),
                    )
                    val inner = 17.dp.toPx()
                    drawArc(
                        color = Color(0xFF8C6CFF).copy(alpha = .42f),
                        startAngle = 360f - sweep * .72f,
                        sweepAngle = 82f,
                        useCenter = false,
                        topLeft = Offset(inner, inner),
                        size = Size(size.width - inner * 2f, size.height - inner * 2f),
                        style = Stroke(1.2.dp.toPx()),
                    )
                }
                Box(
                    modifier = Modifier
                        .fillMaxSize(.58f)
                        .clip(CircleShape)
                        .background(Color(0xFF07100D))
                        .border(1.dp, Neon.Accent.copy(alpha = .52f), CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Image(
                        painter = painterResource(R.drawable.logo_nuke),
                        contentDescription = "Game Nuke",
                        modifier = Modifier.fillMaxSize(.76f).clip(CircleShape),
                    )
                }
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.width(3.dp).height(if (landscape) 22.dp else 28.dp).background(Neon.Accent))
                Spacer(Modifier.width(10.dp))
                Text(
                    "GAME NUKE",
                    color = Color.White,
                    fontSize = if (landscape) 21.sp else 27.sp,
                    fontWeight = FontWeight.Black,
                    letterSpacing = 5.sp,
                )
            }
            Text(
                "ENTERPRISE GAMING CORE  //  v${BuildConfig.VERSION_NAME}",
                color = Neon.Accent,
                fontSize = 9.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 2.4.sp,
                fontFamily = FontFamily.Monospace,
            )

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(HudShape)
                    .background(Neon.BgCard)
                    .border(1.dp, Neon.Accent.copy(alpha = .28f), HudShape)
                    .padding(horizontal = 15.dp, vertical = 11.dp),
                verticalArrangement = Arrangement.spacedBy(7.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(7.dp).background(Neon.Accent, CircleShape))
                    Spacer(Modifier.width(8.dp))
                    Text(status, color = Color.White, fontSize = 9.sp, fontWeight = FontWeight.Black, letterSpacing = 1.2.sp)
                }
                LinearProgressIndicator(
                    progress = { animatedProgress },
                    modifier = Modifier.fillMaxWidth().height(3.dp),
                    color = Neon.Accent,
                    trackColor = Color(0xFF16231E),
                    strokeCap = ProgressIndicatorDefaults.LinearStrokeCap,
                )
            }
        }
    }
}
