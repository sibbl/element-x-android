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

private const val TILE_CLICKABLE_OPEN_APP_ID = "open-app"
private const val TILE_CLICKABLE_OPEN_ROOM_PREFIX = "open-room:"

internal fun openAppTileClickableId(): String = TILE_CLICKABLE_OPEN_APP_ID

internal fun openRoomTileClickableId(roomId: String): String = TILE_CLICKABLE_OPEN_ROOM_PREFIX + Uri.encode(roomId)

internal fun isOpenAppTileClickableId(clickableId: String?): Boolean = clickableId == TILE_CLICKABLE_OPEN_APP_ID

internal fun parseOpenRoomTileClickableId(clickableId: String?): String? {
    if (clickableId == null || !clickableId.startsWith(TILE_CLICKABLE_OPEN_ROOM_PREFIX)) return null
    return clickableId.removePrefix(TILE_CLICKABLE_OPEN_ROOM_PREFIX)
        .takeIf { it.isNotBlank() }
        ?.let(Uri::decode)
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