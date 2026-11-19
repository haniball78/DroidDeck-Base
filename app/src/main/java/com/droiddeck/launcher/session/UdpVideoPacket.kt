package com.droiddeck.launcher.session

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Moonlight-style UDP video transport, reimplemented from the written spec
 * (research report): one access unit per frame, byte-sliced into 1024-byte
 * payloads with a 22-byte header, per-frame Reed–Solomon FEC, and a
 * reliable control channel for reference-frame invalidation. The wire
 * layout is our own; it is not GameStream-compatible — our phone talks
 * only to our tablet.
 */
object UdpVideo {
    const val HEADER_BYTES = 22
    const val PAYLOAD_BYTES = 1024
    const val VERSION: Byte = 1

    const val FLAG_PARITY: Byte = 0x01
    const val FLAG_EOF: Byte = 0x02
    const val FLAG_SOF: Byte = 0x04
    const val FLAG_KEYFRAME: Byte = 0x08

    /** Data packets per FEC block; with 20% parity the block stays under the 255-shard limit. */
    const val MAX_DATA_PACKETS_PER_BLOCK = 200
    const val FEC_PERCENT = 20

    /** The UDP video port and the control port sit right after the TCP stream and pad ports. */
    const val VIDEO_PORT_OFFSET = 2
    const val CONTROL_PORT_OFFSET = 3

    fun videoPort(tcpStreamPort: Int): Int = tcpStreamPort + VIDEO_PORT_OFFSET
    fun controlPort(tcpStreamPort: Int): Int = tcpStreamPort + CONTROL_PORT_OFFSET

    /** Magic + version of the datagram a tablet sends to subscribe to the video stream. */
    val SUBSCRIBE_MAGIC = byteArrayOf(0x44, 0x44, 0x54, 0x56)
    const val SUBSCRIBE_VERSION: Byte = 1

    fun isSubscribeDatagram(bytes: ByteArray, length: Int): Boolean {
        if (length != SUBSCRIBE_MAGIC.size + 1) return false
        if (!bytes.copyOf(SUBSCRIBE_MAGIC.size).contentEquals(SUBSCRIBE_MAGIC)) return false
        return bytes[SUBSCRIBE_MAGIC.size] == SUBSCRIBE_VERSION
    }

    fun subscribeDatagram(): ByteArray = SUBSCRIBE_MAGIC + SUBSCRIBE_VERSION
}

/** One encoded frame as it flows from the encoder to the transports. */
data class StreamFrame(
    val index: Int,
    /** Sender monotonic ms; the packet header carries its low 32 bits. */
    val timestampMs: Long,
    val isKeyFrame: Boolean,
    val bytes: ByteArray,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is StreamFrame) return false
        return index == other.index && timestampMs == other.timestampMs &&
            isKeyFrame == other.isKeyFrame && bytes.contentEquals(other.bytes)
    }

    override fun hashCode(): Int {
        var result = index
        result = 31 * result + timestampMs.hashCode()
        result = 31 * result + isKeyFrame.hashCode()
        result = 31 * result + bytes.contentHashCode()
        return result
    }
}

