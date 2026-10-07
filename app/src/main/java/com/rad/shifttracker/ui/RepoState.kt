package com.rad.shifttracker.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.rad.shifttracker.repo.ShiftRepository

/** Brojač izmena podataka: raste pri svakoj izmeni u [repo], dok je composable na ekranu. */
@Composable
internal fun rememberRepoTick(repo: ShiftRepository): Int {
    var tick by remember(repo) { mutableIntStateOf(0) }
    DisposableEffect(repo) {
        val token = repo.onChange { tick++ }
        onDispose { repo.removeOnChange(token) }
    }
    return tick
}

/**
 * Upit nad repo-om koji se NE ponavlja pri svakoj rekompoziciji: računa se jednom i ponovo tek kad
 * se podaci izmene ili se promeni neki od [keys].
 */
@Composable
internal fun <T> rememberRepoValue(repo: ShiftRepository, vararg keys: Any?, query: ShiftRepository.() -> T): T {
    val tick = rememberRepoTick(repo)
    return remember(repo, tick, *keys) { repo.query() }
}
