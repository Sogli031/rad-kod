package com.rad.shifttracker

import android.content.Context
import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.time.LocalDate
import java.time.Month
import java.time.YearMonth
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * Prvo pokretanje: posle „Popuni smene“ aplikacija traži Gmail sa listićima i odmah iz njega upisuje
 * sve što može — sa konačnih listića koeficijent, vrednost sata, doprinose, porez, olakšicu, minuli
 * rad i osnovicu (isto kao „Lista“), a iz posebnih obračuna prevoz po danu i akontaciju.
 */
internal const val GMAIL_ONBOARDING_TITLE = "Liste iz Gmail-a"
internal const val GMAIL_ONBOARDING_TEXT =
    "Poveži Gmail na koji stižu platne liste."
internal const val GMAIL_ONBOARDING_SKIP_LABEL = "Preskoči"
internal const val GMAIL_ONBOARDING_NEXT_LABEL = "Dalje"
internal const val GMAIL_ONBOARDING_IMPORTING_TEXT = "Čitam liste iz Gmail-a..."

/**
 * Meseci rada za koje onboarding traži listiće: od januara prošle godine do prošlog meseca (konačni
 * za M stiže ~20. u M+1). Cela prošla godina daje iskorišćen godišnji i prenos u ovu. Pravo se meri
 * u periodu 1.7.–30.6.: dok lista za jun ove godine ne stigne (do avgusta), treba i period od jula
 * pretprošle godine.
 */
internal fun onboardingPayslipMonths(today: LocalDate): List<MonthKey> {
    val last = YearMonth.from(today).minusMonths(1)
    val start = if (today.monthValue >= Month.AUGUST.value) YearMonth.of(today.year - 1, 1) else YearMonth.of(today.year - 2, 7)
    return generateSequence(start) { it.plusMonths(1) }
        .takeWhile { !it.isAfter(last) }
        .map { MonthKey(it.year, it.monthValue) }
        .toList()
}

/**
 * Godišnji posle onboardinga, samo ako još nije podešen: pravo po KU za prošlu i ovu godinu (staž sa
 * listića). Prošla godina je praćena, pa se prenos i iskorišćeni dani (šifra 10 na listićima)
 * računaju sami. Prenos u prošlu godinu nije poznat (0). Bez listića za januar prošle godine
 * podešava se samo ova godina. Vraća true ako je upisano.
 */
/** Vraća objašnjenje kako je pravo izračunato, ili null ako godišnji nije upisan (već podešen). */
internal fun applyOnboardingVacation(repo: ShiftRepository, today: LocalDate): String? {
    val year = today.year
    if (repo.getVacationYearBalance(year) != null || repo.getVacationYearBalance(year - 1) != null) return null
    val years = if (repo.getSavedPayslip(year - 1, 1) != null) listOf(year - 1, year) else listOf(year)
    val vacation = onboardingVacation(repo, year)
    for (y in years) {
        repo.setVacationYearBalance(
            VacationYearBalance(
                year = y,
                trackingStartDate = LocalDate.of(y, 1, 1),
                carryoverDaysInitial = 0.0,
                currentYearDaysTotal = vacation.totals.getValue(y).toDouble(),
                currentYearDaysUsedBeforeAvailability = 0.0,
                currentYearAvailableFrom = currentYearVacationAvailableFrom(y),
                manualBaseline = false
            )
        )
    }
    return vacation.explanation
}

/** Pravo po godini i objašnjenje za korisnika kako je dobijeno ([fromPayslips] = iz iskorišćenih dana, ne po KU). */
internal data class OnboardingVacation(
    val totals: Map<Int, Int>,
    val explanation: String,
    val fromPayslips: Boolean = false
)

/**
 * Provera: godišnji je već podešen, pa se ne dira; ako listići daju drugo pravo za ovu godinu,
 * vraća napomenu (pravo može sadržati dodatke kojih nema na listiću, pa odluka ostaje korisniku).
 */
internal fun vacationRecheckNote(repo: ShiftRepository, today: LocalDate): String? {
    val current = repo.getVacationYearBalance(today.year) ?: return null
    val vacation = onboardingVacation(repo, today.year)
    val fromSlips = vacation.totals[today.year] ?: return null
    if (!vacation.fromPayslips || Math.round(current.currentYearDaysTotal).toInt() == fromSlips) return null
    return "Godišnji: po listama pravo $fromSlips dana, upisano ${decimalText(current.currentYearDaysTotal)}. " +
        "Nije menjano — promeni u „Godišnji“ ako treba."
}

internal fun onboardingVacationTotals(repo: ShiftRepository, year: Int): Map<Int, Int> = onboardingVacation(repo, year).totals

