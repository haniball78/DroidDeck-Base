package com.droiddeck.launcher.core.privileged

import android.app.ActivityManager
import android.content.Context
import android.util.Log
import com.droiddeck.launcher.session.SessionPrefs
import java.util.concurrent.atomic.AtomicInteger

// Live Hog Watcher — the session-start hog restriction (HogRestrictionEngine)
// picks its targets once, from yesterday's foreground time, and never looks
// again. This watcher samples the live processes every 90 s while the game
// runs and acts only on a package that keeps hogging for two samples in a
// row, so a one-off sync or update never gets killed.
//
// Thresholds are the user's own: user apps 250 MB RAM or 14% CPU;
// tracking-only system apps 300 MB RAM or 18% CPU. Every other system app
// is excluded, exactly like the session-start restriction. CPU% is the
// share of total CPU capacity (all cores), from /proc deltas over the 90 s
// interval. RAM is VmRSS (resident set) summed over the package's processes.
//
// The action ladder is graduated: restrict first (the same journaled bundle
// as the session-start restriction, restored on exit), then `am kill` when
// it keeps hogging, then `am force-stop` only when the aggressive toggle is
// on and only for user apps. Kills are capped per session with a cooldown
// so a restarting app cannot be killed in a loop.
//
// Sampling runs through the privileged bridge (shell UID 2000) because an
// app UID cannot read other apps' /proc entries. If the bridge is down, or
// the sample cannot be read, the tick is skipped and nothing is touched:
// fail-closed, never guess a hog.

enum class LiveHogClass { EXCLUDED, USER, SYSTEM_TRACKING }

internal data class LiveHogThresholds(val ramMb: Long, val cpuPct: Double)

internal fun liveHogThresholds(kind: LiveHogClass): LiveHogThresholds = when (kind) {
    LiveHogClass.USER -> LiveHogThresholds(250L, 14.0)
    LiveHogClass.SYSTEM_TRACKING -> LiveHogThresholds(300L, 18.0)
    LiveHogClass.EXCLUDED -> LiveHogThresholds(Long.MAX_VALUE, Double.MAX_VALUE)
}

internal fun classifyLiveHog(
    pkg: String,
    userApps: List<String>,
    systemApps: List<String>,
    protected: Set<String>,
    ownPackage: String,
): LiveHogClass {
    if (!AppFreezerPackageJournal.packagePattern.matches(pkg)) return LiveHogClass.EXCLUDED
    if (pkg == ownPackage || pkg in protected) return LiveHogClass.EXCLUDED
    if (pkg in userApps) return LiveHogClass.USER
    if (pkg in systemApps && pkg in HogRestrictionEngine.SAFE_TRACKING_SYSTEM) return LiveHogClass.SYSTEM_TRACKING
    return LiveHogClass.EXCLUDED
}

internal data class LiveHogSample(val pkg: String, val rssMb: Long, val cpuPct: Double?)

internal fun isLiveHog(sample: LiveHogSample, kind: LiveHogClass): Boolean {
    val t = liveHogThresholds(kind)
    if (sample.rssMb >= t.ramMb) return true
    val cpu = sample.cpuPct ?: return false
    return cpu >= t.cpuPct
}

internal enum class LiveHogStage { NONE, RESTRICTED, KILLED, FORCE_STOPPED }

internal enum class LiveHogAction { NONE, RESTRICT, KILL, FORCE_STOP }

// Pure action decision. `hits` is the count of consecutive hog ticks
// including the current one. Restrict at 2 hits, kill when it keeps hogging
// after restriction, force-stop only with the aggressive toggle and never
// for system apps. Kills are throttled by cooldown and a per-session cap.
internal fun liveHogActionFor(
    hits: Int,
    stage: LiveHogStage,
    nowMs: Long,
    lastKillMs: Long,
    killCount: Int,
    aggressive: Boolean,
    isSystemTracking: Boolean,
): LiveHogAction {
    if (hits < LiveHogWatcher.CONFIRM_TICKS) return LiveHogAction.NONE
    return when (stage) {
        LiveHogStage.NONE -> LiveHogAction.RESTRICT
        LiveHogStage.RESTRICTED -> LiveHogAction.KILL
        LiveHogStage.KILLED -> {
            if (aggressive && !isSystemTracking) return LiveHogAction.FORCE_STOP
            if (killCount >= LiveHogWatcher.MAX_KILLS_PER_SESSION) return LiveHogAction.NONE
            if (nowMs - lastKillMs < LiveHogWatcher.KILL_COOLDOWN_MS) return LiveHogAction.NONE
            LiveHogAction.KILL
        }
        LiveHogStage.FORCE_STOPPED -> LiveHogAction.NONE
    }
}

