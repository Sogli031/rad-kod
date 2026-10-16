package com.rad.shifttracker.ui.dialozi

import com.rad.shifttracker.ui.DataActions
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import com.rad.shifttracker.smene.MonthKey
import com.rad.shifttracker.osnova.UI
import com.rad.shifttracker.uvoz.FillAllResult
import com.rad.shifttracker.listici.ParsedPayslip
import com.rad.shifttracker.uvoz.fillableMonths
import com.rad.shifttracker.uvoz.planAiMonthFromPayslip
import com.rad.shifttracker.uvoz.planAiFillBatch
import com.rad.shifttracker.uvoz.PayslipFillProposal
import com.rad.shifttracker.uvoz.PayslipFillBatch
import com.rad.shifttracker.uvoz.applyReviewedFills
import com.rad.shifttracker.uvoz.STALE_FILL_PROPOSAL_TEXT
import com.rad.shifttracker.listici.runPayslipSafely
import com.rad.shifttracker.repo.ShiftRepository
import com.rad.shifttracker.ui.FormErrorText
import com.rad.shifttracker.ui.GlowButton
import com.rad.shifttracker.ui.RoundedButton
import com.rad.shifttracker.ui.extendedColors
import com.rad.shifttracker.uvoz.PAYSLIP_UNEXPECTED_ERROR_TEXT
import com.rad.shifttracker.smene.monthYearText

internal const val PAYSLIP_MONTH_FILL_ACTION_LABEL = "Popuni iz liste"
internal const val PAYSLIP_MONTH_REPLACE_ACTION_LABEL = "Zameni dane po listi"

/**
 * Raspored se priprema na kopiji, pregleda i upisuje isključivo potvrdom „Upiši“.
 * Ako raspored ne može da se nađe ili se podaci u međuvremenu promene, mesec ostaje netaknut.
 */
@Composable
internal fun PayslipMonthFillButton(
    repo: ShiftRepository,
    actions: DataActions,
    payslip: ParsedPayslip,
    replace: Boolean = false,
    onFilled: (MonthKey) -> Unit
) {
    val scope = rememberCoroutineScope()
    var isWorking by remember { mutableStateOf(false) }
    var proposal by remember(payslip) { mutableStateOf<PayslipFillProposal?>(null) }
    var error by remember(payslip) { mutableStateOf<String?>(null) }

    fun start() {
        isWorking = true
        error = null
        scope.launch {
            // Solver radi na kopiji podataka; greška ili nedostatak memorije daje poruku, mesec ostaje netaknut.
            val outcome = withContext(Dispatchers.IO) {
                runPayslipSafely { planAiMonthFromPayslip(repo, payslip) }
            }
            isWorking = false
            proposal = outcome?.first
            error = outcome?.second ?: if (proposal == null) PAYSLIP_UNEXPECTED_ERROR_TEXT else null
        }
    }

    GlowButton(
        text = when {
            isWorking -> "Popunjavam..."
            replace -> PAYSLIP_MONTH_REPLACE_ACTION_LABEL
            else -> PAYSLIP_MONTH_FILL_ACTION_LABEL
        },
        modifier = Modifier
            .fillMaxWidth()
            .height(UI.buttonHeight.dp),
        textStyle = MaterialTheme.typography.labelLarge,
        onClick = {
            if (!isWorking) {
                if (proposal == null) start()
            }
        }
    )
    error?.let { FormErrorText(message = it, style = MaterialTheme.typography.bodySmall, maxLines = 5) }

    proposal?.let { ready ->
        PayslipFillReviewDialog(
            proposals = listOf(ready),
            failures = emptyList(),
            onConfirm = {
                if (applyReviewedFills(repo, listOf(ready), actions::applyMonthFill)) {
                    proposal = null
                    onFilled(ready.result.month)
                } else {
                    proposal = null
                    error = STALE_FILL_PROPOSAL_TEXT
                }
            },
            onDismiss = { proposal = null }
        )
    }
}

internal const val PAYSLIP_FILL_ALL_LABEL = "Popuni sve po listama"
internal const val PAYSLIP_FILL_CANCEL_LABEL = "Prekini popunu"

internal fun fillAllStatusText(result: FillAllResult): String {
    val filled = "Popunjeno: ${result.filled.size}"
    if (result.failed.isEmpty()) return filled
    return "$filled. Nije uspelo: ${result.failed.joinToString { monthYearText(it.first) }}"
}

/**
 * Priprema sve mesece bez korisnikovih upisa. Zajednički pregled prethodi svakom upisu.
 * Neuspeli meseci ostaju netaknuti i navode se u statusu.
 */
@Composable
internal fun PayslipFillAllButton(repo: ShiftRepository, actions: DataActions) {
    val scope = rememberCoroutineScope()
    var refresh by remember { mutableStateOf(0) }
    var months by remember { mutableStateOf<List<MonthKey>>(emptyList()) }
    var isWorking by remember { mutableStateOf(false) }
    var job by remember { mutableStateOf<Job?>(null) }
    var status by remember { mutableStateOf<FillAllResult?>(null) }
    var batch by remember { mutableStateOf<PayslipFillBatch?>(null) }
    var error by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(refresh) {
        months = withContext(Dispatchers.Default) { runPayslipSafely { fillableMonths(repo) } }.orEmpty()
    }
    if (months.isNotEmpty()) {
        RoundedButton(
            text = if (isWorking) PAYSLIP_FILL_CANCEL_LABEL else "$PAYSLIP_FILL_ALL_LABEL (${months.size})",
            modifier = Modifier
                .fillMaxWidth()
                .height(UI.buttonHeight.dp),
            bgColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            textColor = MaterialTheme.colorScheme.onSurface,
            borderColor = MaterialTheme.colorScheme.outlineVariant,
            textStyle = MaterialTheme.typography.labelLarge,
            onClick = {
                if (isWorking) {
                    // Prekid važi između meseci; ništa nije upisano, pa nema šta da se vraća.
                    job?.cancel()
                    job = null
                    isWorking = false
                } else {
                    isWorking = true
                    status = null
                    error = null
                    val pending = months
                    job = scope.launch {
                        val result = withContext(Dispatchers.IO) {
                            val context = currentCoroutineContext()
                            runPayslipSafely { planAiFillBatch(repo, pending, checkCancelled = { context.ensureActive() }) }
                        }
                        isWorking = false
                        job = null
                        if (result != null && result.proposals.isNotEmpty()) batch = result
                        else status = FillAllResult(emptyList(), result?.failures ?: pending.map { it to PAYSLIP_UNEXPECTED_ERROR_TEXT })
                    }
                }
            }
        )
    }
    error?.let { FormErrorText(it) }
    batch?.let { ready ->
        PayslipFillReviewDialog(
            proposals = ready.proposals,
            failures = ready.failures,
            onConfirm = {
                if (applyReviewedFills(repo, ready.proposals, actions::applyMonthFill)) {
                    status = FillAllResult(ready.proposals.map { it.result.month }, ready.failures)
                    refresh++
                } else error = STALE_FILL_PROPOSAL_TEXT
                batch = null
            },
            onDismiss = { batch = null }
        )
    }
    status?.let { result ->
        ReaderText(fillAllStatusText(result), color = if (result.failed.isEmpty()) MaterialTheme.extendedColors.success else MaterialTheme.extendedColors.warning)
    }
}
