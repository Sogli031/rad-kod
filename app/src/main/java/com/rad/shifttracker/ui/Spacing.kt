package com.rad.shifttracker.ui

import androidx.compose.ui.unit.dp

/**
 * Razmaci na M3 mreži od 4dp (8dp za raspored, 4dp i 2dp samo za sitne razmake unutar komponenti).
 * Margine, padding i razmaci između elemenata idu samo kroz ove tokene, ne kroz `.dp` literale.
 */
object Spacing {
    /** Razmak između redova teksta unutar iste celine (npr. vrednost i oznaka). */
    val xxs = 2.dp
    val xs = 4.dp
    val sm = 8.dp
    val md = 12.dp
    /** Unutrašnja margina kartica i ivica ekrana. */
    val lg = 16.dp
    val xl = 24.dp
}
