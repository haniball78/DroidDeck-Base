package com.droiddeck.launcher.core

import android.app.Activity
import android.app.ActivityOptions
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.SystemClock
import android.provider.Settings
import android.service.quicksettings.TileService
import com.droiddeck.launcher.core.privileged.DiagnosticsLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * The single entry point for all wireless-debugging navigation: opening
 * Settings in split-screen next to the app, and landing on the wireless
 * debugging screen. Public Intent flags only — no reflection, no hidden
 * APIs: the previous reflection layer called methods that do not exist
 * (setLaunchAdjacentTaskId) or are blocklisted on API 30+ regardless of
 * targetSdk (setLaunchWindowingMode), so it always failed soft and the
 * real behaviour was just the raw intent flags.
 */
object WirelessDebugNavigator {

    /** Hard deadline for the whole split sequence (user budget: 3-4 s). */
    const val SPLIT_DEADLINE_MS = 4_000L

    /** Poll interval while waiting for the split to engage. */
    private const val SPLIT_POLL_MS = 100L

    /** Undocumented Settings-internal highlight key: scrolls to a preference row, never navigates. */
    const val FRAGMENT_ARG_KEY = ":settings:fragment_args_key"

    /** Preference-row key of the wireless-debugging switch on the Developer options root. */
    const val WIRELESS_ROW_KEY = "toggle_adb_wireless"

    /** Split flags: adjacent + new task + a fresh task, so the user's existing Settings task is never reused or closed. */
    internal val splitFlags: Int =
        Intent.FLAG_ACTIVITY_LAUNCH_ADJACENT or Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_MULTIPLE_TASK

    /** Plain flags: a fresh task, never reuse or close the user's Settings task. */
    internal val plainFlags: Int =
        Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_MULTIPLE_TASK

    private const val SHOW_FRAGMENT_EXTRA = ":settings:show_fragment"
    private const val WIRELESS_FRAGMENT = "com.android.settings.development.WirelessDebuggingFragment"
    private const val SETTINGS_PACKAGE = "com.android.settings"
    private const val QS_TILE_CLASS = "com.android.settings.development.qstile.DevelopmentTiles\$WirelessDebugging"
    private const val SUBSETTINGS_CLASS = "com.android.settings.SubSettings"

    // Lazy: creating a Main-dispatcher scope at class load would touch the
    // Android runtime; the navigator's pure functions must stay usable
    // from JVM unit tests, which never call openSplit.
    private val mainScope by lazy { CoroutineScope(SupervisorJob() + Dispatchers.Main) }

    /**
     * The first cascade step that resolves on this device, deepest first.
     * The highlighted Developer-options root is the final fallback and is
     * always returned: the action is a required part of the platform.
     */
    fun targetIntent(context: Context, highlightKey: String? = WIRELESS_ROW_KEY): Intent =
        resolveTarget(context, highlightKey).first

    /**
     * Cascade target intent for PendingIntent use (notification actions),
     * with the flags a non-activity sender needs.
     */
    fun plainIntent(context: Context, highlightKey: String? = WIRELESS_ROW_KEY): Intent =
        targetIntent(context, highlightKey).apply { addFlags(plainFlags) }

    /**
     * Opens Settings in split-screen next to [activity], then reports
     * whether the split engaged — from the observed window state, never a
     * fixed sleep. Pairing work must start only from [onSplitConfirmed].
     * If the deadline passes without the split, the Settings activity the
     * launch already foregrounded stays as-is (fullscreen) and the
     * callback runs with false; nothing is re-launched and nothing is
     * closed. The callback is skipped if the activity is gone first.
     */
    fun openSplit(
        activity: Activity,
        highlightKey: String? = WIRELESS_ROW_KEY,
        onSplitConfirmed: (splitEngaged: Boolean) -> Unit,
    ) {
        val (intent, step) = resolveTarget(activity, highlightKey)
        intent.addFlags(splitFlags)
        val startedAt = SystemClock.elapsedRealtime()
        val launched = runCatching { activity.startActivity(intent) }.isSuccess
        if (!launched) {
            log(step, engaged = false, startedAt)
            onSplitConfirmed(false)
            return
        }
        mainScope.launch {
            val engaged = waitForSplit(activity, startedAt)
            if (activity.isDestroyed || activity.isFinishing) return@launch
            log(step, engaged, startedAt)
            onSplitConfirmed(engaged)
        }
    }

