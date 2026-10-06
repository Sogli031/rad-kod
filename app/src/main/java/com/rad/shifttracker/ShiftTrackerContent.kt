package com.rad.shifttracker

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfo
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.window.core.layout.WindowSizeClass
import java.time.LocalDate

/**
 * Sadržaj glavnog ekrana: kalendar sa zaglavljem i karticama salda i godišnjeg, statistika i plata,
 * pa donja traka. Na širokom ekranu kalendar i iznosi idu u dve kolone. Dijalozi se otvaraju preko
 * [onOpenDialog], a sam ekran ([ShiftTrackerScreen]) drži koji je dijalog otvoren.
 */
@Composable
internal fun ShiftTrackerContent(
    ui: MainMonthState,
    month: MonthKey,
    today: LocalDate,
    repo: ShiftRepository,
    refreshKey: Int,
    onPrevMonth: () -> Unit,
    onNextMonth: () -> Unit,
    onOpenDialog: (DialogState) -> Unit
) {
    val currentYear = month.year
    val currentMonth = month.month
    val snackbarHostState = LocalAppSnackbar.current

    Column(
        modifier = Modifier
            .fillMaxSize()
            // Pozadina ide i ispod statusne i navigacione trake (inseti), u boji izabrane teme.
            .background(MaterialTheme.colorScheme.background)
            .padding(WindowInsets.safeDrawing.asPaddingValues())
    ) {
        // Cela strana skroluje kad ne staje (veći font, mesec od šest nedelja); donja traka ostaje zakucana.
        val wide = currentWindowAdaptiveInfo().windowSizeClass
            .isWidthAtLeastBreakpoint(WindowSizeClass.WIDTH_DP_EXPANDED_LOWER_BOUND)
        var selectedDay by rememberSaveable(currentYear, currentMonth) { mutableStateOf<Int?>(null) }
        val selectedDayRecord = remember(selectedDay, currentYear, currentMonth, refreshKey) {
            selectedDay?.let { repo.get(currentYear, currentMonth, it) }
        }
        val selectedDayBrigade = remember(refreshKey) { repo.brigadeTypeValue }
        // Kalendar sa zaglavljem i statistika sa platom: jedna kolona na telefonu, dve kolone na širokom ekranu.
        val calendarPane: @Composable ColumnScope.() -> Unit = {
            ShiftCalendarPane(
                ui = ui,
                month = month,
                today = today,
                repo = repo,
                refreshKey = refreshKey,
                onPrevMonth = onPrevMonth,
                onNextMonth = onNextMonth,
                onDayClick = { day, record ->
                    // Na širokom ekranu dodir bira dan (panel desno), izmena ide dugmetom „Izmeni“.
                    if (wide) selectedDay = day else onOpenDialog(DialogState.DayEdit(day, record))
                },
                onOpenDialog = onOpenDialog
            )
        }
        val summaryPane: @Composable ColumnScope.() -> Unit = {
            selectedDay?.takeIf { wide }?.let { day ->
                SelectedDayCard(
                    year = currentYear,
                    month = currentMonth,
                    day = day,
                    today = today,
                    record = selectedDayRecord,
                    brigadeType = selectedDayBrigade,
                    onEdit = { onOpenDialog(DialogState.DayEdit(day, selectedDayRecord)) }
                )
            }
            ShiftPayPane(ui = ui, month = month, onOpenDialog = onOpenDialog)
        }
        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            BackgroundLayer()

            Column(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .fillMaxHeight()
                    .widthIn(max = (if (wide) UI.wideContentMaxWidth else UI.contentMaxWidth).dp)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(
                        start = Spacing.lg,
                        top = Spacing.lg,
                        end = Spacing.lg,
                        bottom = Spacing.sm
                    ),
                verticalArrangement = Arrangement.spacedBy(Spacing.sm)
            ) {
                if (wide) {
                    Row(horizontalArrangement = Arrangement.spacedBy(Spacing.lg)) {
                        Column(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(Spacing.sm),
                            content = calendarPane
                        )
                        Column(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(Spacing.sm),
                            content = summaryPane
                        )
                    }
                } else {
                    calendarPane()
                    summaryPane()
                }
            }

            snackbarHostState?.let { host ->
                SnackbarHost(
                    hostState = host,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(start = Spacing.lg, end = Spacing.lg)
                )
            }
        }

        BottomBar(
            modifier = Modifier,
            onAutoFill = { onOpenDialog(DialogState.AutoFill) },
            onSickVacation = { onOpenDialog(DialogState.SickVacation) },
            onBasePay = { onOpenDialog(DialogState.BasePay) },
            onHolidays = { onOpenDialog(DialogState.BloodDonation) },
            onOptions = { onOpenDialog(DialogState.Options) },
            onDeleteMonth = { onOpenDialog(DialogState.DeleteMonth) },
            basePayEnabled = !isPayslipMonthUnfinished(month, today)
        )
    }
}

