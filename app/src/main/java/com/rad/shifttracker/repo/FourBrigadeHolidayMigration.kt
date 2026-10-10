package com.rad.shifttracker.repo

import java.time.LocalDate
import com.rad.shifttracker.smene.BrigadeType
import com.rad.shifttracker.smene.DayRecordSource
import com.rad.shifttracker.smene.MonthKey
import com.rad.shifttracker.smene.Shift
import com.rad.shifttracker.smene.ShiftScheduler

/**
 * Do 11.1 je auto-popuna četvorobrigade na praznik pon–pet oslobađala samo prvu smenu, a sve
 * praznične OFF dane pisala kao balanceExempt. Po novom pravilu (listići livnice 12/2024–08/2026)
 * praznik na dan smene po ciklusu je slobodan i plaćen (OFF bez balanceExempt), a na slobodan dan
 * po ciklusu se ne plaća. Usklađuju se samo zapisi iz auto-popune:
 * - OFF na praznik kad je po ciklusu smena dobija balanceExempt = false (ostaje plaćen);
 * - II/III smena na praznik od tekućeg meseca postaje plaćen OFF (raniji meseci ostaju kakvi su).
 * Bezbedno je ponavljati; vraća broj promenjenih dana.
 */
internal fun ShiftRepository.migrateFourBrigadeHolidays(today: LocalDate = LocalDate.now(clock)): Int {
    val changes = synchronized(lock) {
        if (payState.brigadeType != BrigadeType.CETVOROBRIGADA) return 0
        val pattern = fourBrigadePatternUnsafe() ?: return 0
        val scheduler = ShiftScheduler()
        val currentMonth = MonthKey(today.year, today.monthValue)
        recordStore.allRecords.mapNotNull { record ->
            if (record.source != DayRecordSource.AUTO_FILL) return@mapNotNull null
            if (!record.isHoliday || !record.weekday.isWorkday) return@mapNotNull null
            val cycleShift = scheduler.fourBrigadeCycleShiftForDate(record.date, pattern.anchorDate, pattern.cycleStartIndex)
            if (cycleShift !in Shift.workShifts()) return@mapNotNull null
            val fix = when {
                record.shift == Shift.OFF -> record.balanceExempt
                record.shift in Shift.workShifts() -> MonthKey(record.year, record.month) >= currentMonth
                else -> false
            }
            if (!fix) return@mapNotNull null
            BatchRecord(
                year = record.year,
                month = record.month,
                day = record.day,
                shift = Shift.OFF,
                hours = 0,
                balanceExempt = false,
                source = DayRecordSource.AUTO_FILL,
                holidayOverride = record.holidayOverride
            )
        }
    }
    if (changes.isNotEmpty()) setBatch(changes)
    return changes.size
}

/** Ciklus četvorobrigade prepoznat iz upisanih smena (sidro i indeks u ciklusu 1-1-2-2-3-3-0-0). */
internal data class FourBrigadeCycle(val anchorDate: LocalDate, val cycleStartIndex: Int)

internal fun ShiftRepository.fourBrigadeCycle(): FourBrigadeCycle? = synchronized(lock) {
    if (payState.brigadeType != BrigadeType.CETVOROBRIGADA) return null
    fourBrigadePatternUnsafe()?.let { FourBrigadeCycle(it.anchorDate, it.cycleStartIndex) }
}

private fun ShiftRepository.fourBrigadePatternUnsafe(): RollingPayBaseMonthSimulator.FourBrigadePattern? =
    (RollingPayBaseMonthSimulator(config, payState, recordStore).findPattern()
        as? RollingPayBaseMonthSimulator.Pattern.FourBrigade)?.pattern
