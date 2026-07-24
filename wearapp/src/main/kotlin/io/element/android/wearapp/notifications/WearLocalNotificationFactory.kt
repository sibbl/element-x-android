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
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.core.app.NotificationCompat
import androidx.core.app.Person
import androidx.core.app.RemoteInput
import io.element.android.watchbridge.contract.WatchCompanionSettings
import io.element.android.watchbridge.contract.WatchFavoriteRoom
import io.element.android.watchbridge.contract.WatchMessageNotification
import io.element.android.watchbridge.contract.WatchNotificationMessagePreview
import io.element.android.watchbridge.contract.WatchNotificationVibrationPattern
import io.element.android.watchbridge.contract.WatchRoomKind
import io.element.android.wearapp.R
import io.element.android.wearapp.WearApp
import io.element.android.wearapp.ui.buildWearLaunchIntent
import io.element.android.wearapp.ui.voice.VoiceRecorderActivity.Companion.EXTRA_RETURN_TO_EVENT_ID
import io.element.android.wearapp.ui.voice.VoiceRecorderActivity

internal data class WearResolvedNotificationVibration(
    val pattern: WatchNotificationVibrationPattern,
    val source: WearNotificationVibrationSource = WearNotificationVibrationSource.Generic,
    val customPattern: String = "",
)

internal sealed interface WearNotificationVibrationSource {
    val idPart: String

    data object Generic : WearNotificationVibrationSource {
        override val idPart: String = "generic"
    }

    data object Groups : WearNotificationVibrationSource {
        override val idPart: String = "group"
    }

    data object Dms : WearNotificationVibrationSource {
        override val idPart: String = "dm"
    }

    data object FavoriteGroups : WearNotificationVibrationSource {
        override val idPart: String = "favorite_group"
    }

    data object FavoriteDms : WearNotificationVibrationSource {
        override val idPart: String = "favorite_dm"
    }

    data class Conversation(
        val roomId: String,
    ) : WearNotificationVibrationSource {
        override val idPart: String = "room_${roomId.stableShortHash()}"
    }

    data object NotificationOverride : WearNotificationVibrationSource {
        override val idPart: String = "notification_override"
    }
}

