package com.rad.shifttracker

import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

@Suppress("LargeClass")
class PayCalculatorTest {

    private lateinit var calculator: PayCalculator
    private lateinit var config: PayConfig

    @Before
    fun setUp() {
        config = PayConfig(
            defaultBasePay = 300.0,
            defaultHours = 8,
            shift3Premium = 1.41,
            overtimePremium = 1.41,
            sundayBonus = 0.25,
            sickPayRate = 0.65,
            vacationPayRate = 1.0,
            holidayBonus = 1.23,
            foodPerDay = 280.0,
            minuliRadPerYear = 0.005,
            vacationPayBaseHourly = 300.0,
            paidHolidayPayBaseHourly = 300.0,
            sickPayBaseHourly = 300.0
        )
        calculator = PayCalculator(config)
    }

    // ============= getSundayHoursForShift tests =============

    @Test
    fun `getSundayHoursForShift returns 0 for sick shift`() {
        val result = calculator.getSundayHoursForShift(
            Weekday.SUNDAY,
            Shift.SICK,
            8,
            BrigadeType.TROBRIGADA
        )
        assertEquals(0, result)
    }

    @Test
    fun `getSundayHoursForShift returns 0 for vacation shift`() {
        val result = calculator.getSundayHoursForShift(
            Weekday.SUNDAY,
            Shift.VACATION,
            8,
            BrigadeType.TROBRIGADA
        )
        assertEquals(0, result)
    }

    @Test
    fun `getSundayHoursForShift returns full hours for first shift on Sunday`() {
        val result = calculator.getSundayHoursForShift(
            Weekday.SUNDAY,
            Shift.FIRST,
            8,
            BrigadeType.TROBRIGADA
        )
        assertEquals(8, result)
    }

    @Test
    fun `getSundayHoursForShift returns full hours for second shift on Sunday`() {
        val result = calculator.getSundayHoursForShift(
            Weekday.SUNDAY,
            Shift.SECOND,
            8,
            BrigadeType.TROBRIGADA
        )
        assertEquals(8, result)
    }

    @Test
    fun `getSundayHoursForShift returns 2 hours max for third shift on Sunday`() {
        val result = calculator.getSundayHoursForShift(
            Weekday.SUNDAY,
            Shift.THIRD,
            8,
            BrigadeType.TROBRIGADA
        )
        assertEquals(2, result)
    }

    @Test
    fun `getSundayHoursForShift returns hours minus 2 for third shift on Saturday`() {
        val result = calculator.getSundayHoursForShift(
            Weekday.SATURDAY,
            Shift.THIRD,
            8,
            BrigadeType.TROBRIGADA
        )
        assertEquals(6, result)
    }

    @Test
    fun `getSundayHoursForShift returns 0 for first shift on Saturday`() {
        val result = calculator.getSundayHoursForShift(
            Weekday.SATURDAY,
            Shift.FIRST,
            8,
            BrigadeType.TROBRIGADA
        )
        assertEquals(0, result)
    }

    @Test
    fun `getSundayHoursForShift returns 0 for weekday`() {
        val result = calculator.getSundayHoursForShift(
            Weekday.MONDAY,
            Shift.FIRST,
            8,
            BrigadeType.TROBRIGADA
        )
        assertEquals(0, result)
    }

    // ============= getOvertimeHours tests =============

    @Test
    fun `getOvertimeHours returns 0 for sick shift`() {
        val record = DayRecord(2025, 1, 6, Shift.SICK, 8) // Monday
        val result = calculator.getOvertimeHours(record, BrigadeType.TROBRIGADA)
        assertEquals(0, result)
    }

    @Test
    fun `getOvertimeHours returns 0 for vacation shift`() {
        val record = DayRecord(2025, 1, 6, Shift.VACATION, 8) // Monday
        val result = calculator.getOvertimeHours(record, BrigadeType.TROBRIGADA)
        assertEquals(0, result)
    }

    @Test
    fun `getOvertimeHours returns 0 for off shift`() {
        val record = DayRecord(2025, 1, 6, Shift.OFF, 0) // Monday
        val result = calculator.getOvertimeHours(record, BrigadeType.TROBRIGADA)
        assertEquals(0, result)
    }

    @Test
    fun `getOvertimeHours returns 0 for regular 8 hour weekday`() {
        val record = DayRecord(2025, 1, 6, Shift.FIRST, 8) // Monday
        val result = calculator.getOvertimeHours(record, BrigadeType.TROBRIGADA)
        assertEquals(0, result)
    }

    @Test
    fun `getOvertimeHours returns 2 for 10 hour weekday`() {
        val record = DayRecord(2025, 1, 6, Shift.FIRST, 10) // Monday
        val result = calculator.getOvertimeHours(record, BrigadeType.TROBRIGADA)
        assertEquals(2, result)
    }

    @Test
    fun `getOvertimeHours returns full hours for weekend work TROBRIGADA`() {
        val record = DayRecord(2025, 1, 4, Shift.FIRST, 8) // Saturday
        val result = calculator.getOvertimeHours(record, BrigadeType.TROBRIGADA)
        assertEquals(8, result)
    }

    @Test
    fun `getOvertimeHours returns zero for workday holiday work`() {
        val record = DayRecord(2026, 5, 1, Shift.FIRST, 8)
        val result = calculator.getOvertimeHours(record, BrigadeType.TROBRIGADA)
        assertEquals(0, result)
    }

    @Test
    fun `getOvertimeHours returns zero for Saturday holiday work`() {
        val record = DayRecord(2026, 5, 2, Shift.FIRST, 5)
        val result = calculator.getOvertimeHours(record, BrigadeType.TROBRIGADA)
        assertEquals(0, result)
    }

    // ============= getFoodAllowance tests =============

    @Test
    fun `getFoodAllowance returns 0 for sick shift`() {
        val record = DayRecord(2025, 1, 6, Shift.SICK, 8)
        val result = calculator.getFoodAllowance(record)
        assertEquals(0.0, result, 0.01)
    }

