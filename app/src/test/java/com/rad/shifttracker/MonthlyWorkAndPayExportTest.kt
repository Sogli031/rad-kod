package com.rad.shifttracker

import org.json.JSONObject
import org.json.JSONTokener
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth

class MonthlyWorkAndPayExportTest {
    @Test
    fun `file name follows selected work month`() {
        assertEquals("rad_obracun_2026_02.json", monthlyWorkAndPayExportFileName(2026, 2))
        assertEquals("rad_obracun_2026_05.json", monthlyWorkAndPayExportFileName(2026, 5))
    }

    @Test
    fun `export is exactly one JSON object with no trailing data`() {
        val json = ShiftRepository().exportSelectedMonthWorkAndPayJson(
            selectedYear = 2026,
            selectedMonth = 5,
            appName = "Rad",
            appVersion = "1.4",
            exportTimestamp = "2026-07-20T20:00:00Z"
        )
        val tokener = JSONTokener(json)

        assertTrue(tokener.nextValue() is JSONObject)
        assertEquals(0.toChar(), tokener.nextClean())
    }

    @Test
    fun `document export explicitly truncates an existing file`() {
        val screenSource = listOf(
            java.io.File("src/main/java/com/rad/shifttracker/ShiftTrackerScreen.kt"),
            java.io.File("app/src/main/java/com/rad/shifttracker/ShiftTrackerScreen.kt")
        ).first { it.exists() }.readText()

        assertEquals("wt", MONTHLY_WORK_AND_PAY_EXPORT_OUTPUT_MODE)
        assertTrue(screenSource.contains("MONTHLY_WORK_AND_PAY_EXPORT_OUTPUT_MODE"))
        assertTrue(!screenSource.contains("openOutputStream(uri)"))
    }

    @Test
    fun `export uses selected month even when previous payroll month is missing`() {
        val repo = ShiftRepository()

        val json = JSONObject(
            repo.exportSelectedMonthWorkAndPayJson(
                selectedYear = 2026,
                selectedMonth = 2,
                appName = "Rad",
                appVersion = "1.0",
                exportTimestamp = "2026-05-15T10:15:30Z"
            )
        )

        val info = json.getJSONObject("exportInfo")
        assertEquals(2026, info.getInt("selectedYear"))
        assertEquals(2, info.getInt("selectedMonth"))
        assertEquals("Februar 2026", info.getString("monthLabel"))
        assertEquals(2026, info.getInt("exportTargetYear"))
        assertEquals(2, info.getInt("exportTargetMonth"))

        val entries = json.getJSONArray("monthWorkEntries")
        assertEquals(28, entries.length())
        assertEquals("2026-02-01", entries.getJSONObject(0).getString("date"))
        assertEquals("2026-02-28", entries.getJSONObject(27).getString("date"))
        assertFalse(entries.getJSONObject(0).getBoolean("hasEntry"))

        assertFalse(json.has("calculationStatus"))
        val status = json.getJSONObject("exportedMonthAuditStatus")
        assertEquals("Februar 2026", status.getString("monthLabel"))
        assertTrue(status.getBoolean("hasMonthlySummary"))
        assertTrue(status.getBoolean("isAuditable"))
        assertTrue(status.isNull("reason"))
        assertFalse(status.has("canCalculate"))
        assertFalse(status.has("missingDependencyMonth"))
    }

