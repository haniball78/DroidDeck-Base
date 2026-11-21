package com.droiddeck.launcher.core.privileged

import android.content.Context
import android.os.SystemClock
import android.util.Log
import com.droiddeck.launcher.core.TvHandoff
import com.droiddeck.launcher.runtime.LinuxRuntime
import com.droiddeck.launcher.session.SessionFiles
import com.droiddeck.launcher.wayland.WaylandCompositor
import java.io.File
import java.util.Locale

// GPU busy is a 0-100 percentage in the KGSL sysfs node. Anything
// outside that range is a misread, never a value.
internal fun parseGpuBusyPercent(raw: String?): Int? {
    val value = raw?.trim()?.toIntOrNull() ?: return null
    if (value < 0 || value > 100) return null
    return value
}

internal fun readGpuBusyPercent(sourcePath: String?): Int? {
    if (sourcePath == null) return null
    val raw = runCatching { File(sourcePath).readText() }.getOrNull() ?: return null
    return parseGpuBusyPercent(raw)
}

internal fun profilerFields(
    gpuBusyPercent: Int?,
    gpuTempC: Float?,
    cpuLoad: String,
    fps: String,
    tier: String = "unknown",
): Map<String, String> {
    return mapOf(
        "gpu_busy_pct" to (gpuBusyPercent?.toString() ?: "unknown"),
        "gpu_temp_c" to if (gpuTempC == null || gpuTempC.isNaN()) "unknown" else String.format(Locale.US, "%.1f", gpuTempC),
        "cpu_load" to cpuLoad,
        "fps" to fps,
        "tier" to tier,
    )
}

// FPS from the Wayland compositor's game-frame counter — the same source
// the TV companion ticker, PerfHud and GameThreadHints already use, so
// no SurfaceFlinger layer lookup and no daemon round-trip is needed.
// First sample has no baseline, and a counter that never moved means no
// game frame was ever observed: both are null, never a guess.
// A counter that moved and then stops is a real stall: 0.0.
internal fun fpsValue(prevCount: Long, prevTimeMs: Long, nowCount: Long, nowMs: Long): Double? {
    if (prevTimeMs <= 0 || nowMs <= prevTimeMs) return null
    if (nowCount < prevCount) return null
    if (nowCount == 0L && prevCount == 0L) return null
    return TvHandoff.fpsFromCounts(prevCount, nowCount, nowMs - prevTimeMs)
}

internal fun fpsString(prevCount: Long, prevTimeMs: Long, nowCount: Long, nowMs: Long): String {
    val value = fpsValue(prevCount, prevTimeMs, nowCount, nowMs) ?: return "unknown"
    return String.format(Locale.US, "%.1f", value)
}

// One 5 s sample, kept in memory for the session-end summary. Nulls mean
// the sensor had no reading that tick, never a guessed value.
data class ProfilerSample(
    val gpuBusyPct: Int?,
    val gpuTempC: Float?,
    val fps: Double?,
    val tier: String,
)

// In-session profiler: a 5 s sampler that reads the GPU/CPU sensors
// in-process (plain File reads of sysfs, never a shell loop) and the
// game-frame counter, and appends one JSONL line per sample to the
// session log directory. The sampler runs on the same diagnostics
// switch as PerformanceLog, so it writes nothing when diagnostics
// are off.
object SessionProfiler {
    const val FILE_NAME = "droiddeck-profiler.jsonl"
    const val SAMPLE_INTERVAL_MS = 5_000L
    private const val TAG = "SessionProfiler"

    @Volatile
    private var running = false
    private var thread: Thread? = null

    // Previous compositor frame count and sample time; the first sample
    // after start has no baseline, so its FPS is "unknown".
    @Volatile
    private var lastFrameCount = -1L
    @Volatile
    private var lastFrameAtMs = 0L

    // This session's samples, for the session-end summary. Written by the
    // sampler thread, taken once by the session-end path.
    private val samples = mutableListOf<ProfilerSample>()

    fun start(context: Context) {
        if (running) return
        running = true
        synchronized(samples) { samples.clear() }
        val appContext = context.applicationContext
        thread = Thread({
            while (running) {
                try {
                    sampleOnce(appContext)
                } catch (t: Throwable) {
                    Log.w(TAG, "profiler sample", t)
                }
                try {
                    Thread.sleep(SAMPLE_INTERVAL_MS)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    return@Thread
                }
            }
        }, "session-profiler").apply { start() }
    }

    fun stop(context: Context): List<ProfilerSample> {
        running = false
        thread?.interrupt()
        thread = null
        lastFrameCount = -1L
        lastFrameAtMs = 0L
        return synchronized(samples) {
            val taken = samples.toList()
            samples.clear()
            taken
        }
    }

    fun isRunning(): Boolean = running

    internal fun sampleOnce(context: Context): Boolean {
        if (!PerformanceLog.isEnabled(context)) return false
        val nowMs = SystemClock.elapsedRealtime()
        val frameCount = WaylandCompositor.gameFrameCount()
        val fps = fpsValue(lastFrameCount, lastFrameAtMs, frameCount, nowMs)
        lastFrameCount = frameCount
        lastFrameAtMs = nowMs
        val gpuBusy = readGpuBusyPercent(LinuxRuntime.gpuLoadSource())
        val gpuTempC = readThermalTemp(LinuxRuntime.gpuTempSource())
        val tier = DynamicAdaptiveGovernor.getCurrentTier().name
        val fields = profilerFields(
            gpuBusyPercent = gpuBusy,
            gpuTempC = gpuTempC,
            cpuLoad = readCpuLoad(),
            fps = fps?.let { String.format(Locale.US, "%.1f", it) } ?: "unknown",
            tier = tier,
        )
        synchronized(samples) {
            samples.add(ProfilerSample(gpuBusy, gpuTempC, fps, tier))
        }
        val dir = SessionFiles.logDirectory(context)
        val written = writeSample(dir, fields)
        return written
    }

    internal fun writeSample(
        dir: File,
        fields: Map<String, String>,
        wallMs: Long = System.currentTimeMillis(),
        elapsedMs: Long = SystemClock.elapsedRealtime(),
    ): Boolean {
        val line = DiagnosticsCore.render(
            wallMs,
            elapsedMs,
            "session.profiler",
            "sample",
            0,
            fields,
        )
        return DiagnosticsCore.append(dir, FILE_NAME, line)
    }
}
