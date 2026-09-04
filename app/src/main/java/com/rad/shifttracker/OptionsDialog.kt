package com.rad.shifttracker

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

@Composable
internal fun OptionsDialog(
    repo: ShiftRepository,
    onCloudClick: () -> Unit,
    onMonthlyExportClick: () -> Unit,
    onDebugExportClick: () -> Unit,
    onHolidaysClick: () -> Unit,
    onNotificationAccessClick: () -> Unit,
    foodReceiptReminderEnabled: Boolean,
    foodReceiptReminderStatus: FoodReceiptReminderScheduleStatus,
    onFoodReceiptReminderChange: (Boolean) -> Unit,
    onDismiss: () -> Unit
) {
    PopupDialog(
        title = "Opcije",
        onDismiss = onDismiss,
        widthRatio = 0.85f,
        scrollable = true,
        accent = THEME.accentSecondary
    ) {
        PopupCard(bgColor = THEME.bgCardElevated, borderColor = THEME.accentSecondary) {
            val actions = optionsDialogActionLabels()
            RoundedButton(
                text = actions[0],
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp),
                bgColor = THEME.accentPrimary,
                textColor = THEME.textDark,
                radius = 14.dp,
                textStyle = MaterialTheme.typography.labelLarge,
                bold = true,
                onClick = onCloudClick
            )
            RoundedButton(
                text = actions[1],
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp),
                bgColor = THEME.info,
                textColor = THEME.textDark,
                radius = 14.dp,
                textStyle = MaterialTheme.typography.labelLarge,
                bold = true,
                onClick = onMonthlyExportClick
            )
            RoundedButton(
                text = actions[2],
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp),
                bgColor = THEME.accentSecondary,
                textColor = THEME.textDark,
                radius = 14.dp,
                textStyle = MaterialTheme.typography.labelLarge,
                bold = true,
                onClick = onDebugExportClick
            )
            RoundedButton(
                text = actions[3],
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp),
                bgColor = THEME.danger,
                textColor = THEME.textPrimary,
                radius = 14.dp,
                textStyle = MaterialTheme.typography.labelLarge,
                bold = true,
                onClick = onHolidaysClick
            )
            var mainPayIncludesTransport by remember(repo.mainPayIncludesTransport) {
                mutableStateOf(repo.mainPayIncludesTransport)
            }
            MainPayTransportSwitch(
                enabled = mainPayIncludesTransport,
                onCheckedChange = { checked ->
                    mainPayIncludesTransport = checked
                    repo.mainPayIncludesTransport = checked
                }
            )
            var autoAdvanceImportEnabled by remember(repo.autoAdvanceImportEnabled) {
                mutableStateOf(repo.autoAdvanceImportEnabled)
            }
            AutoAdvanceImportSwitch(
                enabled = autoAdvanceImportEnabled,
                onCheckedChange = { checked ->
                    autoAdvanceImportEnabled = checked
                    repo.autoAdvanceImportEnabled = checked
                }
            )
            if (autoAdvanceImportEnabled) {
                RoundedButton(
                    text = actions[4],
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp),
                    bgColor = THEME.warning,
                    textColor = THEME.textDark,
                    radius = 14.dp,
                    textStyle = MaterialTheme.typography.labelLarge,
                    bold = true,
                    onClick = onNotificationAccessClick
                )
            }
            FoodReceiptReminderSwitch(
                enabled = foodReceiptReminderEnabled,
                status = foodReceiptReminderStatus,
                onCheckedChange = onFoodReceiptReminderChange
            )
            CreditDeductionSettingsSection(repo = repo)
            UnionDuesSettingsSection(repo = repo)
        }
    }
}

