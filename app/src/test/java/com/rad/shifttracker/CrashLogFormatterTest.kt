package com.rad.shifttracker

import org.junit.Assert.assertTrue
import org.junit.Test

class CrashLogFormatterTest {
    @Test
    fun formatIncludesCrashMetadataAndStackTrace() {
        val log = CrashLogFormatter.format(
            appName = "Rad",
            packageName = "com.rad.shifttracker",
            androidVersion = "15",
            deviceName = "Test Device",
            threadName = "main",
            throwable = IllegalStateException("probni pad"),
            timestampMillis = 1_715_000_000_000L
        )

        assertTrue(log.contains("=== CRASH LOG ==="))
        assertTrue(log.contains("Aplikacija: Rad"))
        assertTrue(log.contains("Paket: com.rad.shifttracker"))
        assertTrue(log.contains("Android verzija: 15"))
        assertTrue(log.contains("Uređaj: Test Device"))
        assertTrue(log.contains("Nit: main"))
        assertTrue(log.contains("IllegalStateException: probni pad"))
    }
}
