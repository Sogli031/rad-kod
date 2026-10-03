package com.rad.shifttracker

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import android.app.Activity
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
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

/*
 * Naglašeni (emphasized) stilovi iz M3 Expressive tipografske skale. Ovaj material3 (1.3) ih još
 * nema, pa su ovde kao proširenja istog imena; posle nadogradnje na verziju koja ih ima, članovi
 * klase imaju prednost i ova proširenja otpadaju (proveriti snimke, zvanična težina je drugačija).
 * UI ne menja `fontWeight` ručno, nego bira stil.
 */
private val EmphasizedWeight = FontWeight.Bold
val Typography.displayLargeEmphasized: TextStyle get() = displayLarge.copy(fontWeight = EmphasizedWeight)
val Typography.displayMediumEmphasized: TextStyle get() = displayMedium.copy(fontWeight = EmphasizedWeight)
val Typography.displaySmallEmphasized: TextStyle get() = displaySmall.copy(fontWeight = EmphasizedWeight)
val Typography.headlineLargeEmphasized: TextStyle get() = headlineLarge.copy(fontWeight = EmphasizedWeight)
val Typography.headlineMediumEmphasized: TextStyle get() = headlineMedium.copy(fontWeight = EmphasizedWeight)
val Typography.headlineSmallEmphasized: TextStyle get() = headlineSmall.copy(fontWeight = EmphasizedWeight)
val Typography.titleLargeEmphasized: TextStyle get() = titleLarge.copy(fontWeight = EmphasizedWeight)
val Typography.titleMediumEmphasized: TextStyle get() = titleMedium.copy(fontWeight = EmphasizedWeight)
val Typography.titleSmallEmphasized: TextStyle get() = titleSmall.copy(fontWeight = EmphasizedWeight)
val Typography.bodyLargeEmphasized: TextStyle get() = bodyLarge.copy(fontWeight = EmphasizedWeight)
val Typography.bodyMediumEmphasized: TextStyle get() = bodyMedium.copy(fontWeight = EmphasizedWeight)
val Typography.bodySmallEmphasized: TextStyle get() = bodySmall.copy(fontWeight = EmphasizedWeight)
val Typography.labelLargeEmphasized: TextStyle get() = labelLarge.copy(fontWeight = EmphasizedWeight)
val Typography.labelMediumEmphasized: TextStyle get() = labelMedium.copy(fontWeight = EmphasizedWeight)
val Typography.labelSmallEmphasized: TextStyle get() = labelSmall.copy(fontWeight = EmphasizedWeight)

/** Naglašena varijanta stila koji je komponenta dobila spolja (npr. oznaka dugmeta). */
fun TextStyle.emphasized(): TextStyle = copy(fontWeight = EmphasizedWeight)

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
    val sunday: Color,
    /** Tekst na punoj boji smene 1, 2 i godišnjeg. */
    val onShiftBright: Color,
    /** Tekst na punoj boji ostalih smena (treća, bolovanje). U tamnoj temi tamni, jer su te boje posvetljene. */
    val onShiftDeep: Color
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
    sunday = Color(0xFFF97316),
    onShiftBright = DarkPalette.textDark,
    onShiftDeep = DarkPalette.textDark
)

/**
 * Svetla paleta: isti akcent (cijan, tamniji ton) i iste uloge boja; boje smena su tamnije da bi tekst u
 * boji smene na toniranoj ćeliji imao >= 4.5:1 (proverava `ThemeContrastTest`).
 */
private object LightPalette {
    val bg = Color(0xFFF8FAFC)
    val card = Color(0xFFFFFFFF)
    val cardLow = Color(0xFFF1F5F9)
    val cardHigh = Color(0xFFEEF2F7)
    val cardHighest = Color(0xFFE2E8F0)
    val border = Color(0xFFCBD5E1)
    val outline = Color(0xFF64748B)
    val accent = Color(0xFF0369A1)
    val textPrimary = Color(0xFF0F172A)
    val textMuted = Color(0xFF475569)
    val danger = Color(0xFFB91C1C)
    val white = Color(0xFFFFFFFF)
    val compTime = Color(0xFF0F6B63)
}

