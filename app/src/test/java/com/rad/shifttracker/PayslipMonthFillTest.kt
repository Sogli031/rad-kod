package com.rad.shifttracker

import java.time.DayOfWeek
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import com.rad.shifttracker.listici.FillDayKind
import com.rad.shifttracker.listici.MonthFillResult
import com.rad.shifttracker.listici.ParsedPayslip
import com.rad.shifttracker.listici.PayslipParseResult
import com.rad.shifttracker.listici.applyMonthFill
import com.rad.shifttracker.listici.buildPayslipReconciliation
import com.rad.shifttracker.listici.isAutomaticPlaceholder
import com.rad.shifttracker.listici.parsePayslip
import com.rad.shifttracker.listici.planMonthFromPayslip
import com.rad.shifttracker.listici.shouldOfferMonthFill
import com.rad.shifttracker.listici.userEnteredDayCount
import com.rad.shifttracker.repo.BatchRecord
import com.rad.shifttracker.repo.ShiftRepository
import com.rad.shifttracker.repo.getMonthlyPayDetails
import com.rad.shifttracker.repo.initialCompBalanceValue
import com.rad.shifttracker.smene.BrigadeType
import com.rad.shifttracker.smene.DayRecord
import com.rad.shifttracker.smene.DayRecordSource
import com.rad.shifttracker.smene.MonthKey
import com.rad.shifttracker.smene.Shift
import com.rad.shifttracker.smene.isSerbianHoliday
import com.rad.shifttracker.listici.ABSENCE_ROUNDING_EXTRA_LABEL
import com.rad.shifttracker.repo.getMonthlyGrossAdditions

class PayslipMonthFillTest {

    /** Sedmična trobrigada kao u stvarnim unosima: 06.07.2026 je nedelja treće smene. */
    private fun repoWithRotation(): ShiftRepository {
        val repo = testRepository()
        val cycle = listOf(Shift.THIRD, Shift.SECOND, Shift.FIRST)
        val anchorMonday = LocalDate.of(2026, 7, 6)
        val batch = mutableListOf<BatchRecord>()
        for (month in listOf(7, 9)) {
            var date = LocalDate.of(2026, month, 1)
            while (date.monthValue == month) {
                if (date.dayOfWeek.value <= DayOfWeek.FRIDAY.value) {
                    val weeks = Math.floorDiv(
                        java.time.temporal.ChronoUnit.DAYS.between(anchorMonday, date.with(DayOfWeek.MONDAY)).toInt(),
                        7
                    )
                    val shift = cycle[Math.floorMod(weeks, 3)]
                    batch += BatchRecord(2026, month, date.dayOfMonth, shift, 8, source = DayRecordSource.AUTO_FILL)
                }
                date = date.plusDays(1)
            }
        }
        repo.setBatch(batch)
        return repo
    }

    private fun august(): ParsedPayslip = (parsePayslip(AUGUST_2026) as PayslipParseResult.Success).payslip

    @Test
    fun augustIsFilledByRotationAndMatchesPayslipExactly() {
        val repo = repoWithRotation()
        val result = planMonthFromPayslip(repo, august())
        assertTrue(result.toString(), result is MonthFillResult.Success)
        result as MonthFillResult.Success

        val hourRows = result.reconciliation.rows.filter { it.isHours }
        assertTrue(hourRows.filterNot { it.matches }.toString(), hourRows.all { it.matches })
        assertEquals(2, result.days.count { it.kind == FillDayKind.VACATION })
        assertEquals(2, result.days.count { it.kind == FillDayKind.PAID_LEAVE })
        // Redovni radni dani prate šablon: 17–21.08. je treća smena.
        val regularThird = result.days.filter { it.kind == FillDayKind.WORK && it.date.dayOfMonth in 17..21 }
        assertTrue(regularThird.all { it.shift == Shift.THIRD })

        applyMonthFill(repo, result)
        val check = buildPayslipReconciliation(repo, august())
        assertTrue(check.rows.filter { it.isHours }.all { it.matches })
        val paidLeave = repo.getMonthRecords(2026, 8).filter { it.isPaidLeave }
        assertEquals(2, paidLeave.size)
        assertTrue(paidLeave.none { it.isBloodDonationLeave })
    }

