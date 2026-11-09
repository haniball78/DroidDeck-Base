package com.droiddeck.launcher.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SplitScreenWindowingTest {

    @Test fun returnDelayIsThreeSeconds() {
        assertEquals(3_000L, SplitScreenWindowing.RETURN_TO_FULLSCREEN_DELAY_MS)
    }

    @Test fun windowingOptionsAreNullWithoutAnAndroidRuntime() {
        // On the JVM unit-test runtime the framework stubs throw, so the
        // reflection path must fail soft instead of crashing.
        assertNull(SplitScreenWindowing.splitPrimaryOptions())
        assertNull(SplitScreenWindowing.fullscreenOptions())
    }
}