    @Test
    fun `known pay base anchor for selected month drives absence pay and export diagnostics`() {
        val repo = ShiftRepository()
        repo.setKnownPayBaseAnchor(2026, 5, 950.58)
        repo.setDefaultPayBaseSelection(PayBaseSelectionMode.KNOWN_BASE)
        repo.set(2026, 5, 4, Shift.SICK, 8, sickPayRate = 1.0, holidayOverride = false)
        repo.set(2026, 5, 5, Shift.SICK, 8, sickPayRate = 1.0, holidayOverride = false)

        val resolved = repo.getResolvedPayBasesForWorkMonth(2026, 5)
        // Bolovanje ima svoju osnovicu i ne prati poznatu osnovicu za GO/praznik.
        assertEquals(918.81, resolved.sickHourlyBase.value, 0.001)
        assertEquals(PayBaseSource.DEFAULT, resolved.sickHourlyBase.source)
        assertEquals(2026, resolved.vacationHourlyBase.sourceYear)
        assertEquals(5, resolved.vacationHourlyBase.sourceMonth)
        assertEquals(950.58, resolved.vacationHourlyBase.value, 0.001)
        assertEquals(PayBaseSource.KNOWN_BASE, resolved.vacationHourlyBase.source)
        assertEquals(950.58, resolved.paidHolidayHourlyBase.value, 0.001)
        assertEquals(PayBaseSource.KNOWN_BASE, resolved.paidHolidayHourlyBase.source)

        val json = repo.auditJson(2026, 5)
        val settings = json.getJSONObject("settingsSnapshot")
        val settingsResolved = settings.getJSONObject("resolvedPayBases")
        val settingsSickBase = settingsResolved.getJSONObject("sickHourlyBase")
        assertEquals(2026, settings.getInt("payBaseSourceYear"))
        assertEquals(5, settings.getInt("payBaseSourceMonth"))
        assertEquals(918.81, settingsSickBase.getDouble("value"), 0.001)
        assertEquals("DEFAULT", settingsSickBase.getString("source"))

        val raw = json.getJSONObject("rawCalculationDebug")
        assertEquals(2026, raw.getInt("selectedYear"))
        assertEquals(5, raw.getInt("selectedMonth"))
        assertEquals(918.81, raw.getDouble("sickPayBaseHourly"), 0.001)
        val resolution = raw.getJSONObject("payBaseResolution")
        assertEquals("KNOWN_BASE", resolution.getString("sourceUsed"))
        assertTrue(resolution.getBoolean("knownPayBaseAnchorFound"))
        assertTrue(resolution.getBoolean("knownPayBaseAnchorApplied"))
        assertEquals(
            "APPLIED_EXACT_SELECTED_MONTH",
            resolution.getString("knownPayBaseAnchorDecision")
        )
        val anchor = resolution.getJSONObject("knownPayBaseAnchor")
        assertEquals(2026, anchor.getInt("year"))
        assertEquals(5, anchor.getInt("month"))
        assertEquals(950.58, anchor.getDouble("grossHourlyBase"), 0.001)

        val payDetails = json.getJSONObject("monthlySummary").getJSONObject("payDetails")
        assertEquals(16, payDetails.getInt("sickHours"))
        assertEquals(14_700.96, payDetails.getDouble("grossSickPay"), 0.01)
    }

    @Test
    fun `absence pay falls back to default only when no applicable anchor exists`() {
        val repo = ShiftRepository()
        repo.set(2026, 5, 4, Shift.SICK, 8, sickPayRate = 1.0, holidayOverride = false)

        val resolved = repo.getResolvedPayBasesForWorkMonth(2026, 5)
        assertEquals(918.81, resolved.sickHourlyBase.value, 0.001)
        assertEquals(PayBaseSource.DEFAULT, resolved.sickHourlyBase.source)
        assertEquals(918.81, resolved.vacationHourlyBase.value, 0.001)
        assertEquals(PayBaseSource.DEFAULT, resolved.vacationHourlyBase.source)
        assertEquals(918.81, resolved.paidHolidayHourlyBase.value, 0.001)
        assertEquals(PayBaseSource.DEFAULT, resolved.paidHolidayHourlyBase.source)

        val json = repo.auditJson(2026, 5)
        val raw = json.getJSONObject("rawCalculationDebug")
        assertEquals(918.81, raw.getDouble("sickPayBaseHourly"), 0.001)
        val resolution = raw.getJSONObject("payBaseResolution")
        assertEquals("DEFAULT", resolution.getString("sourceUsed"))
        assertFalse(resolution.getBoolean("knownPayBaseAnchorFound"))
        assertFalse(resolution.getBoolean("knownPayBaseAnchorApplied"))
        assertEquals("NOT_FOUND", resolution.getString("knownPayBaseAnchorDecision"))
    }