    /** Šablon upisan samo u mesecu pre ciljnog; [weekOfJuly6] je smena nedelje od 06.07.2026. */
    private fun repoWithTemplateBefore(month: Int, weekOfJuly6: Shift): ShiftRepository {
        val repo = testRepository()
        val cycle = listOf(Shift.THIRD, Shift.SECOND, Shift.FIRST)
        val anchorMonday = LocalDate.of(2026, 7, 6)
        val first = LocalDate.of(2026, month, 1).minusMonths(1)
        val batch = mutableListOf<BatchRecord>()
        var date = first
        while (date.monthValue == first.monthValue) {
            if (date.dayOfWeek.value <= DayOfWeek.FRIDAY.value && !isSerbianHoliday(date.year, date.monthValue, date.dayOfMonth)) {
                val weeks = Math.floorDiv(
                    java.time.temporal.ChronoUnit.DAYS.between(anchorMonday, date.with(DayOfWeek.MONDAY)).toInt(),
                    7
                )
                val shift = cycle[Math.floorMod(weeks + cycle.indexOf(weekOfJuly6), 3)]
                batch += BatchRecord(date.year, date.monthValue, date.dayOfMonth, shift, 8, source = DayRecordSource.AUTO_FILL)
            }
            date = date.plusDays(1)
        }
        repo.setBatch(batch)
        return repo
    }

    private fun payslip2026(month: Int): ParsedPayslip =
        (parsePayslip(requireNotNull(PAYSLIPS_2026[month])) as PayslipParseResult.Success).payslip

    /**
     * Svih 8 stvarnih listića 2026, za sve tri faze rotacije: raspored se nađe, provera sati je
     * tačna, i ostaje tačna posle upisa iako korisnik ima pozitivan saldo sati (izostanak sa
     * listića ne sme da se „pokrije“ saldom). Maj ima 13h rada na praznik (petak + subota).
     */
    @Test
    fun everyRealPayslipOf2026IsFilledExactlyForEveryRotationPhase() {
        for (month in 1..8) {
            val slip = payslip2026(month)
            for (phase in listOf(Shift.THIRD, Shift.SECOND, Shift.FIRST)) {
                val repo = repoWithTemplateBefore(month, phase)
                repo.initialCompBalanceValue = 40
                val result = planMonthFromPayslip(repo, slip)
                assertTrue("$month/$phase: $result", result is MonthFillResult.Success)
                result as MonthFillResult.Success
                applyMonthFill(repo, result)
                val mismatches = buildPayslipReconciliation(repo, slip).rows.filter { it.isHours && !it.matches }
                assertTrue("$month/$phase: $mismatches", mismatches.isEmpty())
                val rounding = slip.extras.firstOrNull { it.label == ABSENCE_ROUNDING_EXTRA_LABEL }?.amount?.abs()
                assertEquals(
                    "$month/$phase",
                    0,
                    (rounding ?: java.math.BigDecimal.ZERO).compareTo(repo.getMonthlyGrossAdditions(2026, month).absencePenaltyGross)
                )
            }
        }
    }

    @Test
    fun juneWithRealRotationUsesWholeAbsentDaysNotOneHourDays() {
        val result = planMonthFromPayslip(repoWithTemplateBefore(6, Shift.THIRD), payslip2026(6)) as MonthFillResult.Success
        // 37h izostanka = 4 cela dana + jedan kraći dan, a ne pet dana od po 1h.
        assertEquals(4, result.days.count { it.kind == FillDayKind.ABSENT })
        assertEquals(1, result.days.count { it.kind == FillDayKind.WORK && it.hours < 8 })
    }

