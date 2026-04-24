/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.watchbridge.contract

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Phone -> Watch result for a [WatchCommand], keyed by [requestId].
 *
 * Acks are split into *progress* states (`Accepted`, `Pending`) and *terminal* states
 * (`Sent`, `PayloadReady`, `PlaybackReady`, `Failed`, `Unsupported`).
 */
@Serializable
sealed interface WatchAck : WatchPayload {
    val requestId: String

    @Serializable
    @SerialName("ack.accepted")
    data class Accepted(override val requestId: String) : WatchAck

    @Serializable
    @SerialName("ack.pending")
    data class Pending(override val requestId: String, val reason: String? = null) : WatchAck

    @Serializable
    @SerialName("ack.sent")
    data class Sent(override val requestId: String, val eventId: String? = null) : WatchAck

    @Serializable
    @SerialName("ack.payloadReady")
    data class PayloadReady(override val requestId: String, val dataPath: String) : WatchAck

    @Serializable
    @SerialName("ack.playbackReady")
    data class PlaybackReady(
        override val requestId: String,
        val descriptor: WatchPlaybackDescriptor,
    ) : WatchAck

    @Serializable
    @SerialName("ack.failed")
    data class Failed(
        override val requestId: String,
        val code: WatchErrorCode,
        val message: String? = null,
    ) : WatchAck

    @Serializable
    @SerialName("ack.unsupported")
    data class Unsupported(
        override val requestId: String,
        val serverProtocolVersion: Int,
        val clientProtocolVersion: Int,
    ) : WatchAck
}

/** Stable error codes exchanged with the watch. Do not remove values — only add. */
@Serializable
enum class WatchErrorCode {
    UNKNOWN,
    TIMEOUT,
    NETWORK,
    UNAUTHORIZED,
    NOT_FOUND,
    ENCRYPTION_FAILED,
    PERMISSION_DENIED,
    PAYLOAD_TOO_LARGE,
    UPLOAD_FAILED,
    PLAYBACK_UNAVAILABLE,
    PHONE_APP_UNAVAILABLE,
    FEATURE_DISABLED,
    VALIDATION,
}
