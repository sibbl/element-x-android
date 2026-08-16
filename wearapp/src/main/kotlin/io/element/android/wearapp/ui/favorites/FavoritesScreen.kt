/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.wearapp.ui.favorites

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.items
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material3.ListHeader
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.Text
import io.element.android.watchbridge.contract.WatchCommand
import io.element.android.watchbridge.contract.WatchFavoriteRoom
import io.element.android.watchbridge.contract.WatchTimelineItemKind
import io.element.android.wearapp.R
import io.element.android.wearapp.bridge.WearBridgeClient
import io.element.android.wearapp.ui.common.toWearPlainTextFromMarkdown
import io.element.android.wearapp.ui.common.AvatarBadge
import io.element.android.wearapp.ui.common.PressableWearChip
import io.element.android.wearapp.ui.common.watchCommandErrorMessage
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map

private const val ROOM_PAGE_SIZE = 30
private const val LOAD_MORE_THRESHOLD = 6

@Composable
fun FavoritesScreen(
    bridge: WearBridgeClient,
    onRoomSelected: (String) -> Unit,
    onLongPressRoom: ((WatchFavoriteRoom) -> Unit)? = null,
    onError: ((String) -> Unit)? = null,
    requestedRoomCount: Int = ROOM_PAGE_SIZE,
    restoredPage: Int? = null,
    savedFavoriteListPosition: SavedScalingListPosition? = null,
    savedRecentListPosition: SavedScalingListPosition? = null,
    onRequestedRoomCountChange: (Int) -> Unit = {},
    onPageChanged: (Int) -> Unit = {},
    onFavoriteListPositionChange: (SavedScalingListPosition) -> Unit = {},
    onRecentListPositionChange: (SavedScalingListPosition) -> Unit = {},
) {
    val rooms by bridge.favorites.collectAsState()
    val avatarImages by bridge.avatarImages.collectAsState()
    val reachable by bridge.phoneReachable.collectAsState()
    val context = LocalContext.current
    val minimumRoomCount = requestedRoomCount.coerceAtLeast(ROOM_PAGE_SIZE)

    LaunchedEffect(reachable, minimumRoomCount) {
        bridge.refreshPhoneReachability()
        if (reachable) {
            runCatching {
                bridge.send { id -> WatchCommand.RefreshRooms(requestId = id, minimumCount = minimumRoomCount) }
            }.onFailure {
                onError?.invoke(context.watchCommandErrorMessage(it, R.string.watch_error_refresh_failed))
            }
        }
    }

    val favoriteRooms = rooms.filter { it.isFavorite }
    val recentRooms = rooms.filterNot { it.isFavorite }

    // If no favorites, show All Rooms by default (page 1); otherwise Favorites first (page 0).
    val initialPage = restoredPage?.takeIf { it in 0..1 } ?: if (favoriteRooms.isEmpty()) 1 else 0
    val pagerState = rememberPagerState(initialPage = initialPage) { 2 }

    LaunchedEffect(pagerState) {
        snapshotFlow { pagerState.currentPage }
            .distinctUntilChanged()
            .collect(onPageChanged)
    }

    Box(modifier = Modifier.fillMaxSize()) {
        HorizontalPager(state = pagerState, modifier = Modifier.fillMaxSize()) { page ->
            when (page) {
                0 -> RoomListPage(
                    title = stringResource(R.string.favorites_title),
                    rooms = favoriteRooms,
                    emptyText = stringResource(R.string.empty_favorites),
                    reachable = reachable,
                    isFavoritePage = true,
                    avatarImages = avatarImages,
                    onRoomSelected = onRoomSelected,
                    onLongPressRoom = onLongPressRoom,
                    savedPosition = savedFavoriteListPosition,
                    onListPositionChange = onFavoriteListPositionChange,
                )
                1 -> RoomListPage(
                    title = stringResource(R.string.recent_rooms_title),
                    rooms = recentRooms,
                    emptyText = stringResource(R.string.empty_rooms),
                    reachable = reachable,
                    isFavoritePage = false,
                    avatarImages = avatarImages,
                    onRoomSelected = onRoomSelected,
                    onLongPressRoom = onLongPressRoom,
                    savedPosition = savedRecentListPosition,
                    onListPositionChange = onRecentListPositionChange,
                    onLoadMore = { onRequestedRoomCountChange(minimumRoomCount + ROOM_PAGE_SIZE) },
                )
            }
        }
        PagerDotsIndicator(
            currentPage = pagerState.currentPage,
            pageCount = 2,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 6.dp),
        )
    }
}

@Composable
private fun PagerDotsIndicator(
    currentPage: Int,
    pageCount: Int,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        repeat(pageCount) { page ->
            val active = page == currentPage
            Box(
                modifier = Modifier
                    .size(if (active) 8.dp else 6.dp)
                    .clip(CircleShape)
                    .background(
                        if (active) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.32f)
                        },
                    ),
            )
        }
    }
}

