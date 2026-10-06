package com.rad.shifttracker

import java.io.ByteArrayInputStream
import java.math.BigDecimal
import kotlin.coroutines.cancellation.CancellationException
import kotlin.random.Random
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import com.rad.shifttracker.listici.PayslipParseResult
import com.rad.shifttracker.listici.parsePayslip
import com.rad.shifttracker.listici.payslipFromJsonOrNull
import com.rad.shifttracker.listici.payslipMealRow
import com.rad.shifttracker.listici.readAtMost
import com.rad.shifttracker.listici.runPayslipSafely
import com.rad.shifttracker.listici.toDisplayDecimal
import com.rad.shifttracker.repo.exportCloudBackupJson
import com.rad.shifttracker.repo.getSavedPayslip
import com.rad.shifttracker.repo.importFromJson
import com.rad.shifttracker.repo.savePayslip
import com.rad.shifttracker.listici.PayslipPayBaseResult
import com.rad.shifttracker.listici.buildPayslipImportPlan
import com.rad.shifttracker.listici.fillTargetsFrom
import com.rad.shifttracker.listici.parsePayslipPayBase
import com.rad.shifttracker.listici.payslipImportSummaryText
import com.rad.shifttracker.listici.payslipToJson

/** Linija parsera ne sme da pukne ni na oštećenom PDF tekstu, ni na oštećenom backup-u. */
class PayslipRobustnessTest {

    private val samples: List<List<String>> = PAYSLIPS_2026.values.toList() + listOf(AUGUST_2026)

    /** Nasumično oštećen listić: obrisani, udvojeni, zamenjeni znakovi i redovi. */
    private fun mutate(lines: List<String>, random: Random): List<String> {
        val out = lines.toMutableList()
        repeat(random.nextInt(1, 8)) {
            if (out.isEmpty()) return out
            val index = random.nextInt(out.size)
            val line = out[index]
            out[index] = when (random.nextInt(7)) {
                0 -> ""
                1 -> line + " " + line
                2 -> if (line.isEmpty()) line else line.removeRange(random.nextInt(line.length), line.length)
                3 -> line.map { if (it.isDigit()) '0' + random.nextInt(10) else it }.joinToString("")
                4 -> line.replace('.', ',')
                5 -> line.replace(" ", "")
                else -> line.map { if (random.nextInt(10) == 0) "-9" else it.toString() }.joinToString("")
            }
            if (random.nextInt(5) == 0) out.add(random.nextInt(out.size + 1), out[random.nextInt(out.size)])
        }
        return out
    }

    @Test
    fun damagedPayslipTextNeverThrowsAnywhereInThePipeline() {
        val random = Random(20260924)
        val repo = testRepository()
        var parsedCount = 0
        repeat(1_500) { iteration ->
            val lines = mutate(samples[iteration % samples.size], random)
            val result = try {
                parsePayslip(lines)
            } catch (e: Exception) {
                fail("parsePayslip pukao u iteraciji $iteration: $e\n${lines.joinToString("\n")}")
                return
            }
            val payslip = (result as? PayslipParseResult.Success)?.payslip ?: return@repeat
            parsedCount++
            try {
                buildPayslipImportPlan(repo, listOf(payslip), emptyList())
                payslipImportSummaryText(payslip)
                val restored = payslipFromJsonOrNull(payslipToJson(payslip))
                assertNotNull("sačuvan listić mora da se vrati iz JSON-a", restored)
                if (payslip.payBase != restored!!.payBase) {
                    fail(
                        "osnovica posle čuvanja: ${payslip.payBase} → ${restored.payBase}\n" +
                            lines.filter { it.matches(Regex("""^\d{1,3}\s.*""")) }.joinToString("\n")
                    )
                }
                fillTargetsFrom(payslip)
            } catch (e: Exception) {
                fail("obrada listića pukla u iteraciji $iteration: $e\n${lines.joinToString("\n")}")
            }
        }
        assertTrue("fuzz mora da prođe i kroz uspešno pročitane listiće ($parsedCount)", parsedCount > 300)
    }

    @Test
    fun overlongAndExcessLinesAreIgnoredWithoutChangingTheResult() {
        val clean = parsePayslip(AUGUST_2026) as PayslipParseResult.Success
        val junk = listOf("9".repeat(100_000), "01 Rad u reziji " + "1".repeat(5_000) + ".0 5")
        val noisy = parsePayslip(junk + AUGUST_2026) as PayslipParseResult.Success
        assertEquals(clean.payslip, noisy.payslip)
        // Stotine hiljada praznih i smeće-redova pre listića: brzo i bez pada (listić je posle granice).
        val flood = List(300_000) { "x" } + AUGUST_2026
        assertEquals(PayslipPayBaseResult.NoMonth, parsePayslipPayBase(flood))
    }

