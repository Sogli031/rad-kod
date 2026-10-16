package com.rad.shifttracker.ui.dialozi

import com.rad.shifttracker.ui.DataActions
import androidx.compose.animation.animateContentSize
import com.rad.shifttracker.ui.RadMotion
import com.rad.shifttracker.ui.rememberReducedMotion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.heading
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.rad.shifttracker.ui.components.AutoSizeText
import com.rad.shifttracker.osnova.CONFIG
import com.rad.shifttracker.osnova.UI
import com.rad.shifttracker.osnova.formatNumber
import com.rad.shifttracker.smene.OvertimeType
import com.rad.shifttracker.smene.Shift
import com.rad.shifttracker.ui.GlowButton
import com.rad.shifttracker.ui.PopupCard
import com.rad.shifttracker.ui.SectionLabel
import com.rad.shifttracker.ui.Spacing
import com.rad.shifttracker.ui.extendedColors
import com.rad.shifttracker.osnova.formatHours
import com.rad.shifttracker.osnova.formatHoursAndMinutes
import com.rad.shifttracker.osnova.formatShortageMinutes
import com.rad.shifttracker.godisnji.VacationRequestValidation
import com.rad.shifttracker.ui.CARD_BORDER_ALPHA
import com.rad.shifttracker.ui.LabeledSwitchRow
import com.rad.shifttracker.ui.bodyMediumEmphasized
import com.rad.shifttracker.ui.labelLargeEmphasized
import com.rad.shifttracker.ui.radClickable
import com.rad.shifttracker.ui.titleMediumEmphasized

private const val DAY_EDIT_SHIFT_CHOICE_HEIGHT = 48
private const val DISABLED_CHOICE_ALPHA = 0.45f

/**
 * Neto procena kao dva mirna reda (etiketa levo, iznos desno) — bez zelenog okvira.
 * „Razlika“ uključuje i ostale dane opsega „Do kog dana“ ([rangeNetDifference]).
 */
@Composable
internal fun DayEditStatusHeader(
    estimatedNetDailyPay: Double,
    savedNetDailyPay: Double?,
    rangeNetDifference: Double = 0.0
) {
    val totalDifference = (savedNetDailyPay?.let { estimatedNetDailyPay - it } ?: 0.0) + rangeNetDifference
    val netDifferenceText = formatDayEditNetDifference(
        savedNetPay = if (savedNetDailyPay != null || rangeNetDifference != 0.0) 0.0 else null,
        currentNetPay = totalDifference,
        currency = CONFIG.pay.currency
    )
    val netDifferenceColor = when {
        totalDifference < 0.0 -> MaterialTheme.colorScheme.error
        else -> MaterialTheme.extendedColors.success
    }

    if (estimatedNetDailyPay > 0.0 || netDifferenceText != null) {
        val reducedMotion = rememberReducedMotion()
        // Red „Razlika“ se pojavljuje pri izmeni; kartica raste glatko, ne skokom.
        PopupCard(
            modifier = if (reducedMotion) Modifier else Modifier.animateContentSize(RadMotion.spatial()),
            spacing = Spacing.sm
        ) {
            DayEditValueRow(
                label = "Neto procena",
                value = "${formatNumber(estimatedNetDailyPay)} ${CONFIG.pay.currency}",
                valueColor = MaterialTheme.colorScheme.onSurface
            )
            netDifferenceText?.let { text ->
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = CARD_BORDER_ALPHA))
                DayEditValueRow(
                    label = "Razlika",
                    value = text.removePrefix("Razlika: "),
                    valueColor = netDifferenceColor
                )
            }
        }
    }
}

@Composable
private fun DayEditValueRow(label: String, value: String, valueColor: Color) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.sm)
    ) {
        Text(
            text = label,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        AutoSizeText(
            text = value,
            color = valueColor,
            style = MaterialTheme.typography.titleMediumEmphasized.copy(
                textAlign = TextAlign.End
            ),
            maxFontSize = MaterialTheme.typography.titleMedium.fontSize,
            minFontSize = MaterialTheme.typography.bodyMedium.fontSize,
            maxLines = 1
        )
    }
}

