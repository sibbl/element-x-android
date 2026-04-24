/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.watchbridge.testing

import io.element.android.watchbridge.contract.WatchFavoriteRoom
import io.element.android.watchbridge.contract.WatchPlaybackDescriptor
import io.element.android.watchbridge.contract.WatchReactionSummary
import io.element.android.watchbridge.contract.WatchRoomKind
import io.element.android.watchbridge.contract.WatchRoomSummary
import io.element.android.watchbridge.contract.WatchThreadItem
import io.element.android.watchbridge.contract.WatchTimelineItem
import io.element.android.watchbridge.contract.WatchTimelineItemKind
import io.element.android.watchbridge.contract.WatchVoiceDraft

/** Canned fixtures for unit and contract tests across modules. */
object WatchBridgeFixtures {

    val aFavoriteGroup = WatchFavoriteRoom(
        roomId = "!group:server",
        displayName = "Team Wear",
        kind = WatchRoomKind.GROUP,
        unreadCount = 2,
        hasMentions = true,
        lastActivityTsMs = 1_000L,
        lastPreviewText = "let's ship",
    )

    val aFavoriteDm = WatchFavoriteRoom(
        roomId = "!dm:server",
        displayName = "Alice",
        kind = WatchRoomKind.DM,
        unreadCount = 0,
    )

    val aRoomSummary = WatchRoomSummary(
        roomId = aFavoriteGroup.roomId,
        displayName = aFavoriteGroup.displayName,
        kind = WatchRoomKind.GROUP,
        isEncrypted = true,
        canSendMessages = true,
        timelineVersion = 17L,
        lastSyncTsMs = 1_000L,
    )

    val aTextItem = WatchTimelineItem(
        eventId = "\$ev1:server",
        roomId = aRoomSummary.roomId,
        senderId = "@bob:server",
        senderDisplayName = "Bob",
        timestampMs = 100L,
        kind = WatchTimelineItemKind.TEXT,
        bodyText = "hello",
        reactions = listOf(WatchReactionSummary("👍", 1, reactedBySelf = false)),
    )

    val aThreadItem = WatchThreadItem(
        eventId = "\$rep1:server",
        threadRootEventId = aTextItem.eventId,
        roomId = aRoomSummary.roomId,
        senderId = "@alice:server",
        senderDisplayName = "Alice",
        timestampMs = 200L,
        kind = WatchTimelineItemKind.TEXT,
        bodyText = "reply 1",
    )

    val aVoiceDraft = WatchVoiceDraft(
        draftId = "draft-1",
        roomId = aRoomSummary.roomId,
        tempAudioUri = "content://wear/audio/draft-1.ogg",
        durationMs = 3_200L,
        mimeType = "audio/ogg",
        sampleRateHz = 16_000,
        channelCount = 1,
        sizeBytes = 12_345L,
    )

    val aPlaybackDescriptor = WatchPlaybackDescriptor(
        eventId = "\$voice:server",
        roomId = aRoomSummary.roomId,
        playbackUri = "content://phone/voice/ev",
        durationMs = 4_000L,
        mimeType = "audio/ogg",
    )
}