    @Test
    fun unknownHourItemIsReportedInsteadOfGuessed() {
        val lines = requireNotNull(PAYSLIPS_2026[8]) + "14 Verski praznik 8.0 5098.56"
        val slip = (parsePayslip(lines) as PayslipParseResult.Success).payslip
        val result = planMonthFromPayslip(repoWithTemplateBefore(8, Shift.THIRD), slip)
        assertTrue(result is MonthFillResult.Failure)
        assertTrue((result as MonthFillResult.Failure).message.contains("14 Verski praznik"))
    }

    @Test
    fun shortDayWrittenFromPayslipDoesNotDrawOnHourBalance() {
        val record = DayRecord(2026, 8, 6, Shift.SECOND, 3, balanceExempt = true)
        assertEquals(0, record.hoursDeficitMinutesFor(BrigadeType.TROBRIGADA))
        assertEquals(5 * 60, DayRecord(2026, 8, 6, Shift.SECOND, 3).hoursDeficitMinutesFor(BrigadeType.TROBRIGADA))
    }

    @Test
    fun withoutRotationItRefusesInsteadOfGuessing() {
        val result = planMonthFromPayslip(testRepository(), august())
        assertTrue(result is MonthFillResult.Failure)
    }

    /** „Lista“ nudi „Popuni iz liste“ dok mesec ima samo automatske dane (smene iz „Popuni“, praznici). */
    @Test
    fun fillIsOfferedOnlyWhileMonthHasNoUserEntries() {
        val repo = repoWithRotation()
        val month = MonthKey(2026, 8)
        assertTrue(shouldOfferMonthFill(repo, month, buildPayslipReconciliation(repo, august())))

        // Smene iz „Popuni“ i prazan vikend ne računaju se kao upis.
        repo.set(2026, 8, 3, Shift.SECOND, 8, source = DayRecordSource.AUTO_FILL)
        repo.set(2026, 8, 8, Shift.OFF, 0)
        assertEquals(0, userEnteredDayCount(repo, month))
        assertTrue(shouldOfferMonthFill(repo, month, buildPayslipReconciliation(repo, august())))

        // Posle popunjavanja se više ne nudi.
        applyMonthFill(repo, planMonthFromPayslip(repo, august()) as MonthFillResult.Success)
        assertTrue(!shouldOfferMonthFill(repo, month, buildPayslipReconciliation(repo, august())))

        // Ručno upisan dan (npr. godišnji) znači da je mesec upisan.
        val manual = repoWithRotation()
        manual.set(2026, 8, 4, Shift.VACATION, 8)
        assertEquals(1, userEnteredDayCount(manual, month))
        assertTrue(!shouldOfferMonthFill(manual, month, buildPayslipReconciliation(manual, august())))
    }

    @Test
    fun holidayOffDayIsNotAUserEntry() {
        // 11.11.2026 (sreda) je praznik: slobodan dan na praznik, i stari zapis bez oznake „Popuni“.
        assertTrue(DayRecord(2026, 11, 11, Shift.OFF, 0, balanceExempt = true).isAutomaticPlaceholder())
        assertTrue(DayRecord(2026, 11, 11, Shift.OFF, 0, source = DayRecordSource.AUTO_FILL).isAutomaticPlaceholder())
        // Rad na praznik i slobodan običan radni dan jesu upisi.
        assertTrue(!DayRecord(2026, 11, 11, Shift.FIRST, 8).isAutomaticPlaceholder())
        assertTrue(!DayRecord(2026, 11, 12, Shift.OFF, 0).isAutomaticPlaceholder())
    }

    @Test
    fun paidLeaveDayCountsAsPaidLeaveNotSick() {
        val repo = testRepository()
        repo.set(2026, 8, 3, Shift.SICK, 8, source = DayRecordSource.PAID_LEAVE)
        val details = repo.getMonthlyPayDetails(2026, 8)
        assertEquals(8 * 60, details.paidLeaveMinutes)
        assertEquals(0, details.sickMinutes)
        assertTrue(repo.get(2026, 8, 3)!!.isPaidLeave)
    }
}
