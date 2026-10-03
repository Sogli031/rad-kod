package com.rad.shifttracker

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
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
 * Sirova tamna paleta (plavo-siva, jedan akcenat cijan). UI je ne čita direktno: boje idu kroz
 * [MaterialTheme.colorScheme] (M3 uloge) i [MaterialTheme.extendedColors] (smene i statusi).
 * Treća smena i bolovanje su posvetljeni da budu čitljivi kao TEKST na toniranoj ćeliji.
 */
private object DarkPalette {
    val bgDark = Color(0xFF0D1117)
    val bgSecondary = Color(0xFF0F172A)
    val bgCard = Color(0xFF111827)
    val bgCardElevated = Color(0xFF172033)
    val glassBorder = Color(0xFF334155)
    val accentPrimary = Color(0xFF38BDF8)
    val accentSecondary = Color(0xFFF59E0B)
    val danger = Color(0xFFF87171)
    val textPrimary = Color(0xFFF8FAFC)
    val textMuted = Color(0xFF94A3B8)

    /** M3 `outline`: ivice polja i prekidača, >= 3:1 na svim površinama. */
    val outline = Color(0xFF708199)
    val textDark = Color(0xFF0F172A)
    val empty = Color(0xFF1E293B)
    val compTime = Color(0xFF14B8A6)
}

/**
 * Boje aplikacije koje nemaju M3 ulogu: smene, statusi i dva dodatna nivoa teksta.
 * Svaka tema (tamna, kasnije svetla) daje svoj primerak kroz [LocalExtendedColors].
 */
@Immutable
data class ExtendedColors(
    /** Tekst između `onSurface` i `onSurfaceVariant` (vrednosti u karticama). */
    val textSecondary: Color,
    /** Najtiši čitljiv tekst (>= 4.5:1 na svim površinama). */
    val textFaint: Color,
    val success: Color,
    val warning: Color,
    val info: Color,
    val shift1: Color,
    val shift2: Color,
    val shift3: Color,
    val sick: Color,
    val vacation: Color,
    val off: Color,
    val doubleShift: Color,
    val compTime: Color,
    val compTimeUsed: Color,
    val compTimeDeficit: Color,
    val weekendOt: Color,
    val sunday: Color
)

internal val DarkExtendedColors = ExtendedColors(
    textSecondary = Color(0xFFCBD5E1),
    textFaint = Color(0xFF8494AA),
    success = Color(0xFF22C55E),
    warning = Color(0xFFFBBF24),
    info = Color(0xFF60A5FA),
    shift1 = Color(0xFF38BDF8),
    shift2 = Color(0xFF86EFAC),
    shift3 = Color(0xFFA78BFA),
    sick = Color(0xFFF87171),
    vacation = Color(0xFFFBBF24),
    off = Color(0xFF334155),
    doubleShift = Color(0xFFF97316),
    compTime = DarkPalette.compTime,
    compTimeUsed = Color(0xFFFB923C),
    compTimeDeficit = Color(0xFFF87171),
    weekendOt = Color(0xFFFBBF24),
    sunday = Color(0xFFF97316)
)

val LocalExtendedColors = staticCompositionLocalOf { DarkExtendedColors }

/** Proširene boje teme, uz [MaterialTheme.colorScheme]. */
val MaterialTheme.extendedColors: ExtendedColors
    @Composable
    @ReadOnlyComposable
    get() = LocalExtendedColors.current

/**
 * Puna M3 tamna šema. Sve uloge su zadate, pa nijedna M3 komponenta ne povlači podrazumevanu
 * ljubičastu baseline boju.
 */
internal val RadDarkColorScheme: ColorScheme = darkColorScheme(
    primary = DarkPalette.accentPrimary,
    onPrimary = DarkPalette.textDark,
    primaryContainer = Color(0xFF0C4A6E),
    onPrimaryContainer = Color(0xFFE0F2FE),
    inversePrimary = Color(0xFF0369A1),
    secondary = DarkPalette.accentSecondary,
    onSecondary = DarkPalette.textDark,
    secondaryContainer = Color(0xFF4A3410),
    onSecondaryContainer = Color(0xFFFDE7B8),
    tertiary = DarkPalette.compTime,
    onTertiary = DarkPalette.textDark,
    tertiaryContainer = Color(0xFF0F3F3A),
    onTertiaryContainer = Color(0xFFCCFBF1),
    background = DarkPalette.bgDark,
    onBackground = DarkPalette.textPrimary,
    surface = DarkPalette.bgDark,
    onSurface = DarkPalette.textPrimary,
    surfaceVariant = DarkPalette.empty,
    onSurfaceVariant = DarkPalette.textMuted,
    surfaceTint = DarkPalette.accentPrimary,
    inverseSurface = Color(0xFFE2E8F0),
    inverseOnSurface = DarkPalette.textDark,
    error = DarkPalette.danger,
    onError = DarkPalette.textDark,
    errorContainer = Color(0xFF5B1A1A),
    onErrorContainer = Color(0xFFFEE2E2),
    outline = DarkPalette.outline,
    outlineVariant = DarkPalette.glassBorder,
    scrim = Color.Black,
    surfaceBright = DarkPalette.empty,
    surfaceDim = DarkPalette.bgDark,
    surfaceContainerLowest = DarkPalette.bgDark,
    surfaceContainerLow = DarkPalette.bgSecondary,
    surfaceContainer = DarkPalette.bgCard,
    surfaceContainerHigh = DarkPalette.bgCardElevated,
    surfaceContainerHighest = DarkPalette.empty
)

@Composable
fun RadTheme(content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalExtendedColors provides DarkExtendedColors) {
        MaterialTheme(
            colorScheme = RadDarkColorScheme,
            typography = RadTypography,
            shapes = RadShapes,
            content = content
        )
    }
}
