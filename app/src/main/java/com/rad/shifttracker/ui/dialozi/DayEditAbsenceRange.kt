package com.rad.shifttracker.ui.dialozi

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.produceState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import java.time.LocalDate
import com.rad.shifttracker.repo.ABSENCE_RANGE_MAX_DAYS
import com.rad.shifttracker.repo.ShiftRepository
import com.rad.shifttracker.repo.absenceRangeDates
import com.rad.shifttracker.repo.absenceRangeNetDifference
import com.rad.shifttracker.repo.nearestAbsenceWorkday
import com.rad.shifttracker.smene.DayRecord
import com.rad.shifttracker.smene.ShiftScheduler
import com.rad.shifttracker.ui.DataActions
import com.rad.shifttracker.osnova.MONTH_NAMES
import com.rad.shifttracker.smene.Shift
import com.rad.shifttracker.ui.HeaderChevron
import com.rad.shifttracker.ui.extendedColors
import com.rad.shifttracker.ui.titleLargeEmphasized

internal const val DAY_EDIT_ABSENCE_UNTIL_LABEL = "Do kog dana"

/** „1 radni dan“, „3 radna dana“, „8 radnih dana“ bolovanja ili godišnjeg. */
internal fun absenceRangeCountText(shift: Shift, workdays: Int): String {
    val lastTwo = workdays % 100
    val word = when {
        workdays % 10 == 1 && lastTwo != 11 -> "radni dan"
        workdays % 10 in 2..4 && lastTwo !in 12..14 -> "radna dana"
        else -> "radnih dana"
    }
    val type = if (shift == Shift.VACATION) "godišnjeg" else "bolovanja"
    return "$workdays $word $type"
}

/** „26. Oktobar“; u drugoj godini od [from] i sa godinom. */
internal fun absenceUntilDateText(date: LocalDate, from: LocalDate): String {
    val text = "${date.dayOfMonth}. ${MONTH_NAMES[date.monthValue]}"
    return if (date.year == from.year) text else "$text ${date.year}"
}

/**
 * „Do kog dana“ u prozoru dana: bolovanje i godišnji od [start] do [end], i preko granice meseca.
 * [dates] su dani koji se upisuju: sam dan uvek (i vikendom), ostatak po pravilima bolovanja / godišnjeg.
 */
internal class DayEditAbsenceRange(
    val start: LocalDate,
    val end: LocalDate,
    val shift: Shift?,
    val dates: List<LocalDate>,
    val scheduler: ShiftScheduler,
    val onPrevious: () -> Unit,
    val onNext: () -> Unit
) {
    /** Upisuje opseg posle prvog dana; zove se pre čuvanja samog dana, da godišnji ne bude brojan dvaput. */
    fun applyTo(actions: DataActions, sickPayRate: Double) {
        val type = shift ?: return
        if (!end.isAfter(start)) return
        actions.applyAbsenceRange(scheduler, type, start, end, if (type == Shift.SICK) sickPayRate else null)
    }
}

@Composable
internal fun rememberDayEditAbsenceRange(
    repo: ShiftRepository,
    start: LocalDate,
    form: DayEditForm,
    record: DayRecord?
): DayEditAbsenceRange {
    val scheduler = remember { ShiftScheduler() }
    var endEpoch by rememberSaveable { mutableLongStateOf(start.toEpochDay()) }
    val end = LocalDate.ofEpochDay(endEpoch)
    val shift = when {
        form.shift1 == Shift.SICK && !form.paidLeave -> Shift.SICK
        form.shift1 == Shift.VACATION -> Shift.VACATION
        else -> null
    }
    val dates = remember(repo, shift, start, end, record) {
        val range = if (shift != null && end.isAfter(start)) scheduler.absenceRangeDates(repo, shift, start, end) else emptyList()
        (listOf(start) + range).distinct()
    }
    return DayEditAbsenceRange(
        start = start,
        end = end,
        shift = shift,
        dates = dates,
        scheduler = scheduler,
        // Strelice idu po radnim danima (preskaču vikende i praznike), a ne dan po dan.
        onPrevious = {
            if (end.isAfter(start)) {
                val previous = repo.nearestAbsenceWorkday(end, -1)?.takeIf { it.isAfter(start) } ?: start
                endEpoch = previous.toEpochDay()
            }
        },
        onNext = {
            repo.nearestAbsenceWorkday(end, 1)
                ?.takeIf { !it.isAfter(start.plusDays(ABSENCE_RANGE_MAX_DAYS)) }
                ?.let { endEpoch = it.toEpochDay() }
        }
    )
}

/** Razlika za ostale dane opsega; računa se na kopiji, van glavne niti, posle svake promene kraja. */
@Composable
internal fun rememberAbsenceRangeNetDifference(repo: ShiftRepository, range: DayEditAbsenceRange, sickPayRate: Double): Double {
    val difference by produceState(0.0, range.shift, range.end, sickPayRate) {
        val shift = range.shift
        value = if (shift == null) 0.0 else withContext(Dispatchers.Default) {
            runCatching {
                range.scheduler.absenceRangeNetDifference(
                    repo, shift, range.start, range.end, if (shift == Shift.SICK) sickPayRate else null
                )
            }.getOrDefault(0.0)
        }
    }
    return difference
}

/** Kraj bolovanja ili godišnjeg: strelice pomeraju po radnim danima, i preko granice meseca. */
@Composable
internal fun DayEditAbsenceUntilCard(range: DayEditAbsenceRange, shift: Shift) {
    val dateText = absenceUntilDateText(range.end, range.start)
    val countText = absenceRangeCountText(shift, range.dates.size)
    val onPrevious = range.onPrevious
    val onNext = range.onNext
    DayEditSectionCard(title = DAY_EDIT_ABSENCE_UNTIL_LABEL) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            HeaderChevron(icon = Icons.Filled.ChevronLeft, label = "Dan ranije", onClick = onPrevious)
            Column(modifier = Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = dateText,
                    color = MaterialTheme.colorScheme.onSurface,
                    style = MaterialTheme.typography.titleLargeEmphasized,
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = countText,
                    color = MaterialTheme.extendedColors.textSecondary,
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            HeaderChevron(icon = Icons.Filled.ChevronRight, label = "Dan kasnije", onClick = onNext)
        }
    }
}
