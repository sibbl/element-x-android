/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.wearapp.ui.thread

import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import io.element.android.watchbridge.contract.WatchCommand
import io.element.android.watchbridge.contract.WatchSendSource
import io.element.android.watchbridge.contract.WatchSync
import io.element.android.watchbridge.contract.WatchThreadItem
import io.element.android.watchbridge.contract.WatchTimelineItem
import io.element.android.wearapp.R
import io.element.android.wearapp.audio.WearTextToSpeech
import io.element.android.wearapp.bridge.WearBridgeClient
import io.element.android.wearapp.ui.WearMainActivity
import io.element.android.wearapp.ui.common.watchCommandErrorMessage
import io.element.android.wearapp.ui.favorites.SavedScalingListPosition
import io.element.android.wearapp.ui.room.RoomView
import io.element.android.wearapp.ui.room.RoomViewState
import io.element.android.wearapp.ui.room.displayText
import io.element.android.wearapp.ui.voice.VoiceRecorderActivity
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.launch

@Composable
fun ThreadScreen(
    bridge: WearBridgeClient,
    roomId: String,
    threadRootEventId: String,
    activity: WearMainActivity,
    onMessageSelected: (String) -> Unit = {},
    savedListPosition: SavedScalingListPosition? = null,
    onListPositionChange: (SavedScalingListPosition) -> Unit = {},
    onError: (String) -> Unit = {},
) {
    val cachedItems = remember(roomId, threadRootEventId) {
        bridge.getCachedThread(roomId, threadRootEventId)
    }
    var items by remember(roomId, threadRootEventId) { mutableStateOf(cachedItems) }
    var hasReceivedDelta by remember(roomId, threadRootEventId) {
        mutableStateOf(
            bridge.hasCachedThreadSnapshot(roomId, threadRootEventId) || cachedItems.isNotEmpty(),
        )
    }
    var initialBottomScrollRequestId by remember(roomId, threadRootEventId, savedListPosition) {
        mutableStateOf(System.currentTimeMillis().takeIf { savedListPosition == null })
    }
    val scope = rememberCoroutineScope()
    val tts = remember { WearTextToSpeech(activity) }

    val favorites by bridge.favorites.collectAsState()
    val roomDisplayName = favorites.firstOrNull { it.roomId == roomId }?.displayName ?: ""

    LaunchedEffect(roomId, threadRootEventId) {
        runCatching {
            bridge.ensureThreadSubscription(
                roomId = roomId,
                threadRootEventId = threadRootEventId,
            )
        }.onFailure {
            onError(activity.watchCommandErrorMessage(it, R.string.watch_error_open_thread_failed))
        }
        bridge.syncEvents.filterIsInstance<WatchSync.ThreadDelta>()
            .filter { delta -> delta.roomId == roomId && delta.threadRootEventId == threadRootEventId }
            .collect {
                hasReceivedDelta = true
                items = bridge.getCachedThread(roomId, threadRootEventId)
            }
    }

    val timelineItems = items.map { it.toTimelineItem() }
    val header = buildString {
        append(stringResource(R.string.thread))
        if (roomDisplayName.isNotBlank()) append(" · ").append(roomDisplayName)
    }

    RoomView(
        state = RoomViewState(
            timelineKey = "$roomId/$threadRootEventId",
            displayName = header,
            items = timelineItems,
            composerContextLabel = null,
            isLoading = !hasReceivedDelta,
            emptyText = stringResource(R.string.screen_thread_empty_messages),
            scrollRequestId = initialBottomScrollRequestId,
            forceScrollToBottom = true,
        ),
        onMessageSelected = onMessageSelected,
        // No nested-thread navigation inside a thread.
        onOpenThread = null,
        onScrollRequestHandled = { requestId ->
            if (initialBottomScrollRequestId == requestId) {
                initialBottomScrollRequestId = null
            }
        },
        savedListPosition = savedListPosition,
        onListPositionChange = onListPositionChange,
        mediaPreviewFlowProvider = bridge::mediaPreviewFlow,
        onRequestMediaPreview = bridge::requestMediaPreview,
        onLongPressMessage = { item -> tts.speak(item.displayText()) },
        onReact = {
            timelineItems.lastOrNull()?.let { lastItem ->
                onMessageSelected(lastItem.eventId)
            }
        },
        onReply = {
            activity.launchDictation { dictated ->
                if (!dictated.isNullOrBlank()) {
                    scope.launch {
                        runCatching {
                            bridge.sendAwaitTerminalAck {
                                WatchCommand.SendText(
                                    requestId = it,
                                    roomId = roomId,
                                    threadRootEventId = threadRootEventId,
                                    text = dictated,
                                    source = WatchSendSource.DICTATION,
                                    clientTsMs = System.currentTimeMillis(),
                                )
                            }
                        }.onFailure {
                            onError(activity.watchCommandErrorMessage(it, R.string.watch_error_send_failed))
                        }
                    }
                }
            }
        },
        onVoice = {
            activity.startActivity(
                Intent(activity, VoiceRecorderActivity::class.java)
                    .putExtra("roomId", roomId)
                    .putExtra("roomDisplayName", roomDisplayName)
                    .putExtra("threadRootEventId", threadRootEventId),
            )
        },
    )
}

private fun WatchThreadItem.toTimelineItem(): WatchTimelineItem = WatchTimelineItem(
    eventId = eventId,
    roomId = roomId,
    senderId = senderId,
    senderDisplayName = senderDisplayName,
    timestampMs = timestampMs,
    kind = kind,
    bodyText = bodyText,
    formattedText = null,
    isOwn = isOwn,
    isEdited = false,
    hasThread = false,
    threadRootEventId = threadRootEventId,
    threadReplyCount = 0,
    reactions = reactions,
    voiceMessageMeta = voiceMessageMeta,
    mediaPreview = mediaPreview,
    readableByTts = true,
    threadLastReplyText = null,
)
