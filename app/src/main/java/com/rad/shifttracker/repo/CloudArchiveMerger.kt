package com.rad.shifttracker.repo

import com.rad.shifttracker.smene.MonthKey
import com.rad.shifttracker.data.storage.ShiftRepositorySnapshot

/**
 * Spaja postojeću arhivu iz oblaka sa trenutnim (lokalno orezanim) stanjem.
 *
 * Lokalni podaci se automatski orezuju na ~24 meseca radi brzine, ali backup u
 * oblaku treba da ostane PUNA istorija. Pošto orezivanje briše stare podatke iz
 * memorije, jedini način da oblak zadrži sve jeste da svaki backup nadogradi
 * prethodnu arhivu: stari meseci (van trenutnog prozora) se čuvaju iz arhive, a
 * meseci unutar prozora dolaze iz trenutnog stanja (merodavni za izmene/brisanja).
 *
 * Granica je najstariji mesec koji trenutno stanje još ima. Sve starije od toga
 * je „samo u arhivi" i čuva se netaknuto; sve od granice naviše je merodavno iz
 * trenutnog stanja (pa se poštuju i brisanja u skorašnjim mesecima).
 */
internal object CloudArchiveMerger {

    fun merge(
        archive: ShiftRepositorySnapshot,
        current: ShiftRepositorySnapshot
    ): ShiftRepositorySnapshot {
        // Izričito obrisani meseci (tombstone) se ne vraćaju iz arhive —
        // bez ovoga bi brisanje najstarijeg meseca pomerilo granicu naviše
        // pa bi ga arhiva ponovo isporučila pri sledećem merge-u/restore-u.
        val deletedMonths = (archive.deletedMonths + current.deletedMonths).distinct()
        val tombstones = deletedMonths.toSet()

        val boundary = current.records.minOfOrNull { it.monthKey }
            ?: return mergeWithoutCurrentRecords(archive, current, tombstones, deletedMonths)
                .withPreservedManualEntries(archive, current)

        val keptArchiveRecords = archive.records.filter { it.monthKey < boundary && it.monthKey !in tombstones }
        val hasOlderArchiveData = keptArchiveRecords.isNotEmpty()

        return current.copy(
            records = keptArchiveRecords + current.records,
            deletedMonths = deletedMonths,
            foodPerDayChanges = mergeOlderThan(archive.foodPerDayChanges, current.foodPerDayChanges, boundary),
            monthlyPayBases = mergeOlderThan(
                archive.monthlyPayBases.minus(tombstones),
                current.monthlyPayBases,
                boundary
            ),
            monthlyGrossAdditions = mergeOlderThan(
                archive.monthlyGrossAdditions.minus(tombstones),
                current.monthlyGrossAdditions,
                boundary
            ),
            monthlyNetAdjustments = mergeOlderThan(
                archive.monthlyNetAdjustments.minus(tombstones),
                current.monthlyNetAdjustments,
                boundary
            ),
            vacationYearBalances = archive.vacationYearBalances + current.vacationYearBalances,
            // Kad arhiva donosi zapise pre prozora, početni saldo mora biti
            // ORIGINALNI iz arhive (pre svih tih zapisa), a ne lokalno preračunati
            // saldo na granici — inače bi se sati/odmor duplo brojali.
            initialCompBalance = if (hasOlderArchiveData) archive.initialCompBalance else current.initialCompBalance,
            initialCompBalanceMinutes = if (hasOlderArchiveData) {
                archive.initialCompBalanceMinutes
            } else {
                current.initialCompBalanceMinutes
            },
            initialVacationBalance = if (hasOlderArchiveData) {
                archive.initialVacationBalance
            } else {
                current.initialVacationBalance
            }
        ).withPreservedManualEntries(archive, current)
    }

