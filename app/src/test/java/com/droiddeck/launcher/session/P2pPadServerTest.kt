package com.droiddeck.launcher.session

import android.view.KeyEvent
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
            val bytes = InputWire.wrap(InputWire.KIND_PAD, PadWire.encode(state))
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
            val bytes = InputWire.wrap(InputWire.KIND_PAD, PadWire.encode(state, 0))
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
    fun `begin binds the fixed USB pad port and answers on loopback`() {
        val server = P2pPadServer(UsbTether.USB_PAD_PORT)
        server.attach(RuntimeEnvironment.getApplication())
        val received = ArrayList<Pair<Int, PadState>>()
        val gotState = CountDownLatch(1)
        server.onState = { slot, state ->
            received += slot to state
            gotState.countDown()
        }
        try {
            assertNull(server.begin())
            assertEquals(UsbTether.USB_PAD_PORT, server.port)
            val socket = Socket()
            socket.connect(InetSocketAddress("127.0.0.1", server.port), 5_000)
            val out = DataOutputStream(socket.getOutputStream())
            val bytes = InputWire.wrap(InputWire.KIND_PAD, PadWire.encode(PadState().apply { press(PadState.A, true) }, 1))
            out.writeInt(bytes.size)
            out.write(bytes)
            out.flush()
            assertTrue(gotState.await(5, TimeUnit.SECONDS))
            assertEquals(1, received.size)
            assertEquals(1, received[0].first)
            assertTrue(received[0].second.isDown(PadState.A))
            socket.close()
        } finally {
            server.stop()
        }
    }

    @Test
    fun `server dispatches key and mouse messages to the guest input hook`() {
        val server = P2pPadServer()
        server.attach(RuntimeEnvironment.getApplication())
        val keys = ArrayList<Pair<Int, Boolean>>()
        val mice = ArrayList<InputMessage.Mouse>()
        val gotKey = CountDownLatch(1)
        val gotMouse = CountDownLatch(1)
        val previous = SessionState.guestInput
        SessionState.guestInput = object : GuestInput {
            override fun sendKey(evdev: Int, down: Boolean) {
                keys += evdev to down
                gotKey.countDown()
            }

            override fun sendMouse(dx: Float, dy: Float, buttons: Int, wheel: Float) {
                mice += InputMessage.Mouse(dx, dy, buttons, wheel)
                gotMouse.countDown()
            }
        }
        try {
            assertNull(server.begin())
            val socket = Socket()
            socket.connect(InetSocketAddress("127.0.0.1", server.port), 5_000)
            val out = DataOutputStream(socket.getOutputStream())
            val key = InputWire.encodeKey(KeyEvent.KEYCODE_A, 0, 0, true)
            out.writeInt(key.size)
            out.write(key)
            val mouse = InputWire.encodeMouse(10f, -5f, 1, 0f)
            out.writeInt(mouse.size)
            out.write(mouse)
            out.flush()
            assertTrue(gotKey.await(5, TimeUnit.SECONDS))
            assertTrue(gotMouse.await(5, TimeUnit.SECONDS))
            assertEquals(listOf(30 to true), keys)
            assertEquals(1, mice.size)
            assertEquals(10f, mice[0].dx, 0f)
            assertEquals(-5f, mice[0].dy, 0f)
            assertEquals(1, mice[0].buttons)
            assertEquals(0f, mice[0].wheel, 0f)
            socket.close()
        } finally {
            SessionState.guestInput = previous
            server.stop()
        }
    }

    @Test
    fun `stop closes the listening socket so the port can be bound again`() {
        val first = P2pPadServer(UsbTether.USB_PAD_PORT)
        first.attach(RuntimeEnvironment.getApplication())
        assertNull(first.begin())
        assertEquals(UsbTether.USB_PAD_PORT, first.port)
        first.stop()
        val second = P2pPadServer(UsbTether.USB_PAD_PORT)
        second.attach(RuntimeEnvironment.getApplication())
        try {
            assertNull(second.begin())
            assertEquals(UsbTether.USB_PAD_PORT, second.port)
        } finally {
            second.stop()
        }
    }

    @Test
    fun `stop is safe without begin`() {
        P2pPadServer().stop()
    }
}
