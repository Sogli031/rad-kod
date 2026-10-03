package com.rad.shifttracker.data.storage

import android.util.AtomicFile
import java.io.File
import java.io.FileOutputStream

/** Tekstualni fajl koji se čita i piše u celini; izdvojeno da se keš može testirati bez Android okruženja. */
internal interface TextFileStore {
    fun read(): String?
    fun write(text: String): Boolean
}

/**
 * Upis preko [AtomicFile] (privremeni fajl pa preimenovanje): prekid usred upisa ostavlja ili stari
 * ili novi sadržaj, nikad polovičan JSON. Čitanje takođe vraća sačuvanu kopiju posle prekinutog upisa.
 */
internal class AtomicTextFile(private val file: File) : TextFileStore {
    override fun read(): String? {
        return runCatching { AtomicFile(file).readFully().toString(Charsets.UTF_8) }.getOrNull()
    }

    override fun write(text: String): Boolean {
        val atomicFile = AtomicFile(file)
        var stream: FileOutputStream? = null
        return try {
            stream = atomicFile.startWrite()
            stream.write(text.toByteArray(Charsets.UTF_8))
            atomicFile.finishWrite(stream)
            true
        } catch (_: Exception) {
            stream?.let { runCatching { atomicFile.failWrite(it) } }
            false
        }
    }
}