/** Najviše dana koje KU daje preko onoga što aplikacija zna sama (rizik +1, sprema +1, socijalni +2). */
private const val MAX_UNSEEN_ANNUAL_LEAVE_DAYS = 4

/** Godišnji iskorišćen u jednom periodu (šifra 10 na listićima) i procena prava za [baseYear]. */
private class VacationUsage(
    val baseYear: Int,
    val months: List<MonthKey>,
    val periodText: String,
    repo: ShiftRepository
) {
    val ku = repo.getAnnualLeaveEntitlement(baseYear).breakdown.totalDays
    private val slips = months.mapNotNull { repo.getSavedPayslip(it.year, it.month) }
    val missing = months.filter { repo.getSavedPayslip(it.year, it.month) == null }
    val used = slips.sumOf(::payslipVacationDays)
    private val usedRounded = Math.round(used).toInt()
    val hasSlips = slips.isNotEmpty()

    // Mesec bez listića može samo da doda dane, pa je zbir ostalih donja granica prava.
    private val complete = missing.isEmpty()
    val candidate = if (complete) usedRounded else maxOf(usedRounded, ku)
    val plausible = hasSlips && candidate in ku..ku + MAX_UNSEEN_ANNUAL_LEAVE_DAYS
    val fromUsage = plausible && (complete || usedRounded > ku)
    val usedText get() = "iskorišćeno $periodText: ${decimalText(used)} dana"
    val coverage
        get() = if (complete) "svih 12 listi" else "${months.size - missing.size}/12 listi, fali ${missing.joinToString { monthYearText(it) }}"
}

private fun monthsFrom(start: MonthKey): List<MonthKey> =
    generateSequence(YearMonth.of(start.year, start.month)) { it.plusMonths(1) }
        .take(12)
        .map { MonthKey(it.year, it.monthValue) }
        .toList()

/**
 * Pravo za prošlu i ovu godinu. Odmor se ne ostavlja da propadne, pa je pravo = dani godišnjeg
 * iskorišćeni za godinu dana — tako se vide i dodaci kojih nema na listiću (stručna sprema, rizik,
 * socijalni uslovi). Gleda se samo period 1.7.–30.6. (odluka korisnika): 30.6. propada prenos iz
 * prethodne godine, pa od 1.7. ostaju samo dani tekuće godine. Kad neki mesec nema listić, zbir ostalih
 * je donja granica: važi veće od njega i KU. Broj van KU..KU + [MAX_UNSEEN_ANNUAL_LEAVE_DAYS] nije
 * verovatan, pa tada važi računica po KU. Druga godina dobija razliku ako je staž prešao u viši razred.
 */
internal fun onboardingVacation(repo: ShiftRepository, year: Int): OnboardingVacation {
    val previous = year - 1
    val kuPrevious = repo.getAnnualLeaveEntitlement(previous).breakdown.totalDays
    val kuCurrent = repo.getAnnualLeaveEntitlement(year).breakdown.totalDays
    // Poslednji završen period 1.7.–30.6.: ima listu za jun ove godine ili kasnije (jun ume da fali u Gmail-u).
    val windowBase = if ((6..12).any { repo.getSavedPayslip(year, it) != null }) previous else previous - 1
    val window = VacationUsage(
        baseYear = windowBase,
        months = monthsFrom(MonthKey(windowBase, 7)),
        periodText = "od 1.7.$windowBase. do 30.6.${windowBase + 1}.",
        repo = repo
    )
    Log.i(SIDE_RUN_LOG_TAG, "godišnji: ${window.usedText} (${window.coverage}), KU $previous=$kuPrevious, KU $year=$kuCurrent")
    val totals = if (window.plausible) {
        mapOf(
            previous to window.candidate + (kuPrevious - window.ku),
            year to window.candidate + (kuCurrent - window.ku)
        )
    } else {
        mapOf(previous to kuPrevious, year to kuCurrent)
    }
    val explanation = when {
        !window.hasSlips -> "Pravo ${totals.getValue(year)} dana po KU čl. 29 — nema listi ${window.periodText}. " +
            "Ako imaš dodatak koji nije na listi (III stepen, rizik), uključi ga u „Godišnji“."
        window.fromUsage -> "Pravo ${totals.getValue(year)} dana izračunato preko Gmail-a: ${window.usedText} (${window.coverage}). " +
            "Može da pogreši ako je pre 1.7. uzet odmor unapred iz nove godine."
        else -> "Pravo ${totals.getValue(year)} dana po KU čl. 29 — iz Gmail-a nije moglo tačno " +
            "(${window.usedText}, ${window.coverage}). " +
            "Ako imaš dodatak koji nije na listi (III stepen, rizik), uključi ga u „Godišnji“."
    }
    return OnboardingVacation(totals, explanation, fromPayslips = window.fromUsage)
}

