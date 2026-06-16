/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.wearapp.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import io.element.android.appconfig.WearCompanionConfig
import io.element.android.appconfig.WearCompanionDeepLink
import io.element.android.appconfig.buildWearCompanionDeepLink
import io.element.android.appconfig.parseWearCompanionDeepLink
import io.element.android.watchbridge.contract.WatchTileConversationAction
import io.element.android.wearapp.ui.voice.VoiceRecorderActivity

private const val TILE_CLICKABLE_OPEN_APP_ID = "open-app"
private const val TILE_CLICKABLE_OPEN_ROOM_PREFIX = "open-room:"
private const val TILE_CLICKABLE_READ_LATEST_ROOM_PREFIX = "read-latest-room:"
private const val TILE_CLICKABLE_QUICK_REPLY_EMOJI_PREFIX = "quick-reply-emoji-room:"
private const val TILE_CLICKABLE_DIRECT_REPLY_ROOM_PREFIX = "direct-reply-room:"
private const val TILE_CLICKABLE_VOICE_RECORD_ROOM_PREFIX = "voice-record-room:"
private const val TILE_CLICKABLE_OPEN_LATEST_PREFIX = "open-latest-message:"
private const val EXTRA_TILE_DIRECT_REPLY_ROOM_ID = "tile-direct-reply-room-id"
private const val EXTRA_TILE_READ_LATEST_ROOM_ID = "tile-read-latest-room-id"
private const val EXTRA_TILE_READ_LATEST_TEXT = "tile-read-latest-text"
internal const val EXTRA_VOICE_ROOM_ID = "roomId"
internal const val EXTRA_VOICE_ROOM_DISPLAY_NAME = "roomDisplayName"
internal const val EXTRA_VOICE_THREAD_ROOT_EVENT_ID = "threadRootEventId"
internal const val EXTRA_VOICE_IN_REPLY_TO_EVENT_ID = "inReplyToEventId"

internal data class TileConversationClickable(
    val roomId: String,
    val action: WatchTileConversationAction,
    val eventId: String? = null,
)

internal data class PendingTileReadLatest(
    val roomId: String,
    val previewText: String?,
)

internal fun openAppTileClickableId(): String = TILE_CLICKABLE_OPEN_APP_ID

internal fun openRoomTileClickableId(roomId: String): String = roomTileClickableId(
    roomId = roomId,
    action = WatchTileConversationAction.OPEN_CONVERSATION,
)

@Suppress("DEPRECATION")
internal fun roomTileClickableId(
    roomId: String,
    action: WatchTileConversationAction,
    eventId: String? = null,
): String = when (action) {
    WatchTileConversationAction.OPEN_CONVERSATION -> TILE_CLICKABLE_OPEN_ROOM_PREFIX + Uri.encode(roomId)
    WatchTileConversationAction.READ_LATEST -> TILE_CLICKABLE_READ_LATEST_ROOM_PREFIX + Uri.encode(roomId)
    WatchTileConversationAction.QUICK_REPLY_EMOJI -> TILE_CLICKABLE_QUICK_REPLY_EMOJI_PREFIX + encodeRoomAndOptionalEvent(roomId, eventId)
    WatchTileConversationAction.QUICK_REPLY_TEXT -> TILE_CLICKABLE_DIRECT_REPLY_ROOM_PREFIX + Uri.encode(roomId)
    WatchTileConversationAction.QUICK_REPLY_VOICE -> TILE_CLICKABLE_VOICE_RECORD_ROOM_PREFIX + Uri.encode(roomId)
    WatchTileConversationAction.OPEN_LATEST -> TILE_CLICKABLE_OPEN_LATEST_PREFIX + encodeRoomAndOptionalEvent(roomId, eventId)
    WatchTileConversationAction.DIRECT_REPLY -> TILE_CLICKABLE_DIRECT_REPLY_ROOM_PREFIX + Uri.encode(roomId)
    WatchTileConversationAction.VOICE_RECORDING -> TILE_CLICKABLE_VOICE_RECORD_ROOM_PREFIX + Uri.encode(roomId)
}

internal fun isOpenAppTileClickableId(clickableId: String?): Boolean = clickableId == TILE_CLICKABLE_OPEN_APP_ID

internal fun parseOpenRoomTileClickableId(clickableId: String?): String? {
    return parseRoomTileClickableId(clickableId)
        ?.takeIf { it.action == WatchTileConversationAction.OPEN_CONVERSATION }
        ?.roomId
}

internal fun parseRoomTileClickableId(clickableId: String?): TileConversationClickable? {
    val (prefix, action) = when {
        clickableId == null -> return null
        clickableId.startsWith(TILE_CLICKABLE_OPEN_ROOM_PREFIX) -> {
            TILE_CLICKABLE_OPEN_ROOM_PREFIX to WatchTileConversationAction.OPEN_CONVERSATION
        }
        clickableId.startsWith(TILE_CLICKABLE_READ_LATEST_ROOM_PREFIX) -> {
            TILE_CLICKABLE_READ_LATEST_ROOM_PREFIX to WatchTileConversationAction.READ_LATEST
        }
        clickableId.startsWith(TILE_CLICKABLE_QUICK_REPLY_EMOJI_PREFIX) -> {
            TILE_CLICKABLE_QUICK_REPLY_EMOJI_PREFIX to WatchTileConversationAction.QUICK_REPLY_EMOJI
        }
        clickableId.startsWith(TILE_CLICKABLE_DIRECT_REPLY_ROOM_PREFIX) -> {
            TILE_CLICKABLE_DIRECT_REPLY_ROOM_PREFIX to WatchTileConversationAction.QUICK_REPLY_TEXT
        }
        clickableId.startsWith(TILE_CLICKABLE_VOICE_RECORD_ROOM_PREFIX) -> {
            TILE_CLICKABLE_VOICE_RECORD_ROOM_PREFIX to WatchTileConversationAction.QUICK_REPLY_VOICE
        }
        clickableId.startsWith(TILE_CLICKABLE_OPEN_LATEST_PREFIX) -> {
            TILE_CLICKABLE_OPEN_LATEST_PREFIX to WatchTileConversationAction.OPEN_LATEST
        }
        else -> return null
    }
    val (encodedRoomId, encodedEventId) = clickableId.removePrefix(prefix)
        .takeIf { it.isNotBlank() }
        ?.split(":", limit = 2)
        ?.let { it.first() to it.getOrNull(1) }
        ?: return null
    val roomId = Uri.decode(encodedRoomId).takeIf { it.isNotBlank() } ?: return null
    val eventId = encodedEventId?.takeIf { it.isNotBlank() }?.let(Uri::decode)
    return TileConversationClickable(roomId = roomId, action = action, eventId = eventId)
}

