package com.rad.shifttracker

import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.time.LocalDate
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import com.rad.shifttracker.ai.AiChange
import com.rad.shifttracker.ai.AiChangeKind
import com.rad.shifttracker.ai.AiException
import com.rad.shifttracker.ai.AiSession
import com.rad.shifttracker.ai.AiTools
import com.rad.shifttracker.ai.GeminiTransport
import com.rad.shifttracker.ai.HttpGeminiTransport
import com.rad.shifttracker.ai.applyAiChanges
import com.rad.shifttracker.ai.describeAiChange
import com.rad.shifttracker.ai.LeaveOption
import com.rad.shifttracker.ai.httpError
import com.rad.shifttracker.ai.modelContent
import com.rad.shifttracker.ai.networkError
import com.rad.shifttracker.ai.parseAiChanges
import com.rad.shifttracker.ai.rankBalanced
import com.rad.shifttracker.repo.populateMonthsAhead
import com.rad.shifttracker.smene.Shift
import com.rad.shifttracker.smene.ShiftScheduler
import com.rad.shifttracker.ui.dialozi.AI_CAPABILITIES

class AiAssistantTest {

    private val today = LocalDate.of(2026, 10, 15)

    private fun changes(vararg items: JSONObject) = JSONArray().apply { items.forEach { put(it) } }

    @Test
    fun `izmene se citaju iz argumenata alata`() {
        val parsed = parseAiChanges(
            changes(
                JSONObject().put("tip", "bolovanje").put("od", "2026-11-03").put("do", "2026-11-10"),
                JSONObject().put("tip", "smena").put("od", "2026-11-14").put("smena", 2).put("sati", 10),
                JSONObject().put("tip", "smena").put("od", "2026-11-15").put("smena", 1).put("druga_smena", 2)
            ),
            today
        )
        assertEquals(AiChangeKind.SICK, parsed[0].kind)
        assertEquals(LocalDate.of(2026, 11, 10), parsed[0].to)
        assertEquals(Shift.SECOND, parsed[1].shift)
        assertEquals(10, parsed[1].hours)
        assertEquals(Shift.SECOND, parsed[2].shift2)
        assertEquals(8, parsed[2].hours2)
        assertEquals("Bolovanje 3.11.–10.11.2026.", describeAiChange(parsed[0]))
    }

    @Test
    fun `pogresna izmena daje poruku koju AI moze da ispravi`() {
        val cases = listOf(
            JSONObject().put("tip", "odmor").put("od", "2026-11-03") to "nepoznat 'tip'",
            JSONObject().put("tip", "smena").put("od", "3.11.2026") to "GGGG-MM-DD",
            JSONObject().put("tip", "smena").put("od", "2026-11-03").put("smena", 4) to "'smena' mora biti",
            JSONObject().put("tip", "godisnji").put("od", "2026-11-10").put("do", "2026-11-03") to "'do' je pre 'od'"
        )
        for ((item, expected) in cases) {
            try {
                parseAiChanges(changes(item), today)
                fail("očekivana greška: $expected")
            } catch (e: IllegalArgumentException) {
                assertTrue(e.message, e.message!!.contains(expected))
            }
        }
    }

    @Test
    fun `greske od Google-a imaju jasan naslov i detalje`() {
        val invalidKey = httpError(
            400,
            """{"error":{"code":400,"message":"API key not valid. Please pass a valid API key.","status":"INVALID_ARGUMENT",""" +
                """"details":[{"reason":"API_KEY_INVALID"}]}}"""
        )
        assertEquals("Gemini ključ nije ispravan", invalidKey.title)
        assertTrue(invalidKey.detail!!.contains("API_KEY_INVALID"))

        val quota = httpError(429, """{"error":{"code":429,"message":"Quota exceeded","status":"RESOURCE_EXHAUSTED"}}""")
        assertEquals("Potrošena besplatna kvota, probaj kasnije", quota.title)
        assertTrue(quota.detail!!.contains("HTTP 429 RESOURCE_EXHAUSTED"))

        assertEquals("Ključ nema dozvolu za Gemini", httpError(403, "{}").title)
        assertEquals("Google je trenutno preopterećen, probaj za minut", httpError(503, "nije json").title)
        assertEquals("Nema interneta", networkError(UnknownHostException("generativelanguage.googleapis.com")).title)
        assertEquals("Google nije odgovorio na vreme", networkError(SocketTimeoutException()).title)
    }

