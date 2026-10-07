package com.rad.shifttracker.ui.dialozi

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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.rad.shifttracker.smene.MonthKey
import com.rad.shifttracker.osnova.UI
import com.rad.shifttracker.listici.GmailRangeResult
import com.rad.shifttracker.listici.hasGmailPayslipAccess
import com.rad.shifttracker.listici.readGmailPayslipRange
import com.rad.shifttracker.listici.runPayslipSafely
import com.rad.shifttracker.ui.PopupCard
import com.rad.shifttracker.ui.RoundedButton
import com.rad.shifttracker.ui.SectionLabel
import com.rad.shifttracker.ui.extendedColors
import com.rad.shifttracker.listici.vacationScanStartYear
import com.rad.shifttracker.listici.vacationUsageScan
import com.rad.shifttracker.listici.vacationUsageScanText

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
                        color = MaterialTheme.extendedColors.vacation,
                        trackColor = MaterialTheme.colorScheme.surfaceContainer
                    )
                } else {
                    LinearProgressIndicator(
                        modifier = Modifier.fillMaxWidth().height(6.dp).clip(CircleShape),
                        color = MaterialTheme.extendedColors.vacation,
                        trackColor = MaterialTheme.colorScheme.surfaceContainer
                    )
                }
                Text(
                    text = if (current.total > 0) "Čitam liste... ${current.done} / ${current.total}" else "Tražim mejlove...",
                    color = MaterialTheme.extendedColors.textSecondary,
                    style = MaterialTheme.typography.bodySmall
                )
            }
            is VacationScanState.Result -> Text(
                text = current.text,
                color = if (current.ok) MaterialTheme.colorScheme.onSurface else MaterialTheme.extendedColors.warning,
                style = MaterialTheme.typography.bodyMedium
            )
            VacationScanState.Idle -> Unit
        }
        if (state !is VacationScanState.Reading) {
            RoundedButton(
                text = "Proveri po listama iz Gmail-a",
                modifier = Modifier.fillMaxWidth().height(UI.buttonHeight.dp),
                bgColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                textColor = MaterialTheme.colorScheme.onSurface,
                borderColor = MaterialTheme.colorScheme.outlineVariant,
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
