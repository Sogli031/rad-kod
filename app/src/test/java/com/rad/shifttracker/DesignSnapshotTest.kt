package com.rad.shifttracker

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.captureScreenRoboImage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowEnvironment
import java.time.LocalDate
import java.time.YearMonth
import com.rad.shifttracker.repo.ShiftRepository
import com.rad.shifttracker.repo.getMonthlyReceipt
import com.rad.shifttracker.repo.getSummary
import com.rad.shifttracker.repo.getVacationBalanceSummary
import com.rad.shifttracker.repo.mainPaySalaryOnly
import com.rad.shifttracker.smene.Shift
import com.rad.shifttracker.ui.BackgroundLayer
import com.rad.shifttracker.ui.BottomBar
import com.rad.shifttracker.ui.CalendarSection
import com.rad.shifttracker.ui.CompTimeCard
import com.rad.shifttracker.ui.HeaderSection
import com.rad.shifttracker.ui.PAY_CARDS_SPACING
import com.rad.shifttracker.ui.RadTheme
import com.rad.shifttracker.ui.SHOW_TOTAL_CARD
import com.rad.shifttracker.ui.SalaryCard
import com.rad.shifttracker.ui.ShiftTrackerScreen
import com.rad.shifttracker.ui.Spacing
import com.rad.shifttracker.ui.StatsSection
import com.rad.shifttracker.ui.TotalCard
import com.rad.shifttracker.ui.VacationCard
import com.rad.shifttracker.ui.WeekdayHeader
import com.rad.shifttracker.ui.dialozi.DayEditDialog
import com.rad.shifttracker.ui.dialozi.OptionsDialog

/**
 * Referentni snimci ekrana bez telefona (Roborazzi + Robolectric native graphics).
 *
 * - `gradlew :app:recordRoborazziDebug` upiše snimke u `app/src/test/snapshots`.
 * - `gradlew :app:verifyRoborazziDebug` pada kad se ekran promeni; tako mehaničke izmene dizajna
 *   (THEME → colorScheme, Spacing) dokazuju da su pikselski iste.
 * - Obično `testDebugUnitTest` samo iscrta ekran (bez poređenja), pa i tada hvata pad pri crtanju.
 *
 * Glavni ekran je ovde složen od istih sekcija kao [ShiftTrackerScreen], ali bez Google prijave,
 * dozvola i ponoćnog tajmera; kad se raspored sekcija u [ShiftTrackerScreen] promeni, promeniti i
 * [MainScreenSnapshot]. Datum je fiksan (03.10.2026), pa snimci ne zavise od dana pokretanja.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class DesignSnapshotTest {

    @get:Rule
    val rule = createComposeRule()

    private val today: LocalDate = LocalDate.now(TEST_CLOCK)

    @Test
    fun `main screen 360dp font 1_0`() = mainScreen(widthDp = 360, heightDp = 780, fontScale = 1.0f)

    @Test
    fun `main screen 360dp font 1_5`() = mainScreen(widthDp = 360, heightDp = 780, fontScale = 1.5f)

    @Test
    fun `main screen 360dp font 2_0`() = mainScreen(widthDp = 360, heightDp = 780, fontScale = 2.0f)

    @Test
    fun `main screen 411dp font 1_0`() = mainScreen(widthDp = 411, heightDp = 891, fontScale = 1.0f)

    @Test
    fun `main screen 411dp font 1_5`() = mainScreen(widthDp = 411, heightDp = 891, fontScale = 1.5f)

    @Test
    fun `main screen 840dp font 1_0`() = mainScreen(widthDp = 840, heightDp = 1200, fontScale = 1.0f)

    @Test
    fun `options dialog 360dp font 1_0`() = dialog("options", fontScale = 1.0f) { repo ->
        OptionsDialog(
            repo = repo,
            onCloudClick = {},
            onMonthlyExportClick = {},
            onDebugExportClick = {},
            onHolidaysClick = {},
            onDismiss = {}
        )
    }

    @Test
    fun `options dialog 360dp font 1_5`() = dialog("options", fontScale = 1.5f) { repo ->
        OptionsDialog(
            repo = repo,
            onCloudClick = {},
            onMonthlyExportClick = {},
            onDebugExportClick = {},
            onHolidaysClick = {},
            onDismiss = {}
        )
    }

    @Test
    fun `day edit dialog 360dp font 1_0`() = dialog("day_edit", fontScale = 1.0f) { repo ->
        DayEditDialog(year = YEAR, month = MONTH, day = 13, record = repo.get(YEAR, MONTH, 13), repo = repo, onDismiss = {})
    }

    @Test
    fun `day edit dialog 360dp font 1_5`() = dialog("day_edit", fontScale = 1.5f) { repo ->
        DayEditDialog(year = YEAR, month = MONTH, day = 13, record = repo.get(YEAR, MONTH, 13), repo = repo, onDismiss = {})
    }

    private fun mainScreen(widthDp: Int, heightDp: Int, fontScale: Float) {
        setDevice(widthDp, heightDp, fontScale)
        val repo = sampleRepository()
        rule.setContent {
            RadTheme { MainScreenSnapshot(repo, today) }
        }
        rule.waitForIdle()
        captureScreenRoboImage(snapshotPath("main", widthDp, fontScale))
    }

    private fun dialog(name: String, fontScale: Float, content: @Composable (ShiftRepository) -> Unit) {
        setDevice(360, 780, fontScale)
        val repo = sampleRepository()
        rule.setContent {
            RadTheme { content(repo) }
        }
        // Ulazna animacija lista mora da se završi pre snimka.
        rule.mainClock.advanceTimeBy(DIALOG_SETTLE_MS)
        rule.waitForIdle()
        captureScreenRoboImage(snapshotPath(name, 360, fontScale))
    }

    /** Širina, visina i sistemska veličina fonta kao na telefonu (važi i za prozore dijaloga). */
    private fun setDevice(widthDp: Int, heightDp: Int, fontScale: Float) {
        RuntimeEnvironment.setQualifiers("w${widthDp}dp-h${heightDp}dp-xxhdpi")
        RuntimeEnvironment.setFontScale(fontScale)
        // Opcije pitaju za „Pristup svim fajlovima“; Robolectric bez spoljnog diska tu puca.
        ShadowEnvironment.addExternalDir("snapshot")
    }

    private fun snapshotPath(name: String, widthDp: Int, fontScale: Float): String =
        "src/test/snapshots/${name}_w${widthDp}_f${(fontScale * 100).toInt()}.png"

    private fun sampleRepository(): ShiftRepository = sampleMonthRepository()

    private companion object {
        const val YEAR = 2026
        const val MONTH = 10
        const val DIALOG_SETTLE_MS = 1_000L
    }
}

