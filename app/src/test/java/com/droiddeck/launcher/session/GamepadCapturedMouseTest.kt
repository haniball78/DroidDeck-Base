package com.droiddeck.launcher.session

import android.view.InputDevice
import android.view.MotionEvent
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.DataInputStream
import java.net.ServerSocket
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class GamepadCapturedMouseTest {
    @After
    fun tearDown() {
        GamepadInput.clear()
    }

    @Test
    fun `captured move sums current and historical deltas`() {
        val (server, messages, gotOne, accept) = startMouseServer(1)
        try {
            val client = installClient(server)
            try {
                val event = relativeMove(5f, 7f, 3f, 2f)
                assertTrue(GamepadInput.onCapturedPointer(event))
                assertTrue(gotOne.await(5, TimeUnit.SECONDS))
                assertEquals(1, messages.size)
                assertEquals(8f, messages[0].dx, 0f)
                assertEquals(9f, messages[0].dy, 0f)
                assertEquals(0, messages[0].buttons)
                assertEquals(0f, messages[0].wheel, 0f)
            } finally {
                client.close()
            }
        } finally {
            accept.join(2_000)
            server.close()
        }
    }

    @Test
    fun `captured buttons track the held mask`() {
        val (server, messages, gotFour, accept) = startMouseServer(4)
        try {
            val client = installClient(server)
            try {
                assertTrue(GamepadInput.onCapturedPointer(relativeButton(MotionEvent.ACTION_BUTTON_PRESS, MotionEvent.BUTTON_PRIMARY)))
                assertTrue(GamepadInput.onCapturedPointer(relativeButton(MotionEvent.ACTION_BUTTON_PRESS, MotionEvent.BUTTON_PRIMARY or MotionEvent.BUTTON_SECONDARY)))
                assertTrue(GamepadInput.onCapturedPointer(relativeButton(MotionEvent.ACTION_BUTTON_RELEASE, MotionEvent.BUTTON_SECONDARY)))
                assertTrue(GamepadInput.onCapturedPointer(relativeButton(MotionEvent.ACTION_BUTTON_RELEASE, 0)))
                assertTrue(gotFour.await(5, TimeUnit.SECONDS))
                assertEquals(listOf(1, 3, 2, 0), messages.map { it.buttons })
                assertTrue(messages.all { it.dx == 0f && it.dy == 0f && it.wheel == 0f })
            } finally {
                client.close()
            }
        } finally {
            accept.join(2_000)
            server.close()
        }
    }

    @Test
    fun `captured scroll forwards the wheel value`() {
        val (server, messages, gotOne, accept) = startMouseServer(1)
        try {
            val client = installClient(server)
            try {
                val event = relativeEvent(MotionEvent.ACTION_SCROLL, vscroll = 2f)
                assertTrue(GamepadInput.onCapturedPointer(event))
                assertTrue(gotOne.await(5, TimeUnit.SECONDS))
                assertEquals(1, messages.size)
                assertEquals(0f, messages[0].dx, 0f)
                assertEquals(0f, messages[0].dy, 0f)
                assertEquals(0, messages[0].buttons)
                assertEquals(2f, messages[0].wheel, 0f)
            } finally {
                client.close()
            }
        } finally {
            accept.join(2_000)
            server.close()
        }
    }

    @Test
    fun `absolute mouse events are rejected by the captured path`() {
        val (server, messages, _, accept) = startMouseServer(0)
        try {
            val client = installClient(server)
            try {
                val event = relativeEvent(MotionEvent.ACTION_MOVE, x = 5f, y = 7f)
                event.source = InputDevice.SOURCE_MOUSE
                assertFalse(GamepadInput.onCapturedPointer(event))
                Thread.sleep(300)
                assertTrue(messages.isEmpty())
            } finally {
                client.close()
            }
        } finally {
            accept.join(2_000)
            server.close()
        }
    }

    @Test
    fun `captured pointer without a client returns false`() {
        GamepadInput.clear()
        assertFalse(GamepadInput.onCapturedPointer(relativeMove(5f, 7f, 0f, 0f)))
    }

    @Test
    fun `capture loss clears held buttons`() {
        val (server, messages, gotThree, accept) = startMouseServer(3)
        try {
            val client = installClient(server)
            try {
                assertTrue(GamepadInput.onCapturedPointer(relativeButton(MotionEvent.ACTION_BUTTON_PRESS, MotionEvent.BUTTON_PRIMARY)))
                GamepadInput.onPointerCaptureLost()
                assertTrue(GamepadInput.onCapturedPointer(relativeMove(1f, 1f, 0f, 0f)))
                assertTrue(gotThree.await(5, TimeUnit.SECONDS))
                assertEquals(listOf(1, 0, 0), messages.map { it.buttons })
            } finally {
                client.close()
            }
        } finally {
            accept.join(2_000)
            server.close()
        }
    }

    private fun installClient(server: ServerSocket): P2pPadClient {
        GamepadInput.clear()
        val client = P2pPadClient(1)
        assertTrue(client.connect("127.0.0.1", server.localPort))
        GamepadInput.install(null, client)
        return client
    }

    private data class MouseServer(
        val server: ServerSocket,
        val messages: MutableList<InputMessage.Mouse>,
        val latch: CountDownLatch,
        val accept: Thread,
    )

    private fun startMouseServer(expected: Int): MouseServer {
        val server = ServerSocket(0, 1, null)
        val messages = Collections.synchronizedList(ArrayList<InputMessage.Mouse>())
        val latch = CountDownLatch(expected)
        val accept = server.acceptThread(messages, latch)
        return MouseServer(server, messages, latch, accept)
    }

    private fun ServerSocket.acceptThread(
        messages: MutableList<InputMessage.Mouse>? = null,
        latch: CountDownLatch? = null,
    ): Thread = Thread({
        try {
            val socket = accept()
            val input = DataInputStream(socket.getInputStream())
            while (true) {
                val length = input.readInt()
                val payload = ByteArray(length)
                input.readFully(payload)
                (InputWire.parse(payload) as? InputMessage.Mouse)?.let {
                    messages?.add(it)
                    latch?.countDown()
                }
            }
        } catch (_: Exception) {
            // Client closed.
        }
    }, "captured-mouse-test-accept").apply { start() }

    /** A relative-source MOVE whose current point is (x, y) with one historical point (hx, hy). */
    private fun relativeMove(x: Float, y: Float, hx: Float, hy: Float): MotionEvent {
        val event = relativeEvent(MotionEvent.ACTION_MOVE, x = x, y = y)
        if (hx != 0f || hy != 0f) event.addBatch(5L, hx, hy, 0f, 0f, 0)
        return event
    }

    private fun relativeButton(action: Int, buttonState: Int): MotionEvent {
        val props = arrayOf(MotionEvent.PointerProperties().apply {
            id = 0
            toolType = MotionEvent.TOOL_TYPE_MOUSE
        })
        val coords = arrayOf(MotionEvent.PointerCoords())
        return MotionEvent.obtain(
            0L, 10L, action, 1, props, coords,
            0, buttonState, 1f, 1f, 0, 0,
            InputDevice.SOURCE_MOUSE_RELATIVE, 0,
        )!!
    }

    private fun relativeEvent(
        action: Int,
        x: Float = 0f,
        y: Float = 0f,
        vscroll: Float = 0f,
    ): MotionEvent {
        val props = arrayOf(MotionEvent.PointerProperties().apply {
            id = 0
            toolType = MotionEvent.TOOL_TYPE_MOUSE
        })
        val coords = arrayOf(MotionEvent.PointerCoords().apply {
            this.x = x
            this.y = y
            setAxisValue(MotionEvent.AXIS_VSCROLL, vscroll)
        })
        return MotionEvent.obtain(
            0L, 10L, action, 1, props, coords,
            0, 0, 1f, 1f, 0, 0,
            InputDevice.SOURCE_MOUSE_RELATIVE, 0,
        )!!
    }
}
