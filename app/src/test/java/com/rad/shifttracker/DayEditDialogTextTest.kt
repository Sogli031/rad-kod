package com.rad.shifttracker

import com.rad.shifttracker.ui.components.AppIcons
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File
import java.time.LocalDate

class DayEditDialogTextTest {
    @Test
    fun primaryShiftQuestionUsesDirectAddress() {
        assertEquals("Izaberi smenu", DAY_EDIT_PRIMARY_SHIFT_QUESTION_TEXT)
    }

    @Test
    fun shiftSelectorsUseRequestedCompactMinimumHeight() {
        val source = dayEditDialogSource()

        // Redizajn: jedan zajednički selektor smene, dugmad kroz DayEditChoiceButton (heightIn min).
        // 48dp = M3 minimum dodirne površine.
        assertEquals(48, source.constantValue("DAY_EDIT_SHIFT_CHOICE_HEIGHT"))
        assertEquals(0, Regex("""\.height\(DAY_EDIT_SHIFT_CHOICE_HEIGHT\.dp\)""").findAll(source).count())
        assertEquals(1, Regex("""minHeight = DAY_EDIT_SHIFT_CHOICE_HEIGHT""").findAll(source).count())
        assertEquals(true, source.contains("SingleChoiceSegmentedButtonRow("))
        assertEquals(true, source.contains(".heightIn(min = minHeight.dp)"))
    }

    @Test
    fun overtimeTypeSelectorsUseAdaptiveMinimumHeight() {
        val source = dayEditDialogSource()

        assertEquals(48, source.constantValue("OVERTIME_TYPE_CHOICE_HEIGHT"))
        assertEquals(0, Regex("""\.height\(OVERTIME_TYPE_CHOICE_HEIGHT\.dp\)""").findAll(source).count())
        // Selektor prekovremenog i izbor procenta bolovanja (segmentirani izbor).
        assertEquals(2, Regex("""minHeight = OVERTIME_TYPE_CHOICE_HEIGHT""").findAll(source).count())
        assertEquals(true, source.contains(".heightIn(min = minHeight.dp)"))
    }

    @Test
    fun dayEditTextControlsUseMinimumHeightsInsteadOfExactHeights() {
        val source = dayEditDialogSource()

        assertEquals(0, Regex("""\.height\(DAY_TYPE_CHOICE_HEIGHT\.dp\)""").findAll(source).count())
        assertEquals(5, Regex("""minHeight = DAY_TYPE_CHOICE_HEIGHT""").findAll(source).count())
        assertEquals(false, source.contains(".height(UI.buttonHeight.dp)"))
        assertEquals(false, source.contains(".height(UI.saveButtonHeight.dp)"))
        assertEquals(true, source.contains(".heightIn(min = UI.buttonHeight.dp)"))
        assertEquals(true, source.contains(".heightIn(min = UI.saveButtonHeight.dp)"))
        assertEquals(false, source.contains(".height(44.dp)"))
        assertEquals(true, source.contains(".heightIn(min = minHeight.dp)"))
    }

    @Test
    fun shiftButtonTextUsesReadableLabelsWithoutCodes() {
        assertEquals("Prva", dayEditShiftButtonText(Shift.FIRST))
        assertEquals("Druga", dayEditShiftButtonText(Shift.SECOND))
        assertEquals("Treća", dayEditShiftButtonText(Shift.THIRD))
    }

    @Test
    fun offDayChoiceUsesNotWorkedLabel() {
        assertEquals("Nisam radio", dayEditShiftButtonText(Shift.OFF))
        assertEquals("Nisam radio, bez radnih sati.", dayEditNonWorkDayDetailText(Shift.OFF))
    }

    @Test
    fun bottomBarUsesRequestedLabels() {
        val labels = bottomBarButtonLabels()

        assertEquals("Popuni", labels.autoFill)
        assertEquals("Krv", labels.holidays)
        assertEquals("Opcije", labels.options)
    }

    @Test
    fun bottomBarUsesMaterialIcons() {
        val specs = bottomBarButtonSpecs()

        assertEquals(AppIcons.today, specs.autoFill.icon)
        assertEquals(AppIcons.settings, specs.options.icon)
    }