/** Šta je onboarding našao; pregled se pravi iz vrednosti u aplikaciji posle upisa. */
internal data class OnboardingImportResult(
    val payslips: List<ParsedPayslip>,
    val transportDaily: Double?,
    val advanceMonths: List<MonthKey>,
    val gmailFailed: Boolean,
    /** Gmail dozvola je istekla ili nalog više nije na telefonu (tada je i [gmailFailed]). */
    val gmailNoAccess: Boolean = false,
    /** Kako je izračunato pravo na godišnji (null ako godišnji nije upisan). */
    val vacationNote: String? = null,
    /** Šta je uvoz promenio: naziv, pre, posle. */
    val differences: List<SettingDifference> = emptyList()
)

internal data class SettingDifference(val label: String, val oldText: String, val newText: String)

/**
 * Listići kao da su učitani u „Listi“: globalna podešavanja sa najnovijeg, vrednost sata i
 * osnovica po mesecu, i svaki listić se čuva za svoj mesec. Vraća upisane izmene.
 */
internal fun applyOnboardingPayslips(repo: ShiftRepository, payslips: List<ParsedPayslip>): PayslipImportPlan? {
    if (payslips.isEmpty()) return null
    val built = buildPayslipImportPlan(repo, payslips, failures = emptyList(), includeGlobalSettings = true)
    // Važi samo najnovija osnovica; starije ne utiču ni na šta, pa se ne upisuju.
    val newestBase = built.payslips.lastOrNull { payslipPayBaseAnchor(it) != null }?.month
    val plan = built.copy(
        changes = built.changes.filter { it.kind != PayslipChangeKind.PAY_BASE || it.month == newestBase }
    )
    applyPayslipChanges(repo, plan.changes)
    plan.payslips.forEach { repo.savePayslip(it, withPayBase = it.month == newestBase) }
    return plan
}

/**
 * Akontacije za sve mesece isplate iz uvoza. Obračun akontacije („Ukupno uplaćeno“) je tačan iznos;
 * bez njega važi konačna lista meseca rada (kolona ISPLAĆENO bez regresa), pod mesecom posle njega.
 * Regres iz obračuna regresa mora biti upisan pre ovoga.
 */
internal fun advancesFromImport(
    repo: ShiftRepository,
    runs: List<PayslipParseResult.SideRun>,
    payslips: List<ParsedPayslip>
): Map<MonthKey, Double> {
    val fromPayslips = payslips.mapNotNull { slip ->
        val amount = repo.payslipAdvanceNet(slip)?.takeIf { it > 0.0 } ?: return@mapNotNull null
        val paymentMonth = YearMonth.of(slip.month.year, slip.month.month).plusMonths(1)
        MonthKey(paymentMonth.year, paymentMonth.monthValue) to amount
    }.toMap()
    return (fromPayslips + advanceTotalsByPaymentMonth(runs)).toSortedMap()
}

/**
 * Uvoz iz Gmail-a (onboarding i „Proveri po listićima“): listići od januara prošle godine i
 * posebni obračuni; upisuje sve što se razlikuje i vraća spisak razlika. [enableAutoAdvance] kad
 * je Gmail upravo povezan (povezan Gmail = korisnik želi da se akontacija upisuje sama).
 * Radi mrežu — zvati van glavne niti.
 */
