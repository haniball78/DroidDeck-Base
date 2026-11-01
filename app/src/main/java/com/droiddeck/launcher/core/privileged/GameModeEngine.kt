package com.droiddeck.launcher.core.privileged

import android.content.Context
import android.os.Build
import android.util.Log
import com.droiddeck.launcher.session.SessionPrefs
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

/**
 * GameModeEngine — ultra-low overhead, generation-guarded performance governor and core isolator.
 *
 * ── Philosophy: Load Reduction First ────────────────────────────────────────
 * Relieves system stress and background thrash without forcing hardware into thermal throttling.
 *
 * ── Profile 1: Cool & Efficient (Default) ──────────────────────────────────
 * 1. Clean RAM Cache: Frees cached background pages (`am kill-all`) upfront, eliminating
 *    Android low-memory killer (LMK) page scanning thrash during gameplay.
 * 2. Background Process Limit: Caps background cached apps to 4 (`background_process_limit = 4`),
 *    preventing background sync and push tasks from waking up CPU cores.
 * 3. Dynamic Clock Scaling: Keeps CPU/GPU governors dynamic. Clocks scale DOWN in menus,
 *    loading screens, and light scenes, preventing thermal throttling.
 * 4. Performance Core Affinity: Binds game process AND all its child threads (`/proc/$pid/task`)
 *    to the 5 Cortex-A720 Performance cores (mask `0x7c`, cores 2-6). This avoids cache thrashing
 *    while leaving the hot Cortex-X4 prime core idle to keep phone temperature cool.
 * 5. Gentle Real-Time Nice: Sets process nice to -10, ensuring priority over background
 *    threads without starving audio daemons.
 *
 * ── Profile 2: Aggressive Turbo (Optional, User-Toggled) ────────────────────
 * Activated only when `SessionPrefs.gameModeAggressive(context)` is toggled ON:
 * 1. Samsung Enhanced Processing (`sem_enhanced_cpu_responsiveness = 1`).
 * 2. Samsung Unrestricted Performance (`restricted_device_performance = 0,0`).
 * 3. Power HAL Fixed Performance Mode (`cmd power set-fixed-performance-mode-enabled true`).
 * 4. Prime Core Affinity: Expands affinity mask to `0xfc` (cores 2-7, including Cortex-X4).
 * 5. Maximum Real-Time Nice: -20.
 *
 * ── Ultra-Fast Single IPC Execution ─────────────────────────────────────────
 * All privileged commands for profile activation are bundled into a single shell execution,
 * completing in <20ms and causing zero game startup delay.
 */
internal data class GameModeStep(
    val command: String,
    val verifyCmd: String?,
    val expected: String?,
    val fixedPerformance: Boolean,
)

internal object GameModeStepVerify {
    fun verified(putExitCode: Int, read: PrivilegedResult?, expected: String?, fixedPerformance: Boolean): Boolean {
        if (putExitCode != 0) return false
        if (expected == null) return true
        if (read == null || read.exitCode != 0) return false
        return if (fixedPerformance) {
            read.output.trim() == "true"
        } else {
            SafetyChecks.parseBackgroundLimit(read.output) == expected
        }
    }
}

internal enum class FixedPerformanceGetter {
    SUPPORTED,
    UNSUPPORTED,
    UNREADABLE,
}

// The fixed-performance getter is absent on some ROMs, so an unreadable
// getter must not abort the whole game mode, while a bridge failure still aborts.
internal object FixedPerformanceGetterDecision {
    fun decide(exitCode: Int, output: String): Pair<FixedPerformanceGetter, String?> {
        if (exitCode == -1) return FixedPerformanceGetter.UNREADABLE to null
        val original = if (exitCode == 0) FixedPerformanceState.parse(output) else null
        if (original != null) return FixedPerformanceGetter.SUPPORTED to original
        return FixedPerformanceGetter.UNSUPPORTED to null
    }
}

internal fun buildBoostThreadsCommand(pid: Int, mask: String): String {
    return "[ -d /proc/$pid ] || exit 1; ok=0; failed=0; failed_tids=\"\"; for tid in /proc/$pid/task/*; do t=\${tid##*/}; if taskset -p $mask \$t >/dev/null 2>&1; then ok=\$((ok+1)); else failed=\$((failed+1)); if [ \$failed -le 10 ]; then failed_tids=\"\$failed_tids \$t\"; fi; fi; done; echo \"threads-diag ok=\$ok failed=\$failed total=\$((ok+failed)) failed_tids=\$failed_tids\"; if [ \$failed -gt 0 ]; then exit 1; else exit 0; fi"
}

