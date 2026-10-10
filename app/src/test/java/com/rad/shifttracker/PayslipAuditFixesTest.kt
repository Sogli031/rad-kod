package com.rad.shifttracker

import org.junit.Assert.assertEquals
import org.junit.Test
import com.rad.shifttracker.obracun.MonthlyGrossAdditions
import com.rad.shifttracker.obracun.PayCalculator
import com.rad.shifttracker.obracun.calculateMonthlyNetPayBreakdown
import com.rad.shifttracker.obracun.nightWorkMinutes
import com.rad.shifttracker.repo.applyBloodDonationLeave
import com.rad.shifttracker.repo.calculateDailyPay
import com.rad.shifttracker.repo.getNetPayBreakdown
import com.rad.shifttracker.repo.getRollingPayBaseCalculation
import com.rad.shifttracker.repo.getSummary
import com.rad.shifttracker.repo.initialCompBalanceMinutesValue
import com.rad.shifttracker.smene.BrigadeType
import com.rad.shifttracker.smene.DayRecord
import com.rad.shifttracker.smene.OvertimeType
import com.rad.shifttracker.smene.Shift

/** Greške nađene poređenjem sa platnim listićima 05–08/2026 (provera 2026-09-22). */
class PayslipAuditFixesTest {
    private val calculator = PayCalculator()

    private fun junePaidExtraWithOffDay(): List<DayRecord> = listOf(
        // 03.06. +2h „Plaćeno" je prekovremeni; neradni 08.06. (8h) ostaje ceo manjak.
        DayRecord(2026, 6, 3, Shift.FIRST, 8, shift2 = Shift.SECOND, hours2 = 2),
        DayRecord(2026, 6, 8, Shift.OFF, 0)
    )

    @Test
    fun `placen visak je prekovremeni a manjak pokriva saldo`() {
        val summary = calculator.calculateMonthSummary(
            records = junePaidExtraWithOffDay(),
            baseRate = 300.0,
            brigadeType = BrigadeType.TROBRIGADA,
            summaryYear = 2026,
            summaryMonth = 6,
            startingCompBalanceMinutes = 10 * 60
        )

        // 8h rada + 2h prekovremenog (141%) + ceo neradni dan 8h iz salda.
        assertEquals(2, summary.overtimeHours)
        assertEquals(8 * 300.0 + 2 * 300.0 * 1.41 + 8 * 300.0, summary.totalPay, 0.01)
        assertEquals(8 * 60, summary.coveredDeficitMinutes)
        assertEquals(0, summary.uncoveredDeficitMinutes)
        assertEquals(2 * 60, summary.compTimeEndingMinutes)
    }

    @Test
    fun `placen visak bez salda ostavlja ceo manjak`() {
        val summary = calculator.calculateMonthSummary(
            records = junePaidExtraWithOffDay(),
            baseRate = 300.0,
            brigadeType = BrigadeType.TROBRIGADA,
            summaryYear = 2026,
            summaryMonth = 6
        )

        assertEquals(8 * 300.0 + 2 * 300.0 * 1.41, summary.totalPay, 0.01)
        assertEquals(8 * 60, summary.uncoveredDeficitMinutes)
        assertEquals(-8 * 60, summary.compTimeEndingMinutes)
    }

    @Test
    fun `za sate vraca sate a placeno ne`() {
        val repo = testRepository()
        repo.initialCompBalanceMinutesValue = 10 * 60
        repo.set(2026, 6, 3, Shift.FIRST, 8, shift2 = Shift.SECOND, hours2 = 2)
        repo.set(2026, 6, 8, Shift.OFF, 0)

        assertEquals(2 * 60, repo.getSummary(2026, 6).compTimeEndingMinutes)
        assertEquals(2 * 60, repo.getSummary(2026, 7).compTimeStartingMinutes)

        repo.set(2026, 6, 3, Shift.FIRST, 8, shift2 = Shift.SECOND, hours2 = 2, overtimeType = OvertimeType.COMP_TIME)
        assertEquals(4 * 60, repo.getSummary(2026, 6).compTimeEndingMinutes)
    }

