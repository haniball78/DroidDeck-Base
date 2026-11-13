package com.droiddeck.launcher.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WirelessAdbPatientRetryTest {

    @Test fun retryScheduleCoversTheObservedLandingWindow() {
        assertEquals(10_000L, WirelessAdbFix.PATIENT_RETRY_DELAY_MS)
        assertEquals(42, WirelessAdbFix.MAX_PATIENT_RETRIES)
        assertEquals(420_000L, WirelessAdbFix.PATIENT_RETRY_WINDOW_MS)
        assertEquals(
            WirelessAdbFix.PATIENT_RETRY_WINDOW_MS,
            WirelessAdbFix.PATIENT_RETRY_DELAY_MS * WirelessAdbFix.MAX_PATIENT_RETRIES,
        )
    }

    @Test fun retryBudgetIsSpentAtFortyTwo() {
        assertFalse(WirelessAdbFix.patientRetryExhausted(41))
        assertTrue(WirelessAdbFix.patientRetryExhausted(42))
        assertTrue(WirelessAdbFix.patientRetryExhausted(43))
    }

    @Test fun retryDecisionNeedsReadyParkedAndBudget() {
        assertTrue(WirelessAdbFix.shouldPatientRetry(true, true, 0, 0))
        assertTrue(WirelessAdbFix.shouldPatientRetry(true, true, 41, 419_999))
        assertFalse(WirelessAdbFix.shouldPatientRetry(false, true, 0, 0))
        assertFalse(WirelessAdbFix.shouldPatientRetry(true, false, 0, 0))
        assertFalse(WirelessAdbFix.shouldPatientRetry(true, true, 42, 0))
        assertFalse(WirelessAdbFix.shouldPatientRetry(true, true, 0, 420_000))
    }

    @Test fun revokedIdentityNeedsVerifiedKeyAndOneAttempt() {
        assertFalse(WirelessAdbFix.isRevokedIdentity(false, 1))
        assertFalse(WirelessAdbFix.isRevokedIdentity(true, 0))
        assertTrue(WirelessAdbFix.isRevokedIdentity(true, 1))
        assertTrue(WirelessAdbFix.isRevokedIdentity(true, 14))
    }

    @Test fun portChangeAbandonsParkedPort() {
        assertEquals(40785, WirelessAdbFix.firstChangedPort(41409, listOf(40785)))
        assertNull(WirelessAdbFix.firstChangedPort(41409, listOf(41409)))
        assertNull(WirelessAdbFix.firstChangedPort(41409, listOf(null)))
    }
}
