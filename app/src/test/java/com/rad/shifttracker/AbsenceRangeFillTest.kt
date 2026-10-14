package com.rad.shifttracker

import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Test
import com.rad.shifttracker.repo.absenceRangeDates
import com.rad.shifttracker.repo.applyAbsenceRange
import com.rad.shifttracker.smene.Shift
import com.rad.shifttracker.smene.ShiftScheduler
import com.rad.shifttracker.ui.dialozi.absenceRangeCountText
import com.rad.shifttracker.ui.dialozi.absenceUntilDateText

/** „Do kog dana“ u prozoru dana: bolovanje i godišnji od kliknutog dana, i preko granice meseca. */
class AbsenceRangeFillTest {
    private val scheduler = ShiftScheduler(defaultHours = 8)

    @Test
    fun `bolovanje prelazi u sledeci mesec i preskace vikend`() {
        val repo = testRepository()
        // Čet 29.10. – uto 03.11.2026: čet, pet, pon, uto.
        val count = scheduler.applyAbsenceRange(repo, Shift.SICK, LocalDate.of(2026, 10, 29), LocalDate.of(2026, 11, 3), 0.65)

        assertEquals(4, count)
        listOf(29 to 10, 30 to 10, 2 to 11, 3 to 11).forEach { (day, month) ->
            assertEquals(Shift.SICK, repo.get(2026, month, day)!!.shift)
            assertEquals(0.65, repo.get(2026, month, day)!!.sickPayRate!!, 0.001)
        }
        assertEquals(null, repo.get(2026, 10, 31))
    }

    @Test
    fun `godisnji preko meseca broji samo radne dane bez praznika`() {
        val repo = testRepository()
        // 10.11.2026 (uto) – 13.11.2026 (pet); 11.11. je praznik.
        val dates = scheduler.absenceRangeDates(repo, Shift.VACATION, LocalDate.of(2026, 11, 10), LocalDate.of(2026, 11, 13))

        assertEquals(listOf(10, 12, 13), dates.map { it.dayOfMonth })
    }

    @Test
    fun `tekst broja dana prati padez`() {
        assertEquals("1 radni dan bolovanja", absenceRangeCountText(Shift.SICK, 1))
        assertEquals("3 radna dana godišnjeg", absenceRangeCountText(Shift.VACATION, 3))
        assertEquals("8 radnih dana bolovanja", absenceRangeCountText(Shift.SICK, 8))
        assertEquals("12 radnih dana godišnjeg", absenceRangeCountText(Shift.VACATION, 12))
        assertEquals("21 radni dan godišnjeg", absenceRangeCountText(Shift.VACATION, 21))
    }

    @Test
    fun `datum kraja dobija godinu samo u sledecoj godini`() {
        val from = LocalDate.of(2026, 12, 28)
        assertEquals("31. Decembar", absenceUntilDateText(LocalDate.of(2026, 12, 31), from))
        assertEquals("4. Januar 2027", absenceUntilDateText(LocalDate.of(2027, 1, 4), from))
    }
}
