/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.wearapp.ui.room

import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.wear.compose.material3.CircularProgressIndicator
import androidx.wear.compose.material3.FilledTonalButton
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.Text
import io.element.android.watchbridge.contract.WatchTimelineItem
import io.element.android.watchbridge.contract.WatchTimelineItemKind
import io.element.android.wearapp.R
import io.element.android.wearapp.bridge.isPendingWatchLocalEcho
import io.element.android.wearapp.bridge.mediaPreviewCacheKey
import io.element.android.wearapp.ui.common.PressableWearChip
import io.element.android.wearapp.ui.common.wearTapAndLongPress
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * One actionable row in a timeline list. Tapping opens the [MessageDetailScreen]; the optional
 * thread indicator opens the thread. Long-press reads the message aloud via TTS.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun TimelineMessageRow(
    item: WatchTimelineItem,
    mediaPreviewBytes: ByteArray? = null,
    onRequestMediaPreview: (() -> Unit)? = null,
    onClick: () -> Unit,
    onOpenThread: ((String) -> Unit)?,
    showSender: Boolean = true,
    onLongPress: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val bubbleColor = if (item.isOwn) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainer
    val primaryTextColor = if (item.isOwn) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface
    val secondaryTextColor = if (item.isOwn) {
        MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.82f)
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }
    val bodyOverflow = TextOverflow.Clip

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        PressableWearChip(
            onTap = onClick,
            onLongPress = onLongPress,
            backgroundColor = bubbleColor,
            modifier = Modifier
                .fillMaxWidth(),
            label = if (showSender) {
                {
                    if (item.isPendingWatchLocalEcho()) {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            CircularProgressIndicator(modifier = Modifier.size(10.dp))
                            Text(
                                text = stringResource(R.string.screen_room_sending),
                                maxLines = 1,
                                style = MaterialTheme.typography.bodySmall.copy(color = secondaryTextColor),
                            )
                        }
                    } else {
                        Text(
                            text = item.senderDisplayName ?: item.senderId,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.bodySmall.copy(
                                fontWeight = FontWeight.SemiBold,
                                color = primaryTextColor,
                            ),
                        )
                    }
                }
            } else {
                {
                    MessagePreviewBody(
                        item = item,
                        mediaPreviewBytes = mediaPreviewBytes,
                        onRequestMediaPreview = onRequestMediaPreview,
                        maxLines = Int.MAX_VALUE,
                        style = MaterialTheme.typography.bodyMedium.copy(
                            color = primaryTextColor,
                        ),
                        overflow = bodyOverflow,
                    )
                }
            },
            secondaryLabel = if (showSender) {
                {
                    MessagePreviewBody(
                        item = item,
                        mediaPreviewBytes = mediaPreviewBytes,
                        onRequestMediaPreview = onRequestMediaPreview,
                        maxLines = Int.MAX_VALUE,
                        style = MaterialTheme.typography.bodySmall.copy(
                            color = secondaryTextColor,
                        ),
                        overflow = bodyOverflow,
                    )
                }
            } else null,
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
    onRequestMediaPreview: (() -> Unit)? = null,
    maxLines: Int,
    style: androidx.compose.ui.text.TextStyle = MaterialTheme.typography.bodyMedium,
    modifier: Modifier = Modifier,
    overflow: TextOverflow = TextOverflow.Ellipsis,
) {
    when (item.kind) {
        WatchTimelineItemKind.VOICE -> Text(
            text = voiceLine(item),
            style = style,
            maxLines = maxLines,
            overflow = overflow,
            modifier = modifier,
        )
        WatchTimelineItemKind.IMAGE -> ImageMessagePreview(
            item = item,
            mediaPreviewBytes = mediaPreviewBytes,
            onRequestPreview = onRequestMediaPreview,
            captionStyle = style,
            captionMaxLines = maxLines,
            captionOverflow = overflow,
            modifier = modifier,
        )
        WatchTimelineItemKind.VIDEO -> Text(
            text = "🎬  ${item.bodyText ?: stringResource(R.string.timeline_video)}",
            style = style,
            maxLines = maxLines,
            overflow = overflow,
            modifier = modifier,
        )
        WatchTimelineItemKind.FILE -> Text(
            text = "📎  ${item.bodyText ?: stringResource(R.string.timeline_file)}",
            style = style,
            maxLines = maxLines,
            overflow = overflow,
            modifier = modifier,
        )
        WatchTimelineItemKind.REDACTED -> Text(
            text = stringResource(R.string.timeline_redacted),
            style = style.copy(fontStyle = FontStyle.Italic),
            maxLines = maxLines,
            overflow = overflow,
            modifier = modifier,
        )
        WatchTimelineItemKind.STATE,
        WatchTimelineItemKind.UNSUPPORTED -> Text(
            text = item.displayText(),
            style = style.copy(fontStyle = FontStyle.Italic),
            maxLines = maxLines,
            overflow = overflow,
            modifier = modifier,
        )
        WatchTimelineItemKind.EMOTE -> Text(
            text = "✶ ${item.senderDisplayName ?: item.senderId} ${item.displayText()}",
            style = style.copy(fontStyle = FontStyle.Italic),
            maxLines = maxLines,
            overflow = overflow,
            modifier = modifier,
        )
        WatchTimelineItemKind.NOTICE -> Text(
            text = item.displayText(),
            style = style.copy(fontStyle = FontStyle.Italic),
            maxLines = maxLines,
            overflow = overflow,
            modifier = modifier,
        )
        WatchTimelineItemKind.TEXT -> Text(
            text = item.displayText(),
            style = style,
            maxLines = maxLines,
            overflow = overflow,
            modifier = modifier,
        )
    }
}

