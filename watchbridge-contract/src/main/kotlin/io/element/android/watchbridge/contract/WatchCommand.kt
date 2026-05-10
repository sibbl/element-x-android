/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.watchbridge.contract

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Source that produced a watch command (useful for analytics / fallback UX). */
@Serializable
enum class WatchSendSource { KEYBOARD, DICTATION, QUICK_REPLY, EMOJI_PICKER, OTHER }

/**
 * Watch -> Phone actions. Each command carries a [requestId] used for ack / idempotency / retry.
 *
 * Handlers MUST be idempotent with respect to [requestId]: receiving the same `requestId` twice
 * must not cause a duplicate side effect (e.g. a second message send).
 */
@Serializable
sealed interface WatchCommand : WatchPayload {
    val requestId: String

    @Serializable
    @SerialName("cmd.refreshRooms")
    data class RefreshRooms(
        override val requestId: String,
        val minimumCount: Int = 30,
    ) : WatchCommand

    @Serializable
    @SerialName("cmd.openRoom")
    data class OpenRoom(
        override val requestId: String,
        val roomId: String,
        val limit: Int = 30,
    ) : WatchCommand

    @Serializable
    @SerialName("cmd.fetchThread")
    data class FetchThread(
        override val requestId: String,
        val roomId: String,
        val threadRootEventId: String,
        val limit: Int = 20,
    ) : WatchCommand

    @Serializable
    @SerialName("cmd.sendText")
    data class SendText(
        override val requestId: String,
        val roomId: String,
        val threadRootEventId: String? = null,
        val inReplyToEventId: String? = null,
        val text: String,
        val source: WatchSendSource = WatchSendSource.KEYBOARD,
        val clientTsMs: Long,
    ) : WatchCommand

    @Serializable
    @SerialName("cmd.sendReaction")
    data class SendReaction(
        override val requestId: String,
        val roomId: String,
        val eventId: String,
        val reactionKey: String,
    ) : WatchCommand

    @Serializable
    @SerialName("cmd.uploadVoiceDraft")
    data class UploadVoiceDraft(
        override val requestId: String,
        val draft: WatchVoiceDraft,
    ) : WatchCommand

    @Serializable
    @SerialName("cmd.requestPlayback")
    data class RequestPlayback(
        override val requestId: String,
        val roomId: String,
        val eventId: String,
    ) : WatchCommand

    @Serializable
    @SerialName("cmd.requestMediaPreview")
    data class RequestMediaPreview(
        override val requestId: String,
        val roomId: String,
        val eventId: String,
    ) : WatchCommand

    @Serializable
    @SerialName("cmd.unsubscribe")
    data class Unsubscribe(
        override val requestId: String,
        val roomId: String,
        val threadRootEventId: String? = null,
    ) : WatchCommand

    @Serializable
    @SerialName("cmd.markAsRead")
    data class MarkAsRead(
        override val requestId: String,
        val roomId: String,
        val eventId: String,
        val threadRootEventId: String? = null,
    ) : WatchCommand
}

/** A recorded audio draft awaiting upload; the actual bytes are transferred via a `ChannelClient`. */
@Serializable
data class WatchVoiceDraft(
    val draftId: String,
    val roomId: String,
    val threadRootEventId: String? = null,
    val inReplyToEventId: String? = null,
    /** Opaque content URI on the watch (not transferred; only the bytes are). */
    val tempAudioUri: String,
    val durationMs: Long,
    /** `audio/ogg` / `audio/mp4` / … */
    val mimeType: String,
    val sampleRateHz: Int,
    val channelCount: Int,
    val sizeBytes: Long,
    val waveform: List<Int> = emptyList(),
)
