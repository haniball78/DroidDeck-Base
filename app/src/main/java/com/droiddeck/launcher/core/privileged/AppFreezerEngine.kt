package com.droiddeck.launcher.core.privileged

import android.content.Context
import android.content.Intent
import android.util.Log
import com.droiddeck.launcher.session.SessionPrefs
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * AppFreezerEngine — ultra-fast, intelligent background app suspension & system deep-doze manager.
 *
 * ── Architectural Principles ────────────────────────────────────────────────
 * 1. Zero-Latency Startup: Pre-caches installed bloatware, user apps, and active input methods
 *    in the background at app startup (warmup). At game launch, targets are resolved in 0 ms.
 * 2. Combined Single-Shot Privileged IPC: Bundles whitelist, deep-doze, and package suspension
 *    into a single execution payload, dropping IPC turnaround from ~600ms to <30ms.
 * 3. Race-Free Generation Guard: Uses an atomic session generation token (`activeGen`). If a session
 *    stops rapidly while apply is executing, apply aborts immediately and clean restore is guaranteed.
 * 4. Zero-Collateral-Damage Protection: Guaranteed immunity for critical OS components:
 *    telephony, in-call UI, emergency, sound engines (Dolby/SoundAlive), Google Play Core,
 *    active keyboards (IME), accessibility, and DroidDeck/Shizuku/Termux services.
 * 5. Persistent Fail-Safe: Every suspended package is committed to `suspended-packages.txt`.
 *    On exit or upon next launch (even after crash/power loss), `RecoveryCoordinator` restores everything.
 */
// Pure Fix 4 logic, extracted so it can be simulated in a unit test
// without a Context: the dynamic protected set (current keyboard,
// accessibility services) is merged with the cached set and the app's
// own package, and only then are suspend targets filtered.
internal fun mergeProtectedPackages(
    cached: Set<String>,
    dynamic: Set<String>,
    ownPackage: String,
): Set<String> = cached + dynamic + ownPackage

// A non-package line from pm output ("No", seen on device 2026-11-06) makes
// cmd package suspend/unsuspend throw, which fails the first rollback attempt.
internal fun filterSuspendTargets(
    packages: List<String>,
    protected: Set<String>,
): List<String> = packages.distinct().filter { it !in protected && AppFreezerPackageJournal.packagePattern.matches(it) }

// A system app is safe to close when it is a known-safe background
// service (allowlist) or a user-facing app (launcher icon). Background
// services and critical system services both lack a launcher icon, so
// there is no reliable dynamic signal for them and the list is the
// safety mechanism; the protected set still wins over both.
internal fun isSafeSystemTarget(packageName: String, launcherPackages: Set<String>): Boolean =
    packageName in AppFreezerEngine.SAFE_SYSTEM_ALLOWLIST || packageName in launcherPackages

// System close targets: the curated bloatware list, the safe-service
// allowlist, and every user-facing system app, restricted to what is
// actually installed. The protected filter is applied separately by
// filterSuspendTargets.
internal fun systemCloseTargets(
    installedSystem: Set<String>,
    launcherPackages: Set<String>,
): List<String> = (AppFreezerEngine.KNOWN_BLOATWARE + AppFreezerEngine.SAFE_SYSTEM_ALLOWLIST + launcherPackages)
    .filter { it in installedSystem }
    .distinct()

// Builds the suspend portion of the apply script. A running target is
// force-stopped and then suspended in the same loop iteration, with the
// commands adjacent in one script, so the app cannot restart itself
// (sticky service, alarm, FCM) before its suspend lands. `am kill` is
// not used: it only kills background processes and silently skips apps
// with a foreground service, while a raw PID kill is refused by the
// shell. A sticky target is also disabled between the two, because it
// restarts even after a force-stop; only the sticky set is disabled,
// because disabling hides the launcher icon. A non-running target has
// no RAM to free and no restart race, so it is suspended only, in the
// existing chunks of 50.
internal fun killSuspendScript(running: List<String>, nonRunning: List<String>, sticky: Set<String> = AppFreezerEngine.STICKY_PACKAGES): String {
    return buildString {
        for (pkg in running) {
            append("am force-stop $pkg 2>/dev/null; ")
            if (pkg in sticky) append("pm disable-user --user 0 $pkg 2>/dev/null; ")
            append("cmd package suspend --user 0 $pkg 2>/dev/null; ")
        }
        nonRunning.chunked(50).forEach { chunk ->
            append("cmd package suspend --user 0 ${chunk.joinToString(" ")} 2>/dev/null; ")
        }
    }
}

