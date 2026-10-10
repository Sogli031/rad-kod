package com.rad.shifttracker.obracun

import java.time.LocalDate
import java.time.YearMonth
import kotlin.math.min
import com.rad.shifttracker.smene.BrigadeType
import com.rad.shifttracker.smene.DayKey
import com.rad.shifttracker.smene.DayRecord
import com.rad.shifttracker.smene.Shift
import com.rad.shifttracker.smene.Weekday
import com.rad.shifttracker.smene.isSerbianHoliday

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

/**
 * Prekovremeni po danima za trobrigadu: ceo višak preko 8h radnim danom označen „Plaćeno" je
 * prekovremeni, čak i kad u mesecu ima neradnih dana (korisnik 10.10.2026: sati se vraćaju
 * samo kad je izabrano „Za sate"). Ranije je višak automatski prvo pokrivao cele neradne dane
 * (odrada, po listiću 06/2026), ali 6 lista 2024–2026 ima ≥ 8h izostanaka uz plaćen prekovremeni,
 * pa to nije pravilo firme. Sati „Za sate" se ne plaćaju (idu u saldo), pa ovde ni ne ulaze.
 * Vikend rad ostaje automatski prekovremeni, a rad na praznik nikad nije prekovremeni.
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
    val defaultMinutes = defaultHours * MINUTES_PER_HOUR
    val overtimeByDay = mutableMapOf<DayKey, Int>()
    for (record in sorted) {
        if (record.workedOnHoliday) continue
        if (record.weekday.isWeekend) continue
        val recordPaidMinutes = paidWorkMinuteSegments(record).sumOf { (_, minutes) -> minutes }
        val extraMinutes = recordPaidMinutes - defaultMinutes
        if (extraMinutes > 0) {
            overtimeByDay[record.key] = extraMinutes
        }
    }
    // Bez automatske odrade: višak „Plaćeno" ne pokriva manjak, pa je pokriveno 0.
    return ThreeBrigadeOvertimeAllocation(overtimeByDay, madeUpShortageMinutes = 0)
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
            record.isPaidHolidayOff(BrigadeType.CETVOROBRIGADA) -> defaultHours * MINUTES_PER_HOUR
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
