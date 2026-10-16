package com.rad.shifttracker

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import com.rad.shifttracker.ai.AiException
import com.rad.shifttracker.ai.GeminiPayslipCandidateSelector
import com.rad.shifttracker.ai.GeminiTransport
import com.rad.shifttracker.ai.PayslipCandidateSelector
import com.rad.shifttracker.listici.FillDay
import com.rad.shifttracker.listici.FillDayKind
import com.rad.shifttracker.repo.ShiftRepository
import com.rad.shifttracker.repo.exportCloudBackupJson
import com.rad.shifttracker.repo.savePayslip
import com.rad.shifttracker.repo.populateMonthsAhead
import com.rad.shifttracker.smene.MonthKey
import com.rad.shifttracker.smene.ShiftScheduler
import com.rad.shifttracker.smene.ShiftFillMode
import com.rad.shifttracker.smene.ShiftFillSettings
import com.rad.shifttracker.smene.Shift
import com.rad.shifttracker.uvoz.FillSelection
import com.rad.shifttracker.ui.dialozi.fillSelectionStatus
import com.rad.shifttracker.uvoz.applyMonthFill
import com.rad.shifttracker.uvoz.applyReviewedFills
import com.rad.shifttracker.uvoz.fillRankingPayload
import com.rad.shifttracker.uvoz.fillKindName
import com.rad.shifttracker.repo.getScheduledShiftForDate
import com.rad.shifttracker.uvoz.fillSourceState
import com.rad.shifttracker.uvoz.planAiMonthFromPayslip
import com.rad.shifttracker.uvoz.planAiFillBatch
import com.rad.shifttracker.uvoz.planMonthFromPayslip
import com.rad.shifttracker.uvoz.MonthFillResult
import com.rad.shifttracker.uvoz.verificationCopy

class AiPayslipMonthFillTest {
    private fun sample() = payslipFromRepo(truthMonth(2026, 8, Shift.THIRD, listOf(
        TruthDay(3, Shift.VACATION), TruthDay(4, Shift.VACATION)
    )), 2026, 8)

    private fun repo(): ShiftRepository = templateRepoBefore(2026, 8)

    @Test
    fun aiSelectsVerifiedCandidateWithoutMutatingRepository() {
        val repo = repo()
        val before = repo.exportCloudBackupJson(null)
        var count = 0
        var selectedDays = ""
        val (proposal, error) = planAiMonthFromPayslip(repo, sample(), PayslipCandidateSelector { payload ->
            count = payload.getJSONArray("candidates").length()
            selectedDays = payload.getJSONArray("candidates").getJSONObject(count - 1).getJSONArray("days").toString()
            count - 1
        })
        assertNull(error)
        assertNotNull(proposal)
        assertTrue("Expected ambiguous schedules", count > 1)
        assertEquals(FillSelection.AI, proposal!!.selection)
        assertEquals(selectedDays, fillRankingPayload(repo, sample(), listOf(proposal.result.days))
            .getJSONArray("candidates").getJSONObject(0).getJSONArray("days").toString())
        assertTrue(proposal.result.reconciliation.rows.filter { it.isHours }.all { it.matches })
        assertEquals(before, repo.exportCloudBackupJson(null))
        assertEquals(2, proposal.result.days.count { it.kind == FillDayKind.VACATION })
        assertTrue(applyReviewedFills(repo, listOf(proposal)) { applyMonthFill(repo, it) })
        assertNotEquals(before, repo.exportCloudBackupJson(null))
    }

    @Test
    fun invalidIdAndNetworkFailureFallBackToVerifiedLocalSchedule() {
        for (selector in listOf(
            PayslipCandidateSelector { 999 },
            PayslipCandidateSelector { throw AiException("Nema mreže") }
        )) {
            val repo = repo()
            val before = fillSourceState(repo)
            val (proposal, error) = planAiMonthFromPayslip(repo, sample(), selector)
            assertNull(error)
            assertEquals(FillSelection.FALLBACK, proposal!!.selection)
            assertTrue(proposal.result.reconciliation.rows.filter { it.isHours }.all { it.matches })
            assertEquals(before, fillSourceState(repo))
        }
    }

