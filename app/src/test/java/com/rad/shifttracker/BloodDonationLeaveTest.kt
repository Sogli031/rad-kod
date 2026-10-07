package com.rad.shifttracker

import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import com.rad.shifttracker.krv.BloodDonationLeaveUsage
import com.rad.shifttracker.krv.formatBloodDonationDate
import com.rad.shifttracker.krv.latestPaidLeavePair
import com.rad.shifttracker.krv.lastBloodDonationLeaveUsage
import com.rad.shifttracker.krv.bloodDonationLeaveLastUsageText
import com.rad.shifttracker.krv.bloodDonationLeaveNextUsageText
import com.rad.shifttracker.krv.bloodDonationLeaveUsageText
import com.rad.shifttracker.krv.calculateNextBloodDonationLeaveDate
import com.rad.shifttracker.obracun.MonthlyPayBases
import com.rad.shifttracker.repo.applyBloodDonationLeave
import com.rad.shifttracker.repo.bloodDonationCount
import com.rad.shifttracker.repo.bloodDonationLeaveStartDate
import com.rad.shifttracker.repo.clearBloodDonationLeave
import com.rad.shifttracker.repo.deleteBloodDonation
import com.rad.shifttracker.repo.exportJson
import com.rad.shifttracker.repo.getMonthlyPayDetails
import com.rad.shifttracker.repo.grossHourlyRateValue
import com.rad.shifttracker.repo.importFromJson
import com.rad.shifttracker.repo.setMonthlyPayBases
import com.rad.shifttracker.smene.DayRecord
import com.rad.shifttracker.smene.DayRecordSource
import com.rad.shifttracker.smene.Shift
import com.rad.shifttracker.ui.calendarDayDetailText
import com.rad.shifttracker.ui.dialozi.isDayEditLockedByBloodDonation

class BloodDonationLeaveTest {
    @Test
    fun `poslednje koriscenje je najnoviji par placenog odsustva u kalendaru`() {
        val d = { m: Int, day: Int -> LocalDate.of(2026, m, day) }
        // Jedan dan sam nije par; petak + ponedeljak jeste.
        val pair = latestPaidLeavePair(listOf(d(3, 10), d(3, 11), d(6, 3), d(8, 7), d(8, 10)))
        assertEquals(BloodDonationLeaveUsage(d(8, 7), d(8, 10)), pair)
        assertNull(latestPaidLeavePair(listOf(d(6, 3), d(6, 5))))

        // Ručni upis važi samo ako je noviji od para u kalendaru.
        assertEquals(pair, lastBloodDonationLeaveUsage(pair, d(3, 10)))
        assertEquals(BloodDonationLeaveUsage(d(9, 1), d(9, 2)), lastBloodDonationLeaveUsage(pair, d(9, 1)))
        assertEquals(
            "Poslednje korišćenje dana: ${d(8, 7).formatBloodDonationDate()} i ${d(8, 10).formatBloodDonationDate()}",
            bloodDonationLeaveUsageText(pair)
        )
        assertEquals(d(11, 11), pair!!.nextAllowed)
    }

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
        val repo = testRepository()

        assertTrue(repo.applyBloodDonationLeave(LocalDate.of(2026, 5, 4)))

