package com.droiddeck.launcher.core.privileged

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AdvancedGeneralFixesTest {

    @Test
    fun safeRestoreSucceedsOnlyWhenBothEnginesSucceed() {
        assertTrue(SafeRestoreResult.succeeded(true, true))
        assertFalse(SafeRestoreResult.succeeded(true, false))
        assertFalse(SafeRestoreResult.succeeded(false, true))
        assertFalse(SafeRestoreResult.succeeded(false, false))
    }

    @Test
    fun safeRestoreResultFieldsCarryOutcomeAndJournalRemainder() {
        val fields = SafeRestoreResult.resultFields(true, false, 3)
        assertEquals("true", fields["game_mode_ok"])
        assertEquals("false", fields["freezer_ok"])
        assertEquals("3", fields["journal_remaining"])
        assertEquals("false", fields["succeeded"])
        val success = SafeRestoreResult.resultFields(true, true, 0)
        assertEquals("true", success["succeeded"])
        assertEquals("0", success["journal_remaining"])
    }

    @Test
    fun staleGenerationIsOnlyARealSessionWithADifferentGeneration() {
        // Test apply (999) followed by a real session (1): stale.
        assertTrue(isStaleGeneration(true, 999, 1))
        // Same generation, already active: not stale.
        assertFalse(isStaleGeneration(true, 1, 1))
        // Not applied at all: not stale.
        assertFalse(isStaleGeneration(false, 999, 1))
        // Generation 0 means "no session generation": not stale.
        assertFalse(isStaleGeneration(true, 999, 0))
    }
}