    @Test
    fun `export includes holiday weekend and shorter shift daily breakdowns`() {
        val repo = ShiftRepository()
        repo.set(2026, 4, 1, Shift.FIRST, 8)
        repo.set(2026, 5, 1, Shift.FIRST, 8)
        repo.set(2026, 5, 2, Shift.SECOND, 5)
        repo.set(2026, 5, 4, Shift.SECOND, 6)
        repo.set(2026, 5, 9, Shift.FIRST, 6, holidayOverride = false)

        val json = JSONObject(
            repo.exportSelectedMonthWorkAndPayJson(
                selectedYear = 2026,
                selectedMonth = 5,
                appName = "Rad",
                appVersion = "1.0",
                exportTimestamp = "2026-05-15T10:15:30Z"
            )
        )
        val entriesByDate = json.getJSONArray("monthWorkEntries").associateByDate()

        val mayFirst = entriesByDate.getValue("2026-05-01")
        assertTrue(mayFirst.getBoolean("hasEntry"))
        assertTrue(mayFirst.getBoolean("isPublicHoliday"))
        assertEquals("Prva smena", mayFirst.getString("shiftName"))
        assertEquals(480, mayFirst.getInt("enteredTotalMinutes"))
        val mayFirstBreakdown = mayFirst.getJSONObject("dailyCalculationBreakdown")
        assertEquals(480, mayFirstBreakdown.getInt("holidayWorkMinutes"))
        assertEquals(0, mayFirstBreakdown.getInt("overtimeMinutes"))
        assertTrue(mayFirstBreakdown.getJSONArray("payCategoriesApplied").containsString("holidayWork"))
        assertFalse(mayFirstBreakdown.getJSONArray("payCategoriesApplied").containsString("overtime"))

        val maySecond = entriesByDate.getValue("2026-05-02")
        assertTrue(maySecond.getBoolean("isSaturday"))
        assertTrue(maySecond.getBoolean("isPublicHoliday"))
        assertEquals("Druga smena", maySecond.getString("shiftName"))
        assertEquals(300, maySecond.getInt("enteredTotalMinutes"))
        val maySecondBreakdown = maySecond.getJSONObject("dailyCalculationBreakdown")
        assertEquals(300, maySecondBreakdown.getInt("holidayWorkMinutes"))
        assertEquals(0, maySecondBreakdown.getInt("overtimeMinutes"))
        assertTrue(maySecondBreakdown.getJSONArray("payCategoriesApplied").containsString("holidayWork"))
        assertFalse(maySecondBreakdown.getJSONArray("payCategoriesApplied").containsString("overtime"))

        val mayFourth = entriesByDate.getValue("2026-05-04")
        assertEquals(360, mayFourth.getInt("enteredTotalMinutes"))
        assertEquals(480, mayFourth.getInt("plannedShiftTotalMinutes"))
        val mayFourthBreakdown = mayFourth.getJSONObject("dailyCalculationBreakdown")
        assertEquals(120, mayFourthBreakdown.getInt("absenceMinutes"))
        assertEquals(-120, mayFourthBreakdown.getInt("workBalanceDeltaMinutes"))

        val dailyBreakdowns = json.getJSONArray("dailyCalculationBreakdown")
        assertEquals(31, dailyBreakdowns.length())
        assertEquals(780, dailyBreakdowns.sumInt("holidayWorkMinutes"))
        assertEquals(360, dailyBreakdowns.sumInt("overtimeMinutes"))
        val monthlySummary = json.getJSONObject("monthlySummary")
        assertEquals(6, monthlySummary.getInt("overtimeHours"))
        assertEquals(1_140, monthlySummary.getInt("seniorityEligibleWorkMinutes"))
        assertEquals(
            1_140,
            json.getJSONObject("rawCalculationDebug").getInt("seniorityEligibleWorkMinutes")
        )
        assertTrue(
            json.getJSONObject("auditFormulas")
                .getString("seniorityBonusFormula")
                .contains("seniorityEligibleBaseGross")
        )
        assertTrue(
            json.getJSONObject("auditFormulas")
                .getString("seniorityEligibleBaseGrossFormula")
                .contains("seniority-eligible actual work categories")
        )
        assertFalse(json.getJSONObject("auditFormulas").has("seniorityEligibleMinutesFormula"))
        val seniority = json.getJSONObject("seniorityBreakdown")
        assertEquals(780, seniority.getInt("publicHolidayWorkedBaseMinutes"))
        assertEquals(360, seniority.getInt("regularWorkedBaseMinutes"))
        assertEquals(360, seniority.getInt("overtimeBaseMinutes"))
        assertEquals(1_140, seniority.getInt("seniorityEligibleMinutes"))
        assertEquals(
            seniority.getDouble("regularWorkedBaseGross") +
                seniority.getDouble("publicHolidayWorkedBaseGross"),
            seniority.getDouble("seniorityEligibleBaseGross"),
            0.001
        )
    }

