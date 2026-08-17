/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.wearapp.ui.room

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Star
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.ScalingLazyListState
import androidx.wear.compose.foundation.lazy.itemsIndexed
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.foundation.pager.HorizontalPager
import androidx.wear.compose.foundation.pager.rememberPagerState
import androidx.wear.compose.material3.FilledTonalIconButton
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.ListHeader
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.Text
import io.element.android.watchbridge.contract.WatchRoomSummary
import io.element.android.watchbridge.contract.WatchTimelineItem
import io.element.android.wearapp.R
import io.element.android.wearapp.bridge.mediaPreviewCacheKey
import io.element.android.wearapp.ui.common.AvatarBadge
import io.element.android.wearapp.ui.common.ComposerBar
import io.element.android.wearapp.ui.favorites.SavedScalingListPosition
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * Pure rendering surface for a room timeline. Used by [RoomScreen] (live) and the thread screen
 * (after mapping `WatchThreadItem` -> `WatchTimelineItem`).
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun RoomView(
    state: RoomViewState,
    onMessageSelected: (String) -> Unit,
    onReactionsSelected: ((String) -> Unit)? = null,
    onOpenThread: ((String) -> Unit)?,
    onReply: () -> Unit,
    onVoice: (() -> Unit)?,
    onReact: (() -> Unit)? = null,
    onLongPressMessage: ((WatchTimelineItem) -> Unit)? = null,
    onScrollRequestHandled: ((Long) -> Unit)? = null,
    modifier: Modifier = Modifier,
    listState: ScalingLazyListState? = null,
    savedListPosition: SavedScalingListPosition? = null,
    onListPositionChange: ((SavedScalingListPosition) -> Unit)? = null,
    mediaPreviewFlowProvider: ((String, String) -> StateFlow<ByteArray?>)? = null,
    onRequestMediaPreview: ((String, String) -> Unit)? = null,
    avatarBytes: ByteArray? = null,
    onPageChange: (Int) -> Unit = {},
    enableConversationDetails: Boolean = false,
) {
    val lazyListState = listState ?: rememberScalingLazyListState()
    val pinnedItems = remember(state.items) { state.items.filter(WatchTimelineItem::isPinned) }
    val totalItemCount = lazyListState.layoutInfo.totalItemsCount
    val lastEventId = state.items.lastOrNull()?.eventId
    var shouldStickToBottom by remember(state.timelineKey) { mutableStateOf(true) }
    var followLatestAfterBottomRequest by remember(state.timelineKey) { mutableStateOf(false) }
    var lastAutoScrolledEventId by remember(state.timelineKey) { mutableStateOf<String?>(null) }
    var lastHandledScrollRequestId by remember(state.timelineKey) { mutableStateOf<Long?>(null) }
    var hasRestoredSavedPosition by remember(state.timelineKey, savedListPosition) {
        mutableStateOf(savedListPosition == null)
    }

    fun saveCurrentPosition() {
        onListPositionChange?.invoke(
            SavedScalingListPosition(
                index = lazyListState.centerItemIndex,
                offset = lazyListState.centerItemScrollOffset,
            ),
        )
    }
    val scope = rememberCoroutineScope()
    val shouldRestoreSavedPosition = savedListPosition != null && state.scrollRequestId == null

    val isAtBottom by remember(lazyListState) {
        derivedStateOf {
            isAtBottom(lazyListState)
        }
    }
    val visibleMediaPreviewEventIds by remember(lazyListState, state.items) {
        derivedStateOf {
            if (lastAutoScrolledEventId == null || lazyListState.isScrollInProgress) {
                emptySet()
            } else {
                visibleTimelineEventIds(lazyListState, state.items)
            }
        }
    }

    LaunchedEffect(lazyListState, state.timelineKey) {
        snapshotFlow { isAtBottom(lazyListState) to lazyListState.isScrollInProgress }
            .distinctUntilChanged()
            .collect { (atBottom, isUserScrolling) ->
                if (atBottom) {
                    shouldStickToBottom = true
                } else if (isUserScrolling) {
                    shouldStickToBottom = false
                    followLatestAfterBottomRequest = false
                }
            }
    }

    LaunchedEffect(lazyListState, onListPositionChange, state.timelineKey) {
        if (onListPositionChange == null) return@LaunchedEffect
        snapshotFlow { lazyListState.isScrollInProgress to hasRestoredSavedPosition }
            .distinctUntilChanged()
            .filter { (isScrolling, restored) -> !isScrolling && restored }
            .map {
                SavedScalingListPosition(
                    index = lazyListState.centerItemIndex,
                    offset = lazyListState.centerItemScrollOffset,
                )
            }
            .distinctUntilChanged()
            .collect(onListPositionChange)
    }

    LaunchedEffect(savedListPosition, totalItemCount, state.scrollRequestId, state.timelineKey) {
        val targetPosition = savedListPosition ?: return@LaunchedEffect
        if (hasRestoredSavedPosition || !shouldRestoreSavedPosition || totalItemCount <= 0) return@LaunchedEffect

        hasRestoredSavedPosition = true
        val targetIndex = targetPosition.index.coerceIn(0, (totalItemCount - 1).coerceAtLeast(0))
        if (targetIndex > 0 || targetPosition.offset != 0) {
            lazyListState.scrollToItem(targetIndex, targetPosition.offset)
        }
        shouldStickToBottom = targetIndex >= totalItemCount - 2
        lastAutoScrolledEventId = lastEventId
    }

    LaunchedEffect(
        state.timelineKey,
        state.scrollRequestId,
        state.scrollToEventId,
        state.forceScrollToBottom,
        totalItemCount,
    ) {
        val requestId = state.scrollRequestId
        if (requestId == null || requestId == lastHandledScrollRequestId || totalItemCount <= 0) return@LaunchedEffect

        if (state.forceScrollToBottom) {
            lazyListState.scrollToItem(totalItemCount - 1)
            shouldStickToBottom = true
            followLatestAfterBottomRequest = true
            lastAutoScrolledEventId = lastEventId
        } else {
            val targetIndex = timelineListIndexForEvent(state.items, state.scrollToEventId)
            if (targetIndex != null) {
                lazyListState.scrollToItem(targetIndex)
                shouldStickToBottom = false
                followLatestAfterBottomRequest = false
                lastAutoScrolledEventId = lastEventId
            }
        }
        hasRestoredSavedPosition = true
        lastHandledScrollRequestId = requestId
        onScrollRequestHandled?.invoke(requestId)
    }

    LaunchedEffect(state.timelineKey, lastEventId, totalItemCount) {
        if (lastEventId == null || totalItemCount <= 0) return@LaunchedEffect
        if (shouldRestoreSavedPosition && !hasRestoredSavedPosition) return@LaunchedEffect

        val initialScroll = lastAutoScrolledEventId == null
        val hasNewBottomItem = lastEventId != lastAutoScrolledEventId
        if (initialScroll) {
            lazyListState.scrollToItem(totalItemCount - 1)
            shouldStickToBottom = true
            lastAutoScrolledEventId = lastEventId
        } else if ((shouldStickToBottom || followLatestAfterBottomRequest || isAtBottom(lazyListState, extraToleranceItems = 1)) && hasNewBottomItem) {
            lazyListState.scrollToItem(totalItemCount - 1)
            shouldStickToBottom = true
            followLatestAfterBottomRequest = false
            lastAutoScrolledEventId = lastEventId
        }
    }

    val pageCount = if (enableConversationDetails) 2 else 1
    val pagerState = rememberPagerState(initialPage = 0, pageCount = { pageCount })
    BackHandler(enabled = enableConversationDetails && pagerState.currentPage == 1) {
        scope.launch { pagerState.animateScrollToPage(0) }
    }

    LaunchedEffect(pagerState) {
        snapshotFlow { pagerState.currentPage }
            .distinctUntilChanged()
            .collect(onPageChange)
    }

    Column(modifier = modifier.fillMaxSize()) {
        Box(modifier = Modifier.weight(1f)) {
            HorizontalPager(
                state = pagerState,
                modifier = Modifier.fillMaxSize(),
            ) { page ->
                when (page) {
                    0 -> TimelinePage(
                        state = state,
                        lazyListState = lazyListState,
                        pinnedItems = pinnedItems,
                        isAtBottom = isAtBottom,
                        visibleMediaPreviewEventIds = visibleMediaPreviewEventIds,
                        totalItemCount = totalItemCount,
                        shouldStickToBottom = shouldStickToBottom,
                        followLatestAfterBottomRequest = followLatestAfterBottomRequest,
                        lastAutoScrolledEventId = lastAutoScrolledEventId,
                        hasRestoredSavedPosition = hasRestoredSavedPosition,
                        lastEventId = lastEventId,
                        mediaPreviewFlowProvider = mediaPreviewFlowProvider,
                        onRequestMediaPreview = onRequestMediaPreview,
                        onMessageSelected = onMessageSelected,
                        onReactionsSelected = onReactionsSelected,
                        onOpenThread = onOpenThread,
                        onLongPressMessage = onLongPressMessage,
                        saveCurrentPosition = { saveCurrentPosition() },
                        scope = scope,
                    )
                    1 -> ConversationDetailsPage(
                        summary = state.summary,
                        avatarBytes = avatarBytes,
                        pinnedItems = pinnedItems,
                        onPinnedMessageSelected = { eventId ->
                            scope.launch {
                                pagerState.animateScrollToPage(0)
                                timelineListIndexForEvent(state.items, eventId)?.let { lazyListState.scrollToItem(it) }
                                shouldStickToBottom = false
                                followLatestAfterBottomRequest = false
                            }
                        },
                    )
                }
            }

            // Scroll-to-bottom button remains dedicated to returning to the newest message.
            if (pagerState.currentPage == 0 && !isAtBottom && state.items.size > 3) {
                FilledTonalIconButton(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 4.dp)
                        .size(40.dp),
                    onClick = {
                        scope.launch {
                            val total = lazyListState.layoutInfo.totalItemsCount
                            if (total > 0) lazyListState.scrollToItem(total - 1)
                            shouldStickToBottom = true
                            followLatestAfterBottomRequest = true
                            lastAutoScrolledEventId = lastEventId
                        }
                    },
                ) {
                    Icon(
                        imageVector = Icons.Filled.KeyboardArrowDown,
                        contentDescription = stringResource(R.string.a11y_scroll_to_bottom),
                    )
                }
            }

            // Pager dots indicator
            if (enableConversationDetails) PagerDotsIndicator(
                currentPage = pagerState.currentPage,
                pageCount = pageCount,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 6.dp),
            )
        }
        if (pagerState.currentPage == 0) {
            ComposerBar(
                onReply = onReply,
                onVoice = onVoice,
                onReact = onReact,
                contextLabel = state.composerContextLabel,
            )
        }
    }
}

