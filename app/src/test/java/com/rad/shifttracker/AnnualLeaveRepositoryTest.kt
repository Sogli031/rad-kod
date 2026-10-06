package com.rad.shifttracker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import com.rad.shifttracker.godisnji.AnnualLeaveCalculationInput
import com.rad.shifttracker.godisnji.calculateAnnualLeaveDays
import com.rad.shifttracker.repo.exportJson
import com.rad.shifttracker.repo.getAnnualLeaveCalculationInput
import com.rad.shifttracker.repo.getAnnualLeaveLastCalculatedDays
import com.rad.shifttracker.repo.importFromJson
import com.rad.shifttracker.repo.minuliRadStartMonthValue
import com.rad.shifttracker.repo.minuliRadStartYearValue
import com.rad.shifttracker.repo.setAnnualLeaveCalculation

class AnnualLeaveRepositoryTest {
    @Test
    fun `upitnik za godisnji podrazumevano koristi minuli rad na prvi januar`() {
        val repo = testRepository()
        repo.minuliRadStartYearValue = 2019
        repo.minuliRadStartMonthValue = 5

        val input = repo.getAnnualLeaveCalculationInput(2026)

        assertEquals(6.0, input.totalSeniorityYears, 0.01)
        assertEquals(6.0, input.yearsAtEmployer, 0.01)
    }

    @Test
    fun `upitnik za godisnji i poslednji rezultat se cuvaju kroz export import`() {
        val repo = testRepository()
        val input = AnnualLeaveCalculationInput(
            totalSeniorityYears = 20.0,
            yearsAtEmployer = 20.0,
            hasIncreasedRiskOrBenefitedSeniority = true,
            worksNightShiftSystem = true,
            worksWeekendShiftSystem = true,
            requiresAtLeastThirdDegree = true,
            isSingleParentOfChildUnder14 = true
        )
        val breakdown = calculateAnnualLeaveDays(input)
        repo.setAnnualLeaveCalculation(input, breakdown.totalDays)

        val restored = testRepository()
        assertTrue(restored.importFromJson(repo.exportJson()))
        val restoredInput = restored.getAnnualLeaveCalculationInput(2026)

        assertEquals(input.totalSeniorityYears, restoredInput.totalSeniorityYears, 0.01)
        assertEquals(input.yearsAtEmployer, restoredInput.yearsAtEmployer, 0.01)
        assertEquals(input.hasIncreasedRiskOrBenefitedSeniority, restoredInput.hasIncreasedRiskOrBenefitedSeniority)
        assertEquals(input.worksNightShiftSystem, restoredInput.worksNightShiftSystem)
        assertEquals(input.worksWeekendShiftSystem, restoredInput.worksWeekendShiftSystem)
        assertEquals(input.requiresAtLeastThirdDegree, restoredInput.requiresAtLeastThirdDegree)
        assertEquals(input.isSingleParentOfChildUnder14, restoredInput.isSingleParentOfChildUnder14)
        assertEquals(breakdown.totalDays, restored.getAnnualLeaveLastCalculatedDays())
    }
}
