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
import androidx.compose.foundation.shape.RoundedCornerShape
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
    var progress by remember { mutableFloatStateOf(0.15f) }

    val infinite = rememberInfiniteTransition(label = "logo_pulse")
    val pulseAlpha by infinite.animateFloat(
        initialValue = 0.82f,
        targetValue = 1.0f,
        animationSpec = infiniteRepeatable(tween(1100, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "pulseAlpha",
    )

    LaunchedEffect(Unit) {
        delay(120L); progress = 0.50f
        delay(150L); progress = 0.85f
        delay(150L); progress = 1.0f
        delay(160L)
        onFinished()
    }

    val animatedProgress by animateFloatAsState(
        targetValue = progress,
        animationSpec = tween(200, easing = FastOutSlowInEasing),
        label = "progress",
    )
    val configuration = LocalConfiguration.current
    val landscape = configuration.screenWidthDp > configuration.screenHeightDp

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    listOf(
                        Color(0xFF080B11),
                        Color(0xFF0E131E),
                        Color(0xFF06080D),
                    )
                )
            ),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier
                .fillMaxWidth(if (landscape) 0.60f else 0.82f)
                .padding(horizontal = 24.dp),
        ) {
            // Enterprise Logo Container with soft glow
            Box(
                modifier = Modifier
                    .size(if (landscape) 100.dp else 120.dp)
                    .clip(RoundedCornerShape(26.dp))
                    .background(Color(0xFF131826))
                    .border(1.2.dp, Color(0xFF00E5A3).copy(alpha = 0.45f * pulseAlpha), RoundedCornerShape(26.dp))
                    .padding(18.dp),
                contentAlignment = Alignment.Center,
            ) {
                Image(
                    painter = painterResource(R.drawable.logo_nuke),
                    contentDescription = "Game Nuke",
                    modifier = Modifier.fillMaxSize(),
                )
            }

            Spacer(Modifier.height(if (landscape) 12.dp else 20.dp))

            // App Brand Name
            Text(
                text = "GAME NUKE",
                color = Color(0xFFF1F5F9),
                fontSize = if (landscape) 22.sp else 26.sp,
                fontWeight = FontWeight.ExtraBold,
                letterSpacing = 4.sp,
            )

            Spacer(Modifier.height(if (landscape) 22.dp else 36.dp))

            // Minimalist hairline loading indicator (Zero clutter)
            LinearProgressIndicator(
                progress = { animatedProgress },
                modifier = Modifier
                    .fillMaxWidth(if (landscape) 0.40f else 0.48f)
                    .height(2.dp)
                    .clip(RoundedCornerShape(1.dp)),
                color = Color(0xFF00E5A3),
                trackColor = Color(0xFF1E2638),
                strokeCap = ProgressIndicatorDefaults.LinearStrokeCap,
            )
        }
    }
}
