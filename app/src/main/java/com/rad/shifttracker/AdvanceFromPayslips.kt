package com.rad.shifttracker

import android.content.Context
import android.util.Log
import java.time.LocalDate
import java.time.YearMonth
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

/**
 * Posebni obračuni stižu mejlom „Platni listic“ (ili u folder sa listićima) za PRETHODNI mesec rada:
 * akontacija ~5. („ZA MESEC 08/2026“, isplata 04.09) i prevoz ~15. Akontacija se upisuje pod
 * mesec ISPLATE (septembar), jer aplikacija od plate isplaćene u septembru (za avgust) oduzima
 * akontaciju upisanu pod septembar; iznos je ono što je uplaćeno na račun. Prevoz po danu se
 * uzima iz najnovijeg obračuna prevoza i više se ne unosi ručno.
 */

private const val SIDE_RUN_CHECK_PREFS = "rad_advance_check_prefs"
private const val KEY_TRANSPORT_WORK_MONTH = "transport_work_month"
private const val KEY_LAST_CHECK_MILLIS = "last_check_millis"
private const val KEY_LAST_FULL_CHECK_MILLIS = "last_full_check_millis"
private const val KEY_CONFIRMED_ADVANCE_MONTHS = "confirmed_advance_payment_months"
private const val SIDE_RUN_CHECK_INTERVAL_MS = 30 * 60 * 1000L
private const val DAILY_CHECK_INTERVAL_MS = 24 * 60 * 60 * 1000L

/**
 * Meseci rada čiji se obračuni traže: prošli (akontacija ~5., prevoz ~15.) i tekući (regres), a uz
 * njih i pretprošli kad akontacija za prošli mesec isplate nije potvrđena ([withPreviousAdvance]).
 */
internal fun sideRunCheckMonths(today: LocalDate, withPreviousAdvance: Boolean = false): List<MonthKey> {
    val current = YearMonth.from(today)
    val months = if (withPreviousAdvance) listOf(2L, 1L, 0L) else listOf(1L, 0L)
    return months.map { current.minusMonths(it) }.map { MonthKey(it.year, it.monthValue) }
}

/** Tekući i prošli mesec isplate: njihova akontacija mora da bude potvrđena obračunom iz mejla. */
internal fun advancePaymentMonthsToConfirm(today: LocalDate): List<MonthKey> {
    val current = YearMonth.from(today)
    return listOf(current.minusMonths(1), current).map { MonthKey(it.year, it.monthValue) }
}

/**
 * Meseci isplate čija akontacija još nije potvrđena obračunom: nije upisana, ili je ukucana ručno
 * pa je obračun iz mejla treba zameniti. [confirmed] = meseci isplate već viđeni u obračunu
 * (ili prošli mesec za koji je pretraga uspela a obračuna nema).
 */
internal fun unconfirmedAdvanceMonths(today: LocalDate, confirmed: Set<MonthKey>): List<MonthKey> =
    advancePaymentMonthsToConfirm(today).filter { it !in confirmed }

/**
 * Da li pri ulasku ima šta da se traži: akontacija za tekući ili prošli mesec isplate nije potvrđena
 * obračunom, ili prevoz nije uzet iz obračuna za prošli mesec rada
 * ([transportWorkMonth] = mesec rada poslednjeg primenjenog obračuna prevoza).
 */
internal fun sideRunCheckNeeded(
    today: LocalDate,
    transportWorkMonth: MonthKey?,
    confirmedAdvanceMonths: Set<MonthKey> = emptySet(),
    lastFullCheckMillis: Long = 0L,
    nowMillis: Long = System.currentTimeMillis()
): Boolean {
    val advanceMissing = unconfirmedAdvanceMonths(today, confirmedAdvanceMonths).isNotEmpty()
    val previousWorkMonth = sideRunCheckMonths(today).first()
    val transportMissing = transportWorkMonth == null || transportWorkMonth < previousWorkMonth
    return advanceMissing || transportMissing || dailyCheckDue(lastFullCheckMillis, nowMillis)
}

/**
 * Bilo šta može da stigne bilo kada (regres i van 2/5/8/11, dve rate, ispravka akontacije), pa se
 * pretraga ponavlja najviše jednom dnevno, bez obzira na vrstu obračuna.
 */
internal fun dailyCheckDue(lastFullCheckMillis: Long, nowMillis: Long): Boolean =
    nowMillis - lastFullCheckMillis !in 0 until DAILY_CHECK_INTERVAL_MS

/**
 * Pri svakom ulasku u aplikaciju: ako je sve već upisano ([sideRunCheckNeeded]), ništa; inače
 * traži posebne obračune — prvo Gmail, a ako Gmail nije povezan, nema ih tamo ili ne radi, folder
 * sa listićima — i upisuje akontaciju (ako je uključena) i prevoz po danu.
 * Radi mrežu i čita fajlove — zvati van glavne niti.
 */
