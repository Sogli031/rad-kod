package com.rad.shifttracker.uvoz

import java.time.DayOfWeek
import java.time.YearMonth
import java.security.MessageDigest
import java.util.concurrent.CancellationException
import org.json.JSONArray
import org.json.JSONObject
import com.rad.shifttracker.BuildConfig
import com.rad.shifttracker.ai.AiException
import com.rad.shifttracker.ai.GeminiPayslipCandidateSelector
import com.rad.shifttracker.ai.HttpGeminiTransport
import com.rad.shifttracker.ai.PayslipCandidateSelector
import com.rad.shifttracker.listici.FillDay
import com.rad.shifttracker.listici.FillDayKind
import com.rad.shifttracker.listici.ParsedPayslip
import com.rad.shifttracker.listici.runPayslipSafely
import com.rad.shifttracker.repo.ShiftRepository
import com.rad.shifttracker.repo.bloodDonationHistory
import com.rad.shifttracker.repo.exportCloudBackupJson
import com.rad.shifttracker.repo.getSavedPayslip
import com.rad.shifttracker.repo.brigadeTypeValue
import com.rad.shifttracker.repo.getScheduledShiftForDate
import com.rad.shifttracker.smene.MonthKey
import com.rad.shifttracker.smene.BrigadeType
import com.rad.shifttracker.smene.DayRecord
import com.rad.shifttracker.smene.Shift
import com.rad.shifttracker.smene.isSerbianHoliday

internal enum class FillSelection { LOCAL, AI, FALLBACK }

/** Predlog je vezan za stanje iz kog je napravljen; promenjen kalendar zahteva nov predlog. */
internal data class PayslipFillProposal(
    val result: MonthFillResult.Success,
    val selection: FillSelection,
    val candidateCount: Int,
    val sourceState: String,
    /** Zašto AI nije izabrao (npr. Google preopterećen); null kad je izabrao ili nije ni pitan. */
    val aiError: String? = null
)

internal data class PayslipFillBatch(
    val proposals: List<PayslipFillProposal>,
    val failures: List<Pair<MonthKey, String>>
)

/** Tačnost ispred brzine: rok samo štiti od prekinute veze, a ne skraćuje razmišljanje modela. */
private fun defaultPayslipSelector(): PayslipCandidateSelector = GeminiPayslipCandidateSelector(
    HttpGeminiTransport(BuildConfig.GEMINI_API_KEY, BuildConfig.APPLICATION_ID, readTimeoutMs = AI_FILL_TIMEOUT_MS)
)

