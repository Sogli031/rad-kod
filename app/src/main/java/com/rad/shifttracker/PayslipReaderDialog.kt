package com.rad.shifttracker

import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate

/**
 * „Lista“: listić meseca otvorenog u kalendaru i poređenje sa danima upisanim u tom mesecu.
 * Listić se učitava jednom (PDF) i pamti po mesecu u podacima aplikacije (i u backup-u).
 * Listić za drugi mesec se odbija. Ono čega na listiću nema (otežani uslovi, prevoz) je u Opcijama.
 */
@Composable
internal fun PayslipReaderDialog(repo: ShiftRepository, month: MonthKey, repoTick: Int, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var payslip by remember(month) { mutableStateOf(savedPayslipForMonth(repo, PayslipSnapshotStore(context), month)) }
    var isReading by remember { mutableStateOf(false) }
    var message by remember(month) { mutableStateOf<Pair<String, Boolean>?>(null) }
    var confirmDelete by remember { mutableStateOf(false) }
    // Posle upisa izmena provera obračuna se računa ponovo.
    var revision by remember { mutableStateOf(0) }
    // Sačuvan listić se otvara svaki put: greška u poređenju samo sakriva tabelu, ne ruši „Listu“.
    val reconciliation = remember(payslip, repoTick, revision) {
        payslip?.let { runPayslipSafely { buildPayslipReconciliation(repo, it) } }
    }
    val monthFillOffer = remember(payslip, repoTick, revision) {
        if (payslip == null) MonthFillOffer.NONE else monthFillOffer(repo, month, reconciliation)
    }

    // Listić skeniran ranije (samo „poslednji listić“): prebaci ga u trajno čuvanje po mesecu.
    LaunchedEffect(payslip) {
        payslip?.let { current -> if (repo.getSavedPayslip(month.year, month.month) == null) repo.savePayslip(current) }
    }

    val folderScan = rememberFolderPayslipScan(month, missing = payslip == null)

    // Listić (nađen u folderu ili izabran) se čuva odmah, sa svim predloženim izmenama — bez pregleda.
    fun offer(result: PayslipParseResult) {
        when (val outcome = safePayslipPickOutcome(repo, result, month)) {
            is PayslipPickOutcome.Rejected -> message = outcome.text to true
            is PayslipPickOutcome.Ready -> {
                val changes = outcome.plan.changes
                message = payslipSavedText(replaced = payslip != null, changeCount = changes.size) to false
                applyPayslipChanges(repo, changes)
                repo.savePayslip(outcome.payslip)
                payslip = outcome.payslip
                revision++
            }
        }
    }

    // OpenDocument = Storage Access Framework: bez dozvola za skladište.
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        isReading = true
        message = null
        scope.launch {
            val result = withContext(Dispatchers.IO) { readPayslip(context, uri) }
            isReading = false
            offer(result)
        }
    }
    val pickPdf = { if (!isReading) picker.launch(arrayOf("application/pdf")) }

    PopupDialog(
        title = "Lista · ${monthYearText(month)}",
        onDismiss = onDismiss,
        scrollable = true
    ) {
        val current = payslip
        if (current == null) {
            MissingPayslipSection(
                month = month,
                scan = folderScan,
                isReading = isReading,
                onLoadFound = { hit ->
                    message = null
                    offer(hit.result)
                },
                onPick = pickPdf
            )
        } else {
            SectionLabel(text = "Lista")
            PopupCard {
                LastPayslipValues(current)
            }
        }
        ReaderMessage(message)

        if (current != null && monthFillOffer != MonthFillOffer.NONE) {
            PayslipMonthFillButton(
                repo = repo,
                payslip = current,
                replace = monthFillOffer == MonthFillOffer.REPLACE
            ) { filled ->
                revision++
                message = "${monthYearText(filled)} je popunjen po listi." to false
            }
        }

        reconciliation?.let { check ->
            SectionLabel(text = "Poređenje sa upisanim danima")
            val (text, color) = payslipCompareVerdict(check, MaterialTheme.extendedColors)
            ReaderText(text, color = color)
            PayslipReconciliationCard(check)
        }

        // Ispravljen obračun (npr. rb.3 posle rb.2): ista provera meseca, pa zamena odmah.
        if (current != null) {
            PayslipFileActions(
                isReading = isReading,
                onReplace = pickPdf,
                onDelete = { confirmDelete = true }
            )
        }
    }

    if (confirmDelete) {
        PayslipDeleteDialog(
            month = month,
            onConfirm = {
                deletePayslipForMonth(repo, PayslipSnapshotStore(context), month)
                confirmDelete = false
                payslip = null
                revision++
                message = "Lista za ${monthYearText(month)} je obrisana." to false
            },
            onDismiss = { confirmDelete = false }
        )
    }
}

