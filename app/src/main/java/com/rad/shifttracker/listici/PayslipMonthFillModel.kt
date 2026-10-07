package com.rad.shifttracker.listici

import java.math.BigDecimal
import java.time.DayOfWeek
import java.time.LocalDate
import com.rad.shifttracker.smene.MonthKey
import com.rad.shifttracker.smene.Shift

/**
 * Popunjavanje meseca po platnom listiću.
 *
 * Listić daje samo mesečne zbirove, pa se raspored gradi ovako:
 *  1. smene radnih dana su iz šablona koji je korisnik već upisao (isti kao za neupisane mesece),
 *  2. GO, plaćeno odsustvo i bolovanje su uzastopni blokovi radnih dana,
 *  3. ostatak (izostanci, kraći i produženi dani, rad vikendom i praznikom) bira se tako da
 *     režija, noćni, nedelja, prekovremeni i praznici tačno odgovaraju listiću, sa što manje
 *     neobičnih dana (vidi [solveMonthFill]).
 * Rezultat se pre upisa proverava punim obračunom aplikacije na kopiji korisnikovih podataka.
 */
internal enum class FillDayKind { WORK, VACATION, PAID_LEAVE, SICK, ABSENT, HOLIDAY_OFF, HOLIDAY_WORK, WEEKEND_WORK }

internal data class FillDay(
    val date: LocalDate,
    val kind: FillDayKind,
    val shift: Shift?,
    val hours: Int = 0,
    /** Smena po rasporedu; razlikuje se od [shift] kad je dan rađen u drugoj smeni. */
    val templateShift: Shift? = shift,
    val sickRate: Double? = null
)

/** Uzastopni dani GO, plaćenog odsustva ili bolovanja (jedna stavka listića). */
internal data class FillLeaveBlock(val kind: FillDayKind, val days: Int, val sickRate: Double? = null)

/** Sati sa listića koje raspored mora tačno da da (sve u celim satima). */
internal data class FillTargets(
    val regularHours: Int,
    val holidayWorkHours: Int,
    val holidayPremiumHours: Int,
    val paidHolidayHours: Int,
    val nightHours: Int,
    val sundayHours: Int,
    val overtimeHours: Int,
    val blocks: List<FillLeaveBlock>,
    /** Šifra 96 „Izostanci 1“: ostatak minuta izostanka, upisuje se kao zaokruživanje meseca. */
    val absenceRoundingGross: BigDecimal = BigDecimal.ZERO
)

/**
 * Dan meseca sa smenom po šablonu (vikend: smena te nedelje). [holidayOverride] je praznik koji je
 * korisnik sam uključio ili isključio za taj datum; [isHoliday] već uračunava to.
 */
internal data class FillSlot(
    val date: LocalDate,
    val template: Shift,
    val isHoliday: Boolean,
    val holidayOverride: Boolean? = null
) {
    val isWeekday: Boolean get() = date.dayOfWeek.value <= DayOfWeek.FRIDAY.value
}

internal data class FillCalendar(val month: MonthKey, val slots: List<FillSlot>)

internal const val ABSENCE_ROUNDING_EXTRA_LABEL = "Izostanci 1"