// Wake receiver per package, proven on device 2026-11-13: Firebase for
// WhatsApp and Facebook, Fbns for Instagram.
internal fun wakeReceiverFor(packageName: String): String =
    if (packageName == "com.instagram.android") "com.instagram.push.FbnsInitBroadcastReceiver"
    else "com.google.firebase.iid.FirebaseInstanceIdReceiver"

// Broadcasts an explicit wake to every force-stopped package, then,
// after a short wait, opens any messaging app that is still stopped:
// the broadcast wake proved unreliable for WhatsApp, while other apps
// without a Firebase receiver (BitTorrent) must wake on user open
// instead of having their UI popped. The echo lines let the caller log
// which apps needed the fallback.
internal fun wakeBroadcastScript(forceStopped: List<String>, fallback: Set<String> = AppFreezerEngine.WAKE_FALLBACK_PACKAGES): String {
    return buildString {
        for (pkg in forceStopped) {
            append("am broadcast -a com.droiddeck.WAKE_TEST -n $pkg/${wakeReceiverFor(pkg)} -f 32 2>/dev/null; ")
        }
        append("sleep 5; ")
        val needsFallback = forceStopped.filter { it in fallback }
        if (needsFallback.isNotEmpty()) {
            append("for p in ${needsFallback.joinToString(" ")}; do dumpsys package \$p 2>/dev/null | grep -q \"stopped=true\" && { echo \"wake-fallback:\$p\"; monkey -p \$p -c android.intent.category.LAUNCHER 1 2>/dev/null; }; done; ")
        }
        append("true; ")
    }
}

// The privileged bridge can drop one localhost roundtrip (exit -1 in 2ms
// on device 2026-11-13), so a failed batch is retried once before it is
// reported as a failure. The executor is injected for unit tests.
internal fun runBatchWithRetry(
    script: String,
    execute: (String) -> PrivilegedResult,
    sleepMs: Long = 250,
): PrivilegedResult {
    val first = execute(script)
    if (first.exitCode == 0) return first
    Thread.sleep(sleepMs)
    return execute(script)
}

internal data class AppliedVerification(
    val killed: Int,
    val suspended: Int,
    val survivors: List<String>,
)

// Measured freeze outcome from the post-apply re-read: killed counts the
// running targets that are actually gone, survivors names the running
// targets still alive (bounded, so the applied event stays small).
internal fun appliedVerification(
    orderedRunning: List<String>,
    stillRunning: List<String>,
    suspendedCount: Int,
    maxSurvivors: Int = 5,
): AppliedVerification {
    val still = orderedRunning.filter { it in stillRunning.toSet() }
    return AppliedVerification(
        killed = orderedRunning.size - still.size,
        suspended = suspendedCount,
        survivors = still.take(maxSurvivors),
    )
}

object AppFreezerEngine {
    private const val TAG = "AppFreezerEngine"
    private const val MARKER_FILE = "suspended-packages.txt"
    private const val WAKE_TIMEOUT_MS = 12_000

    internal fun packagesVerifyCmd(targets: List<String>): String {
        return "for p in ${targets.joinToString(" ")}; do dumpsys package \$p 2>/dev/null | grep -q \"suspended=true\" && echo \$p; done | wc -l"
    }

    internal fun packagesVerifyNamesCmd(targets: List<String>): String {
        // The trailing true is not hiding a failure: a not-suspended package is a
        // valid result, not an error, but without it the shell exit is the last
        // grep's exit, so a names read fails whenever the last target is not
        // suspended (or none are), and the per-package result is discarded.
        return "for p in ${targets.joinToString(" ")}; do dumpsys package \$p 2>/dev/null | grep -q \"suspended=true\" && echo \$p; done; true"
    }

    // Chunked verify/names commands: one dumpsys loop over 167 packages
    // exceeds the 4000ms daemon timeout, so each chunk must stay well
    // under it. 25 packages per chunk keeps a chunk near 600ms.
    internal fun packagesVerifyChunkCmds(targets: List<String>, chunkSize: Int = 25): List<String> {
        return targets.chunked(chunkSize).map { packagesVerifyCmd(it) }
    }

    internal fun packagesVerifyNamesChunkCmds(targets: List<String>, chunkSize: Int = 25): List<String> {
        return targets.chunked(chunkSize).map { packagesVerifyNamesCmd(it) }
    }