    @Test
    fun payBaseStaysTheSameAfterSavingWhenACodeRepeats() {
        // Prva stavka 40 nema procenat (ne može biti osnovica); aplikacija svuda koristi prvu stavku po šifri.
        val lines = listOf(
            "OBRAČUN ZARADE ZA MESEC 03/2026 rb.3/konačna",
            "40 Prekovremeni rad 4.0 4302.08",
            "40 Bolovanje za tekuci mesec 40.0 65 23888.93"
        )
        val scanned = (parsePayslip(lines) as PayslipParseResult.Success).payslip
        val restored = payslipFromJsonOrNull(payslipToJson(scanned))
        assertEquals(scanned.payBase, restored?.payBase)
        assertEquals(BigDecimal("4.0"), scanned.items[40]?.hours)
    }

    @Test
    fun implausibleYearIsTreatedAsMissingMonth() {
        assertEquals(PayslipPayBaseResult.NoMonth, parsePayslipPayBase(listOf("ZA MESEC 08/0000", "10 Godisnji odmor 16.0 15267.88")))
        assertEquals(PayslipPayBaseResult.NoMonth, parsePayslipPayBase(listOf("ZA MESEC 08/9999")))
        assertEquals(PayslipPayBaseResult.NoMonth, parsePayslipPayBase(listOf("ZA MESEC 13/2026")))
    }

    @Test
    fun corruptedSavedPayslipJsonIsRejectedInsteadOfCrashingLater() {
        val good = payslipToJson((parsePayslip(AUGUST_2026) as PayslipParseResult.Success).payslip)
        assertNotNull(payslipFromJsonOrNull(good))
        assertNull(payslipFromJsonOrNull(good.replace("\"month\":8", "\"month\":13")))
        assertNull(payslipFromJsonOrNull(good.replace("\"year\":2026", "\"year\":-1")))
        assertNull(payslipFromJsonOrNull("{"))
        assertNull(payslipFromJsonOrNull("[]"))
        assertNull(payslipFromJsonOrNull("""{"year":2026,"month":8,"items":[{"code":"x"}]}"""))
    }

    @Test
    fun safeRunnerTurnsErrorsIntoNull() {
        assertNull(runPayslipSafely<Int> { throw IllegalStateException() })
        assertNull(runPayslipSafely<Int> { throw StackOverflowError() })
        assertEquals(7, runPayslipSafely { 7 })
    }

    @Test
    fun safeRunnerSurvivesOutOfMemoryButKeepsCancellation() {
        assertNull(runPayslipSafely<Int> { throw OutOfMemoryError() })
        try {
            runPayslipSafely<Int> { throw CancellationException("otkazano") }
            fail("otkazivanje korutine ne sme da se proguta")
        } catch (expected: CancellationException) {
            // očekivano
        }
    }

    @Test
    fun pdfLargerThanLimitIsNotRead() {
        val bytes = ByteArray(10_000) { it.toByte() }
        assertArrayEquals(bytes, readAtMost(ByteArrayInputStream(bytes), 10_000))
        assertNull(readAtMost(ByteArrayInputStream(bytes), 9_999))
    }

    @Test
    fun nonFiniteAppValuesAreShownInsteadOfCrashing() {
        val row = payslipMealRow(Double.NaN, BigDecimal("6191.16"), null)
        assertEquals("0,00", row.appText)
        assertEquals(BigDecimal.ZERO, Double.POSITIVE_INFINITY.toDisplayDecimal())
    }

    @Test
    fun savedPayslipUnderWrongMonthKeyIsNotShown() {
        val source = testRepository()
        val august = (parsePayslip(AUGUST_2026) as PayslipParseResult.Success).payslip
        source.savePayslip(august)
        assertEquals(august.month, source.getSavedPayslip(2026, 8)?.month)

        // Oštećen backup: avgustovski listić pod ključem jula.
        val backup = JSONObject(source.exportCloudBackupJson(null))
        val saved = backup.getJSONObject("saved_payslips")
        saved.put("2026-07", saved.getJSONObject("2026-08"))
        saved.remove("2026-08")
        val restored = testRepository()
        assertTrue(restored.importFromJson(backup.toString()))
        assertNull("jul ne sme da prikaže avgustovski listić", restored.getSavedPayslip(2026, 7))
    }
}
