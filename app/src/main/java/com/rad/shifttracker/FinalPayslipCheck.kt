package com.rad.shifttracker

import android.content.Context
import android.util.Log
import java.time.LocalDate
import java.time.YearMonth

/**
 * Konačna lista za prošli mesec rada stiže oko 20. (najranije viđena 17.). Pri ulasku u aplikaciju,
 * dok je nema, jedna pretraga u Gmail-u (folder samo kad Gmail ne radi); nađena lista se upisuje
 * kao u „Listi“ i javlja porukom u aplikaciji.
 */
private const val FINAL_PAYSLIP_CHECK_PREFS = "rad_final_payslip_check"
private const val KEY_LAST_CHECK_MILLIS = "last_check_millis"
private const val KEY_LAST_DEDUCTIONS_REFRESH_MILLIS = "last_deductions_refresh_millis"
private const val KEY_REFRESHED_MONTHS = "refreshed_payslip_months"
private const val FINAL_PAYSLIP_CHECK_INTERVAL_MS = 2 * 60 * 60 * 1000L

/** Mesec rada čija se konačna lista čeka, ili null ako je već učitana, obrisana ili je rano. */
internal fun finalPayslipCheckMonth(repo: ShiftRepository, today: LocalDate): MonthKey? {
    if (today.dayOfMonth < PAYSLIP_ARRIVAL_DAY) return null
    val previous = YearMonth.from(today).minusMonths(1)
    val month = MonthKey(previous.year, previous.monthValue)
    return month.takeIf { repo.getSavedPayslip(it.year, it.month) == null && !repo.isPayslipDeleted(it) }
}

/** Poruka o stigloj listi sa svakom izmenom podešavanja (staro → novo), ili „Bez izmena“. */
internal fun finalPayslipArrivedText(month: MonthKey, changes: List<PayslipChange>): String {
    val heading = "Stigla lista za ${monthYearText(month)} — učitana."
    if (changes.isEmpty()) return "$heading\nBez izmena."
    return heading + "\nIzmene:" + changes.joinToString("") { "\n• ${it.label}: ${it.currentText} → ${it.payslipText}" }
}

/** Upis nađene liste kao u „Listi“; vraća poruku ili null ako lista nije za [month]. */
internal fun applyArrivedFinalPayslip(repo: ShiftRepository, found: PayslipParseResult.Success, month: MonthKey): String? {
    val ready = payslipPickOutcome(repo, found, month) as? PayslipPickOutcome.Ready ?: return null
    applyPayslipChanges(repo, ready.plan.changes)
    repo.savePayslip(ready.payslip)
    return finalPayslipArrivedText(month, ready.plan.changes)
}

/**
 * Sačuvane liste pročitane starijim parserom (bez obustava i isplaćenih iznosa): njih treba jednom
 * ponovo pročitati — obustave se prenose na sledeće mesece, a iz isplaćenog se računa minimum
 * akontacije. Meseci koji su već pokušani sa ovom verzijom parsera se preskaču.
 */
internal fun payslipRefreshMonths(repo: ShiftRepository, attempted: Set<String> = emptySet()): List<MonthKey> =
    repo.getSavedPayslipMonths().filter { month ->
        val slip = repo.getSavedPayslip(month.year, month.month)
        slip != null && (slip.deductions == null || slip.paidNet == null) && month.refreshKey() !in attempted
    }

private fun MonthKey.refreshKey(): String = "v$PAYSLIP_PARSER_VERSION:$year-$month"

