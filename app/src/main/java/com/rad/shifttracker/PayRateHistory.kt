package com.rad.shifttracker

const val DEFAULT_PAY_COEFFICIENT = 2.82
const val HOURLY_VALUE_BEFORE_JANUARY_2026 = 214.5
const val HOURLY_VALUE_FROM_JANUARY_2026 = 226.0

private val JANUARY_2026 = MonthKey(2026, 1)
private const val CLEAN_MAPPING_TOLERANCE = 0.01

data class ResolvedHourlyValue(
    val value: Double,
    val sourceYear: Int? = null,
    val sourceMonth: Int? = null
)

data class HourlyValueChange(
    val year: Int,
    val month: Int,
    val value: Double
)

fun defaultHourlyValueForMonth(year: Int, month: Int): Double {
    val key = MonthKey(year, month)
    return if (key >= JANUARY_2026) {
        HOURLY_VALUE_FROM_JANUARY_2026
    } else {
        HOURLY_VALUE_BEFORE_JANUARY_2026
    }
}

fun resolveHourlyValueForMonth(
    year: Int,
    month: Int,
    changes: Map<MonthKey, Double>
): ResolvedHourlyValue {
    val target = MonthKey(year, month)
    val change = changes
        .asSequence()
        .filter { (key, value) -> key <= target && value.isFinite() && value > 0.0 }
        .maxByOrNull { (key, _) -> key }

    return if (change != null) {
        ResolvedHourlyValue(
            value = change.value,
            sourceYear = change.key.year,
            sourceMonth = change.key.month
        )
    } else {
        ResolvedHourlyValue(defaultHourlyValueForMonth(year, month))
    }
}

fun grossHourlyRateForMonth(
    coefficient: Double,
    year: Int,
    month: Int,
    changes: Map<MonthKey, Double>
): Double {
    val safeCoefficient = if (coefficient.isFinite() && coefficient > 0.0) coefficient else DEFAULT_PAY_COEFFICIENT
    return safeCoefficient * resolveHourlyValueForMonth(year, month, changes).value
}

fun coefficientFromLegacyGrossHourlyRate(
    grossHourlyRate: Double,
    importMonth: MonthKey
): Double {
    if (!grossHourlyRate.isFinite() || grossHourlyRate <= 0.0) return DEFAULT_PAY_COEFFICIENT

    val january2026Gross = DEFAULT_PAY_COEFFICIENT * HOURLY_VALUE_FROM_JANUARY_2026
    if (kotlin.math.abs(grossHourlyRate - january2026Gross) <= CLEAN_MAPPING_TOLERANCE) {
        return DEFAULT_PAY_COEFFICIENT
    }

    val pre2026Gross = DEFAULT_PAY_COEFFICIENT * HOURLY_VALUE_BEFORE_JANUARY_2026
    if (kotlin.math.abs(grossHourlyRate - pre2026Gross) <= CLEAN_MAPPING_TOLERANCE) {
        return DEFAULT_PAY_COEFFICIENT
    }

    return grossHourlyRate / defaultHourlyValueForMonth(importMonth.year, importMonth.month)
}
