package com.rad.shifttracker

import android.accounts.Account
import android.accounts.AccountManager
import android.content.Context
import android.content.Intent
import android.util.Log
import com.google.android.gms.auth.GoogleAuthException
import com.google.android.gms.auth.GoogleAuthUtil
import com.google.android.gms.auth.UserRecoverableAuthException
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.common.api.Scope
import com.google.api.client.googleapis.extensions.android.gms.auth.GoogleAccountCredential
import com.google.api.client.googleapis.extensions.android.gms.auth.GoogleAuthIOException
import com.google.api.client.googleapis.json.GoogleJsonResponseException
import com.google.api.client.http.javanet.NetHttpTransport
import com.google.api.client.json.gson.GsonFactory
import com.google.api.services.gmail.Gmail
import com.google.api.services.gmail.GmailScopes
import com.google.api.services.gmail.model.MessagePart
import java.time.YearMonth
import java.util.Base64

/**
 * Listići stižu mejlom („Obracun zarada i naknada zarada“, PDF u prilogu). Kad „Lista“ ne nađe
 * listić u folderu, pita Gmail: prilozi se preuzimaju samo u memoriju i čitaju isto kao PDF iz
 * foldera — mesec i godinu određuje sadržaj, ime priloga samo redosled.
 */
internal const val GMAIL_PAYSLIP_SECTION_LABEL = "Liste iz Gmail-a"
internal const val GMAIL_PAYSLIP_CONNECT_LABEL = "Poveži Gmail"
internal const val GMAIL_PAYSLIP_CHANGE_LABEL = "Promeni mejl"

private const val GMAIL_PAYSLIP_SUBJECT = "Obracun zarada"
private const val GMAIL_PAYSLIP_SENDER = "platnilistic@vbs.co.rs"
private const val GMAIL_USER = "me"
/** Stiže 3–5 mejlova mesečno (akontacija, konačni, „p“, „-R“), a prozor je 3 meseca. */
private const val MAX_GMAIL_MESSAGES = 30L

/** Listić ima ~30 KB; veći prilozi se ni ne preuzimaju. */
private const val MAX_GMAIL_ATTACHMENT_BYTES = 1024 * 1024

/** Listić za mesec stiže narednih meseci (ispravke rb.2, rb.3...), pa se gleda prozor od 3 meseca. */
private const val GMAIL_SEARCH_MONTHS = 3L

private val GMAIL_SCOPE = Scope(GmailScopes.GMAIL_READONLY)
private const val GMAIL_TOKEN_SCOPE = "oauth2:${GmailScopes.GMAIL_READONLY}"

private const val GMAIL_PREFS_NAME = "rad_gmail_payslip_prefs"
private const val KEY_GMAIL_ACCOUNT = "account_email"

/**
 * Nalog iz kog se čitaju listići. Bira se posebno od backup naloga (GoogleSignIn se ne dira,
 * pa backup ostaje na svom nalogu); dok se ne izabere, koristi se backup nalog ako ima Gmail dozvolu.
 */
internal fun gmailPayslipAccount(context: Context): Account? {
    val saved = context.getSharedPreferences(GMAIL_PREFS_NAME, Context.MODE_PRIVATE)
        .getString(KEY_GMAIL_ACCOUNT, null)
    if (!saved.isNullOrBlank()) return Account(saved, GoogleAuthUtil.GOOGLE_ACCOUNT_TYPE)
    val backup = GoogleSignIn.getLastSignedInAccount(context) ?: return null
    return backup.account?.takeIf { GoogleSignIn.hasPermissions(backup, GMAIL_SCOPE) }
}

fun hasGmailPayslipAccess(context: Context): Boolean = gmailPayslipAccount(context) != null

/** Sistemski birač Google naloga za listiće. */
internal fun gmailPayslipAccountChooserIntent(context: Context): Intent =
    AccountManager.newChooseAccountIntent(
        gmailPayslipAccount(context), null, arrayOf(GoogleAuthUtil.GOOGLE_ACCOUNT_TYPE),
        null, null, null, null
    )

internal sealed interface GmailAuthorizeResult {
    data object Authorized : GmailAuthorizeResult
    data class NeedsConsent(val intent: Intent) : GmailAuthorizeResult
    data object Failed : GmailAuthorizeResult
}

/**
 * Proverava Gmail dozvolu za nalog [email] i, ako je ima, pamti ga kao nalog za listiće.
 * Radi mrežu — zvati van glavne niti.
 */
