package com.rad.shifttracker.ui.dialozi

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import java.time.DayOfWeek
import java.time.LocalDate
import com.rad.shifttracker.smene.Shift
import com.rad.shifttracker.smene.ShiftFillMode
import com.rad.shifttracker.smene.ShiftFillSettings
import com.rad.shifttracker.smene.ShiftScheduler
import com.rad.shifttracker.ui.DataActions
import com.rad.shifttracker.ui.GlowButton
import com.rad.shifttracker.ui.PopupCard
import com.rad.shifttracker.ui.PopupDialog
import com.rad.shifttracker.ui.RadMotion
import com.rad.shifttracker.ui.SectionLabel
import com.rad.shifttracker.ui.Spacing
import com.rad.shifttracker.ui.extendedColors
import com.rad.shifttracker.ui.rememberReducedMotion
import com.rad.shifttracker.ui.titleMediumEmphasized

@Composable
internal fun AutoFillDialog(
    today: LocalDate,
    scheduler: ShiftScheduler,
    actions: DataActions,
    onDismiss: () -> Unit
) {
    var fillMode by remember { mutableStateOf(ShiftFillMode.ONE_SHIFT) }
    var startShift by remember { mutableStateOf(Shift.FIRST) }
    var cycleBlockDayIndex by remember { mutableIntStateOf(0) }
    // Radnim danom pita za ovu sedmicu, vikendom za sledeću — bez datuma, da ne zbunjuje.
    val isWeekend = today.dayOfWeek == DayOfWeek.SATURDAY || today.dayOfWeek == DayOfWeek.SUNDAY
    val referenceWorkWeek = remember(today) {
        scheduler.getReferenceWorkWeek(scheduler.getAutoFillReferenceDate(today))
    }

    PopupDialog(
        title = "Auto-popuna",
        onDismiss = onDismiss,
        scrollable = true
    ) {
        PopupCard {
            SectionLabel(text = "Režim smena")
            AutoFillModeGrid(
                selectedMode = fillMode,
                onModeSelected = { mode ->
                    fillMode = mode
                    startShift = defaultStartShiftForMode(mode, startShift)
                    cycleBlockDayIndex = cycleBlockDayIndex.coerceIn(0, 1)
                }
            )
        }

        // Promena režima: stara kartica izbledi, nova uđe, a visina lista se menja glatko
        // umesto skoka — isti prelaz kao promena tipa dana u prozoru dana.
        val reducedMotion = rememberReducedMotion()
        AnimatedContent(
            targetState = fillMode,
            transitionSpec = {
                if (reducedMotion) {
                    EnterTransition.None togetherWith ExitTransition.None
                } else {
                    (fadeIn(RadMotion.effects()) togetherWith fadeOut(RadMotion.fastEffects()))
                        .using(SizeTransform(clip = true) { _, _ -> RadMotion.spatial() })
                }
            },
            contentAlignment = Alignment.TopCenter,
            label = "autoFillShiftCards"
        ) { mode ->
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(Spacing.sm)
            ) {
                AutoFillShiftCard(
                    fillMode = mode,
                    isWeekend = isWeekend,
                    startShift = startShift,
                    cycleBlockDayIndex = cycleBlockDayIndex,
                    onShiftSelected = { startShift = it },
                    onBlockDaySelected = { cycleBlockDayIndex = it }
                )
            }
        }

        GlowButton(
            text = "Popuni smene",
            modifier = Modifier
                .fillMaxWidth()
                .height(50.dp),
            textStyle = MaterialTheme.typography.labelLarge,
            onClick = {
                val settings = autoFillSettings(
                    mode = fillMode,
                    today = today,
                    referenceWeekStart = referenceWorkWeek.start,
                    startShift = startShift,
                    fourBrigadeCycleDayIndex = { scheduler.getFourBrigadeCycleStartIndex(startShift, cycleBlockDayIndex) }
                )
                actions.populateAutoFill(scheduler, today, settings)
                onDismiss()
            }
        )
        Text(
            text = AUTO_FILL_ROLLING_HINT,
            color = MaterialTheme.extendedColors.textSecondary,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.fillMaxWidth()
        )
    }
}

