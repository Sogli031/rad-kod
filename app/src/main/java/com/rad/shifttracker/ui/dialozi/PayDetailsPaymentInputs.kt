package com.rad.shifttracker.ui.dialozi

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.style.TextOverflow
import com.rad.shifttracker.osnova.CONFIG
import com.rad.shifttracker.osnova.formatCurrency
import com.rad.shifttracker.obracun.AdvancePaymentPrediction

/** Ispod reda „Akontacija“: najmanji iznos akontacije po kolektivnom ugovoru za mesec isplate. */
@Composable
internal fun AdvanceMinimumText(advancePrediction: AdvancePaymentPrediction?) {
    if (advancePrediction == null || !advancePrediction.isAvailable) return
    Text(
        text = "Minimalna akontacija: ${formatCurrency(advancePrediction.minimumAdvanceNet)} ${CONFIG.pay.currency}",
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        style = MaterialTheme.typography.bodySmall,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis
    )
}
