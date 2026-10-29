package com.droiddeck.launcher.core

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.provider.Settings
import com.droiddeck.launcher.FrontEndActivity
import com.droiddeck.launcher.R
import com.droiddeck.launcher.core.privileged.DiagnosticsLog

/**
 * The persistent counterpart of the card's step-advancement Toast.
 * A Toast fires while the user is in Settings and is gone by the time
 * they return, so a genuine step advancement while backgrounded posts
 * this notification instead: it stays until tapped, and its action
 * button lands directly on the next step's destination.
 */
object StepGuideNotifier {
    private const val CHANNEL_ID = "setup-guide"
    private const val NOTIFICATION_ID = 4
    private var lastNotifiedState: ConnectionCardState? = null

    fun post(context: Context, state: ConnectionCardState): Boolean {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return false
        if (!manager.areNotificationsEnabled()) return false
        if (lastNotifiedState == state) return true
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                context.getString(R.string.step_guide_channel_name),
                NotificationManager.IMPORTANCE_HIGH,
            ).apply {
                description = context.getString(R.string.step_guide_channel_desc)
            },
        )
        val openApp = PendingIntent.getActivity(
            context, 0,
            Intent(context, FrontEndActivity::class.java)
                .setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val (textRes, actionLabelRes, action) = when (state) {
            ConnectionCardState.NEED_DEV_OPTIONS -> Triple(
                R.string.step_guide_text_dev,
                R.string.connection_card_action_about,
                PendingIntent.getActivity(
                    context, 10,
                    Intent(Settings.ACTION_DEVICE_INFO_SETTINGS)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    PendingIntent.FLAG_IMMUTABLE,
                ),
            )
            ConnectionCardState.NEED_WIRELESS_ADB -> Triple(
                R.string.connection_card_advance_wireless,
                R.string.connection_card_action_wireless,
                PendingIntent.getActivity(
                    context, 11,
                    WirelessAdbFix.wirelessDebuggingIntent(),
                    PendingIntent.FLAG_IMMUTABLE,
                ),
            )
            ConnectionCardState.NEED_PAIRING -> Triple(
                R.string.connection_card_advance_pair,
                R.string.step_guide_action_open,
                openApp,
            )
            ConnectionCardState.READY_TO_START -> Triple(
                R.string.connection_card_advance_ready,
                R.string.step_guide_action_open,
                openApp,
            )
            else -> return false
        }
        val text = context.getString(textRes)
        manager.notify(
            NOTIFICATION_ID,
            Notification.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_stat_session)
                .setContentTitle(context.getString(R.string.step_guide_title))
                .setContentText(text)
                .setStyle(Notification.BigTextStyle().bigText(text))
                .setContentIntent(openApp)
                .addAction(Notification.Action.Builder(null, context.getString(actionLabelRes), action).build())
                .setAutoCancel(true)
                .build(),
        )
        lastNotifiedState = state
        DiagnosticsLog.log(
            context, "connection-card", "guide-posted",
            fields = mapOf("state" to state.name),
        )
        return true
    }

    fun cancel(context: Context) {
        if (lastNotifiedState == null) return
        lastNotifiedState = null
        context.getSystemService(NotificationManager::class.java)?.cancel(NOTIFICATION_ID)
        DiagnosticsLog.log(context, "connection-card", "guide-cancelled", fields = emptyMap())
    }
}
