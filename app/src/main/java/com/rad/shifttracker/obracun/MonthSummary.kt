package com.rad.shifttracker.obracun

import com.rad.shifttracker.osnova.MONTH_NAMES

data class MonthSummary(
    var year: Int,
    var month: Int,
    var workHours: Int = 0,
    var workMinutes: Int = 0,
    var overtimeHours: Int = 0,
    var sickHours: Int = 0,
    var paidLeaveHours: Int = 0,
    var paidLeaveMinutes: Int = 0,
    var vacationHours: Int = 0,
    var sundayHours: Int = 0,
    var nightHours: Int = 0,
    var totalPay: Double = 0.0,
    var workDays: Int = 0,
    var doubleShiftDays: Int = 0,
    var foodAllowance: Double = 0.0,
    var mealEntitlementCalculation: MealEntitlementCalculation? = null,
    var compTimeEarned: Int = 0,
    var compTimeDeficit: Int = 0,
    var compTimeUsed: Int = 0,
    var compTimeNet: Int = 0,
    var compTimeStarting: Int = 0,
    var compTimeEnding: Int = 0,
    var compTimeEarnedMinutes: Int = 0,
    var compTimeDeficitMinutes: Int = 0,
    var compTimeUsedMinutes: Int = 0,
    var compTimeNetMinutes: Int = 0,
    var compTimeStartingMinutes: Int = 0,
    var compTimeEndingMinutes: Int = 0,
    var coveredDeficitMinutes: Int = 0,
    var uncoveredDeficitMinutes: Int = 0,
    /** Četvorobrigada: sati do mesečnog fonda koje firma plaća kao rad u režiji (01). */
    var fundTopUpMinutes: Int = 0,
    var yearsOfService: Int = 0,
    var seniorityEligibleWorkMinutes: Int = 0,
    var minuliRadBonus: Double = 0.0,
    var difficultWorkConditionsBonus: Double = 0.0,
    var seniorityBreakdown: SeniorityBreakdown = SeniorityBreakdown.empty()
) {
    val totalWithMinuli: Double
        get() = totalPay + minuliRadBonus

    val displayName: String
        get() = if (month in 1..12) {
            "${MONTH_NAMES[month]} $year"
        } else {
            "Mesec $month $year"
        }

    val baseHourlyGross: Double
        get() = seniorityBreakdown.baseHourlyGross
    val regularWorkedBaseMinutes: Int
        get() = seniorityBreakdown.regularWorkedBaseMinutes
    val regularWorkedBaseGross: Double
        get() = seniorityBreakdown.regularWorkedBaseGross
    val publicHolidayWorkedBaseMinutes: Int
        get() = seniorityBreakdown.publicHolidayWorkedBaseMinutes
    val publicHolidayWorkedBaseGross: Double
        get() = seniorityBreakdown.publicHolidayWorkedBaseGross
    val overtimeBaseMinutes: Int
        get() = seniorityBreakdown.overtimeBaseMinutes
    val overtimeBaseGross: Double
        get() = seniorityBreakdown.overtimeBaseGross
    val seniorityEligibleMinutes: Int
        get() = seniorityBreakdown.seniorityEligibleMinutes
    val seniorityEligibleBaseGross: Double
        get() = seniorityBreakdown.seniorityEligibleBaseGross
    val completedYearsOfService: Int
        get() = seniorityBreakdown.completedYearsOfService
    val seniorityRatePerYear: Double
        get() = seniorityBreakdown.seniorityRatePerYear
    val seniorityTotalRate: Double
        get() = seniorityBreakdown.seniorityTotalRate
    val paidOvertimeHours: Int
        get() = overtimeHours
    val paidOvertimeMinutes: Int
        get() = overtimeHours * 60
    val compTimeEarnedHours: Int
        get() = compTimeEarnedMinutes / 60
    val regularOrCoveredPaidMinutes: Int
        get() = regularWorkedBaseMinutes
    val regularOrCoveredPaidHours: Int
        get() = regularOrCoveredPaidMinutes / 60
}
