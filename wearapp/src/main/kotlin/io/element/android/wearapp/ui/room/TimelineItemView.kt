/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.wearapp.ui.room

import android.graphics.BitmapFactory
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.wear.compose.material.Chip
import androidx.wear.compose.material.ChipDefaults
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Text
import io.element.android.watchbridge.contract.WatchTimelineItem
import io.element.android.watchbridge.contract.WatchTimelineItemKind
import io.element.android.wearapp.R

/**
 * One actionable row in a timeline list. Tapping opens the [MessageDetailScreen]; the optional
 * thread indicator opens the thread. Long-press reads the message aloud via TTS.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun TimelineMessageRow(
    item: WatchTimelineItem,
    mediaPreviewBytes: ByteArray? = null,
    onClick: () -> Unit,
    onOpenThread: ((String) -> Unit)?,
    showSender: Boolean = true,
    onLongPress: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Chip(
            modifier = Modifier
                .fillMaxWidth()
                .combinedClickable(
                    onClick = onClick,
                    onLongClick = onLongPress,
                    role = Role.Button,
                ),
            label = if (showSender) {
                {
                    Text(
                        text = item.senderDisplayName ?: item.senderId,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.caption1.copy(fontWeight = FontWeight.SemiBold),
                    )
                }
            } else {
                {
                    MessagePreviewBody(
                        item = item,
                        mediaPreviewBytes = mediaPreviewBytes,
                        maxLines = 3,
                        style = MaterialTheme.typography.caption2,
                    )
                }
            },
            secondaryLabel = if (showSender) {
                {
                    MessagePreviewBody(
                        item = item,
                        mediaPreviewBytes = mediaPreviewBytes,
                        maxLines = 2,
                        style = MaterialTheme.typography.caption2,
                    )
                }
            } else null,
            onClick = onClick,
            colors = if (item.isOwn) ChipDefaults.primaryChipColors() else ChipDefaults.secondaryChipColors(),
        )
        if (item.reactions.isNotEmpty()) {
            ReactionsRow(item = item)
        }
        if (item.hasThread && onOpenThread != null) {
            val threadRoot = item.threadRootEventId ?: item.eventId
            ThreadIndicatorChip(
                replyCount = item.threadReplyCount,
                lastReplyPreview = item.threadLastReplyText,
                onClick = { onOpenThread(threadRoot) },
            )
        }
    }
}

/**
 * Kind-aware body preview used in both lists and the detail screen.
 *
 * Keeps the watch UI consistent across screens while adapting style/maxLines per call-site.
 */
@Composable
internal fun MessagePreviewBody(
    item: WatchTimelineItem,
    mediaPreviewBytes: ByteArray? = null,
    maxLines: Int,
    style: androidx.compose.ui.text.TextStyle = MaterialTheme.typography.body2,
    modifier: Modifier = Modifier,
) {
    when (item.kind) {
        WatchTimelineItemKind.VOICE -> Text(
            text = voiceLine(item),
            style = style,
            maxLines = maxLines,
            overflow = TextOverflow.Ellipsis,
            modifier = modifier,
        )
        WatchTimelineItemKind.IMAGE -> ImageMessagePreview(
            item = item,
            mediaPreviewBytes = mediaPreviewBytes,
            captionStyle = style,
            captionMaxLines = maxLines,
            modifier = modifier,
        )
        WatchTimelineItemKind.VIDEO -> Text(
            text = "🎬  ${item.bodyText ?: stringResource(R.string.timeline_video)}",
            style = style,
            maxLines = maxLines,
            overflow = TextOverflow.Ellipsis,
            modifier = modifier,
        )
        WatchTimelineItemKind.FILE -> Text(
            text = "📎  ${item.bodyText ?: stringResource(R.string.timeline_file)}",
            style = style,
            maxLines = maxLines,
            overflow = TextOverflow.Ellipsis,
            modifier = modifier,
        )
        WatchTimelineItemKind.REDACTED -> Text(
            text = stringResource(R.string.timeline_redacted),
            style = style.copy(fontStyle = FontStyle.Italic),
            maxLines = maxLines,
            overflow = TextOverflow.Ellipsis,
            modifier = modifier,
        )
        WatchTimelineItemKind.STATE,
        WatchTimelineItemKind.UNSUPPORTED -> Text(
            text = item.displayText(),
            style = style.copy(fontStyle = FontStyle.Italic),
            maxLines = maxLines,
            overflow = TextOverflow.Ellipsis,
            modifier = modifier,
        )
        WatchTimelineItemKind.EMOTE -> Text(
            text = "✶ ${item.senderDisplayName ?: item.senderId} ${item.displayText()}",
            style = style.copy(fontStyle = FontStyle.Italic),
            maxLines = maxLines,
            overflow = TextOverflow.Ellipsis,
            modifier = modifier,
        )
        WatchTimelineItemKind.NOTICE -> Text(
            text = item.displayText(),
            style = style.copy(fontStyle = FontStyle.Italic),
            maxLines = maxLines,
            overflow = TextOverflow.Ellipsis,
            modifier = modifier,
        )
        WatchTimelineItemKind.TEXT -> Text(
            text = item.displayText(),
            style = style,
            maxLines = maxLines,
            overflow = TextOverflow.Ellipsis,
            modifier = modifier,
        )
    }
}

