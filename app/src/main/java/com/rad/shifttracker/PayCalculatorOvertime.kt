package com.rad.shifttracker

import java.time.LocalDate
import java.time.YearMonth
import kotlin.math.min

// Mesečna raspodela prekovremenog (trobrigada sa odradom, četvorobrigada po fondu) i dopuna do fonda.

private const val MINUTES_PER_HOUR = 60

internal fun fourBrigadeOvertimeMinutesByDay(
    sorted: List<DayRecord>,
    year: Int,
    month: Int,
    defaultHours: Int
): Map<DayKey, Int> {
    var regularMinutesRemaining = calculateMonthlyFundHours(year, month, defaultHours) * MINUTES_PER_HOUR
    if (regularMinutesRemaining <= 0) return emptyMap()

    // Naknade (bolovanje, GO, plaćeno odsustvo, plaćeni praznici) na listiću
    // pune mesečni fond, pa je norma za redovan rad fond minus ti sati —
    // inače bi mesec sa bolovanjem tražio pun fond stvarnog rada pre prvog
    // prekovremenog minuta.
    regularMinutesRemaining -= min(paidAbsenceFundMinutes(sorted, year, month, defaultHours), regularMinutesRemaining)

    // Radni praznici prvo troše fond i nikad ne nose prekovremene —
    // njihovi sati se plaćaju kroz prazničnu premiju
    // (monthlyOvertimeMinutesForRecord za njih vraća 0). Da im se višak
    // dodeli pa nulira, ti minuti bi propali, a ukupna plata bi zavisila
    // od toga gde u mesecu praznik pada.
    for (record in sorted) {
        if (!record.workedOnHoliday) continue
        val recordPaidMinutes = paidWorkMinuteSegments(record).sumOf { (_, minutes) -> minutes }
        regularMinutesRemaining -= min(recordPaidMinutes, regularMinutesRemaining)
    }

    val overtimeByDay = mutableMapOf<DayKey, Int>()
    for (record in sorted) {
        if (record.workedOnHoliday) continue
        val recordPaidMinutes = paidWorkMinuteSegments(record).sumOf { (_, minutes) -> minutes }
        if (recordPaidMinutes <= 0) continue

        val regularMinutes = min(recordPaidMinutes, regularMinutesRemaining)
        val overtimeMinutes = recordPaidMinutes - regularMinutes
        regularMinutesRemaining -= regularMinutes
        if (overtimeMinutes > 0) {
            overtimeByDay[record.key] = overtimeMinutes
        }
    }

    return overtimeByDay
}

/**
 * Listić broji rad na praznik (05) po satu, isto kao dodatak 123% (23): treća smena uoči praznika
 * daje 6 h na praznik, a ona koja počinje na praznik samo 2 h (a ne cela smena po danu početka).
 */
internal fun clockHolidayWorkMinutes(records: List<DayRecord>): Int = records.sumOf { record ->
    paidWorkMinuteSegments(record).sumOf { (shift, minutes) -> record.holidayPremiumMinutes(shift, minutes) }
}

// Dopuna do fonda važi samo za popunjen mesec (svaki pon–pet upisan, kao posle „Popuni smene“),
// da mesec sa par upisanih dana ne dobije ceo fond.
internal fun isEveryWorkdayRecorded(records: List<DayRecord>, year: Int, month: Int): Boolean {
    val recordedDays = records.map { it.day }.toSet()
    return (1..YearMonth.of(year, month).lengthOfMonth()).all { day ->
        day in recordedDays || !Weekday.from(LocalDate.of(year, month, day)).isWorkday
    }
}

/**
 * Četvorobrigada: svih 19 listića livnice (12/2024–08/2026) ima rad u režiji + naknade +
 * izostanke tačno jednako fondu, iako raspored smena daje manje ili više sati. Firma plaća
 * pun fond, pa se sati koji fali do fonda posle rada, naknada i upisanog manjka plaćaju kao 01.
 */
