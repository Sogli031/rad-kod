package com.rad.shifttracker

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight

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

@Composable
fun RadTheme(content: @Composable () -> Unit) {
    val colors = darkColorScheme(
        primary = THEME.accentPrimary,
        secondary = THEME.accentSecondary,
        background = THEME.bgDark,
        surface = THEME.bgPrimary,
        onPrimary = THEME.textDark,
        onSurface = THEME.textPrimary
    )

    MaterialTheme(
        colorScheme = colors,
        typography = RadTypography,
        content = content
    )
}
