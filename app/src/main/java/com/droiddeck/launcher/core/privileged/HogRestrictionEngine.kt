package com.droiddeck.launcher.core.privileged

import android.app.usage.UsageStatsManager
import android.content.Context
import android.util.Log
import com.droiddeck.launcher.session.SessionPrefs
import java.util.concurrent.atomic.AtomicInteger

// A per-package snapshot of the three background-behaviour states the
// bundle touches. appOps is one of allow/ignore/deny/default/foreground;
// bucket is one of active/working_set/frequent/rare/restricted.
internal data class HogSnapshot(
    val pkg: String,
    val appOps: String,
    val bucket: String,
    val whitelisted: Boolean,
)

internal data class HogTarget(
    val pkg: String,
    val priorAppOps: String,
    val priorBucket: String,
    val wasWhitelisted: Boolean,
) {
    val changeAppOps: Boolean get() = priorAppOps != "ignore"
    val changeBucket: Boolean get() = priorBucket != "restricted"
    val changeWhitelist: Boolean get() = wasWhitelisted
    val needsRestriction: Boolean get() = changeAppOps || changeBucket || changeWhitelist
}

// Resolve at most maxTargets restriction targets: the most-used user apps
// first (foreground time, descending), then safe tracking-like system apps
// present on the device. System apps are excluded unless they are on the
// safe-tracking list. Nothing is picked for a user app when there is no
// usage data at all: never guess a hog.
internal fun resolveRestrictionTargets(
    userApps: List<String>,
    systemApps: List<String>,
    protected: Set<String>,
    ownPackage: String,
    usageForegroundMs: Map<String, Long>,
    safeTrackingSystem: Set<String> = HogRestrictionEngine.SAFE_TRACKING_SYSTEM,
    maxTargets: Int = HogRestrictionEngine.MAX_TARGETS,
): List<String> {
    val rankedUser = if (usageForegroundMs.isEmpty()) {
        emptyList()
    } else {
        userApps.distinct()
            .filter { it !in protected && it != ownPackage && AppFreezerPackageJournal.packagePattern.matches(it) }
            .sortedByDescending { usageForegroundMs[it] ?: 0L }
    }
    val tracking = systemApps.distinct()
        .filter { it in safeTrackingSystem && it !in protected && it != ownPackage && AppFreezerPackageJournal.packagePattern.matches(it) }
    return (rankedUser + tracking).distinct().take(maxTargets)
}

// Parse the batched snapshot script output. Sections are headed by
// "PKG <pkg>" and the whitelist dump follows a "WHITELIST" marker. A
// package whose appops mode or standby bucket cannot be read is dropped
// (fail-closed per package): its prior state is unknown, so it is never
// restricted.
internal fun parseHogSnapshot(output: String, targets: List<String>): Map<String, HogSnapshot> {
    val appOpsMode = Regex("\\b(allow|ignore|deny|default|foreground)\\b")
    val bucketName = Regex("\\b(active|working_set|frequent|rare|restricted)\\b")
    val bucketNumber = Regex("\\b(10|20|30|40|50)\\b")
    val bucketByNumber = mapOf("10" to "active", "20" to "working_set", "30" to "frequent", "40" to "rare", "50" to "restricted")
    val sections = mutableMapOf<String, MutableList<String>>()
    val whitelistLines = mutableListOf<String>()
    var current: String? = null
    var inWhitelist = false
    for (raw in output.lines()) {
        val line = raw.trim()
        when {
            line.startsWith("PKG ") -> {
                current = line.removePrefix("PKG ").trim()
                inWhitelist = false
                if (current !in targets) current = null
                if (current != null) sections.getOrPut(current) { mutableListOf() }
            }
            line == "WHITELIST" -> {
                current = null
                inWhitelist = true
            }
            inWhitelist -> whitelistLines.add(line)
            current != null -> sections.getOrPut(current) { mutableListOf() }.add(line)
        }
    }
    val whitelistText = whitelistLines.joinToString("\n")
    val result = mutableMapOf<String, HogSnapshot>()
    for ((pkg, lines) in sections) {
        val joined = lines.joinToString("\n")
        val appOps = appOpsMode.find(joined)?.groupValues?.get(1) ?: continue
        val bucket = bucketName.find(joined)?.groupValues?.get(1)
            ?: bucketNumber.find(joined)?.groupValues?.get(1)?.let { bucketByNumber[it] }
            ?: continue
        result[pkg] = HogSnapshot(pkg, appOps, bucket, SafetyChecks.isWhitelisted(whitelistText, pkg))
    }
    return result
}

