package com.rad.shifttracker.ui.dialozi

import com.rad.shifttracker.ui.DataActions
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.rad.shifttracker.repo.mainPaySalaryOnly
import com.rad.shifttracker.ui.LabeledSwitchRow
import com.rad.shifttracker.ui.Spacing
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.rad.shifttracker.osnova.UI
import com.rad.shifttracker.listici.GMAIL_PAYSLIP_SECTION_LABEL
import com.rad.shifttracker.repo.ShiftRepository
import com.rad.shifttracker.repo.difficultWorkConditionsPercentValue
import com.rad.shifttracker.ui.rememberRepoValue
import com.rad.shifttracker.ui.PopupCard
import com.rad.shifttracker.ui.PopupDialog
import com.rad.shifttracker.ui.RoundedButton
import com.rad.shifttracker.ui.SectionLabel

/**
 * Opcije: podaci, liste iz Gmail-a i otežani uslovi (samo kad ih ima). Dugmad su tiha (tamna pozadina,
 * svetao tekst) — jedino „Google cloud“ ima cijan pozadinu kao glavna radnja.
 */
@Composable
internal fun OptionsDialog(
    repo: ShiftRepository,
    actions: DataActions,
    onCloudClick: () -> Unit,
    onMonthlyExportClick: () -> Unit,
    onDebugExportClick: () -> Unit,
    onDismiss: () -> Unit
) {
    val actionLabels = optionsDialogActionLabels()
    val savedWithExtras = rememberRepoValue(repo) { !mainPaySalaryOnly }
    var mainPayWithExtras by remember(savedWithExtras) { mutableStateOf(savedWithExtras) }

    PopupDialog(
        title = "Opcije",
        onDismiss = onDismiss,
        scrollable = true
    ) {
        SectionLabel(text = "Podaci")
        PopupCard {
            OptionActionButton(text = actionLabels[0], primary = true, onClick = onCloudClick)
            if (SHOW_TEST_EXPORT_ACTIONS) {
                OptionActionButton(text = actionLabels[1], onClick = onMonthlyExportClick)
                OptionActionButton(text = actionLabels[2], onClick = onDebugExportClick)
            }
            UpdateCheckButton()
        }

        SectionLabel(text = "Prikaz")
        PopupCard(spacing = 0.dp) {
            LabeledSwitchRow(
                title = MAIN_PAY_WITH_EXTRAS_LABEL,
                checked = mainPayWithExtras,
                contentPadding = PaddingValues(vertical = Spacing.md),
                onCheckedChange = { checked ->
                    mainPayWithExtras = checked
                    actions.setMainPayWithExtras(checked)
                }
            )
        }

        SectionLabel(text = GMAIL_PAYSLIP_SECTION_LABEL)
        PopupCard(spacing = 0.dp) {
            GmailPayslipSettingsSection(repo = repo, actions = actions)
        }

        // Otežani uslovi (šifra 21 sa liste) se vide samo kad ih ima; prevoz je u stavci „Prevoz“ ispod kalendara.
        val difficultPercent = rememberRepoValue(repo) { difficultWorkConditionsPercentValue }
        difficultWorkConditionsStatusText(difficultPercent)?.let { status ->
            SectionLabel(text = DIFFICULT_WORK_CONDITIONS_SECTION_LABEL)
            PopupCard {
                Text(
                    text = status,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
private fun OptionActionButton(
    text: String,
    modifier: Modifier = Modifier,
    primary: Boolean = false,
    onClick: () -> Unit
) {
    RoundedButton(
        text = text,
        modifier = modifier
            .fillMaxWidth()
            .height(UI.buttonHeight.dp),
        bgColor = if (primary) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHigh,
        textColor = if (primary) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
        borderColor = if (primary) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
        textStyle = MaterialTheme.typography.labelLarge,
        bold = primary,
        onClick = onClick
    )
}


/** Otežani uslovi sa liste (šifra 21); null kad ih nema, pa se sekcija ne prikazuje. */
internal fun difficultWorkConditionsStatusText(percent: Double): String? =
    if (percent > 0.0) "$DIFFICULT_WORK_CONDITIONS_STATUS_LABEL: ${formatRateInput(percent)}%" else null

// Export meseca i debug export su samo za testiranje; upali kad zatrebaju.
internal const val SHOW_TEST_EXPORT_ACTIONS = false

internal fun optionsDialogActionLabels(): List<String> = listOf(
    "Google cloud",
    "Export rada i obračuna za izabrani mesec",
    "Debug export za ChatGPT – svi meseci"
)
