package com.rad.shifttracker.ui

import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.clearAndSetSemantics
import android.os.Build
import android.provider.Settings
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.gestures.DraggableState
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.ZeroCornerSize
import androidx.compose.material3.SnackbarHost
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import kotlinx.coroutines.launch
import kotlin.math.roundToInt
import android.view.WindowManager
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import com.rad.shifttracker.ui.components.AutoSizeText
import com.rad.shifttracker.osnova.CONFIG
import com.rad.shifttracker.R
import com.rad.shifttracker.osnova.UI
import com.rad.shifttracker.osnova.formatHours
import com.rad.shifttracker.osnova.formatShortageMinutes
import com.rad.shifttracker.osnova.regularShortageMinuteOptions

/** Scrim: M3 koristi crnu boju; na tamnoj pozadini je 60% da bi se list jasno odvojio. */
private const val SCRIM_ALPHA = 0.6f

/** List se zatvara kad se povuče dalje od ovoga (ili brzim potezom nadole). */
private const val DISMISS_DRAG_DP = 96
private const val DISMISS_FLING_VELOCITY = 1500f

/**
 * Sistemska „ukloni animacije“ (skala trajanja animacija = 0): prelazi su trenutni.
 * Čita se pri ulasku u kompoziciju; promena podešavanja važi pri sledećem otvaranju ekrana.
 */
@Composable
internal fun rememberReducedMotion(): Boolean {
    val resolver = LocalContext.current.contentResolver
    return remember {
        Settings.Global.getFloat(resolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
    }
}


/**
 * Dijalog kao M3 modalni list sa dna: oblik `extraLarge` gore, površina `surfaceContainer`,
 * ručica za prevlačenje, ulaz/izlaz po M3 emphasized prelazu. Zatvara se nazad-gestom,
 * dodirom van lista i povlačenjem nadole; [onDismiss] se zove kad izlazna animacija završi.
 * Ostaje na [Dialog] prozoru (ne na `ModalBottomSheet`) jer je njegovo ponašanje sa
 * tastaturom i isečkom kamere već podešeno u [DialogEdgeToEdgeWindow].
 */
@Composable
fun PopupDialog(
    title: String,
    onDismiss: () -> Unit,
    scrollable: Boolean = true,
    titleTextAlign: TextAlign = TextAlign.Start,
    footer: (@Composable ColumnScope.() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    val overlayInteraction = remember { MutableInteractionSource() }
    val reducedMotion = rememberReducedMotion()
    val scheme = MaterialTheme.colorScheme
    val sheetShape = MaterialTheme.shapes.extraLarge.copy(
        bottomStart = ZeroCornerSize,
        bottomEnd = ZeroCornerSize
    )
    val scope = rememberCoroutineScope()
    val currentOnDismiss by rememberUpdatedState(onDismiss)
    val visibleState = remember { MutableTransitionState(false).apply { targetState = true } }
    val dragOffset = remember { Animatable(0f) }
    val dismissDragPx = with(LocalDensity.current) { DISMISS_DRAG_DP.dp.toPx() }
    val requestDismiss: () -> Unit = { visibleState.targetState = false }
    // Pozivalac se obaveštava tek kad je izlazna animacija gotova (ili odmah ako je list uklonjen).
    LaunchedEffect(visibleState.currentState, visibleState.targetState) {
        if (!visibleState.currentState && !visibleState.targetState) currentOnDismiss()
    }
    val scrimAlpha by animateFloatAsState(
        targetValue = if (visibleState.targetState) SCRIM_ALPHA else 0f,
        animationSpec = if (reducedMotion) {
            snap()
        } else if (visibleState.targetState) {
            RadMotion.effects()
        } else {
            RadMotion.fastEffects()
        },
        label = "scrim"
    )
    val dragState = rememberDraggableState { delta ->
        scope.launch { dragOffset.snapTo((dragOffset.value + delta).coerceAtLeast(0f)) }
    }

    Dialog(
        onDismissRequest = requestDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)
    ) {
        DialogEdgeToEdgeWindow()
        BoxWithConstraints(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.BottomCenter
        ) {
            // Scrim je poseban sloj iza lista: dodir van lista zatvara dijalog, a čitač ekrana ga
            // ne fokusira (neimenovana klikabilna površina); zatvaranje nudi ručica lista („Zatvori“).
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(scheme.scrim.copy(alpha = scrimAlpha))
                    // Mora stajati PRE clickable: čisti semantiku ostalih modifiera na istom elementu.
                    .clearAndSetSemantics { }
                    .clickable(
                        interactionSource = overlayInteraction,
                        indication = null,
                        onClick = requestDismiss
                    )
            )
            val insets = WindowInsets.safeDrawing.asPaddingValues()
            // List ne sme da zađe ispod status bara / isečka kamere na nižim ekranima.
            val sheetMaxHeight = UI.popupMaxHeight.dp.coerceAtMost(
                maxHeight - insets.calculateTopPadding() - UI.popupEdgePadding.dp
            )

            AnimatedVisibility(
                visibleState = visibleState,
                // Na širokom ekranu list ostaje čitljive širine, centriran uz donju ivicu.
                modifier = Modifier.widthIn(max = UI.contentMaxWidth.dp).fillMaxWidth(),
                enter = if (reducedMotion) {
                    EnterTransition.None
                } else {
                    slideInVertically(RadMotion.spatial()) { it }
                },
                exit = if (reducedMotion) {
                    ExitTransition.None
                } else {
                    slideOutVertically(RadMotion.fastSpatial()) { it }
                }
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .offset { IntOffset(0, dragOffset.value.roundToInt()) }
                        .semantics { paneTitle = title }
                        .heightIn(max = sheetMaxHeight)
                        .clip(sheetShape)
                        .background(scheme.surfaceContainer, sheetShape)
                        // Guta dodire iznad lista (bez semantike), da ne zatvore dijalog preko scrim-a.
                        .pointerInput(Unit) { detectTapGestures { } }
                        .padding(bottom = insets.calculateBottomPadding())
                        .padding(start = Spacing.lg, end = Spacing.lg, bottom = Spacing.lg)
                        .wrapContentHeight(),
                    verticalArrangement = Arrangement.spacedBy(Spacing.sm)
                ) {
                    SheetHeader(
                        title = title,
                        titleTextAlign = titleTextAlign,
                        dragState = dragState,
                        onDragStopped = { velocity ->
                            if (dragOffset.value > dismissDragPx || velocity > DISMISS_FLING_VELOCITY) {
                                requestDismiss()
                            } else {
                                dragOffset.animateTo(0f)
                            }
                        },
                        onClose = requestDismiss
                    )
                    if (scrollable) {
                        val scrollState = rememberScrollState()
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(1f, fill = false)
                                .verticalScroll(scrollState),
                            verticalArrangement = Arrangement.spacedBy(Spacing.sm)
                        ) {
                            content()
                        }
                    } else {
                        Column(
                            modifier = Modifier.fillMaxWidth(),
                            verticalArrangement = Arrangement.spacedBy(Spacing.sm)
                        ) {
                            content()
                        }
                    }
                    footer?.let { footerContent ->
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = Spacing.xs),
                            verticalArrangement = Arrangement.spacedBy(Spacing.sm)
                        ) {
                            footerContent()
                        }
                    }
                }
            }

            // Dijalog je poseban prozor: poruka glavnog ekrana bi ostala ispod njega, pa je prikazujemo ovde.
            LocalAppSnackbar.current?.let { host ->
                SnackbarHost(
                    hostState = host,
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(top = insets.calculateTopPadding() + Spacing.sm, start = Spacing.lg, end = Spacing.lg)
                )
            }
        }
    }
}

