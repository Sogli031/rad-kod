package com.rad.shifttracker

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import com.rad.shifttracker.obracun.PayBaseSelectionMode
import com.rad.shifttracker.obracun.PayBaseSource
import com.rad.shifttracker.obracun.ResolvedPayBase
import com.rad.shifttracker.obracun.SENIORITY_NOT_CONFIGURED_WARNING_TEXT
import com.rad.shifttracker.obracun.SeniorityInputMode
import com.rad.shifttracker.obracun.payAccuracyWarningTexts
import com.rad.shifttracker.obracun.unreliablePayBaseWarningText
import com.rad.shifttracker.repo.ShiftRepository
import com.rad.shifttracker.repo.getMonthlyPayDetails
import com.rad.shifttracker.repo.isSeniorityConfiguredValue
import com.rad.shifttracker.repo.minuliRadLastPayslipYearValue
import com.rad.shifttracker.repo.minuliRadPayslipPercentValue
import com.rad.shifttracker.repo.minuliRadStartYearValue
import com.rad.shifttracker.repo.seniorityInputModeValue
import com.rad.shifttracker.repo.setDefaultPayBaseSelection
import com.rad.shifttracker.repo.setKnownPayBaseAnchor
import com.rad.shifttracker.smene.Shift

class PayAccuracyWarningsTest {
    @Test
    fun `nepodesen staz i osnovica iz simuliranih meseci daju oba upozorenja`() {
        val repo = repoWithJuneAndJulyVacation()

        val details = repo.getMonthlyPayDetails(2026, 7)
        val warnings = payAccuracyWarningTexts(details, repo.isSeniorityConfiguredValue)

        assertFalse(repo.isSeniorityConfiguredValue)
        assertEquals(2, warnings.size)
        assertEquals(SENIORITY_NOT_CONFIGURED_WARNING_TEXT, warnings[0])
        assertTrue(warnings[1].contains("računa se iz simuliranih meseci (12 od 12)"))
        assertTrue(warnings[1].contains("Skeniraj platnu listu u „Listi“."))
        assertTrue(warnings[1].contains("Ovaj mesec ima 48h naknada po toj osnovici."))
    }

    @Test
    fun `podesen staz i poznata osnovica ne daju upozorenja`() {
        val repo = repoWithJuneAndJulyVacation()
        repo.minuliRadStartYearValue = 2019
        repo.setKnownPayBaseAnchor(2026, 7, 945.60)
        repo.setDefaultPayBaseSelection(PayBaseSelectionMode.KNOWN_BASE)

        val details = repo.getMonthlyPayDetails(2026, 7)

        assertTrue(repo.isSeniorityConfiguredValue)
        assertEquals(emptyList<String>(), payAccuracyWarningTexts(details, repo.isSeniorityConfiguredValue))
    }

    @Test
    fun `staz unet kroz procenat sa listica vazi kao podesen`() {
        val repo = testRepository()
        repo.seniorityInputModeValue = SeniorityInputMode.PAYSLIP_PERCENT
        assertFalse(repo.isSeniorityConfiguredValue)

        repo.minuliRadLastPayslipYearValue = 2026
        repo.minuliRadPayslipPercentValue = 3.5

        assertTrue(repo.isSeniorityConfiguredValue)
    }

    @Test
    fun `podrazumevana osnovica se prijavljuje a poznata ne`() {
        val defaultBase = ResolvedPayBase(918.81, PayBaseSource.DEFAULT)
        val knownBase = ResolvedPayBase(954.24, PayBaseSource.KNOWN_BASE)

        val warning = unreliablePayBaseWarningText(defaultBase)

        assertEquals(
            "Osnovica za GO, praznik i plaćeno odsustvo (918,81 RSD/h) je podrazumevana vrednost, " +
                "a ne tvoj prosek. Skeniraj platnu listu u „Listi“.",
            warning
        )
        assertEquals(null, unreliablePayBaseWarningText(knownBase, compensationMinutes = 480))
    }

    private fun repoWithJuneAndJulyVacation(): ShiftRepository {
        val repo = testRepository()
        weekdaysOf(2026, 6).forEach { day -> repo.set(2026, 6, day, Shift.FIRST, 8) }
        weekdaysOf(2026, 7).forEach { day ->
            val shift = if (day in 13..20) Shift.VACATION else Shift.FIRST
            repo.set(2026, 7, day, shift, 8)
        }
        return repo
    }

    private fun weekdaysOf(year: Int, month: Int): List<Int> {
        return (1..YearMonth.of(year, month).lengthOfMonth()).filter { day ->
            LocalDate.of(year, month, day).dayOfWeek !in setOf(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY)
        }
    }
}
