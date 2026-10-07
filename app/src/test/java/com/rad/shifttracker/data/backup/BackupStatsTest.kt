package com.rad.shifttracker.data.backup

import com.rad.shifttracker.smene.DayRecord
import com.rad.shifttracker.smene.MonthKey
import com.rad.shifttracker.smene.Shift
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import com.rad.shifttracker.data.storage.BackupStats

class BackupStatsTest {

    private fun workDay(year: Int, month: Int, day: Int, hours: Int = 8): DayRecord =
        DayRecord(year = year, month = month, day = day, shift = Shift.FIRST, hours = hours)

    @Test
    fun `prazna lista daje EMPTY`() {
        val stats = BackupStats.from(emptyList())

        assertEquals(BackupStats.EMPTY, stats)
        assertTrue(stats.isEmpty())
    }

    @Test
    fun `broji dane, mesece i radne minute`() {
        val records = listOf(
            workDay(2026, 1, 5, hours = 8),
            workDay(2026, 1, 6, hours = 8),
            workDay(2026, 2, 10, hours = 6)
        )

        val stats = BackupStats.from(records)

        assertEquals(3, stats.recordCount)
        assertEquals(2, stats.monthCount)
        assertEquals((8 + 8 + 6) * 60L, stats.totalWorkMinutes)
        assertEquals(MonthKey(2026, 1), stats.earliestMonth)
        assertEquals(MonthKey(2026, 2), stats.latestMonth)
    }

    @Test
    fun `najstariji i najnoviji mesec preko granice godine`() {
        val records = listOf(
            workDay(2025, 12, 31),
            workDay(2026, 1, 1)
        )

        val stats = BackupStats.from(records)

        assertEquals(MonthKey(2025, 12), stats.earliestMonth)
        assertEquals(MonthKey(2026, 1), stats.latestMonth)
    }
}
