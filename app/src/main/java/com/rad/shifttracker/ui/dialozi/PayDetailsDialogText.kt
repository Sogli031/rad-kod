package com.rad.shifttracker.ui.dialozi

import java.util.Locale
import com.rad.shifttracker.CONFIG
import com.rad.shifttracker.formatMoney2
import com.rad.shifttracker.obracun.HOURLY_VALUE_BEFORE_JANUARY_2026
import com.rad.shifttracker.obracun.HOURLY_VALUE_FROM_JANUARY_2026
import com.rad.shifttracker.MONTH_NAMES
import com.rad.shifttracker.obracun.MonthlyReceipt
import com.rad.shifttracker.obracun.fundAbsenceMinutes
import com.rad.shifttracker.smene.MonthKey

internal const val PAY_DETAILS_WORK_HOURS_LABEL = "Rad u režiji"
internal const val PAY_DETAILS_MIN_BOTTOM_SAFE_PADDING_DP = 72

internal const val PAY_DETAILS_RECEIPT_UNAVAILABLE_TEXT = "Nedostaje obračun prethodnog meseca."

/**
 * Šifra 96 na listiću nije kazna nego zaokruživanje: režija se piše u celim satima
 * naviše, a ostatak minuta se skida ovde po satnici sa minulim radom. Listić 07/2026:
 * 31.07. rađeno 4h30 → 5h režije, 3h izostanaka i −330 din (30 min × 637,32 × 1,035).
 */

/**
 * Procena stavke „Izostanci 1" iz ostatka minuta izostanka u mesecu: minuti preko
 * celih sati × satnica × (1 + stopa minulog rada), zaokruženo na ceo dinar kao na listiću.
 */
internal fun estimateAbsenceRoundingGross(
    fundAbsenceMinutes: Int,
    grossHourlyRate: Double,
    seniorityTotalRate: Double
): Int {
    val remainderMinutes = fundAbsenceMinutes.coerceAtLeast(0) % MINUTES_PER_HOUR_FOR_TEXT
    if (remainderMinutes == 0 || !grossHourlyRate.isFinite() || grossHourlyRate <= 0.0) return 0
    val rate = grossHourlyRate * (1.0 + seniorityTotalRate.coerceAtLeast(0.0))
    return Math.round(rate * remainderMinutes / MINUTES_PER_HOUR_FOR_TEXT).toInt()
}

private const val MINUTES_PER_HOUR_FOR_TEXT = 60

internal fun monthYearText(key: MonthKey): String {
    val monthName = MONTH_NAMES.getOrElse(key.month) { "Mesec" }
    return "$monthName ${key.year}"
}

private fun monthNameText(key: MonthKey): String {
    return MONTH_NAMES.getOrElse(key.month) { "Mesec" }
}

internal fun monthLocativeText(key: MonthKey): String {
    return when (key.month) {
        1 -> "Januaru"
        2 -> "Februaru"
        3 -> "Martu"
        4 -> "Aprilu"
        5 -> "Maju"
        6 -> "Junu"
        7 -> "Julu"
        8 -> "Avgustu"
        9 -> "Septembru"
        10 -> "Oktobru"
        11 -> "Novembru"
        12 -> "Decembru"
        else -> monthNameText(key)
    }
}

internal fun paymentMonthTitleText(receipt: MonthlyReceipt): String {
    return "Isplata u ${monthYearText(receipt.paymentMonth)}"
}

internal fun payrollMonthTitleText(receipt: MonthlyReceipt): String {
    return "Obračun za ${monthYearText(receipt.payrollMonth)}"
}

/** Samo kad plata nije izračunata; inače nema opisa. */
internal fun payDetailsReceiptDescriptionText(receipt: MonthlyReceipt): String? =
    if (receipt.isSalaryAvailable) null else PAY_DETAILS_RECEIPT_UNAVAILABLE_TEXT

internal fun payDetailsNetSalaryLabel(receipt: MonthlyReceipt): String {
    return "Neto plata za ${monthNameText(receipt.payrollMonth)}"
}

internal fun payDetailsRegressLabel(receipt: MonthlyReceipt): String {
    return "Regres za ${monthNameText(receipt.paymentMonth)}"
}

internal fun payDetailsTransportLabel(receipt: MonthlyReceipt): String {
    return "Prevoz za ${monthNameText(receipt.payrollMonth)}"
}

internal fun payDetailsTotalLabel(receipt: MonthlyReceipt): String {
    return "Ukupno za isplatu u ${monthLocativeText(receipt.paymentMonth)}"
}

internal fun payDetailsTransportAmountText(receipt: MonthlyReceipt): String {
    return moneyText(receipt.transportAmount)
}

internal fun defaultHourlyHistoryInfoText(): String {
    return "Fiksno: pre januara 2026. koristi se ${formatHourlyValue(HOURLY_VALUE_BEFORE_JANUARY_2026)} " +
        "${CONFIG.pay.currency}, od januara 2026. koristi se " +
        "${formatHourlyValue(HOURLY_VALUE_FROM_JANUARY_2026)} ${CONFIG.pay.currency}."
}

internal fun moneyText(value: Double): String {
    return "${formatMoney2(value)} ${CONFIG.pay.currency}"
}

internal fun signedMoneyText(value: Double): String {
    val prefix = if (value > 0.0) "+" else ""
    return "$prefix${formatMoney2(value)} ${CONFIG.pay.currency}"
}

internal fun deductionMoneyText(value: Double): String {
    return if (value > 0.0) {
        "-${moneyText(value)}"
    } else {
        moneyText(0.0)
    }
}

private fun formatHourlyValue(value: Double): String {
    val formatted = String.format(Locale.US, "%.2f", value)
    return formatted.trimEnd('0').trimEnd('.')
}
