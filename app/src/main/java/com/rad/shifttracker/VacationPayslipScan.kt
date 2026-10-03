package com.rad.shifttracker

import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import java.time.LocalDate
import java.time.Month
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Godišnji → „Proveri po listićima“ (samo informativno, ništa se ne upisuje): iz Gmail-a se čitaju
 * konačni listići za period 1.7.[startYear]–30.6.([startYear] + 1) i sabira godišnji (šifra 10 / 8 h).
 * 30.6. propada prenos, a odmor se ne ostavlja da propadne, pa je zbir za taj period pravo na godišnji.
 */
internal data class VacationUsageScan(
    val startYear: Int,
    /** Dani godišnjeg po mesecu sa listića (poslednji obračun meseca). */
    val daysByMonth: Map<MonthKey, Double>
) {
    val months: List<MonthKey> get() = (7..12).map { MonthKey(startYear, it) } + (1..6).map { MonthKey(startYear + 1, it) }
    val used: Double get() = daysByMonth.values.sum()
    val missing: List<MonthKey> get() = months.filter { it !in daysByMonth }
    val complete: Boolean get() = missing.isEmpty()
}

/** Poslednji period 1.7.–30.6. za koji je lista za jun već stigla (~20. jula). */
internal fun vacationScanStartYear(today: LocalDate): Int =
    if (today.monthValue >= Month.AUGUST.value) today.year - 1 else today.year - 2

/** Iz pročitanih PDF-ova: konačni listići perioda, za svaki mesec poslednji obračun (rb.3 posle rb.2). */
internal fun vacationUsageScan(results: List<PayslipParseResult>, startYear: Int): VacationUsageScan {
    val period = VacationUsageScan(startYear, emptyMap()).months.toSet()
    val latestByMonth = results.filterIsInstance<PayslipParseResult.Success>()
        .map { it.payslip }
        .filter { it.month in period }
        .groupBy { it.month }
        .mapValues { (_, slips) -> slips.maxBy { payslipRunNumber(it.runLabel) } }
    return VacationUsageScan(startYear, latestByMonth.mapValues { (_, slip) -> payslipVacationDays(slip) })
}

internal fun vacationUsageScanText(scan: VacationUsageScan): String {
    val used = formatVacationInput(scan.used)
    val period = "od 1.7.${scan.startYear}. do 30.6.${scan.startYear + 1}."
    return if (scan.complete) {
        "Iskorišćeno $period: $used dana (12/12 listi)."
    } else {
        val missing = scan.missing.joinToString { monthYearText(it) }
        "Iskorišćeno $period: $used dana (${12 - scan.missing.size}/12 listi, fali $missing)."
    }
}

private sealed interface VacationScanState {
    data object Idle : VacationScanState
    data class Reading(val done: Int, val total: Int) : VacationScanState
    data class Result(val text: String, val ok: Boolean) : VacationScanState
}

/** Kartica u dijalogu za godišnji: dugme, napredak čitanja i rezultat. */
@Composable
internal fun VacationPayslipScanCard() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val startYear = remember { vacationScanStartYear(LocalDate.now()) }
    var state by remember { mutableStateOf<VacationScanState>(VacationScanState.Idle) }

    PopupCard {
        SectionLabel(text = "Pravo po listama")
        when (val current = state) {
            is VacationScanState.Reading -> {
                if (current.total > 0) {
                    LinearProgressIndicator(
                        progress = { current.done.toFloat() / current.total },
                        modifier = Modifier.fillMaxWidth().height(6.dp).clip(CircleShape),
                        color = THEME.vacation,
                        trackColor = THEME.bgCard
                    )
                } else {
                    LinearProgressIndicator(
                        modifier = Modifier.fillMaxWidth().height(6.dp).clip(CircleShape),
                        color = THEME.vacation,
                        trackColor = THEME.bgCard
                    )
                }
                Text(
                    text = if (current.total > 0) "Čitam liste... ${current.done} / ${current.total}" else "Tražim mejlove...",
                    color = THEME.textSecondary,
                    style = MaterialTheme.typography.bodySmall
                )
            }
            is VacationScanState.Result -> Text(
                text = current.text,
                color = if (current.ok) THEME.textPrimary else THEME.warning,
                style = MaterialTheme.typography.bodyMedium
            )
            VacationScanState.Idle -> Unit
        }
        if (state !is VacationScanState.Reading) {
            RoundedButton(
                text = "Proveri po listama iz Gmail-a",
                modifier = Modifier.fillMaxWidth().height(UI.buttonHeight.dp),
                bgColor = THEME.bgCardElevated,
                textColor = THEME.textPrimary,
                borderColor = THEME.glassBorder,
                textStyle = MaterialTheme.typography.labelLarge,
                onClick = {
                    if (!hasGmailPayslipAccess(context)) {
                        state = VacationScanState.Result("Gmail nije povezan — poveži ga u Opcijama.", ok = false)
                        return@RoundedButton
                    }
                    state = VacationScanState.Reading(0, 0)
                    scope.launch {
                        val range = withContext(Dispatchers.IO) {
                            runPayslipSafely {
                                readGmailPayslipRange(
                                    context.applicationContext,
                                    MonthKey(startYear, 7),
                                    MonthKey(startYear + 1, 6)
                                ) { done, total -> scope.launch { state = VacationScanState.Reading(done, total) } }
                            }
                        }
                        state = if (range is GmailRangeResult.Read) {
                            val scan = vacationUsageScan(range.results.map { it.second }, startYear)
                            VacationScanState.Result(vacationUsageScanText(scan), ok = scan.complete)
                        } else {
                            VacationScanState.Result("Gmail trenutno ne odgovara — pokušaj ponovo.", ok = false)
                        }
                    }
                }
            )
        }
    }
}
