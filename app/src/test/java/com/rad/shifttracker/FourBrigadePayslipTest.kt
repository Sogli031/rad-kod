package com.rad.shifttracker

import java.math.BigDecimal
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import com.rad.shifttracker.listici.DIFFICULT_WORK_CONDITIONS_CODE
import com.rad.shifttracker.listici.ParsedPayslip
import com.rad.shifttracker.listici.PayslipParseResult
import com.rad.shifttracker.listici.applyPayslipChanges
import com.rad.shifttracker.listici.buildPayslipImportPlan
import com.rad.shifttracker.listici.buildPayslipReconciliation
import com.rad.shifttracker.listici.parsePayslip
import com.rad.shifttracker.listici.payslipFromJsonOrNull
import com.rad.shifttracker.listici.payslipToJson
import com.rad.shifttracker.listici.unknownHourItems
import com.rad.shifttracker.repo.difficultWorkConditionsPercentValue
import com.rad.shifttracker.repo.getAnnualLeaveEntitlement
import com.rad.shifttracker.repo.getMonthlyPayDetails
import com.rad.shifttracker.repo.savePayslip
import com.rad.shifttracker.smene.Shift
import com.rad.shifttracker.smene.ShiftFillMode
import com.rad.shifttracker.smene.ShiftFillSettings
import com.rad.shifttracker.smene.ShiftScheduler

/** Listić livnice (četvorobrigada) 08/2026 skraćen na redove koje parser koristi; bez imena, JMBG-a i računa. */
private val FOUNDRY_AUGUST_2026 = """
    OBRAČUN ZARADE I NAKNADE ZARADE ZA MESEC 08/2026 rb.3/konačna
    Datum isplate : 18.09.2026
    Ukupni radni staž:41 1000 LIVNICA
    Radni staž za MR:41 3003 Topioničar
    Fond za mesec : 168 Koeficijent : 2.70 Vrednost sata : 226.00 Osnovna zarada : 102,513.60
    Oznaka i vrsta rada ČASOVI % RADA BRUTO IZNOS
    81 Stimulacije 13515
    MR Minuli rad 20.5 21015.29
    REG Regres 28530.67
    TO2 Topli obrok 6626.25
    IA Isplacena akontacija -100 -120469.33
    01 Rad u reziji 168.0 102513.6
    20 Nocni rad 39.0 41 9757.1
    21 Rad u otezanim uslovima 144.0 32 28118.02
    22 Rad nedeljom 11.0 25 1678.05
    UKUPNO 168.0 91,284.65
    Zarade i naknade zarada sa porezom i dop.na teret zaposlenog 211,753.98 120,469.33 91,284.65
    PIO 14.00% 29,645.56 16,865.71 12,779.85
    Zdravstvo 5.15% 10,905.33 6,204.17 4,701.16
    Nezaposlenost 0.75% 1,588.15 903.52 684.63
    PIO 10.00% 21,175.40 12,046.93 9,128.47
    Beneficirani staz 3.70% 7,834.90 4,457.37 3,377.53
    PORESKA OLAKŠICA 34,221.00 0.00 34,221.00
    POREZ NA ZARADE I NAKNADE ZARADE 10.00% 17,753.30 12,046.93 5,706.37
    Zarade i naknade zarada bez poreza i dop.na teret zaposlenog i na teret poslodavca 151,861.64 84,449.00 67,412.64
""".trimIndent().lines()

class FourBrigadePayslipTest {

    private fun foundry(): ParsedPayslip =
        (parsePayslip(FOUNDRY_AUGUST_2026) as PayslipParseResult.Success).payslip

    @Test
    fun difficultConditionsAndBenefitedSeniorityAreReadFromPayslip() {
        val slip = foundry()
        assertEquals(BigDecimal("32"), slip.items[DIFFICULT_WORK_CONDITIONS_CODE]?.percent)
        assertTrue("šifra 21 je poznata stavka", slip.unknownHourItems().isEmpty())
        assertTrue(slip.benefitedSeniority)
        assertTrue(payslipFromJsonOrNull(payslipToJson(slip))!!.benefitedSeniority)
        // Listić valjaonice nema ni otežane uslove ni beneficirani staž.
        val rolling = (parsePayslip(AUGUST_2026) as PayslipParseResult.Success).payslip
        assertFalse(rolling.benefitedSeniority)
    }

    @Test
    fun listaSetsDifficultConditionsAndRemovesThemWhenPayslipHasNone() {
        val repo = testRepository()
        applyPayslipChanges(repo, buildPayslipImportPlan(repo, listOf(foundry()), emptyList()).changes)
        assertEquals(32.0, repo.difficultWorkConditionsPercentValue, 1e-9)

        val rolling = (parsePayslip(AUGUST_2026) as PayslipParseResult.Success).payslip
        applyPayslipChanges(repo, buildPayslipImportPlan(repo, listOf(rolling), emptyList()).changes)
        assertEquals(0.0, repo.difficultWorkConditionsPercentValue, 1e-9)
    }

    @Test
    fun benefitedSeniorityOnPayslipAddsAnnualLeaveDay() {
        val repo = testRepository()
        val without = repo.getAnnualLeaveEntitlement(2026).breakdown.workingConditionsDays
        repo.savePayslip(foundry(), withPayBase = false)
        assertEquals(1, without)
        assertEquals(2, repo.getAnnualLeaveEntitlement(2026).breakdown.workingConditionsDays)
    }

    /** Listić uvek plaća pun fond: neradni dani iz rasporeda nisu izostanci. */
    @Test
    fun fourBrigadeFilledMonthIsPaidUpToTheFund() {
        for (phase in 0..7) {
            val repo = testRepository()
            val first = LocalDate.of(2026, 8, 1)
            ShiftScheduler().populateMonthsAhead(repo, first, ShiftFillSettings(ShiftFillMode.FOUR_BRIGADE, first, Shift.FIRST, phase), 0)
            val slip = foundry()
            applyPayslipChanges(repo, buildPayslipImportPlan(repo, listOf(slip), emptyList()).changes)

            val rows = buildPayslipReconciliation(repo, slip).rows
            val regular = rows.single { it.label.startsWith("Rad u režiji") }
            assertTrue("faza $phase: ${regular.appText}", regular.matches)
            assertTrue("faza $phase", rows.none { it.label.startsWith("Izostanci") })
            val details = repo.getMonthlyPayDetails(2026, 8)
            assertEquals("faza $phase", 0, details.overtimeMinutes)
            assertEquals("faza $phase", BigDecimal("21015.29"), BigDecimal(details.grossSeniorityBonus).setScale(2, java.math.RoundingMode.HALF_UP))
        }
    }

    @Test
    fun threeBrigadeMonthIsNotToppedUp() {
        val repo = testRepository()
        val first = LocalDate.of(2026, 8, 3)
        ShiftScheduler().populateMonthsAhead(repo, first, Shift.FIRST, 0)
        repo.set(2026, 8, 5, Shift.OFF, 0)
        val details = repo.getMonthlyPayDetails(2026, 8)
        assertTrue(details.regularWorkedBaseMinutes < 168 * 60)
    }
}
