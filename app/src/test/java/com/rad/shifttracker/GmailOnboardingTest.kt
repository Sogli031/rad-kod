package com.rad.shifttracker

import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import com.rad.shifttracker.uvoz.OnboardingImportResult
import com.rad.shifttracker.listici.ParsedPayslip
import com.rad.shifttracker.uvoz.PayslipChange
import com.rad.shifttracker.uvoz.PayslipChangeKind
import com.rad.shifttracker.listici.PayslipItem
import com.rad.shifttracker.listici.PayslipParseResult
import com.rad.shifttracker.uvoz.SettingDifference
import com.rad.shifttracker.uvoz.SideRunMessages
import com.rad.shifttracker.uvoz.applyOnboardingPayslips
import com.rad.shifttracker.uvoz.applyOnboardingVacation
import com.rad.shifttracker.uvoz.buildPayslipImportPlan
import com.rad.shifttracker.uvoz.finalPayslipArrivedText
import com.rad.shifttracker.listici.gmailPayslipRangeQuery
import com.rad.shifttracker.uvoz.onboardingPayslipMonths
import com.rad.shifttracker.uvoz.onboardingSummary
import com.rad.shifttracker.listici.parsePayslip
import com.rad.shifttracker.uvoz.recheckSummary
import com.rad.shifttracker.repo.getAnnualLeaveEntitlement
import com.rad.shifttracker.repo.getVacationYearBalance
import com.rad.shifttracker.repo.isVacationCarryoverAutomatic
import com.rad.shifttracker.repo.payCoefficientValue
import com.rad.shifttracker.repo.savePayslip
import com.rad.shifttracker.repo.transportDailyAmountValue
import com.rad.shifttracker.smene.MonthKey
import com.rad.shifttracker.listici.PayslipSideRunKind
import com.rad.shifttracker.uvoz.advancesFromImport
import com.rad.shifttracker.uvoz.applyArrivedFinalPayslip
import com.rad.shifttracker.uvoz.applyPayslipChanges
import com.rad.shifttracker.uvoz.finalPayslipCheckMonth
import com.rad.shifttracker.listici.latestTransportDaily
import com.rad.shifttracker.uvoz.monthRangeText
import com.rad.shifttracker.uvoz.onboardingVacation
import com.rad.shifttracker.uvoz.onboardingVacationTotals
import com.rad.shifttracker.listici.payslipPayBaseAnchor
import com.rad.shifttracker.listici.payslipRunNumber
import com.rad.shifttracker.listici.sideRuns
import com.rad.shifttracker.repo.ShiftRepository
import com.rad.shifttracker.repo.deletePayslip
import com.rad.shifttracker.repo.getHourlyValueForMonth
import com.rad.shifttracker.repo.getKnownPayBaseAnchors
import com.rad.shifttracker.repo.getMonthlyPayDetails
import com.rad.shifttracker.repo.getRegressNetForMonth
import com.rad.shifttracker.repo.getSavedPayslip
import com.rad.shifttracker.repo.isPayslipDeleted
import com.rad.shifttracker.uvoz.PayslipPickOutcome
import com.rad.shifttracker.uvoz.payslipPickOutcome

class GmailOnboardingTest {
    private fun payslip(month: Int): ParsedPayslip =
        (parsePayslip(requireNotNull(PAYSLIPS_2026[month])) as PayslipParseResult.Success).payslip

    @Test
    fun looksBackToJanuaryOfPreviousYear() {
        assertEquals(
            (1..12).map { MonthKey(2025, it) } + (1..8).map { MonthKey(2026, it) },
            onboardingPayslipMonths(LocalDate.of(2026, 9, 25))
        )
        // Pre avgusta lista za jun još nije tu, pa treba i period od jula pretprošle godine.
        assertEquals(
            (7..12).map { MonthKey(2024, it) } + (1..12).map { MonthKey(2025, it) },
            onboardingPayslipMonths(LocalDate.of(2026, 1, 3))
        )
    }

