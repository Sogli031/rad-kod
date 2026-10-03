package com.rad.shifttracker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.LocalDate

class ShiftSchedulerTest {

    private lateinit var scheduler: ShiftScheduler

    @Before
    fun setUp() {
        // Default cycle: [3, 2, 1] = [THIRD, SECOND, FIRST]
        scheduler = ShiftScheduler(
            cycle = listOf(3, 2, 1),
            defaultHours = 8
        )
    }

    // ============= getShiftForDate tests =============

    @Test
    fun `getShiftForDate returns null for weekend`() {
        val saturday = LocalDate.of(2025, 1, 4) // Saturday
        val refDate = LocalDate.of(2025, 1, 6) // Monday
        val result = scheduler.getShiftForDate(saturday, refDate, Shift.FIRST)
        assertNull(result)
    }

    @Test
    fun `getShiftForDate returns null for Sunday`() {
        val sunday = LocalDate.of(2025, 1, 5) // Sunday
        val refDate = LocalDate.of(2025, 1, 6) // Monday
        val result = scheduler.getShiftForDate(sunday, refDate, Shift.FIRST)
        assertNull(result)
    }

    @Test
    fun `getShiftForDate returns same shift for same week`() {
        val monday = LocalDate.of(2025, 1, 6) // Monday
        val friday = LocalDate.of(2025, 1, 10) // Friday same week
        val result = scheduler.getShiftForDate(friday, monday, Shift.FIRST)
        assertEquals(Shift.FIRST, result)
    }

    @Test
    fun `getShiftForDate rotates shift after one week`() {
        val refMonday = LocalDate.of(2025, 1, 6) // Monday
        val nextMonday = LocalDate.of(2025, 1, 13) // Next Monday

        // Cycle is [3, 2, 1] = [THIRD, SECOND, FIRST]
        // If reference is FIRST (index 2), after 1 week -> index (2+1) % 3 = 0 -> THIRD
        val result = scheduler.getShiftForDate(nextMonday, refMonday, Shift.FIRST)
        assertEquals(Shift.THIRD, result)
    }

    @Test
    fun `getShiftForDate rotates through full cycle`() {
        val refMonday = LocalDate.of(2025, 1, 6) // Monday

        // FIRST is at index 2 in cycle [3, 2, 1]
        // Week 0: FIRST (index 2)
        // Week 1: THIRD (index 0)
        // Week 2: SECOND (index 1)
        // Week 3: FIRST (index 2) - back to start

        val week0 = scheduler.getShiftForDate(refMonday, refMonday, Shift.FIRST)
        val week1 = scheduler.getShiftForDate(refMonday.plusWeeks(1), refMonday, Shift.FIRST)
        val week2 = scheduler.getShiftForDate(refMonday.plusWeeks(2), refMonday, Shift.FIRST)
        val week3 = scheduler.getShiftForDate(refMonday.plusWeeks(3), refMonday, Shift.FIRST)

        assertEquals(Shift.FIRST, week0)
        assertEquals(Shift.THIRD, week1)
        assertEquals(Shift.SECOND, week2)
        assertEquals(Shift.FIRST, week3)
    }

    @Test
    fun `getShiftForDate returns null for non-work shifts`() {
        val monday = LocalDate.of(2025, 1, 6)
        val result = scheduler.getShiftForDate(monday, monday, Shift.SICK)
        assertNull(result)
    }

    @Test
    fun `getShiftForDate handles past dates`() {
        val refMonday = LocalDate.of(2025, 1, 13) // Monday
        val prevMonday = LocalDate.of(2025, 1, 6) // Previous Monday

        // If reference is FIRST (index 2), going back 1 week -> index (2-1) % 3 = 1 -> SECOND
        val result = scheduler.getShiftForDate(prevMonday, refMonday, Shift.FIRST)
        assertEquals(Shift.SECOND, result)
    }

    // ============= Helper tests =============

    @Test
    fun `weekday detection works correctly`() {
        assertEquals(true, Weekday.MONDAY.isWorkday)
        assertEquals(true, Weekday.FRIDAY.isWorkday)
        assertEquals(false, Weekday.SATURDAY.isWorkday)
        assertEquals(false, Weekday.SUNDAY.isWorkday)

        assertEquals(false, Weekday.MONDAY.isWeekend)
        assertEquals(false, Weekday.FRIDAY.isWeekend)
        assertEquals(true, Weekday.SATURDAY.isWeekend)
        assertEquals(true, Weekday.SUNDAY.isWeekend)
    }

