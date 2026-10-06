package com.rad.shifttracker.data.storage

import android.content.Context
import android.util.AtomicFile
import com.rad.shifttracker.repo.ShiftRepository
import com.rad.shifttracker.repo.getHourlyValueForMonth
import com.rad.shifttracker.repo.loadFromLocal
import com.rad.shifttracker.repo.saveToLocal
import com.rad.shifttracker.repo.setHourlyValueChange
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

/** Lokalni fajl sa podacima: prekinut upis, oštećen i prazan fajl ne smeju da izgube podatke. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LocalStorageRobustnessTest {

    private lateinit var context: Context
    private val storage = LocalShiftStorage()
    private val dataFile get() = File(context.filesDir, LocalShiftStorage.LOCAL_BACKUP_FILE)

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        context.filesDir.listFiles()?.forEach { it.deleteRecursively() }
    }

    @Test
    fun `upis pa citanje vraca isti tekst sa dijakriticima`() {
        assertTrue(storage.save(context, """{"napomena":"čšžćđ"}"""))
        assertEquals("""{"napomena":"čšžćđ"}""", storage.load(context))
    }

    @Test
    fun `prekinut upis ostavlja prethodni sadrzaj`() {
        storage.save(context, "staro")
        val atomic = AtomicFile(dataFile)
        val stream = atomic.startWrite()
        stream.write("polovi".toByteArray())
        stream.close() // proces ubijen pre finishWrite

        assertEquals("staro", storage.load(context))
    }

    @Test
    fun `fajla nema - load je null i nema podataka`() {
        assertNull(storage.load(context))
        assertFalse(storage.hasData(context))
    }

    @Test
    fun `prazan fajl nije podatak i ne ide u karantin`() {
        dataFile.writeText("")
        val repo = ShiftRepository()

        assertFalse(storage.hasData(context))
        assertFalse(repo.loadFromLocal(context))
        assertTrue(quarantined().isEmpty())
    }

    @Test
    fun `osteceni JSON ide u karantin, a original ostaje do sledeceg upisa`() {
        dataFile.writeText("""{"records": [ {"date": "2026-09-""")
        val repo = ShiftRepository()

        assertFalse(repo.loadFromLocal(context))

        val copies = quarantined()
        assertEquals(1, copies.size)
        assertEquals(dataFile.readText(), copies.single().readText())
    }

    @Test
    fun `karantin cuva najvise tri kopije`() {
        repeat(5) { i ->
            dataFile.writeText("nevalja $i")
            assertTrue(storage.quarantineCorrupt(context))
            Thread.sleep(5)
        }
        assertEquals(3, quarantined().size)
    }

    @Test
    fun `stanje snimljeno repozitorijumom se ucitava u novi repozitorijum`() {
        val original = ShiftRepository()
        original.setHourlyValueChange(2026, 9, 512.34)
        assertTrue(original.saveToLocal(context))

        val restored = ShiftRepository()
        assertTrue(restored.loadFromLocal(context))
        assertEquals(512.34, restored.getHourlyValueForMonth(2026, 9), 0.0001)
    }

    private fun quarantined(): List<File> =
        context.filesDir.listFiles { file -> file.name.contains(LocalShiftStorage.CORRUPT_SUFFIX) }.orEmpty().toList()
}
