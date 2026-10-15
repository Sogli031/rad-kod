package com.rad.shifttracker.ai

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.format.DateTimeParseException
import java.time.temporal.ChronoUnit
import org.json.JSONArray
import org.json.JSONObject
import com.rad.shifttracker.osnova.CONFIG
import com.rad.shifttracker.repo.ShiftRepository
import com.rad.shifttracker.repo.applyAbsenceRange
import com.rad.shifttracker.smene.Shift
import com.rad.shifttracker.smene.ShiftFillSettings
import com.rad.shifttracker.smene.ShiftScheduler
import com.rad.shifttracker.smene.isSerbianHoliday

/** Vrsta izmene koju AI predlaže. */
internal enum class AiChangeKind { SHIFT, OFF, SICK, VACATION }

/**
 * Jedna izmena rasporeda od [from] do [to]. Smena ima [shift] i [hours] (više od 8 je prekovremeno), a
 * po želji i drugu smenu istog dana ([shift2], [hours2]). Sa [workdaysOnly] smena preskače vikend,
 * praznike, bolovanje i godišnji (npr. „sledeće sedmice radim treću“). Bolovanje i godišnji se upisuju kao u prozoru
 * dana („Do kog dana“): samo radni dani, a godišnji bez praznika i samo ako ga ima dovoljno.
 */
internal data class AiChange(
    val kind: AiChangeKind,
    val from: LocalDate,
    val to: LocalDate,
    val shift: Shift? = null,
    val hours: Int = CONFIG.pay.defaultHours,
    val shift2: Shift? = null,
    val hours2: Int = 0,
    val workdaysOnly: Boolean = false
)

/** Izmene (ili popuna rasporeda, [fill]) koje čekaju potvrdu korisnika, sa AI-jevim kratkim opisom. */
internal data class AiProposal(val changes: List<AiChange>, val summary: String, val fill: ShiftFillSettings? = null)

/** Rezultat upisa potvrđenih izmena. */
internal data class AiApplyResult(val writtenDays: Int, val skipped: List<String>)

/** Izmene iz argumenata alata; pogrešan unos daje [IllegalArgumentException] sa porukom za AI. */
internal fun parseAiChanges(array: JSONArray?, today: LocalDate): List<AiChange> {
    require(array != null && array.length() > 0) { "nema nijedne izmene u listi 'izmene'" }
    require(array.length() <= MAX_CHANGES) { "najviše $MAX_CHANGES izmena odjednom" }
    return (0 until array.length()).map { index ->
        val item = array.optJSONObject(index) ?: throw IllegalArgumentException("izmena ${index + 1} nije objekat")
        runCatching { parseAiChange(item, today) }.getOrElse { e ->
            throw IllegalArgumentException("izmena ${index + 1}: ${e.message}", e)
        }
    }
}

private fun parseAiChange(item: JSONObject, today: LocalDate): AiChange {
    val kind = requireNotNull(CHANGE_KINDS[item.optString("tip")]) {
        "nepoznat 'tip' (${item.optString("tip")}); dozvoljeno: smena, slobodan, bolovanje, godisnji"
    }
    val from = parseDate(item.optString("od"), "od")
    val to = item.optString("do").takeIf { it.isNotBlank() }?.let { parseDate(it, "do") } ?: from
    require(!to.isBefore(from)) { "'do' je pre 'od'" }
    val maxDays = if (kind == AiChangeKind.SHIFT || kind == AiChangeKind.OFF) MAX_SHIFT_RANGE_DAYS else MAX_ABSENCE_RANGE_DAYS
    require(ChronoUnit.DAYS.between(from, to) < maxDays) { "opseg je duži od $maxDays dana" }
    require(!from.isBefore(today.minusYears(1)) && !to.isAfter(today.plusYears(2))) { "datum je predaleko od današnjeg" }
    if (kind != AiChangeKind.SHIFT) return AiChange(kind, from, to)
    val shift = requireNotNull(workShift(item.optInt("smena", 0))) { "'smena' mora biti 1, 2 ili 3" }
    val hours = item.optInt("sati", CONFIG.pay.defaultHours)
    require(hours in 1..MAX_SHIFT_HOURS) { "'sati' mora biti od 1 do $MAX_SHIFT_HOURS" }
    val shift2 = item.optInt("druga_smena", 0).takeIf { it != 0 }?.let {
        requireNotNull(workShift(it)) { "'druga_smena' mora biti 1, 2 ili 3" }
    }
    val hours2 = if (shift2 == null) 0 else item.optInt("sati_druge", CONFIG.pay.defaultHours)
    require(shift2 == null || hours2 in 1..MAX_SHIFT_HOURS) { "'sati_druge' mora biti od 1 do $MAX_SHIFT_HOURS" }
    require(shift2 == null || shift2 != shift) { "druga smena ne može biti ista kao prva" }
    require(hours + hours2 <= MAX_DAY_TOTAL_HOURS) { "ukupno više od $MAX_DAY_TOTAL_HOURS sati u danu" }
    return AiChange(kind, from, to, shift, hours, shift2, hours2, item.optBoolean("samo_radni_dani", false))
}

