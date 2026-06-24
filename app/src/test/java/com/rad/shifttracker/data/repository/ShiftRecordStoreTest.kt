package com.rad.shifttracker.data.repository

import com.rad.shifttracker.DayRecord
import com.rad.shifttracker.MonthKey
import com.rad.shifttracker.Shift
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ShiftRecordStoreTest {
    @Test
    fun `set get and month records keep sorted day records`() {
        val changedMonths = mutableListOf<MonthKey>()
        val store = ShiftRecordStore(changedMonths::add)

        store.set(DayRecord(2026, 2, 2, Shift.FIRST, 8))
        store.set(DayRecord(2026, 2, 1, Shift.SECOND, 8))

        assertEquals(Shift.SECOND, store.get(2026, 2, 1)?.shift)
        assertEquals(listOf(1, 2), store.getMonthRecords(2026, 2).map { it.day })
        assertEquals(listOf(MonthKey(2026, 2), MonthKey(2026, 2)), changedMonths)
    }

    @Test
    fun `delete removes record and empty month index`() {
        val store = ShiftRecordStore {}

        store.set(DayRecord(2026, 2, 1, Shift.SECOND, 8))

        assertTrue(store.delete(2026, 2, 1))
        assertNull(store.get(2026, 2, 1))
        assertTrue(store.getAllMonthsSorted().isEmpty())
    }
}
