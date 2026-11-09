package com.droiddeck.launcher.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConnectionCardStateTest {

    @Test fun activeDaemonShowsActive() {
        assertEquals(
            ConnectionCardState.ACTIVE,
            ConnectionCardStateResolver.resolve(
                daemonActive = true, authRejected = false,
                devOptionsOn = true, wirelessAdbOn = true, paired = true,
                pairingRequired = true,
            ),
        )
    }

    @Test fun activeWithWirelessOffShowsDegradedWireless() {
        // A running daemon with wireless debugging off is not "ready":
        // sessions run, but restart or re-pairing is blocked.
        assertEquals(
            ConnectionCardState.ACTIVE_WIRELESS_OFF,
            ConnectionCardStateResolver.resolve(
                daemonActive = true, authRejected = false,
                devOptionsOn = true, wirelessAdbOn = false, paired = true,
                pairingRequired = true,
            ),
        )
    }

    @Test fun activeWithDevOffNamesDevOptionsAsRootCause() {
        // Turning developer options off turns wireless debugging off
        // with it, so the card names dev options, not wireless.
        assertEquals(
            ConnectionCardState.ACTIVE_DEV_OFF,
            ConnectionCardStateResolver.resolve(
                daemonActive = true, authRejected = false,
                devOptionsOn = false, wirelessAdbOn = false, paired = true,
                pairingRequired = true,
            ),
        )
    }

    @Test fun authRejectedBeatsDegraded() {
        assertEquals(
            ConnectionCardState.AUTH_REJECTED,
            ConnectionCardStateResolver.resolve(
                daemonActive = true, authRejected = true,
                devOptionsOn = true, wirelessAdbOn = false, paired = true,
                pairingRequired = true,
            ),
        )
    }

    @Test fun degradedStatesAreDaemonRunningButNotReady() {
        assertTrue(
            ConnectionCardStateResolver.daemonRunning(ConnectionCardState.ACTIVE_WIRELESS_OFF),
        )
        assertTrue(
            ConnectionCardStateResolver.daemonRunning(ConnectionCardState.ACTIVE_DEV_OFF),
        )
        assertTrue(
            ConnectionCardStateResolver.isDegraded(ConnectionCardState.ACTIVE_WIRELESS_OFF),
        )
        assertTrue(
            ConnectionCardStateResolver.isDegraded(ConnectionCardState.ACTIVE_DEV_OFF),
        )
        assertFalse(
            ConnectionCardStateResolver.isDegraded(ConnectionCardState.ACTIVE),
        )
        assertFalse(
            ConnectionCardStateResolver.daemonRunning(ConnectionCardState.READY_TO_START),
        )
    }

    @Test fun authRejectedBeatsApparentActivity() {
        // PING != AUTH: a daemon that answers but rejects commands is not usable.
        assertEquals(
            ConnectionCardState.AUTH_REJECTED,
            ConnectionCardStateResolver.resolve(
                daemonActive = true, authRejected = true,
                devOptionsOn = true, wirelessAdbOn = true, paired = true,
                pairingRequired = true,
            ),
        )
    }

    @Test fun authRejectedWithoutDaemonFallsThroughToSetupSteps() {
        // A stale auth flag with no live daemon must not mask the real next step.
        assertEquals(
            ConnectionCardState.NEED_DEV_OPTIONS,
            ConnectionCardStateResolver.resolve(
                daemonActive = false, authRejected = true,
                devOptionsOn = false, wirelessAdbOn = false, paired = false,
                pairingRequired = true,
            ),
        )
    }

    @Test fun devOptionsOffIsFirstStepRegardlessOfOthers() {
        assertEquals(
            ConnectionCardState.NEED_DEV_OPTIONS,
            ConnectionCardStateResolver.resolve(
                daemonActive = false, authRejected = false,
                devOptionsOn = false, wirelessAdbOn = true, paired = true,
                pairingRequired = true,
            ),
        )
    }

    @Test fun wirelessOffIsSecondStep() {
        assertEquals(
            ConnectionCardState.NEED_WIRELESS_ADB,
            ConnectionCardStateResolver.resolve(
                daemonActive = false, authRejected = false,
                devOptionsOn = true, wirelessAdbOn = false, paired = true,
                pairingRequired = true,
            ),
        )
    }

    @Test fun unpairedIsThirdStep() {
        assertEquals(
            ConnectionCardState.NEED_PAIRING,
            ConnectionCardStateResolver.resolve(
                daemonActive = false, authRejected = false,
                devOptionsOn = true, wirelessAdbOn = true, paired = false,
                pairingRequired = true,
            ),
        )
    }

    @Test fun pairedAndReadyShowsReady() {
        assertEquals(
            ConnectionCardState.READY_TO_START,
            ConnectionCardStateResolver.resolve(
                daemonActive = false, authRejected = false,
                devOptionsOn = true, wirelessAdbOn = true, paired = true,
                pairingRequired = true,
            ),
        )
    }

    @Test fun primaryActionMatchesEveryState() {
        assertEquals(
            ConnectionCardAction.OPEN_ABOUT_PHONE,
            ConnectionCardStateResolver.primaryAction(ConnectionCardState.NEED_DEV_OPTIONS),
        )
        assertEquals(
            ConnectionCardAction.OPEN_DEV_OPTIONS,
            ConnectionCardStateResolver.primaryAction(ConnectionCardState.NEED_WIRELESS_ADB),
        )
        assertEquals(
            ConnectionCardAction.PAIR_UNIFIED,
            ConnectionCardStateResolver.primaryAction(ConnectionCardState.NEED_PAIRING),
        )
        assertEquals(
            ConnectionCardAction.ONE_TAP_START,
            ConnectionCardStateResolver.primaryAction(ConnectionCardState.READY_TO_START),
        )
        assertEquals(
            ConnectionCardAction.NONE,
            ConnectionCardStateResolver.primaryAction(ConnectionCardState.ACTIVE),
        )
        assertEquals(
            ConnectionCardAction.OPEN_DEV_OPTIONS,
            ConnectionCardStateResolver.primaryAction(ConnectionCardState.ACTIVE_WIRELESS_OFF),
        )
        assertEquals(
            ConnectionCardAction.OPEN_ABOUT_PHONE,
            ConnectionCardStateResolver.primaryAction(ConnectionCardState.ACTIVE_DEV_OFF),
        )
        assertEquals(
            ConnectionCardAction.OPEN_DEV_OPTIONS,
            ConnectionCardStateResolver.primaryAction(ConnectionCardState.AUTH_REJECTED),
        )
    }

    @Test fun activeWithStalePairingShowsDegraded() {
        // A running daemon whose own pairing record is gone is not
        // "ready": it answers while fresh connections fail.
        assertEquals(
            ConnectionCardState.ACTIVE_PAIRING_STALE,
            ConnectionCardStateResolver.resolve(
                daemonActive = true, authRejected = false,
                devOptionsOn = true, wirelessAdbOn = true, paired = false,
                pairingRequired = true,
            ),
        )
    }

    @Test fun stalePairingDoesNotApplyWhenPairingIsNotRequired() {
        // A backend that does not use the app's own pairing (Shizuku)
        // is not stale just because the app has no pairing record.
        assertEquals(
            ConnectionCardState.ACTIVE,
            ConnectionCardStateResolver.resolve(
                daemonActive = true, authRejected = false,
                devOptionsOn = true, wirelessAdbOn = true, paired = false,
                pairingRequired = false,
            ),
        )
    }

    @Test fun stalePairingIsDaemonRunningDegradedAndRepairsByPairing() {
        assertTrue(
            ConnectionCardStateResolver.daemonRunning(ConnectionCardState.ACTIVE_PAIRING_STALE),
        )
        assertTrue(
            ConnectionCardStateResolver.isDegraded(ConnectionCardState.ACTIVE_PAIRING_STALE),
        )
        assertEquals(
            ConnectionCardAction.PAIR_UNIFIED,
            ConnectionCardStateResolver.primaryAction(ConnectionCardState.ACTIVE_PAIRING_STALE),
        )
        assertFalse(
            ConnectionCardStateResolver.showFailureGuidance(ConnectionCardState.ACTIVE_PAIRING_STALE, true),
        )
    }

    @Test fun failureGuidanceOnlyWhereAnAttemptIsPossible() {
        assertFalse(
            ConnectionCardStateResolver.showFailureGuidance(ConnectionCardState.NEED_DEV_OPTIONS, true),
        )
        assertFalse(
            ConnectionCardStateResolver.showFailureGuidance(ConnectionCardState.NEED_WIRELESS_ADB, true),
        )
        assertTrue(
            ConnectionCardStateResolver.showFailureGuidance(ConnectionCardState.NEED_PAIRING, true),
        )
        assertTrue(
            ConnectionCardStateResolver.showFailureGuidance(ConnectionCardState.READY_TO_START, true),
        )
        assertTrue(
            ConnectionCardStateResolver.showFailureGuidance(ConnectionCardState.AUTH_REJECTED, true),
        )
        assertFalse(
            ConnectionCardStateResolver.showFailureGuidance(ConnectionCardState.ACTIVE, true),
        )
        assertFalse(
            ConnectionCardStateResolver.showFailureGuidance(ConnectionCardState.ACTIVE_WIRELESS_OFF, true),
        )
        assertFalse(
            ConnectionCardStateResolver.showFailureGuidance(ConnectionCardState.ACTIVE_DEV_OFF, true),
        )
        assertFalse(
            ConnectionCardStateResolver.showFailureGuidance(ConnectionCardState.READY_TO_START, false),
        )
    }
}