internal data class LiveProcEntry(val pid: Int, val rssKb: Long, val utime: Long, val stime: Long)

internal data class LiveProcSnapshot(val entries: Map<Int, LiveProcEntry>, val totalTicks: Long)

// Parse one shell sample. Lines are "P <pid> <rssKb> <full /proc/<pid>/stat>"
// and one "T cpu <jiffies...>". utime/stime are the 12th/13th tokens after
// the closing paren of the comm field (fields 14/15 of /proc/<pid>/stat).
// Malformed lines are dropped, never crash the parse.
internal fun parseLiveProcSample(output: String): LiveProcSnapshot {
    val entries = mutableMapOf<Int, LiveProcEntry>()
    var totalTicks = 0L
    for (raw in output.lines()) {
        val line = raw.trim()
        when {
            line.startsWith("P ") -> {
                val parts = line.split(Regex("\\s+"), limit = 4)
                if (parts.size < 4) continue
                val pid = parts[1].toIntOrNull() ?: continue
                val rssKb = parts[2].toLongOrNull() ?: continue
                val stat = parts[3]
                val close = stat.lastIndexOf(')')
                if (close < 0) continue
                val rest = stat.substring(close + 1).trim().split(Regex("\\s+"))
                if (rest.size < 13) continue
                val utime = rest[11].toLongOrNull() ?: continue
                val stime = rest[12].toLongOrNull() ?: continue
                entries[pid] = LiveProcEntry(pid, rssKb, utime, stime)
            }
            line.startsWith("T ") -> {
                val fields = line.removePrefix("T ").trim().split(Regex("\\s+"))
                totalTicks = fields.drop(1).sumOf { it.toLongOrNull() ?: 0L }
            }
        }
    }
    return LiveProcSnapshot(entries, totalTicks)
}

internal fun computeLiveCpuPct(prevProcTicks: Long, curProcTicks: Long, prevTotal: Long, curTotal: Long): Double? {
    val deltaTotal = curTotal - prevTotal
    val deltaProc = curProcTicks - prevProcTicks
    if (deltaTotal <= 0 || deltaProc < 0) return null
    return 100.0 * deltaProc / deltaTotal
}

// Sum RSS and CPU ticks per package. CPU% needs the previous snapshot;
// on the first tick it is null and RAM alone decides.
internal fun aggregateLiveSamples(
    snapshot: LiveProcSnapshot,
    prevSnapshot: LiveProcSnapshot?,
    pidToPkg: Map<Int, String>,
): Map<String, LiveHogSample> {
    val rssByPkg = mutableMapOf<String, Long>()
    val ticksByPkg = mutableMapOf<String, Long>()
    val prevTicksByPkg = mutableMapOf<String, Long>()
    for ((pid, entry) in snapshot.entries) {
        val pkg = pidToPkg[pid] ?: continue
        rssByPkg[pkg] = (rssByPkg[pkg] ?: 0L) + entry.rssKb
        ticksByPkg[pkg] = (ticksByPkg[pkg] ?: 0L) + entry.utime + entry.stime
    }
    if (prevSnapshot != null) {
        for ((pid, entry) in prevSnapshot.entries) {
            val pkg = pidToPkg[pid] ?: continue
            prevTicksByPkg[pkg] = (prevTicksByPkg[pkg] ?: 0L) + entry.utime + entry.stime
        }
    }
    val result = mutableMapOf<String, LiveHogSample>()
    for ((pkg, rssKb) in rssByPkg) {
        val cpuPct = if (prevSnapshot == null) null else computeLiveCpuPct(
            prevTicksByPkg[pkg] ?: 0L, ticksByPkg[pkg] ?: 0L,
            prevSnapshot.totalTicks, snapshot.totalTicks,
        )
        result[pkg] = LiveHogSample(pkg, rssKb / 1024L, cpuPct)
    }
    return result
}

