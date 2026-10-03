package com.rad.shifttracker

import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.selection.selectable
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.rad.shifttracker.ui.components.AutoSizeText
import java.time.LocalDate

internal const val DAY_TYPE_CHOICE_HEIGHT = 52
private const val DAY_EDIT_SHIFT_CHOICE_HEIGHT = 48
internal const val OVERTIME_TYPE_CHOICE_HEIGHT = 48
private const val DISABLED_CHOICE_ALPHA = 0.45f

// Existing day-edit flow is intentionally grouped; detekt stays active for newly added methods.
@Suppress("LongMethod", "CyclomaticComplexMethod")
@Composable
fun DayEditDialog(
    year: Int,
    month: Int,
    day: Int,
    record: DayRecord?,
    repo: ShiftRepository,
    onDismiss: () -> Unit
) {
    val dayDate = remember(year, month, day) { LocalDate.of(year, month, day) }
    val pickerAccent = MaterialTheme.colorScheme.primary
    if (isDayEditLockedByBloodDonation(record)) {
        BloodDonationLockedDayDialog(
            title = "$day. ${MONTH_NAMES[month]}",
            date = dayDate,
            record = record,
            repo = repo,
            onDismiss = onDismiss
        )
        return
    }
    val autoHoliday = remember(year, month, day) { isSerbianHoliday(year, month, day) }
    val initialSickRate = when {
        record?.shift == Shift.SICK && record.sickPayRate != null -> record.sickPayRate
        else -> CONFIG.pay.sickPayRate
    }

    // rememberSaveable: uneto stanje preživljava rotaciju/ubijanje procesa.
    // Ključ je SADRŽAJ zapisa (toMap), ne instanca — DayRecord nema strukturni
    // equals pa bi nova instanca posle rotacije obrisala uneto.
    val recordContentKey = record?.toMap()
    var shift1 by rememberSaveable(year, month, day, recordContentKey) {
        mutableStateOf(record?.shift ?: Shift.OFF)
    }
    var workHours1 by rememberSaveable(year, month, day, recordContentKey) {
        mutableStateOf(record?.workHours ?: CONFIG.pay.defaultHours)
    }
    var shift2 by rememberSaveable(year, month, day, recordContentKey) {
        mutableStateOf(record?.shift2 ?: defaultOvertimeShiftAfter(record?.shift ?: Shift.OFF))
    }
    var workHours2 by rememberSaveable(year, month, day, recordContentKey) {
        mutableStateOf(
            (record?.workHours2 ?: 0).takeIf { it > 0 } ?: defaultOvertimeHours(record?.shift ?: Shift.OFF)
        )
    }
    var hasDouble by rememberSaveable(year, month, day, recordContentKey) {
        mutableStateOf(record?.isDoubleShift ?: false)
    }
    var overtimeType by rememberSaveable(year, month, day, recordContentKey) {
        mutableStateOf(record?.overtimeType ?: OvertimeType.PAID)
    }
    var weekendOvertimeType by rememberSaveable(year, month, day, recordContentKey) {
        mutableStateOf(record?.weekendOvertimeType ?: OvertimeType.PAID)
    }
    var compHoursUsed by rememberSaveable(year, month, day, recordContentKey) {
        mutableStateOf(record?.compHoursUsed ?: 0)
    }
    var regularShortageMinutes by rememberSaveable(year, month, day, recordContentKey) {
        mutableStateOf(record?.regularShortageMinutes ?: 0)
    }
    var sickPayRate by rememberSaveable(year, month, day, recordContentKey) {
        mutableStateOf(initialSickRate)
    }
    var paidLeave by rememberSaveable(year, month, day, recordContentKey) {
        mutableStateOf(record?.isPaidLeave == true)
    }
    val holidayOverride = record?.holidayOverride
    val holidayWorkOverride = record?.holidayWorkOverride
    val preserveBalanceExempt = record?.balanceExempt == true && shift1 == Shift.OFF

    val isWeekend = remember(dayDate) { dayDate.dayOfWeek.value >= 6 }

    val isHolidayEffective = holidayOverride ?: autoHoliday
    val firstShiftAllowsDouble = shift1 in Shift.workShifts()
    // Stari zapis sa dodatnom smenom van dozvoljenih ostaje vidljiv da može da se ispravi.
    val secondShiftOptions = overtimeShiftsAfter(shift1).let { allowed ->
        if (hasDouble && shift2 !in allowed) (allowed + shift2).sortedBy { it.ordinal } else allowed
    }
    val showDoubleSwitch = secondShiftOptions.isNotEmpty()
    val hasPrimaryNonRegularAdditionalWork = firstShiftAllowsDouble && (isWeekend || isHolidayEffective)
    val hasPrimaryExtraHours = firstShiftAllowsDouble && workHours1 > CONFIG.pay.defaultHours
    val showPrimaryExtraHoursMode = hasPrimaryExtraHours && !hasDouble && !hasPrimaryNonRegularAdditionalWork
    val shortageMinutesAllowed = shift1 in Shift.workShifts() && regularShortageMinutesAllowed(workHours1)
    val effectiveRegularShortageMinutes = if (shortageMinutesAllowed) regularShortageMinutes else 0
    val saveState = DayEditSaveState(
        date = DayKey(year, month, day),
        shift1 = shift1,
        workHours1 = workHours1,
        shift2 = shift2,
        workHours2 = workHours2,
        hasDouble = hasDouble,
        firstShiftAllowsDouble = firstShiftAllowsDouble,
        overtimeType = overtimeType,
        weekendOvertimeType = weekendOvertimeType,
        compHoursUsed = compHoursUsed,
        holidayWorkOverride = holidayWorkOverride,
        holidayOverride = holidayOverride,
        balanceExempt = preserveBalanceExempt,
        sickPayRate = sickPayRate,
        regularShortageMinutes = effectiveRegularShortageMinutes,
        paidLeave = paidLeave
    )
    val previewRecord = saveState.toDayRecord()
    val vacationValidation = remember(repo, dayDate, shift1, record) {
        if (shift1 == Shift.VACATION) {
            repo.validateVacationRequest(listOf(dayDate))
        } else {
            VacationRequestValidation(blockedDates = emptyList(), message = null)
        }
    }

    val holidayText = if (!isHolidayEffective) {
        null
    } else {
        val baseName = getHolidayName(year, month, day)
        val name = when {
            holidayOverride == false -> null
            holidayOverride == true && baseName == null -> MANUAL_HOLIDAY_LABEL
            else -> baseName
        }
        val isWorkday = dayDate.dayOfWeek.value <= 5
        when {
            name == null -> null
            shift1 in Shift.workShifts() -> "Praznik: $name (rad +${(CONFIG.pay.holidayBonus * 100).toInt()}%)"
            isWorkday -> "Praznik: $name (neradni, 100%)"
            else -> "Praznik: $name (vikend)"
        }
    }

    var hoursPicker by remember { mutableStateOf<HoursPickerState?>(null) }
    var showShortageDialog by remember { mutableStateOf(false) }
    var showCompDialog by remember { mutableStateOf(false) }

    PopupDialog(
        title = "$day. ${MONTH_NAMES[month]}",
        onDismiss = onDismiss,
        scrollable = true,
        footer = { DayEditSaveButton(saveState, vacationValidation, repo, onDismiss) }
    ) {
        DayEditStatusHeader(record = record, previewRecord = previewRecord, repo = repo)
        holidayText?.let { text -> DayEditHolidayWarningStrip(text = text) }

        DayEditSectionCard(title = "Tip dana") {
            DayEditDayTypeSelector(
                selectedShift = shift1,
                paidLeaveSelected = paidLeave,
                onWorkSelected = {
                    paidLeave = false
                    if (shift1 !in Shift.workShifts()) {
                        val scheduled = repo.getScheduledShiftForDate(dayDate)
                            ?.takeIf { it in Shift.workShifts() }
                        shift1 = scheduled ?: Shift.FIRST
                        if (overtimeShiftsAfter(shift1).isEmpty()) hasDouble = false
                    }
                    if (workHours1 == 0) {
                        workHours1 = CONFIG.pay.defaultHours
                    }
                    if (!regularShortageMinutesAllowed(workHours1)) {
                        regularShortageMinutes = 0
                    }
                },
                onSickSelected = {
                    shift1 = Shift.SICK
                    paidLeave = false
                    workHours1 = CONFIG.pay.defaultHours
                    hasDouble = false
                    regularShortageMinutes = 0
                },
                onPaidLeaveSelected = {
                    shift1 = Shift.SICK
                    paidLeave = true
                    workHours1 = CONFIG.pay.defaultHours
                    hasDouble = false
                    regularShortageMinutes = 0
                },
                onVacationSelected = {
                    paidLeave = false
                    shift1 = Shift.VACATION
                    workHours1 = CONFIG.pay.defaultHours
                    hasDouble = false
                    regularShortageMinutes = 0
                },
                onOffSelected = {
                    paidLeave = false
                    shift1 = Shift.OFF
                    workHours1 = 0
                    hasDouble = false
                    regularShortageMinutes = 0
                }
            )
        }

        when (shift1) {
            Shift.FIRST, Shift.SECOND, Shift.THIRD -> DayEditWorkControls(
                state = DayEditWorkControlState(
                    shift1 = shift1,
                    workHours1 = workHours1,
                    hasDouble = hasDouble,
                    showDoubleSwitch = showDoubleSwitch,
                    showOvertimeCard = showDoubleSwitch || hasPrimaryNonRegularAdditionalWork || showPrimaryExtraHoursMode,
                    shortageMinutesAllowed = shortageMinutesAllowed,
                    effectiveRegularShortageMinutes = effectiveRegularShortageMinutes
                ),
                onPrimaryShiftSelected = { selected ->
                    shift1 = selected
                    val allowed = overtimeShiftsAfter(selected)
                    if (allowed.isEmpty()) {
                        hasDouble = false
                    } else if (shift2 !in allowed) {
                        shift2 = defaultOvertimeShiftAfter(selected)
                    }
                },
                onRegularHoursClick = {
                    hoursPicker = HoursPickerState(
                        title = "Izaberi sate",
                        current = workHours1,
                        accent = pickerAccent,
                        target = HoursTarget.SHIFT1,
                        minHours = 1
                    )
                },
                onShortageClick = { showShortageDialog = true },
                onDoubleShiftChanged = { checked ->
                    hasDouble = checked
                    if (checked) {
                        if (hasPrimaryNonRegularAdditionalWork) overtimeType = weekendOvertimeType
                        shift2 = defaultOvertimeShiftAfter(shift1)
                        if (workHours2 == 0) {
                            workHours2 = defaultOvertimeHours(shift1)
                        }
                    }
                },
                additionalWorkContent = {
                    DayEditAdditionalWorkControls(
                        state = DayEditAdditionalWorkState(
                            shift2 = shift2,
                            secondShiftOptions = secondShiftOptions,
                            workHours2 = workHours2,
                            hasDouble = hasDouble,
                            overtimeType = overtimeType,
                            weekendOvertimeType = weekendOvertimeType,
                            hasPrimaryNonRegularAdditionalWork = hasPrimaryNonRegularAdditionalWork,
                            showPrimaryExtraHoursMode = showPrimaryExtraHoursMode
                        ),
                        onSecondShiftSelected = { selected -> shift2 = selected },
                        onOvertimeHoursClick = {
                            hoursPicker = HoursPickerState(
                                title = "Izaberi prekovremene sate",
                                current = workHours2,
                                accent = pickerAccent,
                                target = HoursTarget.SHIFT2,
                                minHours = 1
                            )
                        },
                        onOvertimeTypeSelected = { selected -> overtimeType = selected },
                        onWeekendOvertimeTypeSelected = { selected -> weekendOvertimeType = selected }
                    )
                }
            )

            Shift.SICK -> if (paidLeave) {
                DayEditPaidLeaveControls()
            } else {
                DayEditSickControls(
                    sickPayRate = sickPayRate,
                    onSickPayRateSelected = { selected -> sickPayRate = selected }
                )
            }

            Shift.VACATION -> DayEditVacationControls(
                warningMessage = vacationValidation.message
            )

            Shift.OFF -> DayEditOffControls()
        }

        val summary = repo.getSummary(year, month)
        val availableComp = summary.compTimeStarting
        val availableCompMinutes = summary.compTimeStartingMinutes
        if (compHoursUsed > 0 || availableComp > 0) {
            DayEditCompensationBlock(
                compHoursUsed = compHoursUsed,
                availableCompMinutes = availableCompMinutes,
                onUseClick = { showCompDialog = true }
            )
        }
    }

    hoursPicker?.let { picker ->
        HoursPickerDialog(
            title = picker.title,
            currentHours = picker.current,
            minHours = picker.minHours,
            accent = picker.accent,
            onSelect = { selected ->
                when (picker.target) {
                    HoursTarget.SHIFT1 -> {
                        workHours1 = normalizeDayHours(selected)
                        if (!regularShortageMinutesAllowed(workHours1)) {
                            regularShortageMinutes = 0
                        }
                    }
                    HoursTarget.SHIFT2 -> {
                        workHours2 = normalizeDayHours(selected)
                    }
                }
            },
            onDismiss = { hoursPicker = null }
        )
    }

    if (showShortageDialog && shortageMinutesAllowed) {
        ShortageMinutesDialog(
            currentMinutes = effectiveRegularShortageMinutes,
            onSelect = { selected -> regularShortageMinutes = selected },
            onDismiss = { showShortageDialog = false }
        )
    }

    if (showCompDialog) {
        val summary = repo.getSummary(year, month)
        val availableComp = summary.compTimeStarting
        CompUsedDialog(
            availableHours = availableComp,
            currentHours = compHoursUsed,
            onSelect = { selected -> compHoursUsed = selected },
            onDismiss = { showCompDialog = false }
        )
    }
}

