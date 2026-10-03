package com.rad.shifttracker

import androidx.compose.ui.text.input.ImeAction
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.rad.shifttracker.ui.components.AutoSizeText
import java.util.Locale
import kotlin.math.roundToInt

@Composable
fun MinuliRadDialog(
    year: Int,
    month: Int,
    repo: ShiftRepository,
    onDismiss: () -> Unit
) {
    val currYears = repo.getSummary(year, month).yearsOfService
    val currPercent = currYears * seniorityRatePercent()
    var selectedMode by remember { mutableStateOf(repo.seniorityInputModeValue) }
    var startYearInput by remember {
        mutableStateOf(if (repo.minuliRadStartYearValue > 0) repo.minuliRadStartYearValue.toString() else "")
    }
    var selectedStartMonth by remember { mutableIntStateOf(repo.minuliRadStartMonthValue) }
    var benefitEnabled by remember { mutableStateOf(repo.minuliRadBenefitEnabledValue) }
    var selectedIncreaseFactor by remember { mutableStateOf(repo.minuliRadIncreaseFactorValue) }
    var lastPayslipYearInput by remember {
        val savedYear = repo.minuliRadLastPayslipYearValue.takeIf { it > 0 } ?: year
        mutableStateOf(savedYear.toString())
    }
    var selectedLastPayslipMonth by remember {
        mutableIntStateOf(repo.minuliRadLastPayslipMonthValue.takeIf { it in 1..12 } ?: month)
    }
    var payslipPercentInput by remember {
        mutableStateOf(
            repo.minuliRadPayslipPercentValue
                .takeIf { it > 0.0 }
                ?.let(::formatRateInput)
                .orEmpty()
        )
    }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    val knownPreview by remember {
        derivedStateOf {
            calculateKnownStartSeniorityPreview(
                startYear = startYearInput.toIntOrNull() ?: 0,
                startMonth = selectedStartMonth,
                targetYear = year,
                targetMonth = month,
                increaseFactor = if (benefitEnabled) selectedIncreaseFactor else SeniorityIncreaseFactor.NONE,
                ratePerYear = CONFIG.pay.minuliRadPerYear
            )
        }
    }
    val payslipEstimate by remember {
        derivedStateOf {
            estimateSeniorityFromPayslipPercent(
                lastPayslipYear = lastPayslipYearInput.toIntOrNull() ?: 0,
                lastPayslipMonth = selectedLastPayslipMonth,
                payslipPercent = payslipPercentInput.toDoubleOrNull() ?: 0.0,
                increaseFactor = selectedIncreaseFactor,
                ratePerYear = CONFIG.pay.minuliRadPerYear
            )
        }
    }

    PopupDialog(
        title = "Minuli rad",
        onDismiss = onDismiss,
        scrollable = true
    ) {
        CurrentSeniorityCard(currYears = currYears, currPercent = currPercent)
        SeniorityInfoCard()
        SeniorityModeSelector(selectedMode = selectedMode, onModeChange = { selectedMode = it })
        when (selectedMode) {
            SeniorityInputMode.KNOWN_START -> KnownStartSection(
                startYearInput = startYearInput,
                selectedStartMonth = selectedStartMonth,
                benefitEnabled = benefitEnabled,
                selectedIncreaseFactor = selectedIncreaseFactor,
                preview = knownPreview,
                onStartYearChange = {
                    startYearInput = it.filter(Char::isDigit).take(4)
                    errorMessage = null
                },
                onStartMonthChange = { selectedStartMonth = it },
                onBenefitChange = { benefitEnabled = it },
                onIncreaseFactorChange = { selectedIncreaseFactor = it }
            )

            SeniorityInputMode.PAYSLIP_PERCENT -> PayslipPercentSection(
                lastPayslipYearInput = lastPayslipYearInput,
                selectedLastPayslipMonth = selectedLastPayslipMonth,
                payslipPercentInput = payslipPercentInput,
                selectedIncreaseFactor = selectedIncreaseFactor,
                estimate = payslipEstimate,
                onLastPayslipYearChange = {
                    lastPayslipYearInput = it.filter(Char::isDigit).take(4)
                    errorMessage = null
                },
                onLastPayslipMonthChange = { selectedLastPayslipMonth = it },
                onPayslipPercentChange = {
                    payslipPercentInput = filterDecimalInput(it).take(6)
                    errorMessage = null
                },
                onIncreaseFactorChange = { selectedIncreaseFactor = it }
            )
        }
        errorMessage?.let { message ->
            FormErrorText(message = message)
        }
        GlowButton(
            text = "Sačuvaj",
            modifier = Modifier
                .fillMaxWidth()
                .height(50.dp),
            textStyle = MaterialTheme.typography.labelLarge,
            onClick = {
                val validationError = saveSenioritySettings(
                    repo = repo,
                    selectedMode = selectedMode,
                    startYearInput = startYearInput,
                    selectedStartMonth = selectedStartMonth,
                    benefitEnabled = benefitEnabled,
                    selectedIncreaseFactor = selectedIncreaseFactor,
                    lastPayslipYearInput = lastPayslipYearInput,
                    selectedLastPayslipMonth = selectedLastPayslipMonth,
                    payslipPercentInput = payslipPercentInput
                )
                if (validationError != null) {
                    errorMessage = validationError
                } else {
                    onDismiss()
                }
            }
        )
    }
}

