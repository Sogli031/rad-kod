package com.rad.shifttracker.repo

import com.rad.shifttracker.osnova.AppConfig
import com.rad.shifttracker.smene.BrigadeType
import com.rad.shifttracker.smene.DayRecord
import com.rad.shifttracker.smene.DayRecordSource
import com.rad.shifttracker.smene.MonthKey
import com.rad.shifttracker.smene.Shift
import com.rad.shifttracker.smene.ShiftFillMode
import com.rad.shifttracker.smene.ShiftFillSettings
import com.rad.shifttracker.smene.ShiftScheduler
import com.rad.shifttracker.smene.Weekday
import com.rad.shifttracker.smene.isSerbianHoliday
import java.time.LocalDate
import java.time.YearMonth

private const val FOUR_BRIGADE_CYCLE_DAYS = 8
private const val MIN_PATTERN_WORK_RECORDS = 10
private const val MIN_PATTERN_MATCH_PERCENT = 85
private const val RECENT_PATTERN_DAYS = 56L

internal class RollingPayBaseMonthSimulator(
    private val config: AppConfig,
    private val payState: RepositoryPayState,
    private val recordStore: ShiftRecordStore
) {
    data class FourBrigadePattern(
        val anchorDate: LocalDate,
        val cycleStartIndex: Int
    )

    sealed class Pattern {
        data class ShiftFill(val settings: ShiftFillSettings) : Pattern()
        data class FourBrigade(val pattern: FourBrigadePattern) : Pattern()
    }

    private data class PatternRecordSet(
        val records: List<DayRecord>,
        val allowOutliers: Boolean
    )

    private data class PatternMatchCounts(
        val matches: Int,
        val checked: Int
    )

    fun findPattern(): Pattern? {
        val scheduler = ShiftScheduler()
        return when (payState.brigadeType) {
            BrigadeType.CETVOROBRIGADA -> {
                findFourBrigadePattern(scheduler)?.let(Pattern::FourBrigade)
            }
            BrigadeType.TROBRIGADA -> {
                findShiftFillPattern(scheduler)?.let(Pattern::ShiftFill)
            }
        }
    }

    fun simulateEmptyMonth(monthKey: MonthKey, pattern: Pattern?): List<DayRecord>? {
        if (recordStore.getMonthRecords(monthKey).isNotEmpty()) return null
        return simulateMonthRecords(monthKey, pattern)
    }

    fun simulateShiftForDate(date: LocalDate): Shift? {
        val scheduler = ShiftScheduler()
        return when (val pattern = findPattern()) {
            null -> null
            is Pattern.ShiftFill -> scheduler.calculateShiftForWorkedDate(date, pattern.settings)
            is Pattern.FourBrigade -> scheduler.calculateFourBrigadeShiftForDate(
                date = date,
                anchorDate = pattern.pattern.anchorDate,
                cycleStartIndex = pattern.pattern.cycleStartIndex
            )
        }
    }

    fun simulateMonthRecords(monthKey: MonthKey, pattern: Pattern?): List<DayRecord>? {
        if (pattern == null) return null

        return when (pattern) {
            is Pattern.ShiftFill -> simulateShiftFillMonthRecords(monthKey, pattern.settings)
            is Pattern.FourBrigade -> simulateFourBrigadeMonthRecords(monthKey, pattern.pattern)
        }
    }

    private fun simulateShiftFillMonthRecords(
        monthKey: MonthKey,
        settings: ShiftFillSettings
    ): List<DayRecord>? {
        val scheduler = ShiftScheduler()
        val lengthOfMonth = lengthOfMonthOrNull(monthKey) ?: return null
        val records = mutableListOf<DayRecord>()

        for (day in 1..lengthOfMonth) {
            val date = LocalDate.of(monthKey.year, monthKey.month, day)
            if (!Weekday.from(date).isWorkday) continue
            val shift = if (isWorkdayHoliday(date)) {
                Shift.OFF
            } else {
                scheduler.calculateShiftForDate(date, settings)
            }
            if (shift == Shift.OFF) {
                records += DayRecord(monthKey.year, monthKey.month, day, Shift.OFF, 0, balanceExempt = true)
            } else if (shift in Shift.workShifts()) {
                records += DayRecord(monthKey.year, monthKey.month, day, shift, config.pay.defaultHours)
            }
        }

        return records.takeIf { it.isNotEmpty() }
    }

    private fun simulateFourBrigadeMonthRecords(
        monthKey: MonthKey,
        pattern: FourBrigadePattern
    ): List<DayRecord>? {
        val scheduler = ShiftScheduler()
        val lengthOfMonth = lengthOfMonthOrNull(monthKey) ?: return null
        val records = mutableListOf<DayRecord>()

        for (day in 1..lengthOfMonth) {
            val date = LocalDate.of(monthKey.year, monthKey.month, day)
            // Isto pravilo kao auto-popuna: praznik pon–pet je slobodan, plaćen samo ako je po ciklusu smena.
            val cycleWorkday = scheduler.fourBrigadeCycleShiftForDate(date, pattern.anchorDate, pattern.cycleStartIndex) in
                Shift.workShifts()
            val shift = if (isWorkdayHoliday(date)) {
                Shift.OFF
            } else {
                scheduler.calculateFourBrigadeShiftForDate(
                    date = date,
                    anchorDate = pattern.anchorDate,
                    cycleStartIndex = pattern.cycleStartIndex
                )
            }
            val hours = if (shift in Shift.workShifts()) config.pay.defaultHours else 0
            records += DayRecord(
                year = monthKey.year,
                month = monthKey.month,
                day = day,
                shift = shift,
                hours = hours,
                balanceExempt = shift == Shift.OFF && !(isWorkdayHoliday(date) && cycleWorkday)
            )
        }

        return records.takeIf { it.isNotEmpty() }
    }

    private fun findFourBrigadePattern(scheduler: ShiftScheduler): FourBrigadePattern? {
        val primary = fourBrigadePatternRecordSet()
        return findFourBrigadePatternIn(scheduler, primary)
            ?: recentRecordSet(primary)?.let { findFourBrigadePatternIn(scheduler, it) }
    }

    private fun findFourBrigadePatternIn(scheduler: ShiftScheduler, recordSet: PatternRecordSet): FourBrigadePattern? {
        val records = recordSet.records
        val workRecords = records.filter { it.shift in Shift.workShifts() }
        if (workRecords.size < MIN_PATTERN_WORK_RECORDS) return null
        var bestPattern: FourBrigadePattern? = null
        var bestMatchCount = -1
        var bestCheckedCount = 0
        for (anchor in workRecords) {
            for (cycleStartIndex in 0 until FOUR_BRIGADE_CYCLE_DAYS) {
                val expectedAnchorShift = scheduler.calculateFourBrigadeShiftForDate(
                    date = anchor.date,
                    anchorDate = anchor.date,
                    cycleStartIndex = cycleStartIndex
                )
                if (expectedAnchorShift != anchor.shift) continue
                val counts = fourBrigadePatternMatchCounts(scheduler, anchor.date, cycleStartIndex, records)
                if (counts.matches > bestMatchCount) {
                    bestPattern = FourBrigadePattern(anchor.date, cycleStartIndex)
                    bestMatchCount = counts.matches
                    bestCheckedCount = counts.checked
                }
            }
        }
        return bestPattern?.takeIf {
            hasEnoughPatternMatches(bestMatchCount, bestCheckedCount, recordSet.allowOutliers)
        }
    }

    private fun fourBrigadePatternMatchCounts(
        scheduler: ShiftScheduler,
        anchorDate: LocalDate,
        cycleStartIndex: Int,
        records: List<DayRecord>
    ): PatternMatchCounts {
        var matches = 0
        var checked = 0
        for (record in records) {
            if (record.shift !in Shift.workShifts() && record.shift != Shift.OFF) {
                continue
            }
            checked += 1
            val expectedShift = scheduler.calculateFourBrigadeShiftForDate(
                date = record.date,
                anchorDate = anchorDate,
                cycleStartIndex = cycleStartIndex
            )
            val recordMatches = when {
                record.shift in Shift.workShifts() -> expectedShift == record.shift
                isFourBrigadeHolidayOff(record.date) -> true
                else -> expectedShift == Shift.OFF
            }
            if (recordMatches) matches += 1
        }
        return PatternMatchCounts(matches = matches, checked = checked)
    }

    private fun findShiftFillPattern(scheduler: ShiftScheduler): ShiftFillSettings? {
        val primary = shiftFillPatternRecordSet()
        return findShiftFillPatternIn(scheduler, primary)
            ?: recentRecordSet(primary)?.let { findShiftFillPatternIn(scheduler, it) }
    }

    /**
     * Auto-popunjeni dani koji ne prate svi isti raspored (npr. „Popuni“ ponovo posle promene brigade):
     * važi raspored poslednjih [RECENT_PATTERN_DAYS] dana tih zapisa, uz dozvoljena odstupanja.
     */
    private fun recentRecordSet(primary: PatternRecordSet): PatternRecordSet? {
        if (primary.allowOutliers) return null
        val last = primary.records.lastOrNull()?.date ?: return null
        val since = last.minusDays(RECENT_PATTERN_DAYS)
        return PatternRecordSet(records = primary.records.filter { it.date.isAfter(since) }, allowOutliers = true)
    }

    private fun findShiftFillPatternIn(scheduler: ShiftScheduler, recordSet: PatternRecordSet): ShiftFillSettings? {
        val workRecords = recordSet.records
        if (workRecords.size < MIN_PATTERN_WORK_RECORDS) return null
        var bestSettings: ShiftFillSettings? = null
        var bestMatchCount = -1
        for (mode in listOf(ShiftFillMode.ONE_SHIFT, ShiftFillMode.FIRST_SECOND, ShiftFillMode.THREE_BRIGADE)) {
            val validShifts = validSelectedShiftsForMode(mode)
            for (anchor in workRecords) {
                if (anchor.shift !in validShifts) continue
                val settings = ShiftFillSettings(
                    mode = mode,
                    anchorDate = anchor.date,
                    selectedShift = selectedShiftForMode(mode, anchor.shift)
                )
                val matchCount = workRecords.count { record ->
                    scheduler.calculateShiftForDate(record.date, settings) == record.shift
                }
                if (matchCount > bestMatchCount) {
                    bestSettings = settings
                    bestMatchCount = matchCount
                }
            }
        }
        return bestSettings?.takeIf {
            hasEnoughPatternMatches(bestMatchCount, workRecords.size, recordSet.allowOutliers)
        }
    }

    private fun validSelectedShiftsForMode(mode: ShiftFillMode): List<Shift> {
        return when (mode) {
            ShiftFillMode.ONE_SHIFT -> listOf(Shift.FIRST)
            ShiftFillMode.FIRST_SECOND -> listOf(Shift.FIRST, Shift.SECOND)
            ShiftFillMode.THREE_BRIGADE -> listOf(Shift.FIRST, Shift.SECOND, Shift.THIRD)
            ShiftFillMode.FOUR_BRIGADE -> Shift.workShifts()
        }
    }

    private fun selectedShiftForMode(mode: ShiftFillMode, shift: Shift): Shift {
        return if (mode == ShiftFillMode.ONE_SHIFT) Shift.FIRST else shift
    }

    private fun sortedPatternRecords(): List<DayRecord> {
        return recordStore.allRecords
            .sortedWith(compareBy<DayRecord> { it.year }.thenBy { it.month }.thenBy { it.day })
    }

    private fun fourBrigadePatternRecordSet(): PatternRecordSet {
        val records = sortedPatternRecords()
        val autoFillRecords = records.filter { it.source == DayRecordSource.AUTO_FILL }
        val autoFillWorkCount = autoFillRecords.count { it.shift in Shift.workShifts() }
        return if (autoFillWorkCount >= MIN_PATTERN_WORK_RECORDS) {
            PatternRecordSet(records = autoFillRecords, allowOutliers = false)
        } else {
            PatternRecordSet(records = records, allowOutliers = true)
        }
    }

    private fun shiftFillPatternRecordSet(): PatternRecordSet {
        val workRecords = sortedPatternRecords()
            .filter { it.shift in Shift.workShifts() }
            .filter { Weekday.from(it.date).isWorkday }
        val autoFillRecords = workRecords.filter { it.source == DayRecordSource.AUTO_FILL }
        return if (autoFillRecords.size >= MIN_PATTERN_WORK_RECORDS) {
            PatternRecordSet(records = autoFillRecords, allowOutliers = false)
        } else {
            PatternRecordSet(records = workRecords, allowOutliers = true)
        }
    }

    private fun hasEnoughPatternMatches(matches: Int, total: Int, allowOutliers: Boolean): Boolean {
        if (total < MIN_PATTERN_WORK_RECORDS) return false
        if (!allowOutliers) return matches == total
        return matches >= MIN_PATTERN_WORK_RECORDS &&
            matches * 100 >= total * MIN_PATTERN_MATCH_PERCENT
    }

    private fun lengthOfMonthOrNull(monthKey: MonthKey): Int? {
        return try {
            YearMonth.of(monthKey.year, monthKey.month).lengthOfMonth()
        } catch (_: Exception) {
            null
        }
    }

    private fun isWorkdayHoliday(date: LocalDate): Boolean {
        return Weekday.from(date).isWorkday && isSerbianHoliday(date.year, date.monthValue, date.dayOfMonth)
    }

    /**
     * Praznik pon–pet (i subotnji iz ranijih verzija auto-popune) je u četvorobrigadi OFF bez obzira
     * na ciklus, pa ga prepoznavanje obrasca ne broji kao odstupanje.
     */
    private fun isFourBrigadeHolidayOff(date: LocalDate): Boolean {
        val weekday = Weekday.from(date)
        return (weekday.isWorkday || weekday == Weekday.SATURDAY) &&
            isSerbianHoliday(date.year, date.monthValue, date.dayOfMonth)
    }
}