// One shell sample: RSS and utime/stime for each pid, plus the /proc/stat
// cpu line for the total. Parsing stays in Kotlin (unit-tested); the shell
// only echoes raw fields. An empty pid list returns an empty command.
internal fun buildLiveProcSampleCmd(pids: List<Int>): String {
    if (pids.isEmpty()) return ""
    return buildString {
        append("for p in ${pids.joinToString(" ")}; do ")
        append("r=\$(grep VmRSS /proc/\$p/status 2>/dev/null) || continue; ")
        append("s=\$(cat /proc/\$p/stat 2>/dev/null) || continue; ")
        append("echo \"P \$p \$(echo \$r | tr -dc '0-9') \$s\"; done; ")
        append("echo \"T \$(head -1 /proc/stat)\"")
    }
}

// Per-package journal keys: JournalMerge keeps the FIRST item per key, so
// one shared key would record only the first restricted package and the
// rest would never be restored. Per-package keys keep every restore.
internal fun liveHogJournalKey(aspect: String, pkg: String): String = "live-hog-$aspect-$pkg"

object LiveHogWatcher {
    private const val TAG = "LiveHogWatcher"

    // User's own settings: sample every 90 s; two consecutive hog ticks
    // before any action.
    const val POLL_MS = 90_000L
    const val CONFIRM_TICKS = 2
    const val KILL_COOLDOWN_MS = 300_000L
    const val MAX_KILLS_PER_SESSION = 3

    private data class LiveHogState(
        val pkg: String,
        val kind: LiveHogClass,
        var hits: Int = 0,
        var stage: LiveHogStage = LiveHogStage.NONE,
        var lastKillMs: Long = 0L,
        var killCount: Int = 0,
    )

    @Volatile private var running = false
    private var thread: Thread? = null
    private val activeGen = AtomicInteger(0)
    private var appContext: Context? = null
    private var isSessionActive: (() -> Boolean)? = null
    private var userApps: List<String> = emptyList()
    private var systemApps: List<String> = emptyList()
    private var protected: Set<String> = emptySet()
    private var ownPackage: String = ""
    private val states = mutableMapOf<String, LiveHogState>()
    private var prevSnapshot: LiveProcSnapshot? = null
    private val restrictedPkgs = mutableListOf<String>()
    private var sampleFailureLogged = false

    @Synchronized
    fun start(context: Context, sessionGen: Int = 0, isSessionActive: () -> Boolean = { true }): String {
        val previousGen = activeGen.get()
        activeGen.set(sessionGen)
        if (!SessionPrefs.advancedMasterEnabled(context)) return "live hog watcher: disabled (master switch off)"
        if (!SessionPrefs.liveHogWatcher(context)) return "live hog watcher: disabled (toggle off)"
        when (decideEngineApply(running, previousGen, sessionGen)) {
            EngineApplyDecision.ALREADY_ACTIVE -> return "live hog watcher: already active (gen=$sessionGen)"
            EngineApplyDecision.RESTORE_FIRST -> {
                if (!restore(context, sessionGen)) return "live hog watcher: aborted (stale generation restore failed)"
                activeGen.set(sessionGen)
            }
            EngineApplyDecision.FRESH -> {}
        }
        if (hasLiveHogJournal(context) && !Tweaks.rollback(context)) {
            return "live hog watcher: aborted (previous journal rollback failed)"
        }
        val lists = HogRestrictionEngine.liveAppLists(context)
        userApps = lists.first
        systemApps = lists.second
        protected = HogRestrictionEngine.liveProtectedPackages(context)
        ownPackage = context.packageName
        states.clear()
        prevSnapshot = null
        restrictedPkgs.clear()
        sampleFailureLogged = false
        appContext = context.applicationContext
        this.isSessionActive = isSessionActive
        running = true
        thread = Thread({ loop() }, "live-hog-watcher").apply { isDaemon = true; start() }
        val summary = "live hog watcher: ON [gen=$sessionGen, poll=90s]"
        Log.i(TAG, summary)
        DiagnosticsLog.log(context, "device.change", "live-hog-watcher-started", fields = mapOf("generation" to sessionGen.toString()))
        return summary
    }

    @Synchronized
    fun stop() {
        running = false
        thread?.interrupt()
        thread = null
        isSessionActive = null
        appContext = null
    }

