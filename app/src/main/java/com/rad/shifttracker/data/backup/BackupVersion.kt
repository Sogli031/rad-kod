package com.rad.shifttracker.data.backup

import com.rad.shifttracker.data.storage.BackupStats

/**
 * Opis jedne verzije backup-a u oblaku (jedan Drive fajl `rad_backup_<ts>.json`).
 * Metapodaci ([stats], [flagged], verzije šeme/app-a) se čuvaju u Drive
 * appProperties da bi se lista verzija prikazala i orezala bez skidanja sadržaja.
 *
 * @param createdAtMillis vreme nastanka verzije (epoch ms) — primarni ključ za
 *   sortiranje i retenciju; izvodi se iz imena fajla, uz fallback na Drive
 *   createdTime za stare/ručno ubačene fajlove.
 * @param flagged true ako je [BackupAnomalyGuard] ocenio ovu verziju kao sumnjiv
 *   pad podataka; takve verzije se ne tretiraju kao „poslednja dobra".
 * @param stats otisak sadržaja ili null ako verzija nema upisane metapodatke
 *   (legacy fajl iz starije verzije app-a).
 */
data class BackupVersion(
    val fileId: String,
    val fileName: String,
    val createdAtMillis: Long,
    val flagged: Boolean = false,
    val stats: BackupStats? = null,
    val schemaVersion: Int = 0,
    val appVersion: String? = null,
    /**
     * Sigurnosna kopija napravljena neposredno PRE vraćanja (da restore bude
     * poništiv). Nije deo redovne linije backup-a: ne sme da bude „najnovija
     * verzija" za Restore niti arhiva za spajanje — inače bi Restore na novoj
     * instalaciji vratio upravo tu, polu-praznu kopiju.
     */
    val safety: Boolean = false
)
