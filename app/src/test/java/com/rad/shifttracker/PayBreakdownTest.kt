package com.rad.shifttracker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

@Suppress("LargeClass")
class PayBreakdownTest {

    @Test
    fun `unified manual pay base stores the same value for all compensation bases`() {
        val bases = MonthlyPayBases.fromUnifiedPayBase(
            year = 2026,
            month = 5,
            hourlyBase = 918.81
        )

        assertEquals(918.81, bases.vacationHourlyBase!!, 0.001)
        assertEquals(918.81, bases.paidHolidayHourlyBase!!, 0.001)
        assertEquals(918.81, bases.sickHourlyBase!!, 0.001)
        assertEquals(PayBaseSelectionMode.MANUAL, bases.selectionMode)
    }

    @Test
    fun `default pay bases use the shared compensation base`() {
        val bases = ResolvedPayBases.fromDefaults(PayBaseDefaults())

        assertEquals(918.81, bases.vacationHourlyBase.value, 0.001)
        assertEquals(918.81, bases.paidHolidayHourlyBase.value, 0.001)
        assertEquals(918.81, bases.sickHourlyBase.value, 0.001)
    }

    @Test
    fun `resolved pay bases use defaults when no monthly bases exist`() {
        val bases = resolvePayBasesForMonth(
            year = 2026,
            month = 5,
            monthlyBases = emptyMap(),
            defaults = PayBaseDefaults(
                vacationHourlyBase = 912.87,
                paidHolidayHourlyBase = 912.87,
                sickHourlyBase = 918.81
            )
        )

        assertEquals(912.87, bases.vacationHourlyBase.value, 0.001)
        assertEquals(PayBaseSource.DEFAULT, bases.vacationHourlyBase.source)
        assertEquals(912.87, bases.paidHolidayHourlyBase.value, 0.001)
        assertEquals(PayBaseSource.DEFAULT, bases.paidHolidayHourlyBase.source)
        assertEquals(918.81, bases.sickHourlyBase.value, 0.001)
        assertEquals(PayBaseSource.DEFAULT, bases.sickHourlyBase.source)
    }

    @Test
    fun `resolved pay bases use current month before last known`() {
        val bases = resolvePayBasesForMonth(
            year = 2026,
            month = 5,
            monthlyBases = mapOf(
                MonthKey(2026, 3) to MonthlyPayBases(2026, 3, vacationHourlyBase = 900.0),
                MonthKey(2026, 5) to MonthlyPayBases(2026, 5, vacationHourlyBase = 950.0)
            ),
            defaults = PayBaseDefaults()
        )

        assertEquals(950.0, bases.vacationHourlyBase.value, 0.001)
        assertEquals(PayBaseSource.CURRENT_MONTH, bases.vacationHourlyBase.source)
        assertEquals(2026, bases.vacationHourlyBase.sourceYear)
        assertEquals(5, bases.vacationHourlyBase.sourceMonth)
    }

    @Test
    fun `resolved pay bases use last known previous month`() {
        val bases = resolvePayBasesForMonth(
            year = 2026,
            month = 5,
            monthlyBases = mapOf(
                MonthKey(2026, 2) to MonthlyPayBases(2026, 2, sickHourlyBase = 910.0),
                MonthKey(2026, 4) to MonthlyPayBases(2026, 4, sickHourlyBase = 918.81),
                MonthKey(2026, 5) to MonthlyPayBases(
                    year = 2026,
                    month = 5,
                    selectionMode = PayBaseSelectionMode.MANUAL
                )
            ),
            defaults = PayBaseDefaults()
        )

        assertEquals(918.81, bases.sickHourlyBase.value, 0.001)
        assertEquals(PayBaseSource.LAST_KNOWN, bases.sickHourlyBase.source)
        assertEquals(2026, bases.sickHourlyBase.sourceYear)
        assertEquals(4, bases.sickHourlyBase.sourceMonth)
    }

    @Test
    fun `resolved pay bases can force default values despite last known bases`() {
        val bases = resolvePayBasesForMonth(
            year = 2026,
            month = 5,
            monthlyBases = mapOf(
                MonthKey(2026, 3) to MonthlyPayBases(
                    year = 2026,
                    month = 3,
                    vacationHourlyBase = 1_111.0,
                    paidHolidayHourlyBase = 1_222.0,
                    sickHourlyBase = 1_333.0,
                    selectionMode = PayBaseSelectionMode.MANUAL
                ),
                MonthKey(2026, 5) to MonthlyPayBases(
                    year = 2026,
                    month = 5,
                    selectionMode = PayBaseSelectionMode.DEFAULT_VALUES
                )
            ),
            defaults = PayBaseDefaults(
                vacationHourlyBase = 912.87,
                paidHolidayHourlyBase = 912.87,
                sickHourlyBase = 918.81
            )
        )

        assertEquals(912.87, bases.vacationHourlyBase.value, 0.01)
        assertEquals(PayBaseSource.DEFAULT, bases.vacationHourlyBase.source)
        assertEquals(912.87, bases.paidHolidayHourlyBase.value, 0.01)
        assertEquals(PayBaseSource.DEFAULT, bases.paidHolidayHourlyBase.source)
        assertEquals(918.81, bases.sickHourlyBase.value, 0.01)
        assertEquals(PayBaseSource.DEFAULT, bases.sickHourlyBase.source)
    }

    @Test
    fun `resolved pay bases force default values despite calculated base`() {
        val calculation = RollingPayBaseCalculation(
            hourlyBase = 1_025.25,
            grossTotal = 1_968_480.0,
            hoursTotal = 1_920,
            months = (1..12).map { month ->
                RollingPayBaseMonth(2025, month, grossForAverage = 164_040.0, hoursForAverage = 160)
            }
        )

        val bases = resolvePayBasesForMonth(
            year = 2026,
            month = 5,
            monthlyBases = mapOf(
                MonthKey(2026, 5) to MonthlyPayBases(
                    year = 2026,
                    month = 5,
                    selectionMode = PayBaseSelectionMode.DEFAULT_VALUES
                )
            ),
            defaults = PayBaseDefaults(
                vacationHourlyBase = 912.87,
                paidHolidayHourlyBase = 912.87,
                sickHourlyBase = 918.81
            ),
            calculatedBase = calculation
        )

        assertEquals(912.87, bases.vacationHourlyBase.value, 0.01)
        assertEquals(PayBaseSource.DEFAULT, bases.vacationHourlyBase.source)
        assertNull(bases.vacationHourlyBase.calculation)
        assertEquals(912.87, bases.paidHolidayHourlyBase.value, 0.01)
        assertEquals(PayBaseSource.DEFAULT, bases.paidHolidayHourlyBase.source)
        assertNull(bases.paidHolidayHourlyBase.calculation)
        assertEquals(918.81, bases.sickHourlyBase.value, 0.01)
        assertEquals(PayBaseSource.DEFAULT, bases.sickHourlyBase.source)
        assertNull(bases.sickHourlyBase.calculation)
    }