/** One UDP video packet: a 22-byte header plus up to 1024 bytes of shard data. */
data class UdpVideoPacket(
    val flags: Byte,
    val blockIndex: Int,
    val blockCount: Int,
    val packetIndex: Int,
    val dataPackets: Int,
    val parityPackets: Int,
    val frameIndex: Int,
    val timestampMs: Int,
    val frameBytes: Int,
    val payload: ByteArray,
) {
    val isParity: Boolean get() = flags.toInt() and UdpVideo.FLAG_PARITY.toInt() != 0
    val isSof: Boolean get() = flags.toInt() and UdpVideo.FLAG_SOF.toInt() != 0
    val isEof: Boolean get() = flags.toInt() and UdpVideo.FLAG_EOF.toInt() != 0
    val isKeyFrame: Boolean get() = flags.toInt() and UdpVideo.FLAG_KEYFRAME.toInt() != 0

    fun encode(): ByteArray {
        val out = ByteBuffer.allocate(UdpVideo.HEADER_BYTES + payload.size).order(ByteOrder.BIG_ENDIAN)
        out.put(UdpVideo.VERSION)
        out.put(flags)
        out.put(blockIndex.toByte())
        out.put(blockCount.toByte())
        out.putShort(packetIndex.toShort())
        out.putShort(dataPackets.toShort())
        out.putShort(parityPackets.toShort())
        out.putInt(frameIndex)
        out.putInt(timestampMs)
        out.putInt(frameBytes)
        out.put(payload)
        return out.array()
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is UdpVideoPacket) return false
        return flags == other.flags && blockIndex == other.blockIndex && blockCount == other.blockCount &&
            packetIndex == other.packetIndex && dataPackets == other.dataPackets &&
            parityPackets == other.parityPackets && frameIndex == other.frameIndex &&
            timestampMs == other.timestampMs && frameBytes == other.frameBytes &&
            payload.contentEquals(other.payload)
    }

    override fun hashCode(): Int {
        var result = flags.toInt()
        result = 31 * result + blockIndex
        result = 31 * result + blockCount
        result = 31 * result + packetIndex
        result = 31 * result + dataPackets
        result = 31 * result + parityPackets
        result = 31 * result + frameIndex
        result = 31 * result + timestampMs
        result = 31 * result + frameBytes
        result = 31 * result + payload.contentHashCode()
        return result
    }

    companion object {
        /** Parses a received datagram; null when it is not one of our video packets. */
        fun decode(bytes: ByteArray, length: Int): UdpVideoPacket? {
            if (length < UdpVideo.HEADER_BYTES) return null
            if (bytes[0] != UdpVideo.VERSION) return null
            val buf = ByteBuffer.wrap(bytes, 0, length).order(ByteOrder.BIG_ENDIAN)
            buf.get()
            val flags = buf.get()
            val blockIndex = buf.get().toInt() and 0xFF
            val blockCount = buf.get().toInt() and 0xFF
            val packetIndex = buf.short.toInt() and 0xFFFF
            val dataPackets = buf.short.toInt() and 0xFFFF
            val parityPackets = buf.short.toInt() and 0xFFFF
            val frameIndex = buf.int
            val timestampMs = buf.int
            val frameBytes = buf.int
            if (blockCount < 1 || blockIndex >= blockCount) return null
            if (dataPackets < 1 || dataPackets > UdpVideo.MAX_DATA_PACKETS_PER_BLOCK) return null
            if (parityPackets < 0 || parityPackets > dataPackets) return null
            if (packetIndex >= dataPackets + parityPackets) return null
            if (frameBytes < 1) return null
            val payload = bytes.copyOfRange(UdpVideo.HEADER_BYTES, length)
            if (payload.isEmpty() || payload.size > UdpVideo.PAYLOAD_BYTES) return null
            return UdpVideoPacket(
                flags, blockIndex, blockCount, packetIndex, dataPackets, parityPackets,
                frameIndex, timestampMs, frameBytes, payload,
            )
        }
    }
}

/**
 * Splits one encoded frame into FEC blocks of at most 200 data packets and
 * emits the data packets followed by their parity packets. The last data
 * shard of a block is zero-padded to 1024 bytes; the frame is trimmed to
 * frameBytes on reassembly.
 */
fun packetizeFrame(frame: StreamFrame): List<UdpVideoPacket> {
    require(frame.bytes.isNotEmpty()) { "cannot packetize an empty frame" }
    val totalData = (frame.bytes.size + UdpVideo.PAYLOAD_BYTES - 1) / UdpVideo.PAYLOAD_BYTES
    val blockCount = (totalData + UdpVideo.MAX_DATA_PACKETS_PER_BLOCK - 1) / UdpVideo.MAX_DATA_PACKETS_PER_BLOCK
    val packets = ArrayList<UdpVideoPacket>(totalData + blockCount * (totalData * UdpVideo.FEC_PERCENT / 100 + 1))
    var offset = 0
    for (blockIndex in 0 until blockCount) {
        val dataCount = minOf(UdpVideo.MAX_DATA_PACKETS_PER_BLOCK, totalData - blockIndex * UdpVideo.MAX_DATA_PACKETS_PER_BLOCK)
        val parityCount = maxOf(1, dataCount * UdpVideo.FEC_PERCENT / 100)
        val dataShards = ArrayList<ByteArray>(dataCount)
        for (i in 0 until dataCount) {
            val shard = ByteArray(UdpVideo.PAYLOAD_BYTES)
            val n = minOf(UdpVideo.PAYLOAD_BYTES, frame.bytes.size - offset)
            frame.bytes.copyInto(shard, 0, offset, offset + n)
            offset += n
            dataShards.add(shard)
        }
        val parityShards = ReedSolomon.encodeParity(dataShards, parityCount)
        for (i in 0 until dataCount) {
            // EOF lands on the last parity packet of the last block; parity is never zero.
            val sof = if (blockIndex == 0 && i == 0) UdpVideo.FLAG_SOF.toInt() else 0
            val key = if (frame.isKeyFrame) UdpVideo.FLAG_KEYFRAME.toInt() else 0
            packets.add(
                UdpVideoPacket(
                    flags = (sof or key).toByte(),
                    blockIndex = blockIndex,
                    blockCount = blockCount,
                    packetIndex = i,
                    dataPackets = dataCount,
                    parityPackets = parityCount,
                    frameIndex = frame.index,
                    timestampMs = frame.timestampMs.toInt(),
                    frameBytes = frame.bytes.size,
                    payload = dataShards[i],
                ),
            )
        }
        for (p in 0 until parityCount) {
            val lastPacket = blockIndex == blockCount - 1 && p == parityCount - 1
            val key = if (frame.isKeyFrame) UdpVideo.FLAG_KEYFRAME.toInt() else 0
            packets.add(
                UdpVideoPacket(
                    flags = (UdpVideo.FLAG_PARITY.toInt() or key or if (lastPacket) UdpVideo.FLAG_EOF.toInt() else 0).toByte(),
                    blockIndex = blockIndex,
                    blockCount = blockCount,
                    packetIndex = dataCount + p,
                    dataPackets = dataCount,
                    parityPackets = parityCount,
                    frameIndex = frame.index,
                    timestampMs = frame.timestampMs.toInt(),
                    frameBytes = frame.bytes.size,
                    payload = parityShards[p],
                ),
            )
        }
    }
    return packets
}