internal class WearLocalNotificationFactory(
    private val context: Context,
    private val roomAvatarProvider: (String) -> ByteArray? = { roomId ->
        (context.applicationContext as? WearApp)?.bridgeClient?.getCachedAvatar(roomId)
    },
    private val imagePreviewProvider: (WatchMessageNotification) -> ByteArray? = { notification ->
        notification.imagePreviewBytes
            ?: (context.applicationContext as? WearApp)?.bridgeClient?.getCachedMediaPreview(notification.roomId, notification.eventId)
    },
    private val settingsProvider: () -> WatchCompanionSettings = {
        (context.applicationContext as? WearApp)?.bridgeClient?.companionSettings?.value ?: WatchCompanionSettings()
    },
    private val roomInfoProvider: (String) -> WatchFavoriteRoom? = { roomId ->
        (context.applicationContext as? WearApp)?.bridgeClient?.favorites?.value?.firstOrNull { room -> room.roomId == roomId }
    },
) {
    internal fun selectedVibration(notification: WatchMessageNotification): WearResolvedNotificationVibration {
        val vibrationSettings = notification.vibrationSettingsSnapshot ?: settingsProvider().notificationVibrations
        notification.vibrationPatternOverride?.let {
            return resolveConfiguredVibration(
                pattern = it,
                source = WearNotificationVibrationSource.NotificationOverride,
                customPattern = notification.customVibrationPattern.orEmpty()
            )
        }
        vibrationSettings.conversationOverrides
            .firstOrNull { it.roomId == notification.roomId }
            ?.let { roomOverride ->
                roomOverride.pattern?.let { pattern ->
                    return resolveConfiguredVibration(
                        pattern = pattern,
                        source = WearNotificationVibrationSource.Conversation(roomOverride.roomId),
                        customPattern = roomOverride.customPattern,
                    )
                }
            }
        val roomInfo = roomInfoProvider(notification.roomId)
        val roomKind = roomInfo?.kind ?: notification.roomKind
        val isFavorite = roomInfo?.isFavorite == true
        val patterns = vibrationSettings
        val pattern = when {
            isFavorite && roomKind == WatchRoomKind.DM -> patterns.favoriteDms
            isFavorite && roomKind == WatchRoomKind.GROUP -> patterns.favoriteGroups
            roomKind == WatchRoomKind.DM -> patterns.dms
            else -> patterns.groups
        }
        val customPattern = when {
            isFavorite && roomKind == WatchRoomKind.DM -> patterns.favoriteDmsCustomPattern
            isFavorite && roomKind == WatchRoomKind.GROUP -> patterns.favoriteGroupsCustomPattern
            roomKind == WatchRoomKind.DM -> patterns.dmsCustomPattern
            else -> patterns.groupsCustomPattern
        }
        val source = when {
            isFavorite && roomKind == WatchRoomKind.DM -> WearNotificationVibrationSource.FavoriteDms
            isFavorite && roomKind == WatchRoomKind.GROUP -> WearNotificationVibrationSource.FavoriteGroups
            roomKind == WatchRoomKind.DM -> WearNotificationVibrationSource.Dms
            else -> WearNotificationVibrationSource.Groups
        }
        return resolveConfiguredVibration(
            pattern = pattern,
            source = source,
            customPattern = customPattern,
        )
    }

    fun build(
        notification: WatchMessageNotification,
        generatedAtMs: Long,
        expiresAtMs: Long?,
        selectedVibration: WearResolvedNotificationVibration = selectedVibration(notification),
        channelId: String = wearLocalNotificationChannelId(selectedVibration),
    ): Notification {
        val title = notificationTitle(notification)
        val contentText = notificationContentText(notification, title)
        val imagePreview = imagePreview(notification)

        return NotificationCompat.Builder(context, channelId)
            .setSmallIcon(android.R.drawable.stat_notify_chat)
            .setContentTitle(title)
            .setContentText(contentText)
            .setStyle(notificationStyle(notification, title, contentText, imagePreview))
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setAutoCancel(true)
            // Watch-local alerting is controlled by the selected watch notification channel.
            // Do not inherit phone-side silence here: the phone app may be muted while the
            // watch companion stays enabled and should still vibrate according to the watch
            // companion settings.
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setOnlyAlertOnce(false)
            .setWhen(notification.timestampMs)
            .setShowWhen(true)
            .setNumber(notification.messageCount)
            .setContentIntent(contentIntent(notification))
            .setDeleteIntent(dismissIntent(notification.notificationKey, generatedAtMs))
            .addAction(markAsReadAction(notification, generatedAtMs))
            .addAction(voiceAction(notification, title))
            .apply {
                // Tapping a notification is already the direct way into an existing thread.
                // Keep the explicit start-thread action only for non-thread messages.
                if (notification.threadRootEventId == null) addAction(threadAction(notification))
            }
            .addAction(replyAction(notification, generatedAtMs))
            .apply {
                largeIcon(notification)?.let(::setLargeIcon)
                val timeoutAfterMs = expiresAtMs?.minus(System.currentTimeMillis())?.takeIf { it > 0L }
                timeoutAfterMs?.let(::setTimeoutAfter)
            }
            .build()
            .also {
                it.extras.putCharSequence(Notification.EXTRA_TITLE, title)
                it.extras.putCharSequence(Notification.EXTRA_TEXT, contentText)
            }
    }

    private fun resolveConfiguredVibration(
        pattern: WatchNotificationVibrationPattern,
        source: WearNotificationVibrationSource,
        customPattern: String = "",
    ): WearResolvedNotificationVibration {
        return WearResolvedNotificationVibration(
            pattern = pattern,
            source = source,
            customPattern = customPattern,
        )
    }

    private fun notificationTitle(notification: WatchMessageNotification): String {
        val roomTitle = notification.roomDisplayName.takeIf { it.isNotBlank() }
        val senderTitle = notification.senderDisplayName?.takeIf { it.isNotBlank() }
        val isGroupConversation = notification.threadRootEventId != null || notification.roomKind == WatchRoomKind.GROUP
        return when {
            isGroupConversation && roomTitle != null -> roomTitle
            senderTitle != null -> senderTitle
            roomTitle != null -> roomTitle
            else -> context.getString(R.string.app_name)
        }
    }

    private fun notificationContentText(
        notification: WatchMessageNotification,
        fallbackTitle: String,
    ): String {
        val bodyText = notification.bodyText?.takeIf { it.isNotBlank() }
        val senderTitle = notification.senderDisplayName?.takeIf { it.isNotBlank() }
        val isGroupConversation = notification.threadRootEventId != null || notification.roomKind == WatchRoomKind.GROUP
        return when {
            isGroupConversation && senderTitle != null && bodyText != null -> "$senderTitle: $bodyText"
            bodyText != null -> bodyText
            senderTitle != null -> senderTitle
            else -> notification.roomDisplayName.takeIf { it.isNotBlank() } ?: fallbackTitle
        }
    }

    private fun largeIcon(notification: WatchMessageNotification): Bitmap? {
        val imageBytes = roomAvatarProvider(notification.roomId) ?: return null
        return BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size)
    }

    private fun imagePreview(notification: WatchMessageNotification): Bitmap? {
        val imageBytes = imagePreviewProvider(notification) ?: return null
        return BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size)
    }

    private fun notificationStyle(
        notification: WatchMessageNotification,
        title: String,
        contentText: String,
        imagePreview: Bitmap?,
    ): NotificationCompat.Style {
        return if (imagePreview != null) {
            NotificationCompat.BigPictureStyle()
                .setBigContentTitle(title)
                .setSummaryText(contentText)
                .bigPicture(imagePreview)
        } else {
            messagingStyle(notification, contentText)
        }
    }

    private fun messagingStyle(
        notification: WatchMessageNotification,
        fallbackContentText: String,
    ): NotificationCompat.MessagingStyle {
        val style = NotificationCompat.MessagingStyle(
            Person.Builder()
                .setName(context.getString(R.string.app_name))
                .setKey("wear-local-notification")
                .build(),
        )
        val isGroupConversation = notification.threadRootEventId != null || notification.roomKind == WatchRoomKind.GROUP
        style.setGroupConversation(isGroupConversation)
        if (isGroupConversation) {
            notification.roomDisplayName.takeIf { it.isNotBlank() }?.let {
                style.conversationTitle = it
            }
        }
        notificationPreviewMessages(notification, fallbackContentText).forEach { preview ->
            style.addMessage(
                NotificationCompat.MessagingStyle.Message(
                    preview.bodyText,
                    preview.timestampMs.takeIf { it > 0L } ?: notification.timestampMs,
                    previewPerson(preview, notification),
                ),
            )
        }
        return style
    }

    private fun notificationPreviewMessages(
        notification: WatchMessageNotification,
        fallbackContentText: String,
    ): List<WatchNotificationMessagePreview> {
        val previewMessages = notification.previewMessages.filter { it.bodyText.isNotBlank() }
        if (previewMessages.isNotEmpty()) return previewMessages
        return listOf(
            WatchNotificationMessagePreview(
                senderDisplayName = notification.senderDisplayName,
                bodyText = fallbackContentText,
                timestampMs = notification.timestampMs,
            ),
        )
    }

    private fun previewPerson(
        preview: WatchNotificationMessagePreview,
        notification: WatchMessageNotification,
    ): Person {
        val senderName = preview.senderDisplayName?.takeIf { it.isNotBlank() }
            ?: notification.senderDisplayName?.takeIf { it.isNotBlank() }
            ?: notification.roomDisplayName.takeIf { it.isNotBlank() }
            ?: context.getString(R.string.app_name)
        val senderKey = buildString {
            append(notification.notificationKey)
            append(':')
            append(senderName)
            append(':')
            append(preview.timestampMs)
            append(':')
            append(preview.bodyText.hashCode())
        }
        return Person.Builder()
            .setName(senderName)
            .setKey(senderKey)
            .build()
    }

    private fun contentIntent(notification: WatchMessageNotification): PendingIntent {
        val intent = buildWearLaunchIntent(
            context = context,
            roomId = notification.roomId,
            eventId = null,
            threadRootEventId = notification.threadRootEventId ?: notification.eventId,
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
        ).setClass(context, WearNotificationActionActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            context,
            requestCode(notification.notificationKey, WearNotificationActionReceiver.ACTION_MARK_AS_READ),
            markAsReadIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Action.Builder(
            android.R.drawable.checkbox_on_background,
            context.getString(R.string.screen_wear_notification_mark_as_read),
            pendingIntent,
        )
            .setSemanticAction(NotificationCompat.Action.SEMANTIC_ACTION_MARK_AS_READ)
            .setShowsUserInterface(true)
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
            .putExtra(EXTRA_RETURN_TO_EVENT_ID, notification.eventId)
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
