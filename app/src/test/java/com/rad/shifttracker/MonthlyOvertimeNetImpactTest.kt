package com.rad.shifttracker

import org.junit.Assert.assertEquals
import org.junit.Test

class MonthlyOvertimeNetImpactTest {
    @Test
    fun `day edit razlika koristi isti mesecni neto efekat za sest sati prekovremenog sa realnim porezom`() {
        val repo = testRepository().apply {
            minuliRadStartYearValue = 2019
            minuliRadStartMonthValue = 6
            foodPerDayValue = 0.0
            difficultWorkConditionsPercentValue = 0.0
        }
        for (day in 1..30) {
            if (DayRecord(2026, 6, day, Shift.SECOND, 8).weekday.isWorkday) {
                repo.set(2026, 6, day, Shift.SECOND, 8, holidayOverride = false)
            }
        }
        val savedRecord = repo.get(2026, 6, 1)!!
        val previewRecord = DayRecord(
            year = 2026,
            month = 6,
            day = 1,
            shift = Shift.SECOND,
            hours = 8,
            shift2 = Shift.THIRD,
            hours2 = 6,
            overtimeType = OvertimeType.PAID,
            holidayOverride = false
        )

        val dayEditDifference = repo.calculateEstimatedDailyWorkNetPay(previewRecord) -
            repo.calculateEstimatedDailyWorkNetPay(savedRecord)
        repo.set(
            2026, 6, 1, Shift.SECOND, 8,
            shift2 = Shift.THIRD,
            hours2 = 6,
            overtimeType = OvertimeType.PAID,
            holidayOverride = false
        )
        val impact = repo.getMonthlyOvertimeNetImpact(2026, 6)

        assertEquals(dayEditDifference, impact.paidOvertimeNetDifference, 0.01)
    }

    @Test
    fun `day edit razlika bez minulog rada ostaje marginalna i poklapa se sa prekovremenim`() {
        val repo = testRepository().apply {
            foodPerDayValue = 0.0
            difficultWorkConditionsPercentValue = 0.0
        }
        for (day in 1..30) {
            if (DayRecord(2026, 6, day, Shift.SECOND, 8).weekday.isWorkday) {
                repo.set(2026, 6, day, Shift.SECOND, 8, holidayOverride = false)
            }
        }
        val savedRecord = repo.get(2026, 6, 1)!!
        val previewRecord = DayRecord(
            year = 2026,
            month = 6,
            day = 1,
            shift = Shift.SECOND,
            hours = 8,
            shift2 = Shift.THIRD,
            hours2 = 6,
            overtimeType = OvertimeType.PAID,
            holidayOverride = false
        )

        val dayEditDifference = repo.calculateEstimatedDailyWorkNetPay(previewRecord) -
            repo.calculateEstimatedDailyWorkNetPay(savedRecord)
        val overtimeGross = repo.calculateEstimatedDailyWorkGrossPay(previewRecord) -
            repo.calculateEstimatedDailyWorkGrossPay(savedRecord)
        repo.set(
            2026, 6, 1, Shift.SECOND, 8,
            shift2 = Shift.THIRD,
            hours2 = 6,
            overtimeType = OvertimeType.PAID,
            holidayOverride = false
        )
        val impact = repo.getMonthlyOvertimeNetImpact(2026, 6)
        val marginalNetFactor = 1.0 -
            CONFIG.pay.defaultEmployeeContributionsPercent / 100.0 -
            CONFIG.pay.defaultTaxPercent / 100.0

        // Bez podešenog minulog rada oba prikaza i dalje daju isti iznos.
        assertEquals(impact.paidOvertimeNetDifference, dayEditDifference, 0.01)
        // Iznos je marginalan (bruto × faktor), bez lažnog uvećanja preko prosečnog
        // odnosa neto/bruto i bez minulog rada koji nije podešen.
        assertEquals(overtimeGross * marginalNetFactor, dayEditDifference, 0.01)
    }