    @Test
    fun calendarDoesNotRenderLegendAboveGrid() {
        val screenSource = appSource("ShiftTrackerScreen.kt")
        val calendarSource = appSource("ShiftCalendar.kt")

        assertEquals(false, screenSource.contains("CalendarLegend()"))
        assertEquals(false, calendarSource.contains("CalendarLegendItem(\"Dupla\""))
        assertEquals(false, calendarSource.contains("CalendarLegendItem(\"Bilans\""))
        assertEquals(false, calendarSource.contains("CalendarLegendItem(\"Praznik\""))
    }

    @Test
    fun calendarHolidayUsesWarningBackgroundInsteadOfDotOrTextColor() {
        val source = appSource("ShiftCalendar.kt")
        val scheme = RadDarkColorScheme
        val ext = DarkExtendedColors
        val worked = DayRecord(2026, 1, 7, Shift.FIRST, 8)

        // Praznik (i rad na praznik) tonira ćeliju bojom upozorenja, bez obzira na smenu.
        assertEquals(ext.warning, calendarDayTint(null, isHolidayDay = true, workedHoliday = false, scheme, ext).dayNumber)
        assertEquals(ext.warning, calendarDayTint(worked, isHolidayDay = false, workedHoliday = true, scheme, ext).dayNumber)
        assertEquals(ext.shift1, calendarDayTint(worked, isHolidayDay = false, workedHoliday = false, scheme, ext).dayNumber)
        assertEquals(scheme.onSurfaceVariant, calendarDayTint(null, isHolidayDay = false, workedHoliday = false, scheme, ext).dayNumber)
        assertEquals(false, source.contains("holidayIndicatorColor"))
        assertEquals(false, source.contains("holiday = holidayIndicatorColor"))
        assertEquals(false, source.contains("calendarDayDetailColor"))
    }

    @Test
    fun calendarHolidayWithoutRecordShowsPaidHolidayText() {
        assertEquals("Praznik", calendarDayDetailText(record = null, isHolidayDay = true, workedHoliday = false))
    }

    @Test
    fun calendarDayTextShrinksHolidayLabelWithoutForcingSingleLineCells() {
        val source = appSource("ShiftCalendar.kt")

        // Detalj dana se prvo smanjuje (AutoSizeText), a ne seče odmah.
        assertEquals(true, source.contains("AutoSizeText("))
        assertEquals(true, source.contains("text = detailLines[0]"))
        assertEquals(true, source.contains("minFontSize = CALENDAR_DETAIL_FONT_SIZE.sp"))
    }

    @Test
    fun calendarWorkedHolidayShowsOnlyShiftAndHours() {
        val record = DayRecord(2026, 5, 1, Shift.FIRST, 8)

        assertEquals("${Shift.FIRST.icon}\n8h", calendarDayDetailText(record, isHolidayDay = true, workedHoliday = true))
    }

    @Test
    fun popupsUseSharedMaximumHeight() {
        assertEquals(760, UI.popupMaxHeight)
        assertEquals(28, UI.popupEdgePadding)
        // List sa dna pokriva donju traku, pa mu ne treba dodatni razmak iznad nje.
        assertEquals(0, UI.popupBottomSafePadding)
    }

    @Test
    fun defaultOvertimeShiftFollowsRegularShift() {
        assertEquals(Shift.SECOND, defaultOvertimeShiftAfter(Shift.FIRST))
        assertEquals(Shift.THIRD, defaultOvertimeShiftAfter(Shift.SECOND))
        assertEquals(Shift.FIRST, defaultOvertimeShiftAfter(Shift.THIRD))
    }

    @Test
    fun overtimeShiftMustBeLaterThanRegularShift() {
        assertEquals(listOf(Shift.SECOND, Shift.THIRD), overtimeShiftsAfter(Shift.FIRST))
        assertEquals(listOf(Shift.THIRD), overtimeShiftsAfter(Shift.SECOND))
        assertEquals(listOf(Shift.FIRST), overtimeShiftsAfter(Shift.THIRD))
        assertEquals(emptyList<Shift>(), overtimeShiftsAfter(Shift.OFF))
    }

    @Test
    fun defaultOvertimeHoursDefaultsToFourForWorkShifts() {
        assertEquals(4, defaultOvertimeHours(Shift.FIRST))
        assertEquals(4, defaultOvertimeHours(Shift.SECOND))
        assertEquals(4, defaultOvertimeHours(Shift.THIRD))
        assertEquals(0, defaultOvertimeHours(Shift.OFF))
        assertEquals(0, defaultOvertimeHours(Shift.SICK))
    }

