package com.rad.shifttracker

import com.rad.shifttracker.data.storage.TextFileStore
import java.time.YearMonth
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import com.rad.shifttracker.listici.GmailPayslipCache
import com.rad.shifttracker.listici.PAYSLIP_PARSER_VERSION
import com.rad.shifttracker.listici.PayslipParseResult
import com.rad.shifttracker.listici.PayslipSideRunKind
import com.rad.shifttracker.listici.parsePayslip
import com.rad.shifttracker.listici.payslipToJson
import com.rad.shifttracker.smene.MonthKey
import com.rad.shifttracker.listici.PayslipPayBaseResult

/** Keš pročitanih listića preživljava ponovno otvaranje, a oštećen ili nedostupan fajl nikad ne ruši aplikaciju. */
class GmailPayslipCacheStoreTest {

    private class MemoryStore(var text: String? = null) : TextFileStore {
        var writes = 0
        var failWrites = false
        var throwOnRead = false
        var throwOnWrite = false

        override fun read(): String? {
            check(!throwOnRead) { "čitanje ne uspeva" }
            return text
        }

        override fun write(text: String): Boolean {
            check(!throwOnWrite) { "upis ne uspeva" }
            if (failWrites) return false
            this.text = text
            writes++
            return true
        }
    }

    private val today = { YearMonth.of(2026, 10) }
    private val payslip = parsePayslip(requireNotNull(PAYSLIPS_2026[8])) as PayslipParseResult.Success
    private val sideRun = PayslipParseResult.SideRun(PayslipSideRunKind.REGRESS, MonthKey(2026, 8), null, java.math.BigDecimal("20000.00"))

    private fun open(store: MemoryStore, stamp: Long = 7L, month: () -> YearMonth = today) =
        GmailPayslipCache.withStore(store, appInstallStamp = stamp, currentMonth = month)

    private fun filled(store: MemoryStore): GmailPayslipCache = open(store).also { cache ->
        cache.rememberNames("m1", listOf("a.pdf", "b.pdf"))
        cache.remember("m1", "a.pdf", payslip)
        cache.remember("m1", "b.pdf", sideRun)
    }

    @Test
    fun `keš se pamti u skladistu i vraca pri ponovnom otvaranju`() {
        val store = MemoryStore()
        filled(store)

        val reopened = open(store)

        val results = reopened.results("m1")
        assertNotNull(results)
        assertEquals(listOf("a.pdf", "b.pdf"), results!!.map { it.first })
        assertEquals(payslipToJson(payslip.payslip), payslipToJson((results[0].second as PayslipParseResult.Success).payslip))
        assertEquals(sideRun, results[1].second)
    }

    @Test
    fun `ostecen keš se ignorise bez izuzetka i pri sledecem upisu se zamenjuje ispravnim`() {
        val corrupt = listOf(
            "", "   ", "{", "[]", "null", "42", "ovo nije JSON", "{\"version\": 4}", "{\"messages\": 5}",
            "{\"version\": \"x\", \"install\": [], \"messages\": []}"
        )
        for (text in corrupt) {
            val store = MemoryStore(text)
            val cache = open(store)
            assertNull(text, cache.results("m1"))

            cache.rememberNames("m1", listOf("a.pdf"))
            cache.remember("m1", "a.pdf", sideRun)
            assertEquals(text, listOf("a.pdf" to sideRun), open(store).results("m1"))
        }
    }

    @Test
    fun `prekinut upis na bilo kom mestu ne ruši otvaranje i nikad ne daje polovicnu poruku`() {
        val store = MemoryStore()
        filled(store)
        val full = store.text!!

        for (length in 0 until full.length step 7) {
            val reopened = open(MemoryStore(full.substring(0, length)))
            val results = reopened.results("m1")
            // Ili cela poruka sa svim prilozima, ili ništa: poruka sa polovinom priloga se ponovo preuzima.
            assertTrue("prefiks $length", results == null || results.size == 2)
        }
        assertNotNull(open(MemoryStore(full)).results("m1"))
    }

    @Test
    fun `keš druge verzije parsera ili druge instalacije se odbacuje`() {
        val store = MemoryStore()
        filled(store)

        assertNull(open(store, stamp = 8L).results("m1"))
        assertNotNull(open(store, stamp = 7L).results("m1"))

        val otherParser = store.text!!.replace("\"version\":${PAYSLIP_PARSER_VERSION}", "\"version\":${PAYSLIP_PARSER_VERSION + 1}")
        assertNull(open(MemoryStore(otherParser)).results("m1"))
    }

    @Test
    fun `stari obracuni se brisu iz kesa po zadatom tekucem mesecu`() {
        val store = MemoryStore()
        filled(store)

        // Obračun za 08/2026 se čuva 30 meseci: do 02/2029 ostaje, od 03/2029 otpada.
        assertNotNull(open(store, month = { YearMonth.of(2029, 2) }).results("m1"))
        assertNull(open(store, month = { YearMonth.of(2029, 3) }).results("m1"))
    }

    @Test
    fun `neuspeo upis ne ruši keš i podaci ostaju u memoriji`() {
        val store = MemoryStore().apply { failWrites = true }
        val cache = open(store)

        cache.rememberNames("m1", listOf("a.pdf"))
        cache.remember("m1", "a.pdf", sideRun)

        assertEquals(0, store.writes)
        assertEquals(listOf("a.pdf" to sideRun), cache.results("m1"))
    }

    @Test
    fun `izuzetak pri upisu i citanju skladista se guta`() {
        val store = MemoryStore().apply { throwOnWrite = true }
        val cache = open(store)
        cache.rememberNames("m1", listOf("a.pdf"))
        cache.remember("m1", "a.pdf", sideRun)
        assertEquals(listOf("a.pdf" to sideRun), cache.results("m1"))

        val unreadable = MemoryStore("{}").apply { throwOnRead = true }
        assertNull(open(unreadable).results("m1"))
    }

    @Test
    fun `isto skladiste ne pravi nepotreban upis kad se nista ne menja`() {
        val store = MemoryStore()
        val cache = open(store)
        cache.rememberNames("m1", listOf("a.pdf"))
        val afterNames = store.writes
        cache.rememberNames("m1", listOf("a.pdf"))
        assertEquals(afterNames, store.writes)

        // PDF koji nije lista se ne pamti, pa ni ne upisuje.
        cache.remember("m1", "a.pdf", PayslipParseResult.Failure(PayslipPayBaseResult.NoText))
        assertEquals(afterNames, store.writes)
        assertNull(cache.results("m1"))
    }
}
