package com.rad.shifttracker

import com.rad.shifttracker.data.storage.SCHEMA_VERSION_KEY
import com.rad.shifttracker.data.storage.SchemaMigrations
import com.rad.shifttracker.data.storage.ShiftRepositoryJsonCodec
import com.rad.shifttracker.data.storage.ShiftRepositorySnapshot
import com.rad.shifttracker.data.storage.SnapshotDecodeResult
import com.rad.shifttracker.data.storage.SnapshotRejectedException
import com.rad.shifttracker.data.storage.SnapshotRejection
import java.time.Clock
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import kotlin.random.Random
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** Dekoder je poslednja linija odbrane od oštećenog backup-a: ne sme da pukne ni da izmisli podatke. */
class ShiftRepositoryJsonCodecTest {

    private val codec = ShiftRepositoryJsonCodec(CONFIG, TEST_CLOCK)

    private companion object {
        /** Znakovi kojima se kvari izvoz: strukturni, cifre, escape, nula i neparan deo surogat para. */
        const val JUNK = "\"{[]}:,0123456789-.eEtfn\\ \u0000\uD83D"
    }

    private fun decoded(text: String): ShiftRepositorySnapshot =
        (codec.decodeChecked(text) as SnapshotDecodeResult.Decoded).snapshot

    private fun rejection(text: String): SnapshotRejection? =
        (codec.decodeChecked(text) as? SnapshotDecodeResult.Rejected)?.reason

    // Rečnik za poređenje: DayRecord nema equals, a BigDecimal razlikuje 1.5 i 1.50.
    private data class View(
        val rest: ShiftRepositorySnapshot,
        val records: List<Pair<DayKey, Map<String, Any>>>,
        val payslips: Map<MonthKey, Map<String, Any>>
    )

    @Suppress("DEPRECATION")
    private fun view(snapshot: ShiftRepositorySnapshot) = View(
        rest = snapshot.copy(
            records = emptyList(),
            savedPayslips = emptyMap(),
            monthlyGrossAdditions = snapshot.monthlyGrossAdditions.mapValues { (_, value) ->
                value.copy(
                    absencePenaltyGross = value.absencePenaltyGross.stripTrailingZeros(),
                    mealEntitlementNetOverride = value.mealEntitlementNetOverride?.stripTrailingZeros()
                )
            }
        ),
        records = snapshot.records.map { it.key to it.toMap() }.sortedBy { it.first.toString() },
        payslips = snapshot.savedPayslips.mapValues { JSONObject(it.value).toMap() }
    )

    // ---------------- Round-trip ----------------

    @Test
    fun `slucajni snapshot-ovi prezive encode pa decode bez gubitka`() {
        val random = Random(20261003)
        repeat(300) { iteration ->
            val original = randomSnapshot(random)
            val text = codec.encode(original)
            val restored = decoded(text)
            assertEquals("iteracija $iteration", view(original), view(restored))
            // Kanonski oblik: ponovni upis daje isti tekst (ključevi su sortirani, bez šuma).
            assertEquals("iteracija $iteration", text, codec.encode(restored))
        }
    }

    @Test
    fun `slucajni snapshot kroz repozitorijum daje isti izvoz posle vracanja`() {
        val random = Random(77)
        repeat(60) { iteration ->
            val json = codec.encode(randomSnapshot(random))
            val first = testRepository()
            assertEquals("iteracija $iteration", null, first.tryImportFromJson(json))
            val second = testRepository()
            assertEquals("iteracija $iteration", null, second.tryImportFromJson(first.exportCloudBackupJson(null)))
            assertEquals("iteracija $iteration", first.exportCloudBackupJson(null), second.exportCloudBackupJson(null))
        }
    }

    // ---------------- Oštećen, prazan i delimičan JSON ----------------

    private fun sampleJson(): String = codec.encode(randomSnapshot(Random(5)).copy(records = randomSnapshot(Random(6)).records))

    @Test
    fun `svaki prefiks izvoza je necitljiv a ceo je citljiv`() {
        val text = sampleJson()
        assertTrue(text.length > 1_000)
        for (length in 0 until text.length) {
            assertEquals("prefiks $length", SnapshotRejection.UNREADABLE, rejection(text.substring(0, length)))
        }
        assertTrue(codec.decodeChecked(text) is SnapshotDecodeResult.Decoded)
    }

