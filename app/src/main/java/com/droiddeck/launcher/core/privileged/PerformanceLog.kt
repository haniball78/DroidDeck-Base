package com.droiddeck.launcher.core.privileged

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.util.Log
import androidx.core.content.FileProvider
import com.droiddeck.launcher.core.LogRedactor
import com.droiddeck.launcher.session.SessionFiles
import java.io.File
import java.util.Locale

// Thermal-zone temps are in millidegrees Celsius. A null result
// means "not readable on this device", never a guessed value, and
// the log field stays "unknown" in that case.
internal fun parseThermalMilliCelsius(raw: String?): Float? {
    val milli = raw?.trim()?.toLongOrNull() ?: return null
    if (milli < -40000L || milli > 200000L) return null
    return milli / 1000f
}

internal fun readThermalTemp(sourcePath: String?): Float? {
    if (sourcePath == null) return null
    val raw = runCatching { File(sourcePath).readText() }.getOrNull() ?: return null
    return parseThermalMilliCelsius(raw)
}

internal fun performanceFields(
    batteryPercent: Int,
    charging: Boolean,
    thermalStatus: Int,
    batteryTempC: Float,
    headroom: Float,
    governorTier: String,
    governorRunning: Boolean,
    tweaksActive: Boolean,
    aggressiveActive: Boolean,
    ramFreeMb: Long,
    cpuLoad: String = "unknown",
    cpuTempC: Float? = null,
    gpuTempC: Float? = null,
): Map<String, String> {
    // After stop() the governor resets its tier to BALANCED_COOL,
    // which looked like an active tier in old samples. Report it
    // as STOPPED, with an explicit running flag, so a post-END
    // sample can never be mistaken for active cooling.
    val effectiveTier = if (governorRunning) governorTier else "STOPPED"
    return mapOf(
        "battery" to batteryPercent.toString(),
        "charging" to charging.toString(),
        "thermal" to thermalStatus.toString(),
        "battery_temp_c" to String.format(Locale.US, "%.1f", batteryTempC),
        "cpu_temp_c" to if (cpuTempC == null || cpuTempC.isNaN()) "unknown" else String.format(Locale.US, "%.1f", cpuTempC),
        "gpu_temp_c" to if (gpuTempC == null || gpuTempC.isNaN()) "unknown" else String.format(Locale.US, "%.1f", gpuTempC),
        "headroom" to if (headroom.isNaN()) "unknown" else String.format(Locale.US, "%.2f", headroom),
        // NaN headroom in the first samples means "not computed
        // yet", not zero and not a sensor failure: make that
        // explicit so early samples are never read as a value.
        "headroom_status" to if (headroom.isNaN()) "unknown" else "known",
        "governor_tier" to effectiveTier,
        "governor_running" to governorRunning.toString(),
        "background_limit" to when {
            governorTier == "COOLING_RELIEF" -> "2"
            tweaksActive -> "4"
            else -> "default"
        },
        "tweaks_active" to tweaksActive.toString(),
        "aggressive_active" to aggressiveActive.toString(),
        "ram_free_mb" to ramFreeMb.toString(),
        "cpu_load" to cpuLoad,
    )
}

object PerformanceLog {
    const val FILE_NAME = "droiddeck-performance.jsonl"
    private const val TAG = "PerformanceLog"
    private const val PREFS = "droiddeck-performance"
    private const val KEY_ENABLED = "enabled"

