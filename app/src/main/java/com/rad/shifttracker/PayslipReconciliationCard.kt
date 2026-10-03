package com.rad.shifttracker

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow

/** Neutralna napomena ispod tabele: odakle razlika u zbirovima (stimulacije, KIRU...), nije neslaganje. */
internal const val PAYSLIP_TOTALS_NOTE_TITLE =
    "Razlika u bruto/neto, samo informativno (stavke koje aplikacija ne prati):"

@Composable
internal fun PayslipReconciliationCard(reconciliation: PayslipReconciliation) {
    PopupCard {
        Text(
            text = monthYearText(reconciliation.month),
            color = THEME.textPrimary,
            style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold),
            maxLines = 1
        )
        if (!reconciliation.hasAppData) {
            PayslipMutedText("U aplikaciji nema unetih smena za ovaj mesec, pa poređenje nije merodavno.", THEME.warning)
        }
        Row(modifier = Modifier.fillMaxWidth()) {
            PayslipCell("", Modifier.weight(1.4f))
            PayslipCell("Aplikacija", Modifier.weight(1f), THEME.textMuted)
            PayslipCell("Lista", Modifier.weight(1f), THEME.textMuted)
        }
        for (row in reconciliation.rows) {
            Row(modifier = Modifier.fillMaxWidth()) {
                PayslipCell(
                    // Zbirovi su samo za pregled: bez ✓/✗ i bez boje upozorenja.
                    text = when {
                        row.isTotal -> row.label
                        row.matches -> "✓ ${row.label}"
                        else -> "✗ ${row.label}"
                    },
                    modifier = Modifier.weight(1.4f),
                    color = when {
                        row.isTotal -> THEME.textMuted
                        row.tone == PayslipRowTone.ALERT -> THEME.danger
                        row.matches -> THEME.textSecondary
                        else -> THEME.warning
                    }
                )
                PayslipCell(row.appText, Modifier.weight(1f))
                PayslipCell(row.payslipText, Modifier.weight(1f))
            }
        }
        val (notes, mismatches) = reconciliation.differences.partition { it.isNote }
        if (mismatches.isNotEmpty()) {
            PayslipMutedText("Ne slaže se:", color = THEME.warning)
            mismatches.forEachIndexed { index, item ->
                PayslipMutedText(
                    text = "${index + 1}. ${item.text}",
                    color = if (item.isAlert) THEME.danger else THEME.warning,
                    maxLines = Int.MAX_VALUE
                )
            }
        }
        if (notes.isNotEmpty()) {
            PayslipMutedText(PAYSLIP_TOTALS_NOTE_TITLE)
            notes.forEach { item ->
                PayslipMutedText(text = "• ${item.text}", maxLines = Int.MAX_VALUE)
            }
        }
    }
}

@Composable
private fun PayslipCell(text: String, modifier: Modifier, color: Color = THEME.textPrimary) {
    Text(
        text = text,
        color = color,
        style = MaterialTheme.typography.bodySmall,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier
    )
}

@Composable
private fun PayslipMutedText(text: String, color: Color = THEME.textMuted, maxLines: Int = 5) {
    Text(
        text = text,
        color = color,
        style = MaterialTheme.typography.bodySmall,
        maxLines = maxLines,
        overflow = TextOverflow.Ellipsis
    )
}
