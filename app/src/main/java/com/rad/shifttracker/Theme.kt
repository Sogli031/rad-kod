package com.rad.shifttracker

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

private val SpaceGrotesk = FontFamily(
    Font(R.font.space_grotesk_light, weight = FontWeight.W300),
    Font(R.font.space_grotesk_regular, weight = FontWeight.W400),
    Font(R.font.space_grotesk_medium, weight = FontWeight.W500),
    Font(R.font.space_grotesk_semibold, weight = FontWeight.W600),
    Font(R.font.space_grotesk_bold, weight = FontWeight.W700)
)

private val BaseTypography = Typography()
private val RadTypography = Typography(
    displayLarge = BaseTypography.displayLarge.copy(fontFamily = SpaceGrotesk),
    displayMedium = BaseTypography.displayMedium.copy(fontFamily = SpaceGrotesk),
    displaySmall = BaseTypography.displaySmall.copy(fontFamily = SpaceGrotesk),
    headlineLarge = BaseTypography.headlineLarge.copy(fontFamily = SpaceGrotesk),
    headlineMedium = BaseTypography.headlineMedium.copy(fontFamily = SpaceGrotesk),
    headlineSmall = BaseTypography.headlineSmall.copy(fontFamily = SpaceGrotesk),
    titleLarge = BaseTypography.titleLarge.copy(fontFamily = SpaceGrotesk),
    titleMedium = BaseTypography.titleMedium.copy(fontFamily = SpaceGrotesk),
    titleSmall = BaseTypography.titleSmall.copy(fontFamily = SpaceGrotesk),
    bodyLarge = BaseTypography.bodyLarge.copy(fontFamily = SpaceGrotesk),
    bodyMedium = BaseTypography.bodyMedium.copy(fontFamily = SpaceGrotesk),
    bodySmall = BaseTypography.bodySmall.copy(fontFamily = SpaceGrotesk),
    labelLarge = BaseTypography.labelLarge.copy(fontFamily = SpaceGrotesk),
    labelMedium = BaseTypography.labelMedium.copy(fontFamily = SpaceGrotesk),
    labelSmall = BaseTypography.labelSmall.copy(fontFamily = SpaceGrotesk)
)

/** M3 skala oblika: komponente biraju oblik po ulozi, ne po sirovim dp vrednostima. */
val RadShapes = Shapes(
    extraSmall = RoundedCornerShape(4.dp),
    small = RoundedCornerShape(8.dp),
    medium = RoundedCornerShape(12.dp),
    large = RoundedCornerShape(16.dp),
    extraLarge = RoundedCornerShape(28.dp)
)

/**
 * Puna M3 tamna šema izvedena iz [THEME] palete. Sve uloge su zadate, pa nijedna M3 komponenta
 * ne povlači podrazumevanu ljubičastu baseline boju. Boje smena i statusa ostaju u [THEME]
 * (proširene boje aplikacije, nemaju M3 ulogu).
 */
private fun radColorScheme(): ColorScheme = darkColorScheme(
    primary = THEME.accentPrimary,
    onPrimary = THEME.textDark,
    primaryContainer = Color(0xFF0C4A6E),
    onPrimaryContainer = Color(0xFFE0F2FE),
    inversePrimary = Color(0xFF0369A1),
    secondary = THEME.accentSecondary,
    onSecondary = THEME.textDark,
    secondaryContainer = Color(0xFF4A3410),
    onSecondaryContainer = Color(0xFFFDE7B8),
    tertiary = THEME.compTime,
    onTertiary = THEME.textDark,
    tertiaryContainer = Color(0xFF0F3F3A),
    onTertiaryContainer = Color(0xFFCCFBF1),
    background = THEME.bgDark,
    onBackground = THEME.textPrimary,
    surface = THEME.bgDark,
    onSurface = THEME.textPrimary,
    surfaceVariant = THEME.empty,
    onSurfaceVariant = THEME.textMuted,
    surfaceTint = THEME.accentPrimary,
    inverseSurface = Color(0xFFE2E8F0),
    inverseOnSurface = THEME.textDark,
    error = THEME.danger,
    onError = THEME.textDark,
    errorContainer = Color(0xFF5B1A1A),
    onErrorContainer = Color(0xFFFEE2E2),
    outline = THEME.outline,
    outlineVariant = THEME.glassBorder,
    scrim = Color.Black,
    surfaceBright = THEME.empty,
    surfaceDim = THEME.bgDark,
    surfaceContainerLowest = THEME.bgDark,
    surfaceContainerLow = THEME.bgSecondary,
    surfaceContainer = THEME.bgCard,
    surfaceContainerHigh = THEME.bgCardElevated,
    surfaceContainerHighest = THEME.empty
)

@Composable
fun RadTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = radColorScheme(),
        typography = RadTypography,
        shapes = RadShapes,
        content = content
    )
}