internal fun payslipSavedText(replaced: Boolean, changeCount: Int): String =
    "Lista je ${if (replaced) "zamenjena" else "sačuvana"} ($changeCount izmena podešavanja)."

internal const val PAYSLIP_UNEXPECTED_ERROR_TEXT =
    "Lista nije mogla da se obradi (neočekivana greška). Ništa nije promenjeno — pokušaj ponovo."

internal sealed interface PayslipPickOutcome {
    data class Rejected(val text: String) : PayslipPickOutcome
    data class Ready(val payslip: ParsedPayslip, val plan: PayslipImportPlan) : PayslipPickOutcome
}

/** [payslipPickOutcome] koji umesto pada daje poruku o neočekivanoj grešci. */
internal fun safePayslipPickOutcome(repo: ShiftRepository, result: PayslipParseResult, month: MonthKey): PayslipPickOutcome =
    runPayslipSafely { payslipPickOutcome(repo, result, month) }
        ?: PayslipPickOutcome.Rejected(PAYSLIP_UNEXPECTED_ERROR_TEXT)

/**
 * Izabrani PDF: nečitljiv ili za drugi mesec se odbija; inače plan izmena za potvrdu.
 * Stariji listić od najnovijeg sačuvanog ne predlaže globalna podešavanja (koeficijent, porez, staž).
 */
internal fun payslipPickOutcome(repo: ShiftRepository, result: PayslipParseResult, month: MonthKey): PayslipPickOutcome {
    val scanned = when (result) {
        is PayslipParseResult.Failure -> return PayslipPickOutcome.Rejected(payslipImportErrorText(result.reason).orEmpty())
        is PayslipParseResult.Success -> result.payslip
        is PayslipParseResult.SideRun -> return PayslipPickOutcome.Rejected(payslipSideRunPickText(result))
    }
    payslipWrongMonthError(scanned.month, month)?.let { return PayslipPickOutcome.Rejected(it) }
    val latest = repo.getLatestSavedPayslipMonth()
    val plan = buildPayslipImportPlan(
        repo = repo,
        parsed = listOf(scanned),
        failures = emptyList(),
        includeGlobalSettings = latest == null || scanned.month >= latest
    )
    return PayslipPickOutcome.Ready(scanned, plan)
}

/** Listić meseca iz podataka aplikacije; stariji „poslednji listić“ važi ako je za isti mesec. */
internal fun savedPayslipForMonth(repo: ShiftRepository, store: PayslipSnapshotStore, month: MonthKey): ParsedPayslip? =
    repo.getSavedPayslip(month.year, month.month) ?: store.load()?.takeIf { it.month == month }

/**
 * Briše listić meseca iz podataka aplikacije i stari „poslednji listić“ za isti mesec,
 * da ga [savedPayslipForMonth] ne bi vratio. Podešavanja upisana sa listića ostaju.
 */
internal fun deletePayslipForMonth(repo: ShiftRepository, store: PayslipSnapshotStore, month: MonthKey) {
    repo.deletePayslip(month)
    if (store.load()?.month == month) store.clear()
}

internal const val PAYSLIP_FOLDER_NO_ACCESS_TEXT =
    "Uključi „Pristup fajlovima“ u Opcijama — onda se lista sama nađe u folderu."

internal fun payslipNotInFolderText(folder: String, month: MonthKey): String =
    "U folderu $folder nema liste za ${monthYearText(month)}."

