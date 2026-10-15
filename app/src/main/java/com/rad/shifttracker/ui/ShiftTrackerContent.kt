package com.rad.shifttracker.ui

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfo
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.window.core.layout.WindowSizeClass
import java.time.LocalDate
import kotlinx.coroutines.launch
import com.rad.shifttracker.osnova.UI
import com.rad.shifttracker.repo.ShiftRepository
import com.rad.shifttracker.repo.brigadeTypeValue
import com.rad.shifttracker.smene.DayRecord
import com.rad.shifttracker.smene.MonthKey
import com.rad.shifttracker.ui.dialozi.PayDetailsSections
import com.rad.shifttracker.ui.dialozi.isPayslipMonthUnfinished

/**
 * Sadržaj glavnog ekrana: kalendar sa zaglavljem i karticama salda i godišnjeg, statistika i plata,
 * pa donja traka. Meseci su stranice koje se prevlače levo-desno: ekran prati prst, a pri puštanju se
 * vrati ako je prevučen malo, ili pređe na susedni mesec ako je prevučen dovoljno (ili brzo). Svi
 * meseci dele visinu skrolovanja, pa novi mesec ostaje na istom mestu. Na širokom ekranu kalendar i
 * iznosi idu u dve kolone. Dijalozi se otvaraju preko [onOpenDialog].
 */
@Composable
internal fun ShiftTrackerContent(
    states: Map<MonthKey, MainMonthState>,
    month: MonthKey,
    today: LocalDate,
    repo: ShiftRepository,
    refreshKey: Int,
    onMonthSelected: (MonthKey) -> Unit,
    onOpenDialog: (DialogState) -> Unit
) {
    val snackbarHostState = LocalAppSnackbar.current
    val pagerState = rememberPagerState(initialPage = month.pageIndex()) { MONTH_PAGE_COUNT }
    val scope = rememberCoroutineScope()
    // Strelice u zaglavlju i izbor meseca spolja: stranica klizi na taj mesec.
    LaunchedEffect(month) {
        if (pagerState.currentPage != month.pageIndex()) pagerState.animateScrollToPage(month.pageIndex())
    }
    // Prevučen mesec postaje izabran tek kad se stranica smiri (dijalozi i podaci prate njega).
    LaunchedEffect(pagerState) {
        snapshotFlow { pagerState.settledPage }.collect { page -> onMonthSelected(monthOfPage(page)) }
    }
    // Zajednička visina skrolovanja: trenutni mesec je upisuje, susedni je prate.
    var sharedScroll by rememberSaveable { mutableIntStateOf(0) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            // Pozadina ide i ispod statusne i navigacione trake (inseti), u boji izabrane teme.
            .background(MaterialTheme.colorScheme.background)
            .padding(WindowInsets.safeDrawing.asPaddingValues())
    ) {
        val wide = currentWindowAdaptiveInfo().windowSizeClass
            .isWidthAtLeastBreakpoint(WindowSizeClass.WIDTH_DP_EXPANDED_LOWER_BOUND)
        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            BackgroundLayer()

            HorizontalPager(
                state = pagerState,
                modifier = Modifier.fillMaxSize(),
                beyondViewportPageCount = 1,
                key = { it }
            ) { page ->
                val pageMonth = monthOfPage(page)
                val pageUi = states[pageMonth]
                val scroll = rememberSaveable(saver = ScrollState.Saver) { ScrollState(sharedScroll) }
                if (page == pagerState.currentPage) {
                    // Upisuje se samo kad korisnik skroluje gore-dole: kraći mesec stoji na svom dnu,
                    // pa bi njegova manja visina na pola prevlačenja povukla mesec koji izlazi naviše
                    // (poskok), a povratak u duži mesec ne bi bio na istom mestu.
                    LaunchedEffect(scroll) {
                        snapshotFlow { scroll.value }
                            .collect { value -> if (scroll.isScrollInProgress) sharedScroll = value }
                    }
                } else {
                    LaunchedEffect(sharedScroll) { scroll.scrollTo(sharedScroll) }
                }
                if (pageUi == null) {
                    // Susedni mesec se još računa (pozadinska nit): samo pozadina, bez praznog kalendara.
                    Box(Modifier.fillMaxSize())
                } else {
                    MonthPage(
                        ui = pageUi,
                        month = pageMonth,
                        today = today,
                        repo = repo,
                        refreshKey = refreshKey,
                        wide = wide,
                        scroll = scroll,
                        onPrevMonth = { scope.launch { pagerState.animateScrollToPage(page - 1) } },
                        onNextMonth = { scope.launch { pagerState.animateScrollToPage(page + 1) } },
                        onOpenDialog = onOpenDialog
                    )
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
            onAi = { onOpenDialog(DialogState.Ai) },
            onBasePay = { onOpenDialog(DialogState.BasePay) },
            onHolidays = { onOpenDialog(DialogState.BloodDonation) },
            onOptions = { onOpenDialog(DialogState.Options) },
            onDeleteMonth = { onOpenDialog(DialogState.DeleteMonth) },
            basePayEnabled = !isPayslipMonthUnfinished(month, today)
        )
    }
}

/** Jedan mesec kao stranica: kalendar sa zaglavljem, pa statistika i plata; cela strana skroluje. */
@Composable
private fun MonthPage(
    ui: MainMonthState,
    month: MonthKey,
    today: LocalDate,
    repo: ShiftRepository,
    refreshKey: Int,
    wide: Boolean,
    scroll: ScrollState,
    onPrevMonth: () -> Unit,
    onNextMonth: () -> Unit,
    onOpenDialog: (DialogState) -> Unit
) {
    var selectedDay by rememberSaveable(month) { mutableStateOf<Int?>(null) }
    val selectedDayRecord = remember(selectedDay, month, refreshKey) {
        selectedDay?.let { repo.get(month.year, month.month, it) }
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
                year = month.year,
                month = month.month,
                day = day,
                today = today,
                record = selectedDayRecord,
                brigadeType = selectedDayBrigade,
                onEdit = { onOpenDialog(DialogState.DayEdit(day, selectedDayRecord)) }
            )
        }
        ShiftPayPane(ui = ui, repo = repo, onOpenDialog = onOpenDialog)
    }
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        Column(
            modifier = Modifier
                .fillMaxHeight()
                .widthIn(max = (if (wide) UI.wideContentMaxWidth else UI.contentMaxWidth).dp)
                .fillMaxWidth()
                .verticalScroll(scroll)
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
    }
}

