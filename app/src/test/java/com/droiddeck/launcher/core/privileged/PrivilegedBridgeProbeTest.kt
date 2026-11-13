package com.droiddeck.launcher.core.privileged

import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class PrivilegedBridgeProbeTest {

    @Test
    fun probeExit255IsUnsupportedNotFailed() {
        assertEquals("unsupported", PrivilegedBridge.probeExecEvent("cmd power get-fixed-performance-mode-enabled", 255))
        assertEquals("failed", PrivilegedBridge.probeExecEvent("cmd power get-fixed-performance-mode-enabled", 1))
        assertEquals("succeeded", PrivilegedBridge.probeExecEvent("cmd power get-fixed-performance-mode-enabled", 0))
        assertEquals("failed", PrivilegedBridge.probeExecEvent("cmd power set-fixed-performance-mode-enabled true", 255))
        assertEquals("failed", PrivilegedBridge.probeExecEvent("settings get global background_process_limit", 255))
    }
}
