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
 * Phone -> Watch snapshots, deltas, and invalidations.
 *
 * All variants are read-only projections of data the phone already owns. No command side effects.
 */
@Serializable
sealed interface WatchSync : WatchPayload {

    @Serializable
    @SerialName("sync.favorites")
    data class FavoritesSnapshot(
        val rooms: List<WatchFavoriteRoom>,
    ) : WatchSync

    @Serializable
    @SerialName("sync.roomSummary")
    data class RoomSummary(
        val summary: WatchRoomSummary,
    ) : WatchSync

    @Serializable
    @SerialName("sync.timelineDelta")
    data class TimelineDelta(
        val roomId: String,
        val fromTimelineVersion: Long,
        val toTimelineVersion: Long,
        val items: List<WatchTimelineItem>,
        val removedEventIds: List<String> = emptyList(),
    ) : WatchSync

    @Serializable
    @SerialName("sync.threadDelta")
    data class ThreadDelta(
        val roomId: String,
        val threadRootEventId: String,
        val items: List<WatchThreadItem>,
        val removedEventIds: List<String> = emptyList(),
    ) : WatchSync

    @Serializable
    @SerialName("sync.unread")
    data class UnreadUpdate(
        val roomId: String,
        val unreadCount: Int,
        val hasMentions: Boolean,
    ) : WatchSync

    @Serializable
    @SerialName("sync.invalidation")
    data class Invalidation(
        val scope: InvalidationScope,
        val roomId: String? = null,
    ) : WatchSync {
        enum class InvalidationScope { FAVORITES, ROOM, THREAD, ALL }
    }

    @Serializable
    @SerialName("sync.fullRefresh")
    data object FullRefresh : WatchSync
}
