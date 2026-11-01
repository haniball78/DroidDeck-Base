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

internal fun performanceFields(
    batteryPercent: Int,
    charging: Boolean,
    thermalStatus: Int,
    batteryTempC: Float,
    headroom: Float,
    governorTier: String,
    tweaksActive: Boolean,
    aggressiveActive: Boolean,
    ramFreeMb: Long,
): Map<String, String> {
    return mapOf(
        "battery" to batteryPercent.toString(),
        "charging" to charging.toString(),
        "thermal" to thermalStatus.toString(),
        "battery_temp_c" to String.format(Locale.US, "%.1f", batteryTempC),
        "headroom" to if (headroom.isNaN()) "unknown" else String.format(Locale.US, "%.2f", headroom),
        "governor_tier" to governorTier,
        "background_limit" to when {
            governorTier == "COOLING_RELIEF" -> "2"
            tweaksActive -> "4"
            else -> "default"
        },
        "tweaks_active" to tweaksActive.toString(),
        "aggressive_active" to aggressiveActive.toString(),
        "ram_free_mb" to ramFreeMb.toString(),
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
                tweaksActive = tweaksActive,
                aggressiveActive = GameModeEngine.isAggressiveActive(),
                ramFreeMb = ramFreeMb,
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
