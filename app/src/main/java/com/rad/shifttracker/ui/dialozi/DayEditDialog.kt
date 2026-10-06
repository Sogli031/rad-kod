package com.rad.shifttracker.ui.dialozi

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.time.LocalDate
import com.rad.shifttracker.MONTH_NAMES
import com.rad.shifttracker.UI
import com.rad.shifttracker.krv.formatBloodDonationDate
import com.rad.shifttracker.repo.ShiftRepository
import com.rad.shifttracker.repo.clearBloodDonationLeaveForDate
import com.rad.shifttracker.smene.DayRecord
import com.rad.shifttracker.godisnji.VacationRequestValidation
import com.rad.shifttracker.krv.BLOOD_DONATION_BOTTOM_BAR_LABEL
import com.rad.shifttracker.repo.calculateEstimatedDailyWorkNetPay
import com.rad.shifttracker.repo.getScheduledShiftForDate
import com.rad.shifttracker.repo.getSummary
import com.rad.shifttracker.smene.Shift
import com.rad.shifttracker.ui.CompUsedDialog
import com.rad.shifttracker.ui.GlowButton
import com.rad.shifttracker.ui.HoursPickerDialog
import com.rad.shifttracker.ui.PopupCard
import com.rad.shifttracker.ui.PopupDialog
import com.rad.shifttracker.ui.SectionLabel
import com.rad.shifttracker.ui.ShortageMinutesDialog
import com.rad.shifttracker.ui.extendedColors

internal const val DAY_TYPE_CHOICE_HEIGHT = 52
internal const val OVERTIME_TYPE_CHOICE_HEIGHT = 48

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
    val form = rememberDayEditForm(dayDate, record)
    val saveState = form.saveState
    val recordContentKey = record?.toMap()
    // Procene se računaju samo kad se unos promeni, ne pri svakoj rekompoziciji; repo se ne menja dok je dijalog otvoren.
    val estimatedNetDailyPay = remember(saveState) { repo.calculateEstimatedDailyWorkNetPay(saveState.toDayRecord()) }
    val savedNetDailyPay = remember(recordContentKey) { record?.let { repo.calculateEstimatedDailyWorkNetPay(it) } }
    val monthSummary = remember(year, month) { repo.getSummary(year, month) }
    val vacationValidation = remember(repo, dayDate, form.shift1, record) {
        if (form.shift1 == Shift.VACATION) {
            repo.validateVacationRequest(listOf(dayDate))
        } else {
            VacationRequestValidation(blockedDates = emptyList(), message = null)
        }
    }
    val holidayText = dayEditHolidayText(dayDate, form.shift1, record?.holidayOverride)

    var hoursPicker by remember { mutableStateOf<HoursPickerState?>(null) }
    var showShortageDialog by remember { mutableStateOf(false) }
    var showCompDialog by remember { mutableStateOf(false) }

    PopupDialog(
        title = "$day. ${MONTH_NAMES[month]}",
        onDismiss = onDismiss,
        scrollable = true,
        footer = { DayEditSaveButton(saveState, vacationValidation, repo, onDismiss) }
    ) {
        DayEditStatusHeader(estimatedNetDailyPay = estimatedNetDailyPay, savedNetDailyPay = savedNetDailyPay)
        holidayText?.let { text -> DayEditHolidayWarningStrip(text = text) }

        DayEditSectionCard(title = "Tip dana") {
            DayEditDayTypeSelector(
                selectedShift = form.shift1,
                paidLeaveSelected = form.paidLeave,
                onWorkSelected = { form.selectWork { repo.getScheduledShiftForDate(dayDate) } },
                onSickSelected = form::selectSick,
                onPaidLeaveSelected = form::selectPaidLeave,
                onVacationSelected = form::selectVacation,
                onOffSelected = form::selectOff
            )
        }

        DayEditShiftControls(
            form = form,
            vacationWarning = vacationValidation.message,
            onHoursPickerRequest = { picker -> hoursPicker = picker },
            onShortageClick = { showShortageDialog = true }
        )

        if (form.compHoursUsed > 0 || monthSummary.compTimeStarting > 0) {
            DayEditCompensationBlock(
                compHoursUsed = form.compHoursUsed,
                availableCompMinutes = monthSummary.compTimeStartingMinutes,
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
                    HoursTarget.SHIFT1 -> form.setRegularHours(selected)
                    HoursTarget.SHIFT2 -> form.setOvertimeHours(selected)
                }
            },
            onDismiss = { hoursPicker = null }
        )
    }

    if (showShortageDialog && form.shortageMinutesAllowed) {
        ShortageMinutesDialog(
            currentMinutes = form.effectiveRegularShortageMinutes,
            onSelect = { selected -> form.regularShortageMinutes = selected },
            onDismiss = { showShortageDialog = false }
        )
    }

    if (showCompDialog) {
        CompUsedDialog(
            availableHours = monthSummary.compTimeStarting,
            currentHours = form.compHoursUsed,
            onSelect = { selected -> form.compHoursUsed = selected },
            onDismiss = { showCompDialog = false }
        )
    }
}

