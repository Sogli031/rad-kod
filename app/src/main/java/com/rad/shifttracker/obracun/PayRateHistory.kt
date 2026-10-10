package com.rad.shifttracker.obracun

import com.rad.shifttracker.smene.MonthKey
import com.rad.shifttracker.osnova.DEFAULT_PAY_COEFFICIENT
import com.rad.shifttracker.osnova.HOURLY_VALUE_FROM_JANUARY_2026
import com.rad.shifttracker.osnova.HOURLY_VALUE_BEFORE_JANUARY_2026
import com.rad.shifttracker.osnova.HOURLY_VALUE_2024
import com.rad.shifttracker.osnova.HOURLY_VALUE_BEFORE_2024

private const val CLEAN_MAPPING_TOLERANCE = 0.01

/**
 * Vrednost sata sa lista Valjaonice: 185,00 do kraja 2023, pa od januara 195,00 (2024), 214,50 (2025)
 * i 226,00 (2026). Svaka tačka je i granica za upisanu izmenu pre nje: lista 06/2024 (195) važi do
 * kraja 2024, a ne i za 2025–2026, za koje se lista slagala sa ovom tabelom pa izmena nije upisana.
 */
private val DEFAULT_HOURLY_VALUE_POINTS = listOf(
    MonthKey(2024, 1) to HOURLY_VALUE_2024,
    MonthKey(2025, 1) to HOURLY_VALUE_BEFORE_JANUARY_2026,
    MonthKey(2026, 1) to HOURLY_VALUE_FROM_JANUARY_2026
)

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
    return DEFAULT_HOURLY_VALUE_POINTS.lastOrNull { (start, _) -> start <= key }?.second ?: HOURLY_VALUE_BEFORE_2024
}

private fun defaultHourlyValueStart(key: MonthKey): MonthKey? =
    DEFAULT_HOURLY_VALUE_POINTS.lastOrNull { (start, _) -> start <= key }?.first

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
        // Izmena starija od poslednje tačke ugrađene istorije ne važi preko nje.
        ?.takeIf { (key, _) -> defaultHourlyValueStart(target)?.let { key >= it } ?: true }

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
