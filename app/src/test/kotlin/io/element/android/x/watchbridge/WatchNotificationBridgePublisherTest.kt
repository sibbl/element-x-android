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
import io.element.android.libraries.push.impl.notifications.model.ResolvedPushEvent
import io.element.android.watchbridge.contract.WatchBridgeSerialization
import io.element.android.watchbridge.contract.WatchCompanionSettings
import io.element.android.watchbridge.contract.WatchConversationVibrationOverride
import io.element.android.watchbridge.contract.WatchDataPaths
import io.element.android.watchbridge.contract.WatchNotificationMessagePreview
import io.element.android.watchbridge.contract.WatchNotificationVibrationPattern
import io.element.android.watchbridge.contract.WatchNotificationVibrationSettings
import io.element.android.watchbridge.contract.WatchProtocol
import io.element.android.watchbridge.contract.WatchRoomKind
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
        assertThat(payload.notification.previewMessages).containsExactly(
            WatchNotificationMessagePreview(
                senderDisplayName = "Bob",
                bodyText = "First",
                timestampMs = 1L,
            ),
            WatchNotificationMessagePreview(
                senderDisplayName = "Bob",
                bodyText = "Second",
                timestampMs = 2L,
            ),
        ).inOrder()
        assertThat(payload.notification.roomKind).isEqualTo(WatchRoomKind.GROUP)
        assertThat(envelope.expiresAtMs).isEqualTo(100L + 24L * 60L * 60L * 1000L)
    }

    @Test
    fun `preview history keeps the latest four messages in timestamp order`() = runTest {
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
                aNotifiableMessageEvent(eventId = "\$third:server", timestamp = 3L, body = "Third"),
                aNotifiableMessageEvent(eventId = "\$fourth:server", timestamp = 4L, body = "Fourth"),
                aNotifiableMessageEvent(eventId = "\$fifth:server", timestamp = 5L, body = "Fifth"),
            ),
        )

        val payload = transport.published.single().second.payload as WatchSync.MessageNotification

        assertThat(payload.notification.messageCount).isEqualTo(5)
        assertThat(payload.notification.previewMessages.map { it.bodyText }).containsExactly(
            "Second",
            "Third",
            "Fourth",
            "Fifth",
        ).inOrder()
    }

    @Test
    fun `direct room notifications preserve dm room kind`() = runTest {
        val transport = RecordingTransport()
        val publisher = WatchNotificationBridgePublisherDelegate(
            transport = transport,
            imageLabel = "Image",
            clock = { 100L },
        )

        publisher.onMessageNotificationsRendered(
            listOf(
                aNotifiableMessageEvent(
                    eventId = "\$dm:server",
                    timestamp = 2L,
                    body = "Hello Alice",
                    roomIsDm = true,
                ),
            ),
        )

        val payload = transport.published.single().second.payload as WatchSync.MessageNotification
        assertThat(payload.notification.roomKind).isEqualTo(WatchRoomKind.DM)
    }

    @Test
    fun `room-specific vibration override is embedded in published notifications`() = runTest {
        val transport = RecordingTransport()
        val publisher = WatchNotificationBridgePublisherDelegate(
            transport = transport,
            imageLabel = "Image",
            settingsProvider = {
                WatchCompanionSettings(
                    notificationVibrations = WatchNotificationVibrationSettings(
                        conversationOverrides = listOf(
                            WatchConversationVibrationOverride(
                                roomId = "!room:server",
                                pattern = WatchNotificationVibrationPattern.CUSTOM,
                                customPattern = "120 60 240",
                            ),
                        ),
                    ),
                )
            },
            clock = { 100L },
        )

        publisher.onMessageNotificationsRendered(
            listOf(
                aNotifiableMessageEvent(eventId = "\$dm:server", timestamp = 2L, body = "Hello Alice"),
            ),
        )

        val payload = transport.published.single().second.payload as WatchSync.MessageNotification
        assertThat(payload.notification.vibrationPatternOverride).isEqualTo(WatchNotificationVibrationPattern.CUSTOM)
        assertThat(payload.notification.customVibrationPattern).isEqualTo("120 60 240")
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

    @Test
    fun `session clear triggers full watch state clear and deletes notification paths`() = runTest {
        val transport = RecordingTransport()
        val clearedSessions = mutableListOf<SessionId>()
        val publisher = WatchNotificationBridgePublisherDelegate(
            transport = transport,
            imageLabel = "Image",
            clearWatchStateForSession = { sessionId -> clearedSessions += sessionId },
            clock = { 100L },
        )

        publisher.onMessageNotificationsRendered(
            listOf(
                aNotifiableMessageEvent(
                    eventId = "\$event:server",
                    timestamp = 5L,
                    body = "Session update",
                ),
            ),
        )
        publisher.onSessionCleared(SessionId("@alice:server"))

        assertThat(clearedSessions).containsExactly(SessionId("@alice:server"))
        assertThat(transport.deleted).containsExactly(
            WatchDataPaths.notification("message:@alice:server:!room:server"),
        )
    }

    @Test
    fun `redacting any grouped preview event deletes the watch notification`() = runTest {
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
        publisher.onMessageNotificationsRedacted(
            listOf(
                ResolvedPushEvent.Redaction(
                    sessionId = SessionId("@alice:server"),
                    roomId = RoomId("!room:server"),
                    redactedEventId = EventId("\$first:server"),
                    reason = null,
                ),
            ),
        )

        assertThat(transport.deleted).containsExactly(
            WatchDataPaths.notification("message:@alice:server:!room:server"),
        )
    }

    @Test
    fun `image notification carries compact preview bytes`() = runTest {
        val previewBytes = byteArrayOf(9, 8, 7)
        val transport = RecordingTransport()
        val publisher = WatchNotificationBridgePublisherDelegate(
            transport = transport,
            imageLabel = "Image",
            imagePreviewLoader = { previewBytes },
            clock = { 100L },
        )

        publisher.onMessageNotificationsRendered(
            listOf(
                aNotifiableMessageEvent(
                    eventId = "\$image:server",
                    timestamp = 5L,
                    body = null,
                    imageUriString = "content://images/1",
                    imageMimeType = "image/jpeg",
                ),
            ),
        )

        val payload = transport.published.single().second.payload as WatchSync.MessageNotification
        assertThat(payload.notification.bodyText).isEqualTo("Image")
        assertThat(payload.notification.imagePreviewBytes?.toList()).containsExactly(9.toByte(), 8.toByte(), 7.toByte()).inOrder()
    }

    @Test
    fun `oversized image preview is dropped before publishing`() = runTest {
        val transport = RecordingTransport()
        val publisher = WatchNotificationBridgePublisherDelegate(
            transport = transport,
            imageLabel = "Image",
            imagePreviewLoader = { ByteArray(90 * 1024) { 1 } },
            clock = { 100L },
        )

        publisher.onMessageNotificationsRendered(
            listOf(
                aNotifiableMessageEvent(
                    eventId = "\$image:server",
                    timestamp = 5L,
                    body = null,
                    imageUriString = "content://images/1",
                    imageMimeType = "image/jpeg",
                ),
            ),
        )

        val envelope = transport.published.single().second
        val payload = envelope.payload as WatchSync.MessageNotification
        assertThat(payload.notification.imagePreviewBytes).isNull()
        assertThat(WatchBridgeSerialization.encodeEnvelopeToBytes(envelope).size).isAtMost(WatchProtocol.MAX_PAYLOAD_BYTES)
    }

    private fun aNotifiableMessageEvent(
        eventId: String,
        timestamp: Long,
        body: String?,
        threadId: ThreadId? = null,
        imageUriString: String? = null,
        imageMimeType: String? = null,
        roomIsDm: Boolean = false,
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
        imageUriString = imageUriString,
        imageMimeType = imageMimeType,
        threadId = threadId,
        roomName = "Team Wear",
        roomIsDm = roomIsDm,
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
