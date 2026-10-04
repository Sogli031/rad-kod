package com.rad.shifttracker.data.backup

import com.rad.shifttracker.MonthKey
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VersionedBackupWriterTest {

    private class MemoryStore : BackupFileStore {
        val files = linkedMapOf<String, Pair<BackupVersion, String?>>()
        val log = mutableListOf<String>()
        var unreadable = setOf<String>()
        var failCreate = false
        private var nextId = 0

        fun put(createdAt: Long, json: String?, stats: BackupStats? = STATS, safety: Boolean = false, flagged: Boolean = false): String {
            val id = "f${nextId++}"
            val props = stats?.let { BackupMetadata.toProperties(createdAt, it, flagged, "5.6", safety = safety) }
            files[id] = BackupMetadata.buildVersion(id, BackupMetadata.fileName(createdAt), createdAt, props) to json
            return id
        }

        override fun listVersions(): List<BackupVersion> {
            log += "list"
            return files.values.map { it.first }
        }

        override fun download(fileId: String): String? {
            log += "download $fileId"
            return if (fileId in unreadable) null else files[fileId]?.second
        }

        override fun create(fileName: String, json: String, props: Map<String, String>): String {
            log += "create"
            if (failCreate) error("Drive pao")
            val id = "f${nextId++}"
            files[id] = BackupMetadata.buildVersion(id, fileName, null, props) to json
            return id
        }

        override fun delete(fileId: String) {
            log += "delete $fileId"
            files.remove(fileId)
        }
    }

    private fun write(
        store: BackupFileStore,
        now: Long = NOW,
        safety: Boolean = false,
        stats: BackupStats = STATS,
        build: (String?) -> String = { "novo" }
    ) = runBlocking {
        VersionedBackupWriter.write(store, now, "5.7", safety, readTimeoutMillis = 5_000) { existing ->
            CloudBackupContent(build(existing), stats)
        }
    }

    @Test
    fun `nova verzija dobija sadrzaj najsvezije redovne verzije kao arhivu`() {
        val store = MemoryStore()
        store.put(NOW - 2 * DAY, "staro")
        store.put(NOW - DAY, "najnovije")
        var seen: String? = "nije pozvano"

        val result = write(store) { existing -> seen = existing; "spojeno" }

        assertEquals("najnovije", seen)
        assertTrue(result is VersionedWriteResult.Created)
        assertTrue(store.files.values.any { it.second == "spojeno" })
    }

    @Test
    fun `sigurnosna kopija nije arhiva za spajanje`() {
        val store = MemoryStore()
        store.put(NOW - 2 * DAY, "redovna")
        store.put(NOW - DAY, "sigurnosna", safety = true)
        var seen: String? = null

        write(store) { existing -> seen = existing; "x" }

        assertEquals("redovna", seen)
    }

    @Test
    fun `necitljiva najnovija verzija prekida upis bez ikakve izmene u oblaku`() {
        val store = MemoryStore()
        store.put(NOW - 2 * DAY, "staro")
        val latest = store.put(NOW - DAY, "najnovije")
        store.unreadable = setOf(latest)
        val before = store.files.keys.toList()
        var built = false

        val result = write(store) { built = true; "x" }

        assertEquals(VersionedWriteResult.LatestUnreadable, result)
        assertFalse("build ne sme da se pozove bez arhive", built)
        assertEquals(before, store.files.keys.toList())
        assertFalse(store.log.any { it == "create" || it.startsWith("delete") })
    }

    @Test
    fun `prazan oblak daje prvu verziju bez arhive`() {
        val store = MemoryStore()
        var seen: String? = "nije pozvano"

        val result = write(store) { existing -> seen = existing; "prva" }

        assertNull(seen)
        assertTrue(result is VersionedWriteResult.Created)
        assertEquals(1, store.files.size)
    }

    @Test
    fun `orezivanje ide tek posle upisa i nikad ne brise novu verziju`() {
        val store = MemoryStore()
        // 40 verzija u istom danu: retencija zadržava 10 najnovijih, ostale briše.
        repeat(40) { i -> store.put(NOW - (40 - i) * MINUTE, "v$i") }

        val result = write(store) as VersionedWriteResult.Created

        val createIndex = store.log.indexOf("create")
        val firstDelete = store.log.indexOfFirst { it.startsWith("delete") }
        assertTrue("bilo je brisanja", firstDelete >= 0)
        assertTrue("upis pre brisanja", createIndex in 0 until firstDelete)
        assertTrue(result.version.fileId in store.files)
        assertEquals("novo", store.files.getValue(result.version.fileId).second)
    }

    @Test
    fun `pad upisa ne brise nijednu staru verziju`() {
        val store = MemoryStore()
        repeat(40) { i -> store.put(NOW - (40 - i) * MINUTE, "v$i") }
        store.failCreate = true

        runCatching { write(store) }

        assertEquals(40, store.files.size)
        assertFalse(store.log.any { it.startsWith("delete") })
    }

    @Test
    fun `sigurnosna kopija je obelezena i nije nova najnovija verzija`() {
        val store = MemoryStore()
        store.put(NOW - DAY, "redovna")

        val result = write(store, safety = true) as VersionedWriteResult.Created

        assertTrue(result.version.safety)
        assertEquals("redovna", store.files[VersionedBackupPlanner.latest(store.listVersions())!!.fileId]?.second)
    }

    @Test
    fun `masovan pad podataka je oznacen ali se svejedno cuva`() {
        val store = MemoryStore()
        store.put(NOW - DAY, "puno")

        val result = write(store, stats = BackupStats.EMPTY) as VersionedWriteResult.Created

        assertTrue(result.flagged)
        assertTrue(result.reasons.isNotEmpty())
        assertTrue(result.version.flagged)
        assertTrue(result.version.fileId in store.files)
    }

    @Test
    fun `gate - usvojen oblak uvek sme`() {
        assertEquals(AutoBackupGate.ALLOWED, VersionedBackupWriter.gate(adopted = true, cloudVersions = null))
    }

    @Test
    fun `gate - nova instalacija sa verzijama u oblaku ceka odluku korisnika`() {
        val store = MemoryStore()
        store.put(NOW - DAY, "dobri podaci")

        assertEquals(
            AutoBackupGate.NEEDS_USER_DECISION,
            VersionedBackupWriter.gate(adopted = false, cloudVersions = store.listVersions())
        )
    }

    @Test
    fun `gate - samo sigurnosne kopije u oblaku ne blokiraju`() {
        val store = MemoryStore()
        store.put(NOW - DAY, "kopija", safety = true)

        assertEquals(AutoBackupGate.ALLOWED, VersionedBackupWriter.gate(adopted = false, cloudVersions = store.listVersions()))
    }

    @Test
    fun `gate - prazan oblak sme, nepoznat oblak ceka`() {
        assertEquals(AutoBackupGate.ALLOWED, VersionedBackupWriter.gate(adopted = false, cloudVersions = emptyList()))
        assertEquals(AutoBackupGate.UNKNOWN, VersionedBackupWriter.gate(adopted = false, cloudVersions = null))
    }

    private companion object {
        const val NOW = 1_790_000_000_000L
        const val MINUTE = 60_000L
        const val DAY = 86_400_000L
        val STATS = BackupStats(30, 1, 30 * 480L, MonthKey(2026, 9), MonthKey(2026, 9))
    }
}
