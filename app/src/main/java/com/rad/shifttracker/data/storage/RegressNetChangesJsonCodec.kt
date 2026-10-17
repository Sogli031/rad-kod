package com.rad.shifttracker.data.storage

import com.rad.shifttracker.smene.MonthKey
import java.util.Locale
import java.util.TreeMap
import org.json.JSONObject

/** Iznos po mesecu pod ključem [key]: `"<key>": {"2026-11": 25000.0}`. */
internal class MonthAmountsJsonCodec(val key: String) {

    fun write(changes: Map<MonthKey, Double>, data: MutableMap<String, Any>) {
        if (changes.isEmpty()) return
        data[key] = TreeMap(changes.mapKeys { (month, _) -> String.format(Locale.ROOT, "%04d-%02d", month.year, month.month) })
    }

    /** Nevažeći ključevi i iznosi se preskaču. */
    fun read(json: JSONObject): Map<MonthKey, Double> {
        val root = json.optJSONObject(key) ?: return emptyMap()
        val parsed = mutableMapOf<MonthKey, Double>()
        for (name in root.keys()) {
            val parts = name.split('-').mapNotNull(String::toIntOrNull)
            if (parts.size != 2 || parts[0] !in 1900..2200 || parts[1] !in 1..12) continue
            val value = root.optDouble(name, Double.NaN)
            if (value.isFinite() && value > 0.0) parsed[MonthKey(parts[0], parts[1])] = value
        }
        return parsed
    }
}

/** Neto regres od meseca sa obračuna regresa: `"regress_net_changes": {"2026-11": 25000.0}`. */
internal val RegressNetChangesJsonCodec = MonthAmountsJsonCodec("regress_net_changes")

/** Prevoz po danu po mesecu rada, sa obračuna prevoza: `"transport_daily_by_month": {"2026-08": 380.0}`. */
internal val TransportDailyByMonthJsonCodec = MonthAmountsJsonCodec("transport_daily_by_month")