internal fun fourBrigadeFundTopUpMinutes(
    records: List<DayRecord>,
    year: Int,
    month: Int,
    workedMinutes: Int,
    deficitMinutes: Int,
    defaultHours: Int
): Int {
    val fundMinutes = calculateMonthlyFundHours(year, month, defaultHours) * MINUTES_PER_HOUR
    val filledMinutes = workedMinutes + deficitMinutes + paidAbsenceFundMinutes(records, year, month, defaultHours)
    return (fundMinutes - filledMinutes).coerceAtLeast(0)
}

// Zakonski maksimum ukupnog dnevnog rada (Zakon o radu: redovan + prekovremeni
// ne duže od 12h dnevno). Duži dan ne može biti odrada, pa je ceo višak prekovremeni.
private const val MAX_LEGAL_DAILY_WORK_MINUTES = 12 * MINUTES_PER_HOUR

/**
 * Prekovremeni po danima za trobrigadu, sa automatskom odradom kao na listiću:
 * višak preko 8h radnim danom prvo odrađuje CELE radne dane koje u tom mesecu
 * nisi radio (neradni radni dan, bez praznika i bez „iskoristi sate") — plaća se
 * kao redovan rad sa noćnim dodatkom — a prekovremeni je samo ostatak.
 * - Listić 06/2026: neradni 08, 10. i 17.06. (24h); +2h 03.06. i +3h 25.06. su
 *   režija, a 01.06. (8h + 6h = 14h, preko 12h) je 6h prekovremenog.
 * - Listić 07/2026: nema celog neradnog dana (31.07. je samo kraći dan), pa je
 *   +3h od 01.07. prekovremeno uz 3h izostanaka.
 * Sati „Za sate" se ne plaćaju (idu u saldo), pa ovde ni ne ulaze. Vikend rad
 * ostaje automatski prekovremeni, a rad na praznik nikad nije prekovremeni.
 */
internal fun threeBrigadeOvertimeMinutesByDay(
    sorted: List<DayRecord>,
    defaultHours: Int
): Map<DayKey, Int> = threeBrigadeOvertimeAllocation(sorted, defaultHours).overtimeByDay

private data class ThreeBrigadeOvertimeAllocation(
    val overtimeByDay: Map<DayKey, Int>,
    val madeUpShortageMinutes: Int
)

private fun threeBrigadeOvertimeAllocation(
    sorted: List<DayRecord>,
    defaultHours: Int
): ThreeBrigadeOvertimeAllocation {
    var missedDayMinutesRemaining = missedWholeWorkdayMinutes(sorted)
    var madeUpShortageMinutes = 0
    val defaultMinutes = defaultHours * MINUTES_PER_HOUR
    val overtimeByDay = mutableMapOf<DayKey, Int>()
    for (record in sorted) {
        if (record.workedOnHoliday) continue
        if (record.weekday.isWeekend) continue
        val recordPaidMinutes = paidWorkMinuteSegments(record).sumOf { (_, minutes) -> minutes }
        val extraMinutes = recordPaidMinutes - defaultMinutes
        if (extraMinutes <= 0) continue
        val madeUpMinutes = if (record.totalWorkMinutes <= MAX_LEGAL_DAILY_WORK_MINUTES) {
            min(extraMinutes, missedDayMinutesRemaining)
        } else {
            0
        }
        missedDayMinutesRemaining -= madeUpMinutes
        madeUpShortageMinutes += madeUpMinutes
        val overtimeMinutes = extraMinutes - madeUpMinutes
        if (overtimeMinutes > 0) {
            overtimeByDay[record.key] = overtimeMinutes
        }
    }
    return ThreeBrigadeOvertimeAllocation(overtimeByDay, madeUpShortageMinutes)
}

