package com.rad.shifttracker

import java.time.YearMonth
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MonthlyTaxUpdateTest {

    @Test
    fun `preskace azuriranje kada je tekuci mesec vec obradjen`() {
        val currentMonth = YearMonth.of(2026, 5)

        assertFalse(shouldRunMonthlyTaxUpdate("2026-05", currentMonth))
    }

    @Test
    fun `pokrece azuriranje kada nema obradjenog meseca ili je marker star`() {
        val currentMonth = YearMonth.of(2026, 5)

        assertTrue(shouldRunMonthlyTaxUpdate(null, currentMonth))
        assertTrue(shouldRunMonthlyTaxUpdate("", currentMonth))
        assertTrue(shouldRunMonthlyTaxUpdate("2026-04", currentMonth))
        assertTrue(shouldRunMonthlyTaxUpdate("nije-mesec", currentMonth))
    }

    @Test
    fun `marker meseca koristi stabilan year month format`() {
        assertEquals("2026-05", taxUpdateMonthKey(YearMonth.of(2026, 5)))
        assertEquals("2026-11", taxUpdateMonthKey(YearMonth.of(2026, 11)))
    }
}
