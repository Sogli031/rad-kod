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

    val normalizedSettings = normalizeFillSettings(settings)
    repo.brigadeTypeValue = brigadeTypeForFillMode(normalizedSettings.mode)

    val referenceMonth = referenceDate.withDayOfMonth(1)
    repo.deleteAutoFilledHolidayRecordsBefore(
        MonthKey(referenceMonth.year, referenceMonth.monthValue)
    )
    // Ponovna popuna (npr. promena brigade) presnimava raspored od danas nadalje bez pitanja;
    // prošli dani ostaju kakvi su — tada se radilo po starom rasporedu.
    repo.clearScheduleFrom(referenceDate)
    val endMonth = referenceMonth.plusMonths(safeMonths.toLong())
    return fillDateRange(
        repo = repo,
        normalizedSettings = normalizedSettings,
        startDate = referenceMonth,
        endDate = endMonth.withDayOfMonth(endMonth.lengthOfMonth()),
        keepExistingBefore = referenceDate
    )
}

/**
 * Popuna ide „kontinualno“: drži se popunjeno samo do kraja meseca [AUTO_FILL_MONTHS_AHEAD]
 * meseci posle tekućeg, a kako vreme odmiče (pri pokretanju aplikacije) dopisuju se novi dani.
 * Tako raspored nikad ne ističe, a u memoriji je uvek najviše oko godinu dana unapred.
 */
const val AUTO_FILL_MONTHS_AHEAD = 12

/** Poslednji dan koji auto-popuna drži popunjenim. */
fun autoFillHorizonEnd(today: LocalDate): LocalDate {
    val endMonth = today.withDayOfMonth(1).plusMonths(AUTO_FILL_MONTHS_AHEAD.toLong())
    return endMonth.withDayOfMonth(endMonth.lengthOfMonth())
}

/**
 * Dopisuje dane od poslednjeg auto-popunjenog do [autoFillHorizonEnd], po istom obrascu.
 * Ne dira prošlost, ručne unose ni ranije popunjene dane. Bez ijednog auto-popunjenog dana
 * (popuna nikad nije pokrenuta) ne radi ništa. Vraća broj upisanih dana.
 */
fun ShiftScheduler.extendAutoFill(
    repo: ShiftRepository,
    today: LocalDate,
    settings: () -> ShiftFillSettings?
): Int {
    val lastFilled = repo.lastAutoFilledDate() ?: return 0
    val start = maxOf(lastFilled.plusDays(1), today.withDayOfMonth(1))
    val end = autoFillHorizonEnd(today)
    if (start.isAfter(end)) return 0
    val pattern = settings()?.takeIf(::isValidFillSettings) ?: return 0
    return fillDateRange(repo, normalizeFillSettings(pattern), start, end)
}

private fun ShiftScheduler.normalizeFillSettings(settings: ShiftFillSettings): ShiftFillSettings =
    when (settings.mode) {
        ShiftFillMode.FIRST_SECOND,
        ShiftFillMode.THREE_BRIGADE -> settings.copy(anchorDate = getReferenceWorkWeek(settings.anchorDate).start)
        else -> settings
    }

private fun ShiftScheduler.fillDateRange(
    repo: ShiftRepository,
    normalizedSettings: ShiftFillSettings,
    startDate: LocalDate,
    endDate: LocalDate,
    keepExistingBefore: LocalDate = startDate
): Int {
    val records = mutableListOf<BatchRecord>()

    var date = startDate
    while (!date.isAfter(endDate)) {
        val existing = repo.get(date.year, date.monthValue, date.dayOfMonth)
        // Automatsko popunjavanje ne sme da pregazi ručne unose
        // (godišnji, bolovanje, dupla smena...) ni krvodavalaštvo —
        // zamenjuje samo prazne dane i ranije auto-popunjene zapise.
        // Pre [keepExistingBefore] (već prošli dani) dopunjavaju se samo prazni dani.
        val keepExisting = existing != null &&
            (existing.source != DayRecordSource.AUTO_FILL || date.isBefore(keepExistingBefore))
        if (keepExisting) {
            date = date.plusDays(1)
            continue
        }
        // Ručno isključen/uključen praznik (holidayOverride) ima prednost
        // nad kalendarom — isto kao u previewVacationRange.
        val isHolidayDate = existing?.holidayOverride
            ?: isSerbianHoliday(date.year, date.monthValue, date.dayOfMonth)
        // Praznik pon–pet je slobodan dan (plaćen kao državni praznik); subotom četvorobrigada radi po ciklusu.
        val holidayDayApplies = date.dayOfWeek.value <= DayOfWeek.FRIDAY.value
        val record = if (holidayDayApplies && isHolidayDate) {
            BatchRecord(
                year = date.year,
                month = date.monthValue,
                day = date.dayOfMonth,
                shift = Shift.OFF,
                hours = 0,
                balanceExempt = normalizedSettings.mode != ShiftFillMode.FOUR_BRIGADE ||
                    !isFourBrigadeCycleWorkday(date, normalizedSettings),
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

/**
 * Četvorobrigada, praznik pon–pet (livnica, listići 12/2024–08/2026): kad je po ciklusu tog dana
 * smena (i ponedeljkom kad je prva smena slobodna, odnosno subotom kad je treća), praznik se najčešće ne radi i plaća se (12) —
 * zapis bez [BatchRecord.balanceExempt]. Slobodan dan po ciklusu ostaje balanceExempt i ne plaća
 * se: pun fond ga već pokriva kroz rad u režiji (01), npr. 11.11.2025.
 */
private fun ShiftScheduler.isFourBrigadeCycleWorkday(date: LocalDate, settings: ShiftFillSettings): Boolean {
    val cycleStartIndex = settings.cycleDayIndex ?: getFourBrigadeCycleStartIndex(settings.selectedShift, 0)
    return fourBrigadeCycleShiftForDate(date, settings.anchorDate, cycleStartIndex) in Shift.workShifts()
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

    if (records.isNotEmpty()) repo.setBatch(records + fourBrigadeShiftsInsideAbsence(repo, records))
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

    if (records.isNotEmpty()) repo.setBatch(records + fourBrigadeShiftsInsideAbsence(repo, records))
    return records.size
}

/**
 * Četvorobrigada: smena iz „Popuni“ van pon–pet između prvog i poslednjeg dana odsustva se ne radi
 * (na godišnjem se ni subotom ne ide na posao) — listić livnice 08/2025: 15 dana GO, 4 odrađene smene.
 */
private fun fourBrigadeShiftsInsideAbsence(repo: ShiftRepository, absence: List<BatchRecord>): List<BatchRecord> {
    if (repo.brigadeTypeValue != BrigadeType.CETVOROBRIGADA || absence.isEmpty()) return emptyList()
    val dates = absence.map { LocalDate.of(it.year, it.month, it.day) }
    val first = dates.min()
    val last = dates.max()
    return generateSequence(first.plusDays(1)) { it.plusDays(1) }
        .takeWhile { it < last }
        .filter { it.dayOfWeek.value > DayOfWeek.FRIDAY.value }
        .mapNotNull { date -> repo.get(date.year, date.monthValue, date.dayOfMonth) }
        .filter { it.source == DayRecordSource.AUTO_FILL && it.shift in Shift.workShifts() }
        .map { BatchRecord(it.year, it.month, it.day, Shift.OFF, 0, balanceExempt = true, source = DayRecordSource.AUTO_FILL) }
        .toList()
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
