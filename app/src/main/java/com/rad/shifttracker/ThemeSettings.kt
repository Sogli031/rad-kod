package com.rad.shifttracker

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

enum class ThemeMode(val label: String) {
    DARK("Tamna"),
    LIGHT("Svetla"),
    SYSTEM("Sistem")
}

/** Izbor teme: tamna je podrazumevana (kao do sada), svetla ili po sistemu; boje sa pozadine su opcione (Android 12+). */
data class ThemeSettings(
    val mode: ThemeMode = ThemeMode.DARK,
    val dynamicColor: Boolean = false
) {
    fun isDark(systemDark: Boolean): Boolean = when (mode) {
        ThemeMode.DARK -> true
        ThemeMode.LIGHT -> false
        ThemeMode.SYSTEM -> systemDark
    }
}

/** Čuva izbor teme u SharedPreferences; stanje je Compose state pa se tema menja odmah. */
object ThemeStore {
    private const val PREFS = "theme_settings"
    private const val KEY_MODE = "mode"
    private const val KEY_DYNAMIC = "dynamic"

    var settings by mutableStateOf(ThemeSettings())
        private set

    fun load(context: Context) {
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val mode = ThemeMode.entries.firstOrNull { it.name == prefs.getString(KEY_MODE, null) } ?: ThemeMode.DARK
        settings = ThemeSettings(mode, prefs.getBoolean(KEY_DYNAMIC, false))
    }

    fun save(context: Context, value: ThemeSettings) {
        settings = value
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_MODE, value.mode.name)
            .putBoolean(KEY_DYNAMIC, value.dynamicColor)
            .apply()
    }
}
