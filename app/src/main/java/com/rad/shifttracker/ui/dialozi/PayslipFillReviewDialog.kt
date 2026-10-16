package com.rad.shifttracker.ui.dialozi

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.rad.shifttracker.listici.FillDay
import com.rad.shifttracker.listici.FillDayKind
import com.rad.shifttracker.osnova.UI
import com.rad.shifttracker.smene.MonthKey
import com.rad.shifttracker.smene.monthYearText
import com.rad.shifttracker.ui.GlowButton
import com.rad.shifttracker.ui.PopupCard
import com.rad.shifttracker.ui.PopupDialog
import com.rad.shifttracker.ui.RoundedButton
import com.rad.shifttracker.ui.Spacing
import com.rad.shifttracker.ui.extendedColors
import com.rad.shifttracker.uvoz.FillSelection
import com.rad.shifttracker.uvoz.PayslipFillProposal

@Composable
internal fun PayslipFillReviewDialog(
    proposals: List<PayslipFillProposal>,
    failures: List<Pair<MonthKey, String>>,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    PopupDialog(title = "Predlog prema listi", onDismiss = onDismiss, footer = {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            RoundedButton(
                text = "Otkaži",
                modifier = Modifier.weight(1f).heightIn(min = UI.buttonHeight.dp),
                bgColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                textColor = MaterialTheme.colorScheme.onSurface,
                borderColor = MaterialTheme.colorScheme.outlineVariant,
                textStyle = MaterialTheme.typography.labelLarge,
                onClick = onDismiss
            )
            GlowButton(
                text = "Upiši",
                modifier = Modifier.weight(1f).heightIn(min = UI.buttonHeight.dp),
                textStyle = MaterialTheme.typography.labelLarge,
                onClick = onConfirm
            )
        }
    }) {
        for (proposal in proposals) {
            PopupCard {
                Text(monthYearText(proposal.result.month), style = MaterialTheme.typography.titleMedium)
                ReaderText("Procenjeni datumi")
                ReaderText(fillSelectionStatus(proposal))
                if (proposal.result.existingRecordCount > 0) {
                    ReaderText("Zamena upisanih dana: ${proposal.result.existingRecordCount}", MaterialTheme.extendedColors.warning)
                }
                val mismatches = proposal.result.reconciliation.rows.filter { it.isHours && !it.matches }
                ReaderText(
                    if (mismatches.isEmpty()) "Sati se slažu sa listom" else "Odstupanja u satima: ${mismatches.size}",
                    if (mismatches.isEmpty()) MaterialTheme.extendedColors.success else MaterialTheme.extendedColors.warning
                )
                mismatches.forEach { row ->
                    ReaderText("${row.label}: ${row.appText} / lista ${row.payslipText}", MaterialTheme.extendedColors.warning)
                }
            }
            PopupCard {
                proposal.result.days.forEach { day -> ReaderText(fillDayReviewText(day)) }
            }
        }
        failures.forEach { (month, message) ->
            PopupCard { ReaderText("${monthYearText(month)}: $message", MaterialTheme.extendedColors.warning) }
        }
    }
}

internal fun fillSelectionStatus(proposal: PayslipFillProposal): String = when (proposal.selection) {
    FillSelection.AI -> "AI predlog"
    FillSelection.LOCAL -> "Lokalni predlog"
    FillSelection.FALLBACK -> "AI nije dostupan · lokalni predlog"
}

internal fun fillDayReviewText(day: FillDay): String {
    val label = when (day.kind) {
        FillDayKind.WORK, FillDayKind.HOLIDAY_WORK, FillDayKind.WEEKEND_WORK -> day.shift?.fullName ?: "Rad"
        FillDayKind.VACATION -> "Godišnji"
        FillDayKind.PAID_LEAVE -> "Plaćeno odsustvo"
        FillDayKind.SICK -> "Bolovanje"
        FillDayKind.ABSENT -> "Izostanak"
        FillDayKind.HOLIDAY_OFF -> "Plaćeni praznik"
        FillDayKind.CYCLE_OFF -> "Slobodan dan"
    }
    return "${day.date.dayOfMonth}.${day.date.monthValue}. · $label" + if (day.hours > 0) " · ${day.hours} h" else ""
}
