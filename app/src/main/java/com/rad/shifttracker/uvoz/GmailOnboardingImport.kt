package com.rad.shifttracker.uvoz

import android.content.Context
import android.util.Log
import java.time.LocalDate
import java.time.Month
import java.time.YearMonth
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import com.rad.shifttracker.osnova.CONFIG
import com.rad.shifttracker.godisnji.VacationYearBalance
import com.rad.shifttracker.godisnji.currentYearVacationAvailableFrom
import com.rad.shifttracker.repo.ShiftRepository
import com.rad.shifttracker.repo.getSavedPayslip
import com.rad.shifttracker.repo.getVacationYearBalance
import com.rad.shifttracker.repo.setVacationYearBalance
import com.rad.shifttracker.smene.MonthKey
import com.rad.shifttracker.osnova.formatPayBaseRateText
import com.rad.shifttracker.smene.monthYearText
import com.rad.shifttracker.repo.getAnnualLeaveEntitlement
import com.rad.shifttracker.repo.getMonthlyNetAdjustments
import com.rad.shifttracker.repo.payslipAdvanceNet
import com.rad.shifttracker.repo.recordAdvancePaymentFromPayslip
import com.rad.shifttracker.repo.savePayslip
import com.rad.shifttracker.listici.GmailRangeResult
import com.rad.shifttracker.listici.ParsedPayslip
import com.rad.shifttracker.listici.PayslipParseResult
import com.rad.shifttracker.listici.PayslipSideRunKind
import com.rad.shifttracker.listici.advanceTotalsByPaymentMonth
import com.rad.shifttracker.listici.latestTransportDaily
import com.rad.shifttracker.listici.payslipVacationDays
import com.rad.shifttracker.listici.readGmailPayslipRange
import com.rad.shifttracker.listici.runPayslipSafely
import com.rad.shifttracker.listici.sideRuns
import com.rad.shifttracker.listici.SIDE_RUN_LOG_TAG
import com.rad.shifttracker.listici.payslipPayBaseAnchor

// Uvoz listića iz Gmail-a pri prvom pokretanju i „Proveri sve po listama“: šta se upisuje i
// godišnji iz listića, pa izvršavanje van ekrana (GmailImportRunner).

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
    // Osnovica se upisuje sa svake liste: inače stariji meseci pozajme najnoviju (954,24 i za 2025).
    // Tekuće mesece i dalje određuje najnovija, pa se u pregledu izmena pokazuje samo ona.
    applyPayslipChanges(repo, built.changes)
    built.payslips.forEach { repo.savePayslip(it) }
    val newestBase = built.payslips.lastOrNull { payslipPayBaseAnchor(it) != null }?.month
    return built.copy(
        changes = built.changes.filter { it.kind != PayslipChangeKind.PAY_BASE || it.month == newestBase }
    )
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
 * posebni obračuni; upisuje sve što se razlikuje (i akontacije) i vraća spisak razlika.
 * Radi mrežu — zvati van glavne niti.
 */
internal fun importFromGmailForOnboarding(
    context: Context,
    repo: ShiftRepository,
    today: LocalDate = LocalDate.now(),
    onProgress: (done: Int, total: Int) -> Unit = { _, _ -> }
): OnboardingImportResult {
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
        payslips = emptyList()
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
        advanceMonths = advances.keys.sorted(),
        gmailFailed = gmailFailed,
        gmailNoAccess = range == GmailRangeResult.NoAccess,
        vacationNote = vacationNote,
        differences = differences
    )
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

    fun start(context: Context, repo: ShiftRepository) {
        synchronized(this) {
            if (isRunning) return
            _state.value = GmailImportState.Running(0, 0)
        }
        val appContext = context.applicationContext
        scope.launch {
            val result = runPayslipSafely {
                importFromGmailForOnboarding(appContext, repo) { done, total ->
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