/** Detailed body view used by the message detail screen — larger, no preview truncation. */
@Composable
internal fun MessageDetailedBody(
    item: WatchTimelineItem,
    mediaPreviewBytes: ByteArray? = null,
    onRequestMediaPreview: (() -> Unit)? = null,
    onOpenImage: (() -> Unit)? = null,
    onPlayVoice: ((WatchTimelineItem) -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    when (item.kind) {
        WatchTimelineItemKind.VOICE -> VoiceMessageDetailedView(item = item, onPlay = onPlayVoice, modifier = modifier)
        WatchTimelineItemKind.IMAGE -> ImageMessageDetailedView(
            item = item,
            mediaPreviewBytes = mediaPreviewBytes,
            onRequestPreview = onRequestMediaPreview,
            onOpenImage = onOpenImage,
            modifier = modifier,
        )
        WatchTimelineItemKind.REDACTED -> Text(
            text = stringResource(R.string.timeline_redacted),
            style = MaterialTheme.typography.bodyMedium.copy(fontStyle = FontStyle.Italic),
            modifier = modifier,
        )
        WatchTimelineItemKind.STATE,
        WatchTimelineItemKind.UNSUPPORTED -> Text(
            text = item.displayText(),
            style = MaterialTheme.typography.bodyMedium.copy(fontStyle = FontStyle.Italic),
            modifier = modifier,
            overflow = TextOverflow.Clip,
        )
        else -> Text(
            text = item.richDisplayText(),
            style = MaterialTheme.typography.bodyMedium,
            modifier = modifier,
            overflow = TextOverflow.Clip,
        )
    }
}

@Composable
private fun ImageMessagePreview(
    item: WatchTimelineItem,
    mediaPreviewBytes: ByteArray?,
    onRequestPreview: (() -> Unit)?,
    captionStyle: androidx.compose.ui.text.TextStyle,
    captionMaxLines: Int,
    captionOverflow: TextOverflow,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        MediaPreviewImage(
            cacheKey = mediaPreviewCacheKey(item.roomId, item.eventId),
            mediaPreviewBytes = mediaPreviewBytes,
            height = 72.dp,
            onRequestPreview = onRequestPreview,
        )
        Text(
            text = item.bodyText ?: stringResource(R.string.timeline_image),
            style = captionStyle,
            maxLines = captionMaxLines,
            overflow = captionOverflow,
        )
    }
}

@Composable
private fun ImageMessageDetailedView(
    item: WatchTimelineItem,
    mediaPreviewBytes: ByteArray?,
    onRequestPreview: (() -> Unit)?,
    onOpenImage: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        MediaPreviewImage(
            cacheKey = mediaPreviewCacheKey(item.roomId, item.eventId),
            mediaPreviewBytes = mediaPreviewBytes,
            height = 112.dp,
            onClick = onOpenImage,
            onRequestPreview = onRequestPreview,
        )
        if (item.bodyText != null || item.formattedText != null) {
            Text(
                text = item.richDisplayText(),
                style = MaterialTheme.typography.bodyMedium,
                overflow = TextOverflow.Clip,
            )
        } else {
            Text(
                text = stringResource(R.string.timeline_image),
                style = MaterialTheme.typography.bodyMedium,
                overflow = TextOverflow.Clip,
            )
        }
    }
}

