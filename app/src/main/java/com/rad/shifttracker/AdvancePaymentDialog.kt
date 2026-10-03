package com.rad.shifttracker

import androidx.compose.ui.text.input.ImeAction
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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

@Composable
fun AdvancePaymentDialog(
    year: Int,
    month: Int,
    repo: ShiftRepository,
    onDismiss: () -> Unit
) {
    val currentAdjustments = repo.getMonthlyNetAdjustments(year, month)
    val prediction = remember(year, month) { repo.getAdvancePaymentPrediction(year, month) }
    var advanceInput by remember(year, month) {
        mutableStateOf(formatRateInput(currentAdjustments.advancePaymentNet))
    }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    PopupDialog(
        title = ADVANCE_PAYMENT_DIALOG_TITLE,
        onDismiss = onDismiss,
        scrollable = true
    ) {
        PopupCard {
            SectionLabel(text = monthYearText(MonthKey(year, month)))
            ModernTextField(
                value = advanceInput,
                onValueChange = {
                    advanceInput = filterDecimalInput(it)
                    errorMessage = null
                },
                hintText = "0",
                label = "$ADVANCE_PAYMENT_NET_LABEL (${CONFIG.pay.currency})",
                isError = errorMessage != null,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Done),
                modifier = Modifier.fillMaxWidth()
            )
            errorMessage?.let { message ->
                FormErrorText(message = message)
            }
        }

        PopupCard {
            SectionLabel(text = ADVANCE_PREDICTION_SECTION_LABEL)
            if (prediction.isAvailable) {
                PredictionValueRow(
                    label = ADVANCE_PREDICTION_PAID_PREVIOUS_LABEL,
                    value = predictionMoneyText(prediction.previousMonthPaidTotalNet),
                    valueColor = MaterialTheme.colorScheme.onSurface
                )
                PredictionValueRow(
                    label = ADVANCE_PREDICTION_MINIMUM_LABEL,
                    value = predictionMoneyText(prediction.minimumAdvanceNet),
                    valueColor = MaterialTheme.extendedColors.warning
                )
                RoundedButton(
                    text = ADVANCE_PREDICTION_USE_MINIMUM_LABEL,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(44.dp),
                    bgColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                    textColor = MaterialTheme.extendedColors.warning,
                    textStyle = MaterialTheme.typography.labelMedium,
                    onClick = {
                        advanceInput = formatRateInput(prediction.minimumAdvanceNet)
                        errorMessage = null
                    }
                )
            } else {
                Text(
                    text = ADVANCE_PREDICTION_UNAVAILABLE_TEXT,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(UI.defaultSpacing.dp)
        ) {
            RoundedButton(
                text = ADVANCE_PAYMENT_CANCEL_LABEL,
                modifier = Modifier
                    .weight(1f)
                    .height(50.dp),
                bgColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                textColor = MaterialTheme.colorScheme.onSurface,
                textStyle = MaterialTheme.typography.labelMedium,
                onClick = onDismiss
            )
            GlowButton(
                text = ADVANCE_PAYMENT_SAVE_LABEL,
                modifier = Modifier
                    .weight(1f)
                    .height(50.dp),
                textStyle = MaterialTheme.typography.labelLarge,
                onClick = {
                    val value = advanceInput.ifBlank { "0" }.toDoubleOrNull()
                    if (value == null || value < 0.0) {
                        errorMessage = "Unesi ispravan neto iznos akontacije."
                        return@GlowButton
                    }
                    repo.setMonthlyAdvancePaymentNet(year, month, value)
                    onDismiss()
                }
            )
        }
    }
}

@Composable
private fun PredictionValueRow(
    label: String,
    value: String,
    valueColor: Color
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(UI.smallSpacing.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            color = MaterialTheme.extendedColors.textSecondary,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        Text(
            text = value,
            color = valueColor,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

private fun predictionMoneyText(value: Double): String {
    return "${formatCurrency(value)} ${CONFIG.pay.currency}"
}
