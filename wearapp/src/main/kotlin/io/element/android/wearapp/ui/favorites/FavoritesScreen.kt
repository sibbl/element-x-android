/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.wearapp.ui.favorites

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.items
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material.Chip
import androidx.wear.compose.material.ChipDefaults
import androidx.wear.compose.material.ListHeader
import androidx.wear.compose.material.Text
import io.element.android.watchbridge.contract.WatchCommand
import io.element.android.watchbridge.contract.WatchFavoriteRoom
import io.element.android.wearapp.R
import io.element.android.wearapp.bridge.WearBridgeClient
import io.element.android.wearapp.ui.common.AvatarBadge
import kotlinx.coroutines.flow.distinctUntilChanged

private const val ROOM_PAGE_SIZE = 30
private const val LOAD_MORE_THRESHOLD = 6

@Composable
fun FavoritesScreen(
    bridge: WearBridgeClient,
    onRoomSelected: (String) -> Unit,
    onLongPressRoom: ((WatchFavoriteRoom) -> Unit)? = null,
) {
    val rooms by bridge.favorites.collectAsState()
    val reachable by bridge.phoneReachable.collectAsState()
    var requestedRoomCount by remember { mutableIntStateOf(ROOM_PAGE_SIZE) }

    LaunchedEffect(reachable, requestedRoomCount) {
        bridge.refreshPhoneReachability()
        if (reachable) {
            // Ask the phone to push favorites plus the most recent rooms up to the requested count.
            runCatching { bridge.send { id -> WatchCommand.RefreshRooms(requestId = id, minimumCount = requestedRoomCount) } }
        }
    }

    val listState = rememberScalingLazyListState()
    LaunchedEffect(listState, rooms.size, reachable) {
        if (!reachable) return@LaunchedEffect
        snapshotFlow { listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0 }
            .distinctUntilChanged()
            .collect { lastVisibleIndex ->
                if (rooms.isNotEmpty() && lastVisibleIndex >= rooms.size - LOAD_MORE_THRESHOLD) {
                    requestedRoomCount += ROOM_PAGE_SIZE
                }
            }
    }

    val favoriteRooms = rooms.filter { it.isFavorite }
    val recentRooms = rooms.filterNot { it.isFavorite }
    ScalingLazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize().padding(horizontal = 4.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        if (!reachable) {
            item { Text(androidx.compose.ui.res.stringResource(R.string.no_phone)) }
        } else if (rooms.isEmpty()) {
            item { Text(androidx.compose.ui.res.stringResource(R.string.empty_rooms)) }
        } else {
            if (favoriteRooms.isNotEmpty()) {
                item { ListHeader { Text(text = androidx.compose.ui.res.stringResource(R.string.favorites_title)) } }
                items(favoriteRooms, key = { "favorite-${it.roomId}" }) { room ->
                    FavoriteRoomChip(
                        room = room,
                        onClick = { onRoomSelected(room.roomId) },
                        onLongPress = onLongPressRoom?.let { callback -> { callback(room) } },
                    )
                }
            }
            if (recentRooms.isNotEmpty()) {
                item { ListHeader { Text(text = androidx.compose.ui.res.stringResource(R.string.recent_rooms_title)) } }
                items(recentRooms, key = { "recent-${it.roomId}" }) { room ->
                    FavoriteRoomChip(
                        room = room,
                        onClick = { onRoomSelected(room.roomId) },
                        onLongPress = onLongPressRoom?.let { callback -> { callback(room) } },
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FavoriteRoomChip(
    room: WatchFavoriteRoom,
    onClick: () -> Unit,
    onLongPress: (() -> Unit)? = null,
) {
    val subtitle = buildString {
        if (room.isFavorite) append("★")
        if (room.unreadCount > 0) {
            if (isNotEmpty()) append(" · ")
            append(room.unreadCount)
        }
        if (room.hasMentions) {
            if (isNotEmpty()) append(" · ")
            append("@")
        }
        room.lastPreviewText?.let {
            if (isNotEmpty()) append(" · ")
            append(it)
        }
    }
    Chip(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongPress,
                role = Role.Button,
            ),
        icon = {
            AvatarBadge(
                displayName = room.displayName,
                avatarUrl = room.avatarUri,
                modifier = Modifier.size(28.dp),
            )
        },
        label = {
            Text(
                text = room.displayName,
                maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
            )
        },
        secondaryLabel = if (subtitle.isNotBlank()) {
            {
                Text(
                    text = subtitle,
                    maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                )
            }
        } else null,
        onClick = onClick,
        colors = if (room.isFavorite) ChipDefaults.primaryChipColors() else ChipDefaults.secondaryChipColors(),
    )
}