    @Test
    fun `slucajno ostecen izvoz nikad ne baca izuzetak`() {
        val random = Random(20261004)
        val text = sampleJson()
        repeat(2_000) { iteration ->
            val damaged = StringBuilder(text)
            repeat(1 + random.nextInt(6)) {
                val at = random.nextInt(damaged.length)
                when (random.nextInt(5)) {
                    0 -> damaged.deleteCharAt(at)
                    1 -> damaged.insert(at, JUNK[random.nextInt(JUNK.length)])
                    2 -> damaged.setCharAt(at, (32 + random.nextInt(95)).toChar())
                    3 -> damaged.delete(at, minOf(damaged.length, at + random.nextInt(1, 40)))
                    else -> damaged.insert(at, damaged.substring(at, minOf(damaged.length, at + random.nextInt(1, 30))))
                }
                if (damaged.isEmpty()) damaged.append('{')
            }
            try {
                codec.decodeChecked(damaged.toString())
            } catch (e: Exception) {
                fail("iteracija $iteration: ${e::class.simpleName}: ${e.message}\n$damaged")
            }
        }
    }

    @Test
    fun `decode baca izuzetak sa razlogom a decodeChecked ga vraca`() {
        val error = runCatching { codec.decode("{\"x\":1}") }.exceptionOrNull()
        assertTrue(error is SnapshotRejectedException)
        assertEquals(SnapshotRejection.NO_RECOGNIZED_DATA, (error as SnapshotRejectedException).reason)
        assertEquals(SnapshotRejection.NO_RECOGNIZED_DATA, rejection("{}"))
        assertEquals(SnapshotRejection.UNREADABLE, rejection(""))
    }

    // ---------------- Pogrešni tipovi ----------------

    @Test
    fun `pogresan tip bilo kog kljuca ne baca izuzetak`() {
        val original = JSONObject(codec.encode(randomSnapshot(Random(9))))
        val wrongValues = listOf<Any?>(
            JSONObject.NULL, "tekst", "", 12345, -1, 3.14, true, false,
            org.json.JSONArray(), org.json.JSONArray(listOf(1, "a", null)), JSONObject(), JSONObject().put("a", "b"),
            "NaN", "Infinity", "2026-13-45", Long.MAX_VALUE, 1e308
        )
        for (key in original.keys().asSequence().toList()) {
            for (wrong in wrongValues) {
                val copy = JSONObject(original.toString())
                copy.put(key, wrong)
                try {
                    codec.decodeChecked(copy.toString())
                } catch (e: Exception) {
                    fail("$key=$wrong: ${e::class.simpleName}: ${e.message}")
                }
            }
        }
    }

    @Test
    fun `pogresan tip unutar zapisa dana ne baca izuzetak`() {
        val wrongValues = listOf<Any?>(JSONObject.NULL, "x", 9_999_999_999L, -5, 1.5, true, org.json.JSONArray(), JSONObject())
        val fields = listOf(
            "shift", "hours", "shift2", "hours2", "overtime_type", "weekend_overtime_type", "comp_hours_used",
            "holiday_work_override", "holiday_override", "balance_exempt", "sick_pay_rate",
            "regular_shortage_minutes", "source"
        )
        for (field in fields) {
            for (wrong in wrongValues) {
                val day = JSONObject().put("shift", 1).put("hours", 8).put(field, wrong)
                val json = JSONObject().put("pay_coefficient", 2.82).put("2026", JSONObject().put("05", JSONObject().put("04", day)))
                try {
                    val snapshot = decoded(json.toString())
                    snapshot.records.forEach { it.toMap() }
                } catch (e: Exception) {
                    fail("$field=$wrong: ${e::class.simpleName}: ${e.message}")
                }
            }
        }
    }

    @Test
    fun `kljucevi godine meseca i dana van opsega se preskacu`() {
        val badDays = JSONObject()
            .put("00", day()).put("32", day()).put("-1", day()).put("ab", day()).put("29", day()).put("15", day())
        val json = JSONObject()
            .put("pay_coefficient", 2.82)
            .put("1899", JSONObject().put("01", JSONObject().put("01", day())))
            .put("2201", JSONObject().put("01", JSONObject().put("01", day())))
            .put("2026", JSONObject().put("00", JSONObject().put("01", day())).put("13", JSONObject().put("01", day()))
                .put("02", badDays))
        val records = decoded(json.toString()).records
        // 29.02.2026 ne postoji, 00/32/-1/ab nisu dani: ostaje samo 15.02.
        assertEquals(listOf(DayKey(2026, 2, 15)), records.map { it.key })
    }

