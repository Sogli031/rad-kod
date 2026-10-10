package com.rad.shifttracker

import java.math.BigDecimal
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import com.rad.shifttracker.listici.PayslipParseResult
import com.rad.shifttracker.listici.PayslipSideRunKind
import com.rad.shifttracker.listici.parsePayslip
import com.rad.shifttracker.uvoz.sideRunCheckMonths
import com.rad.shifttracker.obracun.calculateMonthlyNetPayBreakdown
import com.rad.shifttracker.repo.exportJson
import com.rad.shifttracker.repo.importFromJson
import com.rad.shifttracker.repo.isRegressPaidInMonth
import com.rad.shifttracker.smene.MonthKey
import com.rad.shifttracker.obracun.MonthSummary
import com.rad.shifttracker.uvoz.PayslipPickOutcome
import com.rad.shifttracker.uvoz.payslipPickOutcome
import com.rad.shifttracker.uvoz.payslipSideRunPickText
import com.rad.shifttracker.uvoz.advancePaymentMonthsToConfirm
import com.rad.shifttracker.uvoz.advanceRecordedText
import com.rad.shifttracker.listici.advanceTotalsByPaymentMonth
import com.rad.shifttracker.uvoz.applySideRuns
import com.rad.shifttracker.uvoz.dailyCheckDue
import com.rad.shifttracker.listici.latestTransportDaily
import com.rad.shifttracker.listici.payslipSideRunKind
import com.rad.shifttracker.uvoz.regressChangedText
import com.rad.shifttracker.listici.regressTotalsByMonth
import com.rad.shifttracker.uvoz.sideRunCheckNeeded
import com.rad.shifttracker.listici.sideRuns
import com.rad.shifttracker.uvoz.transportChangedText
import com.rad.shifttracker.uvoz.shouldResetTransportDaily
import com.rad.shifttracker.obracun.DEFAULT_REGRESS_NET
import com.rad.shifttracker.repo.getMonthlyNetAdjustments
import com.rad.shifttracker.repo.getRegressNetForMonth
import com.rad.shifttracker.repo.setMonthlyAdvancePaymentNet
import com.rad.shifttracker.repo.transportDailyAmountValue
import com.rad.shifttracker.osnova.formatMoney2
import com.rad.shifttracker.smene.monthYearText

private const val DAY_MS = 24 * 60 * 60 * 1000L

class AdvanceFromPayslipsTest {
    /** Obračun akontacije 08/2026 (isplata 04.09.2026), skraćen na redove koje parser koristi. */
    private val advanceAugust = """
        OBRAČUN ZARADE I NAKNADE ZARADE ZA MESEC 08/2026 rb.2
        Datum isplate : 04.09.2026
        Oznaka i vrsta rada ČASOVI% RADA BRUTO IZNOS
        A K O N T A C I J A AK 50518.16
        UKUPNO 50,518.16
        Zarade i naknade zarada sa porezom i dop.na teret zaposlenog 84,082.73 33,564.57 50,518.16
        Zarade i naknade zarada bez poreza i dop.na teret zaposlenog i na teret poslodavca 58,942.00 20,000.00 38,942.00
        Ukupno uplaćeno 38,942.00
    """.trimIndent().lines()

    /** Regres 08/2026 (isplata 28.08.2026, rb.1): obračun akontacije ga prenosi kao „isplaćeno 20.000“. */
    private val regressAugust = """
        OBRAČUN ZARADE I NAKNADE ZARADE ZA MESEC 08/2026 rb.1
        Datum isplate : 28.08.2026
        Oznaka i vrsta rada ČASOVI% RADA BRUTO IZNOS
        RegresREG 33564.57
        UKUPNO 33,564.57
        Ukupno uplaćeno 20,000.00
    """.trimIndent().lines()

    /** Obračun prevoza 08/2026 (isplata 15.09.2026). */
    private val transportAugust = """
        OBRAČUN ZARADE I NAKNADE ZARADE ZA MESEC 08/2026 rb.1
        Datum isplate : 15.09.2026
        Oznaka i vrsta rada ČASOVI% RADA BRUTO IZNOS
        PREVOZPRV 144.0 4320
        UKUPNO 4,320.00
        Ukupno uplaćeno 4,320.00
    """.trimIndent().lines()

