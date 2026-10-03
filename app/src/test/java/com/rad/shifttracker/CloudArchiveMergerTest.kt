package com.rad.shifttracker

import com.rad.shifttracker.data.storage.CloudArchiveMerger
import com.rad.shifttracker.data.storage.ShiftRepositoryJsonCodec
import com.rad.shifttracker.data.storage.ShiftRepositorySnapshot
import java.time.LocalDate
import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Spajanje arhive iz oblaka sa lokalno orezanim stanjem: oblak čuva punu istoriju, ali nikad ne sme
 * da vrati ono što je korisnik izričito obrisao, ni da prepiše popunjeno stanje praznim.
 */
class CloudArchiveMergerTest {

    private val codec = ShiftRepositoryJsonCodec(CONFIG, TEST_CLOCK)

    private fun base(): ShiftRepositorySnapshot = codec.decode("{\"pay_coefficient\": 2.82}")

    private fun rec(year: Int, month: Int, day: Int = 5, shift: Shift = Shift.FIRST) =
        DayRecord(year, month, day, shift, 8)

    private fun snapshot(vararg records: DayRecord, edit: (ShiftRepositorySnapshot) -> ShiftRepositorySnapshot = { it }) =
        edit(base().copy(records = records.toList()))

    private fun months(snapshot: ShiftRepositorySnapshot): List<MonthKey> =
        snapshot.records.map { it.monthKey }.distinct().sorted()

    private fun days(snapshot: ShiftRepositorySnapshot): Set<DayKey> = snapshot.records.map { it.key }.toSet()

    private fun month(year: Int, month: Int) = MonthKey(year, month)

    // ---------------- Osnovno spajanje ----------------

    @Test
    fun `stari meseci dolaze iz arhive a meseci od granice iz trenutnog stanja`() {
        val archive = snapshot(rec(2026, 1), rec(2026, 2), rec(2026, 3, 5), rec(2026, 3, 6), rec(2026, 4))
        val current = snapshot(rec(2026, 3, 5, Shift.SECOND), rec(2026, 4), rec(2026, 5))

        val merged = CloudArchiveMerger.merge(archive, current)

        assertEquals(listOf(month(2026, 1), month(2026, 2), month(2026, 3), month(2026, 4), month(2026, 5)), months(merged))
        // Granični mesec (mart) je merodavan iz trenutnog stanja: obrisan 06.03. se ne vraća, izmena 05.03. pobeđuje.
        assertEquals(setOf(DayKey(2026, 1, 5), DayKey(2026, 2, 5), DayKey(2026, 3, 5), DayKey(2026, 4, 5), DayKey(2026, 5, 5)), days(merged))
        assertEquals(Shift.SECOND, merged.records.single { it.key == DayKey(2026, 3, 5) }.shift)
    }

    @Test
    fun `granicni mesec je najstariji mesec trenutnog stanja a ne mesec arhive`() {
        val archive = snapshot(rec(2026, 5, 10), rec(2026, 6, 10))
        val current = snapshot(rec(2026, 6, 10))

        // Arhiva ima i maj (stariji od granice jun): ostaje; jun je iz trenutnog.
        assertEquals(listOf(month(2026, 5), month(2026, 6)), months(CloudArchiveMerger.merge(archive, current)))
    }

    @Test
    fun `merge bez starijih zapisa u arhivi zadrzava trenutne pocetne salde`() {
        val archive = snapshot(rec(2026, 6)) { it.copy(initialCompBalance = 10, initialCompBalanceMinutes = 600, initialVacationBalance = 9.0) }
        val current = snapshot(rec(2026, 6)) { it.copy(initialCompBalance = 3, initialCompBalanceMinutes = 180, initialVacationBalance = 2.0) }

        val merged = CloudArchiveMerger.merge(archive, current)

        assertEquals(3, merged.initialCompBalance)
        assertEquals(180, merged.initialCompBalanceMinutes)
        assertEquals(2.0, merged.initialVacationBalance, 0.0)
    }

