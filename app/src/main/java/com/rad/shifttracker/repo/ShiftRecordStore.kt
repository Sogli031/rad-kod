package com.rad.shifttracker.repo

import com.rad.shifttracker.smene.DayKey
import com.rad.shifttracker.smene.DayRecord
import com.rad.shifttracker.smene.MonthKey

internal class ShiftRecordStore(private val onMonthChanged: (MonthKey) -> Unit) {
    private val records = mutableMapOf<DayKey, DayRecord>()
    private val byMonth = mutableMapOf<MonthKey, MutableSet<DayKey>>()

    val allRecords: Collection<DayRecord>
        get() = records.values

    fun clear() {
        records.clear()
        byMonth.clear()
    }

    fun get(year: Int, month: Int, day: Int): DayRecord? {
        return records[DayKey(year, month, day)]
    }

    fun set(record: DayRecord) {
        records[record.key] = record
        byMonth.getOrPut(record.monthKey) { mutableSetOf() }.add(record.key)
        onMonthChanged(record.monthKey)
    }

    fun delete(year: Int, month: Int, day: Int): Boolean {
        val record = records.remove(DayKey(year, month, day)) ?: return false
        val monthSet = byMonth[record.monthKey]
        monthSet?.remove(record.key)
        if (monthSet != null && monthSet.isEmpty()) {
            byMonth.remove(record.monthKey)
        }
        onMonthChanged(record.monthKey)
        return true
    }

    fun getMonthRecords(year: Int, month: Int): List<DayRecord> {
        return getMonthRecords(MonthKey(year, month))
    }

    fun getMonthRecords(monthKey: MonthKey): List<DayRecord> {
        return byMonth[monthKey]
            .orEmpty()
            .mapNotNull { key -> records[key] }
            .sortedBy { record -> record.day }
    }

    fun getAllMonthsSorted(): List<MonthKey> {
        return byMonth.keys.sorted()
    }
}