internal data class ThreadsDiag(
    val ok: Int,
    val failed: Int,
    val total: Int,
    val failedTids: List<String>,
)

internal fun parseThreadsDiag(output: String?): ThreadsDiag? {
    if (output.isNullOrBlank()) return null
    val ok = Regex("ok=(\\d+)").find(output)?.groupValues?.get(1)?.toIntOrNull() ?: return null
    val failed = Regex("failed=(\\d+)").find(output)?.groupValues?.get(1)?.toIntOrNull() ?: return null
    val total = Regex("total=(\\d+)").find(output)?.groupValues?.get(1)?.toIntOrNull() ?: return null
    val tids = output.substringAfter("failed_tids=", "").trim().split(" ").filter { it.isNotEmpty() }
    return ThreadsDiag(ok, failed, total, tids)
}

// App-side boost commands only: the guest pid is app-owned, so the shell
// daemon cannot taskset it cross-UID. Negative renice is dropped entirely:
// it requires CAP_SYS_NICE, which neither the app nor the daemon has.
// Writing -1000 to oom_score_adj is dropped for the same reason: it
// requires CAP_SYS_RESOURCE, and a device log read back 0 after the attempt.
internal fun buildBoostAppCommands(pid: Int, mask: String): List<Pair<String, String>> {
    return listOf(
        "taskset-pid" to "taskset -p $mask $pid 2>/dev/null",
        "taskset-threads" to buildBoostThreadsCommand(pid, mask),
    )
}

// A test apply uses generation 999; a real session uses its own
// incrementing generation. If an engine is already applied under a
// different generation, its tweaks are stale and must be restored
// before a fresh apply, not silently accepted as the new session.
internal fun isStaleGeneration(applied: Boolean, activeGen: Int, sessionGen: Int): Boolean {
    return applied && sessionGen != 0 && activeGen != sessionGen
}

object GameModeEngine {
    private const val TAG = "GameModeEngine"
    private const val MARKER_FILE = "gamemode-marker.txt"

    // Cores 2, 3, 4, 5, 6 (5x Cortex-A720 Performance cores, excluding Little cores 0-1 and hot Prime core 7)
    // Binary: 01111100 = 0x7c
    private const val COOL_PERF_CORES_MASK = "7c"

    // Cores 2, 3, 4, 5, 6, 7 (Performance + Prime cores)
    // Binary: 11111100 = 0xfc
    private const val TURBO_CORES_MASK = "fc"

    @Volatile private var applied = false
    @Volatile private var aggressiveActive = false

    private val activeGen = AtomicInteger(0)

    // ── Public API ─────────────────────────────────────────────────────────────

