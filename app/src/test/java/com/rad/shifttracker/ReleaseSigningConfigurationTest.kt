package com.rad.shifttracker

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReleaseSigningConfigurationTest {
    @Test
    fun `release se potpisuje zajednickim kljucem preko debug konfiguracije`() {
        val source = listOf(
            java.io.File("build.gradle.kts"),
            java.io.File("app/build.gradle.kts")
        ).first { it.exists() }.readText()

        assertFalse(source.contains("allowDebugReleaseSigning"))
        // keystore/signing.gradle prepisuje signingConfigs.debug zajedničkim ključem za sve aplikacije.
        assertTrue(source.contains("rootProject.file(\"../keystore/signing.gradle\")"))
        assertTrue(source.contains("signingConfig = signingConfigs.getByName(\"debug\")"))
    }
}
