package com.rad.shifttracker

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsDialogTextTest {

    @Test
    fun `satnica dialog uses coefficient wording`() {
        assertEquals("Koeficijent", SATNICA_COEFFICIENT_LABEL)
        assertEquals("Vrednost sata", SATNICA_HOURLY_VALUE_LABEL)
        assertEquals("Otežani uslovi rada (%)", SATNICA_DIFFICULT_WORK_CONDITIONS_LABEL)
        assertEquals("Prevoz po danu", SATNICA_TRANSPORT_DAILY_AMOUNT_LABEL)
        assertEquals("Unesi iznos prevoza po radnom danu", SATNICA_TRANSPORT_DAILY_AMOUNT_HINT)
    }

    @Test
    fun `satnica gross preview shows coefficient times hourly value`() {
        assertEquals(
            "Bruto satnica: 637.32 RSD/h",
            satnicaGrossPreviewText(2.82, 226.0)
        )
    }

    @Test
    fun `satnica net difference preview shows signed salary difference`() {
        assertEquals(
            "Razlika u plati: +1.450 RSD",
            satnicaNetDifferencePreviewText(1_450.0)
        )
        assertEquals(
            "Razlika u plati: -1.450 RSD",
            satnicaNetDifferencePreviewText(-1_450.0)
        )
    }

    @Test
    fun `satnica dialog exposes hourly value history wording`() {
        assertEquals("Promene vrednosti sata", SATNICA_HOURLY_HISTORY_SECTION_LABEL)
        assertEquals("Od meseca", SATNICA_HOURLY_HISTORY_MONTH_LABEL)
        assertEquals("Godina", SATNICA_HOURLY_HISTORY_YEAR_LABEL)
        assertEquals("Nova vrednost sata", SATNICA_HOURLY_HISTORY_VALUE_LABEL)
    }

    @Test
    fun `base pay dialog keeps visual sections extracted`() {
        val source = basePayDialogSource()

        assertTrue(source.contains("BasePaySectionCard("))
        assertTrue(source.contains("BasePayPreviewStrip("))
    }

    @Test
    fun `decimal input accepts comma and normalizes it for parsing`() {
        assertEquals("917.81", filterDecimalInput("917,81"))
    }

    @Test
    fun `satnica hourly change summary states effective month`() {
        assertEquals(
            "Od januar 2026: 226 RSD",
            satnicaHourlyChangeSummaryText(HourlyValueChange(2026, 1, 226.0))
        )
    }

    @Test
    fun `known pay base source summary lists anchor and real months only`() {
        val calculation = RollingPayBaseCalculation(
            hourlyBase = 955.0,
            grossTotal = 458_400.0,
            hoursTotal = 480,
            months = listOf(
                RollingPayBaseMonth(
                    year = 2026,
                    month = 1,
                    grossForAverage = 917.81 * 168,
                    hoursForAverage = 168,
                    source = PayBaseMonthSource.KNOWN_BASE
                ),
                RollingPayBaseMonth(
                    year = 2026,
                    month = 2,
                    grossForAverage = 160_000.0,
                    hoursForAverage = 160,
                    source = PayBaseMonthSource.REAL
                ),
                RollingPayBaseMonth(
                    year = 2026,
                    month = 3,
                    grossForAverage = 144_000.0,
                    hoursForAverage = 144,
                    source = PayBaseMonthSource.REAL
                )
            )
        )

        val bases = ResolvedPayBases.fromCalculation(calculation, PayBaseSource.KNOWN_BASE)

        assertEquals(
            "Izvor osnovice: poznata osnovica iz Januara 2026 + obračun za Februar i Mart.",
            payBasesSourceSummary(bases)
        )
        assertEquals(
            "Osnovica se računa od poznatog sidra: Januar 2026 = 917,81 RSD/h. " +
                "Meseci pre sidra se ne skeniraju i ne simuliraju. " +
                "Prazni meseci posle sidra koriste poslednju poznatu osnovicu, " +
                "a kompletno upisani realni meseci posle sidra ulaze u dalji obračun. " +
                "Realni meseci posle sidra: 2; preneti prazni meseci: 0.",
            knownPayBaseSourceDetailsText(calculation)
        )
    }

    @Test
    fun `known pay base source details names carry forward without fallback wording`() {
        val calculation = RollingPayBaseCalculation(
            hourlyBase = 940.0,
            grossTotal = 450_000.0,
            hoursTotal = 480,
            months = listOf(
                RollingPayBaseMonth(
                    year = 2026,
                    month = 3,
                    grossForAverage = 918.81 * 176,
                    hoursForAverage = 176,
                    source = PayBaseMonthSource.KNOWN_BASE
                ),
                RollingPayBaseMonth(
                    year = 2026,
                    month = 4,
                    grossForAverage = 918.81 * 176,
                    hoursForAverage = 176,
                    source = PayBaseMonthSource.valueOf("CARRY_FORWARD")
                ),
                RollingPayBaseMonth(
                    year = 2026,
                    month = 5,
                    grossForAverage = 180_000.0,
                    hoursForAverage = 176,
                    source = PayBaseMonthSource.REAL
                )
            )
        )

        val text = knownPayBaseSourceDetailsText(calculation)

        assertEquals(
            "Osnovica se računa od poznatog sidra: Mart 2026 = 918,81 RSD/h. " +
                "Meseci pre sidra se ne skeniraju i ne simuliraju. " +
                "Prazni meseci posle sidra koriste poslednju poznatu osnovicu, " +
                "a kompletno upisani realni meseci posle sidra ulaze u dalji obračun. " +
                "Realni meseci posle sidra: 1; preneti prazni meseci: 1.",
            text
        )
    }

    @Test
    fun `calculated pay base text names selected month`() {
        assertEquals(
            "Izračunata osnovica za Jun 2026: 955,00 RSD/h",
            calculatedPayBaseForMonthText(2026, 6, 955.0)
        )
    }

    @Test
    fun `rolling pay base source summary shows fallback simulation count`() {
        val calculation = RollingPayBaseCalculation(
            hourlyBase = 1_000.0,
            grossTotal = 1_920_000.0,
            hoursTotal = 1_920,
            months = (1..12).map { month ->
                RollingPayBaseMonth(
                    year = 2025,
                    month = month,
                    grossForAverage = 160_000.0,
                    hoursForAverage = 160,
                    source = if (month <= 3) PayBaseMonthSource.SIMULATED else PayBaseMonthSource.REAL
                )
            }
        )
        val bases = ResolvedPayBases.fromCalculation(calculation)

        assertEquals(
            "Izvor osnovice naknada: 12 meseci, fallback simulacija za 3 meseca",
            payBasesSourceSummary(bases)
        )
        assertEquals(
            "Fallback simulacija: realni meseci: 9; simulirani meseci: 3",
            rollingPayBaseSourceDetailsText(calculation)
        )
    }

    private fun basePayDialogSource(): String {
        val candidates = listOf(
            File("src/main/java/com/rad/shifttracker/BasePayDialog.kt"),
            File("app/src/main/java/com/rad/shifttracker/BasePayDialog.kt")
        )
        val sourceFile = candidates.firstOrNull { it.isFile }
        requireNotNull(sourceFile) { "BasePayDialog.kt source file was not found." }
        return sourceFile.readText()
    }
}
