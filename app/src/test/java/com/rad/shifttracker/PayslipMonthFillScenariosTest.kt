package com.rad.shifttracker

import java.time.LocalDate
import java.time.YearMonth
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import com.rad.shifttracker.listici.FillDayKind
import com.rad.shifttracker.listici.HoursVec
import com.rad.shifttracker.uvoz.MonthFillOffer
import com.rad.shifttracker.uvoz.MonthFillResult
import com.rad.shifttracker.listici.ParsedPayslip
import com.rad.shifttracker.listici.PayslipParseResult
import com.rad.shifttracker.uvoz.applyMonthFill
import com.rad.shifttracker.uvoz.buildPayslipReconciliation
import com.rad.shifttracker.uvoz.fillAllMonths
import com.rad.shifttracker.uvoz.fillableMonths
import com.rad.shifttracker.uvoz.monthFillOffer
import com.rad.shifttracker.listici.parsePayslip
import com.rad.shifttracker.uvoz.planMonthFromPayslip
import com.rad.shifttracker.uvoz.userEnteredDayCount
import com.rad.shifttracker.listici.workVector
import com.rad.shifttracker.repo.ShiftRepository
import com.rad.shifttracker.repo.getMonthlyPayDetails
import com.rad.shifttracker.repo.savePayslip
import com.rad.shifttracker.smene.MonthKey
import com.rad.shifttracker.smene.Shift
import com.rad.shifttracker.listici.FillDiagnostics
import com.rad.shifttracker.listici.PayslipCheckRow
import com.rad.shifttracker.uvoz.bloodDonationLeaveStarts
import com.rad.shifttracker.uvoz.fillFailureText
import com.rad.shifttracker.repo.recordBloodDonation
import com.rad.shifttracker.smene.DayRecordSource
import com.rad.shifttracker.ui.dialozi.payslipMonthReplaceQuestionText

class PayslipMonthFillScenariosTest {

    private fun payslip2026(month: Int): ParsedPayslip =
        (parsePayslip(requireNotNull(PAYSLIPS_2026[month])) as PayslipParseResult.Success).payslip

    private fun success(result: MonthFillResult): MonthFillResult.Success {
        assertTrue(result.toString(), result is MonthFillResult.Success)
        return result as MonthFillResult.Success
    }

    private fun daysOf(result: MonthFillResult.Success, kind: FillDayKind): List<Int> =
        result.days.filter { it.kind == kind }.map { it.date.dayOfMonth }.sorted()

    // --- rad na praznik po satu (05 kao 23) ---

    @Test
    fun nightShiftBeforeHolidayHasSixHolidayHoursAndTwoRegular() {
        // 10.04.2026. (petak) je praznik: treća smena od četvrtka 22h ima 6 h praznika posle ponoći.
        val vector = workVector(LocalDate.of(2026, 4, 9), Shift.THIRD, 8)
        assertEquals(HoursVec(regular = 2, holiday = 6, premium = 6, night = 8), vector)
    }

    @Test
    fun nightShiftStartingOnHolidayHasTwoHolidayHoursAndSixRegular() {
        // 13.04.2026. (ponedeljak) je praznik: treća smena počinje 22h, pa su na praznik samo 2 h.
        val vector = workVector(LocalDate.of(2026, 4, 13), Shift.THIRD, 8)
        assertEquals(HoursVec(regular = 6, holiday = 2, premium = 2, night = 8), vector)
    }

    @Test
    fun dayShiftOnHolidayIsAllHolidayWork() {
        assertEquals(HoursVec(holiday = 8, premium = 8), workVector(LocalDate.of(2026, 4, 13), Shift.FIRST, 8))
    }

    @Test
    fun payDetailsCountNightBeforeHolidayByTheHourWithoutChangingPay() {
        val nightBefore = testRepository().apply { set(2026, 4, 9, Shift.THIRD, 8) }
        val details = nightBefore.getMonthlyPayDetails(2026, 4)
        assertEquals(2 * 60, details.regularWorkedBaseMinutes)
        assertEquals(6 * 60, details.publicHolidayWorkedBaseMinutes)

        // 01 i 05 imaju istu satnicu: osnovica za svih 8 sati je ista kao za običnu treću smenu.
        assertEquals(details.grossBaseRegular, details.regularWorkedBaseGross + details.publicHolidayWorkedBaseGross, 0.01)
        val plainNight = testRepository().apply { set(2026, 4, 8, Shift.THIRD, 8) }
        assertEquals(plainNight.getMonthlyPayDetails(2026, 4).grossBaseRegular, details.grossBaseRegular, 0.01)
    }

