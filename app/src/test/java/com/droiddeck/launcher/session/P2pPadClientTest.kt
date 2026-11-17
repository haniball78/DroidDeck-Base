package com.droiddeck.launcher.session

import com.droiddeck.launcher.input.PadState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.DataInputStream
import java.net.ServerSocket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
class P2pPadClientTest {
    @Test
    fun `client delivers the encoded state over loopback`() {
        val server = ServerSocket(0, 1, null)
        val received = ArrayList<PadState>()
        val firstMessage = CountDownLatch(1)
        val accept = Thread({
            val socket = server.accept()
            val input = DataInputStream(socket.getInputStream())
            val length = input.readInt()
            val payload = ByteArray(length)
            input.readFully(payload)
            received += PadWire.decode(payload)!!
            firstMessage.countDown()
            socket.close()
        }, "pad-client-test-accept").apply { start() }
        try {
            val client = P2pPadClient()
            assertTrue(client.connect("127.0.0.1", server.localPort))
            val state = PadState().apply {
                leftX = 0.5f
                press(PadState.A, true)
                up = true
            }
            client.send(state)
            assertTrue(firstMessage.await(5, TimeUnit.SECONDS))
            client.close()
            assertEquals(1, received.size)
            assertEquals(0.5f, received[0].leftX, 0f)
            assertTrue(received[0].isDown(PadState.A))
            assertTrue(received[0].up)
        } finally {
            accept.join(2_000)
            server.close()
        }
    }

    @Test
    fun `client sends only when the state changed`() {
        val server = ServerSocket(0, 1, null)
        val count = AtomicInteger(0)
        val accept = Thread({
            val socket = server.accept()
            val input = DataInputStream(socket.getInputStream())
            try {
                while (true) {
                    val length = input.readInt()
                    input.skipBytes(length)
                    count.incrementAndGet()
                }
            } catch (_: Exception) {
                // Client closed.
            }
            socket.close()
        }, "pad-client-test-count").apply { start() }
        try {
            val client = P2pPadClient()
            assertTrue(client.connect("127.0.0.1", server.localPort))
            val state = PadState().apply { press(PadState.A, true) }
            client.send(state)
            client.send(state)
            client.send(state)
            Thread.sleep(400)
            assertEquals(1, count.get())
            state.press(PadState.B, true)
            client.send(state)
            Thread.sleep(400)
            assertEquals(2, count.get())
            client.close()
        } finally {
            accept.join(2_000)
            server.close()
        }
    }

    @Test
    fun `connect returns false when nothing listens`() {
        val client = P2pPadClient()
        assertFalse(client.connect("127.0.0.1", 1))
    }
}