/** Zaglavlje meseca, kartice salda sati i godišnjeg, i kalendar meseca. */
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
    CalendarSection(
        year = currentYear,
        month = currentMonth,
        today = today,
        repo = repo,
        refreshKey = refreshKey,
        onDayClick = onDayClick
    )
}

/** Statistika meseca (sati, prekovremeno, topli obrok, prevoz ako ga ima), isplata i obračun plate. */
@Composable
private fun ShiftPayPane(ui: MainMonthState, repo: ShiftRepository, onOpenDialog: (DialogState) -> Unit) {
    StatsSection(
        summary = ui.summary,
        onOvertimeClick = { onOpenDialog(DialogState.OvertimeNetImpact) },
        onFoodClick = { onOpenDialog(DialogState.Food) },
        // Prevoz za rad u prikazanom mesecu isplaćuje se sledećeg meseca; bez dnevnog iznosa stavke nema.
        transportAmount = ui.nextMonthReceipt.takeIf { it.transportDailyAmount > 0.0 }?.transportAmount,
        onTransportClick = { onOpenDialog(DialogState.Transport) },
        onWorkHoursClick = { onOpenDialog(DialogState.WorkHours) }
    )
    PayDetailsSections(
        monthlyReceipt = ui.nextMonthReceipt,
        monthlyFinalPaymentBreakdown = ui.nextMonthFinalPaymentBreakdown,
        payDetails = ui.selectedMonthPayDetails,
        repo = repo,
        extras = ui.nextMonthPayExtras
    )
}

private const val MONTHS_PER_YEAR = 12

/** Stranice meseci: od januara 1900. do decembra 2199. (indeks = godina × 12 + mesec − 1, od 1900.). */
private const val FIRST_PAGE_YEAR = 1900
private const val MONTH_PAGE_COUNT = 300 * MONTHS_PER_YEAR

private fun MonthKey.pageIndex(): Int = (year - FIRST_PAGE_YEAR) * MONTHS_PER_YEAR + (month - 1)

private fun monthOfPage(page: Int): MonthKey =
    MonthKey(FIRST_PAGE_YEAR + page / MONTHS_PER_YEAR, page % MONTHS_PER_YEAR + 1)
