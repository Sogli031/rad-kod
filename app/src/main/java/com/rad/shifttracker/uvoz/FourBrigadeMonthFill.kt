package com.rad.shifttracker.uvoz

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth
import kotlin.math.abs
import com.rad.shifttracker.listici.FillDay
import com.rad.shifttracker.listici.FillDayKind
import com.rad.shifttracker.listici.FillHints
import com.rad.shifttracker.listici.FillTargets
import com.rad.shifttracker.listici.ParsedPayslip
import com.rad.shifttracker.listici.DIFFICULT_WORK_CONDITIONS_CODE
import com.rad.shifttracker.obracun.holidayPremiumMinutes
import com.rad.shifttracker.obracun.isPaidHolidayOff
import com.rad.shifttracker.obracun.nightWorkMinutes
import com.rad.shifttracker.obracun.paidWorkMinuteSegments
import com.rad.shifttracker.obracun.sundayWorkMinutes
import com.rad.shifttracker.repo.FourBrigadeCycle
import com.rad.shifttracker.repo.ShiftRepository
import com.rad.shifttracker.repo.fourBrigadeCycle
import com.rad.shifttracker.smene.BrigadeType
import com.rad.shifttracker.smene.DayRecord
import com.rad.shifttracker.smene.DayRecordSource
import com.rad.shifttracker.smene.MonthKey
import com.rad.shifttracker.smene.Shift
import com.rad.shifttracker.smene.ShiftScheduler
import com.rad.shifttracker.smene.isSerbianHoliday
import com.rad.shifttracker.smene.monthYearText

/*
 * Popuna meseca po listi za četvorobrigadu. Lista daje samo zbirove, a četvorobrigada se retko
 * slaže u sat (zamene smena, dodatna nedelja, sat noćnog manje — listići livnice 12/2024–08/2026),
 * pa se upisuje najverovatniji raspored:
 *  - smene po ciklusu prepoznatom iz upisanih smena (nedelja slobodna; do 12.10.2026. ponedeljkom bez prve smene, od tada subotom bez treće),
 *  - GO, plaćeno odsustvo i bolovanje kao uzastopni dani pon–pet (isto kao „Bol/GO“),
 *  - praznik na dan smene: slobodan i plaćen ili rađen, šta bolje odgovara listi,
 * tako da noćni, nedelja, rad na praznik, državni praznik i odrađeni sati (šifra 21) budu što bliži
 * listi. Razlike koje ostanu vide se u poređenju sa listom.
 */

internal const val FOUR_BRIGADE_NO_CYCLE_TEXT =
    "Za četvorobrigadu prvo upiši raspored („Popuni“ → Četvorobrigada), pa popuni mesec po listi."

private const val DAY_HOURS = 8
private const val MINUTES_PER_HOUR = 60

internal fun planFourBrigadeMonth(
    repo: ShiftRepository,
    payslip: ParsedPayslip,
    targets: FillTargets,
    alternatives: MutableList<List<FillDay>>? = null
): MonthFillResult {
    val days = bestFourBrigadeDays(repo, payslip, targets, alternatives) ?: return MonthFillResult.Failure(
        fourBrigadeFailureText(repo, payslip.month)
    )
    val scratch = verificationCopy(repo)
    return MonthFillResult.Success(
        month = payslip.month,
        days = days,
        reconciliation = verifyMonthFill(scratch, payslip, targets, days),
        existingRecordCount = userEnteredDayCount(repo, payslip.month),
        absenceRoundingGross = targets.absenceRoundingGross
    )
}

private fun fourBrigadeFailureText(repo: ShiftRepository, month: MonthKey): String =
    if (repo.fourBrigadeCycle() == null) {
        FOUR_BRIGADE_NO_CYCLE_TEXT
    } else {
        "Godišnji, odsustvo i bolovanje sa liste ne staju u radne dane za ${monthYearText(month)}. Mesec nije menjan."
    }

