package com.rad.shifttracker

import java.time.LocalDate
import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import com.rad.shifttracker.godisnji.VacationWarnings
import com.rad.shifttracker.godisnji.VacationYearBalance
import com.rad.shifttracker.godisnji.calculateVacationBalanceSummary
import com.rad.shifttracker.godisnji.validateVacationRequestAvailability

class VacationTrackingTest {
    @Test
    fun `junski godisnji trosi stari odmor i kada je stanje uneto kasnije`() {
        val balance = VacationYearBalance(
            year = 2026,
            trackingStartDate = LocalDate.of(2026, 6, 15),
            carryoverDaysInitial = 4.0,
            currentYearDaysTotal = 24.0,
            currentYearDaysUsedBeforeAvailability = 0.0,
            currentYearAvailableFrom = LocalDate.of(2026, 7, 1),
            manualBaseline = true
        )

        val summary = calculateVacationBalanceSummary(
            balance = balance,
            vacationDates = listOf(LocalDate.of(2026, 6, 1)),
            today = LocalDate.of(2026, 6, 20)
        )

        assertEquals(3.0, summary.carryoverRemaining, 0.01)
        assertEquals(1.0, summary.carryoverUsed, 0.01)
        assertEquals(0.0, summary.currentYearUsed, 0.01)
        assertEquals(24.0, summary.currentYearRemaining, 0.01)
    }

    @Test
    fun `junski godisnji prvo potrosi sav stari odmor pa tek onda novi`() {
        val balance = VacationYearBalance(
            year = 2026,
            trackingStartDate = LocalDate.of(2026, 6, 15),
            carryoverDaysInitial = 4.0,
            currentYearDaysTotal = 24.0,
            currentYearDaysUsedBeforeAvailability = 0.0,
            currentYearAvailableFrom = LocalDate.of(2026, 7, 1),
            manualBaseline = true
        )

        val summary = calculateVacationBalanceSummary(
            balance = balance,
            vacationDates = listOf(
                LocalDate.of(2026, 6, 1),
                LocalDate.of(2026, 6, 2),
                LocalDate.of(2026, 6, 3),
                LocalDate.of(2026, 6, 4),
                LocalDate.of(2026, 6, 5)
            ),
            today = LocalDate.of(2026, 6, 20)
        )

        assertEquals(0.0, summary.carryoverRemaining, 0.01)
        assertEquals(4.0, summary.carryoverUsed, 0.01)
        assertEquals(1.0, summary.currentYearUsed, 0.01)
        assertEquals(23.0, summary.currentYearRemaining, 0.01)
    }

    @Test
    fun `godisnji posle prvog jula ne trosi istekli stari odmor`() {
        val balance = VacationYearBalance(
            year = 2026,
            trackingStartDate = LocalDate.of(2026, 6, 15),
            carryoverDaysInitial = 4.0,
            currentYearDaysTotal = 24.0,
            currentYearDaysUsedBeforeAvailability = 0.0,
            currentYearAvailableFrom = LocalDate.of(2026, 7, 1),
            manualBaseline = true
        )

        val summary = calculateVacationBalanceSummary(
            balance = balance,
            vacationDates = listOf(LocalDate.of(2026, 7, 2)),
            today = LocalDate.of(2026, 7, 3)
        )

        assertEquals(0.0, summary.carryoverRemaining, 0.01)
        assertEquals(0.0, summary.carryoverUsed, 0.01)
        assertEquals(4.0, summary.carryoverExpired, 0.01)
        assertEquals(1.0, summary.currentYearUsed, 0.01)
        assertEquals(23.0, summary.currentYearRemaining, 0.01)
    }

    @Test
    fun `bez starog odmora dan pre pocetka pracenja se i dalje ne broji`() {
        val balance = VacationYearBalance(
            year = 2026,
            trackingStartDate = LocalDate.of(2026, 6, 15),
            carryoverDaysInitial = 0.0,
            currentYearDaysTotal = 24.0,
            currentYearDaysUsedBeforeAvailability = 0.0,
            currentYearAvailableFrom = LocalDate.of(2026, 7, 1),
            manualBaseline = true
        )

        val summary = calculateVacationBalanceSummary(
            balance = balance,
            vacationDates = listOf(LocalDate.of(2026, 6, 1)),
            today = LocalDate.of(2026, 6, 20)
        )

        assertEquals(0.0, summary.carryoverRemaining, 0.01)
        assertEquals(0.0, summary.carryoverUsed, 0.01)
        assertEquals(0.0, summary.currentYearUsed, 0.01)
        assertEquals(24.0, summary.currentYearRemaining, 0.01)
    }