internal fun importFromGmailForOnboarding(
    context: Context,
    repo: ShiftRepository,
    today: LocalDate = LocalDate.now(),
    enableAutoAdvance: Boolean = true,
    onProgress: (done: Int, total: Int) -> Unit = { _, _ -> }
): OnboardingImportResult {
    if (enableAutoAdvance) repo.autoAdvanceImportEnabled = true
    val months = onboardingPayslipMonths(today)
    val sideMonths = months + sideRunCheckMonths(today).last()
    // Jedan prolaz kroz Gmail za ceo period; folder samo ako Gmail ne radi.
    val range = runPayslipSafely { readGmailPayslipRange(context, months.first(), sideMonths.last(), onProgress) }
        ?: GmailRangeResult.Failed
    val gmailFailed = range !is GmailRangeResult.Read
    Log.i(SIDE_RUN_LOG_TAG, "onboarding: Gmail ${if (range is GmailRangeResult.Read) "${range.results.size} PDF" else range}")
    val payslips: List<ParsedPayslip>
    val runs: List<PayslipParseResult.SideRun>
    if (range is GmailRangeResult.Read) {
        val results = range.results.map { it.second }
        payslips = results.filterIsInstance<PayslipParseResult.Success>().map { it.payslip }.filter { it.month in months }
        runs = sideMonths.flatMap { sideRuns(results.asSequence(), it) }
    } else {
        payslips = if (hasPayslipFolderAccess(context)) {
            val folder = payslipFolderFile(PayslipFolderSettings(context).folder)
            months.mapNotNull { runPayslipSafely { findPayslipInFolder(context, folder, it) }?.result?.payslip }
        } else {
            emptyList()
        }
        runs = collectSideRuns(context, sideMonths).runs
    }
    val plan = applyOnboardingPayslips(repo, payslips)
    val differences = plan?.changes.orEmpty()
        .map { change ->
            val newText = if (change.kind == PayslipChangeKind.PAY_BASE) formatPayBaseRateText(change.value.toDouble()) else change.payslipText
            SettingDifference(change.label, change.currentText, newText)
        }
        .toMutableList()
    fun money(value: Double) = "${decimalText(value, 2)} ${CONFIG.pay.currency}"
    // Prevoz i regres; akontacije posle, jer lista bez obračuna akontacije oduzima upisan regres.
    applySideRuns(
        repo = repo,
        runs = runs.filter { it.kind != PayslipSideRunKind.ADVANCE },
        onTransportChanged = { old, new -> differences += SettingDifference("Prevoz po danu", money(old), money(new)) },
        onRegressChanged = { month, old, new ->
            differences += SettingDifference("Regres od ${monthYearText(month)}", money(old), money(new))
        }
    )
    val advances = advancesFromImport(repo, runs, plan?.payslips.orEmpty())
    for ((month, amount) in advances) {
        val old = repo.getMonthlyNetAdjustments(month.year, month.month).advancePaymentNet
        if (repo.recordAdvancePaymentFromPayslip(month, amount)) {
            val oldText = if (old > 0.0) money(old) else "nije upisana"
            differences += SettingDifference("Akontacija ${monthYearText(month)}", oldText, money(amount))
        }
    }
    rememberTransportWorkMonth(context, runs)
    val vacationNote = when {
        plan == null -> null
        else -> applyOnboardingVacation(repo, today) ?: vacationRecheckNote(repo, today)
    }
    return OnboardingImportResult(
        payslips = plan?.payslips.orEmpty().sortedBy { it.month },
        transportDaily = latestTransportDaily(runs),
        advanceMonths = if (repo.autoAdvanceImportEnabled) advances.keys.sorted() else emptyList(),
        gmailFailed = gmailFailed,
        gmailNoAccess = range == GmailRangeResult.NoAccess,
        vacationNote = vacationNote,
        differences = differences
    )
}

/**
 * Pregled posle provere: samo ono što je promenjeno (staro → novo). Kad se sve slaže, piše
 * koliko je listića provereno.
 */
internal fun recheckSummary(result: OnboardingImportResult): OnboardingSummary {
    val rows = result.differences.map { it.label to "${it.oldText} → ${it.newText}" }
    val notes = mutableListOf<String>()
    val info = mutableListOf<String>()
    if (result.payslips.isEmpty()) {
        notes += "U Gmail-u nema konačnih listi od januara prošle godine."
    } else {
        info += "Provereno ${listCountText(result.payslips.size)} (${monthRangeText(result.payslips.map { it.month })})."
        if (rows.isEmpty()) info += "Sve vrednosti se slažu sa listama — ništa nije menjano."
    }
    result.vacationNote?.let { info += it }
    if (result.gmailNoAccess) {
        notes += GMAIL_RECONNECT_NOTE
    } else if (result.gmailFailed) {
        notes += "Gmail trenutno nije odgovorio — probaj ponovo kasnije."
    }
    return OnboardingSummary(rows, notes, info, stacked = true)
}

internal const val GMAIL_RECONNECT_NOTE =
    "Gmail dozvola je istekla ili nalog nije na telefonu — Opcije → „$GMAIL_PAYSLIP_CHANGE_LABEL“."

/** Pregled posle onboardinga: naziv → vrednost, onako kako je sada upisano u aplikaciji. */
internal data class OnboardingSummary(
    val rows: List<Pair<String, String>>,
    /** Upozorenja (žuto). */
    val notes: List<String>,
    /** Objašnjenja, npr. kako je izračunat godišnji. */
    val info: List<String> = emptyList(),
    /** Naziv pa vrednost ispod (duge vrednosti „staro → novo“), umesto u istom redu. */
    val stacked: Boolean = false
)

