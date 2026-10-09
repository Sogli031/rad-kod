package com.rad.shifttracker

import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import com.rad.shifttracker.smene.DayRecord
import com.rad.shifttracker.smene.OvertimeType
import com.rad.shifttracker.smene.Shift
import com.rad.shifttracker.ui.dialozi.DayEditFields
import com.rad.shifttracker.ui.dialozi.DayEditForm
import com.rad.shifttracker.ui.dialozi.dayEditForm
import com.rad.shifttracker.ui.dialozi.dayEditHolidayText
import com.rad.shifttracker.ui.dialozi.defaultOvertimeHours
import com.rad.shifttracker.osnova.CONFIG
import com.rad.shifttracker.osnova.MANUAL_HOLIDAY_LABEL

/** Prelazi u dijalogu za dan bez UI-ja: ista pravila koja dijalog primenjuje na klik. */
class DayEditFormTest {
    private val monday = LocalDate.of(2026, 5, 4)
    private val saturday = LocalDate.of(2026, 5, 9)
    private val newYear = LocalDate.of(2026, 1, 1)

    private fun form(date: LocalDate, record: DayRecord? = null): DayEditForm =
        dayEditForm(date, record, DayEditFields.from(record))

    private fun workRecord(date: LocalDate, shift: Shift, hours: Int = 8) =
        DayRecord(date.year, date.monthValue, date.dayOfMonth, shift, hours)

    @Test
    fun `prekidac praznik postoji samo na vec poznatim praznicima`() {
        // Običan dan nema prekidač; državni i ranije ručno dodat praznik ga imaju.
        assertFalse(form(monday).showHolidaySwitch)
        assertTrue(form(newYear).showHolidaySwitch)
        val manual = DayRecord(monday.year, monday.monthValue, monday.dayOfMonth, Shift.OFF, 0, holidayOverride = true)
        val manualForm = form(monday, manual)
        assertTrue(manualForm.showHolidaySwitch)
        manualForm.setHoliday(false)
        assertNull(manualForm.saveState.holidayOverride)
        assertTrue("prekidač ostaje dok je prozor otvoren", manualForm.showHolidaySwitch)

        // Nova godina: isključen državni praznik je ručno „nije praznik“, a vraćen briše oznaku.
        val newYearForm = form(newYear)
        assertTrue(newYearForm.isHolidayEffective)
        newYearForm.setHoliday(false)
        assertFalse(newYearForm.isHolidayEffective)
        assertEquals(false, newYearForm.saveState.holidayOverride)
        assertNull(dayEditHolidayText(newYear, newYearForm.shift1, newYearForm.holidayOverride))
        newYearForm.setHoliday(true)
        assertNull(newYearForm.saveState.holidayOverride)
    }

    @Test
    fun `rad na slobodan dan uzima smenu po rasporedu i pun dan`() {
        val form = form(monday)
        form.selectOff()

        form.selectWork { Shift.SECOND }

        assertEquals(Shift.SECOND, form.shift1)
        assertEquals(CONFIG.pay.defaultHours, form.workHours1)
        assertFalse(form.paidLeave)
    }

    @Test
    fun `rad bez rasporeda pocinje prvom smenom`() {
        val form = form(monday)

        form.selectWork { null }

        assertEquals(Shift.FIRST, form.shift1)
    }

    @Test
    fun `rad na vec radni dan ne pita raspored i ne menja smenu`() {
        val form = form(monday, workRecord(monday, Shift.THIRD))
        var scheduleAsked = false

        form.selectWork {
            scheduleAsked = true
            Shift.FIRST
        }

        assertEquals(Shift.THIRD, form.shift1)
        assertFalse(scheduleAsked)
    }

    @Test
    fun `slobodan dan brise sate dodatnu smenu i manjak`() {
        val form = form(monday, workRecord(monday, Shift.FIRST))
        form.setDoubleShift(true)
        form.regularShortageMinutes = 30

        form.selectOff()

        assertEquals(Shift.OFF, form.shift1)
        assertEquals(0, form.workHours1)
        assertFalse(form.hasDouble)
        assertEquals(0, form.regularShortageMinutes)
    }

    @Test
    fun `placeno odsustvo je bolovanje sa oznakom i punim danom`() {
        val form = form(monday)

        form.selectPaidLeave()

        assertEquals(Shift.SICK, form.shift1)
        assertTrue(form.paidLeave)
        assertEquals(CONFIG.pay.defaultHours, form.workHours1)
        assertTrue(form.saveState.paidLeave)
    }

    @Test
    fun `dodatna smena posle prve predlaze drugu i cetiri sata`() {
        val form = form(monday, workRecord(monday, Shift.FIRST))

        form.setDoubleShift(true)

        assertTrue(form.hasDouble)
        assertEquals(Shift.SECOND, form.shift2)
        assertEquals(defaultOvertimeHours(Shift.FIRST), form.workHours2)
    }

    @Test
    fun `dodatna smena vikendom preuzima nacin isplate vikend rada`() {
        val form = form(saturday, workRecord(saturday, Shift.FIRST))
        form.weekendOvertimeType = OvertimeType.COMP_TIME

        form.setDoubleShift(true)

        assertEquals(OvertimeType.COMP_TIME, form.overtimeType)
        assertTrue(form.hasPrimaryNonRegularAdditionalWork)
    }

    @Test
    fun `promena redovne smene ispravlja nedozvoljenu dodatnu smenu`() {
        val form = form(monday, workRecord(monday, Shift.FIRST))
        form.setDoubleShift(true)
        form.shift2 = Shift.THIRD

        form.selectPrimaryShift(Shift.SECOND)

        assertEquals(Shift.THIRD, form.shift2)
        form.selectPrimaryShift(Shift.THIRD)
        assertEquals(Shift.FIRST, form.shift2)
    }

    @Test
    fun `kraci dan dozvoljava manjak a vise od fonda ne`() {
        val form = form(monday, workRecord(monday, Shift.FIRST))
        form.setRegularHours(CONFIG.pay.defaultHours - 2)
        form.regularShortageMinutes = 15

        assertTrue(form.shortageMinutesAllowed)
        assertEquals(15, form.effectiveRegularShortageMinutes)

        form.setRegularHours(CONFIG.pay.defaultHours + 2)

        assertFalse(form.shortageMinutesAllowed)
        assertEquals(0, form.regularShortageMinutes)
        assertTrue(form.showPrimaryExtraHoursMode)
    }

    @Test
    fun `oslobodjenje od salda ostaje samo za slobodan dan`() {
        val exemptOff = DayRecord(2026, 5, 4, Shift.OFF, 0, balanceExempt = true)
        val form = form(monday, exemptOff)

        assertTrue(form.saveState.balanceExempt)
        form.selectWork { Shift.FIRST }
        assertFalse(form.saveState.balanceExempt)
    }

    @Test
    fun `natpis praznika zavisi od rada i dana u nedelji`() {
        assertEquals("Praznik: Nova godina (neradni, 100%)", dayEditHolidayText(newYear, Shift.OFF, null))
        assertTrue(dayEditHolidayText(newYear, Shift.FIRST, null)!!.startsWith("Praznik: Nova godina (rad +"))
        assertNull(dayEditHolidayText(newYear, Shift.OFF, holidayOverride = false))
        assertNull(dayEditHolidayText(monday, Shift.FIRST, null))
        assertEquals("Praznik: $MANUAL_HOLIDAY_LABEL (neradni, 100%)", dayEditHolidayText(monday, Shift.OFF, true))
    }
}
