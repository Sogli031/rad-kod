package com.rad.shifttracker

import org.junit.Assert.assertEquals
import org.junit.Test
import com.rad.shifttracker.ui.dialozi.estimateAbsenceRoundingGross

class AbsenceRoundingEstimateTest {
    private val rate = 637.32
    private val seniority = 0.035

    @Test
    fun `ostatak minuta izostanka daje iznose sa listica`() {
        // Listići 02, 06 i 07/2026: 30 min → 330; 04 i 05/2026: 45 min → 495.
        assertEquals(330, estimateAbsenceRoundingGross(37 * 60 + 30, rate, seniority))
        assertEquals(330, estimateAbsenceRoundingGross(3 * 60 + 30, rate, seniority))
        assertEquals(495, estimateAbsenceRoundingGross(7 * 60 + 45, rate, seniority))
    }

    @Test
    fun `celi sati i neispravna satnica ne daju korekciju`() {
        assertEquals(0, estimateAbsenceRoundingGross(3 * 60, rate, seniority))
        assertEquals(0, estimateAbsenceRoundingGross(0, rate, seniority))
        assertEquals(0, estimateAbsenceRoundingGross(-45, rate, seniority))
        assertEquals(0, estimateAbsenceRoundingGross(45, 0.0, seniority))
        assertEquals(0, estimateAbsenceRoundingGross(45, Double.NaN, seniority))
    }
}
