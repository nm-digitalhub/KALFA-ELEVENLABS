package me.kalfa.agentconsole.ui.viewmodel

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SingleFlightGateTest {

    @Test
    fun `first acquire wins and the second is refused`() {
        val gate = SingleFlightGate()
        assertTrue(gate.tryAcquire())
        assertFalse("a second dial must not pass while one is in flight", gate.tryAcquire())
    }

    @Test
    fun `release reopens the gate`() {
        val gate = SingleFlightGate()
        assertTrue(gate.tryAcquire())
        gate.release()
        assertTrue("hanging up and dialling again is legitimate", gate.tryAcquire())
    }

    @Test
    fun `release is safe when the gate is not held`() {
        // The ViewModel releases from a `finally`. A failure that unwinds before the
        // acquire would otherwise turn one bug into two.
        val gate = SingleFlightGate()
        gate.release()
        assertFalse(gate.isBusy)
        assertTrue(gate.tryAcquire())
    }

    @Test
    fun `isBusy reports the state without taking it`() {
        val gate = SingleFlightGate()
        assertFalse(gate.isBusy)
        gate.tryAcquire()
        assertTrue(gate.isBusy)
        // Reading must not consume: the UI renders this every recomposition.
        assertTrue(gate.isBusy)
    }

    @Test
    fun `exactly one of many simultaneous taps gets through`() {
        // The reason this is a compare-and-set and not `if (!busy) busy = true`.
        // A check-then-act passes this test only by luck, and only sometimes.
        val threads = 32
        val gate = SingleFlightGate()
        val start = CountDownLatch(1)
        val done = CountDownLatch(threads)
        val winners = AtomicInteger(0)
        val pool = Executors.newFixedThreadPool(threads)
        repeat(threads) {
            pool.execute {
                start.await()
                if (gate.tryAcquire()) winners.incrementAndGet()
                done.countDown()
            }
        }
        start.countDown()
        assertTrue("workers did not finish", done.await(10, TimeUnit.SECONDS))
        pool.shutdown()
        assertEquals("exactly one dial may be placed", 1, winners.get())
    }
}
