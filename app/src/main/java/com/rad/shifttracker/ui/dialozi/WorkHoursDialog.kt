package com.rad.shifttracker.ui.dialozi

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import com.rad.shifttracker.obracun.MonthlyPayDetails
import com.rad.shifttracker.obracun.MonthlyReceipt
import com.rad.shifttracker.repo.ShiftRepository
import com.rad.shifttracker.ui.CenteredDialog
import com.rad.shifttracker.ui.Spacing
import com.rad.shifttracker.ui.TOTAL_CARD_UNAVAILABLE_TEXT
import com.rad.shifttracker.ui.extendedColors

/** „Sati rada“: obračun plate prikazanog meseca, ista tabela kao u prozoru plate. */
@Composable
internal fun WorkHoursDialog(
    receipt: MonthlyReceipt,
    payDetails: MonthlyPayDetails,
    repo: ShiftRepository,
    onDismiss: () -> Unit
) {
    CenteredDialog(title = WORK_HOURS_DIALOG_TITLE, onDismiss = onDismiss) {
        if (receipt.isSalaryAvailable) {
            // Tabela je duža od prozora na manjim ekranima, pa se skroluje; dugme „Zatvori“ ostaje vidljivo.
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f, fill = false)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(Spacing.sm)
            ) {
                PayrollMonthTable(monthlyReceipt = receipt, payDetails = payDetails, repo = repo)
            }
        } else {
            Text(
                text = TOTAL_CARD_UNAVAILABLE_TEXT,
                color = MaterialTheme.extendedColors.warning,
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center
            )
        }
    }
}

internal const val WORK_HOURS_DIALOG_TITLE = "Sati rada"
