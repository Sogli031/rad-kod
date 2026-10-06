package com.rad.shifttracker

import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import com.rad.shifttracker.repo.ShiftRepository

/**
 * Stalno „danas“ za testove (03.10.2026, Beograd): rezultati ne zavise od dana pokretanja,
 * pa ni od prelaska granice zadržavanja istorije ili promene godine.
 */
internal val TEST_ZONE: ZoneId = ZoneId.of("Europe/Belgrade")

internal val TEST_CLOCK: Clock = Clock.fixed(Instant.parse("2026-10-03T10:00:00Z"), TEST_ZONE)

/** Repozitorijum sa fiksnim vremenom; zamena za `ShiftRepository()` u testovima. */
internal fun testRepository(clock: Clock = TEST_CLOCK): ShiftRepository = ShiftRepository(clock = clock)
