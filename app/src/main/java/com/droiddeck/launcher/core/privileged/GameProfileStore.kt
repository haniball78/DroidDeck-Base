package com.droiddeck.launcher.core.privileged

import android.content.Context
import com.droiddeck.launcher.session.SessionFiles
import java.io.File
import java.util.Locale

// One session's measured record for a game. Nulls mean the sensor had no
// reading that session, never a guessed value. The latest record for a
// game is its profile; the latest record per mode ("on"/"off") is the
// comparison baseline for that game.
data class GameSessionRecord(
    val gameKey: String,
    val mode: String,
    val samples: Int,
    val avgGpuBusyPct: Double?,
    val avgFps: Double?,
    val minFps: Double?,
    val maxGpuTempC: Double?,
    val throttled: Boolean,
    val tierSeconds: Map<String, Long>,
    val endedAtMs: Long,
)

enum class ComparisonVerdict(val label: String) {
    BETTER("better"),
    WORSE("worse"),
    NO_REAL_DIFFERENCE("no_real_difference"),
    NO_BASELINE("no_baseline"),
    NO_DATA("no_data"),
}

data class GameComparison(
    val gameKey: String,
    val mode: String,
    val record: GameSessionRecord,
    val baseline: GameSessionRecord?,
    val verdict: ComparisonVerdict,
    val fpsDelta: Double?,
    val tempDelta: Double?,
)

object GameProfileStore {
    const val MODE_ON = "on"
    const val MODE_OFF = "off"

    // Thresholds, grounded in the 2026-11-21 play session: a clean
    // 6-minute World of Sea Battle run peaked at GPU 43.2 C with no
    // throttling, so 44 C and under is cool, 48 C and over is hot.
    const val HOT_GPU_TEMP_C = 48.0
    const val COOL_GPU_TEMP_C = 44.0
    const val LOW_AVG_FPS = 25.0
    const val GOOD_AVG_FPS = 50.0
    const val GOOD_MIN_FPS = 30.0

    // Verdict bands: under 2 fps or 1.5 C the difference is noise.
    // 3 C hotter is worse on its own, whatever the FPS did.
    const val FPS_DIFF_MIN = 2.0
    const val TEMP_DIFF_MIN = 1.5
    const val TEMP_WORSE_MAX = 3.0

    const val COMPARISON_FILE_NAME = "droiddeck-comparison.json"

    fun profilesDir(context: Context): File =
        File(context.filesDir, "game-profiles").apply { mkdirs() }

    internal fun sanitizeKey(key: String): String =
        key.map { if (it.isLetterOrDigit() || it == '.' || it == '_' || it == '-') it else '_' }.joinToString("")

    internal fun profileFile(dir: File, gameKey: String): File =
        File(dir, sanitizeKey(gameKey) + ".profile.json")

    internal fun lastSessionFile(dir: File, gameKey: String, mode: String): File =
        File(dir, sanitizeKey(gameKey) + ".last-" + mode + ".json")

    internal fun summarize(
        gameKey: String,
        mode: String,
        samples: List<ProfilerSample>,
        endedAtMs: Long,
    ): GameSessionRecord? {
        if (samples.isEmpty()) return null
        val gpuBusy = samples.mapNotNull { it.gpuBusyPct }
        val fps = samples.mapNotNull { it.fps }
        val temps = samples.mapNotNull { it.gpuTempC }
        val tierSeconds = samples.groupingBy { it.tier }.eachCount()
            .mapValues { (_, count) -> count.toLong() * (SessionProfiler.SAMPLE_INTERVAL_MS / 1000L) }
        return GameSessionRecord(
            gameKey = gameKey,
            mode = mode,
            samples = samples.size,
            avgGpuBusyPct = gpuBusy.takeIf { it.isNotEmpty() }?.average(),
            avgFps = fps.takeIf { it.isNotEmpty() }?.average(),
            minFps = fps.minOrNull(),
            maxGpuTempC = temps.maxOrNull()?.toDouble(),
            throttled = samples.any { it.tier == DynamicAdaptiveGovernor.AdaptiveTier.COOLING_RELIEF.name },
            tierSeconds = tierSeconds,
            endedAtMs = endedAtMs,
        )
    }