    private fun day() = JSONObject().put("shift", 1).put("hours", 8)

    // ---------------- Unicode ----------------

    @Test
    fun `Unicode u kljucevima i vrednostima ne lomi dekoder`() {
        val weird = listOf(
            "čćžšđ ČĆŽŠĐ", "😀👍", "‮evil‬", "a\u0000b", "\uD800", "العربية",
            "٢٠٢٦", "２０２６", "\t\n\r\"\\/"
        )
        for (text in weird) {
            val json = JSONObject()
                .put("pay_coefficient", 2.82)
                .put(text, JSONObject().put(text, JSONObject().put(text, day())))
                .put("brigade_type", text)
                .put("default_pay_base_selection_mode", text)
                .put("seniority_input_mode", text)
                .put("minuli_rad_increase_factor", text)
                .put("blood_donation_last_date", text)
                .put("deleted_months", org.json.JSONArray(listOf(text, "2026-05")))
                .put("saved_payslips", JSONObject().put(text, JSONObject()))
            try {
                val snapshot = decoded(json.toString())
                assertEquals(BrigadeType.TROBRIGADA, snapshot.brigadeType)
                assertEquals(listOf(MonthKey(2026, 5)), snapshot.deletedMonths)
                assertTrue(snapshot.savedPayslips.isEmpty())
            } catch (e: Exception) {
                fail("'$text': ${e::class.simpleName}: ${e.message}")
            }
        }
    }

    // ---------------- Ogromne vrednosti ----------------

    @Test
    fun `ogromne vrednosti se uvoze ogranicene i izvoz ostaje citljiv`() {
        val json = JSONObject()
            .put("pay_coefficient", 1e308)
            .put("config_sick_pay_base_hourly", Double.MAX_VALUE)
            .put("config_tax_relief", -1e308)
            .put("config_food_per_day", 1e300)
            .put("initial_comp_balance", Long.MAX_VALUE)
            .put("initial_comp_balance_minutes", 2_147_483_647)
            .put("minuli_rad_start_year", -2_147_483_648)
            .put("initial_vacation_balance", 1e300)
            .put("hourly_value_changes", JSONObject().put("2026", JSONObject().put("05", JSONObject().put("hourly_value", 1e300))))
            .put("monthly_gross_additions", JSONObject().put("2026", JSONObject().put("05", JSONObject().put("bonus_gross", 1e300))))
            .put("2026", JSONObject().put("05", JSONObject().put("04", day().put("hours", 2_000_000_000))))
        val repo = testRepository()
        assertEquals(null, repo.tryImportFromJson(json.toString()))

        assertTrue(repo.payCoefficientValue <= MAX_BASE_PAY)
        assertTrue(repo.taxReliefValue >= 0.0)
        assertTrue(repo.getHourlyValueForMonth(2026, 5) <= MAX_BASE_PAY)
        // Izvoz posle ogranicavanja se opet cita i obracun se ne ruši.
        repo.getMonthlyPayDetails(2026, 5)
        assertEquals(null, testRepository().tryImportFromJson(repo.exportJson()))
    }

    @Test
    fun `broj dana u ogromnom izvozu se ucitava bez problema`() {
        val months = JSONObject()
        for (month in 1..12) {
            val days = JSONObject()
            for (dayOfMonth in 1..28) days.put(dayOfMonth.toString().padStart(2, '0'), day())
            months.put(month.toString().padStart(2, '0'), days)
        }
        val json = JSONObject().put("pay_coefficient", 2.82)
        for (year in 2000..2200) json.put(year.toString(), months)
        assertEquals(201 * 12 * 28, decoded(json.toString()).records.size)
    }

    // ---------------- Duplirani ključevi ----------------

