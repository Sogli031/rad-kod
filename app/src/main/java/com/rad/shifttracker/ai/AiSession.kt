package com.rad.shifttracker.ai

import java.net.HttpURLConnection
import java.time.LocalDate
import java.time.format.TextStyle
import java.util.Locale
import org.json.JSONArray
import org.json.JSONObject
import com.rad.shifttracker.repo.ShiftRepository

/** Odgovor AI-ja na jednu poruku: tekst i, ako je tražen upis, izmene koje čekaju potvrdu. */
internal data class AiReply(val text: String, val proposal: AiProposal?)

/**
 * Razgovor sa Gemini-jem za aplikaciju. Pamti istoriju (dok traje proces), poziva alate dok AI ne
 * odgovori tekstom i prelazi na rezervni model kad je prvi preopterećen ili ga nema. Blokira nit, pa
 * se poziva van glavne niti.
 */
internal class AiSession(
    private val transport: GeminiTransport,
    private val models: List<String> = AI_MODELS
) {
    private val history = mutableListOf<JSONObject>()
    private var pendingNote: String? = null

    /** Napomena koja ide uz sledeću poruku (npr. da su izmene upisane ili odbijene). */
    fun noteForNextMessage(note: String) {
        pendingNote = note
    }

    @Synchronized
    fun send(message: String, repo: ShiftRepository, today: LocalDate): AiReply {
        val tools = AiTools(repo, today)
        val text = pendingNote?.let { "($it)\n$message" } ?: message
        val turnStart = history.size
        history += content("user", JSONArray().put(JSONObject().put("text", text)))
        try {
            return runTurn(tools, today).also { pendingNote = null }
        } catch (e: AiException) {
            // Neuspela poruka ne ostaje u istoriji: sledeće pitanje kreće od čistog stanja.
            while (history.size > turnStart) history.removeAt(history.lastIndex)
            throw e
        }
    }

    /** Pitanje → (alati →)* tekst; svaki korak se dopisuje u istoriju. */
    private fun runTurn(tools: AiTools, today: LocalDate): AiReply {
        repeat(MAX_TOOL_STEPS) {
            val response = generate(requestBody(tools, today))
            val modelContent = modelContent(response)
            history += modelContent
            val parts = modelContent.optJSONArray("parts") ?: JSONArray()
            val calls = (0 until parts.length()).mapNotNull { parts.optJSONObject(it)?.optJSONObject("functionCall") }
            if (calls.isEmpty()) {
                val reply = (0 until parts.length())
                    .mapNotNull { parts.optJSONObject(it)?.takeUnless { p -> p.optBoolean("thought") }?.optString("text") }
                    .joinToString("").trim()
                if (reply.isNotEmpty() || tools.proposal != null) return AiReply(reply, tools.proposal)
                throw AiException("AI nije vratio odgovor", "Prazan tekst bez poziva alata")
            }
            val responses = JSONArray()
            for (call in calls) {
                val result = tools.execute(call.optString("name"), call.optJSONObject("args") ?: JSONObject())
                val functionResponse = JSONObject().put("name", call.optString("name")).put("response", result)
                call.optString("id").takeIf { it.isNotBlank() }?.let { functionResponse.put("id", it) }
                responses.put(JSONObject().put("functionResponse", functionResponse))
            }
            history += content("user", responses)
        }
        throw AiException("AI se zapetljao", "Više od $MAX_TOOL_STEPS koraka bez odgovora")
    }

    fun clear() {
        history.clear()
        pendingNote = null
    }

    private fun requestBody(tools: AiTools, today: LocalDate) = JSONObject()
        .put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", systemPrompt(today)))))
        .put("contents", JSONArray(history))
        .put("tools", JSONArray().put(JSONObject().put("functionDeclarations", tools.declarations())))

    /** Prvi model koji odgovori; na 404 / 5xx / prekid veze probaju se sledeći, ostale greške se odmah javljaju. */
    private fun generate(body: JSONObject): JSONObject {
        var last: AiException? = null
        for (model in models) {
            try {
                return transport.post(model, withModelConfig(model, body))
            } catch (e: AiException) {
                last = e
                val retryOnNext = e.httpCode == HttpURLConnection.HTTP_NOT_FOUND || e.httpCode >= HttpURLConnection.HTTP_INTERNAL_ERROR
                if (!retryOnNext) throw e
            }
        }
        throw last ?: AiException("Nijedan model nije podešen")
    }
}

