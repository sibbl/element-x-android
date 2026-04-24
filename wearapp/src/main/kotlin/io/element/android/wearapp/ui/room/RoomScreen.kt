/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.wearapp.ui.room

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.items
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material.Chip
import androidx.wear.compose.material.ChipDefaults
import androidx.wear.compose.material.ListHeader
import androidx.wear.compose.material.Text
import io.element.android.watchbridge.contract.WatchCommand
import io.element.android.watchbridge.contract.WatchSync
import io.element.android.watchbridge.contract.WatchTimelineItem
import io.element.android.watchbridge.contract.WatchTimelineItemKind
import io.element.android.wearapp.R
import io.element.android.wearapp.audio.WearTextToSpeech
import io.element.android.wearapp.bridge.WearBridgeClient
import io.element.android.wearapp.ui.WearMainActivity
import io.element.android.wearapp.ui.common.ComposerBar
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.launch

private val QUICK_REACTIONS = listOf("👍", "❤️", "😂", "🎉", "🙏", "👀")

@Composable
fun RoomScreen(
    bridge: WearBridgeClient,
    roomId: String,
    activity: WearMainActivity,
    onOpenThread: (String) -> Unit,
) {
    var items by remember { mutableStateOf<List<WatchTimelineItem>>(emptyList()) }
    val scope = rememberCoroutineScope()
    val tts = remember { WearTextToSpeech(activity) }

    LaunchedEffect(roomId) {
        bridge.send { id -> WatchCommand.OpenRoom(requestId = id, roomId = roomId) }
        bridge.syncEvents.filterIsInstance<WatchSync.TimelineDelta>()
            .collect { delta ->
                if (delta.roomId == roomId) {
                    val merged = (items + delta.items)
                        .filter { it.eventId !in delta.removedEventIds }
                        .distinctBy { it.eventId }
                        .sortedBy { it.timestampMs }
                        .takeLast(100)
                    items = merged
                }
            }
    }

    val listState = rememberScalingLazyListState()
    Column(modifier = Modifier.fillMaxSize()) {
        ScalingLazyColumn(
            state = listState,
            modifier = Modifier.padding(horizontal = 8.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            item { ListHeader { Text(roomId) } }
            items(count = items.size, key = { idx -> items[idx].eventId }) { idx ->
                TimelineItemRow(
                    item = items[idx],
                    onOpenThread = onOpenThread,
                    onReact = { ev, key ->
                        scope.launch {
                            bridge.send {
                                WatchCommand.SendReaction(requestId = it, roomId = roomId, eventId = ev, reactionKey = key)
                            }
                        }
                    },
                    onReadAloud = { text -> tts.speak(text) },
                )
            }
        }
        ComposerBar(
            onSend = { text ->
                scope.launch {
                    bridge.send {
                        WatchCommand.SendText(
                            requestId = it,
                            roomId = roomId,
                            text = text,
                            clientTsMs = System.currentTimeMillis(),
                        )
                    }
                }
            },
            onDictate = {
                activity.launchDictation { dictated ->
                    if (!dictated.isNullOrBlank()) {
                        scope.launch {
                            bridge.send {
                                WatchCommand.SendText(
                                    requestId = it,
                                    roomId = roomId,
                                    text = dictated,
                                    source = io.element.android.watchbridge.contract.WatchSendSource.DICTATION,
                                    clientTsMs = System.currentTimeMillis(),
                                )
                            }
                        }
                    }
                }
            },
            onVoice = {
                activity.startActivity(
                    android.content.Intent(activity, io.element.android.wearapp.ui.voice.VoiceRecorderActivity::class.java)
                        .putExtra("roomId", roomId),
                )
            },
        )
    }
}

@Composable
private fun TimelineItemRow(
    item: WatchTimelineItem,
    onOpenThread: (String) -> Unit,
    onReact: (eventId: String, reactionKey: String) -> Unit,
    onReadAloud: (String) -> Unit,
) {
    val line = "${item.senderDisplayName ?: item.senderId}: ${item.bodyText ?: "[${item.kind}]"}"
    Chip(
        label = { Text(line) },
        onClick = {
            if (item.kind == WatchTimelineItemKind.TEXT && item.bodyText != null) onReadAloud(item.bodyText!!)
        },
        secondaryLabel = {
            val parts = buildList {
                if (item.hasThread) add(stringResource(R.string.thread) + " (${item.threadReplyCount})")
                item.reactions.forEach { add("${it.key} ${it.count}") }
            }
            if (parts.isNotEmpty()) Text(parts.joinToString(" · "))
        },
        colors = if (item.isOwn) ChipDefaults.primaryChipColors() else ChipDefaults.secondaryChipColors(),
    )
    if (item.hasThread && item.threadRootEventId != null) {
        Chip(
            label = { Text(stringResource(R.string.thread)) },
            onClick = { onOpenThread(item.threadRootEventId!!) },
            colors = ChipDefaults.secondaryChipColors(),
        )
    }
    Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        QUICK_REACTIONS.forEach { key ->
            Chip(
                label = { Text(key) },
                onClick = { onReact(item.eventId, key) },
                colors = ChipDefaults.secondaryChipColors(),
            )
        }
    }
}
