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
    fun thermalSevereFallsBackWhenNoTweaksAreActive() {
        assertTrue(
            ResumeOptimizationPolicy.fallbackWithoutOptimizations(
                ResumeBlock.THERMAL_SEVERE_SAFETY,
                tweaksActive = false,
            ),
        )
    }

    @Test
    fun thermalSevereDoesNotFallBackWhenTweaksAreActive() {
        assertFalse(
            ResumeOptimizationPolicy.fallbackWithoutOptimizations(
                ResumeBlock.THERMAL_SEVERE_SAFETY,
                tweaksActive = true,
            ),
        )
    }

    @Test
    fun safetyPlainResumeSkipsThermalCheck() {
        val check = SuspendPolicy.checkResume(
            kind = SuspendKind.SAFETY,
            wantsOptimizations = false,
            bridgeOk = true,
            batteryLevel = 80,
            charging = false,
            thermalStatus = 3,
            explicitResume = true,
        )
        assertTrue(check.allowed)
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
    fun resumeWithoutOptimizationsActionVisibilityMatchesPausedScreen() {
        assertTrue(
            SuspendPolicy.resumeWithoutOptimizationsEnabled(SuspendKind.USER, 5, false),
        )
        assertTrue(
            SuspendPolicy.resumeWithoutOptimizationsEnabled(SuspendKind.SAFETY, 80, false),
        )
        assertTrue(
            SuspendPolicy.resumeWithoutOptimizationsEnabled(SuspendKind.SAFETY, 10, true),
        )
        assertFalse(
            SuspendPolicy.resumeWithoutOptimizationsEnabled(SuspendKind.SAFETY, 10, false),
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
