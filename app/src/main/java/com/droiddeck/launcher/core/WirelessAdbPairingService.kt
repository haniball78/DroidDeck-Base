package com.droiddeck.launcher.core

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.RemoteInput
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import com.droiddeck.launcher.FrontEndActivity
import com.droiddeck.launcher.R
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

class WirelessAdbPairingService : Service() {
    override fun attachBaseContext(newBase: android.content.Context) =
        super.attachBaseContext(com.droiddeck.launcher.core.AppLanguage.wrap(newBase))

    sealed interface Stage {
        data object Idle : Stage
        data object Waiting : Stage
        data class CodeNeeded(val error: String? = null, val pairingPort: Int? = null) : Stage
        data class Working(val step: String) : Stage
        data object Done : Stage
        /** Pairing succeeded, but the daemon start parked: the connect port never changed. */
        data object Parked : Stage
        data class Failed(val error: String) : Stage
    }

    private val main = Handler(Looper.getMainLooper())
    private var nsd: NsdManager? = null
    private var discovery: NsdManager.DiscoveryListener? = null
    private var pairingHost: String? = null
    private var pairingPort: Int? = null
    @Volatile private var working = false
    private val timeout = Runnable { finish(Stage.Failed(getString(R.string.adbpair_timed_out))) }
    private var repeakScheduled = false
    private var codePosted = false
    private var lastCodePostMs = 0L
    private var lastCodeError: String? = null
    private val repeakRunnable = Runnable {
        repeakScheduled = false
        repeakNotification()
    }

    /** Re-posts the code prompt so it peeks again expanded, with the inline code action. */
    private fun repeakNotification() {
        val stage = current.value
        if (stage !is Stage.CodeNeeded) return
        val manager = getSystemService(NotificationManager::class.java)
        manager?.cancel(NOTIFICATION_ID)
        manager?.notify(NOTIFICATION_ID, notification(stage))
        logNotify(stage)
    }

    override fun onCreate() {
        super.onCreate()
        ensureChannel()
    }