internal fun buildHogSnapshotCmd(targets: List<String>): String {
    return buildString {
        append("for p in ${targets.joinToString(" ")}; do echo \"PKG \$p\"; ")
        append("cmd appops get \$p RUN_ANY_IN_BACKGROUND 2>/dev/null; ")
        append("am get-standby-bucket \$p 2>/dev/null; done; ")
        append("echo \"WHITELIST\"; dumpsys deviceidle whitelist 2>/dev/null")
    }
}

internal fun buildHogApplyCmd(targets: List<HogTarget>): String {
    return buildString {
        for (t in targets) {
            if (t.changeAppOps) append("cmd appops set ${t.pkg} RUN_ANY_IN_BACKGROUND ignore 2>/dev/null; ")
            if (t.changeBucket) append("am set-standby-bucket ${t.pkg} restricted 2>/dev/null; ")
            append("am make-uid-idle ${t.pkg} 2>/dev/null; ")
            if (t.changeWhitelist) append("dumpsys deviceidle whitelist -${t.pkg} 2>/dev/null; ")
        }
    }
}

internal fun buildHogAppOpsRestoreCmd(targets: List<HogTarget>): String {
    return buildString {
        for (t in targets) {
            if (t.changeAppOps) append("cmd appops set ${t.pkg} RUN_ANY_IN_BACKGROUND ${t.priorAppOps} 2>/dev/null; ")
        }
    }
}

internal fun buildHogBucketRestoreCmd(targets: List<HogTarget>): String {
    return buildString {
        for (t in targets) {
            if (t.changeBucket) append("am set-standby-bucket ${t.pkg} ${t.priorBucket} 2>/dev/null; ")
        }
    }
}

internal fun buildHogWhitelistRestoreCmd(targets: List<HogTarget>): String {
    return buildString {
        for (t in targets) {
            if (t.changeWhitelist) append("dumpsys deviceidle whitelist +${t.pkg} 2>/dev/null; ")
        }
    }
}

internal fun buildHogAppOpsVerifyCmd(pkgs: List<String>): String {
    return "for p in ${pkgs.joinToString(" ")}; do cmd appops get \$p RUN_ANY_IN_BACKGROUND 2>/dev/null | grep -q -w \"ignore\" && echo \$p; done | wc -l"
}

internal fun buildHogBucketVerifyCmd(pkgs: List<String>): String {
    return "for p in ${pkgs.joinToString(" ")}; do am get-standby-bucket \$p 2>/dev/null | grep -q -w -E \"restricted|50\" && echo \$p; done | wc -l"
}

internal fun buildHogWhitelistVerifyCmd(pkgs: List<String>): String {
    return "for p in ${pkgs.joinToString(" ")}; do dumpsys deviceidle whitelist 2>/dev/null | grep -q -F \"\$p\" || echo \$p; done | wc -l"
}

object HogRestrictionEngine {
    private const val TAG = "HogRestrictionEngine"

    const val MAX_TARGETS = 7

    // System apps that may be restricted anyway: tracking/telemetry-like
    // OEM services whose background work has no gaming value. This mirrors
    // AppFreezerEngine.KNOWN_BLOATWARE except Game Optimizing Service,
    // which is a gaming service, not tracking, and is never restricted.
    val SAFE_TRACKING_SYSTEM = setOf(
        "com.samsung.android.bixby.agent",
        "com.samsung.android.bixby.wakeup",
        "com.samsung.android.bixbyvision.framework",
        "com.samsung.android.forest",
        "com.samsung.android.spayfw",
        "com.facebook.services",
        "com.facebook.system",
        "com.facebook.appmanager",
        "com.samsung.android.arzone",
        "com.samsung.android.kidsinstaller",
        "com.google.android.apps.tachyon",
        "com.microsoft.skydrive",
    )

