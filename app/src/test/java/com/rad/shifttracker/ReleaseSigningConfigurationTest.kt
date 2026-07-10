package com.rad.shifttracker

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReleaseSigningConfigurationTest {
    @Test
    fun `release cuva lokalni debug potpis dok pravi kljuc nije konfigurisan`() {
        val source = listOf(
            java.io.File("build.gradle.kts"),
            java.io.File("app/build.gradle.kts")
        ).first { it.exists() }.readText()

        assertFalse(source.contains("allowDebugReleaseSigning"))
        assertTrue(source.contains("releaseSigningConfigured -> signingConfigs.getByName(\"release\")"))
        assertTrue(source.contains("else -> signingConfigs.getByName(\"debug\")"))
    }
}
