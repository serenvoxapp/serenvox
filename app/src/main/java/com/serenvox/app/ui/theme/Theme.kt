// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright 2026 Ryan Lu
package com.serenvox.app.ui.theme

import android.os.Build
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.serenvox.app.R

val Inter = FontFamily(
    Font(R.font.inter_regular, FontWeight.Normal),
    Font(R.font.inter_medium, FontWeight.Medium),
    Font(R.font.inter_semibold, FontWeight.SemiBold),
    Font(R.font.inter_bold, FontWeight.Bold),
)

private val Light = lightColorScheme(
    primary = Color(0xFF6B4EFF), onPrimary = Color.White,
    primaryContainer = Color(0xFFECE7FF), onPrimaryContainer = Color(0xFF22125E),
    secondary = Color(0xFF8C7BD8), onSecondary = Color.White,
    secondaryContainer = Color(0xFFF1EEFB), onSecondaryContainer = Color(0xFF2C2350),
    tertiary = Color(0xFFE5739A), tertiaryContainer = Color(0xFFFFE3EC),
    background = Color(0xFFFFFFFF), onBackground = Color(0xFF15131D),
    surface = Color(0xFFFFFFFF), onSurface = Color(0xFF15131D),
    surfaceVariant = Color(0xFFF4F2FA), onSurfaceVariant = Color(0xFF615D72),
    surfaceContainerLowest = Color.White, surfaceContainerLow = Color(0xFFFAF9FE),
    surfaceContainer = Color(0xFFF6F4FC), surfaceContainerHigh = Color(0xFFF0EEF8),
    surfaceContainerHighest = Color(0xFFEAE7F4),
    outline = Color(0xFFD9D5E6), outlineVariant = Color(0xFFEAE7F2),
)

private val Dark = darkColorScheme(
    primary = Color(0xFFB8A8FF), onPrimary = Color(0xFF1F0F66),
    primaryContainer = Color(0xFF3B2E86), onPrimaryContainer = Color(0xFFE8E2FF),
    secondary = Color(0xFFC9BEF5), secondaryContainer = Color(0xFF2B2640), onSecondaryContainer = Color(0xFFE7E1FF),
    tertiary = Color(0xFFFF9EBE), tertiaryContainer = Color(0xFF5A2A3C),
    background = Color(0xFF0D0C12), onBackground = Color(0xFFEDEAF5),
    surface = Color(0xFF0D0C12), onSurface = Color(0xFFEDEAF5),
    surfaceVariant = Color(0xFF1C1A25), onSurfaceVariant = Color(0xFFB2ADC4),
    surfaceContainerLowest = Color(0xFF0A090E), surfaceContainerLow = Color(0xFF15131C),
    surfaceContainer = Color(0xFF19171F), surfaceContainerHigh = Color(0xFF221F2B),
    surfaceContainerHighest = Color(0xFF2B2836),
    outline = Color(0xFF3A3648), outlineVariant = Color(0xFF2A2735),
)

private fun t(size: Int, weight: FontWeight, line: Int, ls: Double = 0.0) =
    TextStyle(fontFamily = Inter, fontWeight = weight, fontSize = size.sp, lineHeight = line.sp, letterSpacing = ls.sp)

val SerenvoxTypography = Typography(
    displaySmall = t(34, FontWeight.Bold, 40, -0.5),
    headlineLarge = t(32, FontWeight.Bold, 38, -0.5),
    headlineMedium = t(28, FontWeight.Bold, 34, -0.3),
    headlineSmall = t(24, FontWeight.SemiBold, 30, -0.2),
    titleLarge = t(20, FontWeight.SemiBold, 26),
    titleMedium = t(17, FontWeight.SemiBold, 22),
    titleSmall = t(15, FontWeight.SemiBold, 20),
    bodyLarge = t(17, FontWeight.Normal, 27),
    bodyMedium = t(15, FontWeight.Normal, 22),
    bodySmall = t(13, FontWeight.Normal, 18),
    labelLarge = t(15, FontWeight.SemiBold, 20),
    labelMedium = t(13, FontWeight.Medium, 16),
    labelSmall = t(11, FontWeight.Medium, 14, 0.2),
)

val SerenvoxShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp), small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(18.dp), large = RoundedCornerShape(24.dp), extraLarge = RoundedCornerShape(32.dp),
)

/** Reading text style used by the reader view. */
val ReaderStyle = TextStyle(fontFamily = Inter, fontWeight = FontWeight.Normal, fontSize = 20.sp, lineHeight = 32.sp)

@Composable
fun SerenvoxTheme(dark: Boolean, dynamic: Boolean = false, content: @Composable () -> Unit) {
    val scheme = if (dynamic && Build.VERSION.SDK_INT >= 31) {
        val c = LocalContext.current
        if (dark) dynamicDarkColorScheme(c) else dynamicLightColorScheme(c)
    } else if (dark) Dark else Light
    MaterialTheme(colorScheme = scheme, typography = SerenvoxTypography, shapes = SerenvoxShapes, content = content)
}

/** Gradient pairs used for avatars and library cards. */
val Gradients = listOf(
    Color(0xFF8E7BFF) to Color(0xFF5B3FE0),
    Color(0xFFFF9A8B) to Color(0xFFFF6A88),
    Color(0xFF5EE7DF) to Color(0xFF4A90E2),
    Color(0xFFFFC371) to Color(0xFFFF5F6D),
    Color(0xFF9BE15D) to Color(0xFF00B09B),
    Color(0xFFF78CA0) to Color(0xFFB06AB3),
    Color(0xFF7F7FD5) to Color(0xFF86A8E7),
)

fun gradientFor(key: String) = Gradients[Math.floorMod(key.hashCode(), Gradients.size)]