    private val august = MonthKey(2026, 8)
    private val september = MonthKey(2026, 9)

    private fun side(lines: List<String>) = parsePayslip(lines) as PayslipParseResult.SideRun

    private fun advance(run: String, paid: String?, month: MonthKey = august, paidIn: MonthKey? = null) =
        PayslipParseResult.SideRun(PayslipSideRunKind.ADVANCE, month, run, paid?.let(::BigDecimal), paidIn)

    @Test
    fun advanceIsReadWithPaidAmountAndPaymentMonth() {
        assertEquals(
            PayslipParseResult.SideRun(PayslipSideRunKind.ADVANCE, august, "rb.2", BigDecimal("38942.00"), september),
            parsePayslip(advanceAugust)
        )
        val unspaced = advanceAugust.map { if (it.startsWith("A K O")) "AKONTACIJA AK 50518.16" else it }
        assertEquals(PayslipSideRunKind.ADVANCE, side(unspaced).kind)
    }

    /** Redovi kako ih PdfBox čita na telefonu (Pixel 9): šifra stavke je ispred naziva. */
    @Test
    fun phoneLineOrderWithCodeFirstIsRecognized() {
        val advance = advanceAugust.map { if (it.startsWith("A K O")) "AK A K O N T A C I J A 50518.16" else it }
        assertEquals(
            PayslipParseResult.SideRun(PayslipSideRunKind.ADVANCE, august, "rb.2", BigDecimal("38942.00"), september),
            parsePayslip(advance)
        )
        val transport = transportAugust.map { if (it.startsWith("PREVOZ")) "PRV PREVOZ 144.0 4320" else it }
        assertEquals(PayslipSideRunKind.TRANSPORT, side(transport).kind)
        assertEquals(BigDecimal("240.00"), side(transport).transportDaily)
        val regress = regressAugust.map { if (it.startsWith("Regres")) "REG Regres 33564.57" else it }
        assertEquals(PayslipSideRunKind.REGRESS, side(regress).kind)
    }

    @Test
    fun transportIsReadWithDailyAmount() {
        val run = side(transportAugust)
        assertEquals(PayslipSideRunKind.TRANSPORT, run.kind)
        assertEquals(BigDecimal("240.00"), run.transportDaily)
        val spaced = transportAugust.map { if (it.startsWith("PREVOZ")) "PREVOZ PRV 104.0 3,120.00" else it }
        assertEquals(BigDecimal("240.00"), side(spaced).transportDaily)
    }

    @Test
    fun regressAndUnknownRunsAreSideRuns() {
        assertEquals(PayslipSideRunKind.REGRESS, side(regressAugust).kind)
        val unknown = listOf("OBRAČUN ZARADE I NAKNADE ZARADE ZA MESEC 08/2026 rb.5", "XY Nesto novo 1000.00")
        assertEquals(PayslipSideRunKind.OTHER, side(unknown).kind)
    }

    @Test
    fun finalPayslipsAreNeverSideRuns() {
        for ((month, lines) in PAYSLIPS_2026) {
            assertTrue("listić $month", parsePayslip(lines) is PayslipParseResult.Success)
        }
        val finalWithAdvanceRow = listOf("OBRAČUN ZARADE I NAKNADE ZARADE ZA MESEC 08/2026 rb.3/konačna", "AKONTACIJA AK 1.00")
        assertNull(payslipSideRunKind(finalWithAdvanceRow, emptyMap()))
    }

    @Test
    fun advanceGoesUnderPaymentMonthWithoutRegressOrTransport() {
        val runs = sideRuns(
            sequenceOf(parsePayslip(regressAugust), parsePayslip(advanceAugust), parsePayslip(transportAugust)),
            august
        )
        assertEquals(mapOf(september to 38_942.0), advanceTotalsByPaymentMonth(runs))
    }