internal fun onboardingSummary(
    repo: ShiftRepository,
    result: OnboardingImportResult,
    today: LocalDate = LocalDate.now()
): OnboardingSummary {
    val rows = mutableListOf<Pair<String, String>>()
    val notes = mutableListOf<String>()
    val latest = result.payslips.lastOrNull()
    if (latest == null) {
        notes += "U Gmail-u nema konačnih listi od januara prošle godine — upisaće se sami kad stignu."
    } else {
        val currency = CONFIG.pay.currency
        rows += "Liste" to "${shortMonthRangeText(result.payslips.map { it.month })} (${result.payslips.size})"
        rows += "Koeficijent" to decimalText(repo.payCoefficientValue)
        rows += "Vrednost sata" to "${decimalText(repo.getHourlyValueForMonth(latest.month.year, latest.month.month), 2)} $currency"
        latest.seniorityPercent?.let { percent ->
            rows += "Minuli rad" to "${formatSerbianDecimal(percent)}%"
        }
        rows += "Doprinosi radnika" to "${decimalText(repo.employeeContributionsPercentValue)}%"
        rows += "Porez" to "${decimalText(repo.taxPercentValue)}%"
        rows += "Poreska olakšica" to "${decimalText(repo.taxReliefValue, 2)} $currency"
        repo.getKnownPayBaseAnchors().maxByOrNull { it.month }?.let { anchor ->
            rows += "Osnovica" to "${decimalText(anchor.grossHourlyBase, 2)} $currency/h (${shortMonthYearText(anchor.month)})"
        }
        latest.totalSeniorityYears?.let { rows += "Radni staž" to "$it god." }
        if (repo.getVacationYearBalance(today.year) != null) {
            val vacation = repo.getVacationBalanceSummary(today.year, today)
            rows += "Godišnji" to "${decimalText(vacation.availableNow)} od ${decimalText(vacation.currentYearTotal)} dana"
        }
        val nets = result.payslips.mapNotNull { it.netTotal }
        if (nets.isNotEmpty()) {
            val average = nets.fold(java.math.BigDecimal.ZERO, java.math.BigDecimal::add)
                .divide(java.math.BigDecimal(nets.size), 0, java.math.RoundingMode.HALF_UP)
            rows += "Prosek neto" to "${formatSerbianDecimal(average)} $currency"
        }
    }
    if (latest != null) {
        rows += "Regres" to "${decimalText(repo.getRegressNetForMonth(MonthKey(today.year, today.monthValue)), 2)} ${CONFIG.pay.currency}"
    }
    result.transportDaily?.let {
        rows += "Prevoz po danu" to "${decimalText(repo.transportDailyAmountValue, 2)} ${CONFIG.pay.currency}"
    }
    if (result.advanceMonths.isNotEmpty()) {
        rows += "Akontacija" to "upisana (${shortMonthRangeText(result.advanceMonths)})"
    }
    if (result.gmailNoAccess) {
        notes += GMAIL_RECONNECT_NOTE
    } else if (result.gmailFailed) {
        notes += "Gmail trenutno nije odgovorio — akontacija i prevoz upisaće se pri sledećem ulasku, a listu učitaj u „Listi“."
    }
    return OnboardingSummary(rows, notes, info = listOfNotNull(result.vacationNote))
}

private fun decimalText(value: Double, minFraction: Int = 0): String =
    formatSerbianDecimal(value.toDisplayDecimal(), minFraction = minFraction)

private val SHORT_MONTH_NAMES =
    listOf("Jan", "Feb", "Mar", "Apr", "Maj", "Jun", "Jul", "Avg", "Sep", "Okt", "Nov", "Dec")

/** „1 lista“, „3 liste“, „19 listi“. */
internal fun listCountText(count: Int): String {
    val lastTwo = count % 100
    val word = when {
        count % 10 == 1 && lastTwo != 11 -> "lista"
        count % 10 in 2..4 && lastTwo !in 12..14 -> "liste"
        else -> "listi"
    }
    return "$count $word"
}

/** „Avg 2026“ — za redove koji moraju da stanu u jednu liniju. */
internal fun shortMonthYearText(key: MonthKey): String = "${SHORT_MONTH_NAMES[key.month - 1]} ${key.year}"

/** „Jan 2025 – Avg 2026“, „Jul – Avg 2026“ ili jedan mesec. */
internal fun shortMonthRangeText(months: List<MonthKey>): String {
    val first = months.minOrNull() ?: return ""
    val last = months.max()
    if (first == last) return shortMonthYearText(first)
    val firstText = if (first.year == last.year) SHORT_MONTH_NAMES[first.month - 1] else shortMonthYearText(first)
    return "$firstText – ${shortMonthYearText(last)}"
}