    @Test
    fun `ordinary Saturday remains overtime when it is not a holiday`() {
        val records = listOf(
            DayRecord(2026, 5, 9, Shift.FIRST, 5, holidayOverride = false)
        )
        val breakdown = DailyPayCategoryBreakdownCalculator(
            payCalculator = PayCalculator(),
            difficultConditionsPercent = 0.0
        ).calculateForMonth(
            records = records,
            year = 2026,
            month = 5,
            brigadeType = BrigadeType.TROBRIGADA
        ).getValue(DayKey(2026, 5, 9))

        assertEquals(0, breakdown.holidayWorkMinutes)
        assertEquals(300, breakdown.overtimeMinutes)
        assertTrue(breakdown.payCategoriesApplied.contains("overtime"))
    }

    @Test
    fun `manual holiday work override preserves weekend overtime classification`() {
        val records = listOf(
            DayRecord(
                2026, 5, 2, Shift.FIRST, 5,
                holidayWorkOverride = false,
                holidayOverride = true
            )
        )
        val breakdown = DailyPayCategoryBreakdownCalculator(
            payCalculator = PayCalculator(),
            difficultConditionsPercent = 0.0
        ).calculateForMonth(
            records = records,
            year = 2026,
            month = 5,
            brigadeType = BrigadeType.TROBRIGADA
        ).getValue(DayKey(2026, 5, 2))

        assertEquals(0, breakdown.holidayWorkMinutes)
        assertEquals(300, breakdown.overtimeMinutes)
    }

    @Test
    fun `daily breakdown uses pay calculator monthly overtime guard when fund is unavailable`() {
        val records = listOf(DayRecord(2026, 5, 1, Shift.FIRST, 8))
        val breakdown = DailyPayCategoryBreakdownCalculator(
            payCalculator = PayCalculator(CONFIG.pay.copy(defaultHours = 0)),
            difficultConditionsPercent = 0.0,
            defaultHours = 0
        ).calculateForMonth(
            records = records,
            year = 2026,
            month = 5,
            brigadeType = BrigadeType.CETVOROBRIGADA
        ).getValue(DayKey(2026, 5, 1))

        assertEquals(480, breakdown.regularWorkMinutes)
        assertEquals(0, breakdown.overtimeMinutes)
        assertFalse(breakdown.payCategoriesApplied.contains("overtime"))
    }

    @Test
    fun `gross additions breakdown exposes meal and regress parts for exported month`() {
        val repo = auditRepo(foodPerDay = 280.0)
        repo.populateWeekdayWorkDays(2026, 5)

        val json = repo.auditJson(2026, 5)

        val monthlySummary = json.getJSONObject("monthlySummary")
        val breakdown = json.getJSONObject("grossAdditionsBreakdown")
        assertEquals("AUTO_FROM_NET", breakdown.getString("mealGrossSource"))
        assertEquals(8_388.0, breakdown.getDouble("mealGrossApplied"), 0.01)
        assertEquals(28_530.67, breakdown.getDouble("regressGross"), 0.01)
        assertEquals(0.0, breakdown.getDouble("bonusGross"), 0.01)
        assertEquals(0.0, breakdown.getDouble("otherGrossAdditions"), 0.01)
        assertEquals(36_918.67, breakdown.getDouble("totalGrossAdditions"), 0.01)
        assertEquals(monthlySummary.getDouble("grossAdditionsTotal"), breakdown.getDouble("totalGrossAdditions"), 0.01)
        assertEquals(0.701, breakdown.getDouble("grossUpNetRetentionRate"), 0.0001)
        assertTrue(breakdown.getString("grossUpFormulaDescription").contains("grossUpNetRetentionRate"))

        val checks = json.getJSONObject("auditChecks")
        assertTrue(checks.getBoolean("grossAdditionsMatchesBreakdown"))
        assertTrue(checks.getBoolean("grossTotalMatchesParts"))
        assertTrue(checks.getBoolean("workHoursMatchFund"))
        assertEquals(0, checks.getJSONArray("warnings").length())
    }

    @Test
    fun `gross additions breakdown exposes meal only month with zero regress and bonus`() {
        val repo = auditRepo(foodPerDay = 280.0)
        repo.populateWeekdayWorkDays(2026, 6)

        val json = repo.auditJson(2026, 6)

        val monthlySummary = json.getJSONObject("monthlySummary")
        val breakdown = json.getJSONObject("grossAdditionsBreakdown")
        assertTrue(breakdown.getDouble("mealGrossApplied") > 0.0)
        assertEquals("AUTO_FROM_NET", breakdown.getString("mealGrossSource"))
        assertEquals(0.0, breakdown.getDouble("regressGross"), 0.01)
        assertEquals(0.0, breakdown.getDouble("bonusGross"), 0.01)
        assertEquals(0.0, breakdown.getDouble("otherGrossAdditions"), 0.01)
        assertEquals(monthlySummary.getDouble("grossAdditionsTotal"), breakdown.getDouble("totalGrossAdditions"), 0.01)
    }

