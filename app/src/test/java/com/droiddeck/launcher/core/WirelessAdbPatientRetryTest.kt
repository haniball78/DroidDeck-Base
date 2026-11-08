package com.droiddeck.launcher.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WirelessAdbPatientRetryTest {

    @Test fun retryScheduleCoversTheObservedLandingWindow() {
        assertEquals(30_000L, WirelessAdbFix.PATIENT_RETRY_DELAY_MS)
        assertEquals(14, WirelessAdbFix.MAX_PATIENT_RETRIES)
        assertEquals(420_000L, WirelessAdbFix.PATIENT_RETRY_WINDOW_MS)
        assertEquals(
            WirelessAdbFix.PATIENT_RETRY_WINDOW_MS,
            WirelessAdbFix.PATIENT_RETRY_DELAY_MS * WirelessAdbFix.MAX_PATIENT_RETRIES,
        )
    }

    @Test fun retryBudgetIsSpentAtFourteen() {
        assertFalse(WirelessAdbFix.patientRetryExhausted(13))
        assertTrue(WirelessAdbFix.patientRetryExhausted(14))
        assertTrue(WirelessAdbFix.patientRetryExhausted(15))
    }

    @Test fun retryDecisionNeedsReadyParkedAndBudget() {
        assertTrue(WirelessAdbFix.shouldPatientRetry(true, true, 0, 0))
        assertTrue(WirelessAdbFix.shouldPatientRetry(true, true, 13, 419_999))
        assertFalse(WirelessAdbFix.shouldPatientRetry(false, true, 0, 0))
        assertFalse(WirelessAdbFix.shouldPatientRetry(true, false, 0, 0))
        assertFalse(WirelessAdbFix.shouldPatientRetry(true, true, 14, 0))
        assertFalse(WirelessAdbFix.shouldPatientRetry(true, true, 0, 420_000))
    }
}
