package com.rad.shifttracker.ui.dialozi

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.rad.shifttracker.listici.GMAIL_PAYSLIP_CONNECT_LABEL
import com.rad.shifttracker.uvoz.OnboardingSummary
import com.rad.shifttracker.ui.RoundedButton
import com.rad.shifttracker.ui.Spacing
import com.rad.shifttracker.ui.extendedColors
import com.rad.shifttracker.uvoz.GmailImportRunner
import com.rad.shifttracker.uvoz.GmailImportState
import com.rad.shifttracker.uvoz.OnboardingImportResult
import com.rad.shifttracker.uvoz.SIDE_RUN_MESSAGES_TITLE
import com.rad.shifttracker.listici.hasGmailPayslipAccess
import com.rad.shifttracker.uvoz.onboardingSummary
import com.rad.shifttracker.uvoz.recheckSummary
import com.rad.shifttracker.listici.runPayslipSafely
import com.rad.shifttracker.repo.ShiftRepository
import com.rad.shifttracker.ui.FormErrorText
import com.rad.shifttracker.ui.PopupCard
import com.rad.shifttracker.ui.PopupDialog

/**
 * Prvo pokretanje: posle „Popuni smene“ aplikacija traži Gmail sa listićima i odmah iz njega upisuje
 * sve što može — sa konačnih listića koeficijent, vrednost sata, doprinose, porez, olakšicu, minuli
 * rad i osnovicu (isto kao „Lista“), a iz posebnih obračuna prevoz po danu i akontaciju.
 */
internal const val GMAIL_ONBOARDING_TITLE = "Liste iz Gmail-a"
internal const val GMAIL_ONBOARDING_TEXT =
    "Poveži Gmail na koji stižu platne liste."
internal const val GMAIL_ONBOARDING_SKIP_LABEL = "Preskoči"
internal const val GMAIL_ONBOARDING_NEXT_LABEL = "Dalje"
internal const val GMAIL_ONBOARDING_IMPORTING_TEXT = "Čitam liste iz Gmail-a..."

private sealed interface OnboardingStep {
    data object Intro : OnboardingStep
    data object Importing : OnboardingStep
    data class Done(val summary: OnboardingSummary) : OnboardingStep
}

private val ONBOARDING_FAILED_SUMMARY = OnboardingSummary(
    rows = emptyList(),
    notes = listOf("Liste nisu mogle da se pročitaju — Rad će probati ponovo pri sledećem ulasku.")
)

/** Naziv levo, vrednost desno, sve u jednoj liniji (naziv se ne lomi, vrednost se skraćuje). */
@Composable
private fun InlineSummaryRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(Spacing.md),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 1,
            softWrap = false
        )
        Text(
            text = value,
            color = MaterialTheme.colorScheme.onSurface,
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.End,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
    }
}

/** Naziv, pa vrednost ispod (desno) — za duge „staro → novo“. */
@Composable
private fun StackedSummaryRow(label: String, value: String) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(text = label, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
        Text(
            text = value,
            color = MaterialTheme.colorScheme.onSurface,
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.End,
            modifier = Modifier.fillMaxWidth()
        )
    }
}

/** Pregled posle uvoza: naziv levo, vrednost desno; napomene ispod. */
@Composable
private fun OnboardingSummaryContent(summary: OnboardingSummary, heading: String) {
    Text(
        text = heading,
        color = MaterialTheme.colorScheme.onSurface,
        style = MaterialTheme.typography.titleSmall
    )
    summary.rows.forEach { (label, value) ->
        if (summary.stacked) StackedSummaryRow(label, value) else InlineSummaryRow(label, value)
    }
    summary.info.forEach { info ->
        Text(text = info, color = MaterialTheme.extendedColors.textSecondary, style = MaterialTheme.typography.bodySmall)
    }
    summary.notes.forEach { note ->
        Text(text = note, color = MaterialTheme.extendedColors.warning, style = MaterialTheme.typography.bodySmall)
    }
}