internal fun fillSourceState(repo: ShiftRepository): String = MessageDigest.getInstance("SHA-256")
    .digest(repo.exportCloudBackupJson(null).toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

/** Poziva se samo posle korisnikovog zahteva, van glavne niti. Ni planiranje ni AI ne upisuju podatke. */
internal fun planAiMonthFromPayslip(
    repo: ShiftRepository,
    payslip: ParsedPayslip,
    selector: PayslipCandidateSelector = defaultPayslipSelector()
): Pair<PayslipFillProposal?, String?> {
    val sourceState = fillSourceState(repo)
    val snapshot = verificationCopy(repo)
    val alternatives = mutableListOf<List<FillDay>>()
    val baseline = planMonthFromPayslip(snapshot, payslip, alternatives)
    if (baseline is MonthFillResult.Failure) return null to baseline.message
    baseline as MonthFillResult.Success
    val targets = requireNotNull(fillTargetsFrom(payslip))
    val scratch = verificationCopy(snapshot)
    // Svaki kandidat se ponovo proverava istim obračunom. Četvorobrigada ne sme povećati odstupanje
    // nijedne stavke sati u odnosu na osnovni predlog (strože od samo jednakog zbirnog skora).
    val variants = variedFillCandidates(baseline.days) { days ->
        preservesHourChecks(baseline.reconciliation, verifyMonthFill(scratch, payslip, targets, days))
    }
    val candidates = (variants + alternatives).distinct().filter { days ->
        val check = verifyMonthFill(scratch, payslip, targets, days)
        preservesHourChecks(baseline.reconciliation, check)
    }.take(MAX_AI_FILL_CANDIDATES)
    var aiError: String? = null
    val selectedId = if (candidates.size > 1) {
        try {
            selector.select(fillRankingPayload(snapshot, payslip, candidates))
        } catch (error: CancellationException) {
            throw error
        } catch (error: AiException) {
            aiError = error.title
            null
        } catch (_: Exception) {
            null
        }
    } else null
    val chosenId = selectedId?.takeIf { it in candidates.indices }
    val selection = when {
        candidates.size <= 1 -> FillSelection.LOCAL
        chosenId != null -> FillSelection.AI
        else -> FillSelection.FALLBACK
    }
    val selectedDays = candidates.getOrElse(chosenId ?: 0) { baseline.days }
    val verified = verifyMonthFill(scratch, payslip, targets, selectedDays)
    if (!preservesHourChecks(baseline.reconciliation, verified)) return null to "Predlog nije prošao proveru sati."
    val result = baseline.copy(days = selectedDays, reconciliation = verified)
    return PayslipFillProposal(result, selection, candidates.size, sourceState,
        if (selection == FillSelection.FALLBACK) aiError ?: "neispravan odgovor" else null) to null
}

private fun preservesHourChecks(baseline: PayslipReconciliation, candidate: PayslipReconciliation): Boolean {
    val original = baseline.rows.filter { it.isHours }
    val proposed = candidate.rows.filter { it.isHours }
    return original.size == proposed.size && original.zip(proposed).all { (before, after) ->
        before.label == after.label && before.appText == after.appText && before.payslipText == after.payslipText
    }
}

internal fun planAiFillBatch(
    repo: ShiftRepository,
    months: List<MonthKey>,
    selector: PayslipCandidateSelector = defaultPayslipSelector(),
    checkCancelled: () -> Unit = {}
): PayslipFillBatch {
    val proposals = mutableListOf<PayslipFillProposal>()
    val failures = mutableListOf<Pair<MonthKey, String>>()
    val sourceState = fillSourceState(repo)
    val planning = verificationCopy(repo)
    for (month in months.distinct().sorted()) {
        checkCancelled()
        val slip = planning.getSavedPayslip(month.year, month.month)
        if (slip == null) {
            failures.add(month to "Lista više nije dostupna.")
            continue
        }
        val outcome = runPayslipSafely { planAiMonthFromPayslip(planning, slip, selector) }
        checkCancelled()
        val proposal = outcome?.first
        if (proposal != null) {
            proposals.add(proposal.copy(sourceState = sourceState))
            // Susedni mesec vidi prethodni predlog kao kontekst. Pravi kalendar se ne menja.
            applyMonthFill(planning, proposal.result)
        }
        else failures.add(month to (outcome?.second ?: PAYSLIP_UNEXPECTED_ERROR_TEXT))
    }
    return PayslipFillBatch(proposals, failures)
}

internal const val STALE_FILL_PROPOSAL_TEXT = "Podaci su promenjeni. Ponovo pokreni popunu prema listi."

/** Jedina putanja iz pregleda u upis; svi predlozi se proveravaju pre prvog upisa. */
internal fun applyReviewedFills(
    repo: ShiftRepository,
    proposals: List<PayslipFillProposal>,
    apply: (MonthFillResult.Success) -> Unit
): Boolean = synchronized(repo.lock) {
    val current = fillSourceState(repo)
    if (proposals.isEmpty() || proposals.any { it.sourceState != current }) return@synchronized false
    proposals.forEach { apply(it.result) }
    true
}

/** Dozvoljena polja za mrežu su isključivo datumi i brojevi; nikada se ne serijalizuje ParsedPayslip. */
internal fun fillRankingPayload(repo: ShiftRepository, payslip: ParsedPayslip, candidates: List<List<FillDay>>): JSONObject {
    val month = payslip.month
    val target = YearMonth.of(month.year, month.month)
    val hints = fillHints(repo, payslip)
    val known = JSONArray()
    for (offset in -1L..1L) {
        val ym = target.plusMonths(offset)
        repo.getMonthRecords(ym.year, ym.monthValue).filterNot { it.isAutomaticPlaceholder() }.forEach { record ->
            val kind = when {
                record.shift == Shift.VACATION -> FillDayKind.VACATION
                record.isPaidLeave -> FillDayKind.PAID_LEAVE
                record.shift == Shift.SICK -> FillDayKind.SICK
                record.shift == Shift.OFF -> offDayKind(repo, record)
                else -> FillDayKind.WORK
            }
            known.put(dayNumbers(FillDay(record.date, kind, record.shift, record.workHours)))
        }
    }
    return JSONObject()
        .put("month", target.toString())
        .put("candidates", JSONArray().apply {
            candidates.forEachIndexed { id, days -> put(JSONObject().put("id", id)
                .put("days", JSONArray().apply { days.forEach { put(dayNumbers(it)) } })) }
        })
        .put("knownDays", known)
        .put("donationDates", JSONArray(repo.bloodDonationHistory.filter {
            it >= target.atDay(1).minusDays(7) && it <= target.atEndOfMonth()
        }.sorted().map { it.toString() }))
        .put("adjacentLeaveKinds", JSONArray().put(JSONArray(hints.continuesFromPrevious.map(::fillKindName)))
            .put(JSONArray(hints.continuesIntoNext.map(::fillKindName))))
}

/** Slobodan upis je izostanak samo na dan kad je po rasporedu trebalo raditi. */
private fun offDayKind(repo: ShiftRepository, record: DayRecord): FillDayKind {
    val date = record.date
    if (record.holidayOverride ?: isSerbianHoliday(date.year, date.monthValue, date.dayOfMonth)) return FillDayKind.HOLIDAY_OFF
    val scheduled = repo.getScheduledShiftForDate(date) in Shift.workShifts()
    val workDay = if (repo.brigadeTypeValue == BrigadeType.CETVOROBRIGADA) scheduled
    else scheduled && date.dayOfWeek.value <= DayOfWeek.FRIDAY.value
    return if (workDay) FillDayKind.ABSENT else FillDayKind.CYCLE_OFF
}

/** Ime umesto rednog broja: promena redosleda u [FillDayKind] ne menja značenje za AI. */
internal fun fillKindName(kind: FillDayKind): String = when (kind) {
    FillDayKind.WORK -> "work"
    FillDayKind.VACATION -> "vacation"
    FillDayKind.PAID_LEAVE -> "paid_leave"
    FillDayKind.SICK -> "sick"
    FillDayKind.ABSENT -> "absent"
    FillDayKind.HOLIDAY_OFF -> "holiday_off"
    FillDayKind.HOLIDAY_WORK -> "holiday_work"
    FillDayKind.WEEKEND_WORK -> "weekend_work"
    FillDayKind.CYCLE_OFF -> "cycle_off"
}

private fun dayNumbers(day: FillDay) = JSONArray().put(day.date.toString()).put(fillKindName(day.kind))
    .put(day.shift?.takeIf { it in Shift.workShifts() }?.value ?: 0).put(day.hours).put(day.templateShift?.value ?: 0)

private const val MAX_AI_FILL_CANDIDATES = 12
private const val AI_FILL_TIMEOUT_MS = 180_000
