package com.rad.shifttracker.ui.dialozi

import androidx.compose.ui.text.input.ImeAction
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import java.time.DateTimeException
import java.time.LocalDate
import com.rad.shifttracker.osnova.WEEKDAY_NAMES
import com.rad.shifttracker.repo.ShiftRepository
import com.rad.shifttracker.ui.PopupDialog
import com.rad.shifttracker.ui.labelMediumEmphasized
import com.rad.shifttracker.osnova.UI
import com.rad.shifttracker.smene.HolidayEntry
import com.rad.shifttracker.smene.HolidayEntrySource
import com.rad.shifttracker.ui.FormErrorText
import com.rad.shifttracker.ui.LabeledCheckboxRow
import com.rad.shifttracker.ui.ModernTextField
import com.rad.shifttracker.ui.PopupCard
import com.rad.shifttracker.ui.RoundedButton
import com.rad.shifttracker.ui.SectionLabel
import com.rad.shifttracker.ui.Spacing
import com.rad.shifttracker.ui.bodyLargeEmphasized
import com.rad.shifttracker.ui.extendedColors

@Composable
fun HolidayManagementDialog(
    year: Int,
    repo: ShiftRepository,
    refreshKey: Int,
    onDismiss: () -> Unit
) {
    var dayInput by remember { mutableStateOf("") }
    var monthInput by remember { mutableStateOf("") }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    val entries = remember(refreshKey, year) { repo.getHolidayEntries(year) }

    PopupDialog(
        title = "Praznici $year",
        onDismiss = onDismiss,
        scrollable = true
    ) {
        PopupCard {
            SectionLabel(text = "Dodaj ručni praznik")
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                verticalAlignment = Alignment.CenterVertically
            ) {
                ModernTextField(
                    value = dayInput,
                    onValueChange = { dayInput = filterDigits(it).take(2) },
                    hintText = "1-31",
                    label = "Dan",
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Next),
                    modifier = Modifier.weight(1f)
                )
                ModernTextField(
                    value = monthInput,
                    onValueChange = { monthInput = filterDigits(it).take(2) },
                    hintText = "1-12",
                    label = "Mesec",
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
                    modifier = Modifier.weight(1f)
                )
                RoundedButton(
                    text = "DODAJ",
                    modifier = Modifier
                        .weight(1f)
                        .height(UI.buttonHeight.dp),
                    bgColor = MaterialTheme.extendedColors.warning,
                    textColor = MaterialTheme.colorScheme.onPrimary,
                    textStyle = MaterialTheme.typography.labelLarge,
                    bold = true,
                    onClick = {
                        val day = dayInput.toIntOrNull()
                        val month = monthInput.toIntOrNull()
                        val validDate = try {
                            if (day == null || month == null) {
                                null
                            } else {
                                LocalDate.of(year, month, day)
                            }
                        } catch (_: DateTimeException) {
                            null
                        }

                        if (validDate == null) {
                            errorMessage = "Unesi ispravan datum za $year."
                        } else {
                            repo.setHolidayActive(year, validDate.monthValue, validDate.dayOfMonth, isActive = true)
                            dayInput = ""
                            monthInput = ""
                            errorMessage = null
                        }
                    }
                )
            }
            errorMessage?.let { message ->
                FormErrorText(message = message)
            }
        }

        PopupCard {
            SectionLabel(text = "Lista praznika")
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(Spacing.sm)
            ) {
                entries.forEach { entry ->
                    HolidayEntryRow(
                        entry = entry,
                        onActiveChange = { checked ->
                            repo.setHolidayActive(entry.year, entry.month, entry.day, checked)
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun HolidayEntryRow(
    entry: HolidayEntry,
    onActiveChange: (Boolean) -> Unit
) {
    val date = remember(entry.year, entry.month, entry.day) {
        LocalDate.of(entry.year, entry.month, entry.day)
    }
    val sourceText = when (entry.source) {
        HolidayEntrySource.OFFICIAL -> "Zvanični"
        HolidayEntrySource.MANUAL -> "Ručni"
    }
    val stateColor = if (entry.isActive) MaterialTheme.extendedColors.warning else MaterialTheme.colorScheme.onSurfaceVariant
    LabeledCheckboxRow(
        checked = entry.isActive,
        onCheckedChange = onActiveChange,
        modifier = Modifier
            .heightIn(min = 64.dp)
            .clip(MaterialTheme.shapes.medium)
            .background(MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.72f)),
        checkboxColors = CheckboxDefaults.colors(
            checkedColor = MaterialTheme.extendedColors.warning,
            uncheckedColor = MaterialTheme.colorScheme.onSurfaceVariant,
            checkmarkColor = MaterialTheme.colorScheme.onPrimary
        ),
        contentPadding = PaddingValues(horizontal = Spacing.sm, vertical = Spacing.sm)
    ) {
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(Spacing.xxs)
        ) {
            Text(
                text = entry.name,
                color = MaterialTheme.colorScheme.onSurface,
                style = MaterialTheme.typography.bodyLargeEmphasized,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = "%02d.%02d.%04d. | ${WEEKDAY_NAMES[date.dayOfWeek.ordinal]} | $sourceText".format(
                    entry.day,
                    entry.month,
                    entry.year
                ),
                color = MaterialTheme.extendedColors.textSecondary,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        // „Da/Ne“ samo ponavlja stanje potvrdnog polja, pa je skrivena od čitača ekrana.
        Box(
            modifier = Modifier
                .clearAndSetSemantics { }
                .background(stateColor.copy(alpha = 0.18f), MaterialTheme.shapes.small)
                .padding(horizontal = Spacing.sm, vertical = Spacing.xs)
        ) {
            Text(
                text = if (entry.isActive) "Da" else "Ne",
                color = stateColor,
                style = MaterialTheme.typography.labelMediumEmphasized,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

private fun filterDigits(value: String): String {
    return value.filter { it.isDigit() }
}
