package com.rad.shifttracker

import androidx.compose.ui.text.input.ImeAction
import androidx.compose.foundation.background
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
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
    val summary = remember(year, today) { repo.getVacationBalanceSummary(year, today) }
    val savedBalance = remember(year) { repo.getVacationYearBalance(year) }
    val beforeJuly = today.isBefore(summary.currentYearAvailableFrom)
    val setupFieldLabels = vacationSetupFieldLabels(year)
    val currentYearAvailableFrom = formatVacationDate(summary.currentYearAvailableFrom)
    val currentYearExpiresOn = formatVacationDate(summary.currentYearExpiresOn)
    val initialInputs = vacationSetupInitialInputs(savedBalance, summary)
    // Skriveno polje zadržava početnu vrednost (sačuvanu ili izračunatu), pa se čuva nepromenjeno.
    val visibility = vacationSetupVisibility(
        year = year,
        today = today,
        saved = savedBalance,
        carryoverAutomatic = repo.isVacationCarryoverAutomatic(year),
        monthsWithoutData = repo.getVacationMonthsWithoutData(year, today)
    )
    var carryoverInput by remember { mutableStateOf(initialInputs[0]) }
    // Prazno pravo se popunjava predlogom po ugovoru; broj sa rešenja se upisuje preko njega.
    var currentYearTotalInput by remember {
        mutableStateOf(initialInputs[1].ifBlank { repo.getAnnualLeaveEntitlement(year).breakdown.totalDays.toString() })
    }
    var usedOutsideAppInput by remember { mutableStateOf(initialInputs[2]) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    // Potrošeno se računa od 01.01. iz listića i kalendara; ručno se unosi samo pravo, prenos i ono čega nema nigde.
    fun saveVacationBalance(): Boolean {
        val carryover = carryoverInput.ifBlank { "0" }.toDoubleOrNull()
        val currentTotal = currentYearTotalInput.toDoubleOrNull()
        val usedOutsideApp = usedOutsideAppInput.ifBlank { "0" }.toDoubleOrNull()
        if (
            carryover == null ||
            currentTotal == null ||
            usedOutsideApp == null ||
            carryover < 0.0 ||
            currentTotal < 0.0 ||
            usedOutsideApp < 0.0
        ) {
            errorMessage = "Unesite ispravan broj dana."
            return false
        }
        repo.setVacationYearBalance(
            VacationYearBalance(
                year = year,
                trackingStartDate = LocalDate.of(year, 1, 1),
                carryoverDaysInitial = carryover,
                currentYearDaysTotal = currentTotal,
                currentYearDaysUsedBeforeAvailability = usedOutsideApp,
                currentYearAvailableFrom = currentYearVacationAvailableFrom(year),
                manualBaseline = true
            )
        )
        return true
    }

    PopupDialog(
        title = "Godi\u0161nji odmor",
        onDismiss = onDismiss,
        scrollable = true
    ) {
        PopupCard {
            SectionLabel(text = "Stanje")
            if (beforeJuly) {
                VacationStateRow("Stari odmor:", "${formatVacationInput(summary.availableNow)} dana", MaterialTheme.extendedColors.vacation)
                if (summary.carryoverInitial > 0.0) {
                    VacationStateRow(
                        "Stari potrošeno:",
                        "${formatVacationInput(summary.carryoverUsed)}/${formatVacationInput(summary.carryoverInitial)}",
                        MaterialTheme.colorScheme.onSurface
                    )
                    VacationStateRow("Stari važi do:", currentYearAvailableFrom, MaterialTheme.colorScheme.onSurface)
                }
                VacationStateRow(
                    "Novi odmor:",
                    "${formatVacationInput(summary.currentYearRemaining)} dana od $currentYearAvailableFrom",
                    MaterialTheme.extendedColors.info
                )
            } else {
                VacationStateRow("Novi odmor:", "${formatVacationInput(summary.currentYearRemaining)} dana", MaterialTheme.extendedColors.vacation)
                VacationStateRow(
                    "Potrošeno:",
                    "${formatVacationInput(summary.currentYearUsed)}/${formatVacationInput(summary.currentYearTotal)}",
                    MaterialTheme.colorScheme.onSurface
                )
                VacationStateRow("Važi do:", currentYearExpiresOn, MaterialTheme.colorScheme.onSurface)
                if (summary.carryoverExpired > 0.0) {
                    VacationStateRow(
                        "Propalo starog:",
                        "${formatVacationInput(summary.carryoverExpired)} dana",
                        MaterialTheme.colorScheme.error
                    )
                }
            }
            if (summary.warnings.contains(VacationWarnings.VACATION_EXCEEDED)) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.error.copy(alpha = 0.2f), MaterialTheme.shapes.small)
                        .padding(horizontal = Spacing.sm, vertical = Spacing.xs)
                ) {
                    Text(
                        text = "Prekora\u010den fond godi\u0161njeg!",
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.labelMediumEmphasized.copy(
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
            SectionLabel(text = "Iskorišćeno $year.")
            if (summary.usageByMonth.isEmpty() && summary.carryoverUsed == 0.0) {
                Text(
                    text = "Nema godišnjeg ni na listama ni u kalendaru.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall
                )
            }
            summary.usageByMonth.forEach { usage ->
                VacationStateRow("${MONTH_NAMES[usage.month]}:", vacationMonthUsageText(usage), MaterialTheme.colorScheme.onSurface)
            }
            savedBalance?.currentYearDaysUsedBeforeAvailability?.takeIf { it > 0.0 }?.let { outside ->
                VacationStateRow("Van aplikacije:", "${formatVacationInput(outside)} dana", MaterialTheme.colorScheme.onSurface)
            }
        }

        VacationPayslipScanCard()

        if (SHOW_ANNUAL_LEAVE_ENTITLEMENT_CARD) {
            AnnualLeaveEntitlementCard(year = year, repo = repo, enteredTotal = currentYearTotalInput) {
                currentYearTotalInput = it
                errorMessage = null
            }
        }

        PopupCard {
            SectionLabel(text = "Početno stanje")
            if (visibility.carryover) {
                VacationNumberInput(setupFieldLabels[0], carryoverInput, ImeAction.Next) {
                    carryoverInput = filterDecimalInput(it)
                    errorMessage = null
                }
            }
            val totalImeAction = if (visibility.usedOutsideApp) ImeAction.Next else ImeAction.Done
            VacationNumberInput(setupFieldLabels[1], currentYearTotalInput, totalImeAction) {
                currentYearTotalInput = filterDecimalInput(it)
                errorMessage = null
            }
            if (visibility.usedOutsideApp) {
                VacationNumberInput(setupFieldLabels[2], usedOutsideAppInput, ImeAction.Done) {
                    usedOutsideAppInput = filterDecimalInput(it)
                    errorMessage = null
                }
            }
            Text(
                text = VACATION_SETUP_HINT,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall
            )
            errorMessage?.let { message ->
                FormErrorText(message = message)
            }
        }

        GlowButton(
            text = "Sa\u010duvaj",
            modifier = Modifier
                .fillMaxWidth()
                .height(50.dp),
            textStyle = MaterialTheme.typography.labelLarge,
            onClick = {
                if (saveVacationBalance()) {
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
            color = MaterialTheme.extendedColors.textSecondary,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Text(
            text = value,
            color = valueColor,
            style = MaterialTheme.typography.bodyMediumEmphasized,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun VacationNumberInput(
    label: String,
    value: String,
    imeAction: ImeAction,
    onValueChange: (String) -> Unit
) {
    ModernTextField(
        value = value,
        onValueChange = onValueChange,
        hintText = "Broj dana",
        label = label,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = imeAction),
        modifier = Modifier.fillMaxWidth()
    )
}
