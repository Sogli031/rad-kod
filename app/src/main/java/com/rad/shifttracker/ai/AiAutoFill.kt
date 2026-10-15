package com.rad.shifttracker.ai

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.format.DateTimeParseException
import java.time.temporal.TemporalAdjusters
import org.json.JSONArray
import org.json.JSONObject
import com.rad.shifttracker.smene.Shift
import com.rad.shifttracker.smene.ShiftFillMode
import com.rad.shifttracker.smene.ShiftFillSettings
import com.rad.shifttracker.smene.ShiftScheduler

/**
 * Auto-popuna iz AI razgovora: režim i jedan poznat dan („prva/druga, 19.10. sam druga“) daju ista
 * podešavanja kao prozor „Popuni“. Upisuje se tek na „Upiši“, isto kao „Popuni“: raspored od danas
 * nadalje se presnimava, a prošli dani ostaju.
 */
internal fun parseAiAutoFill(args: JSONObject, today: LocalDate, scheduler: ShiftScheduler = ShiftScheduler()): ShiftFillSettings {
    val mode = requireNotNull(FILL_MODES[args.optString("rezim")]) {
        "nepoznat 'rezim' (${args.optString("rezim")}); dozvoljeno: jedna_smena, prva_druga, trobrigada, cetvorobrigada"
    }
    if (mode == ShiftFillMode.ONE_SHIFT) return ShiftFillSettings(mode, today, Shift.FIRST)

    val date = args.optString("datum").takeIf { it.isNotBlank() }?.let(::parseFillDate)
        ?: throw IllegalArgumentException("za ovaj režim treba 'datum' kad se zna koja je smena")
    require(!date.isBefore(today.minusYears(1)) && !date.isAfter(today.plusYears(1))) { "'datum' je predaleko od današnjeg" }
    val shift = requireNotNull(FILL_SHIFTS[args.optInt("smena", -1)]) { "'smena' mora biti 1, 2, 3 ili 0 (slobodan)" }

    val settings = when (mode) {
        ShiftFillMode.FIRST_SECOND, ShiftFillMode.THREE_BRIGADE -> {
            require(date.dayOfWeek.value <= DayOfWeek.FRIDAY.value) { "za smene po sedmicama 'datum' mora biti radni dan (pon–pet)" }
            ShiftFillSettings(mode, date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)), shift)
        }
        else -> {
            val dayInBlock = args.optInt("dan_u_bloku", 1)
            require(dayInBlock in 1..2) { "'dan_u_bloku' mora biti 1 ili 2" }
            ShiftFillSettings(mode, date, shift, scheduler.getFourBrigadeCycleStartIndex(shift, dayInBlock - 1))
        }
    }
    require(scheduler.isValidFillSettings(settings)) { invalidShiftMessage(mode) }
    return settings
}

/** Kako popuna izgleda narednih dana (bez praznika), da AI proveri sa korisnikom pre upisa. */
internal fun autoFillPreview(settings: ShiftFillSettings, today: LocalDate, scheduler: ShiftScheduler = ShiftScheduler()): JSONArray =
    JSONArray(
        generateSequence(today) { it.plusDays(1) }
            .take(PREVIEW_DAYS)
            .map { date -> "$date ${scheduler.calculateShiftForDate(date, settings).displayName}" }
            .toList()
    )

/** Popuna rečima, za karticu potvrde (npr. „Popuna: Trobrigada, od 19.10. druga smena“). */
internal fun describeAiAutoFill(settings: ShiftFillSettings): String = buildString {
    append("Popuna rasporeda: ${settings.mode.label}")
    if (settings.mode != ShiftFillMode.ONE_SHIFT) {
        val shift = if (settings.selectedShift == Shift.OFF) "slobodan" else settings.selectedShift.fullName.lowercase()
        append(", ${settings.anchorDate.dayOfMonth}.${settings.anchorDate.monthValue}. $shift")
    }
}

private fun invalidShiftMessage(mode: ShiftFillMode): String = when (mode) {
    ShiftFillMode.FIRST_SECOND -> "za prvu/drugu 'smena' mora biti 1 ili 2"
    ShiftFillMode.THREE_BRIGADE -> "za trobrigadu 'smena' mora biti 1, 2 ili 3"
    else -> "neispravna smena za ovaj režim"
}

private fun parseFillDate(text: String): LocalDate = try {
    LocalDate.parse(text)
} catch (e: DateTimeParseException) {
    throw IllegalArgumentException("'datum' mora biti GGGG-MM-DD (dobijeno: '$text')", e)
}

internal fun autoFillParams(): JSONObject = JSONObject()
    .put("type", "object")
    .put(
        "properties",
        JSONObject()
            .put(
                "rezim",
                JSONObject().put("type", "string").put("enum", JSONArray(FILL_MODES.keys.toList()))
                    .put(
                        "description",
                        "jedna_smena = samo prva smena pon–pet; prva_druga = sedmično prva/druga; trobrigada = tri smene " +
                            "(sedmično treća → druga → prva); cetvorobrigada = četiri smene (po dva dana prva, druga, treća, slobodan)"
                    )
            )
            .put("datum", JSONObject().put("type", "string").put("description", "GGGG-MM-DD, dan za koji korisnik zna smenu"))
            .put("smena", JSONObject().put("type", "integer").put("description", "smena tog dana: 1, 2, 3; 0 = slobodan (samo četvorobrigada)"))
            .put(
                "dan_u_bloku",
                JSONObject().put("type", "integer").put("description", "četvorobrigada: 1 = prvi, 2 = drugi dan te smene; podrazumevano 1")
            )
            .put("opis", JSONObject().put("type", "string").put("description", "kratko, šta se popunjava"))
    )
    .put("required", JSONArray().put("rezim").put("opis"))

private val FILL_MODES = linkedMapOf(
    "jedna_smena" to ShiftFillMode.ONE_SHIFT,
    "prva_druga" to ShiftFillMode.FIRST_SECOND,
    "trobrigada" to ShiftFillMode.THREE_BRIGADE,
    "cetvorobrigada" to ShiftFillMode.FOUR_BRIGADE
)

private val FILL_SHIFTS = mapOf(0 to Shift.OFF, 1 to Shift.FIRST, 2 to Shift.SECOND, 3 to Shift.THIRD)

private const val PREVIEW_DAYS = 14
