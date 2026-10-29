package com.droiddeck.launcher.session

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlainResumePolicyTest {
    @Test
    fun bridgeUnavailableFallsBackWhenNoTweaksAreActive() {
        assertTrue(
            ResumeOptimizationPolicy.fallbackWithoutOptimizations(
                ResumeBlock.BRIDGE_UNAVAILABLE,
                tweaksActive = false,
            ),
        )
    }

    @Test
    fun bridgeUnavailableDoesNotFallBackWhenTweaksAreActive() {
        assertFalse(
            ResumeOptimizationPolicy.fallbackWithoutOptimizations(
                ResumeBlock.BRIDGE_UNAVAILABLE,
                tweaksActive = true,
            ),
        )
    }

    @Test
    fun otherBlocksNeverFallBack() {
        assertFalse(
            ResumeOptimizationPolicy.fallbackWithoutOptimizations(
                ResumeBlock.BATTERY_LOW_SAFETY,
                tweaksActive = false,
            ),
        )
        assertFalse(
            ResumeOptimizationPolicy.fallbackWithoutOptimizations(
                ResumeBlock.THERMAL_SEVERE_SAFETY,
                tweaksActive = false,
            ),
        )
        assertFalse(
            ResumeOptimizationPolicy.fallbackWithoutOptimizations(
                ResumeBlock.EXPLICIT_REQUIRED,
                tweaksActive = false,
            ),
        )
        assertFalse(
            ResumeOptimizationPolicy.fallbackWithoutOptimizations(
                ResumeBlock.NONE,
                tweaksActive = false,
            ),
        )
    }

    @Test
    fun plainSessionResumeWithoutOptimizationsIsAllowed() {
        val check = SuspendPolicy.checkResume(
            kind = SuspendKind.USER,
            wantsOptimizations = false,
            bridgeOk = false,
            batteryLevel = 80,
            charging = false,
            thermalStatus = 0,
            explicitResume = true,
        )
        assertTrue(check.allowed)
    }
}
