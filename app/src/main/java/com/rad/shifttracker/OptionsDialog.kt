package com.rad.shifttracker

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/**
 * Opcije: pet grupa (radnje, prikaz, folder sa listićima, otežani uslovi i prevoz, odbici). Dugmad su tiha (tamna pozadina,
 * svetao tekst) — jedino „Google cloud“ ima cijan pozadinu kao glavna radnja.
 */
@Composable
internal fun OptionsDialog(
    repo: ShiftRepository,
    onCloudClick: () -> Unit,
    onMonthlyExportClick: () -> Unit,
    onDebugExportClick: () -> Unit,
    onHolidaysClick: () -> Unit,
    onDismiss: () -> Unit
) {
    val actions = optionsDialogActionLabels()
    val savedMainPayIncludesTransport = rememberRepoValue(repo) { mainPayIncludesTransport }
    var mainPayIncludesTransport by remember(savedMainPayIncludesTransport) {
        mutableStateOf(savedMainPayIncludesTransport)
    }
    val savedAutoAdvanceImportEnabled = rememberRepoValue(repo) { autoAdvanceImportEnabled }
    var autoAdvanceImportEnabled by remember(savedAutoAdvanceImportEnabled) {
        mutableStateOf(savedAutoAdvanceImportEnabled)
    }

    PopupDialog(
        title = "Opcije",
        onDismiss = onDismiss,
        scrollable = true
    ) {
        SectionLabel(text = "Podaci")
        PopupCard {
            OptionActionButton(text = actions[0], primary = true, onClick = onCloudClick)
            if (SHOW_TEST_EXPORT_ACTIONS) {
                OptionActionButton(text = actions[1], onClick = onMonthlyExportClick)
                OptionActionButton(text = actions[2], onClick = onDebugExportClick)
            }
            OptionActionButton(text = actions[3], onClick = onHolidaysClick)
        }

        SectionLabel(text = "Prikaz i obaveštenja")
        PopupCard(spacing = 0.dp) {
            LabeledSwitchRow(
                title = MAIN_PAY_TRANSPORT_LABEL,
                checked = mainPayIncludesTransport,
                contentPadding = OPTION_ROW_PADDING,
                onCheckedChange = { checked ->
                    mainPayIncludesTransport = checked
                    repo.mainPayIncludesTransport = checked
                }
            )
            OptionDivider()
            LabeledSwitchRow(
                title = AUTO_ADVANCE_IMPORT_ENABLED_LABEL,
                checked = autoAdvanceImportEnabled,
                contentPadding = OPTION_ROW_PADDING,
                onCheckedChange = { checked ->
                    autoAdvanceImportEnabled = checked
                    repo.autoAdvanceImportEnabled = checked
                }
            )
        }

        SectionLabel(text = GMAIL_PAYSLIP_SECTION_LABEL)
        PopupCard(spacing = 0.dp) {
            GmailPayslipSettingsSection(repo = repo)
        }

        SectionLabel(text = MANUAL_PAY_SETTINGS_SECTION_LABEL)
        PopupCard(spacing = 0.dp) {
            ManualPaySettingsSection(repo = repo)
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

private val OPTION_ROW_PADDING = PaddingValues(vertical = Spacing.md)

@Composable
private fun OptionDivider() {
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = CARD_BORDER_ALPHA))
}

/**
 * Otežani uslovi rada i prevoz po danu se samo prikazuju: otežane uslove upisuje lista (šifra 21,
 * bez nje 0), a prevoz dnevna provera obračuna prevoza.
 */
@Composable
private fun ManualPaySettingsSection(repo: ShiftRepository) {
    Text(
        text = difficultWorkConditionsStatusText(rememberRepoValue(repo) { difficultWorkConditionsPercentValue }),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        style = MaterialTheme.typography.bodySmall,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.padding(top = Spacing.md, bottom = Spacing.sm)
    )
    // Prevoz po danu se ne kuca: čita se iz najnovijeg obračuna prevoza (~15. u mesecu).
    Text(
        text = transportDailyAmountStatusText(rememberRepoValue(repo) { transportDailyAmountValue }),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        style = MaterialTheme.typography.bodySmall,
        maxLines = 3,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.padding(bottom = Spacing.md)
    )
}

internal fun transportDailyAmountStatusText(daily: Double): String =
    "$SATNICA_TRANSPORT_DAILY_AMOUNT_LABEL: ${formatMoney2(daily)} $CURRENCY_LABEL"

internal fun difficultWorkConditionsStatusText(percent: Double): String =
    if (percent > 0.0) "$DIFFICULT_WORK_CONDITIONS_STATUS_LABEL: ${formatRateInput(percent)}%" else "$DIFFICULT_WORK_CONDITIONS_STATUS_LABEL: nema"

// Export meseca i debug export su samo za testiranje; upali kad zatrebaju.
internal const val SHOW_TEST_EXPORT_ACTIONS = false

internal fun optionsDialogActionLabels(): List<String> = listOf(
    "Google cloud",
    "Export rada i obračuna za izabrani mesec",
    "Debug export za ChatGPT – svi meseci",
    "Praznici"
)
