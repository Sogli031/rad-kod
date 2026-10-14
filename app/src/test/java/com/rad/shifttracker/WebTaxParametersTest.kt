package com.rad.shifttracker

import java.time.LocalDate
import java.time.YearMonth
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import com.rad.shifttracker.listici.ParsedPayslip
import com.rad.shifttracker.listici.PayslipParseResult
import com.rad.shifttracker.listici.parsePayslip
import com.rad.shifttracker.obracun.DatedTaxRelief
import com.rad.shifttracker.obracun.WebTaxParameters
import com.rad.shifttracker.obracun.mergeWebTaxParameters
import com.rad.shifttracker.obracun.ratesApplyTo
import com.rad.shifttracker.obracun.taxReliefFor
import com.rad.shifttracker.repo.savePayslip
import com.rad.shifttracker.repo.webTaxParametersValue
import com.rad.shifttracker.uvoz.collectTaxParameters
import com.rad.shifttracker.uvoz.decodeWebTaxParameters
import com.rad.shifttracker.uvoz.encodeWebTaxParameters

/** Porez i olakšica sa paragraf.rs: važe samo posle poslednje liste, lista ih uvek gazi. */
class WebTaxParametersTest {

    private fun slipFor(month: String, relief: String? = null): ParsedPayslip {
        val lines = requireNotNull(PAYSLIPS_2026[1])
            .map { it.replace("ZA MESEC 01/2026", "ZA MESEC $month") } +
            listOfNotNull(relief?.let { "PORESKA OLAKŠICA $it 0.00 $it" })
        return (parsePayslip(lines) as PayslipParseResult.Success).payslip
    }

    private fun web(
        reliefs: List<DatedTaxRelief> = emptyList(),
        contributions: Double = 19.9,
        seenSince: YearMonth = YearMonth.of(2026, 10)
    ) = WebTaxParameters(
        taxPercent = 10.0,
        contributionsPercent = contributions,
        ratesSeenSince = seenSince,
        reliefs = reliefs,
        fetchedOn = LocalDate.of(2026, 10, 14)
    )

    @Test
    fun `nova olaksica od januara vazi pre nego sto stigne lista`() {
        val repo = testRepository()
        repo.savePayslip(slipFor("11/2026"))
        repo.webTaxParametersValue = web(
            listOf(DatedTaxRelief(LocalDate.of(2026, 1, 1), 34_221.0), DatedTaxRelief(LocalDate.of(2027, 1, 1), 40_000.0))
        )
        val rates = repo.payQueries.taxRates

        // Novembar ima svoju listu; decembar se isplaćuje u januaru 2027, kad važi nova olakšica.
        assertEquals(34_221.0, rates.taxReliefUnsafe(2026, 11), 0.001)
        assertEquals(40_000.0, rates.taxReliefUnsafe(2026, 12), 0.001)
        assertEquals(40_000.0, rates.taxReliefUnsafe(2027, 1), 0.001)

        // Stigne lista za decembar: ona ima prednost i za decembar i za januar (ista godina isplate).
        repo.savePayslip(slipFor("12/2026", relief = "41,000.00"))
        assertEquals(41_000.0, rates.taxReliefUnsafe(2026, 12), 0.001)
        assertEquals(41_000.0, rates.taxReliefUnsafe(2027, 1), 0.001)
    }

    @Test
    fun `olaksica sa interneta ne gazi listu kad je starija od nje`() {
        val repo = testRepository()
        repo.savePayslip(slipFor("11/2026"))
        repo.webTaxParametersValue = web(listOf(DatedTaxRelief(LocalDate.of(2026, 1, 1), 30_000.0)))

        assertEquals(34_221.0, repo.payQueries.taxRates.taxReliefUnsafe(2026, 12), 0.001)
    }

    @Test
    fun `nove stope vaze samo za mesece posle poslednje liste`() {
        val repo = testRepository()
        repo.savePayslip(slipFor("11/2026"))
        val rates = repo.payQueries.taxRates

        // Stope viđene u oktobru su iste kao na listi za novembar: ne menjaju ništa.
        repo.webTaxParametersValue = web(contributions = 21.0, seenSince = YearMonth.of(2026, 10))
        assertEquals(19.9, rates.contributionsPercentUnsafe(2027, 1), 0.001)

        // Promena viđena u januaru 2027: važi za decembar (isplata u januaru) i dalje, ne za novembar.
        repo.webTaxParametersValue = web(contributions = 21.0, seenSince = YearMonth.of(2027, 1))
        assertEquals(19.9, rates.contributionsPercentUnsafe(2026, 11), 0.001)
        assertEquals(21.0, rates.contributionsPercentUnsafe(2026, 12), 0.001)
        assertEquals(21.0, rates.contributionsPercentUnsafe(2027, 1), 0.001)
        assertEquals(10.0, rates.taxPercentUnsafe(2027, 1), 0.001)
    }