    /**
     * Ručno uneti podaci koji ne zavise od prozora zapisa: satnice, davanja krvi i
     * datum početka rada. Trenutno stanje ume da bude „prazno" bez namere
     * korisnika (nova instalacija pre restore-a, izmena tokom prvog pokretanja),
     * pa prazna vrednost NIKAD ne sme da pregazi popunjenu iz arhive.
     *
     *  - Satnice: unija arhive i trenutnog stanja (trenutno pobeđuje), bez onih
     *    koje je korisnik izričito obrisao.
     *  - Davanja krvi i početak rada: trenutno stanje ako ga ima, inače arhiva.
     *  - Sačuvani listići: unija arhive i trenutnog stanja, bez starijih od ~24 meseca
     *    i bez onih koje je korisnik izričito obrisao.
     */
    private fun ShiftRepositorySnapshot.withPreservedManualEntries(
        archive: ShiftRepositorySnapshot,
        current: ShiftRepositorySnapshot
    ): ShiftRepositorySnapshot {
        val deletedHourlyValueMonths = (archive.deletedHourlyValueMonths + current.deletedHourlyValueMonths)
            .distinct()
            .filter { it !in current.hourlyValueChanges }
        val hourlyValueChanges =
            archive.hourlyValueChanges.minus(deletedHourlyValueMonths.toSet()) + current.hourlyValueChanges

        val currentHasBloodDonations = current.bloodDonationHistory.isNotEmpty() ||
            current.bloodDonationCount > 0 ||
            current.lastBloodDonationDate != null
        val bloodSource = if (currentHasBloodDonations) current else archive

        val currentHasSeniority = current.minuliRadStartYear > 0 || current.minuliRadLastPayslipYear > 0
        val senioritySource = if (currentHasSeniority) current else archive

        // Listići su spoljni dokumenti: unija, trenutni pobeđuje za isti mesec. Za razliku od dana,
        // ne čuvaju se zauvek: stariji od ~24 meseca pre najnovijeg upisa se izbacuju i iz oblaka.
        val payslipCutoff = records.maxOfOrNull { it.monthKey }?.minusMonthsForRetention(HISTORY_RETENTION_LOOKBACK_MONTHS)
        // Obrisan listić ostaje obrisan; ponovo učitan listić za isti mesec poništava brisanje.
        val deletedPayslipMonths = (archive.deletedPayslipMonths + current.deletedPayslipMonths)
            .distinct()
            .filter { it !in current.savedPayslips }
        val savedPayslips = (archive.savedPayslips.minus(deletedPayslipMonths.toSet()) + current.savedPayslips)
            .filterKeys { payslipCutoff == null || it >= payslipCutoff }

        return copy(
            savedPayslips = savedPayslips,
            deletedPayslipMonths = deletedPayslipMonths,
            hourlyValueChanges = hourlyValueChanges,
            regressNetChanges = archive.regressNetChanges + current.regressNetChanges,
            transportDailyByMonth = archive.transportDailyByMonth + current.transportDailyByMonth,
            deletedHourlyValueMonths = deletedHourlyValueMonths,
            lastBloodDonationDate = bloodSource.lastBloodDonationDate,
            bloodDonationCount = bloodSource.bloodDonationCount,
            bloodDonationHistory = bloodSource.bloodDonationHistory,
            minuliRadStartYear = senioritySource.minuliRadStartYear,
            minuliRadStartMonth = senioritySource.minuliRadStartMonth,
            seniorityInputMode = senioritySource.seniorityInputMode,
            minuliRadBenefitEnabled = senioritySource.minuliRadBenefitEnabled,
            minuliRadIncreaseFactor = senioritySource.minuliRadIncreaseFactor,
            minuliRadLastPayslipYear = senioritySource.minuliRadLastPayslipYear,
            minuliRadLastPayslipMonth = senioritySource.minuliRadLastPayslipMonth,
            minuliRadPayslipPercent = senioritySource.minuliRadPayslipPercent
        )
    }

    /**
     * Trenutno stanje nema zapisa pa nema granice koju bi ono kontrolisalo;
     * zadrži punu istoriju iz arhive, ali sva globalna podešavanja iz trenutnog.
     */
    private fun mergeWithoutCurrentRecords(
        archive: ShiftRepositorySnapshot,
        current: ShiftRepositorySnapshot,
        tombstones: Set<MonthKey>,
        deletedMonths: List<MonthKey>
    ): ShiftRepositorySnapshot {
        return current.copy(
            records = archive.records.filter { it.monthKey !in tombstones },
            deletedMonths = deletedMonths,
            foodPerDayChanges = archive.foodPerDayChanges + current.foodPerDayChanges,
            monthlyPayBases = archive.monthlyPayBases.minus(tombstones) + current.monthlyPayBases,
            monthlyGrossAdditions = archive.monthlyGrossAdditions.minus(tombstones) + current.monthlyGrossAdditions,
            monthlyNetAdjustments = archive.monthlyNetAdjustments.minus(tombstones) + current.monthlyNetAdjustments,
            vacationYearBalances = archive.vacationYearBalances + current.vacationYearBalances,
            initialCompBalance = archive.initialCompBalance,
            initialCompBalanceMinutes = archive.initialCompBalanceMinutes,
            initialVacationBalance = archive.initialVacationBalance
        )
    }

    private fun <T> mergeOlderThan(
        archive: Map<MonthKey, T>,
        current: Map<MonthKey, T>,
        boundary: MonthKey
    ): Map<MonthKey, T> {
        val merged = LinkedHashMap<MonthKey, T>()
        for ((key, value) in archive) {
            if (key < boundary) merged[key] = value
        }
        merged.putAll(current)
        return merged
    }
}
