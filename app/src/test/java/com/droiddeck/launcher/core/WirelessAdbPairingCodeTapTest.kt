package com.droiddeck.launcher.core

import com.droiddeck.launcher.core.WirelessAdbPairingService.CodeTap
import org.junit.Assert.assertEquals
import org.junit.Test

class WirelessAdbPairingCodeTapTest {
    @Test
    fun shortCodeIsRejectedWhetherWorkingOrNot() {
        assertEquals(CodeTap.REJECT_SHORT, WirelessAdbPairingService.resolveCodeTap("12345", working = false))
        assertEquals(CodeTap.REJECT_SHORT, WirelessAdbPairingService.resolveCodeTap("12345", working = true))
        assertEquals(CodeTap.REJECT_SHORT, WirelessAdbPairingService.resolveCodeTap("", working = false))
    }

    @Test
    fun validCodeStartsPairWhenIdle() {
        assertEquals(CodeTap.START_PAIR, WirelessAdbPairingService.resolveCodeTap("123456", working = false))
    }

    @Test
    fun validCodeCorrectsRunningPair() {
        assertEquals(CodeTap.CORRECT_RUNNING, WirelessAdbPairingService.resolveCodeTap("123456", working = true))
    }

    @Test
    fun sevenDigitCodeIsRejected() {
        assertEquals(CodeTap.REJECT_SHORT, WirelessAdbPairingService.resolveCodeTap("1234567", working = false))
    }
}
