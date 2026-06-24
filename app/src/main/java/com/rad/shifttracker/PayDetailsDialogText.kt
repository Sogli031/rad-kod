package com.rad.shifttracker

import java.util.Locale

internal const val PAY_DETAILS_WORK_HOURS_LABEL = "Rad u režiji"
internal const val PAY_DETAILS_MIN_BOTTOM_SAFE_PADDING_DP = 72

internal const val RECENT_NET_PAY_AVERAGE_SECTION_LABEL = "Neto prosek 3 poslednje plate"
internal const val RECENT_NET_PAY_AVERAGE_VALUE_LABEL = "Neto prosek"
internal const val RECENT_NET_PAY_AVERAGE_UNAVAILABLE_TEXT =
    "Neto prosek 3 poslednje plate trenutno nije moguće izračunati zbog nepotpunih podataka."
internal const val ANNUAL_NET_PAY_AVERAGE_HINT_TEXT = "Računa samo mesece sa unetim zapisima."
internal const val ANNUAL_NET_PAY_AVERAGE_EMPTY_TEXT = "Nema unetih meseci za godišnji prosek."
internal const val PAY_DETAILS_RECEIPT_UNAVAILABLE_TEXT = "Nedostaje obračun prethodnog meseca."
internal const val PAY_DETAILS_HOURLY_VALUE_LABEL = "Vrednost sata"

internal fun annualNetPayAverageLabels(): List<String> {
    return listOf(
        "Prosek plate",
        "Bez regresa",
        "Bez regresa i treće smene"
    )
}

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

internal fun payDetailsReceiptDescriptionText(receipt: MonthlyReceipt): String {
    if (!receipt.isSalaryAvailable) return PAY_DETAILS_RECEIPT_UNAVAILABLE_TEXT
    val payrollMonth = monthYearText(receipt.payrollMonth)
    return if (receipt.transportAmount > 0.0) {
        "Procena uplate na račun za obračunski mesec $payrollMonth, uključujući odvojenu stavku prevoza."
    } else {
        "Procena uplate na račun za obračunski mesec $payrollMonth."
    }
}

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

internal fun recentNetPayAverageMonthsText(average: RecentNetPayAverage): String {
    return average.monthKeys.joinToString(", ") { key ->
        val monthName = MONTH_NAMES.getOrElse(key.month) { "Mesec" }
        "$monthName ${key.year}"
    }.ifBlank { "Meseci nisu dostupni" }
}

internal fun payDetailsHourlyValueText(value: Double): String {
    return "${formatHourlyValue(value)} ${CONFIG.pay.currency}"
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
