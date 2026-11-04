package com.droiddeck.launcher.core.privileged

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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

    @Test
    fun poisonedItemMayBeRemovedOnlyOnProvenUnsupportedGetter() {
        // Setter ran while the getter was unsupported: remove allowed.
        assertTrue(PoisonedFixedPerformanceItem.mayRemove(true, 255, ""))
        assertTrue(PoisonedFixedPerformanceItem.mayRemove(true, 0, "Unknown command\n"))
        assertTrue(PoisonedFixedPerformanceItem.mayRemove(true, 1, ""))
        // Supported getter: the recorded original is verifiable, keep it.
        assertFalse(PoisonedFixedPerformanceItem.mayRemove(true, 0, "true\n"))
        assertFalse(PoisonedFixedPerformanceItem.mayRemove(true, 0, "false\n"))
        // Bridge absent is unreadable, not proven unsupported: keep it.
        assertFalse(PoisonedFixedPerformanceItem.mayRemove(true, -1, ""))
        // No item at all: nothing to remove.
        assertFalse(PoisonedFixedPerformanceItem.mayRemove(false, 255, ""))
    }
}