    internal fun decideStartingTier(record: GameSessionRecord?): DynamicAdaptiveGovernor.AdaptiveTier {
        if (record == null) return DynamicAdaptiveGovernor.AdaptiveTier.BALANCED_COOL
        if (record.throttled) return DynamicAdaptiveGovernor.AdaptiveTier.COOLING_RELIEF
        if (record.maxGpuTempC != null && record.maxGpuTempC >= HOT_GPU_TEMP_C) {
            return DynamicAdaptiveGovernor.AdaptiveTier.COOLING_RELIEF
        }
        if (record.avgFps != null && record.avgFps < LOW_AVG_FPS) {
            return DynamicAdaptiveGovernor.AdaptiveTier.COOLING_RELIEF
        }
        if (record.maxGpuTempC != null && record.maxGpuTempC <= COOL_GPU_TEMP_C &&
            record.avgFps != null && record.avgFps >= GOOD_AVG_FPS &&
            record.minFps != null && record.minFps >= GOOD_MIN_FPS
        ) {
            return DynamicAdaptiveGovernor.AdaptiveTier.HEADROOM_BOOST
        }
        return DynamicAdaptiveGovernor.AdaptiveTier.BALANCED_COOL
    }

    internal fun compare(current: GameSessionRecord, baseline: GameSessionRecord?): GameComparison {
        if (baseline == null) {
            return GameComparison(current.gameKey, current.mode, current, null, ComparisonVerdict.NO_BASELINE, null, null)
        }
        val (onRecord, offRecord) = if (current.mode == MODE_ON) current to baseline else baseline to current
        val fpsDelta = if (onRecord.avgFps != null && offRecord.avgFps != null) onRecord.avgFps - offRecord.avgFps else null
        val tempDelta = if (onRecord.maxGpuTempC != null && offRecord.maxGpuTempC != null) onRecord.maxGpuTempC - offRecord.maxGpuTempC else null
        val verdict = when {
            fpsDelta == null && tempDelta == null -> ComparisonVerdict.NO_DATA
            fpsDelta != null && fpsDelta >= FPS_DIFF_MIN && (tempDelta == null || tempDelta < TEMP_DIFF_MIN) -> ComparisonVerdict.BETTER
            fpsDelta == null && tempDelta != null && tempDelta <= -TEMP_DIFF_MIN -> ComparisonVerdict.BETTER
            fpsDelta != null && fpsDelta <= -FPS_DIFF_MIN -> ComparisonVerdict.WORSE
            tempDelta != null && tempDelta >= TEMP_WORSE_MAX -> ComparisonVerdict.WORSE
            else -> ComparisonVerdict.NO_REAL_DIFFERENCE
        }
        return GameComparison(current.gameKey, current.mode, current, baseline, verdict, fpsDelta, tempDelta)
    }

    // JSON is built and read by hand, like DiagnosticsCore.render:
    // org.json is a stub that returns default values in local unit
    // tests (unitTests.returnDefaultValues), so it cannot be verified
    // there, while hand-rolled JSON behaves identically on device
    // and under test.
    private fun jsonString(value: String): String = "\"" + DiagnosticsCore.jsonEscape(value) + "\""