/** Zaglavlje lista: ručica i naslov; cela površina se može povući nadole da se list zatvori. */
@Composable
private fun SheetHeader(
    title: String,
    titleTextAlign: TextAlign,
    dragState: DraggableState,
    onDragStopped: suspend (velocity: Float) -> Unit,
    onClose: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .draggable(
                state = dragState,
                orientation = Orientation.Vertical,
                onDragStopped = { velocity -> onDragStopped(velocity) }
            ),
        verticalArrangement = Arrangement.spacedBy(Spacing.xs)
    ) {
        SheetDragHandle(
            modifier = Modifier.align(Alignment.CenterHorizontally),
            onClose = onClose
        )
        AutoSizeText(
            text = title,
            color = MaterialTheme.colorScheme.onSurface,
            style = MaterialTheme.typography.titleLargeEmphasized.copy(
                textAlign = titleTextAlign
            ),
            maxFontSize = MaterialTheme.typography.titleLarge.fontSize,
            minFontSize = MaterialTheme.typography.titleMedium.fontSize,
            maxLines = 1,
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = Spacing.xxs)
                .semantics { heading() }
        )
    }
}

/** M3 ručica lista (32x4dp, `onSurfaceVariant` 40%); čitaču ekrana nudi radnju „Zatvori“. */
@Composable
private fun SheetDragHandle(modifier: Modifier = Modifier, onClose: () -> Unit) {
    Box(
        modifier = modifier
            .height(SHEET_HANDLE_AREA_DP.dp)
            .width(SHEET_HANDLE_AREA_WIDTH_DP.dp)
            .semantics(mergeDescendants = true) {
                contentDescription = "Ručica lista, prevuci nadole da zatvoriš"
                customActions = listOf(CustomAccessibilityAction("Zatvori") { onClose(); true })
            },
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .width(32.dp)
                .height(4.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f))
        )
    }
}

private const val SHEET_HANDLE_AREA_DP = 28
private const val SHEET_HANDLE_AREA_WIDTH_DP = 64

/**
 * Prozor dijaloga preko celog ekrana, kao i MainActivity. Bez ovoga Android (npr. Pixel 9)
 * ograniči prozor na prostor između status i navigacione trake, a Compose sadržaj izmeri
 * na punu visinu ekrana — dno lista ode ispod ivice. Razmak od traka daju safeDrawing insets.
 */
