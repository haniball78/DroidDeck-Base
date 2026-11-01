package com.droiddeck.launcher.core.privileged

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FixedPerformanceGetterTest {

    @Test
    fun supportedTrueAndFalseKeepOriginal() {
        assertEquals(
            FixedPerformanceGetter.SUPPORTED to "true",
            FixedPerformanceGetterDecision.decide(0, "true\n"),
        )
        assertEquals(
            FixedPerformanceGetter.SUPPORTED to "false",
            FixedPerformanceGetterDecision.decide(0, "false\n"),
        )
    }

    @Test
    fun unknownCommandOutputIsUnsupportedNotUnreadable() {
        assertEquals(
            FixedPerformanceGetter.UNSUPPORTED to null,
            FixedPerformanceGetterDecision.decide(0, "Unknown command\n"),
        )
    }

    @Test
    fun nonZeroNonBridgeExitIsUnsupported() {
        assertEquals(
            FixedPerformanceGetter.UNSUPPORTED to null,
            FixedPerformanceGetterDecision.decide(255, ""),
        )
        assertEquals(
            FixedPerformanceGetter.UNSUPPORTED to null,
            FixedPerformanceGetterDecision.decide(1, ""),
        )
    }

    @Test
    fun bridgeFailureIsUnreadable() {
        val (decision, original) = FixedPerformanceGetterDecision.decide(-1, "")
        assertEquals(FixedPerformanceGetter.UNREADABLE, decision)
        assertNull(original)
    }
}
