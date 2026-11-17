package com.droiddeck.launcher.session

import android.net.wifi.p2p.WifiP2pConfig
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
}
