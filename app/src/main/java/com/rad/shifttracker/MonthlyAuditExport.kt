package com.rad.shifttracker

import kotlin.math.abs
import kotlin.math.max

private const val MONEY_TOLERANCE = 0.01
private const val PERCENT_DIVISOR = 100.0

internal fun exportedMonthAuditStatusMap(
    target: MonthKey,
    summary: MonthSummary
): Map<String, Any?> {
    val hasMonthlySummary = summary.year == target.year && summary.month == target.month
    return linkedMapOf(
        "monthLabel" to monthYearText(target),
        "hasMonthlySummary" to hasMonthlySummary,
        "isAuditable" to hasMonthlySummary,
        "reason" to if (hasMonthlySummary) null else "Monthly summary is not available for exported month."
    )
}

internal fun grossAdditionsBreakdownMap(
    payBreakdown: MonthlyNetPayBreakdown,
    contributionsPercent: Double,
    taxPercent: Double
): Map<String, Any?> {
    val knownGrossAdditions = payBreakdown.grossMealAddition +
        payBreakdown.grossRegressAddition +
        payBreakdown.grossBonusAddition
    return linkedMapOf(
        "mealGrossApplied" to payBreakdown.mealGrossApplied,
        "mealGrossSource" to payBreakdown.mealGrossSource.name,
        "regressGross" to payBreakdown.grossRegressAddition,
        "bonusGross" to payBreakdown.grossBonusAddition,
        "otherGrossAdditions" to (payBreakdown.grossAdditionsTotal - knownGrossAdditions).zeroIfTiny(),
        "totalGrossAdditions" to payBreakdown.grossAdditionsTotal,
        "grossUpNetRetentionRate" to grossUpNetRetentionRate(contributionsPercent, taxPercent),
        "grossUpFormulaDescription" to
            "mealEntitlementGross = payrollRound(mealEntitlementNet / grossUpNetRetentionRate)"
    )
}

internal fun mealBreakdownMap(payBreakdown: MonthlyNetPayBreakdown): Map<String, Any?> {
    val entitlement = payBreakdown.mealEntitlementCalculation
    return linkedMapOf(
        "mealCalculationMode" to payBreakdown.mealCalculationMode,
        "mealEntitlementSource" to payBreakdown.mealEntitlementSource.name,
        "mealEntitlementNetOverride" to payBreakdown.mealEntitlementNetOverride,
        "mealEntitlementNetApplied" to payBreakdown.mealEntitlementNetApplied,
        "mealPricePeriods" to entitlement.pricePeriods.map(::mealPricePeriodMap),
        "recognizedMealCount" to entitlement.recognizedMealCount,
        "recognizedMealDates" to entitlement.recognizedMeals.map(::recognizedMealMap),
        "estimatedRecognizedMealCount" to payBreakdown.estimatedRecognizedMealCount,
        "estimatedMealEntitlementNet" to payBreakdown.estimatedMealEntitlementNet,
        "estimateAffectsPayroll" to payBreakdown.estimateAffectsPayroll,
        "rejectedMealDatesAndReasons" to entitlement.rejectedMeals.map(::rejectedMealMap),
        "mealPriceNetPerMeal" to entitlement.mealPriceNetPerMeal,
        "mealEntitlementNet" to payBreakdown.mealEntitlementNet,
        "mealEntitlementGross" to payBreakdown.mealEntitlementGross,
        "mealSpentNetEntered" to payBreakdown.manualMealSpentNet,
        "mealSpentNetApplied" to payBreakdown.mealSpentNetApplied,
        "mealNetDeduction" to payBreakdown.mealNetDeduction,
        "mealUnusedNet" to payBreakdown.mealUnusedNet,
        "mealSpendingDefaultedToEntitlement" to payBreakdown.mealSpendingDefaultedToEntitlement,
        "mealGrossSource" to payBreakdown.mealGrossSource.name
    )
}

internal fun mealPricePeriodMap(period: MealPricePeriod): Map<String, Any?> = linkedMapOf(
    "validFrom" to period.validFrom.toString(),
    "netAmountPerMeal" to period.netAmountPerMeal
)