    @Test
    fun staleSingleAndBatchProposalsAreRejectedBeforeAnyWrite() {
        val repo = repo()
        val proposal = planAiMonthFromPayslip(repo, sample(), PayslipCandidateSelector { 0 }).first!!
        assertEquals(proposal.sourceState, fillSourceState(repo))
        repo.set(2026, 8, 7, Shift.SICK, 8)
        var writes = 0
        assertFalse(applyReviewedFills(repo, listOf(proposal)) { writes++ })
        val fresh = proposal.copy(sourceState = fillSourceState(repo))
        assertFalse(applyReviewedFills(repo, listOf(fresh, proposal)) { writes++ })
        assertEquals(0, writes)
        assertEquals(Shift.SICK, repo.get(2026, 8, 7)!!.shift)
    }

    @Test
    fun rankingPayloadContainsOnlyNumbersDatesAndWhitelistedContainers() {
        val slip = sample()
        val day = FillDay(java.time.LocalDate.of(2026, 8, 3), FillDayKind.WORK, Shift.FIRST, 8)
        val payload = fillRankingPayload(repo(), slip, listOf(listOf(day)))
        fun check(value: Any) {
            when (value) {
                is JSONObject -> value.keys().forEach { check(value.get(it)) }
                is JSONArray -> (0 until value.length()).forEach { check(value.get(it)) }
                is Number -> Unit
                is String -> assertTrue(value, value.matches(Regex("\\d{4}-\\d{2}(-\\d{2})?")) ||
                    value in FillDayKind.entries.map(::fillKindName))
                else -> fail("Unexpected payload type: ${value.javaClass}")
            }
        }
        check(payload)
        assertEquals(setOf("month", "candidates", "knownDays", "donationDates", "adjacentLeaveKinds"), payload.keys().asSequence().toSet())
    }

    @Test
    fun kindNamesAreUniqueAndOffDependsOnSchedule() {
        assertEquals(FillDayKind.entries.size, FillDayKind.entries.map(::fillKindName).toSet().size)
        val repo = repo()
        val july = (1..31).map { java.time.LocalDate.of(2026, 7, it) }
        val workDay = july.first { it.dayOfWeek.value <= 5 && repo.getScheduledShiftForDate(it) in Shift.workShifts() }
        val freeDay = july.first { it.dayOfWeek.value >= 6 }
        repo.set(2026, 7, workDay.dayOfMonth, Shift.OFF, 0)
        repo.set(2026, 7, freeDay.dayOfMonth, Shift.OFF, 0)
        val known = fillRankingPayload(repo, sample(), listOf(emptyList())).getJSONArray("knownDays")
        val kinds = (0 until known.length()).associate { known.getJSONArray(it).getString(0) to known.getJSONArray(it).getString(1) }
        assertEquals("absent", kinds[workDay.toString()])
        assertNotEquals("absent", kinds[freeDay.toString()])
    }

    private fun payload() = JSONObject().put("candidates", JSONArray().put(JSONObject()).put(JSONObject()))

