package com.rad.shifttracker.ai

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.TextStyle
import java.time.temporal.ChronoUnit
import java.util.Locale
import kotlin.math.roundToLong
import org.json.JSONArray
import org.json.JSONObject
import com.rad.shifttracker.godisnji.isVacationWorkday
import com.rad.shifttracker.repo.ShiftRepository
import com.rad.shifttracker.repo.applyAbsenceRange
import com.rad.shifttracker.repo.brigadeTypeValue
import com.rad.shifttracker.repo.exportJson
import com.rad.shifttracker.repo.getMonthlyReceipt
import com.rad.shifttracker.repo.importFromJson
import com.rad.shifttracker.smene.BrigadeType
import com.rad.shifttracker.smene.DayRecordSource
import com.rad.shifttracker.smene.Shift
import com.rad.shifttracker.smene.ShiftScheduler
import com.rad.shifttracker.smene.getHolidayName
import com.rad.shifttracker.smene.isSerbianHoliday

/** Šta je korisniku važnije kad bira termin godišnjeg. */
internal enum class LeaveGoal { MOST_DAYS, LEAST_LOSS }

/**
 * Jedan termin godišnjeg: [from]–[to] je opseg koji se upisuje kao godišnji (troši [vacationDays]
 * dana), a [freeFrom]–[freeTo] su svi uzastopni slobodni dani zajedno sa vikendima, praznicima i
 * slobodnim danima iz rasporeda oko njega. [netPayChange] je promena neto plate (minus je gubitak),
 * null kad plata tih meseci ne može da se izračuna.
 */
internal data class LeaveOption(
    val from: LocalDate,
    val to: LocalDate,
    val vacationDays: Int,
    val freeFrom: LocalDate,
    val freeTo: LocalDate,
    val holidays: List<String>,
    val netPayChange: Double? = null
) {
    val freeDays: Int get() = ChronoUnit.DAYS.between(freeFrom, freeTo).toInt() + 1
}

/**
 * Traži najbolje termine za [days] dana godišnjeg u periodu: svaki termin je jedan neprekinut
 * godišnji koji se spaja sa vikendima, praznicima i slobodnim danima iz rasporeda. Godišnji se
 * računa po istim pravilima kao „Do kog dana“ (pon–pet, bez praznika i bolovanja), a gubitak na plati
 * aplikacija računa nad kopijom podataka, kao „šta ako“.
 */
internal class LeavePlanner(private val repo: ShiftRepository) {

    private val fourBrigade = repo.brigadeTypeValue == BrigadeType.CETVOROBRIGADA

    fun plan(days: Int, period: ClosedRange<LocalDate>, goal: LeaveGoal, limit: Int): List<LeaveOption> {
        val candidates = candidates(days, period)
        if (candidates.isEmpty()) return emptyList()
        val byDays = candidates.sortedWith(compareByDescending<LeaveOption> { it.freeDays }.thenBy { it.from })
        val byPremiums = candidates.sortedWith(compareBy<LeaveOption> { premiumDays(it) }.thenByDescending { it.freeDays })
        val pool = (pickAllowed(byDays, PRICED_POOL) + pickAllowed(byPremiums, PRICED_POOL)).distinctBy { it.from }
        val priced = PayComparison(repo).price(pool)
        val sorted = when (goal) {
            LeaveGoal.MOST_DAYS -> priced.sortedWith(
                compareByDescending<LeaveOption> { it.freeDays }.thenBy { lossOrMax(it) }.thenBy { it.from }
            )
            LeaveGoal.LEAST_LOSS -> priced.sortedWith(
                compareBy<LeaveOption> { lossOrMax(it) }.thenByDescending { it.freeDays }.thenBy { it.from }
            )
        }
        return withoutOverlap(sorted, limit)
    }

    /** Svaki dan od kog može da počne godišnji od [days] dana, sa slobodnim danima oko njega. */
    private fun candidates(days: Int, period: ClosedRange<LocalDate>): List<LeaveOption> {
        val result = mutableListOf<LeaveOption>()
        var start = period.start
        while (!start.isAfter(period.endInclusive)) {
            if (isChargeable(start)) {
                rangeFrom(start, days, period.endInclusive)?.let { result += it }
            }
            start = start.plusDays(1)
        }
        return result
    }

