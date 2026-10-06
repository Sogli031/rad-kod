package com.rad.shifttracker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import com.rad.shifttracker.godisnji.AnnualLeaveCalculationInput
import com.rad.shifttracker.godisnji.PayslipSeniorityPoint
import com.rad.shifttracker.godisnji.SeniorityOnJanuaryFirst
import com.rad.shifttracker.godisnji.annualLeaveEntitlement
import com.rad.shifttracker.godisnji.seniorityAnniversaryMonth
import com.rad.shifttracker.godisnji.seniorityOnJanuaryFirst
import com.rad.shifttracker.listici.PayslipParseResult
import com.rad.shifttracker.listici.parsePayslip
import com.rad.shifttracker.listici.payslipFromJsonOrNull
import com.rad.shifttracker.listici.payslipToJson
import com.rad.shifttracker.repo.getAnnualLeaveEntitlement
import com.rad.shifttracker.repo.savePayslip
import com.rad.shifttracker.repo.setAnnualLeaveCalculation
import com.rad.shifttracker.smene.MonthKey
import com.rad.shifttracker.ui.dialozi.annualLeaveBreakdownRows
import com.rad.shifttracker.ui.dialozi.annualLeaveSeniorityNote

class AnnualLeaveEntitlementTest {
    private fun points(vararg values: Pair<String, Int>) = values.map { (month, years) ->
        val (m, y) = month.split("/").map(String::toInt)
        PayslipSeniorityPoint(MonthKey(y, m), years)
    }

    @Test
    fun `stvarni listici 2026 bez povecanja staza daju 7 na 01_01`() {
        val real = (1..8).map { PayslipSeniorityPoint(MonthKey(2026, it), 7) }

        assertNull(seniorityAnniversaryMonth(real))
        assertEquals(SeniorityOnJanuaryFirst(7, exact = true, anniversaryMonth = null), seniorityOnJanuaryFirst(real, 2026))
        // 2027 bez viđenog povećanja: procena.
        assertEquals(SeniorityOnJanuaryFirst(8, exact = false, anniversaryMonth = null), seniorityOnJanuaryFirst(real, 2027))
    }

    @Test
    fun `mesec povecanja se vidi na listicima i vazi za sve godine`() {
        val slips = points("11/2026" to 7, "12/2026" to 7, "1/2027" to 7, "2/2027" to 8, "3/2027" to 8)

        assertEquals(2, seniorityAnniversaryMonth(slips))
        assertEquals(SeniorityOnJanuaryFirst(7, true, 2), seniorityOnJanuaryFirst(slips, 2027))
        // 01.01.2028: 8 iz marta 2027, februar 2028 još nije došao.
        assertEquals(SeniorityOnJanuaryFirst(8, true, 2), seniorityOnJanuaryFirst(slips, 2028))
        // 01.01.2026: 7 iz novembra 2026 minus povećanje u februaru 2026.
        assertEquals(SeniorityOnJanuaryFirst(6, true, 2), seniorityOnJanuaryFirst(slips, 2026))
        // Rupa između listića ne otkriva mesec.
        assertNull(seniorityAnniversaryMonth(points("1/2027" to 7, "3/2027" to 8)))
    }

    @Test
    fun `granica staza od 15 godina menja pravo tek u godini posle navrsenih 15`() {
        val slips = points("4/2030" to 15, "5/2030" to 16)
        fun days(year: Int) = annualLeaveEntitlement(
            manual = AnnualLeaveCalculationInput(requiresAtLeastThirdDegree = true),
            totalSeniority = seniorityOnJanuaryFirst(slips, year),
            employerSeniority = null,
            fallbackSeniorityYears = 0,
            worksNightShifts = true,
            worksWeekendShifts = false
        ).breakdown

        assertEquals(1, days(2030).seniorityDays) // 15 na 01.01.2030.
        assertEquals(2, days(2031).seniorityDays) // 16 na 01.01.2031.
    }

    @Test
    fun `korisnik trosmenski sa 7 godina i III stepenom ima 24 dana a ne 25`() {
        val entitlement = annualLeaveEntitlement(
            manual = AnnualLeaveCalculationInput(requiresAtLeastThirdDegree = true),
            totalSeniority = SeniorityOnJanuaryFirst(7, true, null),
            employerSeniority = SeniorityOnJanuaryFirst(7, true, null),
            fallbackSeniorityYears = 0,
            worksNightShifts = true,
            worksWeekendShifts = false
        )

        assertEquals(24, entitlement.breakdown.totalDays)
        assertEquals(
            listOf(
                "Osnovno" to "20",
                "Staž 7 god. na 01.01." to "+1",
                "Uslovi rada" to "+1",
                "Noćni rad (smene)" to "+1",
                "III stepen spreme" to "+1"
            ),
            annualLeaveBreakdownRows(entitlement)
        )
        assertEquals("Staž je sa januarske liste.", annualLeaveSeniorityNote(entitlement))
    }

    @Test
    fun `repozitorijum cita staz i nocni rad sa sacuvanih listica`() {
        val repo = testRepository()
        for (month in 1..8) {
            val lines = requireNotNull(PAYSLIPS_2026[month]) + "Ukupni radni staž:7 1200 VALJAONICA Radni staž za MR:7"
            repo.savePayslip((parsePayslip(lines) as PayslipParseResult.Success).payslip)
        }
        repo.setAnnualLeaveCalculation(AnnualLeaveCalculationInput(requiresAtLeastThirdDegree = true), 0)

        val entitlement = repo.getAnnualLeaveEntitlement(2026)

        assertEquals(7.0, entitlement.input.totalSeniorityYears, 0.0)
        assertTrue(entitlement.input.worksNightShiftSystem)
        assertFalse(entitlement.input.worksWeekendShiftSystem)
        assertEquals(24, entitlement.breakdown.totalDays)
    }

    @Test
    fun `parser prihvata ukupan i staz bez kvacice`() {
        val base = requireNotNull(PAYSLIPS_2026[8])
        fun total(line: String) = (parsePayslip(base + line) as PayslipParseResult.Success).payslip.totalSeniorityYears

        assertEquals(7, total("Zlakusa bb Ukupni radni staž:7 1200 VALJAONICA"))
        assertEquals(12, total("Ukupan radni staz : 12"))
        assertEquals(7, payslipFromJsonOrNull(
            payslipToJson((parsePayslip(base + "Ukupni radni staž:7") as PayslipParseResult.Success).payslip)
        )?.totalSeniorityYears)
    }
}