    @Test
    fun `getFoodAllowance returns 0 for vacation shift`() {
        val record = DayRecord(2025, 1, 6, Shift.VACATION, 8)
        val result = calculator.getFoodAllowance(record)
        assertEquals(0.0, result, 0.01)
    }

    @Test
    fun `getFoodAllowance returns 0 for off shift`() {
        val record = DayRecord(2025, 1, 6, Shift.OFF, 0)
        val result = calculator.getFoodAllowance(record)
        assertEquals(0.0, result, 0.01)
    }

    @Test
    fun `getFoodAllowance returns foodPerDay for work shift`() {
        val record = DayRecord(2025, 1, 6, Shift.FIRST, 8)
        val result = calculator.getFoodAllowance(record)
        assertEquals(280.0, result, 0.01)
    }

    @Test
    fun `getFoodAllowance returns foodPerDay for any worked day under 4 hours`() {
        val record = DayRecord(2025, 1, 6, Shift.FIRST, 3)
        val result = calculator.getFoodAllowance(record)
        assertEquals(280.0, result, 0.01)
    }

    @Test
    fun `getFoodAllowance returns foodPerDay for exactly 4 hours`() {
        val record = DayRecord(2025, 1, 6, Shift.FIRST, 4)
        val result = calculator.getFoodAllowance(record)
        assertEquals(280.0, result, 0.01)
    }

    @Test
    fun `getFoodAllowance returns foodPerDay for exactly 10 hours`() {
        val record = DayRecord(
            2025, 1, 6, Shift.FIRST, 8,
            shift2 = Shift.SECOND,
            hours2 = 2,
            overtimeType = OvertimeType.PAID
        )
        val result = calculator.getFoodAllowance(record)
        assertEquals(280.0, result, 0.01)
    }

    @Test
    fun `getFoodAllowance returns double foodPerDay for 11 hours`() {
        val record = DayRecord(
            2025, 1, 6, Shift.FIRST, 8,
            shift2 = Shift.SECOND,
            hours2 = 3,
            overtimeType = OvertimeType.PAID
        )
        val result = calculator.getFoodAllowance(record)
        assertEquals(560.0, result, 0.01)
    }

    @Test
    fun `getFoodAllowance returns double foodPerDay for 11 hours on weekend`() {
        val record = DayRecord(
            2025, 1, 4, Shift.FIRST, 11,
            overtimeType = OvertimeType.PAID
        )
        val result = calculator.getFoodAllowance(record)
        assertEquals(560.0, result, 0.01)
    }

    // ============= getNightHours tests =============

    @Test
    fun `getNightHours returns hours for third shift`() {
        val record = DayRecord(2025, 1, 6, Shift.THIRD, 8)
        val result = calculator.getNightHours(record)
        assertEquals(8, result)
    }

    @Test
    fun `getNightHours returns 0 for first shift`() {
        val record = DayRecord(2025, 1, 6, Shift.FIRST, 8)
        val result = calculator.getNightHours(record)
        assertEquals(0, result)
    }

    @Test
    fun `getNightHours returns 0 for second shift`() {
        val record = DayRecord(2025, 1, 6, Shift.SECOND, 8)
        val result = calculator.getNightHours(record)
        assertEquals(0, result)
    }

    // ============= calculateDailyPay tests =============

    @Test
    fun `calculateDailyPay returns 0 for off day on weekend`() {
        val record = DayRecord(2025, 1, 4, Shift.OFF, 0) // Saturday
        val result = calculator.calculateDailyPay(record, 300.0, BrigadeType.TROBRIGADA)
        assertEquals(0.0, result, 0.01)
    }

    @Test
    fun `calculateDailyPay returns sick pay for sick weekday`() {
        val record = DayRecord(2025, 1, 6, Shift.SICK, 8) // Monday
        val result = calculator.calculateDailyPay(record, 300.0, BrigadeType.TROBRIGADA)
        // 8 * 300 * 0.65 = 1560
        assertEquals(1560.0, result, 0.01)
    }

    @Test
    fun `vacation uses resolved vacation hourly base`() {
        val record = DayRecord(2026, 1, 5, Shift.VACATION, hours = 8)
        val bases = ResolvedPayBases.fromDefaults(
            PayBaseDefaults(
                vacationHourlyBase = 912.87,
                paidHolidayHourlyBase = 912.87,
                sickHourlyBase = 918.81
            )
        )

        val pay = calculator.calculateDailyPay(
            record,
            baseRate = 637.32,
            brigadeType = BrigadeType.TROBRIGADA,
            payBases = bases
        )

        assertEquals(7_302.96, pay, 0.01)
    }

    @Test
    fun `sick pay uses resolved sick hourly base and sick rate`() {
        val record = DayRecord(2026, 3, 2, Shift.SICK, hours = 8, sickPayRate = 0.65)
        val bases = ResolvedPayBases.fromDefaults(
            PayBaseDefaults(
                vacationHourlyBase = 912.87,
                paidHolidayHourlyBase = 912.87,
                sickHourlyBase = 918.81
            )
        )

        val pay = calculator.calculateDailyPay(
            record,
            baseRate = 637.32,
            brigadeType = BrigadeType.TROBRIGADA,
            payBases = bases
        )

        assertEquals(4_777.81, pay, 0.01)
    }

    @Test
    fun `paid holiday off workday uses resolved paid holiday hourly base`() {
        val record = DayRecord(2026, 1, 7, Shift.OFF, hours = 0)
        val bases = ResolvedPayBases.fromDefaults(
            PayBaseDefaults(
                vacationHourlyBase = 912.87,
                paidHolidayHourlyBase = 912.87,
                sickHourlyBase = 918.81
            )
        )

        val pay = calculator.calculateDailyPay(
            record,
            baseRate = 637.32,
            brigadeType = BrigadeType.TROBRIGADA,
            payBases = bases
        )

        assertEquals(7_302.96, pay, 0.01)
    }

    @Test
    fun `calculateMonthSummary koristi posebnu osnovicu za bolovanje`() {
        val sickCalculator = PayCalculator(config.copy(sickPayBaseHourly = 918.805))
        val records = (3..7).map { day ->
            DayRecord(2025, 3, day, Shift.SICK, 8)
        }

        val result = sickCalculator.calculateMonthSummary(
            records,
            637.32,
            summaryYear = 2025,
            summaryMonth = 3
        )

        assertEquals(40, result.sickHours)
        assertEquals(23_888.93, result.totalPay, 0.01)
    }

