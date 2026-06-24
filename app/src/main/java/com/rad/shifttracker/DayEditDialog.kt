package com.rad.shifttracker

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.rad.shifttracker.ui.components.ResponsiveInfoRow
import java.time.LocalDate

private const val DAY_TYPE_CHOICE_HEIGHT = 65
private const val DAY_EDIT_SHIFT_CHOICE_HEIGHT = 50
private const val OVERTIME_TYPE_CHOICE_HEIGHT = 50

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

    var shift1 by remember(year, month, day, record) { mutableStateOf(record?.shift ?: Shift.OFF) }
    var workHours1 by remember(year, month, day, record) {
        mutableIntStateOf(record?.workHours ?: CONFIG.pay.defaultHours)
    }
    var shift2 by remember(year, month, day, record) {
        mutableStateOf(record?.shift2 ?: defaultOvertimeShiftAfter(record?.shift ?: Shift.OFF))
    }
    var workHours2 by remember(year, month, day, record) {
        mutableIntStateOf(
            (record?.workHours2 ?: 0).takeIf { it > 0 } ?: defaultOvertimeHours(record?.shift ?: Shift.OFF)
        )
    }
    var hasDouble by remember(year, month, day, record) { mutableStateOf(record?.isDoubleShift ?: false) }
    var overtimeType by remember(year, month, day, record) {
        mutableStateOf(record?.overtimeType ?: OvertimeType.PAID)
    }
    var weekendOvertimeType by remember(year, month, day, record) {
        mutableStateOf(record?.weekendOvertimeType ?: OvertimeType.PAID)
    }
    var compHoursUsed by remember(year, month, day, record) { mutableIntStateOf(record?.compHoursUsed ?: 0) }
    var regularShortageMinutes by remember(year, month, day, record) {
        mutableIntStateOf(record?.regularShortageMinutes ?: 0)
    }
    var sickPayRate by remember(year, month, day, record) { mutableDoubleStateOf(initialSickRate) }
    val holidayOverride = record?.holidayOverride
    val holidayWorkOverride = record?.holidayWorkOverride
    val preserveBalanceExempt = record?.balanceExempt == true && shift1 == Shift.OFF

    val isWeekend = remember(dayDate) { dayDate.dayOfWeek.value >= 6 }

    val isHolidayEffective = holidayOverride ?: autoHoliday
    val firstShiftAllowsDouble = shift1 in Shift.workShifts()
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
        regularShortageMinutes = effectiveRegularShortageMinutes
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
        titleTextAlign = TextAlign.Center,
        footer = { DayEditSaveButton(saveState, vacationValidation, repo, onDismiss) }
    ) {
        DayEditStatusHeader(record = record, previewRecord = previewRecord, repo = repo)
        holidayText?.let { text -> DayEditHolidayWarningStrip(text = text) }

        DayEditSectionCard(title = "Tip dana") {
            DayEditDayTypeSelector(
                selectedShift = shift1,
                onWorkSelected = {
                    if (shift1 !in Shift.workShifts()) {
                        val scheduled = repo.getScheduledShiftForDate(dayDate)
                            ?.takeIf { it in Shift.workShifts() }
                        shift1 = scheduled ?: Shift.FIRST
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
                    workHours1 = CONFIG.pay.defaultHours
                    hasDouble = false
                    regularShortageMinutes = 0
                },
                onVacationSelected = {
                    shift1 = Shift.VACATION
                    workHours1 = CONFIG.pay.defaultHours
                    hasDouble = false
                    regularShortageMinutes = 0
                },
                onOffSelected = {
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
                    shortageMinutesAllowed = shortageMinutesAllowed,
                    effectiveRegularShortageMinutes = effectiveRegularShortageMinutes
                ),
                onPrimaryShiftSelected = { selected ->
                    shift1 = selected
                    if (hasDouble) {
                        shift2 = defaultOvertimeShiftAfter(selected)
                    }
                },
                onRegularHoursClick = {
                    hoursPicker = HoursPickerState(
                        title = "Izaberi sate",
                        current = workHours1,
                        accent = THEME.accentPrimary,
                        target = HoursTarget.SHIFT1,
                        minHours = 1
                    )
                },
                onShortageClick = { showShortageDialog = true },
                onDoubleShiftChanged = { checked ->
                    hasDouble = checked
                    if (checked) {
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
                                accent = THEME.doubleShift,
                                target = HoursTarget.SHIFT2,
                                minHours = 1
                            )
                        },
                        onOvertimeTypeSelected = { selected -> overtimeType = selected },
                        onWeekendOvertimeTypeSelected = { selected -> weekendOvertimeType = selected }
                    )
                }
            )

            Shift.SICK -> DayEditSickControls(
                sickPayRate = sickPayRate,
                onSickPayRateSelected = { selected -> sickPayRate = selected }
            )

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
        savedNetDailyPay != null && estimatedNetDailyPay - savedNetDailyPay < 0.0 -> THEME.danger
        else -> THEME.success
    }

    if (estimatedNetDailyPay > 0.0 || netDifferenceText != null) {
        DayEditSectionCard(
            title = "Zarada",
            bgColor = THEME.bgCardElevated,
            borderColor = THEME.success
        ) {
            ResponsiveInfoRow(
                label = "Neto procena:",
                value = "${formatNumber(estimatedNetDailyPay)} ${CONFIG.pay.currency}",
                labelStyle = MaterialTheme.typography.bodyMedium,
                valueStyle = MaterialTheme.typography.bodyLarge,
                labelColor = THEME.textSecondary,
                valueColor = THEME.success,
                valueFontWeight = FontWeight.Bold
            )
            netDifferenceText?.let { text ->
                ResponsiveInfoRow(
                    label = "Razlika:",
                    value = text.removePrefix("Razlika: "),
                    labelStyle = MaterialTheme.typography.bodyMedium,
                    valueStyle = MaterialTheme.typography.bodyLarge,
                    labelColor = THEME.textSecondary,
                    valueColor = netDifferenceColor,
                    valueFontWeight = FontWeight.Bold
                )
            }
        }
    }
}