    @Synchronized
    fun restore(context: Context, sessionGen: Int = 0): Boolean {
        activeGen.set(0)
        stop()
        states.clear()
        prevSnapshot = null
        if (!hasLiveHogJournal(context)) {
            restrictedPkgs.clear()
            return true
        }
        Log.i(TAG, "live hog watcher: restoring background behaviour (gen=$sessionGen)...")
        if (!Tweaks.rollback(context)) {
            Log.w(TAG, "live hog watcher: restore failed, journal kept for retry")
            return false
        }
        restrictedPkgs.clear()
        Log.i(TAG, "live hog watcher: OFF (all apps restored cleanly)")
        DiagnosticsLog.log(context, "device.change", "live-hog-restored")
        return true
    }

    fun isRunning(): Boolean = running

    fun getRestrictedList(): List<String> = restrictedPkgs.toList()

    private fun loop() {
        while (running) {
            try {
                Thread.sleep(POLL_MS)
            } catch (_: InterruptedException) {
                break
            }
            if (!running) break
            try {
                tick()
            } catch (t: Throwable) {
                Log.w(TAG, "live hog watcher tick failed", t)
            }
        }
    }

    private fun tick() {
        val context = appContext ?: return
        if (!running) return
        if (isSessionActive?.invoke() == false) return
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager ?: return
        val procs = try {
            am.runningAppProcesses
        } catch (_: Exception) {
            null
        } ?: return
        val pidToPkg = mutableMapOf<Int, String>()
        for (info in procs) {
            if (info == null) continue
            if (info.importance <= ActivityManager.RunningAppProcessInfo.IMPORTANCE_VISIBLE) continue
            val pkg = info.processName?.substringBefore(":")?.trim() ?: continue
            if (pkg.isEmpty() || pkg == ownPackage) continue
            pidToPkg[info.pid] = pkg
        }
        if (pidToPkg.isEmpty()) return
        val output = exec(context, buildLiveProcSampleCmd(pidToPkg.keys.toList()))
        if (output.isBlank()) {
            logSampleFailureOnce(context)
            return
        }
        val snapshot = parseLiveProcSample(output)
        if (snapshot.entries.isEmpty()) {
            logSampleFailureOnce(context)
            return
        }
        val samples = aggregateLiveSamples(snapshot, prevSnapshot, pidToPkg)
        prevSnapshot = snapshot
        val now = System.currentTimeMillis()
        val aggressive = SessionPrefs.liveHogAggressiveForceStop(context)
        val seen = mutableSetOf<String>()
        for ((pkg, sample) in samples) {
            seen.add(pkg)
            val kind = classifyLiveHog(pkg, userApps, systemApps, protected, ownPackage)
            if (kind == LiveHogClass.EXCLUDED) {
                states.remove(pkg)
                continue
            }
            val state = states.getOrPut(pkg) { LiveHogState(pkg, kind) }
            if (!isLiveHog(sample, kind)) {
                state.hits = 0
                continue
            }
            state.hits += 1
            val action = liveHogActionFor(
                state.hits, state.stage, now, state.lastKillMs, state.killCount,
                aggressive, kind == LiveHogClass.SYSTEM_TRACKING,
            )
            when (action) {
                LiveHogAction.NONE -> {}
                LiveHogAction.RESTRICT -> {
                    if (restrictOne(context, pkg)) {
                        state.stage = LiveHogStage.RESTRICTED
                        restrictedPkgs.add(pkg)
                        DiagnosticsLog.log(
                            context, "device.change", "live-hog-restricted", generation = activeGen.get(),
                            fields = mapOf(
                                "package" to pkg,
                                "rssMb" to sample.rssMb.toString(),
                                "cpuPct" to (sample.cpuPct?.toString() ?: "unknown"),
                            ),
                        )
                    } else {
                        state.hits = 0
                    }
                }
                LiveHogAction.KILL -> {
                    if (killOne(context, pkg)) {
                        state.stage = LiveHogStage.KILLED
                        state.lastKillMs = now
                        state.killCount += 1
                        DiagnosticsLog.log(
                            context, "device.change", "live-hog-killed", generation = activeGen.get(),
                            fields = mapOf(
                                "package" to pkg,
                                "rssMb" to sample.rssMb.toString(),
                                "cpuPct" to (sample.cpuPct?.toString() ?: "unknown"),
                            ),
                        )
                    } else {
                        state.hits = 0
                    }
                }
                LiveHogAction.FORCE_STOP -> {
                    if (forceStopOne(context, pkg)) {
                        state.stage = LiveHogStage.FORCE_STOPPED
                        DiagnosticsLog.log(
                            context, "device.change", "live-hog-force-stopped", generation = activeGen.get(),
                            fields = mapOf(
                                "package" to pkg,
                                "rssMb" to sample.rssMb.toString(),
                                "cpuPct" to (sample.cpuPct?.toString() ?: "unknown"),
                            ),
                        )
                    } else {
                        state.hits = 0
                    }
                }
            }
        }
        // Drop state for packages that disappeared; their restriction (if any)
        // stays journaled and is restored on exit.
        states.keys.retainAll(seen)
    }

