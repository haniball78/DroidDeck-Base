package com.droiddeck.launcher.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ConnectionCandidatesTest {

    @Test fun savedHostTriedBeforeLoopbackAndDiscoveredPortBeforeSavedPort() {
        assertEquals(
            listOf(
                ConnectCandidate("192.168.1.64", 35529),
                ConnectCandidate("192.168.1.64", 35693),
                ConnectCandidate("127.0.0.1", 35529),
                ConnectCandidate("127.0.0.1", 35693),
            ),
            orderConnectCandidates("192.168.1.64", 35693, 35529, emptyList()),
        )
    }

    @Test fun loopbackSavedHostIsNotDuplicatedAndLanHostsFollow() {
        assertEquals(
            listOf(
                ConnectCandidate("127.0.0.1", 35529),
                ConnectCandidate("127.0.0.1", 35693),
                ConnectCandidate("192.168.1.70", 35529),
                ConnectCandidate("192.168.1.70", 35693),
            ),
            orderConnectCandidates("127.0.0.1", 35693, 35529, listOf("127.0.0.1", "192.168.1.70")),
        )
    }

    @Test fun missingPortsFallBackToWhateverIsKnown() {
        assertEquals(
            listOf(ConnectCandidate("127.0.0.1", 35529)),
            orderConnectCandidates(null, null, 35529, emptyList()),
        )
        assertEquals(
            listOf(
                ConnectCandidate("192.168.1.64", 35693),
                ConnectCandidate("127.0.0.1", 35693),
            ),
            orderConnectCandidates("192.168.1.64", 35693, null, emptyList()),
        )
        assertTrue(orderConnectCandidates(null, null, null, emptyList()).isEmpty())
    }

    @Test fun duplicatePortsAndInvalidValuesAreFilteredAndTheListIsCapped() {
        val candidates = orderConnectCandidates(
            "192.168.1.64", 35693, 35529,
            listOf("192.168.1.70", "192.168.1.71", "192.168.1.72"),
        )
        assertEquals(
            listOf(
                ConnectCandidate("192.168.1.64", 35529),
                ConnectCandidate("192.168.1.64", 35693),
                ConnectCandidate("127.0.0.1", 35529),
                ConnectCandidate("127.0.0.1", 35693),
                ConnectCandidate("192.168.1.70", 35529),
                ConnectCandidate("192.168.1.70", 35693),
            ),
            candidates,
        )
        assertEquals(
            listOf(ConnectCandidate("127.0.0.1", 35529)),
            orderConnectCandidates("  ", 0, 35529, listOf("", "127.0.0.1")),
        )
    }
}