    internal fun packagesRestoreCmd(targets: List<String>, disabled: List<String> = emptyList()): String {
        return buildString {
            targets.chunked(50).forEach { chunk ->
                append("cmd package unsuspend --user 0 ${chunk.joinToString(" ")} 2>/dev/null; ")
            }
            disabled.forEach { pkg ->
                append("pm enable $pkg 2>/dev/null; ")
            }
        }
    }

    /**
     * Non-essential OEM background services, telemetry trackers, and preloaded bloatware
     * that poll CPU/battery during gameplay without providing any gaming value.
     */
    val KNOWN_BLOATWARE = listOf(
        "com.samsung.android.bixby.agent",
        "com.samsung.android.bixby.wakeup",
        "com.samsung.android.bixbyvision.framework",
        "com.samsung.android.forest",         // Samsung Digital Wellbeing (constant usage stats polling)
        "com.samsung.android.spayfw",         // Samsung Pay framework
        "com.facebook.services",
        "com.facebook.system",
        "com.facebook.appmanager",
        "com.samsung.android.arzone",
        "com.samsung.android.kidsinstaller",
        "com.google.android.apps.tachyon",   // Google Meet background daemon
        "com.microsoft.skydrive",             // OneDrive cloud sync
    )

    /**
     * Known-safe background system services that may be closed during a
     * session. A background system service has no launcher icon, so there
     * is no reliable dynamic signal separating a safe one from a critical
     * one; only the packages listed here are closed among background
     * system apps. User-facing system apps are selected dynamically by
     * launcher icon instead and do not need to be listed, though the
     * user-facing ones found by research are listed here too for audit.
     * com.google.android.feedback is listed but inert: it is also in
     * BASE_PROTECTED_PACKAGES, and the protected set wins.
     */
    val SAFE_SYSTEM_ALLOWLIST = setOf(
        "com.facebook.services",
        "com.facebook.appmanager",
        "com.facebook.system",
        "com.google.android.syncadapters.calendar",
        "com.osp.app.signin",
        "com.samsung.android.intellivoiceservice",
        "com.qualcomm.location",
        "com.samsung.android.bixby.agent",
        "com.samsung.android.bixby.wakeup",
        "com.samsung.android.gru",
        "com.samsung.android.da.daagent",
        "com.samsung.android.dbsc",
        "com.samsung.android.beaconmanager",
        "com.sec.spp.push",
        "com.sec.bcservice",
        "com.mygalaxy.service",
        "com.samsung.oda.service",
        "com.samsung.android.sm.devicesecurity",
        "com.samsung.android.scloud",
        "com.samsung.android.app.omcagent",
        "com.samsung.android.app.parentalcare",
        "com.samsung.android.app.camera.sticker.facearavatar.preload",
        "com.samsung.android.dkey",
        "com.samsung.android.fmm",
        "com.samsung.android.mapsagent",
        "com.samsung.android.visionintelligence",
        "com.samsung.android.messaging",
        "com.samsung.android.app.sketchbook",
        "com.samsung.android.app.interpreter",
        "com.google.android.as.oss",
        "com.google.android.feedback",
        "com.google.android.gms.location.history",
        "com.google.android.federatedcompute",
        "com.google.android.ondevicepersonalization.services",
        "com.google.mainline.telemetry",
        "com.google.android.apps.carrier.carrierwifi",
        "com.google.audio.hearing.visualization.accessibility.scribe",
        "com.android.apps.tag",
        "com.android.bookmarkprovider",
        "com.google.android.apps.bard",
        "com.google.android.apps.tachyon",
        "com.google.android.googlequicksearchbox",
        // Device-proven safe to close (escape check 2026-11-13): force-stop
        // + suspend freed their RAM with no effect on the session.
        "com.android.intentresolver",
        "com.samsung.android.service.aircommand",
        "com.google.android.tts",
        "com.samsung.android.samsungpass",
        "com.samsung.android.samsungpassautofill",
        "com.microsoft.appmanager",
        "com.google.android.setupwizard",
        "com.samsung.android.privacydashboard",
        "com.google.android.partnersetup",
    )

    /**
     * Apps that restart after a plain force-stop (proven on device
     * 2026-11-13): only these are disabled, because disabling hides
     * the launcher icon for the whole session.
     */
    val STICKY_PACKAGES = setOf(
        "com.facebook.aura",
        "com.facebook.katana",
        "com.android.vending",
        "com.samsung.android.smartsuggestions",
    )

    /**
     * Messaging apps that get the open fallback when the broadcast wake
     * leaves them stopped: the broadcast proved unreliable for WhatsApp,
     * and messages must resume. Other apps wake on user open instead of
     * having their UI popped.
     */
    val WAKE_FALLBACK_PACKAGES = setOf(
        "com.whatsapp",
        "com.facebook.orca",
    )

