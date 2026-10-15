package com.rad.shifttracker.ai

import java.time.LocalDate
import java.time.YearMonth
import kotlin.math.roundToLong
import org.json.JSONArray
import org.json.JSONObject
import com.rad.shifttracker.osnova.MONTH_NAMES
import com.rad.shifttracker.repo.ShiftRepository
import com.rad.shifttracker.repo.exportJson
import com.rad.shifttracker.repo.getMonthlyFinalPaymentBreakdown
import com.rad.shifttracker.repo.getMonthlyReceipt
import com.rad.shifttracker.repo.getSummary
import com.rad.shifttracker.repo.getVacationBalanceSummary
import com.rad.shifttracker.repo.importFromJson
import com.rad.shifttracker.smene.Shift

/**
 * Alati koje AI poziva. Svi iznosi i sati dolaze iz postojećih obračuna aplikacije, a AI ih samo
 * prepričava. Napolje idu samo brojevi i datumi (bez imena, JMBG-a i računa). „Šta ako“ računa nad
 * kopijom podataka, a izmene se samo predlažu ([proposal]) dok ih korisnik ne potvrdi.
 */
internal class AiTools(private val repo: ShiftRepository, private val today: LocalDate) {

    /** Poslednji predlog izmena u ovom koraku razgovora (null ako ga nema). */
    var proposal: AiProposal? = null
        private set

    fun declarations(): JSONArray = JSONArray()
        .put(
            function(
                "mesec",
                "Sati, smene po danima i plata za rad u jednom mesecu (plata za rad u mesecu M isplaćuje se u mesecu M+1).",
                monthParams()
            )
        )
        .put(function("godisnji", "Stanje godišnjeg odmora danas: koliko dana je ostalo (stari i ovogodišnji).", objectParams()))
        .put(
            function(
                "planer_godisnjeg",
                "Nalazi najbolje termine za N dana godišnjeg: spaja ga sa vikendima, praznicima i slobodnim danima iz " +
                    "rasporeda i za svaki termin računa koliko se menja neto plata. Ništa ne upisuje.",
                leavePlanParams()
            )
        )
        .put(
            function(
                "sta_ako",
                "Izračunava kako bi se plata i sati promenili sa datim izmenama, bez upisa. Za pitanja tipa „koliko ću dobiti ako…“.",
                changesParams(withSummary = false)
            )
        )
        .put(
            function(
                "predlozi_izmene",
                "Predlaže korisniku upis izmena u kalendar; korisnik ih potvrđuje dugmetom u aplikaciji. Ništa se ne upisuje bez potvrde.",
                changesParams(withSummary = true)
            )
        )

    /** Izvršava alat; greška u argumentima se vraća AI-ju kao „greska“ da je ispravi. */
    fun execute(name: String, args: JSONObject): JSONObject = try {
        when (name) {
            "mesec" -> month(args.getInt("godina"), args.getInt("mesec"))
            "godisnji" -> vacation()
            "planer_godisnjeg" -> planLeave(repo, args, today)
            "sta_ako" -> whatIf(parseAiChanges(args.optJSONArray("izmene"), today))
            "predlozi_izmene" -> propose(parseAiChanges(args.optJSONArray("izmene"), today), args.optString("opis"))
            else -> error("nepoznat alat '$name'")
        }
    } catch (e: IllegalArgumentException) {
        JSONObject().put("greska", e.message)
    } catch (e: org.json.JSONException) {
        JSONObject().put("greska", "neispravni argumenti: ${e.message}")
    } catch (e: IllegalStateException) {
        JSONObject().put("greska", e.message)
    }

    private fun month(year: Int, month: Int): JSONObject {
        require(month in 1..MONTHS_PER_YEAR) { "mesec mora biti 1–12" }
        require(year in today.year - 2..today.year + 2) { "godina je predaleko od današnje" }
        return monthJson(repo, year, month).put("dani", daysText(repo, year, month))
    }

