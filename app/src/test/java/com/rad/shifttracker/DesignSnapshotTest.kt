package com.rad.shifttracker

import androidx.compose.foundation.layout.Arrangement
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

    /** Mesec sa svim vrstama dana: tri smene, slobodni, godišnji, bolovanje i prekovremeno. */
    private fun sampleRepository(): ShiftRepository = testRepository().apply {
        val pattern = listOf(Shift.FIRST, Shift.FIRST, Shift.SECOND, Shift.SECOND, Shift.THIRD, Shift.THIRD, Shift.OFF, Shift.OFF)
        val days = YearMonth.of(YEAR, MONTH).lengthOfMonth()
        for (day in 1..days) {
            val shift = when (day) {
                in 20..22 -> Shift.VACATION
                27 -> Shift.SICK
                else -> pattern[(day - 1) % pattern.size]
            }
            val hours = if (shift == Shift.OFF) 0 else 8
            if (day == 9) {
                set(YEAR, MONTH, day, shift, hours, shift2 = Shift.SECOND, hours2 = 4)
            } else {
                set(YEAR, MONTH, day, shift, hours)
            }
        }
    }

    private companion object {
        const val YEAR = 2026
        const val MONTH = 10
        const val DIALOG_SETTLE_MS = 1_000L
    }
}


/** Isti raspored sekcija kao [ShiftTrackerScreen], bez sporednih efekata. */
@Composable
private fun MainScreenSnapshot(repo: ShiftRepository, today: LocalDate) {
    val year = today.year
    val month = today.monthValue
    val summary = repo.getSummary(year, month)
    val payBreakdown = repo.getNetPayBreakdown(year, month)
    val monthlyReceipt = repo.getMonthlyReceipt(year, month)
    val next = YearMonth.of(year, month).plusMonths(1)
    val nextMonthReceipt = repo.getMonthlyReceipt(next.year, next.monthValue)
    Box(modifier = Modifier.fillMaxSize()) {
        BackgroundLayer()
        Column(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxHeight()
                .widthIn(max = UI.contentMaxWidth.dp)
                .fillMaxWidth()
                .padding(
                    start = UI.cardPadding.dp,
                    top = 16.dp,
                    end = UI.cardPadding.dp,
                    bottom = mainContentBottomPaddingDp().dp
                ),
            verticalArrangement = Arrangement.spacedBy(UI.largeSpacing.dp)
        ) {
            HeaderSection(month = month, year = year, onPrev = {}, onNext = {})
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(UI.defaultSpacing.dp)
            ) {
                CompTimeCard(summary = summary, modifier = Modifier.weight(1f), onClick = {})
                VacationCard(repo = repo, repoTick = 0, year = year, today = today, modifier = Modifier.weight(1f), onClick = {})
            }
            WeekdayHeader()
            CalendarSection(
                modifier = Modifier.weight(1f).fillMaxSize(),
                year = year,
                month = month,
                today = today,
                repo = repo,
                onDayClick = { _, _ -> }
            )
            StatsSection(
                summary = summary,
                payBreakdown = payBreakdown,
                onWorkHoursClick = {},
                onOvertimeClick = {},
                onFoodClick = {},
                onMinuliClick = {}
            )
            Column(verticalArrangement = Arrangement.spacedBy(PAY_CARDS_SPACING.dp)) {
                SalaryCard(month = month, nextMonthReceipt = nextMonthReceipt, includeTransport = repo.mainPayIncludesTransport, onClick = {})
                if (SHOW_TOTAL_CARD) {
                    TotalCard(receipt = monthlyReceipt, includeTransport = repo.mainPayIncludesTransport, onClick = {})
                }
            }
        }
        BottomBar(
            modifier = Modifier.align(Alignment.BottomCenter),
            onAutoFill = {},
            onSickVacation = {},
            onBasePay = {},
            onHolidays = {},
            onOptions = {},
            onDeleteMonth = {}
        )
    }
}