private fun recognizedMealMap(decision: RecognizedMealDecision): Map<String, Any?> = linkedMapOf(
    "date" to decision.accountingDate.toString(),
    "workDate" to decision.workDate.toString(),
    "mealCount" to decision.mealCount,
    "reasons" to decision.reasons,
    "netAmountPerMeal" to decision.netAmountPerMeal,
    "entitlementNet" to decision.entitlementNet
)

private fun rejectedMealMap(decision: RejectedMealDecision): Map<String, Any?> = linkedMapOf(
    "date" to decision.accountingDate.toString(),
    "workDate" to decision.workDate?.toString(),
    "reason" to decision.reason
)

internal fun grossNegativeAdjustmentsBreakdownMap(
    payBreakdown: MonthlyNetPayBreakdown
): Map<String, Any?> {
    return linkedMapOf(
        "absencePenaltyGross" to payBreakdown.absencePenaltyGross,
        "absencePenaltyLabel" to "Izostanci 1",
        "totalGrossNegativeAdjustments" to payBreakdown.grossNegativeAdjustmentsTotal,
        "formulaDescription" to
            "grossTotal = grossEarningsBeforeSeniority + grossSeniorityBonus + grossAdditionsTotal - " +
                "absencePenaltyGross"
    )
}

internal fun netDeductionsBreakdownMap(
    payBreakdown: MonthlyNetPayBreakdown
): Map<String, Any?> {
    val otherDeductions = payBreakdown.netDeductionsTotal -
        payBreakdown.mealNetDeduction -
        payBreakdown.unionDuesNet
    return linkedMapOf(
        "mealNetDeduction" to payBreakdown.mealNetDeduction,
        "advanceNet" to 0.0,
        "creditDeductionNet" to 0.0,
        "unionDuesNet" to payBreakdown.unionDuesNet,
        "otherDeductionsNet" to otherDeductions.zeroIfTiny(),
        "totalNetDeductions" to payBreakdown.netDeductionsTotal,
        "displayPayFormulaDescription" to
            "displayPay = max(0, netPayBeforeDeductions - netDeductionsTotal)"
    )
}

internal fun seniorityBreakdownMap(
    summary: MonthSummary,
    payBreakdown: MonthlyNetPayBreakdown
): Map<String, Any?> {
    val breakdown = summary.seniorityBreakdown
    return linkedMapOf(
        "baseHourlyGross" to breakdown.baseHourlyGross,
        "regularWorkedBaseMinutes" to breakdown.regularWorkedBaseMinutes,
        "regularWorkedBaseGross" to breakdown.regularWorkedBaseGross,
        "publicHolidayWorkedBaseMinutes" to breakdown.publicHolidayWorkedBaseMinutes,
        "publicHolidayWorkedBaseGross" to breakdown.publicHolidayWorkedBaseGross,
        "overtimeBaseMinutes" to breakdown.overtimeBaseMinutes,
        "overtimeBaseGross" to breakdown.overtimeBaseGross,
        "seniorityEligibleMinutes" to breakdown.seniorityEligibleMinutes,
        "seniorityEligibleBaseGross" to breakdown.seniorityEligibleBaseGross,
        "completedYearsOfService" to breakdown.completedYearsOfService,
        "seniorityRatePerYear" to breakdown.seniorityRatePerYear,
        "seniorityTotalRate" to breakdown.seniorityTotalRate,
        "grossSeniorityBonus" to breakdown.grossSeniorityBonus,
        "netSeniorityBonus" to payBreakdown.netSeniorityBonus,
        "publicHolidayBaseIncludedInRegular" to breakdown.publicHolidayBaseIncludedInRegular,
        "includedCategories" to breakdown.includedCategories,
        "excludedCategories" to breakdown.excludedCategories
    )
}

