package com.rad.shifttracker

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import java.time.LocalDate
import java.time.YearMonth
import java.util.Locale

enum class OnboardingStep {
    TAX_UPDATE,
    PAY_SETTINGS,
    SENIORITY,
    AUTO_FILL
}

internal fun defaultOnboardingSteps(): List<OnboardingStep> = listOf(
    OnboardingStep.TAX_UPDATE,
    OnboardingStep.PAY_SETTINGS,
    OnboardingStep.SENIORITY,
    OnboardingStep.AUTO_FILL
)

internal fun onboardingStepTitle(step: OnboardingStep): String {
    return when (step) {
        OnboardingStep.TAX_UPDATE -> "Ažuriranje"
        OnboardingStep.PAY_SETTINGS -> "Podešavanja plate"
        OnboardingStep.SENIORITY -> "Minuli rad"
        OnboardingStep.AUTO_FILL -> "Popuni smene"
    }
}

fun requiredOnboardingPermissions(context: Context): Array<String> {
    if (Build.VERSION.SDK_INT !in Build.VERSION_CODES.M..Build.VERSION_CODES.P) {
        return emptyArray()
    }
    val hasStoragePermission = ContextCompat.checkSelfPermission(
        context,
        Manifest.permission.WRITE_EXTERNAL_STORAGE
    ) == PackageManager.PERMISSION_GRANTED
    return if (hasStoragePermission) emptyArray() else arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE)
}