/** „Mart – Avgust 2026“, „Decembar 2025 – Maj 2026“ ili jedan mesec. */
internal fun monthRangeText(months: List<MonthKey>): String {
    val first = months.minOrNull() ?: return ""
    val last = months.max()
    if (first == last) return monthYearText(first)
    val firstText = if (first.year == last.year) monthYearText(first).substringBefore(' ') else monthYearText(first)
    return "$firstText – ${monthYearText(last)}"
}

internal sealed interface GmailImportState {
    data object Idle : GmailImportState

    /** Pročitano / ukupno PDF-ova; ukupno 0 dok Gmail ne vrati spisak mejlova. */
    data class Running(val done: Int, val total: Int) : GmailImportState

    /** null = uvoz je pukao. */
    data class Finished(val result: OnboardingImportResult?) : GmailImportState
}

/**
 * Uvoz iz Gmail-a (onboarding, „Proveri sve po listama“) van ekrana: okretanje telefona ga ne
 * prekida, drugi ne može da krene dok traje, a provera pri ulasku ([isRunning]) ga čeka.
 * Rezultat ostaje dok ga dijalog ne potvrdi ([reset]).
 */
internal object GmailImportRunner {
    private val _state = MutableStateFlow<GmailImportState>(GmailImportState.Idle)
    val state: StateFlow<GmailImportState> = _state
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    val isRunning: Boolean get() = _state.value is GmailImportState.Running

    fun start(context: Context, repo: ShiftRepository, enableAutoAdvance: Boolean) {
        synchronized(this) {
            if (isRunning) return
            _state.value = GmailImportState.Running(0, 0)
        }
        val appContext = context.applicationContext
        scope.launch {
            val result = runPayslipSafely {
                importFromGmailForOnboarding(appContext, repo, enableAutoAdvance = enableAutoAdvance) { done, total ->
                    _state.value = GmailImportState.Running(done, total)
                }
            }
            _state.value = GmailImportState.Finished(result)
        }
    }

    fun reset() {
        synchronized(this) {
            if (!isRunning) _state.value = GmailImportState.Idle
        }
    }
}

private sealed interface OnboardingStep {
    data object Intro : OnboardingStep
    data object Importing : OnboardingStep
    data class Done(val summary: OnboardingSummary) : OnboardingStep
}

private val ONBOARDING_FAILED_SUMMARY = OnboardingSummary(
    rows = emptyList(),
    notes = listOf("Liste nisu mogle da se pročitaju — Rad će probati ponovo pri sledećem ulasku.")
)

/** Naziv levo, vrednost desno, sve u jednoj liniji (naziv se ne lomi, vrednost se skraćuje). */
@Composable
private fun InlineSummaryRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(Spacing.md),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 1,
            softWrap = false
        )
        Text(
            text = value,
            color = MaterialTheme.colorScheme.onSurface,
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.End,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
    }
}

/** Naziv, pa vrednost ispod (desno) — za duge „staro → novo“. */
@Composable
private fun StackedSummaryRow(label: String, value: String) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(text = label, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
        Text(
            text = value,
            color = MaterialTheme.colorScheme.onSurface,
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.End,
            modifier = Modifier.fillMaxWidth()
        )
    }
}

/** Pregled posle uvoza: naziv levo, vrednost desno; napomene ispod. */
@Composable
private fun OnboardingSummaryContent(summary: OnboardingSummary, heading: String) {
    Text(
        text = heading,
        color = MaterialTheme.colorScheme.onSurface,
        style = MaterialTheme.typography.titleSmall
    )
    summary.rows.forEach { (label, value) ->
        if (summary.stacked) StackedSummaryRow(label, value) else InlineSummaryRow(label, value)
    }
    summary.info.forEach { info ->
        Text(text = info, color = MaterialTheme.extendedColors.textSecondary, style = MaterialTheme.typography.bodySmall)
    }
    summary.notes.forEach { note ->
        Text(text = note, color = MaterialTheme.extendedColors.warning, style = MaterialTheme.typography.bodySmall)
    }
}

