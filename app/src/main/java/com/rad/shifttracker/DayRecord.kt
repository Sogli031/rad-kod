package com.rad.shifttracker

import java.time.LocalDate
import org.json.JSONObject

data class DayKey(val year: Int, val month: Int, val day: Int)

data class MonthKey(val year: Int, val month: Int) : Comparable<MonthKey> {
    override fun compareTo(other: MonthKey): Int {
        return if (year != other.year) year - other.year else month - other.month
    }
}

enum class DayRecordSource(val jsonValue: String) {
    MANUAL("manual"),
    AUTO_FILL("auto_fill"),
    BLOOD_DONATION_LEAVE("blood_donation_leave");

    companion object {
        fun fromJson(value: String?): DayRecordSource {
            return values().firstOrNull { it.jsonValue == value } ?: MANUAL
        }
    }
}

class DayRecord(
    val year: Int,
    val month: Int,
    val day: Int,
    val shift: Shift,
    hours: Int,
    shift2: Shift? = null,
    hours2: Int = 0,
    val overtimeType: OvertimeType = OvertimeType.PAID,
    val weekendOvertimeType: OvertimeType = OvertimeType.PAID,
    compHoursUsed: Int = 0,
    val holidayWorkOverride: Boolean? = null,
    val holidayOverride: Boolean? = null,
    val balanceExempt: Boolean = false,
    sickPayRate: Double? = null,
    regularShortageMinutes: Int = 0,
    source: DayRecordSource = DayRecordSource.MANUAL
) {
    val workHours: Int = if (shift == Shift.OFF) 0 else normalizeDayHours(hours)
    private val hasValidSecondShiftInput: Boolean = shift in Shift.workShifts() && shift2 in Shift.workShifts()
    val workHours2: Int = if (hasValidSecondShiftInput) normalizeDayHours(hours2) else 0
    val shift2: Shift? = if (workHours2 > 0) shift2 else null
    val compHoursUsed: Int = normalizeDayHours(compHoursUsed)
    val hours: Int = workHours
    val hours2: Int = workHours2
    val sickPayRate: Double? = run {
        var rate = sickPayRate
        if (rate != null) {
            rate = when {
                rate <= 0.0 -> null
                rate > 1.0 -> 1.0
                else -> rate
            }
        }
        if (shift != Shift.SICK) null else rate
    }
    val regularShortageMinutes: Int = if (shift in Shift.workShifts() && workHours > 0) {
        normalizeRegularShortageMinutesForWorkHours(workHours, regularShortageMinutes)
    } else {
        0
    }
    val source: DayRecordSource = when {
        source == DayRecordSource.BLOOD_DONATION_LEAVE && shift == Shift.SICK -> source
        source == DayRecordSource.AUTO_FILL && (shift in Shift.workShifts() || shift == Shift.OFF) -> source
        else -> DayRecordSource.MANUAL
    }

    val date: LocalDate = LocalDate.of(year, month, day)
    val weekday: Weekday = Weekday.from(date)
    val key: DayKey = DayKey(year, month, day)
    val monthKey: MonthKey = MonthKey(year, month)

    val isWorkDay: Boolean
        get() = shift in Shift.workShifts() && workHours > 0

    val isBloodDonationLeave: Boolean
        get() = source == DayRecordSource.BLOOD_DONATION_LEAVE

    val isDoubleShift: Boolean
        get() = shift in Shift.workShifts() && shift2 != null && shift2 in Shift.workShifts() && workHours2 > 0

    val isCompTimeOvertime: Boolean
        get() = isDoubleShift && overtimeType == OvertimeType.COMP_TIME

    private val isPrimaryNonRegularAdditionalWork: Boolean
        get() = shift in Shift.workShifts() && workHours > 0 && (weekday.isWeekend || workedOnHoliday)

    private val primaryAdditionalWorkType: OvertimeType
        get() = if (isPrimaryNonRegularAdditionalWork) weekendOvertimeType else overtimeType

    val primaryAdditionalWorkMinutes: Int
        get() {
            if (shift !in Shift.workShifts() || effectiveRegularWorkMinutes <= 0) return 0
            if (isPrimaryNonRegularAdditionalWork) return effectiveRegularWorkMinutes
            val regularMinutes = CONFIG.pay.defaultHours * MINUTES_PER_HOUR
            return (effectiveRegularWorkMinutes - regularMinutes).coerceAtLeast(0)
        }

    val compensatoryPrimaryAdditionalWorkMinutes: Int
        get() = if (primaryAdditionalWorkType == OvertimeType.COMP_TIME) {
            primaryAdditionalWorkMinutes
        } else {
            0
        }

    val compensatoryAdditionalWorkMinutes: Int
        get() {
            var minutes = compensatoryPrimaryAdditionalWorkMinutes
            if (isCompTimeOvertime) {
                minutes += workHours2 * MINUTES_PER_HOUR
            }
            return minutes
        }

    val paidPrimaryWorkMinutes: Int
        get() {
            if (shift !in Shift.workShifts()) return 0
            return (effectiveRegularWorkMinutes - compensatoryPrimaryAdditionalWorkMinutes).coerceAtLeast(0)
        }

    val paidSecondShiftMinutes: Int
        get() = if (isDoubleShift && !isCompTimeOvertime) workHours2 * MINUTES_PER_HOUR else 0

    val paidWorkMinutes: Int
        get() = paidPrimaryWorkMinutes + paidSecondShiftMinutes

    val totalHours: Int
        get() = workHours + if (isDoubleShift) workHours2 else 0

    val effectiveRegularWorkMinutes: Int
        get() = (workHours * MINUTES_PER_HOUR + regularShortageMinutes)
            .coerceAtMost(MAX_DAY_HOURS * MINUTES_PER_HOUR)

    val totalWorkMinutes: Int
        get() = effectiveRegularWorkMinutes + if (isDoubleShift) workHours2 * MINUTES_PER_HOUR else 0

    val compTimeEarnedMinutes: Int
        get() = compensatoryAdditionalWorkMinutes

    val compTimeEarned: Int
        get() = compTimeEarnedMinutes / MINUTES_PER_HOUR

    val hoursDeficit: Int
        get() = hoursDeficitFor(BrigadeType.TROBRIGADA)

    fun hoursDeficitFor(brigadeType: BrigadeType): Int {
        return hoursDeficitMinutesFor(brigadeType) / MINUTES_PER_HOUR
    }

    fun hoursDeficitMinutesFor(brigadeType: BrigadeType): Int {
        if (!isBalanceWorkDay(brigadeType)) return 0
        if (shift == Shift.OFF) {
            return if (isHoliday || balanceExempt) 0 else CONFIG.pay.defaultHours * MINUTES_PER_HOUR
        }
        if (shift !in Shift.workShifts()) return 0
        val workedMinutes = if (isDoubleShift && !isCompTimeOvertime) totalWorkMinutes else effectiveRegularWorkMinutes
        val defaultMinutes = CONFIG.pay.defaultHours * MINUTES_PER_HOUR
        return if (workedMinutes < defaultMinutes) defaultMinutes - workedMinutes else 0
    }

    private fun isBalanceWorkDay(brigadeType: BrigadeType): Boolean {
        return when (brigadeType) {
            BrigadeType.TROBRIGADA -> weekday.isWorkday
            BrigadeType.CETVOROBRIGADA -> shift in Shift.workShifts() || shift == Shift.OFF
        }
    }

    /**
     * Koliko se minuta skida sa balansa za ovaj dan. Manjak i "iskoristi
     * sate" na istom danu se preklapaju: iskorišćeni sati pokrivaju manjak
     * istog dana, pa se sa balansa skida veći od ta dva iznosa, a ne zbir —
     * inače OFF radni dan sa "iskoristi 8h" skine 16h za 8h plate.
     */
    fun balanceDebitMinutesFor(brigadeType: BrigadeType): Int {
        return maxOf(hoursDeficitMinutesFor(brigadeType), compHoursUsed * MINUTES_PER_HOUR)
    }

    fun netCompChangeMinutesFor(brigadeType: BrigadeType): Int {
        return compTimeEarnedMinutes - balanceDebitMinutesFor(brigadeType)
    }

    val isHoliday: Boolean by lazy {
        holidayOverride ?: isSerbianHoliday(year, month, day)
    }

    val holidayName: String?
        get() {
            val baseName = getHolidayName(year, month, day)
            if (holidayOverride == false) return null
            if (holidayOverride == true && baseName == null) return MANUAL_HOLIDAY_LABEL
            return baseName
        }

    val workedOnHoliday: Boolean
        get() {
            if (!isHoliday) return false
            if (holidayWorkOverride != null) return holidayWorkOverride
            return shift in Shift.workShifts() && totalHours > 0
        }

    fun toMap(): Map<String, Any> {
        val data = linkedMapOf<String, Any>(
            "shift" to shift.value,
            "hours" to hours
        )
        val s2 = shift2
        if (isDoubleShift && s2 != null) {
            data["shift2"] = s2.value
            data["hours2"] = hours2
            data["overtime_type"] = overtimeType.value
        }
        if (weekendOvertimeType != OvertimeType.PAID) {
            data["weekend_overtime_type"] = weekendOvertimeType.value
        }
        if (compHoursUsed > 0) {
            data["comp_hours_used"] = compHoursUsed
        }
        if (holidayWorkOverride != null) {
            data["holiday_work_override"] = holidayWorkOverride
        }
        if (holidayOverride != null) {
            data["holiday_override"] = holidayOverride
        }
        if (balanceExempt) {
            data["balance_exempt"] = true
        }
        if (sickPayRate != null) {
            data["sick_pay_rate"] = sickPayRate
        }
        if (regularShortageMinutes > 0) {
            data["regular_shortage_minutes"] = regularShortageMinutes
        }
        if (source != DayRecordSource.MANUAL) {
            data["source"] = source.jsonValue
        }
        return data
    }

    companion object {
        private const val MINUTES_PER_HOUR = 60

        fun fromJson(year: Int, month: Int, day: Int, data: JSONObject): DayRecord {
            val shift = Shift.fromValue(data.optInt("shift", 0))
            val hours = data.optInt("hours", 0)
            val shift2 = if (data.has("shift2")) Shift.fromValue(data.optInt("shift2", 0)) else null
            val hours2 = data.optInt("hours2", 0)
            val overtimeType = OvertimeType.fromValue(data.optInt("overtime_type", 0))
            val weekendOvertimeType = OvertimeType.fromValue(data.optInt("weekend_overtime_type", 0))
            val compHoursUsed = data.optInt("comp_hours_used", 0)
            val holidayWorkOverride = if (data.has("holiday_work_override")) {
                data.optBoolean("holiday_work_override")
            } else {
                null
            }
            val holidayOverride = if (data.has("holiday_override")) {
                data.optBoolean("holiday_override")
            } else {
                null
            }
            val balanceExempt = data.optBoolean("balance_exempt", false)
            val sickPayRate = if (data.has("sick_pay_rate")) {
                data.optDouble("sick_pay_rate")
            } else {
                null
            }
            val regularShortageMinutes = data.optInt("regular_shortage_minutes", 0)
            val source = DayRecordSource.fromJson(data.optString("source", DayRecordSource.MANUAL.jsonValue))
            return DayRecord(
                year = year,
                month = month,
                day = day,
                shift = shift,
                hours = hours,
                shift2 = shift2,
                hours2 = hours2,
                overtimeType = overtimeType,
                weekendOvertimeType = weekendOvertimeType,
                compHoursUsed = compHoursUsed,
                holidayWorkOverride = holidayWorkOverride,
                holidayOverride = holidayOverride,
                balanceExempt = balanceExempt,
                sickPayRate = sickPayRate,
                regularShortageMinutes = regularShortageMinutes,
                source = source
            )
        }
    }
}
