package com.neon.gametweak.ui.theme

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.GenericShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

object Neon {
    // Existing identifier retained so the rest of the app receives the new visual system
    // without changing functional call sites.
    val Accent      = NukeGreen
    val AccentDim   = NukeGreenDeep
    val Magenta     = Color(0xFFFF2F78)
    val Danger      = NukeDanger
    val Alert       = NukeWarning
    val Violet      = NukeViolet
    val Cyan        = NukeCyan
    val Bg          = NukeBackground
    val BgRaised    = NukeSurface
    val BgCard      = NukeSurfaceHigh
    val BgCardL     = Color(0xFF111B17)
    val BgInset     = NukeInset
    val Outline     = NukeOutline
    val TextDim     = NukeTextSecondary
}
val HudShape = GenericShape { size, _ ->
    val cut = 18f
    val bite = 7f
    moveTo(cut, 0f)
    lineTo(size.width - bite, 0f)
    lineTo(size.width, bite)
    lineTo(size.width, size.height - cut)
    lineTo(size.width - cut, size.height)
    lineTo(bite, size.height)
    lineTo(0f, size.height - bite)
    lineTo(0f, cut)
    close()
}

val HudShapeSmall = GenericShape { size, _ ->
    val cut = 10f
    moveTo(cut, 0f)
    lineTo(size.width, 0f)
    lineTo(size.width, size.height - cut)
    lineTo(size.width - cut, size.height)
    lineTo(0f, size.height)
    lineTo(0f, cut)
    close()
}

@Composable
fun HudHeader(
    title: String,
    subtitle: String? = null,
    accent: Color = Neon.Accent,
    icon: ImageVector? = null,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .width(3.dp)
                .height(24.dp)
                .background(accent.copy(alpha = 0.72f))
        )
        Spacer(Modifier.width(9.dp))
        if (icon != null) {
            Box(
                modifier = Modifier
                    .size(28.dp)
                    .clip(HudShapeSmall)
                    .background(accent.copy(alpha = 0.08f))
                    .border(1.dp, accent.copy(alpha = 0.24f), HudShapeSmall),
                contentAlignment = Alignment.Center,
            ) {
                Icon(icon, null, tint = accent, modifier = Modifier.size(15.dp))
            }
            Spacer(Modifier.width(8.dp))
        }
        Column {
            Text(
                title,
                color = Color.White,
                fontSize = 13.sp,
                fontWeight = FontWeight.Black,
                letterSpacing = 2.2.sp,
            )
            if (!subtitle.isNullOrBlank()) {
                Text(
                    subtitle,
                    color = accent.copy(alpha = 0.82f),
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium,
                    letterSpacing = 1.2.sp,
                )
            }
        }
        Spacer(Modifier.weight(1f))
        Box(
            Modifier
                .width(42.dp)
                .height(1.dp)
                .background(
                    Brush.horizontalGradient(
                        listOf(Color.Transparent, accent.copy(alpha = 0.42f))
                    )
                )
        )
        Spacer(Modifier.width(6.dp))
        Box(
            Modifier
                .size(5.dp)
                .clip(androidx.compose.foundation.shape.CircleShape)
                .background(accent.copy(alpha = 0.78f))
        )
    }
}

@Composable
fun HudCard(
    accent: Color = Neon.Accent,
    pulsing: Boolean = false,
    background: Color = Neon.BgCard,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    val borderAlpha = if (pulsing) 0.62f else 0.46f
    Column(
        modifier = modifier
            .clip(HudShape)
            .background(
                Brush.linearGradient(
                    listOf(
                        accent.copy(alpha = 0.055f),
                        background,
                        Color(0xFF030A07),
                    )
                )
            )
            .border(1.dp, accent.copy(alpha = borderAlpha * 0.72f), HudShape)
            .padding(14.dp),
        content = content,
    )
}

@Composable
fun HudStatChip(
    label: String,
    value: String,
    accent: Color = Neon.Accent,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .clip(HudShapeSmall)
            .background(Neon.BgCardL)
            .border(1.dp, accent.copy(alpha = 0.35f), HudShapeSmall)
            .padding(horizontal = 10.dp, vertical = 6.dp),
    ) {
        Column {
            Text(
                label,
                color = Neon.TextDim,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace,
                letterSpacing = 2.sp,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                value,
                color = accent,
                fontSize = 14.sp,
                fontWeight = FontWeight.Black,
                fontFamily = FontFamily.Monospace,
            )
        }
    }
}