/** Čitanje listića: prozor na sredini ekrana sa trakom napretka; ne zatvara se dok čitanje traje. */
@Composable
private fun OnboardingProgressDialog(done: Int, total: Int) {
    Dialog(
        onDismissRequest = {},
        properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(MaterialTheme.shapes.extraLarge)
                .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                .border(1.dp, MaterialTheme.colorScheme.primary, MaterialTheme.shapes.extraLarge)
                .padding(Spacing.xl),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(Spacing.lg)
        ) {
            Text(
                text = GMAIL_ONBOARDING_IMPORTING_TEXT,
                color = MaterialTheme.colorScheme.onSurface,
                style = MaterialTheme.typography.titleMedium,
                textAlign = TextAlign.Center
            )
            if (total > 0) {
                LinearProgressIndicator(
                    progress = { done.toFloat() / total },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(8.dp)
                        .clip(MaterialTheme.shapes.extraSmall),
                    color = MaterialTheme.colorScheme.primary,
                    trackColor = MaterialTheme.colorScheme.surfaceContainer
                )
                Text(
                    text = "$done / $total lista i obračuna",
                    color = MaterialTheme.extendedColors.textSecondary,
                    style = MaterialTheme.typography.bodyMedium
                )
            } else {
                LinearProgressIndicator(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(8.dp)
                        .clip(MaterialTheme.shapes.extraSmall),
                    color = MaterialTheme.colorScheme.primary,
                    trackColor = MaterialTheme.colorScheme.surfaceContainer
                )
                Text(
                    text = "Tražim mejlove...",
                    color = MaterialTheme.extendedColors.textSecondary,
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        }
    }
}

/** Poruke iz provere pri ulasku (stigla lista sa izmenama, akontacija, prevoz): prozor na sredini ekrana. */
@Composable
internal fun SideRunMessagesDialog(messages: List<String>, onDismiss: () -> Unit) {
    Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 560.dp)
                .clip(MaterialTheme.shapes.extraLarge)
                .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                .border(1.dp, MaterialTheme.colorScheme.primary, MaterialTheme.shapes.extraLarge)
                .padding(Spacing.xl),
            verticalArrangement = Arrangement.spacedBy(Spacing.lg)
        ) {
            Text(
                text = SIDE_RUN_MESSAGES_TITLE,
                color = MaterialTheme.colorScheme.onSurface,
                style = MaterialTheme.typography.titleMedium,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )
            Column(
                modifier = Modifier
                    .weight(1f, fill = false)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(Spacing.md)
            ) {
                messages.forEach { message ->
                    Text(text = message, color = MaterialTheme.colorScheme.onSurface, style = MaterialTheme.typography.bodyMedium)
                }
            }
            RoundedButton(
                text = "U redu",
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp),
                bgColor = MaterialTheme.colorScheme.primary,
                textColor = MaterialTheme.colorScheme.onPrimary,
                bold = true,
                onClick = onDismiss
            )
        }
    }
}

/**
 * [ONBOARDING] = prvo pokretanje (posle „Popuni smene“, pregled upisanih vrednosti); [UPDATE] = posle
 * ažuriranja stare verzije bez Gmail-a (jednom); [RECHECK] = Opcije → „Proveri po listićima“.
 * UPDATE i RECHECK prikazuju samo razlike (staro → novo).
 */
internal enum class GmailImportMode(
    val title: String,
    val introText: String,
    val doneHeading: String,
    val doneLabel: String
) {
    ONBOARDING(GMAIL_ONBOARDING_TITLE, GMAIL_ONBOARDING_TEXT, "Upisano iz Gmail-a", GMAIL_ONBOARDING_NEXT_LABEL),
    UPDATE(GMAIL_ONBOARDING_TITLE, GMAIL_UPDATE_TEXT, RECHECK_CHANGED_HEADING, "U redu"),
    RECHECK(PAYSLIP_RECHECK_LABEL, GMAIL_RECHECK_CONNECT_TEXT, RECHECK_CHANGED_HEADING, "U redu");

    fun summary(repo: ShiftRepository, result: OnboardingImportResult): OnboardingSummary =
        if (this == ONBOARDING) onboardingSummary(repo, result) else recheckSummary(result)

    /** Provera bez izmena nema „ispravljeno“ u naslovu. */
    fun heading(summary: OnboardingSummary): String =
        if (this != ONBOARDING && summary.rows.isEmpty()) "Provereno po listama" else doneHeading
}

private const val RECHECK_CHANGED_HEADING = "Ispravljeno po listama"
internal const val PAYSLIP_RECHECK_LABEL = "Proveri sve po listama"
private const val GMAIL_UPDATE_TEXT = GMAIL_ONBOARDING_TEXT
private const val GMAIL_RECHECK_CONNECT_TEXT = GMAIL_ONBOARDING_TEXT

private const val GMAIL_UPDATE_PREFS = "rad_gmail_update_prefs"
private const val KEY_UPDATE_INTRO_OFFERED = "update_intro_offered"

/**
 * Posle ažuriranja stare verzije (nije prvo pokretanje, a Gmail nije povezan) jednom ponudi
 * povezivanje Gmail-a i proveru po listićima. Vraća true ako treba prikazati; beleži da je ponuđeno.
 */