    @Test
    fun `duplirani kljucevi daju ili odbijanje ili poslednju vrednost ali nikad izuzetak`() {
        // org.json u testovima baca izuzetak na duplikat, Android-ov uzima poslednju vrednost:
        // oba ishoda su bezbedna, ali dekoder ne sme da pukne ni da pomeša stanje.
        val texts = listOf(
            "{\"pay_coefficient\": 1.5, \"pay_coefficient\": 2.5}",
            "{\"pay_coefficient\": 2.0, \"2026\": {\"05\": {\"04\": {\"shift\": 1, \"hours\": 8}}}, " +
                "\"2026\": {\"05\": {\"04\": {\"shift\": 2, \"hours\": 8}}}}",
            "{\"pay_coefficient\": 2.0, \"2026\": {\"05\": {\"04\": {\"shift\": 1, \"hours\": 8, \"shift\": 3}}}}",
            "{\"pay_coefficient\": 2.0, \"$SCHEMA_VERSION_KEY\": 1, \"$SCHEMA_VERSION_KEY\": 99}"
        )
        for (text in texts) {
            when (val result = codec.decodeChecked(text)) {
                is SnapshotDecodeResult.Decoded -> {
                    assertTrue(text, result.snapshot.payCoefficient in listOf(1.5, 2.0, 2.5))
                    assertTrue(text, result.snapshot.records.size <= 1)
                }
                is SnapshotDecodeResult.Rejected -> assertTrue(
                    text,
                    result.reason == SnapshotRejection.UNREADABLE || result.reason == SnapshotRejection.NEWER_SCHEMA
                )
            }
        }
    }

    @Test
    fun `isti dan pod arapskim i latinskim ciframa je jedan zapis`() {
        // "٢٠٢٦" čita toIntOrNull kao 2026: ne sme da napravi dupli ili pomešan dan u kalendaru.
        val json = JSONObject()
            .put("pay_coefficient", 2.82)
            .put("2026", JSONObject().put("05", JSONObject().put("04", day())))
            .put("٢٠٢٦", JSONObject().put("05", JSONObject().put("04", day().put("shift", 2))))
        val repo = testRepository()
        assertEquals(null, repo.tryImportFromJson(json.toString()))
        assertEquals(1, repo.getMonthRecords(2026, 5).size)
    }

    // ---------------- Verzija sheme i migracije ----------------

    @Test
    fun `migracije se primenjuju redom od verzije fajla do trenutne`() {
        val applied = mutableListOf<Int>()
        val migrations = SchemaMigrations(
            currentVersion = 4,
            steps = mapOf(
                1 to { json -> applied += 1; json.put("config_tax_percent", json.getDouble("stari_porez")) },
                2 to { json -> applied += 2; json.put("config_food_per_day", 123.0) },
                3 to { json -> applied += 3; json.put("config_tax_relief", 777.0) }
            )
        )
        val migrating = ShiftRepositoryJsonCodec(CONFIG, TEST_CLOCK, migrations)
        val base = { version: Any? ->
            JSONObject().put("pay_coefficient", 2.82).put("stari_porez", 7.0).apply {
                version?.let { put(SCHEMA_VERSION_KEY, it) }
            }.toString()
        }

        fun run(version: Any?): ShiftRepositorySnapshot {
            applied.clear()
            return (migrating.decodeChecked(base(version)) as SnapshotDecodeResult.Decoded).snapshot
        }

        val fromLegacy = run(null)
        assertEquals(listOf(1, 2, 3), applied)
        assertEquals(7.0, fromLegacy.taxPercent, 0.0)
        assertEquals(123.0, fromLegacy.foodPerDay, 0.0)
        assertEquals(777.0, fromLegacy.taxRelief, 0.0)

        run(2)
        assertEquals("verzija 2 preskače korak 1", listOf(2, 3), applied)
        run(4)
        assertEquals("trenutna verzija se ne migrira", emptyList<Int>(), applied)
        assertEquals(
            SnapshotRejection.NEWER_SCHEMA,
            (migrating.decodeChecked(base(5)) as SnapshotDecodeResult.Rejected).reason
        )
    }

    @Test
    fun `nedostajuci korak migracije se prijavljuje a ne preskace`() {
        val migrations = SchemaMigrations(currentVersion = 3, steps = mapOf(2 to { _ -> }))
        assertEquals(listOf(1), migrations.missingSteps())

        val broken = ShiftRepositoryJsonCodec(CONFIG, TEST_CLOCK, migrations)
        val error = runCatching { broken.decodeChecked("{\"pay_coefficient\": 2.82}") }.exceptionOrNull()
        assertTrue(error is IllegalStateException)
    }

