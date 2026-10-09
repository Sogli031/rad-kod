package com.rad.shifttracker.azuriranje

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import org.json.JSONObject
import kotlin.coroutines.cancellation.CancellationException

/** Poslednji release sa GitHub-a: isti onaj koji čita Obtainium i link `latest/download/rad.apk`. */
internal const val UPDATE_RELEASES_API = "https://api.github.com/repos/Sogli031/rad/releases/latest"
internal const val UPDATE_APK_NAME = "rad.apk"
internal const val UPDATE_APK_LINK = "https://github.com/Sogli031/rad/releases/latest/download/$UPDATE_APK_NAME"

private const val CONNECT_TIMEOUT_MS = 10_000
private const val READ_TIMEOUT_MS = 20_000
private const val MAX_NOTES_CHARS = 1_500

/** Novija verzija na GitHub-u: broj („8.8“), izmene iz release-a i adresa APK-a. */
internal data class AvailableUpdate(
    val version: String,
    val notes: String,
    val apkUrl: String,
    val apkSize: Long
)

/**
 * Iz odgovora GitHub API-ja (`releases/latest`) izvlači verziju i `rad.apk`; null ako nema
 * tog fajla ili je odgovor neispravan. Iz izmena se skida dodatak sa SHA otiscima koji pravi release.ps1.
 */
internal fun parseLatestRelease(json: String): AvailableUpdate? = try {
    val release = JSONObject(json)
    val version = release.optString("tag_name").removePrefix("v").trim()
    val assets = release.optJSONArray("assets")
    val apk = (0 until (assets?.length() ?: 0))
        .mapNotNull { assets?.optJSONObject(it) }
        .firstOrNull { it.optString("name") == UPDATE_APK_NAME }
    val url = apk?.optString("browser_download_url").orEmpty()
    if (version.isEmpty() || !url.startsWith("https://")) {
        null
    } else {
        AvailableUpdate(
            version = version,
            notes = releaseNotesText(release.optString("body")),
            apkUrl = url,
            apkSize = apk?.optLong("size") ?: 0L
        )
    }
} catch (e: org.json.JSONException) {
    null
}

/** Izmene iz CHANGELOG-a bez markdown oznaka: „**“ se skida, „- “ na početku reda postaje „• “. */
internal fun releaseNotesText(body: String): String =
    body.substringBefore("SHA-256 APK-a")
        .replace("**", "")
        .lines()
        .joinToString("\n") { line -> if (line.startsWith("- ")) "• " + line.removePrefix("- ") else line }
        .trim()
        .take(MAX_NOTES_CHARS)

/** „8.10“ je novije od „8.9“: poredi se broj po broj, ne kao tekst. */
internal fun isNewerVersion(remote: String, current: String): Boolean {
    val a = versionParts(remote)
    val b = versionParts(current)
    if (a.isEmpty() || b.isEmpty()) return false
    for (i in 0 until maxOf(a.size, b.size)) {
        val x = a.getOrElse(i) { 0 }
        val y = b.getOrElse(i) { 0 }
        if (x != y) return x > y
    }
    return false
}

private fun versionParts(version: String): List<Int> =
    version.trim().removePrefix("v").split('.').map { it.toIntOrNull() ?: return emptyList() }

/**
 * Pita GitHub za poslednji release (van glavne niti). Null kad nema mreže, GitHub ne odgovori
 * ili verzija nije novija od [currentVersion]; greška se nikad ne prijavljuje korisniku.
 */
internal fun fetchAvailableUpdate(currentVersion: String): AvailableUpdate? = try {
    val connection = (URL(UPDATE_RELEASES_API).openConnection() as HttpURLConnection).apply {
        connectTimeout = CONNECT_TIMEOUT_MS
        readTimeout = READ_TIMEOUT_MS
        setRequestProperty("Accept", "application/vnd.github+json")
    }
    try {
        if (connection.responseCode != HttpURLConnection.HTTP_OK) {
            null
        } else {
            val json = connection.inputStream.bufferedReader().use { it.readText() }
            parseLatestRelease(json)?.takeIf { isNewerVersion(it.version, currentVersion) }
        }
    } finally {
        connection.disconnect()
    }
} catch (e: CancellationException) {
    throw e
} catch (e: IOException) {
    null
} catch (e: SecurityException) {
    null
}
