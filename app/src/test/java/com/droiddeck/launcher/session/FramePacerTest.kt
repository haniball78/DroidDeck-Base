package com.droiddeck.launcher.session

import android.os.Handler
import android.os.Looper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class FramePacerTest {
    @Test
    fun `the first frame is held one interval then renders`() {
        val pacer = FramePacer()
        val held = pacer.decide(1000, 1000)
        assertTrue(held is PaceDecision.HoldUntil)
        assertEquals(33L, (held as PaceDecision.HoldUntil).delayMs)
        assertEquals(PaceDecision.RenderNow, pacer.decide(1000, 1033))
    }

    @Test
    fun `a frame older than the displayed one is dropped`() {
        val pacer = FramePacer()
        pacer.decide(1000, 1000)
        pacer.decide(1000, 1033)
        pacer.markDisplayed(1000)
        assertEquals(PaceDecision.Drop, pacer.decide(1000, 1100))
        assertEquals(PaceDecision.Drop, pacer.decide(999, 1100))
    }

    @Test
    fun `a frame after a stall re-anchors and renders instead of dropping`() {
        val pacer = FramePacer()
        pacer.decide(1000, 1000)
        pacer.decide(1000, 1033)
        pacer.markDisplayed(1000)
        // The next frame arrives 500 ms later: its display time is long past.
        assertEquals(PaceDecision.RenderNow, pacer.decide(1033, 1533))
        // The stream continues from the new anchor.
        val held = pacer.decide(1066, 1566)
        assertTrue(held is PaceDecision.HoldUntil)
    }

    @Test
    fun `a frame far in the future re-anchors instead of waiting`() {
        val pacer = FramePacer()
        pacer.decide(1000, 1000)
        val held = pacer.decide(5000, 1033)
        assertTrue(held is PaceDecision.HoldUntil)
        assertEquals(33L, (held as PaceDecision.HoldUntil).delayMs)
    }

    @Test
    fun `timestamp ordering survives the 32-bit wraparound`() {
        val pacer = FramePacer()
        val anchor = Int.MAX_VALUE - 10
        pacer.decide(anchor, 1000)
        pacer.decide(anchor, 1033)
        pacer.markDisplayed(anchor)
        // The next timestamp wraps to a small positive number; it is newer, not older.
        val wrapped = Int.MIN_VALUE + 10
        assertEquals(PaceDecision.RenderNow, pacer.decide(wrapped, 1066))
    }

    @Test
    fun `the vsync pacer renders a held frame on the next vsync`() {
        val rendered = ArrayList<Int>()
        val dropped = ArrayList<Int>()
        var now = 1000L
        val pacer = VsyncPacer(
            nowMs = { now },
            render = { rendered.add(it) },
            drop = { dropped.add(it) },
            mainHandler = Handler(Looper.getMainLooper()),
        )
        pacer.start()
        pacer.offer(0, 1000)
        assertTrue(rendered.isEmpty())
        pacer.doFrame(0L)
        assertTrue(rendered.isEmpty())
        now = 1040L
        pacer.doFrame(0L)
        assertEquals(listOf(0), rendered)
        assertTrue(dropped.isEmpty())
        pacer.stop()
    }

    @Test
    fun `the vsync pacer drops a held frame when a newer one arrives`() {
        val rendered = ArrayList<Int>()
        val dropped = ArrayList<Int>()
        var now = 1000L
        val pacer = VsyncPacer(
            nowMs = { now },
            render = { rendered.add(it) },
            drop = { dropped.add(it) },
            mainHandler = Handler(Looper.getMainLooper()),
        )
        pacer.start()
        pacer.offer(0, 1000)
        pacer.offer(1, 1033)
        assertEquals(listOf(0), dropped)
        assertTrue(rendered.isEmpty())
        now = 1100L
        pacer.doFrame(0L)
        assertEquals(listOf(1), rendered)
        pacer.stop()
    }
}