internal fun authorizeGmailPayslipAccount(context: Context, email: String): GmailAuthorizeResult =
    try {
        GoogleAuthUtil.getToken(context, Account(email, GoogleAuthUtil.GOOGLE_ACCOUNT_TYPE), GMAIL_TOKEN_SCOPE)
        context.getSharedPreferences(GMAIL_PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putString(KEY_GMAIL_ACCOUNT, email).apply()
        GmailAuthorizeResult.Authorized
    } catch (e: UserRecoverableAuthException) {
        e.intent?.let { GmailAuthorizeResult.NeedsConsent(it) } ?: GmailAuthorizeResult.Failed
    } catch (e: GoogleAuthException) {
        GmailAuthorizeResult.Failed
    } catch (e: java.io.IOException) {
        GmailAuthorizeResult.Failed
    }

internal fun gmailPayslipAccessStatusText(email: String?): String =
    if (email != null) {
        "Povezan ($email)"
    } else {
        "Nije povezan"
    }

/**
 * Gmail pretraga za listiće i akontacije meseca [month]: stižu od početka tog meseca do 3 meseca
 * kasnije. `{…}` je u Gmail-u ILI — namerno naslov ILI pošiljalac, da listići stignu i ako se
 * promeni jedno od njih. Šta je u PDF-u proverava parser, pa drugi PDF-ovi samo otpadnu.
 */
internal fun gmailPayslipQuery(month: MonthKey): String {
    val from = YearMonth.of(month.year, month.month)
    val until = from.plusMonths(GMAIL_SEARCH_MONTHS)
    return "{subject:\"$GMAIL_PAYSLIP_SUBJECT\" from:$GMAIL_PAYSLIP_SENDER} has:attachment filename:pdf " +
        "after:${from.year}/${from.monthValue}/1 before:${until.year}/${until.monthValue}/1"
}

internal sealed interface GmailPayslipResult {
    data class Found(val payslip: FoundPayslip) : GmailPayslipResult
    data object NotFound : GmailPayslipResult
    data object NoAccess : GmailPayslipResult
    data object Failed : GmailPayslipResult
}

internal sealed interface GmailSideRunsResult {
    data class Found(val runs: List<PayslipParseResult.SideRun>) : GmailSideRunsResult
    data object NotFound : GmailSideRunsResult
    data object NoAccess : GmailSideRunsResult
    data object Failed : GmailSideRunsResult
}

/** PDF prilog u mejlu. */
private data class GmailPdfAttachment(
    val messageId: String,
    val attachmentId: String?,
    val inlineData: String?,
    val fileName: String
)

/**
 * Traži listić za [month] u Gmail-u; od više obračuna meseca (rb.2, pa ispravka rb.3) važi
 * poslednji. Radi mrežu — zvati van glavne niti.
 */
internal fun findPayslipInGmail(context: Context, month: MonthKey): GmailPayslipResult {
    val account = gmailPayslipAccount(context) ?: return GmailPayslipResult.NoAccess
    return try {
        val hit = gmailPdfResults(context, gmailService(context, account), gmailPayslipQuery(month))
            .mapNotNull { (name, result) ->
                (result as? PayslipParseResult.Success)?.takeIf { it.payslip.month == month }?.let { FoundPayslip(name, it) }
            }
            .toList()
            .maxByOrNull { payslipRunNumber(it.result.payslip.runLabel) }
        if (hit != null) GmailPayslipResult.Found(hit) else GmailPayslipResult.NotFound
    } catch (e: GoogleAuthIOException) {
        Log.w(SIDE_RUN_LOG_TAG, "Gmail dozvola: ${e.message}")
        GmailPayslipResult.NoAccess
    } catch (e: java.io.IOException) {
        Log.w(SIDE_RUN_LOG_TAG, "Gmail greška ${e.javaClass.simpleName}: ${e.message}")
        GmailPayslipResult.Failed
    }
}

/** Posebni obračuni (akontacija, prevoz, regres) za mesec rada [month] u Gmail-u. Radi mrežu — van glavne niti. */
internal fun findSideRunsInGmail(context: Context, month: MonthKey): GmailSideRunsResult {
    val account = gmailPayslipAccount(context) ?: return GmailSideRunsResult.NoAccess
    return try {
        val results = gmailPdfResults(context, gmailService(context, account), gmailPayslipQuery(month))
        val runs = sideRuns(results.map { it.second }, month)
        if (runs.isNotEmpty()) GmailSideRunsResult.Found(runs) else GmailSideRunsResult.NotFound
    } catch (e: GoogleAuthIOException) {
        Log.w(SIDE_RUN_LOG_TAG, "Gmail dozvola: ${e.message}")
        GmailSideRunsResult.NoAccess
    } catch (e: java.io.IOException) {
        Log.w(SIDE_RUN_LOG_TAG, "Gmail greška ${e.javaClass.simpleName}: ${e.message}")
        GmailSideRunsResult.Failed
    }
}

internal sealed interface GmailRangeResult {
    /** Svi PDF-ovi iz perioda, svaki preuzet i pročitan jednom (ime → rezultat). */
    data class Read(val results: List<Pair<String, PayslipParseResult>>) : GmailRangeResult
    data object NoAccess : GmailRangeResult
    data object Failed : GmailRangeResult
}

/**
 * Svi listići i posebni obračuni za mesece rada od [from] do [until] (uključivo) u jednom prolazu:
 * jedna pretraga i svaki prilog jednom — za onboarding, gde bi pretraga po mesecu slala stotine
 * zahteva. Radi mrežu — zvati van glavne niti.
 */
internal fun readGmailPayslipRange(
    context: Context,
    from: MonthKey,
    until: MonthKey,
    onProgress: (done: Int, total: Int) -> Unit = { _, _ -> }
): GmailRangeResult {
    val account = gmailPayslipAccount(context) ?: return GmailRangeResult.NoAccess
    return try {
        val results = gmailPdfResults(
            context,
            gmailService(context, account),
            gmailPayslipRangeQuery(from, until),
            maxMessages = MAX_GMAIL_RANGE_MESSAGES,
            onProgress = onProgress
        ).toList()
        GmailRangeResult.Read(results)
    } catch (e: GoogleAuthIOException) {
        Log.w(SIDE_RUN_LOG_TAG, "Gmail dozvola: ${e.message}")
        GmailRangeResult.NoAccess
    } catch (e: java.io.IOException) {
        Log.w(SIDE_RUN_LOG_TAG, "Gmail greška ${e.javaClass.simpleName}: ${e.message}")
        GmailRangeResult.Failed
    }
}

/** Pretraga za mesece rada [from]..[until]: obračun za M stiže do 3 meseca posle M. */
internal fun gmailPayslipRangeQuery(from: MonthKey, until: MonthKey): String {
    val start = YearMonth.of(from.year, from.month)
    val end = YearMonth.of(until.year, until.month).plusMonths(GMAIL_SEARCH_MONTHS)
    return "{subject:\"$GMAIL_PAYSLIP_SUBJECT\" from:$GMAIL_PAYSLIP_SENDER} has:attachment filename:pdf " +
        "after:${start.year}/${start.monthValue}/1 before:${end.year}/${end.monthValue}/1"
}

/**
 * Mesec 3–5 mejlova; od januara prošle godine (do 23 meseca) + 3 meseca čekanja na obračun je
 * ~130. Granica je daleko iznad toga, da se najstariji meseci ne izgube.
 */
private const val MAX_GMAIL_RANGE_MESSAGES = 500L

/** Gmail vraća najviše 500 mejlova po strani. */
private const val GMAIL_PAGE_SIZE = 500L

private const val GMAIL_RETRY_ATTEMPTS = 3
private const val GMAIL_RETRY_FIRST_DELAY_MS = 1_000L

/**
 * Gmail zahtev sa ponovnim pokušajem: Google povremeno vrati preopterećenje (429, 5xx) ili prekine
 * vezu. Istekla dozvola ili nalog kog više nema na telefonu ([GoogleAuthIOException]) i ostale
 * greške zahteva se ne ponavljaju.
 */
private fun <T> gmailRetrying(request: () -> T): T {
    var delayMs = GMAIL_RETRY_FIRST_DELAY_MS
    repeat(GMAIL_RETRY_ATTEMPTS - 1) { attempt ->
        try {
            return request()
        } catch (e: GoogleAuthIOException) {
            throw e
        } catch (e: GoogleJsonResponseException) {
            if (e.statusCode != 429 && e.statusCode !in 500..599) throw e
            Log.w(SIDE_RUN_LOG_TAG, "Gmail ${e.statusCode}, pokušaj ${attempt + 1}")
        } catch (e: java.io.IOException) {
            Log.w(SIDE_RUN_LOG_TAG, "Gmail ${e.javaClass.simpleName}: ${e.message}, pokušaj ${attempt + 1}")
        }
        Thread.sleep(delayMs)
        delayMs *= 2
    }
    return request()
}

private fun gmailService(context: Context, account: Account): Gmail {
    val credential = GoogleAccountCredential.usingOAuth2(context, listOf(GmailScopes.GMAIL_READONLY))
    credential.selectedAccount = account
    return Gmail.Builder(NetHttpTransport(), GsonFactory.getDefaultInstance(), credential)
        .setApplicationName("Rad")
        .build()
}

/**
 * Pročitani PDF prilozi za pretragu [query] (ime → rezultat), lenjo: prilog se preuzima tek kad se
 * do njega stigne. Poruke već pročitane ranije dolaze iz [GmailPayslipCache] bez ijednog zahteva;
 * ostaje samo pretraga.
 */
private fun gmailPdfResults(
    context: Context,
    gmail: Gmail,
    query: String,
    maxMessages: Long = MAX_GMAIL_MESSAGES,
    /** Pročitano / ukupno PDF-ova, posle svakog priloga (i na početku, sa zapamćenim). */
    onProgress: (done: Int, total: Int) -> Unit = { _, _ -> }
): Sequence<Pair<String, PayslipParseResult>> {
    val cache = GmailPayslipCache.open(context)
    val refs = gmailMessageRefs(gmail, query, maxMessages)
    val cached = refs.mapNotNull { cache.results(it.id) }.flatten()
    val fresh = refs.filter { cache.results(it.id) == null }
    val attachments = gmailPdfAttachments(gmail, fresh, cache)
    val total = cached.size + attachments.size
    Log.i(SIDE_RUN_LOG_TAG, "Gmail: ${refs.size} mejlova, zapamćeno ${cached.size} PDF, novih ${attachments.size}")
    onProgress(cached.size, total)
    return cached.asSequence() + attachments.asSequence().withIndex().mapNotNull { (index, attachment) ->
        val result = downloadGmailAttachment(gmail, attachment)?.let { readPayslip(context, it) }
        result?.let { cache.remember(attachment.messageId, attachment.fileName, it) }
        onProgress(cached.size + index + 1, total)
        result?.let { attachment.fileName to it }
    }
}

/** Mejlovi za pretragu, strana po strana, najviše [maxMessages] (najnoviji prvi). */
private fun gmailMessageRefs(gmail: Gmail, query: String, maxMessages: Long): List<com.google.api.services.gmail.model.Message> {
    val refs = mutableListOf<com.google.api.services.gmail.model.Message>()
    var pageToken: String? = null
    do {
        val page = gmailRetrying {
            gmail.users().messages().list(GMAIL_USER).setQ(query)
                .setMaxResults(minOf(GMAIL_PAGE_SIZE, maxMessages - refs.size))
                .setPageToken(pageToken)
                .execute()
        }
        refs += page.messages.orEmpty()
        pageToken = page.nextPageToken
    } while (pageToken != null && refs.size < maxMessages)
    if (pageToken != null) Log.w(SIDE_RUN_LOG_TAG, "Gmail: više od $maxMessages mejlova, najstariji su preskočeni")
    return refs
}

private fun downloadGmailAttachment(gmail: Gmail, attachment: GmailPdfAttachment): ByteArray? {
    val data = attachment.inlineData ?: attachment.attachmentId?.let { id ->
        gmailRetrying { gmail.users().messages().attachments().get(GMAIL_USER, attachment.messageId, id).execute().data }
    } ?: return null
    return runCatching { Base64.getUrlDecoder().decode(data) }.getOrNull()
}

/** Kratak opis pročitanog PDF-a za log (tag [SIDE_RUN_LOG_TAG]). */
internal fun payslipResultDiagnosticText(result: PayslipParseResult?): String = when (result) {
    null -> "PDF nije pročitan"
    is PayslipParseResult.Success -> "listić ${monthYearText(result.payslip.month)} ${result.payslip.runLabel.orEmpty()}"
    is PayslipParseResult.Failure -> "nije listić (${result.reason.javaClass.simpleName})"
    is PayslipParseResult.SideRun -> buildString {
        append("obračun ${result.kind.name} za ${monthYearText(result.month)} ${result.runLabel.orEmpty()}")
        append(", uplaćeno ${result.paidNet?.toPlainString() ?: "?"}")
        append(", isplata ${result.paymentMonth?.let(::monthYearText) ?: "?"}")
        result.transportDaily?.let { append(", prevoz/dan ${it.toPlainString()}") }
    }
}

private fun gmailPdfAttachments(
    gmail: Gmail,
    refs: List<com.google.api.services.gmail.model.Message>,
    cache: GmailPayslipCache
): List<GmailPdfAttachment> =
    refs.flatMap { ref ->
        val message = gmailRetrying { gmail.users().messages().get(GMAIL_USER, ref.id).setFormat("full").execute() }
        val parts = pdfParts(message.payload)
        cache.rememberNames(ref.id, parts.map { it.filename })
        parts.map { part ->
            GmailPdfAttachment(
                messageId = ref.id,
                attachmentId = part.body?.attachmentId,
                inlineData = part.body?.data,
                fileName = part.filename
            )
        }
    }

/** Svi (mali) PDF delovi mejla, i u ugnežđenim multipart delovima. */
private fun pdfParts(part: MessagePart?): List<MessagePart> {
    if (part == null) return emptyList()
    val self = listOfNotNull(
        part.takeIf {
            it.filename.orEmpty().endsWith(".pdf", ignoreCase = true) &&
                (it.body?.size ?: 0) <= MAX_GMAIL_ATTACHMENT_BYTES
        }
    )
    return self + part.parts.orEmpty().flatMap(::pdfParts)
}