    @Test
    fun vacationIsSetForBothYearsWhenJanuaryOfPreviousYearIsKnown() {
        val today = LocalDate.of(2026, 9, 25)
        val repo = testRepository()
        repo.savePayslip(payslip(1).copy(month = MonthKey(2025, 1)))
        repo.savePayslip(payslip(8))

        assertTrue(applyOnboardingVacation(repo, today) != null)
        val previous = repo.getVacationYearBalance(2025)!!
        val current = repo.getVacationYearBalance(2026)!!
        assertEquals(repo.getAnnualLeaveEntitlement(2025).breakdown.totalDays.toDouble(), previous.currentYearDaysTotal, 0.0)
        assertEquals(repo.getAnnualLeaveEntitlement(2026).breakdown.totalDays.toDouble(), current.currentYearDaysTotal, 0.0)
        assertTrue("prenos se računa iz 2025", repo.isVacationCarryoverAutomatic(2026))
        // Već podešen godišnji se ne dira.
        assertEquals(null, applyOnboardingVacation(repo, today))
    }

    /**
     * Listić za [index]-ti mesec perioda 1.7.2025–30.6.2026 (1 = jul 2025) sa [vacationHours] sati
     * godišnjeg (šifra 10), ostalo kao avgust 2026.
     */
    private fun previousYearSlip(index: Int, vacationHours: String): ParsedPayslip {
        val base = payslip(8)
        val items = base.items - 10 +
            (10 to PayslipItem(10, "Godisnji odmor", java.math.BigDecimal(vacationHours), null, java.math.BigDecimal("1000")))
        val month = java.time.YearMonth.of(2025, 7).plusMonths(index - 1L)
        return base.copy(month = MonthKey(month.year, month.monthValue), items = items)
    }

    @Test
    fun entitlementIsTakenFromDaysUsedFromJulyToJune() {
        val repo = testRepository()
        (1..12).forEach { repo.savePayslip(previousYearSlip(it, "16.0")) } // 12 × 2 dana = 24
        repo.savePayslip(payslip(8))
        val ku = repo.getAnnualLeaveEntitlement(2025).breakdown.totalDays
        assertTrue("iskorišćeno je više od KU (vidi i dodatke kojih nema na listiću): $ku", ku < 24)

        assertEquals(mapOf(2025 to 24, 2026 to 24), onboardingVacationTotals(repo, 2026))
        assertTrue(applyOnboardingVacation(repo, LocalDate.of(2026, 9, 25)) != null)
        assertEquals(24.0, repo.getVacationYearBalance(2026)!!.currentYearDaysTotal, 0.0)
    }

    @Test
    fun implausibleOrIncompleteUsageFallsBackToCollectiveAgreement() {
        fun ku(repo: ShiftRepository) = mapOf(
            2025 to repo.getAnnualLeaveEntitlement(2025).breakdown.totalDays,
            2026 to repo.getAnnualLeaveEntitlement(2026).breakdown.totalDays
        )
        val few = testRepository()
        (1..12).forEach { few.savePayslip(previousYearSlip(it, "8.0")) } // 12 dana — manje nego KU
        assertEquals(ku(few), onboardingVacationTotals(few, 2026))

        val many = testRepository()
        (1..12).forEach { many.savePayslip(previousYearSlip(it, "40.0")) } // 60 dana
        assertEquals(ku(many), onboardingVacationTotals(many, 2026))

        // Fali jul 2025: 11 × 2 = 22 dana je donja granica; KU je veći ili jednak, pa važi KU.
        val incomplete = testRepository()
        (2..12).forEach { incomplete.savePayslip(previousYearSlip(it, "16.0")) }
        assertEquals(ku(incomplete), onboardingVacationTotals(incomplete, 2026))
    }

    @Test
    fun missingMonthStillCountsTheRestAsLowerBound() {
        val repo = testRepository()
        // Fali jul 2025, ali ostalih 11 meseci već daje 11 × 2 + 2 = 24 dana.
        (2..12).forEach { repo.savePayslip(previousYearSlip(it, if (it == 2) "32.0" else "16.0")) }
        val vacation = onboardingVacation(repo, 2026)
        assertEquals(mapOf(2025 to 24, 2026 to 24), vacation.totals)
        assertTrue(vacation.explanation, vacation.explanation.startsWith("Pravo 24 dana izračunato preko Gmail-a"))
        assertTrue(vacation.explanation, vacation.explanation.contains("11/12 listi, fali Jul 2025"))
        assertTrue(vacation.explanation, vacation.explanation.endsWith("Može da pogreši ako je pre 1.7. uzet odmor unapred iz nove godine."))
    }