    @Test
    fun regularShortageMinuteOptionsStayQuarterHourOnly() {
        assertEquals(listOf(0, 15, 30, 45), regularShortageMinuteOptions())
        assertEquals("0", formatShortageMinutes(0))
        assertEquals("15min", formatShortageMinutes(15))
        assertEquals("30min", formatShortageMinutes(30))
        assertEquals("45min", formatShortageMinutes(45))
    }

    @Test
    fun previousShiftInSameWeekUsesNearestPreviousWorkedDay() {
        val records = mapOf(
            LocalDate.of(2026, 5, 7) to DayRecord(2026, 5, 7, Shift.FIRST, 8),
            LocalDate.of(2026, 5, 8) to DayRecord(2026, 5, 8, Shift.SECOND, 8)
        )

        val shift = previousWorkedShiftInSameWeek(LocalDate.of(2026, 5, 9)) { records[it] }

        assertEquals(Shift.SECOND, shift)
    }

    @Test
    fun previousShiftInSameWeekSkipsNonWorkedDaysAndDoesNotCrossWeekStart() {
        val records = mapOf(
            LocalDate.of(2026, 5, 4) to DayRecord(2026, 5, 4, Shift.THIRD, 0),
            LocalDate.of(2026, 5, 5) to DayRecord(2026, 5, 5, Shift.SICK, 8),
            LocalDate.of(2026, 5, 6) to DayRecord(2026, 5, 6, Shift.OFF, 0),
            LocalDate.of(2026, 5, 7) to DayRecord(2026, 5, 7, Shift.THIRD, 8)
        )

        assertEquals(
            Shift.THIRD,
            previousWorkedShiftInSameWeek(LocalDate.of(2026, 5, 9)) { records[it] }
        )
        assertNull(previousWorkedShiftInSameWeek(LocalDate.of(2026, 5, 4)) { records[it] })
    }

    @Test
    fun dayEditPreviewRecordUsesUnsavedDialogFields() {
        val preview = DayEditSaveState(
            date = DayKey(2026, 6, 4),
            shift1 = Shift.SICK,
            workHours1 = 6,
            shift2 = Shift.SECOND,
            workHours2 = 4,
            hasDouble = true,
            firstShiftAllowsDouble = false,
            overtimeType = OvertimeType.COMP_TIME,
            weekendOvertimeType = OvertimeType.COMP_TIME,
            compHoursUsed = 2,
            holidayWorkOverride = true,
            holidayOverride = false,
            sickPayRate = 0.8
        ).toDayRecord()

        assertEquals(Shift.SICK, preview.shift)
        assertEquals(CONFIG.pay.defaultHours, preview.hours)
        assertEquals(null, preview.shift2)
        assertEquals(0, preview.hours2)
        assertEquals(2, preview.compHoursUsed)
        assertEquals(0.8, preview.sickPayRate ?: 0.0, 0.01)
    }

    @Test
    fun sickDayEditShowsOnlyPayRateChoices() {
        val source = dayEditDialogSource()
        val sickBranch = source.substring(
            source.indexOf("Shift.SICK -> "),
            source.indexOf("Shift.VACATION -> DayEditVacationControls(")
        )
        val sickControls = functionSource(source, "fun DayEditSickControls(")

        assertEquals(false, sickBranch.contains("HoursPickerState("))
        assertEquals(false, sickBranch.contains("onHoursClick"))
        assertEquals(false, sickControls.contains("onHoursClick"))
        assertEquals(false, sickControls.contains("DayEditFixedHoursBox("))
        assertEquals(false, sickControls.contains("Sati:"))
        assertEquals(true, sickControls.contains("DayEditChoiceHeader(text = \"Isplata bolovanja\")"))
        assertEquals(true, sickControls.contains("CONFIG.pay.sickPayRate to"))
        assertEquals(true, sickControls.contains("1.0 to \"100%\""))
    }

