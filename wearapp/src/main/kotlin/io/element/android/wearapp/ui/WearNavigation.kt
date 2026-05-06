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

private const val TILE_CLICKABLE_OPEN_APP_ID = "open-app"
private const val TILE_CLICKABLE_OPEN_ROOM_PREFIX = "open-room:"
private const val TILE_CLICKABLE_DIRECT_REPLY_ROOM_PREFIX = "direct-reply-room:"
private const val TILE_CLICKABLE_VOICE_RECORD_ROOM_PREFIX = "voice-record-room:"
private const val EXTRA_TILE_DIRECT_REPLY_ROOM_ID = "tile-direct-reply-room-id"

internal data class TileConversationClickable(
    val roomId: String,
    val action: WatchTileConversationAction,
)

internal fun openAppTileClickableId(): String = TILE_CLICKABLE_OPEN_APP_ID

internal fun openRoomTileClickableId(roomId: String): String = roomTileClickableId(
    roomId = roomId,
    action = WatchTileConversationAction.OPEN_CONVERSATION,
)

internal fun roomTileClickableId(
    roomId: String,
    action: WatchTileConversationAction,
): String = when (action) {
    WatchTileConversationAction.OPEN_CONVERSATION -> TILE_CLICKABLE_OPEN_ROOM_PREFIX + Uri.encode(roomId)
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
        clickableId.startsWith(TILE_CLICKABLE_DIRECT_REPLY_ROOM_PREFIX) -> {
            TILE_CLICKABLE_DIRECT_REPLY_ROOM_PREFIX to WatchTileConversationAction.DIRECT_REPLY
        }
        clickableId.startsWith(TILE_CLICKABLE_VOICE_RECORD_ROOM_PREFIX) -> {
            TILE_CLICKABLE_VOICE_RECORD_ROOM_PREFIX to WatchTileConversationAction.VOICE_RECORDING
        }
        else -> return null
    }
    val roomId = clickableId.removePrefix(prefix)
        .takeIf { it.isNotBlank() }
        ?.let(Uri::decode)
        ?: return null
    return TileConversationClickable(roomId = roomId, action = action)
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