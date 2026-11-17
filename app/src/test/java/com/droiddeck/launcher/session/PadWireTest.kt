package com.droiddeck.launcher.session

import com.droiddeck.launcher.input.PadState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class PadWireTest {
    @Test
    fun `encode then decode round-trips every field`() {
        val state = PadState().apply {
            leftX = 0.5f; leftY = -0.25f; rightX = -1f; rightY = 0.75f
            leftTrigger = 0.3f; rightTrigger = 1f
            up = true; right = true; down = false; left = true
            press(PadState.A, true)
            press(PadState.RB, true)
            press(PadState.START, true)
            press(PadState.QAM, true)
        }
        val decoded = PadWire.decode(PadWire.encode(state))!!.second
        assertEquals(0.5f, decoded.leftX, 0f)
        assertEquals(-0.25f, decoded.leftY, 0f)
        assertEquals(-1f, decoded.rightX, 0f)
        assertEquals(0.75f, decoded.rightY, 0f)
        assertEquals(0.3f, decoded.leftTrigger, 0f)
        assertEquals(1f, decoded.rightTrigger, 0f)
        assertTrue(decoded.up)
        assertTrue(decoded.right)
        assertTrue(!decoded.down)
        assertTrue(decoded.left)
        assertTrue(decoded.isDown(PadState.A))
        assertTrue(decoded.isDown(PadState.RB))
        assertTrue(decoded.isDown(PadState.START))
        assertTrue(decoded.isDown(PadState.QAM))
        assertTrue(!decoded.isDown(PadState.B))
        assertTrue(!decoded.isDown(PadState.GUIDE))
    }

    @Test
    fun `empty state encodes to zeros`() {
        val decoded = PadWire.decode(PadWire.encode(PadState()))!!.second
        assertEquals(0f, decoded.leftX, 0f)
        assertTrue(!decoded.isDown(PadState.A))
        assertTrue(!decoded.up)
    }

    @Test
    fun `decode is null for a short or long payload`() {
        assertNull(PadWire.decode(ByteArray(PadWire.PAYLOAD_BYTES - 1)))
        assertNull(PadWire.decode(ByteArray(PadWire.PAYLOAD_BYTES + 1)))
        assertNull(PadWire.decode(ByteArray(0)))
    }

    @Test
    fun `slot round-trips and defaults to 1`() {
        val state = PadState().apply {
            leftX = 0.5f
            press(PadState.A, true)
            down = true
        }
        val (defaultSlot, defaultDecoded) = PadWire.decode(PadWire.encode(state))!!
        assertEquals(1, defaultSlot)
        assertEquals(0.5f, defaultDecoded.leftX, 0f)
        assertTrue(defaultDecoded.isDown(PadState.A))
        for (slot in listOf(0, 2, 3)) {
            val (decodedSlot, decoded) = PadWire.decode(PadWire.encode(state, slot))!!
            assertEquals(slot, decodedSlot)
            assertEquals(0.5f, decoded.leftX, 0f)
            assertTrue(decoded.isDown(PadState.A))
            assertTrue(decoded.down)
        }
    }

    @Test
    fun `decode is null for an invalid slot byte`() {
        val bad4 = PadWire.encode(PadState(), 1).also { it[0] = 4 }
        assertNull(PadWire.decode(bad4))
        val bad255 = PadWire.encode(PadState(), 1).also { it[0] = -1 }
        assertNull(PadWire.decode(bad255))
    }

    @Test
    fun `merge ORs buttons and dpad and lets a centred touch axis pass the physical value through`() {
        val touch = PadState().apply {
            leftX = 0.5f
            press(PadState.A, true)
            up = true
        }
        val hw = PadState().apply {
            leftX = -0.75f
            rightY = 0.25f
            press(PadState.B, true)
            down = true
        }
        val merged = PadState()
        PadWire.merge(merged, touch, hw)
        assertEquals(0.5f, merged.leftX, 0f)
        assertEquals(0.25f, merged.rightY, 0f)
        assertTrue(merged.isDown(PadState.A))
        assertTrue(merged.isDown(PadState.B))
        assertTrue(merged.up)
        assertTrue(merged.down)

        val touchCentred = PadState().apply { press(PadState.X, true) }
        PadWire.merge(merged, touchCentred, hw)
        assertEquals(-0.75f, merged.leftX, 0f)
        assertEquals(0.25f, merged.rightY, 0f)
        assertTrue(merged.isDown(PadState.X))
        assertTrue(merged.isDown(PadState.B))
        assertTrue(!merged.up)
        assertTrue(merged.down)

        val hwZero = PadState()
        PadWire.merge(merged, touch, hwZero)
        assertEquals(0.5f, merged.leftX, 0f)
        assertEquals(0f, merged.rightY, 0f)
        assertTrue(merged.isDown(PadState.A))
        assertTrue(!merged.isDown(PadState.B))
    }
}
