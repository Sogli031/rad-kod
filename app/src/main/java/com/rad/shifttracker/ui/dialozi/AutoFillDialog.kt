package com.rad.shifttracker.ui.dialozi

import com.rad.shifttracker.ui.DataActions
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import com.rad.shifttracker.ui.RadMotion
import com.rad.shifttracker.ui.Spacing
import com.rad.shifttracker.ui.rememberReducedMotion
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.rad.shifttracker.ui.components.AutoSizeText
import java.time.LocalDate
import com.rad.shifttracker.osnova.MONTH_NAMES
import com.rad.shifttracker.smene.Shift
import com.rad.shifttracker.smene.ShiftFillMode
import com.rad.shifttracker.smene.ShiftFillSettings
import com.rad.shifttracker.smene.ShiftScheduler
import com.rad.shifttracker.ui.FormErrorText
import com.rad.shifttracker.ui.GlowButton
import com.rad.shifttracker.ui.ModernTextField
import com.rad.shifttracker.ui.PopupCard
import com.rad.shifttracker.ui.PopupDialog
import com.rad.shifttracker.ui.SectionLabel
import com.rad.shifttracker.ui.titleMediumEmphasized
import com.rad.shifttracker.ui.extendedColors

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
    var monthsInput by remember { mutableStateOf("12") }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    val referenceWorkWeek = remember(today) { scheduler.getReferenceWorkWeek(today) }
    val referenceWeekText = remember(referenceWorkWeek) { formatReferenceWorkWeek(referenceWorkWeek) }
    val fourBrigadeReferenceDateText = remember(today) { formatAutoFillReferenceDate(today) }

    PopupDialog(
        title = "Auto-popuna",
        onDismiss = onDismiss,
        scrollable = true
    ) {
        AutoFillHeaderCard(
            today = today,
            fillMode = fillMode,
            referenceWeekText = referenceWeekText,
            fourBrigadeReferenceDateText = fourBrigadeReferenceDateText
        )

        PopupCard {
            SectionLabel(text = "Režim smena")
            AutoFillModeGrid(
                selectedMode = fillMode,
                onModeSelected = { mode ->
                    fillMode = mode
                    startShift = defaultStartShiftForMode(mode, startShift)
                    cycleBlockDayIndex = cycleBlockDayIndex.coerceIn(0, 1)
                    errorMessage = null
                }
            )
        }

        // Promena režima: stare kartice izblede, nove uđu, a visina lista se menja glatko
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
                AutoFillShiftCards(
                    fillMode = mode,
                    startShift = startShift,
                    cycleBlockDayIndex = cycleBlockDayIndex,
                    onShiftSelected = {
                        startShift = it
                        errorMessage = null
                    },
                    onBlockDaySelected = {
                        cycleBlockDayIndex = it
                        errorMessage = null
                    }
                )
            }
        }

        PopupCard {
            SectionLabel(text = "Meseci unapred")
            ModernTextField(
                value = monthsInput,
                onValueChange = {
                    monthsInput = it.filter(Char::isDigit)
                    errorMessage = null
                },
                hintText = "Unesi broj meseci unapred...",
                label = "Broj meseci",
                isError = errorMessage != null,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
                modifier = Modifier.fillMaxWidth()
            )
        }

        AnimatedVisibility(
            visible = errorMessage != null,
            enter = if (reducedMotion) EnterTransition.None else fadeIn(RadMotion.effects()) + expandVertically(RadMotion.spatial()),
            exit = if (reducedMotion) ExitTransition.None else fadeOut(RadMotion.fastEffects()) + shrinkVertically(RadMotion.fastSpatial())
        ) {
            // Poslednja poruka ostaje dok traje izlazna animacija.
            var shownMessage by remember { mutableStateOf("") }
            LaunchedEffect(errorMessage) { errorMessage?.let { shownMessage = it } }
            FormErrorText(message = errorMessage ?: shownMessage)
        }

        GlowButton(
            text = "Popuni smene",
            modifier = Modifier
                .fillMaxWidth()
                .height(50.dp),
            textStyle = MaterialTheme.typography.labelLarge,
            onClick = {
                val monthsError = autoFillMonthsError(monthsInput)
                if (monthsError != null) {
                    errorMessage = monthsError
                    return@GlowButton
                }
                val monthsAhead = monthsInput.toInt()
                val settings = autoFillSettings(
                    mode = fillMode,
                    today = today,
                    referenceWeekStart = referenceWorkWeek.start,
                    startShift = startShift,
                    fourBrigadeCycleDayIndex = { scheduler.getFourBrigadeCycleStartIndex(startShift, cycleBlockDayIndex) }
                )
                actions.populateMonthsAhead(
                    scheduler,
                    today,
                    settings,
                    monthsAhead
                )
                onDismiss()
            }
        )
    }
}

