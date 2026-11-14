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
}
