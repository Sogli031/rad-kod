package com.rad.shifttracker.ui.dialozi

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.rad.shifttracker.smene.Shift
import com.rad.shifttracker.smene.ShiftFillMode
import com.rad.shifttracker.ui.RoundedButton
import com.rad.shifttracker.ui.color
import com.rad.shifttracker.ui.textColor

private const val AUTO_FILL_MODE_CHOICE_HEIGHT = 65
private const val AUTO_FILL_SHIFT_CHOICE_HEIGHT = 50
private const val AUTO_FILL_MODE_CHOICE_SPACING = 8
private const val AUTO_FILL_SHIFT_CHOICE_SPACING = 6

internal data class AutoFillShiftChoice(
    val label: String,
    val shift: Shift
)

@Composable
internal fun AutoFillModeGrid(
    selectedMode: ShiftFillMode,
    onModeSelected: (ShiftFillMode) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(AUTO_FILL_MODE_CHOICE_SPACING.dp)) {
        for (rowModes in ShiftFillMode.values().toList().chunked(2)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = AUTO_FILL_MODE_CHOICE_HEIGHT.dp),
                horizontalArrangement = Arrangement.spacedBy(AUTO_FILL_MODE_CHOICE_SPACING.dp)
            ) {
                for (mode in rowModes) {
                    val isSelected = mode == selectedMode
                    RoundedButton(
                        text = mode.label,
                        modifier = Modifier
                            .weight(1f)
                            .heightIn(min = AUTO_FILL_MODE_CHOICE_HEIGHT.dp)
                            .fillMaxHeight(),
                        bgColor = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHigh,
                        textColor = if (isSelected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
                        textStyle = MaterialTheme.typography.labelLarge,
                        bold = true,
                        onClick = { onModeSelected(mode) }
                    )
                }
            }
        }
    }
}

@Composable
internal fun AutoFillShiftGrid(
    options: List<AutoFillShiftChoice>,
    selectedShift: Shift,
    onShiftSelected: (Shift) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = AUTO_FILL_SHIFT_CHOICE_HEIGHT.dp),
        horizontalArrangement = Arrangement.spacedBy(AUTO_FILL_SHIFT_CHOICE_SPACING.dp)
    ) {
        for (option in options) {
            val isSelected = option.shift == selectedShift
            RoundedButton(
                text = option.label,
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = AUTO_FILL_SHIFT_CHOICE_HEIGHT.dp)
                    .fillMaxHeight(),
                bgColor = if (isSelected) option.shift.color else option.shift.color.copy(alpha = 0.2f),
                textColor = option.shift.textColor,
                textStyle = MaterialTheme.typography.labelMedium,
                bold = true,
                onClick = { onShiftSelected(option.shift) }
            )
        }
    }
}

@Composable
internal fun AutoFillBlockDayGrid(
    selectedIndex: Int,
    onSelected: (Int) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = AUTO_FILL_SHIFT_CHOICE_HEIGHT.dp),
        horizontalArrangement = Arrangement.spacedBy(AUTO_FILL_SHIFT_CHOICE_SPACING.dp)
    ) {
        for ((label, index) in listOf("Prvi dan" to 0, "Drugi dan" to 1)) {
            val isSelected = selectedIndex == index
            RoundedButton(
                text = label,
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = AUTO_FILL_SHIFT_CHOICE_HEIGHT.dp)
                    .fillMaxHeight(),
                bgColor = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHigh,
                textColor = if (isSelected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
                textStyle = MaterialTheme.typography.labelLarge,
                bold = true,
                onClick = { onSelected(index) }
            )
        }
    }
}

internal fun defaultStartShiftForMode(mode: ShiftFillMode, current: Shift): Shift {
    return when (mode) {
        ShiftFillMode.ONE_SHIFT -> Shift.FIRST
        ShiftFillMode.FIRST_SECOND -> if (current == Shift.SECOND) Shift.SECOND else Shift.FIRST
        ShiftFillMode.THREE_BRIGADE -> if (current in Shift.workShifts()) current else Shift.THIRD
        ShiftFillMode.FOUR_BRIGADE -> if (current in Shift.workShifts() || current == Shift.OFF) {
            current
        } else {
            Shift.FIRST
        }
    }
}
