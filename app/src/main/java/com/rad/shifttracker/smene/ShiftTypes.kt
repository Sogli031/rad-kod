package com.rad.shifttracker.smene

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.graphics.Color
import java.time.LocalDate
import com.rad.shifttracker.ui.ExtendedColors
import com.rad.shifttracker.ui.extendedColors

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

    /** Boja smene u zadatoj temi (za proračune van kompozicije, npr. ton ćelije kalendara). */
    fun colorIn(colors: ExtendedColors): Color = when (this) {
        OFF -> colors.off
        FIRST -> colors.shift1
        SECOND -> colors.shift2
        THIRD -> colors.shift3
        SICK -> colors.sick
        VACATION -> colors.vacation
    }

    val color: Color
        @Composable
        @ReadOnlyComposable
        get() = colorIn(MaterialTheme.extendedColors)

    /** Tekst na punoj boji smene: tamni na svetlim smenama, svetli na ostalim. */
    val textColor: Color
        @Composable
        @ReadOnlyComposable
        get() = when (this) {
            FIRST, SECOND, VACATION -> MaterialTheme.extendedColors.onShiftBright
            else -> MaterialTheme.extendedColors.onShiftDeep
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
    /** Plaćen prekovremeni rad (+41%), bez obzira na manjak u mesecu. */
    PAID(0),

    /** Sati idu u saldo slobodnih sati i ne plaćaju se u tom mesecu. */
    COMP_TIME(1);

    companion object {
        // Vrednost 2 („Odrada", uklonjena 2026-09-22) i nepoznate vrednosti čitaju se kao „Plaćeno".
        fun fromValue(value: Int): OvertimeType {
            return entries.firstOrNull { it.value == value } ?: PAID
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
