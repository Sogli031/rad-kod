package com.rad.shifttracker

import java.time.LocalDate
import java.time.LocalDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNull
import org.junit.Test

class BloodDonationReminderTest {
    @Test
    fun `sledeci datum davanja krvi je tri meseca posle poslednjeg`() {
        assertEquals(
            LocalDate.of(2026, 4, 10),
            calculateNextBloodDonationDate(LocalDate.of(2026, 1, 10))
        )
    }

    @Test
    fun `bez poslednjeg datuma nema zakazanog podsetnika`() {
        val plan = planBloodDonationReminderAlarm(
            lastDonationDate = null,
            now = LocalDateTime.of(2026, 5, 21, 12, 0)
        )

        assertEquals(BloodDonationReminderAlarmPlan.Cancel, plan)
    }

    @Test
    fun `pre isteka tri meseca zakazuje prvi podsetnik na sledeci datum davanja`() {
        val plan = planBloodDonationReminderAlarm(
            lastDonationDate = LocalDate.of(2026, 1, 10),
            now = LocalDateTime.of(2026, 3, 20, 12, 0)
        )

        assertEquals(
            BloodDonationReminderAlarmPlan.Schedule(LocalDateTime.of(2026, 4, 10, 9, 0)),
            plan
        )
    }

    @Test
    fun `kada je korisnik vec podoban zakazuje sledeci dnevni podsetnik`() {
        val plan = planBloodDonationReminderAlarm(
            lastDonationDate = LocalDate.of(2026, 1, 10),
            now = LocalDateTime.of(2026, 4, 10, 9, 1)
        )

        assertEquals(
            BloodDonationReminderAlarmPlan.Schedule(LocalDateTime.of(2026, 4, 11, 9, 0)),
            plan
        )
    }

    @Test
    fun `tekstovi prikazuju poslednji i sledeci datum`() {
        val lastDonationDate = LocalDate.of(2026, 1, 10)

        assertEquals(
            "Datum poslednjeg davanja: 10.01.2026.",
            bloodDonationLastDateText(lastDonationDate)
        )
        assertEquals(
            "Možeš sledeći put: 10.04.2026.",
            bloodDonationNextDateText(lastDonationDate)
        )
    }

    @Test
    fun `unos datuma davanja koristi izabrani mesec`() {
        assertEquals(
            LocalDate.of(2026, 5, 12),
            parseBloodDonationDateInSelectedMonth(dayInput = "12", selectedYear = 2026, selectedMonth = 5)
        )
        assertNull(parseBloodDonationDateInSelectedMonth(dayInput = "31", selectedYear = 2026, selectedMonth = 2))
    }

    @Test
    fun `tekst prikazuje brojac davanja krvi`() {
        assertEquals("Broj davanja: 3", bloodDonationCountText(3))
    }

    @Test
    fun `status ne pominje tacne alarme`() {
        val status = BloodDonationReminderScheduleStatus(
            lastDonationDate = LocalDate.of(2026, 1, 10),
            donationCount = 1,
            nextDonationDate = LocalDate.of(2026, 4, 10),
            nextReminderTime = LocalDateTime.of(2026, 4, 10, 9, 0),
            notificationPermissionGranted = true
        )

        assertFalse(bloodDonationReminderStatusText(status).contains("Tačni alarmi"))
    }

    @Test
    fun `scheduler koristi obican alarm bez dozvole za tacne alarme`() {
        val source = listOf(
            java.io.File("src/main/java/com/rad/shifttracker/BloodDonationReminderScheduler.kt"),
            java.io.File("app/src/main/java/com/rad/shifttracker/BloodDonationReminderScheduler.kt")
        ).first { it.exists() }.readText()

        assertTrue(source.contains("setAndAllowWhileIdle"))
        assertFalse(source.contains("setExact"))
        assertFalse(source.contains("canScheduleExactAlarms"))
    }

    @Test
    fun `tekstovi za istoriju formatiraju datume i potvrdu brisanja`() {
        val date = LocalDate.of(2026, 4, 12)

        assertEquals("Još nema upisanih davanja krvi.", BLOOD_DONATION_HISTORY_EMPTY_TEXT)
        assertEquals("12.04.2026.", bloodDonationHistoryDateText(date))
        assertEquals(
            "Obrisati davanje krvi za 12.04.2026.?",
            bloodDonationDeleteConfirmationText(date)
        )
    }

    @Test
    fun `buduci datum odsustva zbog krvi je dozvoljen`() {
        assertNull(
            validateBloodDonationLeaveStartDate(
                firstDay = LocalDate.of(2026, 8, 20),
                previousLeaveStartDate = LocalDate.of(2026, 5, 18)
            )
        )
        assertNull(
            validateBloodDonationLeaveStartDate(
                firstDay = LocalDate.of(2026, 8, 10),
                previousLeaveStartDate = null
            )
        )
        assertEquals(
            "Unesi ispravan prvi dan odsustva.",
            validateBloodDonationLeaveStartDate(firstDay = null, previousLeaveStartDate = null)
        )
    }

    @Test
    fun `odsustvo zbog krvi ne moze pre isteka tri meseca od prethodnog koriscenja`() {
        assertEquals(
            "Nemaš pravo da koristiš dane pre 20.08.2026.",
            validateBloodDonationLeaveStartDate(
                firstDay = LocalDate.of(2026, 8, 19),
                previousLeaveStartDate = LocalDate.of(2026, 5, 18)
            )
        )
    }

    @Test
    fun `dijalog za davanje krvi ne prikazuje unos godine`() {
        val source = listOf(
            java.io.File("src/main/java/com/rad/shifttracker/BloodDonationDialog.kt"),
            java.io.File("app/src/main/java/com/rad/shifttracker/BloodDonationDialog.kt")
        ).first { it.exists() }.readText()

        assertFalse(source.contains("hintText = \"Godina\""))
        assertFalse(source.contains("hintText = \"Mesec\""))
    }

    @Test
    fun `dijalog za davanje krvi ne popunjava unos sacuvanim datumima`() {
        val source = listOf(
            java.io.File("src/main/java/com/rad/shifttracker/BloodDonationDialog.kt"),
            java.io.File("app/src/main/java/com/rad/shifttracker/BloodDonationDialog.kt")
        ).first { it.exists() }.readText()

        assertFalse(source.contains("mutableStateOf(savedDate?.dayOfMonth"))
        assertFalse(source.contains("mutableStateOf(leaveStartDate?.dayOfMonth"))
    }

    @Test
    fun `dijalog za davanje krvi nema dugme za ponistavanje krvnih dana`() {
        val source = listOf(
            java.io.File("src/main/java/com/rad/shifttracker/BloodDonationDialog.kt"),
            java.io.File("app/src/main/java/com/rad/shifttracker/BloodDonationDialog.kt")
        ).first { it.exists() }.readText()

        assertFalse(source.contains("PONIŠTI DANE"))
        assertFalse(source.contains("clearBloodDonationLeave()"))
    }
}