    @Test
    fun `Weekday from LocalDate works correctly`() {
        val monday = LocalDate.of(2025, 1, 6)
        val saturday = LocalDate.of(2025, 1, 4)
        val sunday = LocalDate.of(2025, 1, 5)

        assertEquals(Weekday.MONDAY, Weekday.from(monday))
        assertEquals(Weekday.SATURDAY, Weekday.from(saturday))
        assertEquals(Weekday.SUNDAY, Weekday.from(sunday))
    }

    // ============= Shift enum tests =============

    @Test
    fun `Shift workShifts returns only work shifts`() {
        val workShifts = Shift.workShifts()
        assertEquals(3, workShifts.size)
        assertEquals(true, workShifts.contains(Shift.FIRST))
        assertEquals(true, workShifts.contains(Shift.SECOND))
        assertEquals(true, workShifts.contains(Shift.THIRD))
        assertEquals(false, workShifts.contains(Shift.OFF))
        assertEquals(false, workShifts.contains(Shift.SICK))
        assertEquals(false, workShifts.contains(Shift.VACATION))
    }

    @Test
    fun `Shift fromValue works correctly`() {
        assertEquals(Shift.OFF, Shift.fromValue(0))
        assertEquals(Shift.FIRST, Shift.fromValue(1))
        assertEquals(Shift.SECOND, Shift.fromValue(2))
        assertEquals(Shift.THIRD, Shift.fromValue(3))
        assertEquals(Shift.SICK, Shift.fromValue(4))
        assertEquals(Shift.VACATION, Shift.fromValue(5))
        assertEquals(Shift.OFF, Shift.fromValue(99)) // Invalid defaults to OFF
    }

    // ============= BrigadeType tests =============

    @Test
    fun `BrigadeType fromValue works correctly`() {
        assertEquals(BrigadeType.TROBRIGADA, BrigadeType.fromValue("trob"))
        assertEquals("cetv", BrigadeType.fromValue("cetv").value)
        assertEquals(BrigadeType.TROBRIGADA, BrigadeType.fromValue(null))
        assertEquals(BrigadeType.TROBRIGADA, BrigadeType.fromValue("invalid"))
    }

    // ============= OvertimeType tests =============

    @Test
    fun `OvertimeType fromValue works correctly`() {
        assertEquals(OvertimeType.PAID, OvertimeType.fromValue(0))
        assertEquals(OvertimeType.COMP_TIME, OvertimeType.fromValue(1))
        assertEquals(OvertimeType.PAID, OvertimeType.fromValue(99)) // Invalid defaults to PAID
    }

    // ============= DayRecord tests =============

    @Test
    fun `DayRecord clamps hours to valid range`() {
        val record = DayRecord(2025, 1, 6, Shift.FIRST, 30) // 30 hours - invalid
        assertEquals(24, record.hours)
    }

    @Test
    fun `DayRecord clamps negative hours to 0`() {
        val record = DayRecord(2025, 1, 6, Shift.FIRST, -5)
        assertEquals(0, record.hours)
    }

    @Test
    fun `DayRecord totalHours includes double shift`() {
        val record = DayRecord(
            2025, 1, 6, Shift.FIRST, 8,
            shift2 = Shift.SECOND,
            hours2 = 4
        )
        assertEquals(12, record.totalHours)
    }

    @Test
    fun `DayRecord isDoubleShift detects double shift`() {
        val single = DayRecord(2025, 1, 6, Shift.FIRST, 8)
        val double = DayRecord(
            2025, 1, 6, Shift.FIRST, 8,
            shift2 = Shift.SECOND,
            hours2 = 4
        )

        assertEquals(false, single.isDoubleShift)
        assertEquals(true, double.isDoubleShift)
    }

    @Test
    fun `DayRecord isWorkDay detects work days`() {
        val workDay = DayRecord(2025, 1, 6, Shift.FIRST, 8)
        val offDay = DayRecord(2025, 1, 6, Shift.OFF, 0)
        val sickDay = DayRecord(2025, 1, 6, Shift.SICK, 8)

        assertEquals(true, workDay.isWorkDay)
        assertEquals(false, offDay.isWorkDay)
        assertEquals(false, sickDay.isWorkDay)
    }

