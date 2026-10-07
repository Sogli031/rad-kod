package com.rad.shifttracker.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import kotlin.math.roundToInt

/**
 * Spring pokret komponenti po M3 `MotionScheme.standard()` (vrednosti iz specifikacije: prostorni pokret
 * ima blag odskok, efekti (prozirnost, boja) ga nemaju). `MotionScheme.standard()` je u material3 1.4 još `internal`
 * (javan od 1.5), pa se iste vrednosti drže ovde; posle prelaska na stabilnu 1.5 ovaj objekat se menja pozivima teme.
 */
internal object RadMotion {
    private const val SPATIAL_DAMPING = 0.9f
    private const val SPATIAL_STIFFNESS = 700f
    private const val FAST_SPATIAL_STIFFNESS = 1400f
    private const val EFFECTS_DAMPING = 1f
    private const val EFFECTS_STIFFNESS = 1600f
    private const val FAST_EFFECTS_STIFFNESS = 3800f

    /** M3 emphasized decelerate: brz početak, mekan kraj (odbrojavanje iznosa). */
    val EmphasizedDecelerate = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1f)

    /** Pomeranje i veličina (list, prelaz meseca). */
    fun <T> spatial(): FiniteAnimationSpec<T> = spring(SPATIAL_DAMPING, SPATIAL_STIFFNESS)

    fun <T> fastSpatial(): FiniteAnimationSpec<T> = spring(SPATIAL_DAMPING, FAST_SPATIAL_STIFFNESS)

    /** Prozirnost i boja (scrim, bledenje). */
    fun <T> effects(): FiniteAnimationSpec<T> = spring(EFFECTS_DAMPING, EFFECTS_STIFFNESS)

    fun <T> fastEffects(): FiniteAnimationSpec<T> = spring(EFFECTS_DAMPING, FAST_EFFECTS_STIFFNESS)
}

private const val COUNT_DURATION_MS = 700

/**
 * Broj koji „odbroji“ od stare do nove vrednosti (promena meseca, izmena dana). Prvi prikaz je odmah
 * tačan; bez animacija u sistemu (skala 0) vrednost samo skoči.
 */
@Composable
internal fun rememberCountingValue(target: Double): Double {
    val safeTarget = if (target.isFinite()) target else 0.0
    val reducedMotion = rememberReducedMotion()
    val animatable = remember { Animatable(safeTarget.toFloat()) }
    LaunchedEffect(safeTarget) {
        if (reducedMotion) {
            animatable.snapTo(safeTarget.toFloat())
        } else {
            animatable.animateTo(
                targetValue = safeTarget.toFloat(),
                animationSpec = tween(COUNT_DURATION_MS, easing = RadMotion.EmphasizedDecelerate)
            )
        }
    }
    // Na kraju tačno cilj (Float gubi preciznost kod velikih iznosa).
    return if (animatable.value == safeTarget.toFloat()) safeTarget else animatable.value.toDouble()
}

@Composable
internal fun rememberCountingValue(target: Int): Int = rememberCountingValue(target.toDouble()).roundToInt()

/** Cifre iste širine: broj koji odbrojava ne „pleše“ levo-desno. */
internal const val TABULAR_FIGURES = "tnum"