    // --- godišnji preko dana uoči praznika ---

    @Test
    fun vacationMayCoverTheNightShiftBeforeAHoliday() {
        val phase = TEST_ROTATION_CYCLE.first { rotationShiftFor(LocalDate.of(2026, 4, 9), it) == Shift.THIRD }
        val truth = truthMonth(2026, 4, phase, listOf(6, 7, 8, 9).map { TruthDay(it, Shift.VACATION) })
        val slip = payslipFromRepo(truth, 2026, 4)
        assertTrue(hourMismatches(truth, slip).isEmpty())

        val repo = templateRepoBefore(2026, 4, phase)
        val result = success(planMonthFromPayslip(repo, slip))
        assertEquals(4, daysOf(result, FillDayKind.VACATION).size)
        applyMonthFill(repo, result)
        assertTrue(hourMismatches(repo, slip).isEmpty())
    }

    // --- radni vikendi i praznici ---

    @Test
    fun monthWithSeveralWorkedWeekendDaysAndHolidaySundayIsFilled() {
        // Februar 2026: nedelja 15.02. je praznik, uz još subotu i nedelju rada (nekad prevelik prostor za pretragu).
        val truth = truthMonth(
            2026, 2, Shift.FIRST,
            listOf(TruthDay(15, Shift.SECOND), TruthDay(28, Shift.SECOND), TruthDay(22, Shift.SECOND))
        )
        val slip = payslipFromRepo(truth, 2026, 2)
        val repo = templateRepoBefore(2026, 2, Shift.FIRST)
        val result = success(planMonthFromPayslip(repo, slip))
        applyMonthFill(repo, result)
        assertTrue(hourMismatches(repo, slip).isEmpty())
    }

    // --- bolovanje preko praznika ---

    @Test
    fun sickLeaveCoversWeekdayHolidayWithoutPaidHoliday() {
        // Listić 11/2025: 80 h bolovanja preko 11.11. (utorak), bez šifre 12 Državni praznik.
        val sickDays = listOf(5, 6, 7, 10, 11, 12, 13, 14, 17, 18)
        val truth = truthMonth(2025, 11, Shift.FIRST, sickDays.map { TruthDay(it, Shift.SICK, sickRate = 0.65) })
        val slip = payslipFromRepo(truth, 2025, 11)
        assertTrue(hourMismatches(truth, slip).isEmpty())
        assertTrue(!slip.items.containsKey(12))

        val repo = templateRepoBefore(2025, 11, Shift.FIRST)
        val result = success(planMonthFromPayslip(repo, slip))
        assertTrue(11 in daysOf(result, FillDayKind.SICK))
        applyMonthFill(repo, result)
        assertTrue(hourMismatches(repo, slip).isEmpty())
    }

    // --- praznik koji je korisnik sam uključio ili isključio ---

    @Test
    fun holidayAddedByUserIsKeptAndUsedByTheFill() {
        val configure = { repo: ShiftRepository -> repo.setHolidayActive(2026, 8, 17, true); Unit }
        val truth = truthMonth(2026, 8, Shift.SECOND, listOf(TruthDay(17, Shift.OFF, 0, exempt = true)), configure)
        val slip = payslipFromRepo(truth, 2026, 8)
        assertTrue(slip.items.containsKey(12))

        val repo = templateRepoBefore(2026, 8, Shift.SECOND)
        configure(repo)
        val result = success(planMonthFromPayslip(repo, slip))
        applyMonthFill(repo, result)
        assertEquals(true, repo.get(2026, 8, 17)?.holidayOverride)
        assertTrue(hourMismatches(repo, slip).isEmpty())
    }

