package com.rad.shifttracker.data.backup

/**
 * Procena da li novi backup izgleda kao slučajno masovno brisanje u odnosu na
 * poslednju poznatu dobru verziju. NE blokira upis — sa verzionisanjem dobra
 * verzija nikad ne nestaje — već daje verdikt na osnovu kojeg pozivalac:
 *  - novi backup označi kao „flagged" (zaseban, ne promoviše se u referentni),
 *  - upozori korisnika, i
 *  - retencija zaštiti poslednju ne-flagovanu verziju od brisanja.
 *
 * Sva pravila su čista funkcija ulaza → trivijalno se testira.
 */
object BackupAnomalyGuard {

    /**
     * @param minRecordsToTriggerWipe ispod ovoliko prethodnih zapisa nestanak
     *   svih zapisa nije „sumnjiv" (npr. nov korisnik s par dana koji obriše).
     * @param recordDropRatio udeo izgubljenih zapisa koji se smatra sumnjivim.
     * @param workMinutesDropRatio udeo izgubljenih radnih minuta koji se smatra
     *   sumnjivim (hvata slučaj kad broj dana ostane sličan ali su sati obrisani).
     */
    data class Config(
        val minRecordsToTriggerWipe: Int = 5,
        val recordDropRatio: Double = 0.5,
        val workMinutesDropRatio: Double = 0.5
    )

    sealed class Verdict {
        data object Ok : Verdict()
        data class Suspicious(val reasons: List<String>) : Verdict() {
            val summary: String get() = reasons.joinToString("; ")
        }

        val isSuspicious: Boolean get() = this is Suspicious
    }

    fun assess(
        previous: BackupStats?,
        candidate: BackupStats,
        config: Config = Config()
    ): Verdict {
        // Prva verzija, ili nije bilo ranijih podataka — nema s čim da se poredi.
        if (previous == null || previous.recordCount == 0) return Verdict.Ok

        val reasons = mutableListOf<String>()

        if (candidate.recordCount == 0 && previous.recordCount >= config.minRecordsToTriggerWipe) {
            reasons += "svi podaci su nestali (ranije ${previous.recordCount} dana)"
        }

        if (candidate.monthCount < previous.monthCount) {
            val lost = previous.monthCount - candidate.monthCount
            reasons += "nestalo meseci: $lost (sa ${previous.monthCount} na ${candidate.monthCount})"
        }

        if (droppedByAtLeast(previous.recordCount.toLong(), candidate.recordCount.toLong(), config.recordDropRatio)) {
            reasons += "pad broja dana: ${previous.recordCount} → ${candidate.recordCount}"
        }

        if (droppedByAtLeast(previous.totalWorkMinutes, candidate.totalWorkMinutes, config.workMinutesDropRatio)) {
            reasons += "pad radnih sati: ${previous.totalWorkMinutes / 60}h → ${candidate.totalWorkMinutes / 60}h"
        }

        return if (reasons.isEmpty()) Verdict.Ok else Verdict.Suspicious(reasons.distinct())
    }

    /** True ako je [now] manje od [before] za bar [ratio] udela (before mora biti > 0). */
    private fun droppedByAtLeast(before: Long, now: Long, ratio: Double): Boolean {
        if (before <= 0L) return false
        if (now >= before) return false
        return (before - now).toDouble() / before.toDouble() >= ratio
    }
}
