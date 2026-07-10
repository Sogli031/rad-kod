package com.rad.shifttracker

import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RepositoryPersistenceSchedulerTest {
    @Test
    fun `flush ceka da poslednje cuvanje bude izvrseno`() {
        val calls = AtomicInteger(0)
        val scheduler = RepositoryPersistenceScheduler(
            work = {
                Thread.sleep(20)
                calls.incrementAndGet()
            }
        )

        scheduler.schedule()

        assertTrue(scheduler.flush(timeoutMillis = 1_000))
        assertTrue(calls.get() >= 1)
    }

    @Test
    fun `flush prijavljuje neuspeh rada`() {
        val scheduler = RepositoryPersistenceScheduler(
            work = { error("disk failure") }
        )

        assertFalse(scheduler.flush(timeoutMillis = 1_000))
    }
}