    @Test
    fun holidayTurnedOffByUserStaysOffAndDayIsWorked() {
        val configure = { repo: ShiftRepository -> repo.setHolidayActive(2026, 5, 1, false); Unit }
        val truth = truthMonth(2026, 5, Shift.THIRD, listOf(TruthDay(1, rotationShiftFor(LocalDate.of(2026, 5, 1), Shift.THIRD))), configure)
        val slip = payslipFromRepo(truth, 2026, 5)

        val repo = templateRepoBefore(2026, 5, Shift.THIRD)
        configure(repo)
        // Isključen praznik nije tvoj upis, pa se popuna nudi odmah.
        assertEquals(0, userEnteredDayCount(repo, MonthKey(2026, 5)))
        val result = success(planMonthFromPayslip(repo, slip))
        applyMonthFill(repo, result)
        assertEquals(false, repo.get(2026, 5, 1)?.holidayOverride)
        assertTrue(hourMismatches(repo, slip).isEmpty())
    }

    // --- ponovna popuna posle ispravljene liste ---

    @Test
    fun correctedPayslipOffersReplacingAlreadyFilledDays() {
        val month = MonthKey(2026, 8)
        val first = payslipFromRepo(truthMonth(2026, 8, Shift.THIRD, listOf(3, 4).map { TruthDay(it, Shift.VACATION) }), 2026, 8)
        val corrected = payslipFromRepo(truthMonth(2026, 8, Shift.THIRD, listOf(3, 4, 5).map { TruthDay(it, Shift.VACATION) }), 2026, 8)

        val repo = templateRepoBefore(2026, 8, Shift.THIRD)
        assertEquals(MonthFillOffer.EMPTY_MONTH, monthFillOffer(repo, month, buildPayslipReconciliation(repo, first)))
        applyMonthFill(repo, success(planMonthFromPayslip(repo, first)))
        assertEquals(MonthFillOffer.NONE, monthFillOffer(repo, month, buildPayslipReconciliation(repo, first)))

        // Lista se zameni ispravljenom (rb.3): časovi se ne slažu, a mesec već ima upisane dane.
        assertEquals(MonthFillOffer.REPLACE, monthFillOffer(repo, month, buildPayslipReconciliation(repo, corrected)))
        val replaced = success(planMonthFromPayslip(repo, corrected))
        applyMonthFill(repo, replaced)
        assertEquals(3, repo.getMonthRecords(2026, 8).count { it.shift == Shift.VACATION })
        assertTrue(hourMismatches(repo, corrected).isEmpty())
    }

    // --- popuni sve ---

    @Test
    fun fillAllFillsEveryMonthWithAListAndNoEntries() {
        val repo = templateRepo(LocalDate.of(2025, 12, 1), LocalDate.of(2026, 9, 30), Shift.THIRD)
        for (month in 1..8) repo.savePayslip(payslip2026(month), withPayBase = false)
        assertEquals((1..8).map { MonthKey(2026, it) }, fillableMonths(repo))

        val result = fillAllMonths(repo)
        assertTrue(result.failed.toString(), result.failed.isEmpty())
        assertEquals(8, result.filled.size)
        for (month in 1..8) assertTrue("$month", hourMismatches(repo, payslip2026(month)).isEmpty())
        assertTrue(fillableMonths(repo).isEmpty())
    }

    @Test
    fun fillAllSkipsMonthsWithUserEntriesAndReportsTheOnesItCannotFill() {
        val repo = templateRepo(LocalDate.of(2025, 12, 1), LocalDate.of(2026, 9, 30), Shift.THIRD)
        for (month in 1..3) repo.savePayslip(payslip2026(month), withPayBase = false)
        repo.set(2026, 2, 4, Shift.VACATION, 8)
        assertEquals(listOf(MonthKey(2026, 1), MonthKey(2026, 3)), fillableMonths(repo))
    }

    // --- prirodniji raspored ---

    @Test
    fun sickLeaveContinuesAcrossTheMonthBoundary() {
        val july = payslipFromRepo(truthMonth(2026, 7, Shift.THIRD, (27..31).map { TruthDay(it, Shift.SICK, sickRate = 0.65) }), 2026, 7)
        val august = payslipFromRepo(truthMonth(2026, 8, Shift.THIRD, (3..7).map { TruthDay(it, Shift.SICK, sickRate = 0.65) }), 2026, 8)
        val repo = templateRepo(LocalDate.of(2026, 6, 1), LocalDate.of(2026, 9, 30), Shift.THIRD)
        repo.savePayslip(july, withPayBase = false)
        repo.savePayslip(august, withPayBase = false)

        assertEquals((27..31).toList(), daysOf(success(planMonthFromPayslip(repo, july)), FillDayKind.SICK))
        assertEquals((3..7).toList(), daysOf(success(planMonthFromPayslip(repo, august)), FillDayKind.SICK))
    }

