package com.droiddeck.launcher.session

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

@RunWith(RobolectricTestRunner::class)
class TvSurfaceEncodeTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun `surface encode test is off by default`() {
        context.getSharedPreferences("session", Context.MODE_PRIVATE)
            .edit().remove("tvSurfaceEncodeTest").apply()
        assertFalse(SessionPrefs.tvSurfaceEncodeTest(context))
    }

    @Test
    fun `surface encode toggle round-trips`() {
        SessionPrefs.setTvSurfaceEncodeTest(context, true)
        assertTrue(SessionPrefs.tvSurfaceEncodeTest(context))
        SessionPrefs.setTvSurfaceEncodeTest(context, false)
        assertFalse(SessionPrefs.tvSurfaceEncodeTest(context))
    }

    @Test
    fun `surface summary formats full stats`() {
        assertEquals(
            "frames=312 bytes=1048576 keyframes=4 dropped=9 firstFrameMs=842 elapsedMs=62000",
            surfaceSummary(longArrayOf(312, 1048576, 4, 9, 842, 62000)),
        )
    }

    @Test
    fun `surface summary reads a zero run as no frames`() {
        assertEquals(
            "frames=0 bytes=0 keyframes=0 dropped=0 firstFrameMs=-1 elapsedMs=0",
            surfaceSummary(longArrayOf(0, 0, 0, 0, -1, 0)),
        )
    }

    @Test
    fun `surface summary tolerates a short array`() {
        assertEquals(
            "frames=3 bytes=99 keyframes=0 dropped=0 firstFrameMs=-1 elapsedMs=0",
            surfaceSummary(longArrayOf(3, 99)),
        )
    }

    @Test
    fun `encode begin fails honestly without the native library`() {
        val part = TvSurfaceEncode(File(context.filesDir, "venc/surface.h264"))
        assertNotNull(part.begin())
        part.stop()
    }
}
