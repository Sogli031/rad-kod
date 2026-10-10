package com.rad.shifttracker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.json.JSONObject
import org.junit.Before
import org.junit.Test
import java.time.LocalDate
import java.time.YearMonth
import kotlin.system.measureTimeMillis
import com.rad.shifttracker.obracun.AnnualNetPayAverages
import com.rad.shifttracker.obracun.KnownPayBaseAnchor
import com.rad.shifttracker.obracun.MANUAL_SPENDING_SAFE_DEFAULT_MODE
import com.rad.shifttracker.obracun.MealEntitlementSource
import com.rad.shifttracker.obracun.MealSpentSource
import com.rad.shifttracker.obracun.MonthlyGrossAdditions
import com.rad.shifttracker.obracun.MonthlyNetPayBreakdown
import com.rad.shifttracker.obracun.MonthlyPayBases
import com.rad.shifttracker.obracun.MonthlyPayDetails
import com.rad.shifttracker.obracun.PayBaseAverageOptions
import com.rad.shifttracker.obracun.PayBaseMonthSource
import com.rad.shifttracker.obracun.PayBaseSelectionMode
import com.rad.shifttracker.obracun.PayBaseSource
import com.rad.shifttracker.obracun.RollingPayBaseCalculation
import com.rad.shifttracker.obracun.calculateManualAnchorGrossHourlyBase
import com.rad.shifttracker.obracun.calculateMonthlyFundHours
import com.rad.shifttracker.obracun.calculatePayBaseMonthForAverage
import com.rad.shifttracker.obracun.previousTwelveMonthKeys
import com.rad.shifttracker.repo.BatchRecord
import com.rad.shifttracker.repo.ShiftRepository
import com.rad.shifttracker.repo.basePayValue
import com.rad.shifttracker.repo.brigadeTypeValue
import com.rad.shifttracker.repo.calculateDailyPay
import com.rad.shifttracker.repo.defaultPayBaseSelectionModeValue
import com.rad.shifttracker.repo.difficultWorkConditionsPercentValue
import com.rad.shifttracker.repo.employeeContributionsPercentValue
import com.rad.shifttracker.repo.exportJson
import com.rad.shifttracker.repo.foodPerDayValue
import com.rad.shifttracker.repo.getAnnualNetPayAverages
import com.rad.shifttracker.repo.getCompBalanceAtMonthStart
import com.rad.shifttracker.repo.getGrossHourlyRateForMonth
import com.rad.shifttracker.repo.getHourlyValueChanges
import com.rad.shifttracker.repo.getHourlyValueForMonth
import com.rad.shifttracker.repo.getKnownPayBaseAnchor
import com.rad.shifttracker.repo.getMonthlyFinalPaymentBreakdown
import com.rad.shifttracker.repo.getMonthlyFoodSpendingBreakdown
import com.rad.shifttracker.repo.getMonthlyGrossAdditions
import com.rad.shifttracker.repo.getMonthlyNetAdjustments
import com.rad.shifttracker.repo.getMonthlyPayBases
import com.rad.shifttracker.repo.getMonthlyPayDetails
import com.rad.shifttracker.repo.getMonthlyReceipt
import com.rad.shifttracker.repo.getNetPayBreakdown
import com.rad.shifttracker.repo.getRecentNetPayAverage
import com.rad.shifttracker.repo.getResolvedPayBases
import com.rad.shifttracker.repo.getRollingPayBaseCalculation
import com.rad.shifttracker.repo.getSummary
import com.rad.shifttracker.repo.grossHourlyRateValue
import com.rad.shifttracker.repo.importFromJson
import com.rad.shifttracker.repo.initialCompBalanceMinutesValue
import com.rad.shifttracker.repo.initialCompBalanceValue
import com.rad.shifttracker.repo.initialVacationBalanceValue
import com.rad.shifttracker.repo.mainPaySalaryOnly
import com.rad.shifttracker.repo.minuliRadStartMonthValue
import com.rad.shifttracker.repo.minuliRadStartYearValue
import com.rad.shifttracker.repo.notificationAccessPromptSuppressed
import com.rad.shifttracker.repo.payCoefficientValue
import com.rad.shifttracker.repo.recordAdvancePaymentFromPayslip
import com.rad.shifttracker.repo.removeHourlyValueChange
import com.rad.shifttracker.repo.savePayslip
import com.rad.shifttracker.repo.setDefaultPayBaseSelection
import com.rad.shifttracker.repo.setFoodPerDayFromMonth
import com.rad.shifttracker.repo.setHourlyValueChange
import com.rad.shifttracker.repo.setKnownPayBaseAnchor
import com.rad.shifttracker.repo.setMonthlyActualFoodSpentNet
import com.rad.shifttracker.repo.setMonthlyAdvancePaymentNet
import com.rad.shifttracker.repo.setMonthlyGrossAdditions
import com.rad.shifttracker.repo.setMonthlyMealSettlementAdjustmentNet
import com.rad.shifttracker.repo.setMonthlyPayBases
import com.rad.shifttracker.repo.sickPayBaseHourlyValue
import com.rad.shifttracker.repo.taxPercentValue
import com.rad.shifttracker.repo.taxReliefValue
import com.rad.shifttracker.repo.transportDailyAmountValue
import com.rad.shifttracker.smene.BrigadeType
import com.rad.shifttracker.smene.DayKey
import com.rad.shifttracker.smene.DayRecord
import com.rad.shifttracker.smene.MonthKey
import com.rad.shifttracker.obracun.MonthSummary
import com.rad.shifttracker.smene.OvertimeType
import com.rad.shifttracker.smene.Shift
import com.rad.shifttracker.smene.ShiftFillMode
import com.rad.shifttracker.smene.ShiftFillSettings
import com.rad.shifttracker.smene.ShiftScheduler
import com.rad.shifttracker.smene.Weekday
import com.rad.shifttracker.smene.isSerbianHoliday
import com.rad.shifttracker.godisnji.VacationYearBalance
import com.rad.shifttracker.obracun.SeniorityIncreaseFactor
import com.rad.shifttracker.obracun.SeniorityInputMode
import com.rad.shifttracker.repo.applyBloodDonationLeave
import com.rad.shifttracker.repo.bloodDonationCount
import com.rad.shifttracker.repo.bloodDonationHistory
import com.rad.shifttracker.repo.calculateBasePayFromNet
import com.rad.shifttracker.repo.calculateEstimatedDailyGrossPay
import com.rad.shifttracker.repo.calculateEstimatedDailyNetPay
import com.rad.shifttracker.repo.calculateEstimatedDailyWorkGrossPay
import com.rad.shifttracker.repo.calculateEstimatedDailyWorkNetPay
import com.rad.shifttracker.repo.deleteBloodDonation
import com.rad.shifttracker.repo.exportCloudBackupJson
import com.rad.shifttracker.repo.getPreviewNetPayBreakdown
import com.rad.shifttracker.repo.getResolvedPayBasesForWorkMonth
import com.rad.shifttracker.repo.getScheduledShiftForDate
import com.rad.shifttracker.repo.getPaidLeaveNetDifference
import com.rad.shifttracker.repo.getSickLeaveNetDifference
import com.rad.shifttracker.repo.getVacationNetDifference
import com.rad.shifttracker.repo.getVacationBalanceSummary
import com.rad.shifttracker.repo.getVacationDaysRemaining
import com.rad.shifttracker.repo.getVacationDaysUsed
import com.rad.shifttracker.repo.getVacationYearBalance
import com.rad.shifttracker.repo.isVacationExceeded
import com.rad.shifttracker.repo.lastBloodDonationDate
import com.rad.shifttracker.repo.minuliRadBenefitEnabledValue
import com.rad.shifttracker.repo.minuliRadIncreaseFactorValue
import com.rad.shifttracker.repo.minuliRadLastPayslipMonthValue
import com.rad.shifttracker.repo.minuliRadLastPayslipYearValue
import com.rad.shifttracker.repo.minuliRadPayslipPercentValue
import com.rad.shifttracker.repo.previewAbsenceRangeNetImpact
import com.rad.shifttracker.repo.previewPayCoefficientNetDifference
import com.rad.shifttracker.repo.pruneIrrelevantHistory
import com.rad.shifttracker.repo.recordBloodDonation
import com.rad.shifttracker.repo.seniorityInputModeValue
import com.rad.shifttracker.repo.setVacationYearBalance
import com.rad.shifttracker.smene.HolidayEntrySource
import com.rad.shifttracker.ui.calendarDayDetailText
import com.rad.shifttracker.repo.populateMonthsAhead
import com.rad.shifttracker.repo.applySickRange
import com.rad.shifttracker.repo.applyVacationRange
import com.rad.shifttracker.osnova.CONFIG
import com.rad.shifttracker.osnova.MANUAL_HOLIDAY_LABEL

@Suppress("LargeClass")
class ShiftRepositoryTest {

    private lateinit var repo: ShiftRepository

    @Before
    fun setUp() {
        repo = testRepository()
        // Testovi zadržavanja istorije glume da je poslednji upisan mesec i tekući (do 12/2027).
        repo.retentionClock = { java.time.YearMonth.of(2027, 12) }
    }

    private fun populateCompletePreviousTwelveMonths(
        year: Int,
        month: Int,
        excludedMonths: Set<MonthKey> = emptySet(),
        skippedDays: Set<DayKey> = emptySet()
    ) {
        for (key in previousTwelveMonthKeys(year, month)) {
            if (key in excludedMonths) continue
            populateCompleteMonth(key.year, key.month, skippedDays)
        }
    }

    private fun populateCompleteMonth(year: Int, month: Int, skippedDays: Set<DayKey> = emptySet()) {
        val lengthOfMonth = YearMonth.of(year, month).lengthOfMonth()
        for (day in 1..lengthOfMonth) {
            if (DayKey(year, month, day) in skippedDays) continue
            repo.set(year, month, day, Shift.FIRST, 8, holidayOverride = false)
        }
    }

    private fun populateNonHolidayWorkdays(year: Int, month: Int) {
        val lengthOfMonth = YearMonth.of(year, month).lengthOfMonth()
        for (day in 1..lengthOfMonth) {
            val date = LocalDate.of(year, month, day)
            if (Weekday.from(date).isWorkday && !isSerbianHoliday(year, month, day)) {
                repo.set(year, month, day, Shift.FIRST, 8)
            }
        }
    }

    private fun populateMonthMarkers(start: MonthKey, end: MonthKey) {
        var current = YearMonth.of(start.year, start.month)
        val finalMonth = YearMonth.of(end.year, end.month)
        while (!current.isAfter(finalMonth)) {
            repo.set(current.year, current.monthValue, 1, Shift.FIRST, 8, holidayOverride = false)
            current = current.plusMonths(1)
        }
    }

    private fun populateCompleteThreeBrigadeMonth(
        scheduler: ShiftScheduler,
        settings: ShiftFillSettings,
        year: Int,
        month: Int
    ) {
        val lengthOfMonth = YearMonth.of(year, month).lengthOfMonth()
        for (day in 1..lengthOfMonth) {
            val date = LocalDate.of(year, month, day)
            if (!Weekday.from(date).isWorkday) continue
            val shift = scheduler.calculateShiftForDate(date, settings)
            repo.set(year, month, day, shift, 8, holidayOverride = false)
        }
    }

    private fun prepareKnownBaseMayScenario(
        anchorMonth: MonthKey = MonthKey(2026, 1),
        anchorBase: Double = 917.81,
        realMonths: Set<MonthKey> = emptySet()
    ) {
        val scheduler = ShiftScheduler()
        val settings = ShiftFillSettings(
            mode = ShiftFillMode.THREE_BRIGADE,
            anchorDate = LocalDate.of(2026, 5, 4),
            selectedShift = Shift.THIRD
        )
        repo.basePayValue = 1_000.0
        repo.foodPerDayValue = 0.0
        repo.employeeContributionsPercentValue = 0.0
        repo.taxReliefValue = 0.0
        repo.taxPercentValue = 0.0
        scheduler.populateMonthsAhead(
            repo = repo,
            referenceDate = settings.anchorDate,
            settings = settings,
            monthsAhead = 0
        )
        for (key in realMonths) {
            populateCompleteThreeBrigadeMonth(scheduler, settings, key.year, key.month)
        }
        repo.setKnownPayBaseAnchor(anchorMonth.year, anchorMonth.month, anchorBase)
        repo.setDefaultPayBaseSelection(PayBaseSelectionMode.KNOWN_BASE)
    }

    private fun resolvedKnownCalculation(year: Int, month: Int): RollingPayBaseCalculation {
        val resolved = repo.getResolvedPayBases(year, month)
        assertEquals(PayBaseSource.KNOWN_BASE, resolved.vacationHourlyBase.source)
        // Bolovanje prati istu osnovicu kao GO i praznik (KU čl. 66).
        assertEquals(PayBaseSource.KNOWN_BASE, resolved.sickHourlyBase.source)
        assertEquals(resolved.vacationHourlyBase.value, resolved.sickHourlyBase.value, 0.0)
        return resolved.vacationHourlyBase.calculation!!
    }

    // ============= Osnovne CRUD operacije =============

    @Test
    fun `set i get vraćaju isti zapis`() {
        repo.set(2025, 1, 6, Shift.FIRST, 8)
        val record = repo.get(2025, 1, 6)
        assertNotNull(record)
        assertEquals(Shift.FIRST, record!!.shift)
        assertEquals(8, record.hours)
    }

    @Test
    fun `get vraca null za nepostojeci zapis`() {
        val record = repo.get(2025, 1, 6)
        assertNull(record)
    }

    @Test
    fun `set prepisuje postojeći zapis`() {
        repo.set(2025, 1, 6, Shift.FIRST, 8)
        repo.set(2025, 1, 6, Shift.SECOND, 6)
        val record = repo.get(2025, 1, 6)
        assertNotNull(record)
        assertEquals(Shift.SECOND, record!!.shift)
        assertEquals(6, record.hours)
    }

    @Test
    fun `set cuva minute manjka samo za redovan rad kraci od punog dana`() {
        repo.set(2025, 1, 6, Shift.FIRST, 7, regularShortageMinutes = 45)
        repo.set(2025, 1, 7, Shift.SICK, 8, regularShortageMinutes = 45)
        repo.set(2025, 1, 8, Shift.FIRST, 8, regularShortageMinutes = 45)

        assertEquals(45, repo.get(2025, 1, 6)!!.regularShortageMinutes)
        assertEquals(0, repo.get(2025, 1, 7)!!.regularShortageMinutes)
        assertEquals(0, repo.get(2025, 1, 8)!!.regularShortageMinutes)
    }

    @Test
    fun `set OFF za vikend brise zapis`() {
        repo.set(2025, 1, 4, Shift.FIRST, 8) // Subota
        repo.set(2025, 1, 4, Shift.OFF, 0)

        assertNull(repo.get(2025, 1, 4))
    }

    @Test
    fun `set OFF za subotu u cetvorobrigadi cuva zapis za bilans`() {
        repo.brigadeTypeValue = BrigadeType.CETVOROBRIGADA
        repo.set(2025, 1, 4, Shift.FIRST, 8) // Subota
        repo.set(2025, 1, 4, Shift.OFF, 0)

        val record = repo.get(2025, 1, 4)

        assertNotNull(record)
        assertEquals(Shift.OFF, record!!.shift)
        assertEquals(CONFIG.pay.defaultHours, record.hoursDeficitFor(BrigadeType.CETVOROBRIGADA))
    }

    @Test
    fun `delete briše postojeći zapis`() {
        repo.set(2025, 1, 6, Shift.FIRST, 8)
        val deleted = repo.delete(2025, 1, 6)
        assertTrue(deleted)
        assertNull(repo.get(2025, 1, 6))
    }

    @Test
    fun `delete vraca false za nepostojeci zapis`() {
        val deleted = repo.delete(2025, 1, 6)
        assertFalse(deleted)
    }

    // ============= Mesečni zapisi =============

    @Test
    fun `getMonthRecords vraca sve zapise za mesec`() {
        repo.set(2025, 1, 6, Shift.FIRST, 8)
        repo.set(2025, 1, 7, Shift.SECOND, 8)
        repo.set(2025, 2, 3, Shift.THIRD, 8)

        val janRecords = repo.getMonthRecords(2025, 1)
        assertEquals(2, janRecords.size)

        val febRecords = repo.getMonthRecords(2025, 2)
        assertEquals(1, febRecords.size)
    }

    @Test
    fun `getMonthRecords vraca praznu listu za mesec bez zapisa`() {
        val records = repo.getMonthRecords(2025, 3)
        assertTrue(records.isEmpty())
    }

    @Test
    fun `ekran moze da izracuna januar posle decembra dve hiljade dvadeset seste`() {
        for (year in 2025..2026) {
            for (month in 1..12) {
                populateCompleteMonth(year, month)
            }
        }

        lateinit var summary: MonthSummary
        lateinit var payBreakdown: MonthlyNetPayBreakdown
        lateinit var payDetails: MonthlyPayDetails
        lateinit var annualAverages: AnnualNetPayAverages
        val elapsedMs = measureTimeMillis {
            summary = repo.getSummary(2027, 1)
            payBreakdown = repo.getNetPayBreakdown(2027, 1)
            payDetails = repo.getMonthlyPayDetails(2027, 1)
            annualAverages = repo.getAnnualNetPayAverages(2027)
        }

        assertTrue("Obračun januara 2027 je prespor: ${elapsedMs}ms", elapsedMs < 3_000)
        assertEquals(2027, summary.year)
        assertEquals(1, summary.month)
        assertEquals(PayBaseSource.CALCULATED_PREVIOUS_12_MONTHS, payDetails.paidHolidayPayBase.source)
        assertEquals(0, annualAverages.monthCount)
        assertTrue(payBreakdown.displayPay >= 0.0)
    }

    @Test
    fun `cetvorobrigada sa upisanim radnim smenama omogucava osnovicu iz prethodnih dvanaest meseci`() {
        val scheduler = ShiftScheduler()
        val referenceDate = LocalDate.of(2026, 5, 10)
        val settings = ShiftFillSettings(
            mode = ShiftFillMode.FOUR_BRIGADE,
            anchorDate = referenceDate,
            selectedShift = Shift.FIRST,
            cycleDayIndex = 0
        )
        repo.brigadeTypeValue = BrigadeType.CETVOROBRIGADA

        for (key in previousTwelveMonthKeys(2026, 5)) {
            val lengthOfMonth = YearMonth.of(key.year, key.month).lengthOfMonth()
            for (day in 1..lengthOfMonth) {
                val date = LocalDate.of(key.year, key.month, day)
                val shift = scheduler.calculateShiftForDate(date, settings)
                if (shift in Shift.workShifts()) {
                    repo.set(key.year, key.month, day, shift, 8, holidayOverride = false)
                }
            }
        }

        val details = repo.getMonthlyPayDetails(2026, 5)
        val calculation = repo.getRollingPayBaseCalculation(2026, 5)

        assertNotNull(calculation)
        assertEquals(12, calculation!!.months.size)
        assertEquals(PayBaseSource.CALCULATED_PREVIOUS_12_MONTHS, details.paidHolidayPayBase.source)
    }

    @Test
    fun `cetvorobrigada bez jedne ocekivane radne smene ne racuna osnovicu`() {
        val scheduler = ShiftScheduler()
        val referenceDate = LocalDate.of(2026, 5, 10)
        val settings = ShiftFillSettings(
            mode = ShiftFillMode.FOUR_BRIGADE,
            anchorDate = referenceDate,
            selectedShift = Shift.FIRST,
            cycleDayIndex = 0
        )
        val skippedWorkDay = DayKey(2025, 5, 7)
        repo.brigadeTypeValue = BrigadeType.CETVOROBRIGADA

        for (key in previousTwelveMonthKeys(2026, 5)) {
            val lengthOfMonth = YearMonth.of(key.year, key.month).lengthOfMonth()
            for (day in 1..lengthOfMonth) {
                val date = LocalDate.of(key.year, key.month, day)
                val shift = scheduler.calculateShiftForDate(date, settings)
                if (shift in Shift.workShifts() && DayKey(key.year, key.month, day) != skippedWorkDay) {
                    repo.set(key.year, key.month, day, shift, 8, holidayOverride = false)
                }
            }
        }

        assertNull(repo.getRollingPayBaseCalculation(2026, 5))
    }

    @Test
    fun `procenat otezanih uslova rada se cuva kroz export import`() {
        repo.difficultWorkConditionsPercentValue = 35.0
        val json = JSONObject(repo.exportJson())

        assertEquals(35.0, json.getDouble("config_difficult_work_conditions_percent"), 0.01)

        val imported = testRepository()
        assertTrue(imported.importFromJson(json.toString()))
        assertEquals(35.0, imported.difficultWorkConditionsPercentValue, 0.01)
    }

    @Test
    fun `export import roundtrip cuva podesavanja plate odmor i zapise`() {
        val source = testRepository()
        source.payCoefficientValue = 2.75
        source.setHourlyValueChange(2026, 2, 610.0)
        source.employeeContributionsPercentValue = 19.9
        source.taxReliefValue = 34221.0
        source.taxPercentValue = 10.0
        source.difficultWorkConditionsPercentValue = 4.5
        source.foodPerDayValue = 320.0
        source.transportDailyAmountValue = 360.0
        source.initialCompBalanceValue = 12
        source.minuliRadStartYearValue = 2012
        source.minuliRadStartMonthValue = 3
        source.brigadeTypeValue = BrigadeType.CETVOROBRIGADA
        source.setMonthlyPayBases(
            2026,
            2,
            MonthlyPayBases(
                year = 2026,
                month = 2,
                vacationHourlyBase = 900.0,
                paidHolidayHourlyBase = 910.0,
                sickHourlyBase = 700.0,
                selectionMode = PayBaseSelectionMode.MANUAL
            )
        )
        source.setMonthlyGrossAdditions(
            2026,
            2,
            MonthlyGrossAdditions(
                year = 2026,
                month = 2,
                mealGross = 1200.0,
                regressGross = 1500.0,
                bonusGross = 2200.0,
                mealSpentNet = 600.0,
                bonusNet = 500.0
            )
        )
        source.setMonthlyAdvancePaymentNet(2026, 2, 50_000.0)
        source.setVacationYearBalance(
            VacationYearBalance(
                year = 2026,
                trackingStartDate = LocalDate.of(2026, 1, 1),
                carryoverDaysInitial = 5.0,
                currentYearDaysTotal = 25.0,
                currentYearDaysUsedBeforeAvailability = 2.0,
                currentYearAvailableFrom = LocalDate.of(2026, 7, 1),
                manualBaseline = true
            )
        )
        source.set(
            year = 2026,
            month = 2,
            day = 3,
            shift = Shift.THIRD,
            hours = 8,
            shift2 = Shift.FIRST,
            hours2 = 4,
            overtimeType = OvertimeType.COMP_TIME,
            weekendOvertimeType = OvertimeType.PAID,
            compHoursUsed = 1,
            holidayWorkOverride = true,
            holidayOverride = true,
            sickPayRate = null
        )

        val restored = testRepository()
        assertTrue(restored.importFromJson(source.exportJson()))

        assertEquals(source.exportJson(), restored.exportJson())
        assertEquals(2.75, restored.payCoefficientValue, 0.001)
        assertEquals(610.0, restored.getHourlyValueForMonth(2026, 2), 0.001)
        assertEquals(320.0, restored.foodPerDayValue, 0.001)
        assertEquals(360.0, restored.transportDailyAmountValue, 0.001)
        assertEquals(12, restored.initialCompBalanceValue)
        assertEquals(BrigadeType.CETVOROBRIGADA, restored.brigadeTypeValue)
        assertEquals(PayBaseSelectionMode.MANUAL, restored.getMonthlyPayBases(2026, 2)?.selectionMode)
        assertEquals(1200.0, restored.getMonthlyGrossAdditions(2026, 2).mealGross ?: Double.NaN, 0.001)
        assertEquals(50_000.0, restored.getMonthlyNetAdjustments(2026, 2).advancePaymentNet, 0.001)
        assertEquals(5.0, restored.getVacationYearBalance(2026)?.carryoverDaysInitial ?: 0.0, 0.001)
        assertEquals(Shift.THIRD, restored.get(2026, 2, 3)?.shift)
        assertEquals(Shift.FIRST, restored.get(2026, 2, 3)?.shift2)
    }

