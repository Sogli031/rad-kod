package com.rad.shifttracker

import com.rad.shifttracker.obracun.DEFAULT_PAY_COEFFICIENT
import com.rad.shifttracker.obracun.HOURLY_VALUE_FROM_JANUARY_2026

object UI {
    const val buttonHeight = 48
    const val iconButtonSize = 48
    const val saveButtonHeight = 52
    const val headerHeight = 52
    const val statCardHeight = 62
    const val totalCardHeight = 96
    const val compTimeBarHeight = 62
    const val weekdayHeaderHeight = 24
    const val dayButtonHeight = 58
    const val popupMaxHeight = 760
    const val popupEdgePadding = 28
    const val popupBottomSafePadding = 0
    const val indicatorDotSize = 6
    const val indicatorBarHeight = 3

    /** Najveća širina sadržaja: na tabletu/preklopljenom telefonu se ne razvlači preko cele širine. */
    const val contentMaxWidth = 600

    /** Na „expanded“ širini (WindowSizeClass, od 840dp) kalendar i iznosi idu u dve kolone. */
    const val wideContentMaxWidth = 1200
}

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
    val defaultTaxPercent: Double = 10.0
)

data class AppConfig(
    val pay: PayConfig = PayConfig(),
    val weeklyShiftCycle: List<Int> = listOf(3, 2, 1)
)

val CONFIG = AppConfig()
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
