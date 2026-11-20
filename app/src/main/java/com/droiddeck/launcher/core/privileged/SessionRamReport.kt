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

internal data class CpuLoadResult(val value: String, val reason: String)

internal fun cpuLoadFromDaemonResult(result: PrivilegedResult?): CpuLoadResult {
    if (result == null) return CpuLoadResult("unknown", "daemon-error")
    if (result.exitCode != 0 || result.output.isBlank()) {
        val reason = when {
            result.exitCode == 0 -> "parse-failed"
            result.output == "UNAUTHORIZED" -> "daemon-unauthorized"
            result.output.contains("timed out", ignoreCase = true) -> "timeout"
            result.mode == PrivilegedMode.NONE -> "daemon-unavailable"
            else -> "daemon-error"
        }
        return CpuLoadResult("unknown", reason)
    }
    val value = parseCpuLoad(result.output)
    return CpuLoadResult(value, if (value == "unknown") "parse-failed" else "daemon")
}

internal fun readCpuLoadWithReason(loadavgPath: String = "/proc/loadavg"): CpuLoadResult {
    val raw = runCatching { File(loadavgPath).readText() }.getOrNull()
    if (raw != null) {
        val value = parseCpuLoad(raw)
        return CpuLoadResult(value, if (value == "unknown") "parse-failed" else "direct")
    }
    // /proc/loadavg is unreadable from the app's UID on Android 16, so a
    // direct read always fails on device; the privileged daemon runs as
    // shell and can read it. Only for the real device path, so unit tests
    // passing a synthetic path never touch the bridge.
    if (loadavgPath != "/proc/loadavg") return CpuLoadResult("unknown", "direct-unreadable")
    // No isPrivilegedAvailable() gate here: executeChecked refreshes the
    // bridge when its cached status is NONE, and the gate only blocked
    // that recovery, so a snapshot taken before the session-start refresh
    // silently stayed "unknown" while the daemon was up.
    val result = runCatching {
        PrivilegedBridge.executeChecked("cat /proc/loadavg", PrivilegedBridge.DEFAULT_TIMEOUT_MS)
    }.getOrNull()
    return cpuLoadFromDaemonResult(result)
}

internal fun readCpuLoad(loadavgPath: String = "/proc/loadavg"): String {
    return readCpuLoadWithReason(loadavgPath).value
}

internal data class RamSnapshot(val availMb: Long, val cpuLoad: String, val cpuLoadReason: String = "unknown")

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
    val cpuLoad = readCpuLoadWithReason(loadavgPath)
    return RamSnapshot(availMb = readAvailRamMb(context), cpuLoad = cpuLoad.value, cpuLoadReason = cpuLoad.reason)
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
        "cpu_load_before_reason" to before.cpuLoadReason,
        "cpu_load_after_reason" to after.cpuLoadReason,
        "restored_avail_mb" to if (restored == null || restored.availMb < 0) "unknown" else restored.availMb.toString(),
        "restored_cpu_load" to (restored?.cpuLoad ?: "unknown"),
        "restored_cpu_load_reason" to (restored?.cpuLoadReason ?: "unknown"),
    )
}

object SessionRamReport {
    internal fun log(context: Context?, before: RamSnapshot, after: RamSnapshot, suspendedCount: Int, restored: RamSnapshot?) {
        val fields = ramReportFields(before, after, suspendedCount, restored)
        DiagnosticsLog.log(context, "session.performance", "session-ram-report", fields = fields)
        PerformanceLog.log(context, "session.performance", "session-ram-report", fields = fields)
    }
}
