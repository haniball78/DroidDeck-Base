package com.droiddeck.launcher.session

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.DataInputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException

@RunWith(RobolectricTestRunner::class)
class TvStreamServerTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun `stream test is off by default`() {
        context.getSharedPreferences("session", Context.MODE_PRIVATE)
            .edit().remove("tvStreamTest").apply()
        assertFalse(SessionPrefs.tvStreamTest(context))
    }

    @Test
    fun `stream toggle round-trips`() {
        SessionPrefs.setTvStreamTest(context, true)
        assertTrue(SessionPrefs.tvStreamTest(context))
        SessionPrefs.setTvStreamTest(context, false)
        assertFalse(SessionPrefs.tvStreamTest(context))
    }

    @Test
    fun `stream summary formats full stats`() {
        assertEquals(
            "frames=12 bytes=304128 clients=1 dropped=0 firstFrameMs=1234 elapsedMs=5000",
            streamSummary(longArrayOf(12, 304128, 1, 0, 1234, 5000)),
        )
    }

    @Test
    fun `stream summary reads a zero run as no frames`() {
        assertEquals(
            "frames=0 bytes=0 clients=0 dropped=0 firstFrameMs=-1 elapsedMs=0",
            streamSummary(longArrayOf(0, 0, 0, 0, -1, 0)),
        )
    }

    @Test
    fun `stream summary tolerates a short array`() {
        assertEquals(
            "frames=3 bytes=99 clients=0 dropped=0 firstFrameMs=-1 elapsedMs=0",
            streamSummary(longArrayOf(3, 99)),
        )
    }

    @Test
    fun `annex b scanner splits three and four byte start codes`() {
        val scanner = AnnexBScanner()
        // SPS payload must not end with 0x00: with the next 3-byte start code it forms a 4-byte
        // start code, and Annex B attributes that byte to the start code, not the NAL.
        val sps = byteArrayOf(0, 0, 0, 1, 0x67, 0x42, 0x01)
        val pps = byteArrayOf(0, 0, 1, 0x68, 0x01)
        val idr = byteArrayOf(0, 0, 0, 1, 0x65, 0x11, 0x22)
        val units = scanner.feed(sps + pps + idr + byteArrayOf(0, 0, 1, 0x41))
        assertEquals(3, units.size)
        assertArrayEquals(sps, units[0])
        assertArrayEquals(pps, units[1])
        assertArrayEquals(idr, units[2])
    }

    @Test
    fun `annex b scanner holds a partial unit across feeds`() {
        val scanner = AnnexBScanner()
        val first = scanner.feed(byteArrayOf(0, 0, 0, 1, 0x67, 0x42, 0x01, 0, 0, 1, 0x68))
        assertEquals(1, first.size)
        val second = scanner.feed(byteArrayOf(0x01, 0x02, 0, 0, 0, 1, 0x65))
        assertEquals(1, second.size)
        assertArrayEquals(byteArrayOf(0, 0, 1, 0x68, 0x01, 0x02), second[0])
    }

    @Test
    fun `annex b scanner drops leading garbage before the first start code`() {
        val scanner = AnnexBScanner()
        val units = scanner.feed(byteArrayOf(0x11, 0x22, 0, 0, 1, 0x65, 0x01, 0, 0, 1, 0x41, 0x02))
        assertEquals(1, units.size)
        assertArrayEquals(byteArrayOf(0, 0, 1, 0x65, 0x01), units[0])
    }

    @Test
    fun `annex b scanner flush emits the trailing unit at a chunk boundary`() {
        val scanner = AnnexBScanner()
        val first = byteArrayOf(0, 0, 0, 1, 0x67, 0x42, 0x01)
        val second = byteArrayOf(0, 0, 1, 0x68, 0x01)
        assertEquals(0, scanner.feed(first).size)
        assertArrayEquals(first, scanner.flush().single())
        assertEquals(0, scanner.feed(second).size)
        assertArrayEquals(second, scanner.flush().single())
    }

    @Test
    fun `annex b scanner flush keeps a bare start code buffered`() {
        val scanner = AnnexBScanner()
        val nal = byteArrayOf(0, 0, 1, 0x65, 0x11)
        assertEquals(1, scanner.feed(nal + byteArrayOf(0, 0, 1)).size)
        assertEquals(0, scanner.flush().size)
        assertEquals(0, scanner.feed(byteArrayOf(0x41, 0x02)).size)
        assertArrayEquals(byteArrayOf(0, 0, 1, 0x41, 0x02), scanner.flush().single())
    }

    @Test
    fun `annex b scanner flush on an empty scanner returns nothing`() {
        assertEquals(0, AnnexBScanner().flush().size)
    }

    @Test
    fun `stream begin fails honestly without a session context`() {
        val server = TvStreamServer()
        assertNotNull(server.begin())
        server.stop()
    }

    @Test
    fun `stream server binds the fixed USB stream port`() {
        val server = TvStreamServer(UsbTether.USB_STREAM_PORT)
        server.attach(context)
        try {
            assertNull(server.begin())
            val socket = Socket()
            socket.connect(InetSocketAddress("127.0.0.1", UsbTether.USB_STREAM_PORT), 5_000)
            assertTrue(socket.isConnected)
            socket.close()
        } finally {
            server.stop()
        }
    }

    @Test
    fun `registration carries the bound port`() {
        val info = buildServiceInfo(28752)
        assertEquals(28752, info.port)
        assertEquals("DroidDeck TV", info.serviceName)
        assertEquals("_droiddeck-tv._tcp.", info.serviceType)
    }

    @Test
    fun `server exposes its bound port after begin`() {
        val server = TvStreamServer(0)
        server.attach(context)
        try {
            assertNull(server.begin())
            assertTrue(server.port > 0)
            assertEquals(server.port, buildServiceInfo(server.port).port)
        } finally {
            server.stop()
        }
    }

    @Test
    fun `feed sends fed chunks to a connected client in order`() {
        val server = TvStreamServer(0)
        server.attach(context)
        var socket: Socket? = null
        try {
            assertNull(server.begin())
            socket = Socket()
            socket.connect(InetSocketAddress("127.0.0.1", server.port), 5_000)
            socket.soTimeout = 5_000
            assertTrue(waitForClient(server))
            val sps = byteArrayOf(0, 0, 0, 1, 0x67, 0x42, 0x01)
            val pps = byteArrayOf(0, 0, 1, 0x68, 0x01)
            val idr = byteArrayOf(0, 0, 0, 1, 0x65, 0x11, 0x22)
            server.feed(sps + pps)
            server.feed(idr + byteArrayOf(0, 0, 1, 0x41))
            val input = DataInputStream(socket.getInputStream())
            assertArrayEquals(sps, readUnit(input))
            assertArrayEquals(pps, readUnit(input))
            assertArrayEquals(idr, readUnit(input))
        } finally {
            runCatching { socket?.close() }
            server.stop()
        }
    }

    @Test
    fun `a single fed chunk reaches the client without a second chunk`() {
        val server = TvStreamServer(0)
        server.attach(context)
        var socket: Socket? = null
        try {
            assertNull(server.begin())
            socket = Socket()
            socket.connect(InetSocketAddress("127.0.0.1", server.port), 5_000)
            socket.soTimeout = 5_000
            assertTrue(waitForClient(server))
            val nal = byteArrayOf(0, 0, 0, 1, 0x65, 0x11, 0x22)
            server.feed(nal)
            val input = DataInputStream(socket.getInputStream())
            assertArrayEquals(nal, readUnit(input))
        } finally {
            runCatching { socket?.close() }
            server.stop()
        }
    }

    @Test
    fun `feed drops chunks while no client is connected`() {
        val server = TvStreamServer(0)
        server.attach(context)
        var socket: Socket? = null
        try {
            assertNull(server.begin())
            val sps = byteArrayOf(0, 0, 0, 1, 0x67, 0x42, 0x01)
            server.feed(sps)
            server.feed(sps)
            assertEquals(0, server.queueSize)
            // A client joining later receives nothing stale.
            socket = Socket()
            socket.connect(InetSocketAddress("127.0.0.1", server.port), 5_000)
            socket.soTimeout = 500
            assertTrue(waitForClient(server))
            val input = DataInputStream(socket.getInputStream())
            try {
                input.readInt()
                fail("expected no units for a client that joined after the feeds")
            } catch (_: SocketTimeoutException) {
                // Nothing stale arrived.
            }
        } finally {
            runCatching { socket?.close() }
            server.stop()
        }
    }

    @Test
    fun `offer drops the oldest chunk when the queue is full`() {
        val server = TvStreamServer(0)
        repeat(TvStreamServer.QUEUE_CAPACITY + 8) { i -> server.offerChunk(byteArrayOf(i.toByte())) }
        assertEquals(TvStreamServer.QUEUE_CAPACITY, server.queueSize)
        // The first 8 offers were dropped; the queue holds the freshest 32.
        repeat(TvStreamServer.QUEUE_CAPACITY) { i ->
            assertArrayEquals(byteArrayOf((i + 8).toByte()), server.pollChunk())
        }
        assertNull(server.pollChunk())
        assertEquals(0, server.queueSize)
    }

    @Test
    fun `queue is cleared when the client disconnects`() {
        val server = TvStreamServer(0)
        server.attach(context)
        var socket: Socket? = null
        try {
            assertNull(server.begin())
            socket = Socket()
            socket.connect(InetSocketAddress("127.0.0.1", server.port), 5_000)
            assertTrue(waitForClient(server))
            val sps = byteArrayOf(0, 0, 0, 1, 0x67, 0x42, 0x01)
            val pps = byteArrayOf(0, 0, 1, 0x68, 0x01)
            val idr = byteArrayOf(0, 0, 0, 1, 0x65, 0x11, 0x22)
            val chunk = sps + pps + idr + byteArrayOf(0, 0, 1, 0x41)
            server.feed(chunk)
            // Abortive close: the server's next write fails at once instead of
            // after the TCP close handshake.
            socket.setSoLinger(true, 0)
            socket.close()
            socket = null
            // Feeding after the disconnect forces the sender to notice the dead
            // socket; its closeClient clears the queue.
            repeat(2) { server.feed(chunk) }
            val deadline = System.currentTimeMillis() + 5_000
            while ((server.queueSize != 0 || server.hasClient()) && System.currentTimeMillis() < deadline) {
                Thread.sleep(10)
            }
            assertEquals(0, server.queueSize)
            assertFalse(server.hasClient())
        } finally {
            runCatching { socket?.close() }
            server.stop()
        }
    }

    @Test
    fun `stop joins the sender promptly with a connected client`() {
        val server = TvStreamServer(0)
        server.attach(context)
        var socket: Socket? = null
        try {
            assertNull(server.begin())
            socket = Socket()
            socket.connect(InetSocketAddress("127.0.0.1", server.port), 5_000)
            assertTrue(waitForClient(server))
            server.feed(byteArrayOf(0, 0, 0, 1, 0x67, 0x42, 0x01))
            val startMs = System.currentTimeMillis()
            server.stop()
            assertTrue("stop took ${System.currentTimeMillis() - startMs}ms", System.currentTimeMillis() - startMs < 5_000)
        } finally {
            runCatching { socket?.close() }
            runCatching { server.stop() }
        }
    }

    private fun waitForClient(server: TvStreamServer, timeoutMs: Long = 5_000): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!server.hasClient() && System.currentTimeMillis() < deadline) Thread.sleep(10)
        return server.hasClient()
    }

    private fun readUnit(input: DataInputStream): ByteArray {
        val length = input.readInt()
        val unit = ByteArray(length)
        input.readFully(unit)
        return unit
    }
}