/** Naslov, tekući mesec i referentna sedmica ili početni dan ciklusa za izabrani režim. */
@Composable
private fun AutoFillHeaderCard(
    today: LocalDate,
    fillMode: ShiftFillMode,
    referenceWeekText: String,
    fourBrigadeReferenceDateText: String
) {
    val reducedMotion = rememberReducedMotion()
    // Red sa referentnom sedmicom / početnim danom se pojavljuje po režimu; kartica raste glatko.
    PopupCard(modifier = if (reducedMotion) Modifier else Modifier.animateContentSize(RadMotion.spatial())) {
        AutoSizeText(
            text = "Automatsko popunjavanje smena",
            color = MaterialTheme.colorScheme.onSurface,
            style = MaterialTheme.typography.titleMediumEmphasized,
            maxFontSize = MaterialTheme.typography.titleMedium.fontSize,
            minFontSize = MaterialTheme.typography.titleSmall.fontSize,
            maxLines = 1
        )
        Text(
            text = "Tekući mesec: ${MONTH_NAMES[today.monthValue]} ${today.year}",
            color = MaterialTheme.extendedColors.textSecondary,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        if (fillMode != ShiftFillMode.ONE_SHIFT) {
            Text(
                text = when (fillMode) {
                    ShiftFillMode.FIRST_SECOND,
                    ShiftFillMode.THREE_BRIGADE -> "Referentna sedmica: $referenceWeekText"
                    ShiftFillMode.FOUR_BRIGADE -> "Početni dan ciklusa: $fourBrigadeReferenceDateText"
                    ShiftFillMode.ONE_SHIFT -> ""
                },
                color = MaterialTheme.extendedColors.textSecondary,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

/** Izbor smene (i dana u bloku za četvorobrigadu) za izabrani režim; jedna smena nema izbor. */
@Composable
private fun AutoFillShiftCards(
    fillMode: ShiftFillMode,
    startShift: Shift,
    cycleBlockDayIndex: Int,
    onShiftSelected: (Shift) -> Unit,
    onBlockDaySelected: (Int) -> Unit
) {
    when (fillMode) {
        ShiftFillMode.ONE_SHIFT -> Unit
        ShiftFillMode.FIRST_SECOND -> {
            PopupCard {
                SectionLabel(text = "Koja je smena u toj sedmici?")
                AutoFillShiftGrid(
                    options = listOf(
                        AutoFillShiftChoice(dayEditShiftButtonText(Shift.FIRST), Shift.FIRST),
                        AutoFillShiftChoice(dayEditShiftButtonText(Shift.SECOND), Shift.SECOND)
                    ),
                    selectedShift = startShift,
                    onShiftSelected = onShiftSelected
                )
            }
        }
        ShiftFillMode.THREE_BRIGADE -> {
            PopupCard {
                SectionLabel(text = "Koja je smena u toj sedmici?")
                AutoFillShiftGrid(
                    options = listOf(
                        AutoFillShiftChoice(dayEditShiftButtonText(Shift.FIRST), Shift.FIRST),
                        AutoFillShiftChoice(dayEditShiftButtonText(Shift.SECOND), Shift.SECOND),
                        AutoFillShiftChoice(dayEditShiftButtonText(Shift.THIRD), Shift.THIRD)
                    ),
                    selectedShift = startShift,
                    onShiftSelected = onShiftSelected
                )
            }
        }
        ShiftFillMode.FOUR_BRIGADE -> {
            PopupCard {
                SectionLabel(text = "Koja si smena u početnom danu ciklusa?")
                AutoFillShiftGrid(
                    options = listOf(
                        AutoFillShiftChoice(dayEditShiftButtonText(Shift.FIRST), Shift.FIRST),
                        AutoFillShiftChoice(dayEditShiftButtonText(Shift.SECOND), Shift.SECOND),
                        AutoFillShiftChoice(dayEditShiftButtonText(Shift.THIRD), Shift.THIRD),
                        AutoFillShiftChoice("Slobodan", Shift.OFF)
                    ),
                    selectedShift = startShift,
                    onShiftSelected = onShiftSelected
                )
            }
            PopupCard {
                SectionLabel(text = "Koji je dan u tom bloku?")
                AutoFillBlockDayGrid(
                    selectedIndex = cycleBlockDayIndex,
                    onSelected = onBlockDaySelected
                )
            }
        }
    }
}

/** Greška za uneti broj meseci unapred (1–24), ili null kad je ispravan. */
internal fun autoFillMonthsError(monthsInput: String): String? {
    val monthsAhead = monthsInput.toIntOrNull()
    return when {
        monthsAhead == null -> "Unesi broj meseci."
        monthsAhead < 1 -> "Broj meseci mora biti najmanje 1."
        monthsAhead > 24 -> "Maksimalno 24 meseca unapred."
        else -> null
    }
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
