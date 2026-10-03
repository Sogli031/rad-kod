package com.rad.shifttracker

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.Settings
import java.io.File

/**
 * Folder u kome se traže PDF listići (npr. Download), da „Lista“ sama ponudi listić meseca.
 * Čita se preko posebnog pristupa „Pristup svim fajlovima“ (kao „Pristup obaveštenjima“):
 * uključuje se jednom u podešavanjima telefona.
 */
internal const val PAYSLIP_FOLDER_SECTION_LABEL = "Folder sa listama"
internal const val PAYSLIP_FOLDER_ACCESS_LABEL = "Pristup fajlovima"
internal const val PAYSLIP_FOLDER_PICK_LABEL = "Izaberi folder"
internal const val PAYSLIP_FOLDER_DEFAULT = "Download"

/**
 * Uobičajeno ime listića: `MM 12 GG_<ID radnika>_<ime>.pdf`, godina napisana obrnuto — „081262“ =
 * avgust 2026, „081242“ = avgust 2024. Na ime se NE oslanja: čita se svaki PDF iz foldera, a mesec i
 * godinu određuje samo sadržaj. Ime samo pomera verovatne fajlove napred, da se nađu brže.
 */
private val PAYSLIP_FILE_NAME = Regex("""^(\d{2})\d{2}(\d)(\d)_.*\.pdf$""", RegexOption.IGNORE_CASE)

/** Najviše toliko PDF-ova se čita po otvaranju „Liste“ (Download ume da bude pun). */
private const val MAX_SCANNED_PAYSLIP_FILES = 30

/** Listić ima ~30 KB; veći PDF-ovi u folderu (računi, knjige...) se ni ne otvaraju. */
private const val MAX_FOLDER_PAYSLIP_BYTES = 1024L * 1024L

private const val PAYSLIP_FOLDER_PREFS = "payslip_folder"
private const val KEY_FOLDER = "folder"

/** Podešavanje važi samo za ovaj telefon (putanja), pa ne ide u backup. */
internal class PayslipFolderSettings(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PAYSLIP_FOLDER_PREFS, Context.MODE_PRIVATE)

    var folder: String
        get() = normalizePayslipFolder(prefs.getString(KEY_FOLDER, null))
        set(value) {
            prefs.edit().putString(KEY_FOLDER, normalizePayslipFolder(value)).apply()
        }
}

/** Putanja relativna u odnosu na memoriju telefona („Download/Liste“); prazno = Download. */
internal fun normalizePayslipFolder(input: String?): String =
    input.orEmpty()
        .trim()
        .replace('\\', '/')
        .trim('/')
        .replace(Regex("/+"), "/")
        .ifEmpty { PAYSLIP_FOLDER_DEFAULT }

@Suppress("DEPRECATION") // Putanja do memorije telefona; čita se uz „Pristup svim fajlovima“.
internal fun payslipFolderFile(folder: String): File =
    File(Environment.getExternalStorageDirectory(), normalizePayslipFolder(folder))

/**
 * Folder izabran u sistemskom biraču → relativna putanja („primary:Download/Liste“ → „Download/Liste“).
 * Folder na SD kartici se ne podržava (null).
 */
internal fun payslipFolderFromTreeUri(uri: Uri): String? =
    runCatching { DocumentsContract.getTreeDocumentId(uri) }.getOrNull()?.let(::payslipFolderFromTreeDocumentId)

internal fun payslipFolderFromTreeDocumentId(documentId: String): String? {
    val volume = documentId.substringBefore(':')
    if (!volume.equals("primary", ignoreCase = true) || !documentId.contains(':')) return null
    return normalizePayslipFolder(documentId.substringAfter(':'))
}

/** Šta ime listića kaže: mesec i poslednje dve cifre godine. */
internal data class PayslipFileHint(val month: Int, val yearSuffix: Int)

internal fun payslipFileHint(fileName: String): PayslipFileHint? =
    PAYSLIP_FILE_NAME.find(fileName)?.let { match ->
        val (month, yearOnes, yearTens) = match.destructured
        PayslipFileHint(month.toInt(), (yearTens + yearOnes).toInt())
    }

/**
 * Svi (mali) PDF-ovi iz foldera, redom kojim se čitaju: prvo oni čije ime liči na listić tog
 * meseca, pa na listić te godine, pa svi ostali; u okviru toga najnoviji prvi.
 */
internal fun payslipFileCandidates(files: List<File>, month: MonthKey): List<File> =
    files.filter { it.isFile && it.name.endsWith(".pdf", ignoreCase = true) && it.length() <= MAX_FOLDER_PAYSLIP_BYTES }
        .sortedWith(
            compareByDescending<File> { payslipFileRank(payslipFileHint(it.name), month) }
                .thenByDescending { it.lastModified() }
        )
        .take(MAX_SCANNED_PAYSLIP_FILES)

internal fun payslipFileRank(hint: PayslipFileHint?, month: MonthKey): Int {
    val sameYear = hint != null && hint.yearSuffix == month.year % 100
    return when {
        sameYear && hint?.month == month.month -> 2
        sameYear -> 1
        else -> 0
    }
}

/** Listić nađen u folderu: ime fajla i pročitan listić (mesec i godina provereni iz sadržaja). */
internal data class FoundPayslip(val fileName: String, val result: PayslipParseResult.Success)

