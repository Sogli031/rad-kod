package com.rad.shifttracker

import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object CrashLogFormatter {
    fun format(
        appName: String,
        packageName: String,
        androidVersion: String,
        deviceName: String,
        threadName: String,
        throwable: Throwable,
        timestampMillis: Long = System.currentTimeMillis()
    ): String {
        val timestamp = SimpleDateFormat("dd.MM.yyyy HH:mm:ss", Locale.getDefault())
            .format(Date(timestampMillis))
        val stackTrace = StringWriter().also { writer ->
            throwable.printStackTrace(PrintWriter(writer))
        }.toString()

        return buildString {
            appendLine("=== CRASH LOG ===")
            appendLine("Vreme: $timestamp")
            appendLine("Aplikacija: $appName")
            appendLine("Paket: $packageName")
            appendLine("Android verzija: $androidVersion")
            appendLine("Uređaj: $deviceName")
            appendLine("Nit: $threadName")
            appendLine("================")
            append(stackTrace)
        }
    }
}