/** Radi mrežu i čita fajlove — zvati van glavne niti. */
internal fun runFinalPayslipCheckOnEntry(context: Context, repo: ShiftRepository, today: LocalDate = LocalDate.now()) {
    val prefs = context.getSharedPreferences(FINAL_PAYSLIP_CHECK_PREFS, Context.MODE_PRIVATE)
    val now = System.currentTimeMillis()
    val attempted = prefs.getStringSet(KEY_REFRESHED_MONTHS, emptySet()).orEmpty()
    val refreshMonths = payslipRefreshMonths(repo, attempted)
    if (refreshMonths.isNotEmpty() && now - prefs.getLong(KEY_LAST_DEDUCTIONS_REFRESH_MILLIS, 0L) >= FINAL_PAYSLIP_CHECK_INTERVAL_MS) {
        prefs.edit().putLong(KEY_LAST_DEDUCTIONS_REFRESH_MILLIS, now).apply()
        val found = findFinalPayslips(context, refreshMonths)
        if (found != null) {
            // Lista je već upisana: čuva se samo ponovo pročitana, podešavanja se ne menjaju.
            found.forEach { repo.savePayslip(it, withPayBase = false) }
            prefs.edit().putStringSet(KEY_REFRESHED_MONTHS, attempted + refreshMonths.map { it.refreshKey() }).apply()
        }
        Log.i(SIDE_RUN_LOG_TAG, "ponovo pročitano ${found?.size ?: "0 (nema pristupa)"} od ${refreshMonths.size} lista")
    }
    val month = finalPayslipCheckMonth(repo, today) ?: return
    if (now - prefs.getLong(KEY_LAST_CHECK_MILLIS, 0L) < FINAL_PAYSLIP_CHECK_INTERVAL_MS) return
    prefs.edit().putLong(KEY_LAST_CHECK_MILLIS, now).apply()

    val (found, gmail) = findFinalPayslip(context, month)
    Log.i(SIDE_RUN_LOG_TAG, "konačna ${monthYearText(month)}: ${if (found != null) "nađena" else "nema"} ($gmail)")
    found?.let { applyArrivedFinalPayslip(repo, it, month) }?.let(SideRunMessages::post)
}

/** Lista za [month] iz Gmail-a, a iz foldera samo kad Gmail ne radi; uz nju i ishod Gmail pretrage. */
private fun findFinalPayslip(context: Context, month: MonthKey): Pair<PayslipParseResult.Success?, GmailPayslipResult> {
    val gmail = if (hasGmailPayslipAccess(context)) findPayslipInGmail(context, month) else GmailPayslipResult.NoAccess
    val found = when (gmail) {
        is GmailPayslipResult.Found -> gmail.payslip.result
        GmailPayslipResult.NotFound -> null
        GmailPayslipResult.NoAccess, GmailPayslipResult.Failed -> if (hasPayslipFolderAccess(context)) {
            findPayslipInFolder(context, payslipFolderFile(PayslipFolderSettings(context).folder), month)?.result
        } else {
            null
        }
    }
    return found to gmail
}

/**
 * Liste za [months] (za svaki mesec najveći rb.): iz Gmail-a u jednom prolazu, a iz foldera samo kad
 * Gmail ne radi. Null kad ni jedno ni drugo nije dostupno (pokušava se ponovo kasnije).
 */
private fun findFinalPayslips(context: Context, months: List<MonthKey>): List<ParsedPayslip>? {
    val wanted = months.toSet()
    val gmail = if (hasGmailPayslipAccess(context)) {
        readGmailPayslipRange(context, months.min(), months.max())
    } else {
        GmailRangeResult.NoAccess
    }
    val slips = when (gmail) {
        is GmailRangeResult.Read -> gmail.results.mapNotNull { (_, result) -> (result as? PayslipParseResult.Success)?.payslip }
        GmailRangeResult.NoAccess, GmailRangeResult.Failed -> {
            if (!hasPayslipFolderAccess(context)) return null
            val folder = payslipFolderFile(PayslipFolderSettings(context).folder)
            months.mapNotNull { findPayslipInFolder(context, folder, it)?.result?.payslip }
        }
    }
    return slips.filter { it.month in wanted }
        .groupBy { it.month }
        .map { (_, sameMonth) -> sameMonth.maxBy { payslipRunNumber(it.runLabel) } }
}