    @Test
    fun `arhiva sa starijim zapisima vraca originalne pocetne salde da se sati ne broje dvaput`() {
        val archive = snapshot(rec(2026, 1), rec(2026, 6)) {
            it.copy(initialCompBalance = 10, initialCompBalanceMinutes = 600, initialVacationBalance = 9.0)
        }
        // Lokalno je saldo preračunat na granici (orezano), pa bi dupliralo sate da se ne vrati originalni.
        val current = snapshot(rec(2026, 6)) {
            it.copy(initialCompBalance = 33, initialCompBalanceMinutes = 1980, initialVacationBalance = 2.0)
        }

        val merged = CloudArchiveMerger.merge(archive, current)

        assertEquals(10, merged.initialCompBalance)
        assertEquals(600, merged.initialCompBalanceMinutes)
        assertEquals(9.0, merged.initialVacationBalance, 0.0)
    }

    // ---------------- Tombstone ----------------

    @Test
    fun `obrisan najstariji mesec se ne vraca iz arhive`() {
        val archive = snapshot(rec(2026, 3), rec(2026, 4), rec(2026, 5))
        // Korisnik je obrisao mart (najstariji): granica se pomera na april, pa bi arhiva vratila mart.
        val withoutTombstone = snapshot(rec(2026, 4), rec(2026, 5))
        val withTombstone = withoutTombstone.copy(deletedMonths = listOf(month(2026, 3)))

        assertTrue(month(2026, 3) in months(CloudArchiveMerger.merge(archive, withoutTombstone)))

        val merged = CloudArchiveMerger.merge(archive, withTombstone)
        assertEquals(listOf(month(2026, 4), month(2026, 5)), months(merged))
        assertEquals(listOf(month(2026, 3)), merged.deletedMonths)
    }

    @Test
    fun `tombstone iz arhive se prenosi i vazi za naredne spajanja`() {
        val archive = snapshot(rec(2026, 2), rec(2026, 3), rec(2026, 4)) { it.copy(deletedMonths = listOf(month(2026, 3))) }
        val current = snapshot(rec(2026, 4), rec(2026, 5))

        val merged = CloudArchiveMerger.merge(archive, current)

        assertEquals(listOf(month(2026, 2), month(2026, 4), month(2026, 5)), months(merged))
        assertEquals(listOf(month(2026, 3)), merged.deletedMonths)
    }

    @Test
    fun `tombstone ne brise trenutne zapise istog meseca`() {
        val archive = snapshot(rec(2026, 3), rec(2026, 4))
        val current = snapshot(rec(2026, 3, 9), rec(2026, 4)) { it.copy(deletedMonths = listOf(month(2026, 3))) }

        val merged = CloudArchiveMerger.merge(archive, current)

        // Trenutno stanje je merodavno za sve od granice; tombstone važi samo za arhivu.
        assertEquals(setOf(DayKey(2026, 3, 9), DayKey(2026, 4, 5)), days(merged))
    }

    @Test
    fun `tombstone izbacuje i mesecne dodatke obrisanog meseca iz arhive`() {
        val march = month(2026, 3)
        val may = month(2026, 5)
        val archive = snapshot(rec(2026, 3), rec(2026, 5)) {
            it.copy(
                monthlyNetAdjustments = mapOf(
                    march to MonthlyNetAdjustments(2026, 3, 1000.0),
                    may to MonthlyNetAdjustments(2026, 5, 2000.0)
                ),
                monthlyPayBases = mapOf(march to MonthlyPayBases(2026, 3, 900.0, selectionMode = PayBaseSelectionMode.MANUAL))
            )
        }
        val current = snapshot(rec(2026, 5)) { it.copy(deletedMonths = listOf(march)) }

        val merged = CloudArchiveMerger.merge(archive, current)

        assertFalse(march in merged.monthlyNetAdjustments)
        assertTrue(march !in merged.monthlyPayBases)
        // Maj nije stariji od granice: dodatke pamti samo trenutno stanje.
        assertFalse(may in merged.monthlyNetAdjustments)
    }

