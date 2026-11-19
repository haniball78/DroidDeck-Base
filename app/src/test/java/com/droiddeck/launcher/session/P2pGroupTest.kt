package com.droiddeck.launcher.session

import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class P2pGroupTest {
    @Test
    fun `group request asks for a valid DIRECT name with passphrase, auto band, no persistence`() {
        assertEquals(
            P2pGroup.GroupRequest("DIRECT-TV", WifiP2pConfig.GROUP_OWNER_BAND_AUTO, false, "DroidDeck1"),
            P2pGroup().groupRequestFor(34),
        )
    }

    @Test
    fun `group request satisfies the name and passphrase rules`() {
        val request = P2pGroup().groupRequestFor(34)!!
        assertTrue(isValidGroupName(request.networkName))
        assertTrue(isValidPassphrase(request.passphrase))
    }

    @Test
    fun `group name must start with DIRECT plus two characters`() {
        assertTrue(isValidGroupName("DIRECT-TV"))
        assertTrue(isValidGroupName("DIRECT-5m-bibars"))
        assertFalse(isValidGroupName("DroidDeck-TV"))
        assertFalse(isValidGroupName("DIRECT-"))
        assertFalse(isValidGroupName("DIRECT-X"))
        assertFalse(isValidGroupName("DIRECT-TV-012345678901234567890123"))
    }

    @Test
    fun `passphrase must be 8 to 63 ASCII characters`() {
        assertTrue(isValidPassphrase("DroidDeck1"))
        assertFalse(isValidPassphrase("short"))
        assertFalse(isValidPassphrase(""))
        assertFalse(isValidPassphrase("a".repeat(64)))
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
    fun `txt record carries the pad port`() {
        val record = txtRecord(28753)
        assertEquals("28753", record["pad"])
        assertEquals("controller", record["role"])
        assertNull(record["video"])
    }

    @Test
    fun `txt record omits the pad port when there is no pad server`() {
        val record = txtRecord(-1)
        assertNull(record["pad"])
        assertEquals("controller", record["role"])
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
