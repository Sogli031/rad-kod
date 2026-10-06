package com.rad.shifttracker

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ScreenSafeAreaTest {

    @Test
    fun `generic popup dialogs stay inside safe drawing insets`() {
        val source = popupComponentsSource()

        // Popup je list sa dna: donji inset kao padding, gornji ulazi u maksimalnu visinu.
        assertTrue(source.contains("WindowInsets.safeDrawing.asPaddingValues()"))
        assertTrue(source.contains(".padding(bottom = insets.calculateBottomPadding())"))
        assertTrue(source.contains("maxHeight - insets.calculateTopPadding() - UI.popupEdgePadding.dp"))
    }

    @Test
    fun `generic popup dialogs wrap content when height is not fixed`() {
        val source = popupComponentsSource()

        assertTrue(source.contains(".wrapContentHeight()"))
        assertTrue(source.contains("contentAlignment = Alignment.BottomCenter"))
    }

    @Test
    fun `shared popup max height does not force empty space`() {
        val source = popupComponentsSource()

        assertFalse(source.substringAfter("fun PopupDialog(").substringBefore(") {").contains("height"))
        assertTrue(source.contains("UI.popupMaxHeight.dp"))
        assertTrue(source.contains(".heightIn(max = sheetMaxHeight)"))
        assertTrue(source.contains(".weight(1f, fill = false)"))
        assertFalse(source.contains("Modifier.height(sheetMaxHeight)"))
        assertFalse(source.contains(".weight(1f, fill = true)"))
    }

    @Test
    fun `popup cards clip content to rounded container`() {
        val source = appSource("UiComponents.kt")

        // OutlinedCard sam zaobljava i kliping sadržaja po obliku kartice.
        assertTrue(source.contains("OutlinedCard("))
    }

    @Test
    fun `shared text buttons are Material buttons with adaptive text`() {
        val source = appSource("UiComponents.kt")
        val roundedButton = functionSource(source, "fun RoundedButton(")
        val glowButton = functionSource(source, "fun GlowButton(")
        val label = functionSource(source, "private fun RadButtonLabel(")

        // M3 Button daje ripple, ulogu dugmeta, dodirnu površinu 48dp i stanja.
        assertTrue(roundedButton.contains("Button("))
        assertTrue(glowButton.contains("Button("))
        assertTrue(roundedButton.contains("RadButtonLabel("))
        assertTrue(glowButton.contains("RadButtonLabel("))
        assertTrue(label.contains("AutoSizeText("))
        assertFalse(label.contains("\n        Text(\n            text = text"))
    }

    @Test
    fun `shared text buttons keep single line recognizable labels`() {
        val source = appSource("UiComponents.kt")
        val label = functionSource(source, "private fun RadButtonLabel(")

        assertTrue(label.contains("maxLines = 1"))
        assertTrue(label.contains("minFontSize = MaterialTheme.typography.labelSmall.fontSize"))
    }

    @Test
    fun `shared glow button supports disabled and destructive states from the theme`() {
        val source = appSource("UiComponents.kt")
        val glowButton = functionSource(source, "fun GlowButton(")
        val colors = functionSource(source, "private fun radButtonColors(")

        assertTrue(glowButton.contains("enabled: Boolean = true"))
        assertTrue(glowButton.contains("enabled = enabled"))
        assertTrue(glowButton.contains("destructive: Boolean = false"))
        assertTrue(glowButton.contains("scheme.error"))
        assertTrue(glowButton.contains("scheme.primary"))
        assertTrue(colors.contains("disabledContainerColor"))
        assertTrue(colors.contains("disabledContentColor"))
    }

    @Test
    fun `shared controls use M3 theme tokens instead of fixed palette`() {
        val source = appSource("UiComponents.kt")

        assertTrue(functionSource(source, "fun GlassCard(").contains("MaterialTheme.colorScheme.surfaceContainer"))
        assertTrue(functionSource(source, "fun GlassCard(").contains("MaterialTheme.shapes.medium"))
        // Ivica polja mora imati kontrast >= 3:1 (outline), ne ukrasnu outlineVariant liniju.
        assertTrue(functionSource(source, "fun ModernTextField(").contains("unfocusedBorderColor = scheme.outline"))
        assertTrue(source.contains("role = Role.Button"))
    }

    @Test
    fun `bottom bar action dialogs do not provide custom popup heights`() {
        listOf("AutoFillDialog.kt", "AbsenceDialog.kt", "PayslipReaderDialog.kt", "HolidayManagementDialog.kt")
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
    return mainSource(fileName)
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