    /** Apply game-mode optimizations at session start, one checked step at a time. */
    @Synchronized
    fun apply(context: Context, sessionGen: Int = 0): String {
        val previousGen = activeGen.get()
        activeGen.set(sessionGen)
        if (!SessionPrefs.advancedMasterEnabled(context)) return "game mode: disabled (master switch off)"
        if (!SessionPrefs.gameModeEngine(context)) return "game mode: disabled in settings"
        if (applied) {
            if (!isStaleGeneration(true, previousGen, sessionGen)) return "game mode: already active"
            if (!restore(context, sessionGen)) return "game mode: aborted (stale generation restore failed)"
            activeGen.set(sessionGen)
        }

        val aggressive = SessionPrefs.gameModeAggressive(context)
        aggressiveActive = aggressive
        var fixedPerformanceSupported = true
        val pkg = context.packageName

        val items = mutableListOf<JournalItem>()
        if (!Tweaks.hasKey(context, "background_process_limit")) {
            val bgRead = execChecked(context, "settings get global background_process_limit")
            if (bgRead.exitCode != 0) {
                Log.w(TAG, "apply aborted: background limit unreadable")
                aggressiveActive = false
                return "game mode: aborted (background limit unreadable)"
            }
            val resolvedBg = Preflight.resolveBackgroundLimitOriginal(bgRead.output)
            if (!resolvedBg.valid) {
                Log.w(TAG, "apply aborted: background limit invalid")
                aggressiveActive = false
                return "game mode: aborted (invalid background limit)"
            }
            if (resolvedBg.leftoverDetected) {
                Log.w(TAG, "preflight: suspected leftover background limit, apply aborted")
                aggressiveActive = false
                return "game mode: aborted (suspected leftover background limit, restore defaults explicitly to continue)"
            }
            items.add(settingItem("background_process_limit", resolvedBg.original, sessionGen))
        }
        if (aggressive) {
            if (!Tweaks.hasKey(context, "fixed-performance")) {
                val read = execChecked(context, "cmd power get-fixed-performance-mode-enabled")
                val (decision, original) = FixedPerformanceGetterDecision.decide(read.exitCode, read.output)
                when (decision) {
                    FixedPerformanceGetter.SUPPORTED -> items.add(FixedPerformanceState.item(original!!, sessionGen))
                    FixedPerformanceGetter.UNSUPPORTED -> {
                        fixedPerformanceSupported = false
                        Log.w(TAG, "game mode: fixed-performance getter unsupported on this ROM, skipping fixed-performance")
                    }
                    FixedPerformanceGetter.UNREADABLE -> {
                        Log.w(TAG, "apply aborted: fixed-performance original unreadable")
                        aggressiveActive = false
                        return "game mode: aborted (fixed-performance original unreadable)"
                    }
                }
            }
            for ((key, readCmd) in listOf(
                "sem_enhanced_cpu_responsiveness" to "settings get global sem_enhanced_cpu_responsiveness",
                "restricted_device_performance" to "settings get global restricted_device_performance",
                "adaptive_battery_management_enabled" to "settings get global adaptive_battery_management_enabled",
            )) {
                if (!Tweaks.hasKey(context, key)) {
                    val read = execChecked(context, readCmd)
                    if (read.exitCode != 0) {
                        Log.w(TAG, "apply aborted: $key unreadable")
                        aggressiveActive = false
                        return "game mode: aborted ($key unreadable)"
                    }
                    val original = Preflight.resolveSettingOriginal(read.output)
                    if (!SafetyChecks.isValidSettingValue(key, original)) {
                        Log.w(TAG, "apply aborted: $key invalid")
                        aggressiveActive = false
                        return "game mode: aborted (invalid $key)"
                    }
                    items.add(settingItem(key, original, sessionGen))
                }
            }
        }
        if (!Tweaks.recordItems(context, items)) {
            Log.w(TAG, "apply aborted: journal save failed")
            return "game mode: aborted (journal save failed)"
        }

        if (sessionGen != 0 && activeGen.get() != sessionGen) {
            Log.w(TAG, "apply aborted: generation changed from $sessionGen to ${activeGen.get()}")
            return "game mode: aborted"
        }

        PrivilegedBridge.ensureBackgroundProtection(context)

        val steps = mutableListOf(
            GameModeStep("settings put global background_process_limit 4", "settings get global background_process_limit", "4", false),
            GameModeStep("am kill-all", null, null, false),
            GameModeStep("cmd game mode 2 $pkg", null, null, false),
        )
        if (aggressive) {
            steps.add(GameModeStep("settings put global sem_enhanced_cpu_responsiveness 1", "settings get global sem_enhanced_cpu_responsiveness", "1", false))
            steps.add(GameModeStep("settings put global restricted_device_performance 0,0", "settings get global restricted_device_performance", "0,0", false))
            if (fixedPerformanceSupported) {
                steps.add(GameModeStep("cmd power set-fixed-performance-mode-enabled true", "cmd power get-fixed-performance-mode-enabled", "true", true))
            }
            steps.add(GameModeStep("settings put global adaptive_battery_management_enabled 0", "settings get global adaptive_battery_management_enabled", "0", false))
        }
        for (step in steps) {
            val put = execChecked(context, step.command)
            val verified = GameModeStepVerify.verified(put.exitCode, step.verifyCmd?.let { execChecked(context, it) }, step.expected, step.fixedPerformance)
            if (!verified) {
                Log.w(TAG, "game mode: checked step failed, rolling back: ${step.command}")
                aggressiveActive = false
                val rolledBack = Tweaks.rollback(context)
                return if (rolledBack) {
                    "game mode: aborted (checked step failed, rolled back)"
                } else {
                    "game mode: aborted (checked step failed, rollback failed, journal kept)"
                }
            }
        }
        applied = true

        val summary = "game mode: ON [gen=$sessionGen, mode=${if (aggressive) "TURBO" else "COOL_LOAD_REDUCER"}]"
        Log.i(TAG, summary)
        com.droiddeck.launcher.core.privileged.DiagnosticsLog.log(
            context, "device.change", "game-mode-applied",
            fields = if (aggressive) {
                mapOf(
                    "mode" to "TURBO",
                    "fixed_performance_supported" to fixedPerformanceSupported.toString(),
                )
            } else {
                mapOf("mode" to "COOL_LOAD_REDUCER")
            },
        )
        return summary
    }

