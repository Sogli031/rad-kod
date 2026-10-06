package com.rad.shifttracker.listici

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate
import com.rad.shifttracker.CONFIG
import com.rad.shifttracker.repo.ShiftRepository
import com.rad.shifttracker.repo.getSavedPayslip
import com.rad.shifttracker.ui.FormErrorText
import com.rad.shifttracker.ui.dialozi.PAYSLIP_UNEXPECTED_ERROR_TEXT
import com.rad.shifttracker.ui.dialozi.PayslipPickOutcome
import com.rad.shifttracker.ui.dialozi.formatPayBaseRateText
import com.rad.shifttracker.ui.dialozi.monthYearText
import com.rad.shifttracker.ui.dialozi.payslipPickOutcome
import com.rad.shifttracker.ui.extendedColors
import com.rad.shifttracker.UI
import com.rad.shifttracker.repo.getMonthlyNetAdjustments
import com.rad.shifttracker.repo.recordAdvancePaymentFromPayslip
import com.rad.shifttracker.repo.savePayslip
import com.rad.shifttracker.ui.RoundedButton

internal const val MANUAL_PAYSLIP_IMPORT_LABEL = "Učitaj listu iz fajla (PDF)"

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

/** Opcije: dugme koje otvara sistemski birač PDF-a (bez dozvola za skladište) i poruka o ishodu. */
@Composable
internal fun ManualPayslipImportButton(repo: ShiftRepository) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var isReading by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<Pair<String, Boolean>?>(null) }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        isReading = true
        message = null
        scope.launch {
            val result = withContext(Dispatchers.IO) { readPayslip(context, uri) }
            message = withContext(Dispatchers.Default) { importManualPayslip(repo, result) }
            (result as? PayslipParseResult.SideRun)?.let { rememberTransportWorkMonth(context, listOf(it)) }
            isReading = false
        }
    }

    RoundedButton(
        text = if (isReading) "Čitam listu..." else MANUAL_PAYSLIP_IMPORT_LABEL,
        modifier = Modifier
            .fillMaxWidth()
            .height(UI.buttonHeight.dp),
        bgColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        textColor = MaterialTheme.colorScheme.onSurface,
        borderColor = MaterialTheme.colorScheme.outlineVariant,
        textStyle = MaterialTheme.typography.labelLarge,
        onClick = { if (!isReading) picker.launch(arrayOf("application/pdf")) }
    )
    message?.let { (text, isError) ->
        if (isError) {
            FormErrorText(message = text, style = MaterialTheme.typography.bodySmall, maxLines = 5)
        } else {
            // Bez ReaderText-a: spisak izmena ume da bude duži od 5 redova.
            Text(
                text = text,
                color = MaterialTheme.extendedColors.success,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }
            )
        }
    }
}