internal fun auditFormulasMap(
    repo: ShiftRepository,
    target: MonthKey
): Map<String, Any?> {
    return linkedMapOf(
        "baseHourlyGrossFormula" to "baseHourlyGross = hourlyValue * payCoefficient",
        "regularWorkGrossFormula" to
            "regularWorkedBaseGross = regularWorkedBaseMinutes / 60 * baseHourlyGross",
        "publicHolidayWorkedBaseGrossFormula" to
            "publicHolidayWorkedBaseGross = publicHolidayWorkedBaseMinutes / 60 * baseHourlyGross",
        "overtimeBaseGrossFormula" to
            "overtimeBaseGross = overtimeBaseMinutes / 60 * baseHourlyGross",
        "nightWorkBonusFormula" to "grossNightPremium = nightMinutes / 60 * baseHourlyGross * (shift3Premium - 1)",
        "overtimeBonusFormula" to "grossOvertimePremium = overtimeMinutes / 60 * baseHourlyGross * (overtimePremium - 1)",
        "holidayWorkBonusFormula" to "grossHolidayPremium = holidayWorkMinutes / 60 * baseHourlyGross * holidayBonus",
        "paidLeaveFormula" to
            "grossPaidLeave = paidLeaveMinutes / 60 * paidLeaveHourlyAverageBase * 1.00",
        "seniorityEligibleBaseGrossFormula" to
            "seniorityEligibleBaseGross = sum(base gross amounts of seniority-eligible actual work categories)",
        "seniorityBonusFormula" to
            "grossSeniorityBonus = seniorityEligibleBaseGross * completedYearsOfService * " +
                "seniorityRatePerYear",
        "grossTotalFormula" to
            "grossTotal = grossEarningsBeforeSeniority + grossSeniorityBonus + grossAdditionsTotal - " +
                "absencePenaltyGross",
        "mealAppliedFormula" to
            "mealSpentNetApplied = mealSpentNetEntered when entered, otherwise maximum automatic estimate; " +
                "without a confirmed override mealEntitlementNetApplied equals mealSpentNetApplied",
        "mealGrossFormula" to
            "mealEntitlementGross = grossUp(mealEntitlementNetApplied)",
        "mealDeductionFormula" to "mealNetDeduction = mealSpentNetApplied",
        "absencePenaltyFormula" to
            "absencePenaltyGross is subtracted before employee contributions and tax; it changes no minutes",
        "employeeContributionsFormula" to "employeeContributions = grossTotal * employeeContributionsRate",
        "taxableBaseFormula" to "taxableBase = max(0, grossTotal - taxRelief)",
        "taxFormula" to "tax = taxableBase * taxRate",
        "netBeforeDeductionsFormula" to
            "netPayBeforeDeductions = max(0, grossTotal - employeeContributions - tax)",
        "displayPayFormula" to "displayPay = max(0, netPayBeforeDeductions - netDeductionsTotal)",
        "hourlyValue" to repo.getHourlyValueForMonth(target.year, target.month),
        "payCoefficient" to repo.payCoefficientValue,
        "employeeContributionsRate" to percentRate(repo.employeeContributionsPercentValue),
        "taxRate" to percentRate(repo.taxPercentValue),
        "nightWorkMultiplier" to CONFIG.pay.shift3Premium,
        "nightWorkBonusRate" to (CONFIG.pay.shift3Premium - 1.0),
        "overtimeMultiplier" to CONFIG.pay.overtimePremium,
        "overtimeBonusRate" to (CONFIG.pay.overtimePremium - 1.0),
        "holidayWorkBonusRate" to CONFIG.pay.holidayBonus,
        "seniorityRatePerYear" to CONFIG.pay.minuliRadPerYear,
        "grossUpNetRetentionRate" to grossUpNetRetentionRate(
            repo.employeeContributionsPercentValue,
            repo.taxPercentValue
        )
    )
}

