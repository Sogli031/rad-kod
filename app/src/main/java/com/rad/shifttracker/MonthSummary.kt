package com.rad.shifttracker

data class MonthSummary(
    var year: Int,
    var month: Int,
    var workHours: Int = 0,
    var workMinutes: Int = 0,
    var overtimeHours: Int = 0,
    var sickHours: Int = 0,
    var vacationHours: Int = 0,
    var sundayHours: Int = 0,
    var nightHours: Int = 0,
    var totalPay: Double = 0.0,
    var workDays: Int = 0,
    var doubleShiftDays: Int = 0,
    var foodAllowance: Double = 0.0,
    var compTimeEarned: Int = 0,
    var compTimeDeficit: Int = 0,
    var compTimeUsed: Int = 0,
    var compTimeNet: Int = 0,
    var compTimeStarting: Int = 0,
    var compTimeEnding: Int = 0,
    var compTimeEarnedMinutes: Int = 0,
    var compTimeDeficitMinutes: Int = 0,
    var compTimeUsedMinutes: Int = 0,
    var compTimeNetMinutes: Int = 0,
    var compTimeStartingMinutes: Int = 0,
    var compTimeEndingMinutes: Int = 0,
    var yearsOfService: Int = 0,
    var seniorityEligibleWorkMinutes: Int = 0,
    var minuliRadBonus: Double = 0.0,
    var difficultWorkConditionsBonus: Double = 0.0
) {
    val totalWithMinuli: Double
        get() = totalPay + minuliRadBonus

    val displayName: String
        get() = if (month in 1..12) {
            "${MONTH_NAMES[month]} $year"
        } else {
            "Mesec $month $year"
        }
}
