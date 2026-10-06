package com.rad.shifttracker

import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.pow
import com.rad.shifttracker.smene.DayRecord
import com.rad.shifttracker.smene.Shift
import com.rad.shifttracker.ui.DarkExtendedColors
import com.rad.shifttracker.ui.ExtendedColors
import com.rad.shifttracker.ui.LightExtendedColors
import com.rad.shifttracker.ui.RadDarkColorScheme
import com.rad.shifttracker.ui.RadLightColorScheme
import com.rad.shifttracker.ui.calendarDayTint

/** Kontrast (WCAG 2.1) za svaku boju smene i oba reda teksta, u obe teme: najmanje 4.5:1. */
class ThemeContrastTest {
    private data class Theme(val name: String, val scheme: ColorScheme, val ext: ExtendedColors)

    private val themes = listOf(
        Theme("tamna", RadDarkColorScheme, DarkExtendedColors),
        Theme("svetla", RadLightColorScheme, LightExtendedColors)
    )

    private fun channel(c: Float): Double =
        if (c <= 0.03928f) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)

    private fun luminance(color: Color): Double =
        0.2126 * channel(color.red) + 0.7152 * channel(color.green) + 0.0722 * channel(color.blue)

    private val failures = mutableListOf<String>()

    /** Beleži kontrast ispod praga; sve greške se prijavljuju zajedno na kraju testa. */
    private fun check(label: String, a: Color, b: Color, min: Double = 4.5) {
        val ratio = contrast(a, b)
        if (ratio < min) failures += "%s: %.2f < %.1f".format(label, ratio, min)
    }

    private fun assertNoFailures() = assertTrue(failures.joinToString(", "), failures.isEmpty())

    private fun contrast(a: Color, b: Color): Double {
        val la = luminance(a)
        val lb = luminance(b)
        return (maxOf(la, lb) + 0.05) / (minOf(la, lb) + 0.05)
    }

    @Test
    fun `calendar cell text is readable for every shift in both themes`() {
        val records = Shift.entries.map { shift -> shift to DayRecord(2026, 10, 13, shift, if (shift == Shift.OFF) 0 else 8) }
        for (theme in themes) {
            val surface = theme.scheme.surfaceContainer
            for ((shift, record) in records) {
                val tint = calendarDayTint(record, false, false, theme.scheme, theme.ext)
                val cell = tint.background.compositeOver(surface)
                check("${theme.name} ${shift.name} broj dana", tint.dayNumber, cell)
                check("${theme.name} ${shift.name} detalj", tint.detail, cell)
            }
            val holiday = calendarDayTint(null, true, false, theme.scheme, theme.ext)
            val holidayCell = holiday.background.compositeOver(surface)
            check("${theme.name} praznik", holiday.dayNumber, holidayCell)
            val empty = calendarDayTint(null, false, false, theme.scheme, theme.ext)
            check("${theme.name} prazan dan", empty.dayNumber, surface)
            check("${theme.name} prazan detalj", empty.detail, surface)
        }
        assertNoFailures()
    }

    @Test
    fun `text on a solid shift color is readable in both themes`() {
        for (theme in themes) {
            val ext = theme.ext
            listOf(ext.shift1, ext.shift2, ext.vacation).forEach {
                check("${theme.name} svetle smene", ext.onShiftBright, it)
            }
            listOf(ext.shift3, ext.sick).forEach {
                check("${theme.name} ostale smene", ext.onShiftDeep, it)
            }
        }
        assertNoFailures()
    }

    @Test
    fun `status and secondary text colors are readable on every surface in both themes`() {
        for (theme in themes) {
            val surfaces = listOf(
                theme.scheme.background,
                theme.scheme.surfaceContainer,
                theme.scheme.surfaceContainerHigh,
                theme.scheme.surfaceContainerHighest
            )
            val ext = theme.ext
            for (surface in surfaces) {
                mapOf(
                    "textSecondary" to ext.textSecondary,
                    "textFaint" to ext.textFaint,
                    "onSurface" to theme.scheme.onSurface,
                    "onSurfaceVariant" to theme.scheme.onSurfaceVariant,
                    "primary" to theme.scheme.primary,
                    "error" to theme.scheme.error,
                    "success" to ext.success,
                    "warning" to ext.warning,
                    "compTime" to ext.compTime
                ).forEach { (role, color) ->
                    check("${theme.name} $role na $surface", color, surface)
                }
                check("${theme.name} outline", theme.scheme.outline, surface, 3.0)
            }
            check("${theme.name} onPrimary", theme.scheme.onPrimary, theme.scheme.primary)
            check("${theme.name} onError", theme.scheme.onError, theme.scheme.error)
        }
        assertNoFailures()
    }
}