    @Test
    fun `DayRecord compTimeEarned calculates correctly`() {
        val noCompTime = DayRecord(
            2025, 1, 6, Shift.FIRST, 8,
            shift2 = Shift.SECOND,
            hours2 = 4,
            overtimeType = OvertimeType.PAID
        )
        val withCompTime = DayRecord(
            2025, 1, 6, Shift.FIRST, 8,
            shift2 = Shift.SECOND,
            hours2 = 4,
            overtimeType = OvertimeType.COMP_TIME
        )

        assertEquals(0, noCompTime.compTimeEarned)
        assertEquals(4, withCompTime.compTimeEarned)
    }

    @Test
    fun `DayRecord hoursDeficit calculates correctly`() {
        val fullDay = DayRecord(2025, 1, 6, Shift.FIRST, 8) // Monday, 8 hours
        val shortDay = DayRecord(2025, 1, 6, Shift.FIRST, 6) // Monday, 6 hours
        val weekend = DayRecord(2025, 1, 4, Shift.FIRST, 6) // Saturday, 6 hours

        assertEquals(0, fullDay.hoursDeficit)
        assertEquals(2, shortDay.hoursDeficit)
        assertEquals(0, weekend.hoursDeficit) // No deficit on weekends
    }

    @Test
    fun `DayRecord sickPayRate is null for non-sick shifts`() {
        val record = DayRecord(2025, 1, 6, Shift.FIRST, 8, sickPayRate = 0.65)
        assertNull(record.sickPayRate)
    }

    @Test
    fun `DayRecord sickPayRate is preserved for sick shift`() {
        val record = DayRecord(2025, 1, 6, Shift.SICK, 8, sickPayRate = 0.80)
        assertEquals(0.80, record.sickPayRate!!, 0.01)
    }

    @Test
    fun `DayRecord sickPayRate clamps to 1 max`() {
        val record = DayRecord(2025, 1, 6, Shift.SICK, 8, sickPayRate = 1.5)
        assertEquals(1.0, record.sickPayRate!!, 0.01)
    }

    // ============= MonthKey tests =============

    @Test
    fun `MonthKey comparison works correctly`() {
        val jan2025 = MonthKey(2025, 1)
        val feb2025 = MonthKey(2025, 2)
        val jan2026 = MonthKey(2026, 1)

        assertEquals(true, jan2025 < feb2025)
        assertEquals(true, feb2025 < jan2026)
        assertEquals(true, jan2025 < jan2026)
        assertEquals(false, feb2025 < jan2025)
    }

    // ============= Mesec koji počinje u nedelju =============

    @Test
    fun `getShiftForDate mesec koji počinje u nedelju`() {
        // Jun 2025 počinje u nedelju
        val sunday = LocalDate.of(2025, 6, 1) // Nedelja
        val monday = LocalDate.of(2025, 6, 2) // Ponedeljak

        val sundayResult = scheduler.getShiftForDate(sunday, monday, Shift.FIRST)
        val mondayResult = scheduler.getShiftForDate(monday, monday, Shift.FIRST)

        // Nedelja se preskače
        assertNull(sundayResult)
        // Ponedeljak je radni dan
        assertEquals(Shift.FIRST, mondayResult)
    }

    @Test
    fun `getShiftForDate mesec koji počinje u subotu`() {
        // Mart 2025 počinje u subotu
        val saturday = LocalDate.of(2025, 3, 1) // Subota
        val monday = LocalDate.of(2025, 3, 3) // Ponedeljak

        val saturdayResult = scheduler.getShiftForDate(saturday, monday, Shift.SECOND)
        assertNull(saturdayResult)

        val mondayResult = scheduler.getShiftForDate(monday, monday, Shift.SECOND)
        assertEquals(Shift.SECOND, mondayResult)
    }

    // ============= Rotacija preko granice meseca =============

    @Test
    fun `getShiftForDate rotacija prelazi iz jednog meseca u drugi`() {
        val refMonday = LocalDate.of(2025, 1, 27) // Poslednji ponedeljak januara
        val nextMonday = LocalDate.of(2025, 2, 3) // Prvi ponedeljak februara

        // FIRST je na indexu 2 u ciklusu [3, 2, 1]
        // Posle 1 nedelje: index (2+1) % 3 = 0 -> THIRD
        val result = scheduler.getShiftForDate(nextMonday, refMonday, Shift.FIRST)
        assertEquals(Shift.THIRD, result)
    }

