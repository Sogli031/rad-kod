package com.rad.shifttracker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import com.rad.shifttracker.azuriranje.isNewerVersion
import com.rad.shifttracker.azuriranje.parseLatestRelease
import com.rad.shifttracker.azuriranje.releaseNotesText

class AppUpdateTest {
    @Test
    fun `verzije se porede broj po broj`() {
        assertTrue(isNewerVersion("8.8", "8.7"))
        assertTrue(isNewerVersion("8.10", "8.9"))
        assertTrue(isNewerVersion("9.0", "8.12"))
        assertTrue(isNewerVersion("v8.8", "8.7"))
        assertFalse(isNewerVersion("8.7", "8.7"))
        assertFalse(isNewerVersion("8.6", "8.7"))
        assertFalse(isNewerVersion("beta", "8.7"))
    }

    @Test
    fun `iz release-a se cita verzija, izmene bez SHA i adresa rad apk`() {
        val json = """
            {"tag_name":"v8.8","body":"Izmene:\n- nova stvar\n\nSHA-256 APK-a: `abc`\nSHA-1 sertifikata: `def`",
             "assets":[{"name":"mapping.txt","browser_download_url":"https://x/mapping.txt","size":1},
                       {"name":"rad.apk","browser_download_url":"https://github.com/Sogli031/rad/releases/download/v8.8/rad.apk","size":4367223}]}
        """.trimIndent()

        val update = parseLatestRelease(json)!!

        assertEquals("8.8", update.version)
        assertEquals("Izmene:\n• nova stvar", update.notes)
        assertEquals("https://github.com/Sogli031/rad/releases/download/v8.8/rad.apk", update.apkUrl)
        assertEquals(4_367_223L, update.apkSize)
    }

    @Test
    fun `izmene su bez markdown oznaka`() {
        assertEquals(
            "• Bolovanje: sa procentom\n• Drugo",
            releaseNotesText("- **Bolovanje:** sa procentom\n- Drugo\n\nSHA-256 APK-a: `x`")
        )
    }

    @Test
    fun `release bez rad apk ili neispravan odgovor nije azuriranje`() {
        assertNull(parseLatestRelease("""{"tag_name":"v8.8","assets":[{"name":"smene-app-v8.8.apk","browser_download_url":"https://x"}]}"""))
        assertNull(parseLatestRelease("nije json"))
        assertNull(parseLatestRelease("""{"assets":[{"name":"rad.apk","browser_download_url":"https://x"}]}"""))
    }
}
