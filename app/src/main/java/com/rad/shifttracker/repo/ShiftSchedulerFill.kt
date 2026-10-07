package com.rad.shifttracker.repo

import java.time.DayOfWeek
import java.time.LocalDate
import com.rad.shifttracker.godisnji.VacationRangePreview
import com.rad.shifttracker.godisnji.VacationWarnings
import com.rad.shifttracker.godisnji.isVacationWorkday
import com.rad.shifttracker.smene.BrigadeType
import com.rad.shifttracker.smene.DayRecordSource
import com.rad.shifttracker.smene.MonthKey
import com.rad.shifttracker.smene.Shift
import com.rad.shifttracker.smene.ShiftFillMode
import com.rad.shifttracker.smene.ShiftFillSettings
import com.rad.shifttracker.smene.ShiftScheduler
import com.rad.shifttracker.smene.isSerbianHoliday

/*
 * Upis rasporeda u kalendar: [ShiftScheduler] samo računa smenu za datum, a ovde se popunjeni
 * meseci, bolovanje i godišnji upisuju u [ShiftRepository].
 */

fun ShiftScheduler.populateMonthsAhead(
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

fun ShiftScheduler.populateMonthsAhead(
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

fun ShiftScheduler.applySickRange(
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

fun ShiftScheduler.applyVacationRange(
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

fun ShiftScheduler.previewVacationRange(
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