    private val RESTRICTION_PROTECTED = setOf(
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
        "com.google.android.gms",
        "com.google.android.gsf",
        "com.android.vending",
        "com.google.android.feedback",
        "com.samsung.android.game.gos",
        "com.sec.android.app.launcher",
        "moe.shizuku.privileged.api",
        "com.termux",
        "com.valvesoftware.steamlink",
        "com.google.android.inputmethod.latin",
        "com.samsung.android.honeyboard",
        "com.touchtype.swiftkey",
        "com.sec.android.app.soundalive",
    )

    @Volatile private var applied = false
    @Volatile private var restrictedPackages = mutableListOf<String>()

    private val activeGen = AtomicInteger(0)

    @Synchronized
    fun apply(context: Context, sessionGen: Int = 0): String {
        val previousGen = activeGen.get()
        activeGen.set(sessionGen)
        if (!SessionPrefs.advancedMasterEnabled(context)) return "hog restriction: disabled (master switch off)"
        if (!SessionPrefs.restrictBackgroundHogs(context)) return "hog restriction: disabled (toggle off)"
        when (decideEngineApply(applied, previousGen, sessionGen)) {
            EngineApplyDecision.ALREADY_ACTIVE -> return "hog restriction: already active (gen=$sessionGen)"
            EngineApplyDecision.RESTORE_FIRST -> {
                if (!restore(context, sessionGen)) return "hog restriction: aborted (stale generation restore failed)"
                activeGen.set(sessionGen)
            }
            EngineApplyDecision.FRESH -> {}
        }

        val dynamicProtected = queryDynamicProtected(context)
        val protectedSet = mergeProtectedPackages(RESTRICTION_PROTECTED, dynamicProtected, context.packageName)
        val userApps = queryUserApps(context)
        val systemApps = querySystemApps(context)
        val usage = queryUsageRanking(context)
        val targets = resolveRestrictionTargets(userApps, systemApps, protectedSet, context.packageName, usage)
        if (targets.isEmpty()) return "hog restriction: no targets"

        if (sessionGen != 0 && activeGen.get() != sessionGen) {
            Log.w(TAG, "apply aborted: generation changed from $sessionGen to ${activeGen.get()}")
            return "hog restriction: aborted (session cancelled)"
        }

        val snapshots = parseHogSnapshot(exec(context, buildHogSnapshotCmd(targets)), targets)
        if (snapshots.isEmpty()) return "hog restriction: snapshot failed"
        val toRestrict = targets.mapNotNull { snapshots[it] }
            .map { HogTarget(it.pkg, it.appOps, it.bucket, it.whitelisted) }
            .filter { it.needsRestriction }
        if (toRestrict.isEmpty()) return "hog restriction: already restricted"

        if (hasHogJournal(context) && !Tweaks.rollback(context)) {
            return "hog restriction: aborted (previous journal rollback failed)"
        }

        val items = mutableListOf<JournalItem>()
        val appOpsPkgs = toRestrict.filter { it.changeAppOps }.map { it.pkg }
        if (appOpsPkgs.isNotEmpty()) {
            items.add(
                JournalItem(
                    key = "hog-restriction-appops",
                    original = "0",
                    restoreCmd = buildHogAppOpsRestoreCmd(toRestrict),
                    verifyCmd = buildHogAppOpsVerifyCmd(appOpsPkgs),
                    verifyMode = JournalVerifyMode.VALUE,
                    persistent = true,
                    generation = sessionGen,
                )
            )
        }
        val bucketPkgs = toRestrict.filter { it.changeBucket }.map { it.pkg }
        if (bucketPkgs.isNotEmpty()) {
            items.add(
                JournalItem(
                    key = "hog-restriction-buckets",
                    original = "0",
                    restoreCmd = buildHogBucketRestoreCmd(toRestrict),
                    verifyCmd = buildHogBucketVerifyCmd(bucketPkgs),
                    verifyMode = JournalVerifyMode.VALUE,
                    persistent = true,
                    generation = sessionGen,
                )
            )
        }
        val whitelistPkgs = toRestrict.filter { it.changeWhitelist }.map { it.pkg }
        if (whitelistPkgs.isNotEmpty()) {
            items.add(
                JournalItem(
                    key = "hog-restriction-whitelist",
                    original = "0",
                    restoreCmd = buildHogWhitelistRestoreCmd(toRestrict),
                    verifyCmd = buildHogWhitelistVerifyCmd(whitelistPkgs),
                    verifyMode = JournalVerifyMode.VALUE,
                    persistent = true,
                    generation = sessionGen,
                )
            )
        }
        if (!Tweaks.recordItems(context, items)) {
            return "hog restriction: aborted (journal save failed)"
        }
        restrictedPackages.clear()
        restrictedPackages.addAll(toRestrict.map { it.pkg })

        val r = exec(context, buildHogApplyCmd(toRestrict))
        Log.d(TAG, "apply batch executed: $r")

        applied = true
        val summary = "hog restriction: ON [gen=$sessionGen, restricted=${toRestrict.size}]"
        Log.i(TAG, summary)
        DiagnosticsLog.log(
            context, "device.change", "hog-restriction-applied",
            fields = mapOf(
                "restricted" to toRestrict.size.toString(),
                "packages" to toRestrict.joinToString(",") { it.pkg },
            ),
        )
        return summary
    }

