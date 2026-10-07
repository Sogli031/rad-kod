package com.rad.shifttracker

import java.math.BigDecimal
import java.time.LocalDate
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import com.rad.shifttracker.obracun.DEFAULT_MEAL_PRICE_PERIODS
import com.rad.shifttracker.obracun.MANUAL_SPENDING_SAFE_DEFAULT_MODE
import com.rad.shifttracker.obracun.MealEntitlementSource
import com.rad.shifttracker.obracun.MonthlyGrossAdditions
import com.rad.shifttracker.obracun.calculateMealEntitlementForAccountingMonth
import com.rad.shifttracker.obracun.calculateMonthlyNetPayBreakdown
import com.rad.shifttracker.izvoz.exportSelectedMonthWorkAndPayJson
import com.rad.shifttracker.repo.exportJson
import com.rad.shifttracker.repo.getMonthlyGrossAdditions
import com.rad.shifttracker.repo.getNetPayBreakdown
import com.rad.shifttracker.repo.importFromJson
import com.rad.shifttracker.repo.setMonthlyActualFoodSpentNet
import com.rad.shifttracker.repo.setMonthlyMealEntitlementNetOverride
import com.rad.shifttracker.smene.DayRecord
import com.rad.shifttracker.smene.MonthKey
import com.rad.shifttracker.obracun.MonthSummary
import com.rad.shifttracker.smene.Shift

class MealEntitlementTest {
    @Test
    fun `manje od sest sati nema obrok a dodatni rad preko dva sata daje drugi`() {
        val records = listOf(
            DayRecord(2026, 6, 1, Shift.FIRST, 3),
            DayRecord(2026, 6, 2, Shift.FIRST, 5),
            DayRecord(2026, 6, 3, Shift.FIRST, 10),
            DayRecord(2026, 6, 4, Shift.FIRST, 11),
            DayRecord(2026, 6, 5, Shift.SICK, 8),
            DayRecord(2026, 6, 6, Shift.VACATION, 8),
            DayRecord(2026, 6, 7, Shift.OFF, 0)
        )

        val calculation = calculateMealEntitlementForAccountingMonth(
            records,
            MonthKey(2026, 6),
            DEFAULT_MEAL_PRICE_PERIODS
        )

        assertEquals(3, calculation.recognizedMealCount)
        assertEquals(0, BigDecimal("720.00").compareTo(calculation.mealEntitlementNet))
        assertTrue(calculation.rejectedMeals.any { it.reason == "EFFECTIVE_WORK_UNDER_6_HOURS" })
        assertTrue(calculation.rejectedMeals.any { it.reason == "SICK_LEAVE_WITHOUT_WORK" })
        assertTrue(calculation.rejectedMeals.any { it.reason == "VACATION_WITHOUT_WORK" })
        assertTrue(calculation.rejectedMeals.any { it.reason == "DAY_OFF_WITHOUT_WORK" })
    }

    @Test
    fun `treca smena prelazi u sledeci mesec i koristi cenu obracunskog datuma`() {
        val calculation = calculateMealEntitlementForAccountingMonth(
            records = listOf(DayRecord(2026, 6, 30, Shift.THIRD, 8)),
            month = MonthKey(2026, 7),
            pricePeriods = DEFAULT_MEAL_PRICE_PERIODS
        )

        assertEquals(1, calculation.recognizedMealCount)
        assertEquals(LocalDate.of(2026, 7, 1), calculation.recognizedMealDates.single())
        assertEquals(0, BigDecimal("280.00").compareTo(calculation.mealEntitlementNet))
    }

    @Test
    fun `jun 2026 ignoriše procenjenih 21 obrok i koristi potvrđeni override`() {
        val repo = testRepository()
        (1..21).forEach { day -> repo.set(2026, 6, day, Shift.FIRST, 8, holidayOverride = false) }
        // Stari ručni unos potrošnje se ignoriše: pojedeno = pripada.
        repo.setMonthlyActualFoodSpentNet(2026, 6, 3_620.0)

        val breakdown = repo.getNetPayBreakdown(2026, 6)
        assertEquals(21, breakdown.mealEntitlementCalculation.recognizedMealCount)
        assertEquals(3_840.0, breakdown.mealEntitlementNet, 0.0)
        assertEquals(5_477.89, breakdown.mealEntitlementGross, 0.0)
        assertEquals(3_840.0, breakdown.mealSpentNetApplied, 0.0)
        assertEquals(3_840.0, breakdown.mealNetDeduction, 0.0)
        assertEquals(0.0, breakdown.mealUnusedNet, 0.0)
        assertTrue(breakdown.mealSpendingDefaultedToEntitlement)

        val debug = JSONObject(repo.exportSelectedMonthWorkAndPayJson(2026, 6))
            .getJSONObject("rawCalculationDebug")
        assertEquals(MANUAL_SPENDING_SAFE_DEFAULT_MODE, debug.getString("mealCalculationMode"))
        assertEquals("CONFIRMED_MONTHLY_OVERRIDE", debug.getString("mealEntitlementSource"))
        assertEquals(3_840.0, debug.getDouble("mealEntitlementNetOverride"), 0.0)
        assertEquals(3_840.0, debug.getDouble("mealEntitlementNetApplied"), 0.0)
        assertEquals(21, debug.getInt("recognizedMealCount"))
        assertEquals(21, debug.getInt("estimatedRecognizedMealCount"))
        assertEquals(21, debug.getJSONArray("recognizedMealDates").length())
        assertTrue(debug.getJSONArray("rejectedMealDatesAndReasons").length() > 0)
        assertEquals(240.0, debug.getDouble("mealPriceNetPerMeal"), 0.0)
        assertEquals(3_840.0, debug.getDouble("mealEntitlementNet"), 0.0)
        assertEquals(5_477.89, debug.getDouble("mealEntitlementGross"), 0.0)
        assertTrue(debug.isNull("mealSpentNetEntered"))
        assertEquals(3_840.0, debug.getDouble("mealSpentNetApplied"), 0.0)
        assertEquals(0.0, debug.getDouble("mealUnusedNet"), 0.0)
        assertEquals(5_040.0, debug.getDouble("estimatedMealEntitlementNet"), 0.0)
        assertFalse(debug.getBoolean("estimateAffectsPayroll"))
        assertTrue(debug.getBoolean("mealSpendingDefaultedToEntitlement"))
    }