    @Test
    fun `calculateDailyPay returns 0 for sick weekend`() {
        val record = DayRecord(2025, 1, 4, Shift.SICK, 8) // Saturday
        val result = calculator.calculateDailyPay(record, 300.0, BrigadeType.TROBRIGADA)
        assertEquals(0.0, result, 0.01)
    }

    @Test
    fun `calculateDailyPay returns vacation pay for vacation weekday`() {
        val record = DayRecord(2025, 1, 6, Shift.VACATION, 8) // Monday
        val result = calculator.calculateDailyPay(record, 300.0, BrigadeType.TROBRIGADA)
        // 8 * 300 * 1.0 = 2400
        assertEquals(2400.0, result, 0.01)
    }

    @Test
    fun `calculateDailyPay calculates regular first shift weekday`() {
        val record = DayRecord(2025, 1, 6, Shift.FIRST, 8) // Monday
        val result = calculator.calculateDailyPay(record, 300.0, BrigadeType.TROBRIGADA)
        // 8 * 300 = 2400
        assertEquals(2400.0, result, 0.01)
    }

    @Test
    fun `calculateDailyPay calculates third shift with night premium`() {
        val record = DayRecord(2025, 1, 6, Shift.THIRD, 8) // Monday
        val result = calculator.calculateDailyPay(record, 300.0, BrigadeType.TROBRIGADA)
        // 8 * 300 * 1.41 = 3384
        assertEquals(3384.0, result, 0.01)
    }

    @Test
    fun `calculateDailyPay calculates overtime on weekday`() {
        val record = DayRecord(2025, 1, 6, Shift.FIRST, 10) // Monday, 10 hours
        val result = calculator.calculateDailyPay(record, 300.0, BrigadeType.TROBRIGADA)
        // Regular: 8 * 300 = 2400
        // Overtime: 2 * 300 * 1.41 = 846
        // Total: 3246
        assertEquals(3246.0, result, 0.01)
    }

    @Test
    fun `calculateDailyPay calculates weekend work with overtime premium`() {
        val record = DayRecord(2025, 1, 4, Shift.FIRST, 8) // Saturday
        val result = calculator.calculateDailyPay(record, 300.0, BrigadeType.TROBRIGADA)
        // 8 * 300 * 1.41 = 3384
        assertEquals(3384.0, result, 0.01)
    }

    @Test
    fun `calculateDailyPay calculates Sunday with Sunday bonus`() {
        val record = DayRecord(2025, 1, 5, Shift.FIRST, 8) // Sunday
        val result = calculator.calculateDailyPay(record, 300.0, BrigadeType.TROBRIGADA)
        // 8 * 300 * (1 + 0.41 + 0.25) = 3984
        assertEquals(3984.0, result, 0.01)
    }

    @Test
    fun `calculateDailyPay subotu na praznik ne uvecava kao prekovremeno`() {
        val record = DayRecord(
            2026, 5, 2, Shift.FIRST, 8, // Subota, 2. maj
            holidayOverride = true
        )
        val result = calculator.calculateDailyPay(record, 468.46, BrigadeType.TROBRIGADA)
        // 468.46 * 8 * (1 + 1.23) = 8357.3264
        assertEquals(8357.3264, result, 0.01)
    }

    @Test
    fun `calculateDailyPay racuna pet sati subote na praznik bez prekovremenog dodatka`() {
        val record = DayRecord(
            2026, 5, 2, Shift.FIRST, 5,
            holidayOverride = true
        )
        val result = calculator.calculateDailyPay(record, 637.32, BrigadeType.TROBRIGADA)
        // 5 * 637.32 * (1 + 1.23) = 7106.118
        assertEquals(7_106.118, result, 0.01)
    }

    @Test
    fun `calculateDailyPay uses custom sick pay rate`() {
        val record = DayRecord(2025, 1, 6, Shift.SICK, 8, sickPayRate = 1.0) // Monday
        val result = calculator.calculateDailyPay(record, 300.0, BrigadeType.TROBRIGADA)
        // 8 * 300 * 1.0 = 2400
        assertEquals(2400.0, result, 0.01)
    }

    // ============= calculateMonthSummary tests =============

    @Test
    fun `calculateMonthSummary returns empty summary for empty records`() {
        val result = calculator.calculateMonthSummary(emptyList(), 300.0)
        assertEquals(0, result.workHours)
        assertEquals(0.0, result.totalPay, 0.01)
    }

    @Test
    fun `calculateMonthSummary calculates work hours correctly`() {
        val records = listOf(
            DayRecord(2025, 1, 6, Shift.FIRST, 8), // Monday
            DayRecord(2025, 1, 7, Shift.FIRST, 8), // Tuesday
            DayRecord(2025, 1, 8, Shift.FIRST, 8)  // Wednesday
        )
        val result = calculator.calculateMonthSummary(records, 300.0)
        assertEquals(24, result.workHours)
        assertEquals(3, result.workDays)
    }

    @Test
    fun `full regular day ignores shortage minutes`() {
        val result = calculator.calculateMonthSummary(
            records = listOf(DayRecord(2025, 3, 3, Shift.FIRST, 8, regularShortageMinutes = 15)),
            baseRate = 300.0,
            summaryYear = 2025,
            summaryMonth = 3
        )

        assertEquals(480, result.workMinutes)
        assertEquals(0, result.compTimeDeficitMinutes)
        assertEquals(0, result.compTimeEndingMinutes)
        assertEquals("0h", formatHoursAndMinutes(result.compTimeEndingMinutes, showPlusForPositive = true))
        assertEquals(2_400.0, result.totalPay, 0.01)
    }

