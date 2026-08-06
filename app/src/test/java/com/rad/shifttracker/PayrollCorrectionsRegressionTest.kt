package com.rad.shifttracker

import java.math.BigDecimal
import java.time.DayOfWeek
import java.time.LocalDate
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PayrollCorrectionsRegressionTest {
    @Test
    fun `jun 2026 potvrđeno pravo i rucna potrosnja ostavljaju razliku u plati`() {
        val breakdown = calculateMonthlyNetPayBreakdown(
            summary = MonthSummary(
                year = 2026,
                month = 6,
                totalPay = 100_000.0,
                foodAllowance = 3_840.0
            ),
            grossHourlyRate = 637.32,
            monthlyFundHours = 176,
            contributionsPercent = 19.9,
            taxRelief = 34_221.0,
            taxPercent = 10.0,
            grossAdditions = MonthlyGrossAdditions(
                year = 2026,
                month = 6,
                mealSpentNet = 3_620.0,
                mealEntitlementNetOverride = BigDecimal("3840.00")
            )
        )

        assertEquals(MANUAL_SPENDING_SAFE_DEFAULT_MODE, breakdown.mealCalculationMode)
        assertEquals(MealEntitlementSource.CONFIRMED_MONTHLY_OVERRIDE, breakdown.mealEntitlementSource)
        assertMoney(3_840.00, breakdown.mealEntitlementNet)
        assertMoney(5_478.00, breakdown.mealEntitlementGross)
        assertMoney(3_620.00, breakdown.mealSpentNetApplied)
        assertMoney(3_620.00, breakdown.mealNetDeduction)
        assertMoney(220.00, breakdown.mealUnusedNet)
        assertMoney(105_478.00, breakdown.grossTotal)
        assertTrue(!breakdown.mealSpendingDefaultedToEntitlement)
    }

    @Test
    fun `bez unete potrosnje podrazumeva se celo automatsko pravo`() {
        val breakdown = calculateMonthlyNetPayBreakdown(
            summary = MonthSummary(
                year = 2026,
                month = 6,
                totalPay = 100_000.0,
                foodAllowance = 5_880.0
            ),
            grossHourlyRate = 637.32,
            monthlyFundHours = 176,
            contributionsPercent = 19.9,
            taxRelief = 34_221.0,
            taxPercent = 10.0
        )

        assertMoney(5_880.0, breakdown.mealEntitlementNet)
        assertMoney(8_388.0, breakdown.mealEntitlementGross)
        assertMoney(5_880.0, breakdown.mealSpentNetApplied)
        assertMoney(5_880.0, breakdown.mealNetDeduction)
        assertMoney(0.0, breakdown.mealUnusedNet)
        assertTrue(breakdown.mealSpendingDefaultedToEntitlement)
        assertEquals(MealSpentSource.ENTITLEMENT_ASSUMED, breakdown.mealSpentSource)
    }

    @Test
    fun `krvni dani 25 i 26 maj su placeno odsustvo a ne bolovanje`() {
        val repo = ShiftRepository()
        repo.setKnownPayBaseAnchor(2026, 5, 950.58)
        repo.setDefaultPayBaseSelection(PayBaseSelectionMode.KNOWN_BASE)
        repo.applyBloodDonationLeave(LocalDate.of(2026, 5, 25))

        val details = repo.getMonthlyPayDetails(2026, 5)
        assertEquals(16, details.paidLeaveHours)
        assertEquals(0, details.sickHours)
        assertEquals(15_209.29, details.grossPaidLeave, 0.02)

        val export = JSONObject(repo.exportSelectedMonthWorkAndPayJson(2026, 5))
        val day = export.getJSONArray("monthWorkEntries").getJSONObject(24)
        val daily = day.getJSONObject("dailyCalculationBreakdown")
        assertEquals("Plaćeno odsustvo – dobrovoljno davanje krvi", day.getString("shiftName"))
        assertEquals(480, daily.getInt("paidLeaveMinutes"))
        assertEquals(0, daily.getInt("sickLeaveMinutes"))
        assertEquals("paidLeave", daily.getJSONArray("payCategoriesApplied").getString(0))
    }

    @Test
    fun `jun placeni i kompenzacioni dodatni rad se ne placaju dvaput`() {
        val config = CONFIG.pay.copy(foodPerDay = 0.0)
        val calculator = PayCalculator(config)
        val records = junePaidAndCompTimeRecords()
        val summary = calculator.calculateMonthSummary(
            records = records,
            baseRate = 637.32,
            startingCompBalanceMinutes = 0,
            yearsOfService = 7,
            brigadeType = BrigadeType.TROBRIGADA,
            summaryYear = 2026,
            summaryMonth = 6
        )
        val net = calculateMonthlyNetPayBreakdown(
            summary = summary,
            grossHourlyRate = 637.32,
            monthlyFundHours = 176,
            contributionsPercent = 19.9,
            taxRelief = 34_221.0,
            taxPercent = 10.0,
            includeRegress = false
        )
        val details = calculator.calculateMonthlyPayDetails(
            records = records,
            summary = summary,
            baseRate = 637.32,
            netBreakdown = net,
            brigadeType = BrigadeType.TROBRIGADA
        )

        assertEquals(6, summary.paidOvertimeHours)
        assertEquals(5, summary.compTimeEarnedHours)
        assertEquals(139, summary.regularOrCoveredPaidHours)
        assertEquals(37 * 60, summary.uncoveredDeficitMinutes)
        assertMoney(3_823.92, details.overtimeBaseGross)
        assertMoney(1_567.81, details.overtimePremiumGross)
        assertMoney(139 * 637.32, summary.seniorityEligibleBaseGross)
        assertMoney(3_100.56, summary.minuliRadBonus)
    }

    @Test
    fun `maj i jun prate kontrolne bruto i neto iznose sa Izostanci 1`() {
        val mayWorkGross = money(132 * 637.32) +
            money(13 * 637.32) +
            15_209.29 +
            money(56 * 637.32 * 0.41) +
            money(6 * 637.32) +
            money(6 * 637.32 * 0.41) +
            money(13 * 637.32 * 1.23)
        val may = calculateMonthlyNetPayBreakdown(
            summary = MonthSummary(
                year = 2026,
                month = 5,
                totalPay = mayWorkGross,
                minuliRadBonus = money(145 * 637.32 * 0.035),
                foodAllowance = BigDecimal("7817.40").multiply(BigDecimal("0.701")).toDouble()
            ),
            grossHourlyRate = 637.32,
            monthlyFundHours = 168,
            contributionsPercent = 19.9,
            taxRelief = 34_221.0,
            taxPercent = 10.0,
            grossAdditions = MonthlyGrossAdditions(
                year = 2026,
                month = 5,
                mealSpentNet = 5_480.0,
                absencePenaltyGross = BigDecimal("495.00")
            )
        )
        assertPayroll(may, 176_923.11, 35_207.70, 14_270.21, 127_445.20, 121_965.20)

        val juneWorkGross = money(139 * 637.32) +
            money(6 * 637.32) +
            money(43 * 637.32 * 0.41) +
            money(6 * 637.32 * 0.41)
        val june = calculateMonthlyNetPayBreakdown(
            summary = MonthSummary(
                year = 2026,
                month = 6,
                totalPay = juneWorkGross,
                minuliRadBonus = 3_100.56,
                foodAllowance = BigDecimal("5478.00").multiply(BigDecimal("0.701")).toDouble()
            ),
            grossHourlyRate = 637.32,
            monthlyFundHours = 176,
            contributionsPercent = 19.9,
            taxRelief = 34_221.0,
            taxPercent = 10.0,
            grossAdditions = MonthlyGrossAdditions(
                year = 2026,
                month = 6,
                mealSpentNet = 3_620.0,
                absencePenaltyGross = BigDecimal("330.00"),
                mealEntitlementNetOverride = BigDecimal("3840.00")
            )
        )
        assertPayroll(june, 113_463.72, 22_579.28, 7_924.27, 82_960.17, 79_340.17)
    }

    @Test
    fun `overtime type i Izostanci 1 prezive cuvanje ucitavanje zbir i debug export`() {
        val source = ShiftRepository()
        source.set(
            year = 2026,
            month = 6,
            day = 3,
            shift = Shift.FIRST,
            hours = 10,
            overtimeType = OvertimeType.COMP_TIME
        )
        source.setMonthlyAbsencePenaltyGross(2026, 6, BigDecimal("330.00"))

        val restored = ShiftRepository()
        assertTrue(restored.importFromJson(source.exportJson()))
        assertEquals(OvertimeType.COMP_TIME, restored.get(2026, 6, 3)?.overtimeType)
        assertEquals(
            0,
            BigDecimal("330.00").compareTo(restored.getMonthlyGrossAdditions(2026, 6).absencePenaltyGross)
        )
        assertEquals(120, restored.getSummary(2026, 6).compTimeEarnedMinutes)

        val export = JSONObject(restored.exportSelectedMonthWorkAndPayJson(2026, 6))
        val day = export.getJSONArray("monthWorkEntries").getJSONObject(2)
        assertEquals("COMP_TIME", day.getString("overtimeType"))
        assertEquals(1, day.getJSONObject("rawRecord").getInt("overtime_type"))
        assertMoney(
            330.0,
            export.getJSONObject("grossNegativeAdjustmentsBreakdown").getDouble("absencePenaltyGross")
        )
    }

    @Test
    fun `bolovanje koristi svoju osnovicu i kad je poznata osnovica za GO drugacija`() {
        val repo = ShiftRepository()
        repo.setKnownPayBaseAnchor(2026, 5, 950.58)
        repo.setDefaultPayBaseSelection(PayBaseSelectionMode.KNOWN_BASE)
        // Listić 03/2026: 23.888,93 / 40 h = 597,22 = 65% × 918,81.
        (2..6).forEach { day ->
            repo.set(2026, 3, day, Shift.SICK, 8, sickPayRate = 0.65, holidayOverride = false)
        }

        val resolved = repo.getResolvedPayBasesForWorkMonth(2026, 3)
        val details = repo.getMonthlyPayDetails(2026, 3)

        assertEquals(918.81, resolved.sickHourlyBase.value, 0.001)
        assertEquals(PayBaseSource.DEFAULT, resolved.sickHourlyBase.source)
        assertEquals(40, details.sickHours)
        // Listić zaokružuje satnicu na 597,22, pa razlika na 40 h ostaje ispod dinara.
        assertEquals(23_888.93, details.grossSickPay, 0.2)
    }

    @Test
    fun `maj 2026 koristi cenu obroka od 274 dinara pre nove cene`() {
        assertEquals(
            0,
            BigDecimal("274.00").compareTo(
                resolveMealPriceForDate(LocalDate.of(2026, 5, 20), DEFAULT_MEAL_PRICE_PERIODS)
            )
        )
        assertEquals(
            0,
            BigDecimal("240.00").compareTo(
                resolveMealPriceForDate(LocalDate.of(2026, 6, 20), DEFAULT_MEAL_PRICE_PERIODS)
            )
        )

        // 20 priznatih obroka × 274,00 = 5.480,00 neto sa listića 05/2026.
        val records = (1..20).map { day -> DayRecord(2026, 5, day, Shift.FIRST, 8) }
        val calculation = calculateMealEntitlementForAccountingMonth(
            records = records,
            month = MonthKey(2026, 5),
            pricePeriods = DEFAULT_MEAL_PRICE_PERIODS
        )
        assertEquals(20, calculation.recognizedMealCount)
        assertEquals(0, BigDecimal("5480.00").compareTo(calculation.mealEntitlementNet))
    }

    private fun junePaidAndCompTimeRecords(): List<DayRecord> {
        val records = mutableListOf(
            DayRecord(
                2026, 6, 1, Shift.FIRST, 8,
                shift2 = Shift.SECOND,
                hours2 = 6,
                overtimeType = OvertimeType.PAID
            ),
            DayRecord(
                2026, 6, 3, Shift.FIRST, 8,
                shift2 = Shift.SECOND,
                hours2 = 2,
                overtimeType = OvertimeType.COMP_TIME
            ),
            DayRecord(
                2026, 6, 25, Shift.FIRST, 8,
                shift2 = Shift.SECOND,
                hours2 = 3,
                overtimeType = OvertimeType.COMP_TIME
            )
        )
        val remaining = (1..30).filter { day ->
            LocalDate.of(2026, 6, day).dayOfWeek !in setOf(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY) &&
                day !in setOf(1, 3, 25)
        }
        remaining.take(13).forEach { day -> records += DayRecord(2026, 6, day, Shift.FIRST, 8) }
        records += DayRecord(2026, 6, remaining[13], Shift.FIRST, 6)
        remaining.drop(14).forEach { day -> records += DayRecord(2026, 6, day, Shift.OFF, 0) }
        return records
    }

    private fun assertPayroll(
        breakdown: MonthlyNetPayBreakdown,
        gross: Double,
        contributions: Double,
        tax: Double,
        netBeforeDeductions: Double,
        displayPay: Double
    ) {
        assertMoney(gross, breakdown.grossTotal)
        assertMoney(contributions, breakdown.employeeContributions)
        assertMoney(tax, breakdown.tax)
        assertMoney(netBeforeDeductions, breakdown.netPayBeforeDeductions)
        assertMoney(displayPay, breakdown.displayPay)
    }

    private fun assertMoney(expected: Double, actual: Double) {
        assertEquals(money(expected), money(actual), 0.0)
    }

    private fun money(value: Double): Double = roundMoneyHalfUp(value)
}
