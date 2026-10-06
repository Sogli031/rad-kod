package com.rad.shifttracker

import java.util.Locale
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import com.rad.shifttracker.listici.PayslipParseResult
import com.rad.shifttracker.listici.parsePayslip
import com.rad.shifttracker.obracun.MonthlyPayBases
import com.rad.shifttracker.repo.ShiftRepository
import com.rad.shifttracker.repo.exportCloudBackupJson
import com.rad.shifttracker.repo.getFoodPerDayForMonth
import com.rad.shifttracker.repo.getHourlyValueChanges
import com.rad.shifttracker.repo.getKnownPayBaseAnchors
import com.rad.shifttracker.repo.getMonthlyPayBases
import com.rad.shifttracker.repo.getSavedPayslip
import com.rad.shifttracker.repo.importFromJson
import com.rad.shifttracker.repo.savePayslip
import com.rad.shifttracker.repo.setFoodPerDayFromMonth
import com.rad.shifttracker.repo.setHourlyValueChange
import com.rad.shifttracker.repo.setKnownPayBaseAnchor
import com.rad.shifttracker.repo.setMonthlyAdvancePaymentNet
import com.rad.shifttracker.repo.setMonthlyPayBases
import com.rad.shifttracker.smene.MonthKey
import com.rad.shifttracker.smene.Shift

/** Backup ne sme da zavisi od jezika sistema: jezici sa ne-ASCII ciframa ne smeju da izgube podatke. */
class BackupLocaleTest {

    private fun filledRepository(): ShiftRepository {
        val repo = testRepository()
        repo.set(2025, 12, 31, Shift.FIRST, 8, holidayOverride = false)
        repo.set(2026, 2, 10, Shift.SECOND, 8, holidayOverride = false)
        repo.set(2026, 3, 5, Shift.FIRST, 8, holidayOverride = false)
        repo.set(2026, 8, 14, Shift.SECOND, 8, holidayOverride = false)
        repo.deleteMonth(2026, 2)
        repo.setHourlyValueChange(2026, 7, 480.0)
        repo.setFoodPerDayFromMonth(2026, 7, 650.0)
        repo.setMonthlyAdvancePaymentNet(2026, 6, 25_000.0)
        repo.setMonthlyPayBases(
            2026,
            5,
            MonthlyPayBases(year = 2026, month = 5, vacationHourlyBase = 501.25, sickHourlyBase = 450.0)
        )
        repo.setKnownPayBaseAnchor(2026, 4, 512.5)
        repo.savePayslip((parsePayslip(AUGUST_2026) as PayslipParseResult.Success).payslip)
        return repo
    }

    private fun <T> withDefaultLocale(locale: Locale, block: () -> T): T {
        val previous = Locale.getDefault()
        Locale.setDefault(locale)
        try {
            return block()
        } finally {
            Locale.setDefault(previous)
        }
    }

    @Test
    fun cloudBackupSurvivesArabicDefaultLocale() {
        val rootJson = withDefaultLocale(Locale.ROOT) { filledRepository().exportCloudBackupJson(null) }

        withDefaultLocale(Locale("ar")) {
            // Bez ovoga test ništa ne dokazuje: "%02d" na arapskom mora dati arapske cifre.
            assertNotEquals("03", "%02d".format(3))

            val source = filledRepository()
            val backupJson = source.exportCloudBackupJson(null)
            assertEquals("backup ne sme da zavisi od jezika sistema", rootJson, backupJson)
            assertFalse(
                "ključevi i vrednosti moraju imati samo ASCII cifre",
                backupJson.any { it.isDigit() && it !in '0'..'9' }
            )

            val restored = testRepository()
            assertTrue(restored.importFromJson(backupJson))
            assertEquals(backupJson, restored.exportCloudBackupJson(null))

            assertEquals(Shift.FIRST, restored.get(2025, 12, 31)?.shift)
            assertEquals(Shift.FIRST, restored.get(2026, 3, 5)?.shift)
            assertEquals(Shift.SECOND, restored.get(2026, 8, 14)?.shift)
            assertTrue("obrisan mesec ostaje prazan", restored.getMonthRecords(2026, 2).isEmpty())
            assertTrue(
                "obrisan mesec mora ostati zabeležen",
                JSONObject(backupJson).getJSONArray("deleted_months").toString().contains("2026-02")
            )
            assertEquals(source.getHourlyValueChanges(), restored.getHourlyValueChanges())
            assertEquals(650.0, restored.getFoodPerDayForMonth(2026, 7), 0.0)
            assertEquals(source.getMonthlyPayBases(2026, 5), restored.getMonthlyPayBases(2026, 5))
            assertEquals(source.getKnownPayBaseAnchors(), restored.getKnownPayBaseAnchors())
            assertTrue(restored.getKnownPayBaseAnchors().any { it.month == MonthKey(2026, 4) })
            assertNotNull("sačuvan listić mora preživeti restore", restored.getSavedPayslip(2026, 8))
            assertEquals(source.getSavedPayslip(2026, 8), restored.getSavedPayslip(2026, 8))
        }
    }
}