    @Test
    fun `resolved pay bases use default selection mode despite calculated base`() {
        val calculation = RollingPayBaseCalculation(
            hourlyBase = 1_025.25,
            grossTotal = 1_968_480.0,
            hoursTotal = 1_920,
            months = (1..12).map { month ->
                RollingPayBaseMonth(2025, month, grossForAverage = 164_040.0, hoursForAverage = 160)
            }
        )

        val bases = resolvePayBasesForMonth(
            year = 2026,
            month = 5,
            monthlyBases = emptyMap(),
            defaults = PayBaseDefaults(
                vacationHourlyBase = 912.87,
                paidHolidayHourlyBase = 912.87,
                sickHourlyBase = 918.81
            ),
            calculatedBase = calculation,
            defaultSelectionMode = PayBaseSelectionMode.DEFAULT_VALUES
        )

        assertEquals(912.87, bases.vacationHourlyBase.value, 0.01)
        assertEquals(PayBaseSource.DEFAULT, bases.vacationHourlyBase.source)
        assertNull(bases.vacationHourlyBase.calculation)
        assertEquals(912.87, bases.paidHolidayHourlyBase.value, 0.01)
        assertEquals(PayBaseSource.DEFAULT, bases.paidHolidayHourlyBase.source)
        assertNull(bases.paidHolidayHourlyBase.calculation)
        assertEquals(918.81, bases.sickHourlyBase.value, 0.01)
        assertEquals(PayBaseSource.DEFAULT, bases.sickHourlyBase.source)
        assertNull(bases.sickHourlyBase.calculation)
    }

    @Test
    fun `resolved pay bases use calculated previous twelve month base when selected`() {
        val calculation = RollingPayBaseCalculation(
            hourlyBase = 1_025.25,
            grossTotal = 1_968_480.0,
            hoursTotal = 1_920,
            months = (1..12).map { month ->
                RollingPayBaseMonth(2025, month, grossForAverage = 164_040.0, hoursForAverage = 160)
            }
        )

        val bases = resolvePayBasesForMonth(
            year = 2026,
            month = 5,
            monthlyBases = mapOf(
                MonthKey(2026, 5) to MonthlyPayBases(
                    year = 2026,
                    month = 5,
                    selectionMode = PayBaseSelectionMode.CALCULATED_PREVIOUS_12_MONTHS
                )
            ),
            defaults = PayBaseDefaults(),
            calculatedBase = calculation
        )

        assertEquals(1_025.25, bases.vacationHourlyBase.value, 0.01)
        assertEquals(PayBaseSource.CALCULATED_PREVIOUS_12_MONTHS, bases.vacationHourlyBase.source)
        assertEquals(calculation, bases.vacationHourlyBase.calculation)
        assertEquals(1_025.25, bases.paidHolidayHourlyBase.value, 0.01)
        assertEquals(PayBaseSource.CALCULATED_PREVIOUS_12_MONTHS, bases.paidHolidayHourlyBase.source)
        assertEquals(1_025.25, bases.sickHourlyBase.value, 0.01)
        assertEquals(PayBaseSource.CALCULATED_PREVIOUS_12_MONTHS, bases.sickHourlyBase.source)
    }

    @Test
    fun `resolved pay bases default to calculated previous twelve month base`() {
        val calculation = RollingPayBaseCalculation(
            hourlyBase = 1_025.25,
            grossTotal = 1_968_480.0,
            hoursTotal = 1_920,
            months = (1..12).map { month ->
                RollingPayBaseMonth(2025, month, grossForAverage = 164_040.0, hoursForAverage = 160)
            }
        )

        val bases = resolvePayBasesForMonth(
            year = 2026,
            month = 5,
            monthlyBases = emptyMap(),
            defaults = PayBaseDefaults(),
            calculatedBase = calculation
        )

        assertEquals(1_025.25, bases.vacationHourlyBase.value, 0.01)
        assertEquals(PayBaseSource.CALCULATED_PREVIOUS_12_MONTHS, bases.vacationHourlyBase.source)
        assertEquals(1_025.25, bases.paidHolidayHourlyBase.value, 0.01)
        assertEquals(PayBaseSource.CALCULATED_PREVIOUS_12_MONTHS, bases.paidHolidayHourlyBase.source)
        assertEquals(1_025.25, bases.sickHourlyBase.value, 0.01)
        assertEquals(PayBaseSource.CALCULATED_PREVIOUS_12_MONTHS, bases.sickHourlyBase.source)
    }

    @Test
    fun `calculated selection falls back to defaults when calculation is unavailable`() {
        val bases = resolvePayBasesForMonth(
            year = 2026,
            month = 5,
            monthlyBases = mapOf(
                MonthKey(2026, 5) to MonthlyPayBases(
                    year = 2026,
                    month = 5,
                    selectionMode = PayBaseSelectionMode.CALCULATED_PREVIOUS_12_MONTHS
                )
            ),
            defaults = PayBaseDefaults(
                vacationHourlyBase = 912.87,
                paidHolidayHourlyBase = 912.87,
                sickHourlyBase = 918.81
            ),
            calculatedBase = null
        )

        assertEquals(912.87, bases.vacationHourlyBase.value, 0.01)
        assertEquals(PayBaseSource.DEFAULT, bases.vacationHourlyBase.source)
        assertEquals(912.87, bases.paidHolidayHourlyBase.value, 0.01)
        assertEquals(PayBaseSource.DEFAULT, bases.paidHolidayHourlyBase.source)
        assertEquals(918.81, bases.sickHourlyBase.value, 0.01)
        assertEquals(PayBaseSource.DEFAULT, bases.sickHourlyBase.source)
    }

