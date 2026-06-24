package com.rad.shifttracker

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate

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
    fun `pocetno stanje pre jula trazi stari novi i potroseni novi odmor`() {
        val labels = vacationSetupFieldLabels(LocalDate.of(2026, 5, 9))

        assertEquals(
            listOf(
                "Ostalo starog odmora",
                "Ukupno novog odmora",
                "Već potrošeno novog"
            ),
            labels
        )
    }

    @Test
    fun `pocetno stanje posle jula trazi samo preostali odmor`() {
        val labels = vacationSetupFieldLabels(LocalDate.of(2026, 7, 2))

        assertEquals(listOf("Preostalo odmora za ovu godinu"), labels)
    }

    @Test
    fun `godisnji meni zadrzava samo rucni unos bez automatskog proracuna`() {
        val source = appSource("VacationBalanceDialog.kt")

        assertEquals(true, source.contains("SectionLabel(text = \"Stanje\")"))
        assertEquals(true, source.contains("SectionLabel(text = \"Početno stanje\")"))
        assertEquals(true, source.contains("VacationNumberInput(setupFieldLabels[0]"))
        assertEquals(false, source.contains("Automatski obračun godišnjeg odmora"))
        assertEquals(false, source.contains("Primeni kao moj godišnji odmor"))
        assertEquals(false, source.contains("Ukupan staž osiguranja na 01.01."))
        assertEquals(false, source.contains("AnnualLeaveCheckRow("))
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
    return File("src/main/java/com/rad/shifttracker/$fileName").readText()
}
