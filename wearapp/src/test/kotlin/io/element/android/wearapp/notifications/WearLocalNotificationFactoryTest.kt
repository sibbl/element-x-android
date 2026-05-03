/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.wearapp.notifications

import android.app.PendingIntent
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import io.element.android.appconfig.parseWearCompanionDeepLink
import io.element.android.watchbridge.contract.WatchMessageNotification
import io.element.android.wearapp.R
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

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

        val contentIntent = shadowOf(notification.contentIntent).savedIntent
        val deepLink = parseWearCompanionDeepLink(contentIntent.data)
        assertThat(deepLink?.roomId).isEqualTo("!room:server")
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

    val threadIntent = shadowOf(notification.actions.orEmpty()[2].actionIntent as PendingIntent).savedIntent
        val threadDeepLink = parseWearCompanionDeepLink(threadIntent.data)

        assertThat(notification.actions.orEmpty()[2].title.toString()).isEqualTo(context.getString(R.string.screen_message_detail_start_thread))
        assertThat(threadDeepLink?.roomId).isEqualTo("!room:server")
        assertThat(threadDeepLink?.threadRootEventId).isEqualTo("\$event:server")
    }
}