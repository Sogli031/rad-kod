package com.rad.shifttracker

import java.math.BigDecimal
import java.time.DayOfWeek
import java.time.LocalDate
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import com.rad.shifttracker.obracun.DEFAULT_MEAL_PRICE_PERIODS
import com.rad.shifttracker.obracun.MealSpentSource
import com.rad.shifttracker.obracun.MonthlyGrossAdditions
import com.rad.shifttracker.obracun.MonthlyNetPayBreakdown
import com.rad.shifttracker.obracun.PayBaseSelectionMode
import com.rad.shifttracker.obracun.PayBaseSource
import com.rad.shifttracker.obracun.PayCalculator
import com.rad.shifttracker.obracun.calculateMealEntitlementForAccountingMonth
import com.rad.shifttracker.obracun.calculateMonthlyNetPayBreakdown
import com.rad.shifttracker.obracun.exportSelectedMonthWorkAndPayJson
import com.rad.shifttracker.obracun.fundAbsenceMinutes
import com.rad.shifttracker.obracun.resolveMealPriceForDate
import com.rad.shifttracker.obracun.roundMoneyHalfUp
import com.rad.shifttracker.repo.applyBloodDonationLeave
import com.rad.shifttracker.repo.exportJson
import com.rad.shifttracker.repo.getMonthlyGrossAdditions
import com.rad.shifttracker.repo.getMonthlyPayDetails
import com.rad.shifttracker.repo.getNetPayBreakdown
import com.rad.shifttracker.repo.getResolvedPayBasesForWorkMonth
import com.rad.shifttracker.repo.getSummary
import com.rad.shifttracker.repo.importFromJson
import com.rad.shifttracker.repo.setDefaultPayBaseSelection
import com.rad.shifttracker.repo.setKnownPayBaseAnchor
import com.rad.shifttracker.repo.setMonthlyAbsencePenaltyGross
import com.rad.shifttracker.smene.BrigadeType
import com.rad.shifttracker.smene.DayRecord
import com.rad.shifttracker.smene.MonthKey
import com.rad.shifttracker.smene.MonthSummary
import com.rad.shifttracker.smene.OvertimeType
import com.rad.shifttracker.smene.Shift