    @Test
    fun paidLeaveStartsOnTheBloodDonationDay() {
        val truth = truthMonth(
            2026, 8, Shift.THIRD,
            listOf(11, 12).map { TruthDay(it, Shift.SICK, source = DayRecordSource.PAID_LEAVE) }
        )
        val slip = payslipFromRepo(truth, 2026, 8)
        val repo = templateRepoBefore(2026, 8, Shift.THIRD)
        repo.recordBloodDonation(LocalDate.of(2026, 8, 11))
        assertEquals(listOf(11, 12), daysOf(success(planMonthFromPayslip(repo, slip)), FillDayKind.PAID_LEAVE))
    }

    @Test
    fun bloodDonationLeaveStartsAreTheDonationDayAndTheNextWorkday() {
        val friday = LocalDate.of(2026, 8, 14)
        val starts = bloodDonationLeaveStarts(listOf(friday, LocalDate.of(2026, 7, 3)), YearMonth.of(2026, 8))
        // Petak i ponedeljak; jul se ne računa.
        assertEquals(setOf(friday, LocalDate.of(2026, 8, 17)), starts)
    }

    @Test
    fun absencesAreSpreadThroughTheMonthNotBunchedAtTheStart() {
        val repo = templateRepoBefore(2026, 6, Shift.THIRD)
        val result = success(planMonthFromPayslip(repo, payslip2026(6)))
        val absent = daysOf(result, FillDayKind.ABSENT)
        assertTrue(absent.toString(), absent.size >= 3)
        assertTrue(absent.toString(), absent.max() - absent.min() >= 10)
    }

    // --- poruke kad popuna ne uspe ---

    @Test
    fun fundOnThePayslipMustBeTheFundOfTheMonth() {
        val lines = requireNotNull(PAYSLIPS_2026[8]).map { line ->
            if (line.startsWith("Fond za mesec")) line.replace(Regex("Fond za mesec : \\d+"), "Fond za mesec : 100") else line
        }
        val slip = (parsePayslip(lines) as PayslipParseResult.Success).payslip
        val result = planMonthFromPayslip(templateRepoBefore(2026, 8, Shift.THIRD), slip)
        assertTrue(result is MonthFillResult.Failure)
        assertTrue((result as MonthFillResult.Failure).message.contains("Fond na listi (100 h)"))
    }

    @Test
    fun failureTextNamesTheClosestMismatchOrTheReason() {
        val month = MonthKey(2026, 4)
        val row = PayslipCheckRow("Izostanci (95)", "18 h", "24 h", matches = false, isHours = true)
        val closest = fillFailureText(month, FillDiagnostics(), listOf(row))
        assertTrue(closest, closest.contains("Izostanci (95) 18 h umesto 24 h"))

        val many = fillFailureText(month, FillDiagnostics().apply { tooManySpecialDays = true }, null)
        assertTrue(many, many.contains("previše radnih vikenda"))
        val holidays = fillFailureText(month, FillDiagnostics().apply { noSpecialCombination = true }, null)
        assertTrue(holidays, holidays.contains("praznik"))
        assertTrue(fillFailureText(month, FillDiagnostics(), null).endsWith("Mesec nije menjan."))
    }

    @Test
    fun replaceQuestionUsesTheRightWordForm() {
        assertTrue(payslipMonthReplaceQuestionText(MonthKey(2026, 8), 1).contains("1 upisan dan"))
        assertTrue(payslipMonthReplaceQuestionText(MonthKey(2026, 8), 21).contains("21 upisan dan"))
        assertTrue(payslipMonthReplaceQuestionText(MonthKey(2026, 8), 11).contains("11 upisanih dana"))
        assertTrue(payslipMonthReplaceQuestionText(MonthKey(2026, 8), 5).contains("5 upisanih dana"))
    }
}