    private fun response(text: String, finish: String = "STOP") = JSONObject().put("candidates", JSONArray().put(
        JSONObject().put("finishReason", finish).put("content", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", text))))
    ))

    @Test
    fun geminiUsesHighThinkingAndOnlyAcceptsCompleteIntegerSelection() {
        val selector = GeminiPayslipCandidateSelector(GeminiTransport { model, body ->
            assertEquals("gemini-3.5-flash", model)
            val config = body.getJSONObject("generationConfig")
            assertEquals("high", config.getJSONObject("thinkingConfig").getString("thinkingLevel"))
            assertEquals("application/json", config.getString("responseMimeType"))
            response("{\"candidateId\":1}")
        })
        assertEquals(1, selector.select(payload()))
        for (text in listOf("{}", "{\"candidateId\":2}", "{\"candidateId\":0.5}", "{\"candidateId\":\"1\"}", "{\"candidateId\":0,\"days\":[]}", "not json")) {
            assertNull(GeminiPayslipCandidateSelector(GeminiTransport { _, _ -> response(text) }).select(payload()))
        }
        assertNull(GeminiPayslipCandidateSelector(GeminiTransport { _, _ -> response("{\"candidateId\":1}", "MAX_TOKENS") }).select(payload()))
    }

    @Test
    fun missingModelUsesBackupButRateLimitDoesNotRepeatRequests() {
        var calls = 0
        val selector = GeminiPayslipCandidateSelector(GeminiTransport { _, _ ->
            calls++
            if (calls == 1) throw AiException("Nema modela", httpCode = 404)
            response("{\"candidateId\":0}")
        }, pause = {})
        assertEquals(0, selector.select(payload()))
        assertEquals(2, calls)
        calls = 0
        val limited = GeminiPayslipCandidateSelector(GeminiTransport { _, _ -> calls++; throw AiException("Limit", httpCode = 429) }, pause = {})
        assertEquals("Limit", assertThrows(AiException::class.java) { limited.select(payload()) }.title)
        assertEquals(1, calls)
    }

    @Test
    fun overloadedGoogleIsRetriedWithPausesBeforeGivingUp() {
        val pauses = mutableListOf<Long>()
        var calls = 0
        val recovers = GeminiPayslipCandidateSelector(GeminiTransport { _, _ ->
            calls++
            if (calls <= 2) throw AiException("Preopterećen", httpCode = 503)
            response("{\"candidateId\":1}")
        }, pause = { pauses.add(it) })
        assertEquals(1, recovers.select(payload()))
        assertEquals(1, pauses.size)

        pauses.clear()
        val down = GeminiPayslipCandidateSelector(GeminiTransport { _, _ -> throw AiException("Preopterećen", httpCode = 503) },
            pause = { pauses.add(it) })
        assertEquals("Preopterećen", assertThrows(AiException::class.java) { down.select(payload()) }.title)
        assertEquals(3, pauses.size)
        assertTrue(pauses.zipWithNext().all { (a, b) -> b > a })
    }

    @Test
    fun fallbackShowsWhyAiWasUnavailable() {
        val proposal = planAiMonthFromPayslip(repo(), sample(), PayslipCandidateSelector {
            throw AiException("Google je trenutno preopterećen, probaj za minut", httpCode = 503)
        }).first!!
        assertEquals(FillSelection.FALLBACK, proposal.selection)
        assertEquals("AI nije dostupan: Google je trenutno preopterećen, probaj za minut · lokalni predlog",
            fillSelectionStatus(proposal))
    }

    @Test
    fun batchPlanningDoesNotWriteAndAllProposalsShareOriginalState() {
        val repo = repo()
        repo.savePayslip(sample(), withPayBase = false)
        val before = fillSourceState(repo)
        val batch = planAiFillBatch(repo, listOf(MonthKey(2026, 8), MonthKey(2026, 9)), PayslipCandidateSelector { 0 })
        assertEquals(1, batch.proposals.size)
        assertEquals(1, batch.failures.size)
        assertEquals(before, batch.proposals.single().sourceState)
        assertEquals(before, fillSourceState(repo))
        assertTrue(applyReviewedFills(repo, batch.proposals) { applyMonthFill(repo, it) })
    }

    @Test
    fun fourBrigadeAiCannotChangeAnyVerifiedHourRow() {
        val repo = testRepository()
        val first = java.time.LocalDate.of(2026, 8, 1)
        ShiftScheduler().populateMonthsAhead(repo, first,
            ShiftFillSettings(ShiftFillMode.FOUR_BRIGADE, first, Shift.FIRST, 0), 0)
        val truth = verificationCopy(repo).apply {
            set(2026, 8, 5, Shift.VACATION, 8)
            set(2026, 8, 6, Shift.VACATION, 8)
        }
        val slip = payslipFromRepo(truth, 2026, 8)
        val baseline = planMonthFromPayslip(repo, slip) as MonthFillResult.Success
        val before = fillSourceState(repo)
        val proposal = planAiMonthFromPayslip(repo, slip, PayslipCandidateSelector {
            it.getJSONArray("candidates").length() - 1
        }).first!!
        assertEquals(baseline.reconciliation.rows.filter { it.isHours }.map { it.appText },
            proposal.result.reconciliation.rows.filter { it.isHours }.map { it.appText })
        assertEquals(before, fillSourceState(repo))
    }

    @Test(expected = java.util.concurrent.CancellationException::class)
    fun cancelledBatchStopsBeforeCallingAi() {
        val repo = repo()
        repo.savePayslip(sample(), withPayBase = false)
        planAiFillBatch(repo, listOf(MonthKey(2026, 8)), PayslipCandidateSelector { fail("AI must not run"); 0 },
            checkCancelled = { throw java.util.concurrent.CancellationException() })
    }
}