/** Gemini 3 sa malo „razmišljanja“: odgovor za par sekundi umesto pola minuta, a alate i dalje bira dobro. */
private fun withModelConfig(model: String, body: JSONObject): JSONObject {
    if (!model.startsWith("gemini-3")) return body
    val config = JSONObject().put("thinkingConfig", JSONObject().put("thinkingLevel", "low"))
    return JSONObject(body.toString()).put("generationConfig", config)
}

/** Sadržaj odgovora modela, ili [AiException] koji kaže zašto ga nema (blokiran, prekinut…). */
internal fun modelContent(response: JSONObject): JSONObject {
    val content = response.optJSONArray("candidates")?.optJSONObject(0)?.optJSONObject("content")
        ?.takeIf { (it.optJSONArray("parts")?.length() ?: 0) > 0 }
        ?: throw emptyResponseError(response)
    if (!content.has("role")) content.put("role", "model")
    return content
}

/** Zašto odgovor nema sadržaj: blokirano pitanje, blokiran ili prekinut odgovor, ili ništa. */
private fun emptyResponseError(response: JSONObject): AiException {
    val blockReason = response.optJSONObject("promptFeedback")?.optString("blockReason").orEmpty()
    if (blockReason.isNotBlank()) return AiException("Google je blokirao pitanje", "blockReason=$blockReason")
    val candidate = response.optJSONArray("candidates")?.optJSONObject(0)
        ?: return AiException("AI nije vratio odgovor", "Nema kandidata u odgovoru")
    val finishReason = candidate.optString("finishReason")
    val title = when (finishReason) {
        "SAFETY", "PROHIBITED_CONTENT", "BLOCKLIST", "SPII" -> "Google je blokirao odgovor"
        "MAX_TOKENS" -> "Odgovor je predugačak i prekinut"
        "MALFORMED_FUNCTION_CALL" -> "AI je pogrešno pozvao funkciju aplikacije"
        else -> "AI nije vratio odgovor"
    }
    return AiException(title, "finishReason=${finishReason.ifBlank { "nepoznat" }}")
}

private fun content(role: String, parts: JSONArray) = JSONObject().put("role", role).put("parts", parts)

internal fun systemPrompt(today: LocalDate): String {
    val weekday = today.dayOfWeek.getDisplayName(TextStyle.FULL, Locale.forLanguageTag("sr-Latn"))
    return """
        Ti si pomoćnik u aplikaciji „rad“ za radnika u smenama (Valjaonica bakra Sevojno). Danas je $today ($weekday).
        Odgovaraj kratko, na srpskom latinicom, obraćaj se sa „ti“, bez tabela i markdown-a.
        Nikad ne računaj novac ni sate sam: svaki iznos i broj sati uzmi iz alata „mesec“ ili „sta_ako“ i navedi ga tačno,
        kao „123.456 din“. Plata za rad u mesecu M isplaćuje se u mesecu M+1 (akontacija, pa ostatak 20.).
        Za „šta ako“ pitanja koristi „sta_ako“ i reci razliku u neto plati i u satima.
        Kad korisnik traži da se nešto upiše (smena, slobodan dan, bolovanje, godišnji, prekovremeno), pozovi „predlozi_izmene“
        i reci da potvrdi dugmetom; nikad ne tvrdi da je upisano pre potvrde.
        Smene: 1 = prva, 2 = druga, 3 = treća. Prekovremeno je više od 8 sati u smeni ili druga smena istog dana.
        Datum bez godine je najbliži takav datum od danas. Ako nešto nije jasno (koji dan, koja smena), pitaj kratko.
        Ako alat vrati „greska“, ispravi poziv ili kaži korisniku šta ne može.
    """.trimIndent()
}

/** Prvi je glavni (besplatan), ostali su rezerva kad je on preopterećen ili ukinut. */
internal val AI_MODELS = listOf("gemini-3.5-flash", "gemini-3.8-flash", "gemini-flash-latest")

private const val MAX_TOOL_STEPS = 6