/** Stanje pretrage foldera sa listićima za mesec bez listića. */
internal data class FolderPayslipScan(
    val found: FoundPayslip? = null,
    val text: String? = null,
    val isScanning: Boolean = false
)

/**
 * Nema listića za mesec: Gmail, ako je povezan. Folder sa listićima (Opcije) samo kad Gmail ne radi
 * (nije povezan, dozvola istekla, nema interneta) — ako Gmail radi, a listića nema, to je odgovor.
 */
@Composable
private fun rememberFolderPayslipScan(month: MonthKey, missing: Boolean): FolderPayslipScan {
    val context = LocalContext.current
    var scan by remember(month) { mutableStateOf(FolderPayslipScan()) }
    LaunchedEffect(month, missing) {
        if (!missing) {
            scan = FolderPayslipScan()
            return@LaunchedEffect
        }
        val gmailText = if (hasGmailPayslipAccess(context)) {
            scan = FolderPayslipScan(isScanning = true, text = GMAIL_SCANNING_TEXT)
            val gmail = withContext(Dispatchers.IO) {
                runPayslipSafely { findPayslipInGmail(context, month) } ?: GmailPayslipResult.Failed
            }
            when (gmail) {
                is GmailPayslipResult.Found -> {
                    scan = FolderPayslipScan(found = gmail.payslip.copy(fileName = "Gmail · ${gmail.payslip.fileName}"))
                    return@LaunchedEffect
                }
                GmailPayslipResult.NotFound -> {
                    scan = FolderPayslipScan(text = payslipNotArrivedText(month))
                    return@LaunchedEffect
                }
                GmailPayslipResult.NoAccess -> GMAIL_NO_ACCESS_TEXT
                GmailPayslipResult.Failed -> GMAIL_FAILED_TEXT
            }
        } else {
            null
        }
        val folderText = if (hasPayslipFolderAccess(context)) {
            val folder = PayslipFolderSettings(context).folder
            scan = FolderPayslipScan(isScanning = true)
            val hit = withContext(Dispatchers.IO) {
                runPayslipSafely { findPayslipInFolder(context, payslipFolderFile(folder), month) }
            }
            if (hit != null) {
                scan = FolderPayslipScan(found = hit)
                return@LaunchedEffect
            }
            payslipNotInFolderText(folder, month)
        } else {
            PAYSLIP_FOLDER_NO_ACCESS_TEXT
        }
        scan = FolderPayslipScan(text = listOfNotNull(gmailText, folderText).joinToString("\n"))
    }
    return scan
}

private const val GMAIL_SCANNING_TEXT = "Tražim listu u Gmail-u..."
private const val GMAIL_NO_ACCESS_TEXT = "Gmail dozvola je istekla — poveži Gmail ponovo u Opcijama."
private const val GMAIL_FAILED_TEXT = "Gmail trenutno nije dostupan (nema interneta?). Pokušaj ponovo."

@Composable
private fun MissingPayslipSection(
    month: MonthKey,
    scan: FolderPayslipScan,
    isReading: Boolean,
    onLoadFound: (FoundPayslip) -> Unit,
    onPick: () -> Unit
) {
    PopupCard {
        ReaderText(payslipMissingText(month))
    }
    val hit = scan.found
    if (hit != null) {
        PopupCard {
            ReaderText("Nađena lista za ${monthYearText(month)}", color = MaterialTheme.extendedColors.success)
            ReaderText(hit.fileName)
        }
    }
    GlowButton(
        text = when {
            hit != null -> "Učitaj nađenu listu"
            isReading -> "Čitam listu..."
            else -> "Učitaj listu za ${monthYearText(month)} (PDF)"
        },
        modifier = Modifier
            .fillMaxWidth()
            .height(50.dp),
        textStyle = MaterialTheme.typography.labelLarge,
        onClick = { if (hit != null) onLoadFound(hit) else onPick() }
    )
    if (hit != null) {
        RoundedButton(
            text = if (isReading) "Čitam listu..." else "Izaberi drugi PDF",
            modifier = Modifier
                .fillMaxWidth()
                .height(UI.buttonHeight.dp),
            bgColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            textColor = MaterialTheme.colorScheme.onSurface,
            borderColor = MaterialTheme.colorScheme.outlineVariant,
            textStyle = MaterialTheme.typography.labelMedium,
            onClick = onPick
        )
    }
    val scanText = if (scan.isScanning) scan.text ?: "Tražim listu u folderu..." else scan.text
    scanText?.let { ReaderText(it) }
}