    // ============= Referentni datum koji nije ponedeljak =============

    @Test
    fun `getShiftForDate referentni datum sreda iste nedelje`() {
        val refWednesday = LocalDate.of(2025, 1, 8) // Sreda
        val monday = LocalDate.of(2025, 1, 6) // Ponedeljak iste nedelje

        // Oba su u istoj nedelji, pa treba ista smena
        val result = scheduler.getShiftForDate(monday, refWednesday, Shift.SECOND)
        assertEquals(Shift.SECOND, result)
    }

    @Test
    fun `getShiftForDate referentni datum petak sledeće nedelje rotira`() {
        val refFriday = LocalDate.of(2025, 1, 10) // Petak
        val nextMonday = LocalDate.of(2025, 1, 13) // Ponedeljak sledeće nedelje

        // Cycle [3, 2, 1]. SECOND je index 1. Posle 1 nedelje: (1+1) % 3 = 2 -> FIRST
        val result = scheduler.getShiftForDate(nextMonday, refFriday, Shift.SECOND)
        assertEquals(Shift.FIRST, result)
    }

    @Test
    fun `getAutoFillReferenceDate za subotu vraca prvi radni dan sledece sedmice`() {
        val saturday = LocalDate.of(2026, 5, 9)

        val result = scheduler.getAutoFillReferenceDate(saturday)

        assertEquals(LocalDate.of(2026, 5, 11), result)
    }

    @Test
    fun `populateMonthsAhead vikend vezuje za referentnu radnu sedmicu`() {
        val repo = testRepository()
        val saturday = LocalDate.of(2026, 5, 9)

        scheduler.populateMonthsAhead(repo, saturday, Shift.THIRD, monthsAhead = 1)

        assertEquals(Shift.THIRD, repo.get(2026, 5, 4)!!.shift)
        assertEquals(Shift.SECOND, repo.get(2026, 5, 11)!!.shift)
        assertEquals(Shift.FIRST, repo.get(2026, 5, 18)!!.shift)
    }

    @Test
    fun `populateMonthsAhead popunjava tekuci i buduce mesece i obrce redosled smena`() {
        val repo = testRepository()
        val referenceDate = LocalDate.of(2026, 5, 4) // Ponedeljak

        scheduler.populateMonthsAhead(repo, referenceDate, Shift.THIRD, monthsAhead = 1)

        assertEquals(Shift.THIRD, repo.get(2026, 5, 4)!!.shift)
        assertEquals(Shift.SECOND, repo.get(2026, 5, 11)!!.shift)
        assertEquals(Shift.FIRST, repo.get(2026, 5, 18)!!.shift)
        assertNull(repo.get(2026, 4, 27))
        assertNull(repo.get(2026, 4, 20))
        assertNull(repo.get(2026, 4, 14))
        assertEquals(Shift.SECOND, repo.get(2026, 6, 1)!!.shift)
    }

    @Test
    fun `populateMonthsAhead ne popunjava prethodne mesece`() {
        val repo = testRepository()
        val referenceDate = LocalDate.of(2026, 5, 4)

        scheduler.populateMonthsAhead(repo, referenceDate, Shift.THIRD, monthsAhead = 1)

        assertNull(repo.get(2025, 5, 5))
        assertNull(repo.get(2025, 4, 30))
        assertNotNull(repo.get(2026, 5, 4))
        assertNotNull(repo.get(2026, 6, 1))
        assertNull(repo.get(2026, 7, 1))
    }

    @Test
    fun `populateMonthsAhead upisuje samo tekuca i buduca praznicna OFF polja`() {
        val repo = testRepository()
        val referenceDate = LocalDate.of(2026, 3, 2)

        scheduler.populateMonthsAhead(repo, referenceDate, Shift.THIRD, monthsAhead = 2)

        val previousHoliday = repo.get(2026, 1, 1)
        val futureHoliday = repo.get(2026, 5, 1)!!

        assertNull(previousHoliday)

        assertEquals(Shift.OFF, futureHoliday.shift)
        assertEquals(0, futureHoliday.hours)
        assertEquals(true, futureHoliday.isHoliday)
        assertEquals(0, futureHoliday.hoursDeficit)
    }

