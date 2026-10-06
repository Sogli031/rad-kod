package com.rad.shifttracker.ui.dialozi

import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import java.time.LocalDate
import com.rad.shifttracker.CONFIG
import com.rad.shifttracker.MANUAL_HOLIDAY_LABEL
import com.rad.shifttracker.normalizeDayHours
import com.rad.shifttracker.regularShortageMinutesAllowed
import com.rad.shifttracker.smene.DayKey
import com.rad.shifttracker.smene.DayRecord
import com.rad.shifttracker.smene.OvertimeType
import com.rad.shifttracker.smene.Shift
import com.rad.shifttracker.smene.getHolidayName
import com.rad.shifttracker.smene.isSerbianHoliday

/**
 * Uneto stanje dijaloga za dan i prelazi između tipova dana. Polja su odvojena stanja iz
 * [rememberDayEditForm], pa svako preživljava rotaciju; ostalo su nepromenljive osobine dana i zapisa.
 */
@Stable
internal class DayEditForm(
    private val date: DayKey,
    private val isWeekend: Boolean,
    private val isHolidayEffective: Boolean,
    private val holidayOverride: Boolean?,
    private val holidayWorkOverride: Boolean?,
    private val recordBalanceExempt: Boolean,
    private val fields: DayEditFields
) {
    var shift1 by fields.shift1
    var workHours1 by fields.workHours1
    var shift2 by fields.shift2
    var workHours2 by fields.workHours2
    var hasDouble by fields.hasDouble
    var overtimeType by fields.overtimeType
    var weekendOvertimeType by fields.weekendOvertimeType
    var compHoursUsed by fields.compHoursUsed
    var regularShortageMinutes by fields.regularShortageMinutes
    var sickPayRate by fields.sickPayRate
    var paidLeave by fields.paidLeave

    val firstShiftAllowsDouble: Boolean
        get() = shift1 in Shift.workShifts()

    /** Stari zapis sa dodatnom smenom van dozvoljenih ostaje vidljiv da može da se ispravi. */
    val secondShiftOptions: List<Shift>
        get() = overtimeShiftsAfter(shift1).let { allowed ->
            if (hasDouble && shift2 !in allowed) (allowed + shift2).sortedBy { it.ordinal } else allowed
        }

    val showDoubleSwitch: Boolean
        get() = secondShiftOptions.isNotEmpty()

    val hasPrimaryNonRegularAdditionalWork: Boolean
        get() = firstShiftAllowsDouble && (isWeekend || isHolidayEffective)

    val showPrimaryExtraHoursMode: Boolean
        get() {
            val hasPrimaryExtraHours = firstShiftAllowsDouble && workHours1 > CONFIG.pay.defaultHours
            return hasPrimaryExtraHours && !hasDouble && !hasPrimaryNonRegularAdditionalWork
        }

    val showOvertimeCard: Boolean
        get() = showDoubleSwitch || hasPrimaryNonRegularAdditionalWork || showPrimaryExtraHoursMode

    val shortageMinutesAllowed: Boolean
        get() = shift1 in Shift.workShifts() && regularShortageMinutesAllowed(workHours1)

    val effectiveRegularShortageMinutes: Int
        get() = if (shortageMinutesAllowed) regularShortageMinutes else 0

    val saveState: DayEditSaveState
        get() = DayEditSaveState(
            date = date,
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
            balanceExempt = recordBalanceExempt && shift1 == Shift.OFF,
            sickPayRate = sickPayRate,
            regularShortageMinutes = effectiveRegularShortageMinutes,
            paidLeave = paidLeave
        )

    /** Rad: smena po rasporedu (ili prva) ako dan još nije bio radni, i pun dan ako sati nisu uneti. */
    fun selectWork(scheduledShift: () -> Shift?) {
        paidLeave = false
        if (shift1 !in Shift.workShifts()) {
            shift1 = scheduledShift()?.takeIf { it in Shift.workShifts() } ?: Shift.FIRST
            if (overtimeShiftsAfter(shift1).isEmpty()) hasDouble = false
        }
        if (workHours1 == 0) {
            workHours1 = CONFIG.pay.defaultHours
        }
        if (!regularShortageMinutesAllowed(workHours1)) {
            regularShortageMinutes = 0
        }
    }

    fun selectSick() = selectNonWork(Shift.SICK, paidLeave = false, hours = CONFIG.pay.defaultHours)

    fun selectPaidLeave() = selectNonWork(Shift.SICK, paidLeave = true, hours = CONFIG.pay.defaultHours)

    fun selectVacation() = selectNonWork(Shift.VACATION, paidLeave = false, hours = CONFIG.pay.defaultHours)

    fun selectOff() = selectNonWork(Shift.OFF, paidLeave = false, hours = 0)

    private fun selectNonWork(shift: Shift, paidLeave: Boolean, hours: Int) {
        shift1 = shift
        this.paidLeave = paidLeave
        workHours1 = hours
        hasDouble = false
        regularShortageMinutes = 0
    }

    fun selectPrimaryShift(selected: Shift) {
        shift1 = selected
        val allowed = overtimeShiftsAfter(selected)
        if (allowed.isEmpty()) {
            hasDouble = false
        } else if (shift2 !in allowed) {
            shift2 = defaultOvertimeShiftAfter(selected)
        }
    }

    fun setDoubleShift(checked: Boolean) {
        val nonRegularAdditionalWork = hasPrimaryNonRegularAdditionalWork
        hasDouble = checked
        if (checked) {
            if (nonRegularAdditionalWork) overtimeType = weekendOvertimeType
            shift2 = defaultOvertimeShiftAfter(shift1)
            if (workHours2 == 0) {
                workHours2 = defaultOvertimeHours(shift1)
            }
        }
    }

    fun setRegularHours(selected: Int) {
        workHours1 = normalizeDayHours(selected)
        if (!regularShortageMinutesAllowed(workHours1)) {
            regularShortageMinutes = 0
        }
    }

    fun setOvertimeHours(selected: Int) {
        workHours2 = normalizeDayHours(selected)
    }
}

