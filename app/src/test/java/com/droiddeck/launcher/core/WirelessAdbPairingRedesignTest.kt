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
                patientRetryExhausted = false,
            ),
        )
    }

    @Test fun finishingWhenReadyAuthRestartFailedButPatientRetryRunning() {
        // The restart budget is spent, but the patient retry is still
        // waiting for the key to land: the red box stays hidden until
        // nothing automatic is left to try.
        assertEquals(
            FailureBoxMode.FINISHING,
            ConnectionCardStateResolver.failureBoxMode(
                ConnectionCardState.READY_TO_START, hasRecordedFailure = true,
                isAuthFailure = true, autoRestartFailed = true,
                patientRetryExhausted = false,
            ),
        )
    }

    @Test fun failureWhenReadyAuthAndRestartFailed() {
        assertEquals(
            FailureBoxMode.FAILURE,
            ConnectionCardStateResolver.failureBoxMode(
                ConnectionCardState.READY_TO_START, hasRecordedFailure = true,
                isAuthFailure = true, autoRestartFailed = true,
                patientRetryExhausted = true,
            ),
        )
    }

    @Test fun failureWhenAuthRejectedEvenWithRestartLeft() {
        assertEquals(
            FailureBoxMode.FAILURE,
            ConnectionCardStateResolver.failureBoxMode(
                ConnectionCardState.AUTH_REJECTED, hasRecordedFailure = true,
                isAuthFailure = true, autoRestartFailed = false,
                patientRetryExhausted = false,
            ),
        )
    }

    @Test fun failureWhenReadyButNotAuth() {
        assertEquals(
            FailureBoxMode.FAILURE,
            ConnectionCardStateResolver.failureBoxMode(
                ConnectionCardState.READY_TO_START, hasRecordedFailure = true,
                isAuthFailure = false, autoRestartFailed = false,
                patientRetryExhausted = true,
            ),
        )
    }

    @Test fun hiddenWhenNoFailure() {
        assertEquals(
            FailureBoxMode.HIDDEN,
            ConnectionCardStateResolver.failureBoxMode(
                ConnectionCardState.READY_TO_START, hasRecordedFailure = false,
                isAuthFailure = false, autoRestartFailed = false,
                patientRetryExhausted = false,
            ),
        )
    }

    @Test fun hiddenWhenActive() {
        assertEquals(
            FailureBoxMode.HIDDEN,
            ConnectionCardStateResolver.failureBoxMode(
                ConnectionCardState.ACTIVE, hasRecordedFailure = true,
                isAuthFailure = true, autoRestartFailed = false,
                patientRetryExhausted = false,
            ),
        )
    }

    @Test fun probeDelaysAreIncreasingAndBounded() {
        assertEquals(2_000L, WirelessAdbFix.samePortProbeDelay(0))
        assertEquals(5_000L, WirelessAdbFix.samePortProbeDelay(1))
        assertEquals(10_000L, WirelessAdbFix.samePortProbeDelay(2))
        assertEquals(20_000L, WirelessAdbFix.samePortProbeDelay(3))
        assertEquals(30_000L, WirelessAdbFix.samePortProbeDelay(4))
        assertNull(WirelessAdbFix.samePortProbeDelay(5))
        assertNull(WirelessAdbFix.samePortProbeDelay(-1))
    }

    @Test fun autoRestartCapIsOne() {
        assertEquals(1, WirelessAdbFix.MAX_AUTO_RESTARTS)
    }
}