    @Test
    fun `six regular hours with minutes count as expected effective minutes`() {
        val fifteen = DayRecord(2025, 3, 3, Shift.FIRST, 6, regularShortageMinutes = 15)
        val thirty = DayRecord(2025, 3, 4, Shift.FIRST, 6, regularShortageMinutes = 30)
        val fortyFive = DayRecord(2025, 3, 5, Shift.FIRST, 6, regularShortageMinutes = 45)

        assertEquals(375, fifteen.effectiveRegularWorkMinutes)
        assertEquals(390, thirty.effectiveRegularWorkMinutes)
        assertEquals(405, fortyFive.effectiveRegularWorkMinutes)
    }

    @Test
    fun `minus thirteen hours plus full day with minutes preserves balance`() {
        val result = calculator.calculateMonthSummary(
            records = listOf(DayRecord(2025, 3, 3, Shift.FIRST, 8, regularShortageMinutes = 45)),
            baseRate = 300.0,
            startingCompBalance = -13,
            summaryYear = 2025,
            summaryMonth = 3
        )

        assertEquals(-780, result.compTimeEndingMinutes)
        assertEquals("-13h", formatHoursAndMinutes(result.compTimeEndingMinutes, showPlusForPositive = true))
    }

    @Test
    fun `full regular day minutes are ignored with paid overtime shift`() {
        val result = calculator.calculateMonthSummary(
            records = listOf(
                DayRecord(
                    2025, 3, 3, Shift.FIRST, 8,
                    shift2 = Shift.SECOND,
                    hours2 = 4,
                    overtimeType = OvertimeType.PAID,
                    regularShortageMinutes = 30
                )
            ),
            baseRate = 300.0,
            summaryYear = 2025,
            summaryMonth = 3
        )

        assertEquals(4, result.overtimeHours)
        assertEquals(720, result.workMinutes)
    }

    @Test
    fun `calculateMonthSummary calculates sick hours correctly`() {
        val records = listOf(
            DayRecord(2025, 1, 6, Shift.SICK, 8), // Monday
            DayRecord(2025, 1, 7, Shift.SICK, 8)  // Tuesday
        )
        val result = calculator.calculateMonthSummary(records, 300.0)
        assertEquals(16, result.sickHours)
        assertEquals(0, result.workHours)
    }

    @Test
    fun `calculateMonthSummary calculates vacation hours correctly`() {
        val records = listOf(
            DayRecord(2025, 1, 6, Shift.VACATION, 8), // Monday
            DayRecord(2025, 1, 7, Shift.VACATION, 8)  // Tuesday
        )
        val result = calculator.calculateMonthSummary(records, 300.0)
        assertEquals(16, result.vacationHours)
        assertEquals(0, result.workHours)
    }

    @Test
    fun `calculateMonthSummary calculates food allowance correctly`() {
        val records = listOf(
            DayRecord(2025, 1, 6, Shift.FIRST, 8), // Monday
            DayRecord(2025, 1, 7, Shift.FIRST, 8), // Tuesday
            DayRecord(2025, 1, 8, Shift.SICK, 8)   // Wednesday - no food
        )
        val result = calculator.calculateMonthSummary(records, 300.0)
        // 2 work days * 280 = 560
        assertEquals(560.0, result.foodAllowance, 0.01)
    }

    @Test
    fun `calculateMonthSummary calculates food allowance by worked day and overtime threshold`() {
        val records = listOf(
            DayRecord(2025, 1, 6, Shift.FIRST, 3),
            DayRecord(
                2025, 1, 7, Shift.FIRST, 8,
                shift2 = Shift.SECOND,
                hours2 = 2,
                overtimeType = OvertimeType.PAID
            ),
            DayRecord(
                2025, 1, 8, Shift.FIRST, 8,
                shift2 = Shift.SECOND,
                hours2 = 3,
                overtimeType = OvertimeType.PAID
            ),
            DayRecord(
                2025, 1, 11, Shift.FIRST, 11,
                overtimeType = OvertimeType.PAID
            )
        )

        val result = calculator.calculateMonthSummary(records, 300.0)

        assertEquals(1_680.0, result.foodAllowance, 0.01)
    }

    @Test
    fun `calculateMonthSummary calculates minuli bonus correctly`() {
        val records = listOf(
            DayRecord(2025, 3, 3, Shift.FIRST, 8) // Monday
        )
        val result = calculator.calculateMonthSummary(
            records,
            300.0,
            yearsOfService = 10
        )
        // Pay: 2400, minuliRate: 10 * 0.005 = 0.05
        // Bonus: 2400 * 0.05 = 120
        assertEquals(120.0, result.minuliRadBonus, 0.01)
    }

    @Test
    fun `calculateMonthSummary adds difficult work conditions to actual worked gross only`() {
        val localCalculator = PayCalculator(
            config.copy(
                difficultWorkConditionsPercent = 35.0,
                vacationPayBaseHourly = 700.0,
                paidHolidayPayBaseHourly = 700.0,
                sickPayBaseHourly = 700.0
            )
        )
        val payBases = ResolvedPayBases.fromDefaults(
            PayBaseDefaults(
                vacationHourlyBase = 700.0,
                paidHolidayHourlyBase = 700.0,
                sickHourlyBase = 700.0
            )
        )
        val records = listOf(
            DayRecord(2026, 3, 2, Shift.FIRST, 8),
            DayRecord(2026, 3, 3, Shift.SICK, 8),
            DayRecord(2026, 3, 4, Shift.VACATION, 8)
        )

        val result = localCalculator.calculateMonthSummary(
            records = records,
            baseRate = 700.0,
            summaryYear = 2026,
            summaryMonth = 3,
            payBases = payBases
        )

        assertEquals(1_960.0, result.difficultWorkConditionsBonus, 0.01)
        assertEquals(16_800.0, result.totalPay, 0.01)
    }

    @Test
    fun `calculateMonthSummary matches difficult work conditions example`() {
        val localCalculator = PayCalculator(config.copy(difficultWorkConditionsPercent = 35.0))
        val records = (2..27)
            .mapNotNull { day ->
                val record = DayRecord(2026, 3, day, Shift.FIRST, 8)
                if (record.weekday.isWorkday) record else null
            }

        val result = localCalculator.calculateMonthSummary(
            records = records,
            baseRate = 700.0,
            summaryYear = 2026,
            summaryMonth = 3
        )

        assertEquals(160, result.workHours)
        assertEquals(39_200.0, result.difficultWorkConditionsBonus, 0.01)
        assertEquals(151_200.0, result.totalPay, 0.01)
    }