/** Najverovatniji raspored meseca; null kad ciklus nije poznat ili blokovi odsustva ne staju u mesec. */
internal fun bestFourBrigadeDays(
    repo: ShiftRepository,
    payslip: ParsedPayslip,
    targets: FillTargets,
    alternatives: MutableList<List<FillDay>>? = null
): List<FillDay>? {
    val cycle = repo.fourBrigadeCycle() ?: return null
    val worked = payslip.items[DIFFICULT_WORK_CONDITIONS_CODE]?.hours?.toInt()
    return FourBrigadeMonthSearch(payslip.month, cycle, targets, worked, fillHints(repo, payslip)).best(alternatives)
}

/**
 * Da li su dani meseca već upisani tačno kao [days] (pa ponovna popuna ništa ne menja).
 * Kod četvorobrigade lista često ostane različita i posle popune, pa se mesec ne nudi ponovo.
 */
internal fun monthMatchesFillDays(repo: ShiftRepository, month: MonthKey, days: List<FillDay>): Boolean {
    val records = repo.getMonthRecords(month.year, month.month).associateBy { it.date }
    if (records.size != days.size) return false
    return days.all { day ->
        val record = records[day.date] ?: return@all false
        val (shift, hours) = when (day.kind) {
            FillDayKind.WORK, FillDayKind.HOLIDAY_WORK, FillDayKind.WEEKEND_WORK -> day.shift to day.hours
            FillDayKind.VACATION -> Shift.VACATION to DAY_HOURS
            FillDayKind.SICK, FillDayKind.PAID_LEAVE -> Shift.SICK to DAY_HOURS
            FillDayKind.ABSENT, FillDayKind.HOLIDAY_OFF, FillDayKind.CYCLE_OFF -> Shift.OFF to 0
        }
        record.shift == shift && record.workHours == hours
    }
}

/** Stanje dana u rasporedu i njegov doprinos zbirovima koje lista daje. */
private enum class DayState { CYCLE, LEAVE, HOLIDAY_ALT }

/** Zbir sati: odrađeno, noćni, nedelja, rad na praznik, državni praznik. */
private class Hours(val worked: Int = 0, val night: Int = 0, val sunday: Int = 0, val holiday: Int = 0, val paidHoliday: Int = 0) {
    operator fun plus(o: Hours) = Hours(worked + o.worked, night + o.night, sunday + o.sunday, holiday + o.holiday, paidHoliday + o.paidHoliday)
    operator fun minus(o: Hours) = Hours(worked - o.worked, night - o.night, sunday - o.sunday, holiday - o.holiday, paidHoliday - o.paidHoliday)
}

