package com.droiddeck.launcher.session

import android.view.KeyEvent
import com.droiddeck.launcher.input.PadState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class InputWireTest {
    @Test
    fun `key round-trips through parse`() {
        val down = InputWire.parse(InputWire.encodeKey(KeyEvent.KEYCODE_A, 0, 0, true)) as InputMessage.Key
        assertEquals(KeyEvent.KEYCODE_A, down.keyCode)
        assertEquals(0, down.unicodeChar)
        assertEquals(0, down.scanCode)
        assertTrue(down.down)
        val up = InputWire.parse(InputWire.encodeKey(KeyEvent.KEYCODE_ENTER, 13, 0, false)) as InputMessage.Key
        assertEquals(KeyEvent.KEYCODE_ENTER, up.keyCode)
        assertEquals(13, up.unicodeChar)
        assertFalse(up.down)
    }

    @Test
    fun `mouse round-trips through parse`() {
        val mouse = InputWire.parse(InputWire.encodeMouse(12.5f, -3.25f, 5, 1f)) as InputMessage.Mouse
        assertEquals(12.5f, mouse.dx, 0f)
        assertEquals(-3.25f, mouse.dy, 0f)
        assertEquals(5, mouse.buttons)
        assertEquals(1f, mouse.wheel, 0f)
    }

    @Test
    fun `pad payload passes through unchanged`() {
        val state = PadState().apply {
            leftX = 0.5f
            press(PadState.A, true)
        }
        val wire = InputWire.wrap(InputWire.KIND_PAD, PadWire.encode(state, 0))
        val message = InputWire.parse(wire) as InputMessage.Pad
        val (slot, decoded) = PadWire.decode(message.payload)!!
        assertEquals(0, slot)
        assertEquals(0.5f, decoded.leftX, 0f)
        assertTrue(decoded.isDown(PadState.A))
    }

    @Test
    fun `unknown kind returns null`() {
        assertNull(InputWire.parse(byteArrayOf(9, 1, 2, 3)))
    }

    @Test
    fun `bad lengths return null`() {
        assertNull(InputWire.parse(byteArrayOf()))
        assertNull(InputWire.parse(byteArrayOf(InputWire.KIND_KEY.toByte(), 1, 2, 3)))
        assertNull(InputWire.parse(byteArrayOf(InputWire.KIND_MOUSE.toByte(), 1, 2, 3)))
        assertNull(InputWire.parse(byteArrayOf(InputWire.KIND_PAD.toByte(), 1, 2, 3)))
    }
}
