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

internal const val INITIAL_COMP_BALANCE_DIALOG_TITLE = "Početni bilans sati"
internal const val INITIAL_COMP_BALANCE_DIALOG_DESCRIPTION =
    "Unesi broj sati koji već imaš u plusu pre početka korišćenja aplikacije."
internal const val INITIAL_COMP_BALANCE_MINUTES_NOTE =
    "Minute upiši kao decimalu: 15min = .25, 30min = .50, 45min = .75."
internal const val INITIAL_COMP_BALANCE_SAVE_LABEL = "Sačuvaj"
internal const val INITIAL_COMP_BALANCE_CANCEL_LABEL = "Otkaži"
internal const val INITIAL_COMP_BALANCE_RESET_LABEL = "Resetuj na 0"
private const val MINUTES_PER_HOUR = 60

internal fun filterInitialCompBalanceInput(input: String): String {
    var separatorUsed = false
    return buildString {
        for (char in input) {
            when {
                char.isDigit() -> append(char)
                (char == '.' || char == ',') && !separatorUsed -> {
                    append('.')
                    separatorUsed = true
                }
            }
        }
    }
}

internal fun parseInitialCompBalanceInputMinutes(input: String): Int? {
    val normalized = input.trim().replace(',', '.')
    if (normalized.isBlank()) return null
    val parts = normalized.split('.')
    if (parts.size > 2) return null

    val hours = when {
        parts[0].isBlank() -> 0
        else -> parts[0].toIntOrNull() ?: return null
    }
    val minutes = when (parts.getOrNull(1).orEmpty()) {
        "", "0", "00" -> 0
        "25" -> 15
        "5", "50" -> 30
        "75" -> 45
        else -> return null
    }
    val totalMinutes = hours.toLong() * MINUTES_PER_HOUR + minutes
    return if (totalMinutes <= Int.MAX_VALUE) totalMinutes.toInt() else null
}

internal fun formatInitialCompBalanceInput(minutes: Int): String {
    val safeMinutes = minutes.coerceAtLeast(0)
    val hours = safeMinutes / MINUTES_PER_HOUR
    return when (safeMinutes % MINUTES_PER_HOUR) {
        15 -> "$hours.25"
        30 -> "$hours.5"
        45 -> "$hours.75"
        else -> hours.toString()
    }
}

@Composable
fun InitialCompBalanceDialog(
    repo: ShiftRepository,
    onDismiss: () -> Unit
) {
    var balanceInput by remember { mutableStateOf(formatInitialCompBalanceInput(repo.initialCompBalanceMinutesValue)) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    PopupDialog(
        title = INITIAL_COMP_BALANCE_DIALOG_TITLE,
        onDismiss = onDismiss,
        widthRatio = 0.85f,
        scrollable = true,
        accent = THEME.compTime
    ) {
        PopupCard(bgColor = THEME.bgCardElevated, borderColor = THEME.compTime) {
            Text(
                text = INITIAL_COMP_BALANCE_DIALOG_DESCRIPTION,
                color = THEME.textSecondary,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = INITIAL_COMP_BALANCE_MINUTES_NOTE,
                color = THEME.textMuted,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis
            )
            ModernTextField(
                value = balanceInput,
                onValueChange = {
                    balanceInput = filterInitialCompBalanceInput(it)
                    errorMessage = null
                },
                hintText = "Broj sati...",
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
                text = INITIAL_COMP_BALANCE_RESET_LABEL,
                modifier = Modifier
                    .weight(1f)
                    .height(50.dp),
                bgColor = THEME.bgCardElevated,
                textColor = THEME.textPrimary,
                textStyle = MaterialTheme.typography.labelMedium,
                onClick = {
                    repo.initialCompBalanceMinutesValue = 0
                    onDismiss()
                }
            )
            RoundedButton(
                text = INITIAL_COMP_BALANCE_CANCEL_LABEL,
                modifier = Modifier
                    .weight(1f)
                    .height(50.dp),
                bgColor = THEME.bgCardElevated,
                textColor = THEME.textPrimary,
                textStyle = MaterialTheme.typography.labelMedium,
                onClick = onDismiss
            )
        }

        GlowButton(
            text = INITIAL_COMP_BALANCE_SAVE_LABEL,
            modifier = Modifier
                .fillMaxWidth()
                .height(50.dp),
            bgColor = THEME.compTime,
            textColor = THEME.textPrimary,
            textStyle = MaterialTheme.typography.labelLarge,
            onClick = {
                val value = parseInitialCompBalanceInputMinutes(balanceInput)
                if (value == null) {
                    errorMessage = "Unesi sate kao broj, za minute koristi .25, .50 ili .75."
                    return@GlowButton
                }
                repo.initialCompBalanceMinutesValue = value
                onDismiss()
            }
        )
    }
}