    @Test
    fun vacationDayEditShowsWarningWithoutManualHoursSelector() {
        val source = dayEditDialogSource()
        val vacationBranch = source.substring(
            source.indexOf("Shift.VACATION -> DayEditVacationControls("),
            source.indexOf("Shift.OFF -> DayEditOffControls()")
        )
        val vacationControls = functionSource(source, "fun DayEditVacationControls(")

        assertEquals(false, vacationBranch.contains("HoursPickerState("))
        assertEquals(false, vacationBranch.contains("onHoursClick"))
        assertEquals(false, vacationControls.contains("onHoursClick"))
        assertEquals(false, vacationControls.contains("workHours: Int"))
        assertEquals(false, vacationControls.contains("Sati:"))
        assertEquals(true, vacationControls.contains("warningMessage?.let"))
    }

    @Test
    fun dayEditSaveButtonUsesVacationValidationAsEnabledState() {
        val source = dayEditDialogSource()
        val saveButton = functionSource(source, "fun DayEditSaveButton(")

        assertEquals(true, saveButton.contains("val isSaveEnabled = saveState.shift1 != Shift.VACATION || vacationValidation.isAllowed"))
        assertEquals(true, saveButton.contains("enabled = isSaveEnabled"))
        // GlowButton je M3 Button: onemogućeno stanje (container 12%, sadržaj 38%) rešava on sam.
        assertEquals(true, saveButton.contains("GlowButton("))
        assertEquals(true, saveButton.contains("if (!isSaveEnabled) {"))
        assertEquals(true, saveButton.contains("return@GlowButton"))
    }

    @Test
    fun dayEditPopupContainsRegularShortageSelector() {
        val source = dayEditDialogSource()

        assertEquals(true, source.contains("label = \"Minuti\""))
        assertEquals(true, source.contains("regularShortageMinutes"))
        assertEquals(true, source.contains("enabled = state.shortageMinutesAllowed"))
        assertEquals(true, source.contains("effectiveRegularShortageMinutes"))
        assertEquals(false, source.contains("Prekovremeni minuti"))
    }

    @Test
    fun popupDialogSupportsOptionalFooterOutsideScrollableContent() {
        val source = popupDialogSource()
        val footerParamIndex = source.indexOf("footer: (@Composable ColumnScope.() -> Unit)? = null")
        val scrollIndex = source.indexOf(".verticalScroll(scrollState)")
        val ifStart = source.indexOf("if (scrollable)")
        val ifBodyStart = source.indexOf('{', ifStart)
        val ifEnd = matchingBraceIndex(source, ifBodyStart)
        val elseStart = source.indexOf("else", ifEnd)
        val elseBodyStart = source.indexOf('{', elseStart)
        val elseEnd = matchingBraceIndex(source, elseBodyStart)
        val footerRenderIndex = source.indexOf("footer?.let")
        val footerRenderBlock = if (footerRenderIndex >= 0) source.substring(footerRenderIndex) else ""

        assertEquals(true, footerParamIndex >= 0)
        assertEquals(true, scrollIndex >= 0)
        assertEquals(true, ifStart >= 0)
        assertEquals(true, ifBodyStart > ifStart)
        assertEquals(true, ifEnd > scrollIndex)
        assertEquals(true, elseStart > ifEnd)
        assertEquals(true, elseBodyStart > elseStart)
        assertEquals(true, elseEnd > elseBodyStart)
        assertEquals(false, source.substring(ifBodyStart, ifEnd + 1).contains("footer?.let"))
        assertEquals(false, source.substring(elseBodyStart, elseEnd + 1).contains("footer?.let"))
        assertEquals(true, footerRenderIndex > elseEnd)
        assertEquals(true, footerRenderBlock.contains("footerContent"))
        assertEquals(true, footerRenderBlock.contains("footerContent()"))
    }

    @Test
    fun dayEditUsesCompactProgressiveWorkflowSections() {
        val source = dayEditDialogBodySource()
        val typeIndex = source.indexOf("DayEditDayTypeSelector(")
        val workIndex = source.indexOf("DayEditWorkControls(")
        val sickIndex = source.indexOf("DayEditSickControls(")
        val vacationIndex = source.indexOf("DayEditVacationControls(")
        val offIndex = source.indexOf("DayEditOffControls(")
        val compensationIndex = source.indexOf("DayEditCompensationBlock(")

        assertEquals(true, source.contains("DayEditStatusHeader("))
        assertEquals(true, source.contains("DayEditHolidayWarningStrip("))
        assertEquals(true, typeIndex >= 0)
        assertEquals(true, workIndex > typeIndex)
        assertEquals(true, sickIndex > typeIndex)
        assertEquals(true, vacationIndex > typeIndex)
        assertEquals(true, offIndex > typeIndex)
        assertEquals(true, compensationIndex > typeIndex)
        assertEquals(true, source.contains("DayEditAdditionalWorkControls("))
        assertEquals(false, source.contains("Nema dodatne smene."))
    }

