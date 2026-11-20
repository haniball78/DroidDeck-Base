package com.droiddeck.launcher.session

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import com.droiddeck.launcher.FrontEndActivity
import com.droiddeck.launcher.R
import com.droiddeck.launcher.frontend.Library
import com.droiddeck.launcher.stores.formatBytes
import java.io.File

class GamePackService : Service() {
    private var wake: PowerManager.WakeLock? = null
    private var worker: Thread? = null
    private var currentAppId = -1
    private val main = Handler(Looper.getMainLooper())
    private var lastNotifyAt = 0L

    override fun onCreate() {
        super.onCreate()
        channels(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_CANCEL -> {
                val appId = intent.getIntExtra(EXTRA_APP_ID, -1)
                if (appId >= 0) GamePackJobs.requestCancel(appId)
                if (currentAppId < 0) stopSelf()
                return START_NOT_STICKY
            }
            ACTION_START -> {
                val appId = intent.getIntExtra(EXTRA_APP_ID, -1)
                val dir = intent.getStringExtra(EXTRA_DIR)?.let { File(it) }
                val name = intent.getStringExtra(EXTRA_NAME).orEmpty()
                val method = runCatching { PackMethod.valueOf(intent.getStringExtra(EXTRA_METHOD) ?: "MAX") }.getOrDefault(PackMethod.MAX)
                val unpack = intent.getBooleanExtra(EXTRA_UNPACK, false)
                if (appId < 0 || dir == null || !dir.isDirectory) return START_NOT_STICKY
                if (currentAppId >= 0) return START_NOT_STICKY
                currentAppId = appId
                GamePackJobs.clearCancel(appId)
                startForegroundCompat(buildNotification(appId, name, unpack, 0, 0, 0f))
                worker = Thread({ runJob(appId, name, dir, method, unpack) }, "gamepack-$appId").apply { start() }
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        releaseWake()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun runJob(appId: Int, name: String, dir: File, method: PackMethod, unpack: Boolean) {
        val context = applicationContext
        acquireWake()
        try {
            val manifest0 = GamePackStore.loadManifest(context, appId)
            var manifest = reconcileManifest(dir, manifest0, appId, method)
            GamePackStore.saveManifest(context, appId, manifest)
            val toProcess = manifest.files.filter { if (unpack) it.status == PackFileStatus.PACKED else it.status == PackFileStatus.ORIG }
            val largest = toProcess.maxOfOrNull { it.origSize } ?: 0L
            val required = requiredFreeBytes(largest)
            val free = GamePackStore.freeBytes(dir)
            if (spaceDecision(free, required) == SpaceDecision.REFUSE) {
                val err = context.getString(R.string.gamepack_space_refused_body, formatBytes(required), formatBytes(free))
                GamePackJobs.set(GamePackJobState(appId, name, unpack, 0, 0, 0L, 0L, error = err, done = true))
                doneNotification(appId, context.getString(R.string.gamepack_notification_refused, name), err)
                return
            }
            val save: (GamePackManifest) -> Unit = {
                manifest = it
                GamePackStore.saveManifest(context, appId, it)
            }
            val onProgress: (PackProgress) -> Unit = { p ->
                GamePackJobs.set(GamePackJobState(appId, name, unpack, p.filesDone, p.filesTotal, p.bytesDone, p.bytesTotal))
                val now = System.currentTimeMillis()
                if (now - lastNotifyAt >= NOTIFY_MS) {
                    lastNotifyAt = now
                    main.post { runCatching { notifyProgress(appId, name, unpack, p) } }
                }
            }
            val result = if (unpack) {
                unpackGame(dir, manifest, { GamePackJobs.shouldStop(appId) }, onProgress, save)
            } else {
                packGame(dir, manifest, method, { GamePackJobs.shouldStop(appId) }, onProgress, save)
            }
            deletableOrphans(dir, manifest).forEach { it.delete() }
            when {
                result.cancelled -> {
                    GamePackJobs.set(GamePackJobState(appId, name, unpack, result.filesDone, 0, 0L, 0L, cancelled = true, done = true))
                    doneNotification(appId, context.getString(R.string.gamepack_notification_cancelled, name), null)
                }
                result.error != null -> {
                    GamePackJobs.set(GamePackJobState(appId, name, unpack, result.filesDone, 0, 0L, 0L, error = result.error, done = true))
                    doneNotification(appId, context.getString(R.string.gamepack_notification_failed, name, result.error), result.error)
                }
                else -> {
                    GamePackJobs.set(GamePackJobState(appId, name, unpack, result.filesDone, result.filesDone, 0L, 0L, done = true))
                    doneNotification(appId, context.getString(R.string.gamepack_notification_done, name), null)
                }
            }
        } finally {
            releaseWake()
            currentAppId = -1
            stopSelf()
        }
    }

    private fun notifyProgress(appId: Int, name: String, unpack: Boolean, p: PackProgress) {
        runCatching {
            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
                .notify(ID, buildNotification(appId, name, unpack, p.filesDone, p.filesTotal, p.fraction))
        }
    }

    private fun doneNotification(appId: Int, title: String, text: String?) {
        val builder = Notification.Builder(this, DONE_CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle(title)
            .setAutoCancel(true)
            .setContentIntent(openApp(appId))
        if (text != null) {
            builder.setContentText(text)
            builder.style = Notification.BigTextStyle().bigText(text)
        }
        runCatching {
            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).notify(DONE_ID + (appId and 0xFFFF), builder.build())
        }
    }

    private fun openApp(code: Int): PendingIntent =
        PendingIntent.getActivity(
            this, code,
            Intent(this, FrontEndActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

    private fun cancelPending(appId: Int): PendingIntent {
        val i = Intent(this, GamePackService::class.java).setAction(ACTION_CANCEL).putExtra(EXTRA_APP_ID, appId)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) PendingIntent.getForegroundService(this, appId, i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        else PendingIntent.getService(this, appId, i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    private fun buildNotification(appId: Int, name: String, unpack: Boolean, filesDone: Int, filesTotal: Int, fraction: Float): Notification {
        val b = Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(getString(if (unpack) R.string.gamepack_notification_unpacking else R.string.gamepack_notification_packing, name))
            .setContentText(if (filesTotal > 0) getString(R.string.gamepack_progress_files, filesDone, filesTotal) else "")
            .setOngoing(true).setOnlyAlertOnce(true)
            .setContentIntent(openApp(appId))
        b.setProgress(1000, (fraction * 1000).toInt(), false)
        b.addAction(Notification.Action.Builder(null, getString(R.string.gamepack_job_cancel), cancelPending(appId)).build())
        return b.build()
    }

    private fun startForegroundCompat(n: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) startForeground(ID, n, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        else startForeground(ID, n)
    }

    @SuppressLint("WakelockTimeout")
    private fun acquireWake() {
        if (wake == null) {
            wake = (getSystemService(POWER_SERVICE) as PowerManager)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "DroidDeck:gamepack").apply { setReferenceCounted(false) }
        }
        runCatching { if (wake?.isHeld != true) wake?.acquire() }
    }

    private fun releaseWake() {
        runCatching { if (wake?.isHeld == true) wake?.release() }
        wake = null
    }

    companion object {
        private const val CHANNEL = "gamepack"
        private const val DONE_CHANNEL = "gamepack_done"
        private const val ID = 7101
        private const val DONE_ID = 7200
        private const val NOTIFY_MS = 500L
        private const val ACTION_START = "com.droiddeck.launcher.gamepack.START"
        private const val ACTION_CANCEL = "com.droiddeck.launcher.gamepack.CANCEL"
        private const val EXTRA_APP_ID = "appId"
        private const val EXTRA_NAME = "name"
        private const val EXTRA_DIR = "dir"
        private const val EXTRA_METHOD = "method"
        private const val EXTRA_UNPACK = "unpack"

        private fun channels(context: Context) {
            val nm = context.getSystemService(NotificationManager::class.java) ?: return
            if (nm.getNotificationChannel(CHANNEL) == null) {
                nm.createNotificationChannel(
                    NotificationChannel(CHANNEL, context.getString(R.string.gamepack_notification_channel), NotificationManager.IMPORTANCE_LOW).apply {
                        description = context.getString(R.string.gamepack_notification_channel_hint)
                        setShowBadge(false)
                    },
                )
            }
            if (nm.getNotificationChannel(DONE_CHANNEL) == null) {
                nm.createNotificationChannel(
                    NotificationChannel(DONE_CHANNEL, context.getString(R.string.gamepack_notification_done_channel), NotificationManager.IMPORTANCE_DEFAULT),
                )
            }
        }

        fun start(context: Context, game: Library.SteamGame, method: PackMethod, unpack: Boolean) {
            val dir = game.gameFiles ?: return
            val app = context.applicationContext
            val intent = Intent(app, GamePackService::class.java).setAction(ACTION_START)
                .putExtra(EXTRA_APP_ID, game.appId)
                .putExtra(EXTRA_NAME, game.name)
                .putExtra(EXTRA_DIR, dir.path)
                .putExtra(EXTRA_METHOD, method.name)
                .putExtra(EXTRA_UNPACK, unpack)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) app.startForegroundService(intent) else app.startService(intent)
        }

        fun cancel(context: Context, appId: Int) {
            val app = context.applicationContext
            val intent = Intent(app, GamePackService::class.java).setAction(ACTION_CANCEL).putExtra(EXTRA_APP_ID, appId)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) app.startForegroundService(intent) else app.startService(intent)
        }
    }
}