    internal fun recordToJson(record: GameSessionRecord): String {
        val out = StringBuilder()
        out.append("{")
        out.append("\"gameKey\":").append(jsonString(record.gameKey)).append(",")
        out.append("\"mode\":").append(jsonString(record.mode)).append(",")
        out.append("\"samples\":").append(record.samples).append(",")
        out.append("\"avgGpuBusyPct\":").append(record.avgGpuBusyPct?.toString() ?: "null").append(",")
        out.append("\"avgFps\":").append(record.avgFps?.toString() ?: "null").append(",")
        out.append("\"minFps\":").append(record.minFps?.toString() ?: "null").append(",")
        out.append("\"maxGpuTempC\":").append(record.maxGpuTempC?.toString() ?: "null").append(",")
        out.append("\"throttled\":").append(record.throttled).append(",")
        out.append("\"tierSeconds\":{")
        out.append(record.tierSeconds.entries.joinToString(",") { (tier, seconds) ->
            jsonString(tier) + ":" + seconds
        })
        out.append("},")
        out.append("\"endedAtMs\":").append(record.endedAtMs)
        out.append("}")
        return out.toString()
    }

    private fun scalarField(text: String, name: String): String? {
        val match = Regex(
            "\"" + Regex.escape(name) + "\"\\s*:\\s*(\"(?:[^\"\\\\]|\\\\.)*\"|-?\\d+(?:\\.\\d+)?(?:[eE][+-]?\\d+)?|true|false|null)",
        ).find(text) ?: return null
        return match.groupValues[1]
    }

    private fun unescapeJsonString(raw: String): String {
        if (!raw.contains('\\')) return raw
        val out = StringBuilder()
        var i = 0
        while (i < raw.length) {
            val ch = raw[i]
            if (ch == '\\' && i + 1 < raw.length) {
                when (raw[i + 1]) {
                    '"' -> out.append('"')
                    '\\' -> out.append('\\')
                    'n' -> out.append('\n')
                    'r' -> out.append('\r')
                    't' -> out.append('\t')
                    'u' -> {
                        val hex = raw.substring(i + 2, minOf(i + 6, raw.length))
                        out.append(hex.toIntOrNull(16)?.toChar() ?: '?')
                        i += 4
                    }
                    else -> out.append(raw[i + 1])
                }
                i += 2
            } else {
                out.append(ch)
                i++
            }
        }
        return out.toString()
    }

    private fun stringField(text: String, name: String): String? {
        val raw = scalarField(text, name) ?: return null
        if (!raw.startsWith("\"") || !raw.endsWith("\"")) return null
        return unescapeJsonString(raw.substring(1, raw.length - 1))
    }

    private fun doubleField(text: String, name: String): Double? {
        val raw = scalarField(text, name) ?: return null
        if (raw == "null") return null
        return raw.toDoubleOrNull()
    }

    private fun tierSecondsField(text: String): Map<String, Long>? {
        val match = Regex("\"tierSeconds\"\\s*:\\s*\\{([^}]*)\\}").find(text) ?: return null
        val body = match.groupValues[1]
        if (body.isBlank()) return emptyMap()
        val result = mutableMapOf<String, Long>()
        for (entry in Regex("\"([^\"]+)\"\\s*:\\s*(-?\\d+)").findAll(body)) {
            result[entry.groupValues[1]] = entry.groupValues[2].toLongOrNull() ?: return null
        }
        return result
    }

    internal fun recordFromJson(text: String): GameSessionRecord? {
        return try {
            val gameKey = stringField(text, "gameKey")?.takeIf { it.isNotEmpty() } ?: return null
            val mode = stringField(text, "mode")?.takeIf { it == MODE_ON || it == MODE_OFF } ?: return null
            val samples = scalarField(text, "samples")?.toIntOrNull()?.takeIf { it >= 0 } ?: return null
            val throttled = scalarField(text, "throttled")?.toBooleanStrictOrNull() ?: return null
            val endedAtMs = scalarField(text, "endedAtMs")?.toLongOrNull() ?: return null
            GameSessionRecord(
                gameKey = gameKey,
                mode = mode,
                samples = samples,
                avgGpuBusyPct = doubleField(text, "avgGpuBusyPct"),
                avgFps = doubleField(text, "avgFps"),
                minFps = doubleField(text, "minFps"),
                maxGpuTempC = doubleField(text, "maxGpuTempC"),
                throttled = throttled,
                tierSeconds = tierSecondsField(text) ?: return null,
                endedAtMs = endedAtMs,
            )
        } catch (_: Exception) {
            null
        }
    }

