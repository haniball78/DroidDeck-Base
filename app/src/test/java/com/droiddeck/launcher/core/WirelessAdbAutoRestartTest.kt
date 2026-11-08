package com.droiddeck.launcher.core

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WirelessAdbAutoRestartTest {

    @Test fun restartRunsWhenCurrentPortIsTheFailedOne() {
        assertTrue(
            WirelessAdbFix.shouldAutoRestartWireless(
                paired = true, currentPort = 35513, lastAuthFailedPort = 35513, restartedPorts = emptySet(),
            ),
        )
    }

    @Test fun restartBlockedWhenUnpaired() {
        assertFalse(
            WirelessAdbFix.shouldAutoRestartWireless(
                paired = false, currentPort = 35513, lastAuthFailedPort = 35513, restartedPorts = emptySet(),
            ),
        )
    }

    @Test fun restartBlockedWithoutAPort() {
        assertFalse(
            WirelessAdbFix.shouldAutoRestartWireless(
                paired = true, currentPort = null, lastAuthFailedPort = 35513, restartedPorts = emptySet(),
            ),
        )
    }

    @Test fun restartBlockedWhenPortAlreadyChanged() {
        assertFalse(
            WirelessAdbFix.shouldAutoRestartWireless(
                paired = true, currentPort = 33845, lastAuthFailedPort = 35513, restartedPorts = emptySet(),
            ),
        )
    }

    @Test fun restartBlockedWhenPortAlreadyRestarted() {
        assertFalse(
            WirelessAdbFix.shouldAutoRestartWireless(
                paired = true, currentPort = 35513, lastAuthFailedPort = 35513, restartedPorts = setOf(35513),
            ),
        )
    }

    @Test fun restartBlockedForANewFailedPortOnceTheSingleRestartIsSpent() {
        assertFalse(
            WirelessAdbFix.shouldAutoRestartWireless(
                paired = true, currentPort = 40035, lastAuthFailedPort = 40035, restartedPorts = setOf(35513),
            ),
        )
    }

    @Test fun restartBlockedAfterTheCap() {
        assertFalse(
            WirelessAdbFix.shouldAutoRestartWireless(
                paired = true, currentPort = 40035, lastAuthFailedPort = 40035,
                restartedPorts = setOf(35513),
            ),
        )
    }
}
