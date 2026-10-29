package com.droiddeck.launcher.core

data class ConnectCandidate(val host: String, val port: Int)

fun orderConnectCandidates(
    savedHost: String?,
    savedPort: Int?,
    discoveredPort: Int?,
    lanHosts: List<String>,
): List<ConnectCandidate> {
    val hosts = ArrayList<String>()
    savedHost?.takeIf { it.isNotBlank() }?.let { hosts.add(it) }
    if (WirelessAdbFix.LOOPBACK !in hosts) hosts.add(WirelessAdbFix.LOOPBACK)
    lanHosts.filter { it.isNotBlank() && it !in hosts }.forEach { hosts.add(it) }
    val ports = listOfNotNull(discoveredPort, savedPort)
        .filter { it in 1..65535 }
        .distinct()
    val candidates = ArrayList<ConnectCandidate>()
    for (host in hosts) {
        for (port in ports) {
            candidates.add(ConnectCandidate(host, port))
            if (candidates.size >= 6) return candidates
        }
    }
    return candidates
}
