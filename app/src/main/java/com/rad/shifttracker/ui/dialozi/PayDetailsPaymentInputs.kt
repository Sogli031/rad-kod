package com.rad.shifttracker.ui.dialozi

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.style.TextOverflow
import com.rad.shifttracker.osnova.CONFIG
import com.rad.shifttracker.osnova.formatCurrency
import com.rad.shifttracker.obracun.MonthlyReceipt
import com.rad.shifttracker.repo.ShiftRepository
import com.rad.shifttracker.repo.getAdvancePaymentPrediction
import com.rad.shifttracker.ui.rememberRepoValue

/** Ispod reda „Akontacija“: najmanji iznos akontacije po kolektivnom ugovoru za mesec isplate. */
@Composable
internal fun AdvanceMinimumText(
    monthlyReceipt: MonthlyReceipt,
    repo: ShiftRepository
) {
    val paymentMonth = monthlyReceipt.paymentMonth
    val advancePrediction = rememberRepoValue(repo, paymentMonth) { getAdvancePaymentPrediction(paymentMonth.year, paymentMonth.month) }
    if (!advancePrediction.isAvailable) return
    Text(
        text = "Minimalna akontacija: ${formatCurrency(advancePrediction.minimumAdvanceNet)} ${CONFIG.pay.currency}",
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        style = MaterialTheme.typography.bodySmall,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis
    )
}
