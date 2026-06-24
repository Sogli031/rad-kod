package com.rad.shifttracker

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FoodReceiptReminderRepositoryTest {

    @Test
    fun `food receipt reminder defaults off for old backups`() {
        val repo = ShiftRepository()

        assertFalse(repo.foodReceiptReminderEnabled)
    }

    @Test
    fun `food receipt reminder enabled flag survives json backup restore`() {
        val source = ShiftRepository()
        source.foodReceiptReminderEnabled = true

        val restored = ShiftRepository()
        assertTrue(restored.importFromJson(source.exportJson()))

        assertTrue(restored.foodReceiptReminderEnabled)
    }
}
