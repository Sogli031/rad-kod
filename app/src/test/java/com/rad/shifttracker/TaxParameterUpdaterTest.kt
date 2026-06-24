package com.rad.shifttracker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class TaxParameterUpdaterTest {

    @Test
    fun `parsira poreske parametre iz tekstova`() {
        val pages = mapOf(
            "income_tax_law" to """
                Zarade iz čl. 13. do 14b ovog zakona oporezuju se po stopi od 10%.
            """.trimIndent(),
            "allowance" to """
                Neoporezivi iznos zarade utvrđuje se od 1. januara 2026. godine na 34.221 dinara mesečno.
            """.trimIndent(),
            "contributions" to """
                Za obavezno penzijsko i invalidsko osiguranje - 24%.
                Penzijsko i invalidsko osiguranje - 14% za lica, a 10% za poslodavca.
                Za obavezno zdravstveno osiguranje - 10,3%.
                Kada se doprinosi plaćaju istovremeno, zdravstveno osiguranje - 5,15%.
                Osiguranje za slučaj nezaposlenosti plaća se iz osnovice po stopi od 0,75%.
            """.trimIndent()
        )

        val result = collectTaxParameters(pages, asOf = LocalDate.of(2026, 5, 9))

        assertEquals(10.0, result.salaryTaxRatePercent, 0.01)
        assertEquals(34_221.0, result.salaryTaxAllowanceRsd, 0.01)
        assertEquals(14.0, result.pioEmployeePercent, 0.01)
        assertEquals(5.15, result.healthEmployeePercent, 0.01)
        assertEquals(0.75, result.unemploymentEmployeePercent, 0.01)
        assertEquals(19.9, result.employeeContributionsPercent, 0.01)
    }

    @Test
    fun `normalizuje html i procente sa zarezom`() {
        val text = htmlToTextForTaxParameters(
            """
            <html>
              <head><style>.hidden { display: none; }</style></head>
              <body>
                <script>ignored()</script>
                Porez na zaradu plaća se po stopi od 10,5%.
              </body>
            </html>
            """.trimIndent()
        )

        val value = extractSalaryTaxRatePercent(text)

        assertEquals(10.5, value, 0.01)
    }

    @Test
    fun `uzima neoporezivi iznos koji vazi za trazeni datum umesto najveceg kandidata`() {
        val pages = mapOf(
            "income_tax_law" to "Zarade iz čl. 13. do 14b oporezuju se po stopi od 10%.",
            "allowance" to """
                Za isplate od 1. januara 2024. godine povećan je neoporezivi iznosa zarade na 25.000 dinara mesečno.
                Za isplate od 1. januara 2026. godine povećan je neoporezivi iznosa zarade na 34.221 dinara mesečno.
                Za isplate od 1. januara 2027. godine povećan je neoporezivi iznosa zarade na 40.000 dinara mesečno.
            """.trimIndent(),
            "contributions" to """
                Penzijsko i invalidsko osiguranje - 14% za lica, a 10% za poslodavca.
                Kada se doprinosi plaćaju istovremeno, zdravstveno osiguranje - 5,15%.
                Osiguranje za slučaj nezaposlenosti plaća se iz osnovice po stopi od 0,75%.
            """.trimIndent()
        )

        val result = collectTaxParameters(pages, asOf = LocalDate.of(2025, 5, 9))

        assertEquals(25_000.0, result.salaryTaxAllowanceRsd, 0.01)
    }

    @Test
    fun `ne bira poresku olaksicu ako nema datirani kandidat za trazeni datum`() {
        val result = runCatching {
            collectTaxParameters(
                mapOf(
                    "income_tax_law" to "Zarade iz čl. 13. do 14b oporezuju se po stopi od 10%.",
                    "allowance" to """
                        Za isplate od 1. januara 2027. godine povećan je neoporezivi iznosa zarade na 40.000 dinara mesečno.
                    """.trimIndent(),
                    "contributions" to """
                        Penzijsko i invalidsko osiguranje - 14% za lica, a 10% za poslodavca.
                        Kada se doprinosi plaćaju istovremeno, zdravstveno osiguranje - 5,15%.
                        Osiguranje za slučaj nezaposlenosti plaća se iz osnovice po stopi od 0,75%.
                    """.trimIndent()
                ),
                asOf = LocalDate.of(2026, 5, 9)
            )
        }

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()?.message.orEmpty().contains("nije pouzdano utvrđen"))
    }

    @Test
    fun `ne bira poresku olaksicu iz nedatiranog iznosa`() {
        val result = runCatching {
            collectTaxParameters(
                mapOf(
                    "income_tax_law" to "Zarade iz čl. 13. do 14b oporezuju se po stopi od 10%.",
                    "allowance" to "Neoporezivi iznos zarade utvrđuje se na 34.221 dinara mesečno.",
                    "contributions" to """
                        Penzijsko i invalidsko osiguranje - 14% za lica, a 10% za poslodavca.
                        Kada se doprinosi plaćaju istovremeno, zdravstveno osiguranje - 5,15%.
                        Osiguranje za slučaj nezaposlenosti plaća se iz osnovice po stopi od 0,75%.
                    """.trimIndent()
                ),
                asOf = LocalDate.of(2026, 5, 9)
            )
        }

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()?.message.orEmpty().contains("nije pouzdano utvrđen"))
    }

    @Test
    fun `ne bira stopu poreza ako izvor sadrzi razlicite kandidate`() {
        val result = runCatching {
            collectTaxParameters(
                mapOf(
                    "income_tax_law" to """
                        Zarade iz čl. 13. do 14b ovog zakona oporezuju se po stopi od 10%.
                        Porez na zaradu plaća se po stopi od 12%.
                    """.trimIndent(),
                    "allowance" to """
                        Neoporezivi iznos zarade utvrđuje se od 1. januara 2026. godine na 34.221 dinara mesečno.
                    """.trimIndent(),
                    "contributions" to """
                        Penzijsko i invalidsko osiguranje - 14% za lica, a 10% za poslodavca.
                        Kada se doprinosi plaćaju istovremeno, zdravstveno osiguranje - 5,15%.
                        Osiguranje za slučaj nezaposlenosti plaća se iz osnovice po stopi od 0,75%.
                    """.trimIndent()
                ),
                asOf = LocalDate.of(2026, 5, 9)
            )
        }

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()?.message.orEmpty().contains("stopa poreza na zarade"))
        assertTrue(result.exceptionOrNull()?.message.orEmpty().contains("nije pouzdano utvrđeno"))
    }

    @Test
    fun `ne bira doprinose ako izvor sadrzi razlicite kandidate`() {
        val result = runCatching {
            collectTaxParameters(
                mapOf(
                    "income_tax_law" to "Zarade iz čl. 13. do 14b oporezuju se po stopi od 10%.",
                    "allowance" to """
                        Neoporezivi iznos zarade utvrđuje se od 1. januara 2026. godine na 34.221 dinara mesečno.
                    """.trimIndent(),
                    "contributions" to """
                        Penzijsko i invalidsko osiguranje - 14% za lica, a 10% za poslodavca.
                        Penzijsko i invalidsko osiguranje - 15% za lica, a 9% za poslodavca.
                        Kada se doprinosi plaćaju istovremeno, zdravstveno osiguranje - 5,15%.
                        Osiguranje za slučaj nezaposlenosti plaća se iz osnovice po stopi od 0,75%.
                    """.trimIndent()
                ),
                asOf = LocalDate.of(2026, 5, 9)
            )
        }

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()?.message.orEmpty().contains("podela PIO doprinosa"))
        assertTrue(result.exceptionOrNull()?.message.orEmpty().contains("nije pouzdano utvrđeno"))
    }

    @Test
    fun `vraca gresku kada obavezno polje nedostaje`() {
        val result = runCatching {
            collectTaxParameters(
                mapOf(
                    "income_tax_law" to "nema stope",
                    "allowance" to "nema olakšice",
                    "contributions" to "nema doprinosa"
                )
            )
        }

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()?.message.orEmpty().contains("Nije pronađeno"))
    }
}