internal fun buildWearLaunchIntent(
    context: Context,
    roomId: String? = null,
    eventId: String? = null,
    threadRootEventId: String? = null,
): Intent {
    val data = roomId?.let {
        buildWearCompanionDeepLink(
            roomId = it,
            eventId = eventId,
            threadRootEventId = threadRootEventId,
        )
    }
    return Intent(Intent.ACTION_VIEW, data, context, WearMainActivity::class.java).apply {
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
    }
}

internal fun buildWearTileDirectReplyIntent(
    context: Context,
    roomId: String,
): Intent {
    return Intent(context, WearMainActivity::class.java).apply {
        putExtra(EXTRA_TILE_DIRECT_REPLY_ROOM_ID, roomId)
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
    }
}

internal fun buildWearTileReadLatestIntent(
    context: Context,
    roomId: String,
    previewText: String?,
): Intent {
    return Intent(context, WearMainActivity::class.java).apply {
        putExtra(EXTRA_TILE_READ_LATEST_ROOM_ID, roomId)
        previewText?.takeIf { it.isNotBlank() }?.let { putExtra(EXTRA_TILE_READ_LATEST_TEXT, it) }
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
    }
}

internal fun buildVoiceRecorderIntent(
    context: Context,
    roomId: String,
    roomDisplayName: String? = null,
    threadRootEventId: String? = null,
    inReplyToEventId: String? = null,
): Intent {
    return Intent(context, VoiceRecorderActivity::class.java).apply {
        putExtra(EXTRA_VOICE_ROOM_ID, roomId)
        roomDisplayName?.let { putExtra(EXTRA_VOICE_ROOM_DISPLAY_NAME, it) }
        threadRootEventId?.let { putExtra(EXTRA_VOICE_THREAD_ROOT_EVENT_ID, it) }
        inReplyToEventId?.let { putExtra(EXTRA_VOICE_IN_REPLY_TO_EVENT_ID, it) }
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
    }
}

internal fun consumePendingDeepLink(intent: Intent?): WearCompanionDeepLink? {
    val explicitRoomId = intent?.getStringExtra(WearCompanionConfig.EXTRA_ROOM_ID)?.takeIf { it.isNotBlank() }
    val explicitEventId = intent?.getStringExtra(WearCompanionConfig.EXTRA_EVENT_ID)?.takeIf { it.isNotBlank() }
    val explicitThreadRootEventId = intent?.getStringExtra(WearCompanionConfig.EXTRA_THREAD_ROOT_EVENT_ID)?.takeIf { it.isNotBlank() }
    val deepLink = parseWearCompanionDeepLink(intent?.data)

    val resolved = when {
        explicitRoomId != null -> WearCompanionDeepLink(
            roomId = explicitRoomId,
            eventId = explicitEventId,
            threadRootEventId = explicitThreadRootEventId,
        )
        else -> deepLink
    }

    if (resolved != null) {
        intent?.removeExtra(WearCompanionConfig.EXTRA_ROOM_ID)
        intent?.removeExtra(WearCompanionConfig.EXTRA_EVENT_ID)
        intent?.removeExtra(WearCompanionConfig.EXTRA_THREAD_ROOT_EVENT_ID)
        intent?.data = null
    }

    return resolved
}

internal fun consumePendingTileDirectReplyRoomId(intent: Intent?): String? {
    val roomId = intent?.getStringExtra(EXTRA_TILE_DIRECT_REPLY_ROOM_ID)
        ?.takeIf { it.isNotBlank() }
    if (roomId != null) {
        intent.removeExtra(EXTRA_TILE_DIRECT_REPLY_ROOM_ID)
    }
    return roomId
}

internal fun consumePendingTileReadLatest(intent: Intent?): PendingTileReadLatest? {
    val roomId = intent?.getStringExtra(EXTRA_TILE_READ_LATEST_ROOM_ID)
        ?.takeIf { it.isNotBlank() }
        ?: return null
    val previewText = intent.getStringExtra(EXTRA_TILE_READ_LATEST_TEXT)
        ?.takeIf { it.isNotBlank() }
    intent.removeExtra(EXTRA_TILE_READ_LATEST_ROOM_ID)
    intent.removeExtra(EXTRA_TILE_READ_LATEST_TEXT)
    return PendingTileReadLatest(roomId = roomId, previewText = previewText)
}

private fun encodeRoomAndOptionalEvent(roomId: String, eventId: String?): String {
    return Uri.encode(roomId) + eventId?.let { ":${Uri.encode(it)}" }.orEmpty()
}