@Composable
private fun MediaPreviewImage(
    cacheKey: String,
    mediaPreviewBytes: ByteArray?,
    height: androidx.compose.ui.unit.Dp,
    onClick: (() -> Unit)? = null,
    onRequestPreview: (() -> Unit)? = null,
) {
    val imageBitmap = rememberDecodedImageBitmap(cacheKey = cacheKey, imageBytes = mediaPreviewBytes)
    var shouldShowUnavailable by remember(mediaPreviewBytes) {
        mutableStateOf(false)
    }

    LaunchedEffect(cacheKey, mediaPreviewBytes) {
        if (mediaPreviewBytes == null) {
            onRequestPreview?.invoke()
        }
    }

    LaunchedEffect(mediaPreviewBytes) {
        if (mediaPreviewBytes != null) {
            shouldShowUnavailable = false
        } else {
            shouldShowUnavailable = false
            delay(4_000L)
            shouldShowUnavailable = true
        }
    }

    val containerModifier = Modifier
        .fillMaxWidth()
        .height(height)
        .clip(RoundedCornerShape(12.dp))
        .background(MaterialTheme.colorScheme.surfaceContainerLow)
        .then(
            if (onClick != null) {
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
        } else if (!shouldShowUnavailable) {
            CircularProgressIndicator(modifier = Modifier.size(20.dp))
        } else {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(2.dp),
                modifier = Modifier.padding(horizontal = 8.dp),
            ) {
                Text(
                    text = "🖼",
                    style = MaterialTheme.typography.titleLarge,
                )
                Text(
                    text = stringResource(R.string.screen_media_preview_unavailable),
                    style = MaterialTheme.typography.bodyExtraSmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

private object DecodedImageBitmapCache {
    private const val MAX_BYTES = 4 * 1024 * 1024
    private val cache = object : LruCache<String, ImageBitmap>(MAX_BYTES) {
        override fun sizeOf(key: String, value: ImageBitmap): Int {
            return value.width * value.height * 4
        }
    }

    fun get(key: String): ImageBitmap? = synchronized(this) { cache.get(key) }

    fun put(key: String, bitmap: ImageBitmap) {
        synchronized(this) {
            cache.put(key, bitmap)
        }
    }
}

@Composable
internal fun rememberDecodedImageBitmap(
    cacheKey: String,
    imageBytes: ByteArray?,
): ImageBitmap? {
    val decodedImage by produceState<ImageBitmap?>(
        initialValue = DecodedImageBitmapCache.get(cacheKey),
        cacheKey,
        imageBytes,
    ) {
        value = when {
            imageBytes == null -> null
            value != null -> value
            else -> withContext(Dispatchers.Default) {
                BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size)
                    ?.asImageBitmap()
                    ?.also { decoded -> DecodedImageBitmapCache.put(cacheKey, decoded) }
            }
        }
    }
    return decodedImage
}

@Composable
private fun VoiceMessageDetailedView(
    item: WatchTimelineItem,
    onPlay: ((WatchTimelineItem) -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val meta = item.voiceMessageMeta
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            text = "🎙  ${stringResource(R.string.timeline_voice_message)}",
            style = MaterialTheme.typography.titleSmall,
        )
        if (meta != null) {
            Text(
                text = formatDurationLabel(meta.durationMs),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (meta.waveform.isNotEmpty()) {
                WaveformView(waveform = meta.waveform)
            }
            if (onPlay != null) {
                FilledTonalButton(
                    modifier = Modifier.fillMaxWidth(),
                    onClick = { onPlay(item) },
                ) {
                    Text("▶  ${stringResource(R.string.play_voice_message)}")
                }
            }
        }
    }
}

@Composable
private fun WaveformView(
    waveform: List<Int>,
    modifier: Modifier = Modifier,
) {
    val color = MaterialTheme.colorScheme.primary
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
    val baseColor = MaterialTheme.colorScheme.surfaceContainerLow
    val highlightColor = MaterialTheme.colorScheme.primaryDim
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
                    style = MaterialTheme.typography.bodyExtraSmall,
                )
            }
        }
        if (item.reactions.size > 5) {
            Spacer(modifier = Modifier.width(2.dp))
            Text(
                text = "+${item.reactions.size - 5}",
                style = MaterialTheme.typography.bodyExtraSmall,
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
    Box(
        modifier = modifier.fillMaxWidth(),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(24.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerLow)
                .wearTapAndLongPress(onTap = onClick)
                .padding(horizontal = 12.dp, vertical = 8.dp)
                .semantics(mergeDescendants = true) {},
        ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                    text = pluralStringResource(R.plurals.thread_indicator_replies, replyCount, replyCount),
                    style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            lastReplyPreview?.takeIf { it.isNotBlank() }?.let { preview ->
                Text(
                    text = preview,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        }
    }
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
