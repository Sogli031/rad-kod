package com.rad.shifttracker

import kotlin.math.abs
import kotlin.math.roundToInt

private const val MAX_PAYROLL_SLIP_MONTH_HOURS = 400.0
private const val QUARTER_HOUR_MINUTES = 15
private const val QUARTERS_PER_HOUR = 4
private const val QUARTER_HOUR_TOLERANCE = 1e-9

data class PayrollSlipInput(
    val month: MonthKey,
    val regularWorkHours: Double = 0.0,
    val nightHours: Double = 0.0,
    val overtimeHours: Double = 0.0,
    val vacationHours: Double = 0.0,
    val sickHours: Double = 0.0,
    val holidayHours: Double = 0.0,
    val regularHolidayWorkHours: Double = 0.0,
    val sundayHours: Double = 0.0,
    val holidayWorkHours: Double = 0.0,
    val absenceHours: Double = 0.0,
    val mealGross: Double = 0.0,
    val regressGross: Double = 0.0,
    val annualAwardGross: Double = 0.0,
    val kirGross: Double = 0.0,
    val otherGross: Double = 0.0
) {
    fun validationErrors(): List<String> {
        val errors = mutableListOf<String>()
        if (month.year <= 0) errors += "Godina mora biti veća od 0."
        if (month.month !in 1..12) errors += "Mesec mora biti između 1 i 12."
        validateHours("Rad u režiji", regularWorkHours, errors)
        validateHours("Noćni rad", nightHours, errors)
        validateHours("Prekovremeni rad", overtimeHours, errors)
        validateHours("Godišnji", vacationHours, errors)
        validateHours("Bolovanje", sickHours, errors)
        validateHours("Državni praznik", holidayHours, errors)
        validateHours("Rad u režiji na DP", regularHolidayWorkHours, errors)
        validateHours("Rad nedeljom", sundayHours, errors)
        validateHours("Rad na praznik", holidayWorkHours, errors)
        validateHours("Izostanci", absenceHours, errors)
        validateMoney("Topli obrok", mealGross, errors)
        validateMoney("Regres", regressGross, errors)
        validateMoney("Godišnja nagrada", annualAwardGross, errors)
        validateMoney("KIR", kirGross, errors)
        validateMoney("Ostalo bruto", otherGross, errors)
        return errors
    }

    fun toMonthlyGrossAdditions(): MonthlyGrossAdditions {
        return MonthlyGrossAdditions(
            year = month.year,
            month = month.month,
            mealGross = mealGross,
            regressGross = regressGross,
            bonusGross = annualAwardGross + kirGross + otherGross
        )
    }
}

data class PayrollSlipDayDraft(
    val year: Int,
    val month: Int,
    val day: Int,
    val shift: Shift,
    val hours: Int,
    val shift2: Shift? = null,
    val hours2: Int = 0,
    val overtimeType: OvertimeType = OvertimeType.PAID,
    val weekendOvertimeType: OvertimeType = OvertimeType.PAID,
    val holidayWorkOverride: Boolean? = null,
    val holidayOverride: Boolean? = null,
    val balanceExempt: Boolean = false,
    val sickPayRate: Double? = null,
    val regularShortageMinutes: Int = 0
) {
    fun toDayRecord(): DayRecord = DayRecord(
        year = year,
        month = month,
        day = day,
        shift = shift,
        hours = hours,
        shift2 = shift2,
        hours2 = hours2,
        overtimeType = overtimeType,
        weekendOvertimeType = weekendOvertimeType,
        holidayWorkOverride = holidayWorkOverride,
        holidayOverride = holidayOverride,
        balanceExempt = balanceExempt,
        sickPayRate = sickPayRate,
        regularShortageMinutes = regularShortageMinutes
    )
}

enum class PayrollSlipWarningType {
    APPROXIMATE_DATES,
    NO_REFERENCE_WEEK,
    UNSUPPORTED_ABSENCE_MINUTES,
    INFEASIBLE_TARGET,
    TARGET_MISMATCH,
    EXISTING_MONTH_WILL_BE_REPLACED
}

data class PayrollSlipWarning(
    val type: PayrollSlipWarningType,
    val message: String
)

data class PayrollSlipComparisonRow(
    val label: String,
    val targetMinutes: Int,
    val calculatedMinutes: Int
) {
    val deltaMinutes: Int
        get() = calculatedMinutes - targetMinutes

    val isExact: Boolean
        get() = deltaMinutes == 0
}

data class PayrollSlipComparison(
    val rows: List<PayrollSlipComparisonRow>,
    val calculatedNetBeforeDeductions: Double,
    val targetNetBeforeDeductions: Double? = null
) {
    val allHoursExact: Boolean
        get() = rows.all { it.isExact }
}

data class PayrollSlipReconstructionResult(
    val input: PayrollSlipInput,
    val records: List<PayrollSlipDayDraft>,
    val additions: MonthlyGrossAdditions,
    val comparison: PayrollSlipComparison,
    val warnings: List<PayrollSlipWarning>
) {
    val hasBlockingErrors: Boolean
        get() = input.validationErrors().isNotEmpty() ||
            warnings.any { it.type == PayrollSlipWarningType.INFEASIBLE_TARGET }
}

fun slipHoursToMinutes(value: Double): Int? {
    if (!value.isFinite() || value < 0.0 || value > MAX_PAYROLL_SLIP_MONTH_HOURS) return null
    val quarterHours = value * QUARTERS_PER_HOUR
    val roundedQuarterHours = quarterHours.roundToInt()
    if (abs(quarterHours - roundedQuarterHours) > QUARTER_HOUR_TOLERANCE) return null
    return roundedQuarterHours * QUARTER_HOUR_MINUTES
}

private fun validateHours(label: String, value: Double, errors: MutableList<String>) {
    when {
        !value.isFinite() -> errors += "$label mora biti broj."
        value < 0.0 -> errors += "$label ne može biti negativan."
        value > MAX_PAYROLL_SLIP_MONTH_HOURS -> errors += "$label ne može biti veći od 400h."
        slipHoursToMinutes(value) == null -> errors += "$label mora biti u koraku od 15 minuta."
    }
}

private fun validateMoney(label: String, value: Double, errors: MutableList<String>) {
    when {
        !value.isFinite() -> errors += "$label mora biti broj."
        value < 0.0 -> errors += "$label ne može biti negativan."
    }
}
