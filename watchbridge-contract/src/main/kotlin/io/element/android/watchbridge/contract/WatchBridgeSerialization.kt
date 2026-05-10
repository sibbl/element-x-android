/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.watchbridge.contract

import kotlinx.serialization.json.Json
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.modules.polymorphic
import kotlinx.serialization.modules.subclass

/**
 * Single source of truth for watch <-> phone JSON (de)serialization.
 *
 * `ignoreUnknownKeys` is ON so older clients can parse newer payloads (additive-only evolution
 * of DTOs is safe).
 *
 * `classDiscriminator` is `type` because the generated `@SerialName("cmd.sendText")` labels
 * match well with that convention.
 */
object WatchBridgeSerialization {
    val json: Json = Json {
        ignoreUnknownKeys = true
        classDiscriminator = "type"
        encodeDefaults = true
        explicitNulls = false
        serializersModule = SerializersModule {
            polymorphic(WatchPayload::class) {
                subclass(WatchSync.FavoritesSnapshot::class)
                subclass(WatchSync.RoomSummary::class)
                subclass(WatchSync.AvatarUpdate::class)
                subclass(WatchSync.MediaPreview::class)
                subclass(WatchSync.TimelineDelta::class)
                subclass(WatchSync.ThreadDelta::class)
                subclass(WatchSync.UnreadUpdate::class)
                subclass(WatchSync.MessageNotification::class)
                subclass(WatchSync.Invalidation::class)
                subclass(WatchSync.FullRefresh::class)
                subclass(WatchSync.SettingsUpdate::class)

                subclass(WatchCommand.RefreshRooms::class)
                subclass(WatchCommand.OpenRoom::class)
                subclass(WatchCommand.FetchThread::class)
                subclass(WatchCommand.SendText::class)
                subclass(WatchCommand.SendReaction::class)
                subclass(WatchCommand.UploadVoiceDraft::class)
                subclass(WatchCommand.RequestPlayback::class)
                subclass(WatchCommand.RequestMediaPreview::class)
                subclass(WatchCommand.Unsubscribe::class)
                subclass(WatchCommand.MarkAsRead::class)

                subclass(WatchAck.Accepted::class)
                subclass(WatchAck.Pending::class)
                subclass(WatchAck.Sent::class)
                subclass(WatchAck.PayloadReady::class)
                subclass(WatchAck.PlaybackReady::class)
                subclass(WatchAck.Failed::class)
                subclass(WatchAck.Unsupported::class)
            }
        }
    }

    fun encodeEnvelope(envelope: WatchSyncEnvelope): String =
        json.encodeToString(WatchSyncEnvelope.serializer(), envelope)

    fun decodeEnvelope(raw: String): WatchSyncEnvelope =
        json.decodeFromString(WatchSyncEnvelope.serializer(), raw)

    fun encodeEnvelopeToBytes(envelope: WatchSyncEnvelope): ByteArray =
        encodeEnvelope(envelope).encodeToByteArray()

    fun decodeEnvelopeFromBytes(bytes: ByteArray): WatchSyncEnvelope =
        decodeEnvelope(bytes.decodeToString())
}