    @Test
    fun advanceRunsSumOncePerRunAndFallBackToNextMonth() {
        val rb1 = advance("rb.1", "20000.00", paidIn = september)
        val rb2 = advance("rb.2", "38942.00", paidIn = september)
        val noDate = advance("rb.1", "30000", month = MonthKey(2026, 12))
        val unreadable = advance("rb.4", null, paidIn = september)
        val runs = sideRuns(sequenceOf(rb2, rb1, rb2, unreadable), august) + sideRuns(sequenceOf(noDate), MonthKey(2026, 12))
        assertEquals(mapOf(september to 58_942.0, MonthKey(2027, 1) to 30_000.0), advanceTotalsByPaymentMonth(runs))
    }

    @Test
    fun latestTransportWins() {
        val july = PayslipParseResult.SideRun(PayslipSideRunKind.TRANSPORT, MonthKey(2026, 7), "rb.1", null, null, BigDecimal("230"))
        assertEquals(240.0, latestTransportDaily(listOf(side(transportAugust), july))!!, 0.001)
        assertNull(latestTransportDaily(listOf(side(advanceAugust))))
    }

    @Test
    fun applySideRunsWritesAdvanceAndTransport() {
        val repo = testRepository()
        val changed = mutableListOf<MonthKey>()
        applySideRuns(
            repo,
            listOf(side(advanceAugust), side(transportAugust)),
            onAdvanceChanged = { month, _, _ -> changed += month }
        )
        assertEquals(38_942.0, repo.getMonthlyNetAdjustments(2026, 9).advancePaymentNet, 0.001)
        assertEquals(0.0, repo.getMonthlyNetAdjustments(2026, 8).advancePaymentNet, 0.001)
        assertEquals(240.0, repo.transportDailyAmountValue, 0.001)
        assertEquals(listOf(september), changed)
    }

    @Test
    fun transportStartsAtZeroUntilATransportRunIsRead() {
        val repo = testRepository()
        assertEquals(0.0, repo.transportDailyAmountValue, 0.001)
        val changes = mutableListOf<Pair<Double, Double>>()
        applySideRuns(repo, listOf(side(transportAugust)), onTransportChanged = { old, new -> changes += old to new })
        assertEquals(listOf(0.0 to 240.0), changes)
        assertEquals(
            "Prevoz po danu upisan: ${formatMoney2(240.0)} RSD (iz obračuna prevoza).",
            transportChangedText(0.0, 240.0)
        )
    }

    @Test
    fun oldDefaultTransportIsResetOnlyWhenNoTransportRunWasApplied() {
        assertEquals(true, shouldResetTransportDaily(transportRunApplied = false, daily = 240.0))
        assertEquals(false, shouldResetTransportDaily(transportRunApplied = true, daily = 240.0))
        // Iznos koji nije stara podrazumevana vrednost je sa obračuna (iz verzija pre pamćenja meseca).
        assertEquals(false, shouldResetTransportDaily(transportRunApplied = false, daily = 230.0))
        assertEquals(false, shouldResetTransportDaily(transportRunApplied = false, daily = 0.0))
    }

    @Test
    fun transportChangeIsReportedOnlyWhenTheAmountChanges() {
        val repo = testRepository()
        repo.transportDailyAmountValue = 230.0
        val changes = mutableListOf<Pair<Double, Double>>()
        applySideRuns(repo, listOf(side(transportAugust)), onTransportChanged = { old, new -> changes += old to new })
        applySideRuns(repo, listOf(side(transportAugust)), onTransportChanged = { old, new -> changes += old to new })
        assertEquals(listOf(230.0 to 240.0), changes)
        assertEquals(
            "Prevoz po danu promenjen: ${formatMoney2(230.0)} → ${formatMoney2(240.0)} RSD (iz obračuna prevoza).",
            transportChangedText(230.0, 240.0)
        )
        assertEquals(
            "Akontacija upisana: ${formatMoney2(38_942.0)} RSD za isplatu u ${monthYearText(september)}.",
            advanceRecordedText(september, 0.0, 38_942.0)
        )
        assertEquals(
            "Akontacija ispravljena: ${formatMoney2(40_000.0)} → ${formatMoney2(38_942.0)} RSD " +
                "za isplatu u ${monthYearText(september)} (iz obračuna).",
            advanceRecordedText(september, 40_000.0, 38_942.0)
        )
    }

