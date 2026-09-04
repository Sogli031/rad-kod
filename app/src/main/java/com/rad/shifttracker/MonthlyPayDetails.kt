package com.rad.shifttracker

import java.math.BigDecimal

data class MonthlyPayDetails(
    val totalPaidHours: Int,
    val paidWorkHours: Int,
    val nightHours: Int,
    val overtimeHours: Int,
    val sundayHours: Int,
    val sickHours: Int,
    val paidLeaveHours: Int,
    val vacationHours: Int,
    val paidHolidayHours: Int,
    val coveredDeficitHours: Int,
    val uncoveredDeficitHours: Int,
    val grossBaseRegular: Double,
    val baseHourlyGross: Double,
    val regularWorkedBaseMinutes: Int,
    val regularWorkedBaseGross: Double,
    val publicHolidayWorkedBaseMinutes: Int,
    val publicHolidayWorkedBaseGross: Double,
    val overtimeBaseMinutes: Int,
    val overtimeBaseGross: Double,
    val seniorityEligibleMinutes: Int,
    val seniorityEligibleBaseGross: Double,
    val completedYearsOfService: Int,
    val seniorityRatePerYear: Double,
    val seniorityTotalRate: Double,
    val grossNightPremium: Double,
    val grossOvertimePremium: Double,
    val grossSundayPremium: Double,
    val grossHolidayPremium: Double,
    val grossDifficultWorkConditions: Double,
    val grossSickPay: Double,
    val grossPaidLeave: Double,
    val grossVacationPay: Double,
    val grossPaidHoliday: Double,
    val grossCoveredDeficitPay: Double,
    val grossUncoveredDeficitLoss: Double,
    val grossOvertimeFull: Double,
    val grossSeniorityBonus: Double,
    val sickPayBaseHourly: Double,
    val paidLeavePayBase: ResolvedPayBase,
    val vacationPayBase: ResolvedPayBase,
    val paidHolidayPayBase: ResolvedPayBase,
    val sickPayBase: ResolvedPayBase,
    val grossMealAddition: Double,
    val grossRegressAddition: Double,
    val grossBonusAddition: Double,
    val absencePenaltyGross: BigDecimal,
    val grossTotalBeforeSeniority: Double,
    val grossTotalWithSeniority: Double,
    val netTotalBeforeSeniority: Double,
    val netSeniorityBonus: Double,
    val netUncoveredDeficitLoss: Double,
    val netTotal: Double,
    val totalPaidMinutes: Int = totalPaidHours * 60,
    val paidWorkMinutes: Int = paidWorkHours * 60,
    val nightMinutes: Int = nightHours * 60,
    val overtimeMinutes: Int = overtimeHours * 60,
    val sundayMinutes: Int = sundayHours * 60,
    val sickMinutes: Int = sickHours * 60,
    val paidLeaveMinutes: Int = paidLeaveHours * 60,
    val vacationMinutes: Int = vacationHours * 60,
    val paidHolidayMinutes: Int = paidHolidayHours * 60,
    val coveredDeficitMinutes: Int = coveredDeficitHours * 60,
    val uncoveredDeficitMinutes: Int = uncoveredDeficitHours * 60
) {
    val grossEarningsBeforeSeniority: Double
        get() = grossTotalBeforeSeniority
    val nightPremiumGross: Double
        get() = roundMoneyHalfUp(grossNightPremium)
    val overtimePremiumGross: Double
        get() = roundMoneyHalfUp(grossOvertimePremium)
    val sundayPremiumGross: Double
        get() = roundMoneyHalfUp(grossSundayPremium)
    val holidayPremiumGross: Double
        get() = roundMoneyHalfUp(grossHolidayPremium)
    val difficultConditionsPremiumGross: Double
        get() = roundMoneyHalfUp(grossDifficultWorkConditions)
    val paidOvertimeHours: Int
        get() = overtimeHours
    val paidOvertimeMinutes: Int
        get() = overtimeMinutes
    val regularOrCoveredPaidMinutes: Int
        get() = regularWorkedBaseMinutes
    val regularOrCoveredPaidHours: Int
        get() = regularOrCoveredPaidMinutes / 60

    val netRatioBeforeSeniority: Double
        get() = if (grossTotalBeforeSeniority > 0.0) netTotalBeforeSeniority / grossTotalBeforeSeniority else 0.0
}

/**
 * Izostanci kao na platnom listiću (šifra 95): mesečni fond umanjen za sve
 * sate plaćene po osnovi koji ga pune (režija, rad na praznik, naknade,
 * pokriven manjak). Prekovremeni sati fond ne pune. Ovo je knjigovodstveni
 * ostatak fonda, a ne dnevni manjak koji hrani saldo sati.
 */
fun MonthlyPayDetails.fundAbsenceMinutes(monthlyFundHours: Int): Int {
    if (monthlyFundHours <= 0) return 0
    val paidNonOvertimeMinutes = totalPaidMinutes - overtimeMinutes
    return (monthlyFundHours * 60 - paidNonOvertimeMinutes).coerceAtLeast(0)
}