    @Test
    fun `cetvorobrigada visak u fondu ne placa manjak jos jednom iz salda`() {
        // Fond jun 2026 = 176h: 20 dana × 8h + jedan dan 6h + jedan dan 10h = 176h.
        val workdays = (1..30).filter { day ->
            java.time.LocalDate.of(2026, 6, day).dayOfWeek.value <= 5
        }
        val records = workdays.mapIndexed { index, day ->
            when (index) {
                0 -> DayRecord(2026, 6, day, Shift.FIRST, 6)
                1 -> DayRecord(2026, 6, day, Shift.FIRST, 10)
                else -> DayRecord(2026, 6, day, Shift.FIRST, 8)
            }
        }
        assertEquals(22, records.size)

        val summary = calculator.calculateMonthSummary(
            records = records,
            baseRate = 300.0,
            brigadeType = BrigadeType.CETVOROBRIGADA,
            summaryYear = 2026,
            summaryMonth = 6,
            startingCompBalanceMinutes = 10 * 60
        )

        assertEquals(176 * 300.0, summary.totalPay, 0.01)
        assertEquals(0, summary.coveredDeficitMinutes)
        assertEquals(10 * 60, summary.compTimeEndingMinutes)
    }

    @Test
    fun `nocni dodatak samo za pune sate trece smene`() {
        // Listić 07/2026: 31.07. rađeno 4h30 u trećoj, noćni rad 4h.
        val record = DayRecord(2026, 7, 31, Shift.THIRD, 4, regularShortageMinutes = 30)
        val records = listOf(record)
        val summary = calculator.calculateMonthSummary(
            records = records,
            baseRate = 637.32,
            brigadeType = BrigadeType.TROBRIGADA,
            summaryYear = 2026,
            summaryMonth = 7
        )
        val details = calculator.calculateMonthlyPayDetails(
            records = records,
            summary = summary,
            baseRate = 637.32,
            netBreakdown = calculateMonthlyNetPayBreakdown(
                summary = summary,
                grossHourlyRate = 637.32,
                monthlyFundHours = 184,
                contributionsPercent = 19.9,
                taxRelief = 34221.0,
                taxPercent = 10.0,
                grossAdditions = MonthlyGrossAdditions(2026, 7)
            )
        )

        assertEquals(4 * 60, nightWorkMinutes(record))
        assertEquals(4 * 60, details.nightMinutes)
        assertEquals(4 * 637.32 * 0.41, details.grossNightPremium, 0.01)
        assertEquals(4.5 * 637.32, details.regularWorkedBaseGross, 0.01)
        assertEquals(details.grossTotalBeforeSeniority, summary.totalPay, 0.01)
    }

    @Test
    fun `praznik oznacen kao da nije rad na praznik nema dodatak 123 posto`() {
        // 04.05.2026 je ponedeljak; ručno označen kao praznik, ali ne kao rad na praznik.
        val record = DayRecord(
            2026, 5, 4, Shift.FIRST, 8,
            holidayOverride = true,
            holidayWorkOverride = false
        )
        val records = listOf(record)
        val summary = calculator.calculateMonthSummary(
            records = records,
            baseRate = 300.0,
            brigadeType = BrigadeType.TROBRIGADA,
            summaryYear = 2026,
            summaryMonth = 5
        )
        val details = calculator.calculateMonthlyPayDetails(
            records = records,
            summary = summary,
            baseRate = 300.0,
            netBreakdown = calculateMonthlyNetPayBreakdown(
                summary = summary,
                grossHourlyRate = 300.0,
                monthlyFundHours = 168,
                contributionsPercent = 19.9,
                taxRelief = 34221.0,
                taxPercent = 10.0,
                grossAdditions = MonthlyGrossAdditions(2026, 5)
            )
        )

        assertEquals(0.0, details.grossHolidayPremium, 0.001)
        assertEquals(8 * 300.0, calculator.calculateDailyPay(record, 300.0, BrigadeType.TROBRIGADA), 0.01)
    }

