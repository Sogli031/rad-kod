package com.rad.shifttracker

import android.content.Context
import android.util.AtomicFile
import com.rad.shifttracker.data.storage.AtomicTextFile
import com.rad.shifttracker.data.storage.TextFileStore
import java.io.File
import java.math.BigDecimal
import java.time.YearMonth
import org.json.JSONArray
import org.json.JSONObject

/**
 * Povećati kad se promeni čitanje liste ili obračuna (nova stavka, ispravka parsera): tada se sve
 * zapamćeno odbacuje i PDF-ovi se jednom ponovo preuzmu i pročitaju. Keš se odbacuje i posle
 * svake nove instalacije aplikacije ([appInstallStamp]), pa zaboravljeno povećanje ne ostavlja
 * pogrešno pročitane obračune.
 */
internal const val PAYSLIP_PARSER_VERSION = 4

private const val CACHE_FILE_NAME = "gmail_payslip_cache.json"

/** Stariji obračuni od ovoga (po mesecu rada) se brišu iz keša. */
private const val CACHE_RETENTION_MONTHS = 30L

/**
 * Pročitani PDF-ovi iz Gmail-a po poruci: imena priloga i šta je iz njih pročitano (samo brojevi,
 * kao sačuvana lista — nikad tekst PDF-a). Poruka čiji su svi prilozi pročitani se više ne preuzima.
 * Pamte se samo liste i posebni obračuni; PDF koji nije lista se čita ponovo. Jedna instanca po
 * procesu, da provera pri ulasku i uvoz koji rade istovremeno ne brišu jedno drugom upis.
 */
internal class GmailPayslipCache private constructor(
    private val store: TextFileStore?,
    private val appInstallStamp: Long = 0L,
    private val currentMonth: () -> YearMonth = { YearMonth.now() }
) {
    private class CachedMessage(val names: List<String>, val results: MutableMap<String, PayslipParseResult>)

    private val messages = mutableMapOf<String, CachedMessage>()

    /** Rezultati svih priloga poruke, ili null ako poruka nije cela u kešu. */
    fun results(messageId: String): List<Pair<String, PayslipParseResult>>? = synchronized(LOCK) {
        val cached = messages[messageId] ?: return null
        cached.names.map { name -> name to (cached.results[name] ?: return null) }
    }

    fun rememberNames(messageId: String, names: List<String>): Unit = synchronized(LOCK) {
        val cached = messages[messageId]
        if (cached?.names == names) return
        messages[messageId] = CachedMessage(names, cached?.results?.filterKeys { it in names }.orEmpty().toMutableMap())
        save()
    }

    fun remember(messageId: String, name: String, result: PayslipParseResult): Unit = synchronized(LOCK) {
        if (result !is PayslipParseResult.Success && result !is PayslipParseResult.SideRun) return
        val cached = messages[messageId] ?: return
        cached.results[name] = result
        save()
    }

    private fun save() {
        val target = store ?: return
        val root = JSONObject().put("version", PAYSLIP_PARSER_VERSION).put("install", appInstallStamp)
        val all = JSONObject()
        for ((id, cached) in messages) {
            val results = JSONObject()
            cached.results.forEach { (name, result) -> resultToJson(result)?.let { results.put(name, it) } }
            all.put(id, JSONObject().put("names", JSONArray(cached.names)).put("results", results))
        }
        // AtomicFile: prekid usred upisa ne sme da ostavi polovičan keš (pa ni ponovno preuzimanje svih PDF-ova).
        runCatching { target.write(root.put("messages", all).toString()) }
    }

    private fun load() {
        val text = store?.let { runCatching { it.read() }.getOrNull() } ?: return
        val root = runCatching { JSONObject(text) }.getOrNull() ?: return
        if (root.optInt("version") != PAYSLIP_PARSER_VERSION || root.optLong("install") != appInstallStamp) return
        val cutoff = currentMonth().minusMonths(CACHE_RETENTION_MONTHS).let { MonthKey(it.year, it.monthValue) }
        val all = root.optJSONObject("messages") ?: return
        for (id in all.keys()) {
            val entry = all.optJSONObject(id) ?: continue
            val namesArray = entry.optJSONArray("names") ?: continue
            val names = (0 until namesArray.length()).map { namesArray.optString(it) }
            val resultsObj = entry.optJSONObject("results") ?: JSONObject()
            val results = mutableMapOf<String, PayslipParseResult>()
            for (name in resultsObj.keys()) {
                resultsObj.optJSONObject(name)?.let(::resultFromJson)?.let { results[name] = it }
            }
            val months = results.values.map(::resultMonth)
            if (months.isNotEmpty() && months.all { it < cutoff }) continue
            messages[id] = CachedMessage(names, results)
        }
    }

    companion object {
        private val LOCK = Any()
        private var shared: GmailPayslipCache? = null

        fun open(context: Context): GmailPayslipCache = synchronized(LOCK) {
            shared ?: GmailPayslipCache(AtomicTextFile(cacheFile(context)), appInstallStamp(context))
                .also { it.load(); shared = it }
        }

        private fun cacheFile(context: Context) = File(context.filesDir, CACHE_FILE_NAME)

        /** Bez fajla — za testove. */
        fun inMemory(): GmailPayslipCache = GmailPayslipCache(null)

        /** Keš nad zadatim skladištem i „današnjim“ mesecem — za testove (oštećen fajl, ponovno otvaranje). */
        fun withStore(
            store: TextFileStore,
            appInstallStamp: Long = 0L,
            currentMonth: () -> YearMonth = { YearMonth.now() }
        ): GmailPayslipCache = GmailPayslipCache(store, appInstallStamp, currentMonth).also { it.load() }

        fun clear(context: Context) = synchronized(LOCK) {
            shared = null
            // AtomicFile.delete briše i rezervnu kopiju, koja bi inače vratila keš pri sledećem čitanju.
            AtomicFile(cacheFile(context)).delete()
        }

        /** Vreme poslednje instalacije aplikacije: menja se sa svakom novom verzijom (i parserom). */
        private fun appInstallStamp(context: Context): Long =
            runCatching { context.packageManager.getPackageInfo(context.packageName, 0).lastUpdateTime }.getOrDefault(0L)
    }
}

