package com.rad.shifttracker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class UnionDuesTest {

    @Test
    fun `clanarina je 1,5 procenta neto zarade pre obustava zaokruzeno na 2 decimale`() {
        // Acceptance (maj 2026): netPayBeforeDeductions = 110517.43, stopa 1,5% -> 1657.76
        assertEquals(
            1657.76,
            calculateUnionDuesNet(110517.43, unionMember = true, unionDuesRate = 0.015),
            0.0001
        )
    }

    @Test
    fun `clanarina je nula kada clanstvo nije ukljuceno`() {
        assertEquals(
            0.0,
            calculateUnionDuesNet(110517.43, unionMember = false, unionDuesRate = 0.015),
            0.0001
        )
    }

    @Test
    fun `clanarina ulazi u netDeductionsTotal i umanjuje displayPay bez diranja poreza i doprinosa`() {
        val summary = MonthSummary(year = 2026, month = 6, totalPay = 130_000.0)

        val base = calculateMonthlyNetPayBreakdown(
            summary = summary,
            grossHourlyRate = 637.32,
            monthlyFundHours = 160,
            contributionsPercent = 19.9,
            taxRelief = 34_221.0,
            taxPercent = 10.0
        )
        val withUnion = calculateMonthlyNetPayBreakdown(
            summary = summary,
            grossHourlyRate = 637.32,
            monthlyFundHours = 160,
            contributionsPercent = 19.9,
            taxRelief = 34_221.0,
            taxPercent = 10.0,
            unionMember = true,
            unionDuesRate = 0.015
        )

        // Bez clanstva: clanarina = 0, displayPay nepromenjen.
        assertEquals(0.0, base.unionDuesNet, 0.0001)

        val expectedDues = calculateUnionDuesNet(withUnion.netPayBeforeDeductions, true, 0.015)
        assertTrue(expectedDues > 0.0)
        assertEquals(expectedDues, withUnion.unionDuesNet, 0.0001)

        // Porez, doprinosi i neto pre obustava ostaju netaknuti.
        assertEquals(base.netPayBeforeDeductions, withUnion.netPayBeforeDeductions, 0.0001)
        assertEquals(base.employeeContributions, withUnion.employeeContributions, 0.0001)
        assertEquals(base.tax, withUnion.tax, 0.0001)
        assertEquals(base.taxableBase, withUnion.taxableBase, 0.0001)

        // Clanarina je obustava iz neta: ulazi u total, umanjuje displayPay.
        assertEquals(base.netDeductionsTotal + expectedDues, withUnion.netDeductionsTotal, 0.0001)
        assertEquals(base.displayPay - expectedDues, withUnion.displayPay, 0.0001)
    }

    @Test
    fun `clanstvo i stopa se cuvaju kroz export i import`() {
        val repo = ShiftRepository()
        repo.setUnionDuesSettings(unionMember = true, unionDuesRate = 0.02)

        val restored = ShiftRepository()
        assertTrue(restored.importFromJson(repo.exportJson()))

        val settings = restored.getUnionDuesSettings()
        assertTrue(settings.unionMember)
        assertEquals(0.02, settings.unionDuesRate, 0.0001)
    }
}
