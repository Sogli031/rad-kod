package com.rad.shifttracker

import org.junit.Assert.assertEquals
import org.junit.Test
import com.rad.shifttracker.godisnji.AnnualLeaveCalculationInput
import com.rad.shifttracker.godisnji.calculateAnnualLeaveDays

class AnnualLeaveCalculationTest {
    @Test
    fun `bez dodataka ostali zaposleni imaju 21 radni dan`() {
        val breakdown = calculateAnnualLeaveDays(
            AnnualLeaveCalculationInput(
                totalSeniorityYears = 0.0,
                yearsAtEmployer = 0.0,
                hasIncreasedRiskOrBenefitedSeniority = false,
                worksNightShiftSystem = false,
                worksWeekendShiftSystem = false,
                requiresAtLeastThirdDegree = false
            )
        )

        assertEquals(20, breakdown.baseDays)
        assertEquals(0, breakdown.seniorityDays)
        assertEquals(1, breakdown.workingConditionsDays)
        assertEquals(0, breakdown.nightWorkDays)
        assertEquals(0, breakdown.weekendWorkDays)
        assertEquals(0, breakdown.educationDays)
        assertEquals(0, breakdown.socialDays)
        assertEquals(21, breakdown.totalDays)
    }

    @Test
    fun `deset godina ostali zaposleni nocni rad i treci stepen daju 24 dana`() {
        val breakdown = calculateAnnualLeaveDays(
            AnnualLeaveCalculationInput(
                totalSeniorityYears = 10.0,
                yearsAtEmployer = 10.0,
                hasIncreasedRiskOrBenefitedSeniority = false,
                worksNightShiftSystem = true,
                worksWeekendShiftSystem = false,
                requiresAtLeastThirdDegree = true
            )
        )

        assertEquals(1, breakdown.seniorityDays)
        assertEquals(1, breakdown.workingConditionsDays)
        assertEquals(1, breakdown.nightWorkDays)
        assertEquals(0, breakdown.weekendWorkDays)
        assertEquals(1, breakdown.educationDays)
        assertEquals(24, breakdown.totalDays)
    }

    @Test
    fun `dvadeset godina povecan rizik nocni vikend i treci stepen daju 27 dana`() {
        val breakdown = calculateAnnualLeaveDays(
            AnnualLeaveCalculationInput(
                totalSeniorityYears = 20.0,
                yearsAtEmployer = 20.0,
                hasIncreasedRiskOrBenefitedSeniority = true,
                worksNightShiftSystem = true,
                worksWeekendShiftSystem = true,
                requiresAtLeastThirdDegree = true
            )
        )

        assertEquals(2, breakdown.seniorityDays)
        assertEquals(2, breakdown.workingConditionsDays)
        assertEquals(1, breakdown.nightWorkDays)
        assertEquals(1, breakdown.weekendWorkDays)
        assertEquals(1, breakdown.educationDays)
        assertEquals(27, breakdown.totalDays)
    }

    @Test
    fun `trideset sest godina kod poslodavca koristi povoljniji dodatak od 4 dana`() {
        val breakdown = calculateAnnualLeaveDays(
            AnnualLeaveCalculationInput(
                totalSeniorityYears = 36.0,
                yearsAtEmployer = 36.0,
                hasIncreasedRiskOrBenefitedSeniority = false,
                worksNightShiftSystem = false,
                worksWeekendShiftSystem = false,
                requiresAtLeastThirdDegree = false
            )
        )

        assertEquals(4, breakdown.seniorityDays)
        assertEquals(1, breakdown.workingConditionsDays)
        assertEquals(25, breakdown.totalDays)
    }

    @Test
    fun `samohrani roditelj dodaje dva dana na obracunati zbir`() {
        val withoutSocial = calculateAnnualLeaveDays(
            AnnualLeaveCalculationInput(
                totalSeniorityYears = 10.0,
                yearsAtEmployer = 10.0,
                hasIncreasedRiskOrBenefitedSeniority = false,
                worksNightShiftSystem = false,
                worksWeekendShiftSystem = false,
                requiresAtLeastThirdDegree = false,
                isSingleParentOfChildUnder14 = false
            )
        )
        val withSingleParent = calculateAnnualLeaveDays(
            AnnualLeaveCalculationInput(
                totalSeniorityYears = 10.0,
                yearsAtEmployer = 10.0,
                hasIncreasedRiskOrBenefitedSeniority = false,
                worksNightShiftSystem = false,
                worksWeekendShiftSystem = false,
                requiresAtLeastThirdDegree = false,
                isSingleParentOfChildUnder14 = true
            )
        )

        assertEquals(withoutSocial.totalDays + 2, withSingleParent.totalDays)
        assertEquals(2, withSingleParent.socialDays)
    }
}