    @Test
    fun `previousTwelveMonthKeys excludes target month and keeps chronological order`() {
        val months = previousTwelveMonthKeys(year = 2026, month = 5)

        assertEquals(
            listOf(
                MonthKey(2025, 5),
                MonthKey(2025, 6),
                MonthKey(2025, 7),
                MonthKey(2025, 8),
                MonthKey(2025, 9),
                MonthKey(2025, 10),
                MonthKey(2025, 11),
                MonthKey(2025, 12),
                MonthKey(2026, 1),
                MonthKey(2026, 2),
                MonthKey(2026, 3),
                MonthKey(2026, 4)
            ),
            months
        )
    }

    @Test
    fun `previousTwelveMonthKeys za maj 2027 koristi maj 2026 do april 2027`() {
        val months = previousTwelveMonthKeys(year = 2027, month = 5)

        assertEquals(
            listOf(
                MonthKey(2026, 5),
                MonthKey(2026, 6),
                MonthKey(2026, 7),
                MonthKey(2026, 8),
                MonthKey(2026, 9),
                MonthKey(2026, 10),
                MonthKey(2026, 11),
                MonthKey(2026, 12),
                MonthKey(2027, 1),
                MonthKey(2027, 2),
                MonthKey(2027, 3),
                MonthKey(2027, 4)
            ),
            months
        )
        assertTrue(MonthKey(2026, 4) !in months)
        assertTrue(MonthKey(2027, 5) !in months)
    }

    @Test
    fun `previousTwelveMonthKeys za jun 2027 koristi jun 2026 do maj 2027`() {
        val months = previousTwelveMonthKeys(year = 2027, month = 6)

        assertEquals(
            listOf(
                MonthKey(2026, 6),
                MonthKey(2026, 7),
                MonthKey(2026, 8),
                MonthKey(2026, 9),
                MonthKey(2026, 10),
                MonthKey(2026, 11),
                MonthKey(2026, 12),
                MonthKey(2027, 1),
                MonthKey(2027, 2),
                MonthKey(2027, 3),
                MonthKey(2027, 4),
                MonthKey(2027, 5)
            ),
            months
        )
        assertTrue(MonthKey(2026, 5) !in months)
        assertTrue(MonthKey(2027, 6) !in months)
    }

    @Test
    fun `previousTwelveMonthKeys returns empty for invalid or out of range inputs`() {
        assertTrue(previousTwelveMonthKeys(1_000_000_000, 5).isEmpty())
        assertTrue(previousTwelveMonthKeys(1, 5).isEmpty())
        assertTrue(previousTwelveMonthKeys(2026, 0).isEmpty())
        assertTrue(previousTwelveMonthKeys(2026, 13).isEmpty())
    }

    @Test
    fun `previousMonthKey returns the salary month paid in target month`() {
        assertEquals(MonthKey(2026, 2), previousMonthKey(2026, 3))
        assertEquals(MonthKey(2025, 12), previousMonthKey(2026, 1))
        assertNull(previousMonthKey(2026, 0))
        assertNull(previousMonthKey(1, 1))
    }

    @Test
    fun `getPayrollMonth returns previous calendar month across year boundary`() {
        assertEquals(MonthKey(2026, 6), getPayrollMonth(MonthKey(2026, 7)))
        assertEquals(MonthKey(2026, 2), getPayrollMonth(MonthKey(2026, 3)))
        assertEquals(MonthKey(2025, 12), getPayrollMonth(MonthKey(2026, 1)))
    }

    @Test
    fun `transport helpers count only real work days with at least four hours`() {
        val records = listOf(
            DayRecord(2026, 6, 1, Shift.FIRST, 3),
            DayRecord(2026, 6, 2, Shift.FIRST, 4),
            DayRecord(2026, 6, 3, Shift.FIRST, 8),
            DayRecord(2026, 6, 4, Shift.FIRST, 11),
            DayRecord(2026, 6, 5, Shift.VACATION, 8),
            DayRecord(2026, 6, 8, Shift.SICK, 8),
            DayRecord(2026, 6, 9, Shift.OFF, 0, holidayOverride = true),
            DayRecord(2026, 6, 13, Shift.SECOND, 8, holidayOverride = true)
        )

        val eligibleDays = calculateTransportEligibleDays(records)

        assertEquals(4, eligibleDays)
        assertEquals(960.0, calculateTransportAmount(eligibleDays, 240.0), 0.01)
    }

    @Test
    fun `monthly receipt keeps salary display separate from transport total`() {
        val receipt = calculateMonthlyReceipt(
            year = 2026,
            month = 7,
            salaryMonth = MonthKey(2026, 6),
            previousMonthSalaryNet = 92_131.0,
            currentMonthRegressNet = 0.0,
            transportEligibleDays = 19,
            transportDailyAmount = 240.0
        )

        assertEquals(2026, receipt.year)
        assertEquals(7, receipt.month)
        assertEquals(MonthKey(2026, 6), receipt.salaryMonth)
        assertEquals(92_131.0, receipt.salaryNetPay, 0.01)
        assertEquals(0.0, receipt.regressNetPay, 0.01)
        assertEquals(92_131.0, receipt.displayPay, 0.01)
        assertEquals(19, receipt.transportEligibleDays)
        assertEquals(240.0, receipt.transportDailyAmount, 0.01)
        assertEquals(4_560.0, receipt.transportAmount, 0.01)
        assertEquals(96_691.0, receipt.totalReceivedThisMonth, 0.01)
    }

    @Test
    fun `recent net pay average month keys follow payroll cutoff day`() {
        assertEquals(
            listOf(MonthKey(2025, 11), MonthKey(2025, 12), MonthKey(2026, 1)),
            recentNetPayAverageMonthKeys(2026, 3, LocalDate.of(2026, 3, 20))
        )
        assertEquals(
            listOf(MonthKey(2025, 12), MonthKey(2026, 1), MonthKey(2026, 2)),
            recentNetPayAverageMonthKeys(2026, 3, LocalDate.of(2026, 3, 21))
        )
    }

    @Test
    fun `recent net pay average returns null when any selected salary month is missing`() {
        val average = calculateRecentNetPayAverage(
            listOf(
                RecentNetPayMonth(MonthKey(2025, 12), 100_000.0),
                RecentNetPayMonth(MonthKey(2026, 1), null),
                RecentNetPayMonth(MonthKey(2026, 2), 120_000.0)
            )
        )

        assertEquals(listOf(MonthKey(2025, 12), MonthKey(2026, 1), MonthKey(2026, 2)), average.monthKeys)
        assertEquals(listOf(MonthKey(2026, 1)), average.missingMonthKeys)
        assertNull(average.averageNetPay)
    }