private class FourBrigadeMonthSearch(
    private val month: MonthKey,
    private val cycle: FourBrigadeCycle,
    private val targets: FillTargets,
    private val workedTarget: Int?,
    private val hints: FillHints
) {
    private val scheduler = ShiftScheduler()
    private val dates: List<LocalDate> = YearMonth.of(month.year, month.month).let { ym -> (1..ym.lengthOfMonth()).map(ym::atDay) }
    private val cycleShift = dates.associateWith { scheduler.fourBrigadeCycleShiftForDate(it, cycle.anchorDate, cycle.cycleStartIndex) }
    private val scheduled = dates.associateWith {
        scheduler.calculateFourBrigadeShiftForDate(it, cycle.anchorDate, cycle.cycleStartIndex)
    }

    private fun isHoliday(date: LocalDate) = isSerbianHoliday(date.year, date.monthValue, date.dayOfMonth)
    private fun isWeekday(date: LocalDate) = date.dayOfWeek.value <= DayOfWeek.FRIDAY.value

    /** Praznik na dan smene po rasporedu: može da bude rađen ili ne (pon–pet podrazumevano ne, subotom da). */
    private val choosable = dates.filter { isHoliday(it) && scheduled.getValue(it) in Shift.workShifts() }

    private fun holidayWorkedByDefault(date: LocalDate) = !isWeekday(date)

    /** Zapis dana u stanju [state] (bez odsustva — ono se dodaje posebno). */
    private fun record(date: LocalDate, state: DayState): DayRecord {
        val shift = scheduled.getValue(date)
        val worked = when {
            state == DayState.LEAVE -> false
            date in choosable -> holidayWorkedByDefault(date) != (state == DayState.HOLIDAY_ALT)
            isWeekday(date) && isHoliday(date) -> false
            else -> shift in Shift.workShifts()
        }
        return if (worked) {
            DayRecord(date.year, date.monthValue, date.dayOfMonth, shift, DAY_HOURS, source = DayRecordSource.AUTO_FILL)
        } else {
            // Praznik pon–pet na dan smene po ciklusu je plaćen; ostalo je slobodan dan po ciklusu.
            val paidHoliday = isWeekday(date) && isHoliday(date) && cycleShift.getValue(date) in Shift.workShifts()
            DayRecord(date.year, date.monthValue, date.dayOfMonth, Shift.OFF, 0, balanceExempt = !paidHoliday, source = DayRecordSource.AUTO_FILL)
        }
    }

    private fun hoursOf(record: DayRecord): Hours {
        val segments = paidWorkMinuteSegments(record)
        return Hours(
            worked = segments.sumOf { it.second } / MINUTES_PER_HOUR,
            night = nightWorkMinutes(record) / MINUTES_PER_HOUR,
            sunday = sundayWorkMinutes(record) / MINUTES_PER_HOUR,
            holiday = segments.sumOf { (shift, minutes) -> record.holidayPremiumMinutes(shift, minutes) } / MINUTES_PER_HOUR,
            paidHoliday = if (record.isPaidHolidayOff(BrigadeType.CETVOROBRIGADA)) DAY_HOURS else 0
        )
    }

    private val base = dates.associateWith { hoursOf(record(it, DayState.CYCLE)) }
    private val alt = choosable.associateWith { hoursOf(record(it, DayState.HOLIDAY_ALT)) }

    /** Uzastopni dani pon–pet bez praznika od [start] (kao godišnji u „Bol/GO“). */
    private fun weekdayRun(start: LocalDate, count: Int): List<LocalDate>? {
        val run = dates.filter { it >= start && isWeekday(it) && !isHoliday(it) }.take(count)
        return run.takeIf { it.size == count }
    }

    private fun score(total: Hours): Int {
        val worked = workedTarget?.let { abs(total.worked - it) } ?: 0
        return worked + abs(total.night - targets.nightHours) + abs(total.sunday - targets.sundayHours) +
            abs(total.holiday - targets.holidayPremiumHours) + abs(total.paidHoliday - targets.paidHolidayHours)
    }

    private class Candidate(
        val leave: Map<LocalDate, Int>,
        val covered: Set<LocalDate>,
        val altHolidays: Set<LocalDate>,
        val score: Int,
        val penalty: Int
    )

    /** Dani van pon–pet između prvog i poslednjeg dana odsustva: na godišnjem se ni subotom ne radi. */
    private fun coveredDays(run: List<LocalDate>): List<LocalDate> =
        dates.filter { it > run.first() && it < run.last() && !isWeekday(it) }

    fun best(alternatives: MutableList<List<FillDay>>? = null): List<FillDay>? {
        val runsPerBlock = targets.blocks.map { block ->
            dates.mapNotNull { weekdayRun(it, block.days) }.distinct().also { if (it.isEmpty()) return null }
        }
        val holidayMasks = (0 until (1 shl choosable.size)).map { mask ->
            choosable.filterIndexed { index, _ -> mask and (1 shl index) != 0 }.toSet()
        }
        val baseTotal = base.values.fold(Hours()) { sum, h -> sum + h }
        var best: Candidate? = null
        val finalists = mutableListOf<Candidate>()
        fun consider(leave: Map<LocalDate, Int>) {
            val covered = targets.blocks.indices.flatMap { index ->
                leave.filterValues { it == index }.keys.sorted().takeIf { it.isNotEmpty() }?.let(::coveredDays).orEmpty()
            }.toSet()
            for (alts in holidayMasks) {
                var total = baseTotal
                for (date in alts) if (date !in leave && date !in covered) total = total - base.getValue(date) + alt.getValue(date)
                for (date in leave.keys + covered) total = total - base.getValue(date)
                val candidate = Candidate(leave, covered, alts, score(total), penalty(leave, alts))
                val current = best
                if (alternatives != null) offerFinalist(finalists, candidate, current)
                if (isBetter(candidate, current)) {
                    best = candidate
                }
            }
        }
        fun place(blockIndex: Int, leave: Map<LocalDate, Int>) {
            if (blockIndex == runsPerBlock.size) {
                consider(leave)
                return
            }
            for (run in runsPerBlock[blockIndex]) {
                if (run.any { it in leave }) continue
                place(blockIndex + 1, leave + run.associateWith { blockIndex })
            }
        }
        place(0, emptyMap())
        val chosen = best ?: return null
        alternatives?.addAll(finalists.map { candidate -> dates.map { date -> fillDay(date, candidate) } }.distinct())
        return dates.map { date -> fillDay(date, chosen) }
    }

    private fun isBetter(candidate: Candidate, current: Candidate?): Boolean =
        current == null || candidate.score < current.score ||
            (candidate.score == current.score && candidate.penalty < current.penalty)

    private fun offerFinalist(finalists: MutableList<Candidate>, candidate: Candidate, current: Candidate?) {
        if (current != null && candidate.score > current.score) return
        if (current == null || candidate.score < current.score) finalists.clear()
        finalists.add(candidate)
        finalists.sortBy { it.penalty }
        if (finalists.size > 12) finalists.removeAt(finalists.lastIndex)
    }

    /** Prednost: odsustvo koje se nastavlja iz susednog meseca i što manje odstupanja od pravila za praznike. */
    private fun penalty(leave: Map<LocalDate, Int>, alts: Set<LocalDate>): Int {
        var penalty = alts.size
        val weekdays = dates.filter { isWeekday(it) && !isHoliday(it) }
        targets.blocks.forEachIndexed { index, block ->
            val run = leave.filterValues { it == index }.keys.sorted()
            if (run.isEmpty()) return@forEachIndexed
            if (block.kind in hints.continuesFromPrevious && run.first() != weekdays.firstOrNull()) penalty += 2
            if (block.kind in hints.continuesIntoNext && run.last() != weekdays.lastOrNull()) penalty += 2
            val preferred = hints.preferredStarts[block.kind].orEmpty()
            if (preferred.isNotEmpty() && run.first() !in preferred) penalty += 1
        }
        return penalty
    }

    private fun fillDay(date: LocalDate, chosen: Candidate): FillDay {
        chosen.leave[date]?.let { blockIndex ->
            val block = targets.blocks[blockIndex]
            return FillDay(date, block.kind, null, DAY_HOURS, templateShift = scheduled.getValue(date), sickRate = block.sickRate)
        }
        if (date in chosen.covered) return FillDay(date, FillDayKind.CYCLE_OFF, null, templateShift = null)
        val record = record(date, if (date in chosen.altHolidays) DayState.HOLIDAY_ALT else DayState.CYCLE)
        val shift = record.shift
        return when {
            shift in Shift.workShifts() && date in chosen.altHolidays ->
                FillDay(date, FillDayKind.HOLIDAY_WORK, shift, DAY_HOURS)
            shift in Shift.workShifts() -> FillDay(date, FillDayKind.WORK, shift, DAY_HOURS)
            !record.balanceExempt -> FillDay(date, FillDayKind.HOLIDAY_OFF, null, templateShift = cycleShift.getValue(date))
            else -> FillDay(date, FillDayKind.CYCLE_OFF, null, templateShift = null)
        }
    }
}