internal fun runSideRunCheckOnEntry(context: Context, repo: ShiftRepository, today: LocalDate = LocalDate.now()) {
    val prefs = context.getSharedPreferences(SIDE_RUN_CHECK_PREFS, Context.MODE_PRIVATE)
    val transportWorkMonth = prefs.getString(KEY_TRANSPORT_WORK_MONTH, null)?.let(::parseMonthKeyOrNull)
    val confirmed = prefs.getStringSet(KEY_CONFIRMED_ADVANCE_MONTHS, emptySet()).orEmpty()
        .mapNotNull(::parseMonthKeyOrNull).toSet()
    val lastFullCheck = prefs.getLong(KEY_LAST_FULL_CHECK_MILLIS, 0L)
    val needed = sideRunCheckNeeded(today, transportWorkMonth, confirmed, lastFullCheck)
    Log.i(
        SIDE_RUN_LOG_TAG,
        "ulaz: potrebno=$needed gmail=${hasGmailPayslipAccess(context)} " +
            "prevozZa=$transportWorkMonth"
    )
    if (!needed) return
    // Dok obračun ne stigne (akontacija ~5.) ili ga tog meseca nema (prevoz), ne pretražuje se na svaki povratak.
    val now = System.currentTimeMillis()
    if (now - prefs.getLong(KEY_LAST_CHECK_MILLIS, 0L) in 0 until SIDE_RUN_CHECK_INTERVAL_MS) return
    prefs.edit().putLong(KEY_LAST_CHECK_MILLIS, now).apply()
    val unconfirmed = unconfirmedAdvanceMonths(today, confirmed)
    val previousPaymentMonth = advancePaymentMonthsToConfirm(today).first()
    val collected = collectSideRuns(context, sideRunCheckMonths(today, withPreviousAdvance = previousPaymentMonth in unconfirmed))
    applySideRuns(
        repo = repo,
        runs = collected.runs,
        onAdvanceChanged = { month, old, amount -> SideRunMessages.post(advanceRecordedText(month, old, amount)) },
        onTransportChanged = { old, new -> SideRunMessages.post(transportChangedText(old, new)) },
        onRegressChanged = { month, old, new -> SideRunMessages.post(regressChangedText(month, old, new)) }
    )
    rememberTransportWorkMonth(context, collected.runs)
    // Dnevna provera je obavljena samo ako je pretraga radila; inače se ponavlja na sledeći ulazak.
    if (!collected.gmailFailed) prefs.edit().putLong(KEY_LAST_FULL_CHECK_MILLIS, now).apply()
    // Potvrđeno: mesec isplate čiji je obračun viđen; prošli mesec i kad pretraga uspe bez obračuna
    // (tada ga i nema), da se ne traži na svaki ulazak.
    val seen = advanceTotalsByPaymentMonth(collected.runs).keys
    val searchWorked = !collected.gmailFailed
    val newlyConfirmed = unconfirmed.filter { it in seen || (searchWorked && it == previousPaymentMonth) }
    if (newlyConfirmed.isNotEmpty()) {
        val keep = advancePaymentMonthsToConfirm(today).toSet()
        val stored = (confirmed.filter { it in keep } + newlyConfirmed).map { "${it.year}-${it.month}" }.toSet()
        prefs.edit().putStringSet(KEY_CONFIRMED_ADVANCE_MONTHS, stored).apply()
    }
}
/** Pamti mesec rada najnovijeg primenjenog obračuna prevoza, da provera pri ulasku zna da ga ima. */
internal fun rememberTransportWorkMonth(context: Context, runs: List<PayslipParseResult.SideRun>) {
    val prefs = context.getSharedPreferences(SIDE_RUN_CHECK_PREFS, Context.MODE_PRIVATE)
    val month = latestTransportRun(runs)?.month ?: return
    val saved = prefs.getString(KEY_TRANSPORT_WORK_MONTH, null)?.let(::parseMonthKeyOrNull)
    if (saved == null || month > saved) {
        prefs.edit().putString(KEY_TRANSPORT_WORK_MONTH, "${month.year}-${month.month}").apply()
    }
}

private fun parseMonthKeyOrNull(text: String): MonthKey? {
    val (year, month) = text.split('-').mapNotNull(String::toIntOrNull).takeIf { it.size == 2 } ?: return null
    return if (month in 1..12) MonthKey(year, month) else null
}

internal data class CollectedSideRuns(val runs: List<PayslipParseResult.SideRun>, val gmailFailed: Boolean)

/**
 * Posebni obračuni za [months]: sve iz Gmail-a; folder sa listićima samo kad Gmail ne radi
 * (nije povezan, dozvola istekla, nema interneta). Ako Gmail radi, a obračuna nema, folder se ne gleda.
 */
