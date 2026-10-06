package com.rad.shifttracker

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate
import com.rad.shifttracker.godisnji.VacationBalanceSummary
import com.rad.shifttracker.godisnji.VacationMonthUsage
import com.rad.shifttracker.godisnji.VacationRangePreview
import com.rad.shifttracker.godisnji.VacationWarnings
import com.rad.shifttracker.godisnji.VacationYearBalance
import com.rad.shifttracker.godisnji.calculateVacationBalanceSummary
import com.rad.shifttracker.godisnji.carryoverExpiresOn
import com.rad.shifttracker.ui.dialozi.vacationMonthUsageText
import com.rad.shifttracker.ui.dialozi.vacationRangePreviewText
import com.rad.shifttracker.ui.dialozi.vacationSetupFieldLabels
import com.rad.shifttracker.ui.dialozi.vacationSetupInitialInputs
import com.rad.shifttracker.ui.vacationCardAsOfDate
import com.rad.shifttracker.ui.vacationCardMainText
import com.rad.shifttracker.ui.vacationCardSubtitleText

class VacationUiTextTest {
    @Test
    fun `kartica pre jula prikazuje odlozenu dostupnost`() {
        val summary = VacationBalanceSummary(
            year = 2026,
            carryoverRemaining = 0.0,
            carryoverExpiresOn = LocalDate.of(2026, 6, 30),
            currentYearTotal = 23.0,
            currentYearUsed = 4.0,
            currentYearRemaining = 19.0,
            availableNow = 0.0,
            availableFromJuly = 19.0,
            warnings = listOf(VacationWarnings.NEW_VACATION_BEFORE_JULY)
        )

        assertEquals("0 sada", vacationCardMainText(summary))
        assertEquals("19 dana od 01.07.2026.", vacationCardSubtitleText(summary))
    }

    @Test
    fun `kartica posle jula prikazuje dostupno stanje`() {
        val summary = VacationBalanceSummary(
            year = 2026,
            carryoverRemaining = 0.0,
            carryoverExpiresOn = LocalDate.of(2026, 6, 30),
            currentYearTotal = 23.0,
            currentYearUsed = 5.0,
            currentYearRemaining = 18.0,
            availableNow = 18.0,
            availableFromJuly = 0.0,
            warnings = emptyList()
        )

        assertEquals("18 dana", vacationCardMainText(summary))
        assertEquals("od sada do 01.07.2027.", vacationCardSubtitleText(summary))
    }

    @Test
    fun `kartica pre jula prikazuje stari odmor kada postoji prenos`() {
        val summary = VacationBalanceSummary(
            year = 2027,
            carryoverRemaining = 17.0,
            carryoverExpiresOn = LocalDate.of(2027, 6, 30),
            currentYearTotal = 24.0,
            currentYearUsed = 0.0,
            currentYearRemaining = 24.0,
            availableNow = 17.0,
            availableFromJuly = 24.0,
            warnings = listOf(VacationWarnings.CARRYOVER_VALID_UNTIL_JUNE),
            carryoverInitial = 19.0,
            carryoverUsed = 2.0
        )

        assertEquals("17 starog", vacationCardMainText(summary))
        assertEquals("potrošeno 2/19, do 01.07.2027.", vacationCardSubtitleText(summary))
    }

    @Test
    fun `kartica odmora za trenutni mesec koristi danasnji dan`() {
        val asOfDate = vacationCardAsOfDate(
            year = 2026,
            month = 6,
            today = LocalDate.of(2026, 6, 2)
        )
        val summary = calculateVacationBalanceSummary(
            balance = VacationYearBalance(
                year = 2026,
                trackingStartDate = LocalDate.of(2026, 6, 2),
                carryoverDaysInitial = 1.0,
                currentYearDaysTotal = 20.0,
                currentYearDaysUsedBeforeAvailability = 0.0,
                currentYearAvailableFrom = LocalDate.of(2026, 7, 1),
                manualBaseline = true
            ),
            vacationDates = listOf(LocalDate.of(2026, 6, 8)),
            today = asOfDate
        )

        assertEquals(LocalDate.of(2026, 6, 2), asOfDate)
        assertEquals("1 starog", vacationCardMainText(summary))
        assertEquals("potrošeno 0/1, do 01.07.2026.", vacationCardSubtitleText(summary))
    }

