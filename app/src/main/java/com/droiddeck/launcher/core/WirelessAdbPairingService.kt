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
import com.droiddeck.launcher.PairingCodeEntryActivity
import com.droiddeck.launcher.R
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

class WirelessAdbPairingService : Service() {
    override fun attachBaseContext(newBase: android.content.Context) =
        super.attachBaseContext(com.droiddeck.launcher.core.AppLanguage.wrap(newBase))

    sealed interface Stage {
        data object Idle : Stage
        data object Waiting : Stage
        data class CodeNeeded(val error: String? = null) : Stage
        data class Working(val step: String) : Stage
        data object Done : Stage
        data class Failed(val error: String) : Stage
    }

    private val main = Handler(Looper.getMainLooper())
    private var nsd: NsdManager? = null
    private var discovery: NsdManager.DiscoveryListener? = null
    private var pairingHost: String? = null
    private var pairingPort: Int? = null
    @Volatile private var working = false
    private val timeout = Runnable { finish(Stage.Failed(getString(R.string.adbpair_timed_out))) }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
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
                if (current.value !is Stage.CodeNeeded) show(Stage.CodeNeeded())
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
            show(Stage.CodeNeeded(getString(R.string.adbpair_six_digits)))
            return
        }
        working = true
        show(Stage.Working(getString(R.string.adbpair_pairing)))
        Thread({
            val paired = runCatching { kotlinx.coroutines.runBlocking { WirelessAdbFix.pair(this@WirelessAdbPairingService, WirelessAdbFix.LOOPBACK, port, code) } }
            if (paired.isFailure) {
                main.post {
                    working = false
                    pairingPort = null
                    show(Stage.CodeNeeded(getString(R.string.adbpair_code_rejected)))
                }
                return@Thread
            }
            main.post {
                stopDiscovery()
                show(Stage.Working(getString(R.string.adbpair_connecting)))
            }
            var attemptedPort: Int? = null
            val result = runCatching {
                val connectPort = WirelessAdbFix.localConnectPort(this)
                    ?: error(getString(R.string.adbpair_port_not_found))
                attemptedPort = connectPort
                main.post { show(Stage.Working(getString(R.string.adbpair_applying))) }
                WirelessAdbFix.startPrivilegedDaemonViaAdb(this, WirelessAdbFix.LOOPBACK, connectPort)
            }
            if (result.isFailure) {
                val reason = result.exceptionOrNull()?.javaClass?.simpleName ?: "unknown"
                val fields = buildMap {
                    attemptedPort?.let { put("port", it.toString()) }
                    put("reason", reason)
                    put("category", classifyConnectionFailure(reason).name.lowercase())
                    put("source", "notification")
                }
                com.droiddeck.launcher.core.privileged.DiagnosticsLog.log(this, "adb.daemon", "failed", fields = fields)
            }
            if (result.isSuccess) {
                // The daemon just started (and the start wrapper cleared the
                // plain-mode latch), so probe now: this updates the shared
                // bridge status instead of leaving it stale until restart.
                com.droiddeck.launcher.core.privileged.PrivilegedBridge.refresh()
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
        // A fresh post (not an update) is what makes the code prompt pop as
        // a heads-up with sound/vibration; updates to the ongoing
        // foreground-service notification do not reliably re-alert.
        if (stage is Stage.CodeNeeded) manager?.cancel(NOTIFICATION_ID)
        manager?.notify(NOTIFICATION_ID, notification(stage))
    }

    private fun finish(stage: Stage) {
        main.removeCallbacks(timeout)
        stopDiscovery()
        publish(stage)
        val manager = getSystemService(NotificationManager::class.java)
        if (stage is Stage.Done || stage is Stage.Failed) {
            manager?.notify(NOTIFICATION_ID, notification(stage))
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
        stopDiscovery()
        if (current.value.let { it is Stage.Waiting || it is Stage.CodeNeeded || it is Stage.Working }) publish(Stage.Idle)
        super.onDestroy()
    }

    private fun notification(stage: Stage): Notification {
        val manager = getSystemService(NotificationManager::class.java)
        // The original channel was created silent (no sound, no vibration),
        // which is why the pairing prompt went unnoticed. Channel settings
        // freeze at creation, so a new channel id carries the audible and
        // visible one; the old silent channel is deleted.
        manager?.deleteNotificationChannel("wireless-adb-pairing")
        manager?.createNotificationChannel(NotificationChannel(CHANNEL_ID, getString(R.string.adbpair_channel_name),
            NotificationManager.IMPORTANCE_HIGH).apply {
            description = getString(R.string.adbpair_channel_description)
            setShowBadge(false)
            enableVibration(true)
        })
        val open = PendingIntent.getActivity(this, 0,
            Intent(this, FrontEndActivity::class.java).setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT),
            PendingIntent.FLAG_IMMUTABLE)
        // Tapping the notification body must reach code entry, not just the
        // app: the inline RemoteInput action cannot be opened from a body
        // tap, so Waiting/CodeNeeded point at the code-entry activity
        // (a bottom card floating over Settings, with the system code
        // dialog still visible above it). Terminal stages keep opening
        // the app, as before.
        val codeEntry = PendingIntent.getActivity(this, 3,
            Intent(this, PairingCodeEntryActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE)
        val cancel = PendingIntent.getService(this, 1,
            Intent(this, WirelessAdbPairingService::class.java).setAction(ACTION_CANCEL), PendingIntent.FLAG_IMMUTABLE)
        // No setTimeoutAfter: the heads-up peek duration is system-controlled,
        // and a timeout would cancel the pairing prompt itself mid-pairing.
        // The notification is ongoing, so it never vanishes early on its own.
        val contentIntent = if (stage is Stage.Waiting || stage is Stage.CodeNeeded) codeEntry else open
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
        private const val KEY_CODE = "code"
        private const val EXTRA_CODE = "code_extra"
        private const val TIMEOUT_MS = 5 * 60_000L

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

        /** Code typed in the code-entry activity (not via RemoteInput). */
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
