package com.droiddeck.launcher.session

import java.io.File
import java.net.Inet4Address
import java.net.NetworkInterface

object UsbTether {
    const val USB_STREAM_PORT = 28752
    const val USB_PAD_PORT = 28753
    const val USB_VIDEO_UDP_PORT = 28754
    const val USB_CONTROL_PORT = 28755

    /** Bounded wait for the USB-tethered link to come up. */
    const val USB_LINK_WAIT_MS = 15_000L
    const val USB_LINK_POLL_MS = 500L

    // Android's USB tethering gateway: the game phone is always .129 on 192.168.42.0/24.
    const val DEFAULT_GATEWAY = "192.168.42.129"

    /**
     * Finds the game phone's address on a USB-tethered link: the tether
     * interface first (rndis/usb), then any interface on the 192.168.42.0/24
     * subnet. Returns the same subnet's .129 address, null when nothing matches.
     */
    fun gatewayFor(addresses: List<Pair<String, String>>): String? {
        val valid = addresses.mapNotNull { (name, ip) ->
            val octets = ip.split('.')
            if (octets.size != 4 || octets.any { it.toIntOrNull() == null }) null else name to octets
        }
        for ((name, octets) in valid) {
            if (name.startsWith("rndis") || name.startsWith("usb")) {
                return "${octets[0]}.${octets[1]}.${octets[2]}.129"
            }
        }
        for ((_, octets) in valid) {
            if (octets[0] == "192" && octets[1] == "168" && octets[2] == "42") {
                return "192.168.42.129"
            }
        }
        return null
    }

    fun systemGateway(): String? {
        val pairs = ArrayList<Pair<String, String>>()
        runCatching {
            NetworkInterface.getNetworkInterfaces()?.toList().orEmpty().forEach { ni ->
                ni.inetAddresses?.toList().orEmpty().forEach { addr ->
                    if (addr is Inet4Address && !addr.isLoopbackAddress) {
                        pairs += ni.name to (addr.hostAddress.orEmpty())
                    }
                }
            }
        }
        return gatewayFor(pairs)
    }

    /** A tether interface as the app sees it: its kernel name, its operstate, and its IPv4 addresses. */
    data class TetherInterface(val name: String, val operstate: String?, val ipv4: List<String>)

    /** True when the host is on Android's USB-tethering subnet (192.168.42.0/24). */
    fun isTetherAddress(host: String): Boolean {
        val octets = host.split('.')
        return octets.size == 4 && octets[0] == "192" && octets[1] == "168" && octets[2] == "42"
    }

    /**
     * True when a USB-tethered link is up: a rndis/usb interface with carrier
     * (operstate "up") and an IPv4 address. When operstate cannot be read, an
     * address on the tethering subnet counts: only the tethering service
     * assigns 192.168.42.0/24. NetworkInterface.isUp alone does not prove a
     * link: it stays set while the cable is unplugged (NO-CARRIER).
     */
    fun linkUp(interfaces: List<TetherInterface>): Boolean {
        return interfaces.any { iface ->
            (iface.name.startsWith("rndis") || iface.name.startsWith("usb")) &&
                iface.ipv4.isNotEmpty() &&
                (iface.operstate == "up" || (iface.operstate == null && iface.ipv4.any { isTetherAddress(it) }))
        }
    }

    /** The live tether interfaces, with operstate read from sysfs; null operstate when it cannot be read. */
    fun readTetherInterfaces(): List<TetherInterface> {
        val out = ArrayList<TetherInterface>()
        runCatching {
            NetworkInterface.getNetworkInterfaces()?.toList().orEmpty().forEach { ni ->
                val name = ni.name
                if (!name.startsWith("rndis") && !name.startsWith("usb")) return@forEach
                val ips = ni.inetAddresses?.toList().orEmpty()
                    .filterIsInstance<Inet4Address>()
                    .filter { !it.isLoopbackAddress }
                    .mapNotNull { it.hostAddress }
                val operstate = runCatching { File("/sys/class/net/$name/operstate").readText().trim() }.getOrNull()
                out += TetherInterface(name, operstate, ips)
            }
        }
        return out
    }

    /** True when the USB-tethered link is up right now. */
    fun linkUpNow(): Boolean = linkUp(readTetherInterfaces())

    /** Polls for the USB-tethered link until it is up, the wait ends, or the timeout passes. */
    fun waitForUsbLink(
        linkUp: () -> Boolean = { linkUpNow() },
        keepWaiting: () -> Boolean = { true },
        timeoutMs: Long = USB_LINK_WAIT_MS,
        pollMs: Long = USB_LINK_POLL_MS,
    ): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (keepWaiting()) {
            if (linkUp()) return true
            if (System.currentTimeMillis() >= deadline) return false
            runCatching { Thread.sleep(pollMs) }
        }
        return false
    }
}