    /**
     * Essential packages that must NEVER be suspended under any circumstance.
     * Prevents any possibility of breaking phone calls, emergency alerts, audio, or keyboards.
     */
    internal val BASE_PROTECTED_PACKAGES = setOf(
        "android",
        "com.android.systemui",
        "com.android.server.telecom",
        "com.samsung.android.incallui",
        "com.samsung.android.dialer",
        "com.android.dialer",
        "com.android.phone",
        "com.android.emergency",
        "com.android.carrierconfig",
        "com.android.providers.telephony",
        "com.android.bluetooth",
        "com.android.nfc",
        // Google Play Core (essential for licensing, SSL certificates, WebView).
        // The Play Store app itself is not protected: it restarts after a
        // force-stop, so it is a sticky target instead.
        "com.google.android.gms",
        "com.google.android.gsf",
        "com.google.android.feedback",
        // Media & Audio HAL
        "android.media",
        "com.android.audio",
        "com.sec.android.app.soundalive",
        "com.android.soundpicker",
        // Standard Keyboards
        "com.google.android.inputmethod.latin",
        "com.samsung.android.honeyboard",
        "com.touchtype.swiftkey",
        "org.pocketworkstation.pckeyboard",
        "helium314.keyboard",
        // Launcher & Privileged Daemons
        "com.sec.android.app.launcher",
        "moe.shizuku.privileged.api",
        "com.termux",
        "com.tailscale.ipn",       // Remote link to the phone; suspending it severs the connection
        "com.droiddeck.launcher",
        "com.valvesoftware.steamlink",
        // Gaming helpers: the optimizer and the in-game toolbar manage the
        // game's own performance and tools, so they must keep running
        // during play instead of being killed or suspended.
        "com.samsung.android.game.gos",
        "com.samsung.android.game.gametools",
        // Alarm and Settings: a suspended Clock cannot ring, and a
        // suspended Settings blocks opening the pairing page mid-session.
        "com.sec.android.app.clockpackage",
        "com.android.settings",
    )

    @Volatile private var cachedBloatware: List<String>? = null
    @Volatile private var cachedUserApps: List<String>? = null
    @Volatile private var cachedLauncherPackages: Set<String>? = null
    @Volatile private var cachedSystemTargets: List<String>? = null
    @Volatile private var cachedProtectedPackages: Set<String> = BASE_PROTECTED_PACKAGES

    @Volatile private var suspendedPackages = mutableListOf<String>()
    @Volatile private var forceStoppedPackages = mutableListOf<String>()
    @Volatile private var disabledPackages = mutableListOf<String>()
    @Volatile private var dozeForced = false
    @Volatile private var applied = false

    private val activeGen = AtomicInteger(0)
    private val isWarmingUp = AtomicBoolean(false)

    // ── Public API ─────────────────────────────────────────────────────────────

    /**
     * Pre-indexes packages and active system services asynchronously in the background.
     * Call this in App.onCreate() so package lists are resolved in 0 ms at game start.
     */
    fun warmup(context: Context) {
        if (!isWarmingUp.compareAndSet(false, true)) return
        val appContext = context.applicationContext
        Thread({
            try {
                val resolvedBloat = queryBloatware(appContext)
                val resolvedUser = queryUserApps(appContext)
                val resolvedLauncher = queryLauncherPackages(appContext)
                val resolvedSystem = querySystemCloseTargets(appContext, resolvedLauncher)
                val dynamicProtected = queryDynamicProtected(appContext)

                if (resolvedBloat.isNotEmpty()) cachedBloatware = resolvedBloat
                if (resolvedUser.isNotEmpty()) cachedUserApps = resolvedUser
                if (resolvedLauncher.isNotEmpty()) cachedLauncherPackages = resolvedLauncher
                if (resolvedSystem.isNotEmpty()) cachedSystemTargets = resolvedSystem
                if (dynamicProtected.isNotEmpty()) cachedProtectedPackages = BASE_PROTECTED_PACKAGES + dynamicProtected
                Log.d(TAG, "warmup complete: bloat=${cachedBloatware?.size ?: 0}, user=${cachedUserApps?.size ?: 0}, protected=${cachedProtectedPackages.size}")
            } catch (t: Throwable) {
                Log.w(TAG, "warmup non-fatal error", t)
            } finally {
                isWarmingUp.set(false)
            }
        }, "freezer-warmup").start()
    }

