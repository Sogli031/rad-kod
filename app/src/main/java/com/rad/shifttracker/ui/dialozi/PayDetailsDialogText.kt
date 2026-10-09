package com.rad.shifttracker.ui.dialozi

import java.util.Locale
import com.rad.shifttracker.osnova.CONFIG
import com.rad.shifttracker.osnova.formatMoney2
import com.rad.shifttracker.osnova.HOURLY_VALUE_BEFORE_JANUARY_2026
import com.rad.shifttracker.osnova.HOURLY_VALUE_FROM_JANUARY_2026
import com.rad.shifttracker.osnova.MONTH_NAMES
import com.rad.shifttracker.obracun.MonthlyReceipt
import com.rad.shifttracker.smene.MonthKey
import com.rad.shifttracker.smene.monthYearText

internal const val PAY_DETAILS_WORK_HOURS_LABEL = "Rad u režiji"
internal const val PAY_DETAILS_MIN_BOTTOM_SAFE_PADDING_DP = 72

internal const val PAY_DETAILS_RECEIPT_UNAVAILABLE_TEXT = "Nedostaje obračun prethodnog meseca."


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
    return "Isplata u ${monthLocativeText(receipt.paymentMonth)} ${receipt.paymentMonth.year}"
}

internal fun payrollMonthTitleText(receipt: MonthlyReceipt): String {
    return "Obračun za ${monthYearText(receipt.payrollMonth)}"
}

/** Samo kad plata nije izračunata; inače nema opisa. */
internal fun payDetailsReceiptDescriptionText(receipt: MonthlyReceipt): String? =
    if (receipt.isSalaryAvailable) null else PAY_DETAILS_RECEIPT_UNAVAILABLE_TEXT

internal fun payrollMonthNameText(receipt: MonthlyReceipt): String = monthNameText(receipt.payrollMonth)

internal fun payDetailsNetSalaryLabel(receipt: MonthlyReceipt): String {
    return "Neto plata za ${monthNameText(receipt.payrollMonth)}"
}

internal fun payDetailsRegressLabel(receipt: MonthlyReceipt): String {
    return "Regres za ${monthNameText(receipt.paymentMonth)}"
}

/** Iznos kao u ostatku prozora isplate: „51.996,94“ (tačka za hiljade, zarez za decimale). */
internal fun srMoney(value: Double): String {
    val safe = if (value.isFinite()) value else 0.0
    return String.format(Locale.US, "%,.2f", safe).replace(",", " ").replace(".", ",").replace(" ", ".")
}

/** Kao [srMoney], sa znakom: „+164,49“ ili „−1.545,52“. */
internal fun signedSrMoney(value: Double): String {
    val prefix = if (value < 0.0) "−" else "+"
    return prefix + srMoney(kotlin.math.abs(value))
}

/** „Ukupno za isplatu 20.11.“: dan konačne isplate u mesecu isplate. */
internal fun payDetailsTotalOn20Label(receipt: MonthlyReceipt): String {
    return "Ukupno za isplatu 20.%02d.".format(receipt.paymentMonth.month)
}

internal fun payDetailsTotalLabel(receipt: MonthlyReceipt): String {
    return "Ukupno za isplatu u ${monthLocativeText(receipt.paymentMonth)}"
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
