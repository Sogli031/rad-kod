package com.rad.shifttracker

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
import androidx.compose.ui.Modifier
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
    var advanceInput by remember(year, month) {
        mutableStateOf(formatRateInput(currentAdjustments.advancePaymentNet))
    }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    PopupDialog(
        title = ADVANCE_PAYMENT_DIALOG_TITLE,
        onDismiss = onDismiss,
        scrollable = true,
        accent = THEME.warning
    ) {
        PopupCard(bgColor = THEME.bgCardElevated, borderColor = THEME.warning) {
            SectionLabel(text = monthYearText(MonthKey(year, month)))
            Text(
                text = ADVANCE_PAYMENT_EXPLANATION_TEXT,
                color = THEME.textSecondary,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 5,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = "$ADVANCE_PAYMENT_NET_LABEL (${CONFIG.pay.currency})",
                color = THEME.textSecondary,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            ModernTextField(
                value = advanceInput,
                onValueChange = {
                    advanceInput = filterDecimalInput(it)
                    errorMessage = null
                },
                hintText = "0",
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier.fillMaxWidth()
            )
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

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(UI.defaultSpacing.dp)
        ) {
            RoundedButton(
                text = ADVANCE_PAYMENT_CANCEL_LABEL,
                modifier = Modifier
                    .weight(1f)
                    .height(50.dp),
                bgColor = THEME.bgCardElevated,
                textColor = THEME.textPrimary,
                textStyle = MaterialTheme.typography.labelMedium,
                onClick = onDismiss
            )
            GlowButton(
                text = ADVANCE_PAYMENT_SAVE_LABEL,
                modifier = Modifier
                    .weight(1f)
                    .height(50.dp),
                bgColor = THEME.warning,
                textColor = THEME.textDark,
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
