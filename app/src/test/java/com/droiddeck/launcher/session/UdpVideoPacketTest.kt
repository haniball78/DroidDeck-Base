package com.droiddeck.launcher.session

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class UdpVideoPacketTest {
    @Test
    fun `packet header round-trips through encode and decode`() {
        val packet = UdpVideoPacket(
            flags = (UdpVideo.FLAG_SOF.toInt() or UdpVideo.FLAG_PARITY.toInt()).toByte(),
            blockIndex = 1,
            blockCount = 3,
            packetIndex = 7,
            dataPackets = 200,
            parityPackets = 40,
            frameIndex = 12345,
            timestampMs = 987654,
            frameBytes = 150_000,
            payload = byteArrayOf(1, 2, 3, 4),
        )
        val decoded = UdpVideoPacket.decode(packet.encode(), packet.encode().size)
        assertEquals(packet, decoded)
    }

    @Test
    fun `decode rejects a wrong version, a short packet, and a bad block`() {
        val packet = UdpVideoPacket(
            flags = 0, blockIndex = 0, blockCount = 1, packetIndex = 0,
            dataPackets = 1, parityPackets = 1, frameIndex = 1, timestampMs = 1,
            frameBytes = 10, payload = byteArrayOf(9),
        )
        val bytes = packet.encode()
        assertNull(UdpVideoPacket.decode(bytes, 10))
        val wrongVersion = bytes.copyOf().also { it[0] = 2 }
        assertNull(UdpVideoPacket.decode(wrongVersion, wrongVersion.size))
        val badBlock = bytes.copyOf().also { it[2] = 1 }
        assertNull(UdpVideoPacket.decode(badBlock, badBlock.size))
        val hugeParity = bytes.copyOf().also { it[8] = 0x7F; it[9] = 0x00 }
        assertNull(UdpVideoPacket.decode(hugeParity, hugeParity.size))
    }

    @Test
    fun `a small frame packetizes into one block with one parity packet`() {
        val frame = StreamFrame(7, 1000L, true, ByteArray(3000) { (it % 251).toByte() })
        val packets = packetizeFrame(frame)
        assertEquals(4, packets.size)
        assertEquals(1, packets[0].blockCount)
        assertEquals(3, packets[0].dataPackets)
        assertEquals(1, packets[0].parityPackets)
        assertTrue(packets[0].isSof)
        assertFalse(packets[0].isParity)
        assertTrue(packets[3].isParity)
        assertTrue(packets[3].isEof)
        assertEquals(7, packets[3].frameIndex)
        assertEquals(3000, packets[3].frameBytes)
    }

    @Test
    fun `a large frame splits into blocks that stay under the shard limit`() {
        val frame = StreamFrame(9, 2000L, false, ByteArray(205 * 1024) { (it % 251).toByte() })
        val packets = packetizeFrame(frame)
        val dataPackets = packets.count { !it.isParity }
        val parityPackets = packets.count { it.isParity }
        assertEquals(205, dataPackets)
        assertEquals(2, packets[0].blockCount)
        assertEquals(200, packets[0].dataPackets)
        assertEquals(40, packets[0].parityPackets)
        assertEquals(5, packets.last { !it.isParity }.dataPackets)
        assertEquals(1, packets.last { it.isParity }.parityPackets)
        assertTrue(packets.count { it.blockIndex == 0 } <= 240)
        assertTrue(packets.count { it.blockIndex == 1 } <= 240)
        assertEquals(41, parityPackets)
    }

    @Test
    fun `packetized frames reassemble byte for byte`() {
        val frame = StreamFrame(3, 3000L, false, ByteArray(5000) { (it * 7 % 251).toByte() })
        val packets = packetizeFrame(frame)
        val assembler = UdpFrameAssembler(
            onFrame = { index, _, bytes ->
                assertEquals(3, index)
                assertArrayEquals(frame.bytes, bytes)
            },
            onFrameLost = { throw AssertionError("frame must not be lost") },
        )
        packets.forEach { assembler.offer(it) }
        assertEquals(1, assembler.framesReceived)
    }

    @Test
    fun `control messages round-trip and reject an unknown kind`() {
        val rfi = ControlMessage.rfi(10, 12)
        assertEquals(rfi, ControlMessage.decode(rfi.encode(), ControlMessage.SIZE))
        assertEquals(0x0301, rfi.kind)
        val idr = ControlMessage.idr()
        assertEquals(idr, ControlMessage.decode(idr.encode(), ControlMessage.SIZE))
        assertEquals(0x0302, idr.kind)
        val unknown = ByteArray(ControlMessage.SIZE).also { it[0] = 0x09; it[1] = 0x09 }
        assertNull(ControlMessage.decode(unknown, ControlMessage.SIZE))
        assertNull(ControlMessage.decode(byteArrayOf(1, 2, 3), 3))
    }

    @Test
    fun `subscribe datagram round-trips and rejects garbage`() {
        val datagram = UdpVideo.subscribeDatagram()
        assertTrue(UdpVideo.isSubscribeDatagram(datagram, datagram.size))
        assertFalse(UdpVideo.isSubscribeDatagram(byteArrayOf(1, 2, 3, 4, 5), 5))
        assertFalse(UdpVideo.isSubscribeDatagram(datagram, 4))
    }

    @Test
    fun `port offsets match the fixed USB port layout`() {
        assertEquals(UsbTether.USB_VIDEO_UDP_PORT, UdpVideo.videoPort(UsbTether.USB_STREAM_PORT))
        assertEquals(UsbTether.USB_CONTROL_PORT, UdpVideo.controlPort(UsbTether.USB_STREAM_PORT))
        assertEquals(28754, UdpVideo.videoPort(28752))
        assertEquals(28755, UdpVideo.controlPort(28752))
    }

    @Test
    fun `idr detection finds type 5 after three and four byte start codes`() {
        val idr4 = byteArrayOf(0, 0, 0, 1, 0x65, 0x11, 0x22)
        val idr3 = byteArrayOf(0, 0, 1, 0x65, 0x11)
        val nonIdr = byteArrayOf(0, 0, 0, 1, 0x41, 0x33, 0x44)
        val sps = byteArrayOf(0, 0, 0, 1, 0x67, 0x42, 0x01)
        assertTrue(containsIdrNal(idr4))
        assertTrue(containsIdrNal(idr3))
        assertFalse(containsIdrNal(nonIdr))
        assertFalse(containsIdrNal(sps))
        assertTrue(containsIdrNal(sps + nonIdr + idr3))
        assertEquals(5, firstNalType(idr4))
        assertEquals(1, firstNalType(nonIdr))
        assertEquals(7, firstNalType(sps))
        assertEquals(-1, firstNalType(byteArrayOf(1, 2, 3)))
    }
}