private fun parseDate(text: String, field: String): LocalDate = try {
    LocalDate.parse(text)
} catch (e: DateTimeParseException) {
    throw IllegalArgumentException("'$field' mora biti datum GGGG-MM-DD (dobijeno: '$text')", e)
}

private fun workShift(value: Int): Shift? = when (value) {
    1 -> Shift.FIRST
    2 -> Shift.SECOND
    3 -> Shift.THIRD
    else -> null
}

/**
 * Upisuje izmene u [repo] i vraća broj upisanih dana. Koristi se i za „šta ako“ (nad kopijom
 * podataka) i za potvrđene izmene (preko DataActions).
 */
internal fun applyAiChanges(repo: ShiftRepository, changes: List<AiChange>): AiApplyResult {
    val scheduler = ShiftScheduler()
    var written = 0
    val skipped = mutableListOf<String>()
    for (change in changes) {
        when (change.kind) {
            AiChangeKind.SICK, AiChangeKind.VACATION -> {
                val shift = if (change.kind == AiChangeKind.SICK) Shift.SICK else Shift.VACATION
                val days = scheduler.applyAbsenceRange(repo, shift, change.from, change.to)
                if (days == 0) skipped += "${describeAiChange(change)}: ${absenceSkipReason(change.kind)}"
                written += days
            }
            AiChangeKind.SHIFT, AiChangeKind.OFF -> {
                var date = change.from
                while (!date.isAfter(change.to)) {
                    if (change.workdaysOnly && !isPlainWorkday(repo, date)) {
                        date = date.plusDays(1)
                        continue
                    }
                    val shift = change.shift ?: Shift.OFF
                    repo.set(
                        year = date.year,
                        month = date.monthValue,
                        day = date.dayOfMonth,
                        shift = shift,
                        hours = if (shift == Shift.OFF) 0 else change.hours,
                        shift2 = change.shift2,
                        hours2 = change.hours2
                    )
                    written += 1
                    date = date.plusDays(1)
                }
            }
        }
    }
    return AiApplyResult(written, skipped)
}

/** Radni dan za „samo radni dani“: pon–pet, nije praznik, bolovanje ni godišnji. */
private fun isPlainWorkday(repo: ShiftRepository, date: LocalDate): Boolean {
    if (date.dayOfWeek.value > DayOfWeek.FRIDAY.value) return false
    val record = repo.get(date.year, date.monthValue, date.dayOfMonth)
    if (record?.holidayOverride ?: isSerbianHoliday(date.year, date.monthValue, date.dayOfMonth)) return false
    return record?.shift != Shift.SICK && record?.shift != Shift.VACATION
}

private fun absenceSkipReason(kind: AiChangeKind): String =
    if (kind == AiChangeKind.VACATION) "nema dovoljno godišnjeg ili nema radnih dana u opsegu" else "nema radnih dana u opsegu"

/** Izmena rečima, za karticu potvrde (npr. „Bolovanje 3.11.–10.11.2026.“). */
internal fun describeAiChange(change: AiChange): String {
    val range = if (change.from == change.to) dateText(change.from) else "${shortDateText(change.from)}–${dateText(change.to)}"
    return when (change.kind) {
        AiChangeKind.SICK -> "Bolovanje $range"
        AiChangeKind.VACATION -> "Godišnji $range"
        AiChangeKind.OFF -> "Slobodan $range"
        AiChangeKind.SHIFT -> buildString {
            append("${change.shift?.fullName} ${change.hours} h")
            change.shift2?.let { append(" + ${it.fullName.lowercase()} ${change.hours2} h") }
            append(", $range")
            if (change.workdaysOnly) append(" (radni dani)")
        }
    }
}

private fun dateText(date: LocalDate) = "${date.dayOfMonth}.${date.monthValue}.${date.year}."
private fun shortDateText(date: LocalDate) = "${date.dayOfMonth}.${date.monthValue}."

private val CHANGE_KINDS = mapOf(
    "smena" to AiChangeKind.SHIFT,
    "slobodan" to AiChangeKind.OFF,
    "bolovanje" to AiChangeKind.SICK,
    "godisnji" to AiChangeKind.VACATION
)

private const val MAX_CHANGES = 20
private const val MAX_SHIFT_RANGE_DAYS = 62L
private const val MAX_ABSENCE_RANGE_DAYS = 365L
private const val MAX_SHIFT_HOURS = 16
private const val MAX_DAY_TOTAL_HOURS = 24
