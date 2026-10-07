package com.rad.shifttracker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import com.rad.shifttracker.ui.CALENDAR_CELL_MAX_FONT_SCALE
import com.rad.shifttracker.ui.CALENDAR_MIN_CELL_HEIGHT
import com.rad.shifttracker.ui.calendarCellFontScale
import com.rad.shifttracker.ui.calendarDayHeight
import com.rad.shifttracker.osnova.UI

class CalendarSizingTest {
    @Test
    fun `default font keeps default cell height`() {
        assertEquals(UI.dayButtonHeight.toFloat(), calendarDayHeight(1.0f), 0.001f)
    }

    @Test
    fun `cells are never lower than the touch target minimum`() {
        assertTrue(calendarDayHeight(0.5f) >= CALENDAR_MIN_CELL_HEIGHT)
    }

    @Test
    fun `cells grow with the font so text is never clipped`() {
        assertTrue(calendarDayHeight(CALENDAR_CELL_MAX_FONT_SCALE) > calendarDayHeight(1.0f))
    }

    @Test
    fun `cell font follows the system font up to the width cap`() {
        assertEquals(0.85f, calendarCellFontScale(0.85f), 0.001f)
        assertEquals(1.15f, calendarCellFontScale(1.15f), 0.001f)
        assertEquals(CALENDAR_CELL_MAX_FONT_SCALE, calendarCellFontScale(2.0f), 0.001f)
    }
}