    @Test
    fun `recent net pay average calculates only when all three salary months are available`() {
        val average = calculateRecentNetPayAverage(
            listOf(
                RecentNetPayMonth(MonthKey(2025, 12), 100_000.0),
                RecentNetPayMonth(MonthKey(2026, 1), 110_000.0),
                RecentNetPayMonth(MonthKey(2026, 2), 120_000.0)
            )
        )

        assertEquals(emptyList<MonthKey>(), average.missingMonthKeys)
        assertEquals(110_000.0, average.averageNetPay!!, 0.01)
    }

    @Test
    fun `calculateRollingPayBaseCalculation averages twelve entered months`() {
        val months = (1..12).map { month ->
            RollingPayBaseMonth(
                year = 2025,
                month = month,
                grossForAverage = 160_000.0,
                hoursForAverage = 160
            )
        }

        val calculation = calculateRollingPayBaseCalculation(months)

        assertNotNull(calculation)
        assertEquals(1_000.0, calculation!!.hourlyBase, 0.01)
        assertEquals(1_920_000.0, calculation.grossTotal, 0.01)
        assertEquals(1_920, calculation.hoursTotal)
        assertEquals(12, calculation.months.size)
    }

    @Test
    fun `calculateRollingPayBaseCalculation koristi ponderisani prosek umesto proseka mesecnih satnica`() {
        val months = (1..12).map { month ->
            if (month == 12) {
                RollingPayBaseMonth(
                    year = 2025,
                    month = month,
                    grossForAverage = 120_000.0,
                    hoursForAverage = 80
                )
            } else {
                RollingPayBaseMonth(
                    year = 2025,
                    month = month,
                    grossForAverage = 160_000.0,
                    hoursForAverage = 160
                )
            }
        }

        val calculation = calculateRollingPayBaseCalculation(months)

        assertNotNull(calculation)
        assertEquals(1_880_000.0 / 1_840.0, calculation!!.hourlyBase, 0.0001)
        assertTrue(kotlin.math.abs(calculation.hourlyBase - 1_041.6667) > 0.1)
    }

    @Test
    fun `calculateRollingPayBaseCalculation allows a zero contribution month when total worked history exists`() {
        val months = (1..12).map { month ->
            if (month == 12) {
                RollingPayBaseMonth(
                    year = 2025,
                    month = month,
                    grossForAverage = 0.0,
                    hoursForAverage = 0
                )
            } else {
                RollingPayBaseMonth(
                    year = 2025,
                    month = month,
                    grossForAverage = 160_000.0,
                    hoursForAverage = 160
                )
            }
        }

        val calculation = calculateRollingPayBaseCalculation(months)

        assertNotNull(calculation)
        assertEquals(1_000.0, calculation!!.hourlyBase, 0.01)
        assertEquals(1_760, calculation.hoursTotal)
    }

    @Test
    fun `rolling pay base debug trace exposes period totals source and anchor difference`() {
        val months = previousTwelveMonthKeys(2027, 5).map { key ->
            RollingPayBaseMonth(
                year = key.year,
                month = key.month,
                grossForAverage = 160_000.0,
                hoursForAverage = 160
            )
        }
        val calculation = calculateRollingPayBaseCalculation(months)!!

        val trace = calculation.toDebugTrace(
            targetMonth = MonthKey(2027, 5),
            anchor = KnownPayBaseAnchor(MonthKey(2026, 4), 953.183125)
        )

        assertEquals(MonthKey(2027, 5), trace.targetMonth)
        assertEquals(MonthKey(2026, 5), trace.calculationPeriodStart)
        assertEquals(MonthKey(2027, 4), trace.calculationPeriodEnd)
        assertEquals(previousTwelveMonthKeys(2027, 5), trace.includedMonths)
        assertEquals(1_920_000.0, trace.totalIncludedGross, 0.01)
        assertEquals(1_920, trace.totalIncludedHours)
        assertEquals(1_000.0, trace.calculatedAverageGrossHourlyBase, 0.01)
        assertEquals(PayBaseCalculationSource.REAL_12_MONTHS, trace.source)
        assertEquals(MonthKey(2026, 4), trace.anchorMonth)
        assertEquals(-46.816875, trace.differenceFromCalculated!!, 0.000001)
    }

    @Test
    fun `manual anchor gross hourly base is calculated from gross amount and hours`() {
        val anchorRate = calculateManualAnchorGrossHourlyBase(15_250.93, 16)

        assertNotNull(anchorRate)
        assertEquals(953.183125, anchorRate!!, 0.000001)
        assertNull(calculateManualAnchorGrossHourlyBase(15_250.93, 0))
    }

    @Test
    fun `calculateKnownPayBaseCalculation averages known anchor with carry forward and real months`() {
        val months = listOf(
            RollingPayBaseMonth(
                year = 2026,
                month = 1,
                grossForAverage = 917.81 * 168,
                hoursForAverage = 168,
                source = PayBaseMonthSource.KNOWN_BASE
            ),
            RollingPayBaseMonth(
                year = 2026,
                month = 2,
                grossForAverage = 160_000.0,
                hoursForAverage = 160,
                source = PayBaseMonthSource.REAL
            ),
            RollingPayBaseMonth(
                year = 2026,
                month = 3,
                grossForAverage = 176_000.0,
                hoursForAverage = 176,
                source = PayBaseMonthSource.CARRY_FORWARD
            )
        )

        val calculation = calculateKnownPayBaseCalculation(months)

        assertNotNull(calculation)
        assertEquals(3, calculation!!.months.size)
        assertEquals(PayBaseMonthSource.KNOWN_BASE, calculation.months.first().source)
        assertEquals(
            (917.81 * 168 + 160_000.0 + 176_000.0) / (168 + 160 + 176).toDouble(),
            calculation.hourlyBase,
            0.01
        )
    }