/**
 * Traži listić za [month] u folderu. PDF se prihvata samo kad je listić i kad u sadržaju
 * ima isti mesec i godinu; ime fajla samo bira redosled.
 */
internal fun findPayslipInFolder(context: Context, folder: File, month: MonthKey): FoundPayslip? {
    val files = runCatching { folder.listFiles()?.toList() }.getOrNull() ?: return null
    for (file in payslipFileCandidates(files, month)) {
        val result = readPayslip(context, file)
        if (result is PayslipParseResult.Success && result.payslip.month == month) {
            return FoundPayslip(file.name, result)
        }
    }
    return null
}

/**
 * Posebni obračuni (akontacija, prevoz, regres) za mesec rada [month], svaki jednom: ista kopija
 * u Gmail-u i folderu ili dvaput preuzet PDF se ne broji dvaput. Dva različita obračuna iste
 * vrste (i bez pročitanog rb.) ostaju oba.
 */
internal fun sideRuns(results: Sequence<PayslipParseResult>, month: MonthKey): List<PayslipParseResult.SideRun> =
    results.filterIsInstance<PayslipParseResult.SideRun>()
        .filter { it.month == month }
        .distinct()
        .toList()

/**
 * Akontacija po mesecu ISPLATE (aplikacija od plate isplaćene u mesecu X oduzima akontaciju
 * upisanu pod X): zbir onoga što je uplaćeno na račun po obračunima akontacije. Mesec isplate je
 * iz „Datum isplate“, a bez njega mesec posle meseca rada. Regres i prevoz ne ulaze — obračun
 * akontacije ih samo prenosi kao „isplaćeno“. Obračun bez pročitanog iznosa se preskače.
 */
internal fun advanceTotalsByPaymentMonth(runs: List<PayslipParseResult.SideRun>): Map<MonthKey, Double> =
    runs.filter { it.kind == PayslipSideRunKind.ADVANCE && (it.paidNet?.signum() ?: 0) > 0 }
        .groupBy(::advancePaymentMonth)
        .mapValues { (_, group) -> group.sumOf { it.paidNet!!.toDouble() } }

/**
 * Regres po mesecu rada („ZA MESEC“ — za 08/2026 isplaćen 28.08): zbir onoga što je uplaćeno
 * po obračunima regresa. Obračun bez pročitanog iznosa se preskače.
 */
internal fun regressTotalsByMonth(runs: List<PayslipParseResult.SideRun>): Map<MonthKey, Double> =
    runs.filter { it.kind == PayslipSideRunKind.REGRESS && (it.paidNet?.signum() ?: 0) > 0 }
        .groupBy { it.month }
        .mapValues { (_, group) -> group.sumOf { it.paidNet!!.toDouble() } }

/** Mesec isplate obračuna: iz „Datum isplate“, a bez njega mesec posle meseca rada. */
internal fun advancePaymentMonth(run: PayslipParseResult.SideRun): MonthKey = run.paymentMonth ?: nextMonthKey(run.month)

/** Najnoviji obračun prevoza sa pročitanim iznosom po danu (po mesecu rada, pa po rb.). */
internal fun latestTransportRun(runs: List<PayslipParseResult.SideRun>): PayslipParseResult.SideRun? =
    runs.filter { it.kind == PayslipSideRunKind.TRANSPORT && (it.transportDaily?.signum() ?: 0) > 0 }
        .maxWithOrNull(compareBy<PayslipParseResult.SideRun> { it.month }.thenBy { payslipRunNumber(it.runLabel) })

/** Prevoz po danu iz najnovijeg obračuna prevoza. */
internal fun latestTransportDaily(runs: List<PayslipParseResult.SideRun>): Double? =
    latestTransportRun(runs)?.transportDaily?.toDouble()

private fun nextMonthKey(month: MonthKey): MonthKey {
    val next = java.time.YearMonth.of(month.year, month.month).plusMonths(1)
    return MonthKey(next.year, next.monthValue)
}

/** Posebni obračuni za mesec rada [month] iz foldera sa listićima. */
internal fun findSideRunsInFolder(context: Context, folder: File, month: MonthKey): List<PayslipParseResult.SideRun> {
    val files = runCatching { folder.listFiles()?.toList() }.getOrNull() ?: return emptyList()
    return sideRuns(payslipFileCandidates(files, month).asSequence().map { readPayslip(context, it) }, month)
}

fun hasPayslipFolderAccess(context: Context): Boolean =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        Environment.isExternalStorageManager()
    } else {
        context.checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
    }

/** Ekran „Pristup svim fajlovima“ za ovu aplikaciju (na Androidu 10 i starijem: dozvole aplikacije). */
fun openPayslipFolderAccessSettings(context: Context) {
    val packageUri = Uri.parse("package:${context.packageName}")
    val intents = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        listOf(
            Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, packageUri),
            Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
        )
    } else {
        listOf(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, packageUri))
    }
    for (intent in intents) {
        if (runCatching { context.startActivity(intent) }.isSuccess) return
    }
}

internal fun payslipFolderAccessStatusText(hasAccess: Boolean): String =
    if (hasAccess) {
        "Uključen"
    } else {
        "Isključen"
    }