    @Test
    fun `rad na praznik i dalje nosi 123 posto`() {
        val record = DayRecord(2026, 5, 1, Shift.FIRST, 8)
        val pay = calculator.calculateDailyPay(record, 300.0, BrigadeType.TROBRIGADA)

        assertEquals(8 * 300.0 * 2.23, pay, 0.01)
    }

    @Test
    fun `treca smena na dan praznika ima samo 2h praznicnog rada`() {
        // 11.11.2026 (sreda) je praznik: treća smena počinje u 22h, do ponoći su 2h.
        val record = DayRecord(2026, 11, 11, Shift.THIRD, 8)
        val pay = calculator.calculateDailyPay(record, 300.0, BrigadeType.TROBRIGADA)

        assertEquals(8 * 300.0 * 1.41 + 2 * 300.0 * 1.23, pay, 0.01)
    }

    @Test
    fun `treca smena uoci praznika ima 6h praznicnog rada posle ponoci`() {
        // 10.11.2026 22h → 11.11. 06h: 6h pada na praznik.
        val record = DayRecord(2026, 11, 10, Shift.THIRD, 8)
        val records = listOf(record)
        val summary = calculator.calculateMonthSummary(
            records = records,
            baseRate = 300.0,
            brigadeType = BrigadeType.TROBRIGADA,
            summaryYear = 2026,
            summaryMonth = 11
        )
        val details = calculator.calculateMonthlyPayDetails(
            records = records,
            summary = summary,
            baseRate = 300.0,
            netBreakdown = calculateMonthlyNetPayBreakdown(
                summary = summary,
                grossHourlyRate = 300.0,
                monthlyFundHours = 168,
                contributionsPercent = 19.9,
                taxRelief = 34221.0,
                taxPercent = 10.0,
                grossAdditions = MonthlyGrossAdditions(2026, 11)
            )
        )

        assertEquals(
            8 * 300.0 * 1.41 + 6 * 300.0 * 1.23,
            calculator.calculateDailyPay(record, 300.0, BrigadeType.TROBRIGADA),
            0.01
        )
        assertEquals(6 * 300.0 * 1.23, details.grossHolidayPremium, 0.01)
    }

    @Test
    fun `prva i druga smena na praznik imaju ceo praznicni rad`() {
        val record = DayRecord(2026, 11, 11, Shift.SECOND, 8)
        val pay = calculator.calculateDailyPay(record, 300.0, BrigadeType.TROBRIGADA)

        assertEquals(8 * 300.0 * 2.23, pay, 0.01)
    }

    @Test
    fun `davanje krvi ostaje sto posto kad se na taj dan menja praznik`() {
        val repo = testRepository()
        repo.applyBloodDonationLeave(java.time.LocalDate.of(2026, 5, 4))

        repo.setHolidayActive(2026, 5, 4, isActive = true)
        assertEquals(true, repo.get(2026, 5, 4)?.isBloodDonationLeave)
        repo.setHolidayActive(2026, 5, 4, isActive = false)
        assertEquals(true, repo.get(2026, 5, 4)?.isBloodDonationLeave)
    }

    @Test
    fun `prosek za osnovicu racuna topli obrok istim pravilom kao plata`() {
        // Dani od 5h ne daju obrok po listiću (prag 6h). Stara formula u proseku
        // (prag 4h, jedna cena) je ubacivala TO2 kog na plati nema.
        val repo = testRepository()
        val workdays = (1..30).filter { day ->
            java.time.LocalDate.of(2026, 6, day).dayOfWeek.value <= 5
        }
        workdays.forEach { day -> repo.set(2026, 6, day, Shift.FIRST, 5) }

        val salaryMealGross = repo.getNetPayBreakdown(2026, 6).grossMealAddition
        val averageMonth = repo.getRollingPayBaseCalculation(2026, 8)
            ?.months
            ?.single { it.year == 2026 && it.month == 6 }

        assertEquals(0.0, salaryMealGross, 0.001)
        requireNotNull(averageMonth)
        assertEquals(0.0, averageMonth.includedMealAllowanceGross, 0.001)
    }
}
