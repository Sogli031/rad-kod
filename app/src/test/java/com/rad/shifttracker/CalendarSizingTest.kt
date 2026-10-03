package com.rad.shifttracker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CalendarSizingTest {
    @Test
    fun `tall screen keeps default cell height`() {
        assertEquals(UI.dayButtonHeight.toFloat(), calendarDayHeight(600f, 5), 0.001f)
    }

    @Test
    fun `short screen shrinks cells so every week fits`() {
        val available = 290f
        val rows = 6
        val height = calendarDayHeight(available, rows)
        assertTrue(height < UI.dayButtonHeight)
        assertTrue(rows * height + (rows - 1) * Spacing.xs.value + 12f <= available + 0.001f)
    }

    @Test
    fun `cells never go below minimum height`() {
        assertEquals(CALENDAR_MIN_DAY_HEIGHT, calendarDayHeight(100f, 6), 0.001f)
    }

    @Test
    fun `cell font follows system font up to cap and shrinks in short cells`() {
        assertEquals(1.0f, calendarCellFontScale(1.0f, 58f), 0.001f)
        assertEquals(CALENDAR_MAX_FONT_SCALE, calendarCellFontScale(1.5f, 58f), 0.001f)
        assertTrue(calendarCellFontScale(1.0f, 38f) < 1.0f)
    }
}