    @Test
    fun `bez kljuca se odmah javlja da ga nema`() {
        try {
            HttpGeminiTransport(apiKey = "", packageName = "com.rad.shifttracker").post("gemini-3.5-flash", JSONObject())
            fail("očekivana greška")
        } catch (e: AiException) {
            assertEquals("Nema Gemini ključa u ovoj verziji aplikacije", e.title)
        }
    }

    @Test
    fun `blokiran ili prazan odgovor kaze zasto`() {
        val blocked = JSONObject().put("promptFeedback", JSONObject().put("blockReason", "SAFETY"))
        assertEquals("Google je blokirao pitanje", runCatching { modelContent(blocked) }.exceptionOrNull()?.let { (it as AiException).title })
        val cut = JSONObject().put("candidates", JSONArray().put(JSONObject().put("finishReason", "MAX_TOKENS")))
        assertEquals("Odgovor je predugačak i prekinut", runCatching { modelContent(cut) }.exceptionOrNull()?.let { (it as AiException).title })
    }

    @Test
    fun `razgovor poziva alat pa vraca tekst i predlog`() {
        val repo = testRepository()
        val requests = mutableListOf<JSONObject>()
        val responses = ArrayDeque(
            listOf(
                functionCall(
                    "predlozi_izmene",
                    JSONObject()
                        .put("opis", "Bolovanje")
                        .put("izmene", changes(JSONObject().put("tip", "bolovanje").put("od", "2026-11-03").put("do", "2026-11-04")))
                ),
                text("Potvrdi upis bolovanja.")
            )
        )
        val session = AiSession(GeminiTransport { _, body -> requests += JSONObject(body.toString()); responses.removeFirst() })

        val reply = session.send("bolovanje 3. i 4. novembar", repo, today)

        assertEquals("Potvrdi upis bolovanja.", reply.text)
        assertNotNull(reply.proposal)
        assertEquals(1, reply.proposal!!.changes.size)
        // Drugi zahtev nosi odgovor alata, a ništa nije upisano dok korisnik ne potvrdi.
        val lastTurn = requests[1].getJSONArray("contents").let { it.getJSONObject(it.length() - 1) }
        assertTrue(lastTurn.toString().contains("functionResponse"))
        assertNull(repo.get(2026, 11, 3))
    }

    @Test
    fun `preopterecen model prelazi na rezervni, a neuspela poruka ne ostaje u istoriji`() {
        val repo = testRepository()
        val used = mutableListOf<String>()
        val session = AiSession(
            GeminiTransport { model, _ ->
                used += model
                if (model == "glavni") throw httpError(503, "{}") else text("ok")
            },
            models = listOf("glavni", "rezerva")
        )
        assertEquals("ok", session.send("zdravo", repo, today).text)
        assertEquals(listOf("glavni", "rezerva"), used)

        val failing = AiSession(GeminiTransport { _, _ -> throw httpError(429, "{}") }, models = listOf("a", "b"))
        val error = runCatching { failing.send("zdravo", repo, today) }.exceptionOrNull() as AiException
        assertEquals("Potrošena besplatna kvota, probaj kasnije", error.title)
    }

    @Test
    fun `sta ako racuna nad kopijom i ne menja podatke`() {
        val repo = testRepository()
        val start = LocalDate.of(2026, 11, 2)
        ShiftScheduler().populateMonthsAhead(repo, start, Shift.FIRST, 0)
        val before = repo.get(2026, 11, 14)

        val result = AiTools(repo, today).execute(
            "sta_ako",
            JSONObject().put("izmene", changes(JSONObject().put("tip", "smena").put("od", "2026-11-14").put("smena", 1)))
        )

        assertTrue(result.toString(), result.has("meseci"))
        val month = result.getJSONArray("meseci").getJSONObject(0)
        assertTrue(month.getJSONObject("posle").getInt("sati_rada") > month.getJSONObject("pre").getInt("sati_rada"))
        assertEquals(before?.shift, repo.get(2026, 11, 14)?.shift)
    }

