package com.rad.shifttracker.data.storage

import com.rad.shifttracker.smene.MonthKey
import org.json.JSONArray
import org.json.JSONObject
import java.time.YearMonth
import java.util.Locale
import java.util.TreeMap

/**
 * Sačuvani platni listići po mesecu („Uporedi“). Vrednost je JSON listića kakav pravi
 * `payslipToJson` (samo brojevi sa listića); ovde se samo ugnezdi pod ključ meseca
 * da bi backup ostao čitljiv, a pri čitanju vraća u isti tekst.
 */
internal object SavedPayslipsJsonCodec {
    const val KEY = "saved_payslips"

    fun write(payslips: Map<MonthKey, String>, data: MutableMap<String, Any>) {
        if (payslips.isEmpty()) return
        val byMonth = TreeMap<String, Any?>()
        for ((month, json) in payslips) {
            val parsed = runCatching { JSONObject(json) }.getOrNull() ?: continue
            // Locale.ROOT: ključ se čita sa YearMonth.parse, pa cifre moraju biti ASCII bez obzira na jezik telefona.
            byMonth[String.format(Locale.ROOT, "%04d-%02d", month.year, month.month)] = plain(parsed)
        }
        if (byMonth.isNotEmpty()) data[KEY] = byMonth
    }

    fun parse(json: JSONObject): Map<MonthKey, String> {
        val root = json.optJSONObject(KEY) ?: return emptyMap()
        val parsed = mutableMapOf<MonthKey, String>()
        val keys = root.keys()
        while (keys.hasNext()) {
            val raw = keys.next()
            val month = runCatching { YearMonth.parse(raw) }.getOrNull() ?: continue
            val payslip = root.optJSONObject(raw) ?: continue
            parsed[MonthKey(month.year, month.monthValue)] = payslip.toString()
        }
        return parsed
    }

    private fun plain(value: Any?): Any? = when (value) {
        is JSONObject -> TreeMap<String, Any?>().also { map ->
            val keys = value.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                map[key] = plain(value.opt(key))
            }
        }
        is JSONArray -> (0 until value.length()).map { plain(value.opt(it)) }
        JSONObject.NULL -> null
        else -> value
    }
}
