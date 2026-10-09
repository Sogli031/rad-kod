package com.rad.shifttracker.uvoz

import java.time.LocalDate
import com.rad.shifttracker.osnova.CONFIG
import com.rad.shifttracker.repo.ShiftRepository
import com.rad.shifttracker.repo.getSavedPayslip
import com.rad.shifttracker.osnova.formatPayBaseRateText
import com.rad.shifttracker.smene.monthYearText
import com.rad.shifttracker.repo.getMonthlyNetAdjustments
import com.rad.shifttracker.repo.recordAdvancePaymentFromPayslip
import com.rad.shifttracker.repo.savePayslip
import com.rad.shifttracker.listici.PayslipParseResult
import com.rad.shifttracker.listici.PayslipSideRunKind
import com.rad.shifttracker.listici.formatSerbianDecimal
import com.rad.shifttracker.listici.runPayslipSafely

internal const val MANUAL_PAYSLIP_IMPORT_LABEL = "Učitaj liste iz fajlova (PDF)"

/**
 * Ručno izabran PDF, jedan po jedan, upisuje se kao iz Gmail-a. Za kolege kojima Gmail još nema stare
 * liste (tek uključeno prosleđivanje). Lista se čuva za mesec koji piše na njoj (ne za mesec u
 * kalendaru), sa svim izmenama podešavanja, akontacijom za sledeći mesec i pravom na godišnji (ako
 * još nije upisano); ponovo učitana lista za isti mesec zamenjuje staru. Poseban obračun (akontacija,
 * regres, prevoz) se upisuje kao iz Gmail-a. Vraća poruku i da li je greška.
 */
internal fun importManualPayslip(
    repo: ShiftRepository,
    result: PayslipParseResult,
    today: LocalDate = LocalDate.now()
): Pair<String, Boolean> =
    runPayslipSafely {
        if (result is PayslipParseResult.SideRun) importManualSideRun(repo, result) else importManualFinalPayslip(repo, result, today)
    } ?: (PAYSLIP_UNEXPECTED_ERROR_TEXT to true)

/**
 * Više izabranih PDF-ova odjednom (npr. poslednjih 5–10 lista skinutih u Downloads): fajlovi koji
 * nisu liste se preskaču, a liste se upisuju od najstarijeg meseca, da na kraju važe podešavanja
 * sa najnovije. Za isti mesec i vrstu važi poslednji izabran fajl. Vraća poruku i da li je sve palo.
 */
internal fun importManualPayslips(
    repo: ShiftRepository,
    results: List<PayslipParseResult>,
    today: LocalDate = LocalDate.now()
): Pair<String, Boolean> {
    val readable = results.filter { it !is PayslipParseResult.Failure }
    val skipped = results.size - readable.size
    val ordered = readable
        .associateBy { result -> manualImportKey(result) }
        .values
        .sortedWith(compareBy({ manualImportMonthIndex(it) }, { if (it is PayslipParseResult.SideRun) 1 else 0 }))
    val messages = ordered.map { importManualPayslip(repo, it, today) }
    val imported = messages.count { !it.second }
    val parts = buildList {
        add("Učitano lista: $imported od ${results.size} izabranih fajlova.")
        if (skipped > 0) add("Preskočeno $skipped (nisu liste ili se ne mogu pročitati).")
        messages.forEach { (text, _) -> add(text) }
    }
    return parts.joinToString("\n\n") to (imported == 0)
}

private fun manualImportKey(result: PayslipParseResult): String = when (result) {
    is PayslipParseResult.Success -> "L-${result.payslip.month}"
    is PayslipParseResult.SideRun -> "S-${result.month}-${result.kind}-${result.runLabel}"
    is PayslipParseResult.Failure -> "F"
}

private fun manualImportMonthIndex(result: PayslipParseResult): Int = when (result) {
    is PayslipParseResult.Success -> result.payslip.month.year * MONTHS_IN_YEAR + result.payslip.month.month
    is PayslipParseResult.SideRun -> result.month.year * MONTHS_IN_YEAR + result.month.month
    is PayslipParseResult.Failure -> 0
}

private const val MONTHS_IN_YEAR = 12

private fun importManualFinalPayslip(repo: ShiftRepository, result: PayslipParseResult, today: LocalDate): Pair<String, Boolean> {
    val outcome = payslipPickOutcome(repo, result, month = null)
    if (outcome is PayslipPickOutcome.Rejected) return outcome.text to true
    val ready = outcome as PayslipPickOutcome.Ready
    val slip = ready.payslip
    val replaced = repo.getSavedPayslip(slip.month.year, slip.month.month) != null
    val lines = ready.plan.changes.map { change ->
        val newText = if (change.kind == PayslipChangeKind.PAY_BASE) formatPayBaseRateText(change.value.toDouble()) else change.payslipText
        "${change.label}: ${change.currentText} → $newText"
    }.toMutableList()
    applyPayslipChanges(repo, ready.plan.changes)
    repo.savePayslip(slip)
    // Isplaćeno po konačnoj listi = akontacija sledećeg meseca (isto kao Gmail uvoz).
    advancesFromImport(repo, emptyList(), listOf(slip)).forEach { (month, amount) ->
        val old = repo.getMonthlyNetAdjustments(month.year, month.month).advancePaymentNet
        if (repo.recordAdvancePaymentFromPayslip(month, amount)) {
            lines += "Akontacija ${monthYearText(month)}: ${manualMoneyText(old)} → ${manualMoneyText(amount)}"
        }
    }
    applyOnboardingVacation(repo, today)?.let { lines += it }
    val heading = "Lista za ${monthYearText(slip.month)} je ${if (replaced) "zamenjena" else "sačuvana"}."
    return manualImportText(heading, lines) to false
}

private fun importManualSideRun(repo: ShiftRepository, run: PayslipParseResult.SideRun): Pair<String, Boolean> {
    val lines = mutableListOf<String>()
    applySideRuns(
        repo = repo,
        runs = listOf(run),
        onAdvanceChanged = { month, old, new -> lines += "Akontacija ${monthYearText(month)}: ${manualMoneyText(old)} → ${manualMoneyText(new)}" },
        onTransportChanged = { old, new -> lines += "Prevoz po danu: ${manualMoneyText(old)} → ${manualMoneyText(new)}" },
        onRegressChanged = { month, old, new -> lines += "Regres od ${monthYearText(month)}: ${manualMoneyText(old)} → ${manualMoneyText(new)}" }
    )
    val what = if (run.kind == PayslipSideRunKind.OTHER) "Poseban obračun" else "Obračun ${run.kind.label}"
    return manualImportText("$what za ${monthYearText(run.month)} je učitan.", lines) to false
}

private fun manualMoneyText(value: Double): String =
    if (value > 0.0) "${formatSerbianDecimal(value.toBigDecimal(), minFraction = 2)} ${CONFIG.pay.currency}" else "nije upisano"

internal fun manualImportText(heading: String, lines: List<String>): String =
    if (lines.isEmpty()) "$heading\nBez izmena." else heading + "\nIzmene:" + lines.joinToString("") { "\n• $it" }