/** Mesec sa svim vrstama dana: tri smene, slobodni, godišnji, bolovanje i prekovremeno (oktobar 2026). */
internal fun sampleMonthRepository(year: Int = 2026, month: Int = 10): ShiftRepository = testRepository().apply {
    val pattern = listOf(Shift.FIRST, Shift.FIRST, Shift.SECOND, Shift.SECOND, Shift.THIRD, Shift.THIRD, Shift.OFF, Shift.OFF)
    for (day in 1..YearMonth.of(year, month).lengthOfMonth()) {
        val shift = when (day) {
            in 20..22 -> Shift.VACATION
            27 -> Shift.SICK
            else -> pattern[(day - 1) % pattern.size]
        }
        val hours = if (shift == Shift.OFF) 0 else 8
        if (day == 9) {
            set(year, month, day, shift, hours, shift2 = Shift.SECOND, hours2 = 4)
        } else {
            set(year, month, day, shift, hours)
        }
    }
}

/** Isti raspored sekcija kao [ShiftTrackerScreen], bez sporednih efekata. */
@Composable
internal fun MainScreenSnapshot(repo: ShiftRepository, today: LocalDate) {
    val year = today.year
    val month = today.monthValue
    val summary = repo.getSummary(year, month)
    val monthlyReceipt = repo.getMonthlyReceipt(year, month)
    val next = YearMonth.of(year, month).plusMonths(1)
    val nextMonthReceipt = repo.getMonthlyReceipt(next.year, next.monthValue)
    Column(modifier = Modifier.fillMaxSize()) {
        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
        BackgroundLayer()
        Column(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxHeight()
                .widthIn(max = UI.contentMaxWidth.dp)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(start = Spacing.lg, top = Spacing.lg, end = Spacing.lg, bottom = Spacing.sm),
            verticalArrangement = Arrangement.spacedBy(Spacing.sm)
        ) {
            HeaderSection(month = month, year = year, onPrev = {}, onNext = {})
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(Spacing.sm)
            ) {
                CompTimeCard(summary = summary, modifier = Modifier.weight(1f), onClick = {})
                VacationCard(summary = repo.getVacationBalanceSummary(year, today), modifier = Modifier.weight(1f), onClick = {})
            }
            WeekdayHeader()
            CalendarSection(
                year = year,
                month = month,
                today = today,
                repo = repo,
                refreshKey = 0,
                onDayClick = { _, _ -> }
            )
            StatsSection(
                summary = summary,
                onWorkHoursClick = {},
                onOvertimeClick = {},
                onFoodClick = {}
            )
            Column(verticalArrangement = Arrangement.spacedBy(PAY_CARDS_SPACING.dp)) {
                SalaryCard(month = month, nextMonthReceipt = nextMonthReceipt, salaryOnly = repo.mainPaySalaryOnly, onClick = {})
                if (SHOW_TOTAL_CARD) {
                    TotalCard(receipt = monthlyReceipt, salaryOnly = repo.mainPaySalaryOnly, onClick = {})
                }
            }
        }
        }
        BottomBar(
            modifier = Modifier,
            onAutoFill = {},
            onSickVacation = {},
            onBasePay = {},
            onHolidays = {},
            onOptions = {},
            onDeleteMonth = {}
        )
    }
}