    @Test
    fun `trenutna shema nema rupa u migracijama`() {
        assertEquals(emptyList<Int>(), SchemaMigrations().missingSteps())
    }

    // ---------------- Vreme: legacy years_of_service ----------------

    private fun legacyStartYear(clock: Clock, years: Int = 5): Int {
        val json = "{\"years_of_service\": $years, \"pay_coefficient\": 2.82}"
        return (ShiftRepositoryJsonCodec(CONFIG, clock).decodeChecked(json) as SnapshotDecodeResult.Decoded)
            .snapshot.minuliRadStartYear
    }

    private fun clockAt(instant: String, zone: ZoneId = TEST_ZONE): Clock =
        Clock.fixed(OffsetDateTime.parse(instant).toInstant(), zone)

    @Test
    fun `legacy years_of_service pocetna godina zavisi samo od zadatog datuma`() {
        // Staž raste u februaru: januar još pripada prošloj godini.
        assertEquals(2021, legacyStartYear(clockAt("2026-02-01T00:00:00+01:00")))
        assertEquals(2020, legacyStartYear(clockAt("2026-01-31T23:59:59+01:00")))
        assertEquals(2020, legacyStartYear(clockAt("2026-01-01T00:00:00+01:00")))
        // Promena godine: 31.12. i 01.01. daju različit rezultat samo preko granice februara.
        assertEquals(2021, legacyStartYear(clockAt("2026-12-31T23:59:59+01:00")))
        assertEquals(2021, legacyStartYear(clockAt("2027-01-01T00:00:00+01:00")))
        assertEquals(2022, legacyStartYear(clockAt("2027-02-01T00:00:00+01:00")))
    }

    @Test
    fun `legacy years_of_service isti trenutak u dve zone moze dati razlicit datum`() {
        // 23:30 UTC 31.01. je već 01.02. u Beogradu: zona je deo „danas“.
        val instant = "2026-01-31T23:30:00Z"
        assertEquals(2021, legacyStartYear(clockAt(instant, ZoneId.of("Europe/Belgrade"))))
        assertEquals(2020, legacyStartYear(clockAt(instant, ZoneOffset.UTC)))
        assertEquals(2020, legacyStartYear(clockAt(instant, ZoneId.of("America/New_York"))))
    }

    @Test
    fun `legacy years_of_service preko prelaska na letnje racunanje vremena`() {
        // 29.03.2026 u 01:00 UTC Beograd prelazi na letnje vreme (02:00 → 03:00); datum je isti.
        assertEquals(2021, legacyStartYear(clockAt("2026-03-29T00:59:59Z")))
        assertEquals(2021, legacyStartYear(clockAt("2026-03-29T01:00:00Z")))
        assertEquals(2021, legacyStartYear(clockAt("2026-10-25T00:30:00Z")))
        assertEquals(2021, legacyStartYear(clockAt("2026-10-25T01:30:00Z")))
    }

    @Test
    fun `years_of_service nula ili izostao ne trazi datum i ne menja pocetnu godinu`() {
        assertEquals(0, legacyStartYear(TEST_CLOCK, years = 0))
        assertEquals(0, legacyStartYear(TEST_CLOCK, years = -3))
        val withExplicitStart = "{\"years_of_service\": 5, \"minuli_rad_start_year\": 2010, \"pay_coefficient\": 2.82}"
        val snapshot = (codec.decodeChecked(withExplicitStart) as SnapshotDecodeResult.Decoded).snapshot
        assertEquals("eksplicitna godina pobeđuje staru vrednost", 2010, snapshot.minuliRadStartYear)
    }

    @Test
    fun `repozitorijum sa fiksnim satom racuna staz iz istog datuma`() {
        val json = "{\"years_of_service\": 5, \"pay_coefficient\": 2.82}"
        val january = ShiftRepository(clock = clockAt("2026-01-15T10:00:00+01:00"))
        assertEquals(null, january.tryImportFromJson(json))
        assertEquals(2020, january.minuliRadStartYearValue)

        val october = ShiftRepository(clock = TEST_CLOCK)
        assertEquals(null, october.tryImportFromJson(json))
        assertEquals(2021, october.minuliRadStartYearValue)
    }
}
