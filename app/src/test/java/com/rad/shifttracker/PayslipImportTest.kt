package com.rad.shifttracker

import java.math.BigDecimal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import com.rad.shifttracker.listici.ParsedPayslip
import com.rad.shifttracker.listici.PayslipChangeKind
import com.rad.shifttracker.listici.PayslipParseResult
import com.rad.shifttracker.listici.PayslipPayBaseResult
import com.rad.shifttracker.listici.applyPayslipChanges
import com.rad.shifttracker.listici.buildPayslipImportPlan
import com.rad.shifttracker.listici.buildPayslipReconciliation
import com.rad.shifttracker.listici.parsePayslip
import com.rad.shifttracker.listici.shouldReplaceLastPayslip
import com.rad.shifttracker.obracun.PayBaseSelectionMode
import com.rad.shifttracker.obracun.SeniorityInputMode
import com.rad.shifttracker.repo.defaultPayBaseSelectionModeValue
import com.rad.shifttracker.repo.employeeContributionsPercentValue
import com.rad.shifttracker.repo.getKnownPayBaseAnchors
import com.rad.shifttracker.repo.getMonthlyNetAdjustments
import com.rad.shifttracker.repo.minuliRadLastPayslipMonthValue
import com.rad.shifttracker.repo.minuliRadLastPayslipYearValue
import com.rad.shifttracker.repo.minuliRadPayslipPercentValue
import com.rad.shifttracker.repo.payCoefficientValue
import com.rad.shifttracker.repo.seniorityInputModeValue
import com.rad.shifttracker.repo.taxPercentValue
import com.rad.shifttracker.repo.taxReliefValue
import com.rad.shifttracker.smene.MonthKey
import com.rad.shifttracker.listici.payslipFromJsonOrNull
import com.rad.shifttracker.listici.payslipToJson
import com.rad.shifttracker.listici.unknownHourItems

/** Listić 08/2026 skraćen na redove koje parser koristi; bez imena, JMBG-a i računa. */
internal val AUGUST_2026 = """
    OBRAČUN ZARADE I NAKNADE ZARADE ZA MESEC 08/2026 rb.3/konačna
    Datum isplate : 18.09.2026
    Ukupni radni staž:7 1200 VALJAONICA
    Radni staž za MR:7 3035 Rukovalac
    Fond za mesec : 168 Koeficijent : 2.82 Vrednost sata : 226.00 Osnovna zarada : 107,069.76
    Oznaka i vrsta rada ČASOVI % RADA BRUTO IZNOS
    95 Izostanci 22.0 0
    96 Izostanci 1 -100 -478
    81 Stimulacije 10699
    KIR K I R U -5 -3632.72
    MR Minuli rad 3.5 2542.91
    REG Regres 28530.67
    TO2 Topli obrok 6191.16
    IA Isplacena akontacija -100 -84082.73
    01 Rad u reziji 114.0 72654.47
    10 Godisnji odmor 16.0 15267.88
    11 Placeno odsustvo 16.0 15267.88
    20 Nocni rad 43.0 41 11235.95
    22 Rad nedeljom 8.0 25 1274.64
    30 Prekovremeni rad 12.0 7647.84
    24 Uvecanje za prekovremeni rad 12.0 41 3135.61
    UKUPNO 168.0 86,254.56
    Zarade i naknade zarada sa porezom i dop.na teret zaposlenog i na teret poslodavca 196,143.39 96,821.26 99,322.13
    Zarade i naknade zarada sa porezom i dop.na teret zaposlenog 170,337.29 84,082.73 86,254.56
    DOPRINOSI NA TERET RADNIKA ZA OBAVEZNO SOCIJALANO OSIGURANJE 33,897.12 16,732.46 17,164.66
    PIO 14.00% 23,847.22 11,771.58 12,075.64
    Zdravstvo 5.15% 8,772.37 4,330.26 4,442.11
    Nezaposlenost 0.75% 1,277.53 630.62 646.91
    DOPRINOSI NA TERET POSLODAVCA ZA OBAVEZNO SOCIJALANO OSIGURANJE 25,806.10 12,738.53 13,067.57
    PIO 10.00% 17,033.73 8,408.27 8,625.46
    Zdravstvo 5.15% 8,772.37 4,330.26 4,442.11
    PORESKA OLAKŠICA 34,221.00 0.00 34,221.00
    OSNOVICA POREZA 136,116.29 84,082.73 52,033.56
    POREZ NA ZARADE I NAKNADE ZARADE 10.00% 13,611.63 8,408.27 5,203.36
    Zarade i naknade zarada bez poreza i dop.na teret zaposlenog i na teret poslodavca 122,828.55 58,942.00 63,886.55
""".trimIndent().lines()

