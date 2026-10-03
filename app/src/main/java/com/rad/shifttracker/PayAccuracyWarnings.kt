package com.rad.shifttracker

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable

internal const val PAY_ACCURACY_WARNINGS_TITLE = "Proveri podešavanja"

internal const val SENIORITY_NOT_CONFIGURED_WARNING_TEXT =
    "Minuli rad nije podešen, pa se ne obračunava (na platnoj listi je to stavka MR). " +
        "Skeniraj platnu listu u „Listi“ ili podesi staž ručno."

/**
 * Podešavanja koja sistematski obaraju tačnost obračuna. Poređenje sa listićima
 * 05–08/2026: nepodešen staž znači 1.400–2.250 din neto manje svakog meseca, a
 * osnovica iz simuliranih meseci (≈810 umesto ≈950 din/h) do 8.500 din neto manje
 * u mesecu sa godišnjim odmorom.
 */
internal fun payAccuracyWarningTexts(
    payDetails: MonthlyPayDetails,
    seniorityConfigured: Boolean
): List<String> = buildList {
    if (!seniorityConfigured) add(SENIORITY_NOT_CONFIGURED_WARNING_TEXT)
    unreliablePayBaseWarningText(payDetails.vacationPayBase, payDetails.compensationMinutes)?.let(::add)
}

internal fun unreliablePayBaseWarningText(payBase: ResolvedPayBase, compensationMinutes: Int = 0): String? {
    val reason = when (payBase.source) {
        PayBaseSource.CALCULATED_PREVIOUS_12_MONTHS -> {
            val simulatedCount = payBase.calculation?.months
                ?.count { it.source == PayBaseMonthSource.SIMULATED }
                ?: 0
            if (simulatedCount <= 0) return null
            "računa se iz simuliranih meseci ($simulatedCount od 12) — bez minulog rada, prekovremenog i " +
                "stimulacija — pa je obično preniska"
        }
        PayBaseSource.DEFAULT -> "je podrazumevana vrednost, a ne tvoj prosek"
        PayBaseSource.CURRENT_MONTH,
        PayBaseSource.LAST_KNOWN,
        PayBaseSource.KNOWN_BASE -> return null
    }
    val affectedHours = if (compensationMinutes > 0) {
        " Ovaj mesec ima ${formatHoursAndMinutes(compensationMinutes)} naknada po toj osnovici."
    } else {
        ""
    }
    return "Osnovica za GO, praznik i plaćeno odsustvo (${formatPayBaseRateText(payBase.value)}) $reason. " +
        "Skeniraj platnu listu u „Listi“.$affectedHours"
}

private val MonthlyPayDetails.compensationMinutes: Int
    get() = vacationMinutes + paidLeaveMinutes + paidHolidayMinutes + sickMinutes

@Composable
internal fun PayAccuracyWarningsCard(warnings: List<String>) {
    if (warnings.isEmpty()) return
    PopupCard {
        SectionLabel(text = PAY_ACCURACY_WARNINGS_TITLE)
        warnings.forEach { warning ->
            Text(
                text = warning,
                color = THEME.warning,
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}
