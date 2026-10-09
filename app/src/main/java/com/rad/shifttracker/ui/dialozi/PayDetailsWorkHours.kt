package com.rad.shifttracker.ui.dialozi

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import com.rad.shifttracker.osnova.formatHoursAndMinutes
import com.rad.shifttracker.ui.PopupCard
import com.rad.shifttracker.ui.SectionLabel
import com.rad.shifttracker.ui.Spacing
import com.rad.shifttracker.ui.bodyMediumEmphasized
import com.rad.shifttracker.ui.components.AutoSizeText
import com.rad.shifttracker.ui.extendedColors

private const val TABLE_LABEL_WEIGHT = 1.6f
private const val TABLE_HOURS_WEIGHT = 0.45f
private const val TABLE_AMOUNT_WEIGHT = 1.1f

/** „Obračun plate za [mesec]“: tabela koja se sabira u neto platu, pa izostanci koji nisu plaćeni. */
@Composable
internal fun PayrollTableCards(title: String, table: PayrollTable) {
    PopupCard {
        SectionLabel(text = title)
        PayrollTableRow(label = "", hours = "Sati", amount = "Iznos", color = MaterialTheme.colorScheme.onSurfaceVariant, header = true)
        PayrollGroup("Rad", table.work)
        PayrollGroup("Naknade", table.compensation)
        PayrollGroup("Ostalo", table.other)
        if (table.showRounding) {
            PayrollTableRow(
                label = "Zaokruženje poreza",
                hours = "",
                amount = signedSrMoney(table.rounding),
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        PayrollTableRow(
            label = "= Neto plata",
            hours = hoursText(table.totalMinutes),
            amount = srMoney(table.netSalary),
            color = MaterialTheme.colorScheme.primary,
            bold = true
        )
    }
    table.notPaid?.let { row ->
        PopupCard {
            SectionLabel(text = "Nije plaćeno")
            PayrollTableRow(
                label = row.label,
                hours = row.minutes?.let(::hoursText).orEmpty(),
                amount = "≈ ${srMoney(row.amount)}",
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            NoteText("Samo informacija — ti sati su već izostavljeni iz plate gore.", MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun PayrollGroup(title: String, rows: List<PayrollRow>) {
    if (rows.isEmpty()) return
    Text(
        text = title,
        color = MaterialTheme.colorScheme.primary,
        style = MaterialTheme.typography.labelMedium,
        modifier = Modifier.padding(top = Spacing.xs)
    )
    rows.forEach { row ->
        PayrollTableRow(
            label = row.label,
            hours = row.minutes?.let(::hoursText).orEmpty(),
            amount = if (row.amount < 0.0) signedSrMoney(row.amount) else srMoney(row.amount),
            color = row.tone.color
        )
        row.note?.let { NoteText(it, row.noteTone.color, indent = true) }
    }
}

@Composable
private fun PayrollTableRow(
    label: String,
    hours: String,
    amount: String,
    color: Color,
    bold: Boolean = false,
    header: Boolean = false
) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        TableText(
            text = label,
            color = if (bold) MaterialTheme.colorScheme.onSurface else MaterialTheme.extendedColors.textSecondary,
            emphasized = bold,
            textAlign = TextAlign.Start,
            weight = TABLE_LABEL_WEIGHT
        )
        TableText(
            text = hours,
            color = if (header) color else MaterialTheme.colorScheme.onSurfaceVariant,
            emphasized = bold || header,
            textAlign = TextAlign.End,
            weight = TABLE_HOURS_WEIGHT
        )
        TableText(
            text = amount,
            color = color,
            emphasized = true,
            textAlign = TextAlign.End,
            weight = TABLE_AMOUNT_WEIGHT
        )
    }
}

@Composable
private fun RowScope.TableText(text: String, color: Color, emphasized: Boolean, textAlign: TextAlign, weight: Float) {
    AutoSizeText(
        text = text,
        color = color,
        style = (if (emphasized) MaterialTheme.typography.bodyMediumEmphasized else MaterialTheme.typography.bodyMedium)
            .copy(textAlign = textAlign),
        maxFontSize = MaterialTheme.typography.bodyMedium.fontSize,
        minFontSize = MaterialTheme.typography.labelSmall.fontSize,
        maxLines = 1,
        modifier = Modifier.weight(weight)
    )
}

@Composable
private fun NoteText(text: String, color: Color, indent: Boolean = false) {
    Text(
        text = text,
        color = color,
        style = MaterialTheme.typography.bodySmall,
        modifier = if (indent) Modifier.padding(start = Spacing.md) else Modifier
    )
}

private fun hoursText(minutes: Int): String = formatHoursAndMinutes(minutes.coerceAtLeast(0))

private val PayrollTone.color: Color
    @Composable
    @ReadOnlyComposable
    get() = when (this) {
        PayrollTone.Plain -> MaterialTheme.colorScheme.onSurface
        PayrollTone.Positive -> MaterialTheme.extendedColors.success
        PayrollTone.Negative -> MaterialTheme.colorScheme.error
        PayrollTone.Muted -> MaterialTheme.colorScheme.onSurfaceVariant
    }
