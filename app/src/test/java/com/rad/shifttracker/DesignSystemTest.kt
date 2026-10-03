package com.rad.shifttracker

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.math.pow

/** Čuva M3 dizajn: kontrast boja (WCAG), opis dana za čitač ekrana i pokrivenost teme. */
class DesignSystemTest {

    private fun channel(value: Float): Double {
        val c = value.toDouble()
        return if (c <= 0.03928) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)
    }

    private fun luminance(color: Color): Double =
        0.2126 * channel(color.red) + 0.7152 * channel(color.green) + 0.0722 * channel(color.blue)

    private fun contrast(a: Color, b: Color): Double {
        val la = luminance(a)
        val lb = luminance(b)
        return (maxOf(la, lb) + 0.05) / (minOf(la, lb) + 0.05)
    }

    private val surfaces = listOf(THEME.bgDark, THEME.bgCard, THEME.bgCardElevated)

    @Test
    fun `secondary text colors reach 4_5 to 1 on every surface`() {
        for (surface in surfaces) {
            assertTrue("textFaint on $surface", contrast(THEME.textFaint, surface) >= 4.5)
            assertTrue("textMuted on $surface", contrast(THEME.textMuted, surface) >= 4.5)
            assertTrue("textSecondary on $surface", contrast(THEME.textSecondary, surface) >= 4.5)
        }
    }

    @Test
    fun `outline for field and switch borders reaches 3 to 1 on every surface`() {
        for (surface in surfaces) {
            assertTrue("outline on $surface", contrast(THEME.outline, surface) >= 3.0)
        }
    }

    @Test
    fun `content on primary and error is readable`() {
        assertTrue(contrast(THEME.textDark, THEME.accentPrimary) >= 4.5)
        assertTrue(contrast(THEME.textDark, THEME.danger) >= 4.5)
    }

    @Test
    fun `theme defines every color role so no default purple leaks in`() {
        val source = File(themeSourcePath()).readText()
        listOf(
            "primaryContainer", "onPrimaryContainer", "secondaryContainer", "tertiary", "errorContainer",
            "outline", "outlineVariant", "surfaceContainerLowest", "surfaceContainerLow", "surfaceContainer",
            "surfaceContainerHigh", "surfaceContainerHighest", "inverseSurface", "scrim"
        ).forEach { role ->
            assertTrue("Theme.kt must set $role", source.contains("$role ="))
        }
        assertTrue(source.contains("shapes = RadShapes"))
    }

    @Test
    fun `day description names date shift hours and color only markers`() {
        val worked = DayRecord(2026, 10, 13, Shift.FIRST, 8)
        val description = calendarDayDescription(
            day = 13, month = 10, record = worked, isToday = true,
            isHolidayDay = false, workedHoliday = false, brigadeType = BrigadeType.TROBRIGADA
        )
        assertTrue(description, description.startsWith("13. oktobar"))
        assertTrue(description, description.contains("danas"))
        assertTrue(description, description.contains(Shift.FIRST.fullName))
        assertTrue(description, description.contains("8"))
    }

    @Test
    fun `day description covers empty leave and holiday days`() {
        val empty = calendarDayDescription(5, 3, null, false, false, false, BrigadeType.TROBRIGADA)
        assertEquals("5. mart, nema unosa", empty)

        val vacation = DayRecord(2026, 7, 2, Shift.VACATION, 8)
        val leave = calendarDayDescription(2, 7, vacation, false, false, false, BrigadeType.TROBRIGADA)
        assertEquals("2. jul, ${Shift.VACATION.fullName}", leave)

        val holiday = calendarDayDescription(1, 1, null, false, true, false, BrigadeType.TROBRIGADA)
        assertTrue(holiday, holiday.contains("praznik"))
    }

    private fun themeSourcePath(): String {
        return listOf(
            "src/main/java/com/rad/shifttracker/Theme.kt",
            "app/src/main/java/com/rad/shifttracker/Theme.kt"
        ).first { File(it).isFile }
    }
}