/** Manjak celih neradnih radnih dana (odsustvo bez praznika i bez „iskoristi sate"). */
private fun missedWholeWorkdayMinutes(records: List<DayRecord>): Int {
    return records
        .filter { record -> record.shift == Shift.OFF }
        .sumOf { record ->
            (record.hoursDeficitMinutesFor(BrigadeType.TROBRIGADA) - record.compHoursUsed * MINUTES_PER_HOUR)
                .coerceAtLeast(0)
        }
}

/** Manjak meseca koji ne pokriva "iskoristi sate" (i koji zato mogu da upiju viškovi). */
private fun shortageMinutesNotCoveredByCompUse(records: List<DayRecord>, brigadeType: BrigadeType): Int {
    // Dan pokriven "iskoristi sate" sa balansa je slobodan dan, ne izostanak,
    // pa njegov manjak ne upija viškove.
    return records.sumOf { record ->
        (record.hoursDeficitMinutesFor(brigadeType) - record.compHoursUsed * MINUTES_PER_HOUR)
            .coerceAtLeast(0)
    }
}

/**
 * Manjak meseca koji su već pokrili dodatni sati plaćeni kao redovan rad:
 * automatska odrada kod trobrigade, mesečni fond kod četvorobrigade (dan od 10h
 * i dan od 6h daju 16h bez prekovremenog). Ti minuti su plaćeni kroz rad, pa ne
 * smeju ponovo da se plate iz salda sati niti da ga umanje.
 */
internal fun shortageMinutesCoveredByExtraWork(
    records: List<DayRecord>,
    year: Int,
    month: Int,
    brigadeType: BrigadeType,
    defaultHours: Int
): Int {
    val sorted = records.sortedWith(compareBy<DayRecord> { it.year }.thenBy { it.month }.thenBy { it.day })
    if (brigadeType == BrigadeType.TROBRIGADA) {
        return threeBrigadeOvertimeAllocation(sorted, defaultHours).madeUpShortageMinutes
    }
    val defaultMinutes = defaultHours * MINUTES_PER_HOUR
    val extraMinutes = sorted.filterNot { it.workedOnHoliday }.sumOf { record ->
        (paidWorkMinuteSegments(record).sumOf { (_, minutes) -> minutes } - defaultMinutes)
            .coerceAtLeast(0)
    }
    val overtimeMinutes = fourBrigadeOvertimeMinutesByDay(sorted, year, month, defaultHours).values.sum()
    return min(
        shortageMinutesNotCoveredByCompUse(sorted, brigadeType),
        (extraMinutes - overtimeMinutes).coerceAtLeast(0)
    )
}

/** Minuti naknada koji na listiću pune mesečni fond (norma = fond − naknade). */
private fun paidAbsenceFundMinutes(
    records: List<DayRecord>,
    year: Int,
    month: Int,
    defaultHours: Int
): Int {
    var minutes = 0
    for (record in records) {
        if (!record.weekday.isWorkday) continue
        minutes += when {
            record.shift == Shift.SICK || record.shift == Shift.VACATION ->
                record.workHours * MINUTES_PER_HOUR
            record.shift == Shift.OFF && record.isHoliday -> defaultHours * MINUTES_PER_HOUR
            else -> 0
        }
    }
    if (records.isNotEmpty()) {
        minutes += unrecordedHolidayFundMinutes(year, month, records.map { it.day }.toSet(), defaultHours)
    }
    return minutes
}

private fun unrecordedHolidayFundMinutes(
    year: Int,
    month: Int,
    recordedDays: Set<Int>,
    defaultHours: Int
): Int {
    if (year <= 0 || month !in 1..12) return 0
    val daysInMonth = YearMonth.of(year, month).lengthOfMonth()
    var minutes = 0
    for (day in 1..daysInMonth) {
        if (day in recordedDays) continue
        if (!isSerbianHoliday(year, month, day)) continue
        if (!Weekday.from(LocalDate.of(year, month, day)).isWorkday) continue
        minutes += defaultHours * MINUTES_PER_HOUR
    }
    return minutes
}