/** Zaglavlje meseca, kartice salda sati i godišnjeg, i kalendar sa prelazom između meseci. */
@Composable
private fun ShiftCalendarPane(
    ui: MainMonthState,
    month: MonthKey,
    today: LocalDate,
    repo: ShiftRepository,
    refreshKey: Int,
    onPrevMonth: () -> Unit,
    onNextMonth: () -> Unit,
    onDayClick: (Int, DayRecord?) -> Unit,
    onOpenDialog: (DialogState) -> Unit
) {
    val currentYear = month.year
    val currentMonth = month.month
    val reducedMotion = rememberReducedMotion()
    HeaderSection(
        month = currentMonth,
        year = currentYear,
        onPrev = onPrevMonth,
        onNext = onNextMonth
    )

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(Spacing.sm)
    ) {
        CompTimeCard(
            summary = ui.summary,
            modifier = Modifier.weight(1f),
            onClick = { onOpenDialog(DialogState.InitialCompBalance) }
        )
        VacationCard(
            summary = ui.vacationSummary,
            modifier = Modifier.weight(1f),
            onClick = { onOpenDialog(DialogState.VacationSettings) }
        )
    }
    WeekdayHeader()
    // Prelaz meseca: M3 „shared axis“ (klizanje po x + bledenje); stari mesec izlazi sa svojim podacima.
    AnimatedContent(
        targetState = currentYear * MONTHS_PER_YEAR + (currentMonth - 1),
        transitionSpec = {
            if (reducedMotion) {
                EnterTransition.None togetherWith ExitTransition.None
            } else {
                monthTransition(forward = targetState > initialState)
            }
        },
        label = "month"
    ) { monthIndex ->
        CalendarSection(
            year = monthIndex / MONTHS_PER_YEAR,
            month = monthIndex % MONTHS_PER_YEAR + 1,
            today = today,
            repo = repo,
            refreshKey = refreshKey,
            onDayClick = onDayClick
        )
    }
}

/** Statistika meseca (sati, prekovremeno, topli obrok) i kartice sa platom. */
@Composable
private fun ShiftPayPane(ui: MainMonthState, month: MonthKey, onOpenDialog: (DialogState) -> Unit) {
    val mainPaySalaryOnly = ui.mainPaySalaryOnly
    StatsSection(
        summary = ui.summary,
        onWorkHoursClick = { onOpenDialog(DialogState.WorkHours) },
        onOvertimeClick = { onOpenDialog(DialogState.OvertimeNetImpact) },
        onFoodClick = { onOpenDialog(DialogState.Food) }
    )
    Column(verticalArrangement = Arrangement.spacedBy(PAY_CARDS_SPACING.dp)) {
        SalaryCard(
            month = month.month,
            nextMonthReceipt = ui.nextMonthReceipt,
            salaryOnly = mainPaySalaryOnly,
            onClick = { onOpenDialog(DialogState.SalaryDetails) }
        )
        if (SHOW_TOTAL_CARD) {
            TotalCard(
                receipt = ui.monthlyReceipt,
                salaryOnly = mainPaySalaryOnly,
                onClick = { onOpenDialog(DialogState.PayDetails) }
            )
        }
    }
}

private const val MONTHS_PER_YEAR = 12
/** M3 shared axis X (spring iz [RadMotion]): novi mesec ulazi sa strane smera, stari izlazi na suprotnu stranu. */
private fun monthTransition(forward: Boolean): ContentTransform {
    val direction = if (forward) 1 else -1
    val enter = slideInHorizontally(RadMotion.spatial()) {
        direction * it / MONTH_SLIDE_DIVISOR
    } + fadeIn(RadMotion.effects())
    val exit = slideOutHorizontally(RadMotion.fastSpatial()) {
        -direction * it / MONTH_SLIDE_DIVISOR
    } + fadeOut(RadMotion.fastEffects())
    return enter togetherWith exit
}

private const val MONTH_SLIDE_DIVISOR = 8
