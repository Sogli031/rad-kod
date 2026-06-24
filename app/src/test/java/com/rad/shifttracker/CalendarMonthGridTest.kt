package com.rad.shifttracker

import org.junit.Assert.assertEquals
import org.junit.Test

class CalendarMonthGridTest {
    @Test
    fun `month matrix starts with blanks before first weekday`() {
        val matrix = getMonthMatrix(2026, 2)

        assertEquals(listOf(0, 0, 0, 0, 0, 0, 1), matrix.first())
        assertEquals(5, matrix.size)
    }

    @Test
    fun `month matrix pads last week`() {
        val matrix = getMonthMatrix(2026, 1)

        assertEquals(7, matrix.last().size)
        assertEquals(0, matrix.last().last())
    }
}