        val first = repo.get(2026, 5, 4)
        val second = repo.get(2026, 5, 5)
        assertEquals(LocalDate.of(2026, 5, 4), repo.bloodDonationLeaveStartDate)
        assertTrue(first?.isBloodDonationLeave == true)
        assertTrue(second?.isBloodDonationLeave == true)
        assertEquals(Shift.SICK, first?.shift)
        assertEquals(8, first?.hours)
        assertNull(first?.sickPayRate)
        assertEquals("Plaćeno\nodsustvo", calendarDayDetailText(first, isHolidayDay = false, workedHoliday = false))
    }

    @Test
    fun `krvni dani se obracunavaju kao placeno odsustvo sto posto`() {
        val repo = testRepository()
        repo.grossHourlyRateValue = 1_000.0
        repo.setMonthlyPayBases(
            2026,
            4,
            MonthlyPayBases.fromUnifiedPayBase(2026, 4, 900.0)
        )

        repo.applyBloodDonationLeave(LocalDate.of(2026, 5, 4))

        val details = repo.getMonthlyPayDetails(2026, 5)
        assertEquals(0, details.sickHours)
        assertEquals(16, details.paidLeaveHours)
        assertEquals(0.0, details.grossSickPay, 0.01)
        assertEquals(14_400.0, details.grossPaidLeave, 0.01)
    }

    @Test
    fun `ponistavanje krvnih dana vraca prethodno stanje`() {
        val repo = testRepository()
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
        val repo = testRepository()
        repo.set(2026, 5, 4, Shift.FIRST, 8)

        repo.applyBloodDonationLeave(LocalDate.of(2026, 5, 4))
        assertTrue(repo.delete(2026, 5, 5))

        assertNull(repo.bloodDonationLeaveStartDate)
        assertEquals(Shift.FIRST, repo.get(2026, 5, 4)?.shift)
        assertNull(repo.get(2026, 5, 5))
    }

    @Test
    fun `postavljanje jednog krvnog dana na neradni ponistava oba krvna dana`() {
        val repo = testRepository()
        repo.set(2026, 5, 4, Shift.FIRST, 8)

        repo.applyBloodDonationLeave(LocalDate.of(2026, 5, 4))
        assertTrue(repo.set(2026, 5, 5, Shift.OFF, 0))

        assertNull(repo.bloodDonationLeaveStartDate)
        assertEquals(Shift.FIRST, repo.get(2026, 5, 4)?.shift)
        assertNull(repo.get(2026, 5, 5))
    }

    @Test
    fun `izmena jednog krvnog dana ponistava krvni par i snima rucnu izmenu`() {
        val repo = testRepository()
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
        val repo = testRepository()

        repo.applyBloodDonationLeave(LocalDate.of(2026, 5, 21))
        repo.applyBloodDonationLeave(LocalDate.of(2026, 8, 23))

        assertEquals(LocalDate.of(2026, 8, 23), repo.bloodDonationLeaveStartDate)
        assertTrue(repo.get(2026, 5, 21)?.isBloodDonationLeave == true)
        assertTrue(repo.get(2026, 5, 22)?.isBloodDonationLeave == true)
        // 23.08.2026. je nedelja — na vikend se ne upisuje plaćeno odsustvo
        assertNull(repo.get(2026, 8, 23))
        assertTrue(repo.get(2026, 8, 24)?.isBloodDonationLeave == true)
    }

    @Test
    fun `krvni dani preskacu vikend pri upisu odsustva`() {
        val repo = testRepository()

        // 08.05.2026. je petak — drugi dan (subota) se ne upisuje
        assertTrue(repo.applyBloodDonationLeave(LocalDate.of(2026, 5, 8)))

        assertTrue(repo.get(2026, 5, 8)?.isBloodDonationLeave == true)
        assertNull(repo.get(2026, 5, 9))
        assertEquals(LocalDate.of(2026, 5, 8), repo.bloodDonationLeaveStartDate)
    }

    @Test
    fun `brisanje davanja krvi cuva rucno unet veci brojac`() {
        val repo = testRepository()
        assertTrue(
            repo.importFromJson(
                """{"blood_donation_count":30,"blood_donation_history":["2026-04-12","2026-01-10"]}"""
            )
        )

        assertTrue(repo.deleteBloodDonation(LocalDate.of(2026, 4, 12)))

        assertEquals(29, repo.bloodDonationCount)
    }

    @Test
    fun `krvni dani i backup se cuvaju kroz export import`() {
        val repo = testRepository()
        repo.set(2026, 5, 4, Shift.THIRD, 8)
        repo.applyBloodDonationLeave(LocalDate.of(2026, 5, 4))
        val restored = testRepository()

        assertTrue(restored.importFromJson(repo.exportJson()))

        assertEquals(LocalDate.of(2026, 5, 4), restored.bloodDonationLeaveStartDate)
        assertTrue(restored.get(2026, 5, 4)?.isBloodDonationLeave == true)
        restored.clearBloodDonationLeave()
        assertEquals(Shift.THIRD, restored.get(2026, 5, 4)?.shift)
    }

    @Test
    fun `vise unosa krvnih dana se cuva kroz export import`() {
        val repo = testRepository()
        repo.applyBloodDonationLeave(LocalDate.of(2026, 5, 21))
        repo.applyBloodDonationLeave(LocalDate.of(2026, 8, 23))
        val restored = testRepository()

        assertTrue(restored.importFromJson(repo.exportJson()))

        assertEquals(LocalDate.of(2026, 8, 23), restored.bloodDonationLeaveStartDate)
        assertTrue(restored.get(2026, 5, 21)?.isBloodDonationLeave == true)
        assertTrue(restored.get(2026, 5, 22)?.isBloodDonationLeave == true)
        // 23.08.2026. je nedelja — nema zapisa ni posle import-a
        assertNull(restored.get(2026, 8, 23))
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
        val source = mainSource("DayEditDialog.kt")

        assertTrue(isDayEditLockedByBloodDonation(bloodLeave))
        assertTrue(source.contains("BloodDonationLockedDayDialog"))
        assertTrue(source.contains("Poništi dane"))
        assertTrue(source.contains("actions.clearBloodDonationLeaveForDate(date)"))
    }
}
