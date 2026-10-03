package com.rad.shifttracker

import androidx.compose.ui.text.input.ImeAction
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.rad.shifttracker.ui.components.AutoSizeText
import java.time.LocalDate

// Existing dialog flow is intentionally grouped; detekt stays active for newly added methods.
@Suppress("LongMethod")
@Composable
fun AutoFillDialog(
    today: LocalDate,
    scheduler: ShiftScheduler,
    repo: ShiftRepository,
    onDismiss: () -> Unit
) {
    var fillMode by remember { mutableStateOf(ShiftFillMode.ONE_SHIFT) }
    var startShift by remember { mutableStateOf(Shift.FIRST) }
    var cycleBlockDayIndex by remember { mutableIntStateOf(0) }
    var monthsInput by remember { mutableStateOf("12") }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    val referenceWorkWeek = remember(today) { scheduler.getReferenceWorkWeek(today) }
    val referenceWeekText = remember(referenceWorkWeek) { formatReferenceWorkWeek(referenceWorkWeek) }
    val fourBrigadeReferenceDateText = remember(today) { formatAutoFillReferenceDate(today) }

    PopupDialog(
        title = "Auto-popuna",
        onDismiss = onDismiss,
        scrollable = true
    ) {
        PopupCard {
            AutoSizeText(
                text = "Automatsko popunjavanje smena",
                color = MaterialTheme.colorScheme.onSurface,
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                maxFontSize = MaterialTheme.typography.titleMedium.fontSize,
                minFontSize = MaterialTheme.typography.titleSmall.fontSize,
                maxLines = 1
            )
            Text(
                text = "Tekući mesec: ${MONTH_NAMES[today.monthValue]} ${today.year}",
                color = MaterialTheme.extendedColors.textSecondary,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (fillMode != ShiftFillMode.ONE_SHIFT) {
                Text(
                    text = when (fillMode) {
                        ShiftFillMode.FIRST_SECOND,
                        ShiftFillMode.THREE_BRIGADE -> "Referentna sedmica: $referenceWeekText"
                        ShiftFillMode.FOUR_BRIGADE -> "Početni dan ciklusa: $fourBrigadeReferenceDateText"
                        ShiftFillMode.ONE_SHIFT -> ""
                    },
                    color = MaterialTheme.extendedColors.textSecondary,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }

        PopupCard {
            SectionLabel(text = "Režim smena")
            AutoFillModeGrid(
                selectedMode = fillMode,
                onModeSelected = { mode ->
                    fillMode = mode
                    startShift = defaultStartShiftForMode(mode, startShift)
                    cycleBlockDayIndex = cycleBlockDayIndex.coerceIn(0, 1)
                    errorMessage = null
                }
            )
        }

        when (fillMode) {
            ShiftFillMode.ONE_SHIFT -> Unit
            ShiftFillMode.FIRST_SECOND -> {
                PopupCard {
                    SectionLabel(text = "Koja je smena u toj sedmici?")
                    AutoFillShiftGrid(
                        options = listOf(
                            AutoFillShiftChoice(dayEditShiftButtonText(Shift.FIRST), Shift.FIRST),
                            AutoFillShiftChoice(dayEditShiftButtonText(Shift.SECOND), Shift.SECOND)
                        ),
                        selectedShift = startShift,
                        onShiftSelected = {
                            startShift = it
                            errorMessage = null
                        }
                    )
                }
            }
            ShiftFillMode.THREE_BRIGADE -> {
                PopupCard {
                    SectionLabel(text = "Koja je smena u toj sedmici?")
                    AutoFillShiftGrid(
                        options = listOf(
                            AutoFillShiftChoice(dayEditShiftButtonText(Shift.FIRST), Shift.FIRST),
                            AutoFillShiftChoice(dayEditShiftButtonText(Shift.SECOND), Shift.SECOND),
                            AutoFillShiftChoice(dayEditShiftButtonText(Shift.THIRD), Shift.THIRD)
                        ),
                        selectedShift = startShift,
                        onShiftSelected = {
                            startShift = it
                            errorMessage = null
                        }
                    )
                }
            }
            ShiftFillMode.FOUR_BRIGADE -> {
                PopupCard {
                    SectionLabel(text = "Koja si smena u početnom danu ciklusa?")
                    AutoFillShiftGrid(
                        options = listOf(
                            AutoFillShiftChoice(dayEditShiftButtonText(Shift.FIRST), Shift.FIRST),
                            AutoFillShiftChoice(dayEditShiftButtonText(Shift.SECOND), Shift.SECOND),
                            AutoFillShiftChoice(dayEditShiftButtonText(Shift.THIRD), Shift.THIRD),
                            AutoFillShiftChoice("Slobodan", Shift.OFF)
                        ),
                        selectedShift = startShift,
                        onShiftSelected = {
                            startShift = it
                            errorMessage = null
                        }
                    )
                }
                PopupCard {
                    SectionLabel(text = "Koji je dan u tom bloku?")
                    AutoFillBlockDayGrid(
                        selectedIndex = cycleBlockDayIndex,
                        onSelected = {
                            cycleBlockDayIndex = it
                            errorMessage = null
                        }
                    )
                }
            }
        }

        PopupCard {
            SectionLabel(text = "Meseci unapred")
            ModernTextField(
                value = monthsInput,
                onValueChange = {
                    monthsInput = it.filter(Char::isDigit)
                    errorMessage = null
                },
                hintText = "Unesi broj meseci unapred...",
                label = "Broj meseci",
                isError = errorMessage != null,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
                modifier = Modifier.fillMaxWidth()
            )
        }

        errorMessage?.let { message ->
            FormErrorText(message = message)
        }

        GlowButton(
            text = "Popuni smene",
            modifier = Modifier
                .fillMaxWidth()
                .height(50.dp),
            textStyle = MaterialTheme.typography.labelLarge,
            onClick = {
                val monthsAhead = monthsInput.toIntOrNull()
                when {
                    monthsAhead == null -> {
                        errorMessage = "Unesi broj meseci."
                        return@GlowButton
                    }
                    monthsAhead < 1 -> {
                        errorMessage = "Broj meseci mora biti najmanje 1."
                        return@GlowButton
                    }
                    monthsAhead > 24 -> {
                        errorMessage = "Maksimalno 24 meseca unapred."
                        return@GlowButton
                    }
                }
                val settings = when (fillMode) {
                    ShiftFillMode.ONE_SHIFT -> ShiftFillSettings(
                        mode = fillMode,
                        anchorDate = today,
                        selectedShift = Shift.FIRST
                    )
                    ShiftFillMode.FIRST_SECOND,
                    ShiftFillMode.THREE_BRIGADE -> ShiftFillSettings(
                        mode = fillMode,
                        anchorDate = referenceWorkWeek.start,
                        selectedShift = startShift
                    )
                    ShiftFillMode.FOUR_BRIGADE -> ShiftFillSettings(
                        mode = fillMode,
                        anchorDate = today,
                        selectedShift = startShift,
                        cycleDayIndex = scheduler.getFourBrigadeCycleStartIndex(
                            startShift,
                            cycleBlockDayIndex
                        )
                    )
                }
                scheduler.populateMonthsAhead(
                    repo,
                    today,
                    settings,
                    monthsAhead
                )
                onDismiss()
            }
        )
    }
}