    @Test
    fun `akontacija se cuva po izabranom mesecu kroz export import`() {
        repo.setMonthlyAdvancePaymentNet(2026, 5, 50_000.0)
        val json = JSONObject(repo.exportJson())
        val mayAdjustments = json
            .getJSONObject("monthly_net_adjustments")
            .getJSONObject("2026")
            .getJSONObject("05")

        assertEquals(50_000.0, mayAdjustments.getDouble("advance_payment_net"), 0.001)

        val restored = testRepository()
        assertTrue(restored.importFromJson(json.toString()))
        assertEquals(50_000.0, restored.getMonthlyNetAdjustments(2026, 5).advancePaymentNet, 0.001)
        assertEquals(0.0, restored.getMonthlyNetAdjustments(2026, 4).advancePaymentNet, 0.001)
    }

    @Test
    fun `akontacija iz obracuna upisuje uplacen iznos za mesec`() {

        assertTrue(repo.recordAdvancePaymentFromPayslip(MonthKey(2026, 8), 38_942.0))

        assertEquals(38_942.0, repo.getMonthlyNetAdjustments(2026, 8).advancePaymentNet, 0.001)
    }

    @Test
    fun `akontacija iz obracuna zamenjuje rucni iznos a isti iznos nije promena`() {
        repo.setMonthlyAdvancePaymentNet(2026, 8, 50_000.0)

        assertTrue(repo.recordAdvancePaymentFromPayslip(MonthKey(2026, 8), 38_942.0))
        assertFalse(repo.recordAdvancePaymentFromPayslip(MonthKey(2026, 8), 38_942.0))
        assertEquals(38_942.0, repo.getMonthlyNetAdjustments(2026, 8).advancePaymentNet, 0.001)
    }

    @Test
    fun `stari backup sa iskljucenom auto akontacijom i dalje upisuje akontaciju`() {
        val restored = testRepository()
        assertTrue(restored.importFromJson("{\"pay_coefficient\": 1.0, \"auto_advance_import_enabled\": false}"))

        assertTrue(restored.recordAdvancePaymentFromPayslip(MonthKey(2026, 8), 38_942.0))
        assertFalse(JSONObject(restored.exportJson()).has("auto_advance_import_enabled"))
    }

    @Test
    fun `nova instalacija ima iskljuceno citanje akontacije iz obavestenja`() {
        assertFalse(testRepository().notificationAccessPromptSuppressed)
    }

    @Test
    fun `stari json sa zastavicom podsetnika za racun hrane se ucitava bez gubitka`() {
        val restored = testRepository()
        assertTrue(
            restored.importFromJson(
                "{\"pay_coefficient\": 1.0, \"food_receipt_reminder_enabled\": true, " +
                    "\"config_difficult_work_conditions_percent\": 7.5, \"config_transport_per_work_day\": 321.0}"
            )
        )
        assertEquals(7.5, restored.difficultWorkConditionsPercentValue, 0.001)
        assertEquals(321.0, restored.transportDailyAmountValue, 0.001)
        assertFalse(JSONObject(restored.exportJson()).has("food_receipt_reminder_enabled"))
    }

    @Test
    fun `podesavanja auto akontacije se cuvaju kroz export import`() {
        repo.notificationAccessPromptSuppressed = true
        val json = JSONObject(repo.exportJson())

        assertTrue(json.getBoolean("notification_access_prompt_suppressed"))

        val restored = testRepository()
        assertTrue(restored.importFromJson(json.toString()))
        assertTrue(restored.notificationAccessPromptSuppressed)
    }

    @Test
    fun `samo plata za rad ispod kalendara je podrazumevano ukljuceno`() {
        assertTrue(repo.mainPaySalaryOnly)
    }

    @Test
    fun `iskljuceno samo plata ispod kalendara se cuva kroz export import i stari backup dobija samo platu`() {
        var changeCount = 0
        val token = repo.onChange { changeCount += 1 }
        repo.mainPaySalaryOnly = false
        repo.mainPaySalaryOnly = false
        repo.removeOnChange(token)
        assertEquals(1, changeCount)

        val json = JSONObject(repo.exportJson())
        assertFalse(json.getBoolean("main_pay_salary_only"))

        val restored = testRepository()
        assertTrue(restored.importFromJson(json.toString()))
        assertFalse(restored.mainPaySalaryOnly)

        val legacy = testRepository()
        assertTrue(legacy.importFromJson("{\"pay_coefficient\": 1.0, \"main_pay_includes_transport\": true}"))
        assertTrue(legacy.mainPaySalaryOnly)
    }

    @Test
    fun `stari backup bez zastavice auto akontacije ostaje ukljucen`() {
        val restored = testRepository()
        assertTrue(restored.importFromJson("{\"pay_coefficient\": 1.0}"))

        assertFalse(restored.notificationAccessPromptSuppressed)
    }

    @Test
    fun `stari backup bez akontacije koristi nulu`() {
        assertTrue(repo.importFromJson("{\"pay_coefficient\": 1.0}"))

        assertEquals(0.0, repo.getMonthlyNetAdjustments(2026, 5).advancePaymentNet, 0.001)
        assertEquals(null, repo.getMonthlyGrossAdditions(2026, 5).mealGross)
        assertEquals(null, repo.getMonthlyGrossAdditions(2026, 5).mealSpentNet)
        assertEquals(0.0, repo.getMonthlyGrossAdditions(2026, 5).mealSettlementAdjustmentNet, 0.001)
    }

    @Test
    fun `eksplicitna nula potrosnje hrane ostaje sacuvana i posle importa`() {
        repo.setMonthlyActualFoodSpentNet(2026, 6, 0.0)

        val restored = testRepository()
        assertTrue(restored.importFromJson(repo.exportJson()))

        val additions = restored.getMonthlyGrossAdditions(2026, 6)
        assertEquals(0.0, additions.mealSpentNet ?: Double.NaN, 0.001)
        assertEquals(0.0, restored.getNetPayBreakdown(2026, 6).mealSpentNetApplied, 0.001)
    }

    @Test
    fun `prazan unos potrosnje kroz backup ostaje podrazumevano celo pravo`() {
        repo.foodPerDayValue = 280.0
        repo.set(2026, 6, 4, Shift.FIRST, 8, holidayOverride = false)

        val restored = testRepository()
        assertTrue(restored.importFromJson(repo.exportJson()))

        val automaticZero = restored.getNetPayBreakdown(2026, 6)
        assertEquals(MealSpentSource.ENTITLEMENT_ASSUMED, automaticZero.mealSpentSource)
        assertEquals(280.0, automaticZero.mealSpentNetApplied, 0.001)

        val absent = restored.getNetPayBreakdown(2026, 6)
        assertEquals(MealSpentSource.ENTITLEMENT_ASSUMED, absent.mealSpentSource)
        assertTrue(absent.mealSpentNetApplied > 0.0)
    }

    @Test
    fun `nulta korekcija preseka je validna i ne brise ostale podatke hrane`() {
        repo.setMonthlyActualFoodSpentNet(2026, 6, 3_620.0)
        repo.setMonthlyMealSettlementAdjustmentNet(2026, 6, 220.0)

        repo.setMonthlyMealSettlementAdjustmentNet(2026, 6, 0.0)

        val additions = repo.getMonthlyGrossAdditions(2026, 6)
        assertEquals(0.0, additions.mealSettlementAdjustmentNet, 0.001)
        assertEquals(3_620.0, additions.mealSpentNet ?: Double.NaN, 0.001)
    }

    @Test
    fun `potrosena hrana akontacija i kredit se svako odbijaju tacno jednom`() {
        repo.foodPerDayValue = 280.0
        repo.set(2026, 5, 4, Shift.FIRST, 8, holidayOverride = false)
        repo.setMonthlyActualFoodSpentNet(2026, 5, 3_620.0)
        repo.setMonthlyAdvancePaymentNet(2026, 6, 10_000.0)
        repo.savePayslip(payslipWithDeductions(5, "000451 NLB Komercijalna banka 100,000.00 20 5,000.00"), withPayBase = false)

        val payroll = repo.getNetPayBreakdown(2026, 5)
        val receipt = repo.getMonthlyReceipt(2026, 6)
        val finalPayment = repo.getMonthlyFinalPaymentBreakdown(2026, 6)

        // Stari ručni unos (3.620) se ignoriše: skida se celo pravo po upisanim danima.
        assertEquals(payroll.mealAllowanceNet, payroll.mealSpentNet, 0.01)
        assertEquals(
            payroll.netPayBeforeDeductions - payroll.mealSpentNet - payroll.unionDuesNet,
            payroll.displayPay,
            0.01
        )
        assertEquals(receipt.salaryNetPay, finalPayment.netPayBeforeAdvance, 0.01)
        assertEquals(5_000.0, finalPayment.deductionsNet, 0.01)
        assertEquals(
            (receipt.salaryNetPay - 10_000.0 - 5_000.0).coerceAtLeast(0.0),
            finalPayment.finalRemainingNetPay,
            0.01
        )
    }

    @Test
    fun `backup polje za stvarni neto se ignorise i ne menja procenu isplate`() {
        repo.foodPerDayValue = 0.0
        repo.set(2026, 4, 6, Shift.FIRST, 8, holidayOverride = false)
        val estimatedNet = repo.getMonthlyReceipt(2026, 5).salaryNetPay
        val json = JSONObject(repo.exportJson())
        json.put(
            "monthly_net_adjustments",
            JSONObject(
                """{"2026":{"04":{"actual_salary_net_pay":123456.78}}}"""
            )
        )

        val restored = testRepository()
        assertTrue(restored.importFromJson(json.toString()))

        assertEquals(estimatedNet, restored.getMonthlyReceipt(2026, 5).salaryNetPay, 0.01)
    }

    @Test
    fun `akontacija se cuva i kada konacna isplata ne moze da se izracuna`() {
        repo.basePayValue = 1_000.0
        repo.set(2026, 5, 4, Shift.FIRST, 8, holidayOverride = false)
        repo.setMonthlyAdvancePaymentNet(2026, 5, 50_000.0)

        val finalPayment = repo.getMonthlyFinalPaymentBreakdown(2026, 5)

        assertEquals(50_000.0, repo.getMonthlyNetAdjustments(2026, 5).advancePaymentNet, 0.001)
        assertEquals(false, finalPayment.isNetPayAvailable)
        assertEquals(0.0, finalPayment.finalRemainingNetPay, 0.001)
    }

    @Test
    fun `konacna isplata koristi neto platu bez regresa za preostalo dvadesetog`() {
        repo.foodPerDayValue = 0.0
        repo.set(2026, 4, 6, Shift.FIRST, 8, holidayOverride = false)
        repo.set(2026, 5, 4, Shift.FIRST, 8, holidayOverride = false)

        val receipt = repo.getMonthlyReceipt(2026, 5)
        val finalPayment = repo.getMonthlyFinalPaymentBreakdown(2026, 5)

        assertTrue(receipt.regressNetPay > 0.0)
        assertEquals(receipt.salaryNetPay, finalPayment.netPayBeforeAdvance, 0.01)
        assertEquals(0.0, finalPayment.deductionsNet, 0.01)
        assertEquals(receipt.salaryNetPay, finalPayment.finalRemainingNetPay, 0.01)
        assertEquals(receipt.regressNetPay, finalPayment.remainingRegressNetPay, 0.01)
        assertEquals(
            receipt.salaryNetPay + receipt.regressNetPay + receipt.transportAmount,
            receipt.totalReceivedThisMonth,
            0.01
        )
    }

    @Test
    fun `konacna isplata ne odbija ponovo hranu vec uracunatu u neto platu`() {
        repo.foodPerDayValue = 300.0
        repo.set(2026, 5, 4, Shift.FIRST, 8, holidayOverride = false)
        repo.setMonthlyGrossAdditions(
            year = 2026,
            month = 5,
            additions = MonthlyGrossAdditions(year = 2026, month = 5, mealSpentNet = 450.0)
        )
        repo.setMonthlyAdvancePaymentNet(2026, 6, 1_000.0)

        val receipt = repo.getMonthlyReceipt(2026, 6)
        val finalPayment = repo.getMonthlyFinalPaymentBreakdown(2026, 6)

        assertEquals(300.0, repo.getSummary(2026, 5).foodAllowance, 0.01)
        assertEquals(receipt.salaryNetPay, finalPayment.netPayBeforeAdvance, 0.01)
        assertEquals(0.0, finalPayment.deductionsNet, 0.01)
        assertEquals(
            (receipt.salaryNetPay - 1_000.0).coerceAtLeast(0.0),
            finalPayment.finalRemainingNetPay,
            0.01
        )
        assertEquals(0.0, finalPayment.remainingRegressNetPay, 0.01)
    }

    @Test
    fun `konacna isplata prikazuje obustavu sa liste i kada hrana nije prekoracena`() {
        repo.foodPerDayValue = 300.0
        repo.set(2026, 5, 4, Shift.FIRST, 8, holidayOverride = false)
        repo.setMonthlyGrossAdditions(
            year = 2026,
            month = 5,
            additions = MonthlyGrossAdditions(year = 2026, month = 5, mealSpentNet = 250.0)
        )
        repo.savePayslip(payslipWithDeductions(5, "000451 NLB Komercijalna banka 2,920,783.99 62 48,839.50"), withPayBase = false)

        val finalPayment = repo.getMonthlyFinalPaymentBreakdown(2026, 6)

        assertEquals(48_839.50, finalPayment.deductionsNet, 0.01)
        assertEquals(listOf("NLB Komercijalna banka"), finalPayment.deductions.map { it.name })
        assertEquals(0.0, finalPayment.remainingRegressNetPay, 0.01)
    }

    @Test
    fun `stvarna potrosena hrana manja od prava ne pravi prekoracenje niti se dodaje na platu`() {
        repo.foodPerDayValue = 300.0
        repo.set(2026, 4, 6, Shift.FIRST, 8, holidayOverride = false)
        repo.setMonthlyActualFoodSpentNet(2026, 4, 250.0)

        val receipt = repo.getMonthlyReceipt(2026, 5)
        val food = repo.getMonthlyFoodSpendingBreakdown(2026, 4)
        val finalPayment = repo.getMonthlyFinalPaymentBreakdown(2026, 5)

        assertEquals(0.0, food.foodOverageNet, 0.01)
        assertEquals(0.0, food.unusedFoodNet, 0.01)
        assertEquals(0.0, finalPayment.deductionsNet, 0.01)
        assertEquals(receipt.salaryNetPay, finalPayment.finalRemainingNetPay, 0.01)
    }

    @Test
    fun `junski neto unos ostaje u backupu a obracun koristi potvrđeno pravo`() {
        repo.foodPerDayValue = 280.0
        repo.set(2026, 6, 1, Shift.FIRST, 8, holidayOverride = false)
        repo.setMonthlyActualFoodSpentNet(2026, 6, 3_620.0)

        val restored = testRepository()
        assertTrue(restored.importFromJson(repo.exportJson()))

        val additions = restored.getMonthlyGrossAdditions(2026, 6)
        val breakdown = restored.getNetPayBreakdown(2026, 6)
        assertEquals(3_620.0, additions.mealSpentNet ?: 0.0, 0.01)
        assertEquals(MANUAL_SPENDING_SAFE_DEFAULT_MODE, breakdown.mealCalculationMode)
        assertEquals(MealEntitlementSource.CONFIRMED_MONTHLY_OVERRIDE, breakdown.mealEntitlementSource)
        assertEquals(3_840.0, breakdown.mealEntitlementNet, 0.01)
        assertEquals(5_477.89, breakdown.mealGrossApplied, 0.01)
        assertEquals(3_840.0, breakdown.mealSpentNetApplied, 0.01)
        assertEquals(3_840.0, breakdown.mealNetDeduction, 0.01)
    }

    @Test
    fun `nova dnevna cena toplog obroka ne menja prethodne mesece`() {
        repo.foodPerDayValue = 200.0
        repo.set(2026, 5, 4, Shift.FIRST, 8, holidayOverride = false)
        repo.set(2026, 6, 1, Shift.FIRST, 8, holidayOverride = false)

        repo.setFoodPerDayFromMonth(2026, 6, 280.0)

        assertEquals(200.0, repo.getSummary(2026, 5).foodAllowance, 0.01)
        assertEquals(280.0, repo.getSummary(2026, 6).foodAllowance, 0.01)

        val restored = testRepository()
        assertTrue(restored.importFromJson(repo.exportJson()))
        assertEquals(200.0, restored.getSummary(2026, 5).foodAllowance, 0.01)
        assertEquals(280.0, restored.getSummary(2026, 6).foodAllowance, 0.01)
    }

    @Test
    fun `hrana trece smene se knjizi po datumu obroka na granici meseca`() {
        repo.foodPerDayValue = 280.0
        for (day in 22..26) {
            repo.set(2027, 2, day, Shift.THIRD, 8, holidayOverride = false)
        }
        repo.set(2027, 2, 28, Shift.THIRD, 8, holidayOverride = false)

        val february = repo.getSummary(2027, 2)
        val march = repo.getSummary(2027, 3)

        assertEquals(1_400.0, february.foodAllowance, 0.01)
        assertEquals(280.0, march.foodAllowance, 0.01)
    }

    @Test
    fun `promena poslednje trece smene prethodnog meseca osvezava hranu sledeceg meseca`() {
        repo.foodPerDayValue = 280.0
        assertEquals(0.0, repo.getSummary(2027, 3).foodAllowance, 0.01)

        repo.set(2027, 2, 28, Shift.THIRD, 8, holidayOverride = false)

        assertEquals(280.0, repo.getSummary(2027, 3).foodAllowance, 0.01)
    }

    @Test
    fun `brisanje stvarne hrane cuva ostale mesecne dodatke`() {
        repo.setMonthlyGrossAdditions(
            year = 2026,
            month = 4,
            additions = MonthlyGrossAdditions(year = 2026, month = 4, bonusGross = 2_000.0)
        )
        repo.setMonthlyActualFoodSpentNet(2026, 4, 250.0)

        repo.setMonthlyActualFoodSpentNet(2026, 4, null)

        val additions = repo.getMonthlyGrossAdditions(2026, 4)
        assertEquals(null, additions.mealSpentNet)
        assertEquals(2_000.0, additions.bonusGross, 0.01)
    }

    @Test
    fun `deleteMonth briše sve zapise za mesec`() {
        repo.set(2025, 1, 6, Shift.FIRST, 8)
        repo.set(2025, 1, 7, Shift.SECOND, 8)
        repo.set(2025, 2, 3, Shift.THIRD, 8)

        val count = repo.deleteMonth(2025, 1)
        assertEquals(2, count)
        assertTrue(repo.getMonthRecords(2025, 1).isEmpty())
        assertEquals(1, repo.getMonthRecords(2025, 2).size)
    }

    @Test
    fun `deleteMonth briše mesečne unose plate samo za taj mesec`() {
        repo.setMonthlyPayBases(
            year = 2025,
            month = 1,
            bases = MonthlyPayBases(year = 2025, month = 1, vacationHourlyBase = 930.0)
        )
        repo.setMonthlyGrossAdditions(
            year = 2025,
            month = 1,
            additions = MonthlyGrossAdditions(year = 2025, month = 1, bonusGross = 1_000.0)
        )
        repo.setMonthlyPayBases(
            year = 2025,
            month = 2,
            bases = MonthlyPayBases(year = 2025, month = 2, vacationHourlyBase = 940.0)
        )
        repo.setMonthlyGrossAdditions(
            year = 2025,
            month = 2,
            additions = MonthlyGrossAdditions(year = 2025, month = 2, bonusGross = 2_000.0)
        )

        repo.deleteMonth(2025, 1)

        assertNull(repo.getMonthlyPayBases(2025, 1))
        assertEquals(0.0, repo.getMonthlyGrossAdditions(2025, 1).totalGross, 0.01)
        assertEquals(940.0, repo.getMonthlyPayBases(2025, 2)!!.vacationHourlyBase!!, 0.01)
        assertEquals(2_000.0, repo.getMonthlyGrossAdditions(2025, 2).bonusGross, 0.01)
    }

    @Test
    fun `deleteAllData briše sve zapise`() {
        repo.set(2025, 1, 6, Shift.FIRST, 8)
        repo.set(2025, 2, 3, Shift.THIRD, 8)

        val count = repo.deleteAllData()
        assertEquals(2, count)
        assertTrue(repo.getMonthRecords(2025, 1).isEmpty())
        assertTrue(repo.getMonthRecords(2025, 2).isEmpty())
    }

    @Test
    fun `deleteAllData resetuje popunjene mesece i čuva globalna podešavanja`() {
        repo.grossHourlyRateValue = 700.0
        repo.foodPerDayValue = 350.0
        repo.initialCompBalanceValue = 12
        repo.minuliRadStartYearValue = 2019
        repo.initialVacationBalanceValue = 20.0
        repo.set(2025, 1, 6, Shift.FIRST, 8)
        repo.set(2025, 2, 3, Shift.THIRD, 8)
        repo.setMonthlyPayBases(
            year = 2025,
            month = 1,
            bases = MonthlyPayBases(year = 2025, month = 1, vacationHourlyBase = 930.0)
        )
        repo.setMonthlyGrossAdditions(
            year = 2025,
            month = 1,
            additions = MonthlyGrossAdditions(year = 2025, month = 1, bonusGross = 1_000.0)
        )

        val count = repo.deleteAllData()

        assertEquals(2, count)
        assertTrue(repo.getAllMonthsSorted().isEmpty())
        assertNull(repo.getMonthlyPayBases(2025, 1))
        assertEquals(0.0, repo.getMonthlyGrossAdditions(2025, 1).totalGross, 0.01)
        assertEquals(700.0, repo.grossHourlyRateValue, 0.01)
        assertEquals(350.0, repo.foodPerDayValue, 0.01)
        assertEquals(12, repo.initialCompBalanceValue)
        assertEquals(2019, repo.minuliRadStartYearValue)
        assertEquals(20.0, repo.initialVacationBalanceValue, 0.01)
    }

    // ============= setBatch testovi =============

    @Test
    fun `setBatch dodaje više zapisa odjednom`() {
        val batch = listOf(
            BatchRecord(2025, 1, 6, Shift.FIRST, 8),
            BatchRecord(2025, 1, 7, Shift.SECOND, 8),
            BatchRecord(2025, 1, 8, Shift.THIRD, 8)
        )
        repo.setBatch(batch)

        assertEquals(3, repo.getMonthRecords(2025, 1).size)
        assertEquals(Shift.FIRST, repo.get(2025, 1, 6)!!.shift)
        assertEquals(Shift.SECOND, repo.get(2025, 1, 7)!!.shift)
        assertEquals(Shift.THIRD, repo.get(2025, 1, 8)!!.shift)
    }

    @Test
    fun `setBatch prepisuje postojeće zapise`() {
        repo.set(2025, 1, 6, Shift.FIRST, 8)
        val batch = listOf(
            BatchRecord(2025, 1, 6, Shift.THIRD, 8)
        )
        repo.setBatch(batch)
        assertEquals(Shift.THIRD, repo.get(2025, 1, 6)!!.shift)
    }

    // ============= Cache invalidation testovi =============

    @Test
    fun `getSummary se ažurira posle set poziva`() {
        repo.set(2025, 1, 6, Shift.FIRST, 8) // Ponedeljak
        val summary1 = repo.getSummary(2025, 1)
        assertEquals(8, summary1.workHours)

        repo.set(2025, 1, 7, Shift.FIRST, 8) // Utorak
        val summary2 = repo.getSummary(2025, 1)
        assertEquals(16, summary2.workHours)
    }

    @Test
    fun `getSummary se ažurira posle delete poziva`() {
        repo.set(2025, 1, 6, Shift.FIRST, 8)
        repo.set(2025, 1, 7, Shift.SECOND, 8)
        assertEquals(16, repo.getSummary(2025, 1).workHours)

        repo.delete(2025, 1, 7)
        assertEquals(8, repo.getSummary(2025, 1).workHours)
    }

    @Test
    fun `getSummary prazan mesec vraca nula sate`() {
        val summary = repo.getSummary(2025, 3)
        assertEquals(0, summary.workHours)
        assertEquals(0.0, summary.totalPay, 0.01)
    }

    // ============= Comp balance keš testovi =============

    @Test
    fun `getCompBalanceAtMonthStart koristi initialCompBalance`() {
        repo.initialCompBalanceValue = 20
        val balance = repo.getCompBalanceAtMonthStart(2025, 1)
        assertEquals(20, balance)
    }

