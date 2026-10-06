package com.rad.shifttracker.data.storage

import android.util.Log
import com.rad.shifttracker.smene.MonthKey
import com.rad.shifttracker.repo.sanitizePayBaseOrNull
import org.json.JSONObject
import java.time.YearMonth
import java.util.Locale
import java.util.TreeMap

/**
 * Osnovice prepisane sa platnih listića. Novi ključ nosi sva sidra, a dva stara
 * ključa i dalje nose najnovije da starije verzije aplikacije ne izgube osnovicu.
 */
internal object KnownPayBaseAnchorsJsonCodec {
    const val ANCHORS_KEY = "known_pay_base_anchors"
    const val LEGACY_MONTH_KEY = "known_pay_base_month"
    const val LEGACY_GROSS_HOURLY_KEY = "known_pay_base_gross_hourly"
    private const val TAG = "KnownPayBaseAnchors"

    fun write(anchors: Map<MonthKey, Double>, data: MutableMap<String, Any>) {
        if (anchors.isEmpty()) return
        val sorted = anchors.toSortedMap()
        data[ANCHORS_KEY] = sorted.entries.associateTo(TreeMap<String, Any>()) { (month, base) ->
            monthText(month) to base
        }
        val latest = sorted.entries.last()
        data[LEGACY_MONTH_KEY] = monthText(latest.key)
        data[LEGACY_GROSS_HOURLY_KEY] = latest.value
    }

    fun parse(json: JSONObject): Map<MonthKey, Double> {
        val anchorsObj = json.optJSONObject(ANCHORS_KEY) ?: return parseLegacy(json)
        val parsed = mutableMapOf<MonthKey, Double>()
        val monthKeys = anchorsObj.keys()
        while (monthKeys.hasNext()) {
            val rawMonth = monthKeys.next()
            val month = parseMonth(rawMonth, ANCHORS_KEY) ?: continue
            val base = anchorsObj.optDouble(rawMonth, Double.NaN).sanitizePayBaseOrNull() ?: continue
            parsed[month] = base
        }
        return parsed
    }

    private fun parseLegacy(json: JSONObject): Map<MonthKey, Double> {
        if (!json.has(LEGACY_MONTH_KEY) || !json.has(LEGACY_GROSS_HOURLY_KEY)) return emptyMap()
        val month = parseMonth(json.optString(LEGACY_MONTH_KEY), LEGACY_MONTH_KEY) ?: return emptyMap()
        val base = json.optDouble(LEGACY_GROSS_HOURLY_KEY, Double.NaN).sanitizePayBaseOrNull()
            ?: return emptyMap()
        return mapOf(month to base)
    }

    private fun parseMonth(rawMonth: String, key: String): MonthKey? {
        val month = runCatching { YearMonth.parse(rawMonth) }.getOrNull()
        if (month == null) {
            Log.w(TAG, "Nevažeća vrednost za $key: '$rawMonth' — sidro odbačeno.")
            return null
        }
        return MonthKey(month.year, month.monthValue)
    }

    // Locale.ROOT: YearMonth.parse ne prihvata ne-ASCII cifre (npr. arapski jezik sistema)
    private fun monthText(month: MonthKey): String =
        String.format(Locale.ROOT, "%04d-%02d", month.year, month.month)
}