    @Test
    fun `calculateKnownPayBaseCalculation rejects missing anchor or empty history`() {
        assertNull(calculateKnownPayBaseCalculation(emptyList()))
        assertNull(
            calculateKnownPayBaseCalculation(
                listOf(
                    RollingPayBaseMonth(
                        year = 2026,
                        month = 2,
                        grossForAverage = 160_000.0,
                        hoursForAverage = 160,
                        source = PayBaseMonthSource.REAL
                    )
                )
            )
        )
        assertNull(
            calculateKnownPayBaseCalculation(
                listOf(
                    RollingPayBaseMonth(
                        year = 2026,
                        month = 1,
                        grossForAverage = 917.81 * 168,
                        hoursForAverage = 168,
                        source = PayBaseMonthSource.KNOWN_BASE
                    ),
                    RollingPayBaseMonth(
                        year = 2026,
                        month = 2,
                        grossForAverage = 160_000.0,
                        hoursForAverage = 160,
                        source = PayBaseMonthSource.SIMULATED
                    )
                )
            )
        )
    }

    @Test
    fun `calculateRollingPayBaseCalculation rejects incomplete or invalid input`() {
        val elevenMonths = (1..11).map { month ->
            RollingPayBaseMonth(2025, month, grossForAverage = 160_000.0, hoursForAverage = 160)
        }
        val zeroHours = (1..12).map { month ->
            RollingPayBaseMonth(2025, month, grossForAverage = 160_000.0, hoursForAverage = 0)
        }
        val zeroGross = (1..12).map { month ->
            RollingPayBaseMonth(2025, month, grossForAverage = 0.0, hoursForAverage = 160)
        }

        assertNull(calculateRollingPayBaseCalculation(elevenMonths))
        assertNull(calculateRollingPayBaseCalculation(zeroHours))
        assertNull(calculateRollingPayBaseCalculation(zeroGross))
    }

    @Test
    fun `informativna neto osnovna satnica koristi mesecnu poresku olaksicu`() {
        val result = calculateInformativeNetHourlyBase(
            grossHourlyRate = 637.32,
            monthlyFundHours = 160,
            contributionsPercent = 19.9,
            taxRelief = 34_221.0,
            taxPercent = 10.0
        )

        assertEquals(468.15, result, 0.01)
    }

    @Test
    fun `informativna neto osnovna satnica za decembar koristi 604 point 89`() {
        val result = calculateInformativeNetHourlyBase(
            grossHourlyRate = 604.89,
            monthlyFundHours = 160,
            contributionsPercent = 19.9,
            taxRelief = 34_221.0,
            taxPercent = 10.0
        )

        assertEquals(445.42, result, 0.01)
    }

    @Test
    fun `informativna neto osnovna satnica za maj koristi poresku osnovicu bez doprinosa`() {
        val result = calculateInformativeNetHourlyBase(
            grossHourlyRate = 637.32,
            monthlyFundHours = 168,
            contributionsPercent = 19.9,
            taxRelief = 34_221.0,
            taxPercent = 10.0
        )

        assertEquals(467.13, result, 0.01)
    }

    @Test
    fun `informativna neto osnovna satnica ne deli nulom`() {
        val result = calculateInformativeNetHourlyBase(
            grossHourlyRate = 637.32,
            monthlyFundHours = 0,
            contributionsPercent = 19.9,
            taxRelief = 34_221.0,
            taxPercent = 10.0
        )

        assertEquals(0.0, result, 0.01)
    }

    @Test
    fun `mesecni fond sati vraca nulu za godinu van opsega`() {
        val result = calculateMonthlyFundHours(1_000_000_000, 5)

        assertEquals(0, result)
    }

    @Test
    fun `mesecni fond sati racuna radne dane za poznat mesec`() {
        val result = calculateMonthlyFundHours(2026, 5)

        assertEquals(168, result)
    }

    @Test
    fun `mesecni fond sati vraca nulu za neispravne ulaze`() {
        assertEquals(0, calculateMonthlyFundHours(2026, 0))
        assertEquals(0, calculateMonthlyFundHours(2026, 13))
        assertEquals(0, calculateMonthlyFundHours(2026, 5, defaultHours = 0))
    }

    @Test
    fun `poreska osnovica ne ide ispod nule`() {
        val summary = MonthSummary(
            year = 2026,
            month = 6,
            totalPay = 10_000.0,
            minuliRadBonus = 0.0
        )

        val breakdown = calculateMonthlyNetPayBreakdown(
            summary = summary,
            grossHourlyRate = 637.32,
            monthlyFundHours = 160,
            contributionsPercent = 19.9,
            taxRelief = 34_221.0,
            taxPercent = 10.0
        )

        assertEquals(10_000.0, breakdown.grossTotal, 0.01)
        assertEquals(1_990.0, breakdown.employeeContributions, 0.01)
        assertEquals(0.0, breakdown.taxableBase, 0.01)
        assertEquals(0.0, breakdown.tax, 0.01)
        assertEquals(8_010.0, breakdown.displayPay, 0.01)
    }

    @Test
    fun `mesecna neto plata se racuna iz bruto ukupno`() {
        val summary = MonthSummary(
            year = 2026,
            month = 6,
            totalPay = 100_000.0,
            minuliRadBonus = 3_500.0
        )

        val breakdown = calculateMonthlyNetPayBreakdown(
            summary = summary,
            grossHourlyRate = 637.32,
            monthlyFundHours = 160,
            contributionsPercent = 19.9,
            taxRelief = 34_221.0,
            taxPercent = 10.0
        )

        assertEquals(100_000.0, breakdown.grossBaseBeforeSeniority, 0.01)
        assertEquals(3_500.0, breakdown.grossSeniorityBonus, 0.01)
        assertEquals(103_500.0, breakdown.grossTotal, 0.01)
        assertEquals(20_596.5, breakdown.employeeContributions, 0.01)
        assertEquals(69_279.0, breakdown.taxableBase, 0.01)
        assertEquals(6_927.9, breakdown.tax, 0.01)
        assertEquals(75_975.6, breakdown.netPayBeforeDeductions, 0.01)
        assertEquals(75_975.6, breakdown.displayPay, 0.01)
    }

