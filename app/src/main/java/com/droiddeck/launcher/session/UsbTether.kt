package com.droiddeck.launcher.session

import java.net.Inet4Address
import java.net.NetworkInterface

object UsbTether {
    const val USB_STREAM_PORT = 28752
    const val USB_PAD_PORT = 28753

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
}