    @Test
    fun `kartica odmora za drugi mesec koristi kraj tog meseca`() {
        assertEquals(
            LocalDate.of(2026, 5, 31),
            vacationCardAsOfDate(
                year = 2026,
                month = 5,
                today = LocalDate.of(2026, 6, 2)
            )
        )
    }

    @Test
    fun `pocetno stanje trazi prenos pravo za godinu i potroseno van aplikacije`() {
        assertEquals(
            listOf(
                "Preneto iz 2025. (važi do 30.06.)",
                "Ukupno za 2026. (po rešenju)",
                "Potrošeno van aplikacije"
            ),
            vacationSetupFieldLabels(2026)
        )
    }

    @Test
    fun `staro stanje uneto kao preostalo od danas trazi ponovni unos prava`() {
        val summary = calculateVacationBalanceSummary(
            balance = VacationYearBalance(2026, LocalDate.of(2026, 9, 10), 0.0, 5.0, 0.0, LocalDate.of(2026, 7, 1), true),
            vacationDates = emptyList(),
            today = LocalDate.of(2026, 9, 24)
        )
        val old = VacationYearBalance(2026, LocalDate.of(2026, 9, 10), 0.0, 5.0, 0.0, LocalDate.of(2026, 7, 1), true)
        val fromJanuary = old.copy(trackingStartDate = LocalDate.of(2026, 1, 1), currentYearDaysTotal = 24.0)

        assertEquals(listOf("0", "", "0"), vacationSetupInitialInputs(old, summary))
        assertEquals(listOf("0", "24", "0"), vacationSetupInitialInputs(fromJanuary, summary))
    }

    @Test
    fun `mesec iskoriscenog odmora kaze odakle je broj`() {
        assertEquals("12 dana · lista", vacationMonthUsageText(VacationMonthUsage(7, 12.0, true, 12)))
        assertEquals("2 dana · lista (kalendar 3)", vacationMonthUsageText(VacationMonthUsage(8, 2.0, true, 3)))
        assertEquals("1 dan · kalendar", vacationMonthUsageText(VacationMonthUsage(9, 1.0, false, 1)))
        assertEquals("2.5 dana · lista (kalendar 3)", vacationMonthUsageText(VacationMonthUsage(6, 2.5, true, 3)))
    }

    @Test
    fun `godisnji meni ima predlog po ugovoru a rucni broj sa resenja ostaje glavni`() {
        val source = appSource("VacationBalanceDialog.kt")

        assertEquals(true, source.contains("SectionLabel(text = \"Stanje\")"))
        assertEquals(true, source.contains("SectionLabel(text = \"Početno stanje\")"))
        assertEquals(true, source.contains("VacationNumberInput(setupFieldLabels[1]"))
        assertEquals(true, source.contains("AnnualLeaveEntitlementCard("))
    }

    @Test
    fun `bol go dijalog koristi blokadu za nedozvoljeni godisnji`() {
        val source = appSource("AbsenceDialog.kt")

        assertEquals(true, source.contains("requestValidation.message"))
        assertEquals(true, source.contains("!preview.requestValidation.isAllowed"))
        assertEquals(true, source.contains("return@GlowButton"))
    }

    @Test
    fun `preview tekst prikazuje preskocene dane`() {
        val preview = VacationRangePreview(
            startDate = LocalDate.of(2026, 1, 1),
            endDate = LocalDate.of(2026, 1, 7),
            countedDates = listOf(LocalDate.of(2026, 1, 5), LocalDate.of(2026, 1, 6)),
            skippedWeekendDays = 2,
            skippedHolidayDays = 2,
            skippedExistingSickDays = 0,
            warnings = listOf(VacationWarnings.RANGE_HAS_SKIPPED_DAYS)
        )

        assertEquals(
            "Računa se 2 dana. Preskočeno: 2 vikend, 2 praznik.",
            vacationRangePreviewText(preview)
        )
    }
}

private fun appSource(fileName: String): String {
    return mainSource(fileName)
}