    @Test
    fun `populateMonthsAhead brise prethodne automatske praznicne OFF zapise`() {
        val repo = testRepository()
        repo.setBatch(
            listOf(
                BatchRecord(2026, 4, 9, Shift.FIRST, 8),
                BatchRecord(2026, 4, 10, Shift.OFF, 0, balanceExempt = true)
            )
        )

        scheduler.populateMonthsAhead(repo, LocalDate.of(2026, 5, 4), Shift.THIRD, monthsAhead = 1)

        assertNotNull(repo.get(2026, 4, 9))
        assertNull(repo.get(2026, 4, 10))
    }

    @Test
    fun `populateMonthsAhead ne gazi rucne unose`() {
        val repo = testRepository()
        val referenceDate = LocalDate.of(2026, 5, 4) // ponedeljak
        repo.set(2026, 5, 6, Shift.VACATION, 8)
        repo.set(
            year = 2026,
            month = 5,
            day = 7,
            shift = Shift.FIRST,
            hours = 8,
            shift2 = Shift.SECOND,
            hours2 = 4
        )

        scheduler.populateMonthsAhead(repo, referenceDate, Shift.THIRD, monthsAhead = 0)

        // Ručni unosi ostaju netaknuti, ostali dani su popunjeni.
        assertEquals(Shift.VACATION, repo.get(2026, 5, 6)?.shift)
        assertEquals(Shift.SECOND, repo.get(2026, 5, 7)?.shift2)
        assertEquals(4, repo.get(2026, 5, 7)?.hours2)
        assertEquals(Shift.THIRD, repo.get(2026, 5, 4)?.shift)
    }

    @Test
    fun `populateMonthsAhead cetvorobrigada radi praznike po rasporedu`() {
        val repo = testRepository()
        val referenceDate = LocalDate.of(2026, 4, 1)

        // Livnica, 25.09.2026. = drugi dan treće smene (indeks 5 u ciklusu 1-1-2-2-3-3-0-0).
        scheduler.populateMonthsAhead(
            repo = repo,
            referenceDate = referenceDate,
            settings = ShiftFillSettings(
                mode = ShiftFillMode.FOUR_BRIGADE,
                anchorDate = LocalDate.of(2026, 9, 25),
                selectedShift = Shift.THIRD,
                cycleDayIndex = 5
            ),
            monthsAhead = 0
        )

        // Uskrs 2026: 10.04. (pet) i 11.04. (sub) treća smena, 12. i 13.04. slobodni po ciklusu.
        assertEquals(Shift.THIRD, repo.get(2026, 4, 10)!!.shift)
        assertTrue(repo.get(2026, 4, 10)!!.workedOnHoliday)
        assertEquals(Shift.OFF, repo.get(2026, 4, 13)!!.shift)
        assertEquals(0, repo.get(2026, 4, 13)!!.hoursDeficitFor(BrigadeType.CETVOROBRIGADA))
        // Listić 04/2026: rad na praznik 14 h (6 + 2 na petak, 6 na subotu), državni praznik 8 h (ponedeljak).
        val details = repo.getMonthlyPayDetails(2026, 4)
        val holidayPremiumMinutes = repo.getMonthRecords(2026, 4).sumOf { record ->
            paidWorkMinuteSegments(record).sumOf { (shift, minutes) -> record.holidayPremiumMinutes(shift, minutes) }
        }
        assertEquals(14 * 60, holidayPremiumMinutes)
        assertEquals("05 po satu, kao na listiću", 14 * 60, details.publicHolidayWorkedBaseMinutes)
        assertEquals(8 * 60, details.paidHolidayMinutes)
    }

    @Test
    fun `populateMonthsAhead cetvorobrigada ne radi prvu smenu na praznik`() {
        val repo = testRepository()
        scheduler.populateMonthsAhead(
            repo = repo,
            referenceDate = LocalDate.of(2026, 2, 1),
            settings = ShiftFillSettings(
                mode = ShiftFillMode.FOUR_BRIGADE,
                anchorDate = LocalDate.of(2026, 9, 25),
                selectedShift = Shift.THIRD,
                cycleDayIndex = 5
            ),
            monthsAhead = 0
        )

        // Listić 02/2026: 17.02. (uto) je po ciklusu prva smena → državni praznik, uz 16.02. (slobodan po ciklusu).
        assertEquals(Shift.OFF, repo.get(2026, 2, 17)!!.shift)
        val details = repo.getMonthlyPayDetails(2026, 2)
        assertEquals(0, details.publicHolidayWorkedBaseMinutes)
        assertEquals(16 * 60, details.paidHolidayMinutes)
        assertEquals(144 * 60, details.regularWorkedBaseMinutes)
    }