    /** Godišnji od [start] dok se ne potroši [days] dana; null ako izlazi iz perioda ili ga prekida radni dan. */
    private fun rangeFrom(start: LocalDate, days: Int, last: LocalDate): LeaveOption? {
        var counted = 0
        var date = start
        while (true) {
            if (date.isAfter(last)) return null
            when {
                isChargeable(date) -> counted += 1
                !isFreeInsideAbsence(date) -> return null
            }
            if (counted == days) break
            date = date.plusDays(1)
        }
        var freeFrom = start
        while (ChronoUnit.DAYS.between(freeFrom, start) < MAX_EXTENSION_DAYS && isFree(freeFrom.minusDays(1))) {
            freeFrom = freeFrom.minusDays(1)
        }
        var freeTo = date
        while (ChronoUnit.DAYS.between(date, freeTo) < MAX_EXTENSION_DAYS && isFree(freeTo.plusDays(1))) {
            freeTo = freeTo.plusDays(1)
        }
        val holidays = generateSequence(freeFrom) { it.plusDays(1) }
            .takeWhile { !it.isAfter(freeTo) }
            .filter(::isHoliday)
            .mapNotNull { getHolidayName(it.year, it.monthValue, it.dayOfMonth) }
            .distinct()
            .toList()
        return LeaveOption(start, date, days, freeFrom, freeTo, holidays)
    }

    /** Dan koji troši godišnji: radni dan (pon–pet) koji nije praznik, bolovanje ni već upisan godišnji. */
    private fun isChargeable(date: LocalDate): Boolean {
        if (!isVacationWorkday(date) || isHoliday(date)) return false
        val shift = record(date)?.shift
        return shift != Shift.SICK && shift != Shift.VACATION
    }

    /** Slobodan i bez godišnjeg: slobodan dan ili godišnji iz rasporeda, a neupisan vikend ili praznik. */
    private fun isFree(date: LocalDate): Boolean {
        val record = record(date) ?: return !isVacationWorkday(date) || isHoliday(date)
        return record.shift == Shift.OFF || record.shift == Shift.VACATION
    }

    /** Unutar godišnjeg se u četvorobrigadi oslobađaju i smene vikendom (kao u Bol/GO). */
    private fun isFreeInsideAbsence(date: LocalDate): Boolean {
        if (isFree(date)) return true
        val record = record(date) ?: return false
        return fourBrigade && date.dayOfWeek.value > DayOfWeek.FRIDAY.value && record.source == DayRecordSource.AUTO_FILL
    }

    private fun isHoliday(date: LocalDate): Boolean =
        record(date)?.holidayOverride ?: isSerbianHoliday(date.year, date.monthValue, date.dayOfMonth)

    private fun record(date: LocalDate) = repo.get(date.year, date.monthValue, date.dayOfMonth)

    /** Grubo: dani sa trećom smenom ili dve smene, jer tu godišnji najviše uzima (noćni rad, prekovremeno). */
    private fun premiumDays(option: LeaveOption): Int =
        generateSequence(option.from) { it.plusDays(1) }
            .takeWhile { !it.isAfter(option.to) }
            .count { date -> record(date)?.let { it.shift == Shift.THIRD || it.shift2 != null } == true }

    /** Prvih [count] termina za koje ima dovoljno godišnjeg. */
    private fun pickAllowed(sorted: List<LeaveOption>, count: Int): List<LeaveOption> {
        val picked = mutableListOf<LeaveOption>()
        for (option in withoutOverlap(sorted, Int.MAX_VALUE)) {
            if (picked.size == count) break
            if (repo.validateVacationRequest(chargedDates(option)).isAllowed) picked += option
        }
        return picked
    }

    private fun chargedDates(option: LeaveOption): List<LocalDate> =
        generateSequence(option.from) { it.plusDays(1) }
            .takeWhile { !it.isAfter(option.to) }
            .filter(::isChargeable)
            .toList()
}

/** Bolji termini prvi, bez onih čiji se slobodni dani preklapaju sa već izabranim. */
private fun withoutOverlap(sorted: List<LeaveOption>, limit: Int): List<LeaveOption> {
    val chosen = mutableListOf<LeaveOption>()
    for (option in sorted) {
        if (chosen.size == limit) break
        val overlaps = chosen.any { !option.freeFrom.isAfter(it.freeTo) && !it.freeFrom.isAfter(option.freeTo) }
        if (!overlaps) chosen += option
    }
    return chosen
}

private fun lossOrMax(option: LeaveOption): Double = option.netPayChange?.let { -it } ?: Double.MAX_VALUE

/** Promena neto plate po terminu: godišnji se upisuje u kopiju podataka i plata se poredi sa sadašnjom. */
private class PayComparison(private val repo: ShiftRepository) {
    private val json = repo.exportJson()
    private val baseline = mutableMapOf<YearMonth, Double?>()

    fun price(options: List<LeaveOption>): List<LeaveOption> = options.map { option ->
        option.copy(netPayChange = netPayChange(option))
    }

