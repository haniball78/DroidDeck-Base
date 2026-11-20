package com.droiddeck.launcher.session

import android.view.KeyEvent
import com.droiddeck.launcher.input.PadState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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
            received += PadWire.decode((InputWire.parse(payload) as InputMessage.Pad).payload)!!.second
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
    fun `a quick press and release both arrive`() {
        val server = ServerSocket(0, 1, null)
        val received = ArrayList<PadState>()
        val gotBoth = CountDownLatch(2)
        val accept = Thread({
            val socket = server.accept()
            val input = DataInputStream(socket.getInputStream())
            try {
                while (true) {
                    val length = input.readInt()
                    val payload = ByteArray(length)
                    input.readFully(payload)
                    received += PadWire.decode((InputWire.parse(payload) as InputMessage.Pad).payload)!!.second
                    gotBoth.countDown()
                }
            } catch (_: Exception) {
                // Client closed.
            }
            socket.close()
        }, "pad-client-test-tap").apply { start() }
        try {
            val client = P2pPadClient()
            assertTrue(client.connect("127.0.0.1", server.localPort))
            client.send(PadState().apply { press(PadState.A, true) })
            client.send(PadState())
            assertTrue(gotBoth.await(5, TimeUnit.SECONDS))
            client.close()
            assertEquals(2, received.size)
            assertTrue(received[0].isDown(PadState.A))
            assertFalse(received[1].isDown(PadState.A))
        } finally {
            accept.join(2_000)
            server.close()
        }
    }

    @Test
    fun `an unchanged state is not resent after delivery`() {
        val server = ServerSocket(0, 1, null)
        val count = AtomicInteger(0)
        val gotFirst = CountDownLatch(1)
        val accept = Thread({
            val socket = server.accept()
            val input = DataInputStream(socket.getInputStream())
            try {
                while (true) {
                    val length = input.readInt()
                    input.skipBytes(length)
                    if (count.incrementAndGet() == 1) gotFirst.countDown()
                }
            } catch (_: Exception) {
                // Client closed.
            }
            socket.close()
        }, "pad-client-test-noresend").apply { start() }
        try {
            val client = P2pPadClient()
            assertTrue(client.connect("127.0.0.1", server.localPort))
            val state = PadState().apply { press(PadState.A, true) }
            client.send(state)
            assertTrue(gotFirst.await(5, TimeUnit.SECONDS))
            client.send(state)
            Thread.sleep(400)
            assertEquals(1, count.get())
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

    @Test
    fun `connect retries until the server appears`() {
        val port = ServerSocket(0, 1, null).use { it.localPort }
        val accepted = CountDownLatch(1)
        val serverThread = Thread({
            Thread.sleep(600)
            val server = ServerSocket(port, 1, null)
            try {
                val socket = server.accept()
                accepted.countDown()
                socket.close()
            } finally {
                server.close()
            }
        }, "pad-client-test-retry").apply { start() }
        try {
            val client = P2pPadClient()
            assertTrue(client.connect("127.0.0.1", port))
            assertTrue(accepted.await(5, TimeUnit.SECONDS))
            client.close()
        } finally {
            serverThread.join(5_000)
        }
    }

    @Test
    fun `client with slot 0 sends a kind-prefixed payload whose pad slot is 0`() {
        val server = ServerSocket(0, 1, null)
        val payloads = ArrayList<ByteArray>()
        val firstMessage = CountDownLatch(1)
        val accept = Thread({
            val socket = server.accept()
            val input = DataInputStream(socket.getInputStream())
            val length = input.readInt()
            val payload = ByteArray(length)
            input.readFully(payload)
            payloads += payload
            firstMessage.countDown()
            socket.close()
        }, "pad-client-test-slot0").apply { start() }
        try {
            val client = P2pPadClient(0)
            assertTrue(client.connect("127.0.0.1", server.localPort))
            client.send(PadState().apply { press(PadState.A, true) })
            assertTrue(firstMessage.await(5, TimeUnit.SECONDS))
            client.close()
            assertEquals(1, payloads.size)
            assertEquals(1 + PadWire.PAYLOAD_BYTES, payloads[0].size)
            assertEquals(InputWire.KIND_PAD, payloads[0][0].toInt())
            val (slot, state) = PadWire.decode((InputWire.parse(payloads[0]) as InputMessage.Pad).payload)!!
            assertEquals(0, slot)
            assertTrue(state.isDown(PadState.A))
        } finally {
            accept.join(2_000)
            server.close()
        }
    }

    @Test
    fun `queued key messages arrive in order and are never coalesced`() {
        val server = ServerSocket(0, 1, null)
        val messages = ArrayList<ByteArray>()
        val gotBoth = CountDownLatch(2)
        val accept = Thread({
            val socket = server.accept()
            val input = DataInputStream(socket.getInputStream())
            try {
                while (true) {
                    val length = input.readInt()
                    val payload = ByteArray(length)
                    input.readFully(payload)
                    messages += payload
                    gotBoth.countDown()
                }
            } catch (_: Exception) {
                // Client closed.
            }
            socket.close()
        }, "pad-client-test-queue").apply { start() }
        try {
            val client = P2pPadClient()
            assertTrue(client.connect("127.0.0.1", server.localPort))
            client.sendKey(KeyEvent.KEYCODE_A, 0, 0, true)
            client.sendKey(KeyEvent.KEYCODE_A, 0, 0, false)
            assertTrue(gotBoth.await(5, TimeUnit.SECONDS))
            client.close()
            assertEquals(2, messages.size)
            val first = InputWire.parse(messages[0]) as InputMessage.Key
            val second = InputWire.parse(messages[1]) as InputMessage.Key
            assertEquals(KeyEvent.KEYCODE_A, first.keyCode)
            assertTrue(first.down)
            assertEquals(KeyEvent.KEYCODE_A, second.keyCode)
            assertFalse(second.down)
        } finally {
            accept.join(2_000)
            server.close()
        }
    }

    @Test
    fun `client rejects a slot outside 0 to 3`() {
        for (bad in listOf(-1, 4)) {
            try {
                P2pPadClient(bad)
                throw AssertionError("expected IllegalArgumentException for slot $bad")
            } catch (_: IllegalArgumentException) {
                // Expected.
            }
        }
    }

    @Test
    fun `nsd endpoint takes host and port from resolution`() {
        assertEquals("192.168.49.1" to 40001, nsdEndpoint("192.168.49.1", 40001))
    }

    @Test
    fun `nsd endpoint rejects the port 0 registration quirk`() {
        assertNull(nsdEndpoint("192.168.49.1", 0))
    }

    @Test
    fun `nsd endpoint rejects a missing host or an unusable port`() {
        assertNull(nsdEndpoint(null, 40001))
        assertNull(nsdEndpoint("", 40001))
        assertNull(nsdEndpoint("  ", 40001))
        assertNull(nsdEndpoint("192.168.49.1", -1))
        assertNull(nsdEndpoint("192.168.49.1", 65536))
    }
}