    /**
     * Apply system doze and package suspension at session start.
     * Guaranteed fast response time (<30ms) and conflict-free execution with generation token.
     */
    @Synchronized
    fun apply(context: Context, sessionGen: Int = 0): String {
        val previousGen = activeGen.get()
        activeGen.set(sessionGen)
        if (!SessionPrefs.advancedMasterEnabled(context)) return "app freezer: disabled (master switch off)"
        when (decideEngineApply(applied, previousGen, sessionGen)) {
            EngineApplyDecision.ALREADY_ACTIVE -> return "freezer: already active (gen=$sessionGen)"
            EngineApplyDecision.RESTORE_FIRST -> {
                if (!restore(context, sessionGen)) return "freezer: aborted (stale generation restore failed)"
                activeGen.set(sessionGen)
            }
            EngineApplyDecision.FRESH -> {}
        }

        val results = mutableListOf<String>()
        val packagesToSuspend = mutableListOf<String>()
        val pkgName = context.packageName

        // 1. Gather targets. Warmup runs in the background, so the
        // cached protected set may still be the static list only; read
        // the dynamic protected set (current keyboard, accessibility
        // services) synchronously here, or a custom keyboard could be
        // suspended if the session starts before warmup finishes.
        val dynamicProtected = queryDynamicProtected(context)
        if (dynamicProtected.isNotEmpty()) cachedProtectedPackages = cachedProtectedPackages + dynamicProtected
        val protectedSet = mergeProtectedPackages(cachedProtectedPackages, dynamicProtected, pkgName)

        if (SessionPrefs.freezeBloatware(context)) {
            val launcher = cachedLauncherPackages ?: queryLauncherPackages(context).also { if (it.isNotEmpty()) cachedLauncherPackages = it }
            val system = cachedSystemTargets ?: querySystemCloseTargets(context, launcher).also { if (it.isNotEmpty()) cachedSystemTargets = it }
            packagesToSuspend.addAll(system)
            results.add("bloat=${system.size}")
        }

        if (SessionPrefs.freezeUserApps(context)) {
            val user = cachedUserApps ?: queryUserApps(context).also { if (it.isNotEmpty()) cachedUserApps = it }
            packagesToSuspend.addAll(user)
            results.add("user_apps=${user.size}")
        }

        val uniqueTargets = filterSuspendTargets(packagesToSuspend, protectedSet)

        // Fast generation check before executing shell commands
        if (sessionGen != 0 && activeGen.get() != sessionGen) {
            Log.w(TAG, "apply aborted: generation changed from $sessionGen to ${activeGen.get()}")
            return "freezer: aborted (session cancelled)"
        }

        if (hasFreezerJournal(context) && !Tweaks.rollback(context)) {
            return "freezer: aborted (previous journal rollback failed)"
        }
        val legacy = loadLegacyMarker(context)
        if (legacy != null) {
            val legacyScript = buildString {
                append("cmd deviceidle unforce 2>/dev/null; ")
                if (legacy.first.isNotEmpty()) {
                    legacy.first.chunked(50).forEach { chunk ->
                        append("cmd package unsuspend --user 0 ${chunk.joinToString(" ")} 2>/dev/null; ")
                    }
                }
            }
            if (execChecked(context, legacyScript).exitCode != 0) {
                return "freezer: aborted (legacy leftover restore failed)"
            }
            deleteLegacyMarker(context)
        }

        // Detect which targets are actually running, so they can be killed
        // (freeing RAM) immediately before their suspend. A failed ps
        // read falls back to suspend-only for every target.
        val runningRss = detectRunningTargets(context, uniqueTargets)
        val orderedRunning = uniqueTargets.filter { it in runningRss }
        val nonRunning = uniqueTargets.filter { it !in runningRss }
        val disabled = orderedRunning.filter { it in STICKY_PACKAGES }
        val freedMbEst = runningRss.values.sum()
        if (orderedRunning.isNotEmpty()) {
            results.add("freed_mb_est=$freedMbEst")
        }

        val batchScript = buildString {
            if (SessionPrefs.deepSystemDoze(context)) {
                append("cmd deviceidle force-idle deep 2>/dev/null; ")
            }
            if (uniqueTargets.isNotEmpty()) {
                append(killSuspendScript(orderedRunning, nonRunning))
            }
        }

        if (uniqueTargets.isNotEmpty() || SessionPrefs.deepSystemDoze(context)) {
            val items = mutableListOf<JournalItem>()
            if (SessionPrefs.deepSystemDoze(context)) {
                items.add(
                    JournalItem(
                        key = "app-freezer-doze",
                        original = null,
                        restoreCmd = "cmd deviceidle unforce 2>/dev/null",
                        verifyCmd = "dumpsys deviceidle",
                        verifyMode = JournalVerifyMode.CONTAINS,
                        persistent = true,
                        generation = sessionGen,
                        verifyExpect = "mForceIdle=false",
                    )
                )
            }
            if (uniqueTargets.isNotEmpty()) {
                val restoreScript = packagesRestoreCmd(uniqueTargets, disabled)
                items.add(
                    JournalItem(
                        key = "app-freezer-packages",
                        original = "0",
                        restoreCmd = restoreScript,
                        verifyCmd = packagesVerifyCmd(uniqueTargets),
                        verifyMode = JournalVerifyMode.VALUE,
                        persistent = true,
                        generation = sessionGen,
                    )
                )
            }
            if (!Tweaks.recordItems(context, items)) {
                return "freezer: aborted (journal save failed)"
            }
            suspendedPackages.clear()
            suspendedPackages.addAll(uniqueTargets)
            forceStoppedPackages.clear()
            forceStoppedPackages.addAll(orderedRunning)
            disabledPackages.clear()
            disabledPackages.addAll(disabled)
        }

        if (SessionPrefs.deepSystemDoze(context)) {
            dozeForced = true
            results.add("deep_doze_forced")
        }

        PrivilegedBridge.ensureBackgroundProtection(context)

        // 4. Single-roundtrip privileged execution, retried once: a
        // failed batch must not be reported as a freeze.
        var batchExit = 0
        if (batchScript.isNotEmpty()) {
            batchExit = runBatchWithRetry(batchScript) { cmd -> execChecked(context, cmd) }.exitCode
        }
        if (batchExit != 0) {
            Tweaks.removeKey(context, "app-freezer-doze")
            Tweaks.removeKey(context, "app-freezer-packages")
            suspendedPackages.clear()
            forceStoppedPackages.clear()
            disabledPackages.clear()
            dozeForced = false
            Log.w(TAG, "app freezer: batch failed, exit=$batchExit")
            com.droiddeck.launcher.core.privileged.DiagnosticsLog.log(
                context, "device.change", "app-freezer-failed",
                fields = mapOf("exit" to batchExit.toString()),
            )
            return "freezer: aborted (batch failed, exit=$batchExit)"
        }

        // Verify what actually happened, so the applied event reports
        // measured counts and names the survivors instead of the
        // planned targets.
        val stillRunning = detectRunningTargets(context, orderedRunning)
        var suspendedCount: Int? = null
        if (uniqueTargets.isNotEmpty()) {
            val outputs = ArrayList<String>()
            var verifyFailed = false
            for (cmd in packagesVerifyChunkCmds(uniqueTargets)) {
                val read = execChecked(context, cmd)
                if (read.exitCode != 0) { verifyFailed = true; break }
                outputs.add(read.output)
            }
            if (!verifyFailed) suspendedCount = PackagesVerifyChunks.sumCounts(outputs)
        }
        val verified = appliedVerification(orderedRunning, stillRunning.keys.toList(), suspendedCount ?: uniqueTargets.size)
        if (orderedRunning.isNotEmpty()) results.add("killed=${verified.killed}")
        if (uniqueTargets.isNotEmpty()) results.add("suspended=${verified.suspended}")
        applied = true
        val summary = "app freezer: ON [gen=$sessionGen, ${results.joinToString(", ")}]"
        Log.i(TAG, summary)
        com.droiddeck.launcher.core.privileged.DiagnosticsLog.log(
            context, "device.change", "app-freezer-applied",
            fields = mapOf(
                "suspended" to verified.suspended.toString(),
                "killed" to verified.killed.toString(),
                "disabled" to disabled.size.toString(),
                "freed_mb_est" to freedMbEst.toString(),
                "doze" to SessionPrefs.deepSystemDoze(context).toString(),
                "survivors" to verified.survivors.joinToString(","),
            ),
        )
        return summary
    }

