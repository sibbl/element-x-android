/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.watchbridge

import io.element.android.watchbridge.contract.WatchFavoriteRoom
import io.element.android.watchbridge.contract.WatchPlaybackDescriptor
import io.element.android.watchbridge.contract.WatchRoomSummary
import io.element.android.watchbridge.contract.WatchThreadItem
import io.element.android.watchbridge.contract.WatchTimelineItem
import io.element.android.watchbridge.contract.WatchVoiceDraft
import kotlinx.coroutines.flow.Flow

/**
 * The thin port exposed by the existing Element X phone app to the companion bridge.
 *
 * All methods here return *projections* (DTOs from `:watchbridge-contract`) — the phone app's
 * internal domain models never cross this boundary.
 *
 * **Integration guidance:** this is the ONLY interface an `@ContributesBinding` in the host app
 * should implement. Implementations live in the host app (not here) to keep this module free of
 * dependencies on Element X core modules. See `docs/watch-companion/patch-plan.md` for a
 * skeleton implementation outline and expected touch points.
 */
interface ElementXWatchPort {

    /** Favorites flow, driven by the phone-side room-list service. Emits on changes. */
    fun favorites(): Flow<List<WatchFavoriteRoom>>

    /** Room summary + recent timeline slice projection. */
    suspend fun roomSummary(roomId: String): WatchRoomSummary?
    fun roomTimeline(roomId: String, limit: Int): Flow<List<WatchTimelineItem>>

    /** Thread read access (root context + recent replies). */
    fun threadTimeline(roomId: String, threadRootEventId: String, limit: Int): Flow<List<WatchThreadItem>>

    /** Text / thread reply sending. Returns the matrix eventId once known. */
    suspend fun sendText(roomId: String, threadRootEventId: String?, text: String): Result<String>

    /** Reaction sending. Implementation must be idempotent for a given `(roomId, eventId, key)`. */
    suspend fun sendReaction(roomId: String, eventId: String, reactionKey: String): Result<Unit>

    /** Voice message send. `audioBytes` is the draft audio pulled off the Wear Data Layer channel. */
    suspend fun sendVoiceMessage(
        draft: WatchVoiceDraft,
        audioBytes: ByteArray,
    ): Result<String>

    /** Materialize a playback descriptor for an existing voice message event. */
    suspend fun playbackDescriptor(roomId: String, eventId: String): Result<WatchPlaybackDescriptor>

    /** Mark a room's timeline as read up to `eventId`. */
    suspend fun markAsRead(roomId: String, eventId: String): Result<Unit>
}