    @Test
    fun `initial comp balance minutes se cuvaju u summary bez zaokruzivanja`() {
        repo.initialCompBalanceMinutesValue = 330

        val summary = repo.getSummary(2025, 1)

        assertEquals(5, repo.initialCompBalanceValue)
        assertEquals(330, repo.initialCompBalanceMinutesValue)
        assertEquals(5, summary.compTimeStarting)
        assertEquals(330, summary.compTimeStartingMinutes)
        assertEquals(330, summary.compTimeEndingMinutes)
    }

    @Test
    fun `bilans plus jedan sat pokriva dva izlaska po trideset minuta`() {
        repo.initialCompBalanceMinutesValue = 60
        repo.set(2025, 1, 6, Shift.FIRST, 7, regularShortageMinutes = 30)
        repo.set(2025, 1, 7, Shift.FIRST, 7, regularShortageMinutes = 30)

        val summary = repo.getSummary(2025, 1)

        assertEquals(60, summary.compTimeStartingMinutes)
        assertEquals(60, summary.compTimeDeficitMinutes)
        assertEquals(0, summary.compTimeEndingMinutes)
    }

    @Test
    fun `getCompBalanceAtMonthStart akumulira iz prethodnih meseci`() {
        repo.initialCompBalanceValue = 10

        // Januar: zaradimo 8 sati comp time (vikend)
        repo.set(
            2025, 1, 4, Shift.FIRST, 8,
            weekendOvertimeType = OvertimeType.COMP_TIME
        )

        // Februar: počinjemo sa 10 + 8 = 18
        val febBalance = repo.getCompBalanceAtMonthStart(2025, 2)
        assertEquals(18, febBalance)
    }

    @Test
    fun `comp balance keš se invalidira posle promene zapisa`() {
        repo.initialCompBalanceValue = 10
        repo.set(
            2025, 1, 4, Shift.FIRST, 8,
            weekendOvertimeType = OvertimeType.COMP_TIME
        )
        // Puni keš
        assertEquals(18, repo.getCompBalanceAtMonthStart(2025, 2))

        // Brišemo januar zapis - keš treba da se invalidira
        repo.delete(2025, 1, 4)
        assertEquals(10, repo.getCompBalanceAtMonthStart(2025, 2))
    }

    // ============= Konfiguracija testovi =============

    @Test
    fun `poreska podesavanja imaju podrazumevane vrednosti`() {
        assertEquals(918.81, repo.sickPayBaseHourlyValue, 0.01)
        assertEquals(19.9, repo.employeeContributionsPercentValue, 0.01)
        assertEquals(34_221.0, repo.taxReliefValue, 0.01)
        assertEquals(10.0, repo.taxPercentValue, 0.01)
    }

    @Test
    fun `poreska podesavanja se cuvaju kroz export import`() {
        repo.sickPayBaseHourlyValue = 950.75
        repo.employeeContributionsPercentValue = 20.1
        repo.taxReliefValue = 35_000.0
        repo.taxPercentValue = 11.0

        val repo2 = testRepository()
        val success = repo2.importFromJson(repo.exportJson())

        assertTrue(success)
        assertEquals(950.75, repo2.sickPayBaseHourlyValue, 0.01)
        assertEquals(20.1, repo2.employeeContributionsPercentValue, 0.01)
        assertEquals(35_000.0, repo2.taxReliefValue, 0.01)
        assertEquals(11.0, repo2.taxPercentValue, 0.01)
    }

    @Test
    fun `satnica podrazumevano koristi koeficijent 2 point 82 i vrednost sata 226 u januaru 2026`() {
        assertEquals(2.82, repo.payCoefficientValue, 0.001)
        assertEquals(226.0, repo.getHourlyValueForMonth(2026, 1), 0.001)
        assertEquals(637.32, repo.getGrossHourlyRateForMonth(2026, 1), 0.001)
    }

    @Test
    fun `satnica pre januara 2026 koristi vrednost sata 214 point 5`() {
        assertEquals(214.5, repo.getHourlyValueForMonth(2025, 12), 0.001)
        assertEquals(604.89, repo.getGrossHourlyRateForMonth(2025, 12), 0.001)
    }

    @Test
    fun `dnevna plata koristi vrednost sata za mesec zapisa`() {
        repo.payCoefficientValue = 2.82
        repo.set(2025, 12, 1, Shift.FIRST, 8, holidayOverride = false)
        repo.set(2026, 1, 5, Shift.FIRST, 8, holidayOverride = false)

        assertEquals(4_839.12, repo.calculateDailyPay(repo.get(2025, 12, 1)!!), 0.01)
        assertEquals(5_098.56, repo.calculateDailyPay(repo.get(2026, 1, 5)!!), 0.01)
    }

    @Test
    fun `mesecni breakdown prikazuje bruto satnicu tog meseca`() {
        repo.payCoefficientValue = 2.82
        repo.set(2025, 12, 1, Shift.FIRST, 8, holidayOverride = false)
        repo.set(2026, 1, 5, Shift.FIRST, 8, holidayOverride = false)

        assertEquals(604.89, repo.getNetPayBreakdown(2025, 12).grossHourlyRate, 0.01)
        assertEquals(637.32, repo.getNetPayBreakdown(2026, 1).grossHourlyRate, 0.01)
    }

    @Test
    fun `mesecni breakdown nosi vrednost sata koriscenu za taj mesec`() {
        repo.payCoefficientValue = 2.82
        repo.setHourlyValueChange(2026, 7, 240.0)

        assertEquals(214.5, repo.getNetPayBreakdown(2025, 12).hourlyValue!!, 0.001)
        assertEquals(226.0, repo.getNetPayBreakdown(2026, 1).hourlyValue!!, 0.001)
        assertEquals(240.0, repo.getNetPayBreakdown(2026, 8).hourlyValue!!, 0.001)
    }

    @Test
    fun `isplata u februaru koristi januarski obracun, januarski prevoz i februarski regres`() {
        repo.basePayValue = 1_000.0
        repo.transportDailyAmountValue = 240.0
        repo.employeeContributionsPercentValue = 20.0
        repo.taxReliefValue = 0.0
        repo.taxPercentValue = 10.0
        repo.set(2026, 1, 5, Shift.FIRST, 8, holidayOverride = false)
        repo.set(2026, 1, 6, Shift.SECOND, 4, holidayOverride = false)
        repo.set(2026, 1, 7, Shift.THIRD, 3, holidayOverride = false)
        repo.set(2026, 2, 2, Shift.FIRST, 8, holidayOverride = false)
        repo.set(2026, 2, 3, Shift.FIRST, 8, holidayOverride = false)

        val januarySalary = repo.getNetPayBreakdown(2026, 1).displayPay
        val februaryRegress = repo.getNetPayBreakdown(2026, 2).netRegressAddition
        val receipt = repo.getMonthlyReceipt(2026, 2)

        assertEquals(MonthKey(2026, 1), receipt.salaryMonth)
        assertEquals(januarySalary, receipt.salaryNetPay, 0.01)
        assertEquals(20_000.0, februaryRegress, 0.01)
        assertEquals(februaryRegress, receipt.regressNetPay, 0.01)
        assertEquals(januarySalary + februaryRegress, receipt.displayPay, 0.01)
        assertEquals(2, receipt.transportEligibleDays)
        assertEquals(480.0, receipt.transportAmount, 0.01)
        assertEquals(januarySalary + februaryRegress + 480.0, receipt.totalReceivedThisMonth, 0.01)
    }

    @Test
    fun `isplata u junu koristi majski obracun bez majskog regresa`() {
        repo.basePayValue = 1_000.0
        repo.transportDailyAmountValue = 240.0
        repo.employeeContributionsPercentValue = 20.0
        repo.taxReliefValue = 0.0
        repo.taxPercentValue = 10.0
        repo.set(2026, 5, 4, Shift.FIRST, 8, holidayOverride = false)
        repo.set(2026, 5, 5, Shift.SECOND, 8, holidayOverride = false)

        val mayBreakdown = repo.getNetPayBreakdown(2026, 5)
        val maySalaryWithoutRegress = mayBreakdown.displayPay - mayBreakdown.netRegressAddition
        val receipt = repo.getMonthlyReceipt(2026, 6)

        assertEquals(MonthKey(2026, 5), receipt.salaryMonth)
        assertEquals(20_000.0, mayBreakdown.netRegressAddition, 0.01)
        assertEquals(maySalaryWithoutRegress, receipt.salaryNetPay, 0.01)
        assertEquals(0.0, receipt.regressNetPay, 0.01)
        assertEquals(maySalaryWithoutRegress, receipt.displayPay, 0.01)
        assertEquals(2, receipt.transportEligibleDays)
        assertEquals(maySalaryWithoutRegress + 480.0, receipt.totalReceivedThisMonth, 0.01)
    }

    @Test
    fun `isplata nije dostupna bez prethodnog meseca i ne dodaje regres`() {
        repo.basePayValue = 1_000.0
        repo.employeeContributionsPercentValue = 20.0
        repo.taxReliefValue = 0.0
        repo.taxPercentValue = 10.0
        populateCompleteMonth(2026, 5)

        val receipt = repo.getMonthlyReceipt(2026, 5)

        assertEquals(MonthKey(2026, 4), receipt.salaryMonth)
        assertFalse(receipt.isSalaryAvailable)
        assertEquals(0.0, receipt.salaryNetPay, 0.01)
        assertEquals(0.0, receipt.regressNetPay, 0.01)
        assertEquals(0, receipt.transportEligibleDays)
        assertEquals(0.0, receipt.transportAmount, 0.01)
        assertEquals(0.0, receipt.displayPay, 0.01)
        assertEquals(0.0, receipt.totalReceivedThisMonth, 0.01)
    }

    @Test
    fun `prosek 3 poslednje neto plate koristi samo kompletne izabrane mesece`() {
        repo.grossHourlyRateValue = 1_000.0
        repo.foodPerDayValue = 0.0
        repo.employeeContributionsPercentValue = 20.0
        repo.taxReliefValue = 0.0
        repo.taxPercentValue = 10.0
        populateCompleteMonth(2025, 12)
        populateCompleteMonth(2026, 1)
        populateCompleteMonth(2026, 2)

        val average = repo.getRecentNetPayAverage(2026, 3, LocalDate.of(2026, 3, 21))
        val expectedAverage = (
            repo.getNetPayBreakdown(2025, 12).displayPay +
                repo.getNetPayBreakdown(2026, 1).displayPay +
                repo.getNetPayBreakdown(2026, 2).displayPay
            ) / 3.0

        assertEquals(listOf(MonthKey(2025, 12), MonthKey(2026, 1), MonthKey(2026, 2)), average.monthKeys)
        assertEquals(emptyList<MonthKey>(), average.missingMonthKeys)
        assertEquals(expectedAverage, average.averageNetPay!!, 0.01)
    }

    @Test
    fun `prosek 3 poslednje neto plate nije dostupan kad jedan izabrani mesec nije kompletan`() {
        repo.grossHourlyRateValue = 1_000.0
        repo.foodPerDayValue = 0.0
        repo.employeeContributionsPercentValue = 20.0
        repo.taxReliefValue = 0.0
        repo.taxPercentValue = 10.0
        populateCompleteMonth(2025, 12)
        populateCompleteMonth(2026, 1)

        val average = repo.getRecentNetPayAverage(2026, 3, LocalDate.of(2026, 3, 21))

        assertEquals(listOf(MonthKey(2025, 12), MonthKey(2026, 1), MonthKey(2026, 2)), average.monthKeys)
        assertEquals(listOf(MonthKey(2026, 2)), average.missingMonthKeys)
        assertNull(average.averageNetPay)
    }

    @Test
    fun `godisnji neto prosek koristi samo mesece sa korisnickim zapisima`() {
        repo.grossHourlyRateValue = 1_000.0
        repo.foodPerDayValue = 0.0
        repo.employeeContributionsPercentValue = 20.0
        repo.taxReliefValue = 0.0
        repo.taxPercentValue = 10.0
        populateCompleteMonth(2026, 5)

        val averages = repo.getAnnualNetPayAverages(2026)
        val mayPay = repo.getNetPayBreakdown(2026, 5).displayPay

        assertEquals(1, averages.monthCount)
        assertEquals(mayPay, averages.averagePay, 0.01)
        assertEquals(mayPay - 20_000.0, averages.averagePayWithoutRegress, 0.01)
    }

    @Test
    fun `rolling osnovica koristi bruto satnicu svakog prethodnog meseca`() {
        repo.payCoefficientValue = 2.82
        repo.foodPerDayValue = 0.0
        repo.employeeContributionsPercentValue = 0.0
        repo.taxReliefValue = 0.0
        repo.taxPercentValue = 0.0
        for (key in previousTwelveMonthKeys(2026, 5)) {
            populateCompleteMonth(key.year, key.month)
        }

        val calculation = repo.getRollingPayBaseCalculation(2026, 5)

        assertNotNull(calculation)
        assertTrue(calculation!!.months.any { it.year == 2025 && it.grossForAverage > 0.0 })
        assertTrue(calculation.months.any { it.year == 2026 && it.grossForAverage > 0.0 })
    }

    @Test
    fun `koeficijent i promene vrednosti sata se cuvaju kroz export import`() {
        repo.payCoefficientValue = 3.10
        repo.setHourlyValueChange(2026, 7, 240.0)

        val repo2 = testRepository()
        assertTrue(repo2.importFromJson(repo.exportJson()))

        assertEquals(3.10, repo2.payCoefficientValue, 0.001)
        assertEquals(240.0, repo2.getHourlyValueForMonth(2026, 7), 0.001)
        assertEquals(744.0, repo2.getGrossHourlyRateForMonth(2026, 7), 0.001)
    }

    @Test
    fun `stari bruto hourly backup migrira u koeficijent`() {
        val json = """
            {
              "config_gross_hourly_rate": 637.32,
              "2026": {
                "05": {
                  "04": {
                    "shift": 1,
                    "hours": 8
                  }
                }
              }
            }
        """.trimIndent()

        assertTrue(repo.importFromJson(json))
        assertEquals(2.82, repo.payCoefficientValue, 0.001)
        assertEquals(637.32, repo.getGrossHourlyRateForMonth(2026, 5), 0.001)
    }

    @Test
    fun `stari bruto hourly backup sa proizvoljnom vrednoscu koristi mesec iz zapisa`() {
        val json = """
            {
              "config_gross_hourly_rate": 678.0,
              "2025": {
                "12": {
                  "15": {
                    "shift": 1,
                    "hours": 8
                  }
                }
              }
            }
        """.trimIndent()

        assertTrue(repo.importFromJson(json))
        assertEquals(678.0 / 214.5, repo.payCoefficientValue, 0.001)
        assertEquals(678.0, repo.getGrossHourlyRateForMonth(2025, 12), 0.001)
    }

    @Test
    fun `stari bruto hourly backup bez zapisa koristi stabilan fallback januar 2026`() {
        val json = """
            {
              "config_gross_hourly_rate": 678.0
            }
        """.trimIndent()

        assertTrue(repo.importFromJson(json))
        assertEquals(678.0 / 226.0, repo.payCoefficientValue, 0.001)
        assertEquals(678.0, repo.getGrossHourlyRateForMonth(2026, 1), 0.001)
    }

    @Test
    fun `stari zapis bez minuta manjka se ucitava kao nula`() {
        val json = """
            {
              "2026": {
                "05": {
                  "04": {
                    "shift": 1,
                    "hours": 8
                  }
                }
              }
            }
        """.trimIndent()

        assertTrue(repo.importFromJson(json))

        assertEquals(0, repo.get(2026, 5, 4)!!.regularShortageMinutes)
    }

    @Test
    fun `minute manjka se cuvaju kroz json export import`() {
        repo.set(2026, 5, 4, Shift.FIRST, 7, regularShortageMinutes = 30)

        val exported = repo.exportJson()
        val exportedRecord = JSONObject(exported)
            .getJSONObject("2026")
            .getJSONObject("05")
            .getJSONObject("04")

        val restored = testRepository()
        assertTrue(restored.importFromJson(exported))

        assertEquals(30, exportedRecord.getInt("regular_shortage_minutes"))
        assertEquals(30, restored.get(2026, 5, 4)!!.regularShortageMinutes)
    }

    @Test
    fun `nevalidni minuti manjka iz json zapisa se tretiraju kao nula`() {
        val record = DayRecord.fromJson(
            year = 2026,
            month = 5,
            day = 4,
            data = JSONObject(
                """
                    {
                      "shift": 1,
                      "hours": 8,
                      "regular_shortage_minutes": 10
                    }
                """.trimIndent()
            )
        )

        assertEquals(0, record.regularShortageMinutes)
    }

    @Test
    fun `nevalidan uvezen koeficijent koristi podrazumevanu vrednost`() {
        val zeroJson = """{ "pay_coefficient": 0.0 }"""
        val negativeJson = """{ "pay_coefficient": -3.0 }"""

        assertTrue(repo.importFromJson(zeroJson))
        assertEquals(2.82, repo.payCoefficientValue, 0.001)

        assertTrue(repo.importFromJson(negativeJson))
        assertEquals(2.82, repo.payCoefficientValue, 0.001)
    }

    @Test
    fun `nevalidne promene vrednosti sata iz importa se ignorisu`() {
        val json = """
            {
              "hourly_value_changes": {
                "2026": {
                  "06": {
                    "hourly_value": 0.0
                  },
                  "07": {
                    "hourly_value": -240.0
                  },
                  "08": {
                    "hourly_value": 240.0
                  }
                }
              }
            }
        """.trimIndent()

        assertTrue(repo.importFromJson(json))
        assertEquals(226.0, repo.getHourlyValueForMonth(2026, 6), 0.001)
        assertEquals(226.0, repo.getHourlyValueForMonth(2026, 7), 0.001)
        assertEquals(240.0, repo.getHourlyValueForMonth(2026, 8), 0.001)
    }

    @Test
    fun `promene vrednosti sata su dostupne sortirane za podesavanja`() {
        repo.setHourlyValueChange(2026, 7, 240.0)
        repo.setHourlyValueChange(2025, 12, 214.5)

        val changes = repo.getHourlyValueChanges()

        assertEquals(2, changes.size)
        assertEquals(2025, changes[0].year)
        assertEquals(12, changes[0].month)
        assertEquals(214.5, changes[0].value, 0.001)
        assertEquals(2026, changes[1].year)
        assertEquals(7, changes[1].month)
        assertEquals(240.0, changes[1].value, 0.001)
    }

    @Test
    fun `brisanje promene vrednosti sata vraca podrazumevanu istoriju`() {
        repo.setHourlyValueChange(2026, 7, 240.0)
        assertEquals(240.0, repo.getHourlyValueForMonth(2026, 8), 0.001)

        repo.removeHourlyValueChange(2026, 7)

        assertTrue(repo.getHourlyValueChanges().isEmpty())
        assertEquals(226.0, repo.getHourlyValueForMonth(2026, 8), 0.001)
    }

    @Test
    fun `export sadrzi koeficijent i promene vrednosti sata bez starih bruto kljuceva`() {
        repo.payCoefficientValue = 3.10
        repo.setHourlyValueChange(2026, 7, 240.0)

        val json = JSONObject(repo.exportJson())
        val hourlyChanges = json.getJSONObject("hourly_value_changes")

        assertEquals(3.10, json.getDouble("pay_coefficient"), 0.001)
        assertEquals(240.0, hourlyChanges.getJSONObject("2026").getJSONObject("07").getDouble("hourly_value"), 0.001)
        assertFalse(json.has("config_gross_hourly_rate"))
        assertFalse(json.has("config_base_pay_hourly"))
    }

    @Test
    fun `mesecne osnovice naknada koriste vrednost za trenutni mesec`() {
        repo.setMonthlyPayBases(
            year = 2026,
            month = 5,
            bases = MonthlyPayBases(
                year = 2026,
                month = 5,
                vacationHourlyBase = 930.0,
                paidHolidayHourlyBase = 940.0,
                sickHourlyBase = 950.0
            )
        )

        val resolved = repo.getResolvedPayBases(2026, 5)

        assertEquals(930.0, resolved.vacationHourlyBase.value, 0.01)
        assertEquals(PayBaseSource.CURRENT_MONTH, resolved.vacationHourlyBase.source)
        assertEquals(940.0, resolved.paidHolidayHourlyBase.value, 0.01)
        assertEquals(PayBaseSource.CURRENT_MONTH, resolved.paidHolidayHourlyBase.source)
        assertEquals(950.0, resolved.sickHourlyBase.value, 0.01)
        assertEquals(PayBaseSource.CURRENT_MONTH, resolved.sickHourlyBase.source)
    }

    @Test
    fun `mesecne osnovice naknada koriste poslednju poznatu vrednost`() {
        repo.setMonthlyPayBases(
            year = 2026,
            month = 3,
            bases = MonthlyPayBases(
                year = 2026,
                month = 3,
                vacationHourlyBase = 912.87,
                paidHolidayHourlyBase = 913.0,
                sickHourlyBase = 918.81
            )
        )
        repo.setMonthlyPayBases(
            year = 2026,
            month = 5,
            bases = MonthlyPayBases(
                year = 2026,
                month = 5,
                selectionMode = PayBaseSelectionMode.MANUAL
            )
        )

        val resolved = repo.getResolvedPayBases(2026, 5)

        assertEquals(912.87, resolved.vacationHourlyBase.value, 0.01)
        assertEquals(PayBaseSource.LAST_KNOWN, resolved.vacationHourlyBase.source)
        assertEquals(2026, resolved.vacationHourlyBase.sourceYear)
        assertEquals(3, resolved.vacationHourlyBase.sourceMonth)
        assertEquals(918.81, resolved.sickHourlyBase.value, 0.01)
        assertEquals(PayBaseSource.LAST_KNOWN, resolved.sickHourlyBase.source)
    }

    @Test
    fun `manual pay base fallback uses configured global sick base without monthly sick base`() {
        repo.sickPayBaseHourlyValue = 950.75
        repo.setMonthlyPayBases(
            year = 2026,
            month = 5,
            bases = MonthlyPayBases(
                year = 2026,
                month = 5,
                selectionMode = PayBaseSelectionMode.MANUAL
            )
        )

        val resolved = repo.getResolvedPayBases(2026, 5)

        assertEquals(950.75, resolved.sickHourlyBase.value, 0.01)
        assertEquals(PayBaseSource.DEFAULT, resolved.sickHourlyBase.source)
    }

    @Test
    fun `satnica rucni izbor vazi za sve mesece i brise mesecne izuzetke`() {
        repo.setMonthlyPayBases(
            year = 2026,
            month = 5,
            bases = MonthlyPayBases(
                year = 2026,
                month = 5,
                vacationHourlyBase = 900.0,
                paidHolidayHourlyBase = 910.0,
                sickHourlyBase = 920.0,
                selectionMode = PayBaseSelectionMode.MANUAL
            )
        )

        repo.setDefaultPayBaseSelection(PayBaseSelectionMode.MANUAL, manualHourlyBase = 1_111.0)

        assertEquals(PayBaseSelectionMode.MANUAL, repo.defaultPayBaseSelectionModeValue)
        assertNull(repo.getMonthlyPayBases(2026, 5))
        val past = repo.getResolvedPayBases(2024, 1)
        val future = repo.getResolvedPayBases(2030, 12)
        assertEquals(1_111.0, past.vacationHourlyBase.value, 0.01)
        assertEquals(1_111.0, past.paidHolidayHourlyBase.value, 0.01)
        assertEquals(1_111.0, past.sickHourlyBase.value, 0.01)
        assertEquals(1_111.0, future.vacationHourlyBase.value, 0.01)
        assertEquals(1_111.0, future.paidHolidayHourlyBase.value, 0.01)
        assertEquals(1_111.0, future.sickHourlyBase.value, 0.01)
    }

    @Test
    fun `satnica dvanaest meseci izbor vazi za sve mesece i brise mesecne izuzetke`() {
        repo.basePayValue = 1_000.0
        populateCompletePreviousTwelveMonths(2026, 5)
        repo.setMonthlyPayBases(
            year = 2026,
            month = 5,
            bases = MonthlyPayBases.fromUnifiedPayBase(2026, 5, 1_111.0)
        )

        repo.setDefaultPayBaseSelection(PayBaseSelectionMode.CALCULATED_PREVIOUS_12_MONTHS)

        val resolved = repo.getResolvedPayBases(2026, 5)

        assertEquals(PayBaseSelectionMode.CALCULATED_PREVIOUS_12_MONTHS, repo.defaultPayBaseSelectionModeValue)
        assertNull(repo.getMonthlyPayBases(2026, 5))
        assertEquals(PayBaseSource.CALCULATED_PREVIOUS_12_MONTHS, resolved.vacationHourlyBase.source)
        assertEquals(resolved.vacationHourlyBase.value, resolved.paidHolidayHourlyBase.value, 0.01)
        assertEquals(resolved.vacationHourlyBase.value, resolved.sickHourlyBase.value, 0.0)
        assertEquals(PayBaseSource.CALCULATED_PREVIOUS_12_MONTHS, resolved.sickHourlyBase.source)
    }