    @Test
    fun dayEditBlocksVacationWhenRequestValidationFails() {
        val source = dayEditDialogSource()

        assertEquals(true, source.contains("repo.validateVacationRequest(listOf(dayDate))"))
        assertEquals(true, source.contains("vacationWarning = vacationValidation.message"))
        assertEquals(true, source.contains("warningMessage = vacationWarning"))
        assertEquals(true, source.contains("saveState.shift1 == Shift.VACATION && !vacationValidation.isAllowed"))
    }

    @Test
    fun bloodDonationLockStaysBeforeCompactWorkflow() {
        val source = dayEditDialogBodySource()
        val fullSource = dayEditDialogSource()
        val lockIndex = source.indexOf("isDayEditLockedByBloodDonation(record)")
        val lockedDialogIndex = source.indexOf("BloodDonationLockedDayDialog(")
        val lockReturnIndex = source.indexOf("return", lockedDialogIndex)
        val formIndex = source.indexOf("rememberDayEditForm(")
        val statusIndex = source.indexOf("DayEditStatusHeader(")
        val normalPopupIndex = source.indexOf("PopupDialog(")

        assertEquals(true, lockIndex >= 0)
        assertEquals(true, lockedDialogIndex > lockIndex)
        assertEquals(true, lockReturnIndex > lockedDialogIndex)
        assertEquals(true, formIndex > lockReturnIndex)
        assertEquals(true, statusIndex > lockedDialogIndex)
        assertEquals(true, lockedDialogIndex < normalPopupIndex)
        assertEquals(true, fullSource.contains("Poništi dane"))
    }

    @Test
    fun dayEditDialogUsesVisualSectionHelpersAndKeepsBloodDonationLockFirst() {
        val source = dayEditDialogSource()

        assertEquals(true, source.contains("fun DayEditSectionCard("))
        assertEquals(true, source.contains("internal fun DayEditChoiceHeader("))
        assertEquals(true, source.contains("DayEditSectionCard("))
        assertEquals(true, source.contains("DayEditChoiceHeader("))
        assertEquals(
            true,
            source.indexOf("BloodDonationLockedDayDialog(") < source.indexOf("PopupDialog(")
        )
    }

    @Test
    fun dayEditPreviewRecordPreservesBalanceExemptOff() {
        val preview = DayEditSaveState(
            date = DayKey(2026, 6, 4),
            shift1 = Shift.OFF,
            workHours1 = 0,
            shift2 = Shift.SECOND,
            workHours2 = 0,
            hasDouble = false,
            firstShiftAllowsDouble = false,
            overtimeType = OvertimeType.PAID,
            weekendOvertimeType = OvertimeType.PAID,
            compHoursUsed = 0,
            holidayWorkOverride = null,
            holidayOverride = null,
            balanceExempt = true,
            sickPayRate = 0.65
        ).toDayRecord()

        assertEquals(true, preview.balanceExempt)
        assertEquals(0, preview.hoursDeficitFor(BrigadeType.CETVOROBRIGADA))
    }

    @Test
    fun quickActionPopupsUseSharedSafeContainer() {
        val autoFillSource = appSource("AutoFillDialog.kt")
        val absenceSource = appSource("AbsenceDialog.kt")

        assertEquals(true, autoFillSource.contains("PopupDialog("))
        assertEquals(true, absenceSource.contains("PopupDialog("))
        assertEquals(false, autoFillSource.contains("heightRatio ="))
        assertEquals(false, absenceSource.contains("heightRatio ="))
    }

