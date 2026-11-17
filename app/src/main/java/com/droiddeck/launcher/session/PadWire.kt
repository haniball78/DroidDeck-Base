package com.droiddeck.launcher.session

import com.droiddeck.launcher.input.PadState
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Wire format for the second phone's touch pad: 27 bytes, little-endian —
 * 6 floats (left/right sticks, triggers), a 16-bit button mask (PadState
 * button indices 0..13), and one dpad byte (up/right/down/left bits).
 */
object PadWire {
    const val PAYLOAD_BYTES = 27

    fun encode(state: PadState): ByteArray {
        var buttons = 0
        for (i in 0..13) if (state.isDown(i)) buttons = buttons or (1 shl i)
        var dpad = 0
        if (state.up) dpad = dpad or 1
        if (state.right) dpad = dpad or 2
        if (state.down) dpad = dpad or 4
        if (state.left) dpad = dpad or 8
        return ByteBuffer.allocate(PAYLOAD_BYTES).order(ByteOrder.LITTLE_ENDIAN)
            .putFloat(state.leftX).putFloat(state.leftY)
            .putFloat(state.rightX).putFloat(state.rightY)
            .putFloat(state.leftTrigger).putFloat(state.rightTrigger)
            .putShort(buttons.toShort()).put(dpad.toByte())
            .array()
    }

    /** Null when the payload is not exactly [PAYLOAD_BYTES] long. */
    fun decode(bytes: ByteArray): PadState? {
        if (bytes.size != PAYLOAD_BYTES) return null
        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
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
        return state
    }
}