    @Test
    fun `gross additions breakdown exports zero values when there are no additions`() {
        val repo = auditRepo(foodPerDay = 0.0)
        repo.populateWeekdayWorkDays(2026, 6)

        val json = repo.auditJson(2026, 6)

        val breakdown = json.getJSONObject("grossAdditionsBreakdown")
        assertEquals(0.0, breakdown.getDouble("mealGrossApplied"), 0.01)
        assertEquals("AUTO_FROM_NET", breakdown.getString("mealGrossSource"))
        assertEquals(0.0, breakdown.getDouble("regressGross"), 0.01)
        assertEquals(0.0, breakdown.getDouble("bonusGross"), 0.01)
        assertEquals(0.0, breakdown.getDouble("otherGrossAdditions"), 0.01)
        assertEquals(0.0, breakdown.getDouble("totalGrossAdditions"), 0.01)
        assertTrue(json.getJSONObject("auditChecks").getBoolean("grossAdditionsMatchesBreakdown"))
    }

    @Test
    fun `junski eksport cuva automatsko pravo i rucnu potrosnju odvojeno`() {
        val repo = ShiftRepository().apply {
            employeeContributionsPercentValue = 19.9
            taxReliefValue = 34_221.0
            taxPercentValue = 10.0
        }
        val workDays = (1..YearMonth.of(2026, 6).lengthOfMonth())
            .filter { day ->
                val dayOfWeek = LocalDate.of(2026, 6, day).dayOfWeek
                dayOfWeek != DayOfWeek.SATURDAY && dayOfWeek != DayOfWeek.SUNDAY
            }
            .take(16)
        workDays.forEach { day ->
            repo.set(2026, 6, day, Shift.FIRST, 8, holidayOverride = false)
        }
        repo.setMonthlyGrossAdditions(
            year = 2026,
            month = 6,
            additions = MonthlyGrossAdditions(
                year = 2026,
                month = 6,
                mealGross = 5_478.0,
                mealSpentNet = 3_620.0,
                mealSettlementAdjustmentNet = 220.0,
                mealEntitlementNetOverride = BigDecimal("3840.00")
            )
        )

        val json = repo.auditJson(2026, 6)
        val settingsAdditions = json.getJSONObject("settingsSnapshot")
            .getJSONObject("monthlyGrossAdditions")
        val monthlySummary = json.getJSONObject("monthlySummary")
        val grossBreakdown = json.getJSONObject("grossAdditionsBreakdown")
        val mealBreakdown = json.getJSONObject("mealBreakdown")
        val netBreakdown = json.getJSONObject("netDeductionsBreakdown")
        val rawDebug = json.getJSONObject("rawCalculationDebug")

        assertEquals(3_840.0, monthlySummary.getDouble("foodAllowance"), 0.01)
        assertFalse(settingsAdditions.has("mealGross"))
        assertFalse(settingsAdditions.has("mealGrossPresent"))
        assertEquals(3_840.0, settingsAdditions.getDouble("mealEntitlementNetOverride"), 0.01)
        assertEquals(3_620.0, settingsAdditions.getDouble("mealSpentNet"), 0.01)
        assertFalse(settingsAdditions.has("mealSettlementAdjustmentNet"))
        assertEquals("AUTO_FROM_NET", grossBreakdown.getString("mealGrossSource"))
        assertEquals(5_478.0, grossBreakdown.getDouble("mealGrossApplied"), 0.01)
        assertEquals(3_620.0, netBreakdown.getDouble("mealNetDeduction"), 0.01)
        assertEquals(3_620.0, monthlySummary.getDouble("netDeductionsTotal"), 0.01)
        assertEquals(3_620.0, netBreakdown.getDouble("totalNetDeductions"), 0.01)
        assertEquals(MANUAL_SPENDING_SAFE_DEFAULT_MODE, mealBreakdown.getString("mealCalculationMode"))
        assertEquals("CONFIRMED_MONTHLY_OVERRIDE", mealBreakdown.getString("mealEntitlementSource"))
        assertEquals(3_840.0, mealBreakdown.getDouble("mealEntitlementNetOverride"), 0.01)
        assertEquals(3_840.0, mealBreakdown.getDouble("mealEntitlementNetApplied"), 0.01)
        assertEquals(16, mealBreakdown.getInt("recognizedMealCount"))
        assertEquals(16, mealBreakdown.getInt("estimatedRecognizedMealCount"))
        assertEquals(3_840.0, mealBreakdown.getDouble("estimatedMealEntitlementNet"), 0.01)
        assertFalse(mealBreakdown.getBoolean("estimateAffectsPayroll"))
        assertEquals(240.0, mealBreakdown.getDouble("mealPriceNetPerMeal"), 0.01)
        assertEquals(3_840.0, mealBreakdown.getDouble("mealEntitlementNet"), 0.01)
        assertEquals(3_620.0, mealBreakdown.getDouble("mealSpentNetApplied"), 0.01)
        assertEquals(5_478.0, mealBreakdown.getDouble("mealEntitlementGross"), 0.01)
        assertEquals("AUTO_FROM_NET", mealBreakdown.getString("mealGrossSource"))
        assertEquals(3_620.0, mealBreakdown.getDouble("mealNetDeduction"), 0.01)
        assertEquals(220.0, mealBreakdown.getDouble("mealUnusedNet"), 0.01)
        assertFalse(mealBreakdown.getBoolean("mealSpendingDefaultedToEntitlement"))
        assertEquals(MANUAL_SPENDING_SAFE_DEFAULT_MODE, rawDebug.getString("mealCalculationMode"))
        assertEquals("CONFIRMED_MONTHLY_OVERRIDE", rawDebug.getString("mealEntitlementSource"))
        assertEquals(3_840.0, rawDebug.getDouble("mealEntitlementNet"), 0.01)
        assertEquals(5_478.0, rawDebug.getDouble("mealEntitlementGross"), 0.01)
        assertEquals(3_620.0, rawDebug.getDouble("mealNetDeduction"), 0.01)
        assertEquals(220.0, rawDebug.getDouble("mealUnusedNet"), 0.01)
        assertFalse(mealBreakdown.has("mealGrossOverride"))
        assertFalse(mealBreakdown.has("mealGrossOverridePresent"))
        assertFalse(mealBreakdown.has("mealSettlementAdjustmentNet"))
        assertFalse(mealBreakdown.has("mealCoveredNet"))
        val expectedGrossTotal = monthlySummary.getDouble("grossBaseBeforeSeniority") +
            monthlySummary.getDouble("grossSeniorityBonus") +
            grossBreakdown.getDouble("mealGrossApplied")
        val expectedContributions = expectedGrossTotal * 0.199
        val expectedTaxableBase = (expectedGrossTotal - 34_221.0).coerceAtLeast(0.0)
        val expectedTax = expectedTaxableBase * 0.10
        assertEquals(expectedGrossTotal, monthlySummary.getDouble("grossTotal"), 0.01)
        assertEquals(expectedContributions, monthlySummary.getDouble("employeeContributions"), 0.01)
        assertEquals(expectedTaxableBase, monthlySummary.getDouble("taxableBase"), 0.01)
        assertEquals(expectedTax, monthlySummary.getDouble("tax"), 0.01)
        assertEquals(
            expectedGrossTotal - expectedContributions - expectedTax,
            monthlySummary.getDouble("netPayBeforeDeductions"),
            0.01
        )
        assertEquals(
            monthlySummary.getDouble("netPayBeforeDeductions") -
                netBreakdown.getDouble("mealNetDeduction") -
                netBreakdown.getDouble("advanceNet") -
                netBreakdown.getDouble("creditDeductionNet") -
                netBreakdown.getDouble("unionDuesNet") -
                netBreakdown.getDouble("otherDeductionsNet"),
            monthlySummary.getDouble("displayPay"),
            0.01
        )
        assertTrue(json.getJSONObject("auditChecks").getBoolean("netDeductionsMatchesBreakdown"))
        assertTrue(json.getJSONObject("auditChecks").getBoolean("displayPayMatchesFormula"))
    }

