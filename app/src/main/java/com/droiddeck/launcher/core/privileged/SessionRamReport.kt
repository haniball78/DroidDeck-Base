package com.droiddeck.launcher.core.privileged

import android.content.Context
import java.io.File

// /proc/loadavg is world-readable; its first field is the 1-minute
// load average. Anything unreadable or unparsable is "unknown",
// never a guessed number.
internal fun parseCpuLoad(raw: String?): String {
    val first = raw?.trim()?.split(Regex("\\s+"))?.firstOrNull() ?: return "unknown"
    val value = first.toDoubleOrNull() ?: return "unknown"
    if (value.isNaN() || value < 0.0) return "unknown"
    return first
}

internal fun readCpuLoad(loadavgPath: String = "/proc/loadavg"): String {
    val raw = runCatching { File(loadavgPath).readText() }.getOrNull() ?: return "unknown"
    return parseCpuLoad(raw)
}

internal data class RamSnapshot(val availMb: Long, val cpuLoad: String)

internal fun readAvailRamMb(context: Context?): Long {
    return try {
        val manager = context?.getSystemService(Context.ACTIVITY_SERVICE) as? android.app.ActivityManager ?: return -1L
        val info = android.app.ActivityManager.MemoryInfo()
        manager.getMemoryInfo(info)
        info.availMem / 1024 / 1024
    } catch (_: Exception) {
        -1L
    }
}

internal fun readRamSnapshot(context: Context?, loadavgPath: String = "/proc/loadavg"): RamSnapshot {
    return RamSnapshot(availMb = readAvailRamMb(context), cpuLoad = readCpuLoad(loadavgPath))
}

internal fun ramReportFields(
    before: RamSnapshot,
    after: RamSnapshot,
    suspendedCount: Int,
    restored: RamSnapshot?,
): Map<String, String> {
    return mapOf(
        "before_avail_mb" to if (before.availMb < 0) "unknown" else before.availMb.toString(),
        "after_avail_mb" to if (after.availMb < 0) "unknown" else after.availMb.toString(),
        "freed_mb" to if (before.availMb < 0 || after.availMb < 0) "unknown" else (after.availMb - before.availMb).toString(),
        "suspended_count" to suspendedCount.toString(),
        "cpu_load_before" to before.cpuLoad,
        "cpu_load_after" to after.cpuLoad,
        "restored_avail_mb" to if (restored == null || restored.availMb < 0) "unknown" else restored.availMb.toString(),
        "restored_cpu_load" to (restored?.cpuLoad ?: "unknown"),
    )
}

object SessionRamReport {
    internal fun log(context: Context?, before: RamSnapshot, after: RamSnapshot, suspendedCount: Int, restored: RamSnapshot?) {
        val fields = ramReportFields(before, after, suspendedCount, restored)
        DiagnosticsLog.log(context, "session.performance", "session-ram-report", fields = fields)
        PerformanceLog.log(context, "session.performance", "session-ram-report", fields = fields)
    }
}
