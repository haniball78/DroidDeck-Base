package com.droiddeck.launcher.session

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

@RunWith(RobolectricTestRunner::class)
class TvStreamLoopbackTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun `loopback test is off by default`() {
        context.getSharedPreferences("session", Context.MODE_PRIVATE)
            .edit().remove("tvStreamLoopbackTest").apply()
        assertFalse(SessionPrefs.tvStreamLoopbackTest(context))
    }

    @Test
    fun `loopback toggle round-trips`() {
        SessionPrefs.setTvStreamLoopbackTest(context, true)
        assertTrue(SessionPrefs.tvStreamLoopbackTest(context))
        SessionPrefs.setTvStreamLoopbackTest(context, false)
        assertFalse(SessionPrefs.tvStreamLoopbackTest(context))
    }

    @Test
    fun `tap summary formats full stats`() {
        assertEquals(
            "frames=12 bytes=304128 keyframes=1 dropped=0 firstFrameMs=1234 elapsedMs=5000",
            tapSummary(longArrayOf(12, 304128, 1, 0, 1234, 5000)),
        )
    }

    @Test
    fun `tap summary reads a zero run as no frames`() {
        assertEquals(
            "frames=0 bytes=0 keyframes=0 dropped=0 firstFrameMs=-1 elapsedMs=0",
            tapSummary(longArrayOf(0, 0, 0, 0, -1, 0)),
        )
    }

    @Test
    fun `tap summary tolerates a short array`() {
        assertEquals(
            "frames=3 bytes=99 keyframes=0 dropped=0 firstFrameMs=-1 elapsedMs=0",
            tapSummary(longArrayOf(3, 99)),
        )
    }

    @Test
    fun `native tap calls are guarded without the native library`() {
        val bridge = VideoEncodeBridge(File(context.filesDir, "venc/venc.sock"))
        assertFalse(bridge.startTap(File(context.filesDir, "venc/loopback.h264").absolutePath))
        assertEquals(0L, bridge.tapStats().sum())
        bridge.stop()
    }
}