    @Test
    fun `day edit preview i prekovremeno prikaz ne racunaju minuli na placena cetiri overtime sata`() {
        val repo = overtimeImpactRepo().apply {
            minuliRadStartYearValue = 2019
            minuliRadStartMonthValue = 6
        }
        repo.set(2026, 6, 3, Shift.SECOND, 8, holidayOverride = false)
        val savedRecord = repo.get(2026, 6, 3)!!
        val previewRecord = DayRecord(
            year = 2026,
            month = 6,
            day = 3,
            shift = Shift.SECOND,
            hours = 8,
            shift2 = Shift.THIRD,
            hours2 = 4,
            overtimeType = OvertimeType.PAID,
            holidayOverride = false
        )

        val dayEditDifference = repo.calculateEstimatedDailyWorkNetPay(previewRecord) -
            repo.calculateEstimatedDailyWorkNetPay(savedRecord)
        repo.set(
            2026, 6, 3, Shift.SECOND, 8,
            shift2 = Shift.THIRD,
            hours2 = 4,
            overtimeType = OvertimeType.PAID,
            holidayOverride = false
        )
        val impact = repo.getMonthlyOvertimeNetImpact(2026, 6)
        val overtimeGross = 4 * 1_000.0 * (
            1.0 + (CONFIG.pay.shift3Premium - 1.0) + (CONFIG.pay.overtimePremium - 1.0)
            )

        assertEquals(overtimeGross, dayEditDifference, 0.01)
        assertEquals(overtimeGross, impact.paidOvertimeNetDifference, 0.01)
        assertEquals(dayEditDifference, impact.paidOvertimeNetDifference, 0.01)
    }

    @Test
    fun `day edit preview i prekovremeno prikaz ne uvecavaju iznos kada minuli rad nije podesen`() {
        val repo = overtimeImpactRepo()
        repo.set(2026, 6, 3, Shift.SECOND, 8, holidayOverride = false)
        val savedRecord = repo.get(2026, 6, 3)!!
        val previewRecord = DayRecord(
            year = 2026,
            month = 6,
            day = 3,
            shift = Shift.SECOND,
            hours = 8,
            shift2 = Shift.THIRD,
            hours2 = 4,
            overtimeType = OvertimeType.PAID,
            holidayOverride = false
        )

        val dayEditDifference = repo.calculateEstimatedDailyWorkNetPay(previewRecord) -
            repo.calculateEstimatedDailyWorkNetPay(savedRecord)
        repo.set(
            2026, 6, 3, Shift.SECOND, 8,
            shift2 = Shift.THIRD,
            hours2 = 4,
            overtimeType = OvertimeType.PAID,
            holidayOverride = false
        )
        val impact = repo.getMonthlyOvertimeNetImpact(2026, 6)
        val expectedNetWithoutSeniority = 4 * 1_000.0 * (
            1.0 + (CONFIG.pay.shift3Premium - 1.0) + (CONFIG.pay.overtimePremium - 1.0)
            )

        assertEquals(expectedNetWithoutSeniority, dayEditDifference, 0.01)
        assertEquals(expectedNetWithoutSeniority, impact.paidOvertimeNetDifference, 0.01)
        assertEquals(dayEditDifference, impact.paidOvertimeNetDifference, 0.01)
    }

    @Test
    fun `prekovremeno racuna nepun sat preko osam sati bez odsecanja na cele sate`() {
        val repo = overtimeImpactRepo()
        // Sreda: 7h30 (7h + 30min) prva + 2h druga = 9h30 ukupno -> 90 min prekovremenog.
        repo.set(
            2026, 6, 3, Shift.FIRST, 7,
            shift2 = Shift.SECOND,
            hours2 = 2,
            overtimeType = OvertimeType.PAID,
            regularShortageMinutes = 30,
            holidayOverride = false
        )

        val impact = repo.getMonthlyOvertimeNetImpact(2026, 6)

        // 90 min × 1000/h × (1 + 0.41) = 2115; odsecanjem na 1h bilo bi pogrešnih 1410.
        assertEquals(2_115.0, impact.paidOvertimeNetDifference, 0.01)
    }

    @Test
    fun `subota praznik ne ulazi u prekovremeni rad`() {
        val repo = overtimeImpactRepo()
        repo.set(2026, 5, 2, Shift.FIRST, 5)

        val impact = repo.getMonthlyOvertimeNetImpact(2026, 5)

        assertEquals(0, impact.overtimeHours)
        assertEquals(0.0, impact.netDifference, 0.01)
    }

    @Test
    fun `obicna subota racuna samo prekovremeni rad bez praznicnog dodatka`() {
        val repo = overtimeImpactRepo()
        repo.set(2026, 6, 6, Shift.FIRST, 8, holidayOverride = false)

        val impact = repo.getMonthlyOvertimeNetImpact(2026, 6)

        assertEquals(8, impact.overtimeHours)
        assertEquals(11_280.0, impact.netDifference, 0.01)
    }

    @Test
    fun `radni dan sa dodatnim satima racuna samo razliku iznad redovnih sati`() {
        val repo = overtimeImpactRepo()
        repo.set(2026, 6, 3, Shift.FIRST, 12, holidayOverride = false)

        val impact = repo.getMonthlyOvertimeNetImpact(2026, 6)

        assertEquals(4, impact.overtimeHours)
        assertEquals(5_640.0, impact.netDifference, 0.01)
    }

