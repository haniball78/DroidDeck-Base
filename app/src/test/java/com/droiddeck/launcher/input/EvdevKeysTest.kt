package com.droiddeck.launcher.input

import android.view.KeyEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class EvdevKeysTest {
    @Test
    fun `letter key maps to its evdev code`() {
        assertEquals(30, EvdevKeys.fromKeyEvent(KeyEvent.KEYCODE_A, 0, 0))
        assertEquals(28, EvdevKeys.fromKeyEvent(KeyEvent.KEYCODE_ENTER, 0, 0))
        assertEquals(42, EvdevKeys.fromKeyEvent(KeyEvent.KEYCODE_SHIFT_LEFT, 0, 0))
    }

    @Test
    fun `character fallback maps a shifted char`() {
        // '!' has no keycode; its unshifted form '1' maps to KEYCODE_1 -> evdev 2.
        assertEquals(2, EvdevKeys.fromKeyEvent(KeyEvent.KEYCODE_UNKNOWN, '!'.code, 0))
    }

    @Test
    fun `scan code is the last fallback`() {
        assertEquals(30, EvdevKeys.fromKeyEvent(KeyEvent.KEYCODE_UNKNOWN, 0, 30))
    }

    @Test
    fun `unknown key maps to nothing`() {
        assertTrue(EvdevKeys.fromKeyEvent(KeyEvent.KEYCODE_UNKNOWN, 0, 0) <= 0)
    }
}
