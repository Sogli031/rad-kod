package com.rad.shifttracker.uvoz

import com.rad.shifttracker.repo.ShiftRepository
import com.rad.shifttracker.smene.MonthKey
import com.rad.shifttracker.repo.getLatestSavedPayslipMonth
import com.rad.shifttracker.smene.monthYearText
import com.rad.shifttracker.listici.ParsedPayslip
import com.rad.shifttracker.listici.PayslipParseResult
import com.rad.shifttracker.listici.PayslipSideRunKind
import com.rad.shifttracker.listici.payslipImportErrorText

internal const val PAYSLIP_UNEXPECTED_ERROR_TEXT =
    "Lista nije mogla da se obradi (neočekivana greška). Ništa nije promenjeno — pokušaj ponovo."

internal sealed interface PayslipPickOutcome {
    data class Rejected(val text: String) : PayslipPickOutcome
    data class Ready(val payslip: ParsedPayslip, val plan: PayslipImportPlan) : PayslipPickOutcome
}

/**
 * Izabrani PDF: nečitljiv ili za drugi mesec se odbija; inače plan izmena za potvrdu.
 * [month] `null` (Opcije → ručno učitavanje) prima listu za mesec koji piše na njoj.
 * Stariji listić od najnovijeg sačuvanog ne predlaže globalna podešavanja (koeficijent, porez, staž).
 */
internal fun payslipPickOutcome(repo: ShiftRepository, result: PayslipParseResult, month: MonthKey?): PayslipPickOutcome {
    val scanned = when (result) {
        is PayslipParseResult.Failure -> return PayslipPickOutcome.Rejected(payslipImportErrorText(result.reason).orEmpty())
        is PayslipParseResult.Success -> result.payslip
        is PayslipParseResult.SideRun -> return PayslipPickOutcome.Rejected(payslipSideRunPickText(result))
    }
    if (month != null) payslipWrongMonthError(scanned.month, month)?.let { return PayslipPickOutcome.Rejected(it) }
    val latest = repo.getLatestSavedPayslipMonth()
    val plan = buildPayslipImportPlan(
        repo = repo,
        parsed = listOf(scanned),
        failures = emptyList(),
        includeGlobalSettings = latest == null || scanned.month >= latest
    )
    return PayslipPickOutcome.Ready(scanned, plan)
}

/**
 * Od ovog dana u sledećem mesecu lista za prethodni mesec može da postoji (lista za septembar: od 15. oktobra).
 * Isti dan uključuje „Listu“ i pokreće automatsku proveru konačne liste iz Gmail-a.
 */
internal const val PAYSLIP_ARRIVAL_DAY = 15

/** Listić mora biti za mesec otvoren u kalendaru; inače greška i ništa se ne učitava. */
internal fun payslipSideRunPickText(run: PayslipParseResult.SideRun): String {
    val what = if (run.kind == PayslipSideRunKind.OTHER) "poseban obračun" else "obračun ${run.kind.label}"
    val advanceNote = if (run.kind == PayslipSideRunKind.ADVANCE) " Akontacija se upisuje sama pri pokretanju." else ""
    return "Ovo je $what za ${monthYearText(run.month)}, ne lista (nema „konačna“).$advanceNote Lista stiže oko 20."
}

internal fun payslipWrongMonthError(scanned: MonthKey, selected: MonthKey): String? =
    if (scanned == selected) {
        null
    } else {
        "Pogrešna lista: izabrani PDF je za ${monthYearText(scanned)}, a otvoren je ${monthYearText(selected)}. " +
            "Lista nije učitana — otvori ${monthYearText(scanned)} u kalendaru pa je učitaj tamo."
    }
