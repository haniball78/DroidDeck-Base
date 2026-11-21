package com.droiddeck.launcher.core.privileged

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GameProfileStoreTest {
    private fun tempDir(): File = Files.createTempDirectory("profile-test").toFile()

    private fun sample(gpuBusy: Int?, temp: Float?, fps: Double?, tier: String) =
        ProfilerSample(gpuBusy, temp, fps, tier)

    private fun coolSamples(): List<ProfilerSample> = List(12) {
        sample(55, 41.0f, 60.0, "HEADROOM_BOOST")
    }

    private fun record(mode: String, avgFps: Double?, maxTemp: Double?): GameSessionRecord =
        GameSessionRecord(
            gameKey = "769",
            mode = mode,
            samples = 12,
            avgGpuBusyPct = 70.0,
            avgFps = avgFps,
            minFps = avgFps?.let { it - 5.0 },
            maxGpuTempC = maxTemp,
            throttled = false,
            tierSeconds = mapOf("BALANCED_COOL" to 60L),
            endedAtMs = 1700000000000L,
        )

    @Test
    fun summarizeSkipsUnknownValues() {
        val samples = listOf(
            sample(80, 45.0f, 60.0, "BALANCED_COOL"),
            sample(null, null, null, "BALANCED_COOL"),
            sample(90, 46.0f, 55.0, "HEADROOM_BOOST"),
        )
        val record = GameProfileStore.summarize("769", "on", samples, 1700000000000L)!!
        assertEquals(3, record.samples)
        assertEquals(85.0, record.avgGpuBusyPct!!, 0.001)
        assertEquals(57.5, record.avgFps!!, 0.001)
        assertEquals(55.0, record.minFps!!, 0.001)
        assertEquals(46.0, record.maxGpuTempC!!, 0.001)
        assertEquals(false, record.throttled)
        assertEquals(10L, record.tierSeconds["BALANCED_COOL"])
        assertEquals(5L, record.tierSeconds["HEADROOM_BOOST"])
    }

    @Test
    fun summarizeEmptyReturnsNull() {
        assertNull(GameProfileStore.summarize("769", "on", emptyList(), 1000L))
    }

    @Test
    fun summarizeThrottledWhenReliefTierPresent() {
        val samples = listOf(
            sample(90, 47.0f, 40.0, "BALANCED_COOL"),
            sample(98, 50.0f, 30.0, "COOLING_RELIEF"),
        )
        val record = GameProfileStore.summarize("769", "on", samples, 1700000000000L)!!
        assertEquals(true, record.throttled)
        assertEquals(50.0, record.maxGpuTempC!!, 0.001)
    }

    @Test
    fun profileJsonRoundTrip() {
        val record = GameProfileStore.summarize("769", "on", coolSamples(), 1700000000000L)!!
        val decoded = GameProfileStore.recordFromJson(GameProfileStore.recordToJson(record))
        assertEquals(record, decoded)
    }

    @Test
    fun profileJsonCorruptReturnsNull() {
        assertNull(GameProfileStore.recordFromJson("not json"))
        assertNull(GameProfileStore.recordFromJson("{}"))
        assertNull(GameProfileStore.recordFromJson("{\"gameKey\":\"769\",\"mode\":\"weird\",\"samples\":12}"))
        assertNull(GameProfileStore.recordFromJson("{\"gameKey\":\"769\",\"mode\":\"on\",\"samples\":-1}"))
    }

    @Test
    fun decideStartingTierHot() {
        val throttled = GameProfileStore.summarize("769", "on", listOf(
            sample(90, 47.0f, 40.0, "BALANCED_COOL"),
            sample(98, 50.0f, 30.0, "COOLING_RELIEF"),
        ), 1700000000000L)!!
        assertEquals(
            DynamicAdaptiveGovernor.AdaptiveTier.COOLING_RELIEF,
            GameProfileStore.decideStartingTier(throttled),
        )
        val hotTemp = GameProfileStore.summarize("769", "on", List(12) {
            sample(90, 52.0f, 55.0, "BALANCED_COOL")
        }, 1700000000000L)!!
        assertEquals(
            DynamicAdaptiveGovernor.AdaptiveTier.COOLING_RELIEF,
            GameProfileStore.decideStartingTier(hotTemp),
        )
        val lowFps = GameProfileStore.summarize("769", "on", List(12) {
            sample(96, 40.0f, 20.0, "BALANCED_COOL")
        }, 1700000000000L)!!
        assertEquals(
            DynamicAdaptiveGovernor.AdaptiveTier.COOLING_RELIEF,
            GameProfileStore.decideStartingTier(lowFps),
        )
    }

    @Test
    fun decideStartingTierCool() {
        val cool = GameProfileStore.summarize("769", "on", coolSamples(), 1700000000000L)!!
        assertEquals(
            DynamicAdaptiveGovernor.AdaptiveTier.HEADROOM_BOOST,
            GameProfileStore.decideStartingTier(cool),
        )
    }

    @Test
    fun decideStartingTierMixedAndNull() {
        val mixed = GameProfileStore.summarize("769", "on", List(12) {
            sample(80, 46.0f, 55.0, "BALANCED_COOL")
        }, 1700000000000L)!!
        assertEquals(
            DynamicAdaptiveGovernor.AdaptiveTier.BALANCED_COOL,
            GameProfileStore.decideStartingTier(mixed),
        )
        assertEquals(
            DynamicAdaptiveGovernor.AdaptiveTier.BALANCED_COOL,
            GameProfileStore.decideStartingTier(null),
        )
    }

    @Test
    fun verdictBetter() {
        val comparison = GameProfileStore.compare(
            record("on", 60.0, 42.0),
            record("off", 55.0, 42.5),
        )
        assertEquals(ComparisonVerdict.BETTER, comparison.verdict)
        assertEquals(5.0, comparison.fpsDelta!!, 0.001)
        assertEquals(-0.5, comparison.tempDelta!!, 0.001)
    }

    @Test
    fun verdictBetterFromOffSessionPerspective() {
        val comparison = GameProfileStore.compare(
            record("off", 55.0, 42.5),
            record("on", 60.0, 42.0),
        )
        assertEquals(ComparisonVerdict.BETTER, comparison.verdict)
        assertEquals(5.0, comparison.fpsDelta!!, 0.001)
    }

    @Test
    fun verdictWorse() {
        val fpsDown = GameProfileStore.compare(
            record("on", 50.0, 42.0),
            record("off", 56.0, 42.0),
        )
        assertEquals(ComparisonVerdict.WORSE, fpsDown.verdict)
        val hotter = GameProfileStore.compare(
            record("on", 60.0, 46.0),
            record("off", 58.0, 42.0),
        )
        assertEquals(ComparisonVerdict.WORSE, hotter.verdict)
    }

    @Test
    fun verdictNoRealDifference() {
        val comparison = GameProfileStore.compare(
            record("on", 56.0, 42.5),
            record("off", 55.0, 42.0),
        )
        assertEquals(ComparisonVerdict.NO_REAL_DIFFERENCE, comparison.verdict)
    }

    @Test
    fun verdictNoBaseline() {
        val comparison = GameProfileStore.compare(record("on", 60.0, 42.0), null)
        assertEquals(ComparisonVerdict.NO_BASELINE, comparison.verdict)
        assertNull(comparison.fpsDelta)
        assertNull(comparison.tempDelta)
    }

    @Test
    fun verdictNoData() {
        val noData = GameProfileStore.summarize("769", "on", List(6) {
            sample(null, null, null, "BALANCED_COOL")
        }, 1700000000000L)!!
        val baseline = GameProfileStore.summarize("769", "off", List(6) {
            sample(null, null, null, "BALANCED_COOL")
        }, 1700000000000L)!!
        assertEquals(ComparisonVerdict.NO_DATA, GameProfileStore.compare(noData, baseline).verdict)
    }

    @Test
    fun saveLoadRoundTrip() {
        val dir = tempDir()
        val record = GameProfileStore.summarize("769", "on", coolSamples(), 1700000000000L)!!
        val file = GameProfileStore.profileFile(dir, "769")
        assertTrue(GameProfileStore.saveRecord(dir, file, record))
        assertEquals(record, GameProfileStore.loadRecord(file))
    }

    @Test
    fun loadCorruptFileReturnsNull() {
        val dir = tempDir()
        val file = GameProfileStore.profileFile(dir, "769")
        file.writeText("garbage{")
        assertNull(GameProfileStore.loadRecord(file))
    }

    @Test
    fun comparisonJsonContainsVerdict() {
        val comparison = GameProfileStore.compare(
            record("on", 60.0, 42.0),
            record("off", 55.0, 42.5),
        )
        val json = GameProfileStore.comparisonToJson(comparison)
        assertTrue(json.contains("\"verdict\":\"better\""))
        assertTrue(json.contains("\"fpsDelta\""))
        assertTrue(json.contains("\"game\":\"769\""))
        assertTrue(json.contains("\"baseline\""))
    }
}
