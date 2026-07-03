package com.rad.shifttracker.data.storage

import android.content.Context
import android.util.AtomicFile
import java.io.File
import java.io.FileOutputStream

internal class LocalShiftStorage(
    private val fileName: String = LOCAL_BACKUP_FILE
) {
    fun save(context: Context, json: String): Boolean {
        val atomicFile = AtomicFile(File(context.filesDir, fileName))
        var stream: FileOutputStream? = null
        return try {
            stream = atomicFile.startWrite()
            stream.write(json.toByteArray(Charsets.UTF_8))
            atomicFile.finishWrite(stream)
            true
        } catch (_: Exception) {
            stream?.let { runCatching { atomicFile.failWrite(it) } }
            false
        }
    }

    fun load(context: Context): String? {
        val atomicFile = AtomicFile(File(context.filesDir, fileName))
        return runCatching {
            atomicFile.readFully().toString(Charsets.UTF_8)
        }.getOrNull()
    }

    fun hasData(context: Context): Boolean {
        return load(context)?.isNotEmpty() == true
    }

    /**
     * Kopira neispravan (neparsiv) fajl u karantin pre nego što ga prvi
     * sledeći autosave pregazi skoro praznim stanjem — da podaci ostanu
     * dostupni za ručni oporavak.
     */
    fun quarantineCorrupt(context: Context): Boolean {
        val source = File(context.filesDir, fileName)
        if (!source.exists()) return false
        return runCatching {
            source.copyTo(File(context.filesDir, "$fileName$CORRUPT_SUFFIX"), overwrite = true)
        }.isSuccess
    }

    companion object {
        const val LOCAL_BACKUP_FILE = "rad_local_data.json"
        const val CORRUPT_SUFFIX = ".corrupt"
    }
}
