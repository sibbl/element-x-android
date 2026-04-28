/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.wearapp.ui.room

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.core.text.HtmlCompat
import io.element.android.watchbridge.contract.WatchCommand
import io.element.android.watchbridge.contract.WatchRoomSummary
import io.element.android.watchbridge.contract.WatchSync
import io.element.android.watchbridge.contract.WatchTimelineItem
import io.element.android.wearapp.bridge.WearBridgeClient
import kotlinx.coroutines.flow.filterIsInstance

private const val MAX_TIMELINE_ITEMS = 100

internal data class RoomTimelineState(
    val summary: WatchRoomSummary? = null,
    val items: List<WatchTimelineItem> = emptyList(),
    val hasReceivedDelta: Boolean = false,
)

@Composable
internal fun rememberRoomTimelineState(
    bridge: WearBridgeClient,
    roomId: String,
): RoomTimelineState {
    var summary by remember(roomId) { mutableStateOf(bridge.getCachedSummary(roomId)) }
    var items by remember(roomId) { mutableStateOf(bridge.getCachedTimeline(roomId)) }
    var hasReceivedDelta by remember(roomId) { mutableStateOf(bridge.getCachedTimeline(roomId).isNotEmpty()) }

    LaunchedEffect(bridge, roomId) {
        runCatching {
            bridge.send { requestId ->
                WatchCommand.OpenRoom(requestId = requestId, roomId = roomId)
            }
        }
    }

    LaunchedEffect(bridge, roomId) {
        bridge.syncEvents.filterIsInstance<WatchSync.RoomSummary>()
            .collect { update ->
                if (update.summary.roomId == roomId) {
                    summary = update.summary
                    bridge.cacheSummary(roomId, update.summary)
                }
            }
    }

    LaunchedEffect(bridge, roomId) {
        bridge.syncEvents.filterIsInstance<WatchSync.TimelineDelta>()
            .collect { delta ->
                if (delta.roomId == roomId) {
                    hasReceivedDelta = true
                    val updated = (items + delta.items)
                        .filter { it.eventId !in delta.removedEventIds }
                        .distinctBy { it.eventId }
                        .sortedBy { it.timestampMs }
                        .takeLast(MAX_TIMELINE_ITEMS)
                    items = updated
                    bridge.cacheTimeline(roomId, updated)
                }
            }
    }

    return RoomTimelineState(
        summary = summary,
        items = items,
        hasReceivedDelta = hasReceivedDelta,
    )
}

internal fun WatchTimelineItem.displayText(): String {
    val formatted = formattedText
        ?.takeIf { it.isNotBlank() }
        ?.let { HtmlCompat.fromHtml(it, HtmlCompat.FROM_HTML_MODE_COMPACT).toString() }
        ?.replace(Regex("\\s+"), " ")
        ?.trim()
        ?.takeIf { it.isNotBlank() }
    return formatted
        ?: bodyText?.takeIf { it.isNotBlank() }
        ?: "[${kind.name.lowercase()}]"
}

internal fun WatchTimelineItem.reactionSummaryText(): String? =
    reactions.takeIf { it.isNotEmpty() }
        ?.joinToString(separator = "  ") { "${it.key} ${it.count}" }