private fun resultMonth(result: PayslipParseResult): MonthKey = when (result) {
    is PayslipParseResult.Success -> result.payslip.month
    is PayslipParseResult.SideRun -> result.month
    is PayslipParseResult.Failure -> MonthKey(0, 1)
}

internal fun resultToJson(result: PayslipParseResult): JSONObject? = when (result) {
    is PayslipParseResult.Success -> JSONObject().put("type", "payslip").put("payslip", payslipToJson(result.payslip))
    is PayslipParseResult.SideRun -> JSONObject().apply {
        put("type", "side")
        put("kind", result.kind.name)
        put("year", result.month.year)
        put("month", result.month.month)
        putOpt("runLabel", result.runLabel)
        putOpt("paidNet", result.paidNet?.toPlainString())
        result.paymentMonth?.let { put("payYear", it.year).put("payMonth", it.month) }
        putOpt("transportDaily", result.transportDaily?.toPlainString())
    }
    is PayslipParseResult.Failure -> null
}

internal fun resultFromJson(obj: JSONObject): PayslipParseResult? = runCatching {
    when (obj.getString("type")) {
        "payslip" -> payslipFromJsonOrNull(obj.getString("payslip"))?.let(PayslipParseResult::Success)
        "side" -> PayslipParseResult.SideRun(
            kind = PayslipSideRunKind.valueOf(obj.getString("kind")),
            month = MonthKey(obj.getInt("year"), obj.getInt("month")),
            runLabel = obj.optString("runLabel").takeIf { it.isNotEmpty() },
            paidNet = obj.optString("paidNet").takeIf { it.isNotEmpty() }?.let(::BigDecimal),
            paymentMonth = if (obj.has("payYear")) MonthKey(obj.getInt("payYear"), obj.getInt("payMonth")) else null,
            transportDaily = obj.optString("transportDaily").takeIf { it.isNotEmpty() }?.let(::BigDecimal)
        )
        else -> null
    }
}.getOrNull()