/** Neto procena kao dva mirna reda (etiketa levo, iznos desno) — bez zelenog okvira. */
@Composable
private fun DayEditStatusHeader(
    record: DayRecord?,
    previewRecord: DayRecord,
    repo: ShiftRepository
) {
    val estimatedNetDailyPay = repo.calculateEstimatedDailyWorkNetPay(previewRecord)
    val savedNetDailyPay = record?.let { repo.calculateEstimatedDailyWorkNetPay(it) }
    val netDifferenceText = formatDayEditNetDifference(
        savedNetPay = savedNetDailyPay,
        currentNetPay = estimatedNetDailyPay,
        currency = CONFIG.pay.currency
    )
    val netDifferenceColor = when {
        savedNetDailyPay != null && estimatedNetDailyPay - savedNetDailyPay < 0.0 -> MaterialTheme.colorScheme.error
        else -> MaterialTheme.extendedColors.success
    }

    if (estimatedNetDailyPay > 0.0 || netDifferenceText != null) {
        PopupCard(spacing = Spacing.sm) {
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
private fun DayEditHolidayWarningStrip(text: String) {
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

@Composable
internal fun RowScope.DayEditChoiceButton(
    text: String,
    selected: Boolean,
    minHeight: Int,
    enabled: Boolean = true,
    onClick: () -> Unit
) {
    val shape = MaterialTheme.shapes.small
    val alpha = if (enabled) 1f else DISABLED_CHOICE_ALPHA
    val bg = if (selected) MaterialTheme.colorScheme.primary.copy(alpha = alpha) else MaterialTheme.colorScheme.surfaceContainerHigh
    val fg = (if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.extendedColors.textSecondary).copy(alpha = alpha)
    val border = if (selected) MaterialTheme.colorScheme.primary.copy(alpha = alpha) else MaterialTheme.colorScheme.outlineVariant.copy(alpha = CARD_BORDER_ALPHA)
    Box(
        modifier = Modifier
            .weight(1f)
            .heightIn(min = MIN_TOUCH_TARGET)
            .heightIn(min = minHeight.dp)
            .clip(shape)
            .background(bg)
            .border(1.dp, border, shape)
            // Izbor se najavljuje kao radio dugme sa stanjem „izabrano“, ne kao običan klik.
            .selectable(selected = selected, enabled = enabled, role = Role.RadioButton, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        AutoSizeText(
            text = text,
            color = fg,
            style = (if (selected) MaterialTheme.typography.labelLargeEmphasized else MaterialTheme.typography.labelLarge).copy(
                textAlign = TextAlign.Center
            ),
            maxFontSize = MaterialTheme.typography.labelLarge.fontSize,
            minFontSize = MaterialTheme.typography.labelSmall.fontSize,
            maxLines = 1,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Spacing.sm, vertical = Spacing.xs)
        )
    }
}

@Composable
private fun DayEditDayTypeSelector(
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
        modifier = Modifier.fillMaxWidth(),
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
        modifier = Modifier.fillMaxWidth(),
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
        modifier = Modifier.fillMaxWidth(),
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
private fun DayEditWorkControls(
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
private fun DayEditAdditionalWorkControls(
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
private fun DayEditSickControls(
    sickPayRate: Double,
    onSickPayRateSelected: (Double) -> Unit
) {
    DayEditSectionCard(title = "Bolovanje") {
        DayEditChoiceHeader(text = "Isplata bolovanja")
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
private fun DayEditVacationControls(
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
private fun DayEditPaidLeaveControls() {
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
private fun DayEditOffControls() {
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
private fun DayEditCompensationBlock(
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
private fun DayEditSaveButton(
    saveState: DayEditSaveState,
    vacationValidation: VacationRequestValidation,
    repo: ShiftRepository,
    onDismiss: () -> Unit
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
            val saveRecord = saveState.toDayRecord()
            repo.set(
                year = saveRecord.year,
                month = saveRecord.month,
                day = saveRecord.day,
                shift = saveRecord.shift,
                hours = saveRecord.hours,
                shift2 = saveRecord.shift2,
                hours2 = saveRecord.hours2,
                overtimeType = saveRecord.overtimeType,
                weekendOvertimeType = saveRecord.weekendOvertimeType,
                compHoursUsed = saveRecord.compHoursUsed,
                holidayWorkOverride = saveRecord.holidayWorkOverride,
                holidayOverride = saveRecord.holidayOverride,
                sickPayRate = saveRecord.sickPayRate,
                balanceExempt = saveRecord.balanceExempt,
                regularShortageMinutes = saveRecord.regularShortageMinutes,
                source = saveRecord.source
            )
            onDismiss()
        }
    )
}

@Composable
private fun DayEditSectionCard(
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
        overflow = TextOverflow.Ellipsis
    )
}

@Composable
private fun BloodDonationLockedDayDialog(
    title: String,
    date: LocalDate,
    record: DayRecord?,
    repo: ShiftRepository,
    onDismiss: () -> Unit
) {
    PopupDialog(
        title = title,
        onDismiss = onDismiss,
        scrollable = false
    ) {
        PopupCard {
            SectionLabel(text = BLOOD_DONATION_BOTTOM_BAR_LABEL)
            record?.let {
                Text(
                    text = "Datum: ${it.date.formatBloodDonationDate()}",
                    color = MaterialTheme.extendedColors.textSecondary,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            GlowButton(
                text = "Poništi dane",
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = UI.buttonHeight.dp),
                destructive = true,
                textStyle = MaterialTheme.typography.labelLarge,
                onClick = {
                    repo.clearBloodDonationLeaveForDate(date)
                    onDismiss()
                }
            )
        }
    }
}

private enum class HoursTarget {
    SHIFT1,
    SHIFT2
}

private data class DayEditWorkControlState(
    val shift1: Shift,
    val workHours1: Int,
    val hasDouble: Boolean,
    val showDoubleSwitch: Boolean,
    val showOvertimeCard: Boolean,
    val shortageMinutesAllowed: Boolean,
    val effectiveRegularShortageMinutes: Int
)

private data class DayEditAdditionalWorkState(
    val shift2: Shift,
    val secondShiftOptions: List<Shift>,
    val workHours2: Int,
    val hasDouble: Boolean,
    val overtimeType: OvertimeType,
    val weekendOvertimeType: OvertimeType,
    val hasPrimaryNonRegularAdditionalWork: Boolean,
    val showPrimaryExtraHoursMode: Boolean
)

private data class HoursPickerState(
    val title: String,
    val current: Int,
    val accent: Color,
    val target: HoursTarget,
    val minHours: Int
)
