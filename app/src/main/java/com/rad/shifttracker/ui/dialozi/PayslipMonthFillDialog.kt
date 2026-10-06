package com.rad.shifttracker.ui.dialozi

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
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
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.rad.shifttracker.smene.MonthKey
import com.rad.shifttracker.UI
import com.rad.shifttracker.listici.FillAllResult
import com.rad.shifttracker.listici.MonthFillResult
import com.rad.shifttracker.listici.ParsedPayslip
import com.rad.shifttracker.listici.applyMonthFill
import com.rad.shifttracker.listici.fillAllMonths
import com.rad.shifttracker.listici.fillableMonths
import com.rad.shifttracker.listici.planMonthFromPayslip
import com.rad.shifttracker.listici.runPayslipSafely
import com.rad.shifttracker.listici.userEnteredDayCount
import com.rad.shifttracker.repo.ShiftRepository
import com.rad.shifttracker.ui.FormErrorText
import com.rad.shifttracker.ui.GlowButton
import com.rad.shifttracker.ui.PopupCard
import com.rad.shifttracker.ui.PopupDialog
import com.rad.shifttracker.ui.RoundedButton
import com.rad.shifttracker.ui.Spacing
import com.rad.shifttracker.ui.extendedColors

internal const val PAYSLIP_MONTH_FILL_ACTION_LABEL = "Popuni iz liste"
internal const val PAYSLIP_MONTH_REPLACE_ACTION_LABEL = "Zameni dane po listi"

internal fun payslipMonthReplaceQuestionText(month: MonthKey, dayCount: Int): String =
    "Zameniti $dayCount ${if (dayCount % 10 == 1 && dayCount % 100 != 11) "upisan dan" else "upisanih dana"} " +
        "u ${monthYearText(month)} po listi?"

/**
 * Dugme u „Listi“: raspored po sačuvanoj listi i šablonu smena upisuje se odmah, bez pregleda.
 * Kad mesec već ima tvojih upisa ([replace]), upisani dani se menjaju tek posle potvrde. Ako raspored
 * ne može da se nađe, mesec ostaje netaknut.
 */
@Composable
internal fun PayslipMonthFillButton(
    repo: ShiftRepository,
    payslip: ParsedPayslip,
    replace: Boolean = false,
    onFilled: (MonthKey) -> Unit
) {
    val scope = rememberCoroutineScope()
    var isWorking by remember { mutableStateOf(false) }
    var confirming by remember { mutableStateOf(false) }
    var error by remember(payslip) { mutableStateOf<String?>(null) }

    fun start() {
        isWorking = true
        error = null
        scope.launch {
            // Solver radi na kopiji podataka; greška ili nedostatak memorije daje poruku, mesec ostaje netaknut.
            val outcome = withContext(Dispatchers.Default) {
                runPayslipSafely { planMonthFromPayslip(repo, payslip) }
            }
            isWorking = false
            when (outcome) {
                is MonthFillResult.Success -> {
                    applyMonthFill(repo, outcome)
                    onFilled(outcome.month)
                }
                is MonthFillResult.Failure -> error = outcome.message
                null -> error = PAYSLIP_UNEXPECTED_ERROR_TEXT
            }
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
            .height(50.dp),
        textStyle = MaterialTheme.typography.labelLarge,
        onClick = {
            if (!isWorking) {
                if (replace) confirming = true else start()
            }
        }
    )
    error?.let { FormErrorText(message = it, style = MaterialTheme.typography.bodySmall, maxLines = 5) }

    if (confirming) {
        val dayCount = remember { userEnteredDayCount(repo, payslip.month) }
        PayslipMonthReplaceDialog(
            month = payslip.month,
            dayCount = dayCount,
            onConfirm = {
                confirming = false
                start()
            },
            onDismiss = { confirming = false }
        )
    }
}

internal const val PAYSLIP_FILL_ALL_LABEL = "Popuni sve po listama"

internal fun fillAllStatusText(result: FillAllResult): String {
    val filled = "Popunjeno: ${result.filled.size}"
    if (result.failed.isEmpty()) return filled
    return "$filled. Nije uspelo: ${result.failed.joinToString { monthYearText(it.first) }}"
}

/**
 * Opcije: popunjava svaki mesec sa sačuvanom listom koji nema tvojih upisa, a časovi se ne slažu.
 * Mesec koji ne može da se složi ostaje netaknut i navodi se u statusu. Dugme se vidi samo dok ima šta da se popuni.
 */
@Composable
internal fun PayslipFillAllButton(repo: ShiftRepository) {
    val scope = rememberCoroutineScope()
    var refresh by remember { mutableStateOf(0) }
    var months by remember { mutableStateOf<List<MonthKey>>(emptyList()) }
    var isWorking by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<FillAllResult?>(null) }

    LaunchedEffect(refresh) {
        months = withContext(Dispatchers.Default) { runPayslipSafely { fillableMonths(repo) } }.orEmpty()
    }
    if (months.isNotEmpty()) {
        RoundedButton(
            text = if (isWorking) "Popunjavam..." else "$PAYSLIP_FILL_ALL_LABEL (${months.size})",
            modifier = Modifier
                .fillMaxWidth()
                .height(UI.buttonHeight.dp),
            bgColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            textColor = MaterialTheme.colorScheme.onSurface,
            borderColor = MaterialTheme.colorScheme.outlineVariant,
            textStyle = MaterialTheme.typography.labelLarge,
            onClick = {
                if (!isWorking) {
                    isWorking = true
                    status = null
                    val pending = months
                    scope.launch {
                        val result = withContext(Dispatchers.Default) { runPayslipSafely { fillAllMonths(repo, pending) } }
                        isWorking = false
                        status = result ?: FillAllResult(emptyList(), pending.map { it to PAYSLIP_UNEXPECTED_ERROR_TEXT })
                        refresh++
                    }
                }
            }
        )
    }
    status?.let { result ->
        ReaderText(fillAllStatusText(result), color = if (result.failed.isEmpty()) MaterialTheme.extendedColors.success else MaterialTheme.extendedColors.warning)
    }
}

@Composable
private fun PayslipMonthReplaceDialog(month: MonthKey, dayCount: Int, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    PopupDialog(
        title = "Zamena dana",
        onDismiss = onDismiss
    ) {
        PopupCard {
            Text(
                text = payslipMonthReplaceQuestionText(month, dayCount),
                color = MaterialTheme.colorScheme.onSurface,
                style = MaterialTheme.typography.bodyMedium
            )
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp),
            horizontalArrangement = Arrangement.spacedBy(Spacing.sm)
        ) {
            RoundedButton(
                text = "Otkaži",
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight(),
                bgColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                textColor = MaterialTheme.colorScheme.onSurface,
                borderColor = MaterialTheme.colorScheme.outlineVariant,
                textStyle = MaterialTheme.typography.labelLarge,
                bold = true,
                onClick = onDismiss
            )
            GlowButton(
                text = "Zameni",
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight(),
                textStyle = MaterialTheme.typography.labelLarge,
                onClick = onConfirm
            )
        }
    }
}
