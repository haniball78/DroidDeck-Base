package com.droiddeck.launcher.session

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class SpsFixupTest {
    @Test
    fun `a baseline sps with four reference frames rewrites to one`() {
        val sps = buildSps(profileIdc = 66, levelIdc = 30, numRefFrames = 4, widthMbsMinus1 = 119, heightMapMinus1 = 67)
        val fields = SpsFixup.parseSps(sps)
        assertEquals(66, fields?.profileIdc)
        assertEquals(30, fields?.levelIdc)
        assertEquals(4, fields?.numRefFrames)
        assertEquals(119, fields?.picWidthInMbsMinus1)
        assertEquals(67, fields?.picHeightInMapUnitsMinus1)
        val rewritten = SpsFixup.rewriteNumRefFrames(sps)
        val after = SpsFixup.parseSps(rewritten)
        assertEquals(1, after?.numRefFrames)
        assertEquals(66, after?.profileIdc)
        assertEquals(30, after?.levelIdc)
        assertEquals(119, after?.picWidthInMbsMinus1)
        assertEquals(67, after?.picHeightInMapUnitsMinus1)
    }

    @Test
    fun `a high profile sps rewrites through the chroma section`() {
        val sps = buildSps(profileIdc = 100, levelIdc = 40, numRefFrames = 2, widthMbsMinus1 = 119, heightMapMinus1 = 67)
        assertEquals(2, SpsFixup.readNumRefFrames(sps))
        val rewritten = SpsFixup.rewriteNumRefFrames(sps)
        assertEquals(1, SpsFixup.readNumRefFrames(rewritten))
        assertEquals(100, SpsFixup.parseSps(rewritten)?.profileIdc)
    }

    @Test
    fun `an sps already at one reference frame is returned unchanged`() {
        val sps = buildSps(profileIdc = 66, levelIdc = 30, numRefFrames = 1, widthMbsMinus1 = 119, heightMapMinus1 = 67)
        assertArrayEquals(sps, SpsFixup.rewriteNumRefFrames(sps))
    }

    @Test
    fun `a non-sps unit and garbage are returned unchanged`() {
        val idr = byteArrayOf(0, 0, 0, 1, 0x65, 0x11, 0x22)
        assertArrayEquals(idr, SpsFixup.rewriteNumRefFrames(idr))
        assertNull(SpsFixup.parseSps(idr))
        val garbage = byteArrayOf(0, 0, 1, 0x67, 0x01, 0x02)
        assertArrayEquals(garbage, SpsFixup.rewriteNumRefFrames(garbage))
    }

    @Test
    fun `an sps with a truncated body parses to nothing`() {
        val sps = buildSps(profileIdc = 66, levelIdc = 30, numRefFrames = 4, widthMbsMinus1 = 119, heightMapMinus1 = 67)
        val truncated = sps.copyOf(6)
        assertNull(SpsFixup.parseSps(truncated))
        assertArrayEquals(truncated, SpsFixup.rewriteNumRefFrames(truncated))
    }

    private fun buildSps(
        profileIdc: Int,
        levelIdc: Int,
        numRefFrames: Int,
        widthMbsMinus1: Int,
        heightMapMinus1: Int,
    ): ByteArray {
        val w = TestBitWriter()
        w.bits(profileIdc, 8)
        w.bits(0, 8)
        w.bits(levelIdc, 8)
        w.ue(0)
        if (profileIdc == 100) {
            w.ue(1)
            w.ue(0)
            w.ue(0)
            w.bit(false)
            w.bit(false)
        }
        w.ue(0)
        w.ue(0)
        w.ue(0)
        w.ue(numRefFrames)
        w.bit(false)
        w.ue(widthMbsMinus1)
        w.ue(heightMapMinus1)
        w.bit(true)
        w.bit(true)
        w.bit(false)
        w.bit(false)
        val rbsp = w.toByteArray()
        val out = ByteArray(4 + 1 + rbsp.size)
        out[0] = 0
        out[1] = 0
        out[2] = 0
        out[3] = 1
        out[4] = 0x67
        rbsp.copyInto(out, 5)
        return out
    }

    private class TestBitWriter {
        private val bits = ArrayList<Boolean>()

        fun bits(value: Int, count: Int) {
            for (i in count - 1 downTo 0) bits.add(((value shr i) and 1) == 1)
        }

        fun bit(value: Boolean) {
            bits.add(value)
        }

        fun ue(value: Int) {
            val codeNum = value + 1
            val leadingZeros = 31 - Integer.numberOfLeadingZeros(codeNum)
            repeat(leadingZeros) { bits.add(false) }
            for (i in leadingZeros downTo 0) bits.add(((codeNum shr i) and 1) == 1)
        }

        fun toByteArray(): ByteArray {
            bits.add(true)
            while (bits.size % 8 != 0) bits.add(false)
            val out = ByteArray(bits.size / 8)
            for (i in bits.indices) {
                if (bits[i]) out[i / 8] = (out[i / 8].toInt() or (1 shl (7 - i % 8))).toByte()
            }
            return out
        }
    }
}
