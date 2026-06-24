package com.rad.shifttracker

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
    repo: ShiftRepository,
    onDismiss: () -> Unit
) {
    var foodInput by remember { mutableStateOf(formatRateInput(repo.foodPerDayValue)) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    PopupDialog(
        title = "Hrana",
        onDismiss = onDismiss,
        widthRatio = 0.85f,
        scrollable = true,
        accent = THEME.accentSecondary
    ) {
        PopupCard(bgColor = THEME.bgCardElevated, borderColor = THEME.accentSecondary) {
            SectionLabel(text = "Globalni dnevni iznos")
            Text(
                text = "Trenutno po danu: ${formatRateInput(repo.foodPerDayValue)} ${CONFIG.pay.currency}",
                color = THEME.textSecondary,
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

        GlowButton(
            text = "Sa\u010duvaj",
            modifier = Modifier
                .fillMaxWidth()
                .height(50.dp),
            bgColor = THEME.accentSecondary,
            textColor = THEME.textPrimary,
            textStyle = MaterialTheme.typography.labelLarge,
            onClick = {
                val newValue = foodInput.toDoubleOrNull()
                if (newValue == null || newValue < 0) {
                    errorMessage = "Unesi ispravan iznos."
                    return@GlowButton
                }
                repo.foodPerDayValue = newValue
                onDismiss()
            }
        )
    }
}

