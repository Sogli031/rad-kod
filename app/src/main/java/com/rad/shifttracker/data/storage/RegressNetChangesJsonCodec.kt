package com.rad.shifttracker.data.storage

import com.rad.shifttracker.smene.MonthKey
import java.util.Locale
import java.util.TreeMap
import org.json.JSONObject

/** Neto regres od meseca sa obračuna regresa: `"regress_net_changes": {"2026-11": 25000.0}`. */
internal object RegressNetChangesJsonCodec {
    const val KEY = "regress_net_changes"

    fun write(changes: Map<MonthKey, Double>, data: MutableMap<String, Any>) {
        if (changes.isEmpty()) return
        data[KEY] = TreeMap(changes.mapKeys { (key, _) -> String.format(Locale.ROOT, "%04d-%02d", key.year, key.month) })
    }

    /** Nevažeći ključevi i iznosi se preskaču. */
    fun read(json: JSONObject): Map<MonthKey, Double> {
        val root = json.optJSONObject(KEY) ?: return emptyMap()
        val parsed = mutableMapOf<MonthKey, Double>()
        for (key in root.keys()) {
            val parts = key.split('-').mapNotNull(String::toIntOrNull)
            if (parts.size != 2 || parts[0] !in 1900..2200 || parts[1] !in 1..12) continue
            val value = root.optDouble(key, Double.NaN)
            if (value.isFinite() && value > 0.0) parsed[MonthKey(parts[0], parts[1])] = value
        }
        return parsed
    }
}
