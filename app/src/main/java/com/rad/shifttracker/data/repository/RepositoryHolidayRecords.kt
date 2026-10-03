package com.rad.shifttracker.data.repository

import com.rad.shifttracker.DayKey
import com.rad.shifttracker.DayRecord
import com.rad.shifttracker.HolidayEntry
import com.rad.shifttracker.HolidayEntrySource
import com.rad.shifttracker.MANUAL_HOLIDAY_LABEL
import com.rad.shifttracker.MonthKey
import com.rad.shifttracker.OvertimeType
import com.rad.shifttracker.Shift
import com.rad.shifttracker.getSerbianHolidays
import com.rad.shifttracker.isSerbianHoliday

internal class RepositoryHolidayRecords(private val recordStore: ShiftRecordStore) {
    fun deleteAutoFilledHolidayRecordsBefore(cutoffMonth: MonthKey): Int {
        val recordsToDelete = recordStore.allRecords
            .filter { record -> record.monthKey < cutoffMonth && record.isAutoFilledHolidayOnlyRecord() }
        for (record in recordsToDelete) {
            recordStore.delete(record.year, record.month, record.day)
        }
        return recordsToDelete.size
    }

    fun getHolidayEntries(year: Int): List<HolidayEntry> {
        val officialHolidays = getSerbianHolidays(year)
        val officialKeys = officialHolidays.map { DayKey(year, it.month, it.day) }.toSet()
        val entries = officialHolidays.map { holiday ->
            val record = recordStore.get(year, holiday.month, holiday.day)
            HolidayEntry(
                year = year,
                month = holiday.month,
                day = holiday.day,
                name = holiday.name,
                isActive = record?.holidayOverride ?: true,
                source = HolidayEntrySource.OFFICIAL
            )
        }.toMutableList()

        recordStore.allRecords
            .asSequence()
            .filter { it.year == year }
            .filter { it.holidayOverride != null }
            .filter { it.key !in officialKeys }
            .forEach { record ->
                entries += HolidayEntry(
                    year = record.year,
                    month = record.month,
                    day = record.day,
                    name = record.holidayName ?: MANUAL_HOLIDAY_LABEL,
                    isActive = record.holidayOverride == true,
                    source = HolidayEntrySource.MANUAL
                )
            }

        return entries.sortedWith(compareBy<HolidayEntry> { it.month }.thenBy { it.day }.thenBy { it.source.ordinal })
    }

    fun setHolidayActive(year: Int, month: Int, day: Int, isActive: Boolean): Boolean {
        val holidayOverride = if (isSerbianHoliday(year, month, day) && isActive) {
            null
        } else {
            isActive
        }
        val existing = recordStore.get(year, month, day)
        if (existing?.holidayOverride == holidayOverride) {
            return false
        }

        existing?.let { recordStore.delete(it.year, it.month, it.day) }

        val updated = existing?.withHolidayState(
            holidayOverride = holidayOverride,
            holidayWorkOverride = resolveHolidayWorkOverride(existing, isActive)
        ) ?: holidayOverride?.let {
            DayRecord(
                year = year,
                month = month,
                day = day,
                shift = Shift.OFF,
                hours = 0,
                holidayWorkOverride = if (isActive) null else false,
                holidayOverride = it
            )
        }

        if (updated != null && (holidayOverride != null || updated.hasWorkData())) {
            recordStore.set(updated)
        }
        return true
    }

    private fun resolveHolidayWorkOverride(record: DayRecord, isActive: Boolean): Boolean? {
        return when {
            !isActive -> false
            record.holidayOverride == false && record.holidayWorkOverride == false -> null
            else -> record.holidayWorkOverride
        }
    }

    private fun DayRecord.withHolidayState(
        holidayOverride: Boolean?,
        holidayWorkOverride: Boolean?
    ): DayRecord {
        return DayRecord(
            year = year,
            month = month,
            day = day,
            shift = shift,
            hours = hours,
            shift2 = shift2,
            hours2 = hours2,
            overtimeType = overtimeType,
            weekendOvertimeType = weekendOvertimeType,
            compHoursUsed = compHoursUsed,
            holidayWorkOverride = holidayWorkOverride,
            holidayOverride = holidayOverride,
            balanceExempt = balanceExempt,
            sickPayRate = sickPayRate,
            regularShortageMinutes = regularShortageMinutes,
            // Bez ovoga dan davanja krvi posle promene praznika postaje obično bolovanje (65%).
            source = source
        )
    }

    private fun DayRecord.isAutoFilledHolidayOnlyRecord(): Boolean {
        return shift == Shift.OFF &&
            hours == 0 &&
            hours2 == 0 &&
            compHoursUsed == 0 &&
            balanceExempt &&
            holidayOverride == null &&
            holidayWorkOverride == null &&
            isHoliday
    }

    private fun DayRecord.hasWorkData(): Boolean {
        return shift != Shift.OFF ||
            hours > 0 ||
            shift2 != null ||
            hours2 > 0 ||
            overtimeType != OvertimeType.PAID ||
            weekendOvertimeType != OvertimeType.PAID ||
            compHoursUsed > 0 ||
            regularShortageMinutes > 0 ||
            balanceExempt ||
            sickPayRate != null
    }
}
