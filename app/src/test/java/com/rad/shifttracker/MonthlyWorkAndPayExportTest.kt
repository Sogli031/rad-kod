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
import com.rad.shifttracker.obracun.DailyPayCategoryBreakdownCalculator
import com.rad.shifttracker.obracun.MANUAL_SPENDING_SAFE_DEFAULT_MODE
import com.rad.shifttracker.izvoz.MONTHLY_WORK_AND_PAY_EXPORT_OUTPUT_MODE
import com.rad.shifttracker.obracun.MonthlyGrossAdditions
import com.rad.shifttracker.obracun.PayBaseSelectionMode
import com.rad.shifttracker.obracun.PayBaseSource
import com.rad.shifttracker.obracun.PayCalculator
import com.rad.shifttracker.izvoz.exportSelectedMonthWorkAndPayJson
import com.rad.shifttracker.izvoz.monthlyWorkAndPayExportFileName
import com.rad.shifttracker.obracun.nightWorkMinutes
import com.rad.shifttracker.obracun.sundayWorkMinutes
import com.rad.shifttracker.repo.ShiftRepository
import com.rad.shifttracker.repo.employeeContributionsPercentValue
import com.rad.shifttracker.repo.foodPerDayValue
import com.rad.shifttracker.repo.getMonthlyFinalPaymentBreakdown
import com.rad.shifttracker.repo.getResolvedPayBasesForWorkMonth
import com.rad.shifttracker.repo.savePayslip
import com.rad.shifttracker.repo.setDefaultPayBaseSelection
import com.rad.shifttracker.repo.setKnownPayBaseAnchor
import com.rad.shifttracker.repo.setMonthlyAdvancePaymentNet
import com.rad.shifttracker.repo.setMonthlyGrossAdditions
import com.rad.shifttracker.repo.taxPercentValue
import com.rad.shifttracker.repo.taxReliefValue
import com.rad.shifttracker.smene.BrigadeType
import com.rad.shifttracker.smene.DayKey
import com.rad.shifttracker.smene.DayRecord
import com.rad.shifttracker.smene.Shift
import com.rad.shifttracker.osnova.CONFIG

class MonthlyWorkAndPayExportTest {
    @Test
    fun `file name follows selected work month`() {
        assertEquals("rad_obracun_2026_02.json", monthlyWorkAndPayExportFileName(2026, 2))
        assertEquals("rad_obracun_2026_05.json", monthlyWorkAndPayExportFileName(2026, 5))
    }

