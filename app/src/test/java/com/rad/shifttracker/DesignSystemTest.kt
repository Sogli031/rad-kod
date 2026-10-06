package com.rad.shifttracker

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.math.pow
import com.rad.shifttracker.smene.Shift
import com.rad.shifttracker.ui.DarkExtendedColors
import com.rad.shifttracker.ui.RadDarkColorScheme
import com.rad.shifttracker.smene.BrigadeType
import com.rad.shifttracker.smene.DayRecord
import com.rad.shifttracker.ui.calendarDayDescription

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

    private val scheme = RadDarkColorScheme
    private val ext = DarkExtendedColors
    private val surfaces = listOf(scheme.background, scheme.surfaceContainer, scheme.surfaceContainerHigh)

    @Test
    fun `secondary text colors reach 4_5 to 1 on every surface`() {
        for (surface in surfaces) {
            assertTrue("textFaint on $surface", contrast(ext.textFaint, surface) >= 4.5)
            assertTrue("textMuted on $surface", contrast(scheme.onSurfaceVariant, surface) >= 4.5)
            assertTrue("textSecondary on $surface", contrast(ext.textSecondary, surface) >= 4.5)
        }
    }

    @Test
    fun `outline for field and switch borders reaches 3 to 1 on every surface`() {
        for (surface in surfaces) {
            assertTrue("outline on $surface", contrast(scheme.outline, surface) >= 3.0)
        }
    }

    @Test
    fun `content on primary and error is readable`() {
        assertTrue(contrast(scheme.onPrimary, scheme.primary) >= 4.5)
        assertTrue(contrast(scheme.onError, scheme.error) >= 4.5)
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

    @Test
    fun `ui reads colors only from the theme`() {
        val mainDir = File(themeSourcePath()).parentFile
        val offenders = mainDir.walkTopDown()
            .filter { it.isFile && it.extension == "kt" && it.name != "Theme.kt" }
            .flatMap { file ->
                file.readLines().mapIndexedNotNull { index, line ->
                    val raw = Regex("""\bTHEME\.|Color\(0x|Color\.(White|Black|Red|Green|Blue|Gray|Yellow)\b""")
                    if (raw.containsMatchIn(line)) "${file.name}:${index + 1}: ${line.trim()}" else null
                }
            }
            .toList()
        assertTrue("Boje idu kroz MaterialTheme.colorScheme/extendedColors:\n" + offenders.joinToString("\n"), offenders.isEmpty())
    }

    @Test
    fun `spacing goes through Spacing tokens`() {
        val mainDir = File(themeSourcePath()).parentFile
        val spacingContext = Regex("""padding\(|spacedBy\(|PaddingValues\(|\bspacing = |Spacer\(""")
        val sizeContext = Regex("""size\(|strokeWidth|border\(|shape|offset\(|\b(height|width|heightIn|widthIn)\(""")
        val literal = Regex("""\b([2-9]|[1-9]\d+)\.dp\b""")
        val offenders = mainDir.walkTopDown()
            .filter { it.isFile && it.extension == "kt" && it.name != "Spacing.kt" }
            .flatMap { file ->
                file.readLines().mapIndexedNotNull { index, line ->
                    val spacingOnly = spacingContext.containsMatchIn(line) && !sizeContext.containsMatchIn(line)
                    if (spacingOnly && literal.containsMatchIn(line)) "${file.name}:${index + 1}: ${line.trim()}" else null
                }
            }
            .toList()
        assertTrue("Razmaci idu kroz Spacing.*:\n" + offenders.joinToString("\n"), offenders.isEmpty())
    }

    @Test
    fun `font weight comes from type scale styles not manual overrides`() {
        val mainDir = File(themeSourcePath()).parentFile
        val offenders = mainDir.walkTopDown()
            .filter { it.isFile && it.extension == "kt" && it.name != "Theme.kt" }
            .flatMap { file ->
                file.readLines().mapIndexedNotNull { index, line ->
                    if (line.contains("FontWeight.")) "${file.name}:${index + 1}: ${line.trim()}" else null
                }
            }
            .toList()
        assertTrue("Težina ide kroz *Emphasized stil ili emphasized():\n" + offenders.joinToString("\n"), offenders.isEmpty())
    }

    private fun themeSourcePath(): String = mainSourceFile("Theme.kt").path
}
