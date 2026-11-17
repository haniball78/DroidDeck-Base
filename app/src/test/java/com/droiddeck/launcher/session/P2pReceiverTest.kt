package com.droiddeck.launcher.session

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class P2pReceiverTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun `video size parses from the TXT record`() {
        assertEquals(1920 to 1080, parseVideoSize(mapOf("role" to "controller", "w" to "1920", "h" to "1080")))
    }

    @Test
    fun `video size is null when a dimension is missing`() {
        assertNull(parseVideoSize(mapOf("role" to "controller", "w" to "1920")))
        assertNull(parseVideoSize(mapOf("role" to "controller")))
    }

    @Test
    fun `video size is null when a dimension is not a number`() {
        assertNull(parseVideoSize(mapOf("w" to "wide", "h" to "1080")))
    }

    @Test
    fun `video size is null when a dimension is not positive`() {
        assertNull(parseVideoSize(mapOf("w" to "0", "h" to "1080")))
        assertNull(parseVideoSize(mapOf("w" to "-1920", "h" to "1080")))
    }

    @Test
    fun `stop is safe without begin`() {
        P2pReceiver(context).stop()
    }
}
