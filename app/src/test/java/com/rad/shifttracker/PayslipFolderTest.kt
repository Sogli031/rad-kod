package com.rad.shifttracker

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PayslipFolderTest {

    private fun august(): ParsedPayslip =
        (parsePayslip(AUGUST_2026) as PayslipParseResult.Success).payslip

    @Test
    fun yearAndRunNumberAreReadFromRealPayslipFileNames() {
        assertEquals(PayslipFileHint(8, 26), payslipFileHint("081262_230671_Glisic Borko.pdf"))
        assertEquals(PayslipFileHint(1, 26), payslipFileHint("011262_230671_Glisic Borko(2).pdf"))
        assertEquals(PayslipFileHint(10, 25), payslipFileHint("101252_230671_Glisic_Borko.PDF"))
        assertEquals(PayslipFileHint(8, 24), payslipFileHint("081242_230671_Glisic Borko.pdf"))
        assertEquals(PayslipFileHint(10, 27), payslipFileHint("101272_230671_Glisic Borko.pdf"))
        assertNull("nije PDF", payslipFileHint("081262_230671_Glisic Borko.md"))
        assertNull("nije ime listića", payslipFileHint("Racun_08.pdf"))
        assertNull("bez donje crte posle broja", payslipFileHint("081262.pdf"))
    }

    @Test
    fun everySmallPdfIsCandidateLikelyPayslipsFirst() {
        val dir = Files.createTempDirectory("liste").toFile()
        try {
            fun pdf(name: String, modified: Long) = File(dir, name).apply {
                writeText("x")
                setLastModified(modified)
            }
            val august = pdf("081262_230671_Glisic Borko.pdf", 1_000_000L)
            val augustCopy = pdf("081262_230671_Glisic Borko(1).pdf", 2_000_000L)
            val july = pdf("071262_230671_Glisic Borko.pdf", 3_000_000L)
            val oldYear = pdf("081252_230671_Glisic Borko.pdf", 4_000_000L)
            val renamed = pdf("lista avgust.PDF", 5_000_000L)
            pdf("beleske.txt", 6_000_000L)
            File(dir, "081262_folder.pdf").mkdir()
            File(dir, "081262_veliki.pdf").apply {
                writeBytes(ByteArray(2 * 1024 * 1024))
                setLastModified(7_000_000L)
            }

            val candidates = payslipFileCandidates(dir.listFiles()!!.toList(), MonthKey(2026, 8))

            // Ime ne isključuje fajl (preimenovan listić se i dalje čita), samo menja redosled.
            assertEquals(
                listOf(augustCopy.name, august.name, july.name, renamed.name, oldYear.name),
                candidates.map { it.name }
            )
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun folderSettingIsRelativeToPhoneStorage() {
        assertEquals("Download", normalizePayslipFolder(null))
        assertEquals("Download", normalizePayslipFolder("  "))
        assertEquals("Download/Liste", normalizePayslipFolder("/Download//Liste/"))
        assertEquals("Download/Liste", normalizePayslipFolder("Download\\Liste"))
    }

    @Test
    fun pickedFolderBecomesRelativePath() {
        assertEquals("Download/Liste", payslipFolderFromTreeDocumentId("primary:Download/Liste"))
        assertEquals("Documents", payslipFolderFromTreeDocumentId("primary:Documents"))
        assertNull("SD kartica", payslipFolderFromTreeDocumentId("1A2B-3C4D:Liste"))
        assertNull(payslipFolderFromTreeDocumentId("primary"))
    }

    @Test
    fun deletedPayslipIsGoneAndOtherMonthsStay() {
        val repo = testRepository()
        repo.savePayslip(august())
        repo.savePayslip(august().copy(month = MonthKey(2026, 7)))

        repo.deletePayslip(MonthKey(2026, 8))

        assertNull(repo.getSavedPayslip(2026, 8))
        assertNotNull(repo.getSavedPayslip(2026, 7))
        assertEquals(MonthKey(2026, 7), repo.getLatestSavedPayslipMonth())
    }

    @Test
    fun deletedPayslipDoesNotComeBackFromCloudArchive() {
        val repo = testRepository()
        repo.savePayslip(august())
        val archive = repo.exportJson()

        repo.deletePayslip(MonthKey(2026, 8))
        val merged = repo.buildCloudBackup(archive).json

        val restored = testRepository()
        assertTrue(restored.importFromJson(merged))
        assertNull("obrisan listić ne sme da se vrati iz oblaka", restored.getSavedPayslip(2026, 8))

        // Ni sledeći backup (arhiva koja već zna za brisanje + stara arhiva) ga ne vraća.
        val again = restored.buildCloudBackup(merged).json
        val restoredAgain = testRepository()
        assertTrue(restoredAgain.importFromJson(again))
        assertNull(restoredAgain.getSavedPayslip(2026, 8))
    }

    @Test
    fun payslipLoadedAgainAfterDeleteIsKept() {
        val repo = testRepository()
        repo.savePayslip(august())
        repo.deletePayslip(MonthKey(2026, 8))
        val archiveWithDelete = repo.exportJson()
        assertTrue(archiveWithDelete.contains("deleted_payslip_months"))

        repo.savePayslip(august())
        assertFalse(repo.exportJson().contains("deleted_payslip_months"))

        val merged = repo.buildCloudBackup(archiveWithDelete).json
        val restored = testRepository()
        assertTrue(restored.importFromJson(merged))
        assertEquals(august(), restored.getSavedPayslip(2026, 8))
    }

    @Test
    fun payoutRowIsJustZaIsplatu() {
        assertEquals("Za isplatu", PAYSLIP_PAYOUT_ROW)
    }
}
