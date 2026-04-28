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
    fun `byte roundtrip preserves content`() {
        val env = WatchSyncEnvelope(generatedAtMs = 1L, payload = WatchSync.FullRefresh)
        val decoded = ser.decodeEnvelopeFromBytes(ser.encodeEnvelopeToBytes(env))
        assertThat(decoded.payload).isInstanceOf(WatchSync.FullRefresh::class.java)
    }
}