/** Polja dijaloga koja korisnik menja; svako je posebno sačuvano stanje. */
internal class DayEditFields(
    val shift1: MutableState<Shift>,
    val workHours1: MutableState<Int>,
    val shift2: MutableState<Shift>,
    val workHours2: MutableState<Int>,
    val hasDouble: MutableState<Boolean>,
    val overtimeType: MutableState<OvertimeType>,
    val weekendOvertimeType: MutableState<OvertimeType>,
    val compHoursUsed: MutableState<Int>,
    val regularShortageMinutes: MutableState<Int>,
    val sickPayRate: MutableState<Double>,
    val paidLeave: MutableState<Boolean>
) {
    companion object {
        /** Početne vrednosti iz zapisa (bez zapisa: slobodan dan). */
        fun from(record: DayRecord?): DayEditFields {
            val shift = record?.shift ?: Shift.OFF
            return DayEditFields(
                shift1 = mutableStateOf(shift),
                workHours1 = mutableStateOf(record?.workHours ?: CONFIG.pay.defaultHours),
                shift2 = mutableStateOf(record?.shift2 ?: defaultOvertimeShiftAfter(shift)),
                workHours2 = mutableStateOf(
                    (record?.workHours2 ?: 0).takeIf { it > 0 } ?: defaultOvertimeHours(shift)
                ),
                hasDouble = mutableStateOf(record?.isDoubleShift ?: false),
                overtimeType = mutableStateOf(record?.overtimeType ?: OvertimeType.PAID),
                weekendOvertimeType = mutableStateOf(record?.weekendOvertimeType ?: OvertimeType.PAID),
                compHoursUsed = mutableStateOf(record?.compHoursUsed ?: 0),
                regularShortageMinutes = mutableStateOf(record?.regularShortageMinutes ?: 0),
                sickPayRate = mutableStateOf(initialSickPayRate(record)),
                paidLeave = mutableStateOf(record?.isPaidLeave == true)
            )
        }

        private fun initialSickPayRate(record: DayRecord?): Double = when {
            record?.shift == Shift.SICK && record.sickPayRate != null -> record.sickPayRate
            else -> CONFIG.pay.sickPayRate
        }
    }
}