    @Test
    fun `globalni satnica izbor se cuva kroz export import`() {
        repo.setDefaultPayBaseSelection(PayBaseSelectionMode.MANUAL, manualHourlyBase = 1_234.0)

        val repo2 = testRepository()
        val success = repo2.importFromJson(repo.exportJson())
        val resolved = repo2.getResolvedPayBases(2025, 1)

        assertTrue(success)
        assertEquals(PayBaseSelectionMode.MANUAL, repo2.defaultPayBaseSelectionModeValue)
        assertEquals(1_234.0, resolved.vacationHourlyBase.value, 0.01)
        assertEquals(1_234.0, resolved.paidHolidayHourlyBase.value, 0.01)
        assertEquals(1_234.0, resolved.sickHourlyBase.value, 0.01)
    }

    @Test
    fun `poznata osnovica januar za maj bez realnih meseci ostaje na sidru`() {
        prepareKnownBaseMayScenario()

        val calculation = resolvedKnownCalculation(2026, 5)

        assertEquals(
            listOf(MonthKey(2026, 1), MonthKey(2026, 2), MonthKey(2026, 3), MonthKey(2026, 4)),
            calculation.months.map { MonthKey(it.year, it.month) }
        )
        assertEquals(
            listOf("KNOWN_BASE", "CARRY_FORWARD", "CARRY_FORWARD", "CARRY_FORWARD"),
            calculation.months.map { it.source.name }
        )
        assertEquals(917.81, calculation.months.first().grossForAverage / calculation.months.first().hoursForAverage, 0.01)
        assertEquals(917.81, calculation.hourlyBase, 0.01)
        assertNull(repo.get(2026, 2, 2))
    }

    @Test
    fun `poznata osnovica za maj koristi samo realne mesece posle sidra`() {
        prepareKnownBaseMayScenario(realMonths = setOf(MonthKey(2026, 2), MonthKey(2026, 4)))

        val calculation = resolvedKnownCalculation(2026, 5)

        assertEquals(
            listOf(MonthKey(2026, 1), MonthKey(2026, 2), MonthKey(2026, 3), MonthKey(2026, 4)),
            calculation.months.map { MonthKey(it.year, it.month) }
        )
        assertEquals(
            listOf(
                "KNOWN_BASE",
                "REAL",
                "CARRY_FORWARD",
                "REAL"
            ),
            calculation.months.map { it.source.name }
        )
        assertNotNull(repo.getMonthRecords(2026, 2).firstOrNull())
        assertNull(repo.get(2026, 3, 2))
        assertNotNull(repo.getMonthRecords(2026, 4).firstOrNull())
    }

    @Test
    fun `poznata osnovica mart za maj bez realnog aprila ostaje na martovskoj osnovici`() {
        prepareKnownBaseMayScenario(anchorMonth = MonthKey(2026, 3), anchorBase = 918.81)

        val calculation = resolvedKnownCalculation(2026, 5)

        assertEquals(
            listOf(MonthKey(2026, 3), MonthKey(2026, 4)),
            calculation.months.map { MonthKey(it.year, it.month) }
        )
        assertEquals(listOf("KNOWN_BASE", "CARRY_FORWARD"), calculation.months.map { it.source.name })
        assertEquals(918.81, calculation.months[1].grossForAverage / calculation.months[1].hoursForAverage, 0.01)
        assertEquals(918.81, calculation.hourlyBase, 0.01)
    }

    @Test
    fun `poznata osnovica mart za jun prenosi prazan april i koristi realan maj`() {
        val scheduler = ShiftScheduler()
        val settings = ShiftFillSettings(
            mode = ShiftFillMode.THREE_BRIGADE,
            anchorDate = LocalDate.of(2026, 6, 1),
            selectedShift = Shift.THIRD
        )
        repo.basePayValue = 1_000.0
        repo.foodPerDayValue = 0.0
        repo.employeeContributionsPercentValue = 0.0
        repo.taxReliefValue = 0.0
        repo.taxPercentValue = 0.0
        populateCompleteThreeBrigadeMonth(scheduler, settings, 2026, 5)
        repo.setKnownPayBaseAnchor(2026, 3, 918.81)
        repo.setDefaultPayBaseSelection(PayBaseSelectionMode.KNOWN_BASE)

        val calculation = resolvedKnownCalculation(2026, 6)

        assertEquals(
            listOf(MonthKey(2026, 3), MonthKey(2026, 4), MonthKey(2026, 5)),
            calculation.months.map { MonthKey(it.year, it.month) }
        )
        assertEquals(listOf("KNOWN_BASE", "CARRY_FORWARD", "REAL"), calculation.months.map { it.source.name })
        assertEquals(918.81, calculation.months[1].grossForAverage / calculation.months[1].hoursForAverage, 0.01)
        assertTrue(calculation.hourlyBase > 918.81)
        assertTrue(calculation.months.none { it.source == PayBaseMonthSource.SIMULATED })
        assertNull(repo.get(2026, 4, 1))
    }

    @Test
    fun `poznata osnovica mart za jun daje maj samo kao jedan od dvanaest meseci`() {
        val scheduler = ShiftScheduler()
        val settings = ShiftFillSettings(
            mode = ShiftFillMode.THREE_BRIGADE,
            anchorDate = LocalDate.of(2026, 6, 1),
            selectedShift = Shift.THIRD
        )
        repo.basePayValue = 1_000.0
        repo.foodPerDayValue = 0.0
        repo.employeeContributionsPercentValue = 0.0
        repo.taxReliefValue = 0.0
        repo.taxPercentValue = 0.0
        populateCompleteThreeBrigadeMonth(scheduler, settings, 2026, 5)
        repo.setKnownPayBaseAnchor(2026, 3, 918.81)
        repo.setDefaultPayBaseSelection(PayBaseSelectionMode.KNOWN_BASE)

        val calculation = resolvedKnownCalculation(2026, 6)

        val anchorHistoryHours = previousTwelveMonthKeys(2026, 6)
            .filter { it <= MonthKey(2026, 3) }
            .sumOf { calculateMonthlyFundHours(it.year, it.month) }
        val aprilCarryForward = calculation.months.single { it.source == PayBaseMonthSource.CARRY_FORWARD }
        val mayReal = calculation.months.single { MonthKey(it.year, it.month) == MonthKey(2026, 5) }
        val expectedHourlyBase = (
            918.81 * anchorHistoryHours.toDouble() +
                aprilCarryForward.grossForAverage +
                mayReal.grossForAverage
            ) / (anchorHistoryHours + aprilCarryForward.hoursForAverage + mayReal.hoursForAverage).toDouble()

        assertEquals(anchorHistoryHours, calculation.months.first().hoursForAverage)
        assertEquals(expectedHourlyBase, calculation.hourlyBase, 0.01)
    }

    @Test
    fun `poznata osnovica mart za jun prikazuje obracun iz marta aprila i maja`() {
        val scheduler = ShiftScheduler()
        val settings = ShiftFillSettings(
            mode = ShiftFillMode.THREE_BRIGADE,
            anchorDate = LocalDate.of(2026, 6, 1),
            selectedShift = Shift.THIRD
        )
        repo.basePayValue = 1_000.0
        repo.foodPerDayValue = 0.0
        repo.employeeContributionsPercentValue = 0.0
        repo.taxReliefValue = 0.0
        repo.taxPercentValue = 0.0
        for (key in listOf(MonthKey(2026, 4), MonthKey(2026, 5), MonthKey(2026, 6))) {
            populateCompleteThreeBrigadeMonth(scheduler, settings, key.year, key.month)
        }
        repo.setKnownPayBaseAnchor(2026, 3, 918.81)
        repo.setDefaultPayBaseSelection(PayBaseSelectionMode.KNOWN_BASE)

        val calculation = resolvedKnownCalculation(2026, 6)

        assertEquals(
            listOf(MonthKey(2026, 3), MonthKey(2026, 4), MonthKey(2026, 5)),
            calculation.months.map { MonthKey(it.year, it.month) }
        )
        assertEquals(
            listOf(PayBaseMonthSource.KNOWN_BASE, PayBaseMonthSource.REAL, PayBaseMonthSource.REAL),
            calculation.months.map { it.source }
        )
        assertTrue(repo.getMonthRecords(2026, 6).isNotEmpty())
    }

    @Test
    fun `poznata osnovica vazi za mesec sidra`() {
        repo.setKnownPayBaseAnchor(2026, 3, 923.10)
        repo.setDefaultPayBaseSelection(PayBaseSelectionMode.KNOWN_BASE)

        val resolved = repo.getResolvedPayBases(2026, 3)

        assertEquals(PayBaseSource.KNOWN_BASE, resolved.vacationHourlyBase.source)
        assertEquals(923.10, resolved.vacationHourlyBase.value, 0.01)
    }

    @Test
    fun `promena poznate osnovice odmah menja naredni obracun`() {
        prepareKnownBaseMayScenario()
        val before = repo.getResolvedPayBases(2026, 5).vacationHourlyBase.value

        repo.setKnownPayBaseAnchor(2026, 1, 1_050.0)

        val after = repo.getResolvedPayBases(2026, 5).vacationHourlyBase.value
        assertTrue(after > before)
    }

    @Test
    fun `promena meseca poznate osnovice ponovo formira istoriju od novog meseca`() {
        prepareKnownBaseMayScenario(realMonths = setOf(MonthKey(2026, 2), MonthKey(2026, 4)))

        repo.setKnownPayBaseAnchor(2026, 2, 950.0)

        val calculation = resolvedKnownCalculation(2026, 5)
        assertEquals(
            listOf(MonthKey(2026, 2), MonthKey(2026, 3), MonthKey(2026, 4)),
            calculation.months.map { MonthKey(it.year, it.month) }
        )
        assertEquals(PayBaseMonthSource.KNOWN_BASE, calculation.months.first().source)
        assertEquals("CARRY_FORWARD", calculation.months[1].source.name)
        assertEquals(PayBaseMonthSource.REAL, calculation.months[2].source)
        assertEquals(950.0, calculation.months.first().grossForAverage / calculation.months.first().hoursForAverage, 0.01)
    }

    @Test
    fun `poznata osnovica se cuva kroz export import`() {
        prepareKnownBaseMayScenario()

        val exported = JSONObject(repo.exportJson())
        val restored = testRepository()
        val imported = restored.importFromJson(exported.toString())

        assertEquals(PayBaseSelectionMode.KNOWN_BASE.name, exported.getString("default_pay_base_selection_mode"))
        assertEquals("2026-01", exported.getString("known_pay_base_month"))
        assertEquals(917.81, exported.getDouble("known_pay_base_gross_hourly"), 0.01)
        assertTrue(imported)
        assertEquals(PayBaseSelectionMode.KNOWN_BASE, restored.defaultPayBaseSelectionModeValue)
        assertEquals(KnownPayBaseAnchor(MonthKey(2026, 1), 917.81), restored.getKnownPayBaseAnchor())
    }

    @Test
    fun `sidro iz aprilskog platnog listica ostaje aprilski obracunski mesec`() {
        val aprilAnchorRate = calculateManualAnchorGrossHourlyBase(15_250.93, 16)!!

        repo.setKnownPayBaseAnchor(2026, 4, aprilAnchorRate)

        assertEquals(
            KnownPayBaseAnchor(MonthKey(2026, 4), 953.183125),
            repo.getKnownPayBaseAnchor()
        )
        assertTrue(JSONObject(repo.exportJson()).getString("known_pay_base_month") == "2026-04")
    }

    @Test
    fun `mesecne osnovice naknada se cuvaju kroz export import`() {
        repo.setMonthlyPayBases(
            year = 2026,
            month = 1,
            bases = MonthlyPayBases(
                year = 2026,
                month = 1,
                vacationHourlyBase = 912.87,
                paidHolidayHourlyBase = 912.87,
                sickHourlyBase = 918.81
            )
        )

        val repo2 = testRepository()
        val success = repo2.importFromJson(repo.exportJson())

        assertTrue(success)
        val saved = repo2.getMonthlyPayBases(2026, 1)
        assertNotNull(saved)
        assertEquals(912.87, saved!!.vacationHourlyBase!!, 0.01)
        assertEquals(912.87, saved.paidHolidayHourlyBase!!, 0.01)
        assertEquals(918.81, saved.sickHourlyBase!!, 0.01)
    }

    @Test
    fun `rolling pay base calculation uses previous twelve complete entered months without mutating records`() {
        repo.basePayValue = 1_000.0
        populateCompletePreviousTwelveMonths(2026, 5)
        val beforeJson = repo.exportJson()

        val calculation = repo.getRollingPayBaseCalculation(2026, 5)

        assertNotNull(calculation)
        assertEquals(12, calculation!!.months.size)
        assertTrue(calculation.hourlyBase > 0.0)
        assertEquals(beforeJson, repo.exportJson())
    }

    @Test
    fun `rolling pay base calculation accepts missing official holidays in entered months`() {
        repo.basePayValue = 1_000.0
        for (key in previousTwelveMonthKeys(2026, 5)) {
            populateNonHolidayWorkdays(key.year, key.month)
        }
        val beforeJson = repo.exportJson()

        val calculation = repo.getRollingPayBaseCalculation(2026, 5)

        assertNotNull(calculation)
        assertEquals(12, calculation!!.months.size)
        assertTrue(calculation.hoursTotal > 0)
        assertEquals(beforeJson, repo.exportJson())
        assertNull(repo.get(2026, 1, 1))
    }

    @Test
    fun `rolling pay base month includes compensation hours meal and regress by default`() {
        repo.basePayValue = 1_000.0
        // Obrok iz upisanih dana (ručni unos potrošnje je ukinut).
        repo.foodPerDayValue = 500.0
        repo.employeeContributionsPercentValue = 0.0
        repo.taxReliefValue = 0.0
        repo.taxPercentValue = 0.0
        populateCompletePreviousTwelveMonths(2027, 5)
        repo.set(2026, 5, 1, Shift.OFF, 0, holidayWorkOverride = false, holidayOverride = true)
        repo.set(2026, 5, 4, Shift.VACATION, 8, holidayOverride = false)
        repo.set(2026, 5, 5, Shift.SICK, 8, sickPayRate = 0.65, holidayOverride = false)
        repo.setMonthlyGrossAdditions(
            year = 2026,
            month = 5,
            additions = MonthlyGrossAdditions(
                year = 2026,
                month = 5,
                regressGross = 20_000.0,
                bonusGross = 30_000.0
            )
        )

        val calculation = repo.getRollingPayBaseCalculation(2027, 5)
        val may = calculation!!.months.first { it.year == 2026 && it.month == 5 }
        val details = repo.getMonthlyPayDetails(2026, 5)
        val expectedGross = details.grossBaseRegular +
            details.grossNightPremium +
            details.grossOvertimePremium +
            details.grossSundayPremium +
            details.grossHolidayPremium +
            details.grossDifficultWorkConditions +
            details.grossSeniorityBonus +
            details.grossBonusAddition

        assertTrue(details.grossPaidHoliday > 0.0)
        assertTrue(details.grossVacationPay > 0.0)
        assertTrue(details.grossSickPay > 0.0)
        assertTrue(details.grossMealAddition > 0.0)
        assertTrue(details.grossRegressAddition > 0.0)
        // Prosek prati listić: i naknade i obrok i regres ulaze u bruto, a
        // časovi naknada u imenilac (05/2026: 950,58 din/h).
        val expectedCompensationHours = details.sickHours +
            details.paidLeaveHours +
            details.vacationHours +
            details.paidHolidayHours +
            details.coveredDeficitHours
        assertEquals(details.paidWorkHours + expectedCompensationHours, may.hoursForAverage)
        assertTrue(may.includedCompensationGross > 0.0)
        assertEquals(details.grossMealAddition, may.includedMealAllowanceGross, 0.01)
        assertEquals(20_000.0, may.includedRegressGross, 0.01)
        assertEquals(
            expectedGross +
                may.includedCompensationGross +
                may.includedMealAllowanceGross +
                may.includedRegressGross,
            may.grossForAverage,
            0.01
        )
        assertEquals(0.0, may.excludedCompensationGross, 0.01)
        assertEquals(0, may.excludedCompensationHours)
    }

    @Test
    fun `rolling pay base counts worked holiday night and overtime hours once while including premiums`() {
        repo.basePayValue = 1_000.0
        repo.foodPerDayValue = 0.0
        repo.employeeContributionsPercentValue = 0.0
        repo.taxReliefValue = 0.0
        repo.taxPercentValue = 0.0
        populateCompletePreviousTwelveMonths(2027, 5)
        repo.set(2026, 6, 1, Shift.THIRD, 8, holidayOverride = false)
        repo.set(
            year = 2026,
            month = 6,
            day = 2,
            shift = Shift.FIRST,
            hours = 8,
            shift2 = Shift.SECOND,
            hours2 = 2,
            overtimeType = OvertimeType.PAID,
            holidayOverride = false
        )
        repo.set(2026, 6, 3, Shift.FIRST, 8, holidayWorkOverride = true, holidayOverride = true)

        val calculation = repo.getRollingPayBaseCalculation(2027, 5)
        val june = calculation!!.months.first { it.year == 2026 && it.month == 6 }
        val details = repo.getMonthlyPayDetails(2026, 6)

        assertTrue(details.nightHours > 0)
        assertTrue(details.overtimeHours > 0)
        assertTrue(details.grossHolidayPremium > 0.0)
        assertEquals(details.paidWorkHours, june.hoursForAverage)
        assertTrue(june.includedNightPremiumGross > 0.0)
        assertTrue(june.includedOvertimeGross > 0.0)
        assertTrue(june.includedHolidayWorkedGross > 0.0)
        assertEquals(0, june.excludedCompensationHours)
    }

    @Test
    fun `rolling pay base includes minuli rad when service start is configured`() {
        repo.basePayValue = 1_000.0
        repo.foodPerDayValue = 0.0
        repo.employeeContributionsPercentValue = 0.0
        repo.taxReliefValue = 0.0
        repo.taxPercentValue = 0.0
        repo.minuliRadStartYearValue = 2020
        repo.minuliRadStartMonthValue = 1
        populateCompletePreviousTwelveMonths(2027, 5)

        val calculation = repo.getRollingPayBaseCalculation(2027, 5)
        val may = calculation!!.months.first { it.year == 2026 && it.month == 5 }
        val details = repo.getMonthlyPayDetails(2026, 5)

        assertTrue(details.grossSeniorityBonus > 0.0)
        assertEquals(details.grossSeniorityBonus, may.includedSeniorityGross, 0.01)
        assertTrue(may.grossForAverage > details.grossBaseRegular)
    }

    @Test
    fun `meal allowance and regress are explicit pay base average options`() {
        repo.basePayValue = 1_000.0
        repo.foodPerDayValue = 280.0
        repo.employeeContributionsPercentValue = 0.0
        repo.taxReliefValue = 0.0
        repo.taxPercentValue = 0.0
        populateCompletePreviousTwelveMonths(2027, 5)
        repo.setMonthlyGrossAdditions(
            year = 2026,
            month = 5,
            additions = MonthlyGrossAdditions(
                year = 2026,
                month = 5,
                mealSpentNet = 10_000.0,
                regressGross = 20_000.0
            )
        )
        val details = repo.getMonthlyPayDetails(2026, 5)

        val defaultMonth = calculatePayBaseMonthForAverage(2026, 5, details)
        val excludedMonth = calculatePayBaseMonthForAverage(
            year = 2026,
            month = 5,
            details = details,
            options = PayBaseAverageOptions(
                includeMealAllowanceInAverage = false,
                includeRegressInAverage = false
            )
        )

        assertTrue(details.grossMealAddition > 0.0)
        assertEquals(details.grossMealAddition, defaultMonth.includedMealAllowanceGross, 0.01)
        assertEquals(20_000.0, defaultMonth.includedRegressGross, 0.01)
        assertEquals(0.0, excludedMonth.includedMealAllowanceGross, 0.01)
        assertEquals(0.0, excludedMonth.includedRegressGross, 0.01)
        assertEquals(
            excludedMonth.grossForAverage + details.grossMealAddition + 20_000.0,
            defaultMonth.grossForAverage,
            0.01
        )
    }

    @Test
    fun `rolling pay base calculation simulates twelve empty previous months from entered trobrigada pattern`() {
        repo.basePayValue = 1_000.0
        ShiftScheduler().populateMonthsAhead(
            repo = repo,
            referenceDate = LocalDate.of(2026, 5, 9),
            referenceShift = Shift.THIRD,
            monthsAhead = 12
        )

        val calculation = repo.getRollingPayBaseCalculation(2026, 5)

        assertNull(repo.get(2026, 5, 9))
        assertNotNull(calculation)
        assertEquals(12, calculation!!.months.size)
        assertNull(repo.get(2025, 5, 5))
    }

    @Test
    fun `rolling pay base calculation simulates only empty previous months when some months are entered`() {
        val scheduler = ShiftScheduler()
        val referenceDate = LocalDate.of(2026, 5, 4)
        val settings = ShiftFillSettings(
            mode = ShiftFillMode.THREE_BRIGADE,
            anchorDate = referenceDate,
            selectedShift = Shift.THIRD
        )
        repo.basePayValue = 1_000.0
        scheduler.populateMonthsAhead(
            repo = repo,
            referenceDate = referenceDate,
            settings = settings,
            monthsAhead = 1
        )
        for (key in previousTwelveMonthKeys(2026, 5).takeLast(8)) {
            populateCompleteThreeBrigadeMonth(scheduler, settings, key.year, key.month)
        }
        val beforeJson = repo.exportJson()

        val calculation = repo.getRollingPayBaseCalculation(2026, 5)

        assertNotNull(calculation)
        assertEquals(12, calculation!!.months.size)
        assertEquals(previousTwelveMonthKeys(2026, 5), calculation.months.map { MonthKey(it.year, it.month) })
        assertEquals(beforeJson, repo.exportJson())
        assertNull(repo.get(2025, 5, 5))
    }

    @Test
    fun `rolling pay base calculation simulates twelve empty previous months from first second pattern`() {
        val scheduler = ShiftScheduler()
        val referenceDate = LocalDate.of(2026, 5, 4)
        val settings = ShiftFillSettings(
            mode = ShiftFillMode.FIRST_SECOND,
            anchorDate = referenceDate,
            selectedShift = Shift.FIRST
        )
        repo.basePayValue = 1_000.0
        scheduler.populateMonthsAhead(
            repo = repo,
            referenceDate = referenceDate,
            settings = settings,
            monthsAhead = 1
        )
        val beforeJson = repo.exportJson()

        val calculation = repo.getRollingPayBaseCalculation(2026, 5)

        assertNotNull(calculation)
        assertEquals(previousTwelveMonthKeys(2026, 5), calculation!!.months.map { MonthKey(it.year, it.month) })
        assertEquals(beforeJson, repo.exportJson())
        assertNull(repo.get(2025, 5, 5))
    }

    @Test
    fun `rolling pay base calculation simulates twelve empty previous months from one shift pattern`() {
        val scheduler = ShiftScheduler()
        val referenceDate = LocalDate.of(2026, 5, 4)
        val settings = ShiftFillSettings(
            mode = ShiftFillMode.ONE_SHIFT,
            anchorDate = referenceDate,
            selectedShift = Shift.FIRST
        )
        repo.basePayValue = 1_000.0
        scheduler.populateMonthsAhead(
            repo = repo,
            referenceDate = referenceDate,
            settings = settings,
            monthsAhead = 1
        )
        val beforeJson = repo.exportJson()

        val calculation = repo.getRollingPayBaseCalculation(2026, 5)

        assertNotNull(calculation)
        assertEquals(previousTwelveMonthKeys(2026, 5), calculation!!.months.map { MonthKey(it.year, it.month) })
        assertEquals(beforeJson, repo.exportJson())
        assertNull(repo.get(2025, 5, 5))
    }

