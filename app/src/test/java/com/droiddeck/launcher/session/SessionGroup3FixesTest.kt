package com.droiddeck.launcher.session

import com.droiddeck.launcher.core.privileged.ShutdownDeadline
import java.util.concurrent.CountDownLatch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionGroup3FixesTest {
    @Test
    fun teardownRestoreAllowedForCurrentGeneration() {
        assertTrue(SessionService.teardownRestoreAllowed(stoppedGen = 5, activeGen = 5))
    }

    @Test
    fun teardownRestoreRefusedForStaleGeneration() {
        assertFalse(SessionService.teardownRestoreAllowed(stoppedGen = 5, activeGen = 6))
    }

    @Test
    fun teardownRestoreAllowedWhenGenerationUnknown() {
        assertTrue(SessionService.teardownRestoreAllowed(stoppedGen = 0, activeGen = 6))
        assertTrue(SessionService.teardownRestoreAllowed(stoppedGen = 5, activeGen = 0))
    }

    @Test
    fun awaitTeardownReturnsTrueWithNoLatch() {
        assertTrue(SessionService.awaitTeardown(null, 1000L))
    }

    @Test
    fun awaitTeardownReturnsTrueWhenLatchAlreadyDone() {
        assertTrue(SessionService.awaitTeardown(CountDownLatch(0), 1000L))
    }

    @Test
    fun awaitTeardownReturnsTrueWhenLatchCountsDown() {
        val latch = CountDownLatch(1)
        Thread({
            Thread.sleep(50)
            latch.countDown()
        }, "group3-latch-countdown").start()
        assertTrue(SessionService.awaitTeardown(latch, 5000L))
    }

    @Test
    fun awaitTeardownReturnsFalseOnTimeout() {
        val latch = CountDownLatch(1)
        val start = System.currentTimeMillis()
        assertFalse(SessionService.awaitTeardown(latch, 50L))
        assertTrue(System.currentTimeMillis() - start < 5000L)
        assertEquals(1L, latch.count)
    }

    @Test
    fun joinWithinDeadlineReturnsQuicklyWhenThreadAlreadyFinished() {
        val thread = Thread({}).apply { start(); join() }
        val start = System.currentTimeMillis()
        assertTrue(ShutdownDeadline.joinWithinDeadline(thread, start, start))
        assertTrue(System.currentTimeMillis() - start < 1000L)
    }

    @Test
    fun joinWithinDeadlineWaitsForShortThread() {
        val thread = Thread({ Thread.sleep(50) }).apply { start() }
        assertTrue(ShutdownDeadline.joinWithinDeadline(thread, 0L, 0L))
        assertFalse(thread.isAlive)
    }

    @Test
    fun joinWithinDeadlineGivesUpAtDeadline() {
        val thread = Thread({ Thread.sleep(5000) }).apply { isDaemon = true; start() }
        val start = System.currentTimeMillis()
        assertFalse(ShutdownDeadline.joinWithinDeadline(thread, 0L, 1900L))
        assertTrue(System.currentTimeMillis() - start < 1000L)
        assertTrue(thread.isAlive)
    }
}
