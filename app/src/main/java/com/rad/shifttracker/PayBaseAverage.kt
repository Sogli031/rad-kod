package com.rad.shifttracker

fun calculatePayBaseMonthForAverage(
    year: Int,
    month: Int,
    details: MonthlyPayDetails,
    source: PayBaseMonthSource = PayBaseMonthSource.REAL,
    options: PayBaseAverageOptions = PayBaseAverageOptions(),
    grossAdditions: MonthlyGrossAdditions = MonthlyGrossAdditions(year, month)
): RollingPayBaseMonth {
    val safeGrossAdditions = grossAdditions.sanitizedFor(year, month)
    val includedMeal = if (options.includeMealAllowanceInAverage) {
        details.grossMealAddition.sanitizeAverageMoney()
    } else {
        0.0
    }
    val includedRegress = if (options.includeRegressInAverage) {
        details.grossRegressAddition.sanitizeAverageMoney()
    } else {
        0.0
    }
    val includedRegular = details.grossBaseRegular.sanitizeAverageMoney()
    val includedOvertime = details.grossOvertimePremium.sanitizeAverageMoney()
    val includedNight = details.grossNightPremium.sanitizeAverageMoney()
    val includedHolidayWorked = details.grossHolidayPremium.sanitizeAverageMoney()
    val includedSunday = details.grossSundayPremium.sanitizeAverageMoney()
    val includedDifficultConditions = details.grossDifficultWorkConditions.sanitizeAverageMoney()
    val includedSeniority = details.grossSeniorityBonus.sanitizeAverageMoney()
    val includedOtherWage = safeGrossAdditions.bonusGross.sanitizeAverageMoney()
    val excludedGross = details.grossSickPay.sanitizeAverageMoney() +
        details.grossVacationPay.sanitizeAverageMoney() +
        details.grossPaidHoliday.sanitizeAverageMoney() +
        details.grossCoveredDeficitPay.sanitizeAverageMoney() +
        excludeWhen(!options.includeMealAllowanceInAverage, details.grossMealAddition) +
        excludeWhen(!options.includeRegressInAverage, details.grossRegressAddition)
    val excludedHours = details.sickHours +
        details.vacationHours +
        details.paidHolidayHours +
        details.coveredDeficitHours

    return RollingPayBaseMonth(
        year = year,
        month = month,
        grossForAverage = includedRegular +
            includedOvertime +
            includedNight +
            includedHolidayWorked +
            includedSunday +
            includedDifficultConditions +
            includedSeniority +
            includedMeal +
            includedRegress +
            includedOtherWage,
        hoursForAverage = details.paidWorkHours.coerceAtLeast(0),
        minutesForAverage = details.paidWorkMinutes.coerceAtLeast(0),
        source = source,
        excludedCompensationGross = excludedGross,
        excludedCompensationHours = excludedHours.coerceAtLeast(0),
        includedRegularGross = includedRegular + includedDifficultConditions,
        includedOvertimeGross = includedOvertime,
        includedNightPremiumGross = includedNight,
        includedHolidayWorkedGross = includedHolidayWorked,
        includedSundayPremiumGross = includedSunday,
        includedSeniorityGross = includedSeniority,
        includedMealAllowanceGross = includedMeal,
        includedRegressGross = includedRegress,
        includedOtherWageGross = includedOtherWage,
        includeMealAllowanceInAverage = options.includeMealAllowanceInAverage,
        includeRegressInAverage = options.includeRegressInAverage
    )
}

fun RollingPayBaseCalculation.toDebugTrace(
    targetMonth: MonthKey,
    anchor: KnownPayBaseAnchor? = null
): PayBaseCalculationDebugTrace {
    val includedMonths = months.map { MonthKey(it.year, it.month) }
    val anchorDifference = anchor?.let { it.grossHourlyBase - hourlyBase }
    val anchorPercentageDifference = anchorDifference?.let { difference ->
        if (hourlyBase > 0.0) difference / hourlyBase * 100.0 else null
    }
    return PayBaseCalculationDebugTrace(
        targetMonth = targetMonth,
        calculationPeriodStart = includedMonths.firstOrNull(),
        calculationPeriodEnd = includedMonths.lastOrNull(),
        includedMonths = includedMonths,
        months = months,
        totalIncludedGross = grossTotal,
        totalIncludedHours = hoursTotal,
        calculatedAverageGrossHourlyBase = hourlyBase,
        source = calculationSource(months),
        anchorMonth = anchor?.month,
        anchorGrossHourlyBase = anchor?.grossHourlyBase,
        differenceFromCalculated = anchorDifference,
        percentageDifference = anchorPercentageDifference
    )
}

fun calculateManualAnchorGrossHourlyBase(grossAmount: Double, hours: Int): Double? {
    if (!grossAmount.isFinite() || grossAmount <= 0.0 || hours <= 0) return null
    return grossAmount / hours.toDouble()
}

private fun calculationSource(months: List<RollingPayBaseMonth>): PayBaseCalculationSource {
    val sources = months.map { it.source }.toSet()
    return when {
        sources == setOf(PayBaseMonthSource.REAL) && months.size == 12 -> PayBaseCalculationSource.REAL_12_MONTHS
        sources == setOf(PayBaseMonthSource.SIMULATED) -> PayBaseCalculationSource.SIMULATED
        sources.all { it == PayBaseMonthSource.KNOWN_BASE || it == PayBaseMonthSource.CARRY_FORWARD } -> {
            PayBaseCalculationSource.MANUAL_ANCHOR
        }
        else -> PayBaseCalculationSource.MIXED_REAL_AND_SIMULATED
    }
}

private fun excludeWhen(shouldExclude: Boolean, amount: Double): Double {
    return if (shouldExclude) amount.sanitizeAverageMoney() else 0.0
}

private fun Double.sanitizeAverageMoney(): Double {
    return if (isFinite() && this > 0.0) this else 0.0
}
