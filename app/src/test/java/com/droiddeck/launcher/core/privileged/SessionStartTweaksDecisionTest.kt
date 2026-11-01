package com.droiddeck.launcher.core.privileged

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionStartTweaksDecisionTest {

    @Test
    fun noPendingJournalApplies() {
        assertTrue(SessionStartTweaksDecision.shouldApply(false, false, false))
        assertTrue(SessionStartTweaksDecision.shouldApply(false, false, true))
    }

    @Test
    fun successfulRollbackAppliesEvenWhenStale() {
        assertTrue(SessionStartTweaksDecision.shouldApply(true, true, true))
    }

    @Test
    fun failedRollbackAppliesOnlyWhenNotStale() {
        assertTrue(SessionStartTweaksDecision.shouldApply(true, false, false))
        assertFalse(SessionStartTweaksDecision.shouldApply(true, false, true))
    }
}