    @Test
    fun missingJuneStillUsesTheLastPeriodWhenLaterPayslipExists() {
        val repo = testRepository()
        // Jul 2025 – maj 2026 (fali jun 2026, kao kod oca), pa jul 2026: period je i dalje 1.7.2025–30.6.2026.
        (1..11).forEach { repo.savePayslip(previousYearSlip(it, if (it == 2) "32.0" else "16.0")) }
        repo.savePayslip(previousYearSlip(13, "80.0"))
        val vacation = onboardingVacation(repo, 2026)
        assertTrue(vacation.explanation, vacation.explanation.contains("od 1.7.2025. do 30.6.2026."))
        assertTrue(vacation.explanation, vacation.explanation.contains("fali Jun 2026"))
    }

    @Test
    fun explanationSaysWhenCollectiveAgreementWasUsed() {
        val repo = testRepository()
        (2..12).forEach { repo.savePayslip(previousYearSlip(it, "8.0")) }
        val vacation = onboardingVacation(repo, 2026)
        assertTrue(vacation.explanation, vacation.explanation.contains("po KU čl. 29 — iz Gmail-a nije moglo tačno"))
        assertTrue(vacation.explanation, vacation.explanation.contains("fali Jul 2025"))

        val complete = testRepository()
        (1..12).forEach { complete.savePayslip(previousYearSlip(it, "16.0")) }
        assertTrue(onboardingVacation(complete, 2026).explanation.contains("svih 12 listi"))
    }

    @Test
    fun withoutPreviousJanuaryOnlyCurrentYearIsSet() {
        val repo = testRepository()
        repo.savePayslip(payslip(8))
        assertTrue(applyOnboardingVacation(repo, LocalDate.of(2026, 9, 25)) != null)
        assertEquals(null, repo.getVacationYearBalance(2025))
        assertTrue(repo.getVacationYearBalance(2026) != null)
    }

    @Test
    fun monthRangeIsShort() {
        assertEquals("Mart – Avgust 2026", monthRangeText(listOf(MonthKey(2026, 8), MonthKey(2026, 3))))
        assertEquals("Decembar 2025 – Maj 2026", monthRangeText(listOf(MonthKey(2025, 12), MonthKey(2026, 5))))
        assertEquals("Avgust 2026", monthRangeText(listOf(MonthKey(2026, 8))))
    }

    @Test
    fun freshInstallTakesSettingsFromNewestPayslipAndSavesEachMonth() {
        val repo = testRepository()
        repo.payCoefficientValue = 1.0
        val changes = applyOnboardingPayslips(repo, listOf(payslip(8), payslip(7)))!!.changes

        assertEquals(2.82, repo.payCoefficientValue, 0.0001)
        assertEquals(226.0, repo.getHourlyValueForMonth(2026, 8), 0.001)
        assertTrue(changes.any { it.kind == PayslipChangeKind.COEFFICIENT })
        assertTrue(changes.any { it.kind == PayslipChangeKind.SENIORITY })
        assertTrue(repo.getSavedPayslip(2026, 7) != null)
        assertTrue(repo.getSavedPayslip(2026, 8) != null)
        // Drugi prolaz nema šta da menja.
        val again = applyOnboardingPayslips(repo, listOf(payslip(8)))!!.changes
        assertTrue(again.joinToString { "${it.kind} ${it.currentText} -> ${it.payslipText}" }, again.isEmpty())
    }

    @Test
    fun everyPayBaseIsWrittenButOnlyNewestIsReported() {
        val repo = testRepository()
        val payslips = (1..8).map(::payslip)
        val bases = applyOnboardingPayslips(repo, payslips)!!.changes.filter { it.kind == PayslipChangeKind.PAY_BASE }
        val withBase = payslips.filter { payslipPayBaseAnchor(it) != null }.map { it.month }
        assertEquals(listOf(withBase.last()), bases.map { it.month })
        // Stariji meseci imaju svoju osnovicu, ne pozajmljuju najnoviju.
        assertEquals(withBase, repo.getKnownPayBaseAnchors().map { it.month }.sorted())
    }