    @Test
    fun `tombstone se primenjuje i kad trenutno stanje nema zapisa`() {
        val archive = snapshot(rec(2026, 3), rec(2026, 4))
        val current = snapshot().copy(deletedMonths = listOf(month(2026, 3)))

        val merged = CloudArchiveMerger.merge(archive, current)

        assertEquals(listOf(month(2026, 4)), months(merged))
    }

    // ---------------- Prazno ----------------

    @Test
    fun `prazan trenutni snapshot ne prepisuje arhivu vec zadrzava punu istoriju i salde`() {
        val archive = snapshot(rec(2026, 1), rec(2026, 2)) {
            it.copy(initialCompBalance = 7, initialCompBalanceMinutes = 420, initialVacationBalance = 5.0)
        }
        val current = snapshot().copy(payCoefficient = 3.3, taxRelief = 5_000.0)

        val merged = CloudArchiveMerger.merge(archive, current)

        assertEquals(listOf(month(2026, 1), month(2026, 2)), months(merged))
        assertEquals(7, merged.initialCompBalance)
        assertEquals(420, merged.initialCompBalanceMinutes)
        assertEquals(5.0, merged.initialVacationBalance, 0.0)
        // Globalna podešavanja su uvek iz trenutnog stanja.
        assertEquals(3.3, merged.payCoefficient, 0.0)
        assertEquals(5_000.0, merged.taxRelief, 0.0)
    }

    @Test
    fun `prazna arhiva daje trenutno stanje`() {
        val current = snapshot(rec(2026, 5), rec(2026, 6)) {
            it.copy(initialCompBalance = 4, initialCompBalanceMinutes = 240, hourlyValueChanges = mapOf(month(2026, 5) to 230.0))
        }

        val merged = CloudArchiveMerger.merge(snapshot(), current)

        assertEquals(days(current), days(merged))
        assertEquals(4, merged.initialCompBalance)
        assertEquals(240, merged.initialCompBalanceMinutes)
        assertEquals(mapOf(month(2026, 5) to 230.0), merged.hourlyValueChanges)
    }

    @Test
    fun `oba prazna daju prazno`() {
        val merged = CloudArchiveMerger.merge(snapshot(), snapshot())
        assertTrue(merged.records.isEmpty())
        assertTrue(merged.deletedMonths.isEmpty())
    }

    // ---------------- Ručni unosi ----------------

    @Test
    fun `satnice su unija a trenutna pobeduje i izbrisana ostaje izbrisana`() {
        val archive = snapshot(rec(2026, 6)) {
            it.copy(hourlyValueChanges = mapOf(month(2026, 1) to 200.0, month(2026, 3) to 210.0, month(2026, 5) to 220.0))
        }
        val current = snapshot(rec(2026, 6)) {
            it.copy(
                hourlyValueChanges = mapOf(month(2026, 5) to 225.0),
                deletedHourlyValueMonths = listOf(month(2026, 3))
            )
        }

        val merged = CloudArchiveMerger.merge(archive, current)

        assertEquals(mapOf(month(2026, 1) to 200.0, month(2026, 5) to 225.0), merged.hourlyValueChanges)
        assertEquals(listOf(month(2026, 3)), merged.deletedHourlyValueMonths)
    }

    @Test
    fun `ponovo uneta satnica ponistava brisanje`() {
        val archive = snapshot(rec(2026, 6)) { it.copy(deletedHourlyValueMonths = listOf(month(2026, 3))) }
        val current = snapshot(rec(2026, 6)) { it.copy(hourlyValueChanges = mapOf(month(2026, 3) to 240.0)) }

        val merged = CloudArchiveMerger.merge(archive, current)

        assertEquals(mapOf(month(2026, 3) to 240.0), merged.hourlyValueChanges)
        assertTrue(merged.deletedHourlyValueMonths.isEmpty())
    }

