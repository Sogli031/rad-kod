package com.rad.shifttracker

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import com.rad.shifttracker.obracun.ALL_MONTHS_CALCULATION_DEBUG_EXPORT_FILE_NAME
import com.rad.shifttracker.obracun.exportAllSavedMonthsCalculationDebugJson
import com.rad.shifttracker.smene.Shift

class AllMonthsCalculationDebugExportTest {
    @Test
    fun `debug export includes every saved month through the real monthly calculation export`() {
        val repo = testRepository()
        for (month in 1..6) {
            repo.set(2026, month, 10, Shift.FIRST, 8, holidayOverride = false)
        }

        val json = JSONObject(
            repo.exportAllSavedMonthsCalculationDebugJson(
                appName = "Rad",
                appVersion = "1.4",
                exportTimestamp = "2026-07-20T20:00:00Z"
            )
        )

        val info = json.getJSONObject("debugExportInfo")
        assertEquals(1, info.getInt("formatVersion"))
        assertEquals(6, info.getInt("savedMonthCount"))
        assertTrue(info.getBoolean("containsRawDailyInputs"))
        assertTrue(info.getBoolean("containsDailyCalculationBreakdown"))
        assertTrue(info.getBoolean("containsMonthlyCalculationBreakdown"))
        assertTrue(info.getBoolean("containsSettingsAndPayBaseResolution"))
        assertTrue(info.getBoolean("containsAuditFormulasAndChecks"))

        val overview = json.getJSONArray("savedMonthsOverview")
        assertEquals(6, overview.length())
        assertEquals("Januar 2026", overview.getJSONObject(0).getString("monthLabel"))
        assertEquals("Jun 2026", overview.getJSONObject(5).getString("monthLabel"))
        assertEquals(1, overview.getJSONObject(0).getInt("savedDayCount"))
        assertEquals(10, overview.getJSONObject(0).getJSONArray("savedDays").getInt(0))

        val months = json.getJSONArray("monthlyCalculationDebug")
        assertEquals(6, months.length())
        for (index in 0 until months.length()) {
            val month = months.getJSONObject(index)
            val expectedMonth = index + 1
            assertEquals(expectedMonth, month.getJSONObject("exportInfo").getInt("selectedMonth"))
            assertTrue(month.has("monthWorkEntries"))
            assertTrue(month.has("dailyCalculationBreakdown"))
            assertTrue(month.has("monthlySummary"))
            assertTrue(month.has("auditFormulas"))
            assertTrue(month.has("auditChecks"))
            assertTrue(month.has("settingsSnapshot"))
            assertTrue(month.has("rawCalculationDebug"))
        }
    }

    @Test
    fun `debug export contains only months with saved daily entries`() {
        val repo = testRepository()
        repo.set(2026, 1, 5, Shift.FIRST, 8, holidayOverride = false)
        repo.set(2026, 6, 5, Shift.SECOND, 8, holidayOverride = false)

        val json = JSONObject(
            repo.exportAllSavedMonthsCalculationDebugJson(
                exportTimestamp = "2026-07-20T20:00:00Z"
            )
        )
        val overview = json.getJSONArray("savedMonthsOverview")

        assertEquals(2, overview.length())
        assertEquals(1, overview.getJSONObject(0).getInt("month"))
        assertEquals(6, overview.getJSONObject(1).getInt("month"))
        assertFalse(json.toString().contains("\"month\":2,\"monthLabel\""))
    }

    @Test
    fun `debug export file name is clear and stable`() {
        assertEquals("rad_debug_obracuni_svi_meseci.json", ALL_MONTHS_CALCULATION_DEBUG_EXPORT_FILE_NAME)
    }
}
