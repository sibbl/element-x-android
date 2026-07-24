/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.wearapp.notifications

import android.app.NotificationManager
import androidx.core.app.NotificationManagerCompat
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import io.element.android.watchbridge.contract.WatchCompanionSettings
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
        assertThat(doubleChannel.vibrationPattern?.toList()).containsExactly(0L, 1L).inOrder()

        assertThat(longChannel.id).isEqualTo(wearLocalNotificationChannelId(WatchNotificationVibrationPattern.LONG))
        assertThat(longChannel.shouldVibrate()).isTrue()
        assertThat(longChannel.vibrationPattern?.toList()).containsExactly(0L, 1L).inOrder()

        assertThat(tripleChannel.id).isEqualTo(wearLocalNotificationChannelId(WatchNotificationVibrationPattern.TRIPLE))
        assertThat(tripleChannel.shouldVibrate()).isTrue()
        assertThat(tripleChannel.vibrationPattern?.toList()).containsExactly(0L, 1L).inOrder()

        assertThat(pulseChannel.id).isEqualTo(wearLocalNotificationChannelId(WatchNotificationVibrationPattern.PULSE))
        assertThat(pulseChannel.shouldVibrate()).isTrue()
        assertThat(pulseChannel.vibrationPattern?.toList()).containsExactly(0L, 1L).inOrder()

        assertThat(escalatingChannel.id).isEqualTo(wearLocalNotificationChannelId(WatchNotificationVibrationPattern.ESCALATING))
        assertThat(escalatingChannel.shouldVibrate()).isTrue()
        assertThat(escalatingChannel.vibrationPattern?.toList()).containsExactly(0L, 1L).inOrder()
    }

    @Test
    fun `show uses one channel per source and effective preset pattern`() {
        val playedPatterns = mutableListOf<List<Long>>()
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
            explicitVibrationPlayer = { playedPatterns += it.toList() },
            explicitVibrationScheduler = { it() },
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
            .filter { it.id == "wear_companion_messages_v22_group_triple" }

        assertThat(createdManualChannels).hasSize(1)
        assertThat(createdManualChannels.single().shouldVibrate()).isTrue()
        assertThat(createdManualChannels.single().vibrationPattern?.toList()).containsExactly(0L, 1L).inOrder()
        assertThat(playedPatterns).containsExactlyElementsIn(
            List(5) { listOf(0L, 70L, 70L, 100L, 70L, 130L) },
        )
        assertThat(notificationManager.activeNotifications.single().notification.channelId)
            .isEqualTo("wear_companion_messages_v22_group_triple")
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
            "wear_companion_messages_v22_group_default",
            "wear_companion_messages_v22_group_silent",
        )
    }
}
