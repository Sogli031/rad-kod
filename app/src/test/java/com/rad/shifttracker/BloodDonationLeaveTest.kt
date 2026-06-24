package com.rad.shifttracker

import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BloodDonationLeaveTest {
    @Test
    fun `tekstovi prikazuju poslednje i sledece koriscenje krvnih dana`() {
        val firstDay = LocalDate.of(2026, 5, 4)

        assertEquals(
            "Poslednje korišćenje dana: 04.05.2026. i 05.05.2026.",
            bloodDonationLeaveLastUsageText(firstDay)
        )
        assertEquals(
            "Možeš ponovo da iskoristiš dane: 06.08.2026.",
            bloodDonationLeaveNextUsageText(firstDay)
        )
        assertEquals(
            "Poslednje korišćenje dana: nije uneto.",
            bloodDonationLeaveLastUsageText(null)
        )
        assertEquals(
            "Možeš ponovo da iskoristiš dane: nije izračunato.",
            bloodDonationLeaveNextUsageText(null)
        )
    }

    @Test
    fun `sledece koriscenje je dan posle tri meseca od drugog krvnog dana`() {
        assertEquals(
            LocalDate.of(2026, 8, 23),
            calculateNextBloodDonationLeaveDate(LocalDate.of(2026, 5, 21))
        )
    }

    @Test
    fun `upis krvnih dana pravi bolovanje sto posto sa posebnim izvorom`() {
        val repo = ShiftRepository()

        assertTrue(repo.applyBloodDonationLeave(LocalDate.of(2026, 5, 4)))

        val first = repo.get(2026, 5, 4)
        val second = repo.get(2026, 5, 5)
        assertEquals(LocalDate.of(2026, 5, 4), repo.bloodDonationLeaveStartDate)
        assertTrue(first?.isBloodDonationLeave == true)
        assertTrue(second?.isBloodDonationLeave == true)
        assertEquals(Shift.SICK, first?.shift)
        assertEquals(8, first?.hours)
        assertEquals(1.0, first?.sickPayRate ?: 0.0, 0.001)
        assertEquals("Krv", calendarDayDetailText(first, isHolidayDay = false, workedHoliday = false))
    }

    @Test
    fun `krvni dani se obracunavaju kao bolovanje sto posto`() {
        val repo = ShiftRepository()
        repo.grossHourlyRateValue = 1_000.0
        repo.setMonthlyPayBases(
            2026,
            4,
            MonthlyPayBases.fromUnifiedPayBase(2026, 4, 900.0)
        )

        repo.applyBloodDonationLeave(LocalDate.of(2026, 5, 4))

        val details = repo.getMonthlyPayDetails(2026, 5)
        assertEquals(16, details.sickHours)
        assertEquals(14_400.0, details.grossSickPay, 0.01)
    }

    @Test
    fun `ponistavanje krvnih dana vraca prethodno stanje`() {
        val repo = ShiftRepository()
        repo.set(2026, 5, 4, Shift.FIRST, 8)
        repo.set(2026, 5, 5, Shift.VACATION, 8)

        repo.applyBloodDonationLeave(LocalDate.of(2026, 5, 4))
        assertTrue(repo.clearBloodDonationLeave())

        assertNull(repo.bloodDonationLeaveStartDate)
        assertEquals(Shift.FIRST, repo.get(2026, 5, 4)?.shift)
        assertEquals(Shift.VACATION, repo.get(2026, 5, 5)?.shift)
    }

    @Test
    fun `brisanje jednog krvnog dana ponistava oba krvna dana`() {
        val repo = ShiftRepository()
        repo.set(2026, 5, 4, Shift.FIRST, 8)

        repo.applyBloodDonationLeave(LocalDate.of(2026, 5, 4))
        assertTrue(repo.delete(2026, 5, 5))

        assertNull(repo.bloodDonationLeaveStartDate)
        assertEquals(Shift.FIRST, repo.get(2026, 5, 4)?.shift)
        assertNull(repo.get(2026, 5, 5))
    }

    @Test
    fun `postavljanje jednog krvnog dana na neradni ponistava oba krvna dana`() {
        val repo = ShiftRepository()
        repo.set(2026, 5, 4, Shift.FIRST, 8)

        repo.applyBloodDonationLeave(LocalDate.of(2026, 5, 4))
        assertTrue(repo.set(2026, 5, 5, Shift.OFF, 0))

        assertNull(repo.bloodDonationLeaveStartDate)
        assertEquals(Shift.FIRST, repo.get(2026, 5, 4)?.shift)
        assertNull(repo.get(2026, 5, 5))
    }

    @Test
    fun `izmena jednog krvnog dana ponistava krvni par i snima rucnu izmenu`() {
        val repo = ShiftRepository()
        repo.set(2026, 5, 4, Shift.FIRST, 8)

        repo.applyBloodDonationLeave(LocalDate.of(2026, 5, 4))
        assertTrue(repo.set(2026, 5, 5, Shift.VACATION, 8))

        assertNull(repo.bloodDonationLeaveStartDate)
        assertEquals(Shift.FIRST, repo.get(2026, 5, 4)?.shift)
        assertEquals(Shift.VACATION, repo.get(2026, 5, 5)?.shift)
        assertFalse(repo.get(2026, 5, 5)?.isBloodDonationLeave == true)
    }

    @Test
    fun `novi unos krvnih dana dodaje dane bez brisanja prethodnih`() {
        val repo = ShiftRepository()

        repo.applyBloodDonationLeave(LocalDate.of(2026, 5, 21))
        repo.applyBloodDonationLeave(LocalDate.of(2026, 8, 23))

        assertEquals(LocalDate.of(2026, 8, 23), repo.bloodDonationLeaveStartDate)
        assertTrue(repo.get(2026, 5, 21)?.isBloodDonationLeave == true)
        assertTrue(repo.get(2026, 5, 22)?.isBloodDonationLeave == true)
        assertTrue(repo.get(2026, 8, 23)?.isBloodDonationLeave == true)
        assertTrue(repo.get(2026, 8, 24)?.isBloodDonationLeave == true)
    }

    @Test
    fun `krvni dani i backup se cuvaju kroz export import`() {
        val repo = ShiftRepository()
        repo.set(2026, 5, 4, Shift.THIRD, 8)
        repo.applyBloodDonationLeave(LocalDate.of(2026, 5, 4))
        val restored = ShiftRepository()

        assertTrue(restored.importFromJson(repo.exportJson()))

        assertEquals(LocalDate.of(2026, 5, 4), restored.bloodDonationLeaveStartDate)
        assertTrue(restored.get(2026, 5, 4)?.isBloodDonationLeave == true)
        restored.clearBloodDonationLeave()
        assertEquals(Shift.THIRD, restored.get(2026, 5, 4)?.shift)
    }

    @Test
    fun `vise unosa krvnih dana se cuva kroz export import`() {
        val repo = ShiftRepository()
        repo.applyBloodDonationLeave(LocalDate.of(2026, 5, 21))
        repo.applyBloodDonationLeave(LocalDate.of(2026, 8, 23))
        val restored = ShiftRepository()

        assertTrue(restored.importFromJson(repo.exportJson()))

        assertEquals(LocalDate.of(2026, 8, 23), restored.bloodDonationLeaveStartDate)
        assertTrue(restored.get(2026, 5, 21)?.isBloodDonationLeave == true)
        assertTrue(restored.get(2026, 5, 22)?.isBloodDonationLeave == true)
        assertTrue(restored.get(2026, 8, 23)?.isBloodDonationLeave == true)
        assertTrue(restored.get(2026, 8, 24)?.isBloodDonationLeave == true)
    }

    @Test
    fun `day edit za krvni dan prikazuje poseban popup sa ponistavanjem`() {
        val bloodLeave = DayRecord(
            year = 2026,
            month = 5,
            day = 4,
            shift = Shift.SICK,
            hours = 8,
            sickPayRate = 1.0,
            source = DayRecordSource.BLOOD_DONATION_LEAVE
        )
        val source = listOf(
            java.io.File("src/main/java/com/rad/shifttracker/DayEditDialog.kt"),
            java.io.File("app/src/main/java/com/rad/shifttracker/DayEditDialog.kt")
        ).first { it.exists() }.readText()

        assertTrue(isDayEditLockedByBloodDonation(bloodLeave))
        assertTrue(source.contains("BloodDonationLockedDayDialog"))
        assertTrue(source.contains("PONIŠTI DANE"))
        assertTrue(source.contains("repo.clearBloodDonationLeaveForDate(date)"))
    }
}
