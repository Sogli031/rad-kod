package com.rad.shifttracker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AdvancePaymentPredictionTest {

    @Test
    fun `minimum is 40 percent of everything paid in previous month`() {
        val prediction = calculateAdvancePaymentPrediction(
            paymentMonth = MonthKey(2026, 6),
            previousPaymentMonth = MonthKey(2026, 5),
            isPreviousMonthNetPayAvailable = true,
            previousMonthAdvanceNet = 30_000.0,
            previousMonthFinalNet = 38_580.48
        )

        assertTrue(prediction.isAvailable)
        assertEquals(68_580.48, prediction.previousMonthPaidTotalNet, 0.001)
        assertEquals(27_432.0, prediction.minimumAdvanceNet, 0.001)
    }

    @Test
    fun `matches real july 2026 payslip where employer paid exactly the KU minimum`() {
        // Jun 2026: akontacija 37.080 isplaćena u julu, konačna isplata 0 (obustave su
        // pojele ceo ostatak). Akontacija za jul po listiću: 14.837 ≈ 40% × 37.080.
        val prediction = calculateAdvancePaymentPrediction(
            paymentMonth = MonthKey(2026, 8),
            previousPaymentMonth = MonthKey(2026, 7),
            isPreviousMonthNetPayAvailable = true,
            previousMonthAdvanceNet = 37_080.0,
            previousMonthFinalNet = 0.0
        )

        assertTrue(prediction.isAvailable)
        assertEquals(14_832.0, prediction.minimumAdvanceNet, 0.001)
    }

    @Test
    fun `regress is not part of the base - september 2026`() {
        // U avgustu je leglo: akontacija za jul 14.837 + konačna za jul 68.357,75 (+ regres 20.000).
        // Firma je za avgust isplatila 38.942: iznad 40% bez regresa (33.278), ispod sa njim (41.278).
        val prediction = calculateAdvancePaymentPrediction(
            paymentMonth = MonthKey(2026, 9),
            previousPaymentMonth = MonthKey(2026, 8),
            isPreviousMonthNetPayAvailable = true,
            previousMonthAdvanceNet = 14_837.0,
            previousMonthFinalNet = 68_357.75
        )

        assertEquals(33_278.0, prediction.minimumAdvanceNet, 0.001)
        assertTrue(prediction.minimumAdvanceNet <= 38_942.0)
    }
    @Test
    fun `unavailable when previous month net pay is not calculated`() {
        val prediction = calculateAdvancePaymentPrediction(
            paymentMonth = MonthKey(2026, 6),
            previousPaymentMonth = MonthKey(2026, 5),
            isPreviousMonthNetPayAvailable = false,
            previousMonthAdvanceNet = 30_000.0,
            previousMonthFinalNet = 40_000.0
        )

        assertFalse(prediction.isAvailable)
        assertEquals(0.0, prediction.minimumAdvanceNet, 0.001)
    }

    @Test
    fun `unavailable when there is no previous month or nothing was paid`() {
        val noPreviousMonth = unavailableAdvancePaymentPrediction(MonthKey(2026, 6))
        val nothingPaid = calculateAdvancePaymentPrediction(
            paymentMonth = MonthKey(2026, 6),
            previousPaymentMonth = MonthKey(2026, 5),
            isPreviousMonthNetPayAvailable = true,
            previousMonthAdvanceNet = 0.0,
            previousMonthFinalNet = 0.0
        )

        assertFalse(noPreviousMonth.isAvailable)
        assertFalse(nothingPaid.isAvailable)
    }

    @Test
    fun `invalid amounts are sanitized to zero`() {
        val prediction = calculateAdvancePaymentPrediction(
            paymentMonth = MonthKey(2026, 6),
            previousPaymentMonth = MonthKey(2026, 5),
            isPreviousMonthNetPayAvailable = true,
            previousMonthAdvanceNet = Double.NaN,
            previousMonthFinalNet = -500.0
        )

        assertFalse(prediction.isAvailable)
        assertEquals(0.0, prediction.previousMonthPaidTotalNet, 0.001)
        assertEquals(0.0, prediction.minimumAdvanceNet, 0.001)
    }
}