/** Praznik: tonirana žuta traka, bez posebne kartice i naslova. */
@Composable
internal fun DayEditHolidayWarningStrip(text: String) {
    val shape = MaterialTheme.shapes.small
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(MaterialTheme.extendedColors.warning.copy(alpha = 0.14f))
            .border(1.dp, MaterialTheme.extendedColors.warning.copy(alpha = 0.35f), shape)
            .padding(horizontal = Spacing.md, vertical = Spacing.sm)
    ) {
        Text(
            text = text,
            color = MaterialTheme.extendedColors.warning,
            style = MaterialTheme.typography.bodyMediumEmphasized,
            modifier = Modifier.fillMaxWidth(),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
    }
}

/**
 * Dugme izbora u jednoj boji teme: izabrano = puna plava (kao „Sačuvaj“),
 * neizabrano = neutralna tamna podloga sa sivim tekstom.
 */
internal class DayEditChoice(val text: String, val selected: Boolean, val onClick: () -> Unit)

/**
 * Izbor jedne od nekoliko vrednosti kao M3 `SingleChoiceSegmentedButtonRow`: izabrani segment je
 * popunjen akcentom (`primary`), ostali su neutralni; svaki je radio dugme za čitač ekrana.
 */
@Composable
internal fun DayEditSegmentedChoice(options: List<DayEditChoice>, minHeight: Int) {
    val scheme = MaterialTheme.colorScheme
    val colors = SegmentedButtonDefaults.colors(
        activeContainerColor = scheme.primary,
        activeContentColor = scheme.onPrimary,
        activeBorderColor = scheme.primary,
        inactiveContainerColor = scheme.surfaceContainerHigh,
        inactiveContentColor = MaterialTheme.extendedColors.textSecondary,
        inactiveBorderColor = scheme.outlineVariant.copy(alpha = CARD_BORDER_ALPHA)
    )
    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
        options.forEachIndexed { index, option ->
            SegmentedButton(
                selected = option.selected,
                onClick = option.onClick,
                shape = SegmentedButtonDefaults.itemShape(index = index, count = options.size),
                modifier = Modifier.heightIn(min = minHeight.dp),
                colors = colors,
                label = {
                    AutoSizeText(
                        text = option.text,
                        style = (if (option.selected) MaterialTheme.typography.labelLargeEmphasized else MaterialTheme.typography.labelLarge)
                            .copy(textAlign = TextAlign.Center),
                        maxFontSize = MaterialTheme.typography.labelLarge.fontSize,
                        minFontSize = MaterialTheme.typography.labelSmall.fontSize,
                        maxLines = 1
                    )
                }
            )
        }
    }
}

/** Izbor u prozoru dana i odsustva: izgled kao do sad, a promena izbora animirana sa kvačicom. */
@Composable
internal fun RowScope.DayEditChoiceButton(
    text: String,
    selected: Boolean,
    minHeight: Int,
    enabled: Boolean = true,
    onClick: () -> Unit
) {
    val scheme = MaterialTheme.colorScheme
    AnimatedChoiceButton(
        text = text,
        selected = selected,
        colors = ChoiceButtonColors(
            selectedContainer = scheme.primary,
            container = scheme.surfaceContainerHigh,
            selectedContent = scheme.onPrimary,
            content = MaterialTheme.extendedColors.textSecondary,
            selectedBorder = scheme.primary,
            border = scheme.outlineVariant.copy(alpha = CARD_BORDER_ALPHA)
        ),
        onClick = onClick,
        modifier = Modifier.weight(1f),
        minHeight = minHeight.dp,
        enabled = enabled,
        disabledAlpha = DISABLED_CHOICE_ALPHA,
        textStyle = MaterialTheme.typography.labelLarge,
        selectedTextStyle = MaterialTheme.typography.labelLargeEmphasized
    )
}