class PayslipImportTest {

    private fun august(): ParsedPayslip =
        (parsePayslip(AUGUST_2026) as PayslipParseResult.Success).payslip

    @Test
    fun parsesHeaderTaxesSeniorityAdvanceAndItems() {
        val slip = august()
        assertEquals(MonthKey(2026, 8), slip.month)
        assertEquals("rb.3/konačna", slip.runLabel)
        assertEquals(168, slip.fundHours)
        assertEquals(BigDecimal("2.82"), slip.coefficient)
        assertEquals(BigDecimal("226.00"), slip.hourlyValue)
        assertEquals(7, slip.seniorityYears)
        assertEquals(BigDecimal("3.5"), slip.seniorityPercent)
        // Samo doprinosi radnika (14 + 5,15 + 0,75), ne i poslodavca.
        assertEquals(0, BigDecimal("19.90").compareTo(slip.employeeContributionsPercent))
        assertEquals(BigDecimal("34221.00"), slip.taxReliefRsd)
        assertEquals(BigDecimal("10.00"), slip.taxPercent)
        assertEquals(BigDecimal("170337.29"), slip.grossTotal)
        assertEquals(BigDecimal("122828.55"), slip.netTotal)
        assertEquals(BigDecimal("114.0"), slip.items[1]?.hours)
        assertEquals(BigDecimal("41"), slip.items[20]?.percent)
        assertEquals(BigDecimal("22.0"), slip.items[95]?.hours)
        assertNull("red 96 nema časove sa decimalom", slip.items[96])
        assertEquals(listOf("Izostanci 1", "Stimulacije", "KIRU", "Regres"), slip.extras.map { it.label })
        assertEquals(BigDecimal("-478"), slip.extras[0].amount)
        assertEquals(BigDecimal("-3632.72"), slip.extras[2].amount)
        val base = slip.payBase as PayslipPayBaseResult.Success
        assertEquals(BigDecimal("954.24"), base.chosen.hourlyBase)
    }

    @Test
    fun planProposesOnlyDifferencesAndAppliesThem() {
        val repo = testRepository()
        repo.payCoefficientValue = 2.5
        repo.employeeContributionsPercentValue = 19.9
        repo.taxReliefValue = 30_000.0
        repo.taxPercentValue = 10.0

        val plan = buildPayslipImportPlan(repo, listOf(august()), emptyList())
        val kinds = plan.changes.map { it.kind }.toSet()
        assertTrue(PayslipChangeKind.COEFFICIENT in kinds)
        assertTrue(PayslipChangeKind.TAX_RELIEF in kinds)
        assertFalse("doprinosi su već 19,9", PayslipChangeKind.CONTRIBUTIONS in kinds)
        assertFalse("porez je već 10", PayslipChangeKind.TAX in kinds)
        assertTrue(PayslipChangeKind.PAY_BASE in kinds)
        assertTrue(PayslipChangeKind.SENIORITY in kinds)

        applyPayslipChanges(repo, plan.changes)

        assertEquals(2.82, repo.payCoefficientValue, 1e-9)
        assertEquals(34_221.0, repo.taxReliefValue, 1e-9)
        assertEquals(954.24, repo.getKnownPayBaseAnchors().single().grossHourlyBase, 1e-9)
        assertEquals(PayBaseSelectionMode.KNOWN_BASE, repo.defaultPayBaseSelectionModeValue)
        assertEquals(0.0, repo.getMonthlyNetAdjustments(2026, 8).advancePaymentNet, 1e-9)
        assertEquals(SeniorityInputMode.PAYSLIP_PERCENT, repo.seniorityInputModeValue)
        assertEquals(3.5, repo.minuliRadPayslipPercentValue, 1e-9)
        assertEquals(2026, repo.minuliRadLastPayslipYearValue)
        assertEquals(8, repo.minuliRadLastPayslipMonthValue)

        // Drugi uvoz istog listića: sve se već poklapa.
        val second = buildPayslipImportPlan(repo, listOf(august()), emptyList())
        assertTrue(second.changes.map { it.kind }.toString(), second.changes.isEmpty())
    }

