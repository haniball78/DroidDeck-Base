package com.droiddeck.launcher.core

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NotificationAlertTest {
    @Test
    fun `first CodeNeeded post cancels and notifies`() {
        assertTrue(WirelessAdbPairingService.shouldCancelBeforeCodePost(false))
    }

    @Test
    fun `later CodeNeeded posts update in place without cancel`() {
        assertFalse(WirelessAdbPairingService.shouldCancelBeforeCodePost(true))
    }

    @Test
    fun `never posted reposts`() {
        assertTrue(WirelessAdbPairingService.shouldRepostCode(0L, null, 1_000L, null))
    }

    @Test
    fun `identical error inside the debounce window does not repost`() {
        val t = 1_000L
        assertFalse(
            WirelessAdbPairingService.shouldRepostCode(
                t, "e", t + WirelessAdbPairingService.REPOST_DEBOUNCE_MS - 1, "e",
            ),
        )
    }

    @Test
    fun `identical error at the debounce boundary reposts`() {
        val t = 1_000L
        assertTrue(
            WirelessAdbPairingService.shouldRepostCode(
                t, "e", t + WirelessAdbPairingService.REPOST_DEBOUNCE_MS, "e",
            ),
        )
    }

    @Test
    fun `different error reposts immediately`() {
        assertTrue(WirelessAdbPairingService.shouldRepostCode(1_000L, "e1", 2_000L, "e2"))
    }
}