    private fun vacation(): JSONObject {
        val summary = repo.getVacationBalanceSummary(today.year, today)
        return JSONObject()
            .put("dostupno_danas_dana", summary.availableNow)
            .put("ovogodisnji_ukupno", summary.currentYearTotal)
            .put("ovogodisnji_iskorisceno", summary.currentYearUsed)
            .put("ovogodisnji_preostalo", summary.currentYearRemaining)
            .put("stari_preostalo", summary.carryoverRemaining)
            .put("stari_istice", summary.carryoverExpiresOn.toString())
            .put("dostupno_od_jula", summary.availableFromJuly)
    }

    private fun whatIf(changes: List<AiChange>): JSONObject {
        val copy = ShiftRepository(repo.config, repo.clock)
        check(copy.importFromJson(repo.exportJson())) { "kopija podataka za proračun nije uspela" }
        val result = applyAiChanges(copy, changes)
        val months = affectedMonths(changes)
        val comparison = JSONArray()
        for (month in months) {
            comparison.put(
                JSONObject()
                    .put("pre", monthJson(repo, month.year, month.monthValue))
                    .put("posle", monthJson(copy, month.year, month.monthValue))
            )
        }
        return JSONObject()
            .put("upisano_dana_u_proracunu", result.writtenDays)
            .put("preskoceno", JSONArray(result.skipped))
            .put("meseci", comparison)
    }

    private fun propose(changes: List<AiChange>, summary: String): JSONObject {
        proposal = AiProposal(changes, summary)
        return JSONObject()
            .put("status", "prikazano korisniku na potvrdu; nije upisano")
            .put("izmene", JSONArray(changes.map(::describeAiChange)))
    }
}

/** Meseci u kojima izmene menjaju sate (i time platu tog meseca). */
private fun affectedMonths(changes: List<AiChange>): List<YearMonth> =
    changes.flatMap { change ->
        generateSequence(YearMonth.from(change.from)) { it.plusMonths(1) }
            .takeWhile { !it.isAfter(YearMonth.from(change.to)) }
            .toList()
    }.distinct().sorted().take(MAX_COMPARED_MONTHS)

/** Sati i plata za rad u mesecu, isto kao ispod kalendara (plata stiže sledećeg meseca). */
internal fun monthJson(repo: ShiftRepository, year: Int, month: Int): JSONObject {
    val summary = repo.getSummary(year, month)
    val payment = YearMonth.of(year, month).plusMonths(1)
    val receipt = repo.getMonthlyReceipt(payment.year, payment.monthValue)
    val final = repo.getMonthlyFinalPaymentBreakdown(payment.year, payment.monthValue)
    val pay = JSONObject()
        .put("isplacuje_se", monthName(payment.year, payment.monthValue))
        .put("dostupna", receipt.isSalaryAvailable)
    if (receipt.isSalaryAvailable) {
        val net = receipt.workSalaryPay
        val deductions = final.deductionsNet.coerceAtLeast(0.0)
        val total = (net - deductions).coerceAtLeast(0.0)
        pay.put("neto_plata_din", money(net))
            .put("obustave_din", money(deductions))
            .put("ukupno_za_isplatu_din", money(total))
            .put("akontacija_uplacena_din", money(final.advancePaymentNet))
        if (final.advancePaymentNet > 0.0) {
            pay.put("ostaje_za_isplatu_20_din", money((total - final.advancePaymentNet).coerceAtLeast(0.0)))
        }
    }
    return JSONObject()
        .put("mesec", monthName(year, month))
        .put("sati_rada", summary.workHours)
        .put("prekovremeno_sati", summary.overtimeHours)
        .put("nocni_sati", summary.nightHours)
        .put("nedelja_sati", summary.sundayHours)
        .put("bolovanje_sati", summary.sickHours)
        .put("godisnji_sati", summary.vacationHours)
        .put("radnih_dana", summary.workDays)
        .put("saldo_sati_na_kraju", summary.compTimeEnding)
        .put("plata", pay)
}

