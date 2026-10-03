package com.rad.shifttracker

import androidx.compose.ui.text.input.ImeAction
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp

@Composable
fun FoodAllowanceDialog(
    year: Int,
    month: Int,
    repo: ShiftRepository,
    onDismiss: () -> Unit
) {
    val selectedMonth = MonthKey(year, month)
    val selectedPrice = repo.getFoodPerDayForMonth(year, month)
    var foodInput by remember(selectedMonth) { mutableStateOf(formatRateInput(selectedPrice)) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    PopupDialog(
        title = "Hrana",
        onDismiss = onDismiss,
        scrollable = true
    ) {
        PopupCard {
            SectionLabel(text = "Dnevni iznos od ${monthYearText(selectedMonth)}")
            Text(
                text = "Za izabrani mesec: ${formatRateInput(selectedPrice)} ${CONFIG.pay.currency}",
                color = MaterialTheme.extendedColors.textSecondary,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            ModernTextField(
                value = foodInput,
                onValueChange = {
                    foodInput = filterDecimalInput(it)
                    errorMessage = null
                },
                hintText = "Iznos hrane...",
                label = "Iznos hrane (${CONFIG.pay.currency})",
                isError = errorMessage != null,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Done),
                modifier = Modifier.fillMaxWidth()
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
                val newValue = foodInput.toDoubleOrNull()
                if (newValue == null || newValue < 0) {
                    errorMessage = "Unesi ispravan iznos."
                    return@GlowButton
                }
                repo.setFoodPerDayFromMonth(year, month, newValue)
                onDismiss()
            }
        )
    }
}

