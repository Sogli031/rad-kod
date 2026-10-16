package com.rad.shifttracker.uvoz

import java.time.temporal.ChronoUnit
import com.rad.shifttracker.listici.FillDay
import com.rad.shifttracker.listici.FillDayKind

/** Solver čuva jedan najbolji blok za dati zbir noćnih sati. Ovde se dodaju i drugi mogući datumi
 * istog bloka, umesto da AI bira samo između različitih zbirova ili složenijih izuzetaka.
 * Ovo su samo kandidati: svaki prolazi pun obračun pre nego što se uopšte pošalje AI-ju.
 */
internal fun variedFillCandidates(
    baseline: List<FillDay>,
    verified: (List<FillDay>) -> Boolean
): List<List<FillDay>> {
    val results = mutableListOf(baseline)
    val maxRuns = leaveKinds.associateWith { leaveRuns(baseline, it) }
    var attempts = 0
    var cursor = 0
    while (cursor < results.size && results.size < MAX_VARIANTS && attempts < MAX_VARIANT_CHECKS) {
        val days = results[cursor++]
        for (i in days.indices) {
            for (j in i + 1 until days.size) {
                val candidate = swappedDays(days, i, j) ?: continue
                if (candidate in results || leaveKinds.any { leaveRuns(candidate, it) > maxRuns.getValue(it) }) continue
                attempts++
                if (verified(candidate)) results.add(candidate)
                if (results.size >= MAX_VARIANTS || attempts >= MAX_VARIANT_CHECKS) return results
            }
        }
    }
    return results
}

private fun swappedDays(days: List<FillDay>, i: Int, j: Int): List<FillDay>? {
    val first = days[i]
    val second = days[j]
    if (first.templateShift != second.templateShift || first.templateShift == null) return null
    if (first.kind !in movableKinds || second.kind !in movableKinds) return null
    if ((first.date.dayOfWeek.value <= 5) != (second.date.dayOfWeek.value <= 5)) return null
    if (first.copy(date = second.date) == second) return null
    return days.toMutableList().apply {
        this[i] = second.copy(date = first.date, templateShift = first.templateShift)
        this[j] = first.copy(date = second.date, templateShift = second.templateShift)
    }
}

private fun leaveRuns(days: List<FillDay>, kind: FillDayKind): Int {
    val dates = days.filter { it.kind == kind }.map { it.date }.sorted()
    if (dates.isEmpty()) return 0
    return 1 + dates.zipWithNext().count { (previous, next) ->
        val gap = ChronoUnit.DAYS.between(previous, next)
        gap > 1 && (1 until gap).any { previous.plusDays(it).dayOfWeek.value <= 5 }
    }
}

private val leaveKinds = setOf(FillDayKind.VACATION, FillDayKind.SICK, FillDayKind.PAID_LEAVE)
private val movableKinds = leaveKinds + setOf(FillDayKind.WORK, FillDayKind.ABSENT)
private const val MAX_VARIANTS = 12
private const val MAX_VARIANT_CHECKS = 160