    private fun netPayChange(option: LeaveOption): Double? {
        val copy = ShiftRepository(repo.config, repo.clock)
        if (!copy.importFromJson(json)) return null
        if (ShiftScheduler().applyAbsenceRange(copy, Shift.VACATION, option.from, option.to) == 0) return null
        var change = 0.0
        for (month in workMonths(option)) {
            val before = baseline.getOrPut(month) { workSalary(repo, month) } ?: return null
            val after = workSalary(copy, month) ?: return null
            change += after - before
        }
        return change
    }
}

private fun workMonths(option: LeaveOption): List<YearMonth> =
    generateSequence(YearMonth.from(option.from)) { it.plusMonths(1) }
        .takeWhile { !it.isAfter(YearMonth.from(option.to)) }
        .toList()

/** Neto plata za rad u mesecu [month] (isplaćuje se sledećeg meseca), ili null ako se ne može izračunati. */
private fun workSalary(repo: ShiftRepository, month: YearMonth): Double? {
    val payment = month.plusMonths(1)
    val receipt = repo.getMonthlyReceipt(payment.year, payment.monthValue)
    return if (receipt.isSalaryAvailable) receipt.workSalaryPay else null
}

/** Argumenti alata „planer_godisnjeg“ → predlozi za AI. */
internal fun planLeave(repo: ShiftRepository, args: JSONObject, today: LocalDate): JSONObject {
    val days = args.optInt("dana", 0)
    require(days in 1..MAX_PLANNED_DAYS) { "'dana' mora biti od 1 do $MAX_PLANNED_DAYS" }
    val from = args.optString("od").takeIf { it.isNotBlank() }?.let { parsePlanDate(it, "od") } ?: today.plusDays(1)
    val to = args.optString("do").takeIf { it.isNotBlank() }?.let { parsePlanDate(it, "do") } ?: from.plusDays(DEFAULT_PERIOD_DAYS)
    require(!to.isBefore(from)) { "'do' je pre 'od'" }
    require(!from.isBefore(today)) { "period ne može da počne pre današnjeg dana" }
    require(ChronoUnit.DAYS.between(from, to) <= MAX_PERIOD_DAYS) { "period je duži od $MAX_PERIOD_DAYS dana" }
    val goal = if (args.optString("cilj") == "plata") LeaveGoal.LEAST_LOSS else LeaveGoal.MOST_DAYS
    val limit = args.optInt("broj_predloga", DEFAULT_SUGGESTIONS).coerceIn(1, MAX_SUGGESTIONS)

    val options = LeavePlanner(repo).plan(days, from..to, goal, limit)
    val result = JSONObject()
        .put("dana_godisnjeg", days)
        .put("period", "$from – $to")
        .put("cilj", if (goal == LeaveGoal.LEAST_LOSS) "najmanji gubitak na plati" else "najviše uzastopnih slobodnih dana")
    if (options.isEmpty()) {
        return result.put("predlozi", JSONArray())
            .put("napomena", "nema termina: nema dovoljno godišnjeg u tom periodu ili ga prekidaju radni dani iz rasporeda")
    }
    return result.put("predlozi", JSONArray(options.map(::optionJson)))
}

private fun optionJson(option: LeaveOption): JSONObject {
    val json = JSONObject()
        .put("godisnji_od", dayText(option.from))
        .put("godisnji_do", dayText(option.to))
        .put("dana_godisnjeg", option.vacationDays)
        .put("slobodno_od", dayText(option.freeFrom))
        .put("slobodno_do", dayText(option.freeTo))
        .put("uzastopno_slobodnih_dana", option.freeDays)
        .put("na_posao", dayText(option.freeTo.plusDays(1)))
        .put("praznici", JSONArray(option.holidays))
    val change = option.netPayChange
    if (change == null) {
        json.put("promena_neto_plate", "ne može da se izračuna (nema podataka o plati za te mesece)")
    } else {
        json.put("promena_neto_plate_din", change.roundToLong())
    }
    return json
}

/** „2026-12-31 (četvrtak)“: datum za alat i dan u nedelji da AI ne mora da ga računa. */
private fun dayText(date: LocalDate): String =
    "$date (${date.dayOfWeek.getDisplayName(TextStyle.FULL, Locale.forLanguageTag("sr-Latn"))})"

private fun parsePlanDate(text: String, field: String): LocalDate =
    runCatching { LocalDate.parse(text) }.getOrElse { throw IllegalArgumentException("'$field' mora biti datum GGGG-MM-DD", it) }

/** Koliko termina se ceni (kopija podataka po terminu), po svakom od dva redosleda. */
private const val PRICED_POOL = 8
private const val MAX_EXTENSION_DAYS = 31L
private const val MAX_PLANNED_DAYS = 40
private const val DEFAULT_PERIOD_DAYS = 365L
private const val MAX_PERIOD_DAYS = 400L
private const val DEFAULT_SUGGESTIONS = 3
private const val MAX_SUGGESTIONS = 5