    @Test
    fun absenceDialogChoiceButtonsMatchDayEditDialog() {
        val source = appSource("AbsenceDialog.kt")

        // Bolovanje/Godišnji i procenat izgledaju kao izbori u prozoru dana.
        assertEquals(2, Regex("""minHeight = DAY_TYPE_CHOICE_HEIGHT""").findAll(source).count())
        assertEquals(1, Regex("""minHeight = OVERTIME_TYPE_CHOICE_HEIGHT""").findAll(source).count())
        assertEquals(true, source.contains("text = \"Bolovanje\""))
        assertEquals(false, source.contains("BOLOVANJE"))
        assertEquals(false, source.contains("extendedColors.sick"))
    }

    @Test
    fun absenceNetImpactTextShowsSignedCurrency() {
        assertEquals("+1.120 RSD", formatAbsenceNetDifference(1_120.0))
        assertEquals("-560 RSD", formatAbsenceNetDifference(-560.0))
        assertEquals("0 RSD", formatAbsenceNetDifference(0.0))
    }

    @Test
    fun dayEditNetDifferenceTextShowsOnlyChangedSavedValue() {
        assertNull(formatDayEditNetDifference(null, 3_805.0, "RSD"))
        assertNull(formatDayEditNetDifference(3_805.0, 3_805.0, "RSD"))
        assertNull(formatDayEditNetDifference(3_805.4, 3_805.0, "RSD"))
        assertEquals("Razlika: +1.450 RSD", formatDayEditNetDifference(3_805.0, 5_255.0, "RSD"))
        assertEquals("Razlika: -1.450 RSD", formatDayEditNetDifference(3_805.0, 2_355.0, "RSD"))
    }

    @Test
    fun totalCardKeepsPhoneWidthLayoutConsistent() {
        assertEquals(false, useCompactTotalCardLayout(cardWidthDp = 360f))
        assertEquals(false, useCompactTotalCardLayout(cardWidthDp = 330f))
        assertEquals(true, useCompactTotalCardLayout(cardWidthDp = 299f))
    }

    @Test
    fun totalCardUsesMonthlyReceiptLabel() {
        assertEquals("Primanja u Septembru", totalCardLabel(2026, 9))
        assertEquals("Primanja u Martu", totalCardLabel(2026, 3))
    }

    @Test
    fun salaryCardUsesSelectedMonthName() {
        assertEquals("Julska plata", salaryCardLabel(7))
        assertEquals("Septembarska plata", salaryCardLabel(9))
        assertEquals("Martovska plata", salaryCardLabel(3))
    }

    @Test
    fun twoPayCardsKeepTheOldTotalCardFootprint() {
        assertEquals(UI.totalCardHeight, COMPACT_PAY_CARD_HEIGHT * 2 + PAY_CARDS_SPACING)
    }

    @Test
    fun totalCardDoesNotRenderInformativeReceiptSubtitle() {
        val source = shiftDashboardCardsSource()

        assertEquals(false, source.contains("monthlyReceiptSubtitleText(receipt)"))
    }

    @Test
    fun overtimeStatCardUsesClickHandlerLikeFoodCard() {
        val source = shiftDashboardCardsSource()
        val statsSection = functionSource(source, "internal fun StatsSection(")

        assertEquals(true, statsSection.contains("onOvertimeClick: () -> Unit"))
        assertEquals(true, statsSection.contains("\"Prekovremeno\""))
        assertEquals(true, statsSection.contains("onClick = onOvertimeClick"))
    }

    @Test
    fun overtimeNetImpactDialogHasRequiredResultCopy() {
        val source = appSource("OvertimeNetImpactDialog.kt")

        assertEquals(true, source.contains("Dodatna neto zarada"))
        assertEquals(true, source.contains("Vraćeno kroz Za sate"))
        assertEquals(true, source.contains("Gubitak zbog manjka sati"))
        assertEquals(true, source.contains("Konačna razlika"))
        assertEquals(true, source.contains("Razlika u odnosu na isti mesec bez tih sati."))
        assertEquals(true, source.contains("Nema prekovremenih sati u ovom mesecu."))
    }

    @Test
    fun initialCompBalanceDialogTextMatchesHourBalanceCard() {
        assertEquals("Početni bilans sati", INITIAL_COMP_BALANCE_DIALOG_TITLE)
        assertEquals(
            "Unesi broj sati koji već imaš u plusu pre početka korišćenja aplikacije.",
            INITIAL_COMP_BALANCE_DIALOG_DESCRIPTION
        )
        assertEquals(
            "Minute upiši kao decimalu: 15min = .25, 30min = .50, 45min = .75.",
            INITIAL_COMP_BALANCE_MINUTES_NOTE
        )
        assertEquals("Sačuvaj", INITIAL_COMP_BALANCE_SAVE_LABEL)
        assertEquals("Otkaži", INITIAL_COMP_BALANCE_CANCEL_LABEL)
        assertEquals("Resetuj na 0", INITIAL_COMP_BALANCE_RESET_LABEL)
    }

