package com.rad.shifttracker

import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.roundToInt

enum class SeniorityInputMode(val jsonValue: String) {
    KNOWN_START("known_start"),
    PAYSLIP_PERCENT("payslip_percent");

    companion object {
        fun fromJson(value: String?): SeniorityInputMode {
            return entries.firstOrNull { it.jsonValue == value || it.name == value } ?: KNOWN_START
        }
    }
}

enum class SeniorityIncreaseFactor(
    val jsonValue: String,
    val label: String,
    val multiplier: Double
) {
    NONE("none", "Nema", 1.0),
    TWELVE_FOURTEEN("12_14", "12/14", 14.0 / 12.0),
    TWELVE_FIFTEEN("12_15", "12/15", 15.0 / 12.0),
    TWELVE_SIXTEEN("12_16", "12/16", 16.0 / 12.0),
    TWELVE_EIGHTEEN("12_18", "12/18", 18.0 / 12.0);

    companion object {
        fun fromJson(value: String?): SeniorityIncreaseFactor {
            return entries.firstOrNull { it.jsonValue == value || it.name == value } ?: NONE
        }
    }
}

data class SeniorityPreview(
    val effectiveMonths: Int,
    val accountingMonths: Double,
    val fullAccountingYears: Int,
    val percent: Double
)

data class PayslipSeniorityEstimate(
    val accountingFullYears: Int,
    val estimatedEffectiveYears: Double,
    val estimatedStartYear: Int,
    val rounded: Boolean
)

fun calculateKnownStartSeniorityPreview(
    startYear: Int,
    startMonth: Int,
    targetYear: Int,
    targetMonth: Int,
    increaseFactor: SeniorityIncreaseFactor,
    ratePerYear: Double
): SeniorityPreview {
    val effectiveMonths = fullMonthsBetween(
        startYear = startYear,
        startMonth = startMonth,
        targetYear = targetYear,
        targetMonth = targetMonth
    )
    return seniorityPreviewFromEffectiveMonths(
        effectiveMonths = effectiveMonths,
        increaseFactor = increaseFactor,
        ratePerYear = ratePerYear
    )
}

fun estimateSeniorityFromPayslipPercent(
    lastPayslipYear: Int,
    @Suppress("UNUSED_PARAMETER") lastPayslipMonth: Int,
    payslipPercent: Double,
    increaseFactor: SeniorityIncreaseFactor,
    ratePerYear: Double
): PayslipSeniorityEstimate {
    val safeRatePercent = ratePerYearPercent(ratePerYear)
    val safePercent = payslipPercent.takeIf { it.isFinite() }?.coerceAtLeast(0.0) ?: 0.0
    val rawAccountingYears = safePercent / safeRatePercent
    val accountingFullYears = rawAccountingYears.roundToInt().coerceAtLeast(0)
    val estimatedEffectiveYears = accountingFullYears / increaseFactor.multiplier
    val safeLastYear = lastPayslipYear.coerceAtLeast(0)
    val estimatedStartYear = (safeLastYear - floor(estimatedEffectiveYears).toInt()).coerceAtLeast(0)
    val rounded = abs(rawAccountingYears - accountingFullYears) > ROUNDING_WARNING_YEAR_TOLERANCE

    return PayslipSeniorityEstimate(
        accountingFullYears = accountingFullYears,
        estimatedEffectiveYears = estimatedEffectiveYears,
        estimatedStartYear = estimatedStartYear,
        rounded = rounded
    )
}

internal fun calculatePayslipAnchoredSeniorityYears(
    lastPayslipYear: Int,
    lastPayslipMonth: Int,
    payslipPercent: Double,
    targetYear: Int,
    targetMonth: Int,
    increaseFactor: SeniorityIncreaseFactor,
    ratePerYear: Double
): Int {
    if (lastPayslipYear <= 0) return 0
    val estimate = estimateSeniorityFromPayslipPercent(
        lastPayslipYear = lastPayslipYear,
        lastPayslipMonth = lastPayslipMonth,
        payslipPercent = payslipPercent,
        increaseFactor = increaseFactor,
        ratePerYear = ratePerYear
    )
    val elapsedEffectiveMonths = fullMonthsBetween(
        startYear = lastPayslipYear,
        startMonth = lastPayslipMonth,
        targetYear = targetYear,
        targetMonth = targetMonth,
        allowNegative = true
    )
    val accountingMonths = estimate.accountingFullYears * MONTHS_PER_YEAR +
        elapsedEffectiveMonths * increaseFactor.multiplier
    return floorAccountingYears(accountingMonths).coerceAtLeast(0)
}

internal fun seniorityPreviewFromEffectiveMonths(
    effectiveMonths: Int,
    increaseFactor: SeniorityIncreaseFactor,
    ratePerYear: Double
): SeniorityPreview {
    val safeEffectiveMonths = effectiveMonths.coerceAtLeast(0)
    val accountingMonths = safeEffectiveMonths * increaseFactor.multiplier
    val fullYears = floorAccountingYears(accountingMonths)
    val percent = fullYears * ratePerYearPercent(ratePerYear)
    return SeniorityPreview(
        effectiveMonths = safeEffectiveMonths,
        accountingMonths = accountingMonths,
        fullAccountingYears = fullYears,
        percent = percent
    )
}

private fun fullMonthsBetween(
    startYear: Int,
    startMonth: Int,
    targetYear: Int,
    targetMonth: Int,
    allowNegative: Boolean = false
): Int {
    if (startYear <= 0) return 0
    val rawMonths = (targetYear - startYear) * MONTHS_PER_YEAR +
        (targetMonth.coerceIn(1, 12) - startMonth.coerceIn(1, 12))
    return if (allowNegative) rawMonths else rawMonths.coerceAtLeast(0)
}

private fun floorAccountingYears(accountingMonths: Double): Int {
    return floor((accountingMonths + FLOAT_EPSILON) / MONTHS_PER_YEAR).toInt()
}

private fun ratePerYearPercent(ratePerYear: Double): Double {
    return if (ratePerYear.isFinite() && ratePerYear > 0.0) {
        ratePerYear * PERCENT_FACTOR
    } else {
        CONFIG.pay.minuliRadPerYear * PERCENT_FACTOR
    }
}

private const val MONTHS_PER_YEAR = 12
private const val PERCENT_FACTOR = 100.0
private const val FLOAT_EPSILON = 1e-9
private const val ROUNDING_WARNING_YEAR_TOLERANCE = 0.05
