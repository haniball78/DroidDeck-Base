package com.droiddeck.launcher.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
}
