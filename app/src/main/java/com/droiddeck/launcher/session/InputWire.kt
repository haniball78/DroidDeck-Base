package com.droiddeck.launcher.session

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Kind-prefixed framing for the second phone's input link: every TCP message
 * is a 4-byte big-endian length, then a kind byte, then the payload. Kind 0
 * carries the PadWire pad payload unchanged; kinds 1/2 carry keyboard and
 * mouse input. Big-endian everywhere, same as the length prefix.
 */
object InputWire {
    const val KIND_PAD = 0
    const val KIND_KEY = 1
    const val KIND_MOUSE = 2

    /** Key payload after the kind byte: keyCode, unicodeChar, scanCode (ints) + down byte. */
    const val KEY_PAYLOAD_BYTES = 13
    /** Mouse payload after the kind byte: dx, dy (floats), buttons byte, wheel (float). */
    const val MOUSE_PAYLOAD_BYTES = 13

    fun wrap(kind: Int, payload: ByteArray): ByteArray {
        val out = ByteArray(1 + payload.size)
        out[0] = kind.toByte()
        payload.copyInto(out, 1)
        return out
    }

    fun encodeKey(keyCode: Int, unicodeChar: Int, scanCode: Int, down: Boolean): ByteArray {
        val payload = ByteBuffer.allocate(KEY_PAYLOAD_BYTES).order(ByteOrder.BIG_ENDIAN)
            .putInt(keyCode).putInt(unicodeChar).putInt(scanCode).put(if (down) 1 else 0).array()
        return wrap(KIND_KEY, payload)
    }

    fun encodeMouse(dx: Float, dy: Float, buttons: Int, wheel: Float): ByteArray {
        val payload = ByteBuffer.allocate(MOUSE_PAYLOAD_BYTES).order(ByteOrder.BIG_ENDIAN)
            .putFloat(dx).putFloat(dy).put(buttons.toByte()).putFloat(wheel).array()
        return wrap(KIND_MOUSE, payload)
    }

    /** Null on an unknown kind or a payload of the wrong length. */
    fun parse(bytes: ByteArray): InputMessage? {
        if (bytes.isEmpty()) return null
        return when (bytes[0].toInt()) {
            KIND_PAD -> if (bytes.size == 1 + PadWire.PAYLOAD_BYTES) {
                InputMessage.Pad(bytes.copyOfRange(1, bytes.size))
            } else null
            KIND_KEY -> if (bytes.size == 1 + KEY_PAYLOAD_BYTES) {
                val buf = ByteBuffer.wrap(bytes, 1, KEY_PAYLOAD_BYTES).order(ByteOrder.BIG_ENDIAN)
                InputMessage.Key(buf.int, buf.int, buf.int, buf.get().toInt() != 0)
            } else null
            KIND_MOUSE -> if (bytes.size == 1 + MOUSE_PAYLOAD_BYTES) {
                val buf = ByteBuffer.wrap(bytes, 1, MOUSE_PAYLOAD_BYTES).order(ByteOrder.BIG_ENDIAN)
                InputMessage.Mouse(buf.float, buf.float, buf.get().toInt() and 0xff, buf.float)
            } else null
            else -> null
        }
    }
}

sealed interface InputMessage {
    class Pad(val payload: ByteArray) : InputMessage
    class Key(val keyCode: Int, val unicodeChar: Int, val scanCode: Int, val down: Boolean) : InputMessage
    class Mouse(val dx: Float, val dy: Float, val buttons: Int, val wheel: Float) : InputMessage
}