    @Test
    fun advancesForEveryPaymentMonthPreferTheAdvanceRunOverTheList() {
        val repo = testRepository()
        fun advance(workMonth: Int, paid: String) = PayslipParseResult.SideRun(
            PayslipSideRunKind.ADVANCE, MonthKey(2026, workMonth), "rb.1", java.math.BigDecimal(paid), null
        )
        val transport = PayslipParseResult.SideRun(
            PayslipSideRunKind.TRANSPORT, MonthKey(2026, 7), "rb.1", null, null, java.math.BigDecimal("240")
        )
        // Lista 06: ISPLAĆENO 37.080 (obračuna akontacije nema); lista 07 ima i obračun (14.837).
        val june = payslip(6).copy(advanceNet = java.math.BigDecimal("37080.00"))
        val july = payslip(7).copy(advanceNet = java.math.BigDecimal("99999.00"))

        val advances = advancesFromImport(repo, listOf(advance(7, "14837.00"), advance(8, "38942.00"), transport), listOf(june, july))

        assertEquals(
            mapOf(MonthKey(2026, 7) to 37_080.0, MonthKey(2026, 8) to 14_837.0, MonthKey(2026, 9) to 38_942.0),
            advances
        )
    }

    @Test
    fun listAdvanceLeavesTheRegressOut() {
        // Lista 08/2026: ISPLAĆENO 58.942 = akontacija 38.942 + regres 20.000.
        val repo = testRepository()
        val august = payslip(8).copy(advanceNet = java.math.BigDecimal("58942.00"))

        val advances = advancesFromImport(repo, emptyList(), listOf(august))

        assertEquals(mapOf(MonthKey(2026, 9) to 58_942.0 - repo.getRegressNetForMonth(MonthKey(2026, 8))), advances)
    }

    @Test
    fun newerListRaisesSeniorityOnlyFromItsMonth() {
        val repo = testRepository()
        val january = payslip(1).copy(seniorityPercent = java.math.BigDecimal("3.0"), seniorityYears = 6)
        val february = payslip(2).copy(seniorityPercent = java.math.BigDecimal("3.5"), seniorityYears = 7)
        fun load(slip: ParsedPayslip) {
            val ready = payslipPickOutcome(repo, PayslipParseResult.Success(slip), slip.month) as PayslipPickOutcome.Ready
            applyPayslipChanges(repo, ready.plan.changes)
            repo.savePayslip(slip)
        }
        load(january)
        assertEquals(6, repo.getMonthlyPayDetails(2026, 2).completedYearsOfService)
        load(february)
        assertEquals(6, repo.getMonthlyPayDetails(2026, 1).completedYearsOfService)
        assertEquals(7, repo.getMonthlyPayDetails(2026, 2).completedYearsOfService)
        assertEquals(7, repo.getMonthlyPayDetails(2026, 6).completedYearsOfService)
    }

    @Test
    fun seniorityFollowsEachListAndTheNewestAnniversary() {
        // Livnica: staž 39 → 40 u martu 2025, a 40 → 41 već u januaru 2026.
        val repo = testRepository()
        fun slip(year: Int, month: Int, years: Int) = payslip(1).copy(
            month = MonthKey(year, month),
            seniorityPercent = java.math.BigDecimal(years * 0.5).setScale(1),
            seniorityYears = years
        )
        listOf(slip(2025, 2, 39), slip(2025, 3, 40), slip(2025, 12, 40), slip(2026, 1, 41), slip(2026, 2, 41)).forEach {
            val ready = payslipPickOutcome(repo, PayslipParseResult.Success(it), it.month) as PayslipPickOutcome.Ready
            applyPayslipChanges(repo, ready.plan.changes)
            repo.savePayslip(it)
        }
        assertEquals(39, repo.getMonthlyPayDetails(2025, 2).completedYearsOfService)
        assertEquals(40, repo.getMonthlyPayDetails(2025, 3).completedYearsOfService)
        assertEquals(41, repo.getMonthlyPayDetails(2026, 1).completedYearsOfService)
        assertEquals(41, repo.getMonthlyPayDetails(2026, 3).completedYearsOfService)
        assertEquals(41, repo.getMonthlyPayDetails(2026, 12).completedYearsOfService)
        assertEquals(42, repo.getMonthlyPayDetails(2027, 1).completedYearsOfService)
    }

