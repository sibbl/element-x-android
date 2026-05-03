/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.wearapp.notifications

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.RemoteInput
import io.element.android.watchbridge.contract.WatchMessageNotification
import io.element.android.wearapp.R
import io.element.android.wearapp.ui.buildWearLaunchIntent
import io.element.android.wearapp.ui.voice.VoiceRecorderActivity

internal class WearLocalNotificationFactory(
    private val context: Context,
) {

    fun build(
        notification: WatchMessageNotification,
        generatedAtMs: Long,
        expiresAtMs: Long?,
    ): Notification {
        val title = notification.roomDisplayName.takeIf { it.isNotBlank() }
            ?: notification.senderDisplayName
            ?: context.getString(R.string.app_name)
        val contentText = notification.bodyText?.takeIf { it.isNotBlank() }
            ?: notification.senderDisplayName
            ?: title

        return NotificationCompat.Builder(context, WearLocalNotificationManager.CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_chat)
            .setContentTitle(title)
            .setContentText(contentText)
            .setStyle(NotificationCompat.BigTextStyle().bigText(contentText))
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setAutoCancel(true)
            .setSilent(!notification.isNoisy)
            .setWhen(notification.timestampMs)
            .setShowWhen(true)
            .setNumber(notification.messageCount)
            .setContentIntent(contentIntent(notification))
            .setDeleteIntent(dismissIntent(notification.notificationKey, generatedAtMs))
            .addAction(markAsReadAction(notification, generatedAtMs))
            .addAction(voiceAction(notification, title))
            .addAction(threadAction(notification))
            .addAction(replyAction(notification, generatedAtMs))
            .apply {
                val timeoutAfterMs = expiresAtMs?.minus(System.currentTimeMillis())?.takeIf { it > 0L }
                timeoutAfterMs?.let(::setTimeoutAfter)
            }
            .build()
    }

    private fun contentIntent(notification: WatchMessageNotification): PendingIntent {
        val intent = buildWearLaunchIntent(
            context = context,
            roomId = notification.roomId,
            eventId = notification.eventId,
            threadRootEventId = notification.threadRootEventId,
        )
        return PendingIntent.getActivity(
            context,
            requestCode(notification.notificationKey, "content"),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun dismissIntent(notificationKey: String, generatedAtMs: Long): PendingIntent {
        val intent = baseBroadcastIntent(
            action = WearNotificationActionReceiver.ACTION_DISMISS,
            notificationKey = notificationKey,
            generatedAtMs = generatedAtMs,
        )
        return PendingIntent.getBroadcast(
            context,
            requestCode(notificationKey, WearNotificationActionReceiver.ACTION_DISMISS),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun markAsReadAction(notification: WatchMessageNotification, generatedAtMs: Long): NotificationCompat.Action {
        val markAsReadIntent = baseBroadcastIntent(
            action = WearNotificationActionReceiver.ACTION_MARK_AS_READ,
            notificationKey = notification.notificationKey,
            generatedAtMs = generatedAtMs,
            notification = notification,
        )
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            requestCode(notification.notificationKey, WearNotificationActionReceiver.ACTION_MARK_AS_READ),
            markAsReadIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Action.Builder(
            android.R.drawable.ic_menu_delete,
            context.getString(R.string.screen_wear_notification_mark_as_read),
            pendingIntent,
        )
            .setSemanticAction(NotificationCompat.Action.SEMANTIC_ACTION_MARK_AS_READ)
            .setShowsUserInterface(false)
            .build()
    }

    private fun replyAction(notification: WatchMessageNotification, generatedAtMs: Long): NotificationCompat.Action {
        val replyIntent = baseBroadcastIntent(
            action = WearNotificationActionReceiver.ACTION_REPLY,
            notificationKey = notification.notificationKey,
            generatedAtMs = generatedAtMs,
            notification = notification,
        )
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            requestCode(notification.notificationKey, WearNotificationActionReceiver.ACTION_REPLY),
            replyIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
        )
        val remoteInput = RemoteInput.Builder(WearNotificationActionReceiver.RESULT_KEY_REPLY_TEXT)
            .setLabel(context.getString(R.string.composer_reply))
            .build()
        return NotificationCompat.Action.Builder(
            android.R.drawable.ic_menu_send,
            context.getString(R.string.composer_reply),
            pendingIntent,
        )
            .addRemoteInput(remoteInput)
            .setSemanticAction(NotificationCompat.Action.SEMANTIC_ACTION_REPLY)
            .setShowsUserInterface(false)
            .build()
    }

    private fun voiceAction(notification: WatchMessageNotification, roomDisplayName: String): NotificationCompat.Action {
        val voiceIntent = Intent(context, VoiceRecorderActivity::class.java)
            .putExtra("roomId", notification.roomId)
            .putExtra("roomDisplayName", roomDisplayName)
            .apply {
                if (notification.threadRootEventId != null) {
                    putExtra("threadRootEventId", notification.threadRootEventId)
                } else {
                    putExtra("inReplyToEventId", notification.eventId)
                }
            }
        val pendingIntent = PendingIntent.getActivity(
            context,
            requestCode(notification.notificationKey, "voice"),
            voiceIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Action.Builder(
            android.R.drawable.ic_btn_speak_now,
            context.getString(R.string.screen_voice_recorder_title),
            pendingIntent,
        ).build()
    }

    private fun threadAction(notification: WatchMessageNotification): NotificationCompat.Action {
        val threadRootEventId = notification.threadRootEventId ?: notification.eventId
        val intent = buildWearLaunchIntent(
            context = context,
            roomId = notification.roomId,
            threadRootEventId = threadRootEventId,
        )
        val pendingIntent = PendingIntent.getActivity(
            context,
            requestCode(notification.notificationKey, "thread"),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val titleRes = if (notification.threadRootEventId != null) {
            R.string.screen_message_detail_open_thread
        } else {
            R.string.screen_message_detail_start_thread
        }
        return NotificationCompat.Action.Builder(
            android.R.drawable.ic_menu_more,
            context.getString(titleRes),
            pendingIntent,
        ).build()
    }

    private fun baseBroadcastIntent(
        action: String,
        notificationKey: String,
        generatedAtMs: Long,
        notification: WatchMessageNotification? = null,
    ): Intent = Intent(context, WearNotificationActionReceiver::class.java)
        .setAction(action)
        .putExtra(WearNotificationActionReceiver.EXTRA_NOTIFICATION_KEY, notificationKey)
        .putExtra(WearNotificationActionReceiver.EXTRA_NOTIFICATION_ID, wearLocalNotificationId(notificationKey))
        .putExtra(WearNotificationActionReceiver.EXTRA_GENERATED_AT_MS, generatedAtMs)
        .apply {
            notification?.let {
                putExtra(WearNotificationActionReceiver.EXTRA_ROOM_ID, it.roomId)
                putExtra(WearNotificationActionReceiver.EXTRA_EVENT_ID, it.eventId)
                putExtra(WearNotificationActionReceiver.EXTRA_THREAD_ROOT_EVENT_ID, it.threadRootEventId)
            }
        }

    private fun requestCode(notificationKey: String, action: String): Int = "$notificationKey:$action".hashCode()
}