internal fun collectSideRuns(context: Context, months: List<MonthKey>): CollectedSideRuns {
    var gmailFailed = false
    val runs = months.flatMap { month ->
        val gmail = if (hasGmailPayslipAccess(context)) {
            runPayslipSafely { findSideRunsInGmail(context, month) } ?: GmailSideRunsResult.Failed
        } else {
            GmailSideRunsResult.NoAccess
        }
        Log.i(SIDE_RUN_LOG_TAG, "${month.year}-${month.month} Gmail: ${gmail.logText()}")
        when (gmail) {
            is GmailSideRunsResult.Found -> return@flatMap gmail.runs
            GmailSideRunsResult.NotFound -> return@flatMap emptyList()
            GmailSideRunsResult.Failed -> gmailFailed = true
            GmailSideRunsResult.NoAccess -> Unit
        }
        emptyList()
    }
    return CollectedSideRuns(runs, gmailFailed)
}

internal const val SIDE_RUN_LOG_TAG = "RadObracuni"

private fun GmailSideRunsResult.logText(): String = when (this) {
    is GmailSideRunsResult.Found -> runs.joinToString { payslipResultDiagnosticText(it) }
    GmailSideRunsResult.NotFound -> "nema posebnih obračuna"
    GmailSideRunsResult.NoAccess -> "nema pristupa"
    is GmailSideRunsResult.Failed -> "greška"
}

/**
 * Upis iz posebnih obračuna: akontacija po mesecu isplate (ako je auto akontacija uključena;
 * [onAdvanceChanged] za svaki promenjen mesec) i prevoz po danu iz najnovijeg obračuna prevoza.
 */
internal fun applySideRuns(
    repo: ShiftRepository,
    runs: List<PayslipParseResult.SideRun>,
    /** Mesec isplate, stara (0 = nije bila upisana) i nova akontacija, kad se promeni. */
    onAdvanceChanged: (MonthKey, Double, Double) -> Unit = { _, _, _ -> },
    /** Stari i novi prevoz po danu, kad se promeni. */
    onTransportChanged: (Double, Double) -> Unit = { _, _ -> },
    /** Mesec, stari i novi regres, kad obračun regresa pokaže drugi iznos. */
    onRegressChanged: (MonthKey, Double, Double) -> Unit = { _, _, _ -> }
) {
    for ((paymentMonth, amount) in advanceTotalsByPaymentMonth(runs)) {
        val old = repo.getMonthlyNetAdjustments(paymentMonth.year, paymentMonth.month).advancePaymentNet
        if (repo.recordAdvancePaymentFromPayslip(paymentMonth, amount)) onAdvanceChanged(paymentMonth, old, amount)
    }
    latestTransportDaily(runs)?.let { daily ->
        val old = repo.transportDailyAmountValue
        if (kotlin.math.abs(old - daily) >= 0.005) {
            repo.transportDailyAmountValue = daily
            onTransportChanged(old, daily)
        }
    }
    for ((month, amount) in regressTotalsByMonth(runs).toSortedMap()) {
        val old = repo.getRegressNetForMonth(month)
        if (repo.recordRegressFromPayslip(month, amount)) onRegressChanged(month, old, amount)
    }
}

internal const val SIDE_RUN_MESSAGES_TITLE = "Iz Gmail-a"

/** Nova akontacija, ili ispravka ručno ukucane ([old] > 0) na iznos iz obračuna. */
internal fun advanceRecordedText(month: MonthKey, old: Double, amount: Double): String =
    if (old > 0.0) {
        "Akontacija ispravljena: ${formatMoney2(old)} → ${formatMoney2(amount)} ${CONFIG.pay.currency} " +
            "za isplatu u ${monthYearText(month)} (iz obračuna)."
    } else {
        "Akontacija upisana: ${formatMoney2(amount)} ${CONFIG.pay.currency} za isplatu u ${monthYearText(month)}."
    }

internal fun regressChangedText(month: MonthKey, old: Double, new: Double): String =
    "Regres promenjen: ${formatMoney2(old)} → ${formatMoney2(new)} ${CONFIG.pay.currency} (od ${monthYearText(month)})."

internal fun transportChangedText(old: Double, new: Double): String =
    "Prevoz po danu promenjen: ${formatMoney2(old)} → ${formatMoney2(new)} ${CONFIG.pay.currency} (iz obračuna prevoza)."

/**
 * Poruke o upisu iz obračuna — prikazuju se u aplikaciji (ne kao obaveštenje na telefonu):
 * provera pri ulasku ih dodaje, ekran ih prikaže i obriše kad se pročitaju.
 */
internal object SideRunMessages {
    private val _pending = MutableStateFlow<List<String>>(emptyList())
    val pending: StateFlow<List<String>> = _pending

    fun post(message: String) = _pending.update { it + message }

    fun clear() {
        _pending.value = emptyList()
    }
}

