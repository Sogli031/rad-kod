package com.rad.shifttracker

import java.math.BigDecimal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Listić valjaonice 08/2026 sa šifrom 41 „Razlika bolovanja do 30 d.“ (40 h, 65%),
 * skraćen na redove koje parser koristi; bez imena, JMBG-a i računa.
 */
private val SICK_DIFFERENCE_AUGUST_2026 = """
    OBRAČUN ZARADE I NAKNADE ZARADE ZA MESEC 08/2026 rb.3/konačna
    Datum isplate : 18.09.2026
    Ukupni radni staž:9 1200 VALJAONICA
    Radni staž za MR:9 3031 Rukovalac HVS sa JZ I grupe
    Fond za mesec : 168 Koeficijent : 3.00 Vrednost sata : 226.00 Osnovna zarada : 113,904.00
    Oznaka i vrsta rada ČASOVI % RADA BRUTO IZNOS
    81 Stimulacije 2717
    MR Minuli rad 4.5 976.32
    REG Regres 28530.67
    TO2 Topli obrok 813.12
    IA Isplacena akontacija -100 -85744.65
    01 Rad u reziji 32.0 21696
    10 Godisnji odmor 88.0 81775.22
    11 Placeno odsustvo 8.0 7434.11
    20 Nocni rad 24.0 41 6671.52
    41 Razlika bolovanja do 30 d. 40.0 65 24159.82
    UKUPNO 168.0 89,029.13
    Zarade i naknade zarada sa porezom i dop.na teret zaposlenog 174,773.78 85,744.65 89,029.13
    PIO 14.00% 24,468.32 12,004.25 12,464.07
    Zdravstvo 5.15% 9,000.85 4,415.85 4,585.00
    Nezaposlenost 0.75% 1,310.80 643.08 667.72
    PIO 10.00% 17,477.38 8,574.47 8,902.91
    PORESKA OLAKŠICA 34,221.00 0.00 34,221.00
    POREZ NA ZARADE I NAKNADE ZARADE 10.00% 14,055.28 8,574.47 5,480.81
    Zarade i naknade zarada bez poreza i dop.na teret zaposlenog i na teret poslodavca 125,938.53 60,107.00 65,831.53
""".trimIndent().lines()

class SickLeaveDifferencePayslipTest {

    private fun slip(): ParsedPayslip =
        (parsePayslip(SICK_DIFFERENCE_AUGUST_2026) as PayslipParseResult.Success).payslip

    @Test
    fun code41IsReadAsSickLeave() {
        val item = slip().items[41]
        assertEquals(BigDecimal("40.0"), item?.hours)
        assertEquals(BigDecimal("65"), item?.percent)
        assertTrue(item!!.isSickLeave())
        assertTrue(slip().unknownHourItems().isEmpty())
    }

    @Test
    fun reconciliationCountsCode41AsSickHours() {
        val rows = buildPayslipReconciliation(testRepository(), slip()).rows
        assertTrue(rows.any { it.label == "Bolovanje (41)" })
    }

    @Test
    fun monthFillPlansFiveSickDaysAt65Percent() {
        val targets = fillTargetsFrom(slip())!!
        val sick = targets.blocks.single { it.kind == FillDayKind.SICK }
        assertEquals(5, sick.days)
        assertEquals(0.65, sick.sickRate!!, 1e-9)
    }
}
