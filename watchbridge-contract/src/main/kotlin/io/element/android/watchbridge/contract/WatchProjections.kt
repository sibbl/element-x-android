/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.watchbridge.contract

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Kind of a room surfaced to the watch. */
@Serializable
enum class WatchRoomKind { GROUP, DM }

/** Compact favorite-room projection shown at the top of the watch list. */
@Serializable
data class WatchFavoriteRoom(
    val roomId: String,
    val displayName: String,
    val avatarUri: String? = null,
    val kind: WatchRoomKind,
    val unreadCount: Int = 0,
    val hasMentions: Boolean = false,
    val lastActivityTsMs: Long = 0L,
    val lastPreviewText: String? = null,
    val isFavorite: Boolean = true,
)

/** Short metadata about a single room the watch currently has open. */
@Serializable
data class WatchRoomSummary(
    val roomId: String,
    val displayName: String,
    val avatarUri: String? = null,
    val kind: WatchRoomKind,
    val isEncrypted: Boolean,
    val canSendMessages: Boolean,
    val timelineVersion: Long,
    val lastSyncTsMs: Long,
)

/** Kind of a timeline item projected to the watch. */
@Serializable
enum class WatchTimelineItemKind { TEXT, EMOTE, NOTICE, IMAGE, VIDEO, FILE, VOICE, REDACTED, STATE, UNSUPPORTED }

/** Bounded image metadata the watch uses to render a cached preview. */
@Serializable
data class WatchMediaPreview(
    val widthPx: Int? = null,
    val heightPx: Int? = null,
    val mimeType: String? = null,
)

/** A single compact timeline item. */
@Serializable
data class WatchTimelineItem(
    val eventId: String,
    val roomId: String,
    val senderId: String,
    val senderDisplayName: String?,
    val timestampMs: Long,
    val kind: WatchTimelineItemKind,
    val bodyText: String? = null,
    val formattedText: String? = null,
    val isOwn: Boolean = false,
    val isEdited: Boolean = false,
    val hasThread: Boolean = false,
    val threadRootEventId: String? = null,
    val threadReplyCount: Int = 0,
    val reactions: List<WatchReactionSummary> = emptyList(),
    val voiceMessageMeta: WatchVoiceMeta? = null,
    val readableByTts: Boolean = true,
    val threadLastReplyText: String? = null,
    val mediaPreview: WatchMediaPreview? = null,
    val isReadMarkerAnchor: Boolean = false,
)

/** Reactions aggregated per reaction key. */
@Serializable
data class WatchReactionSummary(
    val key: String,
    val count: Int,
    val reactedBySelf: Boolean,
)

/** Metadata describing a voice message event. */
@Serializable
data class WatchVoiceMeta(
    val durationMs: Long,
    val waveform: List<Int> = emptyList(),
    val mimeType: String,
    val sizeBytes: Long,
    val audioUrl: String? = null,
)

/** Projection for a thread's root context shown in the indicator. */
@Serializable
data class WatchThreadSummary(
    val threadRootEventId: String,
    val roomId: String,
    val rootSnippet: String,
    val replyCount: Int,
    val lastActivityTsMs: Long,
)

/** Thread-scoped reply projection. */
@Serializable
data class WatchThreadItem(
    val eventId: String,
    val threadRootEventId: String,
    val roomId: String,
    val senderId: String,
    val senderDisplayName: String?,
    val timestampMs: Long,
    val kind: WatchTimelineItemKind,
    val bodyText: String? = null,
    val formattedText: String? = null,
    val isOwn: Boolean = false,
    val reactions: List<WatchReactionSummary> = emptyList(),
    val voiceMessageMeta: WatchVoiceMeta? = null,
    val mediaPreview: WatchMediaPreview? = null,
)

/** Minimum info the watch needs to play a voice message it does not own. */
@Serializable
data class WatchPlaybackDescriptor(
    val eventId: String,
    val roomId: String,
    val playbackUri: String,
    val durationMs: Long,
    val mimeType: String,
    val requiresBluetoothPreferred: Boolean = false,
)