internal fun takeGmailUpdateIntro(context: Context, firstLaunch: Boolean): Boolean {
    val prefs = context.getSharedPreferences(GMAIL_UPDATE_PREFS, Context.MODE_PRIVATE)
    if (prefs.getBoolean(KEY_UPDATE_INTRO_OFFERED, false)) return false
    prefs.edit().putBoolean(KEY_UPDATE_INTRO_OFFERED, true).apply()
    return !firstLaunch && !hasGmailPayslipAccess(context)
}

/**
 * Gmail sa listićima i uvoz (videti [GmailImportMode]); [onFinished] posle „Dalje“ ili „Preskoči“.
 * RECHECK sa već povezanim Gmail-om odmah kreće u čitanje.
 */
@Composable
internal fun GmailOnboardingDialog(
    repo: ShiftRepository,
    mode: GmailImportMode = GmailImportMode.ONBOARDING,
    onFinished: () -> Unit
) {
    val context = LocalContext.current
    // Uvoz teče van ekrana: okretanje telefona ne prekida čitanje niti ga pokreće ponovo.
    val importState by GmailImportRunner.state.collectAsStateWithLifecycle()
    val step = remember(importState) {
        when (val state = importState) {
            GmailImportState.Idle -> OnboardingStep.Intro
            is GmailImportState.Running -> OnboardingStep.Importing
            is GmailImportState.Finished -> OnboardingStep.Done(
                state.result?.let { runPayslipSafely { mode.summary(repo, it) } } ?: ONBOARDING_FAILED_SUMMARY
            )
        }
    }
    val finish = {
        GmailImportRunner.reset()
        onFinished()
    }
    val connector = rememberGmailPayslipConnector { GmailImportRunner.start(context, repo) }
    LaunchedEffect(Unit) {
        if (mode == GmailImportMode.RECHECK && hasGmailPayslipAccess(context) && importState == GmailImportState.Idle) {
            GmailImportRunner.start(context, repo)
        }
    }

    (importState as? GmailImportState.Running)?.let { running ->
        OnboardingProgressDialog(done = running.done, total = running.total)
        return
    }
    // RECHECK sa povezanim Gmail-om: prvi kadar pre nego što LaunchedEffect krene.
    if (step == OnboardingStep.Intro && mode == GmailImportMode.RECHECK && hasGmailPayslipAccess(context)) return

    PopupDialog(
        title = mode.title,
        onDismiss = { if (step !is OnboardingStep.Importing) finish() },
        scrollable = true
    ) {
        PopupCard {
            when (val current = step) {
                is OnboardingStep.Done -> OnboardingSummaryContent(current.summary, mode.heading(current.summary))
                else -> Text(
                    text = if (current == OnboardingStep.Intro) mode.introText else GMAIL_ONBOARDING_IMPORTING_TEXT,
                    color = MaterialTheme.extendedColors.textSecondary,
                    style = MaterialTheme.typography.bodyMedium
                )
            }
            connector.error?.let {
                FormErrorText(message = it, style = MaterialTheme.typography.bodySmall)
            }
        }
        OnboardingActions(step, mode, connector.busy, connector.connect, finish)
    }
}

/** Dugmad ispod teksta: „Preskoči“ / „Poveži Gmail“ na uvodu, „Dalje“ na kraju, ništa tokom čitanja. */
@Composable
private fun OnboardingActions(
    step: OnboardingStep,
    mode: GmailImportMode,
    connecting: Boolean,
    onConnect: () -> Unit,
    onFinish: () -> Unit
) {
    when (step) {
        OnboardingStep.Intro -> Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(Spacing.md)
        ) {
            RoundedButton(
                text = GMAIL_ONBOARDING_SKIP_LABEL,
                modifier = Modifier
                    .weight(1f)
                    .height(48.dp),
                bgColor = MaterialTheme.colorScheme.surfaceContainer,
                textColor = MaterialTheme.colorScheme.onSurface,
                onClick = onFinish
            )
            RoundedButton(
                text = if (connecting) "Povezujem..." else GMAIL_PAYSLIP_CONNECT_LABEL,
                modifier = Modifier
                    .weight(1f)
                    .height(48.dp),
                bgColor = MaterialTheme.colorScheme.primary,
                textColor = MaterialTheme.colorScheme.onPrimary,
                bold = true,
                onClick = onConnect
            )
        }
        OnboardingStep.Importing -> Unit
        is OnboardingStep.Done -> RoundedButton(
            text = mode.doneLabel,
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp),
            bgColor = MaterialTheme.colorScheme.primary,
            textColor = MaterialTheme.colorScheme.onPrimary,
            bold = true,
            onClick = onFinish
        )
    }
}