/** Detailed body view used by the message detail screen — larger, no preview truncation. */
@Composable
internal fun MessageDetailedBody(
    item: WatchTimelineItem,
    mediaPreviewBytes: ByteArray? = null,
    onOpenImage: (() -> Unit)? = null,
    onPlayVoice: ((String) -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    when (item.kind) {
        WatchTimelineItemKind.VOICE -> VoiceMessageDetailedView(item = item, onPlay = onPlayVoice, modifier = modifier)
        WatchTimelineItemKind.IMAGE -> ImageMessageDetailedView(
            item = item,
            mediaPreviewBytes = mediaPreviewBytes,
            onOpenImage = onOpenImage,
            modifier = modifier,
        )
        WatchTimelineItemKind.REDACTED -> Text(
            text = stringResource(R.string.timeline_redacted),
            style = MaterialTheme.typography.body2.copy(fontStyle = FontStyle.Italic),
            modifier = modifier,
        )
        else -> MessagePreviewBody(
            item = item,
            mediaPreviewBytes = mediaPreviewBytes,
            maxLines = 12,
            style = MaterialTheme.typography.body2,
            modifier = modifier,
        )
    }
}

@Composable
private fun ImageMessagePreview(
    item: WatchTimelineItem,
    mediaPreviewBytes: ByteArray?,
    captionStyle: androidx.compose.ui.text.TextStyle,
    captionMaxLines: Int,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        MediaPreviewImage(
            mediaPreviewBytes = mediaPreviewBytes,
            height = 72.dp,
        )
        Text(
            text = item.bodyText ?: stringResource(R.string.timeline_image),
            style = captionStyle,
            maxLines = captionMaxLines,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun ImageMessageDetailedView(
    item: WatchTimelineItem,
    mediaPreviewBytes: ByteArray?,
    onOpenImage: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        MediaPreviewImage(
            mediaPreviewBytes = mediaPreviewBytes,
            height = 112.dp,
            onClick = onOpenImage?.takeIf { mediaPreviewBytes != null },
        )
        Text(
            text = item.bodyText ?: stringResource(R.string.timeline_image),
            style = MaterialTheme.typography.body2,
            maxLines = 6,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun MediaPreviewImage(
    mediaPreviewBytes: ByteArray?,
    height: androidx.compose.ui.unit.Dp,
    onClick: (() -> Unit)? = null,
) {
    val imageBitmap = androidx.compose.runtime.remember(mediaPreviewBytes) {
        mediaPreviewBytes?.let { BitmapFactory.decodeByteArray(it, 0, it.size)?.asImageBitmap() }
    }
    val containerModifier = Modifier
        .fillMaxWidth()
        .height(height)
        .clip(RoundedCornerShape(12.dp))
        .background(MaterialTheme.colors.surface)
        .then(
            if (imageBitmap != null && onClick != null) {
                Modifier.clickable(onClick = onClick)
            } else {
                Modifier
            }
        )

    Box(
        modifier = containerModifier,
        contentAlignment = Alignment.Center,
    ) {
        if (imageBitmap != null) {
            Image(
                bitmap = imageBitmap,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(2.dp),
                modifier = Modifier.padding(horizontal = 8.dp),
            ) {
                Text(
                    text = "🖼",
                    style = MaterialTheme.typography.title3,
                )
                Text(
                    text = stringResource(R.string.screen_media_preview_unavailable),
                    style = MaterialTheme.typography.caption3,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun VoiceMessageDetailedView(
    item: WatchTimelineItem,
    onPlay: ((String) -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val meta = item.voiceMessageMeta
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            text = "🎙  ${stringResource(R.string.timeline_voice_message)}",
            style = MaterialTheme.typography.body2,
        )
        if (meta != null) {
            Text(
                text = formatDurationLabel(meta.durationMs),
                style = MaterialTheme.typography.caption2,
            )
            if (meta.waveform.isNotEmpty()) {
                WaveformView(waveform = meta.waveform)
            }
            val audioUrl = meta.audioUrl
            if (audioUrl != null && onPlay != null) {
                Chip(
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("▶  ${stringResource(R.string.play_voice_message)}") },
                    onClick = { onPlay(audioUrl) },
                    colors = ChipDefaults.secondaryChipColors(),
                )
            }
        }
    }
}

@Composable
private fun WaveformView(
    waveform: List<Int>,
    modifier: Modifier = Modifier,
) {
    val color = MaterialTheme.colors.primary
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(20.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val amps = waveform.take(40)
        amps.forEach { amp ->
            val pct = (amp.coerceIn(0, 100)) / 100f
            val barFraction = pct.coerceAtLeast(0.1f)
            Column(
                modifier = Modifier.weight(1f),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Box(
                    modifier = Modifier
                        .width(2.dp)
                        .fillMaxHeight(barFraction)
                        .clip(RoundedCornerShape(1.dp))
                        .background(color),
                )
            }
        }
    }
}

@Composable
internal fun ReactionsRow(
    item: WatchTimelineItem,
    modifier: Modifier = Modifier,
) {
    val baseColor = MaterialTheme.colors.surface
    val highlightColor = MaterialTheme.colors.primary
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        item.reactions.take(5).forEach { reaction ->
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .background(if (reaction.reactedBySelf) highlightColor.copy(alpha = 0.3f) else baseColor)
                    .padding(horizontal = 6.dp, vertical = 2.dp),
            ) {
                Text(
                    text = "${reaction.key} ${reaction.count}",
                    style = MaterialTheme.typography.caption3,
                )
            }
        }
        if (item.reactions.size > 5) {
            Spacer(modifier = Modifier.width(2.dp))
            Text(
                text = "+${item.reactions.size - 5}",
                style = MaterialTheme.typography.caption3,
            )
        }
    }
}

@Composable
internal fun ThreadIndicatorChip(
    replyCount: Int,
    lastReplyPreview: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Chip(
        modifier = modifier.fillMaxWidth(),
        label = {
            Text(
                text = stringResource(R.string.thread_indicator_replies, replyCount),
                style = MaterialTheme.typography.caption1.copy(fontWeight = FontWeight.SemiBold),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
        secondaryLabel = lastReplyPreview?.takeIf { it.isNotBlank() }?.let { preview ->
            {
                Text(
                    text = preview,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.caption2,
                )
            }
        },
        onClick = onClick,
        colors = ChipDefaults.secondaryChipColors(),
    )
}

@Composable
private fun voiceLine(item: WatchTimelineItem): String {
    val duration = item.voiceMessageMeta?.let { formatDurationLabel(it.durationMs) }
    val label = stringResource(R.string.timeline_voice_message)
    return if (duration != null) "🎙  $label · $duration" else "🎙  $label"
}

internal fun formatDurationLabel(millis: Long): String {
    val totalSeconds = (millis / 1000L).coerceAtLeast(0L)
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return "%d:%02d".format(minutes, seconds)
}
