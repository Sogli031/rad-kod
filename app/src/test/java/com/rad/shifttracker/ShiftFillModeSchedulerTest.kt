package com.rad.shifttracker

import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import java.time.LocalDate
import com.rad.shifttracker.smene.ReferenceWorkWeek
import com.rad.shifttracker.smene.Shift
import com.rad.shifttracker.smene.ShiftFillMode
import com.rad.shifttracker.smene.ShiftScheduler

class ShiftFillModeSchedulerTest {

    private lateinit var scheduler: ShiftScheduler

    @Before
    fun setUp() {
        scheduler = ShiftScheduler(defaultHours = 8)
    }

    @Test
    fun `calculateOneShiftForDate ponedeljak petak prva a vikend off`() {
        assertEquals(Shift.FIRST, scheduler.calculateOneShiftForDate(LocalDate.of(2026, 5, 4)))
        assertEquals(Shift.FIRST, scheduler.calculateOneShiftForDate(LocalDate.of(2026, 5, 8)))
        assertEquals(Shift.OFF, scheduler.calculateOneShiftForDate(LocalDate.of(2026, 5, 9)))
        assertEquals(Shift.OFF, scheduler.calculateOneShiftForDate(LocalDate.of(2026, 5, 10)))
    }

    @Test
    fun `getReferenceWorkWeek vraca radnu sedmicu za sredinu i vikend`() {
        assertEquals(
            ReferenceWorkWeek(LocalDate.of(2026, 5, 11), LocalDate.of(2026, 5, 15)),
            scheduler.getReferenceWorkWeek(LocalDate.of(2026, 5, 13))
        )
        assertEquals(
            ReferenceWorkWeek(LocalDate.of(2026, 5, 11), LocalDate.of(2026, 5, 15)),
            scheduler.getReferenceWorkWeek(LocalDate.of(2026, 5, 16))
        )
        assertEquals(
            ReferenceWorkWeek(LocalDate.of(2026, 5, 11), LocalDate.of(2026, 5, 15)),
            scheduler.getReferenceWorkWeek(LocalDate.of(2026, 5, 17))
        )
        assertEquals(
            ReferenceWorkWeek(LocalDate.of(2026, 5, 18), LocalDate.of(2026, 5, 22)),
            scheduler.getReferenceWorkWeek(LocalDate.of(2026, 5, 18))
        )
    }

    @Test
    fun `calculateWeeklyShiftForDate prva druga selected first rotira sedmicno napred i nazad`() {
        val anchorDate = LocalDate.of(2026, 5, 4)

        for (dayOffset in 0..4) {
            assertEquals(
                Shift.FIRST,
                scheduler.calculateWeeklyShiftForDate(
                    anchorDate.plusDays(dayOffset.toLong()),
                    anchorDate,
                    Shift.FIRST,
                    ShiftFillMode.FIRST_SECOND
                )
            )
        }
        assertEquals(
            Shift.OFF,
            scheduler.calculateWeeklyShiftForDate(
                LocalDate.of(2026, 5, 9),
                anchorDate,
                Shift.FIRST,
                ShiftFillMode.FIRST_SECOND
            )
        )
        assertEquals(
            Shift.OFF,
            scheduler.calculateWeeklyShiftForDate(
                LocalDate.of(2026, 5, 10),
                anchorDate,
                Shift.FIRST,
                ShiftFillMode.FIRST_SECOND
            )
        )
        assertEquals(
            Shift.SECOND,
            scheduler.calculateWeeklyShiftForDate(
                anchorDate.plusWeeks(1),
                anchorDate,
                Shift.FIRST,
                ShiftFillMode.FIRST_SECOND
            )
        )
        assertEquals(
            Shift.FIRST,
            scheduler.calculateWeeklyShiftForDate(
                anchorDate.plusWeeks(2),
                anchorDate,
                Shift.FIRST,
                ShiftFillMode.FIRST_SECOND
            )
        )
        assertEquals(
            Shift.SECOND,
            scheduler.calculateWeeklyShiftForDate(
                anchorDate.minusWeeks(1),
                anchorDate,
                Shift.FIRST,
                ShiftFillMode.FIRST_SECOND
            )
        )
    }