    /** The channel is created once, at service start — never rebuilt per post. */
    private fun ensureChannel() {
        val manager = getSystemService(NotificationManager::class.java) ?: return
        // The original channel was created silent (no sound, no vibration),
        // which is why the pairing prompt went unnoticed. Channel settings
        // freeze at creation, so a new channel id carries the audible and
        // visible one; the old silent channel is deleted.
        manager.deleteNotificationChannel("wireless-adb-pairing")
        manager.createNotificationChannel(NotificationChannel(CHANNEL_ID, getString(R.string.adbpair_channel_name),
            NotificationManager.IMPORTANCE_HIGH).apply {
            description = getString(R.string.adbpair_channel_description)
            setShowBadge(false)
            enableVibration(true)
        })
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_REPEAK -> {
                // A body tap re-posts the notification: it peeks again
                // expanded, with the inline code action, wherever the
                // user tapped. The pending timed re-post is cancelled
                // so a re-post never lands while the user is typing
                // into the inline field.
                repeakNotification()
                main.removeCallbacks(repeakRunnable)
                repeakScheduled = false
            }
            ACTION_CODE -> {
                val remoteCode = RemoteInput.getResultsFromIntent(intent)?.getCharSequence(KEY_CODE)
                    ?.filter(Char::isDigit)?.toString().orEmpty()
                val code = remoteCode.ifEmpty { intent?.getStringExtra(EXTRA_CODE)?.filter(Char::isDigit).orEmpty() }
                onCode(code)
            }
            ACTION_CANCEL -> finish(Stage.Idle)
            else -> begin()
        }
        return START_NOT_STICKY
    }

    private fun begin() {
        pairingHost = null
        pairingPort = null
        working = false
        codePosted = false
        lastCodePostMs = 0L
        lastCodeError = null
        publish(Stage.Waiting)
        startForeground(NOTIFICATION_ID, notification(Stage.Waiting))
        startDiscovery()
        main.removeCallbacks(timeout)
        main.postDelayed(timeout, TIMEOUT_MS)
    }

    private fun startDiscovery() {
        stopDiscovery()
        val manager = getSystemService(Context.NSD_SERVICE) as NsdManager
        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) = Unit
            override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                if (serviceInfo.serviceType?.contains(PAIRING_TYPE) != true) return
                runCatching { manager.resolveService(serviceInfo, resolveListener()) }
            }
            override fun onServiceLost(serviceInfo: NsdServiceInfo) = Unit
            override fun onDiscoveryStopped(serviceType: String) = Unit
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                main.post { finish(Stage.Failed(getString(R.string.adbpair_discovery_failed_code, errorCode))) }
            }
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) = Unit
        }
        runCatching { manager.discoverServices("$PAIRING_TYPE.", NsdManager.PROTOCOL_DNS_SD, listener) }
            .onSuccess { nsd = manager; discovery = listener }
            .onFailure { finish(Stage.Failed(getString(R.string.adbpair_discovery_failed, it.localizedMessage ?: "unknown"))) }
    }

    private fun resolveListener() = object : NsdManager.ResolveListener {
        override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) = Unit
        override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
            val address = serviceInfo.host ?: return
            if (!WirelessAdbFix.isLocalAddress(address)) return
            main.post {
                if (working || current.value == Stage.Done) return@post
                pairingHost = address.hostAddress
                pairingPort = serviceInfo.port
                if (current.value !is Stage.CodeNeeded) show(Stage.CodeNeeded(pairingPort = serviceInfo.port))
            }
        }
    }

    private fun onCode(code: String) {
        val host = pairingHost
        val port = pairingPort
        if (working) return
        if (host == null || port == null) {
            show(Stage.Waiting)
            return
        }
        if (code.length != 6) {
            show(Stage.CodeNeeded(getString(R.string.adbpair_six_digits), pairingPort = port))
            return
        }
        working = true
        show(Stage.Working(getString(R.string.adbpair_pairing)))
        Thread({
            // The settle wait compares against the port from before the
            // pair, so it must be read before pairing starts.
            val prePairPort = WirelessAdbFix.localConnectPort(this)
            val paired = runCatching { kotlinx.coroutines.runBlocking { WirelessAdbFix.pair(this@WirelessAdbPairingService, WirelessAdbFix.LOOPBACK, port, code) } }
            if (paired.isFailure) {
                main.post {
                    working = false
                    pairingPort = null
                    show(Stage.CodeNeeded(getString(R.string.adbpair_code_rejected), pairingPort = port))
                }
                return@Thread
            }
            main.post {
                stopDiscovery()
                show(Stage.Working(getString(R.string.adbpair_connecting)))
            }
            var attemptedPort: Int? = null
            // A usable port after pairing is either a changed port (fresh
            // listener) or the pre-pair port once it starts accepting the
            // key (propagation delay). No usable port means park, not a
            // failed pair: the pair succeeded, and the card retries on
            // its own when the port changes.
            val connectPort = kotlinx.coroutines.runBlocking {
                WirelessAdbFix.waitForUsableConnectPort(this@WirelessAdbPairingService, prePairPort)
            }
            if (connectPort == null) {
                WirelessAdbFix.parkAuthFailure(this, prePairPort ?: WirelessAdbFix.localConnectPort(this))
                main.post {
                    working = false
                    finish(Stage.Parked)
                }
                return@Thread
            }
            val result = runCatching {
                attemptedPort = connectPort
                main.post { show(Stage.Working(getString(R.string.adbpair_applying))) }
                WirelessAdbFix.startPrivilegedDaemonViaAdb(this, WirelessAdbFix.LOOPBACK, connectPort)
            }
            if (result.isFailure) {
                val reason = result.exceptionOrNull()?.javaClass?.simpleName ?: "unknown"
                val fingerprint = WirelessAdbFix.identityFingerprint(this)
                val fields = buildMap {
                    attemptedPort?.let { put("port", it.toString()) }
                    put("reason", reason)
                    put("category", classifyConnectionFailure(reason).name.lowercase())
                    put("source", "notification")
                    fingerprint?.let { put("identity", it) }
                }
                com.droiddeck.launcher.core.privileged.DiagnosticsLog.log(this, "adb.daemon", "failed", fields = fields)
            }
            if (result.isSuccess) {
                // The daemon just started (and the start wrapper cleared the
                // plain-mode latch), so probe now: this updates the shared
                // bridge status instead of leaving it stale until restart.
                com.droiddeck.launcher.core.privileged.PrivilegedBridge.refresh(caller = "pairing-start")
            }
            main.post {
                working = false
                finish(result.exceptionOrNull()?.let { Stage.Failed(it.localizedMessage ?: getString(R.string.adbpair_command_failed)) } ?: Stage.Done)
            }
        }, "wireless-adb-pairing").start()
    }

    private fun show(stage: Stage) {
        publish(stage)
        val manager = getSystemService(NotificationManager::class.java)
        var posted = false
        if (stage is Stage.CodeNeeded) {
            // Alert once: the first code prompt is a fresh post (cancel +
            // notify, so it peeks with sound); later prompts update the
            // same notification in place and stay silent. Identical
            // prompts within the debounce window are one event, not a
            // re-post — so the prompt no longer re-alerts every few
            // seconds. The 8 s re-peak and a body tap still re-post.
            val now = android.os.SystemClock.elapsedRealtime()
            if (shouldRepostCode(lastCodePostMs, lastCodeError, now, stage.error)) {
                if (shouldCancelBeforeCodePost(codePosted)) manager?.cancel(NOTIFICATION_ID)
                manager?.notify(NOTIFICATION_ID, notification(stage))
                codePosted = true
                lastCodePostMs = now
                lastCodeError = stage.error
                posted = true
            }
        } else {
            manager?.notify(NOTIFICATION_ID, notification(stage))
            posted = true
        }
        if (posted) logNotify(stage)
        // The heads-up peek is system-timed, so one re-post keeps the
        // prompt visible well past ten seconds: it peeks again expanded,
        // with the inline code action. Once per code episode; a body tap
        // re-posts immediately and cancels this one, so a re-post never
        // lands while the user is typing into the inline field.
        if (stage is Stage.CodeNeeded) {
            if (!repeakScheduled) {
                repeakScheduled = true
                main.postDelayed(repeakRunnable, REPEAK_DELAY_MS)
            }
        } else {
            main.removeCallbacks(repeakRunnable)
            repeakScheduled = false
        }
    }

    private fun logNotify(stage: Stage) {
        com.droiddeck.launcher.core.privileged.DiagnosticsLog.log(
            this, "adbpair.notify", "posted",
            fields = mapOf("stage" to stage.javaClass.simpleName),
        )
    }

    private fun finish(stage: Stage) {
        main.removeCallbacks(timeout)
        main.removeCallbacks(repeakRunnable)
        stopDiscovery()
        publish(stage)
        val manager = getSystemService(NotificationManager::class.java)
        if (stage is Stage.Done || stage is Stage.Failed || stage is Stage.Parked) {
            manager?.notify(NOTIFICATION_ID, notification(stage))
            logNotify(stage)
            stopForeground(STOP_FOREGROUND_DETACH)
        } else {
            stopForeground(STOP_FOREGROUND_REMOVE)
            manager?.cancel(NOTIFICATION_ID)
        }
        stopSelf()
    }

    private fun stopDiscovery() {
        val listener = discovery ?: return
        runCatching { nsd?.stopServiceDiscovery(listener) }
        discovery = null
    }

    override fun onDestroy() {
        main.removeCallbacks(timeout)
        main.removeCallbacks(repeakRunnable)
        stopDiscovery()
        if (current.value.let { it is Stage.Waiting || it is Stage.CodeNeeded || it is Stage.Working }) publish(Stage.Idle)
        super.onDestroy()
    }

    private fun notification(stage: Stage): Notification {
        val open = PendingIntent.getActivity(this, 0,
            Intent(this, FrontEndActivity::class.java).setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT),
            PendingIntent.FLAG_IMMUTABLE)
        // A body tap cannot expand the notification — the expand chevron
        // is system UI, and no API re-opens it — so the tap re-posts the
        // notification instead: it peeks again expanded, with the inline
        // code action, wherever the user tapped. The code also goes in
        // the in-app panel, and the inline reply reaches the service
        // without opening anything, so the system pairing dialog
        // underneath is never disturbed.
        // Anything but CodeNeeded just opens the app.
        val repeak = PendingIntent.getService(this, 5,
            Intent(this, WirelessAdbPairingService::class.java).setAction(ACTION_REPEAK),
            PendingIntent.FLAG_IMMUTABLE)
        val cancel = PendingIntent.getService(this, 1,
            Intent(this, WirelessAdbPairingService::class.java).setAction(ACTION_CANCEL), PendingIntent.FLAG_IMMUTABLE)
        // No setTimeoutAfter: the heads-up peek duration is system-controlled,
        // and a timeout would cancel the pairing prompt itself mid-pairing.
        // The notification is ongoing, so it never vanishes early on its own.
        val contentIntent = if (stage is Stage.CodeNeeded) repeak else open
        val builder = Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_session)
            .setContentIntent(contentIntent)
            .setShowWhen(false)
            .setCategory(Notification.CATEGORY_STATUS)
        when (stage) {
            Stage.Waiting, Stage.Idle -> builder
                .setContentTitle(getString(R.string.adbpair_title_pair))
                .setContentText(getString(R.string.adbpair_waiting_text))
                .setStyle(Notification.BigTextStyle().bigText(getString(R.string.adbpair_waiting_long)))
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .addAction(Notification.Action.Builder(null, getString(R.string.common_cancel), cancel).build())
            is Stage.CodeNeeded -> {
                val reply = PendingIntent.getService(this, 2,
                    Intent(this, WirelessAdbPairingService::class.java).setAction(ACTION_CODE),
                    if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                    else PendingIntent.FLAG_UPDATE_CURRENT)
                val input = RemoteInput.Builder(KEY_CODE).setLabel(getString(R.string.adbpair_code_label)).build()
                val text = stage.error ?: getString(R.string.adbpair_code_hint)
                builder
                    .setContentTitle(getString(if (stage.error == null) R.string.adbpair_title_code else R.string.adbpair_title_retry))
                    .setContentText(text)
                    .setStyle(Notification.BigTextStyle().bigText(text + "\n" + getString(R.string.adbpair_code_big)))
                    .setOngoing(true)
                    .setOnlyAlertOnce(true)
                    .addAction(Notification.Action.Builder(null, getString(R.string.adbpair_enter_code), reply).addRemoteInput(input).build())
                    .addAction(Notification.Action.Builder(null, getString(R.string.common_cancel), cancel).build())
            }
            is Stage.Working -> builder
                .setContentTitle(getString(R.string.adbpair_title_working))
                .setContentText(stage.step)
                .setProgress(0, 0, true)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
            Stage.Done -> builder
                .setContentTitle(getString(R.string.adbpair_title_done))
                .setContentText(getString(R.string.adbpair_done_text))
                .setStyle(Notification.BigTextStyle().bigText(getString(R.string.adbpair_done_text)))
                .setAutoCancel(true)
            Stage.Parked -> builder
                .setContentTitle(getString(R.string.adbpair_title_parked))
                .setContentText(getString(R.string.adbpair_parked_text))
                .setStyle(Notification.BigTextStyle().bigText(getString(R.string.adbpair_parked_text)))
                .setAutoCancel(true)
            is Stage.Failed -> builder
                .setContentTitle(getString(R.string.adbpair_title_failed))
                .setContentText(stage.error)
                .setStyle(Notification.BigTextStyle().bigText(stage.error))
                .setAutoCancel(true)
        }
        return builder.build()
    }

    companion object {
        private const val CHANNEL_ID = "wireless-adb-pairing-alert"
        private const val NOTIFICATION_ID = 3
        private const val PAIRING_TYPE = "_adb-tls-pairing._tcp"
        private const val ACTION_CODE = "com.droiddeck.launcher.action.WIRELESS_ADB_CODE"
        private const val ACTION_CANCEL = "com.droiddeck.launcher.action.WIRELESS_ADB_CANCEL"
        private const val ACTION_REPEAK = "com.droiddeck.launcher.action.WIRELESS_ADB_REPEAK"
        private const val KEY_CODE = "code"
        private const val EXTRA_CODE = "code_extra"
        private const val TIMEOUT_MS = 5 * 60_000L
        private const val REPEAK_DELAY_MS = 8_000L
        /** Identical code prompts closer than this are one event, not a new alert. */
        const val REPOST_DEBOUNCE_MS = 3_000L

        /** Only the first code prompt is a fresh post; later ones update in place. */
        fun shouldCancelBeforeCodePost(alreadyPosted: Boolean): Boolean = !alreadyPosted

        /** True when this code prompt deserves a re-post at all. */
        fun shouldRepostCode(lastPostMs: Long, lastError: String?, nowMs: Long, error: String?): Boolean {
            if (lastPostMs == 0L) return true
            return nowMs - lastPostMs >= REPOST_DEBOUNCE_MS || error != lastError
        }

        private val current = MutableStateFlow<Stage>(Stage.Idle)
        val stage: StateFlow<Stage> = current

        private fun publish(stage: Stage) { current.value = stage }

        fun start(context: Context) {
            context.getSystemService(NotificationManager::class.java)?.cancel(NOTIFICATION_ID)
            publish(Stage.Waiting)
            val intent = Intent(context, WirelessAdbPairingService::class.java)
            if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(intent) else context.startService(intent)
        }

        fun cancel(context: Context) {
            if (current.value.let { it is Stage.Done || it is Stage.Failed || it is Stage.Idle }) {
                publish(Stage.Idle)
                return
            }
            context.startService(Intent(context, WirelessAdbPairingService::class.java).setAction(ACTION_CANCEL))
        }

        /** Code typed in the in-app panel (not via RemoteInput). */
        fun sendCode(context: Context, code: String) {
            context.startService(
                Intent(context, WirelessAdbPairingService::class.java)
                    .setAction(ACTION_CODE).putExtra(EXTRA_CODE, code),
            )
        }

        fun notificationsEnabled(context: Context): Boolean =
            context.getSystemService(NotificationManager::class.java)?.areNotificationsEnabled() != false
    }
}