    @Test
    fun `bruto dodaci povecavaju bruto ukupno i neto kroz poresku formulu`() {
        val summary = MonthSummary(
            year = 2026,
            month = 6,
            totalPay = 100_000.0,
            minuliRadBonus = 3_500.0
        )

        val breakdown = calculateMonthlyNetPayBreakdown(
            summary = summary,
            grossHourlyRate = 637.32,
            monthlyFundHours = 160,
            contributionsPercent = 19.9,
            taxRelief = 34_221.0,
            taxPercent = 10.0,
            grossAdditions = MonthlyGrossAdditions(
                year = 2026,
                month = 6,
                regressGross = 28_530.67,
                bonusGross = 28_530.67
            )
        )

        assertEquals(0.0, breakdown.grossMealAddition, 0.01)
        assertEquals(28_530.67, breakdown.grossRegressAddition, 0.01)
        assertEquals(28_530.67, breakdown.grossBonusAddition, 0.01)
        assertEquals(57_061.34, breakdown.grossAdditionsTotal, 0.01)
        assertEquals(160_561.34, breakdown.grossTotal, 0.01)
        assertEquals(31_951.71, breakdown.employeeContributions, 0.01)
        assertEquals(126_340.34, breakdown.taxableBase, 0.01)
        assertEquals(12_634.03, breakdown.tax, 0.01)
        assertEquals(115_975.60, breakdown.displayPay, 0.01)
    }

    @Test
    fun `explicit topli obrok bruto sa listica menja bruto TO dodatak`() {
        val summary = MonthSummary(
            year = 2026,
            month = 1,
            totalPay = 100_000.0,
            foodAllowance = 6_160.0
        )

        val breakdown = calculateMonthlyNetPayBreakdown(
            summary = summary,
            grossHourlyRate = 637.32,
            monthlyFundHours = 168,
            contributionsPercent = 19.9,
            taxRelief = 34_221.0,
            taxPercent = 10.0,
            grossAdditions = MonthlyGrossAdditions(
                year = 2026,
                month = 1,
                mealGross = 5_320.97
            )
        )

        assertEquals(5_320.97, breakdown.grossMealAddition, 0.01)
        assertEquals(6_160.0, breakdown.mealAllowanceNet, 0.01)
        assertEquals(6_160.0, breakdown.mealSpentNet, 0.01)
    }

    @Test
    fun `topli obrok se automatski dodaje kao pokriveni TO2 bruto i odbija iz neta`() {
        val summary = MonthSummary(
            year = 2026,
            month = 5,
            totalPay = 100_000.0,
            foodAllowance = 6_160.0
        )

        val breakdown = calculateMonthlyNetPayBreakdown(
            summary = summary,
            grossHourlyRate = 637.32,
            monthlyFundHours = 168,
            contributionsPercent = 19.9,
            taxRelief = 34_221.0,
            taxPercent = 10.0
        )

        assertEquals(6_160.0, breakdown.mealAllowanceNet, 0.01)
        assertEquals(6_160.0, breakdown.mealSpentNet, 0.01)
        assertEquals(6_160.0, breakdown.mealCoveredNet, 0.01)
        assertEquals(8_787.45, breakdown.grossMealAddition, 0.01)
        assertEquals(6_160.0, breakdown.netDeductionsTotal, 0.01)
        assertEquals(breakdown.netPayBeforeDeductions - 6_160.0, breakdown.displayPay, 0.01)
    }

    @Test
    fun `topli obrok preko prava se ignorise i racuna kao pravo`() {
        val summary = MonthSummary(
            year = 2026,
            month = 5,
            totalPay = 100_000.0,
            foodAllowance = 6_160.0
        )

        val defaultSpending = calculateMonthlyNetPayBreakdown(
            summary = summary,
            grossHourlyRate = 637.32,
            monthlyFundHours = 168,
            contributionsPercent = 19.9,
            taxRelief = 34_221.0,
            taxPercent = 10.0
        )
        val overSpending = calculateMonthlyNetPayBreakdown(
            summary = summary,
            grossHourlyRate = 637.32,
            monthlyFundHours = 168,
            contributionsPercent = 19.9,
            taxRelief = 34_221.0,
            taxPercent = 10.0,
            grossAdditions = MonthlyGrossAdditions(
                year = 2026,
                month = 5,
                mealSpentNet = 7_160.0
            )
        )

        assertEquals(6_160.0, overSpending.mealCoveredNet, 0.01)
        assertEquals(6_160.0, overSpending.mealSpentNet, 0.01)
        assertEquals(8_787.45, overSpending.grossMealAddition, 0.01)
        assertEquals(defaultSpending.displayPay, overSpending.displayPay, 0.01)
    }

    @Test
    fun `topli obrok ispod prava se ignorise i racuna kao pravo`() {
        val summary = MonthSummary(
            year = 2026,
            month = 5,
            totalPay = 100_000.0,
            foodAllowance = 6_160.0
        )

        val defaultSpending = calculateMonthlyNetPayBreakdown(
            summary = summary,
            grossHourlyRate = 637.32,
            monthlyFundHours = 168,
            contributionsPercent = 19.9,
            taxRelief = 34_221.0,
            taxPercent = 10.0
        )
        val lowerSpending = calculateMonthlyNetPayBreakdown(
            summary = summary,
            grossHourlyRate = 637.32,
            monthlyFundHours = 168,
            contributionsPercent = 19.9,
            taxRelief = 34_221.0,
            taxPercent = 10.0,
            grossAdditions = MonthlyGrossAdditions(
                year = 2026,
                month = 5,
                mealSpentNet = 5_160.0
            )
        )

        assertEquals(6_160.0, lowerSpending.mealCoveredNet, 0.01)
        assertEquals(6_160.0, lowerSpending.mealSpentNet, 0.01)
        assertEquals(8_787.45, lowerSpending.grossMealAddition, 0.01)
        assertEquals(defaultSpending.displayPay, lowerSpending.displayPay, 0.01)
    }

    @Test
    fun `regres se automatski dodaje u kvartalnim mesecima iz neto iznosa`() {
        val summary = MonthSummary(
            year = 2026,
            month = 5,
            totalPay = 100_000.0
        )

        val breakdown = calculateMonthlyNetPayBreakdown(
            summary = summary,
            grossHourlyRate = 637.32,
            monthlyFundHours = 168,
            contributionsPercent = 19.9,
            taxRelief = 34_221.0,
            taxPercent = 10.0
        )

        assertEquals(28_530.67, breakdown.grossRegressAddition, 0.01)
        assertEquals(20_000.0, breakdown.netRegressAddition, 0.01)
    }

