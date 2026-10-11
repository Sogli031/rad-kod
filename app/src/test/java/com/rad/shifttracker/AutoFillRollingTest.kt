package com.rad.shifttracker

import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import com.rad.shifttracker.repo.autoFillHorizonEnd
import com.rad.shifttracker.repo.extendAutoFill
import com.rad.shifttracker.repo.inferAutoFillSettings
import com.rad.shifttracker.repo.populateMonthsAhead
import com.rad.shifttracker.smene.DayRecordSource
import com.rad.shifttracker.smene.Shift
import com.rad.shifttracker.smene.ShiftFillMode
import com.rad.shifttracker.smene.ShiftFillSettings
import com.rad.shifttracker.smene.ShiftScheduler

/** Kontinualna auto-popuna: drži se oko godinu dana unapred i presnimava se pri promeni brigade. */
class AutoFillRollingTest {
    private val scheduler = ShiftScheduler()
    private val today = LocalDate.of(2026, 10, 7) // sreda
    private val threeBrigade = ShiftFillSettings(
        mode = ShiftFillMode.THREE_BRIGADE,
        anchorDate = LocalDate.of(2026, 10, 5),
        selectedShift = Shift.FIRST
    )

    @Test
    fun `popuna ide do kraja meseca godinu dana unapred`() {
        val repo = testRepository()
        scheduler.populateMonthsAhead(repo, today, threeBrigade, 12)

        assertEquals(LocalDate.of(2027, 10, 31), autoFillHorizonEnd(today))
        val lastFriday = LocalDate.of(2027, 10, 29)
        assertEquals(scheduler.calculateShiftForDate(lastFriday, threeBrigade), repo.get(2027, 10, 29)?.shift)
        assertNull(repo.get(2027, 11, 1))
    }

    @Test
    fun `kako vreme ide raspored se sam dopisuje po istom obrascu`() {
        val repo = testRepository()
        scheduler.populateMonthsAhead(repo, today, threeBrigade, 12)

        // Mesec dana kasnije: dopisuje se novembar 2027, i to po istoj rotaciji.
        val later = LocalDate.of(2026, 11, 2)
        val added = scheduler.extendAutoFill(repo, later) { threeBrigade }

        assertEquals(true, added > 0)
        val expected = scheduler.calculateShiftForDate(LocalDate.of(2027, 11, 30), threeBrigade)
        assertEquals(expected, repo.get(2027, 11, 30)?.shift)
        assertNull(repo.get(2027, 12, 1))
        // Već popunjeno se ne dira ponovo.
        assertEquals(0, scheduler.extendAutoFill(repo, later) { threeBrigade })
    }

    @Test
    fun `bez ikakve ranije popune nista se ne dopisuje`() {
        val repo = testRepository()
        repo.set(2026, 10, 7, Shift.SECOND, 8)

        assertEquals(0, scheduler.extendAutoFill(repo, today) { threeBrigade })
    }

    @Test
    fun `nova popuna presnimava raspored unapred ali cuva proslost i odsustva`() {
        val repo = testRepository()
        scheduler.populateMonthsAhead(repo, today, threeBrigade, 12)
        val pastShift = repo.get(2026, 10, 5)?.shift
        repo.set(2026, 10, 20, Shift.SECOND, 8) // ručno menjana smena unapred
        repo.set(2026, 12, 2, Shift.VACATION, 8) // planirani godišnji

        val fourBrigade = ShiftFillSettings(
            mode = ShiftFillMode.FOUR_BRIGADE,
            anchorDate = today,
            selectedShift = Shift.THIRD,
            cycleDayIndex = scheduler.getFourBrigadeCycleStartIndex(Shift.THIRD, 0)
        )
        scheduler.populateMonthsAhead(repo, today, fourBrigade, 12)

        assertEquals(pastShift, repo.get(2026, 10, 5)?.shift)
        assertEquals(Shift.THIRD, repo.get(2026, 10, 7)?.shift)
        assertEquals(DayRecordSource.AUTO_FILL, repo.get(2026, 10, 20)?.source)
        assertEquals(scheduler.calculateShiftForDate(LocalDate.of(2026, 10, 20), fourBrigade), repo.get(2026, 10, 20)?.shift)
        assertEquals(Shift.VACATION, repo.get(2026, 12, 2)?.shift)
    }

    @Test
    fun `obrazac se prepoznaje iz ranije popune kad nije sacuvan`() {
        val repo = testRepository()
        scheduler.populateMonthsAhead(repo, today, threeBrigade, 2)

        val inferred = repo.inferAutoFillSettings()
        val day = LocalDate.of(2026, 12, 16)
        assertEquals(
            scheduler.calculateShiftForDate(day, threeBrigade),
            inferred?.let { scheduler.calculateShiftForDate(day, it) }
        )
    }
}