/** Čitanje listića: prozor na sredini ekrana sa trakom napretka; ne zatvara se dok čitanje traje. */
@Composable
private fun OnboardingProgressDialog(done: Int, total: Int) {
    Dialog(
        onDismissRequest = {},
        properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(MaterialTheme.shapes.extraLarge)
                .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                .border(1.dp, MaterialTheme.colorScheme.primary, MaterialTheme.shapes.extraLarge)
                .padding(Spacing.xl),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(Spacing.lg)
        ) {
            Text(
                text = GMAIL_ONBOARDING_IMPORTING_TEXT,
                color = MaterialTheme.colorScheme.onSurface,
                style = MaterialTheme.typography.titleMedium,
                textAlign = TextAlign.Center
            )
            if (total > 0) {
                LinearProgressIndicator(
                    progress = { done.toFloat() / total },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(8.dp)
                        .clip(MaterialTheme.shapes.extraSmall),
                    color = MaterialTheme.colorScheme.primary,
                    trackColor = MaterialTheme.colorScheme.surfaceContainer
                )
                Text(
                    text = "$done / $total lista i obračuna",
                    color = MaterialTheme.extendedColors.textSecondary,
                    style = MaterialTheme.typography.bodyMedium
                )
            } else {
                LinearProgressIndicator(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(8.dp)
                        .clip(MaterialTheme.shapes.extraSmall),
                    color = MaterialTheme.colorScheme.primary,
                    trackColor = MaterialTheme.colorScheme.surfaceContainer
                )
                Text(
                    text = "Tražim mejlove...",
                    color = MaterialTheme.extendedColors.textSecondary,
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        }
    }
}

/** Poruke iz provere pri ulasku (stigla lista sa izmenama, akontacija, prevoz): prozor na sredini ekrana. */
@Composable
internal fun SideRunMessagesDialog(messages: List<String>, onDismiss: () -> Unit) {
    Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 560.dp)
                .clip(MaterialTheme.shapes.extraLarge)
                .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                .border(1.dp, MaterialTheme.colorScheme.primary, MaterialTheme.shapes.extraLarge)
                .padding(Spacing.xl),
            verticalArrangement = Arrangement.spacedBy(Spacing.lg)
        ) {
            Text(
                text = SIDE_RUN_MESSAGES_TITLE,
                color = MaterialTheme.colorScheme.onSurface,
                style = MaterialTheme.typography.titleMedium,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )
            Column(
                modifier = Modifier
                    .weight(1f, fill = false)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(Spacing.md)
            ) {
                messages.forEach { message ->
                    Text(text = message, color = MaterialTheme.colorScheme.onSurface, style = MaterialTheme.typography.bodyMedium)
                }
            }
            RoundedButton(
                text = "U redu",
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp),
                bgColor = MaterialTheme.colorScheme.primary,
                textColor = MaterialTheme.colorScheme.onPrimary,
                bold = true,
                onClick = onDismiss
            )
        }
    }
}

/**
 * [ONBOARDING] = prvo pokretanje (posle „Popuni smene“, pregled upisanih vrednosti); [UPDATE] = posle
 * ažuriranja stare verzije bez Gmail-a (jednom); [RECHECK] = Opcije → „Proveri po listićima“.
 * UPDATE i RECHECK prikazuju samo razlike (staro → novo).
 */
internal enum class GmailImportMode(
    val title: String,
    val introText: String,
    val doneHeading: String,
    val doneLabel: String
) {
    ONBOARDING(GMAIL_ONBOARDING_TITLE, GMAIL_ONBOARDING_TEXT, "Upisano iz Gmail-a", GMAIL_ONBOARDING_NEXT_LABEL),
    UPDATE(GMAIL_ONBOARDING_TITLE, GMAIL_UPDATE_TEXT, RECHECK_CHANGED_HEADING, "U redu"),
    RECHECK(PAYSLIP_RECHECK_LABEL, GMAIL_RECHECK_CONNECT_TEXT, RECHECK_CHANGED_HEADING, "U redu");

    fun summary(repo: ShiftRepository, result: OnboardingImportResult): OnboardingSummary =
        if (this == ONBOARDING) onboardingSummary(repo, result) else recheckSummary(result)

    /** Provera bez izmena nema „ispravljeno“ u naslovu. */
    fun heading(summary: OnboardingSummary): String =
        if (this != ONBOARDING && summary.rows.isEmpty()) "Provereno po listama" else doneHeading
}

private const val RECHECK_CHANGED_HEADING = "Ispravljeno po listama"
internal const val PAYSLIP_RECHECK_LABEL = "Proveri sve po listama"
private const val GMAIL_UPDATE_TEXT = GMAIL_ONBOARDING_TEXT
private const val GMAIL_RECHECK_CONNECT_TEXT = GMAIL_ONBOARDING_TEXT

private const val GMAIL_UPDATE_PREFS = "rad_gmail_update_prefs"
private const val KEY_UPDATE_INTRO_OFFERED = "update_intro_offered"

/**
 * Posle ažuriranja stare verzije (nije prvo pokretanje, a Gmail nije povezan) jednom ponudi
 * povezivanje Gmail-a i proveru po listićima. Vraća true ako treba prikazati; beleži da je ponuđeno.
 */
