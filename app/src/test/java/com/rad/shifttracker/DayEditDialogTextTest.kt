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

        assertEquals(50, source.constantValue("DAY_EDIT_SHIFT_CHOICE_HEIGHT"))
        assertEquals(0, Regex("""\.height\(DAY_EDIT_SHIFT_CHOICE_HEIGHT\.dp\)""").findAll(source).count())
        assertEquals(4, Regex("""\.heightIn\(min = DAY_EDIT_SHIFT_CHOICE_HEIGHT\.dp\)""").findAll(source).count())
    }

    @Test
    fun overtimeTypeSelectorsUseAdaptiveMinimumHeight() {
        val source = dayEditDialogSource()

        assertEquals(50, source.constantValue("OVERTIME_TYPE_CHOICE_HEIGHT"))
        assertEquals(0, Regex("""\.height\(OVERTIME_TYPE_CHOICE_HEIGHT\.dp\)""").findAll(source).count())
        assertEquals(10, Regex("""\.heightIn\(min = OVERTIME_TYPE_CHOICE_HEIGHT\.dp\)""").findAll(source).count())
    }

    @Test
    fun dayEditTextControlsUseMinimumHeightsInsteadOfExactHeights() {
        val source = dayEditDialogSource()

        assertEquals(0, Regex("""\.height\(DAY_TYPE_CHOICE_HEIGHT\.dp\)""").findAll(source).count())
        assertEquals(6, Regex("""\.heightIn\(min = DAY_TYPE_CHOICE_HEIGHT\.dp\)""").findAll(source).count())
        assertEquals(false, source.contains(".height(UI.buttonHeight.dp)"))
        assertEquals(false, source.contains(".height(UI.saveButtonHeight.dp)"))
        assertEquals(true, source.contains(".heightIn(min = UI.buttonHeight.dp)"))
        assertEquals(true, source.contains(".heightIn(min = UI.saveButtonHeight.dp)"))
        assertEquals(false, source.contains(".height(44.dp)"))
        assertEquals(true, source.contains(".heightIn(min = 44.dp)"))
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
        val defaultBackground = THEME.empty
        val defaultTextColor = THEME.textPrimary

        assertEquals(THEME.warning, calendarDayBackgroundColor(isHolidayDay = true, workedHoliday = false, defaultBackground))
        assertEquals(THEME.warning, calendarDayBackgroundColor(isHolidayDay = false, workedHoliday = true, defaultBackground))
        assertEquals(defaultBackground, calendarDayBackgroundColor(isHolidayDay = false, workedHoliday = false, defaultBackground))
        assertEquals(THEME.textDark, calendarDayTextColor(isHolidayDay = true, workedHoliday = false, defaultTextColor))
        assertEquals(THEME.textDark, calendarDayTextColor(isHolidayDay = false, workedHoliday = true, defaultTextColor))
        assertEquals(defaultTextColor, calendarDayTextColor(isHolidayDay = false, workedHoliday = false, defaultTextColor))
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

        assertEquals(true, source.contains("CalendarDayText("))
        assertEquals(false, source.contains("softWrap = false"))
        assertEquals(true, source.contains("detailFontSize * detailTextScale"))
        assertEquals(true, source.contains("MIN_CALENDAR_DETAIL_TEXT_SCALE"))
        assertEquals(true, source.contains("detailText == \"Praznik\""))
        assertEquals(true, source.contains("result.lineCount > expectedCalendarDayTextLines(detailText)"))
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
        assertEquals(84, UI.popupBottomSafePadding)
    }

    @Test
    fun defaultOvertimeShiftFollowsRegularShift() {
        assertEquals(Shift.SECOND, defaultOvertimeShiftAfter(Shift.FIRST))
        assertEquals(Shift.THIRD, defaultOvertimeShiftAfter(Shift.SECOND))
        assertEquals(Shift.FIRST, defaultOvertimeShiftAfter(Shift.THIRD))
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
            source.indexOf("Shift.SICK -> DayEditSickControls("),
            source.indexOf("Shift.VACATION -> DayEditVacationControls(")
        )
        val sickControls = functionSource(source, "private fun DayEditSickControls(")

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
        val vacationControls = functionSource(source, "private fun DayEditVacationControls(")

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
        val saveButton = functionSource(source, "private fun DayEditSaveButton(")

        assertEquals(true, saveButton.contains("val isSaveEnabled = saveState.shift1 != Shift.VACATION || vacationValidation.isAllowed"))
        assertEquals(true, saveButton.contains("enabled = isSaveEnabled"))
        assertEquals(true, saveButton.contains("bgColor = if (isSaveEnabled) THEME.success else THEME.empty"))
        assertEquals(true, saveButton.contains("if (!isSaveEnabled) {"))
        assertEquals(true, saveButton.contains("return@GlowButton"))
    }

    @Test
    fun dayEditPopupContainsRegularShortageSelector() {
        val source = dayEditDialogSource()

        assertEquals(true, source.contains("Minuti: "))
        assertEquals(true, source.contains("regularShortageMinutes"))
        assertEquals(true, source.contains("enabled = shortageMinutesAllowed"))
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
        assertEquals(true, source.contains("warningMessage = vacationValidation.message"))
        assertEquals(true, source.contains("saveState.shift1 == Shift.VACATION && !vacationValidation.isAllowed"))
    }

    @Test
    fun bloodDonationLockStaysBeforeCompactWorkflow() {
        val source = dayEditDialogBodySource()
        val fullSource = dayEditDialogSource()
        val lockIndex = source.indexOf("isDayEditLockedByBloodDonation(record)")
        val lockedDialogIndex = source.indexOf("BloodDonationLockedDayDialog(")
        val lockReturnIndex = source.indexOf("return", lockedDialogIndex)
        val autoHolidayIndex = source.indexOf("val autoHoliday")
        val statusIndex = source.indexOf("DayEditStatusHeader(")
        val normalPopupIndex = source.indexOf("PopupDialog(")

        assertEquals(true, lockIndex >= 0)
        assertEquals(true, lockedDialogIndex > lockIndex)
        assertEquals(true, lockReturnIndex > lockedDialogIndex)
        assertEquals(true, autoHolidayIndex > lockReturnIndex)
        assertEquals(true, statusIndex > lockedDialogIndex)
        assertEquals(true, lockedDialogIndex < normalPopupIndex)
        assertEquals(true, fullSource.contains("PONIŠTI DANE"))
    }

    @Test
    fun dayEditDialogUsesVisualSectionHelpersAndKeepsBloodDonationLockFirst() {
        val source = dayEditDialogSource()

        assertEquals(true, source.contains("private fun DayEditSectionCard("))
        assertEquals(true, source.contains("private fun DayEditChoiceHeader("))
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
    fun absenceDialogChoiceButtonsUseExpandedMinimumHeights() {
        val source = appSource("AbsenceDialog.kt")

        assertEquals(58, source.constantValue("ABSENCE_TYPE_CHOICE_HEIGHT"))
        assertEquals(58, source.constantValue("ABSENCE_RATE_CHOICE_HEIGHT"))
        assertEquals(0, Regex("""\.height\(ABSENCE_TYPE_CHOICE_HEIGHT\.dp\)""").findAll(source).count())
        assertEquals(0, Regex("""\.height\(ABSENCE_RATE_CHOICE_HEIGHT\.dp\)""").findAll(source).count())
        assertEquals(3, Regex("""\.heightIn\(min = ABSENCE_TYPE_CHOICE_HEIGHT\.dp\)""").findAll(source).count())
        assertEquals(2, Regex("""\.heightIn\(min = ABSENCE_RATE_CHOICE_HEIGHT\.dp\)""").findAll(source).count())
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
        assertEquals("Primanja u ovom mesecu", TOTAL_CARD_LABEL)
    }

    @Test
    fun salaryCardUsesSelectedMonthName() {
        assertEquals("Plata za Jul", salaryCardLabel(7))
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

private fun dayEditDialogSource(): String {
    return listOf(
        File("src/main/java/com/rad/shifttracker/DayEditDialog.kt"),
        File("app/src/main/java/com/rad/shifttracker/DayEditDialog.kt")
    ).first { it.isFile }.readText()
}

private fun dayEditDialogBodySource(): String {
    return functionSource(dayEditDialogSource(), "fun DayEditDialog(")
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
    val match = Regex("""private const val $name = (\d+)""").find(this)
        ?: error("Missing constant $name")
    return match.groupValues[1].toInt()
}
