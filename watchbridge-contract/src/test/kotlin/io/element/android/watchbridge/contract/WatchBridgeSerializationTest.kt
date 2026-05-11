/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.watchbridge.contract

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class WatchBridgeSerializationTest {

    private val ser = WatchBridgeSerialization

    @Test
    fun `favorites snapshot roundtrips`() {
        val envelope = WatchSyncEnvelope(
            generatedAtMs = 42L,
            payload = WatchSync.FavoritesSnapshot(
                rooms = listOf(
                    WatchFavoriteRoom(
                        roomId = "!room:server",
                        displayName = "Team Wear",
                        kind = WatchRoomKind.GROUP,
                        unreadCount = 3,
                        hasMentions = true,
                        lastActivityTsMs = 100L,
                        lastPreviewText = "hi",
                    ),
                    WatchFavoriteRoom(
                        roomId = "!dm:server",
                        displayName = "Alice",
                        kind = WatchRoomKind.DM,
                    ),
                ),
            ),
        )

        val encoded = ser.encodeEnvelope(envelope)
        val decoded = ser.decodeEnvelope(encoded)

        assertThat(decoded).isEqualTo(envelope)
        assertThat(decoded.protocolVersion).isEqualTo(WatchProtocol.VERSION)
    }

    @Test
    fun `send text command carries request id and thread root`() {
        val cmd = WatchCommand.SendText(
            requestId = "req-1",
            roomId = "!room:server",
            threadRootEventId = "\$root:server",
            inReplyToEventId = "\$reply:server",
            text = "hello from watch",
            source = WatchSendSource.DICTATION,
            clientTsMs = 12345L,
        )
        val envelope = WatchSyncEnvelope(generatedAtMs = 0L, payload = cmd)

        val decoded = ser.decodeEnvelope(ser.encodeEnvelope(envelope))
        val decodedCmd = decoded.payload as WatchCommand.SendText

        assertThat(decoded.payload).isInstanceOf(WatchCommand.SendText::class.java)
        assertThat(decodedCmd.requestId).isEqualTo("req-1")
        assertThat(decodedCmd.inReplyToEventId).isEqualTo("\$reply:server")
    }

    @Test
    fun `mark as read command carries thread root when present`() {
        val cmd = WatchCommand.MarkAsRead(
            requestId = "req-mark",
            roomId = "!room:server",
            eventId = "\$event:server",
            threadRootEventId = "\$root:server",
        )
        val envelope = WatchSyncEnvelope(generatedAtMs = 0L, payload = cmd)

        val decoded = ser.decodeEnvelope(ser.encodeEnvelope(envelope))
        val decodedCmd = decoded.payload as WatchCommand.MarkAsRead

        assertThat(decodedCmd.threadRootEventId).isEqualTo("\$root:server")
    }

    @Test
    fun `unsubscribe command roundtrips`() {
        val cmd = WatchCommand.Unsubscribe(
            requestId = "req-unsubscribe",
            roomId = "!room:server",
            threadRootEventId = "\$root:server",
        )
        val envelope = WatchSyncEnvelope(generatedAtMs = 0L, payload = cmd)

        val decoded = ser.decodeEnvelope(ser.encodeEnvelope(envelope))

        assertThat(decoded.payload).isEqualTo(cmd)
    }

    @Test
    fun `media preview request command roundtrips`() {
        val cmd = WatchCommand.RequestMediaPreview(
            requestId = "req-preview",
            roomId = "!room:server",
            eventId = "\$image:server",
        )
        val envelope = WatchSyncEnvelope(generatedAtMs = 0L, payload = cmd)

        val decoded = ser.decodeEnvelope(ser.encodeEnvelope(envelope))

        assertThat(decoded.payload).isEqualTo(cmd)
    }

    @Test
    fun `ack types are distinct`() {
        val pending = WatchAck.Pending(requestId = "r", reason = "queued")
        val sent = WatchAck.Sent(requestId = "r", eventId = "\$ev:server")
        val failed = WatchAck.Failed(requestId = "r", code = WatchErrorCode.NETWORK, message = "offline")

        listOf(pending, sent, failed).forEach { ack ->
            val envelope = WatchSyncEnvelope(generatedAtMs = 0L, payload = ack)
            val decoded = ser.decodeEnvelope(ser.encodeEnvelope(envelope))
            assertThat(decoded.payload).isEqualTo(ack)
        }
    }

    @Test
    fun `unknown keys are ignored to preserve forward compatibility`() {
        val raw = """
            {
              "protocolVersion": 1,
              "generatedAtMs": 0,
              "payload": {
                "type": "sync.unread",
                "roomId": "!room:server",
                "unreadCount": 2,
                "hasMentions": false,
                "futureField": "ignoreMe"
              }
            }
        """.trimIndent()

        val decoded = ser.decodeEnvelope(raw)
        val payload = decoded.payload as WatchSync.UnreadUpdate
        assertThat(payload.roomId).isEqualTo("!room:server")
    }

    @Test
    fun `message notification roundtrips`() {
        val envelope = WatchSyncEnvelope(
            generatedAtMs = 55L,
            expiresAtMs = 1055L,
            payload = WatchSync.MessageNotification(
                notification = WatchMessageNotification(
                    notificationKey = "message:@alice:server:!room:server",
                    roomId = "!room:server",
                    eventId = "\$event:server",
                    threadRootEventId = "\$root:server",
                    roomDisplayName = "Team Wear",
                    roomKind = WatchRoomKind.DM,
                    senderDisplayName = "Bob",
                    bodyText = "Hello there",
                    timestampMs = 12L,
                    messageCount = 3,
                    previewMessages = listOf(
                        WatchNotificationMessagePreview(
                            senderDisplayName = "Bob",
                            bodyText = "Hello there",
                            timestampMs = 10L,
                        ),
                        WatchNotificationMessagePreview(
                            senderDisplayName = "Bob",
                            bodyText = "Are we still testing the watch build?",
                            timestampMs = 12L,
                        ),
                    ),
                    isNoisy = true,
                    vibrationPatternOverride = WatchNotificationVibrationPattern.ESCALATING,
                    customVibrationPattern = "120 60 240",
                ),
            ),
        )

        val decoded = ser.decodeEnvelope(ser.encodeEnvelope(envelope))
        val notification = (decoded.payload as WatchSync.MessageNotification).notification

        assertThat(decoded).isEqualTo(envelope)
        assertThat(notification.messageCount).isEqualTo(3)
        assertThat(notification.previewMessages)
            .containsExactlyElementsIn((envelope.payload as WatchSync.MessageNotification).notification.previewMessages)
            .inOrder()
    }

    @Test
    fun `message notification image preview bytes roundtrip`() {
        val envelope = WatchSyncEnvelope(
            generatedAtMs = 55L,
            payload = WatchSync.MessageNotification(
                notification = WatchMessageNotification(
                    notificationKey = "message:@alice:server:!room:server",
                    roomId = "!room:server",
                    eventId = "\$image:server",
                    roomDisplayName = "Team Wear",
                    bodyText = "Image",
                    timestampMs = 12L,
                    imagePreviewBytes = byteArrayOf(1, 2, 3),
                ),
            ),
        )

        val decoded = ser.decodeEnvelope(ser.encodeEnvelope(envelope))
        val notification = (decoded.payload as WatchSync.MessageNotification).notification

        assertThat(notification.imagePreviewBytes?.toList()).containsExactly(1.toByte(), 2.toByte(), 3.toByte()).inOrder()
    }

    @Test
    fun `settings update with notification vibration patterns roundtrips`() {
        val envelope = WatchSyncEnvelope(
            generatedAtMs = 99L,
            payload = WatchSync.SettingsUpdate(
                settings = WatchCompanionSettings(
                    recentConversationsTileAction = WatchTileConversationAction.QUICK_REPLY_TEXT,
                    favoriteConversationsTileAction = WatchTileConversationAction.QUICK_REPLY_VOICE,
                    notificationVibrations = WatchNotificationVibrationSettings(
                        groups = WatchNotificationVibrationPattern.CUSTOM,
                        groupsCustomPattern = "120 60 240",
                        dms = WatchNotificationVibrationPattern.PULSE,
                        dmsCustomPattern = "",
                        favoriteGroups = WatchNotificationVibrationPattern.ESCALATING,
                        favoriteGroupsCustomPattern = "",
                        favoriteDms = WatchNotificationVibrationPattern.CUSTOM,
                        favoriteDmsCustomPattern = "90 45 180",
                        conversationOverrides = listOf(
                            WatchConversationVibrationOverride(
                                roomId = "!fav:server",
                                pattern = WatchNotificationVibrationPattern.CUSTOM,
                                customPattern = "120 60 240",
                            ),
                            WatchConversationVibrationOverride(
                                roomId = "!inherit:server",
                                pattern = null,
                            ),
                        ),
                    ),
                ),
            ),
        )

        val decoded = ser.decodeEnvelope(ser.encodeEnvelope(envelope))

        assertThat(decoded).isEqualTo(envelope)
    }

    @Test
    fun `custom vibration parser accepts a valid waveform string`() {
        val parsed = parseCustomWatchNotificationVibrationPattern("120 60 240 80")

        assertThat(parsed?.toList()).containsExactly(0L, 120L, 60L, 240L, 80L).inOrder()
    }

    @Test
    fun `custom vibration parser rejects invalid values`() {
        assertThat(parseCustomWatchNotificationVibrationPattern("120 nope 240")).isNull()
        assertThat(parseCustomWatchNotificationVibrationPattern("0 120 240")).isNull()
        assertThat(parseCustomWatchNotificationVibrationPattern(" ")).isNull()
    }

    @Test
    fun `byte roundtrip preserves content`() {
        val env = WatchSyncEnvelope(generatedAtMs = 1L, payload = WatchSync.FullRefresh)
        val decoded = ser.decodeEnvelopeFromBytes(ser.encodeEnvelopeToBytes(env))
        assertThat(decoded.payload).isInstanceOf(WatchSync.FullRefresh::class.java)
    }
}
