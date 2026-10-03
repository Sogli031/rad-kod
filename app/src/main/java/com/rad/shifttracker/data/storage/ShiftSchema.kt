package com.rad.shifttracker.data.storage

import org.json.JSONObject

internal const val SCHEMA_VERSION_KEY = "schema_version"

/** Fajl bez ključa [SCHEMA_VERSION_KEY] je pisan pre uvođenja verzije: to je verzija 1. */
internal const val LEGACY_SCHEMA_VERSION = 1

/**
 * Verzija sheme lokalnog JSON-a (i kopije u oblaku). Povećava se samo uz odgovarajući korak
 * u [SchemaMigrations]; starija aplikacija ne sme da pregazi fajl koji je napisala novija.
 */
internal const val CURRENT_LOCAL_SCHEMA_VERSION = 1

/** Zašto JSON nije prihvaćen za vraćanje (restore) — nijedan od ovih razloga ne menja stanje. */
internal enum class SnapshotRejection {
    /** Nije JSON objekat ili se ne može parsirati. */
    UNREADABLE,

    /** Objekat nema nijedan ključ koji aplikacija piše (npr. `{}` ili tuđ JSON). */
    NO_RECOGNIZED_DATA,

    /** `schema_version` je veći od onog koji ova verzija aplikacije razume. */
    NEWER_SCHEMA,

    /** `schema_version` nije ceo broj >= 1. */
    INVALID_SCHEMA_VERSION,

    /** Snapshot nema nijedan zapis, a lokalno ih ima: restore bi tiho ispraznio kalendar. */
    WOULD_ERASE_EXISTING_RECORDS
}

internal sealed interface SnapshotDecodeResult {
    data class Decoded(val snapshot: ShiftRepositorySnapshot, val sourceVersion: Int) : SnapshotDecodeResult
    data class Rejected(val reason: SnapshotRejection) : SnapshotDecodeResult
}

internal class SnapshotRejectedException(val reason: SnapshotRejection) :
    IllegalArgumentException("JSON nije prihvaćen: $reason")

/**
 * Koraci koji dižu JSON sa verzije N na N + 1 (ključ je N). Menjaju se samo neobrađeni
 * `JSONObject` pre čitanja, pa parser uvek vidi shemu [currentVersion].
 */
internal class SchemaMigrations(
    val currentVersion: Int = CURRENT_LOCAL_SCHEMA_VERSION,
    private val steps: Map<Int, (JSONObject) -> Unit> = DEFAULT_STEPS
) {
    fun migrate(json: JSONObject, fromVersion: Int) {
        for (version in fromVersion until currentVersion) {
            val step = steps[version] ?: error("Nedostaje migracija sheme sa verzije $version")
            step(json)
        }
    }

    /** Svaka verzija ispod trenutne mora imati korak; proverava se testom. */
    fun missingSteps(): List<Int> = (LEGACY_SCHEMA_VERSION until currentVersion).filter { it !in steps }

    private companion object {
        // Verzija 1 je trenutna: starije verzije ne postoje, pa nema koraka.
        val DEFAULT_STEPS: Map<Int, (JSONObject) -> Unit> = emptyMap()
    }
}

/** Verzija zapisana u JSON-u; null kad vrednost nije ceo broj >= 1. Nedostajući ključ je [LEGACY_SCHEMA_VERSION]. */
internal fun readSchemaVersion(json: JSONObject): Long? {
    if (!json.has(SCHEMA_VERSION_KEY)) return LEGACY_SCHEMA_VERSION.toLong()
    val number = json.opt(SCHEMA_VERSION_KEY) as? Number ?: return null
    val whole = number.toLong()
    return whole.takeIf { number.toDouble() == whole.toDouble() && it >= LEGACY_SCHEMA_VERSION }
}
