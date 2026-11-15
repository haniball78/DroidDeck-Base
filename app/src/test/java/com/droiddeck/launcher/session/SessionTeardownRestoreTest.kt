package com.droiddeck.launcher.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class SessionTeardownRestoreTest {
    @Test
    fun ownGenerationDisarmsWhenNoSessionRunning() {
        assertTrue(SessionService.shouldDisarmDeadMan(disarmingGen = 3, activeGen = 3, sessionRunning = false))
    }

    @Test
    fun staleGenerationNeverDisarmsNewerWatchdog() {
        assertFalse(SessionService.shouldDisarmDeadMan(disarmingGen = 3, activeGen = 4, sessionRunning = false))
    }

    @Test
    fun neverArmedWatchdogIsNotDisarmed() {
        assertFalse(SessionService.shouldDisarmDeadMan(disarmingGen = 0, activeGen = 0, sessionRunning = false))
        assertFalse(SessionService.shouldDisarmDeadMan(disarmingGen = 3, activeGen = 0, sessionRunning = false))
    }

    @Test
    fun runningSessionKeepsItsWatchdog() {
        assertFalse(SessionService.shouldDisarmDeadMan(disarmingGen = 3, activeGen = 3, sessionRunning = true))
    }

    @Test
    fun retryRestoreRefusedWhileSessionRunning() {
        assertFalse(SessionService.retryRestoreAllowed(sessionRunning = true))
        assertTrue(SessionService.retryRestoreAllowed(sessionRunning = false))
    }

    @Test
    fun cancelCountdownActionOnlyWhenMonitorActive() {
        assertTrue(SessionService.cancelCountdownActionAvailable(safetyMonitorActive = true, retryRestoreAction = false))
        assertFalse(SessionService.cancelCountdownActionAvailable(safetyMonitorActive = false, retryRestoreAction = false))
        assertFalse(SessionService.cancelCountdownActionAvailable(safetyMonitorActive = true, retryRestoreAction = true))
        assertFalse(SessionService.cancelCountdownActionAvailable(safetyMonitorActive = false, retryRestoreAction = true))
    }

    @Test
    fun retryRestoreIntentTargetsSessionServiceWithRetryAction() {
        val intent = SessionService.retryRestoreIntent(RuntimeEnvironment.getApplication())
        assertEquals("com.droiddeck.launcher.RETRY_RESTORE", intent.action)
        assertEquals(SessionService::class.java.name, intent.component?.className)
    }

    @Test
    fun failedTeardownRestoreAllowsRetryThenDone() {
        val gate = ExitGate()
        assertTrue(gate.request(ExitReason.USER, 5))
        gate.beginTeardown(5)
        assertTrue(gate.beginRestore(5))
        gate.restoreFailed()
        assertEquals(ExitState.RESTORE_FAILED, gate.state)
        assertTrue(gate.retryRestore())
        assertEquals(ExitState.RESTORING, gate.state)
        gate.restoreSucceeded()
        assertEquals(ExitState.DONE, gate.state)
        assertFalse(gate.retryRestore())
    }

    @Test
    fun retryIsRefusedUnlessRestoreFailed() {
        val gate = ExitGate()
        assertFalse(gate.retryRestore())
        assertTrue(gate.request(ExitReason.USER, 5))
        assertFalse(gate.retryRestore())
        gate.beginTeardown(5)
        assertFalse(gate.retryRestore())
    }

    @Test
    fun beginRestoreReentryWithMatchingGenerationIsAllowed() {
        val gate = ExitGate()
        assertTrue(gate.request(ExitReason.USER, 5))
        gate.beginTeardown(5)
        assertTrue(gate.beginRestore(5))
        assertEquals(ExitState.RESTORING, gate.state)
        assertTrue(gate.beginRestore(5))
        assertFalse(gate.beginRestore(6))
    }

    @Test
    fun staleTeardownRestoreRefusedAfterNewSessionClaimsGate() {
        val gate = ExitGate()
        assertTrue(gate.request(ExitReason.USER, 5))
        gate.beginTeardown(5)
        assertTrue(gate.beginRestore(5))
        gate.restoreSucceeded()
        assertEquals(ExitState.DONE, gate.state)
        gate.reset()
        assertTrue(gate.request(ExitReason.USER, 6))
        gate.beginTeardown(6)
        assertFalse(gate.beginRestore(5))
        assertTrue(gate.beginRestore(6))
    }

    @Test
    fun beginRestoreRefusedFromIdle() {
        val gate = ExitGate()
        assertFalse(gate.beginRestore(5))
        assertFalse(gate.beginRestore(0))
    }

    @Test
    fun beginRestoreAllowedFromRequested() {
        val gate = ExitGate()
        assertTrue(gate.request(ExitReason.USER, 5))
        assertTrue(gate.beginRestore(5))
        assertEquals(ExitState.RESTORING, gate.state)
    }
}