    @Test
    fun `explicit regres bruto ima prednost u kvartalnom mesecu i moze da se iskljuci`() {
        val summary = MonthSummary(
            year = 2026,
            month = 5,
            totalPay = 100_000.0
        )
        val additions = MonthlyGrossAdditions(
            year = 2026,
            month = 5,
            regressGross = 35_000.0
        )

        val withExplicitRegress = calculateMonthlyNetPayBreakdown(
            summary = summary,
            grossHourlyRate = 637.32,
            monthlyFundHours = 168,
            contributionsPercent = 19.9,
            taxRelief = 34_221.0,
            taxPercent = 10.0,
            grossAdditions = additions
        )
        val withoutRegress = calculateMonthlyNetPayBreakdown(
            summary = summary,
            grossHourlyRate = 637.32,
            monthlyFundHours = 168,
            contributionsPercent = 19.9,
            taxRelief = 34_221.0,
            taxPercent = 10.0,
            grossAdditions = additions,
            includeRegress = false
        )

        assertEquals(35_000.0, withExplicitRegress.grossRegressAddition, 0.01)
        assertEquals(24_535.0, withExplicitRegress.netRegressAddition, 0.01)
        assertEquals(0.0, withoutRegress.grossRegressAddition, 0.01)
        assertEquals(0.0, withoutRegress.netRegressAddition, 0.01)
    }

    @Test
    fun `automatski regres se ne dodaje kad mesec nema obracun plate`() {
        val summary = MonthSummary(
            year = 2026,
            month = 5,
            totalPay = 0.0
        )

        val breakdown = calculateMonthlyNetPayBreakdown(
            summary = summary,
            grossHourlyRate = 637.32,
            monthlyFundHours = 168,
            contributionsPercent = 19.9,
            taxRelief = 34_221.0,
            taxPercent = 10.0
        )

        assertEquals(0.0, breakdown.grossRegressAddition, 0.01)
        assertEquals(0.0, breakdown.netRegressAddition, 0.01)
        assertEquals(0.0, breakdown.displayPay, 0.01)
    }

    @Test
    fun `mesecna neto plata moze da iskljuci automatski regres`() {
        val summary = MonthSummary(
            year = 2026,
            month = 5,
            totalPay = 100_000.0
        )

        val withRegress = calculateMonthlyNetPayBreakdown(
            summary = summary,
            grossHourlyRate = 1_000.0,
            monthlyFundHours = 160,
            contributionsPercent = 20.0,
            taxRelief = 0.0,
            taxPercent = 10.0
        )
        val withoutRegress = calculateMonthlyNetPayBreakdown(
            summary = summary,
            grossHourlyRate = 1_000.0,
            monthlyFundHours = 160,
            contributionsPercent = 20.0,
            taxRelief = 0.0,
            taxPercent = 10.0,
            includeRegress = false
        )

        assertEquals(20_000.0, withRegress.netRegressAddition, 0.01)
        assertEquals(0.0, withoutRegress.netRegressAddition, 0.01)
        assertEquals(withRegress.displayPay - 20_000.0, withoutRegress.displayPay, 0.01)
    }

    @Test
    fun `godisnji neto prosek sabira varijante sa regresom bez regresa i bez trece smene`() {
        val average = calculateAnnualNetPayAverages(
            listOf(
                AnnualNetPayMonth(
                    netPay = 120_000.0,
                    netPayWithoutRegress = 100_000.0,
                    netPayWithoutThirdShift = 108_000.0
                ),
                AnnualNetPayMonth(
                    netPay = 100_000.0,
                    netPayWithoutRegress = 100_000.0,
                    netPayWithoutThirdShift = 92_000.0
                )
            )
        )

        assertEquals(2, average.monthCount)
        assertEquals(110_000.0, average.averagePay, 0.01)
        assertEquals(100_000.0, average.averagePayWithoutRegress, 0.01)
        assertEquals(100_000.0, average.averagePayWithoutThirdShift, 0.01)
    }

    @Test
    fun `godisnja nagrada se unosi kao neto i preracunava u bruto`() {
        val summary = MonthSummary(
            year = 2026,
            month = 3,
            totalPay = 100_000.0
        )

        val breakdown = calculateMonthlyNetPayBreakdown(
            summary = summary,
            grossHourlyRate = 637.32,
            monthlyFundHours = 176,
            contributionsPercent = 19.9,
            taxRelief = 34_221.0,
            taxPercent = 10.0,
            grossAdditions = MonthlyGrossAdditions(
                year = 2026,
                month = 3,
                bonusNet = 20_000.0
            )
        )

        assertEquals(28_530.67, breakdown.grossBonusAddition, 0.01)
        assertEquals(20_000.0, breakdown.netBonusAddition, 0.01)
    }

    @Test
    fun `mesecna neto plata ne odbija doprinose pre poreske olaksice`() {
        val summary = MonthSummary(
            year = 2026,
            month = 6,
            totalPay = 147_650.73,
            minuliRadBonus = 0.0
        )

        val breakdown = calculateMonthlyNetPayBreakdown(
            summary = summary,
            grossHourlyRate = 637.32,
            monthlyFundHours = 168,
            contributionsPercent = 19.9,
            taxRelief = 34_221.0,
            taxPercent = 10.0
        )

        assertEquals(147_650.73, breakdown.grossTotal, 0.01)
        assertEquals(29_382.50, breakdown.employeeContributions, 0.01)
        assertEquals(113_429.73, breakdown.taxableBase, 0.01)
        assertEquals(11_342.97, breakdown.tax, 0.01)
        assertEquals(106_925.26, breakdown.displayPay, 0.01)
    }

    @Test
    fun `poreska osnovica za bruto dodatke ne odbija doprinose pre olaksice`() {
        val summary = MonthSummary(
            year = 2026,
            month = 6,
            totalPay = 147_650.73,
            minuliRadBonus = 0.0
        )

        val breakdown = calculateMonthlyNetPayBreakdown(
            summary = summary,
            grossHourlyRate = 637.32,
            monthlyFundHours = 168,
            contributionsPercent = 19.9,
            taxRelief = 34_221.0,
            taxPercent = 10.0,
            grossAdditions = MonthlyGrossAdditions(
                year = 2026,
                month = 6,
                regressGross = 28_530.67
            )
        )

        assertEquals(176_181.40, breakdown.grossTotal, 0.01)
        assertEquals(35_060.10, breakdown.employeeContributions, 0.01)
        assertEquals(141_960.40, breakdown.taxableBase, 0.01)
        assertEquals(14_196.04, breakdown.tax, 0.01)
        assertEquals(126_925.26, breakdown.displayPay, 0.01)
    }

