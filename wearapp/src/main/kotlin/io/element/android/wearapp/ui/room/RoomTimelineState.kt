/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.wearapp.ui.room

import android.graphics.Typeface
import android.text.Spanned
import android.text.style.StrikethroughSpan
import android.text.style.StyleSpan
import android.text.style.TypefaceSpan
import android.text.style.URLSpan
import android.text.style.UnderlineSpan
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.core.text.HtmlCompat
import io.element.android.watchbridge.contract.WatchRoomSummary
import io.element.android.watchbridge.contract.WatchSync
import io.element.android.watchbridge.contract.WatchTimelineItem
import io.element.android.wearapp.bridge.WearBridgeClient
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterIsInstance

private val COLLAPSIBLE_INLINE_WHITESPACE = Regex("[\\t\\x0B\\f ]+")
private val EXCESSIVE_BLANK_LINES = Regex("\\n{3,}")

internal data class RoomTimelineState(
    val summary: WatchRoomSummary? = null,
    val items: List<WatchTimelineItem> = emptyList(),
    val hasReceivedDelta: Boolean = false,
)

@Composable
internal fun rememberRoomTimelineState(
    bridge: WearBridgeClient,
    roomId: String,
    onOpenFailure: ((Throwable) -> Unit)? = null,
): RoomTimelineState {
    var summary by remember(roomId) { mutableStateOf(bridge.getCachedSummary(roomId)) }
    val cachedItems = remember(roomId) { bridge.getCachedTimeline(roomId) }
    var items by remember(roomId) { mutableStateOf(cachedItems) }
    var hasReceivedDelta by remember(roomId) {
        mutableStateOf(bridge.hasCachedTimelineSnapshot(roomId) || cachedItems.isNotEmpty())
    }

    LaunchedEffect(bridge, roomId) {
        runCatching {
            bridge.ensureRoomSubscription(roomId = roomId)
        }.onFailure { onOpenFailure?.invoke(it) }
    }

    DisposableEffect(bridge, roomId) {
        onDispose {
            bridge.unsubscribeRoom(roomId)
        }
    }

    LaunchedEffect(bridge, roomId) {
        bridge.syncEvents.filterIsInstance<WatchSync.RoomSummary>()
            .filter { update -> update.summary.roomId == roomId }
            .collect { update ->
                summary = update.summary
                bridge.cacheSummary(roomId, update.summary)
            }
    }

    LaunchedEffect(bridge, roomId) {
        bridge.syncEvents.filterIsInstance<WatchSync.TimelineDelta>()
            .filter { delta -> delta.roomId == roomId }
            .collect {
                hasReceivedDelta = true
                items = bridge.getCachedTimeline(roomId)
            }
    }

    return RoomTimelineState(
        summary = summary,
        items = items,
        hasReceivedDelta = hasReceivedDelta,
    )
}

internal fun WatchTimelineItem.displayText(): String {
    val formatted = formattedText
        ?.takeIf { it.isNotBlank() }
        ?.let { it.toPlainTimelineText() }
        ?.takeIf { it.isNotBlank() }
    return formatted
        ?: bodyText?.normalizeRawTimelineText()?.takeIf { it.isNotBlank() }
        ?: "[${kind.name.lowercase()}]"
}

internal fun WatchTimelineItem.hasTextForTts(): Boolean =
    readableByTts && (formattedText?.toPlainTimelineText()?.isNotBlank() == true || bodyText?.normalizeRawTimelineText()?.isNotBlank() == true)

internal fun WatchTimelineItem.richDisplayText(): AnnotatedString {
    val formatted = formattedText
        ?.takeIf { it.isNotBlank() }
        ?.let { it.toRichTimelineText() }
        ?.takeIf { it.text.isNotBlank() }
    return formatted
        ?: AnnotatedString(bodyText?.normalizeRawTimelineText()?.takeIf { it.isNotBlank() } ?: "[${kind.name.lowercase()}]")
}

private fun String.normalizeRawTimelineText(): String =
    replace("\r\n", "\n")
        .replace('\r', '\n')

private fun String.toPlainTimelineText(): String {
    return HtmlCompat.fromHtml(this, HtmlCompat.FROM_HTML_MODE_COMPACT)
        .toString()
        .replace("\r\n", "\n")
        .replace('\r', '\n')
        .lines()
        .joinToString("\n") { line ->
            line.replace(COLLAPSIBLE_INLINE_WHITESPACE, " ").trimEnd()
        }
        .replace(EXCESSIVE_BLANK_LINES, "\n\n")
        .trim()
}

private fun String.toRichTimelineText(): AnnotatedString {
    val spanned = HtmlCompat.fromHtml(this, HtmlCompat.FROM_HTML_MODE_COMPACT)
    val rawText = spanned.toString()
    val startOffset = rawText.indexOfFirst { !it.isWhitespace() }
    if (startOffset < 0) return AnnotatedString("")
    val endOffset = rawText.indexOfLast { !it.isWhitespace() } + 1
    val text = rawText.substring(startOffset, endOffset)
    val builder = AnnotatedString.Builder(text)

    spanned.getSpans(0, spanned.length, Any::class.java).forEach { span ->
        val start = (spanned.getSpanStart(span) - startOffset).coerceIn(0, text.length)
        val end = (spanned.getSpanEnd(span) - startOffset).coerceIn(0, text.length)
        if (start >= end) return@forEach
        span.toSpanStyle()?.let { style -> builder.addStyle(style, start, end) }
        if (span is URLSpan) {
            builder.addStringAnnotation(
                tag = "URL",
                annotation = span.url,
                start = start,
                end = end,
            )
        }
    }

    return builder.toAnnotatedString()
}

private fun Any.toSpanStyle(): SpanStyle? = when (this) {
    is StyleSpan -> when (style) {
        Typeface.BOLD -> SpanStyle(fontWeight = FontWeight.Bold)
        Typeface.ITALIC -> SpanStyle(fontStyle = FontStyle.Italic)
        Typeface.BOLD_ITALIC -> SpanStyle(fontWeight = FontWeight.Bold, fontStyle = FontStyle.Italic)
        else -> null
    }
    is TypefaceSpan -> if (family == "monospace") {
        SpanStyle(fontFamily = FontFamily.Monospace)
    } else {
        null
    }
    is URLSpan -> SpanStyle(textDecoration = TextDecoration.Underline)
    is UnderlineSpan -> SpanStyle(textDecoration = TextDecoration.Underline)
    is StrikethroughSpan -> SpanStyle(textDecoration = TextDecoration.LineThrough)
    else -> null
}

internal fun WatchTimelineItem.reactionSummaryText(): String? =
    reactions.takeIf { it.isNotEmpty() }
        ?.joinToString(separator = "  ") { "${it.key} ${it.count}" }