    @Test
    fun `calculateMonthSummary ne racuna minuli na prekovremene minute`() {
        val records = listOf(
            DayRecord(
                2025, 3, 3, Shift.THIRD, 8, // Monday, nocni dodatak
                shift2 = Shift.FIRST,
                hours2 = 4,
                overtimeType = OvertimeType.PAID
            )
        )

        val result = calculator.calculateMonthSummary(
            records,
            300.0,
            yearsOfService = 10
        )

        assertEquals(5_076.0, result.totalPay, 0.01)
        assertEquals(480, result.seniorityEligibleWorkMinutes)
        assertEquals(120.0, result.minuliRadBonus, 0.01)
    }

    @Test
    fun `calculateDailySeniorityBonus koristi osnovnu zaradu bez prekovremenih minuta i dodataka`() {
        val record = DayRecord(
            2025, 3, 3, Shift.THIRD, 8,
            shift2 = Shift.FIRST,
            hours2 = 4,
            overtimeType = OvertimeType.PAID
        )

        val result = calculator.calculateDailySeniorityBonus(
            record = record,
            baseRate = 300.0,
            yearsOfService = 10
        )

        assertEquals(120.0, result, 0.01)
    }

    @Test
    fun `minuli rad za maj ukljucuje jedan sat pokrivenog manjka ali ne i sedam sati izostanka`() {
        val regularFullDays = listOf(
            4, 5, 6, 7, 8,
            11, 12, 13, 14, 15,
            18, 19, 20, 21, 22,
            25, 26, 27
        )
        val records = regularFullDays.map { day ->
            DayRecord(2026, 5, day, Shift.FIRST, 8, holidayOverride = false)
        } + listOf(
            DayRecord(2026, 5, 9, Shift.FIRST, 6, holidayOverride = false),
            DayRecord(2026, 5, 28, Shift.VACATION, 8, holidayOverride = false),
            DayRecord(2026, 5, 29, Shift.VACATION, 8, holidayOverride = false),
            DayRecord(2026, 5, 1, Shift.OFF, 0, holidayOverride = false)
        )

        val result = calculator.calculateMonthSummary(
            records = records,
            baseRate = 637.32,
            startingCompBalanceMinutes = 60,
            yearsOfService = 7,
            brigadeType = BrigadeType.TROBRIGADA,
            summaryYear = 2026,
            summaryMonth = 5
        )

        assertEquals(6, result.overtimeHours)
        assertEquals(16, result.vacationHours)
        assertEquals(480, result.compTimeDeficitMinutes)
        assertEquals(-420, result.compTimeEndingMinutes)
        assertEquals(145 * 60, result.seniorityEligibleWorkMinutes)
        assertEquals(3_234.40, result.minuliRadBonus, 0.01)
    }

    @Test
    fun `trinaest sati rada na praznik ulazi u osnovicu minulog rada`() {
        val result = calculator.calculateMonthSummary(
            records = listOf(
                DayRecord(2026, 5, 1, Shift.FIRST, 8),
                DayRecord(2026, 5, 2, Shift.SECOND, 5)
            ),
            baseRate = 637.32,
            yearsOfService = 7,
            brigadeType = BrigadeType.TROBRIGADA,
            summaryYear = 2026,
            summaryMonth = 5
        )

        assertEquals(13 * 60, result.seniorityEligibleWorkMinutes)
        assertEquals(289.98, result.minuliRadBonus, 0.01)
    }

    @Test
    fun `calculateMonthSummary calculates night hours correctly`() {
        val records = listOf(
            DayRecord(2025, 1, 6, Shift.THIRD, 8), // Monday night
            DayRecord(2025, 1, 7, Shift.FIRST, 8)  // Tuesday day
        )
        val result = calculator.calculateMonthSummary(records, 300.0)
        assertEquals(8, result.nightHours)
    }

    @Test
    fun `calculateMonthlyPayDetails razbija nocne smene bruto i neto`() {
        val records = (3..14).filter { day ->
            DayRecord(2025, 3, day, Shift.THIRD, 8).weekday.isWorkday
        }.take(10).map { day ->
            DayRecord(2025, 3, day, Shift.THIRD, 8)
        }
        val summary = calculator.calculateMonthSummary(
            records,
            300.0,
            yearsOfService = 10,
            summaryYear = 2025,
            summaryMonth = 3
        )
        val netBreakdown = calculateMonthlyNetPayBreakdown(
            summary = summary,
            grossHourlyRate = 300.0,
            monthlyFundHours = 168,
            contributionsPercent = 20.0,
            taxRelief = 0.0,
            taxPercent = 10.0
        )

        val details = calculator.calculateMonthlyPayDetails(
            records = records,
            summary = summary,
            baseRate = 300.0,
            netBreakdown = netBreakdown,
            brigadeType = BrigadeType.TROBRIGADA
        )

        assertEquals(80, details.totalPaidHours)
        assertEquals(24_000.0, details.grossBaseRegular, 0.01)
        assertEquals(9_840.0, details.grossNightPremium, 0.01)
        assertEquals(1_200.0, details.grossSeniorityBonus, 0.01)
        assertEquals(4_000.0, details.grossMealAddition, 0.01)
        assertEquals(39_040.0, details.grossTotalWithSeniority, 0.01)
        assertEquals(netBreakdown.displayPay, details.netTotal, 0.01)
    }