/** Poruka posle radnje: greška crveno, uspeh zeleno. */
@Composable
private fun ReaderMessage(message: Pair<String, Boolean>?) {
    val (text, isError) = message ?: return
    if (isError) {
        FormErrorText(message = text, style = MaterialTheme.typography.bodySmall, maxLines = 5)
    } else {
        ReaderText(text, color = MaterialTheme.extendedColors.success, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
    }
}

private val PAYSLIP_FILE_ACTION_HEIGHT = 56.dp

/** „Zameni listu“ i „Obriši listu“ jedno pored drugog. */
@Composable
private fun PayslipFileActions(isReading: Boolean, onReplace: () -> Unit, onDelete: () -> Unit) {
    // Dugmad se rastežu celom visinom reda (bez fillMaxHeight ostaju visoka koliko tekst).
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(PAYSLIP_FILE_ACTION_HEIGHT),
        horizontalArrangement = Arrangement.spacedBy(Spacing.sm)
    ) {
        RoundedButton(
            text = if (isReading) "Čitam listu..." else "Zameni listu",
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight(),
            bgColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            textColor = MaterialTheme.colorScheme.onSurface,
            borderColor = MaterialTheme.colorScheme.outlineVariant,
            textStyle = MaterialTheme.typography.labelLarge,
            bold = true,
            onClick = onReplace
        )
        RoundedButton(
            text = "Obriši listu",
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight(),
            bgColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            textColor = MaterialTheme.colorScheme.error,
            borderColor = MaterialTheme.colorScheme.error,
            textStyle = MaterialTheme.typography.labelLarge,
            bold = true,
            onClick = onDelete
        )
    }
}

@Composable
private fun PayslipDeleteDialog(month: MonthKey, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    PopupDialog(
        title = "Brisanje liste",
        onDismiss = onDismiss
    ) {
        PopupCard {
            Text(
                text = "Obrisati listu za ${monthYearText(month)}?",
                color = MaterialTheme.colorScheme.onSurface,
                style = MaterialTheme.typography.bodyMedium
            )
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp),
            horizontalArrangement = Arrangement.spacedBy(Spacing.sm)
        ) {
            RoundedButton(
                text = "Otkaži",
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight(),
                bgColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                textColor = MaterialTheme.colorScheme.onSurface,
                borderColor = MaterialTheme.colorScheme.outlineVariant,
                textStyle = MaterialTheme.typography.labelLarge,
                bold = true,
                onClick = onDismiss
            )
            GlowButton(
                text = "Obriši",
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight(),
                destructive = true,
                textStyle = MaterialTheme.typography.labelLarge,
                onClick = onConfirm
            )
        }
    }
}

internal fun payslipMissingText(month: MonthKey): String =
    "Za ${monthYearText(month)} još nije učitana lista."

internal fun payslipNotArrivedText(month: MonthKey): String =
    "Lista za ${monthYearText(month)} još nije stigla."

/** Lista za mesec stiže tek sledećeg meseca, pa za tekući i budući mesec ne može da postoji. */
internal fun isPayslipMonthUnfinished(month: MonthKey, today: LocalDate = LocalDate.now()): Boolean =
    month >= MonthKey(today.year, today.monthValue)

/** Listić mora biti za mesec otvoren u kalendaru; inače greška i ništa se ne učitava. */
internal fun payslipSideRunPickText(run: PayslipParseResult.SideRun): String {
    val what = if (run.kind == PayslipSideRunKind.OTHER) "poseban obračun" else "obračun ${run.kind.label}"
    val advanceNote = if (run.kind == PayslipSideRunKind.ADVANCE) " Akontacija se upisuje sama pri pokretanju." else ""
    return "Ovo je $what za ${monthYearText(run.month)}, ne lista (nema „konačna“).$advanceNote Lista stiže oko 20."
}