    @Test
    fun manuallyTypedAdvanceIsReplacedByTheOneFromMail() {
        val repo = testRepository()
        repo.setMonthlyAdvancePaymentNet(2026, 9, 40_000.0)
        val changes = mutableListOf<Triple<MonthKey, Double, Double>>()

        applySideRuns(repo, listOf(side(advanceAugust)), onAdvanceChanged = { month, old, new -> changes += Triple(month, old, new) })

        assertEquals(listOf(Triple(september, 40_000.0, 38_942.0)), changes)
        assertEquals(38_942.0, repo.getMonthlyNetAdjustments(2026, 9).advancePaymentNet, 0.001)
    }

    @Test
    fun entryCheckRunsOnlyWhileSomethingIsMissing() {
        val today = LocalDate.of(2026, 9, 25)

        val now = 10 * DAY_MS
        // Dnevna provera je upravo obavljena: ostaje samo ono što fali.
        fun needed(transport: MonthKey?, confirmed: Set<MonthKey> = emptySet()) =
            sideRunCheckNeeded(today, transport, confirmed, lastFullCheckMillis = now - 1_000L, nowMillis = now)

        val bothConfirmed = setOf(august, september)
        assertTrue(needed(august))
        // Ručno ukucana akontacija nije potvrda: traži se dok obračun iz mejla ne stigne.
        assertTrue(needed(august, setOf(august)))
        // Prošli mesec isplate (avgust) nije potvrđen: traži se i on.
        assertTrue(needed(august, setOf(september)))
        assertFalse(needed(august, bothConfirmed))
        // Prevoz za avgust još nije uzet iz obračuna.
        assertTrue(needed(MonthKey(2026, 7), bothConfirmed))
        assertTrue(needed(null, bothConfirmed))
    }

    @Test
    fun everythingIsCheckedAtLeastOncePerDay() {
        val now = 10 * DAY_MS
        assertTrue(dailyCheckDue(lastFullCheckMillis = 0L, nowMillis = now))
        assertTrue(dailyCheckDue(lastFullCheckMillis = now - DAY_MS, nowMillis = now))
        assertFalse(dailyCheckDue(lastFullCheckMillis = now - DAY_MS + 1, nowMillis = now))
        // Sat unazad (promenjen sat): proverava se.
        assertTrue(dailyCheckDue(lastFullCheckMillis = now + 1_000L, nowMillis = now))

        // Sve potvrđeno, ali dnevna provera dospela: traži se bilo koji obračun (i regres van 2/5/8/11).
        val october = LocalDate.of(2026, 10, 12)
        val confirmed = setOf(september, MonthKey(2026, 10))
        assertTrue(sideRunCheckNeeded(october, september, confirmed, 0L, now))
        assertFalse(sideRunCheckNeeded(october, september, confirmed, now - 1_000L, now))
    }

    @Test
    fun unconfirmedPreviousPaymentMonthAlsoSearchesItsWorkMonth() {
        val today = LocalDate.of(2026, 10, 3)
        assertEquals(listOf(september, MonthKey(2026, 10)), advancePaymentMonthsToConfirm(today))
        assertEquals(listOf(august, september, MonthKey(2026, 10)), sideRunCheckMonths(today, withPreviousAdvance = true))
        assertEquals(listOf(september, MonthKey(2026, 10)), sideRunCheckMonths(today))
    }

    @Test
    fun regressIsReadForItsWorkMonth() {
        val run = side(regressAugust)
        assertEquals(august, run.month)
        assertEquals(BigDecimal("20000.00"), run.paidNet)
        assertEquals(mapOf(august to 20_000.0), regressTotalsByMonth(listOf(run, side(advanceAugust))))
    }

