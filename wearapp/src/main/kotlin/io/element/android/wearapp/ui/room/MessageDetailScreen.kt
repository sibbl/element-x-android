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
import io.element.android.watchbridge.contract.WatchSendSource
import io.element.android.wearapp.audio.WearTextToSpeech
import io.element.android.wearapp.bridge.WearBridgeClient
import io.element.android.wearapp.ui.WearMainActivity
import io.element.android.wearapp.ui.voice.VoiceRecorderActivity
import kotlinx.coroutines.launch

@Composable
fun MessageDetailScreen(
    bridge: WearBridgeClient,
    roomId: String,
    eventId: String,
    activity: WearMainActivity,
    onOpenThread: (String) -> Unit,
) {
    val favoriteRooms by bridge.favorites.collectAsState()
    val roomState = rememberRoomTimelineState(
        bridge = bridge,
        roomId = roomId,
    )
    val scope = rememberCoroutineScope()
    val tts = remember { WearTextToSpeech(activity) }
    val fallbackRoom = favoriteRooms.firstOrNull { it.roomId == roomId }
    val roomName = roomState.summary?.displayName
        ?: fallbackRoom?.displayName
        ?: ""
    val item = roomState.items.firstOrNull { it.eventId == eventId }

    MessageDetailView(
        state = MessageDetailViewState(
            roomDisplayName = roomName,
            item = item,
        ),
        onReply = {
            if (item != null) {
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
        },
        onVoice = {
            activity.startActivity(
                Intent(activity, VoiceRecorderActivity::class.java)
                    .putExtra("roomId", roomId)
                    .putExtra("inReplyToEventId", item?.eventId),
            )
        },
        onReadAloud = {
            item?.let { tts.speak(it.displayText()) }
        },
        onOpenOrStartThread = {
            val rootEventId = item?.threadRootEventId ?: item?.eventId
            if (rootEventId != null) onOpenThread(rootEventId)
        },
        onSendReaction = { reactionKey ->
            val target = item ?: return@MessageDetailView
            scope.launch {
                runCatching {
                    bridge.send { requestId ->
                        WatchCommand.SendReaction(
                            requestId = requestId,
                            roomId = roomId,
                            eventId = target.eventId,
                            reactionKey = reactionKey,
                        )
                    }
                }
            }
        },
    )
}