    @Test
    fun `rolling pay base calculation simulates previous months like current month auto fill for every shift mode`() {
        val scheduler = ShiftScheduler()
        val scenarios = listOf(
            ShiftFillSettings(
                mode = ShiftFillMode.ONE_SHIFT,
                anchorDate = LocalDate.of(2026, 5, 4),
                selectedShift = Shift.FIRST
            ),
            ShiftFillSettings(
                mode = ShiftFillMode.FIRST_SECOND,
                anchorDate = LocalDate.of(2026, 5, 4),
                selectedShift = Shift.FIRST
            ),
            ShiftFillSettings(
                mode = ShiftFillMode.THREE_BRIGADE,
                anchorDate = LocalDate.of(2026, 5, 4),
                selectedShift = Shift.THIRD
            ),
            ShiftFillSettings(
                mode = ShiftFillMode.FOUR_BRIGADE,
                anchorDate = LocalDate.of(2026, 5, 10),
                selectedShift = Shift.FIRST,
                cycleDayIndex = 0
            )
        )

        for (settings in scenarios) {
            repo.deleteAllData()
            repo.basePayValue = 1_000.0
            scheduler.populateMonthsAhead(
                repo = repo,
                referenceDate = settings.anchorDate,
                settings = settings,
                monthsAhead = 0
            )
            val beforeJson = repo.exportJson()

            val calculation = repo.getRollingPayBaseCalculation(2026, 5)

            assertNotNull("Nije izračunata osnovica za ${settings.mode}", calculation)
            assertEquals(previousTwelveMonthKeys(2026, 5), calculation!!.months.map { MonthKey(it.year, it.month) })
            assertEquals(beforeJson, repo.exportJson())
            assertNull(repo.get(2025, 5, 5))
        }
    }

    @Test
    fun `fresh install auto popuna dvanaest i dvadeset cetiri meseca ne vraca null osnovicu`() {
        val scheduler = ShiftScheduler()
        val today = LocalDate.of(2026, 5, 13)
        val referenceDate = scheduler.getAutoFillReferenceDate(today)
        val scenarios = listOf(
            ShiftFillSettings(
                mode = ShiftFillMode.ONE_SHIFT,
                anchorDate = referenceDate,
                selectedShift = Shift.FIRST
            )
        ) + listOf(Shift.FIRST, Shift.SECOND).map { selectedShift ->
            ShiftFillSettings(
                mode = ShiftFillMode.FIRST_SECOND,
                anchorDate = referenceDate,
                selectedShift = selectedShift
            )
        } + listOf(Shift.THIRD, Shift.SECOND, Shift.FIRST).map { selectedShift ->
            ShiftFillSettings(
                mode = ShiftFillMode.THREE_BRIGADE,
                anchorDate = referenceDate,
                selectedShift = selectedShift
            )
        } + listOf(Shift.FIRST, Shift.SECOND, Shift.THIRD, Shift.OFF).flatMap { selectedShift ->
            (0..1).map { blockDayIndex ->
                ShiftFillSettings(
                    mode = ShiftFillMode.FOUR_BRIGADE,
                    anchorDate = today,
                    selectedShift = selectedShift,
                    cycleDayIndex = scheduler.getFourBrigadeCycleStartIndex(selectedShift, blockDayIndex)
                )
            }
        }

        fun scenarioLabel(settings: ShiftFillSettings): String {
            return "${settings.mode}/${settings.selectedShift}/${settings.cycleDayIndex ?: "-"}"
        }

        for (monthsAhead in listOf(12, 24)) {
            for (settings in scenarios) {
                val freshRepo = testRepository()
                freshRepo.basePayValue = 1_000.0
                scheduler.populateMonthsAhead(
                    repo = freshRepo,
                    referenceDate = today,
                    settings = settings,
                    monthsAhead = monthsAhead
                )
                val startMonth = today.withDayOfMonth(1)

                for (offset in 0..monthsAhead + 1) {
                    val target = startMonth.plusMonths(offset.toLong())
                    val calculation = freshRepo.getRollingPayBaseCalculation(target.year, target.monthValue)

                    assertNotNull(
                        "Osnovica je null za ${scenarioLabel(settings)}, popuna $monthsAhead meseci, cilj $target",
                        calculation
                    )
                    assertEquals(
                        previousTwelveMonthKeys(target.year, target.monthValue),
                        calculation!!.months.map { MonthKey(it.year, it.month) }
                    )
                    assertTrue(
                        "Osnovica mora imati pozitivnu satnicu za ${scenarioLabel(settings)}, cilj $target",
                        calculation.hourlyBase > 0.0
                    )
                }
            }
        }
    }

    @Test
    fun `rolling pay base calculation simulates twelve empty previous months from entered cetvorobrigada pattern`() {
        val scheduler = ShiftScheduler()
        val referenceDate = LocalDate.of(2026, 5, 10)
        repo.basePayValue = 1_000.0
        scheduler.populateMonthsAhead(
            repo = repo,
            referenceDate = referenceDate,
            settings = ShiftFillSettings(
                mode = ShiftFillMode.FOUR_BRIGADE,
                anchorDate = referenceDate,
                selectedShift = Shift.FIRST,
                cycleDayIndex = 0
            ),
            monthsAhead = 1
        )
        val beforeJson = repo.exportJson()

        val calculation = repo.getRollingPayBaseCalculation(2026, 5)

        assertNotNull(calculation)
        assertEquals(12, calculation!!.months.size)
        assertEquals(previousTwelveMonthKeys(2026, 5), calculation.months.map { MonthKey(it.year, it.month) })
        assertEquals(beforeJson, repo.exportJson())
        assertNull(repo.get(2025, 5, 1))
    }

    @Test
    fun `cetvorobrigada rolling pay base accepts missing official holiday when cycle expects work shift`() {
        val scheduler = ShiftScheduler()
        val referenceDate = LocalDate.of(2026, 5, 10)
        val settings = ShiftFillSettings(
            mode = ShiftFillMode.FOUR_BRIGADE,
            anchorDate = referenceDate,
            selectedShift = Shift.FIRST,
            cycleDayIndex = 0
        )
        repo.basePayValue = 1_000.0
        repo.brigadeTypeValue = BrigadeType.CETVOROBRIGADA
        for (key in previousTwelveMonthKeys(2026, 5)) {
            val lengthOfMonth = YearMonth.of(key.year, key.month).lengthOfMonth()
            for (day in 1..lengthOfMonth) {
                val date = LocalDate.of(key.year, key.month, day)
                val shift = scheduler.calculateShiftForDate(date, settings)
                if (
                    shift in Shift.workShifts() &&
                    !(isSerbianHoliday(key.year, key.month, day) && Weekday.from(date).isWorkday)
                ) {
                    repo.set(key.year, key.month, day, shift, 8)
                }
            }
        }
        val beforeJson = repo.exportJson()

        val calculation = repo.getRollingPayBaseCalculation(2026, 5)

        assertNotNull(calculation)
        assertEquals(12, calculation!!.months.size)
        assertEquals(beforeJson, repo.exportJson())
        assertNull(repo.get(2025, 5, 1))
    }

    @Test
    fun `rolling pay base calculation simulates empty previous month when pattern is known`() {
        repo.basePayValue = 1_000.0
        populateCompletePreviousTwelveMonths(2026, 5, excludedMonths = setOf(MonthKey(2026, 1)))

        val calculation = repo.getRollingPayBaseCalculation(2026, 5)

        assertNotNull(calculation)
        assertEquals(12, calculation!!.months.size)
    }

    @Test
    fun `rolling pay base calculation returns null when previous month is incomplete`() {
        repo.basePayValue = 1_000.0
        populateCompletePreviousTwelveMonths(2026, 5, skippedDays = setOf(DayKey(2025, 12, 31)))

        val calculation = repo.getRollingPayBaseCalculation(2026, 5)

        assertNull(calculation)
    }

    @Test
    fun `default pay base selection persists and ignores last known bases`() {
        repo.sickPayBaseHourlyValue = 950.75
        repo.setMonthlyPayBases(
            year = 2026,
            month = 3,
            bases = MonthlyPayBases(
                year = 2026,
                month = 3,
                vacationHourlyBase = 1_111.0,
                paidHolidayHourlyBase = 1_222.0,
                sickHourlyBase = 1_333.0,
                selectionMode = PayBaseSelectionMode.MANUAL
            )
        )
        repo.setMonthlyPayBases(
            year = 2026,
            month = 5,
            bases = MonthlyPayBases(
                year = 2026,
                month = 5,
                selectionMode = PayBaseSelectionMode.DEFAULT_VALUES
            )
        )

        val resolved = repo.getResolvedPayBases(2026, 5)
        val repo2 = testRepository()
        val imported = repo2.importFromJson(repo.exportJson())

        assertEquals(918.81, resolved.vacationHourlyBase.value, 0.01)
        assertEquals(PayBaseSource.DEFAULT, resolved.vacationHourlyBase.source)
        assertEquals(918.81, resolved.paidHolidayHourlyBase.value, 0.01)
        assertEquals(PayBaseSource.DEFAULT, resolved.paidHolidayHourlyBase.source)
        // Ručno podešena osnovica (950,75) važi samo u ručnom režimu; ovde i bolovanje ide na podrazumevanu.
        assertEquals(918.81, resolved.sickHourlyBase.value, 0.01)
        assertEquals(PayBaseSource.DEFAULT, resolved.sickHourlyBase.source)
        assertTrue(imported)
        assertEquals(PayBaseSelectionMode.DEFAULT_VALUES, repo2.getMonthlyPayBases(2026, 5)!!.selectionMode)
    }

    @Test
    fun `calculated pay base selection resolves from previous twelve months`() {
        repo.basePayValue = 1_000.0
        populateCompletePreviousTwelveMonths(2026, 5)
        repo.setMonthlyPayBases(
            year = 2026,
            month = 5,
            bases = MonthlyPayBases(
                year = 2026,
                month = 5,
                selectionMode = PayBaseSelectionMode.CALCULATED_PREVIOUS_12_MONTHS
            )
        )

        val resolved = repo.getResolvedPayBases(2026, 5)

        assertEquals(PayBaseSource.CALCULATED_PREVIOUS_12_MONTHS, resolved.vacationHourlyBase.source)
        assertEquals(resolved.vacationHourlyBase.value, resolved.paidHolidayHourlyBase.value, 0.01)
        assertEquals(resolved.vacationHourlyBase.value, resolved.sickHourlyBase.value, 0.0)
        assertEquals(PayBaseSource.CALCULATED_PREVIOUS_12_MONTHS, resolved.sickHourlyBase.source)
        assertNotNull(resolved.vacationHourlyBase.calculation)
        assertEquals(12, resolved.vacationHourlyBase.calculation!!.months.size)
    }

    @Test
    fun `pay bases default to previous twelve months without saved selection`() {
        repo.basePayValue = 1_000.0
        populateCompletePreviousTwelveMonths(2026, 5)

        val resolved = repo.getResolvedPayBases(2026, 5)

        assertNull(repo.getMonthlyPayBases(2026, 5))
        assertEquals(PayBaseSource.CALCULATED_PREVIOUS_12_MONTHS, resolved.vacationHourlyBase.source)
        assertEquals(resolved.vacationHourlyBase.value, resolved.paidHolidayHourlyBase.value, 0.01)
        assertEquals(resolved.vacationHourlyBase.value, resolved.sickHourlyBase.value, 0.0)
        assertEquals(PayBaseSource.CALCULATED_PREVIOUS_12_MONTHS, resolved.sickHourlyBase.source)
        assertNotNull(resolved.vacationHourlyBase.calculation)
    }

    @Test
    fun `cached calculated summary changes after previous twelve month record edit`() {
        repo.basePayValue = 1_000.0
        populateCompletePreviousTwelveMonths(2026, 4)
        repo.set(2026, 5, 11, Shift.VACATION, 8, holidayOverride = false)
        repo.setMonthlyPayBases(
            year = 2026,
            month = 5,
            bases = MonthlyPayBases(
                year = 2026,
                month = 5,
                selectionMode = PayBaseSelectionMode.CALCULATED_PREVIOUS_12_MONTHS
            )
        )
        val before = repo.getSummary(2026, 5).totalPay

        repo.set(2026, 3, 10, Shift.THIRD, 8, holidayOverride = false)

        val after = repo.getSummary(2026, 5).totalPay
        assertTrue(after > before)
    }

    @Test
    fun `cached calculated summary changes after previous twelve month gross addition edit`() {
        repo.basePayValue = 1_000.0
        populateCompletePreviousTwelveMonths(2026, 4)
        repo.set(2026, 5, 11, Shift.VACATION, 8, holidayOverride = false)
        repo.setMonthlyPayBases(
            year = 2026,
            month = 5,
            bases = MonthlyPayBases(
                year = 2026,
                month = 5,
                selectionMode = PayBaseSelectionMode.CALCULATED_PREVIOUS_12_MONTHS
            )
        )
        val before = repo.getSummary(2026, 5).totalPay

        repo.setMonthlyGrossAdditions(
            year = 2026,
            month = 3,
            additions = MonthlyGrossAdditions(year = 2026, month = 3, bonusGross = 12_000.0)
        )

        val after = repo.getSummary(2026, 5).totalPay
        assertTrue(after > before)
    }

    @Test
    fun `cached calculated summary ignores previous twelve month net addition for gross pay base`() {
        repo.basePayValue = 1_000.0
        populateCompletePreviousTwelveMonths(2026, 5)
        repo.set(2026, 5, 11, Shift.VACATION, 8, holidayOverride = false)
        repo.setMonthlyPayBases(
            year = 2026,
            month = 5,
            bases = MonthlyPayBases(
                year = 2026,
                month = 5,
                selectionMode = PayBaseSelectionMode.CALCULATED_PREVIOUS_12_MONTHS
            )
        )
        val before = repo.getSummary(2026, 5).totalPay

        // Neto dodatak ne sme da uđe u bruto osnovicu proseka.
        repo.setMonthlyGrossAdditions(
            year = 2026,
            month = 4,
            additions = MonthlyGrossAdditions(year = 2026, month = 4, bonusNet = 1_000.0)
        )

        val after = repo.getSummary(2026, 5).totalPay
        assertEquals(before, after, 0.01)
    }

    @Test
    fun `promena poreskih parametara invalidira neto obracun meseca`() {
        repo.set(2026, 2, 2, Shift.FIRST, 8)
        repo.taxReliefValue = 0.0
        val before = repo.getNetPayBreakdown(2026, 2).displayPay

        repo.taxPercentValue = repo.taxPercentValue + 5.0

        val after = repo.getNetPayBreakdown(2026, 2).displayPay
        assertTrue(after < before)
    }

    @Test
    fun `cached calculated summary changes after pre-window record edit affects comp balance`() {
        repo.basePayValue = 1_000.0
        populateCompletePreviousTwelveMonths(2026, 5)
        repo.set(2025, 5, 12, Shift.FIRST, 0, holidayOverride = false)
        repo.set(2026, 5, 11, Shift.VACATION, 8, holidayOverride = false)
        repo.setMonthlyPayBases(
            year = 2026,
            month = 5,
            bases = MonthlyPayBases(
                year = 2026,
                month = 5,
                selectionMode = PayBaseSelectionMode.CALCULATED_PREVIOUS_12_MONTHS
            )
        )
        val before = repo.getSummary(2026, 5).totalPay

        repo.set(
            2025, 4, 5, Shift.FIRST, 8,
            weekendOvertimeType = OvertimeType.COMP_TIME,
            holidayOverride = false
        )

        val after = repo.getSummary(2026, 5).totalPay
        assertTrue(after < before)
    }

    @Test
    fun `import starog backup-a ignorise staru neto satnicu`() {
        val json = """
            {
              "config_base_pay_hourly": 444.46,
              "2026": {
                "05": {
                  "04": {
                    "shift": 1,
                    "hours": 8
                  }
                }
              }
            }
        """.trimIndent()

        val success = repo.importFromJson(json)

        assertTrue(success)
        assertEquals(637.32, repo.grossHourlyRateValue, 0.01)
    }

    @Test
    fun `neto breakdown koristi summary i poreska podesavanja`() {
        repo.grossHourlyRateValue = 637.32
        repo.employeeContributionsPercentValue = 19.9
        repo.taxReliefValue = 34_221.0
        repo.taxPercentValue = 10.0
        repo.set(2026, 6, 4, Shift.FIRST, 8)

        val summary = repo.getSummary(2026, 6)
        val breakdown = repo.getNetPayBreakdown(2026, 6)

        assertEquals(summary.totalWithMinuli + breakdown.grossAdditionsTotal, breakdown.grossTotal, 0.01)
        assertEquals(
            breakdown.netPayBeforeDeductions - breakdown.netDeductionsTotal,
            breakdown.displayPay,
            0.01
        )
    }

    @Test
    fun `godisnji neto prosek bez trece smene ne ukljucuje regres`() {
        repo.grossHourlyRateValue = 1_000.0
        repo.foodPerDayValue = 0.0
        repo.employeeContributionsPercentValue = 20.0
        repo.taxReliefValue = 0.0
        repo.taxPercentValue = 10.0
        for (month in 1..12) {
            populateCompleteMonth(2026, month)
        }
        repo.set(2026, 1, 5, Shift.THIRD, 8, holidayOverride = false)
        val beforeJson = repo.exportJson()

        val averages = repo.getAnnualNetPayAverages(2026)

        assertEquals(12, averages.monthCount)
        assertEquals(averages.averagePay - 80_000.0 / 12.0, averages.averagePayWithoutRegress, 0.01)
        assertEquals(
            averages.averagePayWithoutRegress - 2_296.0 / 12.0,
            averages.averagePayWithoutThirdShift,
            0.01
        )
        assertEquals(beforeJson, repo.exportJson())
    }

    @Test
    fun `dnevna procena neta koristi mesecni neto odnos`() {
        repo.grossHourlyRateValue = 1_000.0
        repo.employeeContributionsPercentValue = 19.9
        repo.taxReliefValue = 0.0
        repo.taxPercentValue = 10.0
        repo.set(2026, 6, 4, Shift.FIRST, 8)

        val record = repo.get(2026, 6, 4)!!
        val grossDailyPay = repo.calculateDailyPay(record)

        assertEquals(8_000.0, grossDailyPay, 0.01)
        assertEquals(5_608.00, repo.calculateEstimatedDailyNetPay(record), 0.01)
    }

    @Test
    fun `dnevna procena neta za preview dan ne mora da bude sacuvana u kalendaru`() {
        repo.grossHourlyRateValue = 1_000.0
        repo.employeeContributionsPercentValue = 20.0
        repo.taxReliefValue = 0.0
        repo.taxPercentValue = 10.0
        var changeNotified = false
        repo.onChange { changeNotified = true }

        val previewRecord = DayRecord(
            year = 2026,
            month = 6,
            day = 4,
            shift = Shift.FIRST,
            hours = 8,
            holidayOverride = false
        )

        assertNull(repo.get(2026, 6, 4))
        assertEquals(8_000.0, repo.calculateEstimatedDailyWorkGrossPay(previewRecord), 0.01)
        assertEquals(5_600.0, repo.calculateEstimatedDailyWorkNetPay(previewRecord), 0.01)
        assertNull(repo.get(2026, 6, 4))
        assertFalse(changeNotified)
    }

    @Test
    fun `dnevna procena neta racuna minute kao dodatni deo sata`() {
        repo.grossHourlyRateValue = 1_000.0
        repo.employeeContributionsPercentValue = 0.0
        repo.taxReliefValue = 0.0
        repo.taxPercentValue = 0.0

        val sixHours = DayRecord(
            year = 2026,
            month = 6,
            day = 4,
            shift = Shift.FIRST,
            hours = 6,
            holidayOverride = false
        )
        val sixHoursThirty = DayRecord(
            year = 2026,
            month = 6,
            day = 4,
            shift = Shift.FIRST,
            hours = 6,
            regularShortageMinutes = 30,
            holidayOverride = false
        )

        assertEquals(6_000.0, repo.calculateEstimatedDailyWorkNetPay(sixHours), 0.01)
        assertEquals(6_500.0, repo.calculateEstimatedDailyWorkNetPay(sixHoursThirty), 0.01)
    }

    @Test
    fun `vikend za sate prikazuje neto efekat pokrivanja negativnog bilansa`() {
        repo.grossHourlyRateValue = 1_000.0
        repo.employeeContributionsPercentValue = 0.0
        repo.taxReliefValue = 0.0
        repo.taxPercentValue = 0.0
        repo.set(2026, 6, 1, Shift.OFF, 0, holidayOverride = false)
        repo.set(2026, 6, 2, Shift.FIRST, 3, holidayOverride = false)

        val record = DayRecord(
            year = 2026,
            month = 6,
            day = 6,
            shift = Shift.FIRST,
            hours = 8,
            weekendOvertimeType = OvertimeType.COMP_TIME,
            holidayOverride = false
        )

        assertEquals(8_000.0, repo.calculateEstimatedDailyWorkNetPay(record), 0.01)

        repo.set(
            2026, 6, 6, Shift.FIRST, 8,
            weekendOvertimeType = OvertimeType.COMP_TIME,
            holidayOverride = false
        )
        val summary = repo.getSummary(2026, 6)
        assertEquals(-5, summary.compTimeEnding)
        assertEquals(0, summary.overtimeHours)
    }

    @Test
    fun `radni dan sa dodatnim satima za sate placa redovno i prikazuje neto efekat dodatka`() {
        repo.grossHourlyRateValue = 1_000.0
        repo.employeeContributionsPercentValue = 0.0
        repo.taxReliefValue = 0.0
        repo.taxPercentValue = 0.0
        repo.set(2026, 6, 1, Shift.OFF, 0, holidayOverride = false)
        repo.set(2026, 6, 2, Shift.FIRST, 3, holidayOverride = false)

        val record = DayRecord(
            year = 2026,
            month = 6,
            day = 3,
            shift = Shift.FIRST,
            hours = 12,
            overtimeType = OvertimeType.COMP_TIME,
            holidayOverride = false
        )

        assertEquals(12_000.0, repo.calculateEstimatedDailyWorkNetPay(record), 0.01)

        repo.set(
            2026, 6, 3, Shift.FIRST, 12,
            overtimeType = OvertimeType.COMP_TIME,
            holidayOverride = false
        )
        val summary = repo.getSummary(2026, 6)
        assertEquals(-9, summary.compTimeEnding)
        assertEquals(0, summary.overtimeHours)
    }

    @Test
    fun `za sate ima neto efekat samo do visine negativnog bilansa`() {
        repo.grossHourlyRateValue = 1_000.0
        repo.employeeContributionsPercentValue = 0.0
        repo.taxReliefValue = 0.0
        repo.taxPercentValue = 0.0
        repo.set(2026, 6, 1, Shift.FIRST, 6, holidayOverride = false)

        val record = DayRecord(
            year = 2026,
            month = 6,
            day = 6,
            shift = Shift.FIRST,
            hours = 4,
            weekendOvertimeType = OvertimeType.COMP_TIME,
            holidayOverride = false
        )

        assertEquals(2_000.0, repo.calculateEstimatedDailyWorkNetPay(record), 0.01)

        repo.set(
            2026, 6, 6, Shift.FIRST, 4,
            weekendOvertimeType = OvertimeType.COMP_TIME,
            holidayOverride = false
        )
        val summary = repo.getSummary(2026, 6)
        assertEquals(2, summary.compTimeEnding)
    }

    @Test
    fun `za sate nema neto efekat kad bilans nije negativan`() {
        repo.grossHourlyRateValue = 1_000.0
        repo.employeeContributionsPercentValue = 0.0
        repo.taxReliefValue = 0.0
        repo.taxPercentValue = 0.0

        val record = DayRecord(
            year = 2026,
            month = 6,
            day = 6,
            shift = Shift.FIRST,
            hours = 4,
            weekendOvertimeType = OvertimeType.COMP_TIME,
            holidayOverride = false
        )

        assertEquals(0.0, repo.calculateEstimatedDailyWorkNetPay(record), 0.01)

        repo.set(
            2026, 6, 6, Shift.FIRST, 4,
            weekendOvertimeType = OvertimeType.COMP_TIME,
            holidayOverride = false
        )
        val summary = repo.getSummary(2026, 6)
        assertEquals(4, summary.compTimeEnding)
    }

