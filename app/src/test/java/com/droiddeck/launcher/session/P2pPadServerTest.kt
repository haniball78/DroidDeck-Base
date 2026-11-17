package com.droiddeck.launcher.session

import com.droiddeck.launcher.input.PadState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.io.DataOutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
class P2pPadServerTest {
    @Test
    fun `begin binds a port and start is idempotent`() {
        val server = P2pPadServer()
        server.attach(RuntimeEnvironment.getApplication())
        try {
            assertNull(server.begin())
            assertTrue(server.port > 0)
            assertNull(server.begin())
        } finally {
            server.stop()
        }
    }

    @Test
    fun `begin fails honestly when not attached`() {
        val server = P2pPadServer()
        assertEquals("no-context", server.begin())
        server.stop()
    }

    @Test
    fun `server decodes a state sent over loopback`() {
        val server = P2pPadServer()
        server.attach(RuntimeEnvironment.getApplication())
        val received = ArrayList<Pair<Int, PadState>>()
        val gotState = CountDownLatch(1)
        server.onState = { slot, state ->
            received += slot to state
            gotState.countDown()
        }
        try {
            assertNull(server.begin())
            val socket = Socket()
            socket.connect(InetSocketAddress("127.0.0.1", server.port), 5_000)
            val out = DataOutputStream(socket.getOutputStream())
            val state = PadState().apply {
                leftX = -0.5f
                rightTrigger = 0.9f
                press(PadState.X, true)
                down = true
            }
            val bytes = PadWire.encode(state)
            out.writeInt(bytes.size)
            out.write(bytes)
            out.flush()
            assertTrue(gotState.await(5, TimeUnit.SECONDS))
            assertEquals(1, received.size)
            assertEquals(1, received[0].first)
            assertEquals(-0.5f, received[0].second.leftX, 0f)
            assertEquals(0.9f, received[0].second.rightTrigger, 0f)
            assertTrue(received[0].second.isDown(PadState.X))
            assertTrue(received[0].second.down)
            socket.close()
        } finally {
            server.stop()
        }
    }

    @Test
    fun `server applies the slot the client sent for slot 0`() {
        val server = P2pPadServer()
        server.attach(RuntimeEnvironment.getApplication())
        val received = ArrayList<Pair<Int, PadState>>()
        val gotState = CountDownLatch(1)
        server.onState = { slot, state ->
            received += slot to state
            gotState.countDown()
        }
        try {
            assertNull(server.begin())
            val socket = Socket()
            socket.connect(InetSocketAddress("127.0.0.1", server.port), 5_000)
            val out = DataOutputStream(socket.getOutputStream())
            val state = PadState().apply {
                leftX = 0.25f
                press(PadState.A, true)
            }
            val bytes = PadWire.encode(state, 0)
            out.writeInt(bytes.size)
            out.write(bytes)
            out.flush()
            assertTrue(gotState.await(5, TimeUnit.SECONDS))
            assertEquals(1, received.size)
            assertEquals(0, received[0].first)
            assertEquals(0.25f, received[0].second.leftX, 0f)
            assertTrue(received[0].second.isDown(PadState.A))
            socket.close()
        } finally {
            server.stop()
        }
    }

    @Test
    fun `stop is safe without begin`() {
        P2pPadServer().stop()
    }
}
