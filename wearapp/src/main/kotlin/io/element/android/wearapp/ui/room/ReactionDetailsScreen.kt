/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.wearapp.ui.room

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.items
import androidx.wear.compose.material3.ListHeader
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.Text
import io.element.android.watchbridge.contract.WatchReactionSender
import io.element.android.wearapp.R
import io.element.android.wearapp.bridge.WearBridgeClient

@Composable
internal fun ReactionDetailsScreen(
    bridge: WearBridgeClient,
    roomId: String,
    eventId: String,
) {
    val roomState = rememberRoomTimelineState(bridge = bridge, roomId = roomId)
    val reactions = roomState.items.firstOrNull { it.eventId == eventId }?.reactions.orEmpty()
    val rows = reactions.flatMap { reaction ->
        if (reaction.senders.isEmpty()) {
            List(reaction.count) { index -> ReactionDetailsRow(reaction.key, null, index) }
        } else {
            reaction.senders.mapIndexed { index, sender -> ReactionDetailsRow(reaction.key, sender, index) }
        }
    }

    ScalingLazyColumn(
        modifier = Modifier.fillMaxSize().padding(horizontal = 8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        item { ListHeader { Text(stringResource(R.string.reaction_details_title)) } }
        if (rows.isEmpty()) {
            item { Text(stringResource(R.string.reaction_details_empty)) }
        } else {
            items(rows, key = { "${it.emoji}-${it.sender?.userId}-${it.index}" }) { row ->
                Text(
                    text = "${row.emoji}  ${row.sender?.displayName ?: stringResource(R.string.reaction_details_unknown)}",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
    }
}

private data class ReactionDetailsRow(
    val emoji: String,
    val sender: WatchReactionSender?,
    val index: Int,
)
