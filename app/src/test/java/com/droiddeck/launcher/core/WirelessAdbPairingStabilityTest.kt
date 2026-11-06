package com.droiddeck.launcher.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WirelessAdbPairingStabilityTest {

    @Test fun autoRetryRunsOnANewPort() {
        assertTrue(
            WirelessAdbFix.shouldAutoRetryDaemonStart(paired = true, currentPort = 46615, lastAuthFailedPort = 41057),
        )
    }

    @Test fun autoRetryBlockedOnTheAuthFailedPort() {
        assertFalse(
            WirelessAdbFix.shouldAutoRetryDaemonStart(paired = true, currentPort = 41057, lastAuthFailedPort = 41057),
        )
    }

    @Test fun autoRetryRunsWithNoParkedPort() {
        assertTrue(
            WirelessAdbFix.shouldAutoRetryDaemonStart(paired = true, currentPort = 41057, lastAuthFailedPort = null),
        )
    }

    @Test fun autoRetryBlockedWithoutAPort() {
        assertFalse(
            WirelessAdbFix.shouldAutoRetryDaemonStart(paired = true, currentPort = null, lastAuthFailedPort = 41057),
        )
        assertFalse(
            WirelessAdbFix.shouldAutoRetryDaemonStart(paired = true, currentPort = null, lastAuthFailedPort = null),
        )
    }

    @Test fun autoRetryBlockedWhenUnpaired() {
        assertFalse(
            WirelessAdbFix.shouldAutoRetryDaemonStart(paired = false, currentPort = 46615, lastAuthFailedPort = 41057),
        )
    }

    @Test fun firstChangedPortFindsTheNewPort() {
        assertEquals(
            46615,
            WirelessAdbFix.firstChangedPort(41057, listOf(41057, 41057, 46615)),
        )
    }

    @Test fun firstChangedPortSkipsMissingObservations() {
        assertEquals(
            40035,
            WirelessAdbFix.firstChangedPort(null, listOf(null, 40035)),
        )
    }

    @Test fun firstChangedPortIsNullWithoutAChange() {
        assertNull(WirelessAdbFix.firstChangedPort(41057, listOf(41057, 41057, null)))
        assertNull(WirelessAdbFix.firstChangedPort(41057, emptyList()))
        assertNull(WirelessAdbFix.firstChangedPort(null, listOf(null, null)))
    }
}