@Composable
private fun CreditDeductionSettingsSection(repo: ShiftRepository) {
    val settings = repo.getCreditDeductionSettings()
    var enabled by remember(settings.creditDeductionEnabled) {
        mutableStateOf(settings.creditDeductionEnabled)
    }
    var amountInput by remember(settings.monthlyCreditDeductionAmount) {
        mutableStateOf(formatMoney2(settings.monthlyCreditDeductionAmount))
    }
    var amountError by remember { mutableStateOf<String?>(null) }

    SectionLabel(text = CREDIT_DEDUCTION_SECTION_LABEL)
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            text = CREDIT_DEDUCTION_ENABLED_LABEL,
            color = THEME.textPrimary,
            style = MaterialTheme.typography.labelLarge,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        Switch(
            checked = enabled,
            onCheckedChange = { checked ->
                enabled = checked
                amountError = null
                repo.setCreditDeductionSettings(
                    enabled = checked,
                    monthlyAmount = parseCreditDeductionAmountInput(amountInput)
                        ?: settings.monthlyCreditDeductionAmount
                )
            },
            colors = SwitchDefaults.colors(
                checkedThumbColor = THEME.textDark,
                checkedTrackColor = THEME.accentPrimary,
                uncheckedThumbColor = THEME.textMuted,
                uncheckedTrackColor = THEME.bgCard
            )
        )
    }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            text = MONTHLY_CREDIT_DEDUCTION_AMOUNT_LABEL,
            color = THEME.textSecondary,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(UI.defaultSpacing.dp)
        ) {
            ModernTextField(
                value = amountInput,
                onValueChange = {
                    amountInput = filterDecimalInput(it)
                    amountError = null
                },
                hintText = "0.00",
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier.weight(0.55f)
            )
            Text(
                text = CREDIT_DEDUCTION_CURRENCY_LABEL,
                color = THEME.textSecondary,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            RoundedButton(
                text = ADVANCE_PAYMENT_SAVE_LABEL,
                modifier = Modifier.weight(0.35f).height(56.dp),
                bgColor = THEME.accentPrimary,
                textColor = THEME.textDark,
                textStyle = MaterialTheme.typography.labelMedium,
                onClick = {
                    val parsedAmount = parseCreditDeductionAmountInput(amountInput)
                    if (parsedAmount == null) {
                        amountError = "Unesi ispravan iznos rate kredita."
                        return@RoundedButton
                    }
                    repo.setCreditDeductionSettings(
                        enabled = enabled,
                        monthlyAmount = parsedAmount
                    )
                    amountInput = formatMoney2(parsedAmount)
                    amountError = null
                }
            )
        }
        amountError?.let { message ->
            Text(
                text = message,
                color = THEME.danger,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

private fun parseCreditDeductionAmountInput(input: String): Double? {
    if (input.isBlank()) return 0.0
    val value = input.toDoubleOrNull() ?: return null
    return if (value.isFinite() && value >= 0.0) value else null
}

@Composable
private fun UnionDuesSettingsSection(repo: ShiftRepository) {
    val settings = repo.getUnionDuesSettings()
    var enabled by remember(settings.unionMember) { mutableStateOf(settings.unionMember) }
    var rateInput by remember(settings.unionDuesRate) {
        mutableStateOf(formatUnionDuesPercent(settings.unionDuesRate))
    }
    var rateError by remember { mutableStateOf<String?>(null) }

    SectionLabel(text = UNION_DUES_SECTION_LABEL)
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            text = UNION_DUES_ENABLED_LABEL,
            color = THEME.textPrimary,
            style = MaterialTheme.typography.labelLarge,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        Switch(
            checked = enabled,
            onCheckedChange = { checked ->
                enabled = checked
                rateError = null
                repo.setUnionDuesSettings(
                    unionMember = checked,
                    unionDuesRate = parseUnionDuesPercentInput(rateInput) ?: settings.unionDuesRate
                )
            },
            colors = SwitchDefaults.colors(
                checkedThumbColor = THEME.textDark,
                checkedTrackColor = THEME.accentPrimary,
                uncheckedThumbColor = THEME.textMuted,
                uncheckedTrackColor = THEME.bgCard
            )
        )
    }
    if (enabled) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                text = UNION_DUES_RATE_LABEL,
                color = THEME.textSecondary,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(UI.defaultSpacing.dp)
            ) {
                ModernTextField(
                    value = rateInput,
                    onValueChange = {
                        rateInput = filterDecimalInput(it)
                        rateError = null
                    },
                    hintText = "1.5",
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.weight(0.55f)
                )
                Text(
                    text = "%",
                    color = THEME.textSecondary,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                RoundedButton(
                    text = ADVANCE_PAYMENT_SAVE_LABEL,
                    modifier = Modifier.weight(0.35f).height(56.dp),
                    bgColor = THEME.accentPrimary,
                    textColor = THEME.textDark,
                    textStyle = MaterialTheme.typography.labelMedium,
                    onClick = {
                        val parsedRate = parseUnionDuesPercentInput(rateInput)
                        if (parsedRate == null) {
                            rateError = "Unesi ispravnu stopu članarine."
                            return@RoundedButton
                        }
                        repo.setUnionDuesSettings(unionMember = enabled, unionDuesRate = parsedRate)
                        rateInput = formatUnionDuesPercent(parsedRate)
                        rateError = null
                    }
                )
            }
            rateError?.let { message ->
                Text(
                    text = message,
                    color = THEME.danger,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

private fun formatUnionDuesPercent(rate: Double): String {
    return formatMoney2(rate * 100.0)
}

private fun parseUnionDuesPercentInput(input: String): Double? {
    if (input.isBlank()) return null
    val percent = input.toDoubleOrNull() ?: return null
    if (!percent.isFinite() || percent <= 0.0 || percent > 100.0) return null
    return percent / 100.0
}

@Composable
private fun MainPayTransportSwitch(
    enabled: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    OptionToggleRow(
        sectionLabel = MAIN_PAY_TRANSPORT_SECTION_LABEL,
        title = MAIN_PAY_TRANSPORT_LABEL,
        hint = if (enabled) MAIN_PAY_TRANSPORT_ENABLED_HINT else MAIN_PAY_TRANSPORT_DISABLED_HINT,
        enabled = enabled,
        onCheckedChange = onCheckedChange
    )
}

@Composable
private fun AutoAdvanceImportSwitch(
    enabled: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    OptionToggleRow(
        sectionLabel = AUTO_ADVANCE_IMPORT_SECTION_LABEL,
        title = AUTO_ADVANCE_IMPORT_ENABLED_LABEL,
        hint = if (enabled) AUTO_ADVANCE_IMPORT_ENABLED_HINT else AUTO_ADVANCE_IMPORT_DISABLED_HINT,
        enabled = enabled,
        onCheckedChange = onCheckedChange
    )
}

@Composable
private fun OptionToggleRow(
    sectionLabel: String,
    title: String,
    hint: String,
    enabled: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    SectionLabel(text = sectionLabel)
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(
                text = title,
                color = THEME.textPrimary,
                style = MaterialTheme.typography.labelLarge,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = hint,
                color = THEME.textSecondary,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 4,
                overflow = TextOverflow.Ellipsis
            )
        }
        Switch(
            checked = enabled,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = THEME.textDark,
                checkedTrackColor = THEME.accentPrimary,
                uncheckedThumbColor = THEME.textMuted,
                uncheckedTrackColor = THEME.bgCard
            )
        )
    }
}

@Composable
private fun FoodReceiptReminderSwitch(
    enabled: Boolean,
    status: FoodReceiptReminderScheduleStatus,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(
                text = FOOD_RECEIPT_REMINDER_OPTION_LABEL,
                color = THEME.textPrimary,
                style = MaterialTheme.typography.labelLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = foodReceiptReminderStatusText(status),
                color = THEME.textSecondary,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 4,
                overflow = TextOverflow.Ellipsis
            )
        }
        Switch(
            checked = enabled,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = THEME.textDark,
                checkedTrackColor = THEME.accentPrimary,
                uncheckedThumbColor = THEME.textMuted,
                uncheckedTrackColor = THEME.bgCard
            )
        )
    }
}

internal fun optionsDialogActionLabels(): List<String> = listOf(
    "Google cloud",
    "Export rada i obračuna za izabrani mesec",
    "Debug export za ChatGPT – svi meseci",
    "Praznici",
    "Pristup obaveštenjima"
)