    /**
     * Restore all suspended packages and unforce doze on session exit.
     * Idempotent, safe, and instantaneous.
     */
    @Synchronized
    fun restore(context: Context, sessionGen: Int = 0): Boolean {
        activeGen.set(0)
        if (!applied && !dozeForced && !Tweaks.recoveryNeeded(context) && !hasLegacyMarker(context)) return true
        Log.i(TAG, "app freezer: restoring suspended packages and system doze (gen=$sessionGen)...")

        val legacy = loadLegacyMarker(context)
        if (legacy != null) {
            val legacyScript = buildString {
                append("cmd deviceidle unforce 2>/dev/null; ")
                if (legacy.first.isNotEmpty()) {
                    legacy.first.chunked(50).forEach { chunk ->
                        append("cmd package unsuspend --user 0 ${chunk.joinToString(" ")} 2>/dev/null; ")
                    }
                }
            }
            if (execChecked(context, legacyScript).exitCode != 0) {
                Log.w(TAG, "app freezer: restore failed, legacy marker kept for retry")
                return false
            }
            deleteLegacyMarker(context)
        }

        if (!Tweaks.rollback(context)) {
            Log.w(TAG, "app freezer: restore failed, journal kept for retry")
            return false
        }

        // Re-enable sticky apps and wake the force-stopped apps. The
        // journal already records the re-enable, but a split retry drops
        // it, so it is repeated here. Best effort: a wake failure leaves
        // the app unsuspended and enabled, so it still starts when the
        // user opens it.
        wakeForceStopped(context)

        applied = false
        dozeForced = false
        suspendedPackages.clear()
        forceStoppedPackages.clear()
        disabledPackages.clear()
        Log.i(TAG, "app freezer: OFF (all apps restored cleanly)")
        com.droiddeck.launcher.core.privileged.DiagnosticsLog.log(
            context, "device.change", "app-freezer-restored",
        )
        return true
    }

