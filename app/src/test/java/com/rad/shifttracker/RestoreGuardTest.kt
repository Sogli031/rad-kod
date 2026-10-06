package com.rad.shifttracker

import android.content.Context
import android.content.ContextWrapper
import com.rad.shifttracker.data.storage.CURRENT_LOCAL_SCHEMA_VERSION
import com.rad.shifttracker.data.storage.LocalDataStore
import com.rad.shifttracker.data.storage.SCHEMA_VERSION_KEY
import com.rad.shifttracker.data.storage.SnapshotRejection
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import com.rad.shifttracker.repo.ShiftRepository
import com.rad.shifttracker.repo.exportJson
import com.rad.shifttracker.repo.importFromJson
import com.rad.shifttracker.repo.loadFromLocal
import com.rad.shifttracker.repo.payCoefficientValue
import com.rad.shifttracker.repo.saveToLocal
import com.rad.shifttracker.repo.taxReliefValue
import com.rad.shifttracker.repo.tryImportFromJson
import com.rad.shifttracker.smene.Shift
import com.rad.shifttracker.repo.buildCloudBackup

/**
 * Restore ne sme da obriše podatke kad fajl nije prava kopija: sve provere su pre brisanja,
 * pa odbijen JSON ostavlja stanje u memoriji i na disku netaknuto.
 */
class RestoreGuardTest {

    private fun filledRepo(): ShiftRepository {
        val repo = testRepository()
        repo.payCoefficientValue = 3.1
        repo.taxReliefValue = 12_345.0
        repo.set(2026, 9, 1, Shift.FIRST, 8)
        repo.set(2026, 9, 2, Shift.SECOND, 8)
        return repo
    }

    private fun assertUntouched(repo: ShiftRepository) {
        assertEquals(3.1, repo.payCoefficientValue, 0.0)
        assertEquals(12_345.0, repo.taxReliefValue, 0.0)
        assertEquals(Shift.FIRST, repo.get(2026, 9, 1)?.shift)
        assertEquals(Shift.SECOND, repo.get(2026, 9, 2)?.shift)
    }

    private fun validBackup(): JSONObject = JSONObject(filledRepo().exportJson())

    @Test
    fun `JSON bez ijednog prepoznatog kljuca se odbija i ne brise nista`() {
        val inputs = listOf(
            "{}",
            "{\"x\":1}",
            "{\"x\":1,\"y\":[1,2,3],\"z\":{\"a\":null}}",
            "{\"2026\": 5}",
            "{\"1800\": {\"01\": {\"01\": {\"shift\": 1, \"hours\": 8}}}}",
            "{\"$SCHEMA_VERSION_KEY\": 1}"
        )
        for (input in inputs) {
            val repo = filledRepo()
            assertEquals(input, SnapshotRejection.NO_RECOGNIZED_DATA, repo.tryImportFromJson(input))
            assertFalse(input, repo.importFromJson(input))
            assertUntouched(repo)
        }
    }

    @Test
    fun `nije JSON objekat odbija se kao necitljiv`() {
        for (input in listOf("", "   ", "ovo nije JSON", "[]", "[{\"pay_coefficient\":1.0}]", "null", "42", "{\"a\":")) {
            val repo = filledRepo()
            assertEquals(input, SnapshotRejection.UNREADABLE, repo.tryImportFromJson(input))
            assertUntouched(repo)
        }
    }

    @Test
    fun `prazan snapshot nad popunjenim stanjem se odbija`() {
        val emptyBackup = testRepository().exportJson()
        assertTrue("prazan export ima prepoznate ključeve", emptyBackup.contains("pay_coefficient"))

        val repo = filledRepo()
        assertEquals(SnapshotRejection.WOULD_ERASE_EXISTING_RECORDS, repo.tryImportFromJson(emptyBackup))
        assertFalse(repo.importFromJson(emptyBackup))
        assertUntouched(repo)
    }

    @Test
    fun `prazan snapshot nad praznim stanjem se prihvata`() {
        val repo = testRepository()
        repo.payCoefficientValue = 2.0
        val other = testRepository()
        other.payCoefficientValue = 3.4

        assertNull(repo.tryImportFromJson(other.exportJson()))
        assertEquals(3.4, repo.payCoefficientValue, 0.0)
    }

    @Test
    fun `popunjen snapshot zamenjuje popunjeno stanje`() {
        val source = testRepository()
        source.payCoefficientValue = 2.5
        source.set(2026, 9, 7, Shift.THIRD, 8)

        val repo = filledRepo()
        assertNull(repo.tryImportFromJson(source.exportJson()))

        assertEquals(2.5, repo.payCoefficientValue, 0.0)
        assertEquals(Shift.THIRD, repo.get(2026, 9, 7)?.shift)
        assertNull("stari zapis je zamenjen", repo.get(2026, 9, 1))
    }

    @Test
    fun `export pise verziju sheme`() {
        val json = validBackup()
        assertEquals(CURRENT_LOCAL_SCHEMA_VERSION, json.getInt(SCHEMA_VERSION_KEY))
    }

    @Test
    fun `backup bez verzije je verzija 1 i uvozi se`() {
        val legacy = validBackup().apply { remove(SCHEMA_VERSION_KEY) }

        val repo = testRepository()
        assertNull(repo.tryImportFromJson(legacy.toString()))
        assertEquals(3.1, repo.payCoefficientValue, 0.0)
        assertEquals(Shift.FIRST, repo.get(2026, 9, 1)?.shift)
    }