    /**
     * Plain launch (no split), e.g. the guided reset. Returns false when
     * the target could not start; the caller keeps its own fallback.
     * [displayId] launches on a secondary display when non-null.
     */
    fun openPlain(context: Context, highlightKey: String? = WIRELESS_ROW_KEY, displayId: Int? = null): Boolean {
        val intent = targetIntent(context, highlightKey).apply { addFlags(plainFlags) }
        return runCatching {
            if (displayId != null && context is Activity) {
                context.startActivity(
                    intent,
                    ActivityOptions.makeBasic().setLaunchDisplayId(displayId).toBundle(),
                )
            } else {
                context.startActivity(intent)
            }
        }.isSuccess
    }

    private fun resolveTarget(context: Context, highlightKey: String?): Pair<Intent, String> {
        // (a) QS tile preferences — the only deep path with AOSP wiring to
        // the wireless-debugging fragment. Skipped on Xiaomi/HyperOS: the
        // tile-preferences screen is reported to crash Settings there.
        if (!isXiaomi()) {
            val tile = Intent(TileService.ACTION_QS_TILE_PREFERENCES).apply {
                putExtra(
                    Intent.EXTRA_COMPONENT_NAME,
                    ComponentName(SETTINGS_PACKAGE, QS_TILE_CLASS),
                )
            }
            if (resolves(context, tile)) return tile to "qs-tile"
        }
        // (b) SubSettings fragment injection, corrected extra. Expected to
        // fail on AOSP (SubSettings is not exported); kept guarded in case
        // a fork exports it. Never the dead ":android:show_fragment" extra.
        val subSettings = Intent().apply {
            component = ComponentName(SETTINGS_PACKAGE, SUBSETTINGS_CLASS)
            putExtra(SHOW_FRAGMENT_EXTRA, WIRELESS_FRAGMENT)
        }
        if (resolves(context, subSettings)) return subSettings to "subsettings"
        // (c) Highlighted Developer-options root — the safe fallback.
        return developerOptionsIntent(context, highlightKey) to "dev-options"
    }

    private fun developerOptionsIntent(context: Context, highlightKey: String?): Intent =
        Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS).apply {
            if (highlightKey != null) putExtra(FRAGMENT_ARG_KEY, highlightKey)
            pinToSettings(context, this)
        }

    /**
     * Pins the fallback to the Settings app: the implicit action resolves
     * to two activities on One UI (Settings + the permission controller),
     * which shows the system resolver. The activity is picked at runtime
     * from PackageManager, never hardcoded — One UI may name it
     * differently from AOSP. Unchanged when Settings does not resolve.
     */
    private fun pinToSettings(context: Context, intent: Intent) {
        runCatching {
            val matches = context.packageManager.queryIntentActivities(
                intent,
                PackageManager.MATCH_DEFAULT_ONLY,
            )
            val settings = matches.firstOrNull { it.activityInfo.packageName == SETTINGS_PACKAGE }
            if (settings != null) {
                intent.component = ComponentName(SETTINGS_PACKAGE, settings.activityInfo.name)
            }
        }
    }

    private fun resolves(context: Context, intent: Intent): Boolean =
        runCatching {
            context.packageManager.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY) != null
        }.getOrDefault(false)

    internal fun isXiaomi(manufacturer: String = Build.MANUFACTURER): Boolean =
        manufacturer.equals("Xiaomi", ignoreCase = true)

    private suspend fun waitForSplit(activity: Activity, startedAt: Long): Boolean {
        while (true) {
            if (activity.isDestroyed || activity.isFinishing) return false
            if (activity.isInMultiWindowMode) return true
            if (SystemClock.elapsedRealtime() - startedAt >= SPLIT_DEADLINE_MS) return false
            delay(SPLIT_POLL_MS)
        }
    }

    private fun log(step: String, engaged: Boolean, startedAt: Long) {
        DiagnosticsLog.log(
            "adbpair", "split-launch",
            fields = mapOf(
                "step" to step,
                "split" to engaged.toString(),
                "elapsed_ms" to (SystemClock.elapsedRealtime() - startedAt).toString(),
            ),
        )
    }
}
