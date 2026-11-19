package com.droiddeck.launcher.session

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.net.InetSocketAddress

@RunWith(RobolectricTestRunner::class)
class UdpVideoServerTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun `a subscribed client receives a packetized frame end to end`() {
        val server = UdpVideoServer(0)
        server.attach(context)
        try {
            assertEquals(null, server.begin())
            val port = server.port
            assertTrue(port > 0)
            // The VM sandbox blocks UDP sends, so the datagram path is driven
            // through the injectable sender and the subscribe handler.
            val sent = ArrayList<ByteArray>()
            server.sender = { bytes, _ ->
                sent.add(bytes)
                true
            }
            var subscribes = 0
            server.onSubscribe = { subscribes++ }
            val clientAddress = InetSocketAddress("127.0.0.1", 40000)
            server.onSubscribeDatagram(clientAddress)
            assertTrue(server.hasClient())
            assertEquals(1, subscribes)
            val frame = StreamFrame(42, 123456L, true, ByteArray(3000) { (it * 7 % 251).toByte() })
            server.feed(frame)
            assertEquals(1, server.framesSent.get())
            val delivered = ArrayList<ByteArray>()
            val assembler = UdpFrameAssembler(
                onFrame = { _, _, bytes -> delivered.add(bytes) },
                onFrameLost = { throw AssertionError("the frame must arrive") },
            )
            for (bytes in sent) {
                val video = UdpVideoPacket.decode(bytes, bytes.size) ?: continue
                assembler.offer(video)
            }
            assertEquals(1, delivered.size)
            assertArrayEquals(frame.bytes, delivered[0])
        } finally {
            server.stop()
        }
    }

    @Test
    fun `feed drops frames with no subscriber`() {
        val server = UdpVideoServer(0)
        server.attach(context)
        try {
            assertEquals(null, server.begin())
            server.feed(StreamFrame(1, 1000L, true, byteArrayOf(1, 2, 3)))
            assertEquals(0, server.framesSent.get())
        } finally {
            server.stop()
        }
    }

    @Test
    fun `the control server delivers rfi and idr messages to the callback`() {
        val received = ArrayList<ControlMessage>()
        val server = UdpControlServer(0) { received.add(it) }
        server.attach(context)
        val client = UdpControlClient()
        try {
            assertEquals(null, server.begin())
            assertTrue(server.port > 0)
            assertTrue(client.connect("127.0.0.1", server.port))
            assertTrue(client.sendRfi(10, 12))
            assertTrue(client.sendIdr())
            val deadline = System.currentTimeMillis() + 5_000
            while (received.size < 2 && System.currentTimeMillis() < deadline) Thread.sleep(10)
            assertEquals(2, received.size)
            assertEquals(ControlMessage(ControlMessage.KIND_RFI, 10, 12), received[0])
            assertEquals(ControlMessage(ControlMessage.KIND_IDR, 0, 0), received[1])
        } finally {
            client.close()
            server.stop()
        }
    }
}
