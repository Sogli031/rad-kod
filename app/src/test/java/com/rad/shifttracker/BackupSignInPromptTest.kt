package com.rad.shifttracker

import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BackupSignInPromptTest {

    @get:Rule
    val rule = createComposeRule()

    private fun show(
        lastSyncTime: Long? = 1_700_000_000_000L,
        signedIn: Boolean = false,
        firstLaunch: Boolean = false,
        dismissed: Boolean = false,
        otherDialogOpen: Boolean = false
    ) = shouldShowBackupSignInPrompt(lastSyncTime, signedIn, firstLaunch, dismissed, otherDialogOpen)

    @Test
    fun `imao backup a nije prijavljen dobija ponudu`() {
        assertTrue(show())
    }

    @Test
    fun `ko nikad nije imao backup ne dobija ponudu`() {
        assertFalse(show(lastSyncTime = null))
        assertFalse(show(lastSyncTime = 0L))
    }

    @Test
    fun `prijavljen korisnik ne dobija ponudu`() {
        assertFalse(show(signedIn = true))
    }

    @Test
    fun `prvo pokretanje (uvod) nema ponudu`() {
        assertFalse(show(firstLaunch = true))
    }

    @Test
    fun `posle Kasnije nema ponude do sledeceg pokretanja`() {
        assertFalse(show(dismissed = true))
    }

    @Test
    fun `ceka da se zatvori drugi prozor`() {
        assertFalse(show(otherDialogOpen = true))
    }

    @Test
    fun `prozor ima naslov i dugmad Prijavi se i Kasnije`() {
        var signIn = 0
        var later = 0
        rule.setContent {
            RadTheme {
                BackupSignInPromptDialog(onSignIn = { signIn++ }, onLater = { later++ })
            }
        }
        rule.onNodeWithText(BACKUP_SIGN_IN_PROMPT_TITLE).assertExists()
        rule.onNodeWithText(BACKUP_SIGN_IN_PROMPT_TEXT).assertExists()
        rule.onNodeWithText(BACKUP_SIGN_IN_PROMPT_CONFIRM).assertHasClickAction().performClick()
        rule.onNodeWithText(BACKUP_SIGN_IN_PROMPT_LATER).assertHasClickAction().performClick()
        assertEquals(1, signIn)
        assertEquals(1, later)
    }

    @Test
    fun `Prijavi se pokrece prijavu i sakriva ponudu`() {
        var signIn = 0
        rule.setContent {
            RadTheme {
                BackupSignInPromptHost(
                    lastSyncTime = 1L,
                    signedIn = false,
                    firstLaunch = false,
                    otherDialogOpen = false,
                    onSignIn = { signIn++ }
                )
            }
        }
        rule.onNodeWithText(BACKUP_SIGN_IN_PROMPT_CONFIRM).performClick()
        rule.waitForIdle()
        assertEquals(1, signIn)
        rule.onNodeWithText(BACKUP_SIGN_IN_PROMPT_TITLE).assertDoesNotExist()
    }

    @Test
    fun `Kasnije sakriva ponudu bez prijave`() {
        var signIn = 0
        rule.setContent {
            RadTheme {
                BackupSignInPromptHost(
                    lastSyncTime = 1L,
                    signedIn = false,
                    firstLaunch = false,
                    otherDialogOpen = false,
                    onSignIn = { signIn++ }
                )
            }
        }
        rule.onNodeWithText(BACKUP_SIGN_IN_PROMPT_LATER).performClick()
        rule.waitForIdle()
        assertEquals(0, signIn)
        rule.onNodeWithText(BACKUP_SIGN_IN_PROMPT_TITLE).assertDoesNotExist()
    }
}
