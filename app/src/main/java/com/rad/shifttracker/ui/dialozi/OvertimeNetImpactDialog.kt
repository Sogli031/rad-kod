package com.rad.shifttracker.ui.dialozi

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import com.rad.shifttracker.ui.components.AutoSizeText
import com.rad.shifttracker.osnova.formatHours
import com.rad.shifttracker.obracun.MonthlyOvertimeNetImpact
import com.rad.shifttracker.ui.PopupCard
import com.rad.shifttracker.ui.PopupDialog
import com.rad.shifttracker.ui.SectionLabel
import com.rad.shifttracker.ui.bodyMediumEmphasized
import com.rad.shifttracker.ui.extendedColors
import com.rad.shifttracker.ui.headlineMediumEmphasized

@Composable
fun OvertimeNetImpactDialog(
    impact: MonthlyOvertimeNetImpact,
    onDismiss: () -> Unit
) {
    PopupDialog(
        title = "Prekovremeno",
        onDismiss = onDismiss,
        scrollable = true
    ) {
        if (!impact.hasAnyNetEffect) {
            PopupCard {
                Text(
                    text = "Nema prekovremenih sati u ovom mesecu.",
                    color = MaterialTheme.extendedColors.warning,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
            return@PopupDialog
        }

        PopupCard {
            SectionLabel(text = "Dodatna neto zarada")
            AutoSizeText(
                text = signedMoneyText(impact.paidOvertimeNetDifference),
                color = MaterialTheme.extendedColors.weekendOt,
                style = MaterialTheme.typography.headlineMediumEmphasized,
                maxFontSize = MaterialTheme.typography.headlineMedium.fontSize,
                minFontSize = MaterialTheme.typography.titleMedium.fontSize,
                maxLines = 1,
                modifier = Modifier.fillMaxWidth()
            )
            Text(
                text = "Za ${formatHours(impact.overtimeHours)} prekovremenog rada.",
                color = MaterialTheme.extendedColors.textSecondary,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            OvertimeImpactMoneyRow(
                label = "Vraćeno kroz Za sate",
                value = signedMoneyText(impact.compTimeRecoveryNetEffect),
                color = if (impact.compTimeRecoveryNetEffect > 0.0) MaterialTheme.extendedColors.compTime else MaterialTheme.colorScheme.onSurfaceVariant,
                visible = impact.compTimeRecoveryNetEffect > 0.0
            )
            OvertimeImpactMoneyRow(
                label = "Gubitak zbog manjka sati",
                value = "-${moneyText(impact.deficitNetLoss)}",
                color = MaterialTheme.colorScheme.error,
                visible = impact.deficitNetLoss > 0.0
            )
            OvertimeImpactMoneyRow(
                label = "Konačna razlika",
                value = signedMoneyText(impact.netDifference),
                color = if (impact.netDifference >= 0.0) MaterialTheme.extendedColors.success else MaterialTheme.colorScheme.error,
                visible = impact.deficitNetLoss > 0.0 || impact.compTimeRecoveryNetEffect > 0.0
            )
            Text(
                text = "Razlika u odnosu na isti mesec bez tih sati.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

private val MonthlyOvertimeNetImpact.hasAnyNetEffect: Boolean
    get() = paidOvertimeNetDifference > 0.0 || compTimeRecoveryNetEffect > 0.0 || deficitNetLoss > 0.0

@Composable
private fun OvertimeImpactMoneyRow(
    label: String,
    value: String,
    color: Color,
    visible: Boolean
) {
    if (!visible) return
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        AutoSizeText(
            text = label,
            color = MaterialTheme.extendedColors.textSecondary,
            style = MaterialTheme.typography.bodyMedium,
            maxFontSize = MaterialTheme.typography.bodyMedium.fontSize,
            minFontSize = MaterialTheme.typography.bodySmall.fontSize,
            maxLines = 1,
            modifier = Modifier.weight(1.15f)
        )
        Text(
            text = value,
            color = color,
            style = MaterialTheme.typography.bodyMediumEmphasized.copy(
                textAlign = TextAlign.End
            ),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(0.85f)
        )
    }
}