    @Test
    fun finalListIsAwaitedFromTheFifteenthUntilItIsLoaded() {
        val repo = testRepository()
        assertEquals(null, finalPayslipCheckMonth(repo, LocalDate.of(2026, 9, 14)))
        assertEquals(MonthKey(2026, 8), finalPayslipCheckMonth(repo, LocalDate.of(2026, 9, 18)))
        assertEquals(MonthKey(2025, 12), finalPayslipCheckMonth(repo, LocalDate.of(2026, 1, 20)))

        val august = payslip(8)
        assertEquals(null, applyArrivedFinalPayslip(repo, PayslipParseResult.Success(august), MonthKey(2026, 7)))
        val text = applyArrivedFinalPayslip(repo, PayslipParseResult.Success(august), MonthKey(2026, 8))
        assertTrue(text.orEmpty(), text!!.startsWith("Stigla lista za Avgust 2026 — učitana"))
        assertEquals(2.82, repo.payCoefficientValue, 0.0001)
        assertEquals(null, finalPayslipCheckMonth(repo, LocalDate.of(2026, 9, 25)))

        // Obrisana lista se ne vraća sama; ponovo učitana poništava brisanje.
        repo.deletePayslip(MonthKey(2026, 8))
        assertEquals(null, finalPayslipCheckMonth(repo, LocalDate.of(2026, 9, 25)))
        repo.savePayslip(august)
        assertEquals(false, repo.isPayslipDeleted(MonthKey(2026, 8)))
    }

    @Test
    fun repeatedItemLineCountsOnceAndSplitLineIsSummed() {
        val lines = requireNotNull(PAYSLIPS_2026[8])
        fun hours(extra: List<String>) =
            (parsePayslip(lines + extra) as PayslipParseResult.Success).payslip.items.getValue(1).hours
        val base = hours(emptyList())
        val itemLine = lines.first { it.startsWith("01 ") }
        assertEquals(0, base.compareTo(hours(listOf(itemLine))))
        assertEquals(0, (base + java.math.BigDecimal("8.0")).compareTo(hours(listOf("01 Rad u reziji 8.0 5000.00"))))
    }

    @Test
    fun laterRunWinsAndSameRunIsCountedOnce() {
        assertEquals(10, payslipRunNumber("rb.10"))
        assertEquals(0, payslipRunNumber(null))
        val rb2 = PayslipParseResult.SideRun(PayslipSideRunKind.TRANSPORT, MonthKey(2026, 8), "rb.2", null, transportDaily = java.math.BigDecimal("240"))
        val rb10 = rb2.copy(runLabel = "rb.10", transportDaily = java.math.BigDecimal("250"))
        assertEquals(250.0, latestTransportDaily(listOf(rb10, rb2))!!, 0.0)
        val advance = PayslipParseResult.SideRun(PayslipSideRunKind.ADVANCE, MonthKey(2026, 8), null, java.math.BigDecimal("100"))
        val other = advance.copy(paidNet = java.math.BigDecimal("50"))
        assertEquals(listOf(advance, other), sideRuns(sequenceOf(advance, advance, other), MonthKey(2026, 8)))
    }

    @Test
    fun noPayslipsChangesNothing() {
        assertEquals(null, applyOnboardingPayslips(testRepository(), emptyList()))
    }

    @Test
    fun rangeQueryCoversAllMonthsPlusWaitingForSideRuns() {
        assertEquals(
            "from:vbs.co.rs {subject:\"Obracun zarada\" from:platnilistic@vbs.co.rs} has:attachment filename:pdf " +
                "after:2026/3/1 before:2026/12/1",
            gmailPayslipRangeQuery(MonthKey(2026, 3), MonthKey(2026, 9))
        )
    }