    @Test
    fun `preview opsega bolovanja menja mesecni neto kao stvarni upis i cuva postojece zapise`() {
        repo.grossHourlyRateValue = 1_000.0
        repo.foodPerDayValue = 0.0
        repo.employeeContributionsPercentValue = 20.0
        repo.taxReliefValue = 0.0
        repo.taxPercentValue = 10.0
        repo.setMonthlyPayBases(
            2026,
            4,
            MonthlyPayBases.fromUnifiedPayBase(2026, 4, 1_000.0)
        )
        repo.set(2026, 5, 4, Shift.THIRD, 8, holidayOverride = false)
        val beforeJson = repo.exportJson()
        val beforeNet = repo.getNetPayBreakdown(2026, 5).displayPay

        val preview = repo.previewAbsenceRangeNetImpact(
            year = 2026,
            month = 5,
            startDay = 4,
            endDay = 4,
            absenceShift = Shift.SICK,
            sickPayRate = 0.65
        )

        assertEquals(beforeJson, repo.exportJson())
        assertEquals(1, preview.affectedDays)
        assertEquals(8, preview.replacedWorkHours)
        assertEquals(8, preview.replacedNightHours)
        assertEquals(beforeNet, preview.currentNetPay, 0.01)
        assertEquals(-4_256.0, preview.netDifference, 0.01)

        ShiftScheduler(defaultHours = 8).applySickRange(repo, 2026, 5, 4, 4, 0.65)
        assertEquals(repo.getNetPayBreakdown(2026, 5).displayPay, preview.previewNetPay, 0.01)
    }

    @Test
    fun `razlika bolovanja posle upisa jednaka je preview-u pri unosu`() {
        repo.grossHourlyRateValue = 1_000.0
        repo.foodPerDayValue = 0.0
        repo.employeeContributionsPercentValue = 20.0
        repo.taxReliefValue = 0.0
        repo.taxPercentValue = 10.0
        repo.setMonthlyPayBases(
            2026,
            4,
            MonthlyPayBases.fromUnifiedPayBase(2026, 4, 1_000.0)
        )
        for (day in 4..8) repo.set(2026, 5, day, Shift.THIRD, 8, holidayOverride = false)
        assertEquals(0.0, repo.getSickLeaveNetDifference(2026, 5), 0.01)

        val preview = repo.previewAbsenceRangeNetImpact(
            year = 2026,
            month = 5,
            startDay = 6,
            endDay = 6,
            absenceShift = Shift.SICK,
            sickPayRate = 0.65
        )
        ShiftScheduler(defaultHours = 8).applySickRange(repo, 2026, 5, 6, 6, 0.65)

        assertTrue(preview.netDifference < 0.0)
        assertEquals(preview.netDifference, repo.getSickLeaveNetDifference(2026, 5), 0.01)
    }

    @Test
    fun `razlika godisnjeg posle upisa jednaka je preview-u pri unosu`() {
        repo.grossHourlyRateValue = 1_000.0
        repo.foodPerDayValue = 0.0
        repo.employeeContributionsPercentValue = 20.0
        repo.taxReliefValue = 0.0
        repo.taxPercentValue = 10.0
        repo.setMonthlyPayBases(2026, 4, MonthlyPayBases.fromUnifiedPayBase(2026, 4, 1_200.0))
        for (day in 4..8) repo.set(2026, 5, day, Shift.FIRST, 8, holidayOverride = false)

        val preview = repo.previewAbsenceRangeNetImpact(
            year = 2026,
            month = 5,
            startDay = 6,
            endDay = 6,
            absenceShift = Shift.VACATION,
            sickPayRate = null
        )
        ShiftScheduler(defaultHours = 8).applyVacationRange(repo, 2026, 5, 6, 6)

        // Osnovica 1.200 > satnica 1.000: godišnji donosi više nego rad.
        assertTrue(preview.netDifference > 0.0)
        assertEquals(preview.netDifference, repo.getVacationNetDifference(2026, 5), 0.01)
    }

    @Test
    fun `davanje krvi ima svoju razliku prema radu i ne mesa se sa bolovanjem`() {
        repo.grossHourlyRateValue = 1_000.0
        repo.foodPerDayValue = 0.0
        repo.employeeContributionsPercentValue = 20.0
        repo.taxReliefValue = 0.0
        repo.taxPercentValue = 10.0
        repo.setMonthlyPayBases(2026, 4, MonthlyPayBases.fromUnifiedPayBase(2026, 4, 1_200.0))
        for (day in 4..8) repo.set(2026, 5, day, Shift.FIRST, 8, holidayOverride = false)
        // Samo davanje krvi (4. i 5.): bolovanja nema, pa njegova razlika mora biti 0.
        repo.applyBloodDonationLeave(LocalDate.of(2026, 5, 4))

        val paidLeave = repo.getPaidLeaveNetDifference(2026, 5)
        assertTrue("osnovica 1.200 > satnica 1.000: više nego rad", paidLeave > 0.0)
        assertEquals(0.0, repo.getSickLeaveNetDifference(2026, 5), 0.01)

        // Bolovanje 7.: ima svoju (negativnu) razliku, a razlika za krv ostaje ista.
        ShiftScheduler(defaultHours = 8).applySickRange(repo, 2026, 5, 7, 7, 0.65)
        assertTrue(repo.getSickLeaveNetDifference(2026, 5) < 0.0)
        assertEquals(paidLeave, repo.getPaidLeaveNetDifference(2026, 5), 50.0)
    }

    @Test
    fun `preview godisnjeg prikazuje dobitak kada je osnovica veca od zamenjene smene`() {
        repo.grossHourlyRateValue = 1_000.0
        repo.foodPerDayValue = 0.0
        repo.employeeContributionsPercentValue = 20.0
        repo.taxReliefValue = 0.0
        repo.taxPercentValue = 10.0
        repo.setMonthlyPayBases(
            2026,
            4,
            MonthlyPayBases.fromUnifiedPayBase(2026, 4, 1_200.0)
        )
        repo.set(2026, 5, 4, Shift.FIRST, 8, holidayOverride = false)

        val preview = repo.previewAbsenceRangeNetImpact(
            year = 2026,
            month = 5,
            startDay = 4,
            endDay = 4,
            absenceShift = Shift.VACATION,
            sickPayRate = null
        )

        assertEquals(1, preview.affectedDays)
        assertEquals(1_120.0, preview.netDifference, 0.01)
    }

    @Test
    fun `godisnji u mesecu koristi osnovicu prethodnog meseca`() {
        repo.grossHourlyRateValue = 1_000.0
        repo.setMonthlyPayBases(
            2026,
            6,
            MonthlyPayBases.fromUnifiedPayBase(2026, 6, 1_200.0)
        )
        repo.setMonthlyPayBases(
            2026,
            7,
            MonthlyPayBases.fromUnifiedPayBase(2026, 7, 800.0)
        )
        repo.set(2026, 7, 6, Shift.VACATION, 8, holidayOverride = false)

        val details = repo.getMonthlyPayDetails(2026, 7)
        val dailyPay = repo.calculateDailyPay(repo.get(2026, 7, 6)!!)

        assertEquals(1_200.0, details.vacationPayBase.value, 0.01)
        assertEquals(9_600.0, details.grossVacationPay, 0.01)
        assertEquals(9_600.0, dailyPay, 0.01)
    }

    @Test
    fun `preview bolovanja sto posto koristi osnovicu bolovanja za razliku`() {
        repo.grossHourlyRateValue = 1_000.0
        repo.foodPerDayValue = 0.0
        repo.employeeContributionsPercentValue = 20.0
        repo.taxReliefValue = 0.0
        repo.taxPercentValue = 10.0
        repo.setMonthlyPayBases(
            2026,
            4,
            MonthlyPayBases.fromUnifiedPayBase(2026, 4, 900.0)
        )
        repo.set(2026, 5, 4, Shift.FIRST, 8, holidayOverride = false)

        val preview = repo.previewAbsenceRangeNetImpact(
            year = 2026,
            month = 5,
            startDay = 4,
            endDay = 4,
            absenceShift = Shift.SICK,
            sickPayRate = 1.0
        )

        assertEquals(1, preview.affectedDays)
        assertEquals(-560.0, preview.netDifference, 0.01)
    }

    @Test
    fun `dnevna procena neta raspodeljuje i minuli rad`() {
        repo.grossHourlyRateValue = 1_000.0
        repo.employeeContributionsPercentValue = 19.9
        repo.taxReliefValue = 0.0
        repo.taxPercentValue = 10.0
        repo.minuliRadStartYearValue = 2019
        repo.set(2026, 6, 4, Shift.FIRST, 8)

        val record = repo.get(2026, 6, 4)!!

        assertEquals(280.0, repo.getSummary(2026, 6).minuliRadBonus, 0.01)
        assertEquals(8_622.37, repo.calculateEstimatedDailyGrossPay(record), 0.01)
        assertEquals(5_804.28, repo.calculateEstimatedDailyNetPay(record), 0.01)
    }

    @Test
    fun `preview neto breakdown koristi kandidat bruto satnicu bez cuvanja`() {
        repo.grossHourlyRateValue = 637.32
        repo.employeeContributionsPercentValue = 19.9
        repo.taxReliefValue = 34_221.0
        repo.taxPercentValue = 10.0
        repo.set(2026, 3, 4, Shift.FIRST, 8)

        val savedRate = repo.grossHourlyRateValue
        val savedPreview = repo.getPreviewNetPayBreakdown(
            year = 2026,
            month = 3,
            grossHourlyRate = savedRate,
            contributionsPercent = repo.employeeContributionsPercentValue,
            taxRelief = repo.taxReliefValue,
            taxPercent = repo.taxPercentValue
        )
        val candidatePreview = repo.getPreviewNetPayBreakdown(
            year = 2026,
            month = 3,
            grossHourlyRate = 1_000.0,
            contributionsPercent = repo.employeeContributionsPercentValue,
            taxRelief = repo.taxReliefValue,
            taxPercent = repo.taxPercentValue
        )

        assertEquals(savedRate, repo.grossHourlyRateValue, 0.01)
        assertTrue(candidatePreview.grossTotal > savedPreview.grossTotal)
        assertEquals(
            savedPreview.grossBaseBeforeSeniority * (1_000.0 / savedRate),
            candidatePreview.grossBaseBeforeSeniority,
            0.01
        )
    }

    @Test
    fun `satnica coefficient preview razlika koristi neto bez prevoza i ne cuva koeficijent`() {
        repo.payCoefficientValue = 2.82
        repo.employeeContributionsPercentValue = 19.9
        repo.taxReliefValue = 34_221.0
        repo.taxPercentValue = 10.0
        repo.set(2026, 5, 4, Shift.FIRST, 8)
        repo.transportDailyAmountValue = 10_000.0

        val highTransportDifference = repo.previewPayCoefficientNetDifference(
            year = 2026,
            month = 5,
            coefficient = 3.5,
            hourlyValue = 226.0
        )
        repo.transportDailyAmountValue = 0.0
        val noTransportDifference = repo.previewPayCoefficientNetDifference(
            year = 2026,
            month = 5,
            coefficient = 3.5,
            hourlyValue = 226.0
        )

        val currentNet = repo.getNetPayBreakdown(2026, 5).displayPay
        val candidateNet = repo.getPreviewNetPayBreakdown(
            year = 2026,
            month = 5,
            grossHourlyRate = 3.5 * 226.0,
            contributionsPercent = repo.employeeContributionsPercentValue,
            taxRelief = repo.taxReliefValue,
            taxPercent = repo.taxPercentValue
        ).displayPay
        assertEquals(2.82, repo.payCoefficientValue, 0.001)
        assertEquals(candidateNet - currentNet, highTransportDifference, 0.01)
        assertEquals(highTransportDifference, noTransportDifference, 0.01)
    }

    @Test
    fun `basePayValue podrazumevana vrednost`() {
        val today = LocalDate.now(TEST_CLOCK)
        assertEquals(
            repo.getGrossHourlyRateForMonth(today.year, today.monthValue),
            repo.basePayValue,
            0.01
        )
    }

    @Test
    fun `basePayValue promena invalidira sumare`() {
        repo.set(2025, 1, 6, Shift.FIRST, 8)
        val summary1 = repo.getSummary(2025, 1)

        repo.basePayValue = 500.0
        val summary2 = repo.getSummary(2025, 1)

        // Plata mora biti različita jer se bazna satnica promenila
        assertTrue(
            "Plata se mora promeniti posle promene base pay",
            summary1.totalPay != summary2.totalPay
        )
    }

    @Test
    fun `basePayValue odbija negativnu vrednost`() {
        val original = repo.basePayValue
        repo.basePayValue = -100.0
        assertEquals(original, repo.basePayValue, 0.01)
    }

    @Test
    fun `basePayValue odbija NaN`() {
        val original = repo.basePayValue
        repo.basePayValue = Double.NaN
        assertEquals(original, repo.basePayValue, 0.01)
    }

    @Test
    fun `basePayValue odbija Infinity`() {
        val original = repo.basePayValue
        repo.basePayValue = Double.POSITIVE_INFINITY
        assertEquals(original, repo.basePayValue, 0.01)
    }

    @Test
    fun `foodPerDayValue prihvata nulu`() {
        repo.foodPerDayValue = 0.0
        assertEquals(0.0, repo.foodPerDayValue, 0.01)
    }

    @Test
    fun `foodPerDayValue odbija negativnu vrednost`() {
        val original = repo.foodPerDayValue
        repo.foodPerDayValue = -50.0
        assertEquals(original, repo.foodPerDayValue, 0.01)
    }

    @Test
    fun `transportDailyAmountValue podrazumevano je 0 i odbija negativnu vrednost`() {
        assertEquals(0.0, repo.transportDailyAmountValue, 0.01)

        repo.transportDailyAmountValue = 240.0
        repo.transportDailyAmountValue = -50.0

        assertEquals(240.0, repo.transportDailyAmountValue, 0.01)
    }

    @Test
    fun `transport ne menja postojeci neto obracun plate`() {
        repo.basePayValue = 1_000.0
        repo.employeeContributionsPercentValue = 20.0
        repo.taxReliefValue = 0.0
        repo.taxPercentValue = 10.0
        repo.transportDailyAmountValue = 0.0
        repo.set(2026, 6, 1, Shift.FIRST, 8, holidayOverride = false)
        val netBeforeTransport = repo.getNetPayBreakdown(2026, 6).displayPay

        repo.transportDailyAmountValue = 240.0

        assertEquals(netBeforeTransport, repo.getNetPayBreakdown(2026, 6).displayPay, 0.01)
    }

    @Test
    fun `calculateBasePayFromNet ignorise podesenu hranu`() {
        repo.foodPerDayValue = 280.0
        repo.set(2025, 3, 3, Shift.FIRST, 8)
        repo.set(2025, 3, 4, Shift.FIRST, 8)

        val result = repo.calculateBasePayFromNet(2025, 3, 16_000.0)

        assertEquals(1_000.0, result!!, 0.01)
    }

    @Test
    fun `calculateBasePayFromNet ignorise stvarni odbitak hrane`() {
        repo.foodPerDayValue = 280.0
        repo.set(2025, 3, 3, Shift.FIRST, 8)
        repo.set(2025, 3, 4, Shift.FIRST, 8)

        val result = repo.calculateBasePayFromNet(
            year = 2025,
            month = 3,
            netPay = 16_000.0,
            actualFoodDeduction = 650.0
        )

        assertEquals(1_000.0, result!!, 0.01)
    }

    @Test
    fun `calculateBasePayFromNet ne zavisi od budzeta hrane`() {
        repo.foodPerDayValue = 280.0
        repo.set(2025, 3, 3, Shift.FIRST, 8)
        repo.set(2025, 3, 4, Shift.FIRST, 8)

        val result = repo.calculateBasePayFromNet(
            year = 2025,
            month = 3,
            netPay = 16_000.0,
            actualFoodDeduction = 500.0
        )

        assertEquals(1_000.0, result!!, 0.01)
    }

    @Test
    fun `calculateBasePayFromNet koristi samo neto platu i koeficijent rada`() {
        repo.foodPerDayValue = 300.0
        repo.set(2025, 3, 3, Shift.FIRST, 8)

        val result = repo.calculateBasePayFromNet(2025, 3, 8_000.0)

        assertEquals(1_000.0, result!!, 0.01)
    }

    @Test
    fun `getSummary racuna minuli rad prema mesecu sumara`() {
        repo.basePayValue = 1_000.0
        repo.minuliRadStartYearValue = 2019
        repo.set(2026, 1, 1, Shift.OFF, 0, holidayOverride = false)
        repo.set(2026, 1, 2, Shift.OFF, 0, holidayOverride = false)
        repo.set(2026, 1, 5, Shift.FIRST, 8)
        repo.set(2026, 1, 7, Shift.OFF, 0, holidayOverride = false)
        repo.set(2026, 2, 2, Shift.FIRST, 8)
        repo.set(2026, 2, 16, Shift.OFF, 0, holidayOverride = false)
        repo.set(2026, 2, 17, Shift.OFF, 0, holidayOverride = false)

        val january = repo.getSummary(2026, 1)
        val february = repo.getSummary(2026, 2)

        assertEquals(6, january.yearsOfService)
        assertEquals(7, february.yearsOfService)
        assertEquals(240.0, january.minuliRadBonus, 0.01)
        assertEquals(280.0, february.minuliRadBonus, 0.01)
    }

    @Test
    fun `getSummary racuna minuli rad prema mesecu pocetka rada`() {
        repo.basePayValue = 1_000.0
        repo.minuliRadStartYearValue = 2019
        repo.minuliRadStartMonthValue = 5
        repo.set(2026, 4, 6, Shift.FIRST, 8)
        repo.set(2026, 5, 4, Shift.FIRST, 8)

        val april = repo.getSummary(2026, 4)
        val may = repo.getSummary(2026, 5)

        assertEquals(6, april.yearsOfService)
        assertEquals(7, may.yearsOfService)
    }

    @Test
    fun `beneficirani staz menja samo broj obracunskih godina za minuli rad`() {
        repo.basePayValue = 1_000.0
        repo.minuliRadStartYearValue = 2018
        repo.minuliRadStartMonthValue = 5
        repo.minuliRadBenefitEnabledValue = true
        repo.minuliRadIncreaseFactorValue = SeniorityIncreaseFactor.TWELVE_EIGHTEEN
        repo.set(2026, 5, 4, Shift.FIRST, 8)

        val summary = repo.getSummary(2026, 5)

        assertEquals(12, summary.yearsOfService)
        assertEquals(480.0, summary.minuliRadBonus, 0.01)
    }

    @Test
    fun `stari korisnik bez novih minuli polja zadrzava isti rezultat 0_5 posto`() {
        assertTrue(
            repo.importFromJson(
                """
                {
                  "minuli_rad_start_year": 2016,
                  "minuli_rad_start_month": 5
                }
                """.trimIndent()
            )
        )
        repo.basePayValue = 1_000.0
        repo.set(2026, 5, 4, Shift.FIRST, 8)

        val summary = repo.getSummary(2026, 5)

        assertEquals(SeniorityInputMode.KNOWN_START, repo.seniorityInputModeValue)
        assertFalse(repo.minuliRadBenefitEnabledValue)
        assertEquals(SeniorityIncreaseFactor.NONE, repo.minuliRadIncreaseFactorValue)
        assertEquals(10, summary.yearsOfService)
        assertEquals(400.0, summary.minuliRadBonus, 0.01)
    }

    @Test
    fun `minuli podesavanja za procenat sa platne liste se cuvaju kroz json`() {
        repo.seniorityInputModeValue = SeniorityInputMode.PAYSLIP_PERCENT
        repo.minuliRadLastPayslipYearValue = 2026
        repo.minuliRadLastPayslipMonthValue = 4
        repo.minuliRadPayslipPercentValue = 8.0
        repo.minuliRadIncreaseFactorValue = SeniorityIncreaseFactor.TWELVE_SIXTEEN

        val restored = testRepository()
        assertTrue(restored.importFromJson(repo.exportJson()))

        assertEquals(SeniorityInputMode.PAYSLIP_PERCENT, restored.seniorityInputModeValue)
        assertEquals(2026, restored.minuliRadLastPayslipYearValue)
        assertEquals(4, restored.minuliRadLastPayslipMonthValue)
        assertEquals(8.0, restored.minuliRadPayslipPercentValue, 0.0001)
        assertEquals(SeniorityIncreaseFactor.TWELVE_SIXTEEN, restored.minuliRadIncreaseFactorValue)
    }

    @Test
    fun `minuliRadStartMonthValue klampuje na validan mesec`() {
        repo.minuliRadStartMonthValue = 0
        assertEquals(1, repo.minuliRadStartMonthValue)

        repo.minuliRadStartMonthValue = 18
        assertEquals(12, repo.minuliRadStartMonthValue)
    }

    @Test
    fun `calculateBasePayFromNet skida istorijski minuli rad iz neto plate`() {
        repo.foodPerDayValue = 280.0
        repo.minuliRadStartYearValue = 2019
        repo.set(2026, 1, 1, Shift.OFF, 0, holidayOverride = false)
        repo.set(2026, 1, 2, Shift.OFF, 0, holidayOverride = false)
        repo.set(2026, 1, 5, Shift.FIRST, 8)
        repo.set(2026, 1, 7, Shift.OFF, 0, holidayOverride = false)

        val result = repo.calculateBasePayFromNet(2026, 1, 8_240.0)

        assertEquals(1_000.0, result!!, 0.01)
    }

    @Test
    fun `initialCompBalanceValue klampuje na minimum 0`() {
        repo.initialCompBalanceValue = -10
        assertEquals(0, repo.initialCompBalanceValue)
    }

    @Test
    fun `initialCompBalanceMinutesValue klampuje na minimum 0`() {
        repo.initialCompBalanceMinutesValue = -10
        assertEquals(0, repo.initialCompBalanceMinutesValue)
    }

    @Test
    fun `minuliRadStartYearValue klampuje na minimum 0`() {
        repo.minuliRadStartYearValue = -5
        assertEquals(0, repo.minuliRadStartYearValue)
    }

    @Test
    fun `brigadeTypeValue podrazumevano koristi trobrigadu`() {
        assertEquals(BrigadeType.TROBRIGADA, repo.brigadeTypeValue)
    }

    @Test
    fun `brigadeTypeValue prihvata cetvorobrigadu za obracun bilansa`() {
        repo.brigadeTypeValue = BrigadeType.fromValue("cetv")

        assertEquals("cetv", repo.brigadeTypeValue.value)
    }

    @Test
    fun `getScheduledShiftForDate vraca null kada nema dovoljno zapisa`() {
        assertNull(repo.getScheduledShiftForDate(LocalDate.of(2026, 7, 13)))
    }

    @Test
    fun `getScheduledShiftForDate vraca smenu po trobrigadi kada je dan promenjen u godisnji`() {
        val scheduler = ShiftScheduler()
        val anchorDate = LocalDate.of(2026, 7, 6)
        val settings = ShiftFillSettings(
            mode = ShiftFillMode.THREE_BRIGADE,
            anchorDate = anchorDate,
            selectedShift = Shift.THIRD
        )
        scheduler.populateMonthsAhead(repo, anchorDate, settings, monthsAhead = 1)

        repo.set(2026, 7, 13, Shift.VACATION, 8, holidayOverride = false)
        repo.set(2026, 7, 14, Shift.VACATION, 8, holidayOverride = false)
        repo.set(2026, 7, 15, Shift.VACATION, 8, holidayOverride = false)

        assertEquals(Shift.SECOND, repo.getScheduledShiftForDate(LocalDate.of(2026, 7, 13)))
        assertEquals(Shift.FIRST, repo.getScheduledShiftForDate(LocalDate.of(2026, 7, 20)))
        assertEquals(Shift.THIRD, repo.getScheduledShiftForDate(LocalDate.of(2026, 7, 27)))
    }

    @Test
    fun `getScheduledShiftForDate koristi smenu sedmice za rucni rad vikendom`() {
        val scheduler = ShiftScheduler()
        val anchorDate = LocalDate.of(2026, 6, 1)
        val settings = ShiftFillSettings(
            mode = ShiftFillMode.THREE_BRIGADE,
            anchorDate = anchorDate,
            selectedShift = Shift.SECOND
        )
        scheduler.populateMonthsAhead(repo, anchorDate, settings, monthsAhead = 0)

        assertNull(repo.get(2026, 6, 6))
        assertEquals(Shift.SECOND, repo.getScheduledShiftForDate(LocalDate.of(2026, 6, 6)))
        assertEquals(Shift.FIRST, repo.getScheduledShiftForDate(LocalDate.of(2026, 6, 13)))
        assertEquals(Shift.THIRD, repo.getScheduledShiftForDate(LocalDate.of(2026, 6, 20)))
    }

