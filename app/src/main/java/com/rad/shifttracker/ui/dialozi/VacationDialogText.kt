package com.rad.shifttracker.ui.dialozi

import java.time.LocalDate
import com.rad.shifttracker.osnova.MONTH_NAMES
import com.rad.shifttracker.godisnji.AnnualLeaveEntitlement
import com.rad.shifttracker.godisnji.VacationBalanceSummary
import com.rad.shifttracker.godisnji.VacationMonthUsage
import com.rad.shifttracker.godisnji.VacationYearBalance
import com.rad.shifttracker.godisnji.carryoverExpiresOn
import com.rad.shifttracker.osnova.formatVacationInput

// Tekstovi dijaloga „Godišnji odmor“: početno stanje, obračun po ugovoru, iskorišćeno po mesecima.

/** Polja početnog stanja: preneto, pravo za godinu i ono što nije ni u kalendaru ni na listiću. */
internal fun vacationSetupFieldLabels(year: Int): List<String> {
    return listOf(
        "Preneto iz ${year - 1}. (važi do 30.06.)",
        "Ukupno za $year. (po rešenju)",
        "Potrošeno van aplikacije"
    )
}

internal const val VACATION_SETUP_HINT =
    "Potrošeno se računa samo: po listi za mesece sa učitanom listom, inače po kalendaru."

/**
 * Početne vrednosti polja [vacationSetupFieldLabels]. Staro stanje uneto kao „preostalo od danas“
 * ne može da se pretvori u pravo za godinu, pa to polje ostaje prazno da ga korisnik unese.
 */
internal fun vacationSetupInitialInputs(saved: VacationYearBalance?, summary: VacationBalanceSummary): List<String> {
    return when {
        saved == null -> listOf(
            formatVacationInput(summary.carryoverInitial),
            if (summary.currentYearTotal > 0.0) formatVacationInput(summary.currentYearTotal) else "",
            "0"
        )
        saved.trackingStartDate == LocalDate.of(saved.year, 1, 1) -> listOf(
            formatVacationInput(saved.carryoverDaysInitial),
            formatVacationInput(saved.currentYearDaysTotal),
            formatVacationInput(saved.currentYearDaysUsedBeforeAvailability)
        )
        else -> listOf(formatVacationInput(saved.carryoverDaysInitial), "", "0")
    }
}

/** Redovi obračuna po KU čl. 29 (naziv → dana); osnove bez dana se ne prikazuju, osim osnovnih 20. */
internal fun annualLeaveBreakdownRows(entitlement: AnnualLeaveEntitlement): List<Pair<String, String>> {
    val b = entitlement.breakdown
    val years = entitlement.input.totalSeniorityYears.toInt()
    return buildList {
        add("Osnovno" to "${b.baseDays}")
        add("Staž $years god. na 01.01." to "+${b.seniorityDays}")
        add(if (b.workingConditionsDays > 1) "Povećani rizik" to "+2" else "Uslovi rada" to "+1")
        if (b.nightWorkDays > 0) add("Noćni rad (smene)" to "+${b.nightWorkDays}")
        if (b.weekendWorkDays > 0) add("Rad vikendom (4 brigade)" to "+${b.weekendWorkDays}")
        if (b.educationDays > 0) add("III stepen spreme" to "+${b.educationDays}")
        if (b.socialDays > 0) add("Socijalni uslovi" to "+${b.socialDays}")
    }
}

/** Odakle je staž: listić (i mesec povećanja kad je viđen) ili podešavanje minulog rada. */
internal fun annualLeaveSeniorityNote(entitlement: AnnualLeaveEntitlement): String {
    val seniority = entitlement.seniority ?: return "Staž je iz podešavanja minulog rada (nema liste)."
    val month = seniority.anniversaryMonth?.let { MONTH_NAMES[it].lowercase() }
    return when {
        month != null -> "Staž je sa liste; povećava se u mesecu: $month."
        seniority.exact -> "Staž je sa januarske liste."
        else -> "Staž je procena sa liste — mesec povećanja se vidi kad lista pokaže godinu više."
    }
}

internal data class VacationSetupVisibility(val carryover: Boolean, val usedOutsideApp: Boolean)

/**
 * Polje se sakriva kad nema šta da se unese: prenos se računa iz praćene prošle godine ili je već
 * istekao (posle 30.06.); „van aplikacije“ kad svaki završen mesec ima listić ili upisane dane.
 * Već uneta vrednost van aplikacije ostaje vidljiva da bi mogla da se vrati na 0.
 */
internal fun vacationSetupVisibility(
    year: Int,
    today: LocalDate,
    saved: VacationYearBalance?,
    carryoverAutomatic: Boolean,
    monthsWithoutData: List<Int>
): VacationSetupVisibility = VacationSetupVisibility(
    carryover = !carryoverAutomatic && !today.isAfter(carryoverExpiresOn(year)),
    usedOutsideApp = monthsWithoutData.isNotEmpty() || (saved?.currentYearDaysUsedBeforeAvailability ?: 0.0) > 0.0
)

/** Red „Iskorišćeno“ za mesec: vrednost i odakle je broj. */
internal fun vacationMonthUsageText(usage: VacationMonthUsage): String {
    val whole = usage.days.toLong()
    val singular = usage.days == whole.toDouble() && whole % 10 == 1L && whole % 100 != 11L
    val days = "${formatVacationInput(usage.days)} ${if (singular) "dan" else "dana"}"
    return when {
        !usage.fromPayslip -> "$days · kalendar"
        usage.calendarDays.toDouble() != usage.days -> "$days · lista (kalendar ${usage.calendarDays})"
        else -> "$days · lista"
    }
}
