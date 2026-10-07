package com.rad.shifttracker.osnova

import android.app.Application
import android.content.Context
import android.os.Build
import java.io.File
import kotlin.system.exitProcess
import com.rad.shifttracker.BuildConfig

/**
 * Crash log ostaje samo u privatnom `filesDir` (ne u javnom Documents): `crash_log.txt` je poslednji pad
 * (prikazuje se pri sledećem pokretanju), a `crash_history.txt` čuva poslednjih [MAX_HISTORY_ENTRIES]
 * padova. Bez poruka izuzetaka — samo klase i stack, jer poruke mogu da sadrže lične podatke.
 */
object CrashLogStorage {
    const val CRASH_LOG_FILE = "crash_log.txt"
    const val CRASH_HISTORY_FILE = "crash_history.txt"

    private const val MAX_HISTORY_ENTRIES = 5
    private const val ENTRY_SEPARATOR = "\n-----\n"

    fun install(application: Application, appName: String) {
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            saveCrashLog(application, appName, thread, throwable)

            if (defaultHandler != null) {
                defaultHandler.uncaughtException(thread, throwable)
            } else {
                android.os.Process.killProcess(android.os.Process.myPid())
                exitProcess(10)
            }
        }
    }

    private fun saveCrashLog(
        context: Context,
        appName: String,
        thread: Thread,
        throwable: Throwable
    ) {
        val logText = CrashLogFormatter.format(
            appName = appName,
            packageName = context.packageName,
            appVersion = appVersion(),
            androidVersion = Build.VERSION.RELEASE
                .takeIf { it.isNotBlank() }
                ?: Build.VERSION.SDK_INT.toString(),
            deviceName = listOf(Build.MANUFACTURER, Build.MODEL)
                .filter { it.isNotBlank() }
                .joinToString(" "),
            threadName = thread.name,
            lastScreen = CrashContext.screen,
            throwable = throwable
        )

        runCatching {
            File(context.filesDir, CRASH_LOG_FILE).writeText(logText, Charsets.UTF_8)
        }
        runCatching {
            appendToHistory(File(context.filesDir, CRASH_HISTORY_FILE), logText)
        }
    }

    internal fun appendToHistory(file: File, logText: String) {
        val previous = if (file.exists()) file.readText(Charsets.UTF_8).split(ENTRY_SEPARATOR).filter { it.isNotBlank() } else emptyList()
        val kept = (previous + logText.trimEnd()).takeLast(MAX_HISTORY_ENTRIES)
        file.writeText(kept.joinToString(ENTRY_SEPARATOR), Charsets.UTF_8)
    }

    private fun appVersion(): String = "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})"
}

/** Poslednji ekran/dijalog koji je korisnik otvorio; ulazi u crash log da se zna gde je pukla aplikacija. */
object CrashContext {
    @Volatile
    var screen: String = "glavni ekran"
}
