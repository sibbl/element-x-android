/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.wearapp.ui.room

import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import io.element.android.watchbridge.contract.WatchCommand
import io.element.android.watchbridge.contract.WatchLongPressMessageAction
import io.element.android.watchbridge.contract.WatchSendSource
import io.element.android.watchbridge.contract.WatchTimelineItem
import io.element.android.wearapp.audio.WearTextToSpeech
import io.element.android.wearapp.bridge.WearBridgeClient
import io.element.android.wearapp.ui.WearMainActivity
import io.element.android.wearapp.ui.common.watchCommandErrorMessage
import io.element.android.wearapp.ui.voice.VoiceRecorderActivity
import kotlinx.coroutines.launch

@Composable
fun RoomScreen(
    bridge: WearBridgeClient,
    roomId: String,
    activity: WearMainActivity,
    onOpenThread: (String) -> Unit,
    onMessageSelected: (String) -> Unit,
    scrollRequestId: Long? = null,
    scrollToEventId: String? = null,
    forceScrollToBottom: Boolean = false,
    onScrollRequestHandled: (Long) -> Unit = {},
    onError: (String) -> Unit = {},
) {
    val favoriteRooms by bridge.favorites.collectAsState()
    val mediaPreviewImages by bridge.mediaPreviewImages.collectAsState()
    val settings by bridge.companionSettings.collectAsState()
    val roomState = rememberRoomTimelineState(
        bridge = bridge,
        roomId = roomId,
        onOpenFailure = { onError(activity.watchCommandErrorMessage(it, io.element.android.wearapp.R.string.watch_error_open_room_failed)) },
    )
    val scope = rememberCoroutineScope()
    val tts = remember { WearTextToSpeech(activity) }
    val fallbackRoom = favoriteRooms.firstOrNull { it.roomId == roomId }
    val displayName = roomState.summary?.displayName
        ?: fallbackRoom?.displayName
        ?: ""

    RoomView(
        state = RoomViewState(
            timelineKey = roomId,
            displayName = displayName,
            items = roomState.items,
            isLoading = !roomState.hasReceivedDelta,
            scrollRequestId = scrollRequestId,
            scrollToEventId = scrollToEventId,
            forceScrollToBottom = forceScrollToBottom,
            mediaPreviewImages = mediaPreviewImages,
        ),
        onMessageSelected = onMessageSelected,
        onOpenThread = onOpenThread,
        onScrollRequestHandled = onScrollRequestHandled,
        onLongPressMessage = { item ->
            when (settings.longPressMessageAction) {
                WatchLongPressMessageAction.READ_ALOUD -> tts.speak(item.displayText())
                WatchLongPressMessageAction.CREATE_THREAD -> {
                    val rootId = item.threadRootEventId ?: item.eventId
                    onOpenThread(rootId)
                }
                WatchLongPressMessageAction.REPLY_EMOJI -> onMessageSelected(item.eventId)
                WatchLongPressMessageAction.REPLY_TEXT -> {
                    activity.launchDictation { dictated ->
                        if (!dictated.isNullOrBlank()) {
                            scope.launch {
                                runCatching {
                                    bridge.sendAwaitTerminalAck {
                                        WatchCommand.SendText(
                                            requestId = it,
                                            roomId = roomId,
                                            inReplyToEventId = item.eventId,
                                            text = dictated,
                                            source = WatchSendSource.DICTATION,
                                            clientTsMs = System.currentTimeMillis(),
                                        )
                                    }
                                }.onFailure {
                                    onError(activity.watchCommandErrorMessage(it, io.element.android.wearapp.R.string.watch_error_send_failed))
                                }
                            }
                        }
                    }
                }
                WatchLongPressMessageAction.REPLY_VOICE -> {
                    activity.startActivity(
                        Intent(activity, VoiceRecorderActivity::class.java)
                            .putExtra("roomId", roomId)
                            .putExtra("roomDisplayName", displayName)
                            .putExtra("inReplyToEventId", item.eventId),
                    )
                }
            }
        },
        onReact = {
            // Navigate to message detail of the last message where reactions can be picked
            roomState.items.lastOrNull()?.let { lastItem ->
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
                                    text = dictated,
                                    source = WatchSendSource.DICTATION,
                                    clientTsMs = System.currentTimeMillis(),
                                )
                            }
                        }.onFailure {
                            onError(activity.watchCommandErrorMessage(it, io.element.android.wearapp.R.string.watch_error_send_failed))
                        }
                    }
                }
            }
        },
        onVoice = {
            activity.startActivity(
                Intent(activity, VoiceRecorderActivity::class.java)
                    .putExtra("roomId", roomId)
                    .putExtra("roomDisplayName", displayName),
            )
        },
    )
}