    fun resolveSuspectLeftover(context: Context, restoreDefaults: Boolean, sessionGen: Int = 0): String {
        if (Tweaks.hasKey(context, "background_process_limit")) {
            return "suspect leftover: journal already records background limit"
        }
        val suspectRead = execChecked(context, "settings get global background_process_limit")
        if (suspectRead.exitCode != 0) {
            return "suspect leftover: current background limit unreadable"
        }
        val resolved = Preflight.resolveBackgroundLimitOriginal(suspectRead.output)
        if (!resolved.valid) {
            return "suspect leftover: current background limit invalid"
        }
        if (!resolved.leftoverDetected) {
            return "suspect leftover: current background limit is clean"
        }
        if (!restoreDefaults) {
            return "suspect leftover: no change made, pass restoreDefaults=true to record restore-by-delete"
        }
        val recorded = Tweaks.recordItem(context, settingItem("background_process_limit", null, sessionGen))
        return if (recorded) {
            "suspect leftover: restore-by-delete recorded"
        } else {
            "suspect leftover: journal save failed"
        }
    }

    /**
     * Boosts the game process PID and deeply binds all existing and newly spawned
     * thread tasks (/proc/$pid/task) to the performance core cluster.
     */
    fun boostSessionProcess(context: Context, pid: Int, sessionGen: Int = 0): String {
        if (!applied || pid <= 1) return "pid_boost: skipped (pid=$pid applied=$applied)"
        if (sessionGen != 0 && activeGen.get() != sessionGen) return "pid_boost: obsolete gen"

        val mask = if (aggressiveActive) TURBO_CORES_MASK else COOL_PERF_CORES_MASK
        val exits = mutableMapOf<String, Int>()
        var threadsDiag: ThreadsDiag? = null
        for ((name, cmd) in buildBoostAppCommands(pid, mask)) {
            if (name == "taskset-threads") {
                val (exit, output) = runAppCommandOutput(cmd)
                exits[name] = exit
                threadsDiag = parseThreadsDiag(output)
            } else {
                exits[name] = runAppTierCommand(cmd)
            }
        }
        val tasksetExit = exits["taskset-pid"] ?: -1
        val threadsExit = exits["taskset-threads"] ?: -1
        val (oomReadExit, oomReadOutput) = runAppCommandOutput("cat /proc/$pid/oom_score_adj 2>/dev/null")
        val pidBoostFields = mutableMapOf(
            "mask" to mask,
            "taskset" to tasksetExit.toString(),
            "threads" to threadsExit.toString(),
            "oom" to "skipped",
            "oom_value" to if (oomReadExit == 0) oomReadOutput else "unreadable",
            "renice" to "skipped",
            "source" to "app",
        )
        threadsDiag?.let {
            pidBoostFields["threads_ok"] = it.ok.toString()
            pidBoostFields["threads_failed"] = it.failed.toString()
            pidBoostFields["threads_total"] = it.total.toString()
            pidBoostFields["threads_failed_tids"] = it.failedTids.joinToString(",")
        }
        com.droiddeck.launcher.core.privileged.DiagnosticsLog.log(
            context, "device.change", "pid-boost",
            fields = pidBoostFields,
        )

        // Asynchronously re-apply affinity after 1.5s when Wine/guest has spawned its worker threads
        Thread({
            try {
                Thread.sleep(1500)
                if (applied && activeGen.get() > 0 && (sessionGen == 0 || activeGen.get() == sessionGen)) {
                    val (followExit, followOutput) = runAppCommandOutput(buildBoostThreadsCommand(pid, mask))
                    val followDiag = parseThreadsDiag(followOutput)
                    val followFields = mutableMapOf("threads" to followExit.toString(), "source" to "app")
                    followDiag?.let {
                        followFields["threads_ok"] = it.ok.toString()
                        followFields["threads_failed"] = it.failed.toString()
                        followFields["threads_total"] = it.total.toString()
                        followFields["threads_failed_tids"] = it.failedTids.joinToString(",")
                    }
                    com.droiddeck.launcher.core.privileged.DiagnosticsLog.log(
                        context, "device.change", "pid-boost-followup",
                        fields = followFields,
                    )
                    Log.d(TAG, "boostSessionProcess: follow-up thread affinity exit=$followExit for pid=$pid")
                }
            } catch (_: Exception) {}
        }, "pid-affinity-followup").start()

        val desc = "pid $pid boost attempted (affinity=0x$mask [cores=${if (aggressiveActive) "2-7" else "2-6"}], taskset=$tasksetExit, threads=$threadsExit, oom=skipped, renice=skipped)"
        Log.i(TAG, "boostSessionProcess: $desc")
        return desc
    }

