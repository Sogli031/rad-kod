package com.rad.shifttracker

import androidx.compose.foundation.background
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import java.time.LocalDate

// Existing vacation setup flow is intentionally grouped; detekt stays active for newly added methods.
@Suppress("LongMethod", "CyclomaticComplexMethod")
@Composable
fun VacationBalanceDialog(
    year: Int,
    today: LocalDate,
    repo: ShiftRepository,
    onDismiss: () -> Unit
) {
    val summary = repo.getVacationBalanceSummary(year, today)
    val savedBalance = repo.getVacationYearBalance(year)
    val beforeJuly = today.isBefore(summary.currentYearAvailableFrom)
    val setupFieldLabels = vacationSetupFieldLabels(beforeJuly)
    val currentYearAvailableFrom = formatVacationDate(summary.currentYearAvailableFrom)
    val currentYearExpiresOn = formatVacationDate(summary.currentYearExpiresOn)
    var carryoverInput by remember {
        mutableStateOf(formatVacationInput(savedBalance?.carryoverDaysInitial ?: summary.carryoverRemaining))
    }
    var currentYearTotalInput by remember {
        mutableStateOf(formatVacationInput(savedBalance?.currentYearDaysTotal ?: summary.currentYearTotal))
    }
    var usedBeforeAvailabilityInput by remember {
        mutableStateOf(
            formatVacationInput(savedBalance?.currentYearDaysUsedBeforeAvailability ?: summary.currentYearUsed)
        )
    }
    var remainingAfterJulyInput by remember {
        mutableStateOf(formatVacationInput(summary.currentYearRemaining))
    }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    fun saveVacationBalance(blankAsZero: Boolean): Boolean {
        val balance = if (beforeJuly) {
            val carryover = carryoverInput.toDoubleOrNull() ?: if (blankAsZero) 0.0 else null
            val currentTotal = currentYearTotalInput.toDoubleOrNull() ?: if (blankAsZero) 0.0 else null
            val usedBefore = usedBeforeAvailabilityInput.toDoubleOrNull() ?: if (blankAsZero) 0.0 else null
            if (
                carryover == null ||
                currentTotal == null ||
                usedBefore == null ||
                carryover < 0.0 ||
                currentTotal < 0.0 ||
                usedBefore < 0.0
            ) {
                errorMessage = "Unesite ispravan broj dana."
                return false
            }
            VacationYearBalance(
                year = year,
                trackingStartDate = today,
                carryoverDaysInitial = carryover,
                currentYearDaysTotal = currentTotal,
                currentYearDaysUsedBeforeAvailability = usedBefore,
                currentYearAvailableFrom = currentYearVacationAvailableFrom(year),
                manualBaseline = true
            )
        } else {
            val remaining = remainingAfterJulyInput.toDoubleOrNull() ?: if (blankAsZero) 0.0 else null
            if (remaining == null || remaining < 0.0) {
                errorMessage = "Unesite ispravan broj dana."
                return false
            }
            VacationYearBalance(
                year = year,
                trackingStartDate = today,
                carryoverDaysInitial = 0.0,
                currentYearDaysTotal = remaining,
                currentYearDaysUsedBeforeAvailability = 0.0,
                currentYearAvailableFrom = currentYearVacationAvailableFrom(year),
                manualBaseline = true
            )
        }
        repo.setVacationYearBalance(balance)
        return true
    }

    PopupDialog(
        title = "Godi\u0161nji odmor",
        onDismiss = onDismiss,
        widthRatio = 0.85f,
        scrollable = true,
        accent = THEME.vacation
    ) {
        PopupCard(bgColor = THEME.bgCardElevated, borderColor = THEME.vacation) {
            SectionLabel(text = "Stanje")
            if (beforeJuly) {
                VacationStateRow("Stari odmor:", "${formatVacationInput(summary.availableNow)} dana", THEME.vacation)
                if (summary.carryoverInitial > 0.0) {
                    VacationStateRow(
                        "Stari potrošeno:",
                        "${formatVacationInput(summary.carryoverUsed)}/${formatVacationInput(summary.carryoverInitial)}",
                        THEME.textPrimary
                    )
                    VacationStateRow("Stari važi do:", currentYearAvailableFrom, THEME.textPrimary)
                }
                VacationStateRow(
                    "Novi odmor:",
                    "${formatVacationInput(summary.currentYearRemaining)} dana od $currentYearAvailableFrom",
                    THEME.info
                )
            } else {
                VacationStateRow("Novi odmor:", "${formatVacationInput(summary.currentYearRemaining)} dana", THEME.vacation)
                VacationStateRow(
                    "Potrošeno:",
                    "${formatVacationInput(summary.currentYearUsed)}/${formatVacationInput(summary.currentYearTotal)}",
                    THEME.textPrimary
                )
                VacationStateRow("Važi do:", currentYearExpiresOn, THEME.textPrimary)
                if (summary.carryoverExpired > 0.0) {
                    VacationStateRow(
                        "Propalo starog:",
                        "${formatVacationInput(summary.carryoverExpired)} dana",
                        THEME.danger
                    )
                }
            }
            if (summary.warnings.contains(VacationWarnings.VACATION_EXCEEDED)) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(THEME.danger.copy(alpha = 0.2f), RoundedCornerShape(8.dp))
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                ) {
                    Text(
                        text = "Prekora\u010den fond godi\u0161njeg!",
                        color = THEME.danger,
                        style = MaterialTheme.typography.labelMedium.copy(
                            fontWeight = FontWeight.Bold,
                            textAlign = TextAlign.Center
                        ),
                        modifier = Modifier.fillMaxWidth(),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }

        PopupCard {
            SectionLabel(text = "Početno stanje")
            if (beforeJuly) {
                VacationNumberInput(setupFieldLabels[0], carryoverInput) {
                    carryoverInput = filterDecimalInput(it)
                    errorMessage = null
                }
                VacationNumberInput(setupFieldLabels[1], currentYearTotalInput) {
                    currentYearTotalInput = filterDecimalInput(it)
                    errorMessage = null
                }
                VacationNumberInput(setupFieldLabels[2], usedBeforeAvailabilityInput) {
                    usedBeforeAvailabilityInput = filterDecimalInput(it)
                    errorMessage = null
                }
            } else {
                VacationNumberInput(setupFieldLabels[0], remainingAfterJulyInput) {
                    remainingAfterJulyInput = filterDecimalInput(it)
                    errorMessage = null
                }
            }
            errorMessage?.let { message ->
                Text(
                    text = message,
                    color = THEME.danger,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }

        GlowButton(
            text = "Sa\u010duvaj",
            modifier = Modifier
                .fillMaxWidth()
                .height(50.dp),
            bgColor = THEME.vacation,
            textColor = THEME.textDark,
            textStyle = MaterialTheme.typography.labelLarge,
            onClick = {
                if (saveVacationBalance(blankAsZero = false)) {
                    onDismiss()
                }
            }
        )
    }
}

@Composable
private fun VacationStateRow(label: String, value: String, valueColor: Color) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = label,
            color = THEME.textSecondary,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Text(
            text = value,
            color = valueColor,
            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun VacationNumberInput(
    label: String,
    value: String,
    onValueChange: (String) -> Unit
) {
    Text(
        text = label,
        color = THEME.textSecondary,
        style = MaterialTheme.typography.labelMedium,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis
    )
    ModernTextField(
        value = value,
        onValueChange = onValueChange,
        hintText = "Broj dana",
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        modifier = Modifier.fillMaxWidth()
    )
}
