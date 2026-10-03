package com.rad.shifttracker

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import java.util.Locale

/**
 * Pomoć za testove popunjavanja po listi: sedmična rotacija i lista sastavljena iz podataka same
 * aplikacije (tako je uvek u skladu sa obračunom, pa popuna mora da nađe raspored).
 */
internal val TEST_ROTATION_CYCLE = listOf(Shift.THIRD, Shift.SECOND, Shift.FIRST)

/** Smena radne nedelje; [phase] je smena nedelje koja počinje ponedeljkom 06.07.2026. */
internal fun rotationShiftFor(date: LocalDate, phase: Shift): Shift {
    val anchorMonday = LocalDate.of(2026, 7, 6)
    val weeks = Math.floorDiv(ChronoUnit.DAYS.between(anchorMonday, date.with(DayOfWeek.MONDAY)).toInt(), 7)
    return TEST_ROTATION_CYCLE[Math.floorMod(weeks + TEST_ROTATION_CYCLE.indexOf(phase), 3)]
}

private fun isHolidayDate(date: LocalDate) = isSerbianHoliday(date.year, date.monthValue, date.dayOfMonth)

/** Šablon smena („Popuni“) upisan za sve radne dane (osim praznika) između [from] i [to]. */
internal fun templateRepo(from: LocalDate, to: LocalDate, phase: Shift = Shift.THIRD): ShiftRepository {
    val repo = testRepository()
    repo.initialCompBalanceValue = 40
    val batch = mutableListOf<BatchRecord>()
    var date = from
    while (date <= to) {
        if (date.dayOfWeek.value <= DayOfWeek.FRIDAY.value && !isHolidayDate(date)) {
            batch += BatchRecord(
                date.year, date.monthValue, date.dayOfMonth, rotationShiftFor(date, phase), 8,
                source = DayRecordSource.AUTO_FILL
            )
        }
        date = date.plusDays(1)
    }
    repo.setBatch(batch)
    return repo
}

/** Šablon samo iz meseca pre [year]/[month] — kao pri prvom pokretanju sa dve nedelje smena. */
internal fun templateRepoBefore(year: Int, month: Int, phase: Shift = Shift.THIRD): ShiftRepository {
    val first = LocalDate.of(year, month, 1).minusMonths(1)
    return templateRepo(first, first.withDayOfMonth(first.lengthOfMonth()), phase)
}

/** Dan pravog meseca za [payslipFromRepo]: sve što lista pamti o njemu. */
internal data class TruthDay(
    val day: Int,
    val shift: Shift,
    val hours: Int = 8,
    val shift2: Shift? = null,
    val hours2: Int = 0,
    val exempt: Boolean = false,
    val sickRate: Double? = null,
    val source: DayRecordSource = DayRecordSource.MANUAL
)

/** Pravi mesec: radni dani po rotaciji, praznici slobodni, pa [changes] preko toga. */
internal fun truthMonth(
    year: Int,
    month: Int,
    phase: Shift,
    changes: List<TruthDay>,
    configure: (ShiftRepository) -> Unit = {}
): ShiftRepository {
    val repo = testRepository()
    repo.initialCompBalanceValue = 40
    val plan = LinkedHashMap<Int, TruthDay>()
    for (day in 1..LocalDate.of(year, month, 1).lengthOfMonth()) {
        val date = LocalDate.of(year, month, day)
        if (date.dayOfWeek.value > DayOfWeek.FRIDAY.value) continue
        plan[day] = if (isHolidayDate(date)) {
            TruthDay(day, Shift.OFF, 0, exempt = true, source = DayRecordSource.AUTO_FILL)
        } else {
            TruthDay(day, rotationShiftFor(date, phase), source = DayRecordSource.AUTO_FILL)
        }
    }
    changes.forEach { plan[it.day] = it }
    repo.setBatch(
        plan.values.filter { it.shift2 == null }.map {
            BatchRecord(year, month, it.day, it.shift, it.hours, sickPayRate = it.sickRate, balanceExempt = it.exempt, source = it.source)
        }
    )
    plan.values.filter { it.shift2 != null }.forEach {
        repo.set(year, month, it.day, it.shift, it.hours, shift2 = it.shift2, hours2 = it.hours2)
    }
    configure(repo)
    return repo
}

private fun hoursText(minutes: Int) = String.format(Locale.US, "%.1f", minutes / 60.0)

/** Lista sa satima koje aplikacija računa za [repo] u mesecu (isti oblik redova kao pravi listić). */
internal fun payslipFromRepo(repo: ShiftRepository, year: Int, month: Int): ParsedPayslip {
    val details = repo.getMonthlyPayDetails(year, month)
    val breakdown = repo.getNetPayBreakdown(year, month)
    val holidayClockMinutes = repo.getMonthRecords(year, month).sumOf { record ->
        paidWorkMinuteSegments(record).sumOf { (shift, minutes) -> record.holidayPremiumMinutes(shift, minutes) }
    }
    val lines = mutableListOf(
        "OBRAČUN ZARADE I NAKNADE ZARADE ZA MESEC %02d/%d rb.3/konačna".format(month, year),
        "Fond za mesec : ${breakdown.monthlyFundHours} Koeficijent : 2.82 Vrednost sata : 226.00 Osnovna zarada : 100,000.00"
    )
    fun item(code: String, name: String, minutes: Int, percent: String? = null) {
        if (minutes > 0) lines += "$code $name ${hoursText(minutes)} ${percent?.let { "$it " }.orEmpty()}1000.00"
    }
    item("95", "Izostanci", details.fundAbsenceMinutes(breakdown.monthlyFundHours))
    item("01", "Rad u reziji", details.regularWorkedBaseMinutes)
    item("05", "Rad u reziji na DP", details.publicHolidayWorkedBaseMinutes)
    item("23", "Rad na praznik", holidayClockMinutes, "123")
    item("10", "Godisnji odmor", details.vacationMinutes)
    item("11", "Placeno odsustvo", details.paidLeaveMinutes)
    item("12", "Drzavni praznik", details.paidHolidayMinutes)
    item("40", "Bolovanje za tekuci mesec", details.sickMinutes, "65")
    item("20", "Nocni rad", details.nightMinutes, "41")
    item("22", "Rad nedeljom", details.sundayMinutes, "25")
    item("30", "Prekovremeni rad", details.overtimeMinutes)
    item("24", "Uvecanje za prekovremeni rad", details.overtimeMinutes, "41")
    return (parsePayslip(lines) as PayslipParseResult.Success).payslip
}

internal fun hourMismatches(repo: ShiftRepository, payslip: ParsedPayslip): List<PayslipCheckRow> =
    buildPayslipReconciliation(repo, payslip).rows.filter { it.isHours && !it.matches }