    @Test
    fun `calculateMonthlyPayDetails racuna neto propustenu zaradu za nepokrivene izostanke`() {
        val records = listOf(
            DayRecord(2025, 3, 3, Shift.FIRST, 6)
        )
        val summary = calculator.calculateMonthSummary(
            records,
            300.0,
            summaryYear = 2025,
            summaryMonth = 3
        )
        val netBreakdown = calculateMonthlyNetPayBreakdown(
            summary = summary,
            grossHourlyRate = 300.0,
            monthlyFundHours = 168,
            contributionsPercent = 20.0,
            taxRelief = 0.0,
            taxPercent = 10.0
        )

        val details = calculator.calculateMonthlyPayDetails(
            records = records,
            summary = summary,
            baseRate = 300.0,
            netBreakdown = netBreakdown,
            brigadeType = BrigadeType.TROBRIGADA
        )

        assertEquals(2, details.uncoveredDeficitHours)
        assertEquals(600.0, details.grossUncoveredDeficitLoss, 0.01)
        assertEquals(420.0, details.netUncoveredDeficitLoss, 0.01)
    }

    @Test
    fun `calculateMonthlyPayDetails prikazuje delimicni minutni deficit kao bruto gubitak`() {
        // Radnik je odradio 7h + 30 min (effective 450 min); default je 480 min, pa je deficit 30 min.
        // summary.compTimeDeficit (Int sati) = 0 jer 30/60 = 0, ali compTimeDeficitMinutes = 30.
        // Detail red mora prikazati pravi RSD gubitak da bi zbir redova matchovao ukupnu plaću.
        val records = listOf(
            DayRecord(2025, 3, 3, Shift.FIRST, 7, regularShortageMinutes = 30)
        )
        val summary = calculator.calculateMonthSummary(
            records,
            300.0,
            summaryYear = 2025,
            summaryMonth = 3
        )
        assertEquals(30, summary.compTimeDeficitMinutes)
        assertEquals(0, summary.compTimeDeficit)

        val netBreakdown = calculateMonthlyNetPayBreakdown(
            summary = summary,
            grossHourlyRate = 300.0,
            monthlyFundHours = 168,
            contributionsPercent = 20.0,
            taxRelief = 0.0,
            taxPercent = 10.0
        )

        val details = calculator.calculateMonthlyPayDetails(
            records = records,
            summary = summary,
            baseRate = 300.0,
            netBreakdown = netBreakdown,
            brigadeType = BrigadeType.TROBRIGADA
        )

        // 30 min × 300 RSD/h = 150 RSD gubitka, mora se videti u detalj redovima
        assertEquals(150.0, details.grossUncoveredDeficitLoss, 0.01)
    }

    @Test
    fun `calculateMonthlyPayDetails koristi minute za bruto obicne sate`() {
        val records = listOf(
            DayRecord(2025, 3, 3, Shift.FIRST, 7, regularShortageMinutes = 30)
        )
        val summary = calculator.calculateMonthSummary(
            records,
            300.0,
            summaryYear = 2025,
            summaryMonth = 3
        )
        val netBreakdown = calculateMonthlyNetPayBreakdown(
            summary = summary,
            grossHourlyRate = 300.0,
            monthlyFundHours = 168,
            contributionsPercent = 20.0,
            taxRelief = 0.0,
            taxPercent = 10.0
        )

        val details = calculator.calculateMonthlyPayDetails(
            records = records,
            summary = summary,
            baseRate = 300.0,
            netBreakdown = netBreakdown,
            brigadeType = BrigadeType.TROBRIGADA
        )

        assertEquals(2_250.0, details.grossBaseRegular, 0.01)
    }

    @Test
    fun `calculateMonthlyPayDetails zaokruzuje broj neupisanih praznika kod decimalne baze`() {
        val payBases = ResolvedPayBases.fromDefaults(
            PayBaseDefaults(
                vacationHourlyBase = 300.0,
                paidHolidayHourlyBase = 0.7,
                sickHourlyBase = 300.0
            )
        )
        val records = listOf(
            DayRecord(2025, 1, 8, Shift.FIRST, 8)
        )
        val summary = calculator.calculateMonthSummary(
            records = records,
            baseRate = 300.0,
            summaryYear = 2025,
            summaryMonth = 1,
            payBases = payBases
        )
        val netBreakdown = calculateMonthlyNetPayBreakdown(
            summary = summary,
            grossHourlyRate = 300.0,
            monthlyFundHours = 184,
            contributionsPercent = 20.0,
            taxRelief = 0.0,
            taxPercent = 10.0
        )

        val details = calculator.calculateMonthlyPayDetails(
            records = records,
            summary = summary,
            baseRate = 300.0,
            netBreakdown = netBreakdown,
            brigadeType = BrigadeType.TROBRIGADA,
            payBases = payBases
        )

        assertEquals(24, details.paidHolidayHours)
    }

    @Test
    fun `calculateMonthSummary tracks comp time balance`() {
        val records = listOf(
            DayRecord(
                2025, 1, 6, Shift.FIRST, 8,
                shift2 = Shift.SECOND,
                hours2 = 4,
                overtimeType = OvertimeType.COMP_TIME
            )
        )
        val result = calculator.calculateMonthSummary(
            records,
            300.0,
            startingCompBalance = 10
        )
        assertEquals(10, result.compTimeStarting)
        assertEquals(4, result.compTimeEarned)
        assertEquals(14, result.compTimeEnding)
    }

    @Test
    fun `calculateMonthSummary primary extra hours marked comp time only reduce balance`() {
        val result = calculator.calculateMonthSummary(
            records = listOf(
                DayRecord(
                    2025, 3, 3, Shift.FIRST, 12,
                    overtimeType = OvertimeType.COMP_TIME
                )
            ),
            baseRate = 300.0,
            startingCompBalance = -13,
            summaryYear = 2025,
            summaryMonth = 3
        )

        assertEquals(-13, result.compTimeStarting)
        assertEquals(4, result.compTimeEarned)
        assertEquals(-9, result.compTimeEnding)
        assertEquals(0, result.overtimeHours)
        assertEquals(2_400.0, result.totalPay, 0.01)
    }

    @Test
    fun `calculateMonthSummary cetvorobrigada rucni off pravi minus za slobodan radnim danom`() {
        val result = calculator.calculateMonthSummary(
            records = listOf(DayRecord(2026, 5, 4, Shift.OFF, 0)),
            baseRate = 300.0,
            brigadeType = BrigadeType.fromValue("cetv"),
            summaryYear = 2026,
            summaryMonth = 5
        )

        assertEquals(CONFIG.pay.defaultHours, result.compTimeDeficit)
        assertEquals(-CONFIG.pay.defaultHours, result.compTimeNet)
    }

