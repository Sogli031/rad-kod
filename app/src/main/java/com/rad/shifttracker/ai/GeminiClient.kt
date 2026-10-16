package com.rad.shifttracker.ai

import java.io.IOException
import java.net.ConnectException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.net.UnknownHostException
import javax.net.ssl.SSLException
import org.json.JSONException
import org.json.JSONObject

/**
 * Greška AI-ja koju korisnik vidi: [title] kaže šta nije u redu (jednom rečenicom), a [detail] nosi
 * HTTP kod i Google-ovu poruku, da se tačno zna o čemu se radi.
 */
internal class AiException(
    val title: String,
    val detail: String? = null,
    cause: Throwable? = null,
    /** HTTP kod od Google-a (0 za grešku mreže ili aplikacije): po njemu se zna da li vredi drugi model. */
    val httpCode: Int = 0
) : Exception(title + (detail?.let { ": $it" } ?: ""), cause)

/** Jedan HTTP poziv ka Gemini API-ju: telo zahteva unutra, JSON odgovor ili [AiException] napolje. */
internal fun interface GeminiTransport {
    fun post(model: String, body: JSONObject): JSONObject
}

/**
 * Gemini REST (generateContent). Ključ ide u zaglavlje, a uz njega paket i potpis aplikacije, pa
 * ključ može da se ograniči na ovu aplikaciju u Google Cloud konzoli.
 */
internal class HttpGeminiTransport(
    private val apiKey: String,
    private val packageName: String,
    private val certSha1: String = SIGNING_CERT_SHA1,
    private val readTimeoutMs: Int = READ_TIMEOUT_MS
) : GeminiTransport {

    override fun post(model: String, body: JSONObject): JSONObject {
        if (apiKey.isBlank()) throw AiException("Nema Gemini ključa u ovoj verziji aplikacije", "GEMINI_API_KEY nije upisan pri pravljenju APK-a")
        val connection = try {
            (URL("$GEMINI_BASE_URL/$model:generateContent").openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = readTimeoutMs
                doOutput = true
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
                setRequestProperty("x-goog-api-key", apiKey)
                setRequestProperty("X-Android-Package", packageName)
                setRequestProperty("X-Android-Cert", certSha1)
            }
        } catch (e: IOException) {
            throw networkError(e, readTimeoutMs)
        }
        try {
            connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            if (code !in 200..299) throw httpError(code, text)
            return try {
                JSONObject(text)
            } catch (e: JSONException) {
                throw AiException("Neočekivan odgovor od Google-a", "HTTP $code, nije JSON: ${text.take(ERROR_SNIPPET_CHARS)}", e)
            }
        } catch (e: AiException) {
            throw e
        } catch (e: IOException) {
            throw networkError(e, readTimeoutMs)
        } finally {
            connection.disconnect()
        }
    }
}

/** Greška mreže, pre nego što je Google uopšte odgovorio. */
internal fun networkError(e: IOException, readTimeoutMs: Int = READ_TIMEOUT_MS): AiException = when (e) {
    is UnknownHostException -> AiException("Nema interneta", "Google server nije pronađen (${e.message ?: "DNS"})", e)
    is ConnectException -> AiException("Nema veze sa Google serverom", e.message, e)
    is SocketTimeoutException -> AiException("Google nije odgovorio na vreme", "Prekid posle ${readTimeoutMs / MS_PER_SECOND} s", e)
    is SSLException -> AiException("Bezbedna veza nije uspela", e.message, e)
    else -> AiException("Greška mreže", "${e.javaClass.simpleName}: ${e.message}", e)
}

/** Greška koju je vratio Google, po HTTP kodu i statusu iz tela odgovora. */
internal fun httpError(code: Int, body: String): AiException {
    val error = runCatching { JSONObject(body).optJSONObject("error") }.getOrNull()
    val status = error?.optString("status").orEmpty()
    val message = error?.optString("message")?.takeIf { it.isNotBlank() } ?: body.take(ERROR_SNIPPET_CHARS)
    val reason = error?.optJSONArray("details")?.let { details ->
        (0 until details.length()).firstNotNullOfOrNull { i ->
            details.optJSONObject(i)?.optString("reason")?.takeIf { it.isNotBlank() }
        }
    }.orEmpty()
    val detail = listOf("HTTP $code", status, reason).filter { it.isNotBlank() }.joinToString(" ") + " — $message"
    val title = when {
        reason == "API_KEY_INVALID" || message.contains("API key not valid", ignoreCase = true) -> "Gemini ključ nije ispravan"
        reason == "API_KEY_ANDROID_APP_BLOCKED" || reason.startsWith("API_KEY_") -> "Ključ je ograničen i ne dozvoljava ovu aplikaciju"
        code == HTTP_TOO_MANY_REQUESTS -> "Potrošena besplatna kvota, probaj kasnije"
        code == HttpURLConnection.HTTP_UNAUTHORIZED || code == HttpURLConnection.HTTP_FORBIDDEN ->
            "Ključ nema dozvolu za Gemini"
        code == HttpURLConnection.HTTP_NOT_FOUND -> "Model nije dostupan"
        code == HttpURLConnection.HTTP_BAD_REQUEST -> "Google je odbio zahtev"
        code == HttpURLConnection.HTTP_UNAVAILABLE -> "Google je trenutno preopterećen, probaj za minut"
        code >= HttpURLConnection.HTTP_INTERNAL_ERROR -> "Greška na Google serveru"
        else -> "Neočekivana greška od Google-a"
    }
    return AiException(title, detail, httpCode = code)
}

/** Potpis zajedničkog ključa (programi\keystore) kojim su potpisane sve verzije aplikacije. */
internal const val SIGNING_CERT_SHA1 = "1BA8C0AAB7CD5BE3AF2C1099443D5C6375B06B55"

private const val GEMINI_BASE_URL = "https://generativelanguage.googleapis.com/v1beta/models"
private const val CONNECT_TIMEOUT_MS = 15_000
private const val READ_TIMEOUT_MS = 60_000
private const val MS_PER_SECOND = 1000
private const val ERROR_SNIPPET_CHARS = 300
private const val HTTP_TOO_MANY_REQUESTS = 429
