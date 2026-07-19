/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.wearapp.notifications

import android.app.NotificationChannel
import android.app.NotificationManager
import androidx.core.app.NotificationManagerCompat
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import io.element.android.watchbridge.contract.WatchCompanionSettings
import io.element.android.watchbridge.contract.WatchConversationVibrationOverride
import io.element.android.watchbridge.contract.WatchMessageNotification
import io.element.android.watchbridge.contract.WatchNotificationVibrationPattern
import io.element.android.watchbridge.contract.WatchNotificationVibrationSettings
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class WearLocalNotificationManagerTest {
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val notificationManager = context.getSystemService(NotificationManager::class.java)

    @Before
    fun clearNotifications() {
        NotificationManagerCompat.from(context).cancelAll()
        notificationManager.notificationChannels.forEach { channel ->
            notificationManager.deleteNotificationChannel(channel.id)
        }
    }

    @Test
    fun `default local watch notification channel uses system vibration without sound`() {
        val channel = buildWearLocalNotificationChannel(context, WatchNotificationVibrationPattern.DEFAULT)

        assertThat(channel.id).isEqualTo(WearLocalNotificationManager.CHANNEL_ID)
        assertThat(channel.importance).isEqualTo(NotificationManager.IMPORTANCE_HIGH)
        assertThat(channel.shouldVibrate()).isTrue()
        assertThat(channel.vibrationPattern).isNull()
        assertThat(channel.sound).isNull()
    }

    @Test
    fun `manual local watch notification channels own their full vibration waveform`() {
        val silentChannel = buildWearLocalNotificationChannel(context, WatchNotificationVibrationPattern.SILENT)
        val doubleChannel = buildWearLocalNotificationChannel(context, WatchNotificationVibrationPattern.DOUBLE)
        val longChannel = buildWearLocalNotificationChannel(context, WatchNotificationVibrationPattern.LONG)
        val tripleChannel = buildWearLocalNotificationChannel(context, WatchNotificationVibrationPattern.TRIPLE)
        val pulseChannel = buildWearLocalNotificationChannel(context, WatchNotificationVibrationPattern.PULSE)
        val escalatingChannel = buildWearLocalNotificationChannel(context, WatchNotificationVibrationPattern.ESCALATING)

        assertThat(silentChannel.id).isEqualTo(wearLocalNotificationChannelId(WatchNotificationVibrationPattern.SILENT))
        assertThat(silentChannel.shouldVibrate()).isFalse()
        assertThat(silentChannel.vibrationPattern).isNull()
        assertThat(silentChannel.sound).isNull()

        assertThat(doubleChannel.id).isEqualTo(wearLocalNotificationChannelId(WatchNotificationVibrationPattern.DOUBLE))
        assertThat(doubleChannel.shouldVibrate()).isTrue()
        assertThat(doubleChannel.vibrationPattern?.toList()).isEqualTo(listOf(0L, 90L, 90L, 170L))

        assertThat(longChannel.id).isEqualTo(wearLocalNotificationChannelId(WatchNotificationVibrationPattern.LONG))
        assertThat(longChannel.shouldVibrate()).isTrue()
        assertThat(longChannel.vibrationPattern?.toList()).isEqualTo(listOf(0L, 800L))

        assertThat(tripleChannel.id).isEqualTo(wearLocalNotificationChannelId(WatchNotificationVibrationPattern.TRIPLE))
        assertThat(tripleChannel.shouldVibrate()).isTrue()
        assertThat(tripleChannel.vibrationPattern?.toList()).isEqualTo(listOf(0L, 70L, 70L, 100L, 70L, 130L))

        assertThat(pulseChannel.id).isEqualTo(wearLocalNotificationChannelId(WatchNotificationVibrationPattern.PULSE))
        assertThat(pulseChannel.shouldVibrate()).isTrue()
        assertThat(pulseChannel.vibrationPattern?.toList()).isEqualTo(listOf(0L, 120L, 110L, 120L, 110L, 120L, 110L, 120L))

        assertThat(escalatingChannel.id).isEqualTo(wearLocalNotificationChannelId(WatchNotificationVibrationPattern.ESCALATING))
        assertThat(escalatingChannel.shouldVibrate()).isTrue()
        assertThat(escalatingChannel.vibrationPattern?.toList()).isEqualTo(listOf(0L, 60L, 70L, 110L, 70L, 220L))
    }

    @Test
    fun `custom local watch notification channel uses the selected waveform`() {
        val vibration = WearResolvedNotificationVibration(
            pattern = WatchNotificationVibrationPattern.CUSTOM,
            customTimingsMs = listOf(0L, 120L, 60L, 240L),
        )

        val channel = buildWearLocalNotificationChannel(context, vibration)

        assertThat(channel.id).startsWith("wear_companion_messages_v16_generic_custom_")
        assertThat(channel.shouldVibrate()).isTrue()
        assertThat(channel.vibrationPattern?.toList()).isEqualTo(listOf(0L, 120L, 60L, 240L))
        assertThat(channel.sound).isNull()
    }

    @Test
    fun `show uses one channel per source and effective preset pattern`() {
        val manager = WearLocalNotificationManager(
            context = context,
            factory = WearLocalNotificationFactory(
                context = context,
                settingsProvider = {
                    WatchCompanionSettings(
                        notificationVibrations = WatchNotificationVibrationSettings(
                            groups = WatchNotificationVibrationPattern.TRIPLE,
                        ),
                    )
                },
                roomInfoProvider = { null },
            ),
            notificationsAllowedProvider = { true },
        )

        repeat(5) { index ->
            manager.show(
                notification = WatchMessageNotification(
                    notificationKey = "same-room-notification",
                    roomId = "!room:server",
                    eventId = "\$event-$index:server",
                    roomDisplayName = "Team Wear",
                    timestampMs = 100L + index,
                ),
                generatedAtMs = 100L + index,
                expiresAtMs = null,
            )
        }

        val createdManualChannels = notificationManager.notificationChannels
            .filter { it.id == "wear_companion_messages_v16_group_triple" }

        assertThat(createdManualChannels).hasSize(1)
        assertThat(createdManualChannels.single().vibrationPattern?.toList())
            .isEqualTo(listOf(0L, 70L, 70L, 100L, 70L, 130L))
        assertThat(notificationManager.activeNotifications.single().notification.channelId)
            .isEqualTo("wear_companion_messages_v16_group_triple")
    }

    @Test
    fun `show creates custom waveform channels from the selected pattern`() {
        val manager = WearLocalNotificationManager(
            context = context,
            factory = WearLocalNotificationFactory(
                context = context,
                settingsProvider = { WatchCompanionSettings() },
                roomInfoProvider = { null },
            ),
            notificationsAllowedProvider = { true },
        )

        repeat(2) { index ->
            manager.show(
                notification = WatchMessageNotification(
                    notificationKey = "manual-custom-haptic-$index",
                    roomId = "!room:server",
                    eventId = "\$event-custom-$index:server",
                    roomDisplayName = "Team Wear",
                    timestampMs = 100L + index,
                    vibrationPatternOverride = WatchNotificationVibrationPattern.CUSTOM,
                    customVibrationPattern = "120 60 240",
                ),
                generatedAtMs = 100L + index,
                expiresAtMs = null,
            )
        }

        val customChannels = notificationManager.notificationChannels
            .filter { it.id.startsWith("wear_companion_messages_v16_notification_override_custom_") }

        assertThat(customChannels.map { it.id }).hasSize(1)
        customChannels.forEach { channel ->
            assertThat(channel.shouldVibrate()).isTrue()
            assertThat(channel.vibrationPattern?.toList()).isEqualTo(listOf(0L, 120L, 60L, 240L))
            assertThat(channel.sound).isNull()
        }
    }

    @Test
    fun `show routes around a stale room-specific custom waveform channel before posting`() {
        val roomId = "!dm:server"
        val desiredVibration = WearResolvedNotificationVibration(
            pattern = WatchNotificationVibrationPattern.CUSTOM,
            customTimingsMs = listOf(0L, 120L, 60L, 240L),
            source = WearNotificationVibrationSource.Conversation(roomId),
        )
        val channelId = wearLocalNotificationChannelId(desiredVibration)
        notificationManager.createNotificationChannel(
            NotificationChannel(
                channelId,
                "Broken custom room channel",
                NotificationManager.IMPORTANCE_HIGH,
            ).apply {
                setSound(null, null)
                enableVibration(false)
            },
        )
        assertThat(notificationManager.getNotificationChannel(channelId).shouldVibrate()).isFalse()

        val manager = WearLocalNotificationManager(
            context = context,
            factory = WearLocalNotificationFactory(
                context = context,
                settingsProvider = {
                    WatchCompanionSettings(
                        notificationVibrations = WatchNotificationVibrationSettings(
                            conversationOverrides = listOf(
                                WatchConversationVibrationOverride(
                                    roomId = roomId,
                                    pattern = WatchNotificationVibrationPattern.CUSTOM,
                                    customPattern = "120 60 240",
                                ),
                            ),
                        ),
                    )
                },
                roomInfoProvider = { null },
            ),
            notificationsAllowedProvider = { true },
        )

        manager.show(
            notification = WatchMessageNotification(
                notificationKey = "room-custom-haptic",
                roomId = roomId,
                eventId = "\$event-room-custom:server",
                roomDisplayName = "Alice",
                timestampMs = 100L,
            ),
            generatedAtMs = 100L,
            expiresAtMs = null,
        )

        val repairedChannelId = notificationManager.activeNotifications.single().notification.channelId
        assertThat(repairedChannelId).startsWith("${channelId}_repair_")
        val repairedChannel = notificationManager.getNotificationChannel(repairedChannelId)
        assertThat(repairedChannel.shouldVibrate()).isTrue()
        assertThat(repairedChannel.vibrationPattern?.toList()).isEqualTo(listOf(0L, 120L, 60L, 240L))
        assertThat(notificationManager.getNotificationChannel(channelId).shouldVibrate()).isFalse()
    }

    @Test
    fun `show keeps default and silent patterns on their fixed channels`() {
        val defaultManager = WearLocalNotificationManager(
            context = context,
            factory = WearLocalNotificationFactory(
                context = context,
                settingsProvider = { WatchCompanionSettings() },
                roomInfoProvider = { null },
            ),
            notificationsAllowedProvider = { true },
        )
        defaultManager.show(
            notification = WatchMessageNotification(
                notificationKey = "default-haptic",
                roomId = "!room:server",
                eventId = "\$event-default:server",
                roomDisplayName = "Team Wear",
                timestampMs = 100L,
            ),
            generatedAtMs = 100L,
            expiresAtMs = null,
        )

        val silentManager = WearLocalNotificationManager(
            context = context,
            factory = WearLocalNotificationFactory(
                context = context,
                settingsProvider = {
                    WatchCompanionSettings(
                        notificationVibrations = WatchNotificationVibrationSettings(
                            groups = WatchNotificationVibrationPattern.SILENT,
                        ),
                    )
                },
                roomInfoProvider = { null },
            ),
            notificationsAllowedProvider = { true },
        )
        silentManager.show(
            notification = WatchMessageNotification(
                notificationKey = "silent-haptic",
                roomId = "!room:silent",
                eventId = "\$event-silent:server",
                roomDisplayName = "Team Wear",
                timestampMs = 100L,
            ),
            generatedAtMs = 100L,
            expiresAtMs = null,
        )

        val activeChannelIds = notificationManager.activeNotifications
            .map { it.notification.channelId }

        assertThat(activeChannelIds).containsAtLeast(
            "wear_companion_messages_v16_group_default",
            "wear_companion_messages_v16_group_silent",
        )
    }
}