    fun isEnabled(context: Context): Boolean {
        return runCatching {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_ENABLED, false)
        }.getOrDefault(false)
    }

    fun setEnabled(context: Context, enabled: Boolean) {
        runCatching {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_ENABLED, enabled).apply()
        }
    }

    fun deleteCurrentFile(context: Context): Boolean {
        return runCatching {
            DiagnosticsCore.deleteCurrentFile(SessionFiles.logDirectory(context), FILE_NAME)
        }.getOrDefault(false)
    }

    @Volatile
    private var attached: Context? = null

    @Volatile
    private var lastSampleMs = -1L
    private val lock = Any()

    fun attach(context: Context) {
        runCatching { attached = context.applicationContext }
    }

    fun log(context: Context?, category: String, event: String, generation: Int = 0, fields: Map<String, String> = emptyMap()) {
        val ctx = context ?: attached ?: return
        if (!isEnabled(ctx)) return
        try {
            val dir = SessionFiles.logDirectory(ctx)
            val line = DiagnosticsCore.render(
                System.currentTimeMillis(),
                SystemClock.elapsedRealtime(),
                category,
                event,
                generation,
                fields,
            )
            synchronized(lock) {
                DiagnosticsCore.append(dir, FILE_NAME, line)
            }
        } catch (t: Throwable) {
            Log.w(TAG, "performance write failed", t)
        }
    }

    fun sample(
        context: Context?,
        batteryPercent: Int,
        charging: Boolean,
        thermalStatus: Int,
        tweaksActive: Boolean,
        force: Boolean = false,
        nowMs: Long = SystemClock.elapsedRealtime(),
    ) {
        val ctx = context ?: attached ?: return
        if (!isEnabled(ctx)) return
        if (!DiagnosticsCore.shouldSample(nowMs, lastSampleMs, force)) return
        lastSampleMs = nowMs
        val ramFreeMb = try {
            val manager = ctx?.getSystemService(Context.ACTIVITY_SERVICE) as? android.app.ActivityManager
            val info = android.app.ActivityManager.MemoryInfo()
            manager?.getMemoryInfo(info)
            info.availMem / 1024 / 1024
        } catch (_: Exception) {
            -1L
        }
        log(
            ctx,
            "session.performance",
            "sample",
            fields = performanceFields(
                batteryPercent = batteryPercent,
                charging = charging,
                thermalStatus = thermalStatus,
                batteryTempC = DynamicAdaptiveGovernor.getBatteryTemperature(ctx),
                headroom = DynamicAdaptiveGovernor.getThermalHeadroom(ctx),
                governorTier = DynamicAdaptiveGovernor.getCurrentTier().name,
                governorRunning = DynamicAdaptiveGovernor.isRunning(),
                tweaksActive = tweaksActive,
                aggressiveActive = GameModeEngine.isAggressiveActive(),
                ramFreeMb = ramFreeMb,
                cpuLoad = readCpuLoad(),
                // CPU/GPU temps reuse the same thermal-zone sources
                // already used for the guest hwmon sensors
                // (LinuxRuntime.cpuTempSource/gpuTempSource), never a
                // hard-coded zone number. Unreadable means unknown,
                // not a promise that these fields will always appear.
                cpuTempC = readThermalTemp(com.droiddeck.launcher.runtime.LinuxRuntime.cpuTempSource()),
                gpuTempC = readThermalTemp(com.droiddeck.launcher.runtime.LinuxRuntime.gpuTempSource()),
            ),
        )
    }

    fun file(context: Context): File? {
        return try {
            File(SessionFiles.logDirectory(context), FILE_NAME).takeIf { it.isFile }
        } catch (_: Exception) {
            null
        }
    }

    fun summary(context: Context): DiagnosticsSummary? {
        return try {
            DiagnosticsCore.summary(SessionFiles.logDirectory(context), FILE_NAME)
        } catch (_: Exception) {
            null
        }
    }

    fun shareIntent(context: Context): Intent? {
        return try {
            val src = file(context) ?: return null
            val shareDir = File(context.cacheDir, "share").apply { mkdirs() }
            val copy = File(shareDir, FILE_NAME)
            copy.bufferedWriter(Charsets.UTF_8).use { writer ->
                LogRedactor.scrubForShare(src, writer)
            }
            val uri = FileProvider.getUriForFile(context, context.packageName + ".logs", copy)
            val send = Intent(Intent.ACTION_SEND)
                .setType("text/plain")
                .putExtra(Intent.EXTRA_STREAM, uri)
                .putExtra(Intent.EXTRA_SUBJECT, FILE_NAME)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            send.clipData = ClipData.newRawUri(FILE_NAME, uri)
            Intent.createChooser(send, FILE_NAME).apply {
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        } catch (_: Exception) {
            null
        }
    }

    fun formatTime(wallMs: Long): String = DiagnosticsLog.formatTime(wallMs)
}
