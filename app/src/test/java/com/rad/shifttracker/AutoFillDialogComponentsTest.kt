package com.rad.shifttracker

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

class AutoFillDialogComponentsTest {
    @Test
    fun modeSelectorsUseDayEditDayTypeHeight() {
        val source = autoFillDialogComponentsSource()

        assertEquals(65, source.constantValue("AUTO_FILL_MODE_CHOICE_HEIGHT"))
    }

    @Test
    fun shiftSelectorsUseDayEditShiftHeight() {
        val source = autoFillDialogComponentsSource()

        assertEquals(50, source.constantValue("AUTO_FILL_SHIFT_CHOICE_HEIGHT"))
    }

    @Test
    fun shiftSelectorsDoNotSplitThreeOrFourChoicesIntoTwoColumnRows() {
        val source = autoFillDialogComponentsSource()

        assertEquals(0, Regex("""options\.chunked\(2\)""").findAll(source).count())
    }

    @Test
    fun selectorsUseAdaptiveMinimumHeightsInsteadOfExactHeights() {
        val source = autoFillDialogComponentsSource()

        assertEquals(0, Regex("""\.height\(AUTO_FILL_MODE_CHOICE_HEIGHT\.dp\)""").findAll(source).count())
        assertEquals(0, Regex("""\.height\(AUTO_FILL_SHIFT_CHOICE_HEIGHT\.dp\)""").findAll(source).count())
        assertEquals(2, Regex("""\.heightIn\(min = AUTO_FILL_MODE_CHOICE_HEIGHT\.dp\)""").findAll(source).count())
        assertEquals(4, Regex("""\.heightIn\(min = AUTO_FILL_SHIFT_CHOICE_HEIGHT\.dp\)""").findAll(source).count())
    }
}

private fun autoFillDialogComponentsSource(): String {
    return listOf(
        File("src/main/java/com/rad/shifttracker/AutoFillDialogComponents.kt"),
        File("app/src/main/java/com/rad/shifttracker/AutoFillDialogComponents.kt")
    ).first { it.isFile }.readText()
}

private fun String.constantValue(name: String): Int {
    val match = Regex("""private const val $name = (\d+)""").find(this)
        ?: error("Missing constant $name")
    return match.groupValues[1].toInt()
}
