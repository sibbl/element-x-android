/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.appconfig

import android.net.Uri

data class WearCompanionDeepLink(
    val roomId: String,
    val eventId: String? = null,
    val threadRootEventId: String? = null,
)

object WearCompanionConfig {
    const val ACTION_OPEN_ON_WEAR = "io.element.android.action.OPEN_ON_WEAR"

    const val EXTRA_ROOM_ID = "io.element.android.wear.roomId"
    const val EXTRA_EVENT_ID = "io.element.android.wear.eventId"
    const val EXTRA_THREAD_ROOT_EVENT_ID = "io.element.android.wear.threadRootEventId"

    const val DEEP_LINK_SCHEME = "elementxwear"
    const val DEEP_LINK_HOST = "open"
}

fun buildWearCompanionDeepLink(
    roomId: String,
    eventId: String? = null,
    threadRootEventId: String? = null,
): Uri {
    require(roomId.isNotBlank()) { "roomId cannot be blank" }

    return Uri.Builder()
        .scheme(WearCompanionConfig.DEEP_LINK_SCHEME)
        .authority(WearCompanionConfig.DEEP_LINK_HOST)
        .apply {
            when {
                threadRootEventId != null -> {
                    appendPath("thread")
                    appendPath(roomId)
                    appendPath(threadRootEventId)
                }
                eventId != null -> {
                    appendPath("message")
                    appendPath(roomId)
                    appendPath(eventId)
                }
                else -> {
                    appendPath("room")
                    appendPath(roomId)
                }
            }
        }
        .build()
}

fun parseWearCompanionDeepLink(uri: Uri?): WearCompanionDeepLink? {
    if (uri == null || uri.scheme != WearCompanionConfig.DEEP_LINK_SCHEME || uri.host != WearCompanionConfig.DEEP_LINK_HOST) {
        return null
    }

    val segments = uri.pathSegments
    return when (segments.firstOrNull()) {
        "room" -> {
            val roomId = segments.getOrNull(1)?.takeIf { it.isNotBlank() }?.let(Uri::decode)
            roomId?.let { WearCompanionDeepLink(roomId = it) }
        }
        "message" -> {
            val roomId = segments.getOrNull(1)?.takeIf { it.isNotBlank() }?.let(Uri::decode)
            val eventId = segments.getOrNull(2)?.takeIf { it.isNotBlank() }?.let(Uri::decode)
            if (roomId != null && eventId != null) {
                WearCompanionDeepLink(roomId = roomId, eventId = eventId)
            } else {
                null
            }
        }
        "thread" -> {
            val roomId = segments.getOrNull(1)?.takeIf { it.isNotBlank() }?.let(Uri::decode)
            val threadRootEventId = segments.getOrNull(2)?.takeIf { it.isNotBlank() }?.let(Uri::decode)
            if (roomId != null && threadRootEventId != null) {
                WearCompanionDeepLink(roomId = roomId, threadRootEventId = threadRootEventId)
            } else {
                null
            }
        }
        else -> null
    }
}