internal const val AUTO_FILL_ROLLING_HINT =
    "Raspored se sam dopunjava unapred kako vreme ide. Ako promeniš smenu, ponovo popuni — sve unapred se presnimi."

/** Pitanje o smeni za izabrani režim: ova (vikendom sledeća) sedmica, ili današnji dan četvorobrigade. */
@Composable
private fun AutoFillShiftCard(
    fillMode: ShiftFillMode,
    isWeekend: Boolean,
    startShift: Shift,
    cycleBlockDayIndex: Int,
    onShiftSelected: (Shift) -> Unit,
    onBlockDaySelected: (Int) -> Unit
) {
    PopupCard {
        SectionLabel(text = "Tvoja smena")
        when (fillMode) {
            ShiftFillMode.ONE_SHIFT -> AutoFillQuestion(text = "Svakog radnog dana ide Prva smena.")
            ShiftFillMode.FIRST_SECOND,
            ShiftFillMode.THREE_BRIGADE -> {
                AutoFillQuestion(text = autoFillWeekQuestion(isWeekend))
                AutoFillShiftGrid(
                    options = autoFillShiftChoices(fillMode),
                    selectedShift = startShift,
                    onShiftSelected = onShiftSelected
                )
            }
            ShiftFillMode.FOUR_BRIGADE -> {
                AutoFillQuestion(text = "Koju smenu radiš danas?")
                AutoFillShiftGrid(
                    options = autoFillShiftChoices(fillMode),
                    selectedShift = startShift,
                    onShiftSelected = onShiftSelected
                )
                AutoFillQuestion(text = "Koji ti je to dan zaredom?")
                AutoFillBlockDayGrid(
                    selectedIndex = cycleBlockDayIndex,
                    onSelected = onBlockDaySelected
                )
            }
        }
    }
}

@Composable
private fun AutoFillQuestion(text: String) {
    Text(
        text = text,
        color = MaterialTheme.colorScheme.onSurface,
        style = MaterialTheme.typography.titleMediumEmphasized,
        modifier = Modifier.fillMaxWidth()
    )
}

/** Radnim danom „ove sedmice“, vikendom „sledeće sedmice“ (popuna kreće od ponedeljka). */
internal fun autoFillWeekQuestion(isWeekend: Boolean): String =
    if (isWeekend) "Koju smenu radiš sledeće sedmice?" else "Koju smenu radiš ove sedmice?"

private fun autoFillShiftChoices(mode: ShiftFillMode): List<AutoFillShiftChoice> = when (mode) {
    ShiftFillMode.ONE_SHIFT -> emptyList()
    ShiftFillMode.FIRST_SECOND -> listOf(Shift.FIRST, Shift.SECOND)
    ShiftFillMode.THREE_BRIGADE -> listOf(Shift.FIRST, Shift.SECOND, Shift.THIRD)
    ShiftFillMode.FOUR_BRIGADE -> listOf(Shift.FIRST, Shift.SECOND, Shift.THIRD, Shift.OFF)
}.map { shift ->
    AutoFillShiftChoice(if (shift == Shift.OFF) "Slobodan" else dayEditShiftButtonText(shift), shift)
}

/** Podešavanja popune: sidro je danas (jedna smena, četvorobrigada) ili ponedeljak referentne sedmice. */
internal fun autoFillSettings(
    mode: ShiftFillMode,
    today: LocalDate,
    referenceWeekStart: LocalDate,
    startShift: Shift,
    fourBrigadeCycleDayIndex: () -> Int
): ShiftFillSettings = when (mode) {
    ShiftFillMode.ONE_SHIFT -> ShiftFillSettings(
        mode = mode,
        anchorDate = today,
        selectedShift = Shift.FIRST
    )
    ShiftFillMode.FIRST_SECOND,
    ShiftFillMode.THREE_BRIGADE -> ShiftFillSettings(
        mode = mode,
        anchorDate = referenceWeekStart,
        selectedShift = startShift
    )
    ShiftFillMode.FOUR_BRIGADE -> ShiftFillSettings(
        mode = mode,
        anchorDate = today,
        selectedShift = startShift,
        cycleDayIndex = fourBrigadeCycleDayIndex()
    )
}
