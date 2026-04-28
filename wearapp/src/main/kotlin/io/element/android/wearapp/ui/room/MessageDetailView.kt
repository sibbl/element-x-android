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
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material.Chip
import androidx.wear.compose.material.ChipDefaults
import androidx.wear.compose.material.ListHeader
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Text
import io.element.android.watchbridge.contract.WatchTimelineItem
import io.element.android.wearapp.R
import io.element.android.wearapp.audio.WearVoicePlayer
import io.element.android.wearapp.ui.common.ComposerBar
import java.text.DateFormat
import java.util.Date

private val QUICK_REACTIONS = listOf("👍", "❤️", "😂", "🎉", "🙏", "👀")

/**
 * Detail surface for a single message. Body is rendered with a slightly smaller font; the action
 * stack lives below it so the user can reply, send a voice message, listen, or open/start a
 * thread without leaving the screen.
 */
@Composable
internal fun MessageDetailView(
    state: MessageDetailViewState,
    onReply: () -> Unit,
    onVoice: (() -> Unit)?,
    onReadAloud: () -> Unit,
    onOpenOrStartThread: () -> Unit,
    onSendReaction: (reactionKey: String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val listState = rememberScalingLazyListState()
    val item = state.item
    val sender = item?.senderDisplayName ?: item?.senderId.orEmpty()

    val voicePlayer = remember { WearVoicePlayer() }
    val voiceState by voicePlayer.state.collectAsState()
    DisposableEffect(Unit) { onDispose { voicePlayer.stop() } }

    Column(modifier = modifier.fillMaxSize()) {
        ScalingLazyColumn(
            state = listState,
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            item {
                ListHeader {
                    Text(
                        text = state.roomDisplayName,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            if (item != null) {
                item {
                    Text(
                        text = sender,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 8.dp),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.caption1.copy(fontWeight = FontWeight.SemiBold),
                    )
                }
                item {
                    Text(
                        text = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
                            .format(Date(item.timestampMs)),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 8.dp),
                        style = MaterialTheme.typography.caption2,
                        color = MaterialTheme.colors.onSurfaceVariant,
                    )
                }
                item {
                    MessageDetailedBody(
                        item = item,
                        onPlayVoice = { url ->
                            if (voiceState == WearVoicePlayer.State.PLAYING) {
                                voicePlayer.stop()
                            } else {
                                voicePlayer.play(url)
                            }
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 8.dp),
                    )
                }
                if (item.reactions.isNotEmpty()) {
                    item { ReactionsRow(item = item) }
                }
                if (item.readableByTts && (item.bodyText != null || item.formattedText != null)) {
                    item {
                        Chip(
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text(stringResource(R.string.read_aloud)) },
                            onClick = onReadAloud,
                            colors = ChipDefaults.secondaryChipColors(),
                        )
                    }
                }
                item {
                    Chip(
                        modifier = Modifier.fillMaxWidth(),
                        label = {
                            Text(
                                text = stringResource(
                                    if (item.hasThread) R.string.screen_message_detail_open_thread
                                    else R.string.screen_message_detail_start_thread,
                                ),
                            )
                        },
                        secondaryLabel = if (item.hasThread) {
                            {
                                Text(
                                    text = stringResource(R.string.thread) + " · ${item.threadReplyCount}",
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        } else null,
                        onClick = onOpenOrStartThread,
                        colors = ChipDefaults.primaryChipColors(),
                    )
                }
                item {
                    Text(
                        text = stringResource(R.string.screen_message_detail_reactions),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 8.dp),
                        style = MaterialTheme.typography.caption2,
                    )
                }
                QUICK_REACTIONS.chunked(3).forEach { reactions ->
                    item {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            reactions.forEach { reactionKey ->
                                Chip(
                                    modifier = Modifier.weight(1f),
                                    label = { Text(reactionKey) },
                                    onClick = { onSendReaction(reactionKey) },
                                    colors = ChipDefaults.secondaryChipColors(),
                                )
                            }
                        }
                    }
                }
            }
        }
        ComposerBar(
            onReply = onReply,
            onVoice = onVoice,
            onReact = null,
            contextLabel = if (item != null) {
                stringResource(R.string.composer_reply_to, sender)
            } else null,
        )
    }
}

internal data class MessageDetailViewState(
    val roomDisplayName: String,
    val item: WatchTimelineItem?,
)