@Composable
private fun DayEditHolidayWarningStrip(text: String) {
    DayEditSectionCard(title = "Praznik") {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(THEME.warning.copy(alpha = 0.25f), RoundedCornerShape(8.dp))
                .padding(horizontal = 8.dp, vertical = 6.dp)
        ) {
            Text(
                text = text,
                color = THEME.warning,
                style = MaterialTheme.typography.bodyMedium.copy(
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center
                ),
                modifier = Modifier.fillMaxWidth(),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun DayEditDayTypeSelector(
    selectedShift: Shift,
    onWorkSelected: () -> Unit,
    onSickSelected: () -> Unit,
    onVacationSelected: () -> Unit,
    onOffSelected: () -> Unit
) {
    Text(
        text = "Unos za ovaj datum",
        color = THEME.textMuted,
        style = MaterialTheme.typography.bodyMedium,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis
    )
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = DAY_TYPE_CHOICE_HEIGHT.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        val isWorkDaySelected = selectedShift in Shift.workShifts()
        RoundedButton(
            text = "Radio sam",
            modifier = Modifier
                .weight(1f)
                .heightIn(min = DAY_TYPE_CHOICE_HEIGHT.dp)
                .fillMaxHeight(),
            bgColor = if (isWorkDaySelected) THEME.accentPrimary else THEME.bgCardElevated,
            textColor = if (isWorkDaySelected) THEME.textDark else THEME.textPrimary,
            radius = 14.dp,
            textStyle = MaterialTheme.typography.labelLarge,
            bold = true,
            onClick = onWorkSelected
        )
        RoundedButton(
            text = "Bolovanje",
            modifier = Modifier
                .weight(1f)
                .heightIn(min = DAY_TYPE_CHOICE_HEIGHT.dp)
                .fillMaxHeight(),
            bgColor = if (selectedShift == Shift.SICK) THEME.sick else THEME.bgCardElevated,
            textColor = THEME.textPrimary,
            radius = 14.dp,
            textStyle = MaterialTheme.typography.labelLarge,
            bold = true,
            onClick = onSickSelected
        )
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = DAY_TYPE_CHOICE_HEIGHT.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        RoundedButton(
            text = "Godišnji",
            modifier = Modifier
                .weight(1f)
                .heightIn(min = DAY_TYPE_CHOICE_HEIGHT.dp)
                .fillMaxHeight(),
            bgColor = if (selectedShift == Shift.VACATION) THEME.vacation else THEME.bgCardElevated,
            textColor = if (selectedShift == Shift.VACATION) THEME.textDark else THEME.textPrimary,
            radius = 14.dp,
            textStyle = MaterialTheme.typography.labelLarge,
            bold = true,
            onClick = onVacationSelected
        )
        RoundedButton(
            text = dayEditShiftButtonText(Shift.OFF),
            modifier = Modifier
                .weight(1f)
                .heightIn(min = DAY_TYPE_CHOICE_HEIGHT.dp)
                .fillMaxHeight(),
            bgColor = if (selectedShift == Shift.OFF) THEME.off else THEME.bgCardElevated,
            textColor = THEME.textPrimary,
            radius = 14.dp,
            textStyle = MaterialTheme.typography.labelLarge,
            bold = true,
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
        DayEditPrimaryShiftSelector(
            selectedShift = state.shift1,
            onSelected = onPrimaryShiftSelected
        )
        DayEditRegularWorkControls(
            workHours = state.workHours1,
            shortageMinutesAllowed = state.shortageMinutesAllowed,
            effectiveRegularShortageMinutes = state.effectiveRegularShortageMinutes,
            onRegularHoursClick = onRegularHoursClick,
            onShortageClick = onShortageClick
        )
    }
    DayEditSectionCard(title = "Prekovremeno") {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                Text(
                    text = "Dodatna smena posle redovne",
                    color = THEME.textMuted,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Switch(
                checked = state.hasDouble,
                onCheckedChange = onDoubleShiftChanged,
                colors = SwitchDefaults.colors(
                    checkedThumbColor = THEME.textDark,
                    checkedTrackColor = THEME.doubleShift,
                    uncheckedThumbColor = THEME.textMuted,
                    uncheckedTrackColor = THEME.bgCardElevated
                )
            )
        }
        additionalWorkContent()
    }
}

@Composable
private fun DayEditPrimaryShiftSelector(
    selectedShift: Shift,
    onSelected: (Shift) -> Unit
) {
    DayEditChoiceHeader(text = DAY_EDIT_PRIMARY_SHIFT_QUESTION_TEXT)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = DAY_EDIT_SHIFT_CHOICE_HEIGHT.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        for (shift in Shift.workShifts()) {
            val isSelected = shift == selectedShift
            RoundedButton(
                text = dayEditShiftButtonText(shift),
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = DAY_EDIT_SHIFT_CHOICE_HEIGHT.dp)
                    .fillMaxHeight(),
                bgColor = if (isSelected) shift.color else shift.color.copy(alpha = 0.2f),
                textColor = shift.textColor,
                radius = 14.dp,
                textStyle = MaterialTheme.typography.labelMedium,
                bold = true,
                onClick = { onSelected(shift) }
            )
        }
    }
}

@Composable
private fun DayEditRegularWorkControls(
    workHours: Int,
    shortageMinutesAllowed: Boolean,
    effectiveRegularShortageMinutes: Int,
    onRegularHoursClick: () -> Unit,
    onShortageClick: () -> Unit
) {
    RoundedButton(
        text = "Redovni sati: ${formatHours(workHours)}",
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = UI.buttonHeight.dp),
        bgColor = THEME.bgCardElevated,
        textColor = THEME.textPrimary,
        radius = 14.dp,
        textStyle = MaterialTheme.typography.bodyLarge,
        bold = true,
        onClick = onRegularHoursClick
    )
    RoundedButton(
        text = "Minuti: ${formatShortageMinutes(effectiveRegularShortageMinutes)}",
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = UI.buttonHeight.dp),
        bgColor = THEME.bgCardElevated,
        textColor = THEME.textPrimary,
        radius = 14.dp,
        textStyle = MaterialTheme.typography.bodyLarge,
        bold = true,
        enabled = shortageMinutesAllowed,
        onClick = onShortageClick
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
        DayEditAdditionalShiftSelector(
            selectedShift = state.shift2,
            onSelected = onSecondShiftSelected
        )
        RoundedButton(
            text = "Prekovremeni sati: ${formatHours(state.workHours2)}",
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = UI.buttonHeight.dp),
            bgColor = THEME.bgCardElevated,
            textColor = THEME.textPrimary,
            radius = 14.dp,
            textStyle = MaterialTheme.typography.bodyLarge,
            bold = true,
            onClick = onOvertimeHoursClick
        )
        DayEditChoiceHeader(text = "Kako se vodi prekovremeno?")
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = OVERTIME_TYPE_CHOICE_HEIGHT.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            val paidSelected = state.overtimeType == OvertimeType.PAID
            RoundedButton(
                text = "Plaćeno",
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = OVERTIME_TYPE_CHOICE_HEIGHT.dp)
                    .fillMaxHeight(),
                bgColor = if (paidSelected) THEME.success else THEME.bgCard,
                textColor = if (paidSelected) THEME.textDark else THEME.textPrimary,
                radius = 14.dp,
                textStyle = MaterialTheme.typography.labelLarge,
                bold = true,
                onClick = { onOvertimeTypeSelected(OvertimeType.PAID) }
            )
            val compSelected = state.overtimeType == OvertimeType.COMP_TIME
            RoundedButton(
                text = "Za sate",
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = OVERTIME_TYPE_CHOICE_HEIGHT.dp)
                    .fillMaxHeight(),
                bgColor = if (compSelected) THEME.compTime else THEME.bgCard,
                textColor = if (compSelected) THEME.textDark else THEME.textPrimary,
                radius = 14.dp,
                textStyle = MaterialTheme.typography.labelLarge,
                bold = true,
                onClick = { onOvertimeTypeSelected(OvertimeType.COMP_TIME) }
            )
        }
    }

    if (state.hasPrimaryNonRegularAdditionalWork) {
        DayEditChoiceHeader(text = "Kako se vodi dodatni rad?")
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = OVERTIME_TYPE_CHOICE_HEIGHT.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            val paidSelected = state.weekendOvertimeType == OvertimeType.PAID
            RoundedButton(
                text = "Plaćeno",
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = OVERTIME_TYPE_CHOICE_HEIGHT.dp)
                    .fillMaxHeight(),
                bgColor = if (paidSelected) THEME.success else THEME.bgCard,
                textColor = if (paidSelected) THEME.textDark else THEME.textPrimary,
                radius = 14.dp,
                textStyle = MaterialTheme.typography.labelLarge,
                bold = true,
                onClick = { onWeekendOvertimeTypeSelected(OvertimeType.PAID) }
            )
            val compSelected = state.weekendOvertimeType == OvertimeType.COMP_TIME
            RoundedButton(
                text = "Za sate",
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = OVERTIME_TYPE_CHOICE_HEIGHT.dp)
                    .fillMaxHeight(),
                bgColor = if (compSelected) THEME.compTime else THEME.bgCard,
                textColor = if (compSelected) THEME.textDark else THEME.textPrimary,
                radius = 14.dp,
                textStyle = MaterialTheme.typography.labelLarge,
                bold = true,
                onClick = { onWeekendOvertimeTypeSelected(OvertimeType.COMP_TIME) }
            )
        }
    }

    if (state.showPrimaryExtraHoursMode) {
        DayEditChoiceHeader(text = "Kako se vode sati preko redovne smene?")
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = OVERTIME_TYPE_CHOICE_HEIGHT.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            val paidSelected = state.overtimeType == OvertimeType.PAID
            RoundedButton(
                text = "Plaćeno",
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = OVERTIME_TYPE_CHOICE_HEIGHT.dp)
                    .fillMaxHeight(),
                bgColor = if (paidSelected) THEME.success else THEME.bgCard,
                textColor = if (paidSelected) THEME.textDark else THEME.textPrimary,
                radius = 14.dp,
                textStyle = MaterialTheme.typography.labelLarge,
                bold = true,
                onClick = { onOvertimeTypeSelected(OvertimeType.PAID) }
            )
            val compSelected = state.overtimeType == OvertimeType.COMP_TIME
            RoundedButton(
                text = "Za sate",
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = OVERTIME_TYPE_CHOICE_HEIGHT.dp)
                    .fillMaxHeight(),
                bgColor = if (compSelected) THEME.compTime else THEME.bgCard,
                textColor = if (compSelected) THEME.textDark else THEME.textPrimary,
                radius = 14.dp,
                textStyle = MaterialTheme.typography.labelLarge,
                bold = true,
                onClick = { onOvertimeTypeSelected(OvertimeType.COMP_TIME) }
            )
        }
    }
}

