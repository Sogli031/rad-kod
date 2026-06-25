package com.rad.shifttracker.data.storage

import com.rad.shifttracker.MonthKey

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
        val boundary = current.records.minOfOrNull { it.monthKey }
            ?: return mergeWithoutCurrentRecords(archive, current)

        val keptArchiveRecords = archive.records.filter { it.monthKey < boundary }
        val hasOlderArchiveData = keptArchiveRecords.isNotEmpty()

        return current.copy(
            records = keptArchiveRecords + current.records,
            hourlyValueChanges = mergeOlderThan(archive.hourlyValueChanges, current.hourlyValueChanges, boundary),
            monthlyPayBases = mergeOlderThan(archive.monthlyPayBases, current.monthlyPayBases, boundary),
            monthlyGrossAdditions = mergeOlderThan(archive.monthlyGrossAdditions, current.monthlyGrossAdditions, boundary),
            monthlyNetAdjustments = mergeOlderThan(archive.monthlyNetAdjustments, current.monthlyNetAdjustments, boundary),
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
        )
    }

    /**
     * Trenutno stanje nema zapisa pa nema granice koju bi ono kontrolisalo;
     * zadrži punu istoriju iz arhive, ali sva globalna podešavanja iz trenutnog.
     */
    private fun mergeWithoutCurrentRecords(
        archive: ShiftRepositorySnapshot,
        current: ShiftRepositorySnapshot
    ): ShiftRepositorySnapshot {
        return current.copy(
            records = archive.records,
            hourlyValueChanges = archive.hourlyValueChanges + current.hourlyValueChanges,
            monthlyPayBases = archive.monthlyPayBases + current.monthlyPayBases,
            monthlyGrossAdditions = archive.monthlyGrossAdditions + current.monthlyGrossAdditions,
            monthlyNetAdjustments = archive.monthlyNetAdjustments + current.monthlyNetAdjustments,
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