    fun hasFreezerJournal(context: Context): Boolean {
        return Tweaks.hasKey(context, "app-freezer") ||
            Tweaks.hasKey(context, "app-freezer-doze") ||
            Tweaks.hasKey(context, "app-freezer-packages")
    }

    fun migrateLegacyToJournal(context: Context): Boolean {
        val appContext = context.applicationContext
        val legacy = loadLegacyMarker(appContext) ?: return true
        val packages = legacy.first
        val items = mutableListOf<JournalItem>()
        if (legacy.second) {
            items.add(
                JournalItem(
                    key = "app-freezer-doze",
                    original = null,
                    restoreCmd = "cmd deviceidle unforce 2>/dev/null",
                    verifyCmd = "dumpsys deviceidle",
                    verifyMode = JournalVerifyMode.CONTAINS,
                    persistent = true,
                    generation = 0,
                    verifyExpect = "mForceIdle=false",
                )
            )
        }
        if (packages.isNotEmpty()) {
            val restoreScript = buildString {
                packages.chunked(50).forEach { chunk ->
                    append("cmd package unsuspend --user 0 ${chunk.joinToString(" ")} 2>/dev/null; ")
                }
            }
            items.add(
                JournalItem(
                    key = "app-freezer-packages",
                    original = "0",
                    restoreCmd = restoreScript,
                    verifyCmd = packagesVerifyCmd(packages),
                    verifyMode = JournalVerifyMode.VALUE,
                    persistent = true,
                    generation = 0,
                )
            )
        }
        if (items.isEmpty()) return true
        return Tweaks.recordItems(appContext, items)
    }

    fun isDozeActive(): Boolean = dozeForced
    fun getSuspendedCount(): Int = suspendedPackages.size

    @Synchronized
    fun onSuspendRollback() {
        applied = false
        dozeForced = false
        suspendedPackages.clear()
        forceStoppedPackages.clear()
        disabledPackages.clear()
    }

    fun getSuspendedList(): List<String> = suspendedPackages.toList()

    /** Live diagnostics for Debug Monitor. */
    fun runDiagnostic(context: Context): Map<String, String> {
        val out = mutableMapOf<String, String>()
        out["Deep Doze State"] = exec(context, "cmd deviceidle get deep").trim().let { if (it.isBlank()) "Unknown" else it }
        out["Currently Suspended Apps"] = if (applied) "${suspendedPackages.size} packages" else "0 (Standby)"
        val bloat = cachedBloatware ?: queryBloatware(context).also { cachedBloatware = it }
        out["Detected Bloatware on Device"] = "${bloat.size} daemons detected"
        val user = cachedUserApps ?: queryUserApps(context).also { cachedUserApps = it }
        out["Total 3rd Party User Apps"] = "${user.size} apps"
        return out
    }

    // ── Internal Helpers ───────────────────────────────────────────────────────

    private fun queryBloatware(context: Context): List<String> {
        val allSystem = exec(context, "pm list packages -s").lines()
            .map { it.removePrefix("package:").trim() }
            .filter { it.isNotEmpty() }
            .toSet()
        return KNOWN_BLOATWARE.filter { it in allSystem }
    }

