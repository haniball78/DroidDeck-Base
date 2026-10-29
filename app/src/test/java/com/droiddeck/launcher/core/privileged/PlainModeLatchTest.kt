package com.droiddeck.launcher.core.privileged

import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class PlainModeLatchTest {
    @Before
    fun resetLatch() {
        PrivilegedBridge.resetPlainModeLatch()
    }

    @After
    fun clearLatch() {
        PrivilegedBridge.resetPlainModeLatch()
    }

    @Test
    fun threeFailedProbesLatchPlainMode() {
        assertFalse(PrivilegedBridge.isPlainModeLatched())
        repeat(PrivilegedBridge.MAX_FAILED_PROBES_BEFORE_PLAIN_MODE) {
            PrivilegedBridge.refresh()
        }
        assertTrue(PrivilegedBridge.isPlainModeLatched())
        assertFalse(PrivilegedBridge.isPrivilegedAvailable())
    }

    @Test
    fun latchedRefreshStaysUnavailableWithoutNewProbes() {
        repeat(PrivilegedBridge.MAX_FAILED_PROBES_BEFORE_PLAIN_MODE) {
            PrivilegedBridge.refresh()
        }
        assertTrue(PrivilegedBridge.isPlainModeLatched())
        val status = PrivilegedBridge.refresh()
        assertFalse(status.isActive)
        assertTrue(PrivilegedBridge.isPlainModeLatched())
    }

    @Test
    fun resetClearsTheLatch() {
        repeat(PrivilegedBridge.MAX_FAILED_PROBES_BEFORE_PLAIN_MODE) {
            PrivilegedBridge.refresh()
        }
        assertTrue(PrivilegedBridge.isPlainModeLatched())
        PrivilegedBridge.resetPlainModeLatch()
        assertFalse(PrivilegedBridge.isPlainModeLatched())
    }
}
