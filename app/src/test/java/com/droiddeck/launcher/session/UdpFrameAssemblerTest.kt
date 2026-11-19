package com.droiddeck.launcher.session

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class UdpFrameAssemblerTest {
    private fun frame(index: Int, size: Int = 3000): StreamFrame =
        StreamFrame(index, 1000L + index * 33, index == 0, ByteArray(size) { (it * 7 + index % 251).toByte() })

    @Test
    fun `frames arrive in order even when packets are shuffled`() {
        val delivered = ArrayList<Pair<Int, ByteArray>>()
        val assembler = UdpFrameAssembler(
            onFrame = { index, _, bytes -> delivered.add(index to bytes) },
            onFrameLost = { throw AssertionError("no frame may be lost") },
        )
        val frames = listOf(frame(0), frame(1), frame(2))
        val packets = frames.flatMap { packetizeFrame(it) }.shuffled()
        packets.forEach { assembler.offer(it) }
        assertEquals(3, delivered.size)
        for (i in frames.indices) {
            assertEquals(i, delivered[i].first)
            assertArrayEquals(frames[i].bytes, delivered[i].second)
        }
        assertEquals(3, assembler.framesReceived)
    }

    @Test
    fun `a single lost data packet is recovered from parity`() {
        val delivered = ArrayList<ByteArray>()
        val assembler = UdpFrameAssembler(
            onFrame = { _, _, bytes -> delivered.add(bytes) },
            onFrameLost = { throw AssertionError("the frame must recover") },
        )
        val f = frame(5)
        val packets = packetizeFrame(f)
        val dropped = packets.first { !it.isParity }
        packets.filter { it !== dropped }.forEach { assembler.offer(it) }
        assertEquals(1, delivered.size)
        assertArrayEquals(f.bytes, delivered[0])
        assertEquals(1, assembler.framesRecovered)
        assertEquals(0, assembler.framesLost)
    }

    @Test
    fun `an unrecoverable frame is reported lost and the next frame still arrives`() {
        var now = 0L
        val delivered = ArrayList<Int>()
        val lost = ArrayList<Int>()
        val assembler = UdpFrameAssembler(
            onFrame = { index, _, _ -> delivered.add(index) },
            onFrameLost = { lost.add(it) },
            clockMs = { now },
        )
        val first = frame(10)
        val packets = packetizeFrame(first)
        val data = packets.filter { !it.isParity }
        // Drop two of three data packets: one parity shard cannot rebuild two.
        packets.filter { it !in data.take(2) }.forEach { assembler.offer(it) }
        assertTrue(delivered.isEmpty())
        assertTrue(lost.isEmpty())
        packetizeFrame(frame(11)).forEach { assembler.offer(it) }
        assertTrue(delivered.isEmpty())
        now += 200
        assembler.sweepStale(now)
        assertEquals(listOf(10), lost)
        assertEquals(1, assembler.framesLost)
        assertEquals(listOf(11), delivered)
        assertEquals(1, assembler.framesReceived)
    }

    @Test
    fun `duplicate and late packets never deliver a frame twice`() {
        val delivered = ArrayList<Int>()
        val assembler = UdpFrameAssembler(
            onFrame = { index, _, _ -> delivered.add(index) },
            onFrameLost = { throw AssertionError("no frame may be lost") },
        )
        val packets = packetizeFrame(frame(20))
        packets.forEach { assembler.offer(it) }
        packets.forEach { assembler.offer(it) }
        assertEquals(listOf(20), delivered)
        assertEquals(1, assembler.framesReceived)
    }

    @Test
    fun `a stale incomplete frame is declared lost by the sweep`() {
        var now = 0L
        val lost = ArrayList<Int>()
        val assembler = UdpFrameAssembler(
            onFrame = { _, _, _ -> throw AssertionError("the frame must not complete") },
            onFrameLost = { lost.add(it) },
            clockMs = { now },
        )
        val packets = packetizeFrame(frame(30))
        assembler.offer(packets.first { !it.isParity })
        assembler.sweepStale(now + 50)
        assertTrue(lost.isEmpty())
        now += 150
        assembler.sweepStale(now)
        assertEquals(listOf(30), lost)
        assertEquals(1, assembler.framesLost)
    }

    @Test
    fun `a frame held behind a lost one is emitted once the loss is declared`() {
        var now = 0L
        val delivered = ArrayList<Int>()
        val lost = ArrayList<Int>()
        val assembler = UdpFrameAssembler(
            onFrame = { index, _, _ -> delivered.add(index) },
            onFrameLost = { lost.add(it) },
            clockMs = { now },
        )
        val first = packetizeFrame(frame(40))
        val second = packetizeFrame(frame(41))
        assembler.offer(first.first { !it.isParity })
        second.forEach { assembler.offer(it) }
        assertTrue(delivered.isEmpty())
        now += 200
        assembler.sweepStale(now)
        assertEquals(listOf(40), lost)
        assertEquals(listOf(41), delivered)
    }
}
