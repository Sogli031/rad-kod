package com.rad.shifttracker.ai

import org.json.JSONArray
import org.json.JSONObject

/**
 * AI bira samo broj već proverenog rasporeda. Ne dobija listu, iznose ni identitet radnika.
 * null = AI je odgovorio, ali neispravno; [AiException] = AI nije dostupan (razlog u naslovu).
 */
internal fun interface PayslipCandidateSelector {
    fun select(payload: JSONObject): Int?
}

internal class GeminiPayslipCandidateSelector(
    private val transport: GeminiTransport,
    private val pause: (Long) -> Unit = { Thread.sleep(it) }
) : PayslipCandidateSelector {
    override fun select(payload: JSONObject): Int? {
        val count = payload.getJSONArray("candidates").length()
        val body = JSONObject()
            .put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", RANKING_PROMPT))))
            .put("contents", JSONArray().put(JSONObject().put("role", "user")
                .put("parts", JSONArray().put(JSONObject().put("text", payload.toString())))))
            .put("generationConfig", JSONObject()
                .put("thinkingConfig", JSONObject().put("thinkingLevel", "high"))
                .put("responseMimeType", "application/json")
                .put("responseJsonSchema", JSONObject().put("type", "object")
                    .put("properties", JSONObject().put("candidateId", JSONObject().put("type", "integer")
                        .put("minimum", 0).put("maximum", count - 1)))
                    .put("required", JSONArray().put("candidateId"))
                    .put("additionalProperties", false)))
        var lastError: AiException? = null
        // Google često kratko vraća 503 (preopterećen) za sve modele: tačnost ima prednost, pa se čeka i ponavlja.
        for (delay in RETRY_DELAYS_MS) {
            if (delay > 0) pause(delay)
            // Rezervni modeli za ovaj zahtev moraju takođe podržavati thinkingLevel=high.
            for (model in AI_MODELS.filter { it.startsWith("gemini-3") }) {
                val response = try {
                    transport.post(model, body)
                } catch (error: AiException) {
                    // 404: model ne postoji; 5xx: preopterećen ili greška servera. Ostalo (ključ, kvota) ne prolazi ponavljanjem.
                    if (error.httpCode != 404 && error.httpCode < 500) throw error
                    lastError = error
                    continue
                }
                return candidateId(response, count)
            }
        }
        throw lastError ?: AiException("Model nije dostupan")
    }

    private fun candidateId(response: JSONObject, count: Int): Int? = try {
        val candidate = response.optJSONArray("candidates")?.optJSONObject(0)
        if (candidate == null || candidate.optString("finishReason") != "STOP") null else {
            val parts = modelContent(response).getJSONArray("parts")
            val text = (0 until parts.length()).mapNotNull { index ->
                parts.optJSONObject(index)?.takeUnless { it.optBoolean("thought") }?.optString("text")
            }.joinToString("")
            val result = JSONObject(text)
            val id = result.opt("candidateId")
            if (result.length() != 1) null else (id as? Number)?.let { number ->
                number.toInt().takeIf { it.toDouble() == number.toDouble() && it in 0 until count }
            }
        }
    } catch (_: org.json.JSONException) {
        null
    }
}

private val RETRY_DELAYS_MS = listOf(0L, 5_000L, 15_000L, 30_000L)

private val RANKING_PROMPT = """
    Choose the most plausible estimated monthly shift schedule from candidates already checked by the application.
    Return only {"candidateId": integer}. Never calculate hours, money or create or modify a schedule.
    Monthly payroll totals do not establish actual dates. Do not infer certainty or invent personal facts.
    Each candidate has id and days: [ISO date, kind, shift, hours, templateShift].
    kind is a self-describing name (work, vacation, paid_leave, sick, absent, holiday_off,
    holiday_work, weekend_work, cycle_off). Shift 0 means no shift; 1/2/3 are work shifts.
    knownDays contains existing entries in the target month and its two adjacent months:
    [ISO date, kind, shift, hours, templateShift]. They can include previous inferred fills;
    use them as contextual hints, never as proof of actual dates.
    Prefer agreement with confirmed dates, continuation of actual leave at month boundaries,
    contiguous leave blocks, fewer isolated shift changes, and coherent overtime runs.
    donationDates are actual blood donation dates; paid leave near them is preferable when applicable.
    adjacentLeaveKinds is [previous month kind names, next month kind names] from payroll totals, a weak hint only.
    Do not prefer evenly scattered unusual days solely for visual symmetry. Do not invent a seasonal
    vacation preference or new work rules. When evidence is tied choose the smallest candidate id.
""".trimIndent()
