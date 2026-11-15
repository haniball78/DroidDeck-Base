package com.droiddeck.launcher.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DeadManSessionTest {
    @Test
    fun freshStartClaimsArm() {
        val session = DeadManSession()
        assertTrue(session.start(3))
        assertTrue(session.isRunning)
        assertEquals(3, session.activeGeneration)
    }

    @Test
    fun takeoverUpdatesGenerationWhileRunning() {
        val session = DeadManSession()
        assertTrue(session.start(3))
        assertFalse(session.start(4))
        assertTrue(session.isRunning)
        assertEquals(4, session.activeGeneration)
    }

    @Test
    fun stopOwnGenerationWhenNotRunning() {
        val session = DeadManSession()
        session.start(3)
        assertTrue(session.stop(3, sessionRunning = false))
        assertFalse(session.isRunning)
    }

    @Test
    fun stopStaleGenerationNeverDisarmsNewerWatchdog() {
        val session = DeadManSession()
        session.start(4)
        assertFalse(session.stop(3, sessionRunning = false))
        assertTrue(session.isRunning)
        assertEquals(4, session.activeGeneration)
    }

    @Test
    fun stopWhileRunningRefused() {
        val session = DeadManSession()
        session.start(3)
        assertFalse(session.stop(3, sessionRunning = true))
        assertTrue(session.isRunning)
    }

    @Test
    fun takenOverSessionCanStillBeStopped() {
        val session = DeadManSession()
        session.start(3)
        session.start(4)
        assertFalse(session.stop(3, sessionRunning = false))
        assertTrue(session.stop(4, sessionRunning = false))
        assertFalse(session.isRunning)
    }

    @Test
    fun abortStartReleasesClaim() {
        val session = DeadManSession()
        assertTrue(session.start(3))
        assertTrue(session.abortStart(3))
        assertFalse(session.isRunning)
        assertEquals(0, session.activeGeneration)
    }

    @Test
    fun abortStartIgnoresNewerTakeover() {
        val session = DeadManSession()
        session.start(3)
        session.start(4)
        assertFalse(session.abortStart(3))
        assertTrue(session.isRunning)
        assertEquals(4, session.activeGeneration)
    }

    @Test
    fun clearGenerationOnlyForCurrentGeneration() {
        val session = DeadManSession()
        session.start(3)
        assertFalse(session.clearGeneration(4))
        assertEquals(3, session.activeGeneration)
        assertTrue(session.clearGeneration(3))
        assertEquals(0, session.activeGeneration)
    }

    @Test
    fun clearGenerationAfterTakeoverKeepsNewerGeneration() {
        val session = DeadManSession()
        session.start(3)
        session.stop(3, sessionRunning = false)
        session.start(4)
        assertFalse(session.clearGeneration(3))
        assertEquals(4, session.activeGeneration)
    }
}