    // Restrict one package with the same snapshot/journal/apply shape as the
    // session-start restriction, but under per-package journal keys so every
    // package's prior state is restored on exit.
    private fun restrictOne(context: Context, pkg: String): Boolean {
        val snapshots = parseHogSnapshot(exec(context, buildHogSnapshotCmd(listOf(pkg))), listOf(pkg))
        val snapshot = snapshots[pkg] ?: return false
        val target = HogTarget(pkg, snapshot.appOps, snapshot.bucket, snapshot.whitelisted)
        if (!target.needsRestriction) return true
        val items = mutableListOf<JournalItem>()
        if (target.changeAppOps && !Tweaks.hasKey(context, liveHogJournalKey("appops", pkg))) {
            items.add(
                JournalItem(
                    key = liveHogJournalKey("appops", pkg),
                    original = "0",
                    restoreCmd = buildHogAppOpsRestoreCmd(listOf(target)),
                    verifyCmd = buildHogAppOpsVerifyCmd(listOf(pkg)),
                    verifyMode = JournalVerifyMode.VALUE,
                    persistent = true,
                    generation = activeGen.get(),
                ),
            )
        }
        if (target.changeBucket && !Tweaks.hasKey(context, liveHogJournalKey("buckets", pkg))) {
            items.add(
                JournalItem(
                    key = liveHogJournalKey("buckets", pkg),
                    original = "0",
                    restoreCmd = buildHogBucketRestoreCmd(listOf(target)),
                    verifyCmd = buildHogBucketVerifyCmd(listOf(pkg)),
                    verifyMode = JournalVerifyMode.VALUE,
                    persistent = true,
                    generation = activeGen.get(),
                ),
            )
        }
        if (target.changeWhitelist && !Tweaks.hasKey(context, liveHogJournalKey("whitelist", pkg))) {
            items.add(
                JournalItem(
                    key = liveHogJournalKey("whitelist", pkg),
                    original = "0",
                    restoreCmd = buildHogWhitelistRestoreCmd(listOf(target)),
                    verifyCmd = buildHogWhitelistVerifyCmd(listOf(pkg)),
                    verifyMode = JournalVerifyMode.VALUE,
                    persistent = true,
                    generation = activeGen.get(),
                ),
            )
        }
        if (items.isNotEmpty() && !Tweaks.recordItems(context, items)) return false
        exec(context, buildHogApplyCmd(listOf(target)))
        return true
    }

    private fun killOne(context: Context, pkg: String): Boolean {
        return execResult(context, "am kill $pkg 2>/dev/null").exitCode == 0
    }

    private fun forceStopOne(context: Context, pkg: String): Boolean {
        return execResult(context, "am force-stop $pkg 2>/dev/null").exitCode == 0
    }

    private fun hasLiveHogJournal(context: Context): Boolean {
        return Tweaks.journalSnapshot(context).any { it.key.startsWith("live-hog-") }
    }

    private fun logSampleFailureOnce(context: Context) {
        if (sampleFailureLogged) return
        sampleFailureLogged = true
        Log.w(TAG, "live hog watcher: sample failed, tick skipped (bridge down or unreadable /proc)")
        DiagnosticsLog.log(context, "device.change", "live-hog-sample-failed")
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

    private fun execResult(context: Context, cmd: String): PrivilegedResult {
        return try {
            PrivilegedBridge.ensureSessionToken(context)
            PrivilegedBridge.execute(cmd)
        } catch (t: Throwable) {
            Log.w(TAG, "exec error: $cmd", t)
            PrivilegedResult(-1, "", PrivilegedMode.NONE)
        }
    }
}
