package com.rad.shifttracker

import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

const val MAX_DAY_HOURS = 24
private val REGULAR_SHORTAGE_MINUTE_OPTIONS = listOf(0, 15, 30, 45)

fun formatCurrency(value: Double): String {
    val rounded = value.roundToInt().toDouble()
    val formatted = String.format(Locale.US, "%,.0f", rounded)
    return formatted.replace(",", ".")
}

fun formatMoney2(value: Double): String {
    val safeValue = if (value.isFinite()) value else 0.0
    return String.format(Locale.US, "%.2f", safeValue)
}

fun formatNumber(value: Double): String {
    val formatted = String.format(Locale.US, "%,.0f", value)
    return formatted.replace(",", ".")
}

fun normalizeDayHours(hours: Int): Int = hours.coerceIn(0, MAX_DAY_HOURS)

fun normalizeRegularShortageMinutes(minutes: Int): Int {
    return if (minutes in REGULAR_SHORTAGE_MINUTE_OPTIONS) minutes else 0
}

internal fun regularShortageMinuteOptions(): List<Int> = REGULAR_SHORTAGE_MINUTE_OPTIONS

internal fun regularShortageMinutesAllowed(workHours: Int): Boolean {
    return normalizeDayHours(workHours) in 1 until CONFIG.pay.defaultHours
}

internal fun normalizeRegularShortageMinutesForWorkHours(workHours: Int, minutes: Int): Int {
    return if (regularShortageMinutesAllowed(workHours)) normalizeRegularShortageMinutes(minutes) else 0
}

internal fun formatShortageMinutes(minutes: Int): String {
    val safeMinutes = normalizeRegularShortageMinutes(minutes)
    return if (safeMinutes == 0) "0" else "${safeMinutes}min"
}

fun formatHours(
    totalHours: Int,
    showPlusForPositive: Boolean = false,
): String {
    val sign = when {
        totalHours < 0 -> "-"
        showPlusForPositive && totalHours > 0 -> "+"
        else -> ""
    }
    return "$sign${abs(totalHours)}h"
}

fun formatHoursAndMinutes(
    totalMinutes: Int,
    showPlusForPositive: Boolean = false,
): String {
    if (totalMinutes == 0) return "0h"

    val sign = when {
        totalMinutes < 0 -> "-"
        showPlusForPositive && totalMinutes > 0 -> "+"
        else -> ""
    }
    val absoluteMinutes = abs(totalMinutes)
    val hours = absoluteMinutes / 60
    val minutes = absoluteMinutes % 60

    return when {
        hours == 0 -> "${sign}${minutes}min"
        minutes == 0 -> "${sign}${hours}h"
        else -> "${sign}${hours}h ${minutes}min"
    }
}
