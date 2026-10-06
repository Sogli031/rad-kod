package com.rad.shifttracker

import org.junit.Assert.assertEquals
import org.junit.Test
import com.rad.shifttracker.obracun.coefficientFromLegacyGrossHourlyRate
import com.rad.shifttracker.obracun.defaultHourlyValueForMonth
import com.rad.shifttracker.obracun.grossHourlyRateForMonth
import com.rad.shifttracker.obracun.resolveHourlyValueForMonth
import com.rad.shifttracker.smene.MonthKey

class PayRateHistoryTest {

    @Test
    fun `fixed hourly history uses 214 point 5 before january 2026`() {
        assertEquals(214.5, defaultHourlyValueForMonth(2025, 12), 0.001)
    }

    @Test
    fun `fixed hourly history uses 226 from january 2026`() {
        assertEquals(226.0, defaultHourlyValueForMonth(2026, 1), 0.001)
        assertEquals(226.0, defaultHourlyValueForMonth(2026, 5), 0.001)
    }

    @Test
    fun `default coefficient with january 2026 value gives 637 point 32`() {
        assertEquals(637.32, grossHourlyRateForMonth(2.82, 2026, 1, emptyMap()), 0.001)
    }

    @Test
    fun `custom hourly value applies from its month forward`() {
        val changes = mapOf(MonthKey(2026, 7) to 240.0)

        assertEquals(226.0, resolveHourlyValueForMonth(2026, 6, changes).value, 0.001)
        assertEquals(240.0, resolveHourlyValueForMonth(2026, 7, changes).value, 0.001)
        assertEquals(240.0, resolveHourlyValueForMonth(2026, 8, changes).value, 0.001)
    }

    @Test
    fun `legacy gross 637 point 32 maps cleanly to coefficient 2 point 82`() {
        assertEquals(
            2.82,
            coefficientFromLegacyGrossHourlyRate(637.32, MonthKey(2026, 5)),
            0.001
        )
    }

    @Test
    fun `legacy gross 604 point 89 maps cleanly to coefficient 2 point 82`() {
        assertEquals(
            2.82,
            coefficientFromLegacyGrossHourlyRate(604.89, MonthKey(2025, 12)),
            0.001
        )
    }
}
