package com.rad.shifttracker

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object CrashLogFormatter {
    private const val MAX_CAUSE_DEPTH = 5

    fun format(
        appName: String,
        packageName: String,
        androidVersion: String,
        deviceName: String,
        threadName: String,
        throwable: Throwable,
        appVersion: String = "nepoznata",
        lastScreen: String = "nepoznat",
        timestampMillis: Long = System.currentTimeMillis()
    ): String {
        val timestamp = SimpleDateFormat("dd.MM.yyyy HH:mm:ss", Locale.getDefault())
            .format(Date(timestampMillis))

        return buildString {
            appendLine("=== CRASH LOG ===")
            appendLine("Vreme: $timestamp")
            appendLine("Aplikacija: $appName")
            appendLine("Verzija: $appVersion")
            appendLine("Paket: $packageName")
            appendLine("Android verzija: $androidVersion")
            appendLine("Uređaj: $deviceName")
            appendLine("Nit: $threadName")
            appendLine("Ekran: $lastScreen")
            appendLine("================")
            append(stackWithoutMessages(throwable))
        }
    }

    /** Klase izuzetaka i okviri steka, bez poruka (poruke mogu da nose lične podatke). */
    internal fun stackWithoutMessages(throwable: Throwable): String = buildString {
        var current: Throwable? = throwable
        var depth = 0
        while (current != null && depth < MAX_CAUSE_DEPTH) {
            appendLine(if (depth == 0) current.javaClass.name else "Caused by: ${current.javaClass.name}")
            current.stackTrace.forEach { appendLine("\tat $it") }
            current = current.cause?.takeIf { it !== current }
            depth++
        }
    }
}