    @Test
    fun `pravila po mesecu isplate`() {
        val params = web(listOf(DatedTaxRelief(LocalDate.of(2027, 1, 1), 40_000.0)), seenSince = YearMonth.of(2027, 1))

        assertNull(params.taxReliefFor(YearMonth.of(2026, 12), lastPayslipPayment = YearMonth.of(2026, 12)))
        assertEquals(40_000.0, params.taxReliefFor(YearMonth.of(2027, 1), YearMonth.of(2026, 12))!!, 0.001)
        assertNull(params.taxReliefFor(YearMonth.of(2027, 2), lastPayslipPayment = YearMonth.of(2027, 1)))
        assertTrue(params.ratesApplyTo(YearMonth.of(2027, 1), YearMonth.of(2026, 12)))
        assertTrue(!params.ratesApplyTo(YearMonth.of(2027, 2), YearMonth.of(2027, 1)))
    }

    @Test
    fun `spajanje cuva mesec od kog su stope iste i sve olaksice`() {
        val first = mergeWebTaxParameters(
            previous = null,
            taxPercent = 10.0,
            contributionsPercent = 19.9,
            reliefs = listOf(DatedTaxRelief(LocalDate.of(2026, 1, 1), 34_221.0)),
            today = LocalDate.of(2026, 10, 14)
        )
        val same = mergeWebTaxParameters(first, 10.0, 19.9, listOf(DatedTaxRelief(LocalDate.of(2027, 1, 1), 40_000.0)), LocalDate.of(2026, 12, 1))
        val changed = mergeWebTaxParameters(same, 10.0, 21.0, emptyList(), LocalDate.of(2027, 1, 3))

        assertEquals(YearMonth.of(2026, 10), same.ratesSeenSince)
        assertEquals(2, same.reliefs.size)
        assertEquals(YearMonth.of(2027, 1), changed.ratesSeenSince)
        assertEquals(2, changed.reliefs.size)
        assertEquals(changed, decodeWebTaxParameters(encodeWebTaxParameters(changed)))
    }

    @Test
    fun `cita stope i datirane olaksice iz teksta paragraf rs`() {
        val result = collectTaxParameters(
            mapOf(
                "income_tax_law" to "Zarade iz čl. 13. do 14b ovog zakona oporezuju se po stopi od 10%.",
                "allowance" to """
                    Za isplate od 1. januara 2026. godine povećan je neoporezivi iznos zarade na 34.221 dinara mesečno.
                    Za isplate od 1. januara 2027. godine povećan je neoporezivi iznos zarade na 40.000 dinara mesečno.
                """.trimIndent(),
                "contributions" to """
                    Penzijsko i invalidsko osiguranje - 14% za lica, a 10% za poslodavca.
                    Kada se doprinosi plaćaju istovremeno, zdravstveno osiguranje - 5,15%.
                    Osiguranje za slučaj nezaposlenosti plaća se iz osnovice po stopi od 0,75%.
                """.trimIndent()
            )
        )

        assertEquals(10.0, result.taxPercent, 0.001)
        assertEquals(19.9, result.contributionsPercent, 0.001)
        assertEquals(
            listOf(DatedTaxRelief(LocalDate.of(2026, 1, 1), 34_221.0), DatedTaxRelief(LocalDate.of(2027, 1, 1), 40_000.0)),
            result.reliefs
        )
    }

    @Test
    fun `dve razlicite olaksice za isti datum nisu pouzdane`() {
        val result = runCatching {
            collectTaxParameters(
                mapOf(
                    "income_tax_law" to "Zarade iz čl. 13. do 14b oporezuju se po stopi od 10%.",
                    "allowance" to """
                        Za isplate od 1. januara 2027. godine neoporezivi iznos zarade na 40.000 dinara mesečno.
                        Za isplate od 1. januara 2027. godine neoporezivi iznos zarade na 41.000 dinara mesečno.
                    """.trimIndent(),
                    "contributions" to """
                        Penzijsko i invalidsko osiguranje - 14% za lica, a 10% za poslodavca.
                        Kada se doprinosi plaćaju istovremeno, zdravstveno osiguranje - 5,15%.
                        Osiguranje za slučaj nezaposlenosti plaća se iz osnovice po stopi od 0,75%.
                    """.trimIndent()
                )
            )
        }
        assertTrue(result.isFailure)
    }
}