    @Test
    fun `getScheduledShiftForDate ignorise rucnu zamenu smene pri izboru sedmicnog rasporeda`() {
        val scheduler = ShiftScheduler()
        val anchorDate = LocalDate.of(2026, 6, 1)
        val settings = ShiftFillSettings(
            mode = ShiftFillMode.THREE_BRIGADE,
            anchorDate = anchorDate,
            selectedShift = Shift.SECOND
        )
        scheduler.populateMonthsAhead(repo, anchorDate, settings, monthsAhead = 0)

        repo.set(2026, 6, 5, Shift.FIRST, 8, holidayOverride = false)

        assertEquals(Shift.SECOND, repo.getScheduledShiftForDate(LocalDate.of(2026, 6, 6)))
        assertEquals(Shift.FIRST, repo.getScheduledShiftForDate(LocalDate.of(2026, 6, 13)))
        assertEquals(Shift.THIRD, repo.getScheduledShiftForDate(LocalDate.of(2026, 6, 20)))
    }

    @Test
    fun `getScheduledShiftForDate koristi auto popunu kao izvor istine posle vise rucnih zamena`() {
        val scheduler = ShiftScheduler()
        val anchorDate = LocalDate.of(2026, 6, 1)
        val settings = ShiftFillSettings(
            mode = ShiftFillMode.THREE_BRIGADE,
            anchorDate = anchorDate,
            selectedShift = Shift.SECOND
        )
        scheduler.populateMonthsAhead(repo, anchorDate, settings, monthsAhead = 0)

        for (day in 1..5) {
            repo.set(2026, 6, day, Shift.FIRST, 8, holidayOverride = false)
        }

        assertEquals(Shift.SECOND, repo.getScheduledShiftForDate(LocalDate.of(2026, 6, 6)))
        assertEquals(Shift.FIRST, repo.getScheduledShiftForDate(LocalDate.of(2026, 6, 13)))
        assertEquals(Shift.THIRD, repo.getScheduledShiftForDate(LocalDate.of(2026, 6, 20)))
    }

    @Test
    fun `getScheduledShiftForDate vraca smenu po cetvorobrigadi za datum van postojecih zapisa`() {
        repo.brigadeTypeValue = BrigadeType.CETVOROBRIGADA
        val scheduler = ShiftScheduler()
        val anchorDate = LocalDate.of(2026, 5, 4)
        val settings = ShiftFillSettings(
            mode = ShiftFillMode.FOUR_BRIGADE,
            anchorDate = anchorDate,
            selectedShift = Shift.FIRST,
            cycleDayIndex = 0
        )
        scheduler.populateMonthsAhead(repo, anchorDate, settings, monthsAhead = 1)
        val probeDate = LocalDate.of(2026, 5, 20)
        val expected = scheduler.calculateShiftForDate(probeDate, settings)
        repo.set(probeDate.year, probeDate.monthValue, probeDate.dayOfMonth, Shift.VACATION, 8, holidayOverride = false)

        assertEquals(expected, repo.getScheduledShiftForDate(probeDate))
    }

    // ============= getAllMonthsSorted testovi =============

    @Test
    fun `getAllMonthsSorted vraća sortiranu listu`() {
        repo.set(2025, 3, 3, Shift.FIRST, 8)
        repo.set(2025, 1, 6, Shift.FIRST, 8)
        repo.set(2024, 12, 1, Shift.FIRST, 8)

        val months = repo.getAllMonthsSorted()
        assertEquals(3, months.size)
        assertEquals(MonthKey(2024, 12), months[0])
        assertEquals(MonthKey(2025, 1), months[1])
        assertEquals(MonthKey(2025, 3), months[2])
    }

    @Test
    fun `getAllMonthsSorted prazna lista za prazan repo`() {
        assertTrue(repo.getAllMonthsSorted().isEmpty())
    }

    // ============= onChange callback testovi =============

    @Test
    fun `onChange callback se poziva posle set`() {
        var called = false
        repo.onChange { called = true }
        repo.set(2025, 1, 6, Shift.FIRST, 8)
        assertTrue("Callback mora biti pozvan posle set()", called)
    }

    @Test
    fun `onChange callback se poziva posle delete`() {
        repo.set(2025, 1, 6, Shift.FIRST, 8)
        var called = false
        repo.onChange { called = true }
        repo.delete(2025, 1, 6)
        assertTrue("Callback mora biti pozvan posle delete()", called)
    }

    @Test
    fun `removeOnChange uklanja callback`() {
        var callCount = 0
        val token = repo.onChange { callCount++ }
        repo.set(2025, 1, 6, Shift.FIRST, 8)
        assertEquals(1, callCount)

        repo.removeOnChange(token)
        repo.set(2025, 1, 7, Shift.SECOND, 8)
        assertEquals(1, callCount) // Nije pozvano ponovo
    }

    @Test
    fun `close ne uklanja trajne onChange callbacke`() {
        var callCount = 0
        repo.onChange { callCount++ }

        repo.close()
        repo.set(2025, 1, 6, Shift.FIRST, 8)

        assertEquals(1, callCount)
    }

    // ============= Konkurentni pristup testovi =============

    @Test
    fun `konkurentni set pozivi ne gube zapise`() {
        val threads = (1..10).map { threadId ->
            Thread {
                for (day in 1..28) {
                    repo.set(2025, threadId.coerceIn(1, 12), day, Shift.FIRST, 8)
                }
            }
        }
        threads.forEach { it.start() }
        threads.forEach { it.join() }

        // Svaki thread upisuje 28 zapisa u svoj mesec
        for (month in 1..10) {
            val records = repo.getMonthRecords(2025, month)
            assertEquals(
                "Mesec $month mora imati 28 zapisa",
                28,
                records.size
            )
        }
    }

    @Test
    fun `konkurentni set i getSummary ne prave crash`() {
        // Popuni inicijalne podatke
        for (day in listOf(6, 7, 8, 9, 10)) { // Pon-Pet
            repo.set(2025, 1, day, Shift.FIRST, 8)
        }

        val writerThread = Thread {
            for (i in 1..100) {
                repo.set(2025, 1, 6, Shift.values()[i % 3 + 1], 8)
            }
        }
        val readerThread = Thread {
            for (i in 1..100) {
                repo.getSummary(2025, 1) // Ne sme da baci exception
            }
        }

        writerThread.start()
        readerThread.start()
        writerThread.join()
        readerThread.join()
        // Ako smo stigli dovde bez exception-a, test prolazi
    }

    // ============= JSON import/export testovi =============

    @Test
    fun `exportJson i importFromJson roundtrip`() {
        repo.basePayValue = 500.0
        repo.foodPerDayValue = 300.0
        repo.minuliRadStartYearValue = 2019
        repo.initialCompBalanceValue = 10
        repo.setMonthlyGrossAdditions(
            2025,
            1,
            MonthlyGrossAdditions(
                year = 2025,
                month = 1,
                mealGross = 5_320.97,
                regressGross = 28_530.67,
                bonusGross = 28_531.0
            )
        )

        repo.set(2025, 1, 6, Shift.FIRST, 8)
        repo.set(2025, 1, 7, Shift.SICK, 8, sickPayRate = 0.80)
        repo.set(
            2025, 1, 8, Shift.SECOND, 8,
            shift2 = Shift.THIRD,
            hours2 = 4,
            overtimeType = OvertimeType.COMP_TIME
        )
        repo.set(
            2025, 1, 4, Shift.FIRST, 8,
            weekendOvertimeType = OvertimeType.COMP_TIME,
            holidayWorkOverride = false,
            holidayOverride = true
        )

        val json = repo.exportJson()
        assertNotNull(json)
        assertTrue(json.isNotEmpty())

        // Uvezi u novi repo
        val repo2 = testRepository()
        val success = repo2.importFromJson(json)
        assertTrue("Import mora uspeti", success)

        assertEquals(500.0, repo2.basePayValue, 0.01)
        assertEquals(300.0, repo2.foodPerDayValue, 0.01)
        assertEquals(2019, repo2.minuliRadStartYearValue)
        assertEquals(10, repo2.initialCompBalanceValue)
        assertEquals(BrigadeType.TROBRIGADA, repo2.brigadeTypeValue)
        val additions = repo2.getMonthlyGrossAdditions(2025, 1)
        assertEquals(5_320.97, additions.mealGross ?: Double.NaN, 0.01)
        assertEquals(28_530.67, additions.regressGross, 0.01)
        assertEquals(28_531.0, additions.bonusGross, 0.01)

        val rec1 = repo2.get(2025, 1, 6)
        assertNotNull(rec1)
        assertEquals(Shift.FIRST, rec1!!.shift)

        val rec2 = repo2.get(2025, 1, 7)
        assertNotNull(rec2)
        assertEquals(Shift.SICK, rec2!!.shift)

        val rec3 = repo2.get(2025, 1, 8)
        assertNotNull(rec3)
        assertEquals(Shift.SECOND, rec3!!.shift)
        assertEquals(Shift.THIRD, rec3.shift2)
        assertEquals(4, rec3.hours2)

        val rec4 = repo2.get(2025, 1, 4)
        assertNotNull(rec4)
        assertEquals(OvertimeType.COMP_TIME, rec4!!.weekendOvertimeType)
        assertEquals(false, rec4.holidayWorkOverride)
        assertEquals(true, rec4.holidayOverride)
    }

    @Test
    fun `initial comp balance minutes se cuvaju kroz json export import`() {
        repo.initialCompBalanceMinutesValue = 345

        val exported = repo.exportJson()
        val exportedJson = JSONObject(exported)
        val restored = testRepository()

        assertTrue(restored.importFromJson(exported))
        assertEquals(345, exportedJson.getInt("initial_comp_balance_minutes"))
        assertEquals(345, restored.initialCompBalanceMinutesValue)
        assertEquals(5, restored.initialCompBalanceValue)
    }

    @Test
    fun `datum poslednjeg davanja krvi se cuva kroz json export import`() {
        val date = LocalDate.of(2026, 1, 10)
        repo.recordBloodDonation(date)

        val exported = repo.exportJson()
        val exportedJson = JSONObject(exported)
        val restored = testRepository()

        assertTrue(restored.importFromJson(exported))
        assertEquals("2026-01-10", exportedJson.getString("blood_donation_last_date"))
        assertEquals(1, exportedJson.getInt("blood_donation_count"))
        assertEquals(date, restored.lastBloodDonationDate)
        assertEquals(1, restored.bloodDonationCount)
    }

    @Test
    fun `istorija davanja krvi se cuva kroz json export import`() {
        val firstDate = LocalDate.of(2026, 1, 10)
        val secondDate = LocalDate.of(2026, 4, 12)
        repo.recordBloodDonation(firstDate)
        repo.recordBloodDonation(firstDate)
        repo.recordBloodDonation(secondDate)

        val exported = repo.exportJson()
        val exportedJson = JSONObject(exported)
        val exportedHistory = exportedJson.getJSONArray("blood_donation_history")
        val restored = testRepository()

        assertTrue(restored.importFromJson(exported))
        assertEquals(2, exportedHistory.length())
        assertEquals("2026-04-12", exportedHistory.getString(0))
        assertEquals("2026-01-10", exportedHistory.getString(1))
        assertEquals(listOf(secondDate, firstDate), restored.bloodDonationHistory)
        assertEquals(secondDate, restored.lastBloodDonationDate)
        assertEquals(2, restored.bloodDonationCount)
    }

    @Test
    fun `upis novog davanja krvi povecava brojac`() {
        repo.recordBloodDonation(LocalDate.of(2026, 1, 10))
        repo.recordBloodDonation(LocalDate.of(2026, 1, 10))
        repo.recordBloodDonation(LocalDate.of(2026, 4, 12))

        assertEquals(LocalDate.of(2026, 4, 12), repo.lastBloodDonationDate)
        assertEquals(2, repo.bloodDonationCount)
    }

    @Test
    fun `brisanje davanja krvi azurira istoriju poslednji datum i brojac`() {
        val firstDate = LocalDate.of(2026, 1, 10)
        val secondDate = LocalDate.of(2026, 4, 12)
        repo.recordBloodDonation(firstDate)
        repo.recordBloodDonation(secondDate)

        assertTrue(repo.deleteBloodDonation(secondDate))

        assertEquals(listOf(firstDate), repo.bloodDonationHistory)
        assertEquals(firstDate, repo.lastBloodDonationDate)
        assertEquals(1, repo.bloodDonationCount)
        assertFalse(repo.deleteBloodDonation(secondDate))
    }

    @Test
    fun `stari backup sa datumom davanja krvi bez brojaca racuna jedno davanje`() {
        val imported = testRepository()

        assertTrue(imported.importFromJson("""{"blood_donation_last_date":"2026-01-10"}"""))

        assertEquals(LocalDate.of(2026, 1, 10), imported.lastBloodDonationDate)
        assertEquals(listOf(LocalDate.of(2026, 1, 10)), imported.bloodDonationHistory)
        assertEquals(1, imported.bloodDonationCount)
    }

    @Test
    fun `bruto dodaci se ne prenose u drugi mesec`() {
        repo.setMonthlyGrossAdditions(
            2026,
            5,
            MonthlyGrossAdditions(
                year = 2026,
                month = 5,
                mealGross = 8_787.45,
                regressGross = 28_530.67,
                bonusGross = 28_530.67
            )
        )

        val mayAdditions = repo.getMonthlyGrossAdditions(2026, 5)
        val juneAdditions = repo.getMonthlyGrossAdditions(2026, 6)

        assertEquals(57_061.34, mayAdditions.totalGross, 0.01)
        assertEquals(null, juneAdditions.mealGross)
        assertEquals(0.0, juneAdditions.regressGross, 0.01)
        assertEquals(0.0, juneAdditions.bonusGross, 0.01)
        assertEquals(0.0, juneAdditions.totalGross, 0.01)
    }

    @Test
    fun `Day Edit bruto procena rasporedjuje bruto dodatke po placenim danima`() {
        repo.basePayValue = 1_000.0
        repo.employeeContributionsPercentValue = 20.0
        repo.taxReliefValue = 0.0
        repo.taxPercentValue = 10.0
        repo.setMonthlyGrossAdditions(
            2026,
            6,
            MonthlyGrossAdditions(year = 2026, month = 6)
        )
        repo.set(2026, 6, 4, Shift.FIRST, 8)
        repo.set(2026, 6, 5, Shift.FIRST, 8)
        repo.set(2026, 6, 6, Shift.OFF, 0)

        val paidRecord = repo.get(2026, 6, 4)
        val offRecord = repo.get(2026, 6, 6)

        assertNotNull(paidRecord)
        assertEquals(8_342.86, repo.calculateEstimatedDailyGrossPay(paidRecord!!), 0.01)
        assertEquals(5_600.00, repo.calculateEstimatedDailyNetPay(paidRecord), 0.01)
        assertNull(offRecord)
    }

    @Test
    fun `Day Edit glavna zarada prikazuje rad bez mesecnih dodataka`() {
        repo.basePayValue = 1_000.0
        repo.employeeContributionsPercentValue = 20.0
        repo.taxReliefValue = 0.0
        repo.taxPercentValue = 10.0
        repo.set(2026, 5, 2, Shift.FIRST, 5, holidayOverride = true)

        val record = repo.get(2026, 5, 2)

        assertNotNull(record)
        assertEquals(11_150.0, repo.calculateEstimatedDailyWorkGrossPay(record!!), 0.01)
        assertEquals(7_805.0, repo.calculateEstimatedDailyWorkNetPay(record), 0.01)
        assertTrue(repo.calculateEstimatedDailyGrossPay(record) > repo.calculateEstimatedDailyWorkGrossPay(record))
        assertTrue(repo.calculateEstimatedDailyNetPay(record) > repo.calculateEstimatedDailyWorkNetPay(record))
    }

    @Test
    fun `getHolidayEntries prikazuje zvanicne praznike sa godinom i statusom`() {
        val entries = repo.getHolidayEntries(2026)

        val newYear = entries.firstOrNull { it.year == 2026 && it.month == 1 && it.day == 1 }

        assertNotNull(newYear)
        assertEquals("Nova godina", newYear!!.name)
        assertEquals(HolidayEntrySource.OFFICIAL, newYear.source)
        assertTrue(newYear.isActive)
    }

    @Test
    fun `setHolidayActive iskljucuje zvanican praznik`() {
        repo.setHolidayActive(2026, 1, 1, isActive = false)

        val record = repo.get(2026, 1, 1)
        val entry = repo.getHolidayEntries(2026).first { it.month == 1 && it.day == 1 }

        assertNotNull(record)
        assertFalse(record!!.isHoliday)
        assertFalse(entry.isActive)
        assertEquals(HolidayEntrySource.OFFICIAL, entry.source)
    }

    @Test
    fun `setHolidayActive dodaje rucni praznik`() {
        repo.setHolidayActive(2026, 3, 10, isActive = true)

        val record = repo.get(2026, 3, 10)
        val entry = repo.getHolidayEntries(2026).first { it.month == 3 && it.day == 10 }

        assertNotNull(record)
        assertTrue(record!!.isHoliday)
        assertEquals(MANUAL_HOLIDAY_LABEL, record.holidayName)
        assertEquals(2026, entry.year)
        assertEquals(MANUAL_HOLIDAY_LABEL, entry.name)
        assertEquals(HolidayEntrySource.MANUAL, entry.source)
        assertTrue(entry.isActive)
    }

    @Test
    fun `setHolidayActive bez promene vraca true bez upisa zapisa`() {
        val result = repo.setHolidayActive(2026, 1, 1, isActive = true)

        assertTrue(result)
        assertNull(repo.get(2026, 1, 1))
    }

    @Test
    fun `deleteAutoFilledHolidayRecordsBefore brise samo stare auto popunjene praznike`() {
        repo.set(2026, 1, 1, Shift.OFF, 0, balanceExempt = true)
        repo.set(2026, 1, 2, Shift.FIRST, 8, holidayOverride = false)
        repo.setHolidayActive(2026, 3, 10, isActive = true)
        repo.set(2026, 5, 1, Shift.OFF, 0, balanceExempt = true)

        val deleted = repo.deleteAutoFilledHolidayRecordsBefore(MonthKey(2026, 5))

        assertEquals(1, deleted)
        assertNull(repo.get(2026, 1, 1))
        assertNotNull(repo.get(2026, 1, 2))
        assertNotNull(repo.get(2026, 3, 10))
        assertNotNull(repo.get(2026, 5, 1))
    }

    @Test
    fun `mesec bez placenih dana prikazuje dodatke ali ih ne rasporedjuje na dan`() {
        repo.employeeContributionsPercentValue = 20.0
        repo.taxReliefValue = 0.0
        repo.taxPercentValue = 10.0
        repo.setMonthlyGrossAdditions(
            2026,
            6,
            MonthlyGrossAdditions(year = 2026, month = 6, bonusGross = 900.0)
        )
        repo.set(2026, 6, 1, Shift.OFF, 0)

        val breakdown = repo.getNetPayBreakdown(2026, 6)
        val offRecord = repo.get(2026, 6, 1)

        assertEquals(900.0, breakdown.grossTotal, 0.01)
        assertEquals(630.0, breakdown.displayPay, 0.01)
        assertNotNull(offRecord)
        assertEquals(0.0, repo.calculateEstimatedDailyGrossPay(offRecord!!), 0.01)
        assertEquals(0.0, repo.calculateEstimatedDailyNetPay(offRecord), 0.01)
    }

    @Test
    fun `importFromJson cuva cetv brigade_type kao cetvorobrigadu`() {
        val json = """
            {
              "brigade_type": "cetv",
              "2025": {
                "01": {
                  "06": {
                    "shift": 1,
                    "hours": 8
                  }
                }
              }
            }
        """.trimIndent()

        val success = repo.importFromJson(json)

        assertTrue("Import mora uspeti", success)
        assertEquals("cetv", repo.brigadeTypeValue.value)
        assertTrue(repo.exportJson().contains("\"brigade_type\": \"cetv\""))
    }

    @Test
    fun `importFromJson sa nevalidnim JSON vraća false`() {
        val result = repo.importFromJson("ovo nije JSON")
        assertFalse(result)
    }

    @Test
    fun `importFromJson sa praznim objektom ne puca i odbija se`() {
        // Prazan objekat nema nijedan prepoznat ključ: ranije je bio „uspešan" uvoz koji briše sve.
        val result = repo.importFromJson("{}")
        assertFalse(result)
        assertTrue(repo.getMonthRecords(2025, 1).isEmpty())
    }

    // ============= Vacation balance testovi =============

    @Test
    fun `vacation balance prati korišćene dane`() {
        repo.initialVacationBalanceValue = 20.0
        repo.set(2025, 1, 6, Shift.VACATION, 8)
        repo.set(2025, 1, 7, Shift.VACATION, 8)

        assertEquals(2, repo.getVacationDaysUsed())
        assertEquals(18.0, repo.getVacationDaysRemaining(), 0.01)
        assertFalse(repo.isVacationExceeded())
    }

    @Test
    fun `vacation exceeded kad se prekorači limit`() {
        repo.initialVacationBalanceValue = 1.0
        repo.set(2025, 1, 6, Shift.VACATION, 8)
        repo.set(2025, 1, 7, Shift.VACATION, 8)

        assertTrue(repo.isVacationExceeded())
        assertEquals(0.0, repo.getVacationDaysRemaining(), 0.01)
    }

    @Test
    fun `initialVacationBalanceValue klampuje na minimum 0`() {
        repo.initialVacationBalanceValue = -5.0
        assertEquals(0.0, repo.initialVacationBalanceValue, 0.01)
    }

    @Test
    fun `vacation summary koristi rucno pocetno stanje pre jula`() {
        repo.setVacationYearBalance(
            VacationYearBalance(
                year = 2026,
                trackingStartDate = LocalDate.of(2026, 5, 9),
                carryoverDaysInitial = 0.0,
                currentYearDaysTotal = 23.0,
                currentYearDaysUsedBeforeAvailability = 4.0,
                currentYearAvailableFrom = LocalDate.of(2026, 7, 1),
                manualBaseline = true
            )
        )

        val summary = repo.getVacationBalanceSummary(2026, LocalDate.of(2026, 5, 9))

        assertEquals(0.0, summary.availableNow, 0.01)
        assertEquals(19.0, summary.availableFromJuly, 0.01)
        assertEquals(19.0, summary.currentYearRemaining, 0.01)
    }

    @Test
    fun `vacation summary sa starim odmorom broji junske dane pre pocetka pracenja`() {
        repo.setVacationYearBalance(
            VacationYearBalance(
                year = 2026,
                trackingStartDate = LocalDate.of(2026, 5, 9),
                carryoverDaysInitial = 2.0,
                currentYearDaysTotal = 20.0,
                currentYearDaysUsedBeforeAvailability = 0.0,
                currentYearAvailableFrom = LocalDate.of(2026, 7, 1),
                manualBaseline = true
            )
        )
        repo.set(2026, 5, 4, Shift.VACATION, 8)
        repo.set(2026, 5, 11, Shift.VACATION, 8)

        val summary = repo.getVacationBalanceSummary(2026, LocalDate.of(2026, 5, 12))

        assertEquals(0.0, summary.carryoverRemaining, 0.01)
        assertEquals(2.0, summary.carryoverUsed, 0.01)
        assertEquals(0.0, summary.currentYearUsed, 0.01)
    }

    @Test
    fun `januar nove godine prenosi preostali odmor iz prethodne godine kao stari`() {
        repo.setVacationYearBalance(
            VacationYearBalance(
                year = 2026,
                trackingStartDate = LocalDate.of(2026, 5, 9),
                carryoverDaysInitial = 0.0,
                currentYearDaysTotal = 23.0,
                currentYearDaysUsedBeforeAvailability = 4.0,
                currentYearAvailableFrom = LocalDate.of(2026, 7, 1),
                manualBaseline = true
            )
        )
        repo.set(2026, 7, 1, Shift.VACATION, 8)
        repo.set(2026, 7, 2, Shift.VACATION, 8)
        repo.set(2027, 1, 12, Shift.VACATION, 8)
        repo.set(2027, 2, 2, Shift.VACATION, 8)

        val summary = repo.getVacationBalanceSummary(2027, LocalDate.of(2027, 1, 31))

        assertEquals(16.0, summary.carryoverRemaining, 0.01)
        assertEquals(16.0, summary.availableNow, 0.01)
        assertEquals(0.0, summary.currentYearUsed, 0.01)
        assertEquals(23.0, summary.availableFromJuly, 0.01)
    }

