package com.rad.shifttracker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PayslipListaMonthTest {

    private fun august(): ParsedPayslip =
        (parsePayslip(AUGUST_2026) as PayslipParseResult.Success).payslip

    @Test
    fun savedPayslipIsKeptPerMonth() {
        val repo = testRepository()
        assertNull(repo.getSavedPayslip(2026, 8))

        repo.savePayslip(august())

        assertEquals(august(), repo.getSavedPayslip(2026, 8))
        assertNull("drugi mesec nema listić", repo.getSavedPayslip(2026, 7))
    }

    @Test
    fun backupThenRestoreOnNewInstallKeepsSavedPayslip() {
        val repo = testRepository()
        repo.savePayslip(august())

        val restored = testRepository()
        assertTrue(restored.importFromJson(repo.exportJson()))

        assertEquals(august(), restored.getSavedPayslip(2026, 8))
        assertEquals(repo.exportJson(), restored.exportJson())
    }

    @Test
    fun emptyNewInstallDoesNotDropPayslipFromCloudArchive() {
        val original = testRepository()
        original.savePayslip(august())
        val archive = original.exportJson()

        val freshInstall = testRepository()
        val merged = freshInstall.buildCloudBackup(archive).json

        val restored = testRepository()
        assertTrue(restored.importFromJson(merged))
        assertNotNull(restored.getSavedPayslip(2026, 8))
    }

    @Test
    fun reconciliationComparesPayslipWithEnteredDays() {
        val repo = testRepository()
        val check = buildPayslipReconciliation(repo, august())

        assertEquals("U ovom mesecu nema upisanih dana.", payslipCompareVerdict(check, DarkExtendedColors).first)
        assertTrue(check.rows.any { it.label == "Godišnji odmor (10)" && !it.matches })
    }

    @Test
    fun savedPayslipSetsPayBaseForItsMonthWhenMissing() {
        // Jul 2026: sačuvan listić, a osnovica uneta samo za avgust → GO je računat sa 918,81.
        val repo = testRepository()
        repo.setKnownPayBaseAnchor(2026, 8, 954.24)
        val july = august().copy(month = MonthKey(2026, 7))
        repo.savePayslip(july)
        assertEquals(954.24, repo.getKnownPayBaseAnchorForWorkMonth(2026, 7)!!.grossHourlyBase, 1e-9)
        assertEquals(MonthKey(2026, 7), repo.getKnownPayBaseAnchorForWorkMonth(2026, 7)!!.month)

        // Već uneta osnovica za mesec se ne menja.
        repo.setKnownPayBaseAnchor(2026, 7, 945.60)
        repo.savePayslip(july)
        assertEquals(945.60, repo.getKnownPayBaseAnchorForWorkMonth(2026, 7)!!.grossHourlyBase, 1e-9)
    }

    @Test
    fun payslipSavedWithoutPayBaseGetsItOnLoad() {
        val original = testRepository()
        original.savePayslip(august())
        original.removeKnownPayBaseAnchor(2026, 8)
        assertTrue(original.getKnownPayBaseAnchors().isEmpty())

        val restored = testRepository()
        assertTrue(restored.importFromJson(original.exportJson()))
        assertEquals(954.24, restored.getKnownPayBaseAnchorForWorkMonth(2026, 8)!!.grossHourlyBase, 1e-9)
    }

    @Test
    fun payslipSavedByOldVersionAsksForReload() {
        val old = august().copy(mealGross = null, mealDeduction = null)
        val check = buildPayslipReconciliation(testRepository(), old)
        assertTrue(check.differences.any { it.text.contains("učitaj je ponovo") })
        assertTrue(buildPayslipReconciliation(testRepository(), august()).differences.none { it.text.contains("učitaj je ponovo") })
    }

    @Test
    fun mismatchCountUsesSerbianPlural() {
        assertEquals("1 stavka se ne slaže", payslipMismatchCountText(1))
        assertEquals("3 stavke se ne slažu", payslipMismatchCountText(3))
        assertEquals("5 stavki se ne slaže", payslipMismatchCountText(5))
        assertEquals("12 stavki se ne slaže", payslipMismatchCountText(12))
        assertEquals("21 stavka se ne slaže", payslipMismatchCountText(21))
    }

    @Test
    fun payslipForAnotherMonthIsRejected() {
        val august = MonthKey(2026, 8)
        assertNull(payslipWrongMonthError(august, august))
        assertEquals(
            "Pogrešna lista: izabrani PDF je za Jul 2026, a otvoren je Avgust 2026. " +
                "Lista nije učitana — otvori Jul 2026 u kalendaru pa je učitaj tamo.",
            payslipWrongMonthError(MonthKey(2026, 7), august)
        )
        assertNotNull(payslipWrongMonthError(MonthKey(2026, 10), august))
    }

    @Test
    fun unfinishedMonthHasNoPayslipYet() {
        val today = java.time.LocalDate.of(2026, 9, 26)
        assertTrue(isPayslipMonthUnfinished(MonthKey(2026, 9), today))
        assertTrue(isPayslipMonthUnfinished(MonthKey(2026, 10), today))
        assertTrue(!isPayslipMonthUnfinished(MonthKey(2026, 8), today))
        // Lista za septembar može da stigne od 15. oktobra: do 14.10. dugme je isključeno, od 15.10. uključeno.
        assertTrue(isPayslipMonthUnfinished(MonthKey(2026, 9), java.time.LocalDate.of(2026, 10, 3)))
        assertTrue(isPayslipMonthUnfinished(MonthKey(2026, 9), java.time.LocalDate.of(2026, 10, 14)))
        assertTrue(!isPayslipMonthUnfinished(MonthKey(2026, 9), java.time.LocalDate.of(2026, 10, 15)))
        // Prelaz godine: lista za decembar od 15. januara.
        assertTrue(isPayslipMonthUnfinished(MonthKey(2026, 12), java.time.LocalDate.of(2027, 1, 14)))
        assertTrue(!isPayslipMonthUnfinished(MonthKey(2026, 12), java.time.LocalDate.of(2027, 1, 15)))
        assertEquals("Lista za Septembar 2026 još nije stigla.", payslipNotArrivedText(MonthKey(2026, 9)))
    }

    @Test
    fun olderPayslipDoesNotProposeGlobalSettings() {
        val repo = testRepository()
        repo.payCoefficientValue = 2.5

        val newest = buildPayslipImportPlan(repo, listOf(august()), emptyList())
        val older = buildPayslipImportPlan(repo, listOf(august()), emptyList(), includeGlobalSettings = false)

        assertTrue(newest.changes.any { it.kind == PayslipChangeKind.COEFFICIENT })
        assertTrue(older.changes.none { it.kind == PayslipChangeKind.COEFFICIENT })
    }

    @Test
    fun latestSavedPayslipMonthIsTracked() {
        val repo = testRepository()
        assertNull(repo.getLatestSavedPayslipMonth())
        repo.savePayslip(august())
        assertEquals(MonthKey(2026, 8), repo.getLatestSavedPayslipMonth())
    }

    private val grossRow = PayslipCheckRow(PAYSLIP_GROSS_ROW, "134.740,34", "170.337,29", matches = false)
    private val augustGross = java.math.BigDecimal("170337.29")

    private fun texts(items: List<PayslipDifference>) = items.map { it.text }

    @Test
    fun listIsEmptyWhenEverythingMatches() {
        val rows = listOf(PayslipCheckRow(PAYSLIP_GROSS_ROW, "1,00", "1,00", matches = true))
        assertTrue(payslipDifferences(rows, august().extras, 1.0, java.math.BigDecimal.ONE).isEmpty())
    }

    @Test
    fun listNamesOnlyTheExtrasThatExplainGrossDifference() {
        // Stimulacije + KIRU + Regres = 35.596,95; „Izostanci 1“ je već u upisanim danima.
        val items = payslipDifferences(listOf(grossRow), august().extras, 134_740.34, augustGross)
        assertEquals(listOf("Stimulacije: 10.699,00 RSD", "KIRU: -3.632,72 RSD", "Regres: 28.530,67 RSD"), texts(items))
    }

    @Test
    fun julyListHasMealAndExtrasWithoutSentences() {
        // Listić 07/2026: svi časovi se slažu, bruto 189.060,84 naspram 179.929,66 u aplikaciji.
        val extras = listOf(
            PayslipExtraItem(ABSENCE_ROUNDING_EXTRA_LABEL, java.math.BigDecimal("-330")),
            PayslipExtraItem("Stimulacije", java.math.BigDecimal("6823"))
        )
        val rows = listOf(
            PayslipCheckRow("Rad u režiji (01)", "85 h", "85 h", matches = true, isHours = true),
            PayslipCheckRow(PAYSLIP_MEAL_ROW, "1.491,63", "4.129,81", matches = false, difference = 2_638.18),
            PayslipCheckRow(PAYSLIP_GROSS_ROW, "179.929,66", "189.060,84", matches = false, difference = 9_131.18)
        )
        val items = payslipDifferences(rows, extras, 179_929.66, java.math.BigDecimal("189060.84"))
        assertEquals(
            listOf("$PAYSLIP_MEAL_ROW: 2.638,18 RSD", "Izostanci 1: -330,00 RSD", "Stimulacije: 6.823,00 RSD"),
            texts(items)
        )
    }

    @Test
    fun extrasAlreadyInAppAreNotCountedTwice() {
        // Avgust 2026 posle „Popuni iz liste“: aplikacija već ima regres i „Izostanci 1“ (478).
        val extras = payslipExtrasBeyondApp(august().extras, 28_530.670_470_756, java.math.BigDecimal(478))
        assertEquals(listOf("Stimulacije", "KIRU"), extras.map { it.label })

        // Manje pojeden topli obrok je zelen i nije na spisku, ali ulazi u razlaganje bruta.
        val rows = listOf(
            payslipMealRow(7_189.73, java.math.BigDecimal("6191.16"), overspend = null),
            PayslipCheckRow(PAYSLIP_GROSS_ROW, "164.269,52", "170.337,29", matches = false, difference = 6_067.77)
        )
        assertEquals(
            listOf("Stimulacije: 10.699,00 RSD", "KIRU: -3.632,72 RSD"),
            texts(payslipDifferences(rows, extras, 164_269.52, augustGross))
        )
    }

    @Test
    fun regressOnlyInAppCountsAgainstIt() {
        val extras = payslipExtrasBeyondApp(emptyList(), 28_530.67, java.math.BigDecimal.ZERO)
        assertEquals(listOf(PayslipExtraItem("Regres", java.math.BigDecimal("-28530.67"))), extras)
    }

    @Test
    fun mealRowIsGreenWhenLessWasEatenAndRedWhenMore() {
        val less = payslipMealRow(7_189.73, java.math.BigDecimal("6191.16"), overspend = null)
        assertEquals(PayslipRowTone.GOOD, less.tone)
        assertTrue(less.matches)

        val more = payslipMealRow(7_589.16, java.math.BigDecimal("7589.16"), overspend = 210.0)
        assertEquals(PayslipRowTone.ALERT, more.tone)
        assertTrue(!more.matches)
        val items = payslipDifferences(listOf(more), emptyList(), 0.0, null, mealOverspend = 210.0)
        assertEquals(listOf(PayslipDifference("Topli obrok, pojedeno više: 210,00 RSD", isAlert = true)), items)

        // Listić priznaje više obroka nego aplikacija: obična razlika.
        val slipMore = payslipMealRow(4_000.0, java.math.BigDecimal("6191.16"), overspend = null)
        assertEquals(PayslipRowTone.DEFAULT, slipMore.tone)
        assertTrue(!slipMore.matches)
    }

    private val julyMealLines = AUGUST_2026 + "T08261 Topli obrok 0.00 0 4,340.00"

    @Test
    fun parserReadsMealSeniorityAmountAndMealDeduction() {
        val slip = (parsePayslip(julyMealLines) as PayslipParseResult.Success).payslip
        assertEquals(java.math.BigDecimal("6191.16"), slip.mealGross)
        assertEquals(java.math.BigDecimal("2542.91"), slip.seniorityAmount)
        assertEquals(java.math.BigDecimal("4340.00"), slip.mealDeduction)
        assertEquals(4_340.0, payslipMealEntitlementNet(slip)!!, 0.001)
        // Pojedeno tačno koliko je priznato: nema prekoračenja.
        assertNull(payslipMealOverspend(slip))

        val restored = payslipFromJsonOrNull(payslipToJson(slip))!!
        assertEquals(slip.mealGross, restored.mealGross)
        assertEquals(slip.seniorityAmount, restored.seniorityAmount)
        assertEquals(slip.mealDeduction, restored.mealDeduction)
    }

    @Test
    fun overspendingMealHasAmount() {
        // Listić 02/2026: TO2 7.589,16 (= 5.320,00 neto), obustava 5.530,00.
        val february = august().copy(
            mealGross = java.math.BigDecimal("7589.16"),
            mealDeduction = java.math.BigDecimal("5530.00")
        )
        assertEquals(210.0, payslipMealOverspend(february)!!, 0.01)
        // Jun 2026: pojedeno manje (3.620 od 3.840) — nema prekoračenja.
        val june = august().copy(
            mealGross = java.math.BigDecimal("5478"),
            mealDeduction = java.math.BigDecimal("3620.00")
        )
        assertNull(payslipMealOverspend(june))
    }

    @Test
    fun listHasMismatchedHourRowsAndNoUnknownRestWhenHoursDiffer() {
        val rows = listOf(
            PayslipCheckRow("Rad u režiji (01)", "114 h", "114 h", matches = true, isHours = true),
            PayslipCheckRow("Godišnji odmor (10)", "8 h", "16 h", matches = false, isHours = true),
            grossRow
        )
        assertEquals(listOf("Godišnji odmor (10): aplikacija 8 h, lista 16 h"), texts(payslipDifferences(rows, emptyList(), 134_740.34, augustGross)))
    }

    @Test
    fun unexplainedRestIsListedWithAmount() {
        val items = payslipDifferences(listOf(grossRow), emptyList(), 134_740.34, augustGross)
        assertEquals(listOf("Nepoznata razlika: 35.596,95 RSD (vrednost sata ili osnovica)"), texts(items))
    }

    @Test
    fun payoutRowIsNotListedAsSeparateItem() {
        val rows = listOf(
            grossRow,
            PayslipCheckRow(PAYSLIP_PAYOUT_ROW, "1,00", "2,00", matches = false, difference = 1.0)
        )
        val items = payslipDifferences(rows, august().extras, 134_740.34, augustGross)
        assertTrue(texts(items).none { it.startsWith("Za isplatu") })
    }

    private fun totalRows() = listOf(
        grossRow,
        PayslipCheckRow(PAYSLIP_NET_ROW, "100.000,00", "110.000,00", matches = false, difference = 10_000.0),
        PayslipCheckRow(PAYSLIP_PAYOUT_ROW, "90.000,00", "100.000,00", matches = false, difference = 10_000.0)
    )

    @Test
    fun totalsAloneAreNotAMismatch() {
        // Bruto, neto i za isplatu se razlikuju zbog stimulacija i KIRU — sve stvarne stavke se slažu.
        val rows = listOf(PayslipCheckRow("Rad u režiji (01)", "114 h", "114 h", matches = true, isHours = true)) + totalRows()
        val check = PayslipReconciliation(MonthKey(2026, 8), hasAppData = true, rows = rows)

        assertTrue(rows.filter { it.label in PAYSLIP_TOTAL_ROWS }.all { it.isTotal })
        assertEquals("Sve se slaže sa listom.", payslipCompareVerdict(check, DarkExtendedColors).first)
    }

    @Test
    fun onlyRealItemsAreCountedAsMismatches() {
        val rows = listOf(
            PayslipCheckRow("Godišnji odmor (10)", "8 h", "16 h", matches = false, isHours = true),
            PayslipCheckRow("Minuli rad", "3%", "3,5%", matches = false)
        ) + totalRows()
        val check = PayslipReconciliation(MonthKey(2026, 8), hasAppData = true, rows = rows)

        assertEquals("2 stavke se ne slažu (označeno sa ✗).", payslipCompareVerdict(check, DarkExtendedColors).first)
    }

    @Test
    fun grossExplanationIsNeutralNoteAndItemDifferenceIsWarning() {
        val rows = listOf(
            PayslipCheckRow("Godišnji odmor (10)", "8 h", "8 h", matches = true, isHours = true),
            grossRow
        )
        val items = payslipDifferences(rows, august().extras, 134_740.34, augustGross)
        assertTrue(items.isNotEmpty())
        assertTrue("stimulacije, KIRU, regres su napomena", items.all { it.isNote })

        val withMismatch = payslipDifferences(
            listOf(PayslipCheckRow("Godišnji odmor (10)", "8 h", "16 h", matches = false, isHours = true), grossRow),
            emptyList(),
            134_740.34,
            augustGross
        )
        assertEquals(listOf(false), withMismatch.map { it.isNote })
    }

    @Test
    fun replacingPayslipKeepsOnlyTheNewOne() {
        val repo = testRepository()
        repo.savePayslip(august().copy(runLabel = "rb.2/konačna"))
        repo.savePayslip(august())

        assertEquals("rb.3/konačna", repo.getSavedPayslip(2026, 8)?.runLabel)
        val restored = testRepository()
        assertTrue(restored.importFromJson(repo.exportJson()))
        assertEquals("rb.3/konačna", restored.getSavedPayslip(2026, 8)?.runLabel)
    }

    @Test
    fun pickedPdfForOpenMonthIsReadyAndOtherMonthIsRejected() {
        val repo = testRepository()
        val parsed = PayslipParseResult.Success(august())

        assertTrue(payslipPickOutcome(repo, parsed, MonthKey(2026, 8)) is PayslipPickOutcome.Ready)
        val rejected = payslipPickOutcome(repo, parsed, MonthKey(2026, 9))
        assertTrue(rejected is PayslipPickOutcome.Rejected)
        assertNull("odbijen listić se ne čuva", repo.getSavedPayslip(2026, 8))
    }

    @Test
    fun bottomBarHasOnlyLista() {
        assertEquals("Lista", bottomBarButtonLabels().basePay)
    }

    @Test
    fun calendarShowsVacationAndPaidLeaveInTwoLines() {
        val vacation = DayRecord(year = 2026, month = 7, day = 6, shift = Shift.VACATION, hours = 8)
        assertEquals("Godišnji\nodmor", calendarDayDetailText(vacation, isHolidayDay = false, workedHoliday = false))
        assertEquals(
            "Bolo-\nvanje",
            calendarDayDetailText(
                DayRecord(year = 2026, month = 7, day = 8, shift = Shift.SICK, hours = 8),
                isHolidayDay = false,
                workedHoliday = false
            )
        )
        val paidLeave = DayRecord(
            year = 2026,
            month = 7,
            day = 7,
            shift = Shift.SICK,
            hours = 8,
            source = DayRecordSource.PAID_LEAVE
        )
        assertEquals("Plaćeno\nodsustvo", calendarDayDetailText(paidLeave, isHolidayDay = false, workedHoliday = false))
    }
}
