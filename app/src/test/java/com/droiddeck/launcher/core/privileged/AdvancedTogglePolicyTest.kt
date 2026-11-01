package com.droiddeck.launcher.core.privileged

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AdvancedTogglePolicyTest {

    @Test
    fun toggleEnabledOnlyWhenMasterOnAndNoSessionRunning() {
        assertTrue(AdvancedTogglePolicy.enabled(true, false))
        assertTrue(AdvancedTogglePolicy.enabled(true, false, true))
        assertFalse(AdvancedTogglePolicy.enabled(true, true))
        assertFalse(AdvancedTogglePolicy.enabled(false, false))
        assertFalse(AdvancedTogglePolicy.enabled(true, false, false))
        assertFalse(AdvancedTogglePolicy.enabled(false, true, true))
    }
}
