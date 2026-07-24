/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.wearapp.ui.room

import androidx.activity.ComponentActivity
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.wear.compose.foundation.lazy.ScalingLazyListState
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import com.google.common.truth.Truth.assertThat
import io.element.android.watchbridge.contract.WatchRoomKind
import io.element.android.watchbridge.contract.WatchRoomSummary
import io.element.android.watchbridge.contract.WatchTimelineItem
import io.element.android.watchbridge.contract.WatchTimelineItemKind
import io.element.android.wearapp.ui.favorites.SavedScalingListPosition
import io.element.android.wearapp.ui.theme.WearAppTheme
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [33])
class RoomViewTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun `conversation details page shows metadata and pinned messages without opening message detail`() {
        var selectedEventId: String? = null
        val pinnedItem = WatchTimelineItem(
            eventId = "${'$'}pinned:server",
            roomId = "!room:server",
            senderId = "@alice:server",
            senderDisplayName = "Alice",
            timestampMs = 1L,
            kind = WatchTimelineItemKind.TEXT,
            bodyText = "Pinned content",
            isPinned = true,
        )
        val summary = WatchRoomSummary(
            roomId = "!room:server",
            displayName = "Project room",
            kind = WatchRoomKind.GROUP,
            isEncrypted = true,
            canSendMessages = true,
            timelineVersion = 1L,
            lastSyncTsMs = 2L,
            topic = "Delivery planning",
            isFavorite = true,
        )

        rule.setContent {
            WearAppTheme {
                RoomView(
                    state = RoomViewState(
                        timelineKey = "!room:server",
                        displayName = "Project room",
                        items = listOf(pinnedItem),
                        isLoading = false,
                        summary = summary,
                    ),
                    onMessageSelected = { selectedEventId = it },
                    onOpenThread = null,
                    onReply = {},
                    onVoice = null,
                    enableConversationDetails = true,
                )
            }
        }

