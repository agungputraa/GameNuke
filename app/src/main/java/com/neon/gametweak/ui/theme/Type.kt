package com.neon.gametweak.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.neon.gametweak.R

// prepareNukeFonts generates the real Google Fonts resources before resource merging.
val OutfitFamily = FontFamily(
    Font(R.font.outfit_bold, FontWeight.Bold),
    Font(R.font.outfit_semibold, FontWeight.SemiBold),
    Font(R.font.outfit_regular, FontWeight.Normal)
)
val InterFamily = FontFamily(
    Font(R.font.inter_medium, FontWeight.Medium),
    Font(R.font.inter_regular, FontWeight.Normal)
)
private fun heading(size: Int, height: Int, weight: FontWeight = FontWeight.SemiBold) =
    TextStyle(fontFamily = OutfitFamily, fontWeight = weight, fontSize = size.sp, lineHeight = height.sp)
private fun body(size: Int, height: Int, weight: FontWeight = FontWeight.Normal) =
    TextStyle(fontFamily = InterFamily, fontWeight = weight, fontSize = size.sp, lineHeight = height.sp)
val Typography = Typography(
    displayLarge = heading(52, 60, FontWeight.Bold), displayMedium = heading(44, 52), displaySmall = heading(36, 44),
    headlineLarge = heading(30, 38, FontWeight.Bold), headlineMedium = heading(26, 34), headlineSmall = heading(22, 30),
    titleLarge = heading(20, 26), titleMedium = heading(16, 22), titleSmall = heading(14, 20),
    bodyLarge = body(16, 24), bodyMedium = body(14, 20), bodySmall = body(12, 18),
    labelLarge = body(14, 20, FontWeight.Medium), labelMedium = body(12, 16, FontWeight.Medium), labelSmall = body(10, 14, FontWeight.Medium)
)