    @Test
    fun `vacation year balance se cuva u json export import`() {
        repo.setVacationYearBalance(
            VacationYearBalance(
                year = 2026,
                trackingStartDate = LocalDate.of(2026, 5, 9),
                carryoverDaysInitial = 1.0,
                currentYearDaysTotal = 23.0,
                currentYearDaysUsedBeforeAvailability = 4.0,
                currentYearAvailableFrom = LocalDate.of(2026, 7, 1),
                manualBaseline = true
            )
        )

        val restored = testRepository()
        assertTrue(restored.importFromJson(repo.exportJson()))

        val balance = restored.getVacationYearBalance(2026)
        assertNotNull(balance)
        assertEquals(LocalDate.of(2026, 5, 9), balance!!.trackingStartDate)
        assertEquals(1.0, balance.carryoverDaysInitial, 0.01)
        assertEquals(23.0, balance.currentYearDaysTotal, 0.01)
        assertEquals(4.0, balance.currentYearDaysUsedBeforeAvailability, 0.01)
    }

    // ============= set sa dupla smena testovi =============

    @Test
    fun `set sa duplom smenom čuva sve podatke`() {
        repo.set(
            2025, 1, 6, Shift.FIRST, 8,
            shift2 = Shift.SECOND,
            hours2 = 4,
            overtimeType = OvertimeType.COMP_TIME
        )
        val record = repo.get(2025, 1, 6)
        assertNotNull(record)
        assertEquals(Shift.FIRST, record!!.shift)
        assertEquals(8, record.hours)
        assertEquals(Shift.SECOND, record.shift2)
        assertEquals(4, record.hours2)
        assertEquals(OvertimeType.COMP_TIME, record.overtimeType)
    }

    @Test
    fun `set ignoriše drugu smenu kada prva smena nije radna`() {
        repo.foodPerDayValue = 300.0

        repo.set(
            2025, 3, 3, Shift.OFF, 0,
            shift2 = Shift.SECOND,
            hours2 = 8
        )

        val record = repo.get(2025, 3, 3)
        assertNotNull(record)
        assertFalse(record!!.isDoubleShift)
        assertNull(record.shift2)
        assertEquals(0, record.hours2)

        val summary = repo.getSummary(2025, 3)
        assertEquals(0, summary.doubleShiftDays)
        assertEquals(0, summary.workHours)
        assertEquals(0.0, summary.foodAllowance, 0.01)
        assertEquals(0.0, summary.totalPay, 0.01)
    }

    @Test
    fun `off na radni dan cuva nula sati i pravi minus u bilansu`() {
        repo.set(2025, 3, 3, Shift.OFF, 8)

        val record = repo.get(2025, 3, 3)
        val summary = repo.getSummary(2025, 3)

        assertNotNull(record)
        assertEquals(Shift.OFF, record!!.shift)
        assertEquals(0, record.hours)
        assertEquals(0, record.totalHours)
        assertFalse(record.isWorkDay)
        assertEquals(CONFIG.pay.defaultHours, record.hoursDeficit)
        assertEquals(0, summary.workHours)
        assertEquals(0, summary.workDays)
        assertEquals(CONFIG.pay.defaultHours, summary.compTimeDeficit)
        assertEquals(-CONFIG.pay.defaultHours, summary.compTimeNet)
        assertEquals(-CONFIG.pay.defaultHours, summary.compTimeEnding)
        assertEquals(0.0, summary.totalPay, 0.01)
    }

    @Test
    fun `cetvorobrigada rucni off radnim danom umanjuje bilans sati`() {
        repo.brigadeTypeValue = BrigadeType.fromValue("cetv")
        repo.set(2025, 3, 3, Shift.OFF, 8)

        val summary = repo.getSummary(2025, 3)

        assertEquals(CONFIG.pay.defaultHours, summary.compTimeDeficit)
        assertEquals(-CONFIG.pay.defaultHours, summary.compTimeNet)
        assertEquals(-CONFIG.pay.defaultHours, summary.compTimeEnding)
    }

    @Test
    fun `cetvorobrigada rucni off iz prethodnog meseca umanjuje pocetni bilans sledeceg`() {
        repo.brigadeTypeValue = BrigadeType.fromValue("cetv")
        repo.initialCompBalanceValue = 16
        repo.set(2025, 3, 3, Shift.OFF, 8)
        repo.set(2025, 4, 1, Shift.FIRST, 8)

        val april = repo.getSummary(2025, 4)

        assertEquals(8, april.compTimeStarting)
        assertEquals(8, april.compTimeEnding)
    }

    @Test
    fun `kalendar prikazuje 0h za off na radni dan`() {
        val record = DayRecord(2025, 3, 3, Shift.OFF, 8)

        assertEquals("0h", calendarDayDetailText(record, isHolidayDay = false, workedHoliday = false))
    }

    @Test
    fun `legacy minuti iz backup-a se ignorisu i ne izvoze ponovo`() {
        val record = DayRecord.fromJson(
            year = 2026,
            month = 5,
            day = 5,
            data = JSONObject(
                """
                    {
                      "shift": 1,
                      "hours": 3,
                      "minutes": 45,
                      "comp_hours_used": 2,
                      "comp_minutes_used": 30
                    }
                """.trimIndent()
            )
        )

        val exported = record.toMap()

        assertEquals(3, record.hours)
        assertEquals(3, record.totalHours)
        assertEquals(2, record.compHoursUsed)
        assertFalse(exported.containsKey("minutes"))
        assertFalse(exported.containsKey("comp_minutes_used"))
    }

    @Test
    fun `json zapis cuva vikend prekovremeno i praznik override polja`() {
        val record = DayRecord.fromJson(
            year = 2026,
            month = 5,
            day = 9,
            data = JSONObject(
                """
                    {
                      "shift": 1,
                      "hours": 8,
                      "weekend_overtime_type": 1,
                      "holiday_work_override": false,
                      "holiday_override": true
                    }
                """.trimIndent()
            )
        )

        val exported = record.toMap()

        assertEquals(OvertimeType.COMP_TIME, record.weekendOvertimeType)
        assertEquals(false, record.holidayWorkOverride)
        assertEquals(true, record.holidayOverride)
        assertEquals(OvertimeType.COMP_TIME.value, exported["weekend_overtime_type"])
        assertEquals(false, exported["holiday_work_override"])
        assertEquals(true, exported["holiday_override"])
    }

    @Test
    fun `set sick pay rate se čuva samo za bolovanje`() {
        repo.set(2025, 1, 6, Shift.SICK, 8, sickPayRate = 0.80)
        val sickRecord = repo.get(2025, 1, 6)
        assertNotNull(sickRecord!!.sickPayRate)

        repo.set(2025, 1, 7, Shift.FIRST, 8, sickPayRate = 0.80)
        val workRecord = repo.get(2025, 1, 7)
        assertNull(workRecord!!.sickPayRate)
    }

    @Test
    fun `retention brise mesece starije od 24 meseca plus dodatni prethodni mesec`() {
        populateMonthMarkers(MonthKey(2025, 1), MonthKey(2027, 12))
        repo.setMonthlyGrossAdditions(2025, 11, MonthlyGrossAdditions(2025, 11, mealGross = 1_000.0))
        repo.setMonthlyGrossAdditions(2025, 12, MonthlyGrossAdditions(2025, 12, mealGross = 2_000.0))
        repo.setMonthlyAdvancePaymentNet(2025, 11, 5_000.0)
        repo.setMonthlyAdvancePaymentNet(2026, 1, 6_000.0)

        val changed = repo.pruneIrrelevantHistory()

        assertTrue(changed)
        assertNull(repo.get(2025, 11, 1))
        assertNotNull(repo.get(2025, 12, 1))
        assertNotNull(repo.get(2027, 12, 1))
        assertEquals(emptyList<MonthKey>(), repo.getAllMonthsSorted().filter { it < MonthKey(2025, 12) })
        assertEquals(null, repo.getMonthlyGrossAdditions(2025, 11).mealGross)
        assertEquals(2_000.0, repo.getMonthlyGrossAdditions(2025, 12).mealGross ?: Double.NaN, 0.001)
        assertEquals(0.0, repo.getMonthlyNetAdjustments(2025, 11).advancePaymentNet, 0.001)
        assertEquals(6_000.0, repo.getMonthlyNetAdjustments(2026, 1).advancePaymentNet, 0.001)
    }

    @Test
    fun `retention ne dira rucno unete satnice`() {
        repo.setHourlyValueChange(2025, 1, 333.0)
        repo.setHourlyValueChange(2026, 3, 444.0)
        populateMonthMarkers(MonthKey(2025, 1), MonthKey(2027, 12))

        repo.pruneIrrelevantHistory()
        // Lokalni save ide kroz exportJson, koji takođe orezuje istoriju.
        val reloaded = testRepository()
        assertTrue(reloaded.importFromJson(repo.exportJson()))

        assertNull(reloaded.get(2025, 1, 1))
        assertEquals(333.0, reloaded.getHourlyValueForMonth(2025, 12), 0.001)
        assertEquals(444.0, reloaded.getHourlyValueForMonth(2026, 3), 0.001)
        assertTrue(reloaded.getHourlyValueChanges().any { it.year == 2025 && it.month == 1 && it.value == 333.0 })
        assertTrue(reloaded.getHourlyValueChanges().any { it.year == 2026 && it.month == 3 && it.value == 444.0 })
    }

    @Test
    fun `retention prebacuje saldo za sate na novi pocetni bilans`() {
        repo.initialCompBalanceValue = 10
        repo.set(
            2025, 1, 4, Shift.FIRST, 8,
            weekendOvertimeType = OvertimeType.COMP_TIME
        )
        populateMonthMarkers(MonthKey(2025, 2), MonthKey(2027, 12))

        repo.pruneIrrelevantHistory()

        assertNull(repo.get(2025, 1, 4))
        assertEquals(18, repo.initialCompBalanceValue)
        assertEquals(18, repo.getCompBalanceAtMonthStart(2025, 12))
    }

    @Test
    fun `retention zadrzava osnovicu potrebnu za prvi zadrzani mesec`() {
        repo.defaultPayBaseSelectionModeValue = PayBaseSelectionMode.MANUAL
        repo.setMonthlyPayBases(
            2025,
            11,
            MonthlyPayBases(
                year = 2025,
                month = 11,
                vacationHourlyBase = 1_111.0,
                paidHolidayHourlyBase = 1_222.0,
                sickHourlyBase = 1_333.0,
                selectionMode = PayBaseSelectionMode.MANUAL
            )
        )
        populateMonthMarkers(MonthKey(2025, 1), MonthKey(2027, 12))

        repo.pruneIrrelevantHistory()

        val resolved = repo.getResolvedPayBasesForWorkMonth(2025, 12)
        assertEquals(1_111.0, resolved.vacationHourlyBase.value, 0.001)
        assertEquals(1_222.0, resolved.paidHolidayHourlyBase.value, 0.001)
        assertEquals(1_333.0, resolved.sickHourlyBase.value, 0.001)
        assertEquals(PayBaseSelectionMode.MANUAL, repo.getMonthlyPayBases(2025, 11)?.selectionMode)
    }

    // ============= cloud backup (puna istorija, razdvojeno od orezivanja) =============

    @Test
    fun `cloud backup cuva mesece van retention prozora`() {
        // Arhiva: 6 meseci unutar prozora -> exportJson ništa ne orezuje
        val archiveRepo = testRepository()
        for (month in 1..6) {
            archiveRepo.set(2025, month, 1, Shift.FIRST, 8, holidayOverride = false)
        }
        archiveRepo.setMonthlyGrossAdditions(2025, 1, MonthlyGrossAdditions(2025, 1, mealGross = 1_234.0))
        val archiveJson = archiveRepo.exportJson()
        assertTrue(JSONObject(archiveJson).has("2025"))

        // Trenutno stanje: ista istorija + produženo daleko, pa se 2025-01.. orežu iz memorije
        repo.importFromJson(archiveJson)
        populateMonthMarkers(MonthKey(2025, 7), MonthKey(2027, 12))
        repo.pruneIrrelevantHistory()
        assertNull(repo.get(2025, 1, 1))

        val merged = JSONObject(repo.exportCloudBackupJson(archiveJson))
        // Stari mesec preživljava iz arhive
        assertTrue(merged.getJSONObject("2025").getJSONObject("01").has("01"))
        // Skorašnji mesec dolazi iz trenutnog stanja
        assertTrue(merged.getJSONObject("2027").getJSONObject("12").has("01"))
        // Mesečni dodatak iz starog meseca je sačuvan
        assertTrue(merged.has("monthly_gross_additions"))
    }

    @Test
    fun `cloud backup koristi originalni pocetni saldo iz arhive`() {
        val archiveRepo = testRepository()
        archiveRepo.initialCompBalanceValue = 7
        for (month in 1..6) {
            archiveRepo.set(2025, month, 1, Shift.FIRST, 8, holidayOverride = false)
        }
        val archiveJson = archiveRepo.exportJson()

        repo.importFromJson(archiveJson)
        // Vikend-rad u starom mesecu menja saldo na granici (2025-01-04 je subota)
        repo.set(2025, 1, 4, Shift.FIRST, 8, weekendOvertimeType = OvertimeType.COMP_TIME)
        populateMonthMarkers(MonthKey(2025, 7), MonthKey(2027, 12))
        repo.pruneIrrelevantHistory()

        // Lokalni početni saldo je preračunat na granicu (više nije 7)
        assertNotEquals(7, repo.initialCompBalanceValue)
        // Arhiva zadržava ORIGINALNI početni saldo da se sati ne broje duplo
        val merged = JSONObject(repo.exportCloudBackupJson(archiveJson))
        assertEquals(7, merged.getInt("initial_comp_balance"))
    }

    @Test
    fun `cloud backup ne vraca obrisane skorasnje zapise`() {
        val archiveRepo = testRepository()
        for (month in 1..6) {
            archiveRepo.set(2026, month, 1, Shift.FIRST, 8, holidayOverride = false)
        }
        archiveRepo.set(2026, 5, 2, Shift.FIRST, 8, holidayOverride = false)
        val archiveJson = archiveRepo.exportJson()

        repo.importFromJson(archiveJson)
        repo.delete(2026, 5, 2)

        val merged = JSONObject(repo.exportCloudBackupJson(archiveJson))
        val may = merged.getJSONObject("2026").getJSONObject("05")
        assertFalse(may.has("02"))
        assertTrue(may.has("01"))
    }

    @Test
    fun `cloud backup bez postojece arhive vraca trenutno stanje`() {
        populateMonthMarkers(MonthKey(2025, 1), MonthKey(2025, 3))
        val merged = JSONObject(repo.exportCloudBackupJson(null))
        assertTrue(merged.getJSONObject("2025").getJSONObject("01").has("01"))
        assertTrue(merged.getJSONObject("2025").getJSONObject("03").has("01"))
    }

    @Test
    fun `cloud backup ignorise neispravan postojeci backup`() {
        populateMonthMarkers(MonthKey(2025, 1), MonthKey(2025, 3))
        val merged = JSONObject(repo.exportCloudBackupJson("{ ovo nije validan json"))
        assertTrue(merged.getJSONObject("2025").has("01"))
    }

    @Test
    fun `backup pa restore na novoj instalaciji vraca identicno stanje`() {
        // Smene: trobrigada rotacija, dupla smena, manjak minuta, bolovanje, GO, ručni praznik.
        ShiftScheduler().populateMonthsAhead(repo, LocalDate.of(2026, 6, 1), Shift.THIRD, monthsAhead = 3)
        repo.set(
            2026, 6, 10, Shift.FIRST, 8,
            shift2 = Shift.SECOND, hours2 = 4, overtimeType = OvertimeType.COMP_TIME
        )
        repo.set(2026, 6, 13, Shift.FIRST, 8, weekendOvertimeType = OvertimeType.COMP_TIME)
        repo.set(2026, 7, 6, Shift.SICK, 8, sickPayRate = 0.65)
        repo.set(2026, 7, 7, Shift.VACATION, 8)
        repo.setHolidayActive(2026, 7, 15, true)
        repo.applyBloodDonationLeave(LocalDate.of(2026, 8, 3))
        // Podešavanja: sve što korisnik može da ukuca.
        repo.payCoefficientValue = 3.15
        repo.setHourlyValueChange(2019, 3, 210.0)
        repo.setHourlyValueChange(2026, 7, 480.0)
        repo.setKnownPayBaseAnchor(2026, 5, 955.5)
        repo.setMonthlyGrossAdditions(2026, 6, MonthlyGrossAdditions(2026, 6, mealGross = 1_234.0))
        repo.setMonthlyAdvancePaymentNet(2026, 6, 25_000.0)
        repo.setFoodPerDayFromMonth(2026, 7, 650.0)
        repo.transportDailyAmountValue = 321.0
        repo.taxReliefValue = 34_221.0
        repo.difficultWorkConditionsPercentValue = 7.5
        repo.mainPaySalaryOnly = false
        repo.recordBloodDonation(LocalDate.of(2026, 2, 10))
        repo.recordBloodDonation(LocalDate.of(2026, 8, 3))
        repo.initialCompBalanceMinutesValue = 450
        repo.minuliRadStartYearValue = 2019
        repo.minuliRadStartMonthValue = 3
        repo.minuliRadBenefitEnabledValue = true
        repo.minuliRadIncreaseFactorValue = SeniorityIncreaseFactor.TWELVE_FOURTEEN
        repo.initialVacationBalanceValue = 12.5

        val backupJson = repo.exportCloudBackupJson(null)

        val freshInstall = testRepository()
        assertTrue(freshInstall.importFromJson(backupJson))

        assertEquals(backupJson, freshInstall.exportCloudBackupJson(null))
        assertEquals(repo.exportJson(), freshInstall.exportJson())
        // Rotacija smena ne sme da „pobegne": isti dan, ista smena.
        var date = LocalDate.of(2026, 6, 1)
        while (date.isBefore(LocalDate.of(2026, 10, 1))) {
            assertEquals(
                "smena za $date",
                repo.get(date.year, date.monthValue, date.dayOfMonth)?.shift,
                freshInstall.get(date.year, date.monthValue, date.dayOfMonth)?.shift
            )
            date = date.plusDays(1)
        }
    }

    @Test
    fun `cloud backup sa nove instalacije ne gazi davanja krvi satnice i pocetak rada`() {
        val archiveRepo = testRepository()
        archiveRepo.set(2026, 5, 4, Shift.FIRST, 8, holidayOverride = false)
        archiveRepo.recordBloodDonation(LocalDate.of(2026, 2, 10))
        archiveRepo.recordBloodDonation(LocalDate.of(2026, 6, 15))
        archiveRepo.setHourlyValueChange(2019, 3, 210.0)
        archiveRepo.setHourlyValueChange(2026, 7, 480.0)
        archiveRepo.minuliRadStartYearValue = 2019
        archiveRepo.minuliRadStartMonthValue = 3
        val archiveJson = archiveRepo.exportCloudBackupJson(null)

        // Nova instalacija: korisnik je uneo samo jedan dan pre nego što je uradio restore.
        repo.set(2026, 9, 1, Shift.FIRST, 8, holidayOverride = false)

        val restored = testRepository()
        assertTrue(restored.importFromJson(repo.exportCloudBackupJson(archiveJson)))

        assertEquals(
            listOf(LocalDate.of(2026, 6, 15), LocalDate.of(2026, 2, 10)),
            restored.bloodDonationHistory
        )
        assertEquals(2, restored.bloodDonationCount)
        assertEquals(2019, restored.minuliRadStartYearValue)
        assertEquals(3, restored.minuliRadStartMonthValue)
        assertTrue(restored.getHourlyValueChanges().any { it.year == 2019 && it.month == 3 && it.value == 210.0 })
        assertTrue(restored.getHourlyValueChanges().any { it.year == 2026 && it.month == 7 && it.value == 480.0 })
        assertNotNull(restored.get(2026, 5, 4))
        assertNotNull(restored.get(2026, 9, 1))
    }

    @Test
    fun `cloud backup postuje trenutna davanja krvi i pocetak rada kad postoje`() {
        val archiveRepo = testRepository()
        archiveRepo.set(2026, 5, 4, Shift.FIRST, 8, holidayOverride = false)
        archiveRepo.recordBloodDonation(LocalDate.of(2026, 2, 10))
        archiveRepo.minuliRadStartYearValue = 2018
        val archiveJson = archiveRepo.exportCloudBackupJson(null)

        repo.importFromJson(archiveJson)
        repo.deleteBloodDonation(LocalDate.of(2026, 2, 10))
        repo.recordBloodDonation(LocalDate.of(2026, 8, 20))
        repo.minuliRadStartYearValue = 2019

        val merged = testRepository()
        assertTrue(merged.importFromJson(repo.exportCloudBackupJson(archiveJson)))

        assertEquals(listOf(LocalDate.of(2026, 8, 20)), merged.bloodDonationHistory)
        assertEquals(2019, merged.minuliRadStartYearValue)
    }

    @Test
    fun `obrisana satnica se ne vraca iz cloud arhive a ponovni unos ponistava tombstone`() {
        repo.set(2026, 5, 4, Shift.FIRST, 8, holidayOverride = false)
        repo.setHourlyValueChange(2025, 1, 333.0)
        repo.setHourlyValueChange(2026, 3, 444.0)
        val archiveJson = repo.exportCloudBackupJson(null)

        repo.removeHourlyValueChange(2026, 3)

        // Tombstone mora da preživi lokalni save/load.
        val reloaded = testRepository()
        assertTrue(reloaded.importFromJson(repo.exportJson()))
        val afterDelete = testRepository()
        assertTrue(afterDelete.importFromJson(reloaded.exportCloudBackupJson(archiveJson)))
        assertFalse(afterDelete.getHourlyValueChanges().any { it.year == 2026 && it.month == 3 })
        assertTrue(afterDelete.getHourlyValueChanges().any { it.year == 2025 && it.month == 1 })

        reloaded.setHourlyValueChange(2026, 3, 455.0)
        val afterReentryJson = reloaded.exportCloudBackupJson(archiveJson)
        assertFalse(JSONObject(afterReentryJson).has("deleted_hourly_value_months"))
        val afterReentry = testRepository()
        assertTrue(afterReentry.importFromJson(afterReentryJson))
        assertTrue(afterReentry.getHourlyValueChanges().any { it.year == 2026 && it.month == 3 && it.value == 455.0 })
    }

    @Test
    fun `obrisan najstariji mesec se ne vraca iz cloud arhive`() {
        populateMonthMarkers(MonthKey(2025, 1), MonthKey(2025, 3))
        val archiveJson = repo.exportCloudBackupJson(null)

        repo.deleteMonth(2025, 1)

        // Bez tombstone-a bi se granica merge-a pomerila na 2025-02 pa bi
        // arhiva ponovo isporučila obrisani januar.
        val merged = JSONObject(repo.exportCloudBackupJson(archiveJson))
        assertFalse(merged.getJSONObject("2025").has("01"))
        assertTrue(merged.getJSONObject("2025").has("02"))
        assertTrue(merged.getJSONObject("2025").has("03"))
    }

    @Test
    fun `tombstone obrisanog meseca prezivljava export import`() {
        populateMonthMarkers(MonthKey(2025, 1), MonthKey(2025, 3))
        val archiveJson = repo.exportCloudBackupJson(null)
        repo.deleteMonth(2025, 1)

        val restored = testRepository()
        assertTrue(restored.importFromJson(repo.exportJson()))

        val merged = JSONObject(restored.exportCloudBackupJson(archiveJson))
        assertFalse(merged.getJSONObject("2025").has("01"))
    }

    @Test
    fun `ponovni unos u obrisan mesec ponistava tombstone`() {
        populateMonthMarkers(MonthKey(2025, 1), MonthKey(2025, 3))
        repo.deleteMonth(2025, 1)
        repo.set(2025, 1, 15, Shift.FIRST, 8, holidayOverride = false)

        val exported = JSONObject(repo.exportJson())

        assertFalse(exported.has("deleted_months"))
        assertTrue(exported.getJSONObject("2025").getJSONObject("01").has("15"))
    }

}
