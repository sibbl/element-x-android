/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.wearapp.ui.room

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.itemsIndexed
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material.Button
import androidx.wear.compose.material.ButtonDefaults
import androidx.wear.compose.material.Icon
import androidx.wear.compose.material.ListHeader
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Text
import io.element.android.watchbridge.contract.WatchTimelineItem
import io.element.android.wearapp.R
import io.element.android.wearapp.ui.common.ComposerBar
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * Pure rendering surface for a room timeline. Used by [RoomScreen] (live) and the thread screen
 * (after mapping `WatchThreadItem` -> `WatchTimelineItem`).
 */
@Composable
internal fun RoomView(
    state: RoomViewState,
    onMessageSelected: (String) -> Unit,
    onOpenThread: ((String) -> Unit)?,
    onReply: () -> Unit,
    onVoice: (() -> Unit)?,
    onReact: (() -> Unit)? = null,
    onLongPressMessage: ((WatchTimelineItem) -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val listState = rememberScalingLazyListState()
    var didInitialScroll by remember(state.timelineKey) { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    // Scroll to the last item as soon as items arrive. Use initialCenterItemIndex-style positioning.
    LaunchedEffect(state.items.size) {
        if (state.items.isNotEmpty() && !didInitialScroll) {
            // +1 for the header item, +1 for potential loading item
            val totalItemCount = listState.layoutInfo.totalItemsCount
            if (totalItemCount > 0) {
                listState.scrollToItem(totalItemCount - 1)
            }
            didInitialScroll = true
        }
    }

    val isAtBottom by remember {
        derivedStateOf {
            val info = listState.layoutInfo
            val lastVisible = info.visibleItemsInfo.lastOrNull()?.index ?: 0
            val totalItems = info.totalItemsCount
            totalItems <= 1 || lastVisible >= totalItems - 2
        }
    }

    Column(modifier = modifier.fillMaxSize()) {
        Box(modifier = Modifier.weight(1f)) {
            ScalingLazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                item {
                    ListHeader {
                        Text(
                            text = state.displayName,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                if (state.items.isEmpty() && state.isLoading) {
                    item {
                        Text(
                            text = stringResource(R.string.screen_room_loading_messages),
                            style = MaterialTheme.typography.body2,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 12.dp),
                        )
                    }
                }
                itemsIndexed(state.items, key = { _, it -> it.eventId }) { index, entry ->
                    val prevItem = state.items.getOrNull(index - 1)
                    val showDayDivider = prevItem == null || !isSameDay(prevItem.timestampMs, entry.timestampMs)
                    val showSender = prevItem == null || prevItem.senderId != entry.senderId || showDayDivider

                    if (showDayDivider && index > 0) {
                        DayDivider(timestampMs = entry.timestampMs)
                    }

                    TimelineMessageRow(
                        item = entry,
                        showSender = showSender,
                        onClick = { onMessageSelected(entry.eventId) },
                        onOpenThread = onOpenThread,
                        onLongPress = onLongPressMessage?.let { callback -> { callback(entry) } },
                    )
                }
            }

            // Scroll-to-bottom button
            if (!isAtBottom && state.items.size > 3) {
                Button(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 4.dp)
                        .size(32.dp),
                    onClick = {
                        scope.launch {
                            val total = listState.layoutInfo.totalItemsCount
                            if (total > 0) listState.animateScrollToItem(total - 1)
                        }
                    },
                    colors = ButtonDefaults.primaryButtonColors(),
                ) {
                    Icon(
                        imageVector = Icons.Filled.KeyboardArrowDown,
                        contentDescription = stringResource(R.string.a11y_scroll_to_bottom),
                    )
                }
            }
        }
        ComposerBar(
            onReply = onReply,
            onVoice = onVoice,
            onReact = onReact,
            contextLabel = state.composerContextLabel,
        )
    }
}

/** Stable snapshot used to drive [RoomView]. */
internal data class RoomViewState(
    val timelineKey: String,
    val displayName: String,
    val items: List<WatchTimelineItem>,
    val composerContextLabel: String? = null,
    val isLoading: Boolean = true,
)

@Composable
private fun DayDivider(timestampMs: Long) {
    Text(
        text = dayLabel(timestampMs),
        style = MaterialTheme.typography.caption2,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp, horizontal = 8.dp),
        color = MaterialTheme.colors.onSurfaceVariant,
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
