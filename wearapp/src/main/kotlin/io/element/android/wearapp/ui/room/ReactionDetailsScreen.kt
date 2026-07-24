/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.wearapp.ui.room

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.items
import androidx.wear.compose.material3.CircularProgressIndicator
import androidx.wear.compose.material3.ListHeader
import androidx.wear.compose.material3.OutlinedIconButton
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.Text
import io.element.android.watchbridge.contract.WatchCommand
import io.element.android.watchbridge.contract.WatchReactionSender
import io.element.android.watchbridge.contract.WatchReactionSummary
import io.element.android.wearapp.R
import io.element.android.wearapp.bridge.WearBridgeClient
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.launch

@Composable
internal fun ReactionDetailsScreen(
    bridge: WearBridgeClient,
    roomId: String,
    eventId: String,
    threadRootEventId: String? = null,
    onError: (Throwable) -> Unit = {},
) {
    val roomState = rememberRoomTimelineState(bridge = bridge, roomId = roomId)
    var threadItems by remember(roomId, threadRootEventId) {
        mutableStateOf(threadRootEventId?.let { bridge.getCachedThread(roomId, it) }.orEmpty())
    }
    if (threadRootEventId != null) {
        DisposableEffect(bridge, roomId, threadRootEventId) {
            onDispose { bridge.unsubscribeThread(roomId, threadRootEventId) }
        }
        LaunchedEffect(bridge, roomId, threadRootEventId) {
            bridge.ensureThreadSubscription(roomId, threadRootEventId)
            bridge.syncEvents.filterIsInstance<io.element.android.watchbridge.contract.WatchSync.ThreadDelta>()
                .filter { it.roomId == roomId && it.threadRootEventId == threadRootEventId }
                .collect { threadItems = bridge.getCachedThread(roomId, threadRootEventId) }
        }
    }
    val sourceReactions = if (threadRootEventId == null) {
        roomState.items.firstOrNull { it.eventId == eventId }?.reactions.orEmpty()
    } else {
        threadItems.firstOrNull { it.eventId == eventId }?.reactions.orEmpty()
    }
    val scope = rememberCoroutineScope()
    var pendingReaction by remember { mutableStateOf<String?>(null) }
    var optimisticStates by remember { mutableStateOf<Map<String, Boolean>>(emptyMap()) }
    LaunchedEffect(sourceReactions) {
        optimisticStates = optimisticStates.filter { (key, expected) ->
            (sourceReactions.firstOrNull { it.key == key }?.reactedBySelf ?: false) != expected
        }
    }
    val reactions = applyOptimisticReactionStates(sourceReactions, optimisticStates)

    ScalingLazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        item { ListHeader { Text(stringResource(R.string.reaction_details_title)) } }
        if (reactions.isEmpty()) {
            item { Text(stringResource(R.string.reaction_details_empty), modifier = Modifier.fillMaxWidth()) }
        }
        reactions.forEach { reaction ->
            item(key = "toggle-${reaction.key}") {
                ReactionToggle(
                    reaction = reaction,
                    enabled = pendingReaction == null,
                    pending = pendingReaction == reaction.key,
                    onToggle = {
                        val target = !reaction.reactedBySelf
                        optimisticStates = optimisticStates + (reaction.key to target)
                        sendReaction(reaction.key, bridge, roomId, eventId, scope, onError) { pending, failed ->
                            pendingReaction = pending
                            if (failed) optimisticStates = optimisticStates - reaction.key
                        }
                    },
                )
            }
            val rows = reactionRows(reaction)
            items(rows, key = { "${it.emoji}-${it.sender?.userId}-${it.index}" }) { row ->
                Text(
                    text = row.sender?.displayName ?: stringResource(R.string.reaction_details_unknown),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                )
            }
        }
        val additionalReactions = availableQuickReactions(reactions)
        if (additionalReactions.isNotEmpty()) {
            item { ListHeader { Text(stringResource(R.string.reaction_details_additional)) } }
            additionalReactions.chunked(3).forEachIndexed { index, row ->
                item(key = "additional-$index") {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                        row.forEach { reactionKey ->
                            OutlinedIconButton(
                                modifier = Modifier.size(40.dp),
                                enabled = pendingReaction == null,
                                onClick = {
                                    optimisticStates = optimisticStates + (reactionKey to true)
                                    sendReaction(reactionKey, bridge, roomId, eventId, scope, onError) { pending, failed ->
                                        pendingReaction = pending
                                        if (failed) optimisticStates = optimisticStates - reactionKey
                                    }
                                },
                            ) {
                                Text(reactionKey, textAlign = TextAlign.Center, style = MaterialTheme.typography.titleSmall)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ReactionToggle(
    reaction: WatchReactionSummary,
    enabled: Boolean,
    pending: Boolean,
    onToggle: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(reaction.key, style = MaterialTheme.typography.titleMedium)
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (pending) {
                CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                Spacer(modifier = Modifier.size(4.dp))
            }
            OutlinedIconButton(onClick = onToggle, enabled = enabled, modifier = Modifier.size(32.dp)) {
                Text(if (reaction.reactedBySelf) "−" else "+", style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

private fun sendReaction(
    reactionKey: String,
    bridge: WearBridgeClient,
    roomId: String,
    eventId: String,
    scope: kotlinx.coroutines.CoroutineScope,
    onError: (Throwable) -> Unit,
    setPending: (String?, Boolean) -> Unit,
) {
    setPending(reactionKey, false)
    scope.launch {
        val result = runCatching {
            bridge.sendAwaitTerminalAck { requestId -> WatchCommand.SendReaction(requestId, roomId, eventId, reactionKey) }
        }
        result.onFailure(onError)
        setPending(null, result.isFailure)
    }
}

internal fun applyOptimisticReactionStates(
    reactions: List<WatchReactionSummary>,
    states: Map<String, Boolean>,
): List<WatchReactionSummary> {
    val byKey = reactions.associateByTo(linkedMapOf()) { it.key }
    states.forEach { (key, reactedBySelf) ->
        val current = byKey[key]
        if (current == null && reactedBySelf) {
            byKey[key] = WatchReactionSummary(key = key, count = 1, reactedBySelf = true)
        } else if (current != null && current.reactedBySelf != reactedBySelf) {
            byKey[key] = current.copy(
                reactedBySelf = reactedBySelf,
                count = (current.count + if (reactedBySelf) 1 else -1).coerceAtLeast(0),
            )
        }
    }
    return byKey.values.filter { it.count > 0 }
}

internal fun availableQuickReactions(reactions: List<WatchReactionSummary>): List<String> {
    val existing = reactions.mapTo(mutableSetOf()) { it.key }
    return QUICK_REACTIONS.filterNot(existing::contains)
}

internal fun reactionRows(reaction: WatchReactionSummary): List<ReactionDetailsRow> =
    if (reaction.senders.isEmpty()) {
        List(reaction.count) { index -> ReactionDetailsRow(reaction.key, null, index) }
    } else {
        reaction.senders.mapIndexed { index, sender -> ReactionDetailsRow(reaction.key, sender, index) }
    }

internal data class ReactionDetailsRow(
    val emoji: String,
    val sender: WatchReactionSender?,
    val index: Int,
)
