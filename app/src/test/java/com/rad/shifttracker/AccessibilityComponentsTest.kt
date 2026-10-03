package com.rad.shifttracker

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Pristupačnost zajedničkih komponenti: semantika (uloga, ime, stanje, liveRegion) i dodirne površine
 * od 48dp. Radi na računaru (Robolectric + Compose UI test), bez uređaja.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AccessibilityComponentsTest {

    @get:Rule
    val rule = createComposeRule()

    /** Dodirna površina (uključuje minimalni interaktivni razmak) mora biti bar [min] visoka. */
    private fun SemanticsNodeInteraction.assertTouchHeightIsAtLeast(min: Dp) {
        val heightPx = fetchSemanticsNode().touchBoundsInRoot.height
        val heightDp = with(rule.density) { heightPx.toDp() }
        assertTrue("dodirna visina $heightDp < $min", heightDp >= min)
    }

    private fun hasRole(role: Role) = SemanticsMatcher.expectValue(SemanticsProperties.Role, role)

    private fun hasPaneTitle() = SemanticsMatcher.keyIsDefined(SemanticsProperties.PaneTitle)

    private fun isHeading() = SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading)

    @Test
    fun `switch row is one toggleable switch named by title and hint`() {
        rule.setContent {
            RadTheme {
                var checked by remember { mutableStateOf(false) }
                LabeledSwitchRow(
                    title = "Prevoz u glavnoj plati",
                    hint = "Dodaje prevoz na iznos kartice",
                    checked = checked,
                    onCheckedChange = { checked = it }
                )
            }
        }

        val row = rule.onNode(isToggleable())
        row.assert(hasRole(Role.Switch))
        row.assert(hasText("Prevoz u glavnoj plati"))
        row.assert(hasText("Dodaje prevoz na iznos kartice"))
        row.assertIsOff()
        row.assertTouchHeightIsAtLeast(48.dp)
        // Sama kontrola nema svoju semantiku: samo jedan element koji se može uključiti.
        rule.onAllNodes(isToggleable()).assertCountEquals(1)
    }

    @Test
    fun `clicking the label text toggles the switch row`() {
        var changes = 0
        rule.setContent {
            RadTheme {
                var checked by remember { mutableStateOf(false) }
                LabeledSwitchRow(
                    title = "Dodatna smena",
                    checked = checked,
                    onCheckedChange = {
                        checked = it
                        changes++
                    }
                )
            }
        }

        rule.onNodeWithText("Dodatna smena").performClick()

        rule.onNode(isToggleable()).assertIsOn()
        assertEquals(1, changes)
    }

    @Test
    fun `disabled switch row cannot be toggled`() {
        var changes = 0
        rule.setContent {
            RadTheme {
                LabeledSwitchRow(title = "Zaključano", checked = true, enabled = false, onCheckedChange = { changes++ })
            }
        }

        rule.onNode(isToggleable()).assertIsNotEnabled()
        rule.onNode(isToggleable()).performClick()
        assertEquals(0, changes)
    }

    @Test
    fun `checkbox row exposes checkbox role and hides decorative duplicate state`() {
        rule.setContent {
            RadTheme {
                var checked by remember { mutableStateOf(true) }
                LabeledCheckboxRow(checked = checked, onCheckedChange = { checked = it }) {
                    Text("Nova godina")
                    // Kao značka „Da/Ne“ u listi praznika: ponavlja stanje pa je skrivena od čitača.
                    Text(text = if (checked) "Da" else "Ne", modifier = Modifier.clearAndSetSemantics { })
                }
            }
        }

        val row = rule.onNode(isToggleable())
        row.assert(hasRole(Role.Checkbox))
        row.assert(hasText("Nova godina"))
        row.assertIsOn()
        row.assertTouchHeightIsAtLeast(48.dp)
        rule.onAllNodes(hasText("Da")).assertCountEquals(0)

        row.performClick()
        row.assertIsOff()
    }

    @Test
    fun `form error is announced as an assertive live region`() {
        rule.setContent { RadTheme { FormErrorText(message = "Unesi ispravan iznos.") } }

        val error = rule.onNodeWithText("Unesi ispravan iznos.")
        error.assert(SemanticsMatcher.expectValue(SemanticsProperties.LiveRegion, LiveRegionMode.Assertive))
        error.assert(SemanticsMatcher.expectValue(SemanticsProperties.Error, "Unesi ispravan iznos."))
    }

    @Test
    fun `buttons keep a 48dp touch target even when the caller asks for less`() {
        rule.setContent {
            RadTheme {
                Column {
                    RoundedButton(
                        text = "Mali",
                        modifier = Modifier.height(36.dp),
                        bgColor = THEME.bgCardElevated,
                        textColor = THEME.textPrimary,
                        onClick = {}
                    )
                    GlowButton(text = "Sačuvaj", modifier = Modifier.height(40.dp), onClick = {})
                    GlowButton(text = "Podrazumevano", onClick = {})
                    Row {
                        DayEditChoiceButton(text = "Niska", selected = false, minHeight = 20, onClick = {})
                    }
                    HeaderChevron(icon = Icons.Filled.ChevronLeft, label = "Prethodni mesec", onClick = {})
                }
            }
        }

        rule.onNodeWithText("Mali").assertTouchHeightIsAtLeast(48.dp)
        rule.onNodeWithText("Sačuvaj").assertTouchHeightIsAtLeast(48.dp)
        rule.onNodeWithText("Podrazumevano").assertTouchHeightIsAtLeast(48.dp)
        rule.onNodeWithText("Niska").assertTouchHeightIsAtLeast(48.dp)
        rule.onNodeWithContentDescription("Prethodni mesec").assertTouchHeightIsAtLeast(48.dp)
    }

    @Test
    fun `day choice button is a radio button and chevron is a named button`() {
        rule.setContent {
            RadTheme {
                Column {
                    Row { DayEditChoiceButton(text = "Bolovanje", selected = true, minHeight = 52, onClick = {}) }
                    HeaderChevron(icon = Icons.Filled.ChevronLeft, label = "Prethodni mesec", onClick = {})
                }
            }
        }

        rule.onNodeWithText("Bolovanje").assert(hasRole(Role.RadioButton))
        rule.onNodeWithText("Bolovanje").assertIsSelected()
        rule.onNodeWithContentDescription("Prethodni mesec").assert(hasRole(Role.Button)).assertHasClickAction()
    }

    @Test
    fun `single character button is spoken through its description`() {
        var clicks = 0
        rule.setContent {
            RadTheme {
                RoundedButton(
                    text = "X",
                    contentDescription = "Ukloni davanje 01.03.2026.",
                    bgColor = THEME.bgCardElevated,
                    textColor = THEME.textPrimary,
                    onClick = { clicks++ }
                )
            }
        }

        val button = rule.onNodeWithContentDescription("Ukloni davanje 01.03.2026.")
        button.assert(hasRole(Role.Button)).assertIsEnabled().assertTouchHeightIsAtLeast(48.dp)
        button.performClick()
        assertEquals(1, clicks)
        rule.onAllNodes(hasText("X")).assertCountEquals(0)
    }

    @Test
    fun `text field keeps its label after a value is entered`() {
        rule.setContent {
            RadTheme {
                ModernTextField(
                    value = "2019",
                    onValueChange = {},
                    hintText = "npr. 2019",
                    label = "Godina",
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                )
            }
        }

        rule.onNodeWithText("Godina", useUnmergedTree = true).assertExists()
    }

    @Test
    fun `popup sheet announces its title as pane title and heading`() {
        rule.setContent { RadTheme { PopupDialog(title = "Opcije", onDismiss = {}) { Text("Sadržaj") } } }
        rule.waitForIdle()

        rule.onNode(hasPaneTitle()).assert(
            SemanticsMatcher.expectValue(SemanticsProperties.PaneTitle, "Opcije")
        )
        rule.onNode(isHeading()).assert(hasText("Opcije"))
    }

    @Test
    fun `popup scrim is not an unnamed clickable and the handle offers close`() {
        var dismissed = false
        rule.setContent {
            RadTheme { PopupDialog(title = "Opcije", onDismiss = { dismissed = true }) { Text("Sadržaj") } }
        }
        rule.waitForIdle()

        rule.onAllNodes(hasClickAction()).assertCountEquals(0)

        val handle = rule.onNode(SemanticsMatcher.keyIsDefined(SemanticsActions.CustomActions))
        val actions = handle.fetchSemanticsNode().config[SemanticsActions.CustomActions]
        assertEquals(listOf("Zatvori"), actions.map { it.label })
        rule.runOnUiThread { actions.first().action() }
        // Izlazna animacija lista mora da završi pre nego što se pozivalac obavesti.
        rule.mainClock.advanceTimeBy(1_000)
        rule.waitForIdle()
        assertTrue(dismissed)
    }

    @Test
    fun `calendar day description stays a full sentence for screen readers`() {
        assertEquals(
            "5. mart, nema unosa",
            calendarDayDescription(5, 3, null, false, false, false, BrigadeType.TROBRIGADA)
        )
        val worked = DayRecord(2026, 10, 13, Shift.FIRST, 8)
        val description = calendarDayDescription(13, 10, worked, true, false, false, BrigadeType.TROBRIGADA)
        assertEquals("13. oktobar, danas, ${Shift.FIRST.fullName}, ${formatHours(8)}", description)
    }
}