private const val PINNED_SWIPE_THRESHOLD_PX = 48f

/** Stable snapshot used to drive [RoomView]. */
internal data class RoomViewState(
    val timelineKey: String,
    val displayName: String,
    val items: List<WatchTimelineItem>,
    val composerContextLabel: String? = null,
    val isLoading: Boolean = true,
    val emptyText: String? = null,
    val scrollRequestId: Long? = null,
    val scrollToEventId: String? = null,
    val forceScrollToBottom: Boolean = false,
    val mediaPreviewImages: Map<String, ByteArray> = emptyMap(),
    val summary: WatchRoomSummary? = null,
)

@Composable
private fun DayDivider(timestampMs: Long) {
    Text(
        text = dayLabel(timestampMs),
        style = MaterialTheme.typography.labelSmall,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp, horizontal = 8.dp),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

private fun isSameDay(ts1: Long, ts2: Long): Boolean {
    val cal1 = Calendar.getInstance().apply { timeInMillis = ts1 }
    val cal2 = Calendar.getInstance().apply { timeInMillis = ts2 }
    return cal1.get(Calendar.YEAR) == cal2.get(Calendar.YEAR) &&
        cal1.get(Calendar.DAY_OF_YEAR) == cal2.get(Calendar.DAY_OF_YEAR)
}

private fun dayLabel(timestampMs: Long): String {
    val now = Calendar.getInstance()
    val then = Calendar.getInstance().apply { timeInMillis = timestampMs }
    val todayDoy = now.get(Calendar.DAY_OF_YEAR)
    val thenDoy = then.get(Calendar.DAY_OF_YEAR)
    val sameYear = now.get(Calendar.YEAR) == then.get(Calendar.YEAR)
    return when {
        sameYear && todayDoy == thenDoy -> "Today"
        sameYear && todayDoy - thenDoy == 1 -> "Yesterday"
        sameYear && todayDoy - thenDoy < 7 -> SimpleDateFormat("EEEE", Locale.getDefault()).format(Date(timestampMs))
        else -> SimpleDateFormat("d MMM", Locale.getDefault()).format(Date(timestampMs))
    }
}

private fun isAtBottom(
    listState: androidx.wear.compose.foundation.lazy.ScalingLazyListState,
    extraToleranceItems: Int = 0,
): Boolean {
    val info = listState.layoutInfo
    val lastVisible = info.visibleItemsInfo.lastOrNull()?.index ?: 0
    val totalItems = info.totalItemsCount
    return totalItems <= 1 || lastVisible >= totalItems - 2 - extraToleranceItems
}

private fun visibleTimelineEventIds(
    listState: androidx.wear.compose.foundation.lazy.ScalingLazyListState,
    items: List<WatchTimelineItem>,
): Set<String> {
    if (items.isEmpty()) return emptySet()
    return listState.layoutInfo.visibleItemsInfo
        .mapNotNull { visibleItem -> items.getOrNull(visibleItem.index - 1)?.eventId }
        .toSet()
}

private fun timelineListIndexForEvent(items: List<WatchTimelineItem>, eventId: String?): Int? {
    if (eventId == null) return null
    var displayIndex = 1 // header
    items.forEachIndexed { index, item ->
        val previous = items.getOrNull(index - 1)
        val showDayDivider = previous == null || !isSameDay(previous.timestampMs, item.timestampMs)
        if (showDayDivider && index > 0) {
            displayIndex += 1
        }
        if (item.eventId == eventId) {
            return displayIndex
        }
        displayIndex += 1
    }
    return null
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
private fun TimelinePage(
    state: RoomViewState,
    lazyListState: ScalingLazyListState,
    pinnedItems: List<WatchTimelineItem>,
    isAtBottom: Boolean,
    visibleMediaPreviewEventIds: Set<String>,
    totalItemCount: Int,
    shouldStickToBottom: Boolean,
    followLatestAfterBottomRequest: Boolean,
    lastAutoScrolledEventId: String?,
    hasRestoredSavedPosition: Boolean,
    lastEventId: String?,
    mediaPreviewFlowProvider: ((String, String) -> StateFlow<ByteArray?>)?,
    onRequestMediaPreview: ((String, String) -> Unit)?,
    onMessageSelected: (String) -> Unit,
    onReactionsSelected: ((String) -> Unit)?,
    onOpenThread: ((String) -> Unit)?,
    onLongPressMessage: ((WatchTimelineItem) -> Unit)?,
    saveCurrentPosition: () -> Unit,
    scope: kotlinx.coroutines.CoroutineScope,
) {
    ScalingLazyColumn(
        state = lazyListState,
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        item {
            ListHeader {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = state.displayName,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (pinnedItems.isNotEmpty()) {
                        Text(
                            text = pluralStringResource(
                                R.plurals.pinned_messages_count,
                                pinnedItems.size,
                                pinnedItems.size,
                            ),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
        if (state.items.isEmpty() && state.isLoading) {
            item {
                Text(
                    text = stringResource(R.string.screen_room_loading_messages),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 12.dp),
                )
            }
        } else if (state.items.isEmpty()) {
            item {
                Text(
                    text = state.emptyText ?: stringResource(R.string.screen_room_empty_messages),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 12.dp),
                )
            }
        }
        itemsIndexed(state.items, key = { _, it -> it.eventId }) { index, entry ->
            val prevItem = state.items.getOrNull(index - 1)
            val showDayDivider = prevItem == null || !isSameDay(prevItem.timestampMs, entry.timestampMs)
            val showSender = prevItem == null || prevItem.senderId != entry.senderId || showDayDivider
            val mediaPreviewBytes = mediaPreviewFlowProvider?.let { provider ->
                provider(entry.roomId, entry.eventId).collectAsState().value
            } ?: state.mediaPreviewImages[mediaPreviewCacheKey(entry.roomId, entry.eventId)]

            if (showDayDivider && index > 0) {
                DayDivider(timestampMs = entry.timestampMs)
            }

            val openThread = onOpenThread?.let { callback ->
                { rootEventId: String ->
                    saveCurrentPosition()
                    callback(rootEventId)
                }
            }

            TimelineMessageRow(
                item = entry,
                mediaPreviewBytes = mediaPreviewBytes,
                showSender = showSender,
                onRequestMediaPreview = onRequestMediaPreview?.let { requestPreview ->
                    if (entry.eventId in visibleMediaPreviewEventIds) {
                        { requestPreview(entry.roomId, entry.eventId) }
                    } else {
                        null
                    }
                },
                onClick = {
                    saveCurrentPosition()
                    onMessageSelected(entry.eventId)
                },
                onReactionsClick = onReactionsSelected?.let { callback -> { callback(entry.eventId) } },
                onOpenThread = openThread,
                onLongPress = onLongPressMessage?.let { callback -> { callback(entry) } },
                onSwipeLeft = null, // Remove swipe-left to open message detail
            )
        }
    }
}

@Composable
private fun ConversationDetailsPage(
    summary: WatchRoomSummary?,
    avatarBytes: ByteArray?,
    pinnedItems: List<WatchTimelineItem>,
    onPinnedMessageSelected: (String) -> Unit,
) {
    val topic = summary?.topic
    ScalingLazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(horizontal = 8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        item {
            ListHeader {
                Text(
                    text = stringResource(R.string.screen_conversation_details),
                )
            }
        }

        // Avatar and name section
        if (summary != null) {
            item {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    AvatarBadge(
                        displayName = summary.displayName,
                        avatarUrl = summary.avatarUri,
                        avatarBytes = avatarBytes,
                        modifier = Modifier.size(48.dp),
                    )
                    Text(
                        text = summary.displayName,
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(top = 8.dp),
                    )

                    // Favorite status
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        modifier = Modifier.padding(top = 4.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Star,
                            contentDescription = null,
                            tint = if (summary.isFavorite) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                            modifier = Modifier.size(16.dp),
                        )
                        Text(
                            text = if (summary.isFavorite) {
                                stringResource(R.string.screen_conversation_details_favorite)
                            } else {
                                stringResource(R.string.screen_conversation_details_not_favorite)
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = if (summary.isFavorite) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                        )
                    }

                    // Topic
                    if (!topic.isNullOrBlank()) {
                        Text(
                            text = topic.orEmpty(),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 8.dp),
                            maxLines = 4,
                            overflow = TextOverflow.Ellipsis,
                        )
                    } else {
                        Text(
                            text = stringResource(R.string.screen_conversation_details_topic_empty),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 8.dp),
                        )
                    }
                }
            }

            // Pinned messages section
            item {
                ListHeader {
                    Text(
                        text = stringResource(R.string.screen_conversation_details_pinned_messages),
                    )
                }
            }

            if (pinnedItems.isEmpty()) {
                item {
                    Text(
                        text = stringResource(R.string.screen_conversation_details_pinned_empty),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 8.dp),
                    )
                }
            } else {
                itemsIndexed(pinnedItems, key = { _, item -> "details-pinned-${item.eventId}" }) { _, item ->
                    TimelineMessageRow(
                        item = item,
                        onClick = { onPinnedMessageSelected(item.eventId) },
                        showSender = true,
                        onOpenThread = null,
                    )
                }
            }
        }
    }
}