        rule.onRoot().performTouchInput { swipeLeft() }
        rule.waitUntil(5_000L) {
            runCatching { rule.onNodeWithText("Conversation details").fetchSemanticsNode() }.isSuccess
        }
        rule.onNodeWithText("Project room").assertExists()
        rule.onNodeWithText("Delivery planning").assertExists()
        rule.onNodeWithText("Favorite").assertExists()
        rule.onNodeWithText("Pinned content").performClick()
        rule.runOnIdle { assertThat(selectedEventId).isNull() }
    }

    @Test
    fun `force scroll request opens the latest timeline items`() {
        lateinit var listState: ScalingLazyListState
        var handledScrollRequestId: Long? = null
        val items = (1..40).map { index ->
            WatchTimelineItem(
                eventId = "\$event-$index:server",
                roomId = "!room:server",
                senderId = "@alice:server",
                senderDisplayName = "Alice",
                timestampMs = index.toLong(),
                kind = WatchTimelineItemKind.TEXT,
                bodyText = "Message $index",
            )
        }

        rule.setContent {
            listState = rememberScalingLazyListState()
            WearAppTheme {
                RoomView(
                    state = RoomViewState(
                        timelineKey = "!room:server",
                        displayName = "Latest room",
                        items = items,
                        isLoading = false,
                        scrollRequestId = 99L,
                        forceScrollToBottom = true,
                    ),
                    onMessageSelected = {},
                    onOpenThread = null,
                    onReply = {},
                    onVoice = null,
                    onScrollRequestHandled = { handledScrollRequestId = it },
                    listState = listState,
                )
            }
        }

        rule.waitUntil(timeoutMillis = 5_000L) { handledScrollRequestId == 99L }

        rule.runOnIdle {
            val totalItems = listState.layoutInfo.totalItemsCount
            val lastVisibleIndex = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1

            assertThat(totalItems).isGreaterThan(0)
            assertThat(lastVisibleIndex).isEqualTo(totalItems - 1)
            assertThat(handledScrollRequestId).isEqualTo(99L)
        }
    }

    @Test
    fun `saved list position restores when reopening a room`() {
        lateinit var listState: ScalingLazyListState
        val items = (1..40).map { index ->
            WatchTimelineItem(
                eventId = "\$event-$index:server",
                roomId = "!room:server",
                senderId = "@alice:server",
                senderDisplayName = "Alice",
                timestampMs = index.toLong(),
                kind = WatchTimelineItemKind.TEXT,
                bodyText = "Message $index",
            )
        }

        rule.setContent {
            listState = rememberScalingLazyListState()
            WearAppTheme {
                RoomView(
                    state = RoomViewState(
                        timelineKey = "!room:server",
                        displayName = "Latest room",
                        items = items,
                        isLoading = false,
                    ),
                    onMessageSelected = {},
                    onOpenThread = null,
                    onReply = {},
                    onVoice = null,
                    listState = listState,
                    savedListPosition = SavedScalingListPosition(index = 18, offset = 0),
                )
            }
        }

        rule.waitUntil(timeoutMillis = 5_000L) {
            listState.centerItemIndex >= 18
        }

        rule.runOnIdle {
            assertThat(listState.centerItemIndex).isAtLeast(18)
        }
    }

    @Test
    fun `first open scrolls to latest item even when a read marker exists`() {
        lateinit var listState: ScalingLazyListState
        val items = (1..40).map { index ->
            WatchTimelineItem(
                eventId = "\$event-$index:server",
                roomId = "!room:server",
                senderId = "@alice:server",
                senderDisplayName = "Alice",
                timestampMs = index.toLong(),
                kind = WatchTimelineItemKind.TEXT,
                bodyText = "Message $index",
                isReadMarkerAnchor = index == 12,
            )
        }

        rule.setContent {
            listState = rememberScalingLazyListState()
            WearAppTheme {
                RoomView(
                    state = RoomViewState(
                        timelineKey = "!room:server",
                        displayName = "Latest room",
                        items = items,
                        isLoading = false,
                    ),
                    onMessageSelected = {},
                    onOpenThread = null,
                    onReply = {},
                    onVoice = null,
                    listState = listState,
                )
            }
        }

        rule.waitUntil(timeoutMillis = 5_000L) {
            val totalItems = listState.layoutInfo.totalItemsCount
            val lastVisibleIndex = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1
            totalItems > 0 && lastVisibleIndex == totalItems - 1
        }

        rule.runOnIdle {
            val totalItems = listState.layoutInfo.totalItemsCount
            val lastVisibleIndex = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1
            assertThat(lastVisibleIndex).isEqualTo(totalItems - 1)
        }
    }

    @Test
    fun `delayed initial timeline keeps the conversation at the latest item`() {
        lateinit var listState: ScalingLazyListState
        val state = mutableStateOf(
            RoomViewState(
                timelineKey = "!room:server",
                displayName = "Delayed room",
                items = emptyList(),
                isLoading = true,
            ),
        )
        val items = (1..40).map { index ->
            WatchTimelineItem(
                eventId = "\$event-$index:server",
                roomId = "!room:server",
                senderId = "@alice:server",
                senderDisplayName = "Alice",
                timestampMs = index.toLong(),
                kind = WatchTimelineItemKind.TEXT,
                bodyText = "Message $index",
            )
        }

        rule.setContent {
            listState = rememberScalingLazyListState()
            WearAppTheme {
                RoomView(
                    state = state.value,
                    onMessageSelected = {},
                    onOpenThread = null,
                    onReply = {},
                    onVoice = null,
                    listState = listState,
                )
            }
        }

        rule.runOnIdle { state.value = state.value.copy(items = items, isLoading = false) }
        rule.waitUntil(timeoutMillis = 5_000L) {
            val info = listState.layoutInfo
            info.totalItemsCount > 0 && info.visibleItemsInfo.lastOrNull()?.index == info.totalItemsCount - 1
        }
    }

    @Test
    fun `message tap snapshots current list position before navigation`() {
        lateinit var initialListState: ScalingLazyListState
        var selectedEventId: String? = null
        var savedPosition: SavedScalingListPosition? = null
        val items = (1..40).map { index ->
            WatchTimelineItem(
                eventId = "\$event-$index:server",
                roomId = "!room:server",
                senderId = "@alice:server",
                senderDisplayName = "Alice",
                timestampMs = index.toLong(),
                kind = WatchTimelineItemKind.TEXT,
                bodyText = "Message $index",
            )
        }

        rule.setContent {
            WearAppTheme {
                initialListState = rememberScalingLazyListState()
                RoomView(
                    state = RoomViewState(
                        timelineKey = "!room:server",
                        displayName = "Latest room",
                        items = items,
                        isLoading = false,
                    ),
                    onMessageSelected = { selectedEventId = it },
                    onOpenThread = null,
                    onReply = {},
                    onVoice = null,
                    listState = initialListState,
                    onListPositionChange = { savedPosition = it },
                )
            }
        }

        rule.runOnIdle {
            runBlocking {
                initialListState.scrollToItem(20)
            }
            savedPosition = null
        }

        rule.waitUntil(timeoutMillis = 5_000L) {
            initialListState.centerItemIndex >= 20
        }

        rule.onNodeWithText("Message 20", useUnmergedTree = true).performClick()

        val capturedPosition = rule.runOnIdle {
            assertThat(selectedEventId).isEqualTo("\$event-20:server")
            assertThat(savedPosition).isNotNull()
            savedPosition
        } ?: error("Expected saved position after message tap")

        rule.runOnIdle {
            assertThat(capturedPosition.index).isAtLeast(20)
        }
    }

    @Test
    fun `force scroll request follows first fresh item after open`() {
        lateinit var listState: ScalingLazyListState
        var handledScrollRequestId: Long? = null
        val initialItems = (1..20).map { index ->
            WatchTimelineItem(
                eventId = "\$event-$index:server",
                roomId = "!room:server",
                senderId = "@alice:server",
                senderDisplayName = "Alice",
                timestampMs = index.toLong(),
                kind = WatchTimelineItemKind.TEXT,
                bodyText = "Message $index",
            )
        }
        val state = mutableStateOf(
            RoomViewState(
                timelineKey = "!room:server",
                displayName = "Latest room",
                items = initialItems,
                isLoading = false,
                scrollRequestId = 7L,
                forceScrollToBottom = true,
            ),
        )

        rule.setContent {
            listState = rememberScalingLazyListState()
            WearAppTheme {
                RoomView(
                    state = state.value,
                    onMessageSelected = {},
                    onOpenThread = null,
                    onReply = {},
                    onVoice = null,
                    onScrollRequestHandled = { handledScrollRequestId = it },
                    listState = listState,
                )
            }
        }

        rule.waitUntil(timeoutMillis = 5_000L) { handledScrollRequestId == 7L }

        rule.runOnIdle {
            state.value = state.value.copy(
                scrollRequestId = null,
                forceScrollToBottom = false,
                items = initialItems + WatchTimelineItem(
                    eventId = "\$event-21:server",
                    roomId = "!room:server",
                    senderId = "@alice:server",
                    senderDisplayName = "Alice",
                    timestampMs = 21L,
                    kind = WatchTimelineItemKind.TEXT,
                    bodyText = "Message 21",
                ),
            )
        }

        rule.waitUntil(timeoutMillis = 5_000L) {
            val totalItems = listState.layoutInfo.totalItemsCount
            val lastVisibleIndex = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1
            totalItems > 0 && lastVisibleIndex == totalItems - 1
        }

        rule.runOnIdle {
            val totalItems = listState.layoutInfo.totalItemsCount
            val lastVisibleIndex = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1
            assertThat(lastVisibleIndex).isEqualTo(totalItems - 1)
        }
    }
}