internal fun auditChecksMap(
    target: MonthKey,
    payBreakdown: MonthlyNetPayBreakdown,
    payDetails: MonthlyPayDetails,
    grossAdditionsBreakdown: Map<String, Any?>,
    netDeductionsBreakdown: Map<String, Any?>
): Map<String, Any?> {
    val warnings = mutableListOf<String>()
    val grossAdditionsParts = grossAdditionsBreakdown.doubleValue("mealGrossApplied") +
        grossAdditionsBreakdown.doubleValue("regressGross") +
        grossAdditionsBreakdown.doubleValue("bonusGross") +
        grossAdditionsBreakdown.doubleValue("otherGrossAdditions")
    val grossAdditionsMatch = moneyMatches(
        grossAdditionsBreakdown.doubleValue("totalGrossAdditions"),
        grossAdditionsParts
    ) && moneyMatches(payBreakdown.grossAdditionsTotal, grossAdditionsBreakdown.doubleValue("totalGrossAdditions"))
    if (!grossAdditionsMatch) {
        warnings += "Gross additions total does not match grossAdditionsBreakdown for ${monthYearText(target)}."
    }

    val netDeductionsParts = netDeductionsBreakdown.doubleValue("mealNetDeduction") +
        netDeductionsBreakdown.doubleValue("advanceNet") +
        netDeductionsBreakdown.doubleValue("creditDeductionNet") +
        netDeductionsBreakdown.doubleValue("unionDuesNet") +
        netDeductionsBreakdown.doubleValue("otherDeductionsNet")
    val netDeductionsMatch = moneyMatches(
        netDeductionsBreakdown.doubleValue("totalNetDeductions"),
        netDeductionsParts
    ) && moneyMatches(payBreakdown.netDeductionsTotal, netDeductionsBreakdown.doubleValue("totalNetDeductions"))
    if (!netDeductionsMatch) {
        warnings += "Net deductions total does not match netDeductionsBreakdown for ${monthYearText(target)}."
    }

    val grossTotalParts = payBreakdown.grossEarningsBeforeSeniority +
        payBreakdown.grossSeniorityBonus +
        payBreakdown.grossAdditionsTotal -
        payBreakdown.absencePenaltyGross.toDouble()
    val grossTotalMatchesParts = moneyMatches(payBreakdown.grossTotal, grossTotalParts)
    if (!grossTotalMatchesParts) {
        warnings += "Gross total does not match base, seniority, and additions for ${monthYearText(target)}."
    }

    val expectedDisplayPay = max(0.0, payBreakdown.netPayBeforeDeductions - payBreakdown.netDeductionsTotal)
    val displayPayMatchesFormula = moneyMatches(payBreakdown.displayPay, expectedDisplayPay)
    if (!displayPayMatchesFormula) {
        warnings += "Display pay does not match net before deductions minus net deductions for ${monthYearText(target)}."
    }

    val fundComparableMinutes = payDetails.totalPaidMinutes - payDetails.overtimeMinutes
    val fundMinutes = payBreakdown.monthlyFundHours * 60
    val workHoursMatchFund = fundMinutes == fundComparableMinutes
    if (!workHoursMatchFund) {
        warnings += "Paid non-overtime hours ${formatHoursAndMinutes(fundComparableMinutes)} do not match monthly " +
            "fund ${formatHoursAndMinutes(fundMinutes)} for ${monthYearText(target)}."
    }

    return linkedMapOf(
        "grossAdditionsMatchesBreakdown" to grossAdditionsMatch,
        "netDeductionsMatchesBreakdown" to netDeductionsMatch,
        "grossTotalMatchesParts" to grossTotalMatchesParts,
        "displayPayMatchesFormula" to displayPayMatchesFormula,
        "workHoursMatchFund" to workHoursMatchFund,
        "warnings" to warnings
    )
}

private fun grossUpNetRetentionRate(
    contributionsPercent: Double,
    taxPercent: Double
): Double {
    return max(0.0, 1.0 - percentRate(contributionsPercent) - percentRate(taxPercent))
}

private fun percentRate(percent: Double): Double {
    return if (percent.isFinite() && percent > 0.0) percent / PERCENT_DIVISOR else 0.0
}

private fun moneyMatches(left: Double, right: Double): Boolean {
    return abs(left - right) <= MONEY_TOLERANCE
}

private fun Map<String, Any?>.doubleValue(key: String): Double {
    return (this[key] as? Number)?.toDouble() ?: 0.0
}

private fun Double.zeroIfTiny(): Double {
    return if (abs(this) <= MONEY_TOLERANCE) 0.0 else this
}