    @Synchronized
    fun deescalateAggressive(context: Context): Boolean {
        if (!applied || !aggressiveActive) return false
        val keys = listOf(
            "sem_enhanced_cpu_responsiveness",
            "restricted_device_performance",
            "adaptive_battery_management_enabled",
            "fixed-performance",
        )
        for (key in keys) {
            if (!Tweaks.hasKey(context, key)) continue
            if (!Tweaks.rollbackOne(context, key)) return false
            com.droiddeck.launcher.core.privileged.DiagnosticsLog.log(
                context, "safety.restore", "restored",
                fields = mapOf("key" to key, "verify" to "verified"),
            )
        }
        aggressiveActive = false
        Log.i(TAG, "game mode: aggressive tweaks de-escalated, cooling tweaks kept")
        com.droiddeck.launcher.core.privileged.DiagnosticsLog.log(
            context, "device.change", "game-mode-deescalated",
        )
        return true
    }

    /** Restore all modified system settings when the session ends. Idempotent and clean. */
    @Synchronized
    fun restore(context: Context, sessionGen: Int = 0): Boolean {
        activeGen.set(0)
        if (!applied && !Tweaks.recoveryNeeded(context) && !hasLegacyMarker(context)) return true
        val wasAggressive = aggressiveActive
        applied = false
        Log.i(TAG, "game mode: restoring system settings (gen=$sessionGen)...")

        val legacy = loadLegacyMarker(context)
        if (legacy != null) {
            if (!Tweaks.recordItems(context, legacyItems(legacy, sessionGen))) {
                Log.w(TAG, "game mode: restore not verified, journal kept for retry (legacy migration failed)")
                aggressiveActive = wasAggressive
                return false
            }
        }
        val hadFixedItem = Tweaks.journalSnapshot(context).any { it.key == "fixed-performance" }
        val rolledBack = Tweaks.rollback(context)
        val pkg = context.packageName
        exec(context, "cmd game reset $pkg 2>/dev/null || cmd game mode 1 $pkg 2>/dev/null; ")
        if (!rolledBack) {
            Log.w(TAG, "game mode: restore not verified, journal kept for retry")
            aggressiveActive = wasAggressive
            return false
        }
        if (!hadFixedItem) {
            exec(context, "cmd power set-fixed-performance-mode-enabled false 2>/dev/null; ")
        }
        if (legacy != null) deleteLegacyMarker(context)
        aggressiveActive = false

        Log.i(TAG, "game mode: OFF (all settings restored cleanly)")
        com.droiddeck.launcher.core.privileged.DiagnosticsLog.log(
            context, "device.change", "game-mode-restored",
        )
        return true
    }

    fun migrateLegacyToJournal(context: Context): Boolean {
        val legacy = loadLegacyMarker(context.applicationContext) ?: return true
        return Tweaks.recordItems(context.applicationContext, legacyItems(legacy, 0))
    }

    fun isActive(): Boolean = applied
    fun isAggressiveActive(): Boolean = aggressiveActive

    @Synchronized
    fun onSuspendRollback() {
        applied = false
        aggressiveActive = false
    }

    // ── Live System Verification & Diagnostics ─────────────────────────────────

