package com.droiddeck.launcher.core

/**
 * Pure decision logic for the Overview connection card.
 *
 * Deliberately free of any Android API: every input is a plain value, so
 * every input combination is unit-testable, and any wrong card display can
 * be traced back to exactly one input or one branch here. The composable
 * only renders what this resolver decides and only calls the existing
 * WirelessAdbFix / PrivilegedBridge actions.
 */
enum class ConnectionCardState {
    /** Developer options are off — first setup step. */
    NEED_DEV_OPTIONS,

    /** Developer options on, wireless debugging off — second setup step. */
    NEED_WIRELESS_ADB,

    /** Wireless debugging on, device not paired yet — third setup step. */
    NEED_PAIRING,

    /** Paired, wireless on, daemon not active — one tap starts it. */
    READY_TO_START,

    /** Daemon (or Shizuku) active and auth not rejected. */
    ACTIVE,

    /** Daemon answers PING but rejects commands — token mismatch (PING != AUTH). */
    AUTH_REJECTED,
}

enum class ConnectionCardAction {
    OPEN_ABOUT_PHONE,
    OPEN_DEV_OPTIONS,
    OPEN_PAIRING_DIALOG,
    ONE_TAP_START,
    NONE,
}

object ConnectionCardStateResolver {

    /**
     * Priority order mirrors the engine page's Smart Setup Assistant steps:
     * auth-rejection beats apparent activity (a PING-active daemon with a
     * rejected token is NOT usable), activity beats setup steps, and the
     * setup steps follow their real dependency order (dev -> wireless ->
     * pairing), so the card never asks for a step whose prerequisite is
     * missing.
     */
    fun resolve(
        daemonActive: Boolean,
        authRejected: Boolean,
        devOptionsOn: Boolean,
        wirelessAdbOn: Boolean,
        paired: Boolean,
    ): ConnectionCardState {
        if (daemonActive && authRejected) return ConnectionCardState.AUTH_REJECTED
        if (daemonActive) return ConnectionCardState.ACTIVE
        if (!devOptionsOn) return ConnectionCardState.NEED_DEV_OPTIONS
        if (!wirelessAdbOn) return ConnectionCardState.NEED_WIRELESS_ADB
        if (!paired) return ConnectionCardState.NEED_PAIRING
        return ConnectionCardState.READY_TO_START
    }

    fun primaryAction(state: ConnectionCardState): ConnectionCardAction = when (state) {
        ConnectionCardState.NEED_DEV_OPTIONS -> ConnectionCardAction.OPEN_ABOUT_PHONE
        ConnectionCardState.NEED_WIRELESS_ADB -> ConnectionCardAction.OPEN_DEV_OPTIONS
        ConnectionCardState.NEED_PAIRING -> ConnectionCardAction.OPEN_PAIRING_DIALOG
        ConnectionCardState.READY_TO_START -> ConnectionCardAction.ONE_TAP_START
        ConnectionCardState.ACTIVE -> ConnectionCardAction.NONE
        ConnectionCardState.AUTH_REJECTED -> ConnectionCardAction.OPEN_PAIRING_DIALOG
    }

    /**
     * The failure-guidance box is shown whenever a daemon failure was
     * recorded and the card is not in the ACTIVE state. An ACTIVE card
     * never shows stale failure guidance.
     */
    fun showFailureGuidance(state: ConnectionCardState, hasRecordedFailure: Boolean): Boolean =
        hasRecordedFailure && state != ConnectionCardState.ACTIVE
}
