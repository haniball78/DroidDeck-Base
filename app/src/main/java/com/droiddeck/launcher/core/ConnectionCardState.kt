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

    /**
     * Daemon active and auth OK, but wireless debugging is off — sessions
     * run, but restart or re-pairing is blocked until it is back on.
     */
    ACTIVE_WIRELESS_OFF,

    /**
     * Daemon active and auth OK, but developer options are off, which
     * turns wireless debugging off with them — sessions run, but
     * restart or re-pairing is blocked until both are back on.
     */
    ACTIVE_DEV_OFF,

    /** Daemon answers PING but rejects commands — token mismatch (PING != AUTH). */
    AUTH_REJECTED,
}

enum class ConnectionCardAction {
    OPEN_ABOUT_PHONE,
    OPEN_DEV_OPTIONS,
    PAIR_UNIFIED,
    ONE_TAP_START,
    NONE,
}

enum class FailureBoxMode {
    /** No failure guidance at all. */
    HIDDEN,

    /**
     * An AUTH failure the app is still healing itself (READY state, restart
     * budget left): show a neutral "finishing setup" line, not a red box.
     */
    FINISHING,

    /** A failure the user must act on: the red box with its action. */
    FAILURE,
}

object ConnectionCardStateResolver {

    /**
     * Priority order mirrors the engine page's Smart Setup Assistant steps:
     * auth-rejection beats apparent activity (a PING-active daemon with a
     * rejected token is NOT usable), a running daemon with a missing
     * restart prerequisite is degraded rather than ready (dev options off
     * names the root cause, since it turns wireless debugging off with
     * it), activity beats setup steps, and the setup steps follow their
     * real dependency order (dev -> wireless -> pairing), so the card
     * never asks for a step whose prerequisite is missing.
     */
    fun resolve(
        daemonActive: Boolean,
        authRejected: Boolean,
        devOptionsOn: Boolean,
        wirelessAdbOn: Boolean,
        paired: Boolean,
    ): ConnectionCardState {
        if (daemonActive && authRejected) return ConnectionCardState.AUTH_REJECTED
        if (daemonActive && !devOptionsOn) return ConnectionCardState.ACTIVE_DEV_OFF
        if (daemonActive && !wirelessAdbOn) return ConnectionCardState.ACTIVE_WIRELESS_OFF
        if (daemonActive) return ConnectionCardState.ACTIVE
        if (!devOptionsOn) return ConnectionCardState.NEED_DEV_OPTIONS
        if (!wirelessAdbOn) return ConnectionCardState.NEED_WIRELESS_ADB
        if (!paired) return ConnectionCardState.NEED_PAIRING
        return ConnectionCardState.READY_TO_START
    }

    fun primaryAction(state: ConnectionCardState): ConnectionCardAction = when (state) {
        ConnectionCardState.NEED_DEV_OPTIONS -> ConnectionCardAction.OPEN_ABOUT_PHONE
        ConnectionCardState.NEED_WIRELESS_ADB -> ConnectionCardAction.OPEN_DEV_OPTIONS
        ConnectionCardState.NEED_PAIRING -> ConnectionCardAction.PAIR_UNIFIED
        ConnectionCardState.READY_TO_START -> ConnectionCardAction.ONE_TAP_START
        ConnectionCardState.ACTIVE -> ConnectionCardAction.NONE
        ConnectionCardState.ACTIVE_WIRELESS_OFF -> ConnectionCardAction.OPEN_DEV_OPTIONS
        ConnectionCardState.ACTIVE_DEV_OFF -> ConnectionCardAction.OPEN_ABOUT_PHONE
        // AUTH is not fixed by re-pairing: the key is rejected on this
        // port, so the primary action is the guided reset (open Developer
        // options: revoke authorizations, toggle wireless), and the card
        // retries on its own when the port changes.
        ConnectionCardState.AUTH_REJECTED -> ConnectionCardAction.OPEN_DEV_OPTIONS
    }

    /** True for ACTIVE and its degraded variants — the daemon is running. */
    fun daemonRunning(state: ConnectionCardState): Boolean =
        state == ConnectionCardState.ACTIVE ||
            state == ConnectionCardState.ACTIVE_WIRELESS_OFF ||
            state == ConnectionCardState.ACTIVE_DEV_OFF

    /** True only for the degraded variants — running, but restart or re-pairing is blocked. */
    fun isDegraded(state: ConnectionCardState): Boolean =
        state == ConnectionCardState.ACTIVE_WIRELESS_OFF ||
            state == ConnectionCardState.ACTIVE_DEV_OFF

    /**
     * The failure-guidance box is shown only where a start or pair attempt
     * actually happened or is the current step: NEED_PAIRING, READY_TO_START
     * and AUTH_REJECTED. It never shows in NEED_DEV_OPTIONS or
     * NEED_WIRELESS_ADB — before wireless pairing starts, a red failure box
     * is illogical (the step text itself is the guidance), and background
     * auto-start failures would otherwise surface as an error the user
     * never caused. An ACTIVE card never shows stale failure guidance.
     */
    fun showFailureGuidance(state: ConnectionCardState, hasRecordedFailure: Boolean): Boolean =
        hasRecordedFailure && (
            state == ConnectionCardState.NEED_PAIRING ||
                state == ConnectionCardState.READY_TO_START ||
                state == ConnectionCardState.AUTH_REJECTED
            )

    /**
     * Which failure presentation the card shows. The silent auto-restart
     * only runs in READY_TO_START, so only there does an AUTH failure
     * read as "finishing setup" while the restart budget is left; once
     * the restart has failed or the budget is spent, the red box with
     * the guided reset is the honest presentation.
     */
    fun failureBoxMode(
        state: ConnectionCardState,
        hasRecordedFailure: Boolean,
        isAuthFailure: Boolean,
        autoRestartFailed: Boolean,
    ): FailureBoxMode {
        if (!showFailureGuidance(state, hasRecordedFailure)) return FailureBoxMode.HIDDEN
        if (state == ConnectionCardState.READY_TO_START && isAuthFailure && !autoRestartFailed) {
            return FailureBoxMode.FINISHING
        }
        return FailureBoxMode.FAILURE
    }
}