    @Test
    fun `backup sa trenutnom verzijom i nepoznatim dodatnim kljucevima se uvozi`() {
        val json = validBackup().put("buduci_kljuc", JSONObject().put("a", 1)).put("drugi", "x")

        val repo = testRepository()
        assertNull(repo.tryImportFromJson(json.toString()))
        assertEquals(Shift.SECOND, repo.get(2026, 9, 2)?.shift)
    }

    @Test
    fun `novija verzija sheme se odbija i ne menja stanje`() {
        for (version in listOf<Any>(CURRENT_LOCAL_SCHEMA_VERSION + 1, 99, 99_999_999_999L)) {
            val newer = validBackup().put(SCHEMA_VERSION_KEY, version).toString()
            val repo = testRepository()
            repo.payCoefficientValue = 2.0
            assertEquals("$version", SnapshotRejection.NEWER_SCHEMA, repo.tryImportFromJson(newer))
            assertFalse(repo.importFromJson(newer))
            assertEquals(2.0, repo.payCoefficientValue, 0.0)
            assertNull(repo.get(2026, 9, 1))
        }
    }

    @Test
    fun `nevazeca verzija sheme se odbija`() {
        val invalid = listOf<Any>(0, -1, "1", "dva", 1.5, true, JSONObject.NULL, JSONObject(), 1e30)
        for (version in invalid) {
            val json = validBackup().put(SCHEMA_VERSION_KEY, version).toString()
            val repo = testRepository()
            assertEquals("$version", SnapshotRejection.INVALID_SCHEMA_VERSION, repo.tryImportFromJson(json))
            assertNull(repo.get(2026, 9, 1))
        }
    }

    @Test
    fun `cela decimalna verzija 1_0 je verzija 1`() {
        val json = validBackup().put(SCHEMA_VERSION_KEY, 1.0).toString()
        assertNull(testRepository().tryImportFromJson(json))
    }

    @Test
    fun `restore kroz oblak prolazi guard`() {
        val source = filledRepo()
        val backup = source.buildCloudBackup(null).json

        val restored = testRepository()
        assertTrue(restored.importFromJson(backup))
        assertUntouched(restored)
    }

    // ---------------- Lokalni fajl: učitavanje i zaštita od downgrade-a ----------------

    private class FakeLocalStore(var content: String?) : LocalDataStore {
        var quarantined = 0
        var writes = 0

        override fun save(context: Context, json: String): Boolean {
            content = json
            writes++
            return true
        }

        override fun load(context: Context): String? = content
        override fun hasData(context: Context): Boolean = content?.isNotEmpty() == true
        override fun quarantineCorrupt(context: Context): Boolean {
            quarantined++
            return true
        }
    }

    private val context: Context = ContextWrapper(null)

    private fun repoWithStore(content: String?): Pair<ShiftRepository, FakeLocalStore> {
        val store = FakeLocalStore(content)
        val repo = testRepository()
        repo.localStorage = store
        return repo to store
    }

    @Test
    fun `lokalni fajl bez verzije se ucitava`() {
        val legacy = validBackup().apply { remove(SCHEMA_VERSION_KEY) }.toString()
        val (repo, store) = repoWithStore(legacy)

        assertTrue(repo.loadFromLocal(context))
        assertUntouched(repo)
        assertEquals(0, store.quarantined)
    }

    @Test
    fun `lokalni fajl sa trenutnom verzijom se ucitava i cuva sa verzijom`() {
        val (repo, store) = repoWithStore(filledRepo().exportJson())

        assertTrue(repo.loadFromLocal(context))
        assertUntouched(repo)
        assertTrue(repo.saveToLocal(context))
        assertEquals(CURRENT_LOCAL_SCHEMA_VERSION, JSONObject(store.content!!).getInt(SCHEMA_VERSION_KEY))
    }

    @Test
    fun `lokalni fajl novije verzije ide u karantin i ne pregazuje se`() {
        val newer = validBackup().put(SCHEMA_VERSION_KEY, CURRENT_LOCAL_SCHEMA_VERSION + 1).toString()
        val (repo, store) = repoWithStore(newer)

        assertFalse(repo.loadFromLocal(context))
        assertEquals(1, store.quarantined)
        assertNull(repo.get(2026, 9, 1))

        // Autosave posle neuspelog učitavanja ne sme da pregazi fajl koji je pisala novija verzija.
        repo.set(2026, 10, 1, Shift.FIRST, 8)
        assertFalse(repo.saveToLocal(context))
        assertEquals(0, store.writes)
        assertEquals(newer, store.content)
    }

    @Test
    fun `neparsiv lokalni fajl ide u karantin a autosave ostaje dozvoljen kao do sada`() {
        for (corrupt in listOf("ovo nije JSON", "{\"a\":", "{}", "{\"x\":1}")) {
            val (repo, store) = repoWithStore(corrupt)

            assertFalse(corrupt, repo.loadFromLocal(context))
            assertEquals(corrupt, 1, store.quarantined)
            assertTrue(corrupt, repo.saveToLocal(context))
            assertEquals(corrupt, 1, store.writes)
        }
    }

    @Test
    fun `prazan ili nepostojeci lokalni fajl nije greska i ne ide u karantin`() {
        for (content in listOf(null, "", "   ")) {
            val (repo, store) = repoWithStore(content)

            assertFalse(repo.loadFromLocal(context))
            assertEquals(0, store.quarantined)
            assertTrue(repo.saveToLocal(context))
        }
    }
}