internal fun takeGmailUpdateIntro(context: Context, firstLaunch: Boolean): Boolean {
    val prefs = context.getSharedPreferences(GMAIL_UPDATE_PREFS, Context.MODE_PRIVATE)
    if (prefs.getBoolean(KEY_UPDATE_INTRO_OFFERED, false)) return false
    prefs.edit().putBoolean(KEY_UPDATE_INTRO_OFFERED, true).apply()
    return !firstLaunch && !hasGmailPayslipAccess(context)
}

/**
 * Gmail sa listićima i uvoz (videti [GmailImportMode]); [onFinished] posle „Dalje“ ili „Preskoči“.
 * RECHECK sa već povezanim Gmail-om odmah kreće u čitanje.
 */
@Composable
internal fun GmailOnboardingDialog(
    repo: ShiftRepository,
    mode: GmailImportMode = GmailImportMode.ONBOARDING,
    onFinished: () -> Unit
) {
    val context = LocalContext.current
    // Uvoz teče van ekrana: okretanje telefona ne prekida čitanje niti ga pokreće ponovo.
    val importState by GmailImportRunner.state.collectAsState()
    val step = remember(importState) {
        when (val state = importState) {
            GmailImportState.Idle -> OnboardingStep.Intro
            is GmailImportState.Running -> OnboardingStep.Importing
            is GmailImportState.Finished -> OnboardingStep.Done(
                state.result?.let { runPayslipSafely { mode.summary(repo, it) } } ?: ONBOARDING_FAILED_SUMMARY
            )
        }
    }
    val finish = {
        GmailImportRunner.reset()
        onFinished()
    }
    val connector = rememberGmailPayslipConnector { GmailImportRunner.start(context, repo, enableAutoAdvance = true) }
    LaunchedEffect(Unit) {
        if (mode == GmailImportMode.RECHECK && hasGmailPayslipAccess(context) && importState == GmailImportState.Idle) {
            GmailImportRunner.start(context, repo, enableAutoAdvance = false)
        }
    }

    (importState as? GmailImportState.Running)?.let { running ->
        OnboardingProgressDialog(done = running.done, total = running.total)
        return
    }
    // RECHECK sa povezanim Gmail-om: prvi kadar pre nego što LaunchedEffect krene.
    if (step == OnboardingStep.Intro && mode == GmailImportMode.RECHECK && hasGmailPayslipAccess(context)) return

    PopupDialog(
        title = mode.title,
        onDismiss = { if (step !is OnboardingStep.Importing) finish() },
        scrollable = true
    ) {
        PopupCard {
            when (val current = step) {
                is OnboardingStep.Done -> OnboardingSummaryContent(current.summary, mode.heading(current.summary))
                else -> Text(
                    text = if (current == OnboardingStep.Intro) mode.introText else GMAIL_ONBOARDING_IMPORTING_TEXT,
                    color = MaterialTheme.extendedColors.textSecondary,
                    style = MaterialTheme.typography.bodyMedium
                )
            }
            connector.error?.let {
                FormErrorText(message = it, style = MaterialTheme.typography.bodySmall)
            }
        }
        OnboardingActions(step, mode, connector.busy, connector.connect, finish)
    }
}

/** Dugmad ispod teksta: „Preskoči“ / „Poveži Gmail“ na uvodu, „Dalje“ na kraju, ništa tokom čitanja. */
@Composable
private fun OnboardingActions(
    step: OnboardingStep,
    mode: GmailImportMode,
    connecting: Boolean,
    onConnect: () -> Unit,
    onFinish: () -> Unit
) {
    when (step) {
        OnboardingStep.Intro -> Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(Spacing.md)
        ) {
            RoundedButton(
                text = GMAIL_ONBOARDING_SKIP_LABEL,
                modifier = Modifier
                    .weight(1f)
                    .height(48.dp),
                bgColor = MaterialTheme.colorScheme.surfaceContainer,
                textColor = MaterialTheme.colorScheme.onSurface,
                onClick = onFinish
            )
            RoundedButton(
                text = if (connecting) "Povezujem..." else GMAIL_PAYSLIP_CONNECT_LABEL,
                modifier = Modifier
                    .weight(1f)
                    .height(48.dp),
                bgColor = MaterialTheme.colorScheme.primary,
                textColor = MaterialTheme.colorScheme.onPrimary,
                bold = true,
                onClick = onConnect
            )
        }
        OnboardingStep.Importing -> Unit
        is OnboardingStep.Done -> RoundedButton(
            text = mode.doneLabel,
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp),
            bgColor = MaterialTheme.colorScheme.primary,
            textColor = MaterialTheme.colorScheme.onPrimary,
            bold = true,
            onClick = onFinish
        )
    }
}
