/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.watchbridge.contract

import kotlinx.serialization.json.Json

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
