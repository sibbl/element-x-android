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
import io.element.android.wearapp.ui.voice.VoiceRecorderActivity
import kotlinx.coroutines.launch

@Composable
fun RoomScreen(
    bridge: WearBridgeClient,
    roomId: String,
    activity: WearMainActivity,
    onOpenThread: (String) -> Unit,
    onMessageSelected: (String) -> Unit,
) {
    val favoriteRooms by bridge.favorites.collectAsState()
    val settings by bridge.companionSettings.collectAsState()
    val roomState = rememberRoomTimelineState(
        bridge = bridge,
        roomId = roomId,
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
        ),
        onMessageSelected = onMessageSelected,
        onOpenThread = onOpenThread,
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
                                bridge.send {
                                    WatchCommand.SendText(
                                        requestId = it,
                                        roomId = roomId,
                                        text = dictated,
                                        source = WatchSendSource.DICTATION,
                                        clientTsMs = System.currentTimeMillis(),
                                    )
                                }
                            }
                        }
                    }
                }
                WatchLongPressMessageAction.REPLY_VOICE -> {
                    activity.startActivity(
                        Intent(activity, VoiceRecorderActivity::class.java)
                            .putExtra("roomId", roomId)
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
                        bridge.send {
                            WatchCommand.SendText(
                                requestId = it,
                                roomId = roomId,
                                text = dictated,
                                source = WatchSendSource.DICTATION,
                                clientTsMs = System.currentTimeMillis(),
                            )
                        }
                    }
                }
            }
        },
        onVoice = {
            activity.startActivity(
                Intent(activity, VoiceRecorderActivity::class.java)
                    .putExtra("roomId", roomId),
            )
        },
    )
}
