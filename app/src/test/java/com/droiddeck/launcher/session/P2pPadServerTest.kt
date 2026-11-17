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
        val received = ArrayList<PadState>()
        val gotState = CountDownLatch(1)
        server.onState = { state ->
            received += state
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
            assertEquals(-0.5f, received[0].leftX, 0f)
            assertEquals(0.9f, received[0].rightTrigger, 0f)
            assertTrue(received[0].isDown(PadState.X))
            assertTrue(received[0].down)
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