@Composable
private fun DayEditAdditionalShiftSelector(
    selectedShift: Shift,
    onSelected: (Shift) -> Unit
) {
    DayEditChoiceHeader(text = "Koja je dodatna smena?")
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = DAY_EDIT_SHIFT_CHOICE_HEIGHT.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        for (shift in Shift.workShifts()) {
            val isSelected = shift == selectedShift
            RoundedButton(
                text = dayEditShiftButtonText(shift),
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = DAY_EDIT_SHIFT_CHOICE_HEIGHT.dp)
                    .fillMaxHeight(),
                bgColor = if (isSelected) shift.color else shift.color.copy(alpha = 0.2f),
                textColor = shift.textColor,
                radius = 14.dp,
                textStyle = MaterialTheme.typography.labelMedium,
                bold = true,
                onClick = { onSelected(shift) }
            )
        }
    }
}

@Composable
private fun DayEditSickControls(
    sickPayRate: Double,
    onSickPayRateSelected: (Double) -> Unit
) {
    DayEditSectionCard(title = "Bolovanje") {
        DayEditChoiceHeader(text = "Isplata bolovanja")
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 44.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            val rates = listOf(
                CONFIG.pay.sickPayRate to "${(CONFIG.pay.sickPayRate * 100).toInt()}%",
                1.0 to "100%"
            )
            for (rate in rates) {
                val isSelected = kotlin.math.abs(sickPayRate - rate.first) < 0.01
                RoundedButton(
                    text = rate.second,
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = OVERTIME_TYPE_CHOICE_HEIGHT.dp),
                    bgColor = if (isSelected) THEME.sick else THEME.bgCardElevated,
                    textColor = THEME.textPrimary,
                    radius = 14.dp,
                    textStyle = MaterialTheme.typography.labelLarge,
                    bold = true,
                    onClick = { onSickPayRateSelected(rate.first) }
                )
            }
        }
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
                color = THEME.warning,
                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
                maxLines = 3,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun DayEditOffControls() {
    DayEditSectionCard(title = "Nisam radio") {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(Shift.OFF.color.copy(alpha = 0.16f), RoundedCornerShape(10.dp))
                .padding(horizontal = 10.dp, vertical = 8.dp)
        ) {
            Text(
                text = "Bez radnih sati.",
                color = THEME.textSecondary,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun DayEditCompensationBlock(
    compHoursUsed: Int,
    availableCompMinutes: Int,
    onUseClick: () -> Unit
) {
    DayEditSectionCard(title = "Kompenzacija") {
        Text(
            text = "Bilans sati: ${formatHoursAndMinutes(availableCompMinutes)}",
            color = THEME.textMuted,
            style = MaterialTheme.typography.labelMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        RoundedButton(
            text = "Iskoristi sate: ${formatHours(compHoursUsed)}",
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 44.dp),
            bgColor = THEME.compTimeUsed.copy(alpha = 0.4f),
            textColor = THEME.textPrimary,
            borderColor = THEME.compTimeUsed,
            radius = 14.dp,
            textStyle = MaterialTheme.typography.labelLarge,
            bold = true,
            onClick = onUseClick
        )
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
        text = "SA\u010cUVAJ",
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = UI.saveButtonHeight.dp),
        bgColor = if (isSaveEnabled) THEME.success else THEME.empty,
        textColor = if (isSaveEnabled) THEME.textDark else THEME.textMuted,
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
                regularShortageMinutes = saveRecord.regularShortageMinutes
            )
            onDismiss()
        }
    )
}