@Composable
private fun CurrentSeniorityCard(currYears: Int, currPercent: Double) {
    PopupCard {
        SectionLabel(text = "Trenutno")
        AutoSizeText(
            text = "$currYears god. = ${formatPercent(currPercent)}%",
            color = THEME.compTime,
            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
            maxFontSize = MaterialTheme.typography.titleMedium.fontSize,
            minFontSize = MaterialTheme.typography.titleSmall.fontSize,
            maxLines = 1
        )
        Text(
            text = "Za svaku punu obračunsku godinu staža: +${formatPercent(seniorityRatePercent())}%",
            color = THEME.textSecondary,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun SeniorityInfoCard() {
    PopupCard {
        Text(
            text = "Beneficirani/uvećani staž se ovde koristi za minuli rad samo ako tvoja platna lista " +
                "ili pravilo firme tako obračunava. Ako nisi siguran, koristi procenat sa poslednje " +
                "platne liste kao izvor istine.",
            color = THEME.textSecondary,
            style = MaterialTheme.typography.bodySmall,
            maxLines = 4,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun SeniorityModeSelector(
    selectedMode: SeniorityInputMode,
    onModeChange: (SeniorityInputMode) -> Unit
) {
    PopupCard {
        SectionLabel(text = "Režim unosa")
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            SeniorityModeButton(
                text = "Znam kada sam počeo",
                selected = selectedMode == SeniorityInputMode.KNOWN_START,
                onClick = { onModeChange(SeniorityInputMode.KNOWN_START) }
            )
            SeniorityModeButton(
                text = "Ne znam datum, znam procenat sa platne liste",
                selected = selectedMode == SeniorityInputMode.PAYSLIP_PERCENT,
                onClick = { onModeChange(SeniorityInputMode.PAYSLIP_PERCENT) }
            )
        }
    }
}

@Composable
private fun SeniorityModeButton(text: String, selected: Boolean, onClick: () -> Unit) {
    RoundedButton(
        text = text,
        modifier = Modifier
            .fillMaxWidth()
            .height(46.dp),
        bgColor = if (selected) THEME.compTime else THEME.bgCardElevated,
        textColor = if (selected) THEME.textDark else THEME.textPrimary,
        textStyle = MaterialTheme.typography.labelMedium,
        bold = true,
        onClick = onClick
    )
}

@Composable
private fun KnownStartSection(
    startYearInput: String,
    selectedStartMonth: Int,
    benefitEnabled: Boolean,
    selectedIncreaseFactor: SeniorityIncreaseFactor,
    preview: SeniorityPreview,
    onStartYearChange: (String) -> Unit,
    onStartMonthChange: (Int) -> Unit,
    onBenefitChange: (Boolean) -> Unit,
    onIncreaseFactorChange: (SeniorityIncreaseFactor) -> Unit
) {
    PopupCard {
        SectionLabel(text = "Godina početka rada")
        ModernTextField(
            value = startYearInput,
            onValueChange = onStartYearChange,
            hintText = "npr. 2019",
            label = "Godina",
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
            modifier = Modifier.fillMaxWidth()
        )
        SectionLabel(text = "Mesec početka rada")
        MonthGrid(selectedMonth = selectedStartMonth, onMonthChange = onStartMonthChange)
        BenefitSwitch(checked = benefitEnabled, onCheckedChange = onBenefitChange)
        if (benefitEnabled) {
            SeniorityFactorDropdown(
                selectedFactor = selectedIncreaseFactor,
                onFactorChange = onIncreaseFactorChange
            )
        }
        SeniorityPreviewBox(preview = preview)
    }
}

@Composable
private fun PayslipPercentSection(
    lastPayslipYearInput: String,
    selectedLastPayslipMonth: Int,
    payslipPercentInput: String,
    selectedIncreaseFactor: SeniorityIncreaseFactor,
    estimate: PayslipSeniorityEstimate,
    onLastPayslipYearChange: (String) -> Unit,
    onLastPayslipMonthChange: (Int) -> Unit,
    onPayslipPercentChange: (String) -> Unit,
    onIncreaseFactorChange: (SeniorityIncreaseFactor) -> Unit
) {
    PopupCard {
        SectionLabel(text = "Poslednji obračunski mesec/godina")
        ModernTextField(
            value = lastPayslipYearInput,
            onValueChange = onLastPayslipYearChange,
            hintText = "npr. 2026",
            label = "Godina",
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Next),
            modifier = Modifier.fillMaxWidth()
        )
        MonthGrid(selectedMonth = selectedLastPayslipMonth, onMonthChange = onLastPayslipMonthChange)
        SectionLabel(text = "Procenat sa poslednje platne liste")
        ModernTextField(
            value = payslipPercentInput,
            onValueChange = onPayslipPercentChange,
            hintText = "npr. 6.5",
            label = "Procenat",
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Done),
            modifier = Modifier.fillMaxWidth()
        )
        Text(
            text = "Stopa minulog rada po godini: ${formatPercent(seniorityRatePercent())}%",
            color = THEME.textSecondary,
            style = MaterialTheme.typography.bodySmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        SeniorityFactorDropdown(
            selectedFactor = selectedIncreaseFactor,
            onFactorChange = onIncreaseFactorChange
        )
        PayslipEstimateBox(estimate = estimate)
    }
}

@Composable
private fun MonthGrid(selectedMonth: Int, onMonthChange: (Int) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        for (monthRow in (1..12).chunked(3)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(42.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                for (monthNumber in monthRow) {
                    val selected = selectedMonth == monthNumber
                    RoundedButton(
                        text = MONTH_NAMES[monthNumber].take(3),
                        modifier = Modifier.weight(1f).fillMaxHeight(),
                        bgColor = if (selected) THEME.compTime else THEME.bgCardElevated,
                        textColor = if (selected) THEME.textDark else THEME.textPrimary,
                        textStyle = MaterialTheme.typography.labelMedium,
                        bold = true,
                        onClick = { onMonthChange(monthNumber) }
                    )
                }
            }
        }
    }
}

@Composable
private fun BenefitSwitch(checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    LabeledSwitchRow(
        title = "Koristi uvećani/beneficirani staž",
        checked = checked,
        onCheckedChange = onCheckedChange,
        titleMaxLines = 1,
        switchColors = SwitchDefaults.colors(
            checkedThumbColor = THEME.textDark,
            checkedTrackColor = THEME.compTime,
            uncheckedThumbColor = THEME.textMuted,
            uncheckedTrackColor = THEME.bgCard
        )
    )
}

@Composable
private fun SeniorityFactorDropdown(
    selectedFactor: SeniorityIncreaseFactor,
    onFactorChange: (SeniorityIncreaseFactor) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        SectionLabel(text = "Stepen uvećanja")
        Box(modifier = Modifier.fillMaxWidth()) {
            RoundedButton(
                text = selectedFactor.label,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(46.dp),
                bgColor = THEME.bgCardElevated,
                textColor = THEME.textPrimary,
                textStyle = MaterialTheme.typography.labelLarge,
                bold = true,
                onClick = { expanded = true }
            )
            DropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false }
            ) {
                SeniorityIncreaseFactor.entries.forEach { factor ->
                    DropdownMenuItem(
                        text = {
                            Text(
                                text = factor.label,
                                color = THEME.textPrimary,
                                style = MaterialTheme.typography.bodyMedium
                            )
                        },
                        onClick = {
                            onFactorChange(factor)
                            expanded = false
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun SeniorityPreviewBox(preview: SeniorityPreview) {
    PreviewBox(color = if (preview.fullAccountingYears > 0) THEME.compTime else THEME.textMuted) {
        PreviewText("Efektivni staž: ${formatSeniorityMonths(preview.effectiveMonths)}")
        PreviewText("Obračunski staž: ${formatSeniorityMonths(preview.accountingMonths.roundToInt())}")
        PreviewText("Minuli rad: ${formatPercent(preview.percent)}%")
    }
}

@Composable
private fun PayslipEstimateBox(estimate: PayslipSeniorityEstimate) {
    PreviewBox(color = THEME.compTime) {
        PreviewText("Procena, mesec početka nije poznat")
        PreviewText("Obračunski staž: ${estimate.accountingFullYears} god.")
        PreviewText("Efektivni staž: ${formatYearsDecimal(estimate.estimatedEffectiveYears)} god.")
        PreviewText("Procena godine početka: ${estimate.estimatedStartYear.takeIf { it > 0 } ?: "-"}")
        if (estimate.rounded) {
            Text(
                text = "Procenat nije tačno ceo broj godina po stopi 0.5%, vrednost je zaokružena.",
                color = THEME.warning,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun PreviewBox(color: androidx.compose.ui.graphics.Color, content: @Composable ColumnScope.() -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(color.copy(alpha = 0.15f), MaterialTheme.shapes.small)
            .padding(horizontal = 8.dp, vertical = 6.dp)
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(3.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            content = content
        )
    }
}

@Composable
private fun PreviewText(text: String) {
    Text(
        text = text,
        color = THEME.compTime,
        modifier = Modifier.fillMaxWidth(),
        style = MaterialTheme.typography.labelMedium.copy(textAlign = TextAlign.Center),
        maxLines = 1,
        overflow = TextOverflow.Ellipsis
    )
}

private fun saveSenioritySettings(
    repo: ShiftRepository,
    selectedMode: SeniorityInputMode,
    startYearInput: String,
    selectedStartMonth: Int,
    benefitEnabled: Boolean,
    selectedIncreaseFactor: SeniorityIncreaseFactor,
    lastPayslipYearInput: String,
    selectedLastPayslipMonth: Int,
    payslipPercentInput: String
): String? {
    val startYear = startYearInput.toIntOrNull() ?: 0
    if (selectedMode == SeniorityInputMode.KNOWN_START && startYearInput.isNotBlank() && startYear !in 1900..2200) {
        return "Unesi ispravnu godinu početka rada."
    }
    val lastPayslipYear = lastPayslipYearInput.toIntOrNull() ?: 0
    val payslipPercent = payslipPercentInput.toDoubleOrNull()
    if (selectedMode == SeniorityInputMode.PAYSLIP_PERCENT) {
        if (lastPayslipYear !in 1900..2200) return "Unesi ispravnu godinu poslednje platne liste."
        if (payslipPercent == null || payslipPercent !in 0.0..100.0) {
            return "Unesi ispravan procenat sa platne liste."
        }
    }
    repo.seniorityInputModeValue = selectedMode
    repo.minuliRadIncreaseFactorValue = selectedIncreaseFactor
    when (selectedMode) {
        SeniorityInputMode.KNOWN_START -> {
            repo.minuliRadStartYearValue = startYear
            repo.minuliRadStartMonthValue = selectedStartMonth
            repo.minuliRadBenefitEnabledValue = benefitEnabled
        }

        SeniorityInputMode.PAYSLIP_PERCENT -> {
            repo.minuliRadBenefitEnabledValue = false
            repo.minuliRadLastPayslipYearValue = lastPayslipYear
            repo.minuliRadLastPayslipMonthValue = selectedLastPayslipMonth
            repo.minuliRadPayslipPercentValue = payslipPercent ?: 0.0
        }
    }
    return null
}

private fun seniorityRatePercent(): Double = CONFIG.pay.minuliRadPerYear * 100.0

private fun formatPercent(value: Double): String = String.format(Locale.US, "%.1f", value)

private fun formatYearsDecimal(value: Double): String = String.format(Locale.US, "%.1f", value)

private fun formatSeniorityMonths(months: Int): String {
    val years = months / 12
    val remainingMonths = months % 12
    return when {
        years <= 0 && remainingMonths <= 0 -> "0 god."
        remainingMonths == 0 -> "$years god."
        years == 0 -> "$remainingMonths mes."
        else -> "$years god. $remainingMonths mes."
    }
}
