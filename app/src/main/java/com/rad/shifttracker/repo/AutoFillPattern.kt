package com.rad.shifttracker.repo

import android.content.Context
import java.time.LocalDate
import com.rad.shifttracker.smene.DayRecordSource
import com.rad.shifttracker.smene.Shift
import com.rad.shifttracker.smene.ShiftFillMode
import com.rad.shifttracker.smene.ShiftFillSettings
import com.rad.shifttracker.smene.ShiftScheduler

/** Poslednji dan koji je upisala auto-popuna, ili null ako popuna nikad nije pokrenuta. */
internal fun ShiftRepository.lastAutoFilledDate(): LocalDate? = synchronized(lock) {
    recordStore.allRecords
        .filter { it.source == DayRecordSource.AUTO_FILL }
        .maxOfOrNull { it.date }
}

/**
 * Obrazac popune prepoznat iz upisanih smena — kad nije sačuvan (verzija pre kontinualne
 * popune, vraćen backup na novom telefonu). Isto prepoznavanje koristi i obračun budućih meseci.
 */
internal fun ShiftRepository.inferAutoFillSettings(): ShiftFillSettings? = synchronized(lock) {
    when (val pattern = RollingPayBaseMonthSimulator(config, payState, recordStore).findPattern()) {
        is RollingPayBaseMonthSimulator.Pattern.ShiftFill -> pattern.settings
        is RollingPayBaseMonthSimulator.Pattern.FourBrigade -> ShiftFillSettings(
            mode = ShiftFillMode.FOUR_BRIGADE,
            anchorDate = pattern.pattern.anchorDate,
            selectedShift = Shift.FIRST,
            cycleDayIndex = pattern.pattern.cycleStartIndex
        )
        null -> null
    }
}

private const val PATTERN_PREFS = "auto_fill_pattern"
private const val KEY_MODE = "mode"
private const val KEY_ANCHOR = "anchor"
private const val KEY_SHIFT = "shift"
private const val KEY_CYCLE_INDEX = "cycle_index"

/** Poslednji obrazac iz „Popuni“, da se raspored sam produžava kako vreme odmiče. */
internal object AutoFillPatternStore {
    fun save(context: Context, settings: ShiftFillSettings) {
        context.getSharedPreferences(PATTERN_PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_MODE, settings.mode.name)
            .putString(KEY_ANCHOR, settings.anchorDate.toString())
            .putInt(KEY_SHIFT, settings.selectedShift.value)
            .putInt(KEY_CYCLE_INDEX, settings.cycleDayIndex ?: -1)
            .apply()
    }

    fun load(context: Context): ShiftFillSettings? {
        val prefs = context.getSharedPreferences(PATTERN_PREFS, Context.MODE_PRIVATE)
        val mode = ShiftFillMode.entries.firstOrNull { it.name == prefs.getString(KEY_MODE, null) } ?: return null
        val anchor = runCatching { LocalDate.parse(prefs.getString(KEY_ANCHOR, null)) }.getOrNull() ?: return null
        val shift = Shift.entries.firstOrNull { it.value == prefs.getInt(KEY_SHIFT, -1) } ?: return null
        val cycleIndex = prefs.getInt(KEY_CYCLE_INDEX, -1).takeIf { it >= 0 }
        return ShiftFillSettings(mode, anchor, shift, cycleIndex)
    }
}

/**
 * Pri pokretanju: dopiše raspored do [autoFillHorizonEnd]. Obrazac je sačuvani iz „Popuni“, a
 * ako ga nema (stara verzija, vraćen backup) prepozna se iz smena i sačuva za sledeći put.
 */
internal fun ShiftRepository.extendAutoFillOnStart(context: Context, today: LocalDate = LocalDate.now(clock)): Int =
    ShiftScheduler().extendAutoFill(this, today) {
        AutoFillPatternStore.load(context)
            ?: inferAutoFillSettings()?.also { AutoFillPatternStore.save(context, it) }
    }
