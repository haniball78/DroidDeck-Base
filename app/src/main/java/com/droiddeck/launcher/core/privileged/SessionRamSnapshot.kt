package com.droiddeck.launcher.core.privileged

import android.content.Context

internal const val RAM_SNAPSHOT_INTERVAL_MS = 60_000L
internal const val RAM_SNAPSHOT_TOP_COUNT = 10
private const val PS_COMMAND = "ps -A -o USER,CMD,RSS"

internal data class PsProcess(val name: String, val rssMb: Long, val system: Boolean)

internal data class PsSnapshot(
    val processes: List<PsProcess>,
    val totalCount: Int,
    val totalRssMb: Long,
)

// App UIDs start at 10000 (u0_a*, u0_i*); anything else is a system
// user. A numeric UID is compared directly so the flag still works if
// the ps USER column ever prints a number instead of a name.
internal fun isSystemUser(user: String): Boolean {
    if (user.startsWith("u0_")) return false
    val uid = user.toIntOrNull()
    if (uid != null) return uid < 10_000
    return true
}

// Parses `ps -A -o USER,CMD,RSS`: USER is the first token, RSS (KB) the
// last, and the name is everything between, so names with spaces survive.
// The header line ends in "RSS", which is not a number, so it is skipped
// like any other unparsable line. Result is the top processes by RSS.
internal fun parsePsSnapshot(output: String): PsSnapshot {
    val parsed = mutableListOf<Triple<String, Long, Boolean>>()
    for (rawLine in output.lineSequence()) {
        val line = rawLine.trim()
        if (line.isEmpty()) continue
        val rssStart = line.lastIndexOf(' ')
        if (rssStart < 0) continue
        val rssKb = line.substring(rssStart + 1).trim().toLongOrNull() ?: continue
        if (rssKb < 0) continue
        val head = line.substring(0, rssStart).trim()
        val nameStart = head.indexOf(' ')
        if (nameStart < 0) continue
        val user = head.substring(0, nameStart).trim()
        val name = head.substring(nameStart + 1).trim()
        if (name.isEmpty()) continue
        parsed.add(Triple(name, rssKb, isSystemUser(user)))
    }
    val totalRssMb = parsed.sumOf { it.second / 1024 }
    val top = parsed.sortedByDescending { it.second }
        .take(RAM_SNAPSHOT_TOP_COUNT)
        .map { PsProcess(name = it.first, rssMb = it.second / 1024, system = it.third) }
    return PsSnapshot(processes = top, totalCount = parsed.size, totalRssMb = totalRssMb)
}

internal fun ramSnapshotFields(snapshot: PsSnapshot): Map<String, String> {
    val fields = mutableMapOf<String, String>()
    fields["count"] = snapshot.totalCount.toString()
    fields["total_rss_mb"] = snapshot.totalRssMb.toString()
    fields["top_count"] = snapshot.processes.size.toString()
    snapshot.processes.forEachIndexed { index, process ->
        fields["p${index}_name"] = process.name
        fields["p${index}_rss_mb"] = process.rssMb.toString()
        fields["p${index}_system"] = process.system.toString()
    }
    return fields
}

internal object SessionRamSnapshot {
    fun sample(context: Context? = null): Boolean {
        if (!PrivilegedBridge.isPrivilegedAvailable()) {
            logSkip(context, "daemon-down")
            return false
        }
        val result = runCatching {
            PrivilegedBridge.executeChecked(PS_COMMAND, 2000)
        }.getOrNull()
        if (result == null || result.exitCode != 0 || result.output.isBlank()) {
            logSkip(context, "ps-failed")
            return false
        }
        val snapshot = parsePsSnapshot(result.output)
        if (snapshot.processes.isEmpty()) {
            logSkip(context, "empty")
            return false
        }
        val fields = ramSnapshotFields(snapshot)
        DiagnosticsLog.log(context, "session.performance", "session-ram-snapshot", fields = fields)
        PerformanceLog.log(context, "session.performance", "session-ram-snapshot", fields = fields)
        return true
    }

    private fun logSkip(context: Context?, reason: String) {
        val fields = mapOf("reason" to reason)
        DiagnosticsLog.log(context, "session.performance", "session-ram-snapshot-skipped", fields = fields)
        PerformanceLog.log(context, "session.performance", "session-ram-snapshot-skipped", fields = fields)
    }
}
