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
    fun `thread notification exposes mark as read voice thread and reply actions in requested order`() {
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
        assertThat(deepLink?.eventId).isEqualTo("\$event:server")
        assertThat(deepLink?.threadRootEventId).isEqualTo("\$root:server")

        val actions = notification.actions.orEmpty()
        assertThat(actions.map { it.title.toString() }).containsExactly(
            context.getString(R.string.screen_wear_notification_mark_as_read),
            context.getString(R.string.screen_voice_recorder_title),
            context.getString(R.string.screen_message_detail_open_thread),
            context.getString(R.string.composer_reply),
        ).inOrder()
        val markAsReadIntent = shadowOf(actions[0].actionIntent as PendingIntent).savedIntent
        assertThat(markAsReadIntent.action).isEqualTo(WearNotificationActionReceiver.ACTION_MARK_AS_READ)
        assertThat(markAsReadIntent.component?.className).isEqualTo(WearNotificationActionActivity::class.java.name)
        assertThat(actions[0].semanticAction).isEqualTo(NotificationCompat.Action.SEMANTIC_ACTION_MARK_AS_READ)

        val voiceIntent = shadowOf(actions[1].actionIntent as PendingIntent).savedIntent
        assertThat(voiceIntent.component?.className).endsWith("VoiceRecorderActivity")
        assertThat(voiceIntent.getStringExtra("threadRootEventId")).isEqualTo("\$root:server")

        val threadIntent = shadowOf(actions[2].actionIntent as PendingIntent).savedIntent
        val threadDeepLink = parseWearCompanionDeepLink(threadIntent.data)
        assertThat(threadDeepLink?.roomId).isEqualTo("!room:server")
        assertThat(threadDeepLink?.threadRootEventId).isEqualTo("\$root:server")

        assertThat(actions[3].remoteInputs.orEmpty().single().resultKey).isEqualTo(WearNotificationActionReceiver.RESULT_KEY_REPLY_TEXT)

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

        assertThat(notification.channelId).isEqualTo(WearLocalNotificationManager.CHANNEL_ID)
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
            .isEqualTo(wearLocalNotificationChannelId(WatchNotificationVibrationPattern.TRIPLE))
        assertThat(dmNotification.channelId)
            .isEqualTo(wearLocalNotificationChannelId(WatchNotificationVibrationPattern.PULSE))
        assertThat(favoriteGroupNotification.channelId)
            .isEqualTo(wearLocalNotificationChannelId(WatchNotificationVibrationPattern.ESCALATING))
        assertThat(favoriteDmNotification.channelId)
            .isEqualTo(wearLocalNotificationChannelId(WatchNotificationVibrationPattern.LONG))
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
            .isEqualTo(wearLocalNotificationChannelId(WatchNotificationVibrationPattern.ESCALATING))
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
            .isEqualTo(wearLocalNotificationChannelId(WatchNotificationVibrationPattern.ESCALATING))
    }

    @Test
    fun `saved custom category vibration uses the configured waveform`() {
        val vibration = createFactory(
            settings = WatchCompanionSettings(
                notificationVibrations = WatchNotificationVibrationSettings(
                    groups = WatchNotificationVibrationPattern.CUSTOM,
                    groupsCustomPattern = "120 60 240",
                ),
            ),
        ).selectedVibration(
            model(
                roomId = "!group:server",
                roomKind = WatchRoomKind.GROUP,
            ),
        )

        assertThat(vibration).isEqualTo(
            WearResolvedNotificationVibration(
                pattern = WatchNotificationVibrationPattern.CUSTOM,
                customTimingsMs = listOf(0L, 120L, 60L, 240L),
            ),
        )
    }

    @Test
    fun `invalid custom category vibration falls back to the default channel`() {
        val notification = createFactory(
            settings = WatchCompanionSettings(
                notificationVibrations = WatchNotificationVibrationSettings(
                    groups = WatchNotificationVibrationPattern.CUSTOM,
                    groupsCustomPattern = "oops nope",
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
            .isEqualTo(wearLocalNotificationChannelId(WatchNotificationVibrationPattern.DEFAULT))
    }

    @Test
    fun `invalid custom room vibration falls back to the default channel`() {
        val notification = createFactory(
            settings = WatchCompanionSettings(
                notificationVibrations = WatchNotificationVibrationSettings(
                    groups = WatchNotificationVibrationPattern.TRIPLE,
                    conversationOverrides = listOf(
                        WatchConversationVibrationOverride(
                            roomId = "!room:server",
                            pattern = WatchNotificationVibrationPattern.CUSTOM,
                            customPattern = "oops nope",
                        ),
                    ),
                ),
            ),
        ).build(
            model(roomId = "!room:server"),
            generatedAtMs = 100L,
            expiresAtMs = null,
        )

        assertThat(notification.channelId)
            .isEqualTo(wearLocalNotificationChannelId(WatchNotificationVibrationPattern.DEFAULT))
    }

    @Test
    fun `explicit custom vibration override uses the notification custom pattern`() {
        val vibration = createFactory().selectedVibration(
            model(roomId = "!room:server").copy(
                vibrationPatternOverride = WatchNotificationVibrationPattern.CUSTOM,
                customVibrationPattern = "120 60 240",
            ),
        )

        assertThat(vibration).isEqualTo(
            WearResolvedNotificationVibration(
                pattern = WatchNotificationVibrationPattern.CUSTOM,
                customTimingsMs = listOf(0L, 120L, 60L, 240L),
            ),
        )
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