    @Test
    fun `export is exactly one JSON object with no trailing data`() {
        val json = testRepository().exportSelectedMonthWorkAndPayJson(
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
        val screenSource = mainSource("ShiftTrackerExports.kt")

        assertEquals("wt", MONTHLY_WORK_AND_PAY_EXPORT_OUTPUT_MODE)
        assertTrue(screenSource.contains("MONTHLY_WORK_AND_PAY_EXPORT_OUTPUT_MODE"))
        assertTrue(!screenSource.contains("openOutputStream(uri)"))
    }

    @Test
    fun `export uses selected month even when previous payroll month is missing`() {
        val repo = testRepository()

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
        val repo = testRepository()
        repo.setKnownPayBaseAnchor(2026, 5, 950.58)
        repo.setDefaultPayBaseSelection(PayBaseSelectionMode.KNOWN_BASE)
        repo.set(2026, 5, 4, Shift.SICK, 8, sickPayRate = 1.0, holidayOverride = false)
        repo.set(2026, 5, 5, Shift.SICK, 8, sickPayRate = 1.0, holidayOverride = false)

        val resolved = repo.getResolvedPayBasesForWorkMonth(2026, 5)
        // Bolovanje prati istu osnovicu kao GO i praznik (KU čl. 66).
        assertEquals(950.58, resolved.sickHourlyBase.value, 0.001)
        assertEquals(PayBaseSource.KNOWN_BASE, resolved.sickHourlyBase.source)
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
        assertEquals(950.58, settingsSickBase.getDouble("value"), 0.001)
        assertEquals("KNOWN_BASE", settingsSickBase.getString("source"))

        val raw = json.getJSONObject("rawCalculationDebug")
        assertEquals(2026, raw.getInt("selectedYear"))
        assertEquals(5, raw.getInt("selectedMonth"))
        assertEquals(950.58, raw.getDouble("sickPayBaseHourly"), 0.001)
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
        assertEquals(16 * 950.58, payDetails.getDouble("grossSickPay"), 0.01)
    }

    @Test
    fun `absence pay falls back to default only when no applicable anchor exists`() {
        val repo = testRepository()
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
        val repo = testRepository()
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
    fun `daily breakdown keeps extra minutes in Sunday work and full night hours`() {
        val records = listOf(
            DayRecord(2026, 7, 31, Shift.THIRD, 4, regularShortageMinutes = 30),
            DayRecord(2026, 7, 5, Shift.FIRST, 4, regularShortageMinutes = 30)
        )
        val breakdowns = DailyPayCategoryBreakdownCalculator(
            payCalculator = PayCalculator(),
            difficultConditionsPercent = 0.0
        ).calculateForMonth(
            records = records,
            year = 2026,
            month = 7,
            brigadeType = BrigadeType.TROBRIGADA
        )

        val friday = breakdowns.getValue(DayKey(2026, 7, 31))
        assertEquals(270, friday.regularWorkMinutes)
        // Listić 07/2026: noćni rad samo za pune sate (4h30 rada → 4h noćnog).
        assertEquals(240, friday.nightWorkMinutes)

        val sunday = breakdowns.getValue(DayKey(2026, 7, 5))
        assertEquals(270, sunday.overtimeMinutes)
        assertEquals(270, sunday.sundayWorkMinutes)
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
        assertEquals(8_388.02, breakdown.getDouble("mealGrossApplied"), 0.01)
        assertEquals(28_530.67, breakdown.getDouble("regressGross"), 0.01)
        assertEquals(0.0, breakdown.getDouble("bonusGross"), 0.01)
        assertEquals(0.0, breakdown.getDouble("otherGrossAdditions"), 0.01)
        assertEquals(36_918.69, breakdown.getDouble("totalGrossAdditions"), 0.01)
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
    fun `junski eksport cuva staru rucnu potrosnju ali obracun skida celo pravo`() {
        val repo = testRepository().apply {
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
        assertEquals(5_477.89, grossBreakdown.getDouble("mealGrossApplied"), 0.01)
        assertEquals(3_840.0, netBreakdown.getDouble("mealNetDeduction"), 0.01)
        assertEquals(3_840.0, monthlySummary.getDouble("netDeductionsTotal"), 0.01)
        assertEquals(3_840.0, netBreakdown.getDouble("totalNetDeductions"), 0.01)
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
        assertEquals(3_840.0, mealBreakdown.getDouble("mealSpentNetApplied"), 0.01)
        assertEquals(5_477.89, mealBreakdown.getDouble("mealEntitlementGross"), 0.01)
        assertEquals("AUTO_FROM_NET", mealBreakdown.getString("mealGrossSource"))
        assertEquals(3_840.0, mealBreakdown.getDouble("mealNetDeduction"), 0.01)
        assertEquals(0.0, mealBreakdown.getDouble("mealUnusedNet"), 0.01)
        assertTrue(mealBreakdown.getBoolean("mealSpendingDefaultedToEntitlement"))
        assertEquals(MANUAL_SPENDING_SAFE_DEFAULT_MODE, rawDebug.getString("mealCalculationMode"))
        assertEquals("CONFIRMED_MONTHLY_OVERRIDE", rawDebug.getString("mealEntitlementSource"))
        assertEquals(3_840.0, rawDebug.getDouble("mealEntitlementNet"), 0.01)
        assertEquals(5_477.89, rawDebug.getDouble("mealEntitlementGross"), 0.01)
        assertEquals(3_840.0, rawDebug.getDouble("mealNetDeduction"), 0.01)
        assertEquals(0.0, rawDebug.getDouble("mealUnusedNet"), 0.01)
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
        repo.savePayslip(payslipWithDeductions(4, "000451 NLB Komercijalna banka 2,920,783.99 62 48,839.50"), withPayBase = false)
        val finalPayment = repo.getMonthlyFinalPaymentBreakdown(2026, 5)
        assertTrue(finalPayment.advancePaymentNet > 0.0)
        assertTrue(finalPayment.deductionsNet > 0.0)

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
    return testRepository().apply {
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
