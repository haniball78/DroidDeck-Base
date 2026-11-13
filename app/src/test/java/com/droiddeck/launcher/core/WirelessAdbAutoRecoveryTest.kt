package com.droiddeck.launcher.core

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WirelessAdbAutoRecoveryTest {

    @Test fun recoveryFiresOncePerParkedPort() {
        assertTrue(WirelessAdbFix.shouldAutoRecoverIdentity(42029, emptySet(), true, 1))
        assertFalse(WirelessAdbFix.shouldAutoRecoverIdentity(42029, setOf(42029), true, 1))
        assertFalse(WirelessAdbFix.shouldAutoRecoverIdentity(42029, setOf(42029), true, 14))
        assertTrue(WirelessAdbFix.shouldAutoRecoverIdentity(43000, setOf(42029), true, 1))
    }

    @Test fun recoveryNeedsRevokedIdentity() {
        assertFalse(WirelessAdbFix.shouldAutoRecoverIdentity(42029, emptySet(), false, 1))
        assertFalse(WirelessAdbFix.shouldAutoRecoverIdentity(42029, emptySet(), false, 14))
        assertFalse(WirelessAdbFix.shouldAutoRecoverIdentity(42029, emptySet(), true, 0))
        assertTrue(WirelessAdbFix.shouldAutoRecoverIdentity(42029, emptySet(), true, 14))
    }

    @Test fun recoveryNeedsParkedPort() {
        assertFalse(WirelessAdbFix.shouldAutoRecoverIdentity(null, emptySet(), true, 1))
    }
}