@Composable
private fun DayEditSectionCard(
    title: String,
    bgColor: Color = THEME.bgCard,
    borderColor: Color = THEME.glassBorder,
    content: @Composable ColumnScope.() -> Unit
) {
    PopupCard(bgColor = bgColor, borderColor = borderColor) {
        SectionLabel(text = title)
        content()
    }
}

@Composable
private fun DayEditChoiceHeader(text: String) {
    Text(
        text = text,
        color = THEME.textSecondary,
        style = MaterialTheme.typography.labelMedium,
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
        scrollable = false,
        titleTextAlign = TextAlign.Center,
        accent = THEME.danger
    ) {
        PopupCard(bgColor = THEME.bgCardElevated, borderColor = THEME.danger) {
            SectionLabel(text = BLOOD_DONATION_BOTTOM_BAR_LABEL)
            record?.let {
                Text(
                    text = "Datum: ${it.date.formatBloodDonationDate()}",
                    color = THEME.textSecondary,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            RoundedButton(
                text = "PONIŠTI DANE",
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = UI.buttonHeight.dp),
                bgColor = THEME.danger,
                textColor = THEME.textPrimary,
                textStyle = MaterialTheme.typography.labelLarge,
                bold = true,
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
    val shortageMinutesAllowed: Boolean,
    val effectiveRegularShortageMinutes: Int
)

private data class DayEditAdditionalWorkState(
    val shift2: Shift,
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
