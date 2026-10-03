package com.rad.shifttracker

import androidx.compose.ui.text.input.ImeAction
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
    val paymentAdjustments = repo.getMonthlyNetAdjustments(paymentMonth.year, paymentMonth.month)
    val advancePrediction = repo.getAdvancePaymentPrediction(paymentMonth.year, paymentMonth.month)

    var advanceInput by remember(paymentMonth, paymentAdjustments.advancePaymentNet) {
        mutableStateOf(formatPositiveMoneyInput(paymentAdjustments.advancePaymentNet))
    }
    var advanceError by remember(paymentMonth) { mutableStateOf<String?>(null) }

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
            "Minimalno po KU: ${mealStatusMoneyText(advancePrediction.minimumAdvanceNet)}"
        } else {
            null
        },
        accentColor = MaterialTheme.extendedColors.warning
    )
    // Topli obrok se ne unosi: računa se kao da je pojedeno koliko pripada, a „Lista“
    // pri učitavanju listića javlja ako je pojedeno više od toga.
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
    accentColor: Color = MaterialTheme.colorScheme.primary
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(UI.smallSpacing.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            AutoSizeText(
                text = label,
                color = MaterialTheme.extendedColors.textSecondary,
                style = MaterialTheme.typography.bodyMedium,
                maxFontSize = MaterialTheme.typography.bodyMedium.fontSize,
                minFontSize = MaterialTheme.typography.bodySmall.fontSize,
                maxLines = 1,
                modifier = Modifier.weight(1f)
            )
            if (manualEntered) {
                Text(
                    text = MANUAL_ENTRY_BADGE_TEXT,
                    color = MaterialTheme.extendedColors.warning,
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
                label = label,
                isError = errorMessage != null,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Done),
                modifier = Modifier.weight(0.7f)
            )
            RoundedButton(
                text = ADVANCE_PAYMENT_SAVE_LABEL,
                modifier = Modifier.weight(0.3f).heightIn(min = 56.dp),
                bgColor = accentColor,
                textColor = MaterialTheme.colorScheme.onPrimary,
                textStyle = MaterialTheme.typography.labelMedium,
                onClick = onSave
            )
        }
        errorMessage?.let { message ->
            FormErrorText(message = message, style = MaterialTheme.typography.bodySmall)
        }
        supportingText?.let { text ->
            Text(
                text = text,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

private fun parseOptionalMoneyInput(input: String): Double? {
    if (input.isBlank()) return null
    val value = input.toDoubleOrNull() ?: return null
    return if (value.isFinite() && value >= 0.0) value else null
}

private fun formatPositiveMoneyInput(value: Double): String {
    return if (value > 0.0) formatMoney2(value) else ""
}

private fun mealStatusMoneyText(value: Double): String {
    return "${formatCurrency(value)} ${CONFIG.pay.currency}"
}