@Composable
internal fun DayEditDayTypeSelector(
    selectedShift: Shift,
    paidLeaveSelected: Boolean,
    onWorkSelected: () -> Unit,
    onSickSelected: () -> Unit,
    onPaidLeaveSelected: () -> Unit,
    onVacationSelected: () -> Unit,
    onOffSelected: () -> Unit
) {
    val isWorkDaySelected = selectedShift in Shift.workShifts()
    Row(
        modifier = Modifier.fillMaxWidth().selectableGroup(),
        horizontalArrangement = Arrangement.spacedBy(Spacing.sm)
    ) {
        DayEditChoiceButton(
            text = "Radio sam",
            selected = isWorkDaySelected,
            minHeight = DAY_TYPE_CHOICE_HEIGHT,
            onClick = onWorkSelected
        )
        DayEditChoiceButton(
            text = "Bolovanje",
            selected = selectedShift == Shift.SICK && !paidLeaveSelected,
            minHeight = DAY_TYPE_CHOICE_HEIGHT,
            onClick = onSickSelected
        )
    }
    Row(
        modifier = Modifier.fillMaxWidth().selectableGroup(),
        horizontalArrangement = Arrangement.spacedBy(Spacing.sm)
    ) {
        DayEditChoiceButton(
            text = "Godišnji",
            selected = selectedShift == Shift.VACATION,
            minHeight = DAY_TYPE_CHOICE_HEIGHT,
            onClick = onVacationSelected
        )
        DayEditChoiceButton(
            text = DAY_EDIT_PAID_LEAVE_LABEL,
            selected = selectedShift == Shift.SICK && paidLeaveSelected,
            minHeight = DAY_TYPE_CHOICE_HEIGHT,
            onClick = onPaidLeaveSelected
        )
    }
    Row(
        modifier = Modifier.fillMaxWidth().selectableGroup(),
        horizontalArrangement = Arrangement.spacedBy(Spacing.sm)
    ) {
        DayEditChoiceButton(
            text = dayEditShiftButtonText(Shift.OFF),
            selected = selectedShift == Shift.OFF,
            minHeight = DAY_TYPE_CHOICE_HEIGHT,
            onClick = onOffSelected
        )
    }
}

@Composable
internal fun DayEditWorkControls(
    state: DayEditWorkControlState,
    onPrimaryShiftSelected: (Shift) -> Unit,
    onRegularHoursClick: () -> Unit,
    onShortageClick: () -> Unit,
    onDoubleShiftChanged: (Boolean) -> Unit,
    additionalWorkContent: @Composable ColumnScope.() -> Unit
) {
    DayEditSectionCard(title = "Smena") {
        DayEditShiftSelector(
            question = DAY_EDIT_PRIMARY_SHIFT_QUESTION_TEXT,
            selectedShift = state.shift1,
            onSelected = onPrimaryShiftSelected
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(Spacing.sm)
        ) {
            DayEditValueButton(
                label = "Redovni sati",
                value = formatHours(state.workHours1),
                modifier = Modifier.weight(1f),
                onClick = onRegularHoursClick
            )
            DayEditValueButton(
                label = "Minuti",
                value = formatShortageMinutes(state.effectiveRegularShortageMinutes),
                modifier = Modifier.weight(1f),
                enabled = state.shortageMinutesAllowed,
                onClick = onShortageClick
            )
        }
    }
    if (!state.showOvertimeCard) return
    DayEditSectionCard(title = "Prekovremeno") {
        if (state.showDoubleSwitch) {
            LabeledSwitchRow(
                title = "Dodatna smena posle redovne",
                checked = state.hasDouble,
                onCheckedChange = onDoubleShiftChanged,
                titleStyle = MaterialTheme.typography.bodyMedium
            )
        }
        additionalWorkContent()
    }
}