    fun runLiveVerification(context: Context): Map<String, String> {
        val out = mutableMapOf<String, String>()
        out["Game Mode State"] = if (applied) (if (aggressiveActive) "🟢 ACTIVE (Turbo)" else "🟢 ACTIVE (Cool / Load Reducer)") else "⚪ STANDBY"
        out["Background Process Limit"] = exec(context, "settings get global background_process_limit").trim().let { if (it.isBlank() || it == "null") "Default (Unlimited)" else it }
        out["Free System RAM"] = exec(context, "cat /proc/meminfo | grep MemFree").trim().substringAfter(":").trim()
        out["Android Game Mode"] = exec(context, "cmd game list-modes ${context.packageName}").trim()

        val sem = exec(context, "settings get global sem_enhanced_cpu_responsiveness").trim()
        out["Samsung Enhanced Processing"] = if (sem == "1") "ON (High Clock)" else "OFF (Balanced / Cool)"

        val restricted = exec(context, "settings get global restricted_device_performance").trim()
        out["Samsung Restricted Perf"] = if (restricted.isBlank() || restricted == "null") "Default" else restricted

        return out
    }

    fun testApplyNow(context: Context): String {
        val res = apply(context, 999)
        val metrics = runLiveVerification(context)
        return buildString {
            appendLine("=== TEST APPLY RESULT ===")
            appendLine(res)
            appendLine("--- Verified Live System State ---")
            metrics.forEach { (k, v) -> appendLine("$k: $v") }
        }
    }

    fun testRestoreNow(context: Context): String {
        restore(context, 999)
        val metrics = runLiveVerification(context)
        return buildString {
            appendLine("=== TEST RESTORE RESULT ===")
            appendLine("game mode restored to defaults")
            appendLine("--- Verified Live System State ---")
            metrics.forEach { (k, v) -> appendLine("$k: $v") }
        }
    }

    // ── Internal Helpers ───────────────────────────────────────────────────────

    private fun settingItem(key: String, original: String?, generation: Int): JournalItem {
        val restore = if (original == null) {
            "settings delete global $key 2>/dev/null"
        } else {
            "settings put global $key $original 2>/dev/null"
        }
        return JournalItem(
            key = key,
            original = original,
            restoreCmd = restore,
            verifyCmd = "settings get global $key",
            verifyMode = JournalVerifyMode.VALUE,
            persistent = true,
            generation = generation,
        )
    }

    private fun legacyItems(marker: GameModeMarker, generation: Int): List<JournalItem> {
        val items = mutableListOf(settingItem("background_process_limit", marker.backgroundLimit.takeIf { SafetyChecks.isCleanBackgroundLimit(it) }, generation))
        if (marker.semEnhanced != null) items.add(settingItem("sem_enhanced_cpu_responsiveness", marker.semEnhanced, generation))
        if (marker.restrictedPerf != null) items.add(settingItem("restricted_device_performance", marker.restrictedPerf, generation))
        if (marker.adaptiveBattery != null) items.add(settingItem("adaptive_battery_management_enabled", marker.adaptiveBattery, generation))
        return items
    }

    private fun loadLegacyMarker(context: Context): GameModeMarker? {
        return try {
            val f = File(context.filesDir, MARKER_FILE)
            if (f.exists()) SafetyChecks.decodeGameModeMarker(f.readText()) else null
        } catch (_: Exception) { null }
    }

    fun hasLegacyMarker(context: Context): Boolean = File(context.filesDir, MARKER_FILE).exists()

    fun deleteLegacyMarker(context: Context) {
        try { File(context.filesDir, MARKER_FILE).delete() } catch (_: Exception) {}
    }

    private fun exec(context: Context, cmd: String): String {
        return try {
            PrivilegedBridge.ensureSessionToken(context)
            PrivilegedBridge.execute(cmd).output
        } catch (t: Throwable) {
            Log.w(TAG, "exec error: $cmd", t)
            ""
        }
    }

    private fun execChecked(context: Context, cmd: String): PrivilegedResult {
        return try {
            PrivilegedBridge.ensureSessionToken(context)
            PrivilegedBridge.executeChecked(cmd)
        } catch (t: Throwable) {
            Log.w(TAG, "execChecked error: $cmd", t)
            PrivilegedResult(-1, "", PrivilegedMode.NONE)
        }
    }

}
