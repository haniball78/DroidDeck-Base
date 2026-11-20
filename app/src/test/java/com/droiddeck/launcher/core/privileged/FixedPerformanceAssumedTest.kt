package com.droiddeck.launcher.core.privileged

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FixedPerformanceAssumedTest {
    private fun freqSample(cpu2: Long, cpu7: Long): String {
        val freqs = longArrayOf(499200, 614400, cpu2, 1200000, 1400000, 1600000, 1800000, cpu7)
        return freqs.joinToString("\n") { it.toString() } + "\n"
    }

    private fun pinnedSamples(): List<String> =
        listOf(freqSample(1920000, 1939200), freqSample(1920000, 1939200), freqSample(1920000, 1939200))

    @Test
    fun assumedItemJournalsFalseWithExitCodeOnlyRestore() {
        val item = FixedPerformanceState.assumedItem(5)
        assertEquals("fixed-performance", item.key)
        assertEquals("false", item.original)
        assertEquals("cmd power set-fixed-performance-mode-enabled false 2>/dev/null", item.restoreCmd)
        assertNull(item.verifyCmd)
        assertEquals(JournalVerifyMode.UNVERIFIED, item.verifyMode)
        assertEquals(5, item.generation)
    }

    @Test
    fun assumedItemRoundTripsThroughJournalCodec() {
        val items = listOf(FixedPerformanceState.assumedItem(5))
        assertEquals(items, JournalCodec.decode(JournalCodec.encode(items)))
    }

    @Test
    fun isAssumedDistinguishesAssumedFromGetterReadItems() {
        assertTrue(FixedPerformanceState.isAssumed(FixedPerformanceState.assumedItem(3)))
        assertFalse(FixedPerformanceState.isAssumed(FixedPerformanceState.item("false", 3)))
        assertFalse(FixedPerformanceState.isAssumed(FixedPerformanceState.item("true", 3)))
    }

    @Test
    fun freqCheckAcceptsPinnedCpu2AndCpu7AcrossThreeSamples() {
        assertTrue(FixedPerformanceFreqCheck.verified(pinnedSamples()))
    }

    @Test
    fun freqCheckRejectsFluctuatingCpu2() {
        val samples = listOf(
            freqSample(1920000, 1939200),
            freqSample(1900000, 1939200),
            freqSample(1920000, 1939200),
        )
        assertFalse(FixedPerformanceFreqCheck.verified(samples))
    }

    @Test
    fun freqCheckRejectsFluctuatingCpu7() {
        val samples = listOf(
            freqSample(1920000, 1939200),
            freqSample(1920000, 1800000),
            freqSample(1920000, 1939200),
        )
        assertFalse(FixedPerformanceFreqCheck.verified(samples))
    }

    @Test
    fun freqCheckRejectsIdleStableLowFrequencies() {
        val samples = List(3) { freqSample(614400, 614400) }
        assertFalse(FixedPerformanceFreqCheck.verified(samples))
    }

    @Test
    fun freqCheckRejectsShortAndEmptySamples() {
        assertFalse(FixedPerformanceFreqCheck.verified(listOf("1920000\n1939200\n")))
        assertFalse(FixedPerformanceFreqCheck.verified(emptyList()))
        assertFalse(FixedPerformanceFreqCheck.verified(listOf(freqSample(1920000, 1939200))))
    }

    @Test
    fun freqCheckParsesOnlyNumericLines() {
        assertEquals(
            listOf(1920000L, 1939200L),
            FixedPerformanceFreqCheck.parseFreqs("1920000\nnot-a-freq\n\n1939200\n"),
        )
    }

    @Test
    fun stepVerifyUsesFreqSamplesForFixedPerformance() {
        assertTrue(GameModeStepVerify.verified(0, null, null, true, freqSamples = pinnedSamples()))
        assertFalse(GameModeStepVerify.verified(1, null, null, true, freqSamples = pinnedSamples()))
        assertFalse(
            GameModeStepVerify.verified(
                0, null, null, true,
                freqSamples = listOf(
                    freqSample(1920000, 1939200),
                    freqSample(1500000, 1939200),
                    freqSample(1920000, 1939200),
                ),
            ),
        )
    }

    @Test
    fun stepVerifyGetterPathUnchangedWhenNoFreqSamples() {
        val read = PrivilegedResult(0, "true\n", PrivilegedMode.NONE)
        assertTrue(GameModeStepVerify.verified(0, read, "true", true))
        assertFalse(GameModeStepVerify.verified(0, PrivilegedResult(0, "false\n", PrivilegedMode.NONE), "true", true))
    }

    @Test
    fun freqStepDefaultsOffAndCanBeEnabled() {
        val plain = GameModeStep("cmd power set-fixed-performance-mode-enabled true", "cmd power get-fixed-performance-mode-enabled", "true", true)
        assertFalse(plain.freqVerify)
        val freq = GameModeStep("cmd power set-fixed-performance-mode-enabled true", FixedPerformanceFreqCheck.FREQ_READ_CMD, null, true, freqVerify = true)
        assertTrue(freq.freqVerify)
    }

    @Test
    fun assumedItemIsNeverRemovedAsPoisoned() {
        assertFalse(PoisonedFixedPerformanceItem.mayRemove(true, 255, "", assumedItem = true))
        assertFalse(PoisonedFixedPerformanceItem.mayRemove(true, 0, "Unknown command\n", assumedItem = true))
        assertTrue(PoisonedFixedPerformanceItem.mayRemove(true, 255, "", assumedItem = false))
    }
}