    @Test
    fun `potvrdjene izmene se upisuju`() {
        val repo = testRepository()
        val result = applyAiChanges(
            repo,
            listOf(
                AiChange(AiChangeKind.SHIFT, LocalDate.of(2026, 11, 14), LocalDate.of(2026, 11, 14), Shift.SECOND, 10),
                AiChange(AiChangeKind.SICK, LocalDate.of(2026, 11, 2), LocalDate.of(2026, 11, 3))
            )
        )
        assertEquals(3, result.writtenDays)
        assertEquals(Shift.SECOND, repo.get(2026, 11, 14)?.shift)
        assertEquals(10, repo.get(2026, 11, 14)?.hours)
        assertEquals(Shift.SICK, repo.get(2026, 11, 2)?.shift)
    }

    @Test
    fun `planer godisnjeg spaja godisnji sa vikendom i praznikom`() {
        val repo = testRepository()
        val result = AiTools(repo, today).execute(
            "planer_godisnjeg",
            JSONObject().put("dana", 2).put("od", "2026-11-01").put("do", "2026-11-30")
        )

        val best = result.getJSONArray("predlozi").getJSONObject(0)
        // Pon 9. i uto 10. novembar: od subote 7. do Dana primirja u sredu 11. je pet slobodnih dana.
        assertEquals("2026-11-09 (ponedeljak)", best.getString("godisnji_od"))
        assertEquals("2026-11-10 (utorak)", best.getString("godisnji_do"))
        assertEquals("2026-11-07 (subota)", best.getString("slobodno_od"))
        assertEquals(5, best.getInt("uzastopno_slobodnih_dana"))
        assertTrue(best.getJSONArray("praznici").getString(0).startsWith("Dan primirja"))
        assertNull(repo.get(2026, 11, 9))
    }

    @Test
    fun `planer godisnjeg ne nudi termine koji se preklapaju i javlja pogresan unos`() {
        val tools = AiTools(testRepository(), today)
        val result = tools.execute("planer_godisnjeg", JSONObject().put("dana", 3).put("broj_predloga", 5))
        val options = result.getJSONArray("predlozi")
        assertEquals(5, options.length())
        val ranges = (0 until options.length()).map { index ->
            val option = options.getJSONObject(index)
            LocalDate.parse(option.getString("slobodno_od").take(10))..LocalDate.parse(option.getString("slobodno_do").take(10))
        }
        for (i in ranges.indices) for (j in i + 1 until ranges.size) {
            assertTrue(ranges[i].endInclusive < ranges[j].start || ranges[j].endInclusive < ranges[i].start)
        }

        assertTrue(tools.execute("planer_godisnjeg", JSONObject().put("dana", 0)).has("greska"))
        assertTrue(tools.execute("planer_godisnjeg", JSONObject().put("dana", 2).put("od", "2026-01-01")).has("greska"))
    }

    @Test
    fun `balans bira vise slobodnih dana samo kad gubitak nije mnogo veci`() {
        fun option(day: Int, freeDays: Long, change: Double?) = LeaveOption(
            from = LocalDate.of(2026, 12, day),
            to = LocalDate.of(2026, 12, day),
            vacationDays = 3,
            freeFrom = LocalDate.of(2026, 12, day),
            freeTo = LocalDate.of(2026, 12, day).plusDays(freeDays - 1),
            holidays = emptyList(),
            netPayChange = change
        )
        val long = option(1, 9, -3_000.0)
        val short = option(2, 5, -2_500.0)
        val cheap = option(3, 5, -500.0)
        val unknown = option(4, 9, null)

        // 9 dana za 3.000 din pobeđuje 5 dana za 2.500, ali 5 dana za 500 din pobeđuje oba.
        assertEquals(listOf(cheap, long, unknown, short), rankBalanced(listOf(short, long, cheap, unknown)))
    }

    @Test
    fun `uvod u AI prozoru nabraja sve sto AI ume, i planer godisnjeg`() {
        val titles = AI_CAPABILITIES.map { it.first }
        assertEquals(listOf("Plata i sati", "Šta ako", "Planer godišnjeg", "Upis u kalendar"), titles)
        assertTrue(AI_CAPABILITIES.all { (_, example) -> example.isNotBlank() })
    }

    private fun candidate(parts: JSONArray) = JSONObject().put(
        "candidates",
        JSONArray().put(JSONObject().put("content", JSONObject().put("role", "model").put("parts", parts)).put("finishReason", "STOP"))
    )

    private fun text(value: String) = candidate(JSONArray().put(JSONObject().put("text", value)))

    private fun functionCall(name: String, args: JSONObject) =
        candidate(JSONArray().put(JSONObject().put("functionCall", JSONObject().put("name", name).put("args", args).put("id", "c1"))))
}
