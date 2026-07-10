package com.rad.shifttracker

import java.time.LocalDate
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

    @Test
    fun `poreski kes vazi samo za isti kalendarski mesec`() {
        val cachedDate = LocalDate.of(2026, 7, 3)

        assertTrue(
            isTaxParameterCacheUsable(
                cachedDate.toEpochDay(),
                LocalDate.of(2026, 7, 31)
            )
        )
        assertFalse(
            isTaxParameterCacheUsable(
                cachedDate.toEpochDay(),
                LocalDate.of(2026, 8, 1)
            )
        )
        assertFalse(isTaxParameterCacheUsable(Long.MIN_VALUE, LocalDate.of(2026, 7, 3)))
    }

    @Test
    fun `poruka jasno oznacava kada je koriscen poreski kes`() {
        val parameters = TaxParameters(
            salaryTaxRatePercent = 10.0,
            salaryTaxAllowanceRsd = 34_221.0,
            pioEmployeePercent = 14.0,
            healthEmployeePercent = 5.15,
            unemploymentEmployeePercent = 0.75
        )

        val message = taxUpdateResultMessage(
            TaxParameterUpdateResult(parameters, TaxParameterUpdateSource.CACHE)
        )

        assertTrue(message.contains("iz keša za ovaj mesec"))
    }
}
