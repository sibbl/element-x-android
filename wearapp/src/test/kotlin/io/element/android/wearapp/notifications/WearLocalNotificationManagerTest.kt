/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.wearapp.notifications

import android.app.NotificationManager
import androidx.test.core.app.ApplicationProvider
import androidx.core.app.NotificationManagerCompat
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

    @Before
    fun clearNotifications() {
        NotificationManagerCompat.from(context).cancelAll()
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
    fun `custom local watch notification channels stay silent so custom watch haptics own the pattern`() {
        val silentChannel = buildWearLocalNotificationChannel(context, WatchNotificationVibrationPattern.SILENT)
        val doubleChannel = buildWearLocalNotificationChannel(context, WatchNotificationVibrationPattern.DOUBLE)
        val longChannel = buildWearLocalNotificationChannel(context, WatchNotificationVibrationPattern.LONG)
        val tripleChannel = buildWearLocalNotificationChannel(context, WatchNotificationVibrationPattern.TRIPLE)
        val pulseChannel = buildWearLocalNotificationChannel(context, WatchNotificationVibrationPattern.PULSE)
        val escalatingChannel = buildWearLocalNotificationChannel(context, WatchNotificationVibrationPattern.ESCALATING)
        val customChannel = buildWearLocalNotificationChannel(context, WatchNotificationVibrationPattern.CUSTOM)

        assertThat(silentChannel.id).isEqualTo(wearLocalNotificationChannelId(WatchNotificationVibrationPattern.SILENT))
        assertThat(silentChannel.shouldVibrate()).isFalse()
        assertThat(silentChannel.vibrationPattern).isNull()
        assertThat(silentChannel.sound).isNull()

        assertThat(doubleChannel.id).isEqualTo(wearLocalNotificationChannelId(WatchNotificationVibrationPattern.DOUBLE))
        assertThat(doubleChannel.shouldVibrate()).isFalse()
        assertThat(doubleChannel.vibrationPattern).isNull()

        assertThat(longChannel.id).isEqualTo(wearLocalNotificationChannelId(WatchNotificationVibrationPattern.LONG))
        assertThat(longChannel.shouldVibrate()).isFalse()
        assertThat(longChannel.vibrationPattern).isNull()

        assertThat(tripleChannel.id).isEqualTo(wearLocalNotificationChannelId(WatchNotificationVibrationPattern.TRIPLE))
        assertThat(tripleChannel.shouldVibrate()).isFalse()
        assertThat(tripleChannel.vibrationPattern).isNull()

        assertThat(pulseChannel.id).isEqualTo(wearLocalNotificationChannelId(WatchNotificationVibrationPattern.PULSE))
        assertThat(pulseChannel.shouldVibrate()).isFalse()
        assertThat(pulseChannel.vibrationPattern).isNull()

        assertThat(escalatingChannel.id).isEqualTo(wearLocalNotificationChannelId(WatchNotificationVibrationPattern.ESCALATING))
        assertThat(escalatingChannel.shouldVibrate()).isFalse()
        assertThat(escalatingChannel.vibrationPattern).isNull()

        assertThat(customChannel.id).isEqualTo(wearLocalNotificationChannelId(WatchNotificationVibrationPattern.CUSTOM))
        assertThat(customChannel.shouldVibrate()).isFalse()
        assertThat(customChannel.vibrationPattern).isNull()
    }

    @Test
    fun `show triggers manual watch haptics for custom patterns`() {
        val playedPatterns = mutableListOf<WearResolvedNotificationVibration>()
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
            hapticPlayer = object : WearLocalNotificationHapticPlayer {
                override fun play(vibration: WearResolvedNotificationVibration) {
                    playedPatterns += vibration
                }
            },
            notificationsAllowedProvider = { true },
        )

        manager.show(
            notification = WatchMessageNotification(
                notificationKey = "manual-haptic",
                roomId = "!room:server",
                eventId = "\$event:server",
                roomDisplayName = "Team Wear",
                timestampMs = 100L,
            ),
            generatedAtMs = 100L,
            expiresAtMs = null,
        )

        assertThat(playedPatterns).containsExactly(
            WearResolvedNotificationVibration(pattern = WatchNotificationVibrationPattern.TRIPLE),
        )
    }

    @Test
    fun `show triggers manual watch haptics for valid custom waveform patterns`() {
        val playedPatterns = mutableListOf<WearResolvedNotificationVibration>()
        val manager = WearLocalNotificationManager(
            context = context,
            factory = WearLocalNotificationFactory(
                context = context,
                settingsProvider = { WatchCompanionSettings() },
                roomInfoProvider = { null },
            ),
            hapticPlayer = object : WearLocalNotificationHapticPlayer {
                override fun play(vibration: WearResolvedNotificationVibration) {
                    playedPatterns += vibration
                }
            },
            notificationsAllowedProvider = { true },
        )

        manager.show(
            notification = WatchMessageNotification(
                notificationKey = "manual-custom-haptic",
                roomId = "!room:server",
                eventId = "\$event-custom:server",
                roomDisplayName = "Team Wear",
                timestampMs = 100L,
                vibrationPatternOverride = WatchNotificationVibrationPattern.CUSTOM,
                customVibrationPattern = "120 60 240",
            ),
            generatedAtMs = 100L,
            expiresAtMs = null,
        )

        assertThat(playedPatterns).containsExactly(
            WearResolvedNotificationVibration(
                pattern = WatchNotificationVibrationPattern.CUSTOM,
                customTimingsMs = listOf(0L, 120L, 60L, 240L),
            ),
        )
    }

    @Test
    fun `show triggers manual watch haptics for saved category custom waveforms`() {
        val playedPatterns = mutableListOf<WearResolvedNotificationVibration>()
        val manager = WearLocalNotificationManager(
            context = context,
            factory = WearLocalNotificationFactory(
                context = context,
                settingsProvider = {
                    WatchCompanionSettings(
                        notificationVibrations = WatchNotificationVibrationSettings(
                            groups = WatchNotificationVibrationPattern.CUSTOM,
                            groupsCustomPattern = "120 60 240",
                        ),
                    )
                },
                roomInfoProvider = { null },
            ),
            hapticPlayer = object : WearLocalNotificationHapticPlayer {
                override fun play(vibration: WearResolvedNotificationVibration) {
                    playedPatterns += vibration
                }
            },
            notificationsAllowedProvider = { true },
        )

        manager.show(
            notification = WatchMessageNotification(
                notificationKey = "manual-custom-category-haptic",
                roomId = "!room:server",
                eventId = "\$event-category-custom:server",
                roomDisplayName = "Team Wear",
                timestampMs = 100L,
            ),
            generatedAtMs = 100L,
            expiresAtMs = null,
        )

        assertThat(playedPatterns).containsExactly(
            WearResolvedNotificationVibration(
                pattern = WatchNotificationVibrationPattern.CUSTOM,
                customTimingsMs = listOf(0L, 120L, 60L, 240L),
            ),
        )
    }

    @Test
    fun `show does not trigger manual watch haptics for default or silent patterns`() {
        val playedPatterns = mutableListOf<WearResolvedNotificationVibration>()
        val hapticPlayer = object : WearLocalNotificationHapticPlayer {
            override fun play(vibration: WearResolvedNotificationVibration) {
                playedPatterns += vibration
            }
        }

        val defaultManager = WearLocalNotificationManager(
            context = context,
            factory = WearLocalNotificationFactory(
                context = context,
                settingsProvider = { WatchCompanionSettings() },
                roomInfoProvider = { null },
            ),
            hapticPlayer = hapticPlayer,
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
            hapticPlayer = hapticPlayer,
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

        assertThat(playedPatterns).isEmpty()
    }
}