    @Test
    fun `net deductions breakdown exposes meal spending and display pay formula`() {
        val repo = auditRepo(foodPerDay = 280.0)
        repo.populateWeekdayWorkDays(2026, 6)

        val json = repo.auditJson(2026, 6)

        val monthlySummary = json.getJSONObject("monthlySummary")
        val breakdown = json.getJSONObject("netDeductionsBreakdown")
        val mealBreakdown = json.getJSONObject("mealBreakdown")
        assertEquals(
            mealBreakdown.getDouble("mealNetDeduction"),
            breakdown.getDouble("mealNetDeduction"),
            0.01
        )
        assertEquals(0.0, breakdown.getDouble("advanceNet"), 0.01)
        assertEquals(0.0, breakdown.getDouble("creditDeductionNet"), 0.01)
        assertEquals(0.0, breakdown.getDouble("otherDeductionsNet"), 0.01)
        assertEquals(monthlySummary.getDouble("netDeductionsTotal"), breakdown.getDouble("totalNetDeductions"), 0.01)
        assertEquals(
            monthlySummary.getDouble("netPayBeforeDeductions") - breakdown.getDouble("totalNetDeductions"),
            monthlySummary.getDouble("displayPay"),
            0.01
        )
        assertTrue(breakdown.getString("displayPayFormulaDescription").contains("netDeductionsTotal"))
        assertTrue(json.getJSONObject("auditChecks").getBoolean("netDeductionsMatchesBreakdown"))
        assertTrue(json.getJSONObject("auditChecks").getBoolean("displayPayMatchesFormula"))
    }

