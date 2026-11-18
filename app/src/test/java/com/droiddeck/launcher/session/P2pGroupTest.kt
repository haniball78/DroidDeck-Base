package com.droiddeck.launcher.session

import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class P2pGroupTest {
    @Test
    fun `group request asks for 5 GHz on API 29 and above`() {
        assertEquals(
            P2pGroup.GroupRequest("DroidDeck-TV", WifiP2pConfig.GROUP_OWNER_BAND_5GHZ, true),
            P2pGroup().groupRequestFor(34),
        )
    }

    @Test
    fun `group request is absent below API 29`() {
        assertNull(P2pGroup().groupRequestFor(28))
    }

    @Test
    fun `begin fails honestly when not attached`() {
        val part = P2pGroup()
        assertEquals("not-attached", part.begin())
        part.stop()
    }

    @Test
    fun `stop is safe without begin`() {
        P2pGroup().stop()
    }

    @Test
    fun `txt record carries the bound stream port`() {
        val record = txtRecord(-1, 28752, 1920 to 1080)
        assertEquals("28752", record["video"])
        assertEquals("1920", record["w"])
        assertEquals("1080", record["h"])
        assertEquals("controller", record["role"])
    }

    @Test
    fun `txt record omits the video port when there is no stream server`() {
        val record = txtRecord(28753, -1, 1920 to 1080)
        assertNull(record["video"])
        assertEquals("28753", record["pad"])
    }

    @Test
    fun `p2p failure reason names the code`() {
        assertEquals("busy", p2pFailureReason(WifiP2pManager.BUSY))
        assertEquals("p2p-unsupported", p2pFailureReason(WifiP2pManager.P2P_UNSUPPORTED))
        assertEquals("error", p2pFailureReason(WifiP2pManager.ERROR))
        assertEquals("no-service-requests", p2pFailureReason(WifiP2pManager.NO_SERVICE_REQUESTS))
        assertEquals("reason-99", p2pFailureReason(99))
    }
}
