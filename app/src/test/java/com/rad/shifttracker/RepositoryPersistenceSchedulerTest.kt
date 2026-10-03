package com.rad.shifttracker

import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Bez spavanja: tok se usklađuje bravama (latch), a vremena su samo gornja granica za pad testa —
 * uspešan tok ih nikad ne čeka.
 */
class RepositoryPersistenceSchedulerTest {

    private val caller = Executors.newCachedThreadPool()
    private val gates = mutableListOf<CountDownLatch>()

    @After
    fun tearDown() {
        // Otpusti sve što je ostalo zaključano da nit scheduler-a ne visi posle neuspelog testa.
        gates.forEach { it.countDown() }
        caller.shutdownNow()
    }

    private fun gate(): CountDownLatch = CountDownLatch(1).also { gates += it }

    private fun CountDownLatch.awaitOpen() = check(await(LIMIT_SECONDS, TimeUnit.SECONDS)) { "brava nije otvorena" }

    @Test
    fun `flush ceka da snimanje u toku bude izvrseno`() {
        val started = gate()
        val release = gate()
        val calls = AtomicInteger(0)
        val scheduler = RepositoryPersistenceScheduler(
            work = {
                started.countDown()
                release.awaitOpen()
                calls.incrementAndGet()
            }
        )

        scheduler.schedule()
        started.awaitOpen()
        val flushed = caller.submit(Callable { scheduler.flush(timeoutMillis = LIMIT_MILLIS) })

        // Posao je zaključan, pa flush ne može da bude gotov.
        assertFalse(flushed.isDone)
        release.countDown()
        assertTrue(flushed.get(LIMIT_SECONDS, TimeUnit.SECONDS))
        assertEquals(1, calls.get())
    }

    @Test
    fun `flush prijavljuje neuspeh kad snimanje ne stigne u zadatom roku`() {
        val started = gate()
        val release = gate()
        val failures = mutableListOf<Throwable>()
        val scheduler = RepositoryPersistenceScheduler(
            work = {
                started.countDown()
                release.awaitOpen()
            },
            onFailure = { failures += it }
        )

        scheduler.schedule()
        started.awaitOpen()

        // Posao je zaključan dok se test ne završi: kratak rok mora da istekne, bez obzira na brzinu računara.
        assertFalse(scheduler.flush(timeoutMillis = 1))
        assertEquals(1, failures.size)
        assertTrue(failures.single() is java.util.concurrent.TimeoutException)
    }

    @Test
    fun `flush prijavljuje neuspeh rada`() {
        val failures = mutableListOf<Throwable>()
        val scheduler = RepositoryPersistenceScheduler(
            work = { error("disk failure") },
            onFailure = { failures += it }
        )

        scheduler.schedule()

        assertFalse(scheduler.flush(timeoutMillis = LIMIT_MILLIS))
        assertTrue(failures.any { it.message == "disk failure" })
    }

    @Test
    fun `flush bez izmena ne pokrece rad`() {
        val calls = AtomicInteger(0)
        val scheduler = RepositoryPersistenceScheduler(work = { calls.incrementAndGet() })

        assertTrue(scheduler.flush(timeoutMillis = LIMIT_MILLIS))
        assertEquals(0, calls.get())

        scheduler.schedule()
        assertTrue(scheduler.flush(timeoutMillis = LIMIT_MILLIS))
        assertEquals(1, calls.get())

        // Sve je snimljeno: dodatni flush (npr. pri svakom gašenju ekrana) ne radi ništa.
        assertTrue(scheduler.flush(timeoutMillis = LIMIT_MILLIS))
        assertEquals(1, calls.get())
    }

    @Test
    fun `flush ponovo pokusava kad je cuvanje oznaceno kao neuspelo`() {
        val calls = AtomicInteger(0)
        val scheduler = RepositoryPersistenceScheduler(work = { calls.incrementAndGet() })

        scheduler.schedule()
        assertTrue(scheduler.flush(timeoutMillis = LIMIT_MILLIS))
        assertEquals(1, calls.get())

        scheduler.markDirty()
        assertTrue(scheduler.flush(timeoutMillis = LIMIT_MILLIS))
        assertEquals(2, calls.get())
    }

    @Test
    fun `niz izmena tokom snimanja se spaja u jedno novo snimanje`() {
        val started = gate()
        val release = gate()
        val calls = AtomicInteger(0)
        val scheduler = RepositoryPersistenceScheduler(
            work = {
                // Prvo snimanje čeka; sva sledeća prolaze odmah.
                if (calls.incrementAndGet() == 1) {
                    started.countDown()
                    release.awaitOpen()
                }
            }
        )

        scheduler.schedule()
        started.awaitOpen()
        repeat(5) { scheduler.schedule() }
        release.countDown()

        assertTrue(scheduler.flush(timeoutMillis = LIMIT_MILLIS))
        // Jedno snimanje u toku + tačno jedno naknadno za svih pet izmena.
        assertEquals(2, calls.get())
    }

    @Test
    fun `izuzetak u radu ostavlja stanje nesnimljenim pa flush ponovo pokusava i uspeva`() {
        val calls = AtomicInteger(0)
        val scheduler = RepositoryPersistenceScheduler(
            work = { if (calls.incrementAndGet() == 1) error("prvi put ne uspe") }
        )

        scheduler.schedule()
        // Prvo snimanje pada, flush ga ponavlja (drugi poziv uspeva) i vraća uspeh.
        assertTrue(scheduler.flush(timeoutMillis = LIMIT_MILLIS))
        assertEquals(2, calls.get())
        // Uspelo je: dodatni flush ne radi ništa.
        assertTrue(scheduler.flush(timeoutMillis = LIMIT_MILLIS))
        assertEquals(2, calls.get())
    }

    private companion object {
        const val LIMIT_SECONDS = 30L
        const val LIMIT_MILLIS = 30_000L
    }
}
