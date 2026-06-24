package com.rad.shifttracker

import java.time.LocalDate

fun foodAccountingDate(shiftDate: LocalDate, shift: Shift): LocalDate {
    return if (shift == Shift.THIRD) shiftDate.plusDays(1) else shiftDate
}

internal fun foodAccountingMonth(shiftDate: LocalDate, shift: Shift): MonthKey {
    val date = foodAccountingDate(shiftDate, shift)
    return MonthKey(date.year, date.monthValue)
}
