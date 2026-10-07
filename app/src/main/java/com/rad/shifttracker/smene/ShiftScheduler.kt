package com.rad.shifttracker.smene

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import java.time.temporal.TemporalAdjusters
import com.rad.shifttracker.osnova.CONFIG

data class ReferenceWorkWeek(
    val start: LocalDate,
    val end: LocalDate
)

class ShiftScheduler(
    private val cycle: List<Int> = CONFIG.weeklyShiftCycle,
    internal val defaultHours: Int = CONFIG.pay.defaultHours
) {
    private companion object {
        val FIRST_SECOND_CYCLE = listOf(Shift.FIRST, Shift.SECOND)
        val THREE_BRIGADE_CYCLE = listOf(Shift.THIRD, Shift.SECOND, Shift.FIRST)
        val FOUR_BRIGADE_CYCLE = listOf(
            Shift.FIRST,
            Shift.FIRST,
            Shift.SECOND,
            Shift.SECOND,
            Shift.THIRD,
            Shift.THIRD,
            Shift.OFF,
            Shift.OFF
        )
    }

    fun getAutoFillReferenceDate(today: LocalDate): LocalDate {
        return if (today.dayOfWeek.value <= DayOfWeek.FRIDAY.value) {
            today
        } else {
            today.with(TemporalAdjusters.next(DayOfWeek.MONDAY))
        }
    }

    fun getReferenceWorkWeek(activeDate: LocalDate): ReferenceWorkWeek {
        val start = activeDate.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
        return ReferenceWorkWeek(start = start, end = start.plusDays(4))
    }

    fun getShiftForDate(
        targetDate: LocalDate,
        referenceDate: LocalDate,
        referenceShift: Shift
    ): Shift? {
        if (targetDate.dayOfWeek.value > DayOfWeek.FRIDAY.value) return null

        val refIndex = cycle.indexOf(referenceShift.value)
        if (refIndex < 0) return null

        val refMonday = referenceDate.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
        val targetMonday = targetDate.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
        val weeksDiff = java.time.temporal.ChronoUnit.WEEKS.between(refMonday, targetMonday).toInt()
        val cycleIndex = Math.floorMod(refIndex + weeksDiff, cycle.size)
        return Shift.fromValue(cycle[cycleIndex])
    }

    fun calculateOneShiftForDate(date: LocalDate): Shift {
        return if (date.dayOfWeek.value <= DayOfWeek.FRIDAY.value) Shift.FIRST else Shift.OFF
    }

    fun calculateWeeklyShiftForDate(
        date: LocalDate,
        anchorDate: LocalDate,
        selectedShift: Shift,
        mode: ShiftFillMode
    ): Shift {
        if (date.dayOfWeek.value > DayOfWeek.FRIDAY.value) return Shift.OFF

        val weeklyCycle = when (mode) {
            ShiftFillMode.FIRST_SECOND -> FIRST_SECOND_CYCLE
            // Trobrigada se u ovoj aplikaciji računa kao sedmična rotacija smena: treća → druga → prva. Nije dnevna rotacija.
            ShiftFillMode.THREE_BRIGADE -> THREE_BRIGADE_CYCLE
            else -> return Shift.OFF
        }
        val anchorCycleIndex = weeklyCycle.indexOf(selectedShift)
        if (anchorCycleIndex < 0) return Shift.OFF

        val anchorMonday = anchorDate.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
        val targetMonday = date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
        val weekDiff = ChronoUnit.WEEKS.between(anchorMonday, targetMonday).toInt()
        val shiftIndex = Math.floorMod(anchorCycleIndex + weekDiff, weeklyCycle.size)
        return weeklyCycle[shiftIndex]
    }

    fun getFourBrigadeCycleShift(cycleIndex: Int): Shift {
        return FOUR_BRIGADE_CYCLE[Math.floorMod(cycleIndex, FOUR_BRIGADE_CYCLE.size)]
    }

    fun getFourBrigadeCycleStartIndex(selectedShift: Shift, cycleDayIndex: Int): Int {
        val blockStartIndex = when (selectedShift) {
            Shift.FIRST -> 0
            Shift.SECOND -> 2
            Shift.THIRD -> 4
            Shift.OFF -> 6
            else -> 0
        }
        return blockStartIndex + cycleDayIndex.coerceIn(0, 1)
    }

    fun calculateFourBrigadeShiftForDate(
        date: LocalDate,
        anchorDate: LocalDate,
        cycleStartIndex: Int
    ): Shift {
        val daysDiff = ChronoUnit.DAYS.between(anchorDate, date).toInt()
        val cycleIndex = Math.floorMod(cycleStartIndex + daysDiff, FOUR_BRIGADE_CYCLE.size)
        val cycleShift = getFourBrigadeCycleShift(cycleIndex)
        return applyFourBrigadeNonWorkingRules(date, cycleShift)
    }

    private fun applyFourBrigadeNonWorkingRules(date: LocalDate, cycleShift: Shift): Shift {
        return when {
            date.dayOfWeek == DayOfWeek.SUNDAY -> Shift.OFF
            date.dayOfWeek == DayOfWeek.MONDAY && cycleShift == Shift.FIRST -> Shift.OFF
            else -> cycleShift
        }
    }

    fun calculateShiftForDate(date: LocalDate, settings: ShiftFillSettings): Shift {
        return when (settings.mode) {
            ShiftFillMode.ONE_SHIFT -> calculateOneShiftForDate(date)
            ShiftFillMode.FIRST_SECOND,
            ShiftFillMode.THREE_BRIGADE -> calculateWeeklyShiftForDate(
                date = date,
                anchorDate = settings.anchorDate,
                selectedShift = settings.selectedShift,
                mode = settings.mode
            )
            ShiftFillMode.FOUR_BRIGADE -> calculateFourBrigadeShiftForDate(
                date = date,
                anchorDate = settings.anchorDate,
                cycleStartIndex = settings.cycleDayIndex
                    ?: getFourBrigadeCycleStartIndex(settings.selectedShift, 0)
            )
        }
    }

    fun calculateShiftForWorkedDate(date: LocalDate, settings: ShiftFillSettings): Shift? {
        val scheduledShift = calculateShiftForDate(date, settings)
        if (scheduledShift in Shift.workShifts()) return scheduledShift
        if (date.dayOfWeek.value <= DayOfWeek.FRIDAY.value) return null

        return when (settings.mode) {
            ShiftFillMode.ONE_SHIFT,
            ShiftFillMode.FIRST_SECOND,
            ShiftFillMode.THREE_BRIGADE -> {
                val weekAnchor = date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
                calculateShiftForDate(weekAnchor, settings).takeIf { it in Shift.workShifts() }
            }
            ShiftFillMode.FOUR_BRIGADE -> null
        }
    }

    internal fun isValidFillSettings(settings: ShiftFillSettings): Boolean {
        return when (settings.mode) {
            ShiftFillMode.ONE_SHIFT -> true
            ShiftFillMode.FIRST_SECOND -> settings.selectedShift in FIRST_SECOND_CYCLE
            ShiftFillMode.THREE_BRIGADE -> settings.selectedShift in THREE_BRIGADE_CYCLE
            ShiftFillMode.FOUR_BRIGADE -> {
                val validShift = settings.selectedShift in Shift.workShifts() || settings.selectedShift == Shift.OFF
                val validCycleIndex = settings.cycleDayIndex?.let { it in FOUR_BRIGADE_CYCLE.indices } ?: true
                validShift && validCycleIndex
            }
        }
    }
}
