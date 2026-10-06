package com.rad.shifttracker

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import java.time.temporal.TemporalAdjusters

data class ReferenceWorkWeek(
    val start: LocalDate,
    val end: LocalDate
)

class ShiftScheduler(
    private val cycle: List<Int> = CONFIG.weeklyShiftCycle,
    private val defaultHours: Int = CONFIG.pay.defaultHours
) {
    private companion object {
        val FIRST_SECOND_CYCLE = listOf(Shift.FIRST, Shift.SECOND)
        val THREE_BRIGADE_CYCLE = listOf(Shift.THIRD, Shift.SECOND, Shift.FIRST)
        val FOUR_BRIGADE_CYCLE = listOf(
            Shift.FIRST,
            Shift.FIRST,
            Shift.SECOND,
            Shift.SECOND,
            Shift.THIRD,
            Shift.THIRD,
            Shift.OFF,
            Shift.OFF
        )
    }

    fun getAutoFillReferenceDate(today: LocalDate): LocalDate {
        return if (today.dayOfWeek.value <= DayOfWeek.FRIDAY.value) {
            today
        } else {
            today.with(TemporalAdjusters.next(DayOfWeek.MONDAY))
        }
    }

    fun getReferenceWorkWeek(activeDate: LocalDate): ReferenceWorkWeek {
        val start = activeDate.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
        return ReferenceWorkWeek(start = start, end = start.plusDays(4))
    }

    fun getShiftForDate(
        targetDate: LocalDate,
        referenceDate: LocalDate,
        referenceShift: Shift
    ): Shift? {
        if (targetDate.dayOfWeek.value > DayOfWeek.FRIDAY.value) return null

        val refIndex = cycle.indexOf(referenceShift.value)
        if (refIndex < 0) return null

        val refMonday = referenceDate.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
        val targetMonday = targetDate.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
        val weeksDiff = java.time.temporal.ChronoUnit.WEEKS.between(refMonday, targetMonday).toInt()
        val cycleIndex = Math.floorMod(refIndex + weeksDiff, cycle.size)
        return Shift.fromValue(cycle[cycleIndex])
    }

    fun calculateOneShiftForDate(date: LocalDate): Shift {
        return if (date.dayOfWeek.value <= DayOfWeek.FRIDAY.value) Shift.FIRST else Shift.OFF
    }

    fun calculateWeeklyShiftForDate(
        date: LocalDate,
        anchorDate: LocalDate,
        selectedShift: Shift,
        mode: ShiftFillMode
    ): Shift {
        if (date.dayOfWeek.value > DayOfWeek.FRIDAY.value) return Shift.OFF

        val weeklyCycle = when (mode) {
            ShiftFillMode.FIRST_SECOND -> FIRST_SECOND_CYCLE
            // Trobrigada se u ovoj aplikaciji računa kao sedmična rotacija smena: treća → druga → prva. Nije dnevna rotacija.
            ShiftFillMode.THREE_BRIGADE -> THREE_BRIGADE_CYCLE
            else -> return Shift.OFF
        }
        val anchorCycleIndex = weeklyCycle.indexOf(selectedShift)
        if (anchorCycleIndex < 0) return Shift.OFF

        val anchorMonday = anchorDate.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
        val targetMonday = date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
        val weekDiff = ChronoUnit.WEEKS.between(anchorMonday, targetMonday).toInt()
        val shiftIndex = Math.floorMod(anchorCycleIndex + weekDiff, weeklyCycle.size)
        return weeklyCycle[shiftIndex]
    }

    fun getFourBrigadeCycleShift(cycleIndex: Int): Shift {
        return FOUR_BRIGADE_CYCLE[Math.floorMod(cycleIndex, FOUR_BRIGADE_CYCLE.size)]
    }

    fun getFourBrigadeCycleStartIndex(selectedShift: Shift, cycleDayIndex: Int): Int {
        val blockStartIndex = when (selectedShift) {
            Shift.FIRST -> 0
            Shift.SECOND -> 2
            Shift.THIRD -> 4
            Shift.OFF -> 6
            else -> 0
        }
        return blockStartIndex + cycleDayIndex.coerceIn(0, 1)
    }

    fun calculateFourBrigadeShiftForDate(
        date: LocalDate,
        anchorDate: LocalDate,
        cycleStartIndex: Int
    ): Shift {
        val daysDiff = ChronoUnit.DAYS.between(anchorDate, date).toInt()
        val cycleIndex = Math.floorMod(cycleStartIndex + daysDiff, FOUR_BRIGADE_CYCLE.size)
        val cycleShift = getFourBrigadeCycleShift(cycleIndex)
        return applyFourBrigadeNonWorkingRules(date, cycleShift)
    }

    private fun applyFourBrigadeNonWorkingRules(date: LocalDate, cycleShift: Shift): Shift {
        return when {
            date.dayOfWeek == DayOfWeek.SUNDAY -> Shift.OFF
            date.dayOfWeek == DayOfWeek.MONDAY && cycleShift == Shift.FIRST -> Shift.OFF
            else -> cycleShift
        }
    }

    fun calculateShiftForDate(date: LocalDate, settings: ShiftFillSettings): Shift {
        return when (settings.mode) {
            ShiftFillMode.ONE_SHIFT -> calculateOneShiftForDate(date)
            ShiftFillMode.FIRST_SECOND,
            ShiftFillMode.THREE_BRIGADE -> calculateWeeklyShiftForDate(
                date = date,
                anchorDate = settings.anchorDate,
                selectedShift = settings.selectedShift,
                mode = settings.mode
            )
            ShiftFillMode.FOUR_BRIGADE -> calculateFourBrigadeShiftForDate(
                date = date,
                anchorDate = settings.anchorDate,
                cycleStartIndex = settings.cycleDayIndex
                    ?: getFourBrigadeCycleStartIndex(settings.selectedShift, 0)
            )
        }
    }

    fun calculateShiftForWorkedDate(date: LocalDate, settings: ShiftFillSettings): Shift? {
        val scheduledShift = calculateShiftForDate(date, settings)
        if (scheduledShift in Shift.workShifts()) return scheduledShift
        if (date.dayOfWeek.value <= DayOfWeek.FRIDAY.value) return null

        return when (settings.mode) {
            ShiftFillMode.ONE_SHIFT,
            ShiftFillMode.FIRST_SECOND,
            ShiftFillMode.THREE_BRIGADE -> {
                val weekAnchor = date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
                calculateShiftForDate(weekAnchor, settings).takeIf { it in Shift.workShifts() }
            }
            ShiftFillMode.FOUR_BRIGADE -> null
        }
    }

    fun populateMonthsAhead(
        repo: ShiftRepository,
        referenceDate: LocalDate,
        referenceShift: Shift,
        monthsAhead: Int
    ): Int {
        return populateMonthsAhead(
            repo = repo,
            referenceDate = referenceDate,
            settings = ShiftFillSettings(
                mode = ShiftFillMode.THREE_BRIGADE,
                anchorDate = getReferenceWorkWeek(referenceDate).start,
                selectedShift = referenceShift
            ),
            monthsAhead = monthsAhead
        )
    }

    fun populateMonthsAhead(
        repo: ShiftRepository,
        referenceDate: LocalDate,
        settings: ShiftFillSettings,
        monthsAhead: Int
    ): Int {
        val safeMonths = monthsAhead.coerceAtLeast(0)
        if (!isValidFillSettings(settings)) return 0

        val normalizedSettings = when (settings.mode) {
            ShiftFillMode.FIRST_SECOND,
            ShiftFillMode.THREE_BRIGADE -> settings.copy(anchorDate = getReferenceWorkWeek(settings.anchorDate).start)
            else -> settings
        }
        repo.brigadeTypeValue = brigadeTypeForFillMode(normalizedSettings.mode)

        val referenceMonth = referenceDate.withDayOfMonth(1)
        repo.deleteAutoFilledHolidayRecordsBefore(
            MonthKey(referenceMonth.year, referenceMonth.monthValue)
        )
        val startDate = referenceMonth
        val endMonth = referenceMonth.plusMonths(safeMonths.toLong())
        val endDate = endMonth.withDayOfMonth(endMonth.lengthOfMonth())
        val records = mutableListOf<BatchRecord>()

        var date = startDate
        while (!date.isAfter(endDate)) {
            val existing = repo.get(date.year, date.monthValue, date.dayOfMonth)
            // Automatsko popunjavanje ne sme da pregazi ručne unose
            // (godišnji, bolovanje, dupla smena...) ni krvodavalaštvo —
            // zamenjuje samo prazne dane i ranije auto-popunjene zapise.
            if (existing != null && existing.source != DayRecordSource.AUTO_FILL) {
                date = date.plusDays(1)
                continue
            }
            // Ručno isključen/uključen praznik (holidayOverride) ima prednost
            // nad kalendarom — isto kao u previewVacationRange.
            val isHolidayDate = existing?.holidayOverride
                ?: isSerbianHoliday(date.year, date.monthValue, date.dayOfMonth)
            // Praznik pon–pet je slobodan dan (plaćen kao državni praznik). Četvorobrigada radi
            // praznike po ciklusu, osim prve smene (kao ponedeljkom posle nedelje) — livnica, listići
            // 02, 04 i 05/2026: rad na praznik i državni praznik tačni tek uz to pravilo.
            val holidayDayApplies = when (normalizedSettings.mode) {
                ShiftFillMode.FOUR_BRIGADE ->
                    calculateShiftForDate(date, normalizedSettings) == Shift.FIRST
                else -> date.dayOfWeek.value <= DayOfWeek.FRIDAY.value
            }
            val record = if (holidayDayApplies && isHolidayDate) {
                BatchRecord(
                    year = date.year,
                    month = date.monthValue,
                    day = date.dayOfMonth,
                    shift = Shift.OFF,
                    hours = 0,
                    balanceExempt = true,
                    source = DayRecordSource.AUTO_FILL
                )
            } else {
                val shift = calculateShiftForDate(date, normalizedSettings)
                val hours = if (shift in Shift.workShifts()) defaultHours else 0
                BatchRecord(
                    year = date.year,
                    month = date.monthValue,
                    day = date.dayOfMonth,
                    shift = shift,
                    hours = hours,
                    balanceExempt = normalizedSettings.mode == ShiftFillMode.FOUR_BRIGADE && shift == Shift.OFF,
                    source = DayRecordSource.AUTO_FILL
                )
            }
            if (!shouldSkipAutoFillRecord(record, normalizedSettings.mode)) {
                records.add(record)
            }
            date = date.plusDays(1)
        }

        if (records.isNotEmpty()) {
            repo.setBatch(records)
        }
        return records.size
    }

    private fun isWeekendOffRecord(record: BatchRecord): Boolean {
        return record.shift == Shift.OFF &&
            LocalDate.of(record.year, record.month, record.day).dayOfWeek.value > DayOfWeek.FRIDAY.value
    }

    private fun shouldSkipAutoFillRecord(record: BatchRecord, mode: ShiftFillMode): Boolean {
        return mode != ShiftFillMode.FOUR_BRIGADE && isWeekendOffRecord(record)
    }

    private fun brigadeTypeForFillMode(mode: ShiftFillMode): BrigadeType {
        return when (mode) {
            ShiftFillMode.FOUR_BRIGADE -> BrigadeType.CETVOROBRIGADA
            else -> BrigadeType.TROBRIGADA
        }
    }

    private fun isValidFillSettings(settings: ShiftFillSettings): Boolean {
        return when (settings.mode) {
            ShiftFillMode.ONE_SHIFT -> true
            ShiftFillMode.FIRST_SECOND -> settings.selectedShift in FIRST_SECOND_CYCLE
            ShiftFillMode.THREE_BRIGADE -> settings.selectedShift in THREE_BRIGADE_CYCLE
            ShiftFillMode.FOUR_BRIGADE -> {
                val validShift = settings.selectedShift in Shift.workShifts() || settings.selectedShift == Shift.OFF
                val validCycleIndex = settings.cycleDayIndex?.let { it in FOUR_BRIGADE_CYCLE.indices } ?: true
                validShift && validCycleIndex
            }
        }
    }

    fun applySickRange(
        repo: ShiftRepository,
        year: Int,
        month: Int,
        startDay: Int,
        endDay: Int,
        sickPayRate: Double? = null
    ): Int {
        var start = startDay
        var end = endDay
        if (start > end) {
            val temp = start
            start = end
            end = temp
        }

        val daysInMonth = LocalDate.of(year, month, 1).lengthOfMonth()
        val records = mutableListOf<BatchRecord>()

        for (day in start..end) {
            if (day in 1..daysInMonth) {
                if (LocalDate.of(year, month, day).dayOfWeek.value <= DayOfWeek.FRIDAY.value) {
                    records.add(BatchRecord(year, month, day, Shift.SICK, defaultHours, sickPayRate))
                }
            }
        }

        if (records.isNotEmpty()) repo.setBatch(records)
        return records.size
    }

    fun applyVacationRange(
        repo: ShiftRepository,
        year: Int,
        month: Int,
        startDay: Int,
        endDay: Int
    ): Int {
        val preview = previewVacationRange(repo, year, month, startDay, endDay)
        if (!preview.requestValidation.isAllowed) return 0
        val records = preview.countedDates.map { date ->
            BatchRecord(date.year, date.monthValue, date.dayOfMonth, Shift.VACATION, defaultHours)
        }

        if (records.isNotEmpty()) repo.setBatch(records)
        return records.size
    }

    fun previewVacationRange(
        repo: ShiftRepository,
        year: Int,
        month: Int,
        startDay: Int,
        endDay: Int
    ): VacationRangePreview {
        var start = startDay
        var end = endDay
        if (start > end) {
            val temp = start
            start = end
            end = temp
        }

        val daysInMonth = LocalDate.of(year, month, 1).lengthOfMonth()
        val countedDates = mutableListOf<LocalDate>()
        var skippedWeekendDays = 0
        var skippedHolidayDays = 0
        var skippedExistingSickDays = 0

        for (day in start..end) {
            if (day in 1..daysInMonth) {
                val date = LocalDate.of(year, month, day)
                val existing = repo.get(year, month, day)
                val isHoliday = existing?.holidayOverride ?: isSerbianHoliday(year, month, day)
                when {
                    !isVacationWorkday(date) -> skippedWeekendDays += 1
                    isHoliday -> skippedHolidayDays += 1
                    existing?.shift == Shift.SICK -> skippedExistingSickDays += 1
                    else -> countedDates.add(date)
                }
            }
        }

        val requestValidation = repo.validateVacationRequest(countedDates)
        val warnings = buildList {
            if (skippedWeekendDays > 0 || skippedHolidayDays > 0 || skippedExistingSickDays > 0) {
                add(VacationWarnings.RANGE_HAS_SKIPPED_DAYS)
            }
            if (countedDates.size in 1..5) {
                add(VacationWarnings.SHORT_PART_EXCEPTION)
            }
            if (countedDates.size in 6..9) {
                add(VacationWarnings.MAIN_PART_MISSING)
            }
            requestValidation.message?.let(::add)
        }

        return VacationRangePreview(
            startDate = LocalDate.of(year, month, start.coerceIn(1, daysInMonth)),
            endDate = LocalDate.of(year, month, end.coerceIn(1, daysInMonth)),
            countedDates = countedDates,
            skippedWeekendDays = skippedWeekendDays,
            skippedHolidayDays = skippedHolidayDays,
            skippedExistingSickDays = skippedExistingSickDays,
            warnings = warnings,
            requestValidation = requestValidation
        )
    }
}
