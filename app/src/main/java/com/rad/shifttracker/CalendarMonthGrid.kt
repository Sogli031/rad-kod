package com.rad.shifttracker

import java.time.YearMonth

internal fun getMonthMatrix(year: Int, month: Int): List<List<Int>> {
    val yearMonth = YearMonth.of(year, month)
    val firstDay = yearMonth.atDay(1)
    val startOffset = firstDay.dayOfWeek.value - 1
    val daysInMonth = yearMonth.lengthOfMonth()
    val cells = MutableList(startOffset) { 0 } + (1..daysInMonth).toList()
    return cells.chunked(7).map { week ->
        if (week.size == 7) week else week + List(7 - week.size) { 0 }
    }
}