    @Test
    fun sideRunMessagesAreKeptUntilCleared() {
        SideRunMessages.clear()
        SideRunMessages.post("a")
        SideRunMessages.post("b")
        assertEquals(listOf("a", "b"), SideRunMessages.pending.value)
        SideRunMessages.clear()
        assertTrue(SideRunMessages.pending.value.isEmpty())
    }

    @Test
    fun summaryShowsValuesFromTheAppWithoutAdvanceAmounts() {
        val repo = testRepository()
        val payslips = listOf(payslip(7), payslip(8))
        applyOnboardingPayslips(repo, payslips)
        repo.transportDailyAmountValue = 240.0
        val summary = onboardingSummary(
            repo,
            OnboardingImportResult(
                payslips = payslips,
                transportDaily = 240.0,
                advanceMonths = listOf(MonthKey(2026, 8), MonthKey(2026, 9)),
                gmailFailed = false
            )
        )
        val rows = summary.rows.toMap()
        assertEquals("Jul – Avg 2026 (2)", rows["Liste"])
        assertEquals("2,82", rows["Koeficijent"])
        assertEquals("226,00 RSD", rows["Vrednost sata"])
        assertTrue(rows.toString(), rows["Minuli rad"] == "3,5%")
        assertTrue(rows.toString(), rows["Osnovica"]!!.endsWith("(Avg 2026)"))
        assertEquals("240,00 RSD", rows["Prevoz po danu"])
        assertEquals("upisana (Avg – Sep 2026)", rows["Akontacija"])
        assertTrue(summary.rows.none { (_, value) -> value.contains("38") && value.contains("942") })
        assertTrue(summary.notes.isEmpty())

        val empty = onboardingSummary(testRepository(), OnboardingImportResult(emptyList(), null, emptyList(), true))
        assertTrue(empty.rows.isEmpty())
        assertTrue(empty.notes.toString(), empty.notes.any { it.contains("nema konačnih listi") })
        assertTrue(empty.notes.toString(), empty.notes.any { it.contains("sledećem ulasku") })
    }

    @Test
    fun recheckShowsOnlyWhatChanged() {
        val repo = testRepository()
        repo.payCoefficientValue = 2.5
        val payslips = listOf(payslip(7), payslip(8))
        val plan = buildPayslipImportPlan(repo, payslips, failures = emptyList())
        val coefficient = plan.changes.single { it.kind == PayslipChangeKind.COEFFICIENT }
        val summary = recheckSummary(
            OnboardingImportResult(
                payslips = payslips,
                transportDaily = 240.0,
                advanceMonths = emptyList(),
                gmailFailed = false,
                differences = listOf(
                    SettingDifference(coefficient.label, coefficient.currentText, coefficient.payslipText),
                    SettingDifference("Prevoz po danu", "200,00 RSD", "240,00 RSD")
                )
            )
        )
        val rows = summary.rows.toMap()
        assertEquals("2,5 → 2,82", rows["Koeficijent"])
        assertEquals("200,00 RSD → 240,00 RSD", rows["Prevoz po danu"])
        assertTrue(summary.info.toString(), summary.info.any { it.contains("Provereno 2 liste") })

        val same = recheckSummary(OnboardingImportResult(payslips, null, emptyList(), false))
        assertTrue(same.rows.isEmpty())
        assertTrue(same.info.toString(), same.info.any { it.contains("Sve vrednosti se slažu") })
    }

    @Test
    fun arrivedPayslipMessageListsEveryChange() {
        val month = MonthKey(2026, 9)
        val change = PayslipChange(
            kind = PayslipChangeKind.COEFFICIENT,
            month = month,
            label = "Koeficijent",
            currentText = "2,82",
            payslipText = "2,9",
            value = java.math.BigDecimal("2.9")
        )

        assertEquals(
            "Stigla lista za Septembar 2026 — učitana.\nIzmene:\n• Koeficijent: 2,82 → 2,9",
            finalPayslipArrivedText(month, listOf(change))
        )
        assertEquals("Stigla lista za Septembar 2026 — učitana.\nBez izmena.", finalPayslipArrivedText(month, emptyList()))
    }
}