    @Test
    fun `minus bilansa prikazuje gubitak sa dodatkom trece smene i konacnu razliku`() {
        val repo = overtimeImpactRepo()
        repo.set(2026, 6, 1, Shift.THIRD, 0, holidayOverride = false)
        repo.set(2026, 6, 2, Shift.FIRST, 3, holidayOverride = false)
        repo.set(2026, 6, 6, Shift.FIRST, 8, holidayOverride = false)

        val impact = repo.getMonthlyOvertimeNetImpact(2026, 6)

        assertEquals(8, impact.overtimeHours)
        assertEquals(11_280.0, impact.paidOvertimeNetDifference, 0.01)
        assertEquals(16_280.0, impact.deficitNetLoss, 0.01)
        assertEquals(-5_000.0, impact.netDifference, 0.01)
    }

    @Test
    fun `off dan koristi popunjenu smenu iz iste sedmice za gubitak manjka`() {
        val repo = overtimeImpactRepo()
        repo.set(2026, 6, 1, Shift.THIRD, 8, holidayOverride = false)
        repo.set(2026, 6, 2, Shift.THIRD, 8, holidayOverride = false)
        repo.set(2026, 6, 3, Shift.OFF, 0, holidayOverride = false)
        repo.set(2026, 6, 4, Shift.THIRD, 8, holidayOverride = false)
        repo.set(2026, 6, 5, Shift.THIRD, 8, holidayOverride = false)
        repo.set(2026, 6, 6, Shift.FIRST, 8, holidayOverride = false)

        val impact = repo.getMonthlyOvertimeNetImpact(2026, 6)

        assertEquals(8, impact.overtimeHours)
        assertEquals(11_280.0, impact.paidOvertimeNetDifference, 0.01)
        assertEquals(11_280.0, impact.deficitNetLoss, 0.01)
        assertEquals(0.0, impact.netDifference, 0.01)
    }

    @Test
    fun `za sate u negativnom bilansu ulazi kao obican sat bez dodataka`() {
        val repo = overtimeImpactRepo()
        repo.set(2026, 6, 1, Shift.OFF, 0, holidayOverride = false)
        repo.set(
            2026, 6, 6, Shift.FIRST, 4,
            weekendOvertimeType = OvertimeType.COMP_TIME,
            holidayOverride = false
        )

        val impact = repo.getMonthlyOvertimeNetImpact(2026, 6)

        assertEquals(0, impact.overtimeHours)
        assertEquals(0.0, impact.paidOvertimeNetDifference, 0.01)
        assertEquals(4_000.0, impact.compTimeRecoveryNetEffect, 0.01)
        assertEquals(8_000.0, impact.deficitNetLoss, 0.01)
        assertEquals(-4_000.0, impact.netDifference, 0.01)
    }

    @Test
    fun `vracanje kroz za sate koristi marginalni neto uz poresku olaksicu`() {
        val repo = testRepository().apply {
            grossHourlyRateValue = 1_000.0
            foodPerDayValue = 0.0
            difficultWorkConditionsPercentValue = 0.0
        }
        // Radni fond da mesečni bruto pređe poresku olakšicu (34.221).
        for (day in listOf(2, 3, 4, 5, 8, 9, 10, 11, 12)) {
            repo.set(2026, 6, day, Shift.SECOND, 8, holidayOverride = false)
        }
        // OFF na radni dan pravi negativan bilans (manjak 8h).
        repo.set(2026, 6, 1, Shift.OFF, 0, holidayOverride = false)
        // Subota "Za sate" (COMP) vraća 4h dok je bilans negativan.
        repo.set(
            2026, 6, 6, Shift.FIRST, 4,
            weekendOvertimeType = OvertimeType.COMP_TIME,
            holidayOverride = false
        )

        val impact = repo.getMonthlyOvertimeNetImpact(2026, 6)
        val marginalNetFactor = 1.0 -
            CONFIG.pay.defaultEmployeeContributionsPercent / 100.0 -
            CONFIG.pay.defaultTaxPercent / 100.0
        val expectedRecovery = 4 * 1_000.0 * marginalNetFactor

        // "Vraćeno kroz Za sate" mora da koristi marginalni neto (kao gubitak manjka i
        // prekovremeno), a ne prosečnu informativnu satnicu sa razmazanom olakšicom.
        assertEquals(expectedRecovery, impact.compTimeRecoveryNetEffect, 0.01)
    }
}

private fun overtimeImpactRepo(): ShiftRepository {
    return testRepository().apply {
        grossHourlyRateValue = 1_000.0
        foodPerDayValue = 0.0
        employeeContributionsPercentValue = 0.0
        taxReliefValue = 0.0
        taxPercentValue = 0.0
        difficultWorkConditionsPercentValue = 0.0
    }
}