@Composable
fun HudButton(
    text: String,
    onClick: () -> Unit,
    icon: ImageVector? = null,
    accent: Color = Neon.Accent,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val effectiveAccent = if (enabled) accent else Neon.TextDim
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.height(46.dp),
        shape = HudShapeSmall,
        colors = ButtonDefaults.buttonColors(
            containerColor = effectiveAccent.copy(alpha = 0.085f),
            contentColor = effectiveAccent,
            disabledContainerColor = Neon.BgCardL,
            disabledContentColor = Neon.TextDim,
        ),
        border = androidx.compose.foundation.BorderStroke(1.dp, effectiveAccent.copy(alpha = 0.48f)),
        contentPadding = PaddingValues(horizontal = 14.dp),
    ) {
        if (icon != null) {
            Icon(icon, null, tint = effectiveAccent, modifier = Modifier.size(15.dp))
            Spacer(Modifier.width(6.dp))
        }
        Text(
            text,
            color = effectiveAccent,
            fontSize = 11.sp,
            fontWeight = FontWeight.Black,
            letterSpacing = 2.sp,
            fontFamily = FontFamily.Monospace,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
fun ScanlineBackground(
    modifier: Modifier = Modifier,
    color: Color = Neon.Accent.copy(alpha = 0.04f),
) {
    Canvas(modifier = modifier) {
        val rowH = 6f * density
        val total = (size.height / rowH).toInt() + 2
        val offset = 0f
        for (i in 0 until total) {
            val y = i * rowH - offset
            drawLine(
                color = color,
                start = Offset(0f, y),
                end = Offset(size.width, y),
                strokeWidth = 0.7f * density,
            )
        }
    }
}

@Composable
fun HexGridBackground(
    modifier: Modifier = Modifier,
    color: Color = Neon.Accent.copy(alpha = 0.06f),
) {
    Canvas(modifier = modifier) {
        val r = 16f * density
        val w = r * 1.732f
        val h = r * 1.5f
        var row = 0
        var y = -r
        while (y < size.height + r) {
            val xOff = if (row % 2 == 0) 0f else w / 2f
            var x = -w + xOff
            while (x < size.width + w) {
                drawHex(Offset(x, y), r, color, density)
                x += w
            }
            y += h
            row++
        }
    }
}

private fun DrawScope.drawHex(c: Offset, r: Float, color: Color, density: Float) {
    val sides = 6
    var i = 0
    var prev = Offset(c.x + r, c.y)
    while (i < sides) {
        val angle = (i + 1) * (2 * Math.PI / sides)
        val nx = c.x + r * kotlin.math.cos(angle).toFloat()
        val ny = c.y + r * kotlin.math.sin(angle).toFloat()
        drawLine(color, prev, Offset(nx, ny), strokeWidth = 0.6f * density)
        prev = Offset(nx, ny)
        i++
    }
}

@Composable
fun HudStatusPill(
    label: String,
    online: Boolean,
    accent: Color = Neon.Accent,
) {
    val color = if (online) accent else Neon.Danger
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .clip(HudShapeSmall)
            .background(Neon.BgCardL)
            .border(1.dp, color.copy(alpha = 0.45f), HudShapeSmall)
            .padding(horizontal = 10.dp, vertical = 5.dp),
    ) {
        Box(
            modifier = Modifier
                .size(6.dp)
                .background(color.copy(alpha = if (online) 0.88f else 0.5f), androidx.compose.foundation.shape.CircleShape)
        )
        Spacer(Modifier.width(6.dp))
        Text(
            label,
            color = color,
            fontSize = 9.sp,
            fontWeight = FontWeight.Black,
            letterSpacing = 2.sp,
            fontFamily = FontFamily.Monospace,
        )
    }
}

@Composable
fun NeonDivider(accent: Color = Neon.Accent, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(
                Brush.horizontalGradient(
                    listOf(
                        Color.Transparent,
                        accent.copy(alpha = 0.6f),
                        accent.copy(alpha = 0.1f),
                        Color.Transparent,
                    )
                )
            )
    )
}
