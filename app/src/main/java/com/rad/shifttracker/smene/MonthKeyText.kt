package com.rad.shifttracker.smene

import com.rad.shifttracker.osnova.MONTH_NAMES

internal fun monthYearText(key: MonthKey): String {
    val monthName = MONTH_NAMES.getOrElse(key.month) { "Mesec" }
    return "$monthName ${key.year}"
}