    @Test
    fun `calculateMonthSummary cetvorobrigada planski off ne pravi minus`() {
        val result = calculator.calculateMonthSummary(
            records = listOf(DayRecord(2026, 5, 4, Shift.OFF, 0, balanceExempt = true)),
            baseRate = 300.0,
            brigadeType = BrigadeType.fromValue("cetv"),
            summaryYear = 2026,
            summaryMonth = 5
        )

        assertEquals(0, result.compTimeDeficit)
        assertEquals(0, result.compTimeNet)
    }

    @Test
    fun `getOvertimeHours cetvorobrigada vikend smena nije automatski prekovremena`() {
        val record = DayRecord(2026, 5, 10, Shift.FIRST, 8)

        val result = calculator.getOvertimeHours(record, BrigadeType.fromValue("cetv"))

        assertEquals(0, result)
    }

    @Test
    fun `calculateMonthSummary cetvorobrigada ne racuna prekovremeno dok rad ne predje mesecni fond`() {
        val records = listOf(
            DayRecord(
                2026, 3, 2, Shift.FIRST, 8,
                shift2 = Shift.SECOND,
                hours2 = 4,
                overtimeType = OvertimeType.PAID
            ),
            DayRecord(2026, 3, 3, Shift.SECOND, 8)
        )

        val result = calculator.calculateMonthSummary(
            records = records,
            baseRate = 300.0,
            brigadeType = BrigadeType.CETVOROBRIGADA,
            summaryYear = 2026,
            summaryMonth = 3
        )

        assertEquals(20, result.workHours)
        assertEquals(0, result.overtimeHours)
        assertEquals(6_000.0, result.totalPay, 0.01)
    }

    @Test
    fun `calculateMonthSummary cetvorobrigada racuna samo sate preko mesecnog fonda kao prekovremene`() {
        val regularDays = listOf(
            2, 3, 4, 5, 6,
            9, 10, 11, 12, 13,
            16, 17, 18, 19, 20,
            23, 24, 25, 26, 27,
            30
        )
        val records = regularDays.map { day ->
            DayRecord(2026, 3, day, Shift.FIRST, 8)
        } + DayRecord(
            2026, 3, 31, Shift.FIRST, 8,
            shift2 = Shift.SECOND,
            hours2 = 8,
            overtimeType = OvertimeType.PAID
        )

        val result = calculator.calculateMonthSummary(
            records = records,
            baseRate = 300.0,
            brigadeType = BrigadeType.CETVOROBRIGADA,
            summaryYear = 2026,
            summaryMonth = 3
        )

        assertEquals(184, result.workHours)
        assertEquals(8, result.overtimeHours)
        assertEquals(56_184.0, result.totalPay, 0.01)
    }

    @Test
    fun `calculateMonthSummary cetvorobrigada placa nepun sat prekovremenog preko fonda`() {
        // Cetvorobrigada, mart 2026, fond = 176h (10560 min).
        // 20 punih 8h dana = 9600 min, + jedan 7h30 dan = 450 min -> kumulativ 10050 min (sve redovno).
        // Granicni dan (8h + 4h = 720 min): ostatak fonda 510 min redovno, 210 min (3h30) prekovremeno.
        // Bug je sekao 210 -> 3h (180 min), pa je 30 min gubilo 41% dodatak i placalo se kao redovno.
        val regularDays = listOf(2, 3, 4, 5, 6, 9, 10, 11, 12, 13, 16, 17, 18, 19, 20, 23, 24, 25, 26, 27)
        val records = regularDays.map { day -> DayRecord(2026, 3, day, Shift.FIRST, 8) } +
            DayRecord(2026, 3, 30, Shift.FIRST, 7, regularShortageMinutes = 30) +
            DayRecord(
                2026, 3, 31, Shift.FIRST, 8,
                shift2 = Shift.SECOND,
                hours2 = 4,
                overtimeType = OvertimeType.PAID
            )

        val summary = calculator.calculateMonthSummary(
            records = records,
            baseRate = 300.0,
            brigadeType = BrigadeType.CETVOROBRIGADA,
            summaryYear = 2026,
            summaryMonth = 3
        )

        assertEquals(3, summary.overtimeHours)
        // 48000 (20x8h) + 2250 (7h30) + 4030.5 (granicni dan sa 3h30 prekovremenog) = 54280.5
        assertEquals(54_280.5, summary.totalPay, 0.01)

        val netBreakdown = calculateMonthlyNetPayBreakdown(
            summary = summary,
            grossHourlyRate = 300.0,
            monthlyFundHours = 176,
            contributionsPercent = 20.0,
            taxRelief = 0.0,
            taxPercent = 10.0
        )
        val details = calculator.calculateMonthlyPayDetails(
            records = records,
            summary = summary,
            baseRate = 300.0,
            netBreakdown = netBreakdown,
            brigadeType = BrigadeType.CETVOROBRIGADA
        )

        assertEquals(210, details.overtimeMinutes)
        // 210 min / 60 * 300 * 0.41 = 430.5
        assertEquals(430.5, details.grossOvertimePremium, 0.01)
    }

    // ============= Holiday pay =============

    @Test
    fun `calculateDailyPay praznik TROBRIGADA radni dan`() {
        // 7. januar 2025 je utorak (Božić)
        val record = DayRecord(2025, 1, 7, Shift.FIRST, 8)
        val result = calculator.calculateDailyPay(record, 300.0, BrigadeType.TROBRIGADA)
        // Regularno: 8 * 300 = 2400
        // Holiday bonus: 2400 * 1.23 = 2952
        // Ukupno: 2400 + 2952 = 5352
        assertEquals(5352.0, result, 0.01)
    }

    @Test
    fun `calculateDailyPay slobodan dan na praznik radnog dana`() {
        // 7. januar 2025 je utorak (Božić), OFF smena
        val record = DayRecord(2025, 1, 7, Shift.OFF, 0)
        val result = calculator.calculateDailyPay(record, 300.0, BrigadeType.TROBRIGADA)
        // OFF na praznik radnog dana: 8 * 300 = 2400
        assertEquals(2400.0, result, 0.01)
    }

