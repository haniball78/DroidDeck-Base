package com.droiddeck.launcher.session

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream

@RunWith(RobolectricTestRunner::class)
class TvStreamReceiverTest {
    @Test
    fun `read units delivers every framed unit in order`() {
        val units = listOf(
            byteArrayOf(0, 0, 0, 1, 0x65, 0x11, 0x22),
            byteArrayOf(0, 0, 1, 0x41, 0x33),
            byteArrayOf(0, 0, 0, 1, 0x06, 0x44, 0x55, 0x66),
        )
        val input = DataInputStream(ByteArrayInputStream(framed(units)))
        val got = ArrayList<ByteArray>()
        assertEquals(3, readUnits(input, { false }) { got.add(it) })
        assertEquals(3, got.size)
        for (i in units.indices) assertArrayEquals(units[i], got[i])
    }

    @Test
    fun `read units stops on a bad length and keeps what arrived`() {
        val out = ByteArrayOutputStream().also { DataOutputStream(it).writeInt(4); it.write(byteArrayOf(1, 2, 3, 4)) }
        DataOutputStream(out).writeInt(MAX_UNIT_BYTES + 1)
        val input = DataInputStream(ByteArrayInputStream(out.toByteArray()))
        val got = ArrayList<ByteArray>()
        assertEquals(1, readUnits(input, { false }) { got.add(it) })
        assertArrayEquals(byteArrayOf(1, 2, 3, 4), got[0])
    }

    @Test
    fun `read units stops on a truncated unit`() {
        val out = ByteArrayOutputStream().also {
            DataOutputStream(it).writeInt(8)
            it.write(byteArrayOf(1, 2, 3))
        }
        val input = DataInputStream(ByteArrayInputStream(out.toByteArray()))
        val got = ArrayList<ByteArray>()
        assertEquals(0, readUnits(input, { false }) { got.add(it) })
    }

    @Test
    fun `read units delivers nothing once stopped`() {
        val input = DataInputStream(ByteArrayInputStream(framed(listOf(byteArrayOf(1, 2, 3)))))
        var calls = 0
        assertEquals(0, readUnits(input, { true }) { calls++ })
        assertEquals(0, calls)
    }

    private fun framed(units: List<ByteArray>): ByteArray {
        val out = ByteArrayOutputStream()
        val data = DataOutputStream(out)
        for (unit in units) {
            data.writeInt(unit.size)
            data.write(unit)
        }
        data.flush()
        return out.toByteArray()
    }
}