    @Test
    fun `calculateWeeklyShiftForDate prva druga selected second rotira sedmicno napred i nazad`() {
        val anchorDate = LocalDate.of(2026, 5, 4)

        assertEquals(
            Shift.SECOND,
            scheduler.calculateWeeklyShiftForDate(anchorDate, anchorDate, Shift.SECOND, ShiftFillMode.FIRST_SECOND)
        )
        assertEquals(
            Shift.FIRST,
            scheduler.calculateWeeklyShiftForDate(
                anchorDate.plusWeeks(1),
                anchorDate,
                Shift.SECOND,
                ShiftFillMode.FIRST_SECOND
            )
        )
        assertEquals(
            Shift.FIRST,
            scheduler.calculateWeeklyShiftForDate(
                anchorDate.minusWeeks(1),
                anchorDate,
                Shift.SECOND,
                ShiftFillMode.FIRST_SECOND
            )
        )
    }

    @Test
    fun `calculateWeeklyShiftForDate trobrigada selected third rotira sedmicno napred i nazad`() {
        val anchorDate = LocalDate.of(2026, 5, 4)

        assertEquals(
            Shift.THIRD,
            scheduler.calculateWeeklyShiftForDate(anchorDate, anchorDate, Shift.THIRD, ShiftFillMode.THREE_BRIGADE)
        )
        assertEquals(
            Shift.SECOND,
            scheduler.calculateWeeklyShiftForDate(
                anchorDate.plusWeeks(1),
                anchorDate,
                Shift.THIRD,
                ShiftFillMode.THREE_BRIGADE
            )
        )
        assertEquals(
            Shift.FIRST,
            scheduler.calculateWeeklyShiftForDate(
                anchorDate.plusWeeks(2),
                anchorDate,
                Shift.THIRD,
                ShiftFillMode.THREE_BRIGADE
            )
        )
        assertEquals(
            Shift.THIRD,
            scheduler.calculateWeeklyShiftForDate(
                anchorDate.plusWeeks(3),
                anchorDate,
                Shift.THIRD,
                ShiftFillMode.THREE_BRIGADE
            )
        )
        assertEquals(
            Shift.FIRST,
            scheduler.calculateWeeklyShiftForDate(
                anchorDate.minusWeeks(1),
                anchorDate,
                Shift.THIRD,
                ShiftFillMode.THREE_BRIGADE
            )
        )
    }

    @Test
    fun `calculateWeeklyShiftForDate trobrigada selected second rotira sedmicno napred i nazad`() {
        val anchorDate = LocalDate.of(2026, 5, 4)

        assertEquals(
            Shift.SECOND,
            scheduler.calculateWeeklyShiftForDate(anchorDate, anchorDate, Shift.SECOND, ShiftFillMode.THREE_BRIGADE)
        )
        assertEquals(
            Shift.FIRST,
            scheduler.calculateWeeklyShiftForDate(
                anchorDate.plusWeeks(1),
                anchorDate,
                Shift.SECOND,
                ShiftFillMode.THREE_BRIGADE
            )
        )
        assertEquals(
            Shift.THIRD,
            scheduler.calculateWeeklyShiftForDate(
                anchorDate.minusWeeks(1),
                anchorDate,
                Shift.SECOND,
                ShiftFillMode.THREE_BRIGADE
            )
        )
    }

    @Test
    fun `calculateWeeklyShiftForDate trobrigada selected first rotira sedmicno napred i nazad`() {
        val anchorDate = LocalDate.of(2026, 5, 4)

        assertEquals(
            Shift.FIRST,
            scheduler.calculateWeeklyShiftForDate(anchorDate, anchorDate, Shift.FIRST, ShiftFillMode.THREE_BRIGADE)
        )
        assertEquals(
            Shift.THIRD,
            scheduler.calculateWeeklyShiftForDate(
                anchorDate.plusWeeks(1),
                anchorDate,
                Shift.FIRST,
                ShiftFillMode.THREE_BRIGADE
            )
        )
        assertEquals(
            Shift.SECOND,
            scheduler.calculateWeeklyShiftForDate(
                anchorDate.minusWeeks(1),
                anchorDate,
                Shift.FIRST,
                ShiftFillMode.THREE_BRIGADE
            )
        )
    }

