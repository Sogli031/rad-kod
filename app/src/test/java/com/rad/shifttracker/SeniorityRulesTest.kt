package com.rad.shifttracker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path

class SeniorityRulesTest {

    @Test
    fun `bez uvecanja 10 punih godina je 5 procenata`() {
        val preview = calculateKnownStartSeniorityPreview(
            startYear = 2016,
            startMonth = 5,
            targetYear = 2026,
            targetMonth = 5,
            increaseFactor = SeniorityIncreaseFactor.NONE,
            ratePerYear = 0.005
        )

        assertEquals(120, preview.effectiveMonths)
        assertEquals(120.0, preview.accountingMonths, 0.0001)
        assertEquals(10, preview.fullAccountingYears)
        assertEquals(5.0, preview.percent, 0.0001)
    }

    @Test
    fun `12 15 za 12 efektivnih meseci daje 15 obracunskih meseci i 1 godinu`() {
        val preview = calculateKnownStartSeniorityPreview(
            startYear = 2025,
            startMonth = 5,
            targetYear = 2026,
            targetMonth = 5,
            increaseFactor = SeniorityIncreaseFactor.TWELVE_FIFTEEN,
            ratePerYear = 0.005
        )

        assertEquals(12, preview.effectiveMonths)
        assertEquals(15.0, preview.accountingMonths, 0.0001)
        assertEquals(1, preview.fullAccountingYears)
        assertEquals(0.5, preview.percent, 0.0001)
    }

    @Test
    fun `12 18 za 8 efektivnih godina daje 12 obracunskih godina`() {
        val preview = calculateKnownStartSeniorityPreview(
            startYear = 2018,
            startMonth = 5,
            targetYear = 2026,
            targetMonth = 5,
            increaseFactor = SeniorityIncreaseFactor.TWELVE_EIGHTEEN,
            ratePerYear = 0.005
        )

        assertEquals(96, preview.effectiveMonths)
        assertEquals(144.0, preview.accountingMonths, 0.0001)
        assertEquals(12, preview.fullAccountingYears)
        assertEquals(6.0, preview.percent, 0.0001)
    }

    @Test
    fun `procenat 6_4 sa stopom 0_5 zaokruzuje na 13 obracunskih godina`() {
        val estimate = estimateSeniorityFromPayslipPercent(
            lastPayslipYear = 2026,
            lastPayslipMonth = 4,
            payslipPercent = 6.4,
            increaseFactor = SeniorityIncreaseFactor.NONE,
            ratePerYear = 0.005
        )

        assertEquals(13, estimate.accountingFullYears)
        assertEquals(13.0, estimate.estimatedEffectiveYears, 0.0001)
        assertEquals(2013, estimate.estimatedStartYear)
        assertTrue(estimate.rounded)
    }

    @Test
    fun `procenat 8_0 sa 12 16 daje oko 12 efektivnih godina`() {
        val estimate = estimateSeniorityFromPayslipPercent(
            lastPayslipYear = 2026,
            lastPayslipMonth = 4,
            payslipPercent = 8.0,
            increaseFactor = SeniorityIncreaseFactor.TWELVE_SIXTEEN,
            ratePerYear = 0.005
        )

        assertEquals(16, estimate.accountingFullYears)
        assertEquals(12.0, estimate.estimatedEffectiveYears, 0.0001)
        assertEquals(2014, estimate.estimatedStartYear)
        assertFalse(estimate.rounded)
    }

    @Test
    fun `seniority info tekst koristi uvek dijakritike za uvecani`() {
        val source = sourceText("SeniorityDialog.kt")

        assertTrue(source.contains("Beneficirani/uvećani staž se ovde koristi za minuli rad"))
        assertFalse(source.contains("Beneficirani/uvecani staž"))
    }

    private fun sourceText(fileName: String): String {
        val path = checkNotNull(listOf(
            Path.of("src", "main", "java", "com", "rad", "shifttracker", fileName),
            Path.of("app", "src", "main", "java", "com", "rad", "shifttracker", fileName)
        ).firstOrNull { Files.exists(it) }) { "Source file not found: $fileName" }
        return String(Files.readAllBytes(path), Charsets.UTF_8)
    }
}
