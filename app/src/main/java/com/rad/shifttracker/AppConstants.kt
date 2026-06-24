package com.rad.shifttracker

import androidx.compose.ui.graphics.Color

object UI {
    const val buttonHeight = 46
    const val iconButtonSize = 44
    const val saveButtonHeight = 55
    const val cardRadius = 8
    const val cardPadding = 12
    const val headerHeight = 60
    const val statCardHeight = 80
    const val totalCardHeight = 82
    const val compTimeBarHeight = 50
    const val weekdayHeaderHeight = 32
    const val dayButtonHeight = 62
    const val bottomBarHeight = 95
    const val popupWidthRatio = 0.95f
    const val popupMaxHeight = 760
    const val popupEdgePadding = 28
    const val popupBottomSafePadding = 84
    const val defaultSpacing = 8
    const val largeSpacing = 12
    const val smallSpacing = 4
    const val indicatorDotSize = 8
    const val indicatorBarHeight = 4
}

data class ModernTheme(
    val bgDark: Color = Color(0xFF0D1117),
    val bgPrimary: Color = Color(0xFF111827),
    val bgSecondary: Color = Color(0xFF0F172A),
    val bgCard: Color = Color(0xFF111827),
    val bgCardElevated: Color = Color(0xFF172033),
    val glassBorder: Color = Color(0xFF334155),
    val accentPrimary: Color = Color(0xFF38BDF8),
    val accentSecondary: Color = Color(0xFFF59E0B),
    val success: Color = Color(0xFF22C55E),
    val warning: Color = Color(0xFFFBBF24),
    val danger: Color = Color(0xFFF87171),
    val info: Color = Color(0xFF60A5FA),
    val textPrimary: Color = Color(0xFFF8FAFC),
    val textSecondary: Color = Color(0xFFCBD5E1),
    val textMuted: Color = Color(0xFF94A3B8),
    val textDark: Color = Color(0xFF0F172A),
    val shadow: Color = Color(0xFF000000),
    val shift1: Color = Color(0xFF38BDF8),
    val shift2: Color = Color(0xFF86EFAC),
    val shift3: Color = Color(0xFF7C3AED),
    val sick: Color = Color(0xFFEF4444),
    val vacation: Color = Color(0xFFF59E0B),
    val off: Color = Color(0xFF334155),
    val empty: Color = Color(0xFF1E293B),
    val doubleShift: Color = Color(0xFFF97316),
    val compTime: Color = Color(0xFF14B8A6),
    val compTimeUsed: Color = Color(0xFFFB923C),
    val compTimeDeficit: Color = Color(0xFFF87171),
    val weekendOt: Color = Color(0xFFFBBF24),
    val sunday: Color = Color(0xFFF97316)
)

data class PayConfig(
    val defaultCoefficient: Double = DEFAULT_PAY_COEFFICIENT,
    val defaultBasePay: Double = defaultCoefficient * HOURLY_VALUE_FROM_JANUARY_2026,
    val defaultHours: Int = 8,
    val shift3Premium: Double = 1.41,
    val overtimePremium: Double = 1.41,
    val sundayBonus: Double = 0.25,
    val sickPayRate: Double = 0.65,
    val vacationPayRate: Double = 1.0,
    val holidayBonus: Double = 1.23,
    val currency: String = "RSD",
    val foodPerDay: Double = 280.0,
    val minuliRadPerYear: Double = 0.005,
    val vacationPayBaseHourly: Double = 918.81,
    val paidHolidayPayBaseHourly: Double = 918.81,
    val sickPayBaseHourly: Double = 918.81,
    val transportPerWorkDay: Double = 240.0,
    val difficultWorkConditionsPercent: Double = 0.0,
    val defaultEmployeeContributionsPercent: Double = 19.9,
    val defaultTaxRelief: Double = 34_221.0,
    val defaultTaxPercent: Double = 10.0,
    val defaultUnionDuesRate: Double = DEFAULT_UNION_DUES_RATE
)

data class AppConfig(
    val theme: ModernTheme = ModernTheme(),
    val pay: PayConfig = PayConfig(),
    val weeklyShiftCycle: List<Int> = listOf(3, 2, 1)
)

val CONFIG = AppConfig()
val THEME = CONFIG.theme
const val MANUAL_HOLIDAY_LABEL = "Ručno unet praznik"

val WEEKDAY_NAMES = listOf("PON", "UTO", "SRE", "\u010cET", "PET", "SUB", "NED")
val MONTH_NAMES = listOf(
    "",
    "Januar",
    "Februar",
    "Mart",
    "April",
    "Maj",
    "Jun",
    "Jul",
    "Avgust",
    "Septembar",
    "Oktobar",
    "Novembar",
    "Decembar"
)