    @Test
    fun `rucna potrosnja se ignorise i racuna se procenjeno pravo`() {
        val estimate = calculateMealEntitlementForAccountingMonth(
            records = (1..21).map { day -> DayRecord(2026, 6, day, Shift.FIRST, 8) },
            month = MonthKey(2026, 6),
            pricePeriods = DEFAULT_MEAL_PRICE_PERIODS
        )
        val breakdown = calculateMonthlyNetPayBreakdown(
            summary = MonthSummary(
                year = 2026,
                month = 6,
                totalPay = 100_000.0,
                foodAllowance = estimate.mealEntitlementNet.toDouble(),
                mealEntitlementCalculation = estimate
            ),
            grossHourlyRate = 637.32,
            monthlyFundHours = 176,
            contributionsPercent = 19.9,
            taxRelief = 34_221.0,
            taxPercent = 10.0,
            grossAdditions = MonthlyGrossAdditions(2026, 6, mealSpentNet = 3_620.0)
        )
        assertEquals(MealEntitlementSource.SAFE_DEFAULT_EQUALS_SPENDING, breakdown.mealEntitlementSource)
        assertEquals(5_040.0, breakdown.mealEntitlementNetApplied, 0.0)
        assertEquals(5_040.0, breakdown.mealSpentNetApplied, 0.0)
        assertEquals(0.0, breakdown.mealUnusedNet, 0.0)
        assertEquals(21, breakdown.estimatedRecognizedMealCount)
        assertEquals(5_040.0, breakdown.estimatedMealEntitlementNet, 0.0)
        assertFalse(breakdown.estimateAffectsPayroll)
    }

    @Test
    fun `prazan unos potrosnje se u debug izvozu oznacava kao podrazumevano pravo`() {
        val repo = testRepository()
        repo.set(2026, 6, 1, Shift.FIRST, 8, holidayOverride = false)

        val debug = JSONObject(repo.exportSelectedMonthWorkAndPayJson(2026, 6))
            .getJSONObject("mealBreakdown")
        assertTrue(debug.isNull("mealSpentNetEntered"))
        assertTrue(debug.getBoolean("mealSpendingDefaultedToEntitlement"))
        assertEquals(debug.getDouble("mealEntitlementNet"), debug.getDouble("mealSpentNetApplied"), 0.0)
        assertEquals("SAFE_DEFAULT_EQUALS_SPENDING", debug.getString("mealEntitlementSource"))
        assertEquals(0.0, debug.getDouble("mealUnusedNet"), 0.0)
        assertFalse(debug.getBoolean("estimateAffectsPayroll"))
    }

    @Test
    fun `potvrđeno mesecno pravo ostaje sacuvano kroz backup`() {
        val source = testRepository()
        source.setMonthlyMealEntitlementNetOverride(2026, 6, BigDecimal("3840.00"))
        source.setMonthlyActualFoodSpentNet(2026, 6, 3_620.0)

        val restored = testRepository()
        assertTrue(restored.importFromJson(source.exportJson()))

        val additions = restored.getMonthlyGrossAdditions(2026, 6)
        val breakdown = restored.getNetPayBreakdown(2026, 6)
        assertEquals(0, BigDecimal("3840.00").compareTo(additions.mealEntitlementNetOverride))
        assertEquals(MealEntitlementSource.CONFIRMED_MONTHLY_OVERRIDE, breakdown.mealEntitlementSource)
        assertEquals(3_840.0, breakdown.mealEntitlementNetApplied, 0.0)
        // Ručni unos ostaje u backup-u, ali ne utiče na obračun.
        assertEquals(3_620.0, additions.mealSpentNet ?: 0.0, 0.0)
        assertEquals(0.0, breakdown.mealUnusedNet, 0.0)
    }
}
