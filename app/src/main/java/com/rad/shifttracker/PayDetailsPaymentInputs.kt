package com.rad.shifttracker

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.rad.shifttracker.ui.components.AutoSizeText
import java.math.BigDecimal

@Suppress("LongMethod")
@Composable
internal fun MonthlyPaymentInputRows(
    monthlyReceipt: MonthlyReceipt,
    repo: ShiftRepository
) {
    val paymentMonth = monthlyReceipt.paymentMonth
    val payrollMonth = monthlyReceipt.payrollMonth
    val paymentAdjustments = repo.getMonthlyNetAdjustments(paymentMonth.year, paymentMonth.month)
    val advancePrediction = repo.getAdvancePaymentPrediction(paymentMonth.year, paymentMonth.month)
    val payrollGrossAdditions = repo.getMonthlyGrossAdditions(payrollMonth.year, payrollMonth.month)
    val payBreakdown = repo.getNetPayBreakdown(payrollMonth.year, payrollMonth.month)

    var advanceInput by remember(paymentMonth, paymentAdjustments.advancePaymentNet) {
        mutableStateOf(formatPositiveMoneyInput(paymentAdjustments.advancePaymentNet))
    }
    var foodSpentInput by remember(payrollMonth, payrollGrossAdditions.mealSpentNet) {
        mutableStateOf(formatOptionalMoneyInput(payrollGrossAdditions.mealSpentNet))
    }
    var foodSpentPresent by remember(payrollMonth, payrollGrossAdditions.mealSpentNet) {
        mutableStateOf(payrollGrossAdditions.mealSpentNet != null)
    }
    var absencePenaltyInput by remember(payrollMonth, payrollGrossAdditions.absencePenaltyGross) {
        mutableStateOf(formatOptionalDecimalInput(payrollGrossAdditions.absencePenaltyGross))
    }
    var advanceError by remember(paymentMonth) { mutableStateOf<String?>(null) }
    var foodSpentError by remember(payrollMonth) { mutableStateOf<String?>(null) }
    var absencePenaltyError by remember(payrollMonth) { mutableStateOf<String?>(null) }

    Spacer(modifier = Modifier.height(UI.defaultSpacing.dp))
    PaymentAdjustmentInputRow(
        label = ADVANCE_PAYMENT_DIALOG_TITLE,
        value = advanceInput,
        onValueChange = {
            advanceInput = filterDecimalInput(it)
            advanceError = null
        },
        onSave = {
            val value = parseOptionalMoneyInput(advanceInput)
            if (advanceInput.isNotBlank() && value == null) {
                advanceError = "Unesi ispravan neto iznos akontacije."
                return@PaymentAdjustmentInputRow
            }
            repo.setMonthlyAdvancePaymentNet(paymentMonth.year, paymentMonth.month, value ?: 0.0)
            advanceInput = formatPositiveMoneyInput(value ?: 0.0)
            advanceError = null
        },
        errorMessage = advanceError,
        supportingText = if (advancePrediction.isAvailable) {
            "Min. po KU čl. 91: ${mealStatusMoneyText(advancePrediction.minimumAdvanceNet)}"
        } else {
            null
        },
        accentColor = THEME.warning
    )
    PaymentAdjustmentInputRow(
        label = "Izostanci 1",
        value = absencePenaltyInput,
        onValueChange = {
            absencePenaltyInput = filterDecimalInput(it)
            absencePenaltyError = null
        },
        onSave = {
            val value = parseOptionalDecimalInput(absencePenaltyInput)
            if (absencePenaltyInput.isNotBlank() && value == null) {
                absencePenaltyError = "Unesi ispravan bruto iznos za Izostanci 1."
                return@PaymentAdjustmentInputRow
            }
            repo.setMonthlyAbsencePenaltyGross(
                payrollMonth.year,
                payrollMonth.month,
                value ?: BigDecimal.ZERO
            )
            absencePenaltyInput = formatOptionalDecimalInput(value ?: BigDecimal.ZERO)
            absencePenaltyError = null
        },
        errorMessage = absencePenaltyError,
        supportingText = "Posebna bruto negativna korekcija; ne menja sate ni osnovicu minulog rada.",
        accentColor = THEME.danger
    )
    PaymentAdjustmentInputRow(
        label = ACTUAL_FOOD_SPENT_INPUT_LABEL,
        value = foodSpentInput,
        onValueChange = {
            foodSpentInput = filterDecimalInput(it)
            foodSpentError = null
            val parsed = parseOptionalMealMoneyInput(foodSpentInput)
            if (foodSpentInput.isBlank() || parsed != null) {
                repo.setMonthlyActualFoodSpentNetDecimal(payrollMonth.year, payrollMonth.month, parsed)
                foodSpentPresent = parsed != null
            }
        },
        onSave = {
            val value = parseOptionalMealMoneyInput(foodSpentInput)
            if (foodSpentInput.isNotBlank() && value == null) {
                foodSpentError = "Unesi ispravan iznos potrošene hrane."
                return@PaymentAdjustmentInputRow
            }
            repo.setMonthlyActualFoodSpentNetDecimal(payrollMonth.year, payrollMonth.month, value)
            foodSpentInput = formatOptionalMoneyInput(value?.toDouble())
            foodSpentPresent = value != null
            foodSpentError = null
        },
        errorMessage = foodSpentError,
        manualEntered = foodSpentPresent,
        supportingText = if (foodSpentPresent) {
            MEAL_SPENT_MANUAL_HELP_TEXT
        } else {
            MEAL_SPENT_ASSUMED_HELP_TEXT
        },
        accentColor = THEME.success
    )
    if (payBreakdown.canDisplayMealUnused) {
        FoodSpendingStatusLine(
            label = "Ostaje u plati",
            value = mealStatusMoneyText(payBreakdown.mealUnusedNet),
            valueColor = THEME.success
        )
    } else {
        Text(
            text = MEAL_SAFE_DEFAULT_STATUS_TEXT,
            color = THEME.textMuted,
            style = MaterialTheme.typography.bodySmall
        )
    }
}

