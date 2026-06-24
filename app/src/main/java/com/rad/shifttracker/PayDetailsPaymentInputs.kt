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

@Suppress("LongMethod")
@Composable
internal fun MonthlyPaymentInputRows(
    monthlyReceipt: MonthlyReceipt,
    repo: ShiftRepository
) {
    val paymentMonth = monthlyReceipt.paymentMonth
    val payrollMonth = monthlyReceipt.payrollMonth
    val paymentAdjustments = repo.getMonthlyNetAdjustments(paymentMonth.year, paymentMonth.month)
    val payrollGrossAdditions = repo.getMonthlyGrossAdditions(payrollMonth.year, payrollMonth.month)
    val foodBreakdown = repo.getMonthlyFoodSpendingBreakdown(payrollMonth.year, payrollMonth.month)

    var advanceInput by remember(paymentMonth, paymentAdjustments.advancePaymentNet) {
        mutableStateOf(formatPositiveMoneyInput(paymentAdjustments.advancePaymentNet))
    }
    var foodSpentInput by remember(payrollMonth, payrollGrossAdditions.mealSpentNet) {
        mutableStateOf(formatOptionalMoneyInput(payrollGrossAdditions.mealSpentNet))
    }
    var advanceError by remember(paymentMonth) { mutableStateOf<String?>(null) }
    var foodSpentError by remember(payrollMonth) { mutableStateOf<String?>(null) }

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
        accentColor = THEME.warning
    )
    PaymentAdjustmentInputRow(
        label = actualFoodSpentInputLabel(payrollMonth),
        value = foodSpentInput,
        onValueChange = {
            foodSpentInput = filterDecimalInput(it)
            foodSpentError = null
        },
        onSave = {
            val value = parseOptionalMoneyInput(foodSpentInput)
            if (foodSpentInput.isNotBlank() && value == null) {
                foodSpentError = "Unesi ispravan iznos potrošene hrane."
                return@PaymentAdjustmentInputRow
            }
            repo.setMonthlyActualFoodSpentNet(payrollMonth.year, payrollMonth.month, value)
            foodSpentInput = formatOptionalMoneyInput(value)
            foodSpentError = null
        },
        errorMessage = foodSpentError,
        manualEntered = foodBreakdown.actualFoodSpentNet != null,
        accentColor = THEME.success
    )
    FoodSpendingStatusRows(foodBreakdown)
}

@Composable
private fun PaymentAdjustmentInputRow(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    onSave: () -> Unit,
    errorMessage: String?,
    manualEntered: Boolean = false,
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
    }
}

@Composable
private fun FoodSpendingStatusRows(food: MonthlyFoodSpendingBreakdown) {
    val actualSpent = food.actualFoodSpentNet ?: return

    FoodSpendingStatusLine(
        label = FOOD_ALLOWANCE_STATUS_LABEL,
        value = paymentInputMoneyText(food.foodAllowanceNet)
    )
    FoodSpendingStatusLine(
        label = FOOD_ACTUAL_SPENT_STATUS_LABEL,
        value = paymentInputMoneyText(actualSpent)
    )

    if (food.foodOverageNet > 0.0) {
        FoodSpendingStatusLine(
            label = FINAL_PAYMENT_FOOD_OVERAGE_LABEL,
            value = paymentInputMoneyText(food.foodOverageNet),
            valueColor = THEME.danger
        )
    } else {
        Text(
            text = FOOD_NOT_OVERAGE_TEXT,
            color = THEME.success,
            style = MaterialTheme.typography.bodySmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        if (food.unusedFoodNet > 0.0) {
            FoodSpendingStatusLine(
                label = FOOD_UNUSED_LABEL,
                value = paymentInputMoneyText(food.unusedFoodNet),
                valueColor = THEME.success
            )
            Text(
                text = FOOD_UNUSED_NOT_PAID_TEXT,
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

private fun formatOptionalMoneyInput(value: Double?): String {
    return value?.let { formatMoney2(it) }.orEmpty()
}

private fun formatPositiveMoneyInput(value: Double): String {
    return if (value > 0.0) formatMoney2(value) else ""
}

private fun paymentInputMoneyText(value: Double): String {
    return "${formatMoney2(value)} ${CONFIG.pay.currency}"
}