@Composable
fun OnboardingDialog(
    step: OnboardingStep,
    year: Int,
    month: Int,
    repo: ShiftRepository,
    onStepChange: (OnboardingStep) -> Unit,
    onComplete: () -> Unit
) {
    PopupDialog(
        title = onboardingStepTitle(step),
        onDismiss = {},
        widthRatio = 0.88f,
        scrollable = true,
        accent = onboardingStepAccent(step)
    ) {
        when (step) {
            OnboardingStep.TAX_UPDATE -> OnboardingTaxUpdateStep(
                repo = repo,
                onNext = { onStepChange(OnboardingStep.PAY_SETTINGS) }
            )
            OnboardingStep.PAY_SETTINGS -> OnboardingPaySettingsStep(
                year = year,
                month = month,
                repo = repo,
                onNext = { onStepChange(OnboardingStep.SENIORITY) }
            )
            OnboardingStep.SENIORITY -> OnboardingSeniorityStep(
                repo = repo,
                onNext = { onStepChange(OnboardingStep.AUTO_FILL) }
            )
            OnboardingStep.AUTO_FILL -> {
                LaunchedEffect(Unit) {
                    onComplete()
                }
                PopupCard(bgColor = THEME.bgCardElevated, borderColor = THEME.accentSecondary) {
                    Text(
                        text = "Otvaram meni za popunjavanje smena.",
                        color = THEME.textPrimary,
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}

@Composable
private fun OnboardingTaxUpdateStep(
    repo: ShiftRepository,
    onNext: () -> Unit
) {
    var attempt by remember { mutableIntStateOf(0) }
    var isUpdating by remember { mutableStateOf(true) }
    var updateSucceeded by remember { mutableStateOf<Boolean?>(null) }
    var message by remember { mutableStateOf("Ažuriranje je u toku...") }
    val updater = remember { TaxParameterUpdater() }
    val context = LocalContext.current
    val monthlyTaxUpdateStore = remember(context) { MonthlyTaxUpdateStore(context) }

    LaunchedEffect(attempt) {
        isUpdating = true
        updateSucceeded = null
        message = "Skidam najnovije poreske parametre..."
        val updateDate = LocalDate.now()
        runCatching { fetchAndApplyLatestTaxParameters(repo, updater, updateDate) }
            .onSuccess { parameters ->
                monthlyTaxUpdateStore.markProcessed(YearMonth.from(updateDate))
                updateSucceeded = true
                message = taxUpdateSuccessMessage(parameters)
            }
            .onFailure { error ->
                updateSucceeded = false
                message = "Ažuriranje nije uspelo: ${error.message ?: "proveri internet vezu"}"
            }
        isUpdating = false
    }

    PopupCard(bgColor = THEME.bgCardElevated, borderColor = THEME.success) {
        SectionLabel(text = "Automatsko ažuriranje")
        Text(
            text = "Aplikacija sama preuzima najnovije doprinose, poresku olakšicu i porez.",
            color = THEME.textSecondary,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis
        )
        if (isUpdating) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                CircularProgressIndicator(
                    modifier = Modifier.size(18.dp),
                    color = THEME.success,
                    strokeWidth = 2.dp
                )
                Text(
                    text = "Preuzimanje je u toku...",
                    color = THEME.textSecondary,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
            }
        }
        Text(
            text = message,
            color = when (updateSucceeded) {
                true -> THEME.success
                false -> THEME.danger
                null -> THEME.textSecondary
            },
            style = MaterialTheme.typography.bodySmall,
            maxLines = 4,
            overflow = TextOverflow.Ellipsis
        )
    }
    if (updateSucceeded == false) {
        RoundedButton(
            text = "Pokušaj ponovo",
            modifier = Modifier
                .fillMaxWidth()
                .height(44.dp),
            bgColor = THEME.bgCardElevated,
            textColor = THEME.textPrimary,
            radius = 14.dp,
            textStyle = MaterialTheme.typography.labelLarge,
            bold = true,
            enabled = !isUpdating,
            onClick = { attempt += 1 }
        )
    }
    if (!isUpdating) {
        GlowButton(
            text = if (updateSucceeded == false) "Nastavi sa postojećim" else "Nastavi",
            modifier = Modifier
                .fillMaxWidth()
                .height(50.dp),
            bgColor = THEME.success,
            textColor = THEME.textDark,
            textStyle = MaterialTheme.typography.labelLarge,
            onClick = onNext
        )
    }
}

@Composable
private fun OnboardingPaySettingsStep(
    year: Int,
    month: Int,
    repo: ShiftRepository,
    onNext: () -> Unit
) {
    var coefficientInput by remember { mutableStateOf(formatRateInput(repo.payCoefficientValue)) }
    var difficultWorkConditionsInput by remember {
        mutableStateOf(formatRateInput(repo.difficultWorkConditionsPercentValue))
    }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    val hourlyValue = remember(year, month) { repo.getHourlyValueForMonth(year, month) }
    val coefficientPreview = coefficientInput.toDoubleOrNull() ?: repo.payCoefficientValue

    PopupCard(bgColor = THEME.bgCardElevated, borderColor = THEME.success) {
        SectionLabel(text = "Koeficijent")
        OnboardingInput(
            label = SATNICA_COEFFICIENT_LABEL,
            value = coefficientInput,
            hintText = "npr. 2.82",
            onValueChange = {
                coefficientInput = filterDecimalInput(it)
                errorMessage = null
            }
        )
        Text(
            text = satnicaGrossPreviewText(coefficientPreview, hourlyValue),
            color = THEME.textMuted,
            style = MaterialTheme.typography.bodySmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        OnboardingInput(
            label = SATNICA_DIFFICULT_WORK_CONDITIONS_LABEL,
            value = difficultWorkConditionsInput,
            hintText = "0 ako ne postoji",
            onValueChange = {
                difficultWorkConditionsInput = filterDecimalInput(it)
                errorMessage = null
            }
        )
        Text(
            text = "Otežani uslovi su bruto dodatak na stvarno odrađene sate.",
            color = THEME.textMuted,
            style = MaterialTheme.typography.bodySmall,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
    }
    errorMessage?.let { messageText ->
        Text(
            text = messageText,
            color = THEME.danger,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
    }
    GlowButton(
        text = "Sačuvaj i nastavi",
        modifier = Modifier
            .fillMaxWidth()
            .height(50.dp),
        bgColor = THEME.success,
        textColor = THEME.textDark,
        textStyle = MaterialTheme.typography.labelLarge,
        onClick = {
            val coefficient = coefficientInput.toDoubleOrNull()
            val difficultWorkConditions = difficultWorkConditionsInput.ifBlank { "0" }.toDoubleOrNull()
            when {
                coefficient == null || coefficient <= 0.0 -> {
                    errorMessage = "Unesi ispravan koeficijent."
                }
                difficultWorkConditions == null ||
                    difficultWorkConditions < 0.0 ||
                    difficultWorkConditions > 100.0 -> {
                    errorMessage = "Unesi procenat otežanih uslova od 0 do 100."
                }
                else -> {
                    repo.payCoefficientValue = coefficient
                    repo.difficultWorkConditionsPercentValue = difficultWorkConditions
                    onNext()
                }
            }
        }
    )
}

@Composable
private fun OnboardingSeniorityStep(
    repo: ShiftRepository,
    onNext: () -> Unit
) {
    val currentStartYear = repo.minuliRadStartYearValue
    val currentStartMonth = repo.minuliRadStartMonthValue
    var startYearInput by remember {
        mutableStateOf(if (currentStartYear > 0) currentStartYear.toString() else "")
    }
    var selectedStartMonth by remember { mutableIntStateOf(currentStartMonth) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    val previewYears by remember {
        derivedStateOf {
            val inputYear = startYearInput.toIntOrNull() ?: 0
            if (inputYear > 0) {
                val now = LocalDate.now()
                val years = if (now.monthValue >= selectedStartMonth) {
                    now.year - inputYear
                } else {
                    now.year - 1 - inputYear
                }
                years.coerceAtLeast(0)
            } else {
                0
            }
        }
    }

    PopupCard(bgColor = THEME.bgCardElevated, borderColor = THEME.compTime) {
        SectionLabel(text = "Kada je počeo radni staž?")
        OnboardingInput(
            label = "Godina početka rada",
            value = startYearInput,
            hintText = "npr. 2019",
            keyboardType = KeyboardType.Number,
            onValueChange = {
                startYearInput = it.filter(Char::isDigit).take(4)
                errorMessage = null
            }
        )
        SectionLabel(text = "Mesec početka rada")
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            for (monthRow in (1..12).chunked(3)) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(42.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    for (monthNumber in monthRow) {
                        val selected = selectedStartMonth == monthNumber
                        RoundedButton(
                            text = MONTH_NAMES[monthNumber].take(3),
                            modifier = Modifier.weight(1f),
                            bgColor = if (selected) THEME.compTime else THEME.bgCardElevated,
                            textColor = if (selected) THEME.textDark else THEME.textPrimary,
                            radius = 14.dp,
                            textStyle = MaterialTheme.typography.labelMedium,
                            bold = true,
                            onClick = { selectedStartMonth = monthNumber }
                        )
                    }
                }
            }
        }
        Text(
            text = "Trenutno: $previewYears god. = ${String.format(Locale.US, "%.1f", previewYears * 0.5)}%",
            color = THEME.compTime,
            modifier = Modifier.fillMaxWidth(),
            style = MaterialTheme.typography.labelMedium.copy(textAlign = TextAlign.Center),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
    errorMessage?.let { messageText ->
        Text(
            text = messageText,
            color = THEME.danger,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
    }
    GlowButton(
        text = "Sačuvaj i otvori Popuni",
        modifier = Modifier
            .fillMaxWidth()
            .height(50.dp),
        bgColor = THEME.compTime,
        textColor = THEME.textDark,
        textStyle = MaterialTheme.typography.labelLarge,
        onClick = {
            val startYear = startYearInput.toIntOrNull() ?: 0
            if (startYearInput.isNotBlank() && startYear !in 1900..2200) {
                errorMessage = "Unesi ispravnu godinu početka rada."
                return@GlowButton
            }
            repo.minuliRadStartYearValue = startYear
            repo.minuliRadStartMonthValue = selectedStartMonth
            onNext()
        }
    )
}

@Composable
private fun OnboardingInput(
    label: String,
    value: String,
    hintText: String,
    keyboardType: KeyboardType = KeyboardType.Decimal,
    onValueChange: (String) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            text = label,
            color = THEME.textSecondary,
            style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        ModernTextField(
            value = value,
            onValueChange = onValueChange,
            hintText = hintText,
            keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
            modifier = Modifier.fillMaxWidth()
        )
    }
}

private fun onboardingStepAccent(step: OnboardingStep) = when (step) {
    OnboardingStep.TAX_UPDATE -> THEME.success
    OnboardingStep.PAY_SETTINGS -> THEME.success
    OnboardingStep.SENIORITY -> THEME.compTime
    OnboardingStep.AUTO_FILL -> THEME.accentSecondary
}