    @Test
    fun `populateMonthsAhead ne upisuje nista za smenu koja nije u starom ciklusu`() {
        val repo = testRepository()
        val referenceDate = LocalDate.of(2026, 5, 4)

        val count = scheduler.populateMonthsAhead(repo, referenceDate, Shift.OFF, monthsAhead = 1)

        assertEquals(0, count)
        assertNull(repo.get(2026, 5, 4))
    }

    @Test
    fun `populateMonthsAhead cetvorobrigada prebacuje repo na cetvorobrigadni obracun`() {
        val repo = testRepository()
        val referenceDate = LocalDate.of(2026, 5, 4)

        scheduler.populateMonthsAhead(
            repo = repo,
            referenceDate = referenceDate,
            settings = ShiftFillSettings(
                mode = ShiftFillMode.FOUR_BRIGADE,
                anchorDate = referenceDate,
                selectedShift = Shift.OFF,
                cycleDayIndex = 6
            ),
            monthsAhead = 1
        )

        assertEquals("cetv", repo.brigadeTypeValue.value)
    }

    @Test
    fun `populateMonthsAhead cetvorobrigada nedelju i prvu smenu u ponedeljak upisuje kao off bez minusa`() {
        val repo = testRepository()
        val referenceDate = LocalDate.of(2026, 5, 10) // Nedelja u prvoj smeni ciklusa

        scheduler.populateMonthsAhead(
            repo = repo,
            referenceDate = referenceDate,
            settings = ShiftFillSettings(
                mode = ShiftFillMode.FOUR_BRIGADE,
                anchorDate = referenceDate,
                selectedShift = Shift.FIRST,
                cycleDayIndex = 0
            ),
            monthsAhead = 0
        )

        val sunday = repo.get(2026, 5, 10)
        val monday = repo.get(2026, 5, 11)
        val summary = repo.getSummary(2026, 5)

        assertNotNull(sunday)
        assertNotNull(monday)
        assertEquals(Shift.OFF, sunday!!.shift)
        assertEquals(0, sunday.hours)
        assertEquals(0, sunday.hoursDeficitFor(BrigadeType.CETVOROBRIGADA))
        assertEquals(Shift.OFF, monday!!.shift)
        assertEquals(0, monday.hours)
        assertEquals(0, monday.hoursDeficitFor(BrigadeType.CETVOROBRIGADA))
        assertEquals(0, summary.compTimeDeficit)
        assertEquals(0, summary.compTimeNet)
        assertEquals(0, summary.compTimeEnding)
    }

    @Test
    fun `populateMonthsAhead trobrigada prebacuje repo na trobrigadni obracun`() {
        val repo = testRepository()
        repo.brigadeTypeValue = BrigadeType.fromValue("cetv")
        val referenceDate = LocalDate.of(2026, 5, 4)

        scheduler.populateMonthsAhead(
            repo = repo,
            referenceDate = referenceDate,
            settings = ShiftFillSettings(
                mode = ShiftFillMode.THREE_BRIGADE,
                anchorDate = referenceDate,
                selectedShift = Shift.THIRD
            ),
            monthsAhead = 1
        )

        assertEquals(BrigadeType.TROBRIGADA, repo.brigadeTypeValue)
    }

    @Test
    fun `previewVacationRange preskace vikende praznike i postojece bolovanje`() {
        val repo = testRepository()
        repo.set(2026, 1, 6, Shift.SICK, 8)

        val preview = scheduler.previewVacationRange(repo, 2026, 1, 1, 7)

        assertEquals(listOf(LocalDate.of(2026, 1, 5)), preview.countedDates)
        assertEquals(1, preview.countedDays)
        assertEquals(2, preview.skippedWeekendDays)
        assertEquals(3, preview.skippedHolidayDays)
        assertEquals(1, preview.skippedExistingSickDays)
    }

