package com.rad.shifttracker

import org.junit.Assert.assertEquals
import org.junit.Test

class GmailPayslipsTest {
    @Test
    fun queryCoversMonthAndThreeMonthsAfter() {
        assertEquals(
            "{subject:\"Obracun zarada\" from:platnilistic@vbs.co.rs} has:attachment filename:pdf " +
                "after:2026/8/1 before:2026/11/1",
            gmailPayslipQuery(MonthKey(2026, 8))
        )
    }

    @Test
    fun queryCrossesYearEnd() {
        assertEquals(
            "{subject:\"Obracun zarada\" from:platnilistic@vbs.co.rs} has:attachment filename:pdf " +
                "after:2026/11/1 before:2027/2/1",
            gmailPayslipQuery(MonthKey(2026, 11))
        )
    }
}
