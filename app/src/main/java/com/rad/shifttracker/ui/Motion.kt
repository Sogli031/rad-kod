package com.rad.shifttracker.ui

import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.spring

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

    /** Pomeranje i veličina (list, prelaz meseca). */
    fun <T> spatial(): FiniteAnimationSpec<T> = spring(SPATIAL_DAMPING, SPATIAL_STIFFNESS)

    fun <T> fastSpatial(): FiniteAnimationSpec<T> = spring(SPATIAL_DAMPING, FAST_SPATIAL_STIFFNESS)

    /** Prozirnost i boja (scrim, bledenje). */
    fun <T> effects(): FiniteAnimationSpec<T> = spring(EFFECTS_DAMPING, EFFECTS_STIFFNESS)

    fun <T> fastEffects(): FiniteAnimationSpec<T> = spring(EFFECTS_DAMPING, FAST_EFFECTS_STIFFNESS)
}
