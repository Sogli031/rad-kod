package com.rad.shifttracker

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import com.rad.shifttracker.osnova.CrashLogFormatter
import com.rad.shifttracker.osnova.CrashLogStorage

class CrashLogFormatterTest {
    @Test
    fun formatIncludesCrashMetadataAndStackTrace() {
        val log = CrashLogFormatter.format(
            appName = "Rad",
            packageName = "com.rad.shifttracker",
            androidVersion = "15",
            deviceName = "Test Device",
            threadName = "main",
            appVersion = "5.6 (47)",
            lastScreen = "glavni ekran",
            throwable = IllegalStateException("probni pad"),
            timestampMillis = 1_715_000_000_000L
        )

        assertTrue(log.contains("=== CRASH LOG ==="))
        assertTrue(log.contains("Aplikacija: Rad"))
        assertTrue(log.contains("Paket: com.rad.shifttracker"))
        assertTrue(log.contains("Android verzija: 15"))
        assertTrue(log.contains("Uređaj: Test Device"))
        assertTrue(log.contains("Nit: main"))
        assertTrue(log.contains("java.lang.IllegalStateException"))
        assertTrue(log.contains("Ekran: glavni ekran"))
        assertTrue(log.contains("Verzija: 5.6 (47)"))
    }

    @Test
    fun formatLeavesOutExceptionMessagesAndKeepsCauses() {
        val log = CrashLogFormatter.format(
            appName = "Rad",
            packageName = "com.rad.shifttracker",
            androidVersion = "15",
            deviceName = "Test Device",
            threadName = "main",
            throwable = IllegalStateException("JMBG 0101990710000", IllegalArgumentException("racun 160-123")),
            timestampMillis = 1_715_000_000_000L
        )

        assertFalse(log.contains("JMBG"))
        assertFalse(log.contains("racun"))
        assertTrue(log.contains("Caused by: java.lang.IllegalArgumentException"))
    }

    @Test
    fun historyKeepsOnlyLastFiveCrashes() {
        val file = File.createTempFile("crash_history", ".txt")
        try {
            repeat(8) { CrashLogStorage.appendToHistory(file, "pad $it") }
            val text = file.readText()
            assertFalse(text.contains("pad 2"))
            assertTrue(text.contains("pad 3"))
            assertTrue(text.contains("pad 7"))
        } finally {
            file.delete()
        }
    }
}