@Composable
private fun RoomListPage(
    title: String,
    rooms: List<WatchFavoriteRoom>,
    emptyText: String,
    reachable: Boolean,
    isFavoritePage: Boolean,
    avatarImages: Map<String, ByteArray>,
    onRoomSelected: (String) -> Unit,
    onLongPressRoom: ((WatchFavoriteRoom) -> Unit)?,
    savedPosition: SavedScalingListPosition? = null,
    onListPositionChange: ((SavedScalingListPosition) -> Unit)? = null,
    onLoadMore: (() -> Unit)? = null,
) {
    val listState = rememberScalingLazyListState()
    var hasRestoredScroll by remember(title, savedPosition) { mutableStateOf(savedPosition == null) }
    var lastLoadMoreRoomCount by remember(title) { mutableIntStateOf(-1) }
    val maxScrollableIndex = when {
        !reachable || rooms.isEmpty() -> 1
        else -> rooms.size
    }

    LaunchedEffect(listState, onListPositionChange) {
        if (onListPositionChange == null) return@LaunchedEffect
        snapshotFlow { listState.isScrollInProgress to hasRestoredScroll }
            .distinctUntilChanged()
            .filter { (isScrolling, restored) -> !isScrolling && restored }
            .map {
                SavedScalingListPosition(
                    index = listState.centerItemIndex,
                    offset = listState.centerItemScrollOffset,
                )
            }
            .distinctUntilChanged()
            .collect(onListPositionChange)
    }

    LaunchedEffect(savedPosition, maxScrollableIndex) {
        val targetPosition = savedPosition ?: return@LaunchedEffect
        if (hasRestoredScroll) return@LaunchedEffect

        hasRestoredScroll = true
        val targetIndex = targetPosition.index.coerceIn(0, maxScrollableIndex)
        if (targetIndex > 0 || targetPosition.offset != 0) {
            listState.scrollToItem(targetIndex, targetPosition.offset)
        }
    }

    // Infinite scroll for the All Rooms page.
    if (onLoadMore != null) {
        LaunchedEffect(listState, rooms.size) {
            snapshotFlow { listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0 }
                .distinctUntilChanged()
                .collect { lastVisibleIndex ->
                    if (
                        rooms.isNotEmpty() &&
                        lastVisibleIndex >= rooms.size - LOAD_MORE_THRESHOLD &&
                        lastLoadMoreRoomCount != rooms.size
                    ) {
                        lastLoadMoreRoomCount = rooms.size
                        onLoadMore()
                    }
                }
        }
    }

    ScalingLazyColumn(
        state = listState,
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 4.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        item { ListHeader { Text(text = title) } }
        if (!reachable) {
            item {
                Text(
                    text = stringResource(R.string.no_phone),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        } else if (rooms.isEmpty()) {
            item {
                Text(
                    text = emptyText,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        } else {
            items(rooms, key = { "${if (isFavoritePage) "fav" else "recent"}-${it.roomId}" }) { room ->
                FavoriteRoomChip(
                    room = room,
                    avatarBytes = avatarImages[room.roomId],
                    onClick = { onRoomSelected(room.roomId) },
                    onLongPress = onLongPressRoom?.let { callback -> { callback(room) } },
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FavoriteRoomChip(
    room: WatchFavoriteRoom,
    avatarBytes: ByteArray?,
    onClick: () -> Unit,
    onLongPress: (() -> Unit)? = null,
) {
    val subtitle = if (room.lastPreviewKind == WatchTimelineItemKind.VOICE) {
        stringResource(R.string.timeline_voice_message)
    } else {
        cleanConversationPreview(room.lastPreviewText)
    }
    val chipColor = when {
        room.hasMentions -> MaterialTheme.colorScheme.secondaryContainer
        room.unreadCount > 0 -> MaterialTheme.colorScheme.surfaceContainerHigh
        else -> MaterialTheme.colorScheme.surfaceContainer
    }
    PressableWearChip(
        onTap = onClick,
        onLongPress = onLongPress,
        backgroundColor = chipColor,
        modifier = Modifier
            .fillMaxWidth(),
        icon = {
            AvatarBadge(
                displayName = room.displayName,
                avatarUrl = room.avatarUri,
                avatarBytes = avatarBytes,
                modifier = Modifier.size(32.dp),
            )
        },
        label = {
            Text(
                text = room.displayName,
                maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
        },
        secondaryLabel = if (!subtitle.isNullOrBlank()) {
            {
                Text(
                    text = subtitle,
                    maxLines = 2,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            null
        },
    )
}

internal fun cleanConversationPreview(preview: String?): String? = preview
    ?.trim()
    ?.removePrefix("Sending:")
    ?.removePrefix("Sending…")
    ?.trim()
    ?.toWearPlainTextFromMarkdown()
    ?.takeIf { it.isNotEmpty() }

data class SavedScalingListPosition(
    val index: Int = 0,
    val offset: Int = 0,
)
