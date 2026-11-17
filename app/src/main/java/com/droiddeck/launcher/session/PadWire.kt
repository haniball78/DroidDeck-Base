package com.droiddeck.launcher.session

import com.droiddeck.launcher.input.PadState
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Wire format for the second phone's pad: 28 bytes, little-endian — a slot
 * byte first (0..3), then 6 floats (left/right sticks, triggers), a 16-bit
 * button mask (PadState button indices 0..13), and one dpad byte
 * (up/right/down/left bits).
 */
object PadWire {
    const val PAYLOAD_BYTES = 28

    fun encode(state: PadState, slot: Int = 1): ByteArray {
        require(slot in 0..3) { "slot must be 0..3" }
        var buttons = 0
        for (i in 0..13) if (state.isDown(i)) buttons = buttons or (1 shl i)
        var dpad = 0
        if (state.up) dpad = dpad or 1
        if (state.right) dpad = dpad or 2
        if (state.down) dpad = dpad or 4
        if (state.left) dpad = dpad or 8
        return ByteBuffer.allocate(PAYLOAD_BYTES).order(ByteOrder.LITTLE_ENDIAN)
            .put(slot.toByte())
            .putFloat(state.leftX).putFloat(state.leftY)
            .putFloat(state.rightX).putFloat(state.rightY)
            .putFloat(state.leftTrigger).putFloat(state.rightTrigger)
            .putShort(buttons.toShort()).put(dpad.toByte())
            .array()
    }

    /** Null when the payload is not exactly [PAYLOAD_BYTES] long or the slot byte is outside 0..3. */
    fun decode(bytes: ByteArray): Pair<Int, PadState>? {
        if (bytes.size != PAYLOAD_BYTES) return null
        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val slot = buf.get().toInt() and 0xff
        if (slot !in 0..3) return null
        val state = PadState()
        state.leftX = buf.float
        state.leftY = buf.float
        state.rightX = buf.float
        state.rightY = buf.float
        state.leftTrigger = buf.float
        state.rightTrigger = buf.float
        val buttons = buf.short.toInt() and 0xffff
        for (i in 0..13) state.press(i, buttons and (1 shl i) != 0)
        val dpad = buf.get().toInt()
        state.up = dpad and 1 != 0
        state.right = dpad and 2 != 0
        state.down = dpad and 4 != 0
        state.left = dpad and 8 != 0
        return slot to state
    }

    /**
     * Merges a touch-pad state with a physical-pad state: buttons and dpad
     * OR, each axis takes the touch value unless the touch axis is centred,
     * so a centred touch stick never clobbers a held physical stick.
     */
    fun merge(into: PadState, touch: PadState, hw: PadState) {
        for (i in 0..13) into.press(i, touch.isDown(i) || hw.isDown(i))
        into.up = touch.up || hw.up
        into.right = touch.right || hw.right
        into.down = touch.down || hw.down
        into.left = touch.left || hw.left
        into.leftX = if (touch.leftX != 0f) touch.leftX else hw.leftX
        into.leftY = if (touch.leftY != 0f) touch.leftY else hw.leftY
        into.rightX = if (touch.rightX != 0f) touch.rightX else hw.rightX
        into.rightY = if (touch.rightY != 0f) touch.rightY else hw.rightY
        into.leftTrigger = if (touch.leftTrigger != 0f) touch.leftTrigger else hw.leftTrigger
        into.rightTrigger = if (touch.rightTrigger != 0f) touch.rightTrigger else hw.rightTrigger
    }
}
