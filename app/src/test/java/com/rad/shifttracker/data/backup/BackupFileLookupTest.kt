package com.rad.shifttracker.data.backup

import com.google.api.services.drive.Drive
import com.google.api.services.drive.model.File as DriveFile
import com.google.api.services.drive.model.FileList
import io.mockk.every
import io.mockk.verify
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class BackupFileLookupTest {

    private data class LookupFixture(
        val lookup: BackupFileLookup,
        val files: Drive.Files
    )

    private fun mockLookup(response: FileList? = null, error: RuntimeException? = null): LookupFixture {
        val drive = mockk<Drive>()
        val files = mockk<Drive.Files>()
        val listRequest = mockk<Drive.Files.List>()
        val deleteRequest = mockk<Drive.Files.Delete>()

        every { drive.files() } returns files
        every { files.list() } returns listRequest
        every { listRequest.setSpaces(any()) } returns listRequest
        every { listRequest.setQ(any()) } returns listRequest
        every { listRequest.setOrderBy(any()) } returns listRequest
        every { listRequest.setFields(any()) } returns listRequest
        every { files.delete(any()) } returns deleteRequest
        every { deleteRequest.execute() } returns null
        if (error != null) {
            every { listRequest.execute() } throws error
        } else {
            every { listRequest.execute() } returns response
        }

        return LookupFixture(BackupFileLookup(drive), files)
    }

    @Test
    fun `findBackupFileId vraca null kada backup ne postoji`() {
        val fixture = mockLookup(response = FileList().setFiles(emptyList()))

        assertNull(fixture.lookup.findBackupFileId())
    }

    @Test
    fun `findBackupFileId vraca najnoviji backup i brise starije duplikate`() {
        val fixture = mockLookup(
            response = FileList().setFiles(
                listOf(
                    DriveFile().setId("backup-new"),
                    DriveFile().setId("backup-old")
                )
            )
        )

        assertEquals("backup-new", fixture.lookup.findBackupFileId())
        verify { fixture.files.delete("backup-old") }
    }

    @Test
    fun `findBackupFileId propagira Drive gresku`() {
        val fixture = mockLookup(error = RuntimeException("Drive unavailable"))

        val error = assertThrows(RuntimeException::class.java) {
            fixture.lookup.findBackupFileId()
        }

        assertEquals("Drive unavailable", error.message)
    }
}
