package com.rad.shifttracker.repo

import java.time.DayOfWeek
import java.time.LocalDate
import com.rad.shifttracker.smene.BrigadeType
import com.rad.shifttracker.smene.DayRecordSource
import com.rad.shifttracker.smene.FOUR_BRIGADE_SATURDAY_RULE_FROM
import com.rad.shifttracker.smene.MonthKey
import com.rad.shifttracker.smene.Shift
import com.rad.shifttracker.smene.ShiftScheduler

/**
 * Do 11.2 je auto-popuna četvorobrigade na praznik pon–pet oslobađala samo prvu smenu, a sve
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

/**
 * Novo pravilo četvorobrigade ([FOUR_BRIGADE_SATURDAY_RULE_FROM]): već auto-popunjen raspored od
 * danas unapred se usklađuje sam — ponedeljak sa prvom smenom po ciklusu postaje prva smena, a
 * subota sa trećom postaje slobodna. Samo četvorobrigada i samo zapisi auto-popune; praznici,
 * ručni unosi, odsustva i prošli dani se ne diraju. Bezbedno je ponavljati; vraća broj promenjenih dana.
 */
internal fun ShiftRepository.migrateFourBrigadeSaturdayRule(today: LocalDate = LocalDate.now(clock)): Int {
    val changes = synchronized(lock) {
        if (payState.brigadeType != BrigadeType.CETVOROBRIGADA) return 0
        val pattern = fourBrigadePatternUnsafe() ?: return 0
        val scheduler = ShiftScheduler()
        val from = maxOf(today, FOUR_BRIGADE_SATURDAY_RULE_FROM)
        recordStore.allRecords.mapNotNull { record ->
            if (record.source != DayRecordSource.AUTO_FILL || record.date.isBefore(from)) return@mapNotNull null
            if (record.isHoliday) return@mapNotNull null
            val dayOfWeek = record.date.dayOfWeek
            if (dayOfWeek != DayOfWeek.MONDAY && dayOfWeek != DayOfWeek.SATURDAY) return@mapNotNull null
            val expected = scheduler.calculateFourBrigadeShiftForDate(record.date, pattern.anchorDate, pattern.cycleStartIndex)
            val stale = when (dayOfWeek) {
                DayOfWeek.MONDAY -> record.shift == Shift.OFF && expected == Shift.FIRST
                else -> record.shift == Shift.THIRD && expected == Shift.OFF
            }
            if (!stale) return@mapNotNull null
            BatchRecord(
                year = record.year,
                month = record.month,
                day = record.day,
                shift = expected,
                hours = if (expected == Shift.OFF) 0 else scheduler.defaultHours,
                balanceExempt = expected == Shift.OFF,
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
