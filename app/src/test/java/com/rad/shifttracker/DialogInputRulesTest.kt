package com.rad.shifttracker

import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import com.rad.shifttracker.smene.Shift
import com.rad.shifttracker.smene.ShiftFillMode
import com.rad.shifttracker.ui.dialozi.absenceDayRangeError
import com.rad.shifttracker.ui.dialozi.absenceDayRangeOrNull
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

    @Test
    fun `opseg odsustva je ispravan samo unutar meseca i redom`() {
        assertEquals(3..7, absenceDayRangeOrNull("3", "7", 30))
        assertEquals(30..30, absenceDayRangeOrNull("30", "30", 30))
        assertNull(absenceDayRangeOrNull("", "7", 30))
        assertNull(absenceDayRangeOrNull("8", "7", 30))
        assertNull(absenceDayRangeOrNull("1", "31", 30))
        assertNull(absenceDayRangeOrNull("0", "3", 30))
    }

    @Test
    fun `greska opsega odsustva prati redosled provera`() {
        assertEquals("Unesite početni dan.", absenceDayRangeError("", "", 30))
        assertEquals("Unesite krajnji dan.", absenceDayRangeError("3", "", 30))
        assertEquals("Početni dan mora biti između 1 i 30.", absenceDayRangeError("31", "2", 30))
        assertEquals("Krajnji dan mora biti između 1 i 30.", absenceDayRangeError("2", "31", 30))
        assertEquals("Početni dan mora biti manji ili jednak krajnjem.", absenceDayRangeError("9", "2", 30))
        assertNull(absenceDayRangeError("2", "9", 30))
    }
}