class PayrollCorrectionsRegressionTest {
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
        assertMoney(8_388.02, breakdown.mealEntitlementGross)
        assertMoney(5_880.0, breakdown.mealSpentNetApplied)
        assertMoney(5_880.0, breakdown.mealNetDeduction)
        assertMoney(0.0, breakdown.mealUnusedNet)
        assertTrue(breakdown.mealSpendingDefaultedToEntitlement)
        assertEquals(MealSpentSource.ENTITLEMENT_ASSUMED, breakdown.mealSpentSource)
    }

    @Test
    fun `krvni dani 25 i 26 maj su placeno odsustvo a ne bolovanje`() {
        val repo = testRepository()
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
    fun `maj 2026 izostanci po fondu prate listic a manjak po danima ostaje saldo`() {
        val repo = testRepository()
        repo.set(2026, 5, 1, Shift.FIRST, 8)
        repo.set(2026, 5, 2, Shift.SECOND, 5)
        listOf(4, 5, 6, 8, 27, 28, 29).forEach { day -> repo.set(2026, 5, day, Shift.THIRD, 8) }
        repo.set(2026, 5, 7, Shift.OFF, 0)
        repo.set(2026, 5, 11, Shift.SECOND, 5)
        listOf(12, 13, 15).forEach { day -> repo.set(2026, 5, day, Shift.SECOND, 8) }
        repo.set(2026, 5, 14, Shift.SECOND, 6)
        (18..22).forEach { day -> repo.set(2026, 5, day, Shift.FIRST, 8) }
        repo.set(2026, 5, 23, Shift.FIRST, 6)
        repo.applyBloodDonationLeave(LocalDate.of(2026, 5, 25))

        val details = repo.getMonthlyPayDetails(2026, 5)
        val breakdown = repo.getNetPayBreakdown(2026, 5)
        assertEquals(168, breakdown.monthlyFundHours)
        assertEquals(131 * 60, details.regularWorkedBaseMinutes)
        assertEquals(13 * 60, details.publicHolidayWorkedBaseMinutes)
        assertEquals(6 * 60, details.overtimeMinutes)
        assertEquals(16 * 60, details.paidLeaveMinutes)
        // Manjak po danima (saldo): 07.05 ceo dan, 11.05 3h, 14.05 2h.
        assertEquals(13 * 60, details.uncoveredDeficitMinutes)
        // Listić 05/2026 šifra 95: 168 − (132 + 13 + 16) = 7h. Subota 02.05 (5h na praznik)
        // puni fond, prekovremeno 23.05 ne. App ima 1h režije manje u unosima, pa daje 8h.
        assertEquals(8 * 60, details.fundAbsenceMinutes(breakdown.monthlyFundHours))

        val export = JSONObject(repo.exportSelectedMonthWorkAndPayJson(2026, 5))
        assertEquals(8 * 60, export.getJSONObject("monthlySummary").getInt("fundAbsenceMinutes"))
        assertEquals(8 * 60, export.getJSONObject("rawCalculationDebug").getInt("fundAbsenceMinutes"))
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
                // 20 priznatih obroka × 274,00 = 5.480,00 neto (pojedeno = pripada).
                foodAllowance = 5_480.0
            ),
            grossHourlyRate = 637.32,
            monthlyFundHours = 168,
            contributionsPercent = 19.9,
            taxRelief = 34_221.0,
            taxPercent = 10.0,
            grossAdditions = MonthlyGrossAdditions(
                year = 2026,
                month = 5,
                absencePenaltyGross = BigDecimal("495.00")
            )
        )
        // Listić 05/2026 u dinar: TO2 7.817,40 = 5.480 / 0,701 na dve decimale.
        assertPayroll(may, 176_923.51, 35_207.78, 14_270.25, 127_445.48, 121_965.48)

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
                absencePenaltyGross = BigDecimal("330.00"),
                mealEntitlementNetOverride = BigDecimal("3840.00")
            )
        )
        // Listić 06/2026 ima TO2 5.478,00 (odgovara neto 3.840,08); uz potvrđeno pravo
        // 3.840,00 gross-up daje 5.477,89, pa kontrolni iznosi odstupaju 0,11 od listića.
        // Pojedeno se ne unosi: aplikacija skida celo pravo (3.840), a listić je skinuo stvarnih
        // 3.620 — za isplatu je zato 220 manje od listića (bruto i neto se slažu).
        assertPayroll(june, 113_463.61, 22_579.26, 7_924.26, 82_960.09, 79_120.09)
    }

    @Test
    fun `overtime type i Izostanci 1 prezive cuvanje ucitavanje zbir i debug export`() {
        val source = testRepository()
        source.set(
            year = 2026,
            month = 6,
            day = 3,
            shift = Shift.FIRST,
            hours = 10,
            overtimeType = OvertimeType.COMP_TIME
        )
        source.setMonthlyAbsencePenaltyGross(2026, 6, BigDecimal("330.00"))

        val restored = testRepository()
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
    fun `bolovanje prati istu osnovicu kao GO i praznik`() {
        val repo = testRepository()
        // Osnovice sa listića: 03/2026 (bolovanje 23.888,93 / 40 h / 0,65) i 05/2026 (plaćeno odsustvo).
        repo.setKnownPayBaseAnchor(2026, 3, 918.805)
        repo.setKnownPayBaseAnchor(2026, 5, 950.58)
        repo.setDefaultPayBaseSelection(PayBaseSelectionMode.KNOWN_BASE)
        (2..6).forEach { day ->
            repo.set(2026, 3, day, Shift.SICK, 8, sickPayRate = 0.65, holidayOverride = false)
        }
        repo.set(2026, 6, 1, Shift.SICK, 8, sickPayRate = 0.65, holidayOverride = false)

        val march = repo.getResolvedPayBasesForWorkMonth(2026, 3)
        val marchDetails = repo.getMonthlyPayDetails(2026, 3)
        val june = repo.getResolvedPayBasesForWorkMonth(2026, 6)
        val juneDetails = repo.getMonthlyPayDetails(2026, 6)

        assertEquals(918.805, march.sickHourlyBase.value, 0.001)
        assertEquals(PayBaseSource.KNOWN_BASE, march.sickHourlyBase.source)
        assertEquals(march.vacationHourlyBase.value, march.sickHourlyBase.value, 0.0)
        assertEquals(40, marchDetails.sickHours)
        assertEquals(23_888.93, marchDetails.grossSickPay, 0.01)
        // Novo bolovanje se plaća po tekućoj osnovici (KU čl. 66), a ne po martovskoj.
        assertEquals(950.58, june.sickHourlyBase.value, 0.001)
        assertEquals(8 * 950.58 * 0.65, juneDetails.grossSickPay, 0.01)
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
