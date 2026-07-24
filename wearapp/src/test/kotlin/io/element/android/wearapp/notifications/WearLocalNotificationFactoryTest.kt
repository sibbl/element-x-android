/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.wearapp.notifications

import android.app.Notification
import android.app.PendingIntent
import android.graphics.Bitmap
import android.graphics.Color
import androidx.core.app.NotificationCompat
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import io.element.android.appconfig.parseWearCompanionDeepLink
import io.element.android.watchbridge.contract.WatchCompanionSettings
import io.element.android.watchbridge.contract.WatchConversationVibrationOverride
import io.element.android.watchbridge.contract.WatchFavoriteRoom
import io.element.android.watchbridge.contract.WatchMessageNotification
import io.element.android.watchbridge.contract.WatchNotificationMessagePreview
import io.element.android.watchbridge.contract.WatchNotificationVibrationPattern
import io.element.android.watchbridge.contract.WatchNotificationVibrationSettings
import io.element.android.watchbridge.contract.WatchRoomKind
import io.element.android.wearapp.R
import io.element.android.wearapp.ui.voice.VoiceRecorderActivity.Companion.EXTRA_RETURN_TO_EVENT_ID
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class WearLocalNotificationFactoryTest {
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val factory = WearLocalNotificationFactory(context)

    @Test
    fun `thread notification opens thread on tap without redundant thread action`() {
        val model = WatchMessageNotification(
            notificationKey = "message:@alice:server:!room:server|\$root:server",
            roomId = "!room:server",
            eventId = "\$event:server",
            threadRootEventId = "\$root:server",
            roomDisplayName = "Team Wear",
            senderDisplayName = "Bob",
            bodyText = "Hello from thread",
            timestampMs = 123L,
            messageCount = 2,
            isNoisy = true,
        )

        val notification = factory.build(model, generatedAtMs = 100L, expiresAtMs = 1_000L)

        assertThat(notification.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString())
            .startsWith("Team Wear")
        assertThat(notification.extras.getCharSequence(Notification.EXTRA_TEXT)?.toString())
            .isEqualTo("Bob: Hello from thread")

        val contentIntent = shadowOf(notification.contentIntent).savedIntent
        val deepLink = parseWearCompanionDeepLink(contentIntent.data)
        assertThat(deepLink?.roomId).isEqualTo("!room:server")
        assertThat(deepLink?.eventId).isNull()
        assertThat(deepLink?.threadRootEventId).isEqualTo("\$root:server")

        val actions = notification.actions.orEmpty()
        assertThat(actions.map { it.title.toString() }).containsExactly(
            context.getString(R.string.screen_wear_notification_mark_as_read),
            context.getString(R.string.screen_voice_recorder_title),
            context.getString(R.string.composer_reply),
        ).inOrder()
        val markAsReadIntent = shadowOf(actions[0].actionIntent as PendingIntent).savedIntent
        assertThat(markAsReadIntent.action).isEqualTo(WearNotificationActionReceiver.ACTION_MARK_AS_READ)
        assertThat(markAsReadIntent.component?.className).isEqualTo(WearNotificationActionActivity::class.java.name)
        assertThat(actions[0].semanticAction).isEqualTo(NotificationCompat.Action.SEMANTIC_ACTION_MARK_AS_READ)

        val voiceIntent = shadowOf(actions[1].actionIntent as PendingIntent).savedIntent
        assertThat(voiceIntent.component?.className).endsWith("VoiceRecorderActivity")
        assertThat(voiceIntent.getStringExtra("threadRootEventId")).isEqualTo("\$root:server")
        assertThat(voiceIntent.getStringExtra(EXTRA_RETURN_TO_EVENT_ID)).isEqualTo("\$event:server")

        assertThat(actions[2].remoteInputs.orEmpty().single().resultKey).isEqualTo(WearNotificationActionReceiver.RESULT_KEY_REPLY_TEXT)

        val dismissIntent = shadowOf(notification.deleteIntent).savedIntent
        assertThat(dismissIntent.action).isEqualTo(WearNotificationActionReceiver.ACTION_DISMISS)
    }

    @Test
    fun `plain message notification offers start thread action`() {
        val model = WatchMessageNotification(
            notificationKey = "message:@alice:server:!room:server",
            roomId = "!room:server",
            eventId = "\$event:server",
            roomDisplayName = "Team Wear",
            senderDisplayName = "Bob",
            bodyText = "Hello there",
            timestampMs = 456L,
            isNoisy = false,
        )

        val notification = factory.build(model, generatedAtMs = 100L, expiresAtMs = null)

        assertThat(notification.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString())
            .startsWith("Team Wear")
        assertThat(notification.extras.getCharSequence(Notification.EXTRA_TEXT)?.toString())
            .isEqualTo("Bob: Hello there")

        val contentDeepLink = parseWearCompanionDeepLink(shadowOf(notification.contentIntent).savedIntent.data)
        assertThat(contentDeepLink?.eventId).isNull()
        assertThat(contentDeepLink?.threadRootEventId).isEqualTo("\$event:server")

        val threadIntent = shadowOf(notification.actions.orEmpty()[2].actionIntent as PendingIntent).savedIntent
        val threadDeepLink = parseWearCompanionDeepLink(threadIntent.data)

        assertThat(notification.actions.orEmpty()[2].title.toString()).isEqualTo(context.getString(R.string.screen_message_detail_start_thread))
        assertThat(threadDeepLink?.roomId).isEqualTo("!room:server")
        assertThat(threadDeepLink?.threadRootEventId).isEqualTo("\$event:server")
    }

    @Test
    fun `text conversation notification uses messaging style with preview history`() {
        val model = WatchMessageNotification(
            notificationKey = "message:@alice:server:!room:server",
            roomId = "!room:server",
            eventId = "\$latest:server",
            roomDisplayName = "Team Wear",
            roomKind = WatchRoomKind.GROUP,
            senderDisplayName = "Bob",
            bodyText = "Latest update",
            timestampMs = 789L,
            messageCount = 3,
            previewMessages = listOf(
                WatchNotificationMessagePreview(
                    senderDisplayName = "Bob",
                    bodyText = "First update",
                    timestampMs = 700L,
                ),
                WatchNotificationMessagePreview(
                    senderDisplayName = "Bob",
                    bodyText = "Second update",
                    timestampMs = 750L,
                ),
                WatchNotificationMessagePreview(
                    senderDisplayName = "Bob",
                    bodyText = "Latest update",
                    timestampMs = 789L,
                ),
            ),
            isNoisy = true,
        )

        val notification = factory.build(model, generatedAtMs = 100L, expiresAtMs = null)
        val messagingStyle = NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(notification)

        assertThat(notification.extras.getString(Notification.EXTRA_TEMPLATE)).contains("MessagingStyle")
        assertThat(messagingStyle).isNotNull()
        val extractedMessagingStyle = checkNotNull(messagingStyle)
        assertThat(notification.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString())
            .startsWith("Team Wear")
        assertThat(notification.extras.getCharSequence(Notification.EXTRA_TEXT)?.toString())
            .isEqualTo("Bob: Latest update")
        assertThat(extractedMessagingStyle.conversationTitle?.toString()).isEqualTo("Team Wear")
        assertThat(extractedMessagingStyle.messages.map { it.text.toString() }).containsExactly(
            "First update",
            "Second update",
            "Latest update",
        ).inOrder()
        assertThat(extractedMessagingStyle.messages.map { it.person?.name?.toString() }).containsExactly(
            "Bob",
            "Bob",
            "Bob",
        ).inOrder()
        assertThat(extractedMessagingStyle.messages.map { it.person?.key }).containsNoDuplicates()
    }

    @Test
    fun `text conversation notification preserves multiline message text`() {
        val model = WatchMessageNotification(
            notificationKey = "message:@alice:server:!room:server",
            roomId = "!room:server",
            eventId = "\$latest:server",
            roomDisplayName = "Team Wear",
            roomKind = WatchRoomKind.GROUP,
            senderDisplayName = "Bob",
            bodyText = "First line\nSecond line\n\nThird line",
            timestampMs = 789L,
            messageCount = 1,
            previewMessages = listOf(
                WatchNotificationMessagePreview(
                    senderDisplayName = "Bob",
                    bodyText = "First line\nSecond line\n\nThird line",
                    timestampMs = 789L,
                ),
            ),
            isNoisy = true,
        )

        val notification = factory.build(model, generatedAtMs = 100L, expiresAtMs = null)
        val messagingStyle = checkNotNull(NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(notification))

        assertThat(notification.extras.getCharSequence(Notification.EXTRA_TEXT)?.toString())
            .isEqualTo("Bob: First line\nSecond line\n\nThird line")
        assertThat(messagingStyle.messages.single().text.toString())
            .isEqualTo("First line\nSecond line\n\nThird line")
    }

    @Test
    fun `watch local notification uses the local vibration channel even when bridged event is quiet`() {
        val model = WatchMessageNotification(
            notificationKey = "message:@alice:server:!room:server",
            roomId = "!room:server",
            eventId = "\$event:server",
            roomDisplayName = "Team Wear",
            roomKind = WatchRoomKind.GROUP,
            senderDisplayName = "Bob",
            bodyText = "Hello there",
            timestampMs = 456L,
            isNoisy = false,
        )

        val notification = factory.build(model, generatedAtMs = 100L, expiresAtMs = null)

        assertThat(notification.channelId).isEqualTo("wear_companion_messages_v21_group_default")
    }

    @Test
    fun `notification vibration channel follows the configured group dm and favorite patterns`() {
        val configuredFactory = createFactory(
            settings = WatchCompanionSettings(
                notificationVibrations = WatchNotificationVibrationSettings(
                    groups = WatchNotificationVibrationPattern.TRIPLE,
                    dms = WatchNotificationVibrationPattern.PULSE,
                    favoriteGroups = WatchNotificationVibrationPattern.ESCALATING,
                    favoriteDms = WatchNotificationVibrationPattern.LONG,
                ),
            ),
            knownRooms = listOf(
                WatchFavoriteRoom(
                    roomId = "!favorite-group:server",
                    displayName = "Favorite Team Wear",
                    kind = WatchRoomKind.GROUP,
                    isFavorite = true,
                ),
                WatchFavoriteRoom(
                    roomId = "!favorite-dm:server",
                    displayName = "Alice",
                    kind = WatchRoomKind.DM,
                    isFavorite = true,
                ),
            ),
        )

        val groupNotification = configuredFactory.build(
            model(
                roomId = "!group:server",
                roomKind = WatchRoomKind.GROUP,
            ),
            generatedAtMs = 100L,
            expiresAtMs = null,
        )
        val dmNotification = configuredFactory.build(
            model(
                roomId = "!dm:server",
                roomKind = WatchRoomKind.DM,
            ),
            generatedAtMs = 100L,
            expiresAtMs = null,
        )
        val favoriteGroupNotification = configuredFactory.build(
            model(
                roomId = "!favorite-group:server",
                roomKind = WatchRoomKind.GROUP,
            ),
            generatedAtMs = 100L,
            expiresAtMs = null,
        )
        val favoriteDmNotification = configuredFactory.build(
            model(
                roomId = "!favorite-dm:server",
                roomKind = WatchRoomKind.DM,
            ),
            generatedAtMs = 100L,
            expiresAtMs = null,
        )

        assertThat(groupNotification.channelId)
            .isEqualTo("wear_companion_messages_v21_group_triple")
        assertThat(dmNotification.channelId)
            .isEqualTo("wear_companion_messages_v21_dm_pulse")
        assertThat(favoriteGroupNotification.channelId)
            .isEqualTo("wear_companion_messages_v21_favorite_group_escalating")
        assertThat(favoriteDmNotification.channelId)
            .isEqualTo("wear_companion_messages_v21_favorite_dm_long")
    }

    @Test
    fun `quiet thread reply uses room custom vibration from notification settings snapshot`() {
        val notification = createFactory().build(
            model(
                roomId = "!group:server",
                roomKind = WatchRoomKind.GROUP,
            ).copy(
                threadRootEventId = "\$thread:server",
                isNoisy = false,
                vibrationSettingsSnapshot = WatchNotificationVibrationSettings(
                    groups = WatchNotificationVibrationPattern.CUSTOM,
                    groupsCustomPattern = "100,200",
                ),
            ),
            generatedAtMs = 100L,
            expiresAtMs = null,
        )

        assertThat(notification.channelId)
            .isEqualTo("wear_companion_messages_v21_group_custom_fd0a5f89")
    }

    @Test
    fun `manual vibration notifications are still alerting so Wear OS can peek them`() {
        val notification = createFactory(
            settings = WatchCompanionSettings(
                notificationVibrations = WatchNotificationVibrationSettings(
                    groups = WatchNotificationVibrationPattern.TRIPLE,
                ),
            ),
        ).build(
            model(
                roomId = "!group:server",
                roomKind = WatchRoomKind.GROUP,
            ),
            generatedAtMs = 100L,
            expiresAtMs = null,
        )

        assertThat(notification.channelId)
            .isEqualTo("wear_companion_messages_v21_group_triple")
        assertThat(notification.flags and Notification.FLAG_ONLY_ALERT_ONCE).isEqualTo(0)
        assertThat(notification.group).isNotEqualTo("silent")
        assertThat(notification.groupAlertBehavior).isNotEqualTo(Notification.GROUP_ALERT_SUMMARY)
    }

    @Test
    fun `explicit vibration override wins over the room classification`() {
        val notification = createFactory(
            settings = WatchCompanionSettings(
                notificationVibrations = WatchNotificationVibrationSettings(
                    groups = WatchNotificationVibrationPattern.DEFAULT,
                    dms = WatchNotificationVibrationPattern.DEFAULT,
                    favoriteGroups = WatchNotificationVibrationPattern.DEFAULT,
                    favoriteDms = WatchNotificationVibrationPattern.DEFAULT,
                ),
            ),
        ).build(
            model(
                roomId = "!dm:server",
                roomKind = WatchRoomKind.DM,
            ).copy(vibrationPatternOverride = WatchNotificationVibrationPattern.ESCALATING),
            generatedAtMs = 100L,
            expiresAtMs = null,
        )

        assertThat(notification.channelId)
            .isEqualTo("wear_companion_messages_v21_notification_override_escalating")
    }

    @Test
    fun `room-specific vibration override wins over the room classification`() {
        val notification = createFactory(
            settings = WatchCompanionSettings(
                notificationVibrations = WatchNotificationVibrationSettings(
                    groups = WatchNotificationVibrationPattern.DEFAULT,
                    dms = WatchNotificationVibrationPattern.DEFAULT,
                    conversationOverrides = listOf(
                        WatchConversationVibrationOverride(
                            roomId = "!dm:server",
                            pattern = WatchNotificationVibrationPattern.ESCALATING,
                        ),
                    ),
                ),
            ),
        ).build(
            model(
                roomId = "!dm:server",
                roomKind = WatchRoomKind.DM,
            ),
            generatedAtMs = 100L,
            expiresAtMs = null,
        )

        assertThat(notification.channelId)
            .isEqualTo("wear_companion_messages_v21_room_${"!dm:server".stableShortHash()}_escalating")
    }

    @Test
    fun `notification vibration snapshot is used when cached settings are stale`() {
        val notification = createFactory(
            settings = WatchCompanionSettings(
                notificationVibrations = WatchNotificationVibrationSettings(
                    groups = WatchNotificationVibrationPattern.DEFAULT,
                    favoriteGroups = WatchNotificationVibrationPattern.DEFAULT,
                ),
            ),
            knownRooms = listOf(
                WatchFavoriteRoom(
                    roomId = "!group:server",
                    displayName = "Favorite group",
                    kind = WatchRoomKind.GROUP,
                    isFavorite = true,
                ),
            ),
        ).build(
            model(
                roomId = "!group:server",
                roomKind = WatchRoomKind.GROUP,
            ).copy(
                vibrationSettingsSnapshot = WatchNotificationVibrationSettings(
                    favoriteGroups = WatchNotificationVibrationPattern.TRIPLE,
                ),
            ),
            generatedAtMs = 100L,
            expiresAtMs = null,
        )

        assertThat(notification.channelId)
            .isEqualTo(
                wearLocalNotificationChannelId(
                    WearResolvedNotificationVibration(
                        pattern = WatchNotificationVibrationPattern.TRIPLE,
                        source = WearNotificationVibrationSource.FavoriteGroups,
                    ),
                ),
            )
    }

    @Test
    fun `explicit triple vibration override is selected`() {
        val vibration = createFactory().selectedVibration(
            model(roomId = "!room:server").copy(
                vibrationPatternOverride = WatchNotificationVibrationPattern.TRIPLE,
            ),
        )

        assertThat(vibration).isEqualTo(
            WearResolvedNotificationVibration(
                pattern = WatchNotificationVibrationPattern.TRIPLE,
                source = WearNotificationVibrationSource.NotificationOverride,
            ),
        )
    }

    @Test
    fun `explicit triple vibration notification uses correct channel`() {
        val model = model(roomId = "!room:server").copy(
            vibrationPatternOverride = WatchNotificationVibrationPattern.TRIPLE,
        )

        val notification = createFactory().build(
            model,
            generatedAtMs = 100L,
            expiresAtMs = null,
        )

        assertThat(notification.channelId).startsWith("wear_companion_messages_v21_notification_override_triple")
    }

    @Test
    fun `image notification uses big picture style`() {
        val imageFactory = WearLocalNotificationFactory(
            context = context,
            roomAvatarProvider = { null },
            imagePreviewProvider = { notification -> notification.imagePreviewBytes },
            settingsProvider = { WatchCompanionSettings() },
            roomInfoProvider = { null },
        )
        val model = WatchMessageNotification(
            notificationKey = "message:@alice:server:!room:server",
            roomId = "!room:server",
            eventId = "\$image:server",
            roomDisplayName = "Team Wear",
            roomKind = WatchRoomKind.GROUP,
            senderDisplayName = "Bob",
            bodyText = "Image",
            timestampMs = 789L,
            isNoisy = true,
            imagePreviewBytes = createPreviewBytes(),
        )

        val notification = imageFactory.build(model, generatedAtMs = 100L, expiresAtMs = null)

        assertThat(notification.extras.getString(Notification.EXTRA_TEMPLATE)).contains("BigPictureStyle")
        @Suppress("DEPRECATION")
        val picture = notification.extras.getParcelable<Bitmap>(Notification.EXTRA_PICTURE)
        assertThat(picture).isNotNull()
    }

    private fun createPreviewBytes(): ByteArray {
        val bitmap = Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888)
        return try {
            bitmap.eraseColor(Color.MAGENTA)
            ByteArrayOutputStream().use { output ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)
                output.toByteArray()
            }
        } finally {
            bitmap.recycle()
        }
    }

    private fun createFactory(
        settings: WatchCompanionSettings = WatchCompanionSettings(),
        knownRooms: List<WatchFavoriteRoom> = emptyList(),
    ): WearLocalNotificationFactory {
        return WearLocalNotificationFactory(
            context = context,
            roomAvatarProvider = { null },
            imagePreviewProvider = { notification -> notification.imagePreviewBytes },
            settingsProvider = { settings },
            roomInfoProvider = { roomId -> knownRooms.firstOrNull { room -> room.roomId == roomId } },
        )
    }

    private fun model(
        roomId: String = "!room:server",
        roomKind: WatchRoomKind = WatchRoomKind.GROUP,
    ): WatchMessageNotification {
        return WatchMessageNotification(
            notificationKey = "message:@alice:server:$roomId",
            roomId = roomId,
            eventId = "\$event:server",
            roomDisplayName = "Team Wear",
            roomKind = roomKind,
            senderDisplayName = "Bob",
            bodyText = "Hello there",
            timestampMs = 456L,
            isNoisy = false,
        )
    }
}
