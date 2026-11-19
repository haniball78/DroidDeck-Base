package com.droiddeck.launcher.session

import java.util.concurrent.atomic.AtomicBoolean
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UsbTetherTest {
    @Test
    fun `rndis interface derives the tether gateway`() {
        assertEquals("192.168.42.129", UsbTether.gatewayFor(listOf("rndis0" to "192.168.42.5")))
    }

    @Test
    fun `usb interface derives the tether gateway`() {
        assertEquals("192.168.42.129", UsbTether.gatewayFor(listOf("usb0" to "192.168.42.17")))
    }

    @Test
    fun `tether interface wins over another subnet`() {
        assertEquals(
            "192.168.43.129",
            UsbTether.gatewayFor(listOf("wlan0" to "192.168.42.10", "rndis0" to "192.168.43.5")),
        )
    }

    @Test
    fun `forty-two subnet matches without a tether interface`() {
        assertEquals("192.168.42.129", UsbTether.gatewayFor(listOf("wlan0" to "192.168.42.10")))
    }

    @Test
    fun `no match returns null`() {
        assertNull(UsbTether.gatewayFor(listOf("wlan0" to "192.168.1.10", "rmnet0" to "10.0.0.2")))
        assertNull(UsbTether.gatewayFor(emptyList()))
    }

    @Test
    fun `malformed addresses are skipped`() {
        assertNull(UsbTether.gatewayFor(listOf("rndis0" to "not-an-ip")))
        assertEquals("192.168.42.129", UsbTether.gatewayFor(listOf("rndis0" to "not-an-ip", "usb0" to "192.168.42.5")))
    }

    @Test
    fun `link up needs carrier and an address on a tether interface`() {
        assertTrue(UsbTether.linkUp(listOf(UsbTether.TetherInterface("rndis0", "up", listOf("192.168.42.5")))))
        assertFalse(UsbTether.linkUp(listOf(UsbTether.TetherInterface("rndis0", "down", listOf("192.168.42.5")))))
        assertFalse(UsbTether.linkUp(listOf(UsbTether.TetherInterface("rndis0", "up", emptyList()))))
        assertFalse(UsbTether.linkUp(listOf(UsbTether.TetherInterface("wlan0", "up", listOf("192.168.42.5")))))
        assertFalse(UsbTether.linkUp(emptyList()))
    }

    @Test
    fun `link up without operstate counts only the tether subnet`() {
        assertTrue(UsbTether.linkUp(listOf(UsbTether.TetherInterface("rndis0", null, listOf("192.168.42.5")))))
        assertFalse(UsbTether.linkUp(listOf(UsbTether.TetherInterface("rndis0", null, listOf("10.224.186.120")))))
    }

    @Test
    fun `tether address is the forty-two subnet`() {
        assertTrue(UsbTether.isTetherAddress("192.168.42.129"))
        assertTrue(UsbTether.isTetherAddress("192.168.42.5"))
        assertFalse(UsbTether.isTetherAddress("192.168.49.1"))
        assertFalse(UsbTether.isTetherAddress("192.168.1.5"))
        assertFalse(UsbTether.isTetherAddress("not-an-ip"))
        assertFalse(UsbTether.isTetherAddress(""))
    }

    @Test
    fun `wait for usb link returns at once when the link is up`() {
        assertTrue(UsbTether.waitForUsbLink(linkUp = { true }, timeoutMs = 200, pollMs = 10))
    }

    @Test
    fun `wait for usb link returns when the link comes up`() {
        val up = AtomicBoolean(false)
        Thread { runCatching { Thread.sleep(50) }; up.set(true) }.start()
        assertTrue(UsbTether.waitForUsbLink(linkUp = { up.get() }, timeoutMs = 2_000, pollMs = 10))
    }

    @Test
    fun `wait for usb link times out when the link never comes up`() {
        assertFalse(UsbTether.waitForUsbLink(linkUp = { false }, timeoutMs = 100, pollMs = 10))
    }

    @Test
    fun `wait for usb link stops when the wait ends`() {
        assertFalse(UsbTether.waitForUsbLink(linkUp = { false }, keepWaiting = { false }, timeoutMs = 2_000, pollMs = 10))
    }
}
