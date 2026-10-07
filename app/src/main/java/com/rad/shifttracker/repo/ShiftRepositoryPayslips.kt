package com.rad.shifttracker.repo

import com.rad.shifttracker.listici.ParsedPayslip
import com.rad.shifttracker.listici.payslipFromJsonOrNull
import com.rad.shifttracker.listici.payslipPayBaseAnchor
import com.rad.shifttracker.listici.payslipToJson
import com.rad.shifttracker.listici.payslipVacationDays
import com.rad.shifttracker.smene.MonthKey
import com.rad.shifttracker.obracun.PayBaseSelectionMode

// Sačuvani listići po radnom mesecu: čitanje, upis (uz osnovicu) i brisanje sa oznakom da se ne vraća.

/** Listić sačuvan za radni mesec („Uporedi“), ili null ako ga nema. */
internal fun ShiftRepository.getSavedPayslip(year: Int, month: Int): ParsedPayslip? {
    val key = MonthKey(year, month)
    val json = synchronized(lock) { savedPayslipJsonByMonth[key] } ?: return null
    // Listić iz oštećenog backup-a čiji mesec ne odgovara ključu ne prikazuje se pod tuđim mesecom.
    return payslipFromJsonOrNull(json)?.takeIf { it.month == key }
}

/**
 * Dani godišnjeg (šifra 10 / 8 h) sa sačuvanih listića za godinu. Listić bez šifre 10 daje 0,
 * a listić koji ne može da se pročita se preskače (taj mesec se broji po kalendaru).
 */
internal fun ShiftRepository.payslipVacationDaysByMonthUnsafe(year: Int): Map<Int, Double> =
    savedPayslipJsonByMonth.entries
        .filter { it.key.year == year }
        .mapNotNull { (key, json) ->
            val cached = payslipVacationDaysCache[key]?.takeIf { it.first == json }?.second
            val days = cached ?: payslipFromJsonOrNull(json)
                ?.takeIf { it.month == key }
                ?.let(::payslipVacationDays)
                ?.also { payslipVacationDaysCache[key] = json to it }
            days?.let { key.month to it }
        }
        .toMap()

/** Listić meseca je korisnik obrisao („Obriši listu“), pa se ne upisuje ponovo sam od sebe. */
internal fun ShiftRepository.isPayslipDeleted(month: MonthKey): Boolean = synchronized(lock) { month in deletedPayslipTombstones }

/** Mesec najnovijeg sačuvanog listića, ili null ako nijedan nije sačuvan. */
internal fun ShiftRepository.getLatestSavedPayslipMonth(): MonthKey? = synchronized(lock) { savedPayslipJsonByMonth.keys.maxOrNull() }

/** Meseci svih sačuvanih listića, od najstarijeg. */
internal fun ShiftRepository.getSavedPayslipMonths(): List<MonthKey> = synchronized(lock) { savedPayslipJsonByMonth.keys.sorted() }

/**
 * Čuva listić pod njegovim mesecem (zamenjuje raniji za isti mesec); ide i u backup.
 * Ako za taj mesec nije uneta osnovica, uzima se sa listića (GO, PO, praznik, bolovanje).
 */
/** [withPayBase] = false: listić se samo čuva, osnovica sa njega se ne upisuje (stariji listići). */
internal fun ShiftRepository.savePayslip(payslip: ParsedPayslip, withPayBase: Boolean = true) {
    val json = payslipToJson(payslip)
    val changed = synchronized(lock) {
        deletedPayslipTombstones.remove(payslip.month)
        savedPayslipJsonByMonth.put(payslip.month, json) != json
    }
    val anchor = payslipPayBaseAnchor(payslip)?.takeIf { withPayBase }
    if (anchor != null && getKnownPayBaseAnchors().none { it.month == anchor.month }) {
        setKnownPayBaseAnchor(anchor.month.year, anchor.month.month, anchor.grossHourlyBase)
        ensureKnownPayBaseMode(this)
    } else if (changed) {
        notifyChange()
    }
}

/**
 * Briše sačuvan listić meseca (i iz backup-a, preko tombstone-a). Podešavanja koja su
 * ranije upisana sa listića (koeficijent, osnovica, porez...) ostaju kakva jesu.
 */
internal fun ShiftRepository.deletePayslip(month: MonthKey) {
    val removed = synchronized(lock) {
        (savedPayslipJsonByMonth.remove(month) != null).also { wasRemoved ->
            if (wasRemoved) {
                deletedPayslipTombstones.add(month)
                payslipVacationDaysCache.remove(month)
            }
        }
    }
    if (removed) notifyChange()
}

/** Osnovica se uvek uzima sa listića; bez unetih osnovica ostaje dosadašnji proračun. */
internal fun ensureKnownPayBaseMode(repo: ShiftRepository) {
    if (repo.getKnownPayBaseAnchors().isNotEmpty() &&
        repo.defaultPayBaseSelectionModeValue != PayBaseSelectionMode.KNOWN_BASE
    ) {
        repo.setDefaultPayBaseSelection(PayBaseSelectionMode.KNOWN_BASE)
    }
}
