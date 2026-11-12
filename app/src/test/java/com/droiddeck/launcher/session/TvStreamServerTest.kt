package com.droiddeck.launcher.session

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

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
        val sps = byteArrayOf(0, 0, 0, 1, 0x67, 0x42, 0x00)
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
        val first = scanner.feed(byteArrayOf(0, 0, 0, 1, 0x67, 0x42, 0x00, 0, 0, 1, 0x68))
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
    fun `stream begin fails honestly without a session context`() {
        val server = TvStreamServer(File(context.filesDir, "venc/surface.h264"))
        assertNotNull(server.begin())
        server.stop()
    }
}
