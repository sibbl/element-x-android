/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.wearapp.ui.thread

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import io.element.android.watchbridge.contract.WatchThreadItem
import io.element.android.wearapp.R
import io.element.android.wearapp.bridge.WearBridgeClient
import io.element.android.wearapp.ui.WearMainActivity
import io.element.android.wearapp.ui.common.ComposerBar
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.launch

@Composable
fun ThreadScreen(
    bridge: WearBridgeClient,
    roomId: String,
    threadRootEventId: String,
    activity: WearMainActivity,
) {
    var items by remember { mutableStateOf<List<WatchThreadItem>>(emptyList()) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(roomId, threadRootEventId) {
        bridge.send {
            WatchCommand.FetchThread(requestId = it, roomId = roomId, threadRootEventId = threadRootEventId)
        }
        bridge.syncEvents.filterIsInstance<WatchSync.ThreadDelta>()
            .collect { delta ->
                if (delta.roomId == roomId && delta.threadRootEventId == threadRootEventId) {
                    val merged = (items + delta.items)
                        .filter { it.eventId !in delta.removedEventIds }
                        .distinctBy { it.eventId }
                        .sortedBy { it.timestampMs }
                        .takeLast(50)
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
            item { ListHeader { Text(stringResource(R.string.thread)) } }
            items(count = items.size, key = { idx -> items[idx].eventId }) { idx ->
                val item = items[idx]
                Chip(
                    label = { Text("${item.senderDisplayName ?: item.senderId}: ${item.bodyText ?: "[${item.kind}]"}") },
                    onClick = {},
                    colors = if (item.isOwn) ChipDefaults.primaryChipColors() else ChipDefaults.secondaryChipColors(),
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
                            threadRootEventId = threadRootEventId,
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
                                    threadRootEventId = threadRootEventId,
                                    text = dictated,
                                    source = io.element.android.watchbridge.contract.WatchSendSource.DICTATION,
                                    clientTsMs = System.currentTimeMillis(),
                                )
                            }
                        }
                    }
                }
            },
            onVoice = null,
        )
    }
}
