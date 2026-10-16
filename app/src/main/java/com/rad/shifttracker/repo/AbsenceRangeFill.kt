package com.rad.shifttracker.repo

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth
import com.rad.shifttracker.smene.Shift
import com.rad.shifttracker.smene.ShiftScheduler
import com.rad.shifttracker.smene.isSerbianHoliday

/** Najduži opseg „Do kog dana“ u prozoru dana. */
internal const val ABSENCE_RANGE_MAX_DAYS = 365L

/**
 * Radni dani bolovanja ili godišnjeg od [start] do [end], i preko granice meseca. Bolovanje: pon–pet
 * bez praznika (praznik ostaje praznik); godišnji kao [previewVacationRange] (bez vikenda, praznika i bolovanja).
 */
fun ShiftScheduler.absenceRangeDates(repo: ShiftRepository, shift: Shift, start: LocalDate, end: LocalDate): List<LocalDate> {
    if (end.isBefore(start)) return emptyList()
    return monthSegments(start, end).flatMap { (month, from, to) ->
        when (shift) {
            Shift.SICK -> (from..to)
                .map { month.atDay(it) }
                .filter { repo.isAbsenceWorkday(it) }
            Shift.VACATION -> previewVacationRange(repo, month.year, month.monthValue, from, to).countedDates
            else -> emptyList()
        }
    }
}

/** Radni dan za opseg odsustva: pon–pet i nije praznik (ručno isključen praznik se računa kao radni). */
internal fun ShiftRepository.isAbsenceWorkday(date: LocalDate): Boolean {
    if (date.dayOfWeek.value > DayOfWeek.FRIDAY.value) return false
    val holiday = get(date.year, date.monthValue, date.dayOfMonth)?.holidayOverride
        ?: isSerbianHoliday(date.year, date.monthValue, date.dayOfMonth)
    return !holiday
}

/** Sledeći (step = 1) ili prethodni (step = -1) radni dan posle [from]; null posle [limit] dana traženja. */
internal fun ShiftRepository.nearestAbsenceWorkday(from: LocalDate, step: Long, limit: Long = 31): LocalDate? =
    generateSequence(from.plusDays(step)) { it.plusDays(step) }
        .take(limit.toInt())
        .firstOrNull { isAbsenceWorkday(it) }

/**
 * Upisuje bolovanje ili godišnji od [start] do [end] (i preko granice meseca) i vraća broj
 * upisanih dana; godišnji se ne upisuje ako ga nema dovoljno (0). U četvorobrigadi se smene
 * vikendom unutar odsustva oslobađaju, kao u Bol/GO.
 */
fun ShiftScheduler.applyAbsenceRange(
    repo: ShiftRepository,
    shift: Shift,
    start: LocalDate,
    end: LocalDate,
    sickPayRate: Double? = null
): Int {
    val dates = absenceRangeDates(repo, shift, start, end)
    if (dates.isEmpty()) return 0
    if (shift == Shift.VACATION && !repo.validateVacationRequest(dates).isAllowed) return 0
    val records = dates.map { date ->
        BatchRecord(
            date.year,
            date.monthValue,
            date.dayOfMonth,
            shift,
            defaultHours,
            if (shift == Shift.SICK) sickPayRate else null
        )
    }
    repo.setBatch(records + fourBrigadeShiftsInsideAbsence(repo, records))
    return records.size
}

private fun monthSegments(start: LocalDate, end: LocalDate): List<Triple<YearMonth, Int, Int>> {
    val segments = mutableListOf<Triple<YearMonth, Int, Int>>()
    var month = YearMonth.from(start)
    val last = YearMonth.from(end)
    while (month <= last) {
        val from = if (month == YearMonth.from(start)) start.dayOfMonth else 1
        val to = if (month == last) end.dayOfMonth else month.lengthOfMonth()
        segments += Triple(month, from, to)
        month = month.plusMonths(1)
    }
    return segments
}

/**
 * Promena neto procene za dane posle [start] do [end] kad se upiše bolovanje ili godišnji, istom
 * dnevnom procenom kao u prozoru dana. Računa se na kopiji podataka; [repo] se ne menja. Sam [start]
 * nije uključen (njega prozor dana već računa iz unosa).
 */
fun ShiftScheduler.absenceRangeNetDifference(
    repo: ShiftRepository,
    shift: Shift,
    start: LocalDate,
    end: LocalDate,
    sickPayRate: Double? = null
): Double {
    if (!end.isAfter(start)) return 0.0
    val copy = ShiftRepository(repo.config, repo.clock)
    if (!copy.importFromJson(repo.exportJson())) return 0.0
    if (applyAbsenceRange(copy, shift, start, end, sickPayRate) == 0) return 0.0
    return generateSequence(start.plusDays(1)) { it.plusDays(1) }
        .takeWhile { !it.isAfter(end) }
        .sumOf { date ->
            val after = copy.get(date.year, date.monthValue, date.dayOfMonth)
            val before = repo.get(date.year, date.monthValue, date.dayOfMonth)
            (after?.let(copy::calculateEstimatedDailyWorkNetPay) ?: 0.0) -
                (before?.let(repo::calculateEstimatedDailyWorkNetPay) ?: 0.0)
        }
}
