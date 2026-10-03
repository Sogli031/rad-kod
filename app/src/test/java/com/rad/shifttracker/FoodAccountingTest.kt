package com.rad.shifttracker

import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Test

class FoodAccountingTest {

    @Test
    fun `food accounting date moves third shift to next calendar day`() {
        assertEquals(
            LocalDate.of(2027, 2, 27),
            foodAccountingDate(LocalDate.of(2027, 2, 26), Shift.THIRD)
        )
        assertEquals(
            LocalDate.of(2027, 2, 26),
            foodAccountingDate(LocalDate.of(2027, 2, 26), Shift.FIRST)
        )
        assertEquals(
            LocalDate.of(2027, 2, 26),
            foodAccountingDate(LocalDate.of(2027, 2, 26), Shift.SECOND)
        )
    }
}
