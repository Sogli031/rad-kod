package com.rad.shifttracker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import com.rad.shifttracker.obracun.YearOverview
import com.rad.shifttracker.obracun.YearOverviewMonth
import com.rad.shifttracker.ui.bottomBarButtonLabels

class YearOverviewTest {
    private fun month(month: Int, net: Double?, overtime: Int = 0, sick: Int = 0) =
        YearOverviewMonth(month, net, workHours = 160, overtimeHours = overtime, nightHours = 40, sundayHours = 8, sickHours = sick, vacationHours = 0)

    @Test
    fun `zbir, prosek i najbolji mesec idu samo po mesecima sa netom`() {
        val overview = YearOverview.build(
            2026,
            listOf(month(2, 110_000.0, overtime = 12), month(1, 100_000.0), month(3, null, sick = 24)),
            emptyList()
        )
        assertEquals(listOf(1, 2, 3), overview.months.map { it.month })
        assertEquals(210_000.0, overview.totalNet, 0.001)
        assertEquals(105_000.0, overview.averageNet!!, 0.001)
        assertEquals(2, overview.bestMonth!!.month)
        assertEquals(480, overview.workHours)
        assertEquals(12, overview.overtimeHours)
        assertEquals(3.0, overview.sickDays, 0.001)
        assertNull(overview.netDifference)
    }

    @Test
    fun `prosla godina se poredi samo po istim mesecima`() {
        val overview = YearOverview.build(
            2026,
            listOf(month(1, 100_000.0), month(2, 110_000.0), month(10, null)),
            listOf(month(1, 90_000.0), month(2, 95_000.0), month(11, 120_000.0), month(12, 130_000.0))
        )
        assertEquals(setOf(1, 2), overview.comparedMonths)
        assertEquals(185_000.0, overview.previousYearNet!!, 0.001)
        assertEquals(25_000.0, overview.netDifference!!, 0.001)
    }

    @Test
    fun `dugme Pregled je u donjoj traci`() {
        assertEquals("Pregled", bottomBarButtonLabels().yearOverview)
    }
}