/** Raspored meseca kratko: „1 S1 8h; 2 S2 8h+S3 8h; 3 OFF; 4 BOL …“ (prazni dani se izostavljaju). */
private fun daysText(repo: ShiftRepository, year: Int, month: Int): String =
    repo.getMonthRecords(year, month).sortedBy { it.day }.joinToString("; ") { record ->
        buildString {
            append("${record.day} ${record.shift.displayName}")
            if (record.shift in Shift.workShifts()) append(" ${record.hours}h")
            record.shift2?.let { append("+${it.displayName} ${record.hours2}h") }
        }
    }

private fun monthName(year: Int, month: Int) = "${MONTH_NAMES[month]} $year"

private fun money(value: Double): Long = value.roundToLong()

private fun function(name: String, description: String, parameters: JSONObject) = JSONObject()
    .put("name", name)
    .put("description", description)
    .put("parameters", parameters)

private fun objectParams() = JSONObject().put("type", "object").put("properties", JSONObject())

private fun monthParams() = JSONObject()
    .put("type", "object")
    .put(
        "properties",
        JSONObject()
            .put("godina", JSONObject().put("type", "integer"))
            .put("mesec", JSONObject().put("type", "integer").put("description", "1–12"))
    )
    .put("required", JSONArray().put("godina").put("mesec"))

private fun leavePlanParams() = JSONObject()
    .put("type", "object")
    .put(
        "properties",
        JSONObject()
            .put("dana", JSONObject().put("type", "integer").put("description", "koliko dana godišnjeg želi da potroši"))
            .put("od", JSONObject().put("type", "string").put("description", "početak perioda GGGG-MM-DD, podrazumevano sutra"))
            .put("do", JSONObject().put("type", "string").put("description", "kraj perioda GGGG-MM-DD, podrazumevano godinu dana od početka"))
            .put(
                "cilj",
                JSONObject().put("type", "string").put("enum", JSONArray(listOf("spajanje", "plata")))
                    .put("description", "spajanje = najviše uzastopnih slobodnih dana (podrazumevano), plata = najmanji gubitak na plati")
            )
            .put("broj_predloga", JSONObject().put("type", "integer").put("description", "1–5, podrazumevano 3"))
    )
    .put("required", JSONArray().put("dana"))

private fun changesParams(withSummary: Boolean): JSONObject {
    val change = JSONObject()
        .put("type", "object")
        .put(
            "properties",
            JSONObject()
                .put(
                    "tip",
                    JSONObject().put("type", "string").put("enum", JSONArray(listOf("smena", "slobodan", "bolovanje", "godisnji")))
                )
                .put("od", JSONObject().put("type", "string").put("description", "GGGG-MM-DD"))
                .put("do", JSONObject().put("type", "string").put("description", "GGGG-MM-DD, za jedan dan isto kao 'od' ili izostavi"))
                .put("smena", JSONObject().put("type", "integer").put("description", "za tip 'smena': 1, 2 ili 3"))
                .put("sati", JSONObject().put("type", "integer").put("description", "sati u smeni, podrazumevano 8; više od 8 je prekovremeno"))
                .put("druga_smena", JSONObject().put("type", "integer").put("description", "druga smena istog dana (prekovremeno): 1, 2 ili 3"))
                .put("sati_druge", JSONObject().put("type", "integer").put("description", "sati druge smene, podrazumevano 8"))
        )
        .put("required", JSONArray().put("tip").put("od"))
    val properties = JSONObject().put("izmene", JSONObject().put("type", "array").put("items", change))
    val required = JSONArray().put("izmene")
    if (withSummary) {
        properties.put("opis", JSONObject().put("type", "string").put("description", "kratko, šta se upisuje"))
        required.put("opis")
    }
    return JSONObject().put("type", "object").put("properties", properties).put("required", required)
}

private const val MONTHS_PER_YEAR = 12
private const val MAX_COMPARED_MONTHS = 4