internal fun dayEditForm(date: LocalDate, record: DayRecord?, fields: DayEditFields): DayEditForm {
    val holidayOverride = record?.holidayOverride
    return DayEditForm(
        date = DayKey(date.year, date.monthValue, date.dayOfMonth),
        isWeekend = date.dayOfWeek.value >= 6,
        isHolidayEffective = holidayOverride ?: isSerbianHoliday(date.year, date.monthValue, date.dayOfMonth),
        holidayOverride = holidayOverride,
        holidayWorkOverride = record?.holidayWorkOverride,
        recordBalanceExempt = record?.balanceExempt == true,
        fields = fields
    )
}

/**
 * rememberSaveable: uneto stanje preživljava rotaciju/ubijanje procesa.
 * Ključ je SADRŽAJ zapisa (toMap), ne instanca — DayRecord nema strukturni
 * equals pa bi nova instanca posle rotacije obrisala uneto.
 */
@Composable
internal fun rememberDayEditForm(date: LocalDate, record: DayRecord?): DayEditForm {
    val year = date.year
    val month = date.monthValue
    val day = date.dayOfMonth
    val recordContentKey = record?.toMap()
    val initial = remember(year, month, day, recordContentKey) { DayEditFields.from(record) }
    val fields = DayEditFields(
        shift1 = rememberSaveable(year, month, day, recordContentKey) { initial.shift1 },
        workHours1 = rememberSaveable(year, month, day, recordContentKey) { initial.workHours1 },
        shift2 = rememberSaveable(year, month, day, recordContentKey) { initial.shift2 },
        workHours2 = rememberSaveable(year, month, day, recordContentKey) { initial.workHours2 },
        hasDouble = rememberSaveable(year, month, day, recordContentKey) { initial.hasDouble },
        overtimeType = rememberSaveable(year, month, day, recordContentKey) { initial.overtimeType },
        weekendOvertimeType = rememberSaveable(year, month, day, recordContentKey) { initial.weekendOvertimeType },
        compHoursUsed = rememberSaveable(year, month, day, recordContentKey) { initial.compHoursUsed },
        regularShortageMinutes = rememberSaveable(year, month, day, recordContentKey) {
            initial.regularShortageMinutes
        },
        sickPayRate = rememberSaveable(year, month, day, recordContentKey) { initial.sickPayRate },
        paidLeave = rememberSaveable(year, month, day, recordContentKey) { initial.paidLeave }
    )
    return dayEditForm(date, record, fields)
}

/** Natpis praznika iznad izbora tipa dana; null kad dan nije praznik ili je praznik ručno skinut. */
internal fun dayEditHolidayText(date: LocalDate, shift1: Shift, holidayOverride: Boolean?): String? {
    val isHolidayEffective = holidayOverride ?: isSerbianHoliday(date.year, date.monthValue, date.dayOfMonth)
    if (!isHolidayEffective) return null
    val baseName = getHolidayName(date.year, date.monthValue, date.dayOfMonth)
    // Ručno skinut praznik (holidayOverride == false) je već vratio null iznad.
    val name = (if (holidayOverride == true && baseName == null) MANUAL_HOLIDAY_LABEL else baseName)
        ?: return null
    val isWorkday = date.dayOfWeek.value <= 5
    return when {
        shift1 in Shift.workShifts() -> "Praznik: $name (rad +${(CONFIG.pay.holidayBonus * 100).toInt()}%)"
        isWorkday -> "Praznik: $name (neradni, 100%)"
        else -> "Praznik: $name (vikend)"
    }
}