/** Dugme sa etiketom i vrednošću (npr. „Redovni sati · 8h“) koje otvara birač. */
@Composable
private fun DayEditValueButton(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onClick: () -> Unit
) {
    val shape = MaterialTheme.shapes.small
    val alpha = if (enabled) 1f else 0.45f
    Column(
        modifier = modifier
            .heightIn(min = UI.buttonHeight.dp)
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = CARD_BORDER_ALPHA), shape)
            .radClickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = Spacing.md, vertical = Spacing.sm),
        verticalArrangement = Arrangement.spacedBy(Spacing.xxs)
    ) {
        Text(
            text = label,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = alpha),
            style = MaterialTheme.typography.labelSmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Text(
            text = value,
            color = MaterialTheme.colorScheme.primary.copy(alpha = alpha),
            style = MaterialTheme.typography.titleMediumEmphasized,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun DayEditShiftSelector(
    question: String,
    selectedShift: Shift,
    shifts: List<Shift> = Shift.workShifts(),
    onSelected: (Shift) -> Unit
) {
    DayEditChoiceHeader(text = question)
    DayEditSegmentedChoice(
        options = shifts.map { shift ->
            DayEditChoice(dayEditShiftButtonText(shift), shift == selectedShift) { onSelected(shift) }
        },
        minHeight = DAY_EDIT_SHIFT_CHOICE_HEIGHT
    )
}

/** „Plaćeno / Za sate“ — jedan red, isti stil za sva pitanja o dodatnom radu. */
@Composable
private fun DayEditOvertimeTypeSelector(
    question: String,
    selected: OvertimeType,
    onSelected: (OvertimeType) -> Unit
) {
    DayEditChoiceHeader(text = question)
    DayEditSegmentedChoice(
        options = listOf(
            DayEditChoice("Plaćeno", selected == OvertimeType.PAID) { onSelected(OvertimeType.PAID) },
            DayEditChoice("Za sate", selected == OvertimeType.COMP_TIME) { onSelected(OvertimeType.COMP_TIME) }
        ),
        minHeight = OVERTIME_TYPE_CHOICE_HEIGHT
    )
}

@Composable
internal fun DayEditAdditionalWorkControls(
    state: DayEditAdditionalWorkState,
    onSecondShiftSelected: (Shift) -> Unit,
    onOvertimeHoursClick: () -> Unit,
    onOvertimeTypeSelected: (OvertimeType) -> Unit,
    onWeekendOvertimeTypeSelected: (OvertimeType) -> Unit
) {
    if (state.hasDouble) {
        DayEditShiftSelector(
            question = "Koja je dodatna smena?",
            selectedShift = state.shift2,
            shifts = state.secondShiftOptions,
            onSelected = onSecondShiftSelected
        )
        DayEditValueButton(
            label = "Prekovremeni sati",
            value = formatHours(state.workHours2),
            modifier = Modifier.fillMaxWidth(),
            onClick = onOvertimeHoursClick
        )
        DayEditOvertimeTypeSelector(
            question = "Kako se vodi prekovremeno?",
            selected = state.overtimeType,
            onSelected = { selected ->
                // Vikend/praznik + dodatna smena: jedan odgovor važi za oba dela dana.
                onOvertimeTypeSelected(selected)
                if (state.hasPrimaryNonRegularAdditionalWork) onWeekendOvertimeTypeSelected(selected)
            }
        )
    }

    if (state.hasPrimaryNonRegularAdditionalWork && !state.hasDouble) {
        DayEditOvertimeTypeSelector(
            question = "Kako se vodi dodatni rad?",
            selected = state.weekendOvertimeType,
            onSelected = onWeekendOvertimeTypeSelected
        )
    }

    if (state.showPrimaryExtraHoursMode) {
        DayEditOvertimeTypeSelector(
            question = "Kako se vode sati preko redovne smene?",
            selected = state.overtimeType,
            onSelected = onOvertimeTypeSelected
        )
    }
}

@Composable
internal fun DayEditSickControls(
    sickPayRate: Double,
    onSickPayRateSelected: (Double) -> Unit
) {
    DayEditSectionCard(title = "Isplata bolovanja") {
        val rates = listOf(
            CONFIG.pay.sickPayRate to "${(CONFIG.pay.sickPayRate * 100).toInt()}%",
            1.0 to "100%"
        )
        DayEditSegmentedChoice(
            options = rates.map { rate ->
                DayEditChoice(rate.second, kotlin.math.abs(sickPayRate - rate.first) < 0.01) {
                    onSickPayRateSelected(rate.first)
                }
            },
            minHeight = OVERTIME_TYPE_CHOICE_HEIGHT
        )
    }
}

@Composable
internal fun DayEditVacationControls(
    warningMessage: String?
) {
    warningMessage?.let { message ->
        DayEditSectionCard(title = "Godišnji") {
            Text(
                text = message,
                color = MaterialTheme.extendedColors.warning,
                style = MaterialTheme.typography.bodyMediumEmphasized,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
internal fun DayEditPaidLeaveControls() {
    DayEditSectionCard(title = DAY_EDIT_PAID_LEAVE_LABEL) {
        Text(
            text = DAY_EDIT_PAID_LEAVE_DETAIL_TEXT,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
internal fun DayEditOffControls() {
    DayEditSectionCard(title = "Nisam radio") {
        Text(
            text = "Bez radnih sati.",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
internal fun DayEditCompensationBlock(
    compHoursUsed: Int,
    availableCompMinutes: Int,
    onUseClick: () -> Unit
) {
    DayEditSectionCard(title = "Kompenzacija") {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(Spacing.sm)
        ) {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = UI.buttonHeight.dp)
                    .padding(horizontal = Spacing.xxs, vertical = Spacing.sm),
                verticalArrangement = Arrangement.spacedBy(Spacing.xxs)
            ) {
                Text(
                    text = "Bilans sati",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.labelSmall,
                    maxLines = 1
                )
                Text(
                    text = formatHoursAndMinutes(availableCompMinutes),
                    color = MaterialTheme.colorScheme.primary,
                    style = MaterialTheme.typography.titleMediumEmphasized,
                    maxLines = 1
                )
            }
            DayEditValueButton(
                label = "Iskoristi sate",
                value = formatHours(compHoursUsed),
                modifier = Modifier.weight(1f),
                onClick = onUseClick
            )
        }
    }
}

@Composable
internal fun DayEditSaveButton(
    saveState: DayEditSaveState,
    vacationValidation: VacationRequestValidation,
    actions: DataActions,
    onDismiss: () -> Unit,
    beforeSaveDay: () -> Unit
) {
    val isSaveEnabled = saveState.shift1 != Shift.VACATION || vacationValidation.isAllowed
    GlowButton(
        text = "Sa\u010duvaj",
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = UI.saveButtonHeight.dp),
        textStyle = MaterialTheme.typography.labelLarge,
        enabled = isSaveEnabled,
        onClick = {
            if (!isSaveEnabled) {
                return@GlowButton
            }
            if (saveState.shift1 == Shift.VACATION && !vacationValidation.isAllowed) {
                return@GlowButton
            }
            // Opseg „Do kog dana“ se upisuje pre samog dana, da godišnji tog dana ne bude brojan dvaput.
            beforeSaveDay()
            actions.saveDay(saveState.toDayRecord())
            onDismiss()
        }
    )
}

@Composable
internal fun DayEditSectionCard(
    title: String,
    content: @Composable ColumnScope.() -> Unit
) {
    PopupCard {
        SectionLabel(text = title)
        content()
    }
}

@Composable
internal fun DayEditChoiceHeader(text: String) {
    Text(
        text = text,
        color = MaterialTheme.extendedColors.textSecondary,
        style = MaterialTheme.typography.bodySmall,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        // Odeljci dijaloga: čitač ekrana skače po naslovima.
        modifier = Modifier.semantics { heading() }
    )
}

internal data class DayEditWorkControlState(
    val shift1: Shift,
    val workHours1: Int,
    val hasDouble: Boolean,
    val showDoubleSwitch: Boolean,
    val showOvertimeCard: Boolean,
    val shortageMinutesAllowed: Boolean,
    val effectiveRegularShortageMinutes: Int
)

internal data class DayEditAdditionalWorkState(
    val shift2: Shift,
    val secondShiftOptions: List<Shift>,
    val workHours2: Int,
    val hasDouble: Boolean,
    val overtimeType: OvertimeType,
    val weekendOvertimeType: OvertimeType,
    val hasPrimaryNonRegularAdditionalWork: Boolean,
    val showPrimaryExtraHoursMode: Boolean
)
