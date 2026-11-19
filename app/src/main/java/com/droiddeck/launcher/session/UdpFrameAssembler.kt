package com.droiddeck.launcher.session

import android.os.SystemClock

/**
 * Reassembles UDP video frames from packets, in frame-index order.
 * A frame is emitted when all its FEC blocks are complete — directly, or
 * recovered from parity shards. A frame that cannot be recovered is
 * reported lost (the caller sends an RFI) and skipped, so a newer frame
 * never waits behind a dead one. The first keyframe seen anchors the
 * floor: a later frame is held until every frame from the keyframe on
 * is resolved, so reordering can never emit a frame past a keyframe
 * that is still in flight. Single-threaded: call only from the
 * receiver's UDP loop.
 */
class UdpFrameAssembler(
    private val onFrame: (index: Int, timestampMs: Int, bytes: ByteArray) -> Unit,
    private val onFrameLost: (index: Int) -> Unit,
    private val clockMs: () -> Long = { SystemClock.elapsedRealtime() },
) {
    var framesReceived: Long = 0
        private set
    var framesRecovered: Long = 0
        private set
    var framesLost: Long = 0
        private set

    fun offer(packet: UdpVideoPacket) {
        if (packet.isKeyFrame && floorIndex == null) {
            floorIndex = packet.frameIndex
            if (nextIndex == null) nextIndex = packet.frameIndex
        }
        val next = nextIndex
        if (next != null && packet.frameIndex - next < 0) {
            // An older frame than the next expected one: before anything has
            // been emitted or lost only an earlier keyframe re-anchors (a
            // stray P-frame from an older GOP is ignored); after that it
            // is a late duplicate and is ignored.
            if (packet.isKeyFrame && framesReceived == 0L && framesLost == 0L) {
                nextIndex = packet.frameIndex
                floorIndex = packet.frameIndex
            } else {
                return
            }
        }
        val frame = frames.getOrPut(packet.frameIndex) {
            FrameState(packet.frameIndex, packet.timestampMs, packet.frameBytes, packet.blockCount, clockMs())
        }
        if (frame.completed || frame.lost) return
        val block = frame.blocks.getOrPut(packet.blockIndex) {
            BlockState(packet.dataPackets, packet.parityPackets)
        }
        if (packet.isParity) {
            val p = packet.packetIndex - packet.dataPackets
            if (p < 0 || p >= block.parityPackets || block.parity[p] != null) return
            block.parity[p] = packet.payload
            block.parityPresent++
        } else {
            if (packet.packetIndex >= block.dataPackets || block.data[packet.packetIndex] != null) return
            block.data[packet.packetIndex] = packet.payload
            block.dataPresent++
        }
        if (block.dataPresent < block.dataPackets &&
            block.dataPresent + block.parityPresent >= block.dataPackets
        ) {
            if (tryRecover(block)) block.recovered = true
        }
        if (frame.blocks.size == frame.blockCount && frame.blocks.values.all { it.dataPresent == it.dataPackets }) {
            frame.completed = true
            emitReady()
        }
    }

    /** Drops incomplete frames older than the stale deadline; call on socket-timeout ticks. */
    fun sweepStale(nowMs: Long = clockMs()) {
        var floorSet = false
        if (floorIndex == null) {
            // No keyframe ever arrived: give up waiting for it once the
            // oldest held frame is stale. The floor becomes the smallest
            // frame seen, and the presumed keyframe just below it is
            // reported lost so the caller asks for a fresh one.
            val oldest = frames.values.minByOrNull { it.firstSeenMs }
            if (oldest != null && nowMs - oldest.firstSeenMs > STALE_MS) {
                val floor = frames.keys.minOrNull() ?: return
                floorIndex = floor
                nextIndex = floor
                framesLost++
                onFrameLost(floor - 1)
                floorSet = true
            }
        }
        val stale = frames.values.filter { !it.completed && !it.lost && nowMs - it.firstSeenMs > STALE_MS }
        if (stale.isEmpty() && !floorSet) return
        for (frame in stale) declareLost(frame)
        emitReady()
    }

    private fun tryRecover(block: BlockState): Boolean {
        val shards = HashMap<Int, ByteArray>(block.dataPackets + block.parityPackets)
        for (i in block.data.indices) block.data[i]?.let { shards[i] = it }
        for (p in block.parity.indices) block.parity[p]?.let { shards[block.dataPackets + p] = it }
        val recovered = ReedSolomon.decode(shards, block.dataPackets, block.parityPackets) ?: return false
        for (i in recovered.indices) block.data[i] = recovered[i]
        block.dataPresent = block.dataPackets
        return true
    }

    /**
     * Emits every resolved frame from the next expected index, in order;
     * a lost frame is skipped. Frame indices are compared by Int
     * subtraction, so the ordering survives the 32-bit wraparound.
     */
    private fun emitReady() {
        var next = nextIndex ?: return
        while (true) {
            val frame = frames[next] ?: break
            if (frame.lost) {
                frames.remove(next)
                next += 1
                nextIndex = next
                continue
            }
            if (!frame.completed) break
            frames.remove(next)
            framesReceived++
            if (frame.blocks.values.any { it.recovered }) framesRecovered++
            val bytes = ByteArray(frame.frameBytes)
            var offset = 0
            for (b in 0 until frame.blockCount) {
                val block = frame.blocks[b] ?: break
                for (shard in block.data) {
                    val shardBytes = shard ?: break
                    val n = minOf(shardBytes.size, frame.frameBytes - offset)
                    if (n <= 0) break
                    shardBytes.copyInto(bytes, offset, 0, n)
                    offset += n
                }
            }
            onFrame(frame.index, frame.timestampMs, bytes)
            next += 1
            nextIndex = next
        }
        // A held frame far ahead of the next expected one means the frames in
        // between are never coming; declare the oldest incomplete one lost.
        if (frames.size > MAX_HELD_FRAMES) {
            val oldest = frames.values.filter { !it.completed && !it.lost }.minByOrNull { it.firstSeenMs } ?: return
            declareLost(oldest)
            emitReady()
        }
    }

    private fun declareLost(frame: FrameState) {
        if (frame.lost) return
        frame.lost = true
        framesLost++
        onFrameLost(frame.index)
    }

    private var nextIndex: Int? = null
    private var floorIndex: Int? = null
    private val frames = HashMap<Int, FrameState>()

    private class FrameState(
        val index: Int,
        val timestampMs: Int,
        val frameBytes: Int,
        val blockCount: Int,
        val firstSeenMs: Long,
    ) {
        val blocks = HashMap<Int, BlockState>()
        var completed = false
        var lost = false
    }

    private class BlockState(val dataPackets: Int, val parityPackets: Int) {
        val data = arrayOfNulls<ByteArray>(dataPackets)
        val parity = arrayOfNulls<ByteArray>(parityPackets)
        var dataPresent = 0
        var parityPresent = 0
        var recovered = false
    }

    private companion object {
        const val STALE_MS = 100L
        const val MAX_HELD_FRAMES = 8
    }
}