    @Test
    fun `maj prikazuje novi odmor tek od prvog jula`() {
        val balance = VacationYearBalance(
            year = 2026,
            trackingStartDate = LocalDate.of(2026, 5, 9),
            carryoverDaysInitial = 0.0,
            currentYearDaysTotal = 23.0,
            currentYearDaysUsedBeforeAvailability = 4.0,
            currentYearAvailableFrom = LocalDate.of(2026, 7, 1),
            manualBaseline = true
        )

        val summary = calculateVacationBalanceSummary(
            balance = balance,
            vacationDates = emptyList(),
            today = LocalDate.of(2026, 5, 9)
        )

        assertEquals(0.0, summary.carryoverRemaining, 0.01)
        assertEquals(23.0, summary.currentYearTotal, 0.01)
        assertEquals(4.0, summary.currentYearUsed, 0.01)
        assertEquals(19.0, summary.currentYearRemaining, 0.01)
        assertEquals(0.0, summary.availableNow, 0.01)
        assertEquals(19.0, summary.availableFromJuly, 0.01)
        assertTrue(summary.warnings.contains(VacationWarnings.NEW_VACATION_BEFORE_JULY))
    }

    @Test
    fun `do tridesetog juna upis trosi prvo stari odmor`() {
        val balance = VacationYearBalance(
            year = 2026,
            trackingStartDate = LocalDate.of(2026, 1, 1),
            carryoverDaysInitial = 3.0,
            currentYearDaysTotal = 20.0,
            currentYearDaysUsedBeforeAvailability = 0.0,
            currentYearAvailableFrom = LocalDate.of(2026, 7, 1),
            manualBaseline = true
        )

        val summary = calculateVacationBalanceSummary(
            balance = balance,
            vacationDates = listOf(
                LocalDate.of(2026, 5, 4),
                LocalDate.of(2026, 5, 5)
            ),
            today = LocalDate.of(2026, 5, 9)
        )

        assertEquals(1.0, summary.carryoverRemaining, 0.01)
        assertEquals(0.0, summary.currentYearUsed, 0.01)
        assertEquals(1.0, summary.availableNow, 0.01)
        assertEquals(20.0, summary.availableFromJuly, 0.01)
    }

    @Test
    fun `posle prvog jula dostupan je novi odmor`() {
        val balance = VacationYearBalance(
            year = 2026,
            trackingStartDate = LocalDate.of(2026, 1, 1),
            carryoverDaysInitial = 5.0,
            currentYearDaysTotal = 20.0,
            currentYearDaysUsedBeforeAvailability = 0.0,
            currentYearAvailableFrom = LocalDate.of(2026, 7, 1),
            manualBaseline = true
        )

        val summary = calculateVacationBalanceSummary(
            balance = balance,
            vacationDates = listOf(LocalDate.of(2026, 7, 1)),
            today = LocalDate.of(2026, 7, 2)
        )

        assertEquals(0.0, summary.carryoverRemaining, 0.01)
        assertEquals(1.0, summary.currentYearUsed, 0.01)
        assertEquals(19.0, summary.currentYearRemaining, 0.01)
        assertEquals(19.0, summary.availableNow, 0.01)
        assertEquals(0.0, summary.availableFromJuly, 0.01)
        assertTrue(summary.warnings.contains(VacationWarnings.CARRYOVER_EXPIRED))
    }

    @Test
    fun `godisnji pre prvog jula se blokira kada nema starog odmora`() {
        val validation = validateVacationRequestAvailability(
            requestedDates = listOf(LocalDate.of(2026, 6, 8)),
            existingVacationDates = emptySet(),
            carryoverRemainingBeforeDate = { 0.0 }
        )

        assertEquals(false, validation.isAllowed)
        assertEquals(listOf(LocalDate.of(2026, 6, 8)), validation.blockedDates)
        assertTrue(validation.message?.contains("Novi odmor može da se koristi od 01.07.2026.") == true)
    }

    @Test
    fun `godisnji od prvog jula se dozvoljava i bez starog odmora`() {
        val validation = validateVacationRequestAvailability(
            requestedDates = listOf(LocalDate.of(2026, 7, 1)),
            existingVacationDates = emptySet(),
            carryoverRemainingBeforeDate = { 0.0 }
        )

        assertEquals(true, validation.isAllowed)
        assertEquals(emptyList<LocalDate>(), validation.blockedDates)
        assertEquals(null, validation.message)
    }

    @Test
    fun `vise junskih dana blokira deo koji prelazi stari odmor`() {
        val validation = validateVacationRequestAvailability(
            requestedDates = listOf(
                LocalDate.of(2026, 6, 8),
                LocalDate.of(2026, 6, 9)
            ),
            existingVacationDates = emptySet(),
            carryoverRemainingBeforeDate = { 1.0 }
        )

        assertEquals(false, validation.isAllowed)
        assertEquals(listOf(LocalDate.of(2026, 6, 9)), validation.blockedDates)
    }
}
