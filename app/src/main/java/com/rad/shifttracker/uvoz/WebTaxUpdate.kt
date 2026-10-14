package com.rad.shifttracker.uvoz

import android.content.Context
import android.util.Log
import java.io.IOException
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeParseException
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import com.rad.shifttracker.obracun.DatedTaxRelief
import com.rad.shifttracker.obracun.WebTaxParameters
import com.rad.shifttracker.obracun.mergeWebTaxParameters
import com.rad.shifttracker.repo.ShiftRepository
import com.rad.shifttracker.repo.webTaxParametersValue

/**
 * Tiho ažuriranje poreza, doprinosa i olakšice sa paragraf.rs: jednom dnevno pri pokretanju, u
 * pozadini; bez interneta ili kad stranica ne može pouzdano da se pročita, ostaje prethodno stanje.
 * Vrednosti važe samo za mesece posle poslednje liste ([WebTaxParameters]); listu nikad ne gaze.
 */
internal object WebTaxUpdate {
    private const val PREFS = "web_tax"
    private const val KEY = "parameters"
    private const val TAG = "WebTaxUpdate"

    /** Sačuvano stanje u repozitorijum (sinhrono, brzo) — pre prvog računanja ekrana. */
    fun applyStored(context: Context, repo: ShiftRepository) {
        repo.webTaxParametersValue = load(context)
    }

    /** Ako danas još nije provereno, čita paragraf.rs (pozvati van glavne niti). */
    fun refreshIfDue(context: Context, repo: ShiftRepository, today: LocalDate = LocalDate.now()) {
        val previous = load(context)
        if (previous?.fetchedOn == today) return
        val scraped = try {
            fetchTaxParameters()
        } catch (e: IOException) {
            Log.i(TAG, "Porez sa interneta nije proveren: ${e.message}")
            return
        } catch (e: IllegalStateException) {
            Log.w(TAG, "Porez sa interneta nije pouzdano pročitan: ${e.message}")
            return
        } catch (e: IllegalArgumentException) {
            Log.w(TAG, "Porez sa interneta nije pouzdano pročitan: ${e.message}")
            return
        }
        val merged = mergeWebTaxParameters(
            previous = previous,
            taxPercent = scraped.taxPercent,
            contributionsPercent = scraped.contributionsPercent,
            reliefs = scraped.reliefs,
            today = today
        )
        save(context, merged)
        repo.webTaxParametersValue = merged
    }

    private fun load(context: Context): WebTaxParameters? {
        val json = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null) ?: return null
        return try {
            decodeWebTaxParameters(json)
        } catch (e: JSONException) {
            Log.w(TAG, "Sačuvan porez sa interneta je neispravan", e)
            null
        } catch (e: DateTimeParseException) {
            Log.w(TAG, "Sačuvan porez sa interneta je neispravan", e)
            null
        }
    }

    private fun save(context: Context, value: WebTaxParameters) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY, encodeWebTaxParameters(value))
            .apply()
    }
}

internal fun encodeWebTaxParameters(value: WebTaxParameters): String = JSONObject().apply {
    put("taxPercent", value.taxPercent)
    put("contributionsPercent", value.contributionsPercent)
    put("ratesSeenSince", value.ratesSeenSince.toString())
    put("fetchedOn", value.fetchedOn.toString())
    put(
        "reliefs",
        JSONArray().apply {
            value.reliefs.forEach { put(JSONObject().put("validFrom", it.validFrom.toString()).put("amount", it.amount)) }
        }
    )
}.toString()

internal fun decodeWebTaxParameters(json: String): WebTaxParameters {
    val obj = JSONObject(json)
    val reliefs = obj.getJSONArray("reliefs")
    return WebTaxParameters(
        taxPercent = obj.getDouble("taxPercent"),
        contributionsPercent = obj.getDouble("contributionsPercent"),
        ratesSeenSince = YearMonth.parse(obj.getString("ratesSeenSince")),
        reliefs = (0 until reliefs.length()).map { i ->
            val item = reliefs.getJSONObject(i)
            DatedTaxRelief(LocalDate.parse(item.getString("validFrom")), item.getDouble("amount"))
        },
        fetchedOn = LocalDate.parse(obj.getString("fetchedOn"))
    )
}