    @Test
    fun globalSettingsComeOnlyFromNewestPayslip() {
        val repo = testRepository()
        repo.payCoefficientValue = 2.82
        val older = august().copy(month = MonthKey(2026, 1), coefficient = BigDecimal("2.50"))
        val plan = buildPayslipImportPlan(repo, listOf(august(), older), emptyList())
        assertTrue(plan.changes.none { it.kind == PayslipChangeKind.COEFFICIENT })
        assertEquals(MonthKey(2026, 8), plan.payslips.last().month)
    }

    @Test
    fun laterRunOfSameMonthWins() {
        val repo = testRepository()
        val rb2 = august().copy(runLabel = "rb.2/konačna", coefficient = BigDecimal("2.50"))
        val plan = buildPayslipImportPlan(repo, listOf(august(), rb2), emptyList())
        assertEquals(1, plan.payslips.size)
        assertEquals("rb.3/konačna", plan.payslips.single().runLabel)
    }

    @Test
    fun reconciliationComparesHoursAndFlagsDifferences() {
        val repo = testRepository()
        val check = buildPayslipReconciliation(repo, august())
        assertFalse(check.hasAppData)
        val regular = check.rows.single { it.label.startsWith("Rad u režiji") }
        assertEquals("114 h", regular.payslipText)
        assertFalse(regular.matches)
        assertTrue(check.differences.any { it.text == "Rad u režiji (01): aplikacija 0 h, lista 114 h" })
        assertTrue(check.differences.any { it.text == "Stimulacije: 10.699,00 RSD" })
    }

    @Test
    fun everyRealPayslipOf2026IsParsedUpToTheLastDinar() {
        // Bruto sa listića 01–08/2026: sve pročitane stavke (sa časovima, bez časova, MR, TO2) daju tačno taj iznos.
        val gross = mapOf(
            1 to "150461.65", 2 to "159788.03", 3 to "169935.69", 4 to "109709.17",
            5 to "176923.51", 6 to "113463.72", 7 to "189060.84", 8 to "170337.29"
        )
        for ((month, lines) in PAYSLIPS_2026) {
            val withTable = lines.take(2) + "Oznaka i vrsta rada ČASOVI % RADA BRUTO IZNOS" + lines.drop(2) +
                "UKUPNO 176.0 0" + "Zarade i naknade zarada sa porezom i dop.na teret zaposlenog ${gross[month]} 0 0"
            val slip = (parsePayslip(withTable) as PayslipParseResult.Success).payslip
            val sum = slip.items.values.fold(BigDecimal.ZERO) { acc, item -> acc + item.amount } +
                slip.extras.fold(BigDecimal.ZERO) { acc, extra -> acc + extra.amount } +
                slip.seniorityAmount!! + slip.mealGross!!
            assertEquals("mesec $month", 0, sum.compareTo(slip.grossTotal))
            assertTrue("mesec $month", slip.unknownHourItems().isEmpty())
        }
    }

    @Test
    fun unknownCodesAreReadUnderTheirPayslipName() {
        val lines = AUGUST_2026.flatMap { line ->
            if (line.startsWith("REG ")) listOf(line, "JN JUBILARNA NAGRADA 50000", "50 Davanje krvi 8.0 7633.92") else listOf(line)
        }
        val slip = (parsePayslip(lines) as PayslipParseResult.Success).payslip
        assertEquals(listOf("Izostanci 1", "Stimulacije", "KIRU", "Regres", "Jubilarna nagrada (JN)"), slip.extras.map { it.label })
        assertEquals(listOf(50), slip.unknownHourItems().map { it.code })

        val check = buildPayslipReconciliation(testRepository(), slip)
        assertTrue(check.differences.any { it.text == "Jubilarna nagrada (JN): 50.000,00 RSD" })
        assertTrue(check.differences.any { it.text == "Davanje krvi (50, 8 h): 7.633,92 RSD" })
    }

    @Test
    fun snapshotRoundTripKeepsValuesAndPayBase() {
        val slip = august()
        val restored = payslipFromJsonOrNull(payslipToJson(slip))
        assertEquals(slip.copy(payBase = restored!!.payBase), restored)
        assertEquals(slip.payBase, restored.payBase)
        assertFalse(payslipToJson(slip).contains("Rukovalac"))
    }

    @Test
    fun olderScanDoesNotReplaceDisplayedPayslip() {
        val slip = august()
        assertTrue(shouldReplaceLastPayslip(null, slip))
        assertTrue(shouldReplaceLastPayslip(slip, slip))
        assertFalse(shouldReplaceLastPayslip(slip, slip.copy(month = MonthKey(2026, 7))))
        assertTrue(shouldReplaceLastPayslip(slip, slip.copy(month = MonthKey(2026, 9))))
    }
}
