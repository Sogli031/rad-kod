package com.rad.shifttracker

import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Test
import com.rad.shifttracker.smene.Shift
import com.rad.shifttracker.smene.ShiftFillMode
import com.rad.shifttracker.ui.dialozi.autoFillWeekQuestion
import com.rad.shifttracker.ui.dialozi.autoFillSettings

/** Pravila unosa izdvojena iz dijaloga „Auto-popuna“ i „Bolovanje / Godišnji“. */
class DialogInputRulesTest {
    @Test
    fun `pitanje o smeni radnim danom za ovu a vikendom za sledecu sedmicu`() {
        assertEquals("Koju smenu radiš ove sedmice?", autoFillWeekQuestion(isWeekend = false))
        assertEquals("Koju smenu radiš sledeće sedmice?", autoFillWeekQuestion(isWeekend = true))
    }

    @Test
    fun `sidro popune zavisi od rezima`() {
        val today = LocalDate.of(2026, 10, 7)
        val monday = LocalDate.of(2026, 10, 5)

        val one = autoFillSettings(ShiftFillMode.ONE_SHIFT, today, monday, Shift.THIRD) { error("ne treba") }
        assertEquals(today, one.anchorDate)
        assertEquals(Shift.FIRST, one.selectedShift)

        val three = autoFillSettings(ShiftFillMode.THREE_BRIGADE, today, monday, Shift.SECOND) { error("ne treba") }
        assertEquals(monday, three.anchorDate)
        assertEquals(Shift.SECOND, three.selectedShift)

        val four = autoFillSettings(ShiftFillMode.FOUR_BRIGADE, today, monday, Shift.THIRD) { 5 }
        assertEquals(today, four.anchorDate)
        assertEquals(5, four.cycleDayIndex)
    }
}
