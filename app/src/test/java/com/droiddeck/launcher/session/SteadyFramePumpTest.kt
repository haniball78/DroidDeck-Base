package com.droiddeck.launcher.session

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class SteadyFramePumpTest {
    private val idr = byteArrayOf(0, 0, 0, 1, 0x65, 0x11, 0x22)
    private val pFrame = byteArrayOf(0, 0, 0, 1, 0x41, 0x33, 0x44)

    @Test
    fun `offer forwards immediately with rising indices and keyframe detection`() {
        var now = 1000L
        val forwarded = ArrayList<StreamFrame>()
        val pump = SteadyFramePump(onFrame = { forwarded.add(it) }, clockMs = { now })
        pump.offer(idr)
        pump.offer(pFrame)
        assertEquals(2, forwarded.size)
        assertEquals(0, forwarded[0].index)
        assertTrue(forwarded[0].isKeyFrame)
        assertArrayEquals(idr, forwarded[0].bytes)
        assertEquals(1, forwarded[1].index)
        assertFalse(forwarded[1].isKeyFrame)
        assertEquals(1000L, forwarded[0].timestampMs)
    }

    @Test
    fun `an idle tick re-emits the last keyframe but never a p-frame`() {
        var now = 1000L
        val forwarded = ArrayList<StreamFrame>()
        val pump = SteadyFramePump(onFrame = { forwarded.add(it) }, clockMs = { now })
        pump.offer(pFrame)
        now = 1100L
        pump.tick(now)
        assertEquals(1, forwarded.size)
        pump.offer(idr)
        now = 1200L
        pump.tick(now)
        assertEquals(3, forwarded.size)
        assertEquals(2, forwarded[2].index)
        assertTrue(forwarded[2].isKeyFrame)
        assertArrayEquals(idr, forwarded[2].bytes)
        assertEquals(1200L, forwarded[2].timestampMs)
    }

    @Test
    fun `a tick before a full idle interval does nothing`() {
        var now = 1000L
        val forwarded = ArrayList<StreamFrame>()
        val pump = SteadyFramePump(onFrame = { forwarded.add(it) }, clockMs = { now })
        pump.offer(idr)
        now = 1010L
        pump.tick(now)
        assertEquals(1, forwarded.size)
        now = 1040L
        pump.tick(now)
        assertEquals(2, forwarded.size)
    }
}