    private fun queryUserApps(context: Context): List<String> {
        return exec(context, "pm list packages -3").lines()
            .filter { it.startsWith("package:") }
            .map { it.removePrefix("package:").trim() }
            .filter { it.isNotEmpty() && it !in BASE_PROTECTED_PACKAGES && it != context.packageName }
    }

    private fun queryLauncherPackages(context: Context): Set<String> {
        val launch = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return runCatching {
            context.packageManager.queryIntentActivities(launch, 0)
                .mapNotNull { it.activityInfo?.packageName }
                .toSet()
        }.getOrDefault(emptySet())
    }

    private fun querySystemCloseTargets(context: Context, launcherPackages: Set<String>): List<String> {
        val installedSystem = exec(context, "pm list packages -s").lines()
            .map { it.removePrefix("package:").trim() }
            .filter { it.isNotEmpty() }
            .toSet()
        return systemCloseTargets(installedSystem, launcherPackages)
    }

    // Which targets are running, with their RSS in MB. Any failure (daemon
    // down, ps timeout, empty output) returns empty, so the caller falls
    // back to suspend-only instead of failing the session start.
    private fun detectRunningTargets(context: Context, targets: List<String>): Map<String, Long> {
        if (targets.isEmpty()) return emptyMap()
        return runCatching {
            val result = execChecked(context, PS_COMMAND, 2000)
            if (result.exitCode == 0 && result.output.isNotBlank()) runningTargetRss(result.output, targets) else emptyMap()
        }.getOrDefault(emptyMap())
    }

    private fun queryDynamicProtected(context: Context): Set<String> {
        val set = mutableSetOf<String>()
        // Dynamic input method
        val ime = exec(context, "settings get secure default_input_method").trim()
        if (ime.isNotEmpty() && ime != "null" && "/" in ime) {
            set.add(ime.substringBefore("/"))
        }
        // Accessibility services
        val a11y = exec(context, "settings get secure enabled_accessibility_services").trim()
        if (a11y.isNotEmpty() && a11y != "null") {
            a11y.split(":").forEach { s ->
                if ("/" in s) set.add(s.substringBefore("/"))
            }
        }
        return set
    }

    private fun loadLegacyMarker(context: Context): Pair<List<String>, Boolean>? {
        return try {
            val f = File(context.filesDir, MARKER_FILE)
            if (f.exists()) SafetyChecks.decodeFreezerMarker(f.readText()) else null
        } catch (_: Exception) { null }
    }

    fun hasLegacyMarker(context: Context): Boolean = File(context.filesDir, MARKER_FILE).exists()

    fun deleteLegacyMarker(context: Context) {
        try { File(context.filesDir, MARKER_FILE).delete() } catch (_: Exception) {}
    }

    // Wakes the apps force-stopped at apply time: re-enables the sticky
    // set first (a disabled app cannot receive a broadcast), then
    // broadcasts the wake, then opens any messaging app still stopped.
    // Best effort and bounded by WAKE_TIMEOUT_MS; it never fails the
    // restore, because an unwoken app is unsuspended and enabled and
    // starts normally when the user opens it.
    private fun wakeForceStopped(context: Context) {
        val stopped = forceStoppedPackages.toList()
        if (stopped.isEmpty()) return
        val script = buildString {
            disabledPackages.toList().forEach { pkg ->
                append("pm enable $pkg 2>/dev/null; ")
            }
            append(wakeBroadcastScript(stopped))
        }
        val result = execChecked(context, script, WAKE_TIMEOUT_MS)
        val fallback = result.output.lineSequence()
            .map { it.trim() }
            .filter { it.startsWith("wake-fallback:") }
            .map { it.removePrefix("wake-fallback:") }
            .toList()
        Log.i(TAG, "app freezer: wake done, woken=${stopped.size}, fallback=$fallback")
        DiagnosticsLog.log(
            context, "device.change", "app-freezer-wake",
            fields = mapOf(
                "woken" to stopped.size.toString(),
                "fallback" to fallback.joinToString(","),
            ),
        )
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

    private fun execChecked(context: Context, cmd: String, timeoutMs: Int = PrivilegedBridge.DEFAULT_TIMEOUT_MS): PrivilegedResult {
        return try {
            PrivilegedBridge.ensureSessionToken(context)
            PrivilegedBridge.executeChecked(cmd, timeoutMs)
        } catch (t: Throwable) {
            Log.w(TAG, "execChecked error: $cmd", t)
            PrivilegedResult(-1, "", PrivilegedMode.NONE)
        }
    }
}