    @Test
    fun regressRaiseIsRecordedAndCarriedForward() {
        val repo = testRepository()
        val changes = mutableListOf<Triple<MonthKey, Double, Double>>()
        // Isti iznos kao podrazumevani: ništa se ne upisuje.
        applySideRuns(repo, listOf(side(regressAugust)), onRegressChanged = { m, o, n -> changes += Triple(m, o, n) })
        assertTrue(changes.isEmpty())

        val november = MonthKey(2026, 11)
        val raised = PayslipParseResult.SideRun(PayslipSideRunKind.REGRESS, november, "rb.1", BigDecimal("25000.00"))
        applySideRuns(repo, listOf(raised), onRegressChanged = { m, o, n -> changes += Triple(m, o, n) })
        assertEquals(listOf(Triple(november, 20_000.0, 25_000.0)), changes)
        assertEquals(20_000.0, repo.getRegressNetForMonth(august), 0.001)
        assertEquals(25_000.0, repo.getRegressNetForMonth(MonthKey(2027, 2)), 0.001)

        val restored = testRepository()
        assertTrue(restored.importFromJson(repo.exportJson()))
        assertEquals(25_000.0, restored.getRegressNetForMonth(MonthKey(2027, 5)), 0.001)
        assertEquals(
            "Regres promenjen: ${formatMoney2(20_000.0)} → ${formatMoney2(25_000.0)} RSD (od ${monthYearText(november)}).",
            regressChangedText(november, 20_000.0, 25_000.0)
        )
    }

    @Test
    fun regressAmountIsUsedInNetPay() {
        val summary = MonthSummary(year = 2026, month = 11, totalPay = 100_000.0)
        fun regress(net: Double) = calculateMonthlyNetPayBreakdown(
            summary, 800.0, 160, 19.9, 34_221.0, 10.0, regressNet = net
        ).netRegressAddition
        assertEquals(20_000.0, regress(DEFAULT_REGRESS_NET), 0.01)
        assertEquals(25_000.0, regress(25_000.0), 0.01)
    }

    @Test
    fun regressInstallmentsOutsideRegressMonthsAreCounted() {
        val repo = testRepository()
        val october = MonthKey(2026, 10)
        val november = MonthKey(2026, 11)
        val changes = mutableListOf<Triple<MonthKey, Double, Double>>()
        fun run(month: MonthKey, net: String) =
            PayslipParseResult.SideRun(PayslipSideRunKind.REGRESS, month, "rb.1", BigDecimal(net))

        assertFalse(repo.isRegressPaidInMonth(october))
        // 20.000 u oktobru (isto kao važeći iznos): mesec se pamti, poruka o promeni ne izlazi.
        applySideRuns(repo, listOf(run(october, "20000.00")), onRegressChanged = { m, o, n -> changes += Triple(m, o, n) })
        applySideRuns(repo, listOf(run(november, "20000.00")), onRegressChanged = { m, o, n -> changes += Triple(m, o, n) })
        assertTrue(changes.isEmpty())
        assertTrue(repo.isRegressPaidInMonth(october))
        assertTrue(repo.isRegressPaidInMonth(november))
        assertFalse(repo.isRegressPaidInMonth(MonthKey(2026, 9)))

        val summary = MonthSummary(year = 2026, month = 10, totalPay = 100_000.0)
        fun regress(paid: Boolean) = calculateMonthlyNetPayBreakdown(
            summary, 800.0, 160, 19.9, 34_221.0, 10.0, regressPaidInMonth = paid
        ).netRegressAddition
        assertEquals(0.0, regress(false), 0.01)
        assertEquals(20_000.0, regress(true), 0.01)

        val restored = testRepository()
        assertTrue(restored.importFromJson(repo.exportJson()))
        assertTrue(restored.isRegressPaidInMonth(october))
    }

    @Test
    fun checksPreviousAndCurrentWorkMonth() {
        assertEquals(listOf(august, september), sideRunCheckMonths(LocalDate.of(2026, 9, 25)))
        assertEquals(listOf(MonthKey(2025, 12), MonthKey(2026, 1)), sideRunCheckMonths(LocalDate.of(2026, 1, 5)))
    }

    @Test
    fun listaRejectsSideRunPdfs() {
        for (lines in listOf(advanceAugust, transportAugust, regressAugust)) {
            val parsed = side(lines)
            assertEquals(
                PayslipPickOutcome.Rejected(payslipSideRunPickText(parsed)),
                payslipPickOutcome(testRepository(), parsed, august)
            )
        }
    }
}