@Composable
private fun PaymentAdjustmentInputRow(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    onSave: () -> Unit,
    errorMessage: String?,
    manualEntered: Boolean = false,
    supportingText: String? = null,
    accentColor: Color = THEME.accentPrimary
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(UI.smallSpacing.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            AutoSizeText(
                text = label,
                color = THEME.textSecondary,
                style = MaterialTheme.typography.bodyMedium,
                maxFontSize = MaterialTheme.typography.bodyMedium.fontSize,
                minFontSize = MaterialTheme.typography.bodySmall.fontSize,
                maxLines = 1,
                modifier = Modifier.weight(1f)
            )
            if (manualEntered) {
                Text(
                    text = MANUAL_ENTRY_BADGE_TEXT,
                    color = THEME.warning,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(UI.defaultSpacing.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            ModernTextField(
                value = value,
                onValueChange = onValueChange,
                hintText = "0",
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier.weight(0.7f)
            )
            RoundedButton(
                text = ADVANCE_PAYMENT_SAVE_LABEL,
                modifier = Modifier.weight(0.3f).heightIn(min = 56.dp),
                bgColor = accentColor,
                textColor = THEME.textDark,
                textStyle = MaterialTheme.typography.labelMedium,
                onClick = onSave
            )
        }
        errorMessage?.let { message ->
            Text(
                text = message,
                color = THEME.danger,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
        supportingText?.let { text ->
            Text(
                text = text,
                color = THEME.textMuted,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun FoodSpendingStatusLine(
    label: String,
    value: String,
    valueColor: Color = THEME.textSecondary
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(UI.smallSpacing.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        AutoSizeText(
            text = label,
            color = THEME.textSecondary,
            style = MaterialTheme.typography.bodySmall,
            maxFontSize = MaterialTheme.typography.bodySmall.fontSize,
            minFontSize = MaterialTheme.typography.labelSmall.fontSize,
            maxLines = 1,
            modifier = Modifier.weight(1f)
        )
        Text(
            text = value,
            color = valueColor,
            style = MaterialTheme.typography.bodySmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

private fun parseOptionalMoneyInput(input: String): Double? {
    if (input.isBlank()) return null
    val value = input.toDoubleOrNull() ?: return null
    return if (value.isFinite() && value >= 0.0) value else null
}

private fun parseOptionalMealMoneyInput(input: String): BigDecimal? {
    if (input.isBlank()) return null
    return input.toBigDecimalOrNull()?.takeIf { it.signum() >= 0 }?.toMealMoney()
}

private fun formatOptionalMoneyInput(value: Double?): String {
    return value?.let { formatMoney2(it) }.orEmpty()
}

private fun formatPositiveMoneyInput(value: Double): String {
    return if (value > 0.0) formatMoney2(value) else ""
}

private fun parseOptionalDecimalInput(input: String): BigDecimal? {
    if (input.isBlank()) return null
    return input.toBigDecimalOrNull()?.takeIf { it.signum() >= 0 }
}

private fun formatOptionalDecimalInput(value: BigDecimal): String {
    return if (value.signum() > 0) formatMoney2(value.toDouble()) else ""
}

private fun mealStatusMoneyText(value: Double): String {
    return "${formatCurrency(value)} ${CONFIG.pay.currency}"
}