internal val LightExtendedColors = ExtendedColors(
    textSecondary = Color(0xFF334155),
    textFaint = Color(0xFF526277),
    success = Color(0xFF166534),
    warning = Color(0xFF92400E),
    info = Color(0xFF1D4ED8),
    shift1 = LightPalette.accent,
    shift2 = Color(0xFF166534),
    shift3 = Color(0xFF6D28D9),
    sick = LightPalette.danger,
    vacation = Color(0xFF92400E),
    off = LightPalette.border,
    doubleShift = Color(0xFFC2410C),
    compTime = LightPalette.compTime,
    compTimeUsed = Color(0xFFC2410C),
    compTimeDeficit = LightPalette.danger,
    weekendOt = Color(0xFF92400E),
    sunday = Color(0xFFC2410C),
    onShiftBright = LightPalette.white,
    onShiftDeep = LightPalette.white
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

internal val RadLightColorScheme: ColorScheme = lightColorScheme(
    primary = LightPalette.accent,
    onPrimary = LightPalette.white,
    primaryContainer = Color(0xFFE0F2FE),
    onPrimaryContainer = Color(0xFF0C4A6E),
    inversePrimary = DarkPalette.accentPrimary,
    secondary = Color(0xFFB45309),
    onSecondary = LightPalette.white,
    secondaryContainer = Color(0xFFFDE7B8),
    onSecondaryContainer = Color(0xFF4A3410),
    tertiary = LightPalette.compTime,
    onTertiary = LightPalette.white,
    tertiaryContainer = Color(0xFFCCFBF1),
    onTertiaryContainer = Color(0xFF0F3F3A),
    background = LightPalette.bg,
    onBackground = LightPalette.textPrimary,
    surface = LightPalette.bg,
    onSurface = LightPalette.textPrimary,
    surfaceVariant = LightPalette.cardHighest,
    onSurfaceVariant = LightPalette.textMuted,
    surfaceTint = LightPalette.accent,
    inverseSurface = Color(0xFF1E293B),
    inverseOnSurface = Color(0xFFF1F5F9),
    error = LightPalette.danger,
    onError = LightPalette.white,
    errorContainer = Color(0xFFFEE2E2),
    onErrorContainer = Color(0xFF5B1A1A),
    outline = LightPalette.outline,
    outlineVariant = LightPalette.border,
    scrim = Color.Black,
    surfaceBright = LightPalette.card,
    surfaceDim = Color(0xFFE2E8F0),
    surfaceContainerLowest = LightPalette.card,
    surfaceContainerLow = LightPalette.cardLow,
    surfaceContainer = LightPalette.card,
    surfaceContainerHigh = LightPalette.cardHigh,
    surfaceContainerHighest = LightPalette.cardHighest
)

/** Boja pozadine prozora u izabranoj temi (da prvi frejm nema bljesak suprotne teme). */
internal fun windowBackgroundColor(dark: Boolean): Color =
    if (dark) RadDarkColorScheme.background else RadLightColorScheme.background

@Composable
fun RadTheme(settings: ThemeSettings = ThemeStore.settings, content: @Composable () -> Unit) {
    val dark = settings.isDark(isSystemInDarkTheme())
    val context = LocalContext.current
    val colorScheme = when {
        settings.dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        dark -> RadDarkColorScheme
        else -> RadLightColorScheme
    }
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as? Activity)?.window ?: return@SideEffect
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = !dark
                isAppearanceLightNavigationBars = !dark
            }
        }
    }
    CompositionLocalProvider(LocalExtendedColors provides if (dark) DarkExtendedColors else LightExtendedColors) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = RadTypography,
            shapes = RadShapes,
            content = content
        )
    }
}
