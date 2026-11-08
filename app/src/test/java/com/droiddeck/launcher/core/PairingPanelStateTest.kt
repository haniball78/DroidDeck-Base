package com.droiddeck.launcher.core

import com.droiddeck.launcher.core.WirelessAdbPairingService.Stage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PairingPanelStateTest {
    @Test
    fun `manual port hidden while idle`() {
        assertFalse(WirelessAdbFix.shouldShowManualPort(Stage.Idle, false))
    }

    @Test
    fun `manual port hidden on a clean code prompt`() {
        assertFalse(WirelessAdbFix.shouldShowManualPort(Stage.CodeNeeded(), false))
    }

    @Test
    fun `manual port shown when the code prompt carries an error`() {
        assertTrue(WirelessAdbFix.shouldShowManualPort(Stage.CodeNeeded(error = "bad code"), false))
    }

    @Test
    fun `manual port shown on failure`() {
        assertTrue(WirelessAdbFix.shouldShowManualPort(Stage.Failed("x"), false))
    }

    @Test
    fun `manual port stays shown once revealed`() {
        assertTrue(WirelessAdbFix.shouldShowManualPort(Stage.Idle, true))
    }

    @Test
    fun `fullscreen window is fullscreen`() {
        assertEquals("fullscreen", WirelessAdbFix.splitHalfFromBounds(0, 2400, 2400))
    }

    @Test
    fun `upper half is top`() {
        assertEquals("top", WirelessAdbFix.splitHalfFromBounds(0, 1200, 2400))
    }

    @Test
    fun `lower half is bottom`() {
        assertEquals("bottom", WirelessAdbFix.splitHalfFromBounds(1200, 1200, 2400))
    }

    @Test
    fun `zero height is unknown`() {
        assertEquals("unknown", WirelessAdbFix.splitHalfFromBounds(0, 0, 2400))
    }
}
