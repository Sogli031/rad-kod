package com.rad.shifttracker.obracun

import java.time.LocalDate
import com.rad.shifttracker.smene.MonthKey
import com.rad.shifttracker.smene.Shift

fun foodAccountingDate(shiftDate: LocalDate, shift: Shift): LocalDate {
    return if (shift == Shift.THIRD) shiftDate.plusDays(1) else shiftDate
}

internal fun foodAccountingMonth(shiftDate: LocalDate, shift: Shift): MonthKey {
    val date = foodAccountingDate(shiftDate, shift)
    return MonthKey(date.year, date.monthValue)
}
