/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.wearapp.bridge

import com.google.common.truth.Truth.assertThat
import io.element.android.watchbridge.contract.WatchCompanionSettings
import io.element.android.watchbridge.contract.WatchMessageNotification
import io.element.android.watchbridge.contract.WatchSync
import io.element.android.watchbridge.contract.WatchSyncEnvelope
import io.element.android.watchbridge.contract.WatchTimelineItemKind
import org.junit.Test

class WearBridgeClientNotificationProjectionTest {
    @Test
    fun `room notification projects to open conversation timeline item`() {
        val item = aMessageNotification().toTimelineItemForOpenConversation()

        assertThat(item).isNotNull()
        assertThat(item!!.eventId).isEqualTo("\$event:server")
        assertThat(item.roomId).isEqualTo("!room:server")
        assertThat(item.senderDisplayName).isEqualTo("Alice")
        assertThat(item.kind).isEqualTo(WatchTimelineItemKind.TEXT)
        assertThat(item.bodyText).isEqualTo("Hello from phone")
        assertThat(item.readableByTts).isTrue()
    }

    @Test
    fun `thread notification projects only to open thread item`() {
        val notification = aMessageNotification(threadRootEventId = "\$root:server")

        val timelineItem = notification.toTimelineItemForOpenConversation()
        val threadItem = notification.toThreadItemForOpenThread()

        assertThat(timelineItem).isNull()
        assertThat(threadItem).isNotNull()
        assertThat(threadItem!!.eventId).isEqualTo("\$event:server")
        assertThat(threadItem.threadRootEventId).isEqualTo("\$root:server")
        assertThat(threadItem.bodyText).isEqualTo("Hello from phone")
    }

    @Test
    fun `blank notification body is not projected into cached timelines`() {
        val notification = aMessageNotification(bodyText = " ")

        assertThat(notification.toTimelineItemForOpenConversation()).isNull()
        assertThat(notification.copy(threadRootEventId = "\$root:server").toThreadItemForOpenThread()).isNull()
    }

    @Test
    fun `test notifications are never projected into conversation history`() {
        val notification = aMessageNotification()
            .copy(isTestNotification = true)

        assertThat(notification.toTimelineItemForOpenConversation()).isNull()
        assertThat(notification.copy(threadRootEventId = "\$root:server").toThreadItemForOpenThread()).isNull()
    }

    @Test
    fun `incoming settings are dispatched before message notifications from the same batch`() {
        val notificationEnvelope = WatchSyncEnvelope(
            generatedAtMs = 1L,
            payload = WatchSync.MessageNotification(aMessageNotification()),
        )
        val settingsEnvelope = WatchSyncEnvelope(
            generatedAtMs = 2L,
            payload = WatchSync.SettingsUpdate(WatchCompanionSettings()),
        )

        val ordered = orderIncomingEnvelopesForDispatch(listOf(notificationEnvelope, settingsEnvelope))

        assertThat(ordered).containsExactly(settingsEnvelope, notificationEnvelope).inOrder()
    }

    private fun aMessageNotification(
        bodyText: String? = "Hello from phone",
        threadRootEventId: String? = null,
    ) = WatchMessageNotification(
        notificationKey = "notification-key",
        roomId = "!room:server",
        eventId = "\$event:server",
        threadRootEventId = threadRootEventId,
        roomDisplayName = "Project",
        senderDisplayName = "Alice",
        bodyText = bodyText,
        timestampMs = 123L,
    )
}