    @Test
    fun `four brigade raw cycle follows eight day order`() {
        assertEquals(Shift.FIRST, scheduler.getFourBrigadeCycleShift(0))
        assertEquals(Shift.FIRST, scheduler.getFourBrigadeCycleShift(1))
        assertEquals(Shift.SECOND, scheduler.getFourBrigadeCycleShift(2))
        assertEquals(Shift.SECOND, scheduler.getFourBrigadeCycleShift(3))
        assertEquals(Shift.THIRD, scheduler.getFourBrigadeCycleShift(4))
        assertEquals(Shift.THIRD, scheduler.getFourBrigadeCycleShift(5))
        assertEquals(Shift.OFF, scheduler.getFourBrigadeCycleShift(6))
        assertEquals(Shift.OFF, scheduler.getFourBrigadeCycleShift(7))
    }

    @Test
    fun `four brigade choice mapping returns cycle indexes`() {
        assertEquals(0, scheduler.getFourBrigadeCycleStartIndex(Shift.FIRST, 0))
        assertEquals(1, scheduler.getFourBrigadeCycleStartIndex(Shift.FIRST, 1))
        assertEquals(2, scheduler.getFourBrigadeCycleStartIndex(Shift.SECOND, 0))
        assertEquals(3, scheduler.getFourBrigadeCycleStartIndex(Shift.SECOND, 1))
        assertEquals(4, scheduler.getFourBrigadeCycleStartIndex(Shift.THIRD, 0))
        assertEquals(5, scheduler.getFourBrigadeCycleStartIndex(Shift.THIRD, 1))
        assertEquals(6, scheduler.getFourBrigadeCycleStartIndex(Shift.OFF, 0))
        assertEquals(7, scheduler.getFourBrigadeCycleStartIndex(Shift.OFF, 1))
    }

    @Test
    fun `calculateFourBrigadeShiftForDate svaka nedelja je slobodna`() {
        val sunday = LocalDate.of(2026, 5, 10)

        assertEquals(Shift.OFF, scheduler.calculateFourBrigadeShiftForDate(sunday, sunday, 0))
        assertEquals(Shift.OFF, scheduler.calculateFourBrigadeShiftForDate(sunday, sunday, 2))
        assertEquals(Shift.OFF, scheduler.calculateFourBrigadeShiftForDate(sunday, sunday, 4))
    }

    @Test
    fun `calculateFourBrigadeShiftForDate ponedeljak prva je slobodna`() {
        val monday = LocalDate.of(2026, 5, 11)

        assertEquals(Shift.OFF, scheduler.calculateFourBrigadeShiftForDate(monday, monday, 0))
    }

    @Test
    fun `calculateFourBrigadeShiftForDate ponedeljak druga i treca ostaju radne`() {
        val monday = LocalDate.of(2026, 5, 11)

        assertEquals(Shift.SECOND, scheduler.calculateFourBrigadeShiftForDate(monday, monday, 2))
        assertEquals(Shift.THIRD, scheduler.calculateFourBrigadeShiftForDate(monday, monday, 4))
    }

    @Test
    fun `calculateFourBrigadeShiftForDate prati ciklus sa neradnom nedeljom i prvom ponedeljkom`() {
        val sunday = LocalDate.of(2026, 5, 10)
        val monday = LocalDate.of(2026, 5, 11)

        assertEquals(Shift.OFF, scheduler.calculateFourBrigadeShiftForDate(sunday, sunday, 0))
        assertEquals(Shift.OFF, scheduler.calculateFourBrigadeShiftForDate(monday, monday, 0))
        assertEquals(Shift.SECOND, scheduler.calculateFourBrigadeShiftForDate(sunday.plusDays(2), sunday, 0))
        assertEquals(Shift.THIRD, scheduler.calculateFourBrigadeShiftForDate(sunday.plusDays(4), sunday, 0))
        assertEquals(Shift.OFF, scheduler.calculateFourBrigadeShiftForDate(sunday.plusDays(6), sunday, 0))
    }
}