/** Kontrole izabranog tipa dana: smene i sati za rad, stopa za bolovanje, upozorenje za godišnji. */
@Composable
private fun DayEditShiftControls(
    form: DayEditForm,
    vacationWarning: String?,
    onHoursPickerRequest: (HoursPickerState) -> Unit,
    onShortageClick: () -> Unit
) {
    val pickerAccent = MaterialTheme.colorScheme.primary
    when (form.shift1) {
        Shift.FIRST, Shift.SECOND, Shift.THIRD -> DayEditWorkControls(
            state = DayEditWorkControlState(
                shift1 = form.shift1,
                workHours1 = form.workHours1,
                hasDouble = form.hasDouble,
                showDoubleSwitch = form.showDoubleSwitch,
                showOvertimeCard = form.showOvertimeCard,
                shortageMinutesAllowed = form.shortageMinutesAllowed,
                effectiveRegularShortageMinutes = form.effectiveRegularShortageMinutes
            ),
            onPrimaryShiftSelected = form::selectPrimaryShift,
            onRegularHoursClick = {
                onHoursPickerRequest(
                    HoursPickerState(
                        title = "Izaberi sate",
                        current = form.workHours1,
                        accent = pickerAccent,
                        target = HoursTarget.SHIFT1,
                        minHours = 1
                    )
                )
            },
            onShortageClick = onShortageClick,
            onDoubleShiftChanged = form::setDoubleShift,
            additionalWorkContent = {
                DayEditAdditionalWorkControls(
                    state = DayEditAdditionalWorkState(
                        shift2 = form.shift2,
                        secondShiftOptions = form.secondShiftOptions,
                        workHours2 = form.workHours2,
                        hasDouble = form.hasDouble,
                        overtimeType = form.overtimeType,
                        weekendOvertimeType = form.weekendOvertimeType,
                        hasPrimaryNonRegularAdditionalWork = form.hasPrimaryNonRegularAdditionalWork,
                        showPrimaryExtraHoursMode = form.showPrimaryExtraHoursMode
                    ),
                    onSecondShiftSelected = { selected -> form.shift2 = selected },
                    onOvertimeHoursClick = {
                        onHoursPickerRequest(
                            HoursPickerState(
                                title = "Izaberi prekovremene sate",
                                current = form.workHours2,
                                accent = pickerAccent,
                                target = HoursTarget.SHIFT2,
                                minHours = 1
                            )
                        )
                    },
                    onOvertimeTypeSelected = { selected -> form.overtimeType = selected },
                    onWeekendOvertimeTypeSelected = { selected -> form.weekendOvertimeType = selected }
                )
            }
        )

        Shift.SICK -> if (form.paidLeave) {
            DayEditPaidLeaveControls()
        } else {
            DayEditSickControls(
                sickPayRate = form.sickPayRate,
                onSickPayRateSelected = { selected -> form.sickPayRate = selected }
            )
        }

        Shift.VACATION -> DayEditVacationControls(warningMessage = vacationWarning)

        Shift.OFF -> DayEditOffControls()
    }
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

private data class HoursPickerState(
    val title: String,
    val current: Int,
    val accent: Color,
    val target: HoursTarget,
    val minHours: Int
)
