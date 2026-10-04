package com.rad.shifttracker.data.storage

import android.content.Context
import java.io.File

/** Lokalni fajl sa podacima; izdvojeno da se čuvanje i učitavanje mogu testirati bez Android okruženja. */
internal interface LocalDataStore {
    fun save(context: Context, json: String): Boolean
    fun load(context: Context): String?
    fun hasData(context: Context): Boolean
    fun quarantineCorrupt(context: Context): Boolean
}

internal class LocalShiftStorage(
    private val fileName: String = LOCAL_BACKUP_FILE
) : LocalDataStore {
    override fun save(context: Context, json: String): Boolean = file(context).write(json)

    override fun load(context: Context): String? = file(context).read()

    private fun file(context: Context) = AtomicTextFile(File(context.filesDir, fileName))

    override fun hasData(context: Context): Boolean {
        return load(context)?.isNotEmpty() == true
    }

    /**
     * Kopira neispravan (neparsiv) fajl u karantin pre nego što ga prvi
     * sledeći autosave pregazi skoro praznim stanjem — da podaci ostanu
     * dostupni za ručni oporavak. Svaki put nova kopija (sa vremenom), da drugi
     * neispravan fajl ne pregazi prvi; čuva se poslednjih [KEEP_QUARANTINED].
     */
    override fun quarantineCorrupt(context: Context): Boolean {
        val source = File(context.filesDir, fileName)
        if (!source.exists()) return false
        return runCatching {
            val target = File(context.filesDir, "$fileName$CORRUPT_SUFFIX.${System.currentTimeMillis()}")
            source.copyTo(target, overwrite = true)
            context.filesDir.listFiles { file -> file.name.startsWith("$fileName$CORRUPT_SUFFIX") }
                .orEmpty()
                .sortedByDescending { it.lastModified() }
                .drop(KEEP_QUARANTINED)
                .forEach { it.delete() }
        }.isSuccess
    }

    companion object {
        const val LOCAL_BACKUP_FILE = "rad_local_data.json"
        const val CORRUPT_SUFFIX = ".corrupt"
        private const val KEEP_QUARANTINED = 3
    }
}
