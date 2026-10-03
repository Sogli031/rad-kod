package com.rad.shifttracker

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class KnownPayBaseAnchorsTest {
    @Test
    fun `svaki mesec koristi osnovicu sa poslednjeg listica koji nije posle njega`() {
        val repo = testRepository()
        // Osnovice sa listića 05, 07 i 08/2026.
        repo.setKnownPayBaseAnchor(2026, 5, 950.58)
        repo.setKnownPayBaseAnchor(2026, 7, 945.60)
        repo.setKnownPayBaseAnchor(2026, 8, 954.24)
        repo.setDefaultPayBaseSelection(PayBaseSelectionMode.KNOWN_BASE)

        assertEquals(950.58, vacationBase(repo, 2026, 5), 0.001)
        // Jun nema svoj listić sa osnovicom: prenosi se majska.
        assertEquals(950.58, vacationBase(repo, 2026, 6), 0.001)
        assertEquals(945.60, vacationBase(repo, 2026, 7), 0.001)
        assertEquals(954.24, vacationBase(repo, 2026, 8), 0.001)
        // Septembar i oktobar nemaju unos, pa nose avgustovsku osnovicu umesto stare majske.
        assertEquals(954.24, vacationBase(repo, 2026, 9), 0.001)
        assertEquals(954.24, vacationBase(repo, 2026, 10), 0.001)
        assertEquals(PayBaseSource.KNOWN_BASE, repo.getResolvedPayBasesForWorkMonth(2026, 9).vacationHourlyBase.source)
    }

    @Test
    fun `mesec pre prvog listica uzima prvu kasniju osnovicu umesto podrazumevane`() {
        val repo = testRepository()
        // Učitani samo listići 07 i 08/2026; jun i ranije nemaju svoju osnovicu.
        repo.setKnownPayBaseAnchor(2026, 7, 945.60)
        repo.setKnownPayBaseAnchor(2026, 8, 954.24)
        repo.setDefaultPayBaseSelection(PayBaseSelectionMode.KNOWN_BASE)

        assertEquals(945.60, vacationBase(repo, 2026, 6), 0.001)
        assertEquals(945.60, vacationBase(repo, 2026, 1), 0.001)
        assertEquals(PayBaseSource.KNOWN_BASE, repo.getResolvedPayBasesForWorkMonth(2026, 6).vacationHourlyBase.source)
        assertEquals(945.60, vacationBase(repo, 2026, 7), 0.001)
        assertEquals(954.24, vacationBase(repo, 2026, 9), 0.001)
    }

    @Test
    fun `sidro po mesecu i lista sidara`() {
        val repo = testRepository()
        repo.setKnownPayBaseAnchor(2026, 8, 954.24)
        repo.setKnownPayBaseAnchor(2026, 5, 950.58)

        assertEquals(
            listOf(
                KnownPayBaseAnchor(MonthKey(2026, 5), 950.58),
                KnownPayBaseAnchor(MonthKey(2026, 8), 954.24)
            ),
            repo.getKnownPayBaseAnchors()
        )
        assertEquals(KnownPayBaseAnchor(MonthKey(2026, 8), 954.24), repo.getKnownPayBaseAnchor())
        // Pre prvog listića važi prvo kasnije sidro (majsko), ne podrazumevana osnovica.
        assertEquals(KnownPayBaseAnchor(MonthKey(2026, 5), 950.58), repo.getKnownPayBaseAnchorForWorkMonth(2026, 4))
        assertEquals(950.58, repo.getKnownPayBaseAnchorForWorkMonth(2026, 7)!!.grossHourlyBase, 0.001)
        assertEquals(954.24, repo.getKnownPayBaseAnchorForWorkMonth(2027, 1)!!.grossHourlyBase, 0.001)
    }

    @Test
    fun `ponovni unos za isti mesec menja osnovicu a brisanje poslednje vraca racunati rezim`() {
        val repo = testRepository()
        repo.setKnownPayBaseAnchor(2026, 8, 950.0)
        repo.setKnownPayBaseAnchor(2026, 8, 954.24)
        repo.setDefaultPayBaseSelection(PayBaseSelectionMode.KNOWN_BASE)

        assertEquals(1, repo.getKnownPayBaseAnchors().size)
        assertEquals(954.24, vacationBase(repo, 2026, 8), 0.001)

        repo.removeKnownPayBaseAnchor(2026, 8)

        assertTrue(repo.getKnownPayBaseAnchors().isEmpty())
        assertEquals(PayBaseSelectionMode.CALCULATED_PREVIOUS_12_MONTHS, repo.defaultPayBaseSelectionModeValue)
    }

    @Test
    fun `sva sidra prezive export import a stari kljucevi nose najnovije`() {
        val repo = testRepository()
        repo.setKnownPayBaseAnchor(2026, 5, 950.58)
        repo.setKnownPayBaseAnchor(2026, 8, 954.24)
        repo.setDefaultPayBaseSelection(PayBaseSelectionMode.KNOWN_BASE)

        val exported = JSONObject(repo.exportJson())
        val anchors = exported.getJSONObject("known_pay_base_anchors")
        assertEquals(950.58, anchors.getDouble("2026-05"), 0.001)
        assertEquals(954.24, anchors.getDouble("2026-08"), 0.001)
        assertEquals("2026-08", exported.getString("known_pay_base_month"))
        assertEquals(954.24, exported.getDouble("known_pay_base_gross_hourly"), 0.001)

        val restored = testRepository()
        assertTrue(restored.importFromJson(exported.toString()))
        assertEquals(repo.getKnownPayBaseAnchors(), restored.getKnownPayBaseAnchors())
        assertEquals(PayBaseSelectionMode.KNOWN_BASE, restored.defaultPayBaseSelectionModeValue)
    }

    @Test
    fun `stari bekap sa jednim sidrom se i dalje ucitava`() {
        val legacy = JSONObject()
            .put("default_pay_base_selection_mode", "KNOWN_BASE")
            .put("known_pay_base_month", "2026-05")
            .put("known_pay_base_gross_hourly", 950.58)

        val restored = testRepository()
        assertTrue(restored.importFromJson(legacy.toString()))

        assertEquals(listOf(KnownPayBaseAnchor(MonthKey(2026, 5), 950.58)), restored.getKnownPayBaseAnchors())
        assertEquals(PayBaseSelectionMode.KNOWN_BASE, restored.defaultPayBaseSelectionModeValue)
    }

    @Test
    fun `tekstovi i parsiranje unosa poznate osnovice`() {
        assertEquals(
            "Avgust 2026: 954,24 RSD/h",
            knownPayBaseAnchorSummaryText(KnownPayBaseAnchor(MonthKey(2026, 8), 954.24))
        )
        assertEquals(
            KnownPayBaseAnchor(MonthKey(2026, 8), 954.24),
            parseKnownPayBaseAnchorInput("2026", "8", "954.24")
        )
        assertNull(parseKnownPayBaseAnchorInput("2026", "13", "954.24"))
        assertNull(parseKnownPayBaseAnchorInput("2026", "8", ""))
        assertNull(parseKnownPayBaseAnchorInput("", "8", "954.24"))
    }

    @Test
    fun `upozorenje za simulirane mesece se javlja samo kad ih ima`() {
        fun calculation(simulated: Int) = RollingPayBaseCalculation(
            hourlyBase = 809.28,
            grossTotal = 1_689_766.0,
            hoursTotal = 2_088,
            months = (1..12).map { month ->
                RollingPayBaseMonth(
                    year = 2025,
                    month = month,
                    grossForAverage = 140_000.0,
                    hoursForAverage = 174,
                    source = if (month <= simulated) PayBaseMonthSource.SIMULATED else PayBaseMonthSource.REAL
                )
            }
        )

        assertNull(simulatedPayBaseMonthsWarningText(null))
        assertNull(simulatedPayBaseMonthsWarningText(calculation(simulated = 0)))
        assertTrue(
            simulatedPayBaseMonthsWarningText(calculation(simulated = 10))!!
                .startsWith("Upozorenje: 10 od 12 meseci u proseku je simulirano")
        )
    }

    private fun vacationBase(repo: ShiftRepository, year: Int, month: Int): Double {
        return repo.getResolvedPayBasesForWorkMonth(year, month).vacationHourlyBase.value
    }
}
