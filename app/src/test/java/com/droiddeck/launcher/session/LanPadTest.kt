package com.droiddeck.launcher.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LanPadTest {
    @Test
    fun `home lan address wins over p2p and tether`() {
        assertEquals("192.168.1.122", pickLanIp(listOf("192.168.49.1", "192.168.42.5", "192.168.1.122")))
    }

    @Test
    fun `p2p and tether addresses are excluded`() {
        assertNull(pickLanIp(listOf("192.168.49.1", "192.168.42.5", "127.0.0.1")))
    }

    @Test
    fun `ten range is a fallback`() {
        assertEquals("10.0.0.5", pickLanIp(listOf("10.0.0.5")))
    }

    @Test
    fun `malformed addresses are ignored`() {
        assertNull(pickLanIp(listOf("not-an-ip", "192.168.1")))
    }

    @Test
    fun `lan transport prefers the lan endpoint`() {
        val endpoints = listOf("192.168.49.1" to 28753, "192.168.1.122" to 28753)
        assertEquals("192.168.1.122", preferEndpoint(endpoints, PadTransport.LAN)?.first)
    }

    @Test
    fun `p2p transport prefers the group address`() {
        val endpoints = listOf("192.168.1.122" to 28753, "192.168.49.1" to 28753)
        assertEquals("192.168.49.1", preferEndpoint(endpoints, PadTransport.P2P)?.first)
    }

    @Test
    fun `usb transport prefers the tether address`() {
        val endpoints = listOf("192.168.1.122" to 28753, "192.168.42.129" to 28753)
        assertEquals("192.168.42.129", preferEndpoint(endpoints, PadTransport.USB)?.first)
    }

    @Test
    fun `preference falls back to the first endpoint`() {
        val endpoints = listOf("192.168.49.1" to 28753)
        assertEquals("192.168.49.1", preferEndpoint(endpoints, PadTransport.LAN)?.first)
    }

    @Test
    fun `empty endpoints prefer nothing`() {
        assertNull(preferEndpoint(emptyList(), PadTransport.LAN))
    }

    @Test
    fun `p2p subnet detection`() {
        assertTrue(isP2pAddress("192.168.49.1"))
        assertFalse(isP2pAddress("192.168.1.122"))
        assertFalse(isP2pAddress("192.168.4.9"))
    }

    @Test
    fun `lan detection excludes p2p and tether`() {
        assertTrue(isLanAddress("192.168.1.122"))
        assertFalse(isLanAddress("192.168.49.1"))
        assertFalse(isLanAddress("192.168.42.129"))
    }
}