    @Test
    fun `prazna davanja krvi i staz ne pregazuju popunjena iz arhive`() {
        val donation = LocalDate.of(2026, 2, 3)
        val archive = snapshot(rec(2026, 6)) {
            it.copy(
                lastBloodDonationDate = donation,
                bloodDonationCount = 3,
                bloodDonationHistory = listOf(donation),
                minuliRadStartYear = 2015,
                minuliRadStartMonth = 4,
                minuliRadBenefitEnabled = true
            )
        }
        val current = snapshot(rec(2026, 6))

        val merged = CloudArchiveMerger.merge(archive, current)

        assertEquals(donation, merged.lastBloodDonationDate)
        assertEquals(3, merged.bloodDonationCount)
        assertEquals(2015, merged.minuliRadStartYear)
        assertEquals(4, merged.minuliRadStartMonth)
    }

    @Test
    fun `popunjena davanja krvi i staz iz trenutnog stanja pobedjuju arhivu`() {
        val archive = snapshot(rec(2026, 6)) {
            it.copy(lastBloodDonationDate = LocalDate.of(2026, 1, 1), bloodDonationCount = 1, minuliRadStartYear = 2015)
        }
        val newer = LocalDate.of(2026, 5, 5)
        val current = snapshot(rec(2026, 6)) {
            it.copy(
                lastBloodDonationDate = newer,
                bloodDonationCount = 2,
                bloodDonationHistory = listOf(newer),
                minuliRadStartYear = 2018
            )
        }

        val merged = CloudArchiveMerger.merge(archive, current)

        assertEquals(newer, merged.lastBloodDonationDate)
        assertEquals(2018, merged.minuliRadStartYear)
    }

    private fun payslip(month: Int) = payslipToJson(
        (parsePayslip(requireNotNull(PAYSLIPS_2026[month])) as PayslipParseResult.Success).payslip
    )

    @Test
    fun `listici su unija bez obrisanih i bez starijih od granice zadrzavanja`() {
        val archive = snapshot(rec(2026, 8)) {
            it.copy(savedPayslips = mapOf(month(2026, 1) to payslip(1), month(2026, 3) to payslip(3), month(2026, 5) to payslip(5)))
        }
        val current = snapshot(rec(2028, 8)) {
            it.copy(savedPayslips = mapOf(month(2028, 7) to payslip(7)), deletedPayslipMonths = listOf(month(2026, 5)))
        }

        val merged = CloudArchiveMerger.merge(archive, current)

        // Najnoviji zapis je 08/2028: granica je 08/2026, stariji listići (01, 03, 05/2026) otpadaju.
        assertEquals(setOf(month(2028, 7)), merged.savedPayslips.keys)
        assertEquals(listOf(month(2026, 5)), merged.deletedPayslipMonths)
    }

    @Test
    fun `obrisan listic ostaje obrisan a ponovo ucitan ponistava brisanje`() {
        val archive = snapshot(rec(2026, 8)) {
            it.copy(savedPayslips = mapOf(month(2026, 5) to payslip(5), month(2026, 6) to payslip(6)))
        }
        val current = snapshot(rec(2026, 8)) {
            it.copy(
                savedPayslips = mapOf(month(2026, 6) to payslip(6)),
                deletedPayslipMonths = listOf(month(2026, 5), month(2026, 6))
            )
        }

        val merged = CloudArchiveMerger.merge(archive, current)

        assertEquals(setOf(month(2026, 6)), merged.savedPayslips.keys)
        assertEquals(listOf(month(2026, 5)), merged.deletedPayslipMonths)
    }

    // ---------------- Idempotentnost ----------------

    private data class Fingerprint(val rest: ShiftRepositorySnapshot, val records: List<Pair<DayKey, Map<String, Any>>>)

    private fun fingerprint(snapshot: ShiftRepositorySnapshot) = Fingerprint(
        rest = snapshot.copy(records = emptyList()),
        records = snapshot.records.map { it.key to it.toMap() }.sortedBy { it.first.toString() }
    )

