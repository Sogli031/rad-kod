package com.rad.shifttracker

import java.time.LocalDate

enum class Shift(val value: Int) {
    OFF(0),
    FIRST(1),
    SECOND(2),
    THIRD(3),
    SICK(4),
    VACATION(5);

    val displayName: String
        get() = when (this) {
            OFF -> "OFF"
            FIRST -> "S1"
            SECOND -> "S2"
            THIRD -> "S3"
            SICK -> "BOL"
            VACATION -> "GO"
        }

    val fullName: String
        get() = when (this) {
            OFF -> "Slobodan dan"
            FIRST -> "Prva smena"
            SECOND -> "Druga smena"
            THIRD -> "Tre\u0107a smena"
            SICK -> "Bolovanje"
            VACATION -> "Godi\u0161nji odmor"
        }

    val color
        get() = when (this) {
            OFF -> THEME.off
            FIRST -> THEME.shift1
            SECOND -> THEME.shift2
            THIRD -> THEME.shift3
            SICK -> THEME.sick
            VACATION -> THEME.vacation
        }

    val textColor
        get() = when (this) {
            FIRST, SECOND, VACATION -> THEME.textDark
            else -> THEME.textPrimary
        }

    val icon: String
        get() = when (this) {
            FIRST -> "Prva"
            SECOND -> "Druga"
            THIRD -> "Treća"
            else -> displayName
        }

    companion object {
        fun fromValue(value: Int): Shift {
            return values().firstOrNull { it.value == value } ?: OFF
        }

        fun workShifts(): List<Shift> = listOf(FIRST, SECOND, THIRD)

        fun paidNonWork(): List<Shift> = listOf(SICK, VACATION)
    }
}

enum class BrigadeType(val value: String, val label: String) {
    TROBRIGADA("trob", "Trobrigada"),
    CETVOROBRIGADA("cetv", "Četvorobrigada");

    companion object {
        fun fromValue(value: String?): BrigadeType {
            return values().firstOrNull { it.value == value } ?: TROBRIGADA
        }
    }
}

enum class ShiftFillMode(val label: String) {
    ONE_SHIFT("Jedna smena"),
    FIRST_SECOND("Prva/Druga"),
    THREE_BRIGADE("Trobrigada"),
    FOUR_BRIGADE("Četvorobrigada")
}

data class ShiftFillSettings(
    val mode: ShiftFillMode,
    val anchorDate: LocalDate,
    val selectedShift: Shift,
    val cycleDayIndex: Int? = null
)

enum class OvertimeType(val value: Int) {
    PAID(0),
    COMP_TIME(1);

    companion object {
        fun fromValue(value: Int): OvertimeType {
            return if (value == 1) COMP_TIME else PAID
        }
    }
}

enum class Weekday {
    MONDAY,
    TUESDAY,
    WEDNESDAY,
    THURSDAY,
    FRIDAY,
    SATURDAY,
    SUNDAY;

    val isWeekend: Boolean
        get() = this >= SATURDAY

    val isWorkday: Boolean
        get() = this <= FRIDAY

    companion object {
        fun from(date: LocalDate): Weekday {
            return values()[date.dayOfWeek.ordinal]
        }
    }
}