    @Test
    fun initialCompBalanceInputParsesQuarterHourDecimals() {
        assertEquals(300, parseInitialCompBalanceInputMinutes("5"))
        assertEquals(315, parseInitialCompBalanceInputMinutes("5.25"))
        assertEquals(330, parseInitialCompBalanceInputMinutes("5.5"))
        assertEquals(330, parseInitialCompBalanceInputMinutes("5.50"))
        assertEquals(345, parseInitialCompBalanceInputMinutes("5.75"))
        assertEquals(15, parseInitialCompBalanceInputMinutes(".25"))
        assertNull(parseInitialCompBalanceInputMinutes("5.15"))
    }

    @Test
    fun initialCompBalanceInputFormatsStoredMinutes() {
        assertEquals("5", formatInitialCompBalanceInput(300))
        assertEquals("5.25", formatInitialCompBalanceInput(315))
        assertEquals("5.5", formatInitialCompBalanceInput(330))
        assertEquals("5.75", formatInitialCompBalanceInput(345))
    }

    @Test
    fun compTimeCardDisplaysMinuteBalanceWithoutRounding() {
        val source = appSource("UiComponents.kt")

        assertEquals(true, source.contains("summary.compTimeEndingMinutes"))
        assertEquals(true, source.contains("formatHoursAndMinutes(balanceMinutes"))
        assertEquals(false, source.contains("formatHours(balance, showPlusForPositive = true)"))
    }

    @Test
    fun calendarCompIndicatorUsesMinuteDeficitWithoutTruncatingPartialHours() {
        val source = appSource("ShiftCalendar.kt")

        assertEquals(true, source.contains("record.hoursDeficitMinutesFor(brigadeType) > 0"))
        assertEquals(false, source.contains("record.hoursDeficitFor(brigadeType) > 0"))
    }
}

/** Dijalog za dan je podeljen na sam dijalog, kontrole i stanje forme; provere gledaju sva tri fajla. */
private fun dayEditDialogSource(): String {
    return listOf("DayEditDialog.kt", "DayEditControls.kt", "DayEditForm.kt").joinToString("\n") { appSource(it) }
}

/** Tok dijaloga: sam dijalog i kontrole izabranog tipa dana (DayEditShiftControls). */
private fun dayEditDialogBodySource(): String {
    val source = dayEditDialogSource()
    return functionSource(source, "fun DayEditDialog(") + "\n" + functionSource(source, "fun DayEditShiftControls(")
}

private fun popupDialogSource(): String {
    return functionSource(appSource("PopupComponents.kt"), "fun PopupDialog(")
}

private fun functionSource(source: String, signature: String): String {
    val functionStart = source.indexOf(signature)
    assertEquals(true, functionStart >= 0)
    val bodyStart = source.indexOf('{', functionStart)
    assertEquals(true, bodyStart > functionStart)
    val bodyEnd = matchingBraceIndex(source, bodyStart)
    return source.substring(functionStart, bodyEnd + 1)
}

private fun matchingBraceIndex(source: String, openBraceIndex: Int): Int {
    var depth = 0
    for (index in openBraceIndex until source.length) {
        when (source[index]) {
            '{' -> depth++
            '}' -> {
                depth--
                if (depth == 0) return index
            }
        }
    }
    error("Missing matching brace")
}

private fun shiftDashboardCardsSource(): String {
    return appSource("ShiftDashboardCards.kt")
}

private fun appSource(fileName: String): String {
    return listOf(
        File("src/main/java/com/rad/shifttracker/$fileName"),
        File("app/src/main/java/com/rad/shifttracker/$fileName")
    ).first { it.isFile }.readText()
}

private fun String.constantValue(name: String): Int {
    val match = Regex("""(?:private|internal) const val $name = (\d+)""").find(this)
        ?: error("Missing constant $name")
    return match.groupValues[1].toInt()
}
