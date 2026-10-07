package com.rad.shifttracker

import org.junit.Assert.assertEquals
import org.junit.Test
import com.rad.shifttracker.osnova.formatHours
import com.rad.shifttracker.osnova.formatMoney2

class FormattingTest {

    @Test
    fun `formatMoney2 uses two decimals without grouping`() {
        assertEquals("34221.00", formatMoney2(34_221.0))
    }

    @Test
    fun `formatMoney2 handles non finite values`() {
        assertEquals("0.00", formatMoney2(Double.NaN))
    }

    @Test
    fun `formatHours treats value as whole hours`() {
        assertEquals("8h", formatHours(8))
        assertEquals("+2h", formatHours(2, showPlusForPositive = true))
        assertEquals("-3h", formatHours(-3))
    }
}
