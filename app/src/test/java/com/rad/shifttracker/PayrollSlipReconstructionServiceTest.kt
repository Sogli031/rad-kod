package com.rad.shifttracker

import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PayrollSlipReconstructionServiceTest {
    private val service = PayrollSlipReconstructionService()

    @Test
    fun `inferThreeBrigadeReference finds future populated work week`() {
        val repo = ShiftRepository()
        populateReferenceWeek(repo, Shift.THIRD)

        val reference = service.inferThreeBrigadeReference(repo, MonthKey(2026, 1))

        assertNotNull(reference)
        assertEquals(ShiftFillMode.THREE_BRIGADE, reference!!.mode)
        assertEquals(LocalDate.of(2026, 5, 4), reference.anchorDate)
        assertEquals(Shift.THIRD, reference.selectedShift)
    }

    @Test
    fun `inferThreeBrigadeReference returns null when no reliable future work week exists`() {
        val repo = ShiftRepository()
        repo.set(2026, 5, 4, Shift.THIRD, CONFIG.pay.defaultHours)
        repo.set(2026, 5, 5, Shift.SECOND, CONFIG.pay.defaultHours)

        assertNull(service.inferThreeBrigadeReference(repo, MonthKey(2026, 1)))
    }

    @Test
    fun `reconstruct preview base generation does not mutate repository and warns approximate dates`() {
        val repo = ShiftRepository()
        populateReferenceWeek(repo, Shift.THIRD)
        val beforeJson = repo.exportJson()

        val result = service.reconstruct(
            repo = repo,
            input = PayrollSlipInput(month = MonthKey(2026, 1), regularWorkHours = 176.0)
        )

        assertTrue(result.records.isNotEmpty())
        assertEquals(beforeJson, repo.exportJson())
        assertTrue(result.warnings.any { it.type == PayrollSlipWarningType.APPROXIMATE_DATES })
        assertTrue(result.comparison.rows.any { it.label == "Rad u režiji" })
        assertTrue(result.comparison.rows.any { it.label == "Noćni rad" })
        assertTrue(result.comparison.rows.any { it.label == "Prekovremeni rad" })
        assertTrue(result.comparison.calculatedNetBeforeDeductions > 0.0)
    }

    @Test
    fun `reconstruct warns existing month will be replaced without mutating existing record`() {
        val repo = ShiftRepository()
        repo.set(2026, 1, 5, Shift.FIRST, CONFIG.pay.defaultHours)
        populateReferenceWeek(repo, Shift.THIRD)

        val result = service.reconstruct(
            repo = repo,
            input = PayrollSlipInput(month = MonthKey(2026, 1), regularWorkHours = 176.0)
        )

        val existingRecord = repo.get(2026, 1, 5)
        assertNotNull(existingRecord)
        assertEquals(Shift.FIRST, existingRecord!!.shift)
        assertEquals(CONFIG.pay.defaultHours, existingRecord.hours)
        assertTrue(result.warnings.any { it.type == PayrollSlipWarningType.EXISTING_MONTH_WILL_BE_REPLACED })
    }

    @Test
    fun `reconstruction applies vacation sick absence and overtime to drafts`() {
        val repo = ShiftRepository()
        populateReferenceWeek(repo, Shift.FIRST)

        val result = service.reconstruct(
            repo = repo,
            input = PayrollSlipInput(
                month = MonthKey(2026, 1),
                regularWorkHours = 120.0,
                vacationHours = 16.0,
                sickHours = 8.0,
                absenceHours = 2.0,
                overtimeHours = 5.0
            )
        )

        val records = result.records.map { it.toDayRecord() }
        assertEquals(2, records.count { it.shift == Shift.VACATION })
        assertEquals(1, records.count { it.shift == Shift.SICK })
        assertEquals(1, records.count { it.shift in Shift.workShifts() && it.hours == 6 })
        assertEquals(5, records.sumOf { it.hours2 })
    }

    @Test
    fun `reconstruction allocates partial whole-hour vacation and sick targets`() {
        val repo = ShiftRepository()
        populateReferenceWeek(repo, Shift.FIRST)

        val result = service.reconstruct(
            repo = repo,
            input = PayrollSlipInput(
                month = MonthKey(2026, 1),
                regularWorkHours = 120.0,
                vacationHours = 4.0,
                sickHours = 4.0
            )
        )

        val records = result.records.map { it.toDayRecord() }
        assertTrue(records.any { it.shift == Shift.VACATION && it.hours == 4 })
        assertTrue(records.any { it.shift == Shift.SICK && it.hours == 4 })
        assertEquals(4 * 60, result.comparison.row("Godišnji").calculatedMinutes)
        assertEquals(4 * 60, result.comparison.row("Bolovanje").calculatedMinutes)
    }

    @Test
    fun `reconstruction warns when paid absence target has unsupported minute remainder`() {
        val repo = ShiftRepository()
        populateReferenceWeek(repo, Shift.FIRST)

        val result = service.reconstruct(
            repo = repo,
            input = PayrollSlipInput(
                month = MonthKey(2026, 1),
                regularWorkHours = 120.0,
                vacationHours = 4.25
            )
        )

        assertTrue(result.warnings.any { it.type == PayrollSlipWarningType.UNSUPPORTED_ABSENCE_MINUTES })
        assertEquals(255, result.comparison.row("Godišnji").targetMinutes)
        assertEquals(240, result.comparison.row("Godišnji").calculatedMinutes)
    }

    @Test
    fun `unsupported absence remainder is warned instead of hidden`() {
        val repo = ShiftRepository()
        populateReferenceWeek(repo, Shift.FIRST)

        val result = service.reconstruct(
            repo = repo,
            input = PayrollSlipInput(
                month = MonthKey(2026, 1),
                regularWorkHours = 120.0,
                absenceHours = 1.10
            )
        )

        assertTrue(result.warnings.any { it.type == PayrollSlipWarningType.UNSUPPORTED_ABSENCE_MINUTES })
    }

    @Test
    fun `comparison includes all payslip hour rows`() {
        val repo = ShiftRepository()
        populateReferenceWeek(repo, Shift.THIRD)

        val result = service.reconstruct(
            repo = repo,
            input = PayrollSlipInput(
                month = MonthKey(2026, 1),
                regularWorkHours = 105.0,
                nightHours = 24.0,
                overtimeHours = 5.0,
                vacationHours = 40.0,
                holidayHours = 24.0,
                absenceHours = 7.0
            )
        )

        assertEquals(
            listOf(
                "Rad u režiji",
                "Noćni rad",
                "Prekovremeni rad",
                "Godišnji",
                "Bolovanje",
                "Državni praznik",
                "Rad u režiji na DP",
                "Rad nedeljom",
                "Rad na praznik",
                "Izostanci"
            ),
            result.comparison.rows.map { it.label }
        )
    }

    @Test
    fun `february fund validation uses regime work on holiday and ignores premium rows`() {
        val repo = ShiftRepository()
        populateReferenceWeek(repo, Shift.THIRD)

        val result = service.reconstruct(
            repo = repo,
            input = PayrollSlipInput(
                month = MonthKey(2026, 2),
                regularWorkHours = 131.0,
                regularHolidayWorkHours = 8.0,
                nightHours = 39.0,
                overtimeHours = 8.0,
                holidayHours = 8.0,
                sundayHours = 8.0,
                holidayWorkHours = 8.0,
                absenceHours = 13.0
            )
        )

        assertEquals(8 * 60, result.comparison.row("Rad u režiji na DP").targetMinutes)
        assertTrue(result.warnings.none { it.type == PayrollSlipWarningType.INFEASIBLE_TARGET })
        assertEquals(false, result.hasBlockingErrors)
    }

    @Test
    fun `february fund validation does not count premium rows as covered fund`() {
        val repo = ShiftRepository()
        populateReferenceWeek(repo, Shift.THIRD)

        val result = service.reconstruct(
            repo = repo,
            input = PayrollSlipInput(
                month = MonthKey(2026, 2),
                regularWorkHours = 131.0,
                nightHours = 39.0,
                overtimeHours = 8.0,
                holidayHours = 8.0,
                sundayHours = 8.0,
                holidayWorkHours = 8.0,
                absenceHours = 13.0
            )
        )

        assertTrue(result.warnings.any {
            it.type == PayrollSlipWarningType.INFEASIBLE_TARGET &&
                it.message.contains("Fond pokriven listićem je 152h, a fond meseca je 160h.")
        })
        assertEquals(true, result.hasBlockingErrors)
    }

    @Test
    fun `january payslip rows are exact when fund rows cover monthly fund`() {
        val repo = ShiftRepository()
        populateReferenceWeek(repo, Shift.THIRD)

        val result = service.reconstruct(
            repo = repo,
            input = PayrollSlipInput(
                month = MonthKey(2026, 1),
                regularWorkHours = 105.0,
                nightHours = 24.0,
                overtimeHours = 5.0,
                vacationHours = 40.0,
                holidayHours = 24.0,
                absenceHours = 7.0
            )
        )

        assertExactHours(result, "Rad u režiji", 105.0)
        assertExactHours(result, "Noćni rad", 24.0)
        assertExactHours(result, "Prekovremeni rad", 5.0)
        assertExactHours(result, "Godišnji", 40.0)
        assertExactHours(result, "Državni praznik", 24.0)
        assertExactHours(result, "Izostanci", 7.0)
        assertTrue(result.comparison.allHoursExact)
    }

    @Test
    fun `february payslip rows are exact with regime holiday work outside premium fund rows`() {
        val repo = ShiftRepository()
        populateReferenceWeek(repo, Shift.THIRD)

        val result = service.reconstruct(
            repo = repo,
            input = PayrollSlipInput(
                month = MonthKey(2026, 2),
                regularWorkHours = 131.0,
                regularHolidayWorkHours = 8.0,
                nightHours = 39.0,
                overtimeHours = 8.0,
                holidayHours = 8.0,
                sundayHours = 8.0,
                holidayWorkHours = 8.0,
                absenceHours = 13.0
            )
        )

        assertExactHours(result, "Rad u režiji", 131.0)
        assertExactHours(result, "Rad u režiji na DP", 8.0)
        assertExactHours(result, "Državni praznik", 8.0)
        assertExactHours(result, "Noćni rad", 39.0)
        assertExactHours(result, "Rad nedeljom", 8.0)
        assertExactHours(result, "Rad na praznik", 8.0)
        assertExactHours(result, "Prekovremeni rad", 8.0)
        assertExactHours(result, "Izostanci", 13.0)
        assertTrue(result.comparison.allHoursExact)
    }

    @Test
    fun `march payslip rows are exact with sick leave and overtime outside fund`() {
        val repo = ShiftRepository()
        populateReferenceWeek(repo, Shift.THIRD)

        val result = service.reconstruct(
            repo = repo,
            input = PayrollSlipInput(
                month = MonthKey(2026, 3),
                regularWorkHours = 134.0,
                nightHours = 78.0,
                overtimeHours = 3.0,
                sickHours = 40.0,
                absenceHours = 2.0
            )
        )

        assertExactHours(result, "Rad u režiji", 134.0)
        assertExactHours(result, "Noćni rad", 78.0)
        assertExactHours(result, "Prekovremeni rad", 3.0)
        assertExactHours(result, "Bolovanje", 40.0)
        assertExactHours(result, "Izostanci", 2.0)
        assertTrue(result.comparison.allHoursExact)
    }

    @Test
    fun `january 2026 payslip-like input produces preview rows before apply`() {
        val repo = ShiftRepository()
        populateReferenceWeek(repo, Shift.THIRD)

        val result = service.reconstruct(
            repo = repo,
            input = PayrollSlipInput(
                month = MonthKey(2026, 1),
                regularWorkHours = 105.0,
                nightHours = 24.0,
                overtimeHours = 5.0,
                vacationHours = 40.0,
                holidayHours = 24.0,
                absenceHours = 7.0,
                mealGross = 5_320.97,
                kirGross = 6_691.86
            )
        )

        assertEquals(10, result.comparison.rows.size)
        assertEquals(5_320.97, result.additions.mealGross, 0.01)
        assertEquals(6_691.86, result.additions.bonusGross, 0.01)
        assertTrue(repo.getMonthRecords(2026, 1).isEmpty())
    }

    @Test
    fun `reconstruct net preview uses live difficult conditions instead of default calculator`() {
        val repo = ShiftRepository()
        repo.difficultWorkConditionsPercentValue = 35.0
        populateReferenceWeek(repo, Shift.THIRD)
        val input = PayrollSlipInput(
            month = MonthKey(2026, 3),
            regularWorkHours = 8.0
        )

        val result = service.reconstruct(repo, input)
        val records = result.records.map { it.toDayRecord() }
        val grossHourlyRate = repo.getGrossHourlyRateForMonth(2026, 3)
        val defaultSummary = PayCalculator().calculateMonthSummary(
            records = records,
            baseRate = grossHourlyRate,
            brigadeType = BrigadeType.TROBRIGADA,
            summaryYear = 2026,
            summaryMonth = 3,
            payBases = repo.getResolvedPayBasesForWorkMonth(2026, 3)
        )
        val defaultBreakdown = calculateMonthlyNetPayBreakdown(
            summary = defaultSummary,
            grossHourlyRate = grossHourlyRate,
            monthlyFundHours = calculateMonthlyFundHours(2026, 3, CONFIG.pay.defaultHours),
            contributionsPercent = repo.employeeContributionsPercentValue,
            taxRelief = repo.taxReliefValue,
            taxPercent = repo.taxPercentValue,
            grossAdditions = result.additions
        )

        assertTrue(
            result.comparison.calculatedNetBeforeDeductions > defaultBreakdown.netPayBeforeDeductions + 0.01
        )
    }

    private fun populateReferenceWeek(repo: ShiftRepository, shift: Shift) {
        for (day in 4..8) {
            repo.set(2026, 5, day, shift, CONFIG.pay.defaultHours)
        }
    }

    private fun PayrollSlipComparison.row(label: String): PayrollSlipComparisonRow {
        return rows.first { it.label == label }
    }

    private fun assertExactHours(
        result: PayrollSlipReconstructionResult,
        label: String,
        hours: Double
    ) {
        val row = result.comparison.row(label)
        assertEquals(slipHoursToMinutes(hours), row.targetMinutes)
        assertEquals(row.targetMinutes, row.calculatedMinutes)
    }
}