/**
 * Control-channel message: kind, first and last frame index. RFI asks
 * the encoder to invalidate the damaged reference frames; the phone
 * answers with an IDR, since the public MediaCodec API has no
 * reference-invalidation call. IDR asks for a keyframe outright.
 */
data class ControlMessage(val kind: Int, val firstFrameIndex: Int, val lastFrameIndex: Int) {
    fun encode(): ByteArray {
        val out = ByteBuffer.allocate(SIZE).order(ByteOrder.BIG_ENDIAN)
        out.putShort(kind.toShort())
        out.putInt(firstFrameIndex)
        out.putInt(lastFrameIndex)
        return out.array()
    }

    companion object {
        const val KIND_RFI = 0x0301
        const val KIND_IDR = 0x0302
        const val SIZE = 10

        fun rfi(firstFrameIndex: Int, lastFrameIndex: Int): ControlMessage =
            ControlMessage(KIND_RFI, firstFrameIndex, lastFrameIndex)

        fun idr(): ControlMessage = ControlMessage(KIND_IDR, 0, 0)

        fun decode(bytes: ByteArray, length: Int): ControlMessage? {
            if (length < SIZE) return null
            return runCatching {
                val buf = ByteBuffer.wrap(bytes, 0, length).order(ByteOrder.BIG_ENDIAN)
                val kind = buf.short.toInt() and 0xFFFF
                if (kind != KIND_RFI && kind != KIND_IDR) return null
                ControlMessage(kind, buf.int, buf.int)
            }.getOrNull()
        }
    }
}

/** NAL type of the first unit in an Annex B chunk; -1 when no start code is present. */
fun firstNalType(chunk: ByteArray): Int {
    var i = 0
    while (i + 3 < chunk.size) {
        if (chunk[i] == 0.toByte() && chunk[i + 1] == 0.toByte()) {
            if (chunk[i + 2] == 1.toByte()) return chunk[i + 3].toInt() and 0x1F
            if (i + 4 < chunk.size && chunk[i + 2] == 0.toByte() && chunk[i + 3] == 1.toByte()) {
                return chunk[i + 4].toInt() and 0x1F
            }
        }
        i++
    }
    return -1
}

/** True when an Annex B chunk contains an IDR NAL (type 5). */
fun containsIdrNal(chunk: ByteArray): Boolean {
    var i = 0
    while (i + 3 < chunk.size) {
        if (chunk[i] == 0.toByte() && chunk[i + 1] == 0.toByte()) {
            if (chunk[i + 2] == 1.toByte()) {
                if (chunk[i + 3].toInt() and 0x1F == 5) return true
                i += 3
                continue
            }
            if (i + 4 < chunk.size && chunk[i + 2] == 0.toByte() && chunk[i + 3] == 1.toByte()) {
                if (chunk[i + 4].toInt() and 0x1F == 5) return true
                i += 4
                continue
            }
        }
        i++
    }
    return false
}
