package com.rad.shifttracker.ui

import androidx.compose.runtime.saveable.Saver
import com.rad.shifttracker.smene.DayRecord

/** Sealed class za upravljanje stanjem dijaloga - zamenjuje 10+ boolean varijabli */
internal sealed class DialogState {
    data object None : DialogState()
    data object AutoFill : DialogState()

    /** Prvo pokretanje: „Popuni smene“, a kad se zatvori, Gmail sa listama. */
    data object OnboardingAutoFill : DialogState()
    data object GmailOnboarding : DialogState()
    data object GmailUpdate : DialogState()
    data object SickVacation : DialogState()
    data object BasePay : DialogState()
    data object PayDetails : DialogState()
    data object SalaryDetails : DialogState()
    data object OvertimeNetImpact : DialogState()
    data object Food : DialogState()
    data object Transport : DialogState()
    data object Minuli : DialogState()
    data object Options : DialogState()
    data object CloudBackup : DialogState()
    data object AdvancePayment : DialogState()
    data object InitialCompBalance : DialogState()
    data object DeleteMonth : DialogState()
    data object VacationSettings : DialogState()
    data object BloodDonation : DialogState()
    data class DayEdit(val day: Int, val record: DayRecord?) : DialogState()
}

/**
 * Saver za [DialogState]: objekat-stanja se čuvaju po imenu, DayEdit po danu
 * (zapis se pri obnovi ponovo čita iz repozitorijuma — snimljeni snapshot bi
 * mogao biti zastareo). Bez ovoga rotacija/ubijanje procesa zatvara dijalog.
 */
internal fun dialogStateSaver(recordForDay: (Int) -> DayRecord?): Saver<DialogState, String> = Saver(
    save = { state -> dialogStateToKey(state) },
    restore = { saved -> dialogStateFromKey(saved, recordForDay) }
)

/** Ključ stanja: ime objekta (toString data object-a, nezavisno od R8 preimenovanja) ili „DayEdit:dan“. */
internal fun dialogStateToKey(state: DialogState): String = when (state) {
    is DialogState.DayEdit -> "DayEdit:${state.day}"
    else -> state.toString()
}

private val simpleDialogStatesByKey: Map<String, DialogState> = listOf(
    DialogState.None,
    DialogState.AutoFill,
    DialogState.OnboardingAutoFill,
    DialogState.GmailOnboarding,
    DialogState.GmailUpdate,
    DialogState.SickVacation,
    DialogState.BasePay,
    DialogState.PayDetails,
    DialogState.SalaryDetails,
    DialogState.OvertimeNetImpact,
    DialogState.Food,
    DialogState.Transport,
    DialogState.Minuli,
    DialogState.Options,
    DialogState.CloudBackup,
    DialogState.AdvancePayment,
    DialogState.InitialCompBalance,
    DialogState.DeleteMonth,
    DialogState.VacationSettings,
    DialogState.BloodDonation
).associateBy { dialogStateToKey(it) }

internal fun dialogStateFromKey(saved: String, recordForDay: (Int) -> DayRecord?): DialogState {
    if (saved.startsWith("DayEdit:")) {
        return saved.removePrefix("DayEdit:").toIntOrNull()
            ?.let { day -> DialogState.DayEdit(day, recordForDay(day)) }
            ?: DialogState.None
    }
    return simpleDialogStatesByKey[saved] ?: DialogState.None
}