    @Test
    fun `audit net deductions do not mix final payment advance or credit from receipt flow`() {
        val repo = auditRepo(foodPerDay = 0.0)
        repo.populateWeekdayWorkDays(2026, 4)
        repo.populateWeekdayWorkDays(2026, 5)
        repo.setMonthlyAdvancePaymentNet(2026, 5, 10_000.0)
        repo.setCreditDeductionSettings(enabled = true, monthlyAmount = 48_839.50)
        val finalPayment = repo.getMonthlyFinalPaymentBreakdown(2026, 5)
        assertTrue(finalPayment.advancePaymentNet > 0.0)
        assertTrue(finalPayment.loanDeductionNet > 0.0)

        val json = repo.auditJson(2026, 5)

        val monthlySummary = json.getJSONObject("monthlySummary")
        val breakdown = json.getJSONObject("netDeductionsBreakdown")
        assertEquals(monthlySummary.getDouble("netDeductionsTotal"), breakdown.getDouble("totalNetDeductions"), 0.01)
        assertEquals(0.0, breakdown.getDouble("advanceNet"), 0.01)
        assertEquals(0.0, breakdown.getDouble("creditDeductionNet"), 0.01)
        assertEquals(0.0, breakdown.getDouble("otherDeductionsNet"), 0.01)
    }
}

private fun auditRepo(foodPerDay: Double): ShiftRepository {
    return ShiftRepository().apply {
        foodPerDayValue = foodPerDay
        employeeContributionsPercentValue = 19.9
        taxReliefValue = 34_221.0
        taxPercentValue = 10.0
    }
}

private fun ShiftRepository.auditJson(year: Int, month: Int): JSONObject {
    return JSONObject(
        exportSelectedMonthWorkAndPayJson(
            selectedYear = year,
            selectedMonth = month,
            appName = "Rad",
            appVersion = "1.0",
            exportTimestamp = "2026-05-15T10:15:30Z"
        )
    )
}

private fun ShiftRepository.populateWeekdayWorkDays(year: Int, month: Int) {
    val yearMonth = YearMonth.of(year, month)
    for (day in 1..yearMonth.lengthOfMonth()) {
        val date = LocalDate.of(year, month, day)
        if (date.dayOfWeek != DayOfWeek.SATURDAY && date.dayOfWeek != DayOfWeek.SUNDAY) {
            set(year, month, day, Shift.FIRST, 8)
        }
    }
}

private fun org.json.JSONArray.associateByDate(): Map<String, JSONObject> {
    val entries = mutableMapOf<String, JSONObject>()
    for (index in 0 until length()) {
        val entry = getJSONObject(index)
        entries[entry.getString("date")] = entry
    }
    return entries
}

private fun org.json.JSONArray.containsString(value: String): Boolean {
    for (index in 0 until length()) {
        if (getString(index) == value) return true
    }
    return false
}

private fun org.json.JSONArray.sumInt(field: String): Int {
    var total = 0
    for (index in 0 until length()) {
        total += getJSONObject(index).getInt(field)
    }
    return total
}