internal fun payslipWrongMonthError(scanned: MonthKey, selected: MonthKey): String? =
    if (scanned == selected) {
        null
    } else {
        "Pogrešna lista: izabrani PDF je za ${monthYearText(scanned)}, a otvoren je ${monthYearText(selected)}. " +
            "Lista nije učitana — otvori ${monthYearText(scanned)} u kalendaru pa je učitaj tamo."
    }

/** Broje se samo stvarne stavke; bruto, neto i za isplatu su zbirovi i ne ulaze u neslaganje. */
internal fun payslipCompareVerdict(check: PayslipReconciliation, colors: ExtendedColors): Pair<String, Color> {
    val mismatches = check.rows.count { !it.isTotal && !it.matches }
    return when {
        !check.hasAppData -> "U ovom mesecu nema upisanih dana." to colors.warning
        mismatches == 0 -> "Sve se slaže sa listom." to colors.success
        else -> "${payslipMismatchCountText(mismatches)} (označeno sa ✗)." to colors.warning
    }
}

/** 1 stavka se ne slaže / 2–4 stavke se ne slažu / 5+ stavki se ne slaže (21 stavka, 22 stavke...). */
internal fun payslipMismatchCountText(count: Int): String {
    val lastTwo = count % 100
    val last = count % 10
    return when {
        last == 1 && lastTwo != 11 -> "$count stavka se ne slaže"
        last in 2..4 && lastTwo !in 12..14 -> "$count stavke se ne slažu"
        else -> "$count stavki se ne slaže"
    }
}

@Composable
internal fun LastPayslipValues(payslip: ParsedPayslip) {
    val currency = CONFIG.pay.currency
    ReaderValueRow("Lista", monthYearText(payslip.month) + (payslip.runLabel?.let { " · $it" } ?: ""))
    payslip.coefficient?.let { ReaderValueRow("Koeficijent", formatSerbianDecimal(it)) }
    payslip.hourlyValue?.let { ReaderValueRow("Vrednost sata", "${formatSerbianDecimal(it, minFraction = 2)} $currency") }
    payslip.fundHours?.let { ReaderValueRow("Fond sati", "$it h") }
    payslip.seniorityPercent?.let { percent ->
        val years = payslip.seniorityYears?.let { " ($it god.)" }.orEmpty()
        ReaderValueRow("Minuli rad", "${formatSerbianDecimal(percent)}%$years")
    }
    payslip.employeeContributionsPercent?.let { ReaderValueRow("Doprinosi radnika", "${formatSerbianDecimal(it)}%") }
    payslip.taxReliefRsd?.let { ReaderValueRow("Poreska olakšica", "${formatSerbianDecimal(it)} $currency") }
    payslip.taxPercent?.let { ReaderValueRow("Porez", "${formatSerbianDecimal(it)}%") }
    when (val base = payslip.payBase) {
        is PayslipPayBaseResult.Success -> {
            ReaderValueRow("Osnovica naknada", formatPayBaseRateText(base.chosen.hourlyBase.toDouble()))
            ReaderText("${payslipSourceText(base.chosen)}: ${payslipFormulaText(base.chosen)}")
        }
        else -> ReaderText(payslipImportErrorText(base).orEmpty())
    }
    payslip.grossTotal?.let { ReaderValueRow("Bruto ukupno", "${formatSerbianDecimal(it, minFraction = 2)} $currency") }
    payslip.netTotal?.let { ReaderValueRow("Neto ukupno", "${formatSerbianDecimal(it, minFraction = 2)} $currency") }
}

@Composable
private fun ReaderValueRow(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
        Text(
            text = label,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodySmall,
            maxLines = 1,
            modifier = Modifier.weight(1f)
        )
        Text(
            text = value,
            color = MaterialTheme.colorScheme.onSurface,
            style = MaterialTheme.typography.bodySmall,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
internal fun ReaderText(text: String, color: Color = MaterialTheme.colorScheme.onSurfaceVariant, modifier: Modifier = Modifier) {
    Text(
        text = text,
        color = color,
        modifier = modifier,
        style = MaterialTheme.typography.bodySmall,
        maxLines = 5,
        overflow = TextOverflow.Ellipsis
    )
}
