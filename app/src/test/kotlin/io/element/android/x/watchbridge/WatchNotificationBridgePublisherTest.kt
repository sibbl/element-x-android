/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.x.watchbridge

import com.google.common.truth.Truth.assertThat
import io.element.android.libraries.matrix.api.core.EventId
import io.element.android.libraries.matrix.api.core.RoomId
import io.element.android.libraries.matrix.api.core.SessionId
import io.element.android.libraries.matrix.api.core.ThreadId
import io.element.android.libraries.matrix.api.core.UserId
import io.element.android.libraries.push.impl.notifications.model.NotifiableMessageEvent
import io.element.android.watchbridge.contract.WatchDataPaths
import io.element.android.watchbridge.contract.WatchSync
import io.element.android.watchbridge.contract.WatchSyncEnvelope
import io.element.android.watchbridge.transport.WatchChannel
import io.element.android.watchbridge.transport.WatchTransport
import kotlinx.coroutines.test.runTest
import org.junit.Test

class WatchNotificationBridgePublisherTest {

    @Test
    fun `latest event per conversation is published once`() = runTest {
        val transport = RecordingTransport()
        val publisher = WatchNotificationBridgePublisherDelegate(
            transport = transport,
            imageLabel = "Image",
            clock = { 100L },
        )

        publisher.onMessageNotificationsRendered(
            listOf(
                aNotifiableMessageEvent(eventId = "\$first:server", timestamp = 1L, body = "First"),
                aNotifiableMessageEvent(eventId = "\$second:server", timestamp = 2L, body = "Second"),
            ),
        )

        assertThat(transport.published).hasSize(1)
        val (path, envelope) = transport.published.single()
        assertThat(path).isEqualTo(WatchDataPaths.notification("message:@alice:server:!room:server"))
        val payload = envelope.payload as WatchSync.MessageNotification
        assertThat(payload.notification.eventId).isEqualTo("\$second:server")
        assertThat(payload.notification.messageCount).isEqualTo(2)
        assertThat(envelope.expiresAtMs).isEqualTo(100L + 24L * 60L * 60L * 1000L)
    }

    @Test
    fun `thread clear deletes the published watch notification path`() = runTest {
        val transport = RecordingTransport()
        val publisher = WatchNotificationBridgePublisherDelegate(
            transport = transport,
            imageLabel = "Image",
            clock = { 100L },
        )
        val threadId = ThreadId("\$thread:server")

        publisher.onMessageNotificationsRendered(
            listOf(
                aNotifiableMessageEvent(
                    eventId = "\$thread-event:server",
                    timestamp = 5L,
                    body = "Thread update",
                    threadId = threadId,
                ),
            ),
        )
        publisher.onMessagesClearedForThread(
            sessionId = SessionId("@alice:server"),
            roomId = RoomId("!room:server"),
            threadId = threadId,
        )

        assertThat(transport.deleted).containsExactly(
            WatchDataPaths.notification("message:@alice:server:!room:server|\$thread:server"),
        )
    }

    private fun aNotifiableMessageEvent(
        eventId: String,
        timestamp: Long,
        body: String,
        threadId: ThreadId? = null,
    ): NotifiableMessageEvent = NotifiableMessageEvent(
        sessionId = SessionId("@alice:server"),
        roomId = RoomId("!room:server"),
        eventId = EventId(eventId),
        editedEventId = null,
        canBeReplaced = false,
        senderId = UserId("@bob:server"),
        noisy = false,
        timestamp = timestamp,
        senderDisambiguatedDisplayName = "Bob",
        body = body,
        imageUriString = null,
        imageMimeType = null,
        threadId = threadId,
        roomName = "Team Wear",
    )

    private class RecordingTransport : WatchTransport {
        val published = mutableListOf<Pair<String, WatchSyncEnvelope>>()
        val deleted = mutableListOf<String>()

        override suspend fun publishSync(path: String, envelope: WatchSyncEnvelope) {
            published += path to envelope
        }

        override suspend fun sendMessage(path: String, envelope: WatchSyncEnvelope): String = "node"

        override suspend fun deleteSync(path: String) {
            deleted += path
        }

        override suspend fun openChannel(path: String): WatchChannel? = null
    }
}