package com.droiddeck.launcher.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WirelessAdbPairingRedesignTest {

    @Test fun finishingWhenReadyAuthAndRestartStillPossible() {
        assertEquals(
            FailureBoxMode.FINISHING,
            ConnectionCardStateResolver.failureBoxMode(
                ConnectionCardState.READY_TO_START, hasRecordedFailure = true,
                isAuthFailure = true, autoRestartFailed = false,
            ),
        )
    }

    @Test fun failureWhenReadyAuthAndRestartFailed() {
        assertEquals(
            FailureBoxMode.FAILURE,
            ConnectionCardStateResolver.failureBoxMode(
                ConnectionCardState.READY_TO_START, hasRecordedFailure = true,
                isAuthFailure = true, autoRestartFailed = true,
            ),
        )
    }

    @Test fun failureWhenAuthRejectedEvenWithRestartLeft() {
        assertEquals(
            FailureBoxMode.FAILURE,
            ConnectionCardStateResolver.failureBoxMode(
                ConnectionCardState.AUTH_REJECTED, hasRecordedFailure = true,
                isAuthFailure = true, autoRestartFailed = false,
            ),
        )
    }

    @Test fun failureWhenReadyButNotAuth() {
        assertEquals(
            FailureBoxMode.FAILURE,
            ConnectionCardStateResolver.failureBoxMode(
                ConnectionCardState.READY_TO_START, hasRecordedFailure = true,
                isAuthFailure = false, autoRestartFailed = false,
            ),
        )
    }

    @Test fun hiddenWhenNoFailure() {
        assertEquals(
            FailureBoxMode.HIDDEN,
            ConnectionCardStateResolver.failureBoxMode(
                ConnectionCardState.READY_TO_START, hasRecordedFailure = false,
                isAuthFailure = false, autoRestartFailed = false,
            ),
        )
    }

    @Test fun hiddenWhenActive() {
        assertEquals(
            FailureBoxMode.HIDDEN,
            ConnectionCardStateResolver.failureBoxMode(
                ConnectionCardState.ACTIVE, hasRecordedFailure = true,
                isAuthFailure = true, autoRestartFailed = false,
            ),
        )
    }

    @Test fun probeDelaysAreIncreasingAndBounded() {
        assertEquals(2_000L, WirelessAdbFix.samePortProbeDelay(0))
        assertEquals(5_000L, WirelessAdbFix.samePortProbeDelay(1))
        assertEquals(10_000L, WirelessAdbFix.samePortProbeDelay(2))
        assertEquals(20_000L, WirelessAdbFix.samePortProbeDelay(3))
        assertNull(WirelessAdbFix.samePortProbeDelay(4))
        assertNull(WirelessAdbFix.samePortProbeDelay(-1))
    }

    @Test fun autoRestartCapIsThree() {
        assertEquals(3, WirelessAdbFix.MAX_AUTO_RESTARTS)
    }
}