    // ============= Treća smena + nedelja + praznik kombinacija =============

    @Test
    fun `calculateDailyPay treća smena nedelja TROBRIGADA`() {
        val record = DayRecord(2025, 1, 5, Shift.THIRD, 8) // Nedelja
        val result = calculator.calculateDailyPay(record, 300.0, BrigadeType.TROBRIGADA)
        // 6 sati: 6 * 300 * (1 + 0.41 + 0.41) = 3276
        // 2 sata nedelje: 2 * 300 * (1 + 0.41 + 0.41 + 0.25) = 1242
        // Ukupno: 4518
        assertEquals(4518.0, result, 0.01)
    }

    @Test
    fun `calculateDailyPay treća smena subota TROBRIGADA`() {
        val record = DayRecord(2025, 1, 4, Shift.THIRD, 8) // Subota
        val result = calculator.calculateDailyPay(record, 300.0, BrigadeType.TROBRIGADA)
        // 2 sata: 2 * 300 * (1 + 0.41 + 0.41) = 1092
        // 6 sati nedelje: 6 * 300 * (1 + 0.41 + 0.41 + 0.25) = 3726
        // Ukupno: 4818
        assertEquals(4818.0, result, 0.01)
    }

    // ============= COMP_TIME + Holiday kombinacija =============

    @Test
    fun `calculateMonthSummary comp time vikend sa praznikom`() {
        // 1. januar 2025 je sreda - koristimo 2. februar koji je nedelja u 2025
        // Zapravo, koristimo zapis sa holidayOverride za testiranje
        val records = listOf(
            DayRecord(
                2025, 1, 5, Shift.FIRST, 8, // Nedelja
                weekendOvertimeType = OvertimeType.COMP_TIME,
                holidayOverride = true
            )
        )
        val result = calculator.calculateMonthSummary(
            records,
            300.0,
            brigadeType = BrigadeType.TROBRIGADA
        )
        // Vikend comp time: sati idu u comp time, ne u platu
        assertEquals(8, result.compTimeEarned)
    }

    // ============= Double shift plata testovi =============

    @Test
    fun `calculateDailyPay dupla smena radni dan`() {
        val record = DayRecord(
            2025, 1, 6, Shift.FIRST, 8, // Ponedeljak
            shift2 = Shift.SECOND,
            hours2 = 4,
            overtimeType = OvertimeType.PAID
        )
        val result = calculator.calculateDailyPay(record, 300.0, BrigadeType.TROBRIGADA)
        // Regularno: 8 * 300 = 2400 (prva smena)
        // Overtime: 4 * 300 * 1.41 = 1692 (druga smena, svi sati prekovremeni)
        // Ukupno: 4092
        assertEquals(4092.0, result, 0.01)
    }

    @Test
    fun `calculateDailyPay dupla smena comp time ne ide u platu`() {
        val record = DayRecord(
            2025, 1, 6, Shift.FIRST, 8, // Ponedeljak
            shift2 = Shift.SECOND,
            hours2 = 4,
            overtimeType = OvertimeType.COMP_TIME
        )
        val result = calculator.calculateDailyPay(record, 300.0, BrigadeType.TROBRIGADA)
        // Samo prva smena: 8 * 300 = 2400
        assertEquals(2400.0, result, 0.01)
    }

    // ============= Neupisani praznici =============

    @Test
    fun `calculateUnrecordedHolidayPay računa neupisane praznike`() {
        // Januar 2025: 1. i 2. su praznici (sreda i četvrtak), 7. je Božić (utorak)
        val recordedDays = setOf(7) // Samo Božić je upisan
        val result = calculator.calculateUnrecordedHolidayPay(
            2025, 1, recordedDays, 300.0, BrigadeType.TROBRIGADA
        )
        // Neupisani praznici na radnim danima: 1. jan (sreda), 2. jan (četvrtak)
        // 2 * 8 * 300 = 4800
        assertEquals(4800.0, result, 0.01)
    }

    @Test
    fun `calculateUnrecordedHolidayPay ne računa vikend praznike za TROBRIGADA`() {
        // Februar 2025: 15. je subota, 16. nedelja
        val recordedDays = emptySet<Int>()
        val result = calculator.calculateUnrecordedHolidayPay(
            2025, 2, recordedDays, 300.0, BrigadeType.TROBRIGADA
        )
        // 15. feb subota - nije radni dan, preskače se
        // 16. feb nedelja - nije radni dan, preskače se
        // 17. feb ponedeljak - jeste radni dan: 8 * 300 = 2400
        assertEquals(2400.0, result, 0.01)
    }

    // ============= Weekend comp time testovi =============

    @Test
    fun `getOvertimeHours vikend comp time ne broji prvu smenu`() {
        val record = DayRecord(
            2025, 1, 4, Shift.FIRST, 8, // Subota
            weekendOvertimeType = OvertimeType.COMP_TIME
        )
        val result = calculator.getOvertimeHours(record, BrigadeType.TROBRIGADA)
        // Weekend comp time: prva smena ne ide u overtime
        assertEquals(0, result)
    }

    @Test
    fun `getSundayHours vikend comp time ne broji prvu smenu`() {
        val record = DayRecord(
            2025, 1, 5, Shift.FIRST, 8, // Nedelja
            weekendOvertimeType = OvertimeType.COMP_TIME
        )
        val result = calculator.getSundayHours(record, BrigadeType.TROBRIGADA)
        assertEquals(0, result)
    }

    @Test
    fun `calculateDailyPay vikend comp time ne uključuje prvu smenu`() {
        val record = DayRecord(
            2025, 1, 4, Shift.FIRST, 8, // Subota
            weekendOvertimeType = OvertimeType.COMP_TIME
        )
        val result = calculator.calculateDailyPay(record, 300.0, BrigadeType.TROBRIGADA)
        // Weekend comp time: prva smena ne ide u platu
        assertEquals(0.0, result, 0.01)
    }
}
