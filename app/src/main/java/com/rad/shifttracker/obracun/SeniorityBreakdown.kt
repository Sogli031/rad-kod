package com.rad.shifttracker.obracun

import java.math.BigDecimal
import java.math.MathContext
import com.rad.shifttracker.CONFIG

private const val MINUTES_PER_HOUR = 60

internal val SENIORITY_INCLUDED_CATEGORIES = listOf(
    "01 Rad u režiji",
    "05 Rad u režiji na DP",
    "covered deficit minutes paid as regular work"
)

internal val SENIORITY_EXCLUDED_CATEGORIES = listOf(
    "overtime base",
    "overtime premium",
    "night premium",
    "Sunday premium",
    "holiday premium",
    "difficult conditions premium",
    "vacation",
    "sick leave",
    "paid absence",
    "state holiday not worked",
    "KIRU and other compensation",
    "meal allowance",
    "regress",
    "awards and other gross additions"
)

data class SeniorityBreakdown(
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
    val grossSeniorityBonus: Double,
    val publicHolidayBaseIncludedInRegular: Boolean,
    val includedCategories: List<String> = SENIORITY_INCLUDED_CATEGORIES,
    val excludedCategories: List<String> = SENIORITY_EXCLUDED_CATEGORIES
) {
    companion object {
        fun empty(ratePerYear: Double = CONFIG.pay.minuliRadPerYear): SeniorityBreakdown {
            return calculateSeniorityBreakdownForCategories(
                baseHourlyGross = 0.0,
                regularWorkedBaseMinutes = 0,
                publicHolidayWorkedBaseMinutes = 0,
                overtimeBaseMinutes = 0,
                completedYearsOfService = 0,
                seniorityRatePerYear = ratePerYear
            )
        }
    }
}

internal fun calculateSeniorityBreakdownForCategories(
    baseHourlyGross: Double,
    regularWorkedBaseMinutes: Int,
    publicHolidayWorkedBaseMinutes: Int,
    overtimeBaseMinutes: Int,
    completedYearsOfService: Int,
    seniorityRatePerYear: Double,
    publicHolidayBaseIncludedInRegular: Boolean = false
): SeniorityBreakdown {
    val safeHourlyGross = roundMoneyHalfUp(baseHourlyGross)
    val safeRegularMinutes = regularWorkedBaseMinutes.coerceAtLeast(0)
    val safeHolidayMinutes = if (publicHolidayBaseIncludedInRegular) {
        0
    } else {
        publicHolidayWorkedBaseMinutes.coerceAtLeast(0)
    }
    val safeOvertimeMinutes = overtimeBaseMinutes.coerceAtLeast(0)
    val safeYears = completedYearsOfService.coerceAtLeast(0)
    val safeRatePerYear = if (seniorityRatePerYear.isFinite() && seniorityRatePerYear > 0.0) {
        seniorityRatePerYear
    } else {
        0.0
    }

    val regularGross = baseGrossForMinutes(safeRegularMinutes, safeHourlyGross)
    val holidayGross = baseGrossForMinutes(safeHolidayMinutes, safeHourlyGross)
    val overtimeGross = baseGrossForMinutes(safeOvertimeMinutes, safeHourlyGross)
    val eligibleGross = regularGross + holidayGross
    val totalRate = BigDecimal.valueOf(safeRatePerYear)
        .multiply(BigDecimal.valueOf(safeYears.toLong()))
        .toDouble()
    val bonus = roundMoneyHalfUp(eligibleGross * totalRate)

    return SeniorityBreakdown(
        baseHourlyGross = safeHourlyGross,
        regularWorkedBaseMinutes = safeRegularMinutes,
        regularWorkedBaseGross = regularGross,
        publicHolidayWorkedBaseMinutes = safeHolidayMinutes,
        publicHolidayWorkedBaseGross = holidayGross,
        overtimeBaseMinutes = safeOvertimeMinutes,
        overtimeBaseGross = overtimeGross,
        seniorityEligibleMinutes = safeRegularMinutes + safeHolidayMinutes,
        seniorityEligibleBaseGross = eligibleGross,
        completedYearsOfService = safeYears,
        seniorityRatePerYear = safeRatePerYear,
        seniorityTotalRate = totalRate,
        grossSeniorityBonus = bonus,
        publicHolidayBaseIncludedInRegular = publicHolidayBaseIncludedInRegular
    )
}

private fun baseGrossForMinutes(minutes: Int, hourlyGross: Double): Double {
    if (minutes <= 0 || hourlyGross <= 0.0) return 0.0
    return BigDecimal.valueOf(hourlyGross)
        .multiply(BigDecimal.valueOf(minutes.toLong()))
        .divide(BigDecimal.valueOf(MINUTES_PER_HOUR.toLong()), MathContext.DECIMAL128)
        .toDouble()
}