    @Synchronized
    fun restore(context: Context, sessionGen: Int = 0): Boolean {
        activeGen.set(0)
        if (!applied && !Tweaks.recoveryNeeded(context) && !hasHogJournal(context)) return true
        Log.i(TAG, "hog restriction: restoring background behaviour (gen=$sessionGen)...")
        if (!Tweaks.rollback(context)) {
            Log.w(TAG, "hog restriction: restore failed, journal kept for retry")
            return false
        }
        applied = false
        restrictedPackages.clear()
        Log.i(TAG, "hog restriction: OFF (all apps restored cleanly)")
        DiagnosticsLog.log(
            context, "device.change", "hog-restriction-restored",
        )
        return true
    }

    fun hasHogJournal(context: Context): Boolean {
        return Tweaks.hasKey(context, "hog-restriction-appops") ||
            Tweaks.hasKey(context, "hog-restriction-buckets") ||
            Tweaks.hasKey(context, "hog-restriction-whitelist")
    }

    fun getRestrictedList(): List<String> = restrictedPackages.toList()

    private fun queryUserApps(context: Context): List<String> {
        return exec(context, "pm list packages -3").lines()
            .filter { it.startsWith("package:") }
            .map { it.removePrefix("package:").trim() }
            .filter { it.isNotEmpty() }
    }

    private fun querySystemApps(context: Context): List<String> {
        return exec(context, "pm list packages -s").lines()
            .map { it.removePrefix("package:").trim() }
            .filter { it.isNotEmpty() }
            .toSet().toList()
    }

    private fun queryDynamicProtected(context: Context): Set<String> {
        val set = mutableSetOf<String>()
        val ime = exec(context, "settings get secure default_input_method").trim()
        if (ime.isNotEmpty() && ime != "null" && "/" in ime) {
            set.add(ime.substringBefore("/"))
        }
        val a11y = exec(context, "settings get secure enabled_accessibility_services").trim()
        if (a11y.isNotEmpty() && a11y != "null") {
            a11y.split(":").forEach { s ->
                if ("/" in s) set.add(s.substringBefore("/"))
            }
        }
        return set
    }

    private fun queryUsageRanking(context: Context): Map<String, Long> {
        return try {
            val usm = context.getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager ?: return emptyMap()
            val now = System.currentTimeMillis()
            val stats = usm.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, now - 24 * 60 * 60 * 1000L, now) ?: return emptyMap()
            stats.associate { it.packageName to it.totalTimeInForeground }
        } catch (_: Exception) {
            emptyMap()
        }
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
}