    @Test
    fun `merge je idempotentan na rucno sastavljenim slucajevima`() {
        val cases = listOf(
            snapshot(rec(2026, 1), rec(2026, 2), rec(2026, 3)) to snapshot(rec(2026, 3, 9), rec(2026, 4)),
            snapshot(rec(2026, 1), rec(2026, 3)) to snapshot(rec(2026, 4)).copy(deletedMonths = listOf(month(2026, 3))),
            snapshot(rec(2026, 1), rec(2026, 2)) to snapshot(),
            snapshot() to snapshot(rec(2026, 2)),
            snapshot() to snapshot()
        )
        for ((archive, current) in cases) {
            val once = CloudArchiveMerger.merge(archive, current)
            val twice = CloudArchiveMerger.merge(once, current)
            assertEquals(fingerprint(once), fingerprint(twice))
        }
    }

    @Test
    fun `merge je idempotentan na slucajnim snapshot-ovima`() {
        val random = Random(20261005)
        repeat(300) { iteration ->
            val archive = randomSnapshot(random)
            val current = randomSnapshot(random).let { generated ->
                // Trenutno stanje je orezan prozor arhive: pola slučajeva ima zapise samo od slučajne granice.
                if (random.nextBoolean()) {
                    val boundary = archive.records.map { it.monthKey }.sorted().let { it.getOrNull(random.nextInt(it.size + 1)) }
                    generated.copy(records = generated.records.filter { boundary == null || it.monthKey >= boundary })
                } else {
                    generated
                }
            }
            val once = CloudArchiveMerger.merge(archive, current)
            val twice = CloudArchiveMerger.merge(once, current)
            assertEquals("iteracija $iteration", fingerprint(once), fingerprint(twice))
        }
    }

    @Test
    fun `merge sa samim sobom ne menja nista osim kanonskog oblika`() {
        val random = Random(31)
        repeat(100) { iteration ->
            val snapshot = randomSnapshot(random)
            val merged = CloudArchiveMerger.merge(snapshot, snapshot)
            assertEquals("iteracija $iteration", days(snapshot), days(merged))
        }
    }

    // ---------------- Obrisan mesec koji arhiva vraća (kroz repozitorijum) ----------------

    @Test
    fun `obrisan najstariji mesec ostaje obrisan kroz ceo ciklus backup restore`() {
        val repo = testRepository()
        for (month in 3..6) repo.set(2026, month, 3, Shift.FIRST, 8)
        val firstBackup = repo.buildCloudBackup(null).json

        assertEquals(1, repo.deleteMonth(2026, 3))
        val secondBackup = repo.buildCloudBackup(firstBackup).json
        val restored = testRepository()
        assertTrue(restored.importFromJson(secondBackup))

        assertTrue("mart je obrisan", restored.getMonthRecords(2026, 3).isEmpty())
        assertEquals(listOf(MonthKey(2026, 4), MonthKey(2026, 5), MonthKey(2026, 6)), restored.getAllMonthsSorted())

        // Treći backup nad drugim ostaje isti: nema „vaskrsavanja“ pri svakom sledećem spajanju.
        assertEquals(secondBackup, repo.buildCloudBackup(secondBackup).json)
    }

    @Test
    fun `stariji meseci iz arhive prezive lokalno orezivanje`() {
        val archiveRepo = testRepository()
        for (month in 1..12) archiveRepo.set(2024, month, 3, Shift.FIRST, 8)
        for (month in 1..10) archiveRepo.set(2026, month, 3, Shift.FIRST, 8)
        val archive = archiveRepo.buildCloudBackup(null).json

        val local = testRepository()
        assertTrue(local.importFromJson(archive))
        // Lokalno je ostalo samo ~24 meseca; arhiva i dalje ima 2024.
        assertNull(local.get(2024, 1, 3))

        val snapshot = codec.decode(local.buildCloudBackup(archive).json)
        assertTrue("oblak i dalje ima 2024", DayKey(2024, 1, 3) in days(snapshot))
        assertTrue(DayKey(2026, 10, 3) in days(snapshot))
    }
}