    @Test
    fun `neto minuli rad je razlika neta sa i bez minulog`() {
        val summary = MonthSummary(
            year = 2026,
            month = 6,
            totalPay = 100_000.0,
            minuliRadBonus = 3_500.0
        )

        val breakdown = calculateMonthlyNetPayBreakdown(
            summary = summary,
            grossHourlyRate = 637.32,
            monthlyFundHours = 160,
            contributionsPercent = 19.9,
            taxRelief = 34_221.0,
            taxPercent = 10.0
        )

        assertEquals(2_453.5, breakdown.netSeniorityBonus, 0.01)
    }

    @Test
    fun `bruto nula daje sve rezultate nula`() {
        val summary = MonthSummary(year = 2026, month = 6)

        val breakdown = calculateMonthlyNetPayBreakdown(
            summary = summary,
            grossHourlyRate = 637.32,
            monthlyFundHours = 160,
            contributionsPercent = 19.9,
            taxRelief = 34_221.0,
            taxPercent = 10.0
        )

        assertEquals(0.0, breakdown.grossTotal, 0.01)
        assertEquals(0.0, breakdown.employeeContributions, 0.01)
        assertEquals(0.0, breakdown.taxableBase, 0.01)
        assertEquals(0.0, breakdown.tax, 0.01)
        assertEquals(0.0, breakdown.netPayBeforeDeductions, 0.01)
        assertEquals(0.0, breakdown.netSeniorityBonus, 0.01)
        assertEquals(468.15, breakdown.informativeNetHourlyBase, 0.01)
        assertEquals(0.0, breakdown.displayPay, 0.01)
    }

    @Test
    fun `konacna isplata odbija samo neto akontaciju i obustave od neto plate`() {
        val withoutDeductions = calculateFinalNetPayBreakdown(
            netPayBeforeAdvance = 120_000.0,
            advancePaymentNet = 50_000.0,
            deductionsNet = 0.0
        )
        val withDeductions = calculateFinalNetPayBreakdown(
            netPayBeforeAdvance = 120_000.0,
            advancePaymentNet = 50_000.0,
            deductionsNet = 10_000.0
        )
        val withoutAdvance = calculateFinalNetPayBreakdown(
            netPayBeforeAdvance = 120_000.0,
            advancePaymentNet = 0.0,
            deductionsNet = 0.0
        )
        val largerAdvance = calculateFinalNetPayBreakdown(
            netPayBeforeAdvance = 120_000.0,
            advancePaymentNet = 130_000.0,
            deductionsNet = 0.0
        )

        assertEquals(70_000.0, withoutDeductions.finalRemainingNetPay, 0.01)
        assertEquals(60_000.0, withDeductions.finalRemainingNetPay, 0.01)
        assertEquals(120_000.0, withoutAdvance.finalRemainingNetPay, 0.01)
        assertEquals(0.0, largerAdvance.finalRemainingNetPay, 0.01)
        assertEquals(true, largerAdvance.advanceExceedsCalculatedNetPay)
    }

    @Test
    fun `mesecna konacna isplata podrazumevano ne odbija kredit od preostalog dvadesetog`() {
        val settings = CreditDeductionSettings()
        val breakdown = calculateMonthlyFinalPaymentBreakdown(
            month = MonthKey(2026, 5),
            isNetPayAvailable = true,
            netPayBeforeAdvance = 98_575.56,
            advancePaymentNet = 0.0,
            loanDeductionNet = settings.activeDeductionNet
        )

        assertEquals(0.0, breakdown.deductionsNet, 0.01)
        assertEquals(98_575.56, breakdown.finalRemainingNetPay, 0.01)
        assertEquals(0.0, breakdown.remainingRegressNetPay, 0.01)
    }

    @Test
    fun `kredit iskljucen ne umanjuje preostalo dvadesetog ni kada je rata uneta`() {
        val settings = CreditDeductionSettings(
            creditDeductionEnabled = false,
            monthlyCreditDeductionAmount = 48_839.50
        )

        val breakdown = calculateMonthlyFinalPaymentBreakdown(
            month = MonthKey(2026, 5),
            isNetPayAvailable = true,
            netPayBeforeAdvance = 98_575.56,
            advancePaymentNet = 0.0,
            loanDeductionNet = settings.activeDeductionNet
        )

        assertEquals(0.0, breakdown.deductionsNet, 0.01)
        assertEquals(98_575.56, breakdown.finalRemainingNetPay, 0.01)
    }

    @Test
    fun `aktivan kredit umanjuje samo preostalo dvadesetog za podesenu ratu`() {
        val cases = listOf(
            Triple(0.0, 48_839.50, 49_736.06),
            Triple(0.0, 30_000.0, 68_575.56),
            Triple(20_000.0, 48_839.50, 29_736.06)
        )

        for ((advance, creditAmount, expectedRemaining) in cases) {
            val settings = CreditDeductionSettings(
                creditDeductionEnabled = true,
                monthlyCreditDeductionAmount = creditAmount
            )
            val breakdown = calculateMonthlyFinalPaymentBreakdown(
                month = MonthKey(2026, 5),
                isNetPayAvailable = true,
                netPayBeforeAdvance = 98_575.56,
                advancePaymentNet = advance,
                loanDeductionNet = settings.activeDeductionNet
            )

            assertEquals(creditAmount, breakdown.loanDeductionNet, 0.01)
            assertEquals(creditAmount, breakdown.deductionsNet, 0.01)
            assertEquals(expectedRemaining, breakdown.finalRemainingNetPay, 0.01)
        }
    }

    @Test
    fun `nevalidna rata kredita se sanitizuje na nulu`() {
        val negative = CreditDeductionSettings(
            creditDeductionEnabled = true,
            monthlyCreditDeductionAmount = -1.0
        )
        val infinite = CreditDeductionSettings(
            creditDeductionEnabled = true,
            monthlyCreditDeductionAmount = Double.POSITIVE_INFINITY
        )

        assertEquals(0.0, negative.activeDeductionNet, 0.01)
        assertEquals(0.0, infinite.activeDeductionNet, 0.01)
    }
}
