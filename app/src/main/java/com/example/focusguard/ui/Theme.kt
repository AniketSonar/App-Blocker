package com.example.focusguard

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/*
 * Design direction: "calm paper". Warm off-white pages, white cards with a hairline border,
 * a deep forest green as the one brand color, and a serif for big numbers and headlines.
 * The only loud color is coral, and it is reserved for "distracting".
 */

// ---- category colors (used by charts, tags, dots) ------------------------------------------
val FgProductive = Color(0xFF3FA37F)
val FgNeutral = Color(0xFF8795A3)
val FgDistracting = Color(0xFFD9543F)
val FgWarn = Color(0xFFD99A2B)

fun AppCategory.color(): Color = when (this) {
    AppCategory.PRODUCTIVE -> FgProductive
    AppCategory.NEUTRAL -> FgNeutral
    AppCategory.DISTRACTING -> FgDistracting
}

// ---- color schemes -------------------------------------------------------------------------------
internal val LightColors = lightColorScheme(
    primary = Color(0xFF1E4D43),
    onPrimary = Color(0xFFF7F4EE),
    primaryContainer = Color(0xFF1E4D43),
    onPrimaryContainer = Color(0xFFF7F4EE),
    secondary = Color(0xFF55645D),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFE2EAE5),
    onSecondaryContainer = Color(0xFF1E4D43),
    tertiary = Color(0xFFD9543F),
    onTertiary = Color(0xFFFFFFFF),
    background = Color(0xFFF5F2EC),
    onBackground = Color(0xFF1D1C1A),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF1D1C1A),
    surfaceVariant = Color(0xFFEDE9E1),
    onSurfaceVariant = Color(0xFF6B665C),
    outline = Color(0xFFB9B3A6),
    outlineVariant = Color(0xFFE4DFD4),
    error = Color(0xFFD9543F),
    onError = Color(0xFFFFFFFF),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFFAF8F4),
    surfaceContainer = Color(0xFFF5F2EC),
    surfaceContainerHigh = Color(0xFFEFEBE3),
    surfaceContainerHighest = Color(0xFFEDE9E1)
)

internal val DarkColors = darkColorScheme(
    primary = Color(0xFF8FD3C2),
    onPrimary = Color(0xFF0F2A24),
    primaryContainer = Color(0xFF1F3F37),
    onPrimaryContainer = Color(0xFFEAF3EF),
    secondary = Color(0xFFA9B8B0),
    onSecondary = Color(0xFF14201C),
    secondaryContainer = Color(0xFF26352F),
    onSecondaryContainer = Color(0xFFCFE3DA),
    tertiary = Color(0xFFFF8A73),
    onTertiary = Color(0xFF3A1008),
    background = Color(0xFF0E1311),
    onBackground = Color(0xFFEAE7E0),
    surface = Color(0xFF161D1A),
    onSurface = Color(0xFFEAE7E0),
    surfaceVariant = Color(0xFF212A26),
    onSurfaceVariant = Color(0xFF9CA59F),
    outline = Color(0xFF4A5651),
    outlineVariant = Color(0xFF28322E),
    error = Color(0xFFFF8A73),
    onError = Color(0xFF3A1008),
    surfaceContainerLowest = Color(0xFF0B0F0D),
    surfaceContainerLow = Color(0xFF121816),
    surfaceContainer = Color(0xFF161D1A),
    surfaceContainerHigh = Color(0xFF1C2421),
    surfaceContainerHighest = Color(0xFF212A26)
)

// ---- type: serif for display numbers and headlines, system sans for everything else -----------
private val Serif = FontFamily.Serif

internal val FgTypography = Typography().copy(
    displayLarge = TextStyle(fontFamily = Serif, fontWeight = FontWeight.Medium, fontSize = 56.sp, lineHeight = 60.sp, letterSpacing = (-1).sp),
    displayMedium = TextStyle(fontFamily = Serif, fontWeight = FontWeight.Medium, fontSize = 46.sp, lineHeight = 50.sp, letterSpacing = (-0.8).sp),
    displaySmall = TextStyle(fontFamily = Serif, fontWeight = FontWeight.Medium, fontSize = 36.sp, lineHeight = 40.sp, letterSpacing = (-0.5).sp),
    headlineLarge = TextStyle(fontFamily = Serif, fontWeight = FontWeight.SemiBold, fontSize = 32.sp, lineHeight = 38.sp, letterSpacing = (-0.3).sp),
    headlineMedium = TextStyle(fontFamily = Serif, fontWeight = FontWeight.SemiBold, fontSize = 28.sp, lineHeight = 34.sp, letterSpacing = (-0.2).sp),
    headlineSmall = TextStyle(fontFamily = Serif, fontWeight = FontWeight.SemiBold, fontSize = 22.sp, lineHeight = 28.sp),
    titleLarge = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 20.sp, lineHeight = 26.sp),
    titleMedium = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 16.sp, lineHeight = 22.sp),
    titleSmall = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 14.sp, lineHeight = 20.sp),
    bodyLarge = TextStyle(fontWeight = FontWeight.Normal, fontSize = 16.sp, lineHeight = 24.sp),
    bodyMedium = TextStyle(fontWeight = FontWeight.Normal, fontSize = 14.sp, lineHeight = 21.sp),
    bodySmall = TextStyle(fontWeight = FontWeight.Normal, fontSize = 12.sp, lineHeight = 17.sp),
    labelLarge = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 14.sp, lineHeight = 20.sp),
    labelMedium = TextStyle(fontWeight = FontWeight.Medium, fontSize = 12.sp, lineHeight = 16.sp, letterSpacing = 0.4.sp),
    labelSmall = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 11.sp, lineHeight = 14.sp, letterSpacing = 1.0.sp)
)

private val FgShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(22.dp),
    extraLarge = RoundedCornerShape(28.dp)
)

@Composable
fun FocusGuardTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) DarkColors else LightColors,
        typography = FgTypography,
        shapes = FgShapes,
        content = content
    )
}