    internal fun comparisonToJson(comparison: GameComparison): String {
        val out = StringBuilder()
        out.append("{")
        out.append("\"game\":").append(jsonString(comparison.gameKey)).append(",")
        out.append("\"mode\":").append(jsonString(comparison.mode)).append(",")
        out.append("\"verdict\":").append(jsonString(comparison.verdict.label)).append(",")
        out.append("\"fpsDelta\":").append(comparison.fpsDelta?.toString() ?: "null").append(",")
        out.append("\"tempDelta\":").append(comparison.tempDelta?.toString() ?: "null").append(",")
        out.append("\"this\":").append(recordToJson(comparison.record))
        if (comparison.baseline != null) {
            out.append(",\"baseline\":").append(recordToJson(comparison.baseline))
        }
        out.append("}")
        return out.toString()
    }

    internal fun saveRecord(dir: File, file: File, record: GameSessionRecord): Boolean {
        return try {
            AtomicStateFile.save(file, recordToJson(record))
        } catch (_: Exception) {
            false
        }
    }

    internal fun loadRecord(file: File): GameSessionRecord? {
        return try {
            AtomicStateFile.readRecovered(file) { text -> recordFromJson(text) }
        } catch (_: Exception) {
            null
        }
    }

    fun startingTierFor(context: Context, gameKey: String): DynamicAdaptiveGovernor.AdaptiveTier {
        val profile = runCatching { loadRecord(profileFile(profilesDir(context), gameKey)) }.getOrNull()
        return decideStartingTier(profile)
    }

    fun recordSessionEnd(
        context: Context,
        gameKey: String,
        profilesOn: Boolean,
        samples: List<ProfilerSample>,
        sessionDir: File?,
    ): GameComparison? {
        val mode = if (profilesOn) MODE_ON else MODE_OFF
        val record = summarize(gameKey, mode, samples, System.currentTimeMillis()) ?: return null
        val dir = runCatching { profilesDir(context) }.getOrNull() ?: return null
        runCatching { saveRecord(dir, profileFile(dir, gameKey), record) }
        runCatching { saveRecord(dir, lastSessionFile(dir, gameKey, mode), record) }
        val oppositeMode = if (profilesOn) MODE_OFF else MODE_ON
        val baseline = runCatching { loadRecord(lastSessionFile(dir, gameKey, oppositeMode)) }.getOrNull()
        val comparison = compare(record, baseline)
        val reportDir = sessionDir?.takeIf { it.isDirectory }
            ?: runCatching { SessionFiles.logDirectory(context) }.getOrNull()
        if (reportDir != null) {
            runCatching {
                AtomicStateFile.save(File(reportDir, COMPARISON_FILE_NAME), comparisonToJson(comparison))
            }
        }
        runCatching {
            DiagnosticsLog.log(
                context, "session.profile", "comparison",
                fields = mapOf(
                    "game" to gameKey,
                    "mode" to mode,
                    "verdict" to comparison.verdict.label,
                    "fps_avg" to (record.avgFps?.let { String.format(Locale.US, "%.1f", it) } ?: "unknown"),
                    "fps_delta" to (comparison.fpsDelta?.let { String.format(Locale.US, "%.1f", it) } ?: "unknown"),
                    "temp_max" to (record.maxGpuTempC?.let { String.format(Locale.US, "%.1f", it) } ?: "unknown"),
                    "temp_delta" to (comparison.tempDelta?.let { String.format(Locale.US, "%.1f", it) } ?: "unknown"),
                ),
            )
        }
        return comparison
    }
}
