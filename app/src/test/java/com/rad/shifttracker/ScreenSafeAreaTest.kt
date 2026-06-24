package com.rad.shifttracker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ScreenSafeAreaTest {

    @Test
    fun `main content reserves the bottom bar boundary`() {
        assertEquals(UI.bottomBarHeight + UI.largeSpacing, mainContentBottomPaddingDp())
    }

    @Test
    fun `generic popup dialogs stay inside safe drawing insets`() {
        val source = popupComponentsSource()

        assertTrue(source.contains("WindowInsets.safeDrawing.asPaddingValues()"))
        assertTrue(source.contains("bottom = UI.popupBottomSafePadding.dp"))
    }

    @Test
    fun `generic popup dialogs wrap content when height is not fixed`() {
        val source = popupComponentsSource()

        assertTrue(source.contains("popupContentModifier"))
        assertTrue(source.contains("Modifier.fillMaxWidth().wrapContentHeight()"))
    }

    @Test
    fun `shared popup max height does not force empty space`() {
        val source = popupComponentsSource()

        assertFalse(source.substringAfter("fun PopupDialog(").substringBefore(") {").contains("height"))
        assertTrue(source.contains("UI.popupMaxHeight.dp"))
        assertTrue(source.contains("maxHeight - UI.popupEdgePadding.dp - UI.popupBottomSafePadding.dp"))
        assertTrue(source.contains("Modifier.heightIn(max = popupHeight)"))
        assertTrue(source.contains(".weight(1f, fill = false)"))
        assertFalse(source.contains("Modifier.height(popupHeight)"))
        assertFalse(source.contains(".weight(1f, fill = true)"))
    }

    @Test
    fun `popup cards clip content to rounded container`() {
        val source = appSource("UiComponents.kt")

        assertTrue(source.contains(".clip(shape)"))
    }

    @Test
    fun `shared text buttons use adaptive text instead of fixed Text`() {
        val source = appSource("UiComponents.kt")
        val roundedButton = functionSource(source, "fun RoundedButton(")
        val glowButton = functionSource(source, "fun GlowButton(")

        assertTrue(roundedButton.contains("AutoSizeText("))
        assertTrue(glowButton.contains("AutoSizeText("))
        assertFalse(roundedButton.contains("\n        Text(\n            text = text"))
        assertFalse(glowButton.contains("\n        Text(\n            text = text"))
    }

    @Test
    fun `shared text buttons keep single line recognizable labels`() {
        val source = appSource("UiComponents.kt")
        val roundedButton = functionSource(source, "fun RoundedButton(")
        val glowButton = functionSource(source, "fun GlowButton(")

        assertTrue(roundedButton.contains("maxLines = 1"))
        assertTrue(glowButton.contains("maxLines = 1"))
        assertTrue(roundedButton.contains("minFontSize = MaterialTheme.typography.labelSmall.fontSize"))
        assertTrue(glowButton.contains("minFontSize = MaterialTheme.typography.labelSmall.fontSize"))
    }

    @Test
    fun `shared glow button supports disabled visual state`() {
        val source = appSource("UiComponents.kt")
        val glowButton = functionSource(source, "fun GlowButton(")

        assertTrue(glowButton.contains("enabled: Boolean = true"))
        assertTrue(glowButton.contains("val resolvedBackground = if (enabled) bgColor else THEME.empty"))
        assertTrue(glowButton.contains("val resolvedTextColor = if (enabled) textColor else THEME.textMuted"))
        assertTrue(glowButton.contains("enabled = enabled"))
    }

    @Test
    fun `bottom bar action dialogs do not provide custom popup heights`() {
        listOf("AutoFillDialog.kt", "AbsenceDialog.kt", "BasePayDialog.kt", "HolidayManagementDialog.kt")
            .forEach { fileName ->
                val source = appSource(fileName)

                assertTrue("$fileName should use PopupDialog", source.contains("PopupDialog("))
                assertFalse("$fileName should not set popup height ratio", source.contains("heightRatio ="))
                assertFalse("$fileName should not set popup height", source.contains("height ="))
            }
    }

    @Test
    fun `production popup dialogs cannot opt into custom heights`() {
        val sources = appSourceFiles().filterNot { it.name == "PopupComponents.kt" }

        sources.forEach { file ->
            val source = file.readText()
            popupDialogArgumentBlocks(source).forEach { arguments ->
                assertFalse("${file.name} should not set popup height ratio", arguments.contains("heightRatio ="))
                assertFalse("${file.name} should not set popup height", arguments.contains("height ="))
            }
        }
    }
}

private fun popupComponentsSource(): String {
    return appSource("PopupComponents.kt")
}

private fun appSource(fileName: String): String {
    return listOf(
        File("src/main/java/com/rad/shifttracker/$fileName"),
        File("app/src/main/java/com/rad/shifttracker/$fileName")
    ).first { it.isFile }.readText()
}

private fun appSourceFiles(): List<File> {
    return listOf(
        File("src/main/java/com/rad/shifttracker"),
        File("app/src/main/java/com/rad/shifttracker")
    ).first { it.isDirectory }
        .walkTopDown()
        .filter { it.isFile && it.extension == "kt" }
        .toList()
}

private fun popupDialogArgumentBlocks(source: String): List<String> {
    val blocks = mutableListOf<String>()
    var start = source.indexOf("PopupDialog(")
    while (start >= 0) {
        val argumentStart = start + "PopupDialog(".length
        val argumentEnd = source.indexOf(") {", argumentStart)
        if (argumentEnd >= 0) {
            blocks += source.substring(argumentStart, argumentEnd)
        }
        start = source.indexOf("PopupDialog(", argumentStart)
    }
    return blocks
}

private fun functionSource(source: String, signature: String): String {
    val functionStart = source.indexOf(signature)
    assertTrue("Missing function $signature", functionStart >= 0)
    val bodyStart = source.indexOf('{', functionStart)
    assertTrue("Missing body for $signature", bodyStart > functionStart)
    val bodyEnd = matchingBraceIndex(source, bodyStart)
    return source.substring(functionStart, bodyEnd + 1)
}

private fun matchingBraceIndex(source: String, openBraceIndex: Int): Int {
    var depth = 0
    for (index in openBraceIndex until source.length) {
        when (source[index]) {
            '{' -> depth++
            '}' -> {
                depth--
                if (depth == 0) return index
            }
        }
    }
    error("Missing matching brace")
}
