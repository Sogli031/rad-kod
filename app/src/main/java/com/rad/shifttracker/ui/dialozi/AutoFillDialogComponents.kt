package com.rad.shifttracker.ui.dialozi

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.rad.shifttracker.smene.Shift
import com.rad.shifttracker.smene.ShiftFillMode
import com.rad.shifttracker.ui.emphasized

private const val AUTO_FILL_MODE_CHOICE_HEIGHT = 65
private const val AUTO_FILL_SHIFT_CHOICE_HEIGHT = 50
private const val AUTO_FILL_MODE_CHOICE_SPACING = 8
private const val AUTO_FILL_BORDER_ALPHA = 0.5f

internal data class AutoFillShiftChoice(
    val label: String,
    val shift: Shift
)

/** Neutralni izbor (režim, dan u bloku): izabrano `primary`, ostalo tamna podloga — kao do sad. */
@Composable
private fun autoFillNeutralColors(): ChoiceButtonColors {
    val scheme = MaterialTheme.colorScheme
    return ChoiceButtonColors(
        selectedContainer = scheme.primary,
        container = scheme.surfaceContainerHigh,
        selectedContent = scheme.onPrimary,
        content = scheme.onSurface,
        selectedBorder = scheme.primary,
        border = scheme.outlineVariant.copy(alpha = AUTO_FILL_BORDER_ALPHA)
    )
}

@Composable
internal fun AutoFillModeGrid(
    selectedMode: ShiftFillMode,
    onModeSelected: (ShiftFillMode) -> Unit
) {
    val colors = autoFillNeutralColors()
    Column(
        modifier = Modifier.selectableGroup(),
        verticalArrangement = Arrangement.spacedBy(AUTO_FILL_MODE_CHOICE_SPACING.dp)
    ) {
        for (rowModes in ShiftFillMode.values().toList().chunked(2)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = AUTO_FILL_MODE_CHOICE_HEIGHT.dp),
                horizontalArrangement = Arrangement.spacedBy(AUTO_FILL_MODE_CHOICE_SPACING.dp)
            ) {
                for (mode in rowModes) {
                    AnimatedChoiceButton(
                        text = mode.label,
                        selected = mode == selectedMode,
                        colors = colors,
                        onClick = { onModeSelected(mode) },
                        modifier = Modifier.weight(1f).fillMaxHeight(),
                        minHeight = AUTO_FILL_MODE_CHOICE_HEIGHT.dp,
                        shape = CircleShape,
                        textStyle = MaterialTheme.typography.labelLarge.emphasized()
                    )
                }
            }
        }
    }
}

/** Izbor smene kao „Izaberi smenu“ u prozoru dana: jedan red segmenata, izabrani sa kvačicom. */
@Composable
internal fun AutoFillShiftGrid(
    options: List<AutoFillShiftChoice>,
    selectedShift: Shift,
    onShiftSelected: (Shift) -> Unit
) {
    DayEditSegmentedChoice(
        options = options.map { option ->
            DayEditChoice(option.label, option.shift == selectedShift) { onShiftSelected(option.shift) }
        },
        minHeight = AUTO_FILL_SHIFT_CHOICE_HEIGHT
    )
}

/** „Prvi dan / Drugi dan“ — isti segmentni izbor kao smena iznad. */
@Composable
internal fun AutoFillBlockDayGrid(
    selectedIndex: Int,
    onSelected: (Int) -> Unit
) {
    DayEditSegmentedChoice(
        options = listOf("Prvi dan" to 0, "Drugi dan" to 1).map { (label, index) ->
            DayEditChoice(label, selectedIndex == index) { onSelected(index) }
        },
        minHeight = AUTO_FILL_SHIFT_CHOICE_HEIGHT
    )
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
