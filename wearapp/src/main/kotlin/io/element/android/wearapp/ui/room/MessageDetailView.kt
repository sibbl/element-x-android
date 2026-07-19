/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.wearapp.ui.room

import android.net.Uri
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.rotary.onRotaryScrollEvent
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.FilledTonalButton
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.ListHeader
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.OutlinedIconButton
import androidx.wear.compose.material3.Text
import io.element.android.watchbridge.contract.WatchTimelineItem
import io.element.android.wearapp.R
import io.element.android.wearapp.audio.WearTextToSpeech
import io.element.android.wearapp.audio.WearVoicePlayer
import io.element.android.wearapp.ui.common.ComposerBar
import kotlinx.coroutines.launch
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
    readAloudPlaybackState: WearTextToSpeech.PlaybackState = WearTextToSpeech.PlaybackState.IDLE,
    mediaPreviewBytes: ByteArray? = null,
    onRequestMediaPreview: (() -> Unit)? = null,
    onReply: () -> Unit,
    onVoice: (() -> Unit)?,
    onReadAloud: () -> Unit,
    onOpenImage: (() -> Unit)? = null,
    onOpenOrStartThread: () -> Unit,
    onSendReaction: (reactionKey: String) -> Unit,
    onResolveVoicePlaybackUri: (suspend (WatchTimelineItem) -> Uri)? = null,
    onVoicePlaybackError: (Throwable) -> Unit = {},
    scrollState: ScrollState = rememberScrollState(),
    modifier: Modifier = Modifier,
) {
    val item = state.item
    val sender = item?.senderDisplayName ?: item?.senderId.orEmpty()
    val scrollFocusRequester = remember { FocusRequester() }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    val voicePlayer = remember { WearVoicePlayer() }
    val voiceState by voicePlayer.state.collectAsState()
    DisposableEffect(Unit) { onDispose { voicePlayer.stop() } }
    LaunchedEffect(scrollFocusRequester) {
        scrollFocusRequester.requestFocus()
    }

    Column(modifier = modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .focusRequester(scrollFocusRequester)
                .onRotaryScrollEvent { event ->
                    scope.launch {
                        scrollState.scrollBy(event.verticalScrollPixels)
                    }
                    true
                }
                .focusable()
                .verticalScroll(scrollState)
                .padding(horizontal = 8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            ListHeader {
                Text(
                    text = state.roomDisplayName,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (item != null) {
                Text(
                    text = sender,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
                )
                Text(
                    text = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
                        .format(Date(item.timestampMs)),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                MessageDetailedBody(
                    item = item,
                    mediaPreviewBytes = mediaPreviewBytes,
                    onRequestMediaPreview = onRequestMediaPreview,
                    onOpenImage = onOpenImage,
                    onPlayVoice = onResolveVoicePlaybackUri?.let { resolveVoicePlaybackUri ->
                        { voiceItem ->
                            if (voiceState == WearVoicePlayer.State.PLAYING) {
                                voicePlayer.stop()
                            } else {
                                scope.launch {
                                    runCatching {
                                        val uri = resolveVoicePlaybackUri(voiceItem)
                                        voicePlayer.play(context, uri)
                                    }.onFailure(onVoicePlaybackError)
                                }
                            }
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp),
                )
                if (item.reactions.isNotEmpty()) {
                    ReactionsRow(item = item)
                }
                if (item.hasTextForTts()) {
                    val isReadingAloud = readAloudPlaybackState == WearTextToSpeech.PlaybackState.LOADING ||
                        readAloudPlaybackState == WearTextToSpeech.PlaybackState.PLAYING
                    FilledTonalButton(
                        modifier = Modifier.fillMaxWidth(),
                        onClick = onReadAloud,
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                imageVector = if (isReadingAloud) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                                contentDescription = null,
                            )
                            Text(
                                text = stringResource(
                                    if (isReadingAloud) {
                                        R.string.screen_message_detail_pause_read_aloud
                                    } else {
                                        R.string.read_aloud
                                    },
                                ),
                                textAlign = TextAlign.Center,
                            )
                        }
                    }
                }
                Button(
                    modifier = Modifier.fillMaxWidth(),
                    onClick = onOpenOrStartThread,
                ) {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        Text(
                            text = stringResource(
                                if (item.hasThread) {
                                    R.string.screen_message_detail_open_thread
                                } else {
                                    R.string.screen_message_detail_start_thread
                                },
                            ),
                        )
                        if (item.hasThread) {
                            Text(
                                text = stringResource(R.string.thread) + " · ${item.threadReplyCount}",
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                style = MaterialTheme.typography.labelSmall,
                            )
                        }
                    }
                }
                Text(
                    text = stringResource(R.string.screen_message_detail_reactions),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                QUICK_REACTIONS.chunked(3).forEach { reactions ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceEvenly,
                    ) {
                        reactions.forEach { reactionKey ->
                            OutlinedIconButton(
                                modifier = Modifier.size(44.dp),
                                onClick = { onSendReaction(reactionKey) },
                            ) {
                                Box(
                                    modifier = Modifier.fillMaxSize(),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Text(
                                        text = reactionKey,
                                        textAlign = TextAlign.Center,
                                        style = MaterialTheme.typography.titleSmall,
                                    )
                                }
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
            contextLabel = null,
        )
    }
}

internal data class MessageDetailViewState(
    val roomDisplayName: String,
    val item: WatchTimelineItem?,
)
