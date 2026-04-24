/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.wearapp.ui.favorites

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.items
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material.Chip
import androidx.wear.compose.material.ChipDefaults
import androidx.wear.compose.material.ListHeader
import androidx.wear.compose.material.Text
import io.element.android.watchbridge.contract.WatchCommand
import io.element.android.watchbridge.contract.WatchFavoriteRoom
import io.element.android.watchbridge.contract.WatchRoomKind
import io.element.android.wearapp.R
import io.element.android.wearapp.bridge.WearBridgeClient
import kotlinx.coroutines.launch

@Composable
fun FavoritesScreen(
    bridge: WearBridgeClient,
    onRoomSelected: (String) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val rooms by bridge.favorites.collectAsState()
    val reachable by bridge.phoneReachable.collectAsState()

    LaunchedEffect(Unit) {
        // Ask the phone to push its current favorites snapshot.
        runCatching { bridge.send { id -> WatchCommand.RefreshRooms(requestId = id) } }
    }

    val listState = rememberScalingLazyListState()
    ScalingLazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize().padding(horizontal = 8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        item { ListHeader { Text(text = androidx.compose.ui.res.stringResource(R.string.favorites_title)) } }
        if (!reachable) {
            item { Text(androidx.compose.ui.res.stringResource(R.string.no_phone)) }
        }
        if (rooms.isEmpty()) {
            item { Text(androidx.compose.ui.res.stringResource(R.string.empty_favorites)) }
        }
        items(rooms, key = { it.roomId }) { room ->
            FavoriteRoomChip(room) { onRoomSelected(room.roomId) }
        }
    }
}

@Composable
private fun FavoriteRoomChip(room: WatchFavoriteRoom, onClick: () -> Unit) {
    val subtitle = buildString {
        append(if (room.kind == WatchRoomKind.DM) "DM" else "Room")
        if (room.unreadCount > 0) append(" · ${room.unreadCount}")
        if (room.hasMentions) append(" · @")
    }
    Chip(
        label = { Text(room.displayName) },
        secondaryLabel = { Text(subtitle) },
        onClick = onClick,
        colors = ChipDefaults.primaryChipColors(),
    )
}