    @Test
    fun `applyVacationRange upisuje samo dane koji se racunaju`() {
        val repo = testRepository()
        repo.set(2026, 1, 6, Shift.SICK, 8)

        val count = scheduler.applyVacationRange(repo, 2026, 1, 1, 7)

        assertEquals(1, count)
        assertEquals(Shift.VACATION, repo.get(2026, 1, 5)!!.shift)
        assertEquals(Shift.SICK, repo.get(2026, 1, 6)!!.shift)
        assertNull(repo.get(2026, 1, 7))
        assertNull(repo.get(2026, 1, 1))
    }

    @Test
    fun `applyVacationRange blokira godisnji pre jula kada nema starog odmora`() {
        val repo = testRepository()
        repo.setVacationYearBalance(
            VacationYearBalance(
                year = 2026,
                trackingStartDate = LocalDate.of(2026, 6, 2),
                carryoverDaysInitial = 0.0,
                currentYearDaysTotal = 20.0,
                currentYearDaysUsedBeforeAvailability = 0.0,
                currentYearAvailableFrom = LocalDate.of(2026, 7, 1),
                manualBaseline = true
            )
        )

        val preview = scheduler.previewVacationRange(repo, 2026, 6, 8, 8)
        val count = scheduler.applyVacationRange(repo, 2026, 6, 8, 8)

        assertEquals(false, preview.requestValidation.isAllowed)
        assertTrue(preview.warnings.any { it.contains("Novi odmor može da se koristi od 01.07.2026.") })
        assertEquals(0, count)
        assertNull(repo.get(2026, 6, 8))
    }

    // ============= Edge case-ovi =============

    @Test
    fun `DayRecord weekday from za 1 januar 2025 sreda`() {
        val record = DayRecord(2025, 1, 1, Shift.FIRST, 8)
        assertEquals(Weekday.WEDNESDAY, record.weekday)
    }

    @Test
    fun `getShiftForDate OFF smena nema u ciklusu`() {
        val monday = LocalDate.of(2025, 1, 6)
        val result = scheduler.getShiftForDate(monday, monday, Shift.OFF)
        // OFF (value 0) nije u ciklusu [3, 2, 1], vraca null
        assertNull(result)
    }

    @Test
    fun `getShiftForDate VACATION smena nema u ciklusu`() {
        val monday = LocalDate.of(2025, 1, 6)
        val result = scheduler.getShiftForDate(monday, monday, Shift.VACATION)
        assertNull(result)
    }

    // ============= Dugačak period unapred =============

    @Test
    fun `getShiftForDate 52 nedelje unapred vraca se na početak ciklusa`() {
        val refMonday = LocalDate.of(2025, 1, 6)
        // 52 nedelje = 17 * 3 + 1 ciklus (3 nedelje po ciklusu)
        // Posle 51 nedelje (17 ciklusa): isto kao početak
        // Posle 52 nedelje: 1 pozicija dalje

        val result51 = scheduler.getShiftForDate(refMonday.plusWeeks(51), refMonday, Shift.FIRST)
        val result0 = scheduler.getShiftForDate(refMonday, refMonday, Shift.FIRST)
        // 51 = 17 * 3, pa je isti kao početak
        assertEquals(result0, result51)
    }

    // ============= DayRecord sickPayRate edge case-ovi =============

    @Test
    fun `DayRecord sickPayRate nula se postavlja na null`() {
        val record = DayRecord(2025, 1, 6, Shift.SICK, 8, sickPayRate = 0.0)
        assertNull(record.sickPayRate)
    }

    @Test
    fun `DayRecord sickPayRate negativna se postavlja na null`() {
        val record = DayRecord(2025, 1, 6, Shift.SICK, 8, sickPayRate = -0.5)
        assertNull(record.sickPayRate)
    }

    @Test
    fun `DayRecord compHoursUsed se klampuje`() {
        val record = DayRecord(2025, 1, 6, Shift.FIRST, 8, compHoursUsed = 30)
        assertEquals(24, record.compHoursUsed)
    }

    @Test
    fun `DayRecord compHoursUsed negativno se klampuje na 0`() {
        val record = DayRecord(2025, 1, 6, Shift.FIRST, 8, compHoursUsed = -5)
        assertEquals(0, record.compHoursUsed)
    }

    @Test
    fun `DayRecord hours2 se klampuje na 0-24`() {
        val record = DayRecord(
            2025, 1, 6, Shift.FIRST, 8,
            shift2 = Shift.SECOND,
            hours2 = 30
        )
        assertEquals(24, record.hours2)
    }
}