@Composable
private fun DialogEdgeToEdgeWindow() {
    val window = (LocalView.current.parent as? DialogWindowProvider)?.window ?: return
    SideEffect {
        // Tastatura: bez resize Android pomeri ceo prozor nagore (naslov ode pod status bar);
        // sa resize list dobije ime inset kroz safeDrawing i stane iznad tastature.
        @Suppress("DEPRECATION")
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        val attrs = window.attributes
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            attrs.fitInsetsTypes = 0
            attrs.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        } else {
            attrs.flags = attrs.flags or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                attrs.layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
        window.attributes = attrs
    }
}

/** Grupa unutar lista: tamnija površina (`surfaceContainerLowest`) i tanka linija `outlineVariant`. */
@Composable
fun PopupCard(
    modifier: Modifier = Modifier,
    spacing: Dp = Spacing.sm,
    content: @Composable ColumnScope.() -> Unit
) {
    GlassCard(
        modifier = modifier,
        bgColor = MaterialTheme.colorScheme.surfaceContainerLowest
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Spacing.lg, vertical = Spacing.md),
            verticalArrangement = Arrangement.spacedBy(spacing)
        ) {
            content()
        }
    }
}

@Composable
fun HoursPickerDialog(
    title: String,
    currentHours: Int,
    minHours: Int = 1,
    accent: Color = MaterialTheme.colorScheme.primary,
    onSelect: (Int) -> Unit,
    onDismiss: () -> Unit
) {
    PopupDialog(
        title = title,
        onDismiss = onDismiss,
        scrollable = false
    ) {
        val hours = (minHours..CONFIG.pay.defaultHours).toList()
        val rows = hours.chunked(4)
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(Spacing.md)
        ) {
            for (row in rows) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(Spacing.md)
                ) {
                    for (hour in row) {
                        val isSelected = hour == currentHours
                        RoundedButton(
                            text = hour.toString(),
                            modifier = Modifier
                                .weight(1f)
                                .height(UI.buttonHeight.dp),
                            bgColor = if (isSelected) accent else MaterialTheme.colorScheme.background,
                            textColor = if (isSelected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
                            textStyle = MaterialTheme.typography.titleLarge,
                            bold = true,
                            onClick = {
                                onSelect(hour)
                                onDismiss()
                            }
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun ShortageMinutesDialog(
    currentMinutes: Int,
    onSelect: (Int) -> Unit,
    onDismiss: () -> Unit
) {
    PopupDialog(
        title = "Minuti",
        onDismiss = onDismiss,
        scrollable = false
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(Spacing.sm)
        ) {
            for (minutes in regularShortageMinuteOptions()) {
                val isSelected = minutes == currentMinutes
                RoundedButton(
                    text = formatShortageMinutes(minutes),
                    modifier = Modifier
                        .weight(1f)
                        .height(UI.buttonHeight.dp),
                    bgColor = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.background,
                    textColor = if (isSelected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
                    textStyle = MaterialTheme.typography.titleMedium,
                    bold = true,
                    onClick = {
                        onSelect(minutes)
                        onDismiss()
                    }
                )
            }
        }
    }
}

@Composable
fun CompUsedDialog(
    availableHours: Int,
    currentHours: Int,
    onSelect: (Int) -> Unit,
    onDismiss: () -> Unit
) {
    val maxHours = minOf(CONFIG.pay.defaultHours, availableHours.coerceAtLeast(0))
    val hourOptions = (0..maxHours).toList()
    val rows = hourOptions.chunked(4)

    PopupDialog(
        title = "Iskoristi zara\u0111ene sate",
        onDismiss = onDismiss,
        scrollable = true
    ) {
        AutoSizeText(
            text = "Dostupno: ${formatHours(availableHours)}",
            color = MaterialTheme.extendedColors.compTime,
            style = MaterialTheme.typography.bodyLargeEmphasized,
            maxFontSize = MaterialTheme.typography.bodyLarge.fontSize,
            minFontSize = MaterialTheme.typography.bodyMedium.fontSize,
            maxLines = 1
        )
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(Spacing.sm)
        ) {
            for (row in rows) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(Spacing.sm)
                ) {
                    for (hours in row) {
                        val isSelected = hours == currentHours
                        RoundedButton(
                            text = formatHours(hours),
                            modifier = Modifier
                                .weight(1f)
                                .height(UI.buttonHeight.dp),
                            bgColor = if (isSelected) MaterialTheme.extendedColors.compTimeUsed else MaterialTheme.colorScheme.background,
                            textColor = MaterialTheme.colorScheme.onSurface,
                            textStyle = MaterialTheme.typography.titleLarge,
                            bold = true,
                            onClick = {
                                onSelect(hours)
                                onDismiss()
                            }
                        )
                    }
                    if (row.size < 4) {
                        Spacer(modifier = Modifier.weight((4 - row.size).toFloat()))
                    }
                }
            }
        }
    }